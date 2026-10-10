//! Consent partitions: `PartitionedPhenotypicObservationStore`.
//!
//! A data directory holds either one store at its root (unpartitioned) or one store per
//! immediate subdirectory, each named after the consent that grants it. A [`PartitionSet`]
//! opens them all with one key and one block cache, merges their dictionaries into one
//! [`SummaryColumnMeta`] per concept, and picks the partitions a query may read
//! ([`PartitionSet::select`]).
//!
//! A partitioned data directory may also hold a combined store in [`COMBINED_DIR_NAME`],
//! written by the ETL with every partition's rows in the order this module merges them. It
//! is never a partition. When it is present, listed in `.generation` and consistent with
//! the partitions, a query whose consents select every partition reads it instead of the
//! partitions; any narrower selection reads only its own partitions.

use std::borrow::Cow;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};

use hpds_format::{
    Block, ConceptMeta, ConceptType, ConceptView, CryptoKey, INDEX_FILE_NAME, Protection,
    java_double_cmp, java_double_to_string, java_string_cmp,
};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde_json::Value;

use crate::error::{QueryError, Result};
use crate::model::Query;
use crate::store::{
    BlockCache, COMBINED_GROUP, ConceptBlocks, ConceptEntry, KEY_FILE_NAME, PARTITIONS_GROUP,
    Store, StoreFiles, StoreOptions, block_cache, read_store_id,
};
use crate::validate::ConceptLookup;

/// Name of the single partition of an unpartitioned data directory. It never matches a
/// consent, as in Java.
pub const UNPARTITIONED_NAME: &str = "__unpartitioned__";

/// Name of the combined store's directory inside a partitioned data directory. It starts
/// with `.`, so it is never a partition, and the ETL refuses a partition of that name.
pub const COMBINED_DIR_NAME: &str = ".combined";

/// The tag after the combined store's id on its `.generation` line.
const COMBINED_GENERATION_TAG: &str = ".combined";

/// Whether a [`PartitionSet`] uses the combined store (`HPDS_COMBINED_STORE`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum CombinedMode {
    /// Open the combined store when the data directory has one, and refuse the open when
    /// it is listed in `.generation` but missing, present but not listed, or inconsistent
    /// with the partitions.
    #[default]
    Auto,
    /// Never open it. `.generation`'s combined store line is ignored, and every query reads
    /// its partitions, as before the combined store existed.
    Off,
}

/// Whether a [`PartitionSet`] may count cross count patients per partition
/// (`HPDS_PER_PARTITION_COUNTS`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PerPartitionCounts {
    /// When the partitions are patient-disjoint and a query reads two or more of them from
    /// their own stores, count a concept's (or a category's) cohort patients in each
    /// partition and sum the counts, instead of unioning the partitions' patient sets
    /// first. No patient is in two partitions, so the sum is the count of the union.
    #[default]
    Auto,
    /// Always union the partitions' patient sets before counting.
    Off,
}

/// Whether an open [`PartitionSet`] answers full selections from a combined store.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CombinedStatus {
    /// A combined store is open, and every query that selects every partition reads it.
    InUse,
    /// The data directory has no combined store (or is unpartitioned).
    Absent,
    /// [`CombinedMode::Off`]: any combined store was ignored.
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
    /// Cache budget and key rules. `cache_bytes` bounds the one cache all partitions share.
    pub store: StoreOptions,
    /// The Java property `hpds.requireAuthorizationFilter`. When set, an unpartitioned data
    /// directory is refused at open, and every query must carry at least one consent value.
    /// The [`PartitionSet`] keeps it, and [`crate::Engine::validate`] reads it from there.
    pub require_consents: bool,
    /// Whether to use the combined store. Its blocks share the one cache.
    pub combined: CombinedMode,
    /// Whether to count cross count patients per partition when the partitions are
    /// disjoint.
    pub per_partition_counts: PerPartitionCounts,
}

impl Default for PartitionOptions {
    /// The default store options, consents required (the Java default), the combined
    /// store used when present, and per-partition counts when the partitions are disjoint.
    fn default() -> Self {
        PartitionOptions {
            store: StoreOptions::default(),
            require_consents: true,
            combined: CombinedMode::Auto,
            per_partition_counts: PerPartitionCounts::Auto,
        }
    }
}

/// Whether the data directory is split by consent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    /// One store per consent subdirectory.
    Partitioned,
    /// One store at the root, read as the single partition [`UNPARTITIONED_NAME`] whatever
    /// the consents.
    Unpartitioned,
}

/// File names of a Java javabin store. A partition directory holding one of them but no
/// [`INDEX_FILE_NAME`] has not been migrated.
const JAVABIN_FILE_NAMES: [&str; 2] = ["allObservationsStore.javabin", "columnMeta.javabin"];

/// One consent's store. Only its name is public; its data is read through the engine.
#[derive(Debug)]
pub struct Partition {
    name: String,
    store: Store,
}

impl Partition {
    /// The partition name: the subdirectory name, or [`UNPARTITIONED_NAME`].
    pub fn name(&self) -> &str {
        &self.name
    }
}

/// One store's copy of one concept: the store's slot (a partition's position in
/// [`PartitionSet`] order, or one past the last partition for the combined store) and the
/// concept's position in that store's index, where the store keeps its
/// [`ConceptEntry`].
#[derive(Debug, Clone, Copy)]
struct Holder {
    slot: u32,
    position: u32,
}

/// Partitions whose indexes are decoded at once while a [`PartitionSet`] opens. Each body
/// is merged into the shared concept table and dropped before the next group, which bounds
/// the memory the open needs on top of what it keeps.
const OPEN_GROUP: usize = 8;

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
            patient_count: u64::from(concept.patient_count),
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

fn merge_option<T: Copy>(a: Option<T>, b: Option<T>, pick: fn(T, T) -> T) -> Option<T> {
    match (a, b) {
        (Some(a), Some(b)) => Some(pick(a, b)),
        (a, b) => a.or(b),
    }
}

/// `Math.min` on doubles: NaN wins, and `-0.0` is below `0.0`.
fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if java_double_cmp(a, b).is_le() {
        a
    } else {
        b
    }
}

/// `Math.max` on doubles: NaN wins, and `0.0` is above `-0.0`.
fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if java_double_cmp(a, b).is_ge() {
        a
    } else {
        b
    }
}

/// Every partition of a data directory, opened with one key and one block cache. Share it
/// behind an `Arc`; every method takes `&self`.
///
/// Its public methods read only the merged dictionary, the partition names and the cache
/// counters, for search and health checks. Patient data is read only by the
/// [`crate::Engine`], through a [`Selection`] that [`PartitionSet::select`] builds from the
/// query's consents, so every read is scoped by consent. Neither a selection's blocks nor
/// a partition's store can be reached from outside the crate:
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
    holders: Vec<Holder>,
    all_patients: RoaringBitmap,
    combined: Option<Store>,
    combined_mode: CombinedMode,
    disjoint: bool,
    per_partition_counts: PerPartitionCounts,
    evaluating: AtomicUsize,
    cache: BlockCache,
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
    /// Opens the data directory `dir`.
    ///
    /// - When `dir` itself holds `pheno.index`, it is one unpartitioned store, read as the
    ///   partition [`UNPARTITIONED_NAME`]. Its subdirectories are ignored.
    /// - Otherwise the immediate subdirectories are read, and nothing deeper. One whose name
    ///   starts with `.` is ignored. One holding `pheno.index` is a partition named after the
    ///   subdirectory. One holding a Java javabin file (`allObservationsStore.javabin`,
    ///   `columnMeta.javabin`, or both) but no `pheno.index` is a partition that was never
    ///   migrated, and refuses the open, naming it, rather than silently dropping that
    ///   consent group. Any other subdirectory (a genomic `all` directory, `lost+found`) is
    ///   ignored. Java loads each of those as an empty partition and lists it; here it is
    ///   not a partition at all, so a consent naming it selects nothing.
    ///
    /// When `<dir>/.generation` exists, the ETL wrote it last, after every partition of a
    /// load was in place. It lists the store id of each partition of that load, one per
    /// line as 32 lowercase hex digits ([`hpds_format::StoreId`]'s display form); blank
    /// lines are skipped. A line whose second token is `.combined` lists the combined
    /// store; a line with any other second token starting with `.` refuses the open, in
    /// either combined mode, as a store kind this service does not know; otherwise only the
    /// first token of a line is read. Every opened partition's id
    /// must be listed and every listed partition id must belong to an opened partition, or
    /// the open is refused as a partially published load. Without the file the stores open
    /// as found.
    ///
    /// The combined store, under [`CombinedMode::Auto`], is opened when `.generation` lists
    /// it, and must then be in [`COMBINED_DIR_NAME`] with that store id. It must hold
    /// exactly the merged dictionary's concepts, each with the merged type and the summed
    /// observation count of the partitions holding it, and exactly the union of the
    /// partitions' patients. A `.combined/pheno.index` that `.generation` does not list
    /// (or with no `.generation` at all) refuses the open too, as a store nothing ties to
    /// these partitions. An unpartitioned directory has no combined store; one listed or
    /// present refuses the open. A directory with exactly one partition reads it directly:
    /// a combined store there would only repeat it, so any combined store is ignored and
    /// the status is [`CombinedStatus::Absent`]. [`CombinedMode::Off`] skips all of this.
    ///
    /// The key is `<dir>/encryption_key`, shared by every partition. A partition may carry
    /// its own `encryption_key` only if it holds the same key. With no root key the
    /// partitions open as plain stores, and only if `options.store.allow_plain` is set.
    /// Partitions are kept in Java string order of their names.
    ///
    /// Memory: the partitions' concept tables are merged into one shared table, the merged
    /// dictionary, holding each path and category value once. Each store keeps only a
    /// compact entry per concept (observation count, block location and decoded size
    /// bound). Indexes are decoded a few at a time and each is dropped once merged.
    ///
    /// # Errors
    /// [`QueryError::Open`] when the directory cannot be listed, holds no store (Java starts
    /// empty instead), holds a subdirectory with a javabin file but no `pheno.index`, holds a
    /// `.generation` file that cannot be read or does not match the opened stores' ids, is
    /// unpartitioned while `options.require_consents` is set, a partition's key file
    /// differs from the shared key, a store cannot be opened (its index or blocks file is
    /// missing, corrupt, from another store, or protected differently from the key), a
    /// partition's index is replaced between the `.generation` check and its decoding, or the
    /// combined store is missing, unlisted or inconsistent as described above.
    /// [`QueryError::TypeConflict`] when two partitions type one concept differently.
    pub fn open(dir: &Path, options: &PartitionOptions) -> Result<Self> {
        let cache = block_cache(options.store.cache_bytes);
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
        let (layout, found) = if dir.join(INDEX_FILE_NAME).exists() {
            if options.require_consents {
                return Err(open_error(
                    dir,
                    format!(
                        "found an unpartitioned {INDEX_FILE_NAME}, which cannot be scoped by \
                         consent. Load the data partitioned by consent, or set \
                         hpds.requireAuthorizationFilter=false"
                    ),
                ));
            }
            (
                Layout::Unpartitioned,
                vec![(UNPARTITIONED_NAME.to_owned(), dir.to_path_buf())],
            )
        } else {
            let mut found = partition_dirs(dir)?;
            if found.is_empty() {
                return Err(open_error(
                    dir,
                    format!("no {INDEX_FILE_NAME} in it or in any immediate subdirectory"),
                ));
            }
            found.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
            for (_, path) in &found {
                check_partition_key(path, root_key.as_deref())?;
            }
            (Layout::Partitioned, found)
        };
        let ids = found
            .par_iter()
            .map(|(name, path)| Ok((name.as_str(), read_store_id(path)?)))
            .collect::<Result<Vec<_>>>()?;
        let generation = read_generation(dir)?;
        check_generation(dir, &ids, generation.as_ref())?;
        let mut merger = DictionaryMerger::default();
        let mut partitions = Vec::with_capacity(found.len());
        for (group, group_ids) in found.chunks(OPEN_GROUP).zip(ids.chunks(OPEN_GROUP)) {
            let opened = group
                .par_iter()
                .map(|(_, path)| StoreFiles::open(path, protection.clone()))
                .collect::<Result<Vec<_>>>()?;
            for (((name, path), (_, id)), files) in group.iter().zip(group_ids).zip(opened) {
                if files.index().store_id() != *id {
                    return Err(open_error(
                        path,
                        "its index was replaced while the service opened it. Wait for the \
                         load to finish, or republish it",
                    ));
                }
                let slot = partitions.len() as u32;
                let entries = merger.add(slot, name, &files)?;
                partitions.push(Partition {
                    name: name.clone(),
                    store: files.into_store(entries),
                });
            }
        }
        let (dictionary, holder_ends, holders) = merger.finish();
        let all_patients = partitions.iter().map(|p| p.store.patients()).union();
        let summed: u64 = partitions.iter().map(|p| p.store.patients().len()).sum();
        let disjoint = summed == all_patients.len();
        let mut set = PartitionSet {
            dir: dir.to_path_buf(),
            layout,
            require_consents: options.require_consents,
            partitions,
            dictionary,
            holder_ends,
            holders,
            all_patients,
            combined: None,
            combined_mode: options.combined,
            disjoint,
            per_partition_counts: options.per_partition_counts,
            evaluating: AtomicUsize::new(0),
            cache,
        };
        if options.combined == CombinedMode::Auto {
            set.combined = set.open_combined(generation.as_ref(), protection)?;
        }
        Ok(set)
    }

    /// The combined store [`PartitionSet::open`] describes, or `None` when the directory has
    /// none or holds a single partition.
    ///
    /// # Errors
    /// [`QueryError::Open`] for every refusal [`PartitionSet::open`] lists for the combined
    /// store.
    fn open_combined(
        &self,
        generation: Option<&Generation>,
        protection: Protection,
    ) -> Result<Option<Store>> {
        let combined_dir = self.dir.join(COMBINED_DIR_NAME);
        let present = combined_dir.join(INDEX_FILE_NAME).exists();
        let listed = generation.and_then(|g| g.combined.as_deref());
        if self.layout == Layout::Unpartitioned {
            if present || listed.is_some() {
                return Err(open_error(
                    &self.dir,
                    format!(
                        "an unpartitioned store has no combined store, but {COMBINED_DIR_NAME} \
                         or {GENERATION_FILE_NAME} names one. Remove it, or set \
                         HPDS_COMBINED_STORE=off"
                    ),
                ));
            }
            return Ok(None);
        }
        if self.partitions.len() == 1 {
            return Ok(None);
        }
        let id = match (listed, present) {
            (None, false) => return Ok(None),
            (Some(id), true) => id,
            (Some(_), false) => {
                return Err(open_error(
                    &self.dir,
                    format!(
                        "{GENERATION_FILE_NAME} lists a combined store but {} holds no \
                         {INDEX_FILE_NAME}. Republish the load, or set HPDS_COMBINED_STORE=off",
                        combined_dir.display()
                    ),
                ));
            }
            (None, true) => {
                return Err(open_error(
                    &self.dir,
                    format!(
                        "{} holds a combined store that {GENERATION_FILE_NAME} does not list, \
                         so nothing ties it to these partitions. Republish the load, remove \
                         it with hpds-etl combine --remove, or set HPDS_COMBINED_STORE=off",
                        combined_dir.display()
                    ),
                ));
            }
        };
        u32::try_from(self.partitions.len())
            .map_err(|_| open_error(&self.dir, "too many partitions"))?;
        let files = StoreFiles::open(&combined_dir, protection)?;
        let opened = files.index().store_id().to_string();
        if opened != id {
            return Err(open_error(
                &self.dir,
                format!(
                    "a partially published load: {GENERATION_FILE_NAME} lists combined store \
                     {id} but {} holds {opened}. Wait for the load to finish, or republish it",
                    combined_dir.display()
                ),
            ));
        }
        let entries = self.check_combined(&files)?;
        Ok(Some(files.into_store(entries)))
    }

    /// Refuses a combined store that does not hold exactly the partitions' data: the merged
    /// dictionary's concepts in the same order, each with the merged type and the summed
    /// observation count of its holders, and the union of the partitions' patients.
    /// Returns its concepts' entries, which are in dictionary order.
    ///
    /// # Errors
    /// [`QueryError::Open`] naming the first disagreement, or a concept table that does not
    /// parse.
    fn check_combined(&self, files: &StoreFiles) -> Result<Vec<ConceptEntry>> {
        let refuse = |detail: String| {
            Err(open_error(
                &self.dir.join(COMBINED_DIR_NAME),
                format!(
                    "the combined store disagrees with the partitions: {detail}. Rebuild it \
                     with hpds-etl combine, or set HPDS_COMBINED_STORE=off"
                ),
            ))
        };
        let index_body = files.index();
        if index_body.concept_count() != self.dictionary.len() {
            return refuse(format!(
                "it holds {} concepts, the partitions {}",
                index_body.concept_count(),
                self.dictionary.len()
            ));
        }
        let mut entries = Vec::with_capacity(self.dictionary.len());
        for (index, concept) in index_body.concepts().enumerate() {
            let concept = concept.map_err(|e| open_error(files.dir(), e))?;
            let summary = &self.dictionary[index];
            if concept.path != summary.name {
                return refuse(format!(
                    "concept {:?} differs in path: the combined store holds {:?} in its \
                     place",
                    summary.name, concept.path
                ));
            }
            if concept.concept_type != summary.concept_type() {
                return refuse(format!(
                    "concept {:?} differs in type: {} in the combined store, {} in the \
                     partitions",
                    summary.name,
                    type_name(concept.concept_type),
                    type_name(summary.concept_type())
                ));
            }
            let summed: u64 = self
                .holders_of(index)
                .iter()
                .map(|h| u64::from(self.entry(*h).observation_count))
                .sum();
            if u64::from(concept.observation_count) != summed {
                return refuse(format!(
                    "concept {:?} has {} observations, the partitions {summed}",
                    summary.name, concept.observation_count
                ));
            }
            entries.push(ConceptEntry::of(&concept));
        }
        if index_body.patients() != &self.all_patients {
            return refuse(format!(
                "it holds {} patients, the partitions {}",
                index_body.patients().len(),
                self.all_patients.len()
            ));
        }
        Ok(entries)
    }

    /// The store at `slot`: a partition, or the combined store one past the last partition.
    fn store_at(&self, slot: u32) -> &Store {
        match self.partitions.get(slot as usize) {
            Some(partition) => &partition.store,
            None => self
                .combined
                .as_ref()
                .unwrap_or_else(|| unreachable!("slot {slot} names no opened store")),
        }
    }

    /// The entry of the concept `holder` names, in its store.
    fn entry(&self, holder: Holder) -> ConceptEntry {
        self.store_at(holder.slot).entry(holder.position)
    }

    /// Whether full selections read a combined store.
    pub fn combined_status(&self) -> CombinedStatus {
        match (&self.combined, self.combined_mode) {
            (Some(_), _) => CombinedStatus::InUse,
            (None, CombinedMode::Off) => CombinedStatus::Off,
            (None, CombinedMode::Auto) => CombinedStatus::Absent,
        }
    }

    /// True when no patient id is in two partitions: the partitions' patient counts sum to
    /// the count of their union. Computed at open from the partitions' patient sets.
    pub fn partitions_disjoint(&self) -> bool {
        self.disjoint
    }

    /// True when queries reading two or more partitions from their own stores count cross
    /// count patients per partition: the partitions are disjoint, there is more than one,
    /// and [`PerPartitionCounts::Off`] was not set.
    pub fn counts_per_partition(&self) -> bool {
        self.disjoint
            && self.layout == Layout::Partitioned
            && self.partitions.len() > 1
            && self.per_partition_counts == PerPartitionCounts::Auto
    }

    /// The data directory.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Whether the directory is split by consent.
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

    /// The number of distinct concepts over every partition.
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

    /// The merged dictionary, one entry per concept of any partition, in Java string order
    /// of the paths.
    pub fn dictionary(&self) -> &[SummaryColumnMeta] {
        &self.dictionary
    }

    /// The merged metadata of the concept at exactly `path`.
    pub fn summary(&self, path: &str) -> Option<&SummaryColumnMeta> {
        self.concept_index(path).map(|i| &self.dictionary[i])
    }

    /// The position of the concept at exactly `path` in the merged dictionary.
    fn concept_index(&self, path: &str) -> Option<usize> {
        self.dictionary
            .binary_search_by(|c| java_string_cmp(&c.name, path))
            .ok()
    }

    /// Every merged concept whose path starts with `prefix` (`String.startsWith`), the
    /// concept at `prefix` included.
    pub fn dictionary_with_prefix(&self, prefix: &str) -> &[SummaryColumnMeta] {
        &self.dictionary[self.prefix_range(prefix)]
    }

    /// The positions in the merged dictionary of the concepts whose path starts with
    /// `prefix`, which are contiguous in Java string order.
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

    /// The partitions holding the concept at dictionary position `index`, in slot order.
    fn holders_of(&self, index: usize) -> &[Holder] {
        &self.holders[self.holders_start(index)..self.holder_ends[index] as usize]
    }

    /// True when some partition holds at least one concept, the readiness rule for health
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

    /// Number of cached blocks across all partitions and the combined store, after
    /// applying the cache's pending inserts and evictions.
    pub fn cached_block_count(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache
            .iter()
            .map(|(_, blocks)| blocks.filled() as u64)
            .sum()
    }

    /// Summed weight in bytes of the cached blocks across all partitions, after applying
    /// the cache's pending inserts and evictions.
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

    /// Drops every cached block of every partition.
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
/// A selection reads the partitions it names. The engine's admission step alone may route
/// it to the combined store ([`Selection::routed`]), and a routed selection reads the
/// combined store only while it names every partition: [`Selection::combined_store`]
/// checks that on every read, so no narrower selection can reach the combined rows.
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

    /// True when every partition of the set is selected.
    pub fn is_all(&self) -> bool {
        self.members.len() == self.set.partitions.len()
            && self.mask.len() == self.set.partitions.len()
            && self.mask.iter().all(|&selected| selected)
    }

    /// True when the engine counts this selection's cross count patients per partition and
    /// sums the counts: the set counts per partition
    /// ([`PartitionSet::counts_per_partition`]), two or more partitions are selected, and
    /// the selection reads them, not the combined store.
    pub fn counts_per_partition(&self) -> bool {
        self.set.counts_per_partition() && self.members.len() > 1 && !self.reads_combined()
    }

    /// True when this selection reads the combined store instead of its partitions.
    pub fn reads_combined(&self) -> bool {
        self.combined_slot().is_some()
    }

    /// This selection, routed to the combined store when it selects every partition and
    /// the set has one open. The one place the engine decides to read the combined store;
    /// called only by its admission step, after the consent checks.
    pub(crate) fn routed(mut self) -> Self {
        self.routed_to_combined = self.is_all() && self.set.combined.is_some();
        self
    }

    /// The combined store's slot, when this selection is routed to it and still selects
    /// every partition. The combined store holds every concept at its dictionary position.
    fn combined_slot(&self) -> Option<u32> {
        if !self.routed_to_combined || !self.is_all() {
            return None;
        }
        self.set
            .combined
            .as_ref()
            .map(|_| self.set.partitions.len() as u32)
    }

    /// The union of the selected partitions' patients
    /// (`PartitionedPhenotypicObservationStore.getPatientIds`). Borrowed when one partition
    /// or every partition is selected.
    pub(crate) fn patients(&self) -> Cow<'a, RoaringBitmap> {
        match self.members.as_slice() {
            [] => Cow::Owned(RoaringBitmap::new()),
            [one] => Cow::Borrowed(one.store.patients()),
            _ if self.is_all() => Cow::Borrowed(&self.set.all_patients),
            many => Cow::Owned(many.iter().map(|p| p.store.patients()).union()),
        }
    }

    /// The type of the concept at exactly `path`, or `None` when no selected partition holds
    /// it. Partitions never disagree on a type: [`PartitionSet::open`] refuses that, and the
    /// combined store holds every concept with the merged type.
    pub fn concept_type(&self, path: &str) -> Option<ConceptType> {
        let index = self.set.concept_index(path)?;
        self.selected_holders(index)
            .next()
            .map(|_| self.set.dictionary[index].concept_type())
    }

    /// The holders of the concept at dictionary position `index` whose partition is
    /// selected, in slot order.
    fn selected_holders(&self, index: usize) -> impl Iterator<Item = Holder> + Clone + '_ {
        self.set
            .holders_of(index)
            .iter()
            .copied()
            .filter(|h| self.mask[h.slot as usize])
    }

    /// The block of `path` in each selected partition that holds the concept, in partition
    /// name order. Only the partitions holding the concept are visited, through one cache
    /// lookup for the concept whatever the number of partitions. Blocks not yet cached load
    /// on the rayon pool. A selection routed to the combined store returns the one combined
    /// block, whose rows are those blocks' rows merged. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub(crate) fn blocks(&self, path: &str) -> Result<Vec<Arc<Block>>> {
        let Some(index) = self.set.concept_index(path) else {
            return Ok(Vec::new());
        };
        if let Some(slot) = self.combined_slot() {
            let request = BlockRequest::combined(slot, index);
            return self.load(COMBINED_GROUP, std::iter::once(request), 1);
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
        if let Some(slot) = self.combined_slot() {
            let capacity = range.len();
            let requests = range
                .map(|index| BlockRequest::combined(slot, index))
                .filter(|r| self.set.entry(r.holder).observation_count > 0);
            return self.load(COMBINED_GROUP, requests, capacity);
        }
        let capacity = if range.is_empty() {
            0
        } else {
            self.set.holder_ends[range.end - 1] as usize - self.set.holders_start(range.start)
        };
        let requests = range
            .flat_map(|index| self.selected_requests(index))
            .filter(|r| self.set.entry(r.holder).observation_count > 0);
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

    /// The requests for the blocks of the concept at dictionary position `index` in the
    /// selected partitions holding it, in slot order.
    fn selected_requests(&self, index: usize) -> impl Iterator<Item = BlockRequest> + Clone + '_ {
        self.set
            .holders_of(index)
            .iter()
            .enumerate()
            .filter(|(_, h)| self.mask[h.slot as usize])
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

    /// [`Selection::load`] when some block is not cached: creates the missing cache
    /// entries, loads every missing block on the rayon pool, and inserts each entry it
    /// filled again so the cache weighs its new blocks. Two queries missing the same block
    /// at once may both read it; the first to fill the slot wins.
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
        let loaded = missing
            .par_iter()
            .map(|(_, request, entry)| {
                let block = self.set.store_at(request.holder.slot).read_block(
                    request.holder.position,
                    &self.set.dictionary[request.index].name,
                )?;
                Ok(entry.fill(request.slot, Arc::new(block)))
            })
            .collect::<Result<Vec<_>>>()?;
        let mut reweighed = None;
        for ((position, request, entry), block) in missing.iter().zip(loaded) {
            blocks[*position] = Some(block);
            if reweighed != Some(request.index) {
                self.set
                    .cache
                    .insert((group, request.index as u32), Arc::clone(entry));
                reweighed = Some(request.index);
            }
        }
        Ok(blocks.into_iter().flatten().collect())
    }
}

/// One block a selection reads: the concept's position `index` in the shared concept
/// table, the slot of its cache entry that holds the block, and the store and position it
/// is read from.
#[derive(Debug, Clone, Copy)]
struct BlockRequest {
    index: usize,
    slot: usize,
    holder: Holder,
}

impl BlockRequest {
    /// The request for the combined store's block of the concept at `index`, held by the
    /// store in `store_slot` at the concept's dictionary position.
    fn combined(store_slot: u32, index: usize) -> Self {
        BlockRequest {
            index,
            slot: 0,
            holder: Holder {
                slot: store_slot,
                position: index as u32,
            },
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

fn check_partition_key(partition_dir: &Path, root_key: Option<&str>) -> Result<()> {
    let Some(own) = read_key_text(&partition_dir.join(KEY_FILE_NAME))? else {
        return Ok(());
    };
    if root_key == Some(own.as_str()) {
        return Ok(());
    }
    Err(open_error(
        partition_dir,
        format!(
            "its {KEY_FILE_NAME} differs from the shared key file {}",
            partition_dir
                .parent()
                .unwrap_or(partition_dir)
                .join(KEY_FILE_NAME)
                .display()
        ),
    ))
}

/// Every immediate subdirectory of `dir` holding a store, as (name, path).
///
/// # Errors
/// [`QueryError::Open`] when `dir` cannot be listed, a partition name is not UTF-8, or a
/// subdirectory holds a javabin store but no `pheno.index`.
fn partition_dirs(dir: &Path) -> Result<Vec<(String, PathBuf)>> {
    let entries = std::fs::read_dir(dir).map_err(|e| open_error(dir, e))?;
    let mut found = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| open_error(dir, e))?;
        if entry.file_name().as_encoded_bytes().starts_with(b".") {
            continue;
        }
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        if !path.join(INDEX_FILE_NAME).exists() {
            if let Some(javabin) = JAVABIN_FILE_NAMES
                .iter()
                .find(|name| path.join(name).exists())
            {
                return Err(open_error(
                    &path,
                    format!(
                        "it holds {javabin} but no {INDEX_FILE_NAME}, so it is a half-migrated \
                         partition. Migrate it with hpds-etl migrate, or move it out of the \
                         data directory"
                    ),
                ));
            }
            continue;
        }
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| open_error(&path, "the partition name is not valid UTF-8"))?
            .to_owned();
        found.push((name, path));
    }
    Ok(found)
}

/// Name of the file the ETL writes last into a data directory, listing the store ids of the
/// load it completed.
const GENERATION_FILE_NAME: &str = ".generation";

/// The store ids a `.generation` file lists.
#[derive(Debug, Default)]
struct Generation {
    /// The partitions' ids, lowercase.
    partitions: std::collections::BTreeSet<String>,
    /// The combined store's id, lowercase, from the line tagged `.combined`.
    combined: Option<String>,
}

/// Reads `<dir>/.generation`, or `None` when there is none.
///
/// # Errors
/// [`QueryError::Open`] when the file cannot be read, a line's first token is not a store
/// id, two lines are tagged `.combined`, or a line carries another second token starting
/// with `.` (a store kind this service does not know).
fn read_generation(dir: &Path) -> Result<Option<Generation>> {
    let path = dir.join(GENERATION_FILE_NAME);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(open_error(&path, e)),
    };
    let mut generation = Generation::default();
    for line in text.lines() {
        let mut tokens = line.split_whitespace();
        let Some(token) = tokens.next() else {
            continue;
        };
        if token.len() != 32 || !token.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(open_error(
                &path,
                format!("{token:?} is not a store id of 32 hex digits"),
            ));
        }
        let id = token.to_ascii_lowercase();
        match tokens.next() {
            Some(COMBINED_GENERATION_TAG) => {
                if generation.combined.replace(id).is_some() {
                    return Err(open_error(&path, "it lists more than one combined store"));
                }
            }
            Some(tag) if tag.starts_with('.') => {
                return Err(open_error(
                    &path,
                    format!(
                        "it tags store id {id} with {tag:?}, a store this service does not \
                         know (only {COMBINED_GENERATION_TAG:?} is). The store was written by \
                         a newer ETL; serve it with a matching service"
                    ),
                ));
            }
            _ => {
                generation.partitions.insert(id);
            }
        }
    }
    Ok(Some(generation))
}

/// Refuses a data directory whose `.generation` file names a different set of partition
/// stores than `partitions` hold. Passes when there is no such file. The combined store's
/// line is checked by [`PartitionSet::open`], not here.
///
/// # Errors
/// [`QueryError::Open`] when the listed partition ids and the opened partitions' ids
/// differ.
fn check_generation(
    dir: &Path,
    partitions: &[(&str, hpds_format::StoreId)],
    generation: Option<&Generation>,
) -> Result<()> {
    let Some(generation) = generation else {
        return Ok(());
    };
    let listed = &generation.partitions;
    let opened: std::collections::BTreeMap<String, &str> = partitions
        .iter()
        .map(|(name, id)| (id.to_string(), *name))
        .collect();
    let unlisted: Vec<&str> = opened
        .iter()
        .filter(|(id, _)| !listed.contains(*id))
        .map(|(_, name)| *name)
        .collect();
    let missing = listed.iter().filter(|id| !opened.contains_key(*id)).count();
    if unlisted.is_empty() && missing == 0 {
        return Ok(());
    }
    Err(open_error(
        dir,
        format!(
            "a partially published load: {GENERATION_FILE_NAME} lists {} store ids, {missing} \
             of them not found among the partitions, and the partitions {unlisted:?} are not \
             listed. Wait for the load to finish, or republish it",
            listed.len()
        ),
    ))
}

fn type_name(concept_type: ConceptType) -> &'static str {
    match concept_type {
        ConceptType::Categorical => "categorical",
        ConceptType::Numeric => "numeric",
    }
}

/// A merged dictionary and its concepts' holders, flattened: the holders of the concept at
/// position `i` are `holders[ends[i - 1]..ends[i]]` (from 0 for the first), in slot order.
type MergedDictionary = (Vec<SummaryColumnMeta>, Vec<u32>, Vec<Holder>);

/// One concept of the merged dictionary while the partitions are read.
struct MergingConcept {
    first_slot: u32,
    concept_type: ConceptType,
    width_in_bytes: i32,
    values: Vec<String>,
    min: Option<f64>,
    max: Option<f64>,
    patient_count: u64,
    has_timestamp: bool,
    timestamp_min: Option<i64>,
    timestamp_max: Option<i64>,
    holder_count: u32,
}

impl MergingConcept {
    fn new(concept: &ConceptView<'_>, slot: u32) -> Self {
        MergingConcept {
            first_slot: slot,
            concept_type: concept.concept_type,
            width_in_bytes: concept.width_in_bytes,
            values: Vec::new(),
            min: concept.min,
            max: concept.max,
            patient_count: u64::from(concept.patient_count),
            has_timestamp: concept.has_timestamp,
            timestamp_min: concept.timestamp_min,
            timestamp_max: concept.timestamp_max,
            holder_count: 0,
        }
    }

    /// Folds another partition's copy of the concept in, by the rules of
    /// [`SummaryColumnMeta`]. Category values are merged separately by [`merge_values`].
    fn absorb(&mut self, concept: &ConceptView<'_>) {
        self.width_in_bytes = self.width_in_bytes.max(concept.width_in_bytes);
        self.min = merge_option(self.min, concept.min, java_min);
        self.max = merge_option(self.max, concept.max, java_max);
        self.patient_count += u64::from(concept.patient_count);
        self.has_timestamp |= concept.has_timestamp;
        self.timestamp_min = merge_option(self.timestamp_min, concept.timestamp_min, i64::min);
        self.timestamp_max = merge_option(self.timestamp_max, concept.timestamp_max, i64::max);
    }

    fn finish(self, name: String) -> SummaryColumnMeta {
        SummaryColumnMeta {
            name,
            width_in_bytes: self.width_in_bytes,
            categorical: self.concept_type == ConceptType::Categorical,
            category_values: self.values,
            min: self.min,
            max: self.max,
            patient_count: self.patient_count,
            has_timestamp: self.has_timestamp,
            timestamp_min: self.timestamp_min,
            timestamp_max: self.timestamp_max,
        }
    }
}

/// Merges `incoming` into `values`, which is distinct and in Java string order and stays
/// so. Only values not already present are copied, and when every incoming value is
/// present `values` is left as it is. `incoming` is sorted and deduplicated in place.
fn merge_values(values: &mut Vec<String>, incoming: &mut Vec<&str>) {
    incoming.sort_by(|a, b| java_string_cmp(a, b));
    incoming.dedup();
    let mut cursor = 0;
    let mut added = 0;
    for value in incoming.iter() {
        while cursor < values.len() && java_string_cmp(&values[cursor], value).is_lt() {
            cursor += 1;
        }
        if values.get(cursor).is_none_or(|present| present != value) {
            added += 1;
        }
    }
    if added == 0 {
        return;
    }
    let mut merged = Vec::with_capacity(values.len() + added);
    let mut present = std::mem::take(values).into_iter().peekable();
    for value in incoming.iter() {
        while let Some(before) = present.next_if(|p| java_string_cmp(p, value).is_lt()) {
            merged.push(before);
        }
        match present.next_if(|p| p == value) {
            Some(same) => merged.push(same),
            None => merged.push((*value).to_owned()),
        }
    }
    merged.extend(present);
    *values = merged;
}

/// Builds the merged dictionary (`createAllPartitionMetaStore`) one partition at a time, so
/// no partition's index body outlives its own merge. Each path and category value is held
/// once, however many partitions hold it; each partition keeps only its table of
/// [`ConceptEntry`]s, and [`DictionaryMerger::finish`] links each concept to its holders.
#[derive(Default)]
struct DictionaryMerger {
    positions: std::collections::HashMap<Box<str>, u32>,
    concepts: Vec<MergingConcept>,
    partition_names: Vec<String>,
    concept_ids: Vec<Vec<u32>>,
}

impl DictionaryMerger {
    /// Merges the concept table of the partition `name` at `slot`, which must be the next
    /// slot, and returns its entries in index order.
    ///
    /// # Errors
    /// [`QueryError::TypeConflict`] when it types a concept differently from an earlier
    /// partition, and [`QueryError::Open`] when its concept table does not parse.
    fn add(&mut self, slot: u32, name: &str, files: &StoreFiles) -> Result<Vec<ConceptEntry>> {
        debug_assert_eq!(slot as usize, self.partition_names.len());
        self.partition_names.push(name.to_owned());
        let index = files.index();
        let mut entries = Vec::with_capacity(index.concept_count());
        let mut ids = Vec::with_capacity(index.concept_count());
        let mut incoming: Vec<&str> = Vec::new();
        for concept in index.concepts() {
            let concept = concept.map_err(|e| open_error(files.dir(), e))?;
            let id = match self.positions.get(concept.path) {
                Some(&id) => {
                    let merging = &mut self.concepts[id as usize];
                    if merging.concept_type != concept.concept_type {
                        return Err(QueryError::TypeConflict {
                            concept_path: concept.path.to_owned(),
                            first_partition: self.partition_names[merging.first_slot as usize]
                                .clone(),
                            first_type: type_name(merging.concept_type),
                            second_partition: name.to_owned(),
                            second_type: type_name(concept.concept_type),
                        });
                    }
                    merging.absorb(&concept);
                    id
                }
                None => {
                    let id = self.concepts.len() as u32;
                    self.positions.insert(concept.path.into(), id);
                    self.concepts.push(MergingConcept::new(&concept, slot));
                    id
                }
            };
            let merging = &mut self.concepts[id as usize];
            merging.holder_count += 1;
            if let Some(values) = concept.category_values {
                incoming.clear();
                incoming.extend(values.iter());
                merge_values(&mut merging.values, &mut incoming);
            }
            ids.push(id);
            entries.push(ConceptEntry::of(&concept));
        }
        self.concept_ids.push(ids);
        Ok(entries)
    }

    /// The merged dictionary in Java string order of the paths, with each concept's
    /// holders in slot order.
    fn finish(self) -> MergedDictionary {
        let mut names: Vec<(Box<str>, u32)> = self.positions.into_iter().collect();
        names.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        let mut rank = vec![0u32; self.concepts.len()];
        for (sorted, (_, id)) in names.iter().enumerate() {
            rank[*id as usize] = sorted as u32;
        }
        let mut concepts: Vec<Option<MergingConcept>> =
            self.concepts.into_iter().map(Some).collect();
        let mut dictionary = Vec::with_capacity(names.len());
        let mut ends = Vec::with_capacity(names.len());
        let mut end = 0u32;
        for (name, id) in names {
            let concept = concepts[id as usize]
                .take()
                .unwrap_or_else(|| unreachable!("each concept id is named once"));
            end += concept.holder_count;
            ends.push(end);
            dictionary.push(concept.finish(name.into_string()));
        }
        drop(concepts);
        let mut next: Vec<u32> = std::iter::once(0)
            .chain(ends.iter().copied())
            .take(ends.len())
            .collect();
        let mut holders = vec![
            Holder {
                slot: 0,
                position: 0,
            };
            end as usize
        ];
        for (slot, ids) in self.concept_ids.iter().enumerate() {
            for (position, id) in ids.iter().enumerate() {
                let cursor = &mut next[rank[*id as usize] as usize];
                holders[*cursor as usize] = Holder {
                    slot: slot as u32,
                    position: position as u32,
                };
                *cursor += 1;
            }
        }
        (dictionary, ends, holders)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_min_and_max_follow_math_rules() {
        assert!(java_min(f64::NAN, 1.0).is_nan());
        assert!(java_max(1.0, f64::NAN).is_nan());
        assert!(java_min(0.0, -0.0).is_sign_negative());
        assert!(java_max(-0.0, 0.0).is_sign_positive());
        assert_eq!(java_min(2.0, 1.0), 1.0);
        assert_eq!(java_max(2.0, 1.0), 2.0);
        assert_eq!(merge_option(None, Some(3), i64::min), Some(3));
        assert_eq!(merge_option(Some(4), None, i64::min), Some(4));
        assert_eq!(merge_option::<i64>(None, None, i64::min), None);
    }

    #[test]
    fn merge_values_keeps_distinct_values_in_java_order_and_copies_only_new_ones() {
        let mut values = Vec::new();
        merge_values(&mut values, &mut vec!["b", "a", "b"]);
        assert_eq!(values, ["a", "b"]);
        let first = values[0].as_ptr();
        merge_values(&mut values, &mut vec!["b", "a"]);
        assert_eq!(values, ["a", "b"]);
        assert_eq!(
            values[0].as_ptr(),
            first,
            "nothing new leaves the values as they were"
        );
        merge_values(
            &mut values,
            &mut vec!["c", "\u{e000}", "\u{1f600}", "a", "A"],
        );
        assert_eq!(values, ["A", "a", "b", "c", "\u{1f600}", "\u{e000}"]);
        assert_eq!(
            values[1].as_ptr(),
            first,
            "values already present are moved, not copied"
        );
        let mut expected: Vec<String> = values.clone();
        expected.sort_by(|a, b| java_string_cmp(a, b));
        assert_eq!(values, expected);
        merge_values(&mut values, &mut Vec::new());
        assert_eq!(values.len(), 6);
    }

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
