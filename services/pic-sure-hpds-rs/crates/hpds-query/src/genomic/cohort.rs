//! Genomic cohort evaluation: `GenomicProcessorNodeImpl.runGetPatientMask` on every node, and
//! the union the mode's parent processors make of the nodes' answers.
//!
//! For node `n` of group `g`, with `S_g` the positions of the cohort's patients in the group:
//!
//! 1. INFO sets, when the query has an INFO filter: one set per categorical column (the
//!    variants holding any requested value, exactly), and one set per distinct stored value a
//!    numeric range selects through the `CompressedIndex` port. A column the node lacks adds
//!    an empty set; a numeric range selecting no stored value adds nothing. `I` is the
//!    intersection of the list, or empty when the list is.
//! 2. `M` is `S_g` without an INFO filter, else the union of `he` and `ho` over `I`, within
//!    `S_g`. A single categorical column is answered from the precomputed carriers of its
//!    values (the carrier fast path) wherever its info block is categorical; other shapes, and
//!    nodes where the column is continuous, read the mask chunks holding `I`.
//! 3. Each spec filter intersects `M` with the masks, of the zygosities asked for, of every
//!    variant of its bucket whose spec starts with the key; a node without the key's contig
//!    or bucket empties `M`.
//!
//! A group's answer is the union of its nodes' `M`, and the store's the union over groups
//! mapped to patient ids. The per-node AND makes a spec on one chromosome AND a spec on
//! another empty in the distributed layouts, as in Java.
//!
//! Every refusal is decided before any mask is read and does not depend on which nodes the
//! evaluation could skip: the column checks over the index, then a pass over every node's
//! info blocks for the requested columns (the `"_____"` rule, and the variant sets with the
//! short spec rule on nodes whose scope is not empty).
//!
//! Threads: the request's blocking thread enters the genomic pool, never a worker of the main
//! pool. Node tasks run on the genomic pool and stream: each holds the info blocks it needs
//! and one mask chunk at a time, and folds each chunk into its node's bitset before it reads
//! the next. A scan's permit is held only while one chunk is read, decoded and folded, in a
//! frame that makes no rayon call.

use std::sync::Arc;

use hpds_format::{GenoNode, InfoBlock, MaskKind, PosBitset};
use rayon::prelude::*;
use roaring::RoaringBitmap;

use super::filters::{CategoricalFilter, GenomicFilters, NumericFilter, SpecFilter};
use super::store::{GenomicStore, PLAN_CACHE_SHARE_DIVISOR, ReadPath};
use crate::error::{QueryError, Result};

/// `VariantIndexCache`'s delimiter between a column and a key.
const COLUMN_AND_KEY_DELIMITER: &str = "_____";

/// The patients of `cohort` the genomic filters keep: the union over groups of each node's
/// answer (see the module docs). Expects filters that [`GenomicFilters::has_filters`].
///
/// An empty cohort answers empty without reading a mask chunk, once every refusal has been
/// decided.
///
/// # Errors
/// [`QueryError::GenomicFilter`] for a numeric filter on a column some node holds as
/// categorical, a selected key that trips the `"_____"` delimiter, and a short spec in the
/// variant set of a node whose scope is not empty; [`QueryError::GenomicBlock`] when a block
/// cannot be loaded.
pub(crate) fn genomic_cohort(
    store: &GenomicStore,
    filters: &GenomicFilters,
    cohort: &RoaringBitmap,
) -> Result<RoaringBitmap> {
    debug_assert!(
        rayon::current_thread_index().is_none(),
        "genomic work was entered from a rayon worker"
    );
    let columns = Columns::of(store, filters)?;
    let scopes = group_scopes(store, cohort);
    let node_count = store.index().nodes().len();
    let infos: Vec<NodeInfo> = if filters.has_info_filter() {
        store
            .pool()
            .install(|| {
                (0..node_count)
                    .into_par_iter()
                    .map(|n| node_info(store, filters, &columns, &scopes, n as u16))
                    .collect::<Vec<Result<NodeInfo>>>()
            })
            .into_iter()
            .collect::<Result<_>>()?
    } else {
        (0..node_count).map(|_| NodeInfo::default()).collect()
    };
    if scopes.iter().all(Option::is_none) {
        return Ok(RoaringBitmap::new());
    }
    let path = read_path(store, filters, &scopes, &infos);
    let masks: Vec<Option<PosBitset>> = store
        .pool()
        .install(|| {
            (0..node_count)
                .into_par_iter()
                .map(|n| {
                    let node = n as u16;
                    let scope = scopes[usize::from(store.node(node).group)].as_ref();
                    match scope {
                        None => Ok(None),
                        Some(scope) => {
                            node_mask(store, filters, &columns, node, scope, &infos[n], path)
                        }
                    }
                })
                .collect::<Vec<Result<Option<PosBitset>>>>()
        })
        .into_iter()
        .collect::<Result<_>>()?;
    let mut found = RoaringBitmap::new();
    for (g, group) in store.groups().iter().enumerate() {
        let mut union: Option<PosBitset> = None;
        for (n, mask) in masks.iter().enumerate() {
            if usize::from(store.node(n as u16).group) != g {
                continue;
            }
            if let Some(mask) = mask {
                match &mut union {
                    None => union = Some(mask.clone()),
                    Some(u) => u.union_with(mask),
                }
            }
        }
        if let Some(union) = union {
            found.extend(union.iter().map(|p| group.ids_by_pos[p as usize]));
        }
    }
    Ok(found)
}

/// The column table index of every INFO filter's column, after the checks over the index.
struct Columns {
    categorical: Vec<Option<u16>>,
    numeric: Vec<Option<u16>>,
}

impl Columns {
    /// Looks the filters' columns up and refuses a numeric filter on a column some node holds
    /// as categorical, where Java's `getValuesInRange` dereferences a null index.
    fn of(store: &GenomicStore, filters: &GenomicFilters) -> Result<Self> {
        let index = store.index();
        let numeric: Vec<Option<u16>> = filters
            .numeric
            .iter()
            .map(|f| index.column_index(&f.column))
            .collect();
        for (filter, column) in filters.numeric.iter().zip(&numeric) {
            let Some(column) = *column else { continue };
            for (n, node) in index.nodes().iter().enumerate() {
                if node.info(column).is_some_and(|entry| !entry.continuous) {
                    return Err(QueryError::GenomicFilter(format!(
                        "the numeric genomic filter on {} names a column that is categorical on \
                         node {}",
                        filter.column,
                        store.node_name(n as u16)
                    )));
                }
            }
        }
        Ok(Columns {
            categorical: filters
                .categorical
                .iter()
                .map(|f| index.column_index(&f.column))
                .collect(),
            numeric,
        })
    }
}

/// Per group, the positions of the cohort's patients, or `None` when there are none.
fn group_scopes(store: &GenomicStore, cohort: &RoaringBitmap) -> Vec<Option<PosBitset>> {
    store
        .groups()
        .iter()
        .map(|group| {
            let held = cohort & group.ids();
            if held.is_empty() {
                return None;
            }
            let mut scope = PosBitset::new(group.count());
            for id in &held {
                if let Some(position) = group.position(id) {
                    scope.insert(position);
                }
            }
            Some(scope)
        })
        .collect()
}

/// What the pass over a node's info blocks learned.
#[derive(Debug, Default)]
struct NodeInfo {
    /// The variant set `I`, on a node whose scope is not empty and when the query has an INFO
    /// filter.
    variants: Option<RoaringBitmap>,
    /// For the carrier fast path, the indexes of the requested keys in the one categorical
    /// column's info block, when that block is categorical on this node.
    carrier_keys: Option<Vec<usize>>,
}

/// Whether the query's INFO filter is one categorical column and nothing else, the shape the
/// carrier fast path answers.
fn fast_path_shape(filters: &GenomicFilters) -> bool {
    filters.categorical.len() == 1 && filters.numeric.is_empty()
}

/// The pass over node `n`'s info blocks for the requested columns: the `"_____"` check of
/// every selected key and, on a node whose scope is not empty, the variant set `I` and the
/// short spec check.
///
/// # Errors
/// [`QueryError::GenomicFilter`] for a key tripping the delimiter or a short spec in `I`, and
/// [`QueryError::GenomicBlock`] when an info block cannot be loaded.
fn node_info(
    store: &GenomicStore,
    filters: &GenomicFilters,
    columns: &Columns,
    scopes: &[Option<PosBitset>],
    n: u16,
) -> Result<NodeInfo> {
    let node = store.node(n);
    let in_scope = scopes[usize::from(node.group)].is_some();
    let mut variants = Intersection::default();
    let mut carrier_keys = None;
    for (filter, column) in filters.categorical.iter().zip(&columns.categorical) {
        let Some((block, continuous)) = info_of(store, node, n, *column)? else {
            variants.push_empty();
            continue;
        };
        let keys = categorical_keys(&block, filter);
        check_delimiter(&filter.column, keys.iter().map(|&k| block.key(k)))?;
        if fast_path_shape(filters) && !continuous {
            carrier_keys = Some(keys.clone());
        }
        if in_scope && !variants.is_empty_already() {
            let mut union = RoaringBitmap::new();
            for &k in &keys {
                union |= variants_of(store, &block, n, k)?;
            }
            variants.push(union);
        }
    }
    for (filter, column) in filters.numeric.iter().zip(&columns.numeric) {
        let Some((block, _)) = info_of(store, node, n, *column)? else {
            variants.push_empty();
            continue;
        };
        let keys = numeric_keys(store, &block, n, filter)?;
        check_delimiter(&filter.column, keys.iter().map(|&k| block.key(k)))?;
        if in_scope {
            for &k in &keys {
                if variants.is_empty_already() {
                    break;
                }
                variants.push(variants_of(store, &block, n, k)?);
            }
        }
    }
    if !in_scope {
        return Ok(NodeInfo::default());
    }
    let variants = variants.finish();
    if !variants.is_empty()
        && let Some(short) = node.short_specs.iter().find(|&&v| variants.contains(v))
    {
        return Err(QueryError::GenomicFilter(format!(
            "variant {short} of node {} matches the INFO filter and its spec has fewer than six \
             fields, so Java cannot read it",
            store.node_name(n)
        )));
    }
    Ok(NodeInfo {
        variants: Some(variants),
        carrier_keys,
    })
}

/// The intersection of a list of variant sets, as Java builds it: empty when the list is.
#[derive(Default)]
struct Intersection {
    acc: Option<RoaringBitmap>,
}

impl Intersection {
    fn push(&mut self, set: RoaringBitmap) {
        self.acc = Some(match self.acc.take() {
            None => set,
            Some(acc) => acc & set,
        });
    }

    fn push_empty(&mut self) {
        self.push(RoaringBitmap::new());
    }

    /// True when a set already pushed made the intersection empty, so later sets change
    /// nothing.
    fn is_empty_already(&self) -> bool {
        self.acc.as_ref().is_some_and(RoaringBitmap::is_empty)
    }

    fn finish(self) -> RoaringBitmap {
        self.acc.unwrap_or_default()
    }
}

/// Node `n`'s info block for `column` and whether the node holds the column as continuous,
/// or `None` when the node lacks the column.
fn info_of(
    store: &GenomicStore,
    node: &GenoNode,
    n: u16,
    column: Option<u16>,
) -> Result<Option<(Arc<InfoBlock>, bool)>> {
    let Some(column) = column else {
        return Ok(None);
    };
    let Some(entry) = node.info(column) else {
        return Ok(None);
    };
    Ok(Some((store.info_block(n, column)?, entry.continuous)))
}

/// The indexes of `block`'s keys equal to one of `filter`'s values, ascending and distinct:
/// `filterInfoCategoryKeys`, whose binary search over the sorted values is an exact match.
fn categorical_keys(block: &InfoBlock, filter: &CategoricalFilter) -> Vec<usize> {
    let mut keys: Vec<usize> = filter
        .values
        .iter()
        .filter_map(|value| block.find_key(value))
        .collect();
    keys.sort_unstable();
    keys.dedup();
    keys
}

/// The indexes of the keys `getValuesInRange(Range.closed(min, max))` returns from a
/// continuous block, ascending and distinct.
fn numeric_keys(
    store: &GenomicStore,
    block: &InfoBlock,
    n: u16,
    filter: &NumericFilter,
) -> Result<Vec<usize>> {
    let index = block.compressed_index().ok_or_else(|| {
        QueryError::GenomicFilter(format!(
            "the numeric genomic filter on {} names a column that is categorical on node {}",
            filter.column,
            store.node_name(n)
        ))
    })?;
    let keys = index
        .keys_in_range(filter.min, filter.max)
        .map_err(|e| QueryError::GenomicFilter(e.to_string()))?;
    Ok(keys.into_iter().map(|k| k as usize).collect())
}

/// The variants of key `k` of `block`.
fn variants_of(store: &GenomicStore, block: &InfoBlock, n: u16, k: usize) -> Result<RoaringBitmap> {
    block.variants(k).map_err(|e| QueryError::GenomicBlock {
        node: store.node_name(n),
        what: format!("info block key {k}"),
        source: Arc::new(e),
    })
}

/// Refuses a selected key for which `VariantIndexCache.get(column, key)` would not read back
/// the column and the key: `(column + "_____" + key).split("_____")`, with Java's trailing
/// empty strings removed, must be exactly `[column, key]`.
///
/// # Errors
/// [`QueryError::GenomicFilter`] naming the column.
fn check_delimiter<'a>(column: &str, keys: impl Iterator<Item = &'a str>) -> Result<()> {
    for key in keys {
        let joined = format!("{column}{COLUMN_AND_KEY_DELIMITER}{key}");
        let mut parts: Vec<&str> = joined.split(COLUMN_AND_KEY_DELIMITER).collect();
        while parts.last().is_some_and(|p| p.is_empty()) {
            parts.pop();
        }
        if parts != [column, key] {
            return Err(QueryError::GenomicFilter(format!(
                "the genomic filter on {column} selects a value that Java cannot look up, since \
                 the column or the value holds {COLUMN_AND_KEY_DELIMITER} or the value is empty"
            )));
        }
    }
    Ok(())
}

/// The chunks of node `node` holding a variant of `variants`, ascending.
fn chunks_holding<'a>(
    node: &'a GenoNode,
    variants: &'a RoaringBitmap,
) -> impl Iterator<Item = u32> + 'a {
    node.chunks
        .iter()
        .enumerate()
        .filter_map(move |(c, chunk)| {
            let first = chunk.first_variant;
            let end = first.saturating_add(chunk.variant_count);
            (variants.range_cardinality(first..end) > 0).then_some(c as u32)
        })
}

/// The chunk of node `node` holding `spec`'s bucket, when the node has the contig and a chunk
/// covers the bucket.
fn spec_chunk(node: &GenoNode, spec: &SpecFilter) -> Option<u32> {
    let contig = node.contig_index(&spec.contig)?;
    node.chunk_for_bucket(contig, spec.bucket).map(|c| c as u32)
}

/// Whether the query reads its mask chunks through the cache or scans: the decoded bytes of
/// every chunk it may read, on nodes whose scope is not empty, against a share of the cache.
fn read_path(
    store: &GenomicStore,
    filters: &GenomicFilters,
    scopes: &[Option<PosBitset>],
    infos: &[NodeInfo],
) -> ReadPath {
    let mut planned: u64 = 0;
    for (n, node) in store.index().nodes().iter().enumerate() {
        if scopes[usize::from(node.group)].is_none() {
            continue;
        }
        let info = &infos[n];
        if info.carrier_keys.is_none()
            && let Some(variants) = &info.variants
        {
            for c in chunks_holding(node, variants) {
                planned = planned.saturating_add(store.mask_decoded_len(n as u16, c));
            }
        }
        for spec in &filters.specs {
            if let Some(c) = spec_chunk(node, spec) {
                planned = planned.saturating_add(store.mask_decoded_len(n as u16, c));
            }
        }
    }
    if planned <= store.cache_capacity() / PLAN_CACHE_SHARE_DIVISOR {
        ReadPath::Cached
    } else {
        ReadPath::Scan
    }
}

/// Node `n`'s answer `M` within `scope`, or `None` when it is empty.
///
/// # Errors
/// [`QueryError::GenomicBlock`] when a block cannot be loaded.
fn node_mask(
    store: &GenomicStore,
    filters: &GenomicFilters,
    columns: &Columns,
    n: u16,
    scope: &PosBitset,
    info: &NodeInfo,
    path: ReadPath,
) -> Result<Option<PosBitset>> {
    let mut mask = if !filters.has_info_filter() {
        scope.clone()
    } else {
        let Some(variants) = info.variants.as_ref().filter(|v| !v.is_empty()) else {
            return Ok(None);
        };
        match (
            &info.carrier_keys,
            columns.categorical.first().copied().flatten(),
        ) {
            (Some(keys), Some(column)) => carrier_mask(store, n, column, keys, scope)?,
            _ => variant_mask(store, n, variants, scope, path)?,
        }
    };
    let node = store.node(n);
    for spec in &filters.specs {
        if mask.is_empty() {
            break;
        }
        let Some(chunk) = spec_chunk(node, spec) else {
            return Ok(None);
        };
        let matched = store.with_mask_chunk(n, chunk, path, |chunk| {
            let mut matched = PosBitset::new(chunk.patient_count());
            for i in chunk.find_bucket(spec.bucket).into_iter().flatten() {
                if !chunk.spec(i).starts_with(spec.key.as_str()) {
                    continue;
                }
                let kinds = [
                    (spec.zygosities.he, MaskKind::Heterozygous),
                    (spec.zygosities.ho, MaskKind::Homozygous),
                ];
                for (wanted, kind) in kinds {
                    if let Some(m) = chunk.mask(i, kind).filter(|_| wanted) {
                        m.positions.union_into(&mut matched);
                    }
                }
            }
            matched
        })?;
        mask.intersect_with(&matched);
    }
    Ok((!mask.is_empty()).then_some(mask))
}

/// The carrier fast path: the union of the carriers of `keys` in node `n`'s categorical info
/// block for `column`, within `scope`. Exact, since Java's join is a union over the keys'
/// variants of their `he` and `ho` masks, and each key's carriers are that union for the key.
///
/// # Errors
/// [`QueryError::GenomicBlock`] when the info block cannot be loaded.
pub(crate) fn carrier_mask(
    store: &GenomicStore,
    n: u16,
    column: u16,
    keys: &[usize],
    scope: &PosBitset,
) -> Result<PosBitset> {
    let block = store.info_block(n, column)?;
    let mut mask = PosBitset::new(scope.patient_count());
    for &k in keys {
        if let Some(carriers) = block.carriers(k) {
            carriers.union_into(&mut mask);
        }
    }
    mask.intersect_with(scope);
    Ok(mask)
}

/// `PatientVariantJoinHandler`: the union of the `he` and `ho` masks of every variant of
/// `variants` holding a mask record, within `scope`, read chunk by chunk. `hen` never counts.
///
/// # Errors
/// [`QueryError::GenomicBlock`] when a mask chunk cannot be loaded.
pub(crate) fn variant_mask(
    store: &GenomicStore,
    n: u16,
    variants: &RoaringBitmap,
    scope: &PosBitset,
    path: ReadPath,
) -> Result<PosBitset> {
    let node = store.node(n);
    let mut mask = PosBitset::new(scope.patient_count());
    for c in chunks_holding(node, variants) {
        let entry = &node.chunks[c as usize];
        let first = entry.first_variant;
        let end = first.saturating_add(entry.variant_count);
        store.with_mask_chunk(n, c, path, |chunk| {
            for id in variants.range(first..end) {
                let i = (id - first) as usize;
                for kind in [MaskKind::Heterozygous, MaskKind::Homozygous] {
                    if let Some(m) = chunk.mask(i, kind) {
                        m.positions.union_into(&mut mask);
                    }
                }
            }
        })?;
    }
    mask.intersect_with(scope);
    Ok(mask)
}
