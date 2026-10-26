//! VCF_EXCERPT and AGGREGATE_VCF_EXCERPT: `VariantListV3Processor.runVcfExcerptQuery` over the
//! variant lists, written row by row.
//!
//! - **Rows.** The listed specs in `VariantSpec` order. Java collects them in a `TreeMap` keyed
//!   by `VariantSpec`, so specs whose six parsed fields are equal collapse into one row; Rust
//!   keeps the first of them in raw-byte order, with its own masks and metadata (Java keeps an
//!   unspecified one). A listed spec with fewer than six fields is refused, where Java's
//!   comparator throws.
//! - **Header.** `CHROM\tPOSITION\tREF\tALT`, one column per INFO column in `ColumnSorter`
//!   order, the two count columns, and for VCF_EXCERPT one column per subset patient in
//!   genomic patient-list order (groups in index order), headed by its label.
//! - **INFO cells.** Each metadata string of the spec, from every node holding it, split on
//!   `;` and then `=`, keeping two-part pairs; one key's values joined with `,` in Java
//!   `HashSet` order; `null` when the key is absent. Two equal strings in one node's array are
//!   refused, where Java's `Set.of` throws.
//! - **Masks.** Per group, the first node in index order whose bucket holds the spec with a
//!   mask record. One group (and the `local` and `localDistributed` layouts) passes its masks
//!   through; with two or more groups each kind merges as `VariableVariantMasks.appendMask`
//!   folds it. In `local` and `localDistributed` a spec no node holds is refused, where Java's
//!   `Optional.get` throws; the patient layouts treat it as holding no mask.
//! - **Counts**, as Java computes them from each mask's form: with `het` the `he` mask when
//!   present and else `hen`, column 1 is the size of `(het ∪ ho) ∩ subset`, plus the four
//!   padding bits of a bitmask when that union is a bitmask, over the subset's size; column 2
//!   is the raw counts of `he` and `ho` (each bitmask counting four more) less column 1's
//!   numerator, over 4 (the subset mask's four padding bits).
//! - **Genotype cells** (VCF_EXCERPT): `0/1` when `het` holds the patient, else `1/1` when
//!   `ho` does, else `0/0`.
//!
//! Streaming: either the excerpt is generated twice, first to measure it (genotype cells
//! counted, not written) so an excerpt over `HPDS_MAX_EXCERPT_BYTES` and every refusal above
//! are decided before the first byte is written, then to write it ([`VcfExcerpt::write_to`]);
//! or it is generated once into a writer that holds it, measured as it is written
//! ([`VcfExcerpt::write_measuring`]), with the same refusals decided before it returns. Each
//! pass holds the mask and metadata chunks of at most one chunk per node of the contig it
//! walks, plus one cursor chunk per node (see [`super::variants`]), and one row.

use std::io::Write;
use std::sync::Arc;

use hpds_format::{
    GenoMode, JavaHashCapacity, MaskChunk, MaskKind, MetadataChunk, PosBitset, VariantSpecKey,
    java_hash_order, java_split_spec,
};

use super::cohort::group_scopes;
use super::filters::echo;
use super::store::{GenomicStore, ReadPath};
use super::variants::{VariantLists, for_each_listed_spec};
use crate::error::{QueryError, Result};

/// Default bound on an excerpt's or a variant list's body (`HPDS_MAX_EXCERPT_BYTES`): 1 GiB.
pub const DEFAULT_MAX_EXCERPT_BYTES: u64 = 1 << 30;

/// The answer Java gives an excerpt whose variant list is empty.
pub const NO_VARIANTS_FOUND: &str = "No Variants Found\n";

/// `VariableVariantMasks.SPARSE_VARIANT_THRESHOLD`: two sparse masks appended stay sparse when
/// they hold at most this many patients together.
const SPARSE_VARIANT_THRESHOLD: u64 = 5;

/// The padding bits a Java bitmask mask carries, two at each end, which `bitCount` counts.
const BITMASK_PADDING: i64 = 4;

/// The settings of the variant result types, Java's `VariantListV3Processor` and
/// `ColumnSorter` properties.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VariantResultSettings {
    /// `VCF_EXCERPT_ENABLED`: allows VCF_EXCERPT, and with it AGGREGATE_VCF_EXCERPT and
    /// VARIANT_LIST_FOR_QUERY.
    pub vcf_excerpt_enabled: bool,
    /// `AGGREGATE_VCF_EXCERPT_ENABLED`: allows AGGREGATE_VCF_EXCERPT and
    /// VARIANT_LIST_FOR_QUERY.
    pub aggregate_vcf_excerpt_enabled: bool,
    /// `ID_CUBE_NAME`: the concept whose value heads each patient column of VCF_EXCERPT
    /// instead of the patient id; `None` for Java's `NONE`.
    pub id_cube_name: Option<String>,
    /// `variant.info_column_order`: a comma-separated list of INFO columns; when it names any,
    /// the excerpt holds exactly the listed columns that exist, in the listed order.
    pub info_column_order: String,
    /// `HPDS_MAX_EXCERPT_BYTES`: the largest excerpt or variant list body answered; a larger
    /// one is refused.
    pub max_excerpt_bytes: u64,
}

impl Default for VariantResultSettings {
    /// Java's defaults: both excerpt flags off, no id concept, no column order, and
    /// [`DEFAULT_MAX_EXCERPT_BYTES`].
    fn default() -> Self {
        VariantResultSettings {
            vcf_excerpt_enabled: false,
            aggregate_vcf_excerpt_enabled: false,
            id_cube_name: None,
            info_column_order: String::new(),
            max_excerpt_bytes: DEFAULT_MAX_EXCERPT_BYTES,
        }
    }
}

impl VariantResultSettings {
    /// Whether VARIANT_LIST_FOR_QUERY is allowed: either flag.
    pub fn variant_list_enabled(&self) -> bool {
        self.vcf_excerpt_enabled || self.aggregate_vcf_excerpt_enabled
    }

    /// Whether the excerpt of `include_patients` (VCF_EXCERPT when true) is allowed.
    pub fn excerpt_enabled(&self, include_patients: bool) -> bool {
        if include_patients {
            self.vcf_excerpt_enabled
        } else {
            self.variant_list_enabled()
        }
    }
}

/// The text Java answers for a disabled variant result type.
pub(crate) fn not_allowed(what: &str) -> String {
    format!("{what} query type not allowed")
}

/// The excerpt's INFO columns in `ColumnSorter.sortInfoColumns` order over the store's
/// columns: with `order` naming columns, the named columns that exist in the named order (a
/// name listed twice takes its last place); otherwise Java's `HashSet` order of the columns,
/// as `getInfoStoreColumns` builds the set in the store's layout.
pub(crate) fn excerpt_columns(store: &GenomicStore, order: &str) -> Vec<String> {
    let columns = store.index().columns();
    let mut places: Vec<(&str, usize)> = Vec::new();
    if !order.is_empty() {
        for (i, name) in java_split(order, ',').into_iter().enumerate() {
            match places.iter_mut().find(|(n, _)| *n == name) {
                Some(place) => place.1 = i,
                None => places.push((name, i)),
            }
        }
    }
    if places.is_empty() {
        let names = columns.iter().map(String::as_str);
        let order: Vec<&str> = if store.mode() == GenoMode::Local {
            let keys: Vec<&str> = java_hash_order(names, JavaHashCapacity::Default)
                .into_iter()
                .map(|(_, s)| s)
                .collect();
            let n = keys.len();
            java_hash_order(keys, JavaHashCapacity::FromCollection(n))
                .into_iter()
                .map(|(_, s)| s)
                .collect()
        } else {
            java_hash_order(names, JavaHashCapacity::Default)
                .into_iter()
                .map(|(_, s)| s)
                .collect()
        };
        return order.into_iter().map(str::to_owned).collect();
    }
    let mut kept: Vec<(usize, &String)> = columns
        .iter()
        .filter_map(|c| places.iter().find(|(n, _)| n == c).map(|&(_, i)| (i, c)))
        .collect();
    kept.sort_by_key(|&(i, _)| i);
    kept.into_iter().map(|(_, c)| c.clone()).collect()
}

/// Java's `String.split` with a one-character literal pattern: the string itself when the
/// character is absent, otherwise the parts with trailing empty strings removed.
pub(crate) fn java_split(s: &str, separator: char) -> Vec<&str> {
    if !s.contains(separator) {
        return vec![s];
    }
    let mut parts: Vec<&str> = s.split(separator).collect();
    while parts.last() == Some(&"") {
        parts.pop();
    }
    parts
}

/// An excerpt ready to write: its variant lists, header and patient columns, measured.
pub struct VcfExcerpt<'e> {
    store: &'e GenomicStore,
    lists: VariantLists,
    include_patients: bool,
    columns: Vec<String>,
    labels: Vec<String>,
    subset: Vec<Option<PosBitset>>,
    subset_count: u64,
    len: u64,
    max_bytes: u64,
}

impl std::fmt::Debug for VcfExcerpt<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("VcfExcerpt")
            .field("include_patients", &self.include_patients)
            .field("columns", &self.columns)
            .field("subset_count", &self.subset_count)
            .field("len", &self.len)
            .finish_non_exhaustive()
    }
}

/// What an excerpt request answers: Java's text for a disabled type or an empty variant
/// list, or the excerpt.
#[derive(Debug)]
pub enum ExcerptAnswer<'e> {
    /// A `text/plain` body written whole.
    Text(String),
    /// The excerpt, `text/plain`, written row by row with [`VcfExcerpt::write_to`], or once
    /// with [`VcfExcerpt::write_measuring`] when it came unmeasured.
    Rows(VcfExcerpt<'e>),
}

/// The ids of the patients the excerpt's columns hold, in column order: groups in index
/// order, positions ascending.
pub(crate) fn subset_column_ids(store: &GenomicStore, subset: &roaring::RoaringBitmap) -> Vec<u32> {
    group_scopes(store, subset)
        .iter()
        .zip(store.groups())
        .flat_map(|(scope, group)| {
            scope
                .iter()
                .flat_map(|s| s.iter())
                .map(|p| group.ids_by_pos[p as usize])
                .collect::<Vec<u32>>()
        })
        .collect()
}

impl<'e> VcfExcerpt<'e> {
    /// The excerpt of `lists` for the subset `subset` (the cohort the query's genomic filters
    /// keep), with `columns` and, for VCF_EXCERPT, one patient column per subset patient
    /// headed by `labels` in [`subset_column_ids`] order. Measures it, refusing it when it
    /// would exceed `max_bytes`.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] for an excerpt over `max_bytes`,
    /// [`QueryError::GenomicFilter`] where Java throws while building it (a listed spec with
    /// fewer than six fields, duplicate metadata strings, a spec no node holds in `local` and
    /// `localDistributed`), and [`QueryError::GenomicBlock`] when a block cannot be loaded.
    pub(crate) fn new(
        store: &'e GenomicStore,
        lists: VariantLists,
        subset: &roaring::RoaringBitmap,
        include_patients: bool,
        columns: Vec<String>,
        labels: Vec<String>,
        max_bytes: u64,
    ) -> Result<Self> {
        let mut excerpt = Self::unmeasured(
            store,
            lists,
            subset,
            include_patients,
            columns,
            labels,
            max_bytes,
        );
        excerpt.len = excerpt.generate(false, &mut |_| Ok(false))?;
        excerpt.refuse_over_limit()?;
        Ok(excerpt)
    }

    /// The excerpt [`VcfExcerpt::new`] builds, not yet measured: its length is 0 until
    /// [`VcfExcerpt::write_measuring`] writes it, and nothing has been refused yet.
    pub(crate) fn unmeasured(
        store: &'e GenomicStore,
        lists: VariantLists,
        subset: &roaring::RoaringBitmap,
        include_patients: bool,
        columns: Vec<String>,
        labels: Vec<String>,
        max_bytes: u64,
    ) -> Self {
        VcfExcerpt {
            store,
            lists,
            include_patients,
            columns,
            labels,
            subset: group_scopes(store, subset),
            subset_count: subset.len(),
            len: 0,
            max_bytes,
        }
    }

    /// Refuses the excerpt when its measured length is over its byte bound.
    fn refuse_over_limit(&self) -> Result<()> {
        if self.len > self.max_bytes {
            return Err(QueryError::LimitExceeded(format!(
                "the excerpt would be {} bytes, more than the {} \
                 HPDS_MAX_EXCERPT_BYTES allows",
                self.len, self.max_bytes
            )));
        }
        Ok(())
    }

    /// The body's length in bytes; 0 for an unmeasured excerpt not yet written.
    pub fn len(&self) -> u64 {
        self.len
    }

    /// True when the body is empty, which an excerpt never is: it has a header.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Writes the body to `out`, a row at a time.
    ///
    /// # Errors
    /// [`QueryError::Output`] when `out` fails, and [`QueryError::GenomicBlock`] when a block
    /// cannot be loaded.
    pub fn write_to(&self, out: &mut dyn Write) -> Result<()> {
        let written = self.generate(true, &mut |bytes| {
            out.write_all(bytes)
                .map_err(|e| QueryError::Output(Arc::new(e)))?;
            Ok(true)
        })?;
        debug_assert_eq!(
            written, self.len,
            "the measured and written excerpts differ"
        );
        Ok(())
    }

    /// Generates the excerpt once, writing the header and every row to `out` while the body
    /// stays within its byte bound, and records its length. Past the bound nothing more is
    /// written and the rest is only measured, so the refusal names the whole length, as the
    /// measure pass of [`VcfExcerpt::new`] does. Every refusal of the measure pass is decided
    /// before this returns, and none of them leaves a partial body that could be mistaken for
    /// the excerpt: on any error `out` holds a prefix to discard.
    ///
    /// # Errors
    /// As for [`VcfExcerpt::new`], and [`QueryError::Output`] when `out` fails.
    pub fn write_measuring(&mut self, out: &mut dyn Write) -> Result<u64> {
        let bound = self.max_bytes;
        let mut written: u64 = 0;
        self.len = self.generate(true, &mut |bytes| {
            let next = written + bytes.len() as u64;
            if next > bound {
                return Ok(false);
            }
            out.write_all(bytes)
                .map_err(|e| QueryError::Output(Arc::new(e)))?;
            written = next;
            Ok(true)
        })?;
        self.refuse_over_limit()?;
        Ok(self.len)
    }

    /// Generates the header and every row, handing each to `emit`, and returns the body's
    /// length. Without `genotypes` the genotype cells are counted but not generated, and
    /// nothing is handed on. Once `emit` answers false the rest is generated that way.
    fn generate(
        &self,
        genotypes: bool,
        emit: &mut dyn FnMut(&[u8]) -> Result<bool>,
    ) -> Result<u64> {
        let genotype_bytes = if self.include_patients {
            4 * self.subset_count
        } else {
            0
        };
        let mut total: u64 = 0;
        let mut row: Vec<u8> = Vec::with_capacity(4096);
        row.extend_from_slice(b"CHROM\tPOSITION\tREF\tALT");
        for column in &self.columns {
            row.push(b'\t');
            row.extend_from_slice(column.as_bytes());
        }
        row.extend_from_slice(
            b"\tPatients with this variant in subset\tPatients with this variant NOT in subset",
        );
        if self.include_patients {
            for label in &self.labels {
                row.push(b'\t');
                row.extend_from_slice(label.as_bytes());
            }
        }
        row.push(b'\n');
        total += row.len() as u64;
        let mut writing = genotypes && emit(&row)?;
        let mut rows = Rows {
            excerpt: self,
            holders: (0..self.store.index().nodes().len())
                .map(|_| None)
                .collect(),
            contig: None,
            contig_nodes: Vec::new(),
            scratch: self
                .store
                .groups()
                .iter()
                .map(|g| PosBitset::new(g.count()))
                .collect(),
            genotypes,
        };
        let mut group: Vec<ListedSpec> = Vec::new();
        let mut flush = |group: &mut Vec<ListedSpec>, total: &mut u64| -> Result<()> {
            if group.is_empty() {
                return Ok(());
            }
            row.clear();
            rows.genotypes = writing;
            rows.row(group, &mut row)?;
            *total += row.len() as u64;
            if writing {
                writing = emit(&row)?;
            } else {
                *total += genotype_bytes;
            }
            group.clear();
            Ok(())
        };
        for_each_listed_spec(self.store, &self.lists, |spec, at| {
            let key = VariantSpecKey::parse(spec);
            if key.is_none_or(|k| k.is_short()) {
                tracing::warn!(
                    variant = %echo(spec),
                    "a listed variant spec has fewer than six fields"
                );
                return Err(QueryError::GenomicFilter(
                    "a listed variant has a spec with fewer than six fields, so Java cannot \
                     sort the excerpt's rows"
                        .into(),
                ));
            }
            let same_row = group
                .first()
                .is_some_and(|first| VariantSpecKey::parse(&first.spec) == key);
            if !same_row {
                flush(&mut group, &mut total)?;
            }
            group.push(ListedSpec {
                spec: spec.to_owned(),
                at: at
                    .iter()
                    .map(|a| (a.node, a.chunk, Arc::clone(a.masks)))
                    .collect(),
            });
            Ok(())
        })?;
        flush(&mut group, &mut total)?;
        Ok(total)
    }
}

/// A listed spec of a row, with the mask chunk of every node that listed it, as the merge
/// read it.
struct ListedSpec {
    spec: String,
    at: Vec<(u16, u32, Arc<MaskChunk>)>,
}

/// The chunk of one node an excerpt is reading: its mask chunk and, once needed, its metadata.
struct Holder {
    chunk: u32,
    masks: Arc<MaskChunk>,
    metadata: Option<Arc<MetadataChunk>>,
}

/// One kind of mask of a spec in one group, or merged over groups.
#[derive(Clone)]
struct KindMask {
    bitmask_form: bool,
    count: u64,
    in_subset: u64,
    patients: Option<PosBitset>,
}

/// A group's masks of a spec, the kinds the excerpt reads.
#[derive(Default, Clone)]
struct GroupMasks {
    he: Option<KindMask>,
    ho: Option<KindMask>,
    hen: Option<KindMask>,
    he_or_ho_in_subset: u64,
    hen_or_ho_in_subset: u64,
}

/// The row generator's state: the chunk each node is being read at, the nodes holding the
/// row's contig with its index there, and scratch bitsets.
struct Rows<'x, 'e> {
    excerpt: &'x VcfExcerpt<'e>,
    holders: Vec<Option<Holder>>,
    contig: Option<String>,
    contig_nodes: Vec<(usize, u16)>,
    scratch: Vec<PosBitset>,
    genotypes: bool,
}

impl Rows<'_, '_> {
    fn store(&self) -> &GenomicStore {
        self.excerpt.store
    }

    fn path(&self) -> ReadPath {
        self.excerpt.lists.path
    }

    /// Appends the row of `group`, the listed specs of one `VariantSpec` key in raw-byte
    /// order, to `out`.
    fn row(&mut self, group: &[ListedSpec], out: &mut Vec<u8>) -> Result<()> {
        let spec = group[0].spec.as_str();
        let (_, fields) = java_split_spec(spec);
        if self.contig.as_deref() != Some(fields[0]) {
            self.holders.iter_mut().for_each(|h| *h = None);
            let store = self.excerpt.store;
            self.contig_nodes = (0..self.holders.len())
                .filter_map(|n| {
                    let ci = store.node(n as u16).contig_index(fields[0])?;
                    Some((n, ci))
                })
                .collect();
            self.contig = Some(fields[0].to_owned());
        }
        for listed in group {
            for (node, chunk, masks) in &listed.at {
                let n = usize::from(*node);
                if self.holders[n].as_ref().is_none_or(|h| h.chunk != *chunk) {
                    self.holders[n] = Some(Holder {
                        chunk: *chunk,
                        masks: Arc::clone(masks),
                        metadata: None,
                    });
                }
            }
        }
        let mut metadata = Vec::new();
        for (i, listed) in group.iter().enumerate() {
            let strings = self.metadata_of(&listed.spec)?;
            if i == 0 {
                metadata = strings;
            }
        }
        let masks = self.masks_of(spec)?;
        let (count_in, count_out) = counts(&masks);
        let (field_count, _) = java_split_spec(spec);
        for (i, field) in fields.iter().take(4).enumerate() {
            if i > 0 {
                out.push(b'\t');
            }
            if i < field_count {
                out.extend_from_slice(field.as_bytes());
            }
        }
        write_info_cells(&self.excerpt.columns, &metadata, out);
        out.extend_from_slice(
            format!(
                "\t{count_in}/{}\t{count_out}/{BITMASK_PADDING}",
                self.excerpt.subset_count
            )
            .as_bytes(),
        );
        if self.genotypes && self.excerpt.include_patients {
            self.write_genotypes(&masks, out);
        }
        out.push(b'\n');
        Ok(())
    }

    /// Holds node `n`'s chunk `chunk`, reading its mask chunk when another is held.
    fn hold(&mut self, n: usize, chunk: u32) -> Result<&mut Holder> {
        if self.holders[n].as_ref().is_none_or(|h| h.chunk != chunk) {
            let masks = self.store().hold_mask_chunk(n as u16, chunk, self.path())?;
            self.holders[n] = Some(Holder {
                chunk,
                masks,
                metadata: None,
            });
        }
        Ok(self.holders[n].as_mut().expect("held above"))
    }

    /// The nodes whose bucket of `spec` holds it, in index order, with the chunk variant:
    /// only the nodes holding the row's contig are looked at.
    fn holders_of(&mut self, spec: &str) -> Result<Vec<(usize, usize)>> {
        let Some(key) = VariantSpecKey::parse(spec) else {
            return Ok(Vec::new());
        };
        let mut found = Vec::new();
        for k in 0..self.contig_nodes.len() {
            let (n, ci) = self.contig_nodes[k];
            let Some(chunk) = self
                .store()
                .node(n as u16)
                .chunk_for_bucket(ci, key.bucket())
            else {
                continue;
            };
            let holder = self.hold(n, chunk as u32)?;
            if let Some(i) = holder.masks.find_spec(spec) {
                found.push((n, i));
            }
        }
        Ok(found)
    }

    /// The metadata strings of `spec` from every node holding it, each once, in node order:
    /// `getVariantMetadata`'s union.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] when one node's array holds a string twice, and
    /// [`QueryError::GenomicBlock`] when a block cannot be loaded.
    fn metadata_of(&mut self, spec: &str) -> Result<Vec<String>> {
        let mut strings: Vec<String> = Vec::new();
        for (n, i) in self.holders_of(spec)? {
            let path = self.path();
            let store = self.excerpt.store;
            let holder = self.holders[n].as_mut().expect("held by holders_of");
            if holder.metadata.is_none() {
                holder.metadata = Some(store.hold_metadata_chunk(n as u16, holder.chunk, path)?);
            }
            let metadata = holder.metadata.as_ref().expect("read above");
            let own: Vec<&str> = metadata.strings(i).collect();
            for (k, s) in own.iter().enumerate() {
                if own[..k].contains(s) {
                    tracing::warn!(
                        variant = %echo(spec),
                        node = %store.node_name(n as u16),
                        "a listed variant holds a metadata string twice on one node"
                    );
                    return Err(QueryError::GenomicFilter(
                        "a listed variant holds a metadata string twice on one node, which \
                         Java cannot read"
                            .into(),
                    ));
                }
                if !strings.iter().any(|x| x == s) {
                    strings.push((*s).to_owned());
                }
            }
        }
        Ok(strings)
    }

    /// The masks of `spec` as Java's `getMasks` merges them over the store's groups.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] for a spec no node holds in `local` and
    /// `localDistributed`, and [`QueryError::GenomicBlock`] when a block cannot be loaded.
    fn masks_of(&mut self, spec: &str) -> Result<GroupMasks> {
        let store = self.excerpt.store;
        let groups = store.groups().len();
        let mut per_group: Vec<Option<GroupMasks>> = vec![None; groups];
        for (n, i) in self.holders_of(spec)? {
            let g = usize::from(store.node(n as u16).group);
            if per_group[g].is_some() {
                continue;
            }
            let holder = self.holders[n].as_ref().expect("held by holders_of");
            if !holder.masks.has_record(i) {
                continue;
            }
            let masks = Arc::clone(&holder.masks);
            per_group[g] = Some(self.group_masks(g, &masks, i));
        }
        let single =
            matches!(store.mode(), GenoMode::Local | GenoMode::LocalDistributed) || groups == 1;
        if single {
            return match per_group.pop().flatten() {
                Some(masks) => Ok(masks),
                None if matches!(store.mode(), GenoMode::Local | GenoMode::LocalDistributed) => {
                    tracing::warn!(
                        variant = %echo(spec),
                        "no node holds the masks of a listed variant"
                    );
                    Err(QueryError::GenomicFilter(
                        "no node holds the masks of a listed variant, which Java cannot read"
                            .into(),
                    ))
                }
                None => Ok(GroupMasks::default()),
            };
        }
        let merge = |kind: fn(&GroupMasks) -> &Option<KindMask>| -> Option<KindMask> {
            let parts: Vec<&KindMask> = per_group
                .iter()
                .filter_map(|g| g.as_ref().and_then(|g| kind(g).as_ref()))
                .collect();
            let present = parts.iter().any(|p| p.bitmask_form || p.count > 0);
            if !present {
                return None;
            }
            let count: u64 = parts.iter().map(|p| p.count).sum();
            Some(KindMask {
                bitmask_form: parts.iter().any(|p| p.bitmask_form)
                    || count > SPARSE_VARIANT_THRESHOLD,
                count,
                in_subset: parts.iter().map(|p| p.in_subset).sum(),
                patients: None,
            })
        };
        let mut merged = GroupMasks {
            he: merge(|g| &g.he),
            ho: merge(|g| &g.ho),
            hen: merge(|g| &g.hen),
            he_or_ho_in_subset: per_group
                .iter()
                .flatten()
                .map(|g| g.he_or_ho_in_subset)
                .sum(),
            hen_or_ho_in_subset: per_group
                .iter()
                .flatten()
                .map(|g| g.hen_or_ho_in_subset)
                .sum(),
        };
        if self.genotypes && self.excerpt.include_patients {
            merged.he = merged
                .he
                .map(|m| self.merged_patients(m, &per_group, |g| &g.he));
            merged.ho = merged
                .ho
                .map(|m| self.merged_patients(m, &per_group, |g| &g.ho));
            merged.hen = merged
                .hen
                .map(|m| self.merged_patients(m, &per_group, |g| &g.hen));
        }
        Ok(merged)
    }

    /// `merged` with the subset patients of every group's mask of `kind`, as one bitset over
    /// the groups' positions laid end to end in group order.
    fn merged_patients(
        &self,
        mut merged: KindMask,
        per_group: &[Option<GroupMasks>],
        kind: fn(&GroupMasks) -> &Option<KindMask>,
    ) -> KindMask {
        let groups = self.store().groups();
        let total: u32 = groups.iter().map(|g| g.count()).sum();
        let mut all = PosBitset::new(total);
        let mut offset = 0;
        for (g, group) in groups.iter().enumerate() {
            if let Some(Some(KindMask {
                patients: Some(patients),
                ..
            })) = per_group[g].as_ref().map(kind)
            {
                for p in patients.iter() {
                    all.insert(offset + p);
                }
            }
            offset += group.count();
        }
        merged.patients = Some(all);
        merged
    }

    /// Group `g`'s masks of chunk variant `i` of `chunk`.
    fn group_masks(&mut self, g: usize, chunk: &MaskChunk, i: usize) -> GroupMasks {
        let subset = self.excerpt.subset[g].as_ref();
        let keep_patients = self.genotypes && self.excerpt.include_patients;
        let scratch = &mut self.scratch[g];
        let mut kind_mask = |kind: MaskKind| -> Option<KindMask> {
            let mask = chunk.mask(i, kind)?;
            let (in_subset, patients) = match subset {
                None => (0, None),
                Some(subset) => {
                    mask.positions.decode_into(scratch);
                    scratch.intersect_with(subset);
                    (
                        scratch.count() as u64,
                        keep_patients.then(|| scratch.clone()),
                    )
                }
            };
            Some(KindMask {
                bitmask_form: mask.bitmask_form,
                count: mask.positions.len() as u64,
                in_subset,
                patients,
            })
        };
        let he = kind_mask(MaskKind::Heterozygous);
        let ho = kind_mask(MaskKind::Homozygous);
        let hen = kind_mask(MaskKind::HeterozygousNoCall);
        let union_in_subset = |a: MaskKind, b: MaskKind, scratch: &mut PosBitset| -> u64 {
            let Some(subset) = subset else { return 0 };
            scratch.clear();
            for kind in [a, b] {
                if let Some(m) = chunk.mask(i, kind) {
                    m.positions.union_into(scratch);
                }
            }
            scratch.intersect_with(subset);
            scratch.count() as u64
        };
        let scratch = &mut self.scratch[g];
        GroupMasks {
            he,
            ho,
            hen,
            he_or_ho_in_subset: union_in_subset(
                MaskKind::Heterozygous,
                MaskKind::Homozygous,
                scratch,
            ),
            hen_or_ho_in_subset: union_in_subset(
                MaskKind::HeterozygousNoCall,
                MaskKind::Homozygous,
                scratch,
            ),
        }
    }

    /// Appends one genotype cell per subset patient, in column order.
    fn write_genotypes(&self, masks: &GroupMasks, out: &mut Vec<u8>) {
        let het = masks.he.as_ref().or(masks.hen.as_ref());
        let holds = |m: Option<&KindMask>, p: u32| {
            m.and_then(|m| m.patients.as_ref())
                .is_some_and(|b| b.contains(p))
        };
        let multi = !(matches!(
            self.store().mode(),
            GenoMode::Local | GenoMode::LocalDistributed
        ) || self.store().groups().len() == 1);
        let mut offset = 0;
        for (g, group) in self.store().groups().iter().enumerate() {
            if let Some(subset) = &self.excerpt.subset[g] {
                for p in subset.iter() {
                    let at = if multi { offset + p } else { p };
                    let cell: &[u8] = if holds(het, at) {
                        b"\t0/1"
                    } else if holds(masks.ho.as_ref(), at) {
                        b"\t1/1"
                    } else {
                        b"\t0/0"
                    };
                    out.extend_from_slice(cell);
                }
            }
            offset += group.count();
        }
    }
}

/// The two count columns' numerators, as Java computes them from the masks' forms.
fn counts(masks: &GroupMasks) -> (i64, i64) {
    let het = masks.he.as_ref().or(masks.hen.as_ref());
    let padding = |m: &KindMask| if m.bitmask_form { BITMASK_PADDING } else { 0 };
    let inside = match (het, masks.ho.as_ref()) {
        (None, None) => 0,
        (Some(het), Some(ho)) => {
            let union = if masks.he.is_some() {
                masks.he_or_ho_in_subset
            } else {
                masks.hen_or_ho_in_subset
            };
            let form = if het.bitmask_form || ho.bitmask_form {
                BITMASK_PADDING
            } else {
                0
            };
            union as i64 + form
        }
        (Some(only), None) | (None, Some(only)) => only.in_subset as i64 + padding(only),
    };
    let raw: i64 = [masks.he.as_ref(), masks.ho.as_ref()]
        .into_iter()
        .flatten()
        .map(|m| m.count as i64 + padding(m))
        .sum();
    (inside, raw - inside)
}

/// Appends one INFO cell per column: the values of the column's key in `metadata`, joined
/// with `,` in Java `HashSet` order, or `null`.
fn write_info_cells(columns: &[String], metadata: &[String], out: &mut Vec<u8>) {
    let mut pairs: Vec<(&str, &str)> = Vec::new();
    for string in metadata {
        for entry in java_split(string, ';') {
            if let [key, value] = java_split(entry, '=')[..] {
                pairs.push((key, value));
            }
        }
    }
    for column in columns {
        out.push(b'\t');
        let values: Vec<&str> = pairs
            .iter()
            .filter(|(k, _)| k == column)
            .map(|&(_, v)| v)
            .collect();
        match values.len() {
            0 => out.extend_from_slice(b"null"),
            1 => out.extend_from_slice(values[0].as_bytes()),
            _ => {
                for (k, (_, value)) in java_hash_order(values, JavaHashCapacity::Default)
                    .into_iter()
                    .enumerate()
                {
                    if k > 0 {
                        out.push(b',');
                    }
                    out.extend_from_slice(value.as_bytes());
                }
            }
        }
    }
}

/// `Arrays.binarySearch` over `keys`, sorted ascending: the index of an element equal to
/// `key` where Java's search lands on one, so with repeated keys the same one Java finds.
pub(crate) fn java_binary_search(keys: &[u32], key: u32) -> Option<usize> {
    let (mut low, mut high) = (0usize, keys.len());
    while low < high {
        let mid = (low + high - 1) / 2;
        match keys[mid].cmp(&key) {
            std::cmp::Ordering::Less => low = mid + 1,
            std::cmp::Ordering::Greater => high = mid,
            std::cmp::Ordering::Equal => return Some(mid),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_binary_search_lands_where_javas_does() {
        let keys = [1, 2, 2, 2, 2, 2, 2, 3];
        assert_eq!(java_binary_search(&keys, 2), Some(3));
        assert_eq!(java_binary_search(&keys, 1), Some(0));
        assert_eq!(java_binary_search(&keys, 3), Some(7));
        assert_eq!(java_binary_search(&keys, 4), None);
        assert_eq!(java_binary_search(&[], 4), None);
        assert_eq!(java_binary_search(&[5, 5], 5), Some(0));
    }

    #[test]
    fn java_split_drops_trailing_empty_strings_and_keeps_a_string_without_the_separator() {
        assert_eq!(java_split("a=b", '='), ["a", "b"]);
        assert_eq!(java_split("a=", '='), ["a"]);
        assert_eq!(java_split("=b", '='), ["", "b"]);
        assert_eq!(java_split("=", '='), Vec::<&str>::new());
        assert_eq!(java_split("", '='), [""]);
        assert_eq!(java_split("a=b=c", '='), ["a", "b", "c"]);
        assert_eq!(java_split("a,,b,,", ','), ["a", "", "b"]);
    }

    #[test]
    fn info_cells_keep_two_part_pairs_and_join_a_keys_values_in_hash_order() {
        let mut out = Vec::new();
        write_info_cells(
            &["A".into(), "B".into(), "C".into(), "".into()],
            &["A=1;B=x=y;FLAG;A=2;C=;=e".into(), "A=1;A=3".into()],
            &mut out,
        );
        assert_eq!(String::from_utf8(out).unwrap(), "\t1,2,3\tnull\tnull\te");
    }

    fn kind(bitmask_form: bool, count: u64, in_subset: u64) -> Option<KindMask> {
        Some(KindMask {
            bitmask_form,
            count,
            in_subset,
            patients: None,
        })
    }

    #[test]
    fn counts_follow_the_forms_of_the_masks() {
        let sparse = GroupMasks {
            he: kind(false, 3, 2),
            ho: kind(false, 1, 1),
            hen: None,
            he_or_ho_in_subset: 3,
            hen_or_ho_in_subset: 1,
        };
        assert_eq!(counts(&sparse), (3, 1));
        let bitmask = GroupMasks {
            he: kind(true, 8, 5),
            ..sparse.clone()
        };
        assert_eq!(counts(&bitmask), (7, 13 - 7), "the union is a bitmask");
        let he_only = GroupMasks {
            he: kind(true, 8, 5),
            ho: None,
            ..sparse.clone()
        };
        assert_eq!(counts(&he_only), (9, 12 - 9));
        let no_call = GroupMasks {
            he: None,
            ho: kind(false, 1, 0),
            hen: kind(false, 2, 2),
            he_or_ho_in_subset: 0,
            hen_or_ho_in_subset: 2,
        };
        assert_eq!(
            counts(&no_call),
            (2, 1 - 2),
            "hen stands in for he in column 1 but not in the raw counts"
        );
        assert_eq!(counts(&GroupMasks::default()), (0, 0));
    }
}
