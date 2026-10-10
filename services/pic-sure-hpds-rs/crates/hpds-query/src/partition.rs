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

use hpds_format::{
    Block, ConceptMeta, ConceptType, CryptoKey, INDEX_FILE_NAME, Protection, java_double_cmp,
    java_double_to_string, java_string_cmp,
};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde_json::Value;

use crate::error::{QueryError, Result};
use crate::model::Query;
use crate::store::{BlockCache, KEY_FILE_NAME, Store, StoreOptions, block_cache};
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
}

impl Default for PartitionOptions {
    /// The default store options, consents required (the Java default), and the combined
    /// store used when present.
    fn default() -> Self {
        PartitionOptions {
            store: StoreOptions::default(),
            require_consents: true,
            combined: CombinedMode::Auto,
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

/// Where one store keeps one concept: the store's slot (a partition's position in
/// [`PartitionSet`] order, or one past the last partition for the combined store) and the
/// concept's position in that store's index, which together are the block's cache key.
#[derive(Debug, Clone, Copy)]
struct Holder {
    slot: u32,
    position: u32,
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
            patient_count: u64::from(concept.patient_count),
            has_timestamp: concept.has_timestamp,
            timestamp_min: concept.timestamp_min,
            timestamp_max: concept.timestamp_max,
        }
    }

    /// Folds another partition's metadata for the same concept into this one. Category
    /// values are appended; [`SummaryColumnMeta::finish`] sorts and deduplicates them.
    fn absorb(&mut self, concept: &ConceptMeta) {
        self.width_in_bytes = self.width_in_bytes.max(concept.width_in_bytes);
        self.category_values
            .extend(concept.category_values.iter().flatten().cloned());
        self.min = merge_option(self.min, concept.min, java_min);
        self.max = merge_option(self.max, concept.max, java_max);
        self.patient_count += u64::from(concept.patient_count);
        self.has_timestamp |= concept.has_timestamp;
        self.timestamp_min = merge_option(self.timestamp_min, concept.timestamp_min, i64::min);
        self.timestamp_max = merge_option(self.timestamp_max, concept.timestamp_max, i64::max);
    }

    fn finish(&mut self) {
        self.category_values.sort_by(|a, b| java_string_cmp(a, b));
        self.category_values.dedup();
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
    /// present refuses the open. [`CombinedMode::Off`] skips all of this.
    ///
    /// The key is `<dir>/encryption_key`, shared by every partition. A partition may carry
    /// its own `encryption_key` only if it holds the same key. With no root key the
    /// partitions open as plain stores, and only if `options.store.allow_plain` is set.
    /// Partitions are kept in Java string order of their names.
    ///
    /// # Errors
    /// [`QueryError::Open`] when the directory cannot be listed, holds no store (Java starts
    /// empty instead), holds a subdirectory with a javabin file but no `pheno.index`, holds a
    /// `.generation` file that cannot be read or does not match the opened stores' ids, is
    /// unpartitioned while `options.require_consents` is set, a partition's key file
    /// differs from the shared key, a store cannot be opened (its index or blocks file is
    /// missing, corrupt, from another store, or protected differently from the key), or the
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
        let (layout, partitions) = if dir.join(INDEX_FILE_NAME).exists() {
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
            let store = Store::open_shared(dir, protection.clone(), cache.clone(), 0)?;
            let partition = Partition {
                name: UNPARTITIONED_NAME.to_owned(),
                store,
            };
            (Layout::Unpartitioned, vec![partition])
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
            let partitions = found
                .into_par_iter()
                .enumerate()
                .map(|(slot, (name, path))| {
                    let store =
                        Store::open_shared(&path, protection.clone(), cache.clone(), slot as u32)?;
                    Ok(Partition { name, store })
                })
                .collect::<Result<Vec<_>>>()?;
            (Layout::Partitioned, partitions)
        };
        let generation = read_generation(dir)?;
        check_generation(dir, &partitions, generation.as_ref())?;
        let (dictionary, holder_ends, holders) = merge_dictionaries(&partitions)?;
        let all_patients = partitions
            .iter()
            .map(|p| p.store.index().patients())
            .union();
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
            cache,
        };
        if options.combined == CombinedMode::Auto {
            set.combined = set.open_combined(generation.as_ref(), protection)?;
        }
        Ok(set)
    }

    /// The combined store [`PartitionSet::open`] describes, or `None` when the directory has
    /// none.
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
        let slot = u32::try_from(self.partitions.len())
            .map_err(|_| open_error(&self.dir, "too many partitions"))?;
        let store = Store::open_shared(&combined_dir, protection, self.cache.clone(), slot)?;
        let opened = store.index().store_id().to_string();
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
        self.check_combined(&store)?;
        Ok(Some(store))
    }

    /// Refuses a combined store that does not hold exactly the partitions' data: the merged
    /// dictionary's concepts in the same order, each with the merged type and the summed
    /// observation count of its holders, and the union of the partitions' patients.
    ///
    /// # Errors
    /// [`QueryError::Open`] naming the first disagreement.
    fn check_combined(&self, store: &Store) -> Result<()> {
        let refuse = |detail: String| {
            Err(open_error(
                &self.dir.join(COMBINED_DIR_NAME),
                format!(
                    "the combined store disagrees with the partitions: {detail}. Rebuild it \
                     with hpds-etl combine, or set HPDS_COMBINED_STORE=off"
                ),
            ))
        };
        let concepts = store.index().concepts();
        if concepts.len() != self.dictionary.len() {
            return refuse(format!(
                "it holds {} concepts, the partitions {}",
                concepts.len(),
                self.dictionary.len()
            ));
        }
        for (index, (concept, summary)) in concepts.iter().zip(&self.dictionary).enumerate() {
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
                .map(|h| u64::from(self.partition_concept(*h).observation_count))
                .sum();
            if u64::from(concept.observation_count) != summed {
                return refuse(format!(
                    "concept {:?} has {} observations, the partitions {summed}",
                    summary.name, concept.observation_count
                ));
            }
        }
        if store.index().patients() != &self.all_patients {
            return refuse(format!(
                "it holds {} patients, the partitions {}",
                store.index().patients().len(),
                self.all_patients.len()
            ));
        }
        Ok(())
    }

    /// The metadata of a partition's concept.
    fn partition_concept(&self, holder: Holder) -> &ConceptMeta {
        &self.partitions[holder.slot as usize]
            .store
            .index()
            .concepts()[holder.position as usize]
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

    /// Whether full selections read a combined store.
    pub fn combined_status(&self) -> CombinedStatus {
        match (&self.combined, self.combined_mode) {
            (Some(_), _) => CombinedStatus::InUse,
            (None, CombinedMode::Off) => CombinedStatus::Off,
            (None, CombinedMode::Auto) => CombinedStatus::Absent,
        }
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
        let members = self
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

    /// Number of cached blocks across all partitions, after applying the cache's pending
    /// inserts and evictions.
    pub fn cached_block_count(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache.entry_count()
    }

    /// Summed weight in bytes of the cached blocks across all partitions, after applying
    /// the cache's pending inserts and evictions.
    pub fn cached_bytes(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache.weighted_size()
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

    /// True when this selection reads the combined store instead of its partitions.
    pub fn reads_combined(&self) -> bool {
        self.combined_store().is_some()
    }

    /// This selection, routed to the combined store when it selects every partition and
    /// the set has one open. The one place the engine decides to read the combined store;
    /// called only by its admission step, after the consent checks.
    pub(crate) fn routed(mut self) -> Self {
        self.routed_to_combined = self.is_all() && self.set.combined.is_some();
        self
    }

    /// The combined store and its slot, when this selection is routed to it and still
    /// selects every partition.
    fn combined_store(&self) -> Option<(&'a Store, u32)> {
        if !self.routed_to_combined || !self.is_all() {
            return None;
        }
        self.set
            .combined
            .as_ref()
            .map(|store| (store, self.set.partitions.len() as u32))
    }

    /// The union of the selected partitions' patients
    /// (`PartitionedPhenotypicObservationStore.getPatientIds`). Borrowed when one partition
    /// or every partition is selected.
    pub(crate) fn patients(&self) -> Cow<'a, RoaringBitmap> {
        match self.members.as_slice() {
            [] => Cow::Owned(RoaringBitmap::new()),
            [one] => Cow::Borrowed(one.store.index().patients()),
            _ if self.is_all() => Cow::Borrowed(&self.set.all_patients),
            many => Cow::Owned(many.iter().map(|p| p.store.index().patients()).union()),
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

    fn concept(&self, holder: Holder) -> &'a ConceptMeta {
        &self.set.store_at(holder.slot).index().concepts()[holder.position as usize]
    }

    /// The block of `path` in each selected partition that holds the concept, in partition
    /// name order. Only the partitions holding the concept are visited. When every block
    /// is cached they are returned without spawning work; otherwise all of them load on the
    /// rayon pool, where the cached ones return at once. A selection routed to the combined
    /// store returns the one combined block, whose rows are those blocks' rows merged.
    /// Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub(crate) fn blocks(&self, path: &str) -> Result<Vec<Arc<Block>>> {
        let Some(index) = self.set.concept_index(path) else {
            return Ok(Vec::new());
        };
        if let Some((_, slot)) = self.combined_store() {
            let holder = Holder {
                slot,
                position: index as u32,
            };
            return self.load(std::iter::once(holder), 1);
        }
        self.load(
            self.selected_holders(index),
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
        if let Some((store, slot)) = self.combined_store() {
            let concepts = store.index().concepts();
            let capacity = range.len();
            let holders = range
                .filter(|&index| concepts[index].observation_count > 0)
                .map(|index| Holder {
                    slot,
                    position: index as u32,
                });
            return self.load(holders, capacity);
        }
        let capacity = if range.is_empty() {
            0
        } else {
            self.set.holder_ends[range.end - 1] as usize - self.set.holders_start(range.start)
        };
        let holders = range
            .flat_map(|index| self.selected_holders(index))
            .filter(|&h| self.concept(h).observation_count > 0);
        self.load(holders, capacity)
    }

    /// The number of concepts whose path starts with `prefix` that a selected partition
    /// holds, counting no further than `limit`.
    pub(crate) fn count_with_prefix(&self, prefix: &str, limit: usize) -> usize {
        let range = self.set.prefix_range(prefix);
        if self.is_all() {
            return range.len().min(limit);
        }
        range
            .filter(|&index| self.selected_holders(index).next().is_some())
            .take(limit)
            .count()
    }

    /// The blocks at `holders`, in order. Reads the shared cache first, and on the first
    /// miss loads every holder on the rayon pool instead.
    fn load(
        &self,
        holders: impl Iterator<Item = Holder> + Clone,
        capacity: usize,
    ) -> Result<Vec<Arc<Block>>> {
        let mut blocks = Vec::with_capacity(capacity);
        for holder in holders.clone() {
            match self.set.cache.get(&(holder.slot, holder.position)) {
                Some(block) => blocks.push(block),
                None => return self.load_all(holders),
            }
        }
        Ok(blocks)
    }

    fn load_all(&self, holders: impl Iterator<Item = Holder>) -> Result<Vec<Arc<Block>>> {
        let holders: Vec<Holder> = holders.collect();
        holders
            .par_iter()
            .map(|h| self.set.store_at(h.slot).block_at(h.position as usize))
            .collect()
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
    partitions: &[Partition],
    generation: Option<&Generation>,
) -> Result<()> {
    let Some(generation) = generation else {
        return Ok(());
    };
    let listed = &generation.partitions;
    let opened: std::collections::BTreeMap<String, &str> = partitions
        .iter()
        .map(|p| (p.store.index().store_id().to_string(), p.name.as_str()))
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

/// The merged dictionary of `partitions` (`createAllPartitionMetaStore`), in Java string
/// order, with each concept's holders, refusing a concept typed differently in two
/// partitions.
fn merge_dictionaries(partitions: &[Partition]) -> Result<MergedDictionary> {
    let mut merged: Vec<(SummaryColumnMeta, &str, Vec<Holder>)> = Vec::new();
    let mut positions: std::collections::HashMap<&str, usize> = std::collections::HashMap::new();
    for (slot, partition) in partitions.iter().enumerate() {
        for (position, concept) in partition.store.index().concepts().iter().enumerate() {
            let holder = Holder {
                slot: slot as u32,
                position: position as u32,
            };
            match positions.get(concept.path.as_str()) {
                Some(&i) => {
                    let (summary, first_partition, holders) = &mut merged[i];
                    if summary.concept_type() != concept.concept_type {
                        return Err(QueryError::TypeConflict {
                            concept_path: concept.path.clone(),
                            first_partition: (*first_partition).to_owned(),
                            first_type: type_name(summary.concept_type()),
                            second_partition: partition.name.clone(),
                            second_type: type_name(concept.concept_type),
                        });
                    }
                    summary.absorb(concept);
                    holders.push(holder);
                }
                None => {
                    positions.insert(&concept.path, merged.len());
                    merged.push((
                        SummaryColumnMeta::from_concept(concept),
                        partition.name.as_str(),
                        vec![holder],
                    ));
                }
            }
        }
    }
    merged.sort_by(|a, b| java_string_cmp(&a.0.name, &b.0.name));
    let mut dictionary = Vec::with_capacity(merged.len());
    let mut ends = Vec::with_capacity(merged.len());
    let mut holders = Vec::new();
    for (mut summary, _, concept_holders) in merged {
        summary.finish();
        dictionary.push(summary);
        holders.extend(concept_holders);
        ends.push(holders.len() as u32);
    }
    Ok((dictionary, ends, holders))
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
