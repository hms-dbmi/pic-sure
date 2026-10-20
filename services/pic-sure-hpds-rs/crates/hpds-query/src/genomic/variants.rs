//! Variant lists: `GenomicProcessorNodeImpl.runGetVariantList` on every node, and the union of
//! spec strings the parent processors make of the nodes' lists.
//!
//! For node `n` of group `g`, when the query has an INFO filter:
//!
//! 1. The seed `E` is the node's INFO variant set (the intersection of its INFO sets, as the
//!    cohort computes it). Spec filters never seed the list.
//! 2. `M` is the node's patient answer, spec filters and the cohort included.
//! 3. The prefilter `F` keeps the variants of `E` whose bucket has a `he`, `hen` or `ho`
//!    carrier in `M`, read from the bucket carrier blocks (Java's `BucketIndexBySample`).
//! 4. Below 100,000 variants in `F`, the node lists each variant of `F` holding a mask record
//!    whose `he`, `ho` or `hen` meets `M`; at 100,000 or more it lists all of `E`, unfiltered.
//!
//! The threshold is per node. A node whose group holds no cohort patient, or whose `M` is
//! empty, lists nothing. Lists are node variant ids; spec strings are read from the mask
//! chunks only when the lists are merged, contig by contig, in the store's spec order
//! ([`for_each_listed_spec`]), so a spec two nodes list is one entry.
//!
//! Threads: node passes run on the genomic pool. Each reads one chunk's carrier block and
//! then its mask block, each folded under its own permit on the scan path. The merge runs on
//! the calling thread and holds at most one mask chunk per node of the contig it walks.

use std::cmp::Ordering;
use std::sync::Arc;

use hpds_format::{GenoBlockKind, MaskChunk, MaskKind, PosBitset, compare_specs, java_string_cmp};
use rayon::prelude::*;
use roaring::RoaringBitmap;

use super::cohort::{CheckedFilters, NodeEvaluation, chunks_holding, evaluate_nodes};
use super::store::{GenomicStore, PLAN_CACHE_SHARE_DIVISOR, ReadPath};
use crate::error::Result;

/// `runGetVariantList`'s threshold: a node whose prefiltered variants number at least this
/// lists its whole seed, unfiltered.
pub(crate) const VARIANT_LIST_THRESHOLD: u64 = 100_000;

/// What every node lists for a query, and the node evaluation it came from.
pub(crate) struct VariantLists {
    /// Per node, the node variant ids it lists.
    pub(crate) nodes: Vec<RoaringBitmap>,
    /// The nodes' evaluation within the cohort, `None` when the query has no INFO filter or no
    /// group holds a cohort patient.
    pub(crate) evaluation: Option<NodeEvaluation>,
    /// How the chunks of the listed variants are read.
    pub(crate) path: ReadPath,
}

impl VariantLists {
    /// True when no node lists a variant.
    pub(crate) fn is_empty(&self) -> bool {
        self.nodes.iter().all(RoaringBitmap::is_empty)
    }
}

/// Every node's variant list within `cohort` (see the module docs).
///
/// # Errors
/// The refusals of the cohort evaluation ([`super::genomic_cohort`]), and
/// [`crate::QueryError::GenomicBlock`] when a block cannot be loaded.
pub(crate) fn variant_lists(
    store: &GenomicStore,
    filters: &CheckedFilters,
    cohort: &RoaringBitmap,
) -> Result<VariantLists> {
    let node_count = store.index().nodes().len();
    let empty = |evaluation| VariantLists {
        nodes: vec![RoaringBitmap::new(); node_count],
        evaluation,
        path: ReadPath::Cached,
    };
    if !filters.filters.has_info_filter() {
        return Ok(empty(None));
    }
    let Some(evaluation) = evaluate_nodes(store, filters, cohort)? else {
        return Ok(empty(None));
    };
    let work: Vec<Option<(&RoaringBitmap, &PosBitset)>> = (0..node_count)
        .map(|n| {
            let seed = evaluation.infos[n].variants.as_ref()?;
            let mask = evaluation.masks[n].as_ref()?;
            (!seed.is_empty()).then_some((seed, mask))
        })
        .collect();
    let path = pass_path(store, &work);
    let nodes: Vec<RoaringBitmap> = store
        .pool()
        .install(|| {
            work.par_iter()
                .enumerate()
                .map(|(n, w)| match w {
                    None => Ok(RoaringBitmap::new()),
                    Some((seed, mask)) => node_list(store, n as u16, seed, mask, path),
                })
                .collect::<Vec<Result<RoaringBitmap>>>()
        })
        .into_iter()
        .collect::<Result<_>>()?;
    let listed_path = listed_path(store, &nodes);
    Ok(VariantLists {
        nodes,
        evaluation: Some(evaluation),
        path: listed_path,
    })
}

/// Whether the node passes read through the cache: the carrier and mask blocks of every chunk
/// holding a seed variant, against a share of the cache.
fn pass_path(store: &GenomicStore, work: &[Option<(&RoaringBitmap, &PosBitset)>]) -> ReadPath {
    let mut planned: u64 = 0;
    for (n, w) in work.iter().enumerate() {
        let Some((seed, _)) = w else { continue };
        let node = store.node(n as u16);
        for c in chunks_holding(node, seed) {
            for kind in [GenoBlockKind::Carriers, GenoBlockKind::Mask] {
                planned = planned.saturating_add(store.chunk_decoded_len(n as u16, c, kind));
            }
        }
    }
    path_for(store, planned)
}

/// Whether reading the mask and metadata chunks of every listed variant goes through the
/// cache.
fn listed_path(store: &GenomicStore, lists: &[RoaringBitmap]) -> ReadPath {
    let mut planned: u64 = 0;
    for (n, list) in lists.iter().enumerate() {
        let node = store.node(n as u16);
        for c in chunks_holding(node, list) {
            for kind in [GenoBlockKind::Mask, GenoBlockKind::Metadata] {
                planned = planned.saturating_add(store.chunk_decoded_len(n as u16, c, kind));
            }
        }
    }
    path_for(store, planned)
}

/// The cached path when `planned` decoded bytes fit a share of the cache, else the scan.
fn path_for(store: &GenomicStore, planned: u64) -> ReadPath {
    if planned <= store.cache_capacity() / PLAN_CACHE_SHARE_DIVISOR {
        ReadPath::Cached
    } else {
        ReadPath::Scan
    }
}

/// Node `n`'s list from its seed and its answer `mask`: the prefilter count and the
/// per-variant test in one pass over the chunks holding the seed, then the threshold.
///
/// # Errors
/// [`crate::QueryError::GenomicBlock`] when a block cannot be loaded.
fn node_list(
    store: &GenomicStore,
    n: u16,
    seed: &RoaringBitmap,
    mask: &PosBitset,
    path: ReadPath,
) -> Result<RoaringBitmap> {
    let node = store.node(n);
    let mut prefiltered: u64 = 0;
    let mut meeting = RoaringBitmap::new();
    for c in chunks_holding(node, seed) {
        let first = node.chunks[c as usize].first_variant;
        let hot: Vec<bool> = store.with_carrier_chunk(n, c, path, |carriers| {
            (0..carriers.len())
                .map(|j| carriers.bucket(j).is_some_and(|b| b.intersects(mask)))
                .collect()
        })?;
        store.with_mask_chunk(n, c, path, |chunk| {
            for (j, &is_hot) in hot.iter().enumerate() {
                let Some((_, range)) = chunk.bucket(j).filter(|_| is_hot) else {
                    continue;
                };
                let ids = first + range.start as u32..first + range.end as u32;
                for id in seed.range(ids) {
                    prefiltered += 1;
                    if meets(chunk, (id - first) as usize, mask) {
                        meeting.insert(id);
                    }
                }
            }
        })?;
    }
    Ok(if prefiltered < VARIANT_LIST_THRESHOLD {
        meeting
    } else {
        seed.clone()
    })
}

/// True when chunk variant `i` holds a mask record whose `he`, `ho` or `hen` meets `mask`.
fn meets(chunk: &MaskChunk, i: usize, mask: &PosBitset) -> bool {
    chunk.has_record(i)
        && [
            MaskKind::Heterozygous,
            MaskKind::Homozygous,
            MaskKind::HeterozygousNoCall,
        ]
        .into_iter()
        .any(|kind| {
            chunk
                .mask(i, kind)
                .is_some_and(|m| m.positions.intersects(mask))
        })
}

/// One node's walk over the variants it lists on one contig, holding the mask chunk of its
/// next variant only while that variant and the one after it share the chunk.
struct Cursor {
    node: u16,
    variants: Vec<u32>,
    next: usize,
    chunk: Option<(u32, Arc<MaskChunk>)>,
    head: Option<String>,
}

impl Cursor {
    /// Moves to the next listed variant, reading its spec.
    ///
    /// # Errors
    /// [`crate::QueryError::GenomicBlock`] when its mask chunk cannot be loaded.
    fn advance(&mut self, store: &GenomicStore, path: ReadPath) -> Result<()> {
        self.head = None;
        let Some(&id) = self.variants.get(self.next) else {
            self.chunk = None;
            return Ok(());
        };
        self.next += 1;
        let node = store.node(self.node);
        let c = chunk_of(node, id);
        if self.chunk.as_ref().is_none_or(|(held, _)| *held != c) {
            self.chunk = Some((c, store.hold_mask_chunk(self.node, c, path)?));
        }
        if let Some((_, chunk)) = &self.chunk {
            self.head = Some(chunk.spec((id - chunk.first_variant()) as usize).to_owned());
        }
        let next_shares_chunk = self
            .variants
            .get(self.next)
            .is_some_and(|&next| chunk_of(node, next) == c);
        if !next_shares_chunk {
            self.chunk = None;
        }
        Ok(())
    }
}

/// The chunk of node variant `id`, which the caller knows the node holds.
fn chunk_of(node: &hpds_format::GenoNode, id: u32) -> u32 {
    node.chunk_for_variant(id).unwrap_or_default() as u32
}

/// The store's order of two spec strings: [`compare_specs`], or raw bytes for a spec without
/// a key, which no listed spec lacks.
fn spec_order(a: &str, b: &str) -> Ordering {
    compare_specs(a, b).unwrap_or_else(|| a.as_bytes().cmp(b.as_bytes()))
}

/// Calls `visit` once for each distinct spec string the nodes list, in the store's spec
/// order: contigs in Java `String.compareTo` order, and within a contig a merge of the
/// nodes' lists, which are in that order already.
///
/// # Errors
/// [`crate::QueryError::GenomicBlock`] when a mask chunk cannot be loaded, and the errors of
/// `visit`.
pub(crate) fn for_each_listed_spec(
    store: &GenomicStore,
    lists: &VariantLists,
    mut visit: impl FnMut(&str) -> Result<()>,
) -> Result<()> {
    let nodes = store.index().nodes();
    let mut contigs: Vec<&str> = nodes
        .iter()
        .enumerate()
        .filter(|(n, _)| !lists.nodes[*n].is_empty())
        .flat_map(|(_, node)| node.contigs.iter().map(String::as_str))
        .collect();
    contigs.sort_by(|a, b| java_string_cmp(a, b));
    contigs.dedup();
    for contig in contigs {
        let mut cursors = Vec::new();
        for (n, node) in nodes.iter().enumerate() {
            let list = &lists.nodes[n];
            let Some(ci) = node.contig_index(contig).filter(|_| !list.is_empty()) else {
                continue;
            };
            let mut of_contig = node.chunks.iter().filter(|c| c.contig == ci);
            let Some(first) = of_contig.next() else {
                continue;
            };
            let last = of_contig.next_back().unwrap_or(first);
            let end = last.first_variant.saturating_add(last.variant_count);
            let variants: Vec<u32> = list.range(first.first_variant..end).collect();
            if variants.is_empty() {
                continue;
            }
            let mut cursor = Cursor {
                node: n as u16,
                variants,
                next: 0,
                chunk: None,
                head: None,
            };
            cursor.advance(store, lists.path)?;
            cursors.push(cursor);
        }
        while let Some(least) = cursors
            .iter()
            .filter_map(|c| c.head.as_deref())
            .min_by(|a, b| spec_order(a, b))
            .map(str::to_owned)
        {
            visit(&least)?;
            for cursor in &mut cursors {
                if cursor.head.as_deref() == Some(least.as_str()) {
                    cursor.advance(store, lists.path)?;
                }
            }
        }
    }
    Ok(())
}
