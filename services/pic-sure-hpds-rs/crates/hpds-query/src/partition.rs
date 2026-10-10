//! Consent partitions: `PartitionedPhenotypicObservationStore`.
//!
//! A data directory holds one store. A partitioned store records its consent partitions in
//! its index, and each concept's block holds every partition's rows. A [`PartitionSet`]
//! opens it, keeps its dictionary as one [`SummaryColumnMeta`] per concept, and picks the
//! partitions a query may read ([`PartitionSet::select`]).
//!
//! A query whose consents select every partition reads each concept's whole block. Any
//! narrower selection, and every selection with [`CombinedMode::Off`], reads each selected
//! partition's rows on their own: the block a store of that partition alone would hold,
//! derived from the whole block on first use and cached ([`crate::split`]).

use std::borrow::Cow;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use hpds_format::{
    Block, ConceptMeta, ConceptType, CryptoKey, INDEX_FILE_NAME, PartitionHolder, Protection,
    java_double_to_string, java_string_cmp,
};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde_json::Value;

use crate::error::{QueryError, Result};
use crate::model::Query;
use crate::split::partition_block;
use crate::store::{
    BlockCache, COMBINED_GROUP, ConceptBlocks, ConceptEntry, KEY_FILE_NAME, PARTITIONS_GROUP,
    Store, StoreFiles, StoreOptions, block_cache,
};
use crate::validate::ConceptLookup;

/// Name of the single partition of an unpartitioned data directory. It never matches a
/// consent, as in Java.
pub const UNPARTITIONED_NAME: &str = "__unpartitioned__";

/// Name of the combined store's directory in a partitioned data directory of format 1. A
/// data directory holding one, or partition directories, holds a store `hpds-etl convert`
/// must convert first.
pub const COMBINED_DIR_NAME: &str = ".combined";

/// Whether a [`PartitionSet`] answers selections of every partition from whole blocks
/// (`HPDS_COMBINED_STORE`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CombinedMode {
    /// A query that selects every partition of a store with two or more reads each
    /// concept's whole block.
    #[default]
    Auto,
    /// Every query reads its partitions' rows one partition at a time, as the v2 engine read
    /// one store per partition. The answers are the same.
    Off,
}

/// Whether a [`PartitionSet`] may count cross count patients per partition
/// (`HPDS_PER_PARTITION_COUNTS`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PerPartitionCounts {
    /// When the partitions are patient-disjoint, a query reads two or more of them one at a
    /// time, and every one of those carries the ETL's record that its rows hold no patient
    /// outside its patient set, count a concept's (or a category's) cohort patients in each
    /// partition and sum the counts, instead of unioning the partitions' patient sets
    /// first. No patient is then in two of those partitions' rows, so the sum is the count
    /// of the union.
    #[default]
    Auto,
    /// Always union the partitions' patient sets before counting.
    Off,
}

/// Whether an open [`PartitionSet`] answers full selections from whole blocks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CombinedStatus {
    /// Every query that selects every partition reads whole blocks.
    InUse,
    /// The store is unpartitioned or has one partition, whose rows are the whole block.
    Absent,
    /// [`CombinedMode::Off`]: full selections read partitions one at a time.
    Off,
}

impl CombinedStatus {
    /// The status as health and the startup log write it: `IN_USE`, `ABSENT` or `OFF`.
    pub fn as_str(self) -> &'static str {
        match self {
            CombinedStatus::InUse => "IN_USE",
            CombinedStatus::Absent => "ABSENT",
            CombinedStatus::Off => "OFF",
        }
    }
}

/// How a [`PartitionSet`] is opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartitionOptions {
    /// Cache budget and key rules. `cache_bytes` bounds the one block cache.
    pub store: StoreOptions,
    /// The Java property `hpds.requireAuthorizationFilter`. When set, an unpartitioned data
    /// directory is refused at open, and every query must carry at least one consent value.
    /// The [`PartitionSet`] keeps it, and [`crate::Engine::validate`] reads it from there.
    pub require_consents: bool,
    /// Whether full selections read whole blocks.
    pub combined: CombinedMode,
    /// Whether to count cross count patients per partition when the partitions are
    /// disjoint.
    pub per_partition_counts: PerPartitionCounts,
}

impl Default for PartitionOptions {
    /// The default store options, consents required (the Java default), whole blocks for
    /// full selections, and per-partition counts when the partitions are disjoint.
    fn default() -> Self {
        PartitionOptions {
            store: StoreOptions::default(),
            require_consents: true,
            combined: CombinedMode::Auto,
            per_partition_counts: PerPartitionCounts::Auto,
        }
    }
}

/// Whether the store is split by consent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    /// Consent partitions inside each block.
    Partitioned,
    /// No partitions, read as the single partition [`UNPARTITIONED_NAME`] whatever the
    /// consents.
    Unpartitioned,
}

/// File names of a Java javabin store. A partition directory holding one of them but no
/// [`INDEX_FILE_NAME`] has not been migrated.
const JAVABIN_FILE_NAMES: [&str; 2] = ["allObservationsStore.javabin", "columnMeta.javabin"];

/// One consent partition. Only its name and record are public; its data is read through
/// the engine.
#[derive(Debug)]
pub struct Partition {
    name: String,
    patients: RoaringBitmap,
    patients_indexed: bool,
}

impl Partition {
    /// The partition name, or [`UNPARTITIONED_NAME`].
    pub fn name(&self) -> &str {
        &self.name
    }

    /// True when the index records that every patient with a row in the partition is in
    /// its patient set.
    pub fn patients_indexed(&self) -> bool {
        self.patients_indexed
    }
}

/// `SummaryColumnMeta`: a concept's metadata merged over every partition that holds it, the
/// dictionary `/search` and the validators read. Not consent-scoped.
///
/// Merge rules, as `SummaryColumnMeta.merge` applies them: the largest `width_in_bytes`, the
/// union of category values, the smallest `min` and largest `max` and timestamps (absent
/// values skipped, `Math.min` and `Math.max` semantics for NaN and signed zero), the summed
/// patient count (a patient in two partitions counts twice, as in Java), and `has_timestamp`
/// when any partition has one. The type is the same everywhere: a conflict refuses the open.
#[derive(Debug, Clone, PartialEq)]
pub struct SummaryColumnMeta {
    /// The concept path.
    pub name: String,
    /// The largest `widthInBytes` of any partition.
    pub width_in_bytes: i32,
    /// True for categorical concepts.
    pub categorical: bool,
    /// Every category value of any partition, distinct, in Java string order. Empty for
    /// numeric concepts. Java holds an unordered set here.
    pub category_values: Vec<String>,
    /// The smallest value, `None` when no partition records one.
    pub min: Option<f64>,
    /// The largest value, `None` when no partition records one.
    pub max: Option<f64>,
    /// Patient counts summed over partitions.
    pub patient_count: u64,
    /// True when any partition's observations carry a timestamp.
    pub has_timestamp: bool,
    /// The earliest timestamp in epoch milliseconds.
    pub timestamp_min: Option<i64>,
    /// The latest timestamp in epoch milliseconds.
    pub timestamp_max: Option<i64>,
}

impl SummaryColumnMeta {
    /// The summary of one partition's concept (`new SummaryColumnMeta(columnMeta)`).
    pub fn from_concept(concept: &ConceptMeta) -> Self {
        SummaryColumnMeta {
            name: concept.path.clone(),
            width_in_bytes: concept.width_in_bytes,
            categorical: concept.is_categorical(),
            category_values: concept.category_values.clone().unwrap_or_default(),
            min: concept.min,
            max: concept.max,
            patient_count: concept.summed_patient_count(),
            has_timestamp: concept.has_timestamp,
            timestamp_min: concept.timestamp_min,
            timestamp_max: concept.timestamp_max,
        }
    }

    /// Numeric or categorical.
    pub fn concept_type(&self) -> ConceptType {
        if self.categorical {
            ConceptType::Categorical
        } else {
            ConceptType::Numeric
        }
    }

    /// Appends the concept as Jackson writes `SummaryColumnMeta` from its getters: `name`,
    /// `widthInBytes`, `categorical`, `categoryValues`, `min`, `max`, `patientCount`,
    /// `timestampMin`, `timestampMax`, with absent values as `null`. `hasTimestamp` has no
    /// bean getter and is left out. Doubles are written as `Double.toString` writes them,
    /// with NaN and the infinities as strings. Category values come out in Java string order,
    /// where Java's set order is unspecified.
    pub fn write_json(&self, out: &mut String) {
        out.push_str(r#"{"name":"#);
        push_json_string(out, &self.name);
        let _ = write!(
            out,
            r#","widthInBytes":{},"categorical":{},"categoryValues":["#,
            self.width_in_bytes, self.categorical
        );
        for (i, value) in self.category_values.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            push_json_string(out, value);
        }
        out.push(']');
        for (name, value) in [("min", self.min), ("max", self.max)] {
            let _ = write!(out, r#","{name}":"#);
            match value {
                Some(v) if v.is_finite() => out.push_str(&java_double_to_string(v)),
                Some(v) => push_json_string(out, &java_double_to_string(v)),
                None => out.push_str("null"),
            }
        }
        let _ = write!(out, r#","patientCount":{}"#, self.patient_count);
        for (name, value) in [
            ("timestampMin", self.timestamp_min),
            ("timestampMax", self.timestamp_max),
        ] {
            let _ = write!(out, r#","{name}":"#);
            match value {
                Some(v) => {
                    let _ = write!(out, "{v}");
                }
                None => out.push_str("null"),
            }
        }
        out.push('}');
    }

    /// [`SummaryColumnMeta::write_json`] into a new string.
    pub fn to_json(&self) -> String {
        let mut out = String::new();
        self.write_json(&mut out);
        out
    }
}

fn push_json_string(out: &mut String, value: &str) {
    out.push_str(&Value::String(value.to_owned()).to_string());
}

/// The store of a data directory and its consent partitions, with one block cache. Share it
/// behind an `Arc`; every method takes `&self`.
///
/// Its public methods read only the dictionary, the partition names and the cache counters,
/// for search and health checks. Patient data is read only by the [`crate::Engine`], through
/// a [`Selection`] that [`PartitionSet::select`] builds from the query's consents, so every
/// read is scoped by consent. Neither a selection's blocks nor the store can be reached
/// from outside the crate:
///
/// ```compile_fail
/// fn blocks(selection: &hpds_query::Selection<'_>) {
///     let _ = selection.blocks("\\a\\");
/// }
/// ```
///
/// ```compile_fail
/// fn store(set: &hpds_query::PartitionSet) {
///     let _ = set.partition("p1").unwrap().store();
/// }
/// ```
///
/// ```compile_fail
/// fn patients(set: &hpds_query::PartitionSet) {
///     let _ = set.select_consents(&[]).unwrap().patients();
/// }
/// ```
pub struct PartitionSet {
    dir: PathBuf,
    layout: Layout,
    require_consents: bool,
    partitions: Vec<Partition>,
    dictionary: Vec<SummaryColumnMeta>,
    holder_ends: Vec<u32>,
    holders: Vec<PartitionHolder>,
    index_holders: bool,
    all_patients: RoaringBitmap,
    store: Store,
    combined_mode: CombinedMode,
    disjoint: bool,
    per_partition_counts: PerPartitionCounts,
    evaluating: AtomicUsize,
    cache: BlockCache,
    generation: u128,
}

impl std::fmt::Debug for PartitionSet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PartitionSet")
            .field("dir", &self.dir)
            .field("layout", &self.layout)
            .field("require_consents", &self.require_consents)
            .field(
                "partitions",
                &self.partitions.iter().map(|p| &p.name).collect::<Vec<_>>(),
            )
            .field("concepts", &self.dictionary.len())
            .field("patients", &self.all_patients.len())
            .field("combined", &self.combined_status())
            .finish_non_exhaustive()
    }
}

impl PartitionSet {
    /// Opens the store in the data directory `dir`.
    ///
    /// `dir` must hold `pheno.index` and `pheno.blocks` of the current format, under the key
    /// `<dir>/encryption_key` (with no key file, a plain store opens only when
    /// `options.store.allow_plain` is set). A store whose index records partitions is
    /// partitioned, its partitions in Java string order of their names; any other is
    /// unpartitioned and read as the single partition [`UNPARTITIONED_NAME`], which is
    /// refused while `options.require_consents` is set.
    ///
    /// A directory with no `pheno.index` but partition directories holding one, or a
    /// [`COMBINED_DIR_NAME`] directory, holds a partitioned store of format 1 (Rust HPDS v1
    /// to v3), and an index of format 1 is a store of format 1: both are refused naming
    /// `hpds-etl convert`. A subdirectory holding Java javabins but no `pheno.index` is a
    /// partition never migrated, refused naming `hpds-etl migrate`.
    ///
    /// When `<dir>/.generation` exists (the ETL writes it last), it must hold exactly the
    /// store id of the index, as 32 hex digits, or the open is refused as a load still
    /// being published.
    ///
    /// # Errors
    /// [`QueryError::Open`] for every refusal above, a key file that cannot be read, and a
    /// store whose index or blocks file is missing, corrupt, from another store, or
    /// protected differently from the key.
    pub fn open(dir: &Path, options: &PartitionOptions) -> Result<Self> {
        let cache = block_cache(options.store.cache_bytes);
        if !dir.join(INDEX_FILE_NAME).exists() {
            return Err(refuse_without_index(dir)?);
        }
        let root_key = read_key_text(&dir.join(KEY_FILE_NAME))?;
        let protection = match &root_key {
            Some(text) => Protection::Encrypted(
                CryptoKey::from_key_file_bytes(text.as_bytes()).map_err(|e| open_error(dir, e))?,
            ),
            None if options.store.allow_plain => Protection::Plain,
            None => {
                return Err(open_error(
                    dir,
                    format!(
                        "no key file {} and plain stores are not allowed",
                        dir.join(KEY_FILE_NAME).display()
                    ),
                ));
            }
        };
        let files = StoreFiles::open(dir, protection)?;
        let store_id = files.index().store_id();
        check_generation(dir, &store_id.to_string())?;
        let partitioned = !files.index().partition_table().is_empty();
        if !partitioned && options.require_consents {
            return Err(open_error(
                dir,
                format!(
                    "found an unpartitioned {INDEX_FILE_NAME}, which cannot be scoped by \
                     consent. Load the data partitioned by consent, or set \
                     hpds.requireAuthorizationFilter=false"
                ),
            ));
        }
        let mut dictionary = Vec::with_capacity(files.index().concept_count());
        let mut entries = Vec::with_capacity(files.index().concept_count());
        let mut holder_ends = Vec::with_capacity(files.index().concept_count());
        let mut holders = Vec::new();
        for concept in files.index().concepts() {
            let concept = concept.map_err(|e| open_error(dir, e))?;
            let meta = concept.to_meta();
            if partitioned {
                holders.extend(concept.holders.iter());
            } else {
                holders.push(PartitionHolder {
                    partition: 0,
                    observations: concept.observation_count,
                    patients: concept.patient_count,
                });
            }
            holder_ends.push(holders.len() as u32);
            entries.push(ConceptEntry::of(&concept));
            dictionary.push(SummaryColumnMeta::from_concept(&meta));
        }
        let (store, table) = files.into_store(entries);
        let partitions: Vec<Partition> = if partitioned {
            table
                .partitions
                .into_iter()
                .map(|p| Partition {
                    name: p.name,
                    patients: p.patients,
                    patients_indexed: p.patients_indexed,
                })
                .collect()
        } else {
            vec![Partition {
                name: UNPARTITIONED_NAME.to_owned(),
                patients: store.patients().clone(),
                patients_indexed: true,
            }]
        };
        let all_patients = store.patients().clone();
        let summed: u64 = partitions.iter().map(|p| p.patients.len()).sum();
        let disjoint = summed == all_patients.len();
        let generation = xxhash_rust::xxh3::xxh3_128(store_id.as_bytes());
        Ok(PartitionSet {
            dir: dir.to_path_buf(),
            layout: if partitioned {
                Layout::Partitioned
            } else {
                Layout::Unpartitioned
            },
            require_consents: options.require_consents,
            partitions,
            dictionary,
            holder_ends,
            holders,
            index_holders: partitioned,
            all_patients,
            store,
            combined_mode: options.combined,
            disjoint,
            per_partition_counts: options.per_partition_counts,
            evaluating: AtomicUsize::new(0),
            cache,
            generation,
        })
    }

    /// True when full selections read whole blocks: a partitioned store with two or more
    /// partitions and [`CombinedMode::Auto`].
    fn reads_whole_blocks(&self) -> bool {
        self.layout == Layout::Partitioned
            && self.partitions.len() > 1
            && self.combined_mode == CombinedMode::Auto
    }

    /// A hash of the store id. Another load of the data directory gives another value.
    pub(crate) fn generation(&self) -> u128 {
        self.generation
    }

    /// Whether full selections read whole blocks.
    pub fn combined_status(&self) -> CombinedStatus {
        if self.layout == Layout::Unpartitioned || self.partitions.len() < 2 {
            CombinedStatus::Absent
        } else if self.combined_mode == CombinedMode::Off {
            CombinedStatus::Off
        } else {
            CombinedStatus::InUse
        }
    }

    /// True when no patient id is in two partitions: the partitions' patient counts sum to
    /// the count of their union. Computed at open from the partitions' patient sets.
    pub fn partitions_disjoint(&self) -> bool {
        self.disjoint
    }

    /// True when some query reading two or more partitions one at a time counts cross count
    /// patients per partition: the partitions' patient sets are disjoint, at least two
    /// partitions carry the `patients-indexed` record ([`Partition::patients_indexed`]), and
    /// [`PerPartitionCounts::Off`] was not set. A query does so only when every partition it
    /// reads carries the record ([`Selection::counts_per_partition`]).
    pub fn counts_per_partition(&self) -> bool {
        self.sums_disjoint_partitions() && self.patients_indexed_partitions() > 1
    }

    /// The number of partitions carrying the `patients-indexed` record.
    pub fn patients_indexed_partitions(&self) -> usize {
        self.partitions
            .iter()
            .filter(|p| p.patients_indexed)
            .count()
    }

    /// The conditions of [`PartitionSet::counts_per_partition`] that do not depend on the
    /// selection: disjoint patient sets, a partitioned layout, more than one partition, and
    /// [`PerPartitionCounts::Auto`].
    fn sums_disjoint_partitions(&self) -> bool {
        self.disjoint
            && self.layout == Layout::Partitioned
            && self.partitions.len() > 1
            && self.per_partition_counts == PerPartitionCounts::Auto
    }

    /// The data directory.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Whether the store is split by consent.
    pub fn layout(&self) -> Layout {
        self.layout
    }

    /// True when every query must carry a consent value (the Java property
    /// `hpds.requireAuthorizationFilter`).
    pub fn requires_consents(&self) -> bool {
        self.require_consents
    }

    /// The partition names, in Java string order.
    pub fn partition_names(&self) -> impl ExactSizeIterator<Item = &str> + '_ {
        self.partitions.iter().map(|p| p.name.as_str())
    }

    /// The number of concepts.
    pub fn concept_count(&self) -> usize {
        self.dictionary.len()
    }

    /// The partition named `name`.
    pub fn partition(&self, name: &str) -> Option<&Partition> {
        self.partitions
            .binary_search_by(|p| java_string_cmp(&p.name, name))
            .ok()
            .map(|i| &self.partitions[i])
    }

    /// The dictionary, one entry per concept, in Java string order of the paths, its
    /// metadata merged over the partitions as `SummaryColumnMeta` merges them.
    pub fn dictionary(&self) -> &[SummaryColumnMeta] {
        &self.dictionary
    }

    /// The merged metadata of the concept at exactly `path`.
    pub fn summary(&self, path: &str) -> Option<&SummaryColumnMeta> {
        self.concept_index(path).map(|i| &self.dictionary[i])
    }

    /// The position of the concept at exactly `path` in the dictionary, which is its
    /// position in the index.
    fn concept_index(&self, path: &str) -> Option<usize> {
        self.dictionary
            .binary_search_by(|c| java_string_cmp(&c.name, path))
            .ok()
    }

    /// Every concept whose path starts with `prefix` (`String.startsWith`), the concept at
    /// `prefix` included.
    pub fn dictionary_with_prefix(&self, prefix: &str) -> &[SummaryColumnMeta] {
        &self.dictionary[self.prefix_range(prefix)]
    }

    /// The positions in the dictionary of the concepts whose path starts with `prefix`,
    /// which are contiguous in Java string order.
    fn prefix_range(&self, prefix: &str) -> std::ops::Range<usize> {
        let start = self
            .dictionary
            .partition_point(|c| java_string_cmp(&c.name, prefix).is_lt());
        let len = self.dictionary[start..]
            .iter()
            .take_while(|c| c.name.starts_with(prefix))
            .count();
        start..start + len
    }

    /// The start in `holders` of the holders of the concept at dictionary position `index`.
    fn holders_start(&self, index: usize) -> usize {
        index
            .checked_sub(1)
            .map_or(0, |previous| self.holder_ends[previous] as usize)
    }

    /// The partitions holding the concept at dictionary position `index`, in partition
    /// order. An unpartitioned store's one partition holds every concept.
    fn holders_of(&self, index: usize) -> &[PartitionHolder] {
        &self.holders[self.holders_start(index)..self.holder_ends[index] as usize]
    }

    /// The holders the index records for the concept at `index`, which its block must
    /// repeat: none in an unpartitioned store.
    fn index_holders_of(&self, index: usize) -> &[PartitionHolder] {
        if self.index_holders {
            self.holders_of(index)
        } else {
            &[]
        }
    }

    /// True when the store holds at least one concept, the readiness rule for health
    /// checks.
    pub fn is_healthy(&self) -> bool {
        !self.dictionary.is_empty()
    }

    /// The partitions `query` may read (`partitionsFor(query.consentValues())`):
    ///
    /// | Consent values | Consents required | Layout | Selected |
    /// |---|---|---|---|
    /// | none | yes | any | error |
    /// | none | no | any | every partition |
    /// | some | any | unpartitioned | the one partition |
    /// | some | any | partitioned | those named by a value; unknown values are skipped |
    ///
    /// # Errors
    /// [`QueryError::UserConsentsRequired`] in the first row.
    pub fn select(&self, query: &Query) -> Result<Selection<'_>> {
        self.select_consents(&query.consent_values())
    }

    /// [`PartitionSet::select`] for an explicit list of consent values.
    ///
    /// # Errors
    /// As for [`PartitionSet::select`].
    pub fn select_consents(&self, consents: &[&str]) -> Result<Selection<'_>> {
        let mask: Vec<bool> = if consents.is_empty() {
            if self.require_consents {
                return Err(QueryError::UserConsentsRequired);
            }
            vec![true; self.partitions.len()]
        } else if self.layout == Layout::Unpartitioned {
            vec![true; self.partitions.len()]
        } else {
            let mut sorted = consents.to_vec();
            sorted.sort_unstable();
            self.partitions
                .iter()
                .map(|p| sorted.binary_search(&p.name.as_str()).is_ok())
                .collect()
        };
        let members: Vec<&Partition> = self
            .partitions
            .iter()
            .zip(&mask)
            .filter_map(|(partition, &selected)| selected.then_some(partition))
            .collect();
        Ok(Selection {
            set: self,
            members,
            mask,
            routed_to_combined: false,
        })
    }

    /// Fails exactly when [`PartitionSet::select`] would: consents are required and `query`
    /// carries no consent value.
    ///
    /// # Errors
    /// [`QueryError::UserConsentsRequired`].
    pub fn check_consents(&self, query: &Query) -> Result<()> {
        if self.require_consents && query.consent_values().is_empty() {
            return Err(QueryError::UserConsentsRequired);
        }
        Ok(())
    }

    /// Number of cached blocks, whole and per partition, after applying the cache's
    /// pending inserts and evictions.
    pub fn cached_block_count(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache
            .iter()
            .map(|(_, blocks)| blocks.filled() as u64)
            .sum()
    }

    /// Summed weight in bytes of the cached blocks, after applying the cache's pending
    /// inserts and evictions.
    pub fn cached_bytes(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache.weighted_size()
    }

    /// Counts a cohort evaluation as running until the guard is dropped, for
    /// [`PartitionSet::spare_parallelism`].
    pub(crate) fn begin_evaluation(&self) -> Evaluating<'_> {
        self.evaluating.fetch_add(1, Ordering::Relaxed);
        Evaluating {
            count: &self.evaluating,
        }
    }

    /// How many rayon threads one running cohort evaluation may spread a large union over:
    /// the pool's threads divided by the evaluations running now, at least 1. A lone query
    /// gets the whole pool; at the concurrency of the pool or above, each runs on its own
    /// thread, since splitting would only queue its chunks behind other queries' work.
    pub(crate) fn spare_parallelism(&self) -> usize {
        let running = self.evaluating.load(Ordering::Relaxed).max(1);
        (rayon::current_num_threads() / running).max(1)
    }

    /// Drops every cached block.
    pub fn clear_cache(&self) {
        self.cache.invalidate_all();
        self.cache.run_pending_tasks();
    }
}

impl ConceptLookup for PartitionSet {
    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.summary(path).map(SummaryColumnMeta::concept_type)
    }
}

/// A running cohort evaluation, counted until dropped
/// ([`PartitionSet::begin_evaluation`]).
pub(crate) struct Evaluating<'a> {
    count: &'a AtomicUsize,
}

impl Drop for Evaluating<'_> {
    fn drop(&mut self) {
        self.count.fetch_sub(1, Ordering::Relaxed);
    }
}

/// The partitions one query reads, in partition name order. Built only by
/// [`PartitionSet::select`] and [`PartitionSet::select_consents`]. Publicly it only names
/// the partitions; the engine reads patient data through it.
///
/// A selection reads its partitions' rows one partition at a time. The engine's admission
/// step alone may route it to whole blocks ([`Selection::routed`]), and a routed selection
/// reads whole blocks only while it names every partition: [`Selection::combined_store`]
/// checks that on every read, so no narrower selection can reach another partition's rows.
#[derive(Debug, Clone)]
pub struct Selection<'a> {
    set: &'a PartitionSet,
    members: Vec<&'a Partition>,
    mask: Vec<bool>,
    routed_to_combined: bool,
}

impl<'a> Selection<'a> {
    /// The partition set selected from.
    pub fn set(&self) -> &'a PartitionSet {
        self.set
    }

    /// The selected partition names, in order.
    pub fn names(&self) -> Vec<&'a str> {
        self.members.iter().map(|p| p.name.as_str()).collect()
    }

    /// One flag per partition of the set, in partition order: true when it is selected.
    pub(crate) fn mask(&self) -> &[bool] {
        &self.mask
    }

    /// True when every partition of the set is selected.
    pub fn is_all(&self) -> bool {
        self.members.len() == self.set.partitions.len()
            && self.mask.len() == self.set.partitions.len()
            && self.mask.iter().all(|&selected| selected)
    }

    /// True when the engine counts this selection's cross count patients per partition and
    /// sums the counts: the partitions' patient sets are disjoint and
    /// [`PerPartitionCounts::Off`] was not set ([`PartitionSet::counts_per_partition`]),
    /// two or more partitions are selected, the selection reads them one at a time, not
    /// whole blocks, and every selected partition carries the `patients-indexed` record, so
    /// no patient is in two selected partitions' rows either.
    pub fn counts_per_partition(&self) -> bool {
        self.set.sums_disjoint_partitions()
            && self.members.len() > 1
            && !self.reads_combined()
            && self.members.iter().all(|p| p.patients_indexed)
    }

    /// True when this selection reads whole blocks instead of its partitions one at a time.
    pub fn reads_combined(&self) -> bool {
        self.combined_store()
    }

    /// This selection, routed to whole blocks when it selects every partition and the set
    /// reads whole blocks for that ([`PartitionSet::combined_status`]). The one place the
    /// engine decides to read whole blocks; called only by its admission step, after the
    /// consent checks.
    pub(crate) fn routed(mut self) -> Self {
        self.routed_to_combined = self.is_all() && self.set.reads_whole_blocks();
        self
    }

    /// True when this selection is routed to whole blocks and still selects every
    /// partition.
    fn combined_store(&self) -> bool {
        self.routed_to_combined && self.is_all()
    }

    /// The union of the selected partitions' patients
    /// (`PartitionedPhenotypicObservationStore.getPatientIds`). Borrowed when one partition
    /// or every partition is selected.
    pub(crate) fn patients(&self) -> Cow<'a, RoaringBitmap> {
        match self.members.as_slice() {
            [] => Cow::Owned(RoaringBitmap::new()),
            [one] => Cow::Borrowed(&one.patients),
            _ if self.is_all() => Cow::Borrowed(&self.set.all_patients),
            many => Cow::Owned(many.iter().map(|p| &p.patients).union()),
        }
    }

    /// The type of the concept at exactly `path`, or `None` when no selected partition holds
    /// it.
    pub fn concept_type(&self, path: &str) -> Option<ConceptType> {
        let index = self.set.concept_index(path)?;
        self.selected_requests(index)
            .next()
            .map(|_| self.set.dictionary[index].concept_type())
    }

    /// The block of `path` in each selected partition that holds the concept, in partition
    /// name order, each holding that partition's rows only. Only the partitions holding the
    /// concept are visited, through one cache lookup for the concept whatever the number of
    /// partitions. Blocks not yet cached load on the rayon pool. A selection routed to whole
    /// blocks returns the one whole block, whose rows are those blocks' rows merged.
    /// Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub(crate) fn blocks(&self, path: &str) -> Result<Vec<Arc<Block>>> {
        let Some(index) = self.set.concept_index(path) else {
            return Ok(Vec::new());
        };
        if self.combined_store() {
            return self.load(
                COMBINED_GROUP,
                std::iter::once(BlockRequest::whole(index)),
                1,
            );
        }
        self.load(
            PARTITIONS_GROUP,
            self.selected_requests(index),
            self.set.holders_of(index).len(),
        )
    }

    /// The blocks of every concept whose path starts with `prefix` and that has at least one
    /// observation in a selected partition, the concept at `prefix` included, from every
    /// selected partition holding it with observations. Other blocks are never loaded.
    /// Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub(crate) fn blocks_with_prefix(&self, prefix: &str) -> Result<Vec<Arc<Block>>> {
        let range = self.set.prefix_range(prefix);
        if self.combined_store() {
            let capacity = range.len();
            let requests = range
                .map(BlockRequest::whole)
                .filter(|r| self.set.store.entry(r.index as u32).observation_count > 0);
            return self.load(COMBINED_GROUP, requests, capacity);
        }
        let capacity = if range.is_empty() {
            0
        } else {
            self.set.holder_ends[range.end - 1] as usize - self.set.holders_start(range.start)
        };
        let requests = range
            .flat_map(|index| self.selected_requests(index))
            .filter(|r| r.holder.observations > 0);
        self.load(PARTITIONS_GROUP, requests, capacity)
    }

    /// The number of concepts whose path starts with `prefix` that a selected partition
    /// holds, counting no further than `limit`.
    pub(crate) fn count_with_prefix(&self, prefix: &str, limit: usize) -> usize {
        let range = self.set.prefix_range(prefix);
        if self.is_all() {
            return range.len().min(limit);
        }
        range
            .filter(|&index| self.selected_requests(index).next().is_some())
            .take(limit)
            .count()
    }

    /// The requests for the rows of the concept at dictionary position `index` in the
    /// selected partitions holding it, in partition order.
    fn selected_requests(&self, index: usize) -> impl Iterator<Item = BlockRequest> + Clone + '_ {
        self.set
            .holders_of(index)
            .iter()
            .enumerate()
            .filter(|(_, h)| self.mask[usize::from(h.partition)])
            .map(move |(slot, &holder)| BlockRequest {
                index,
                slot,
                holder,
            })
    }

    /// The number of slots of the cache entry of the concept at `index` in `group`.
    fn entry_len(&self, group: u32, index: usize) -> usize {
        if group == COMBINED_GROUP {
            1
        } else {
            self.set.holders_of(index).len()
        }
    }

    /// The blocks `requests` name, in order, from the cache entries of `group`. Looks each
    /// concept up once, and on the first block not cached hands over to
    /// [`Selection::load_missing`].
    fn load(
        &self,
        group: u32,
        requests: impl Iterator<Item = BlockRequest> + Clone,
        capacity: usize,
    ) -> Result<Vec<Arc<Block>>> {
        let mut blocks = Vec::with_capacity(capacity);
        let mut current: Option<(usize, Arc<ConceptBlocks>)> = None;
        for request in requests.clone() {
            let entry = match current {
                Some((index, ref entry)) if index == request.index => entry,
                _ => match self.set.cache.get(&(group, request.index as u32)) {
                    Some(entry) => &current.insert((request.index, entry)).1,
                    None => return self.load_missing(group, requests, capacity),
                },
            };
            match entry.get(request.slot) {
                Some(block) => blocks.push(Arc::clone(block)),
                None => return self.load_missing(group, requests, capacity),
            }
        }
        Ok(blocks)
    }

    /// The whole block of the concept at `index`, from the cache or read and cached.
    /// Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read.
    fn whole_block(&self, index: usize) -> Result<Arc<Block>> {
        let key = (COMBINED_GROUP, index as u32);
        let entry = self
            .set
            .cache
            .get_with(key, || Arc::new(ConceptBlocks::new(1)));
        if let Some(block) = entry.get(0) {
            return Ok(Arc::clone(block));
        }
        let block = self.read_whole(index)?;
        let block = entry.fill(0, Arc::new(block));
        self.set.cache.insert(key, entry);
        Ok(block)
    }

    /// Reads the whole block of the concept at `index` from the store, uncached.
    fn read_whole(&self, index: usize) -> Result<Block> {
        self.set.store.read_block(
            index as u32,
            &self.set.dictionary[index].name,
            self.set.index_holders_of(index),
        )
    }

    /// The block `request` names: in [`COMBINED_GROUP`] the whole block, read from the
    /// store; in [`PARTITIONS_GROUP`] the rows of the requested partition, which is the whole
    /// block when that partition is the concept's only holder, and otherwise derived from
    /// the whole block ([`partition_block`]), which is read through the cache.
    fn read_request(&self, group: u32, request: &BlockRequest) -> Result<Block> {
        if group == COMBINED_GROUP || self.set.holders_of(request.index).len() == 1 {
            return self.read_whole(request.index);
        }
        let whole = self.whole_block(request.index)?;
        partition_block(
            &whole,
            request.holder.partition,
            self.set.store.membership(),
            &self.set.dictionary[request.index].name,
        )
    }

    /// [`Selection::load`] when some block is not cached: creates the missing cache
    /// entries, loads every missing block on the rayon pool, and inserts each entry it
    /// filled again so the cache weighs its new blocks. Two queries missing the same block
    /// at once may both read it; the first to fill the slot wins.
    ///
    /// Every missing block is attempted. When some fail, the entries the others filled are
    /// still inserted again, so the cache never holds decoded blocks it weighs as empty,
    /// and the first failure in request order is returned.
    fn load_missing(
        &self,
        group: u32,
        requests: impl Iterator<Item = BlockRequest>,
        capacity: usize,
    ) -> Result<Vec<Arc<Block>>> {
        let mut blocks: Vec<Option<Arc<Block>>> = Vec::with_capacity(capacity);
        let mut missing: Vec<(usize, BlockRequest, Arc<ConceptBlocks>)> = Vec::new();
        let mut current: Option<(usize, Arc<ConceptBlocks>)> = None;
        for request in requests {
            let entry = match current {
                Some((index, ref entry)) if index == request.index => entry,
                _ => {
                    let len = self.entry_len(group, request.index);
                    let entry = self.set.cache.get_with((group, request.index as u32), || {
                        Arc::new(ConceptBlocks::new(len))
                    });
                    &current.insert((request.index, entry)).1
                }
            };
            match entry.get(request.slot) {
                Some(block) => blocks.push(Some(Arc::clone(block))),
                None => {
                    missing.push((blocks.len(), request, Arc::clone(entry)));
                    blocks.push(None);
                }
            }
        }
        let loaded: Vec<Result<Arc<Block>>> = missing
            .par_iter()
            .map(|(_, request, entry)| {
                let block = self.read_request(group, request)?;
                Ok(entry.fill(request.slot, Arc::new(block)))
            })
            .collect();
        let mut reweighed = None;
        let mut first_error = None;
        for ((position, request, entry), block) in missing.iter().zip(loaded) {
            match block {
                Ok(block) => blocks[*position] = Some(block),
                Err(error) => {
                    first_error.get_or_insert(error);
                    continue;
                }
            }
            if reweighed != Some(request.index) {
                self.set
                    .cache
                    .insert((group, request.index as u32), Arc::clone(entry));
                reweighed = Some(request.index);
            }
        }
        match first_error {
            Some(error) => Err(error),
            None => Ok(blocks.into_iter().flatten().collect()),
        }
    }
}

/// One block a selection reads: the concept's position `index` in the index, the slot of
/// its cache entry that holds the block, and the partition holding the rows.
#[derive(Debug, Clone, Copy)]
struct BlockRequest {
    index: usize,
    slot: usize,
    holder: PartitionHolder,
}

impl BlockRequest {
    /// The request for the whole block of the concept at `index`.
    fn whole(index: usize) -> Self {
        BlockRequest {
            index,
            slot: 0,
            holder: PartitionHolder::default(),
        }
    }
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}

/// The key file's text trimmed with Java `String.trim` rules, or `None` when there is no
/// such file.
fn read_key_text(path: &Path) -> Result<Option<String>> {
    match std::fs::read(path) {
        Ok(bytes) => Ok(Some(
            String::from_utf8_lossy(&bytes)
                .trim_matches(|c: char| c <= ' ')
                .to_owned(),
        )),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(open_error(path, e)),
    }
}

/// The refusal for a data directory `dir` without `pheno.index`: a partitioned store of
/// format 1 (a subdirectory holding `pheno.index`, or a [`COMBINED_DIR_NAME`] directory)
/// names `hpds-etl convert`, a subdirectory holding javabins but no `pheno.index` names
/// `hpds-etl migrate`, and anything else says no store is there.
///
/// # Errors
/// [`QueryError::Open`] when `dir` cannot be listed or a subdirectory name is not UTF-8.
fn refuse_without_index(dir: &Path) -> Result<QueryError> {
    let entries = std::fs::read_dir(dir).map_err(|e| open_error(dir, e))?;
    let mut old_partitions = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| open_error(dir, e))?;
        if entry.file_name().as_encoded_bytes().starts_with(b".") {
            continue;
        }
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        if path.join(INDEX_FILE_NAME).exists() {
            old_partitions.push(entry.file_name().to_string_lossy().into_owned());
        } else if let Some(javabin) = JAVABIN_FILE_NAMES
            .iter()
            .find(|name| path.join(name).exists())
        {
            return Ok(open_error(
                &path,
                format!(
                    "it holds {javabin} but no {INDEX_FILE_NAME}, so it is a half-migrated \
                     partition. Migrate it with hpds-etl migrate, or move it out of the \
                     data directory"
                ),
            ));
        }
    }
    if !old_partitions.is_empty() || dir.join(COMBINED_DIR_NAME).exists() {
        old_partitions.sort();
        return Ok(open_error(
            dir,
            format!(
                "it holds a partitioned store of format 1 (Rust HPDS v1 to v3: partitions \
                 {old_partitions:?} in their own directories), which this service does not \
                 read. Convert it with hpds-etl convert --store <this directory> --out <new \
                 directory> and serve the new directory"
            ),
        ));
    }
    Ok(open_error(
        dir,
        format!("no {INDEX_FILE_NAME} in it or in any immediate subdirectory"),
    ))
}

/// Name of the file the ETL writes last into a data directory, holding the store id of the
/// load it completed.
const GENERATION_FILE_NAME: &str = ".generation";

/// Refuses a data directory whose `.generation` file does not hold exactly `store_id`, as
/// 32 hex digits: a load still being published, or a marker of an earlier ETL. Passes when
/// there is no such file.
///
/// # Errors
/// [`QueryError::Open`] when the file cannot be read or names anything else.
fn check_generation(dir: &Path, store_id: &str) -> Result<()> {
    let path = dir.join(GENERATION_FILE_NAME);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(open_error(&path, e)),
    };
    let tokens: Vec<String> = text
        .split_whitespace()
        .map(str::to_ascii_lowercase)
        .collect();
    if tokens.len() == 1 && tokens[0] == store_id {
        return Ok(());
    }
    Err(open_error(
        dir,
        format!(
            "a partially published load: {GENERATION_FILE_NAME} does not name the store id \
             {store_id} of {INDEX_FILE_NAME} alone. Wait for the load to finish, or republish \
             it"
        ),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn summary_json_matches_the_java_field_order() {
        let summary = SummaryColumnMeta {
            name: "\\a\\".into(),
            width_in_bytes: 8,
            categorical: false,
            category_values: Vec::new(),
            min: Some(31.0),
            max: Some(f64::INFINITY),
            patient_count: 1126,
            has_timestamp: false,
            timestamp_min: None,
            timestamp_max: Some(5),
        };
        assert_eq!(
            summary.to_json(),
            r#"{"name":"\\a\\","widthInBytes":8,"categorical":false,"categoryValues":[],"min":31.0,"max":"Infinity","patientCount":1126,"timestampMin":null,"timestampMax":5}"#
        );
    }
}
