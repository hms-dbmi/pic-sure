//! Consent partitions: `PartitionedPhenotypicObservationStore`.
//!
//! A data directory holds one store. A partitioned store records its consent partitions in
//! its index, and each concept's block holds every partition's rows. A [`PartitionSet`]
//! opens it, keeps its dictionary as one [`SummaryColumnMeta`] per concept, and picks the
//! partitions a query may read ([`PartitionSet::select`]).
//!
//! A query whose consents select every partition reads each concept's whole block. A
//! narrower [`Selection`] reads the same blocks, concept by concept: when its partitions
//! hold at least [`NARROW_VIEW_SHARE`] of the concept's rows it evaluates the whole block,
//! and the engine keeps only the patients the selection reaches; below that share it reads
//! the concept's partition view ([`crate::view`]), built on first use and cached with the
//! block, so the work follows the selected rows.

use std::borrow::Cow;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::Instant;

use hpds_format::{
    Block, ConceptMeta, ConceptType, CryptoKey, INDEX_FILE_NAME, PUBLISH_JOURNAL_FILE_NAME,
    PartitionHolder, Protection, java_double_to_string, java_string_cmp,
};
use moka::ops::compute::Op;
use moka::sync::Cache;
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde_json::Value;

use crate::counters::{BlockCacheCounters, BlockCacheStats};
use crate::error::{QueryError, Result};
use crate::model::Query;
use crate::store::{
    BlockCache, CachedConcept, ConceptEntry, KEY_FILE_NAME, RECORD_EVERY, RecentEntries,
    ServingLock, Store, StoreFiles, StoreOptions, block_cache,
};
use crate::validate::ConceptLookup;
use crate::view::View;

/// Name of the single partition of an unpartitioned data directory. It never matches a
/// consent, as in Java.
pub const UNPARTITIONED_NAME: &str = "__unpartitioned__";

/// Name of the combined store's directory in a partitioned data directory of format 1. A
/// data directory holding one, or partition directories, holds a store `hpds-etl convert`
/// must convert first.
pub const COMBINED_DIR_NAME: &str = ".combined";

/// The share of a concept's rows below which a narrow selection reads the concept's
/// partition view instead of its whole block: the selected partitions' rows divided by all
/// of its rows. At or above it, evaluating the whole block and dropping the patients outside
/// the selection costs less than reading the selected rows one partition at a time. Chosen
/// by measurement on a store of 40 partitions over shares from 0.25 to 1: the view is faster
/// up to about three quarters of the rows, and slower above.
pub const NARROW_VIEW_SHARE: f64 = 0.75;

/// Bytes of selection patient sets [`PartitionSet`] keeps for repeated selections.
const SCOPE_CACHE_BYTES: u64 = 64 << 20;

/// How many times smaller than the patient set it is checked against a narrow selection's
/// patient set must be to be walked through the route table instead of compared as bitmaps:
/// for the member check, and for dropping the patients a selection does not reach. Only a
/// sparse set is walked ([`is_sparse`]); a dense one is compared as bitmaps.
const ROUTE_WALK_RATIO: u64 = 4;

/// True when most of `patients`' containers hold arrays rather than bitsets. Roaring
/// intersects an array with a bitset in time proportional to the array, and two arrays in
/// time proportional to both, so a set of many patients in array containers is cheaper to
/// test a small set against through the route table, one lookup per patient.
fn is_sparse(patients: &RoaringBitmap) -> bool {
    let statistics = patients.statistics();
    statistics.n_array_containers * 2 > statistics.n_containers
}

/// How a [`PartitionSet`] is opened.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PartitionOptions {
    /// Cache budget and key rules. `cache_bytes` bounds the one cache of blocks and their
    /// partition views.
    pub store: StoreOptions,
    /// The Java property `hpds.requireAuthorizationFilter`. When set, an unpartitioned data
    /// directory is refused at open, and every query must carry at least one consent value.
    /// The [`PartitionSet`] keeps it, and [`crate::Engine::validate`] reads it from there.
    pub require_consents: bool,
    /// The share of a concept's rows below which a narrow selection reads partition views.
    /// [`NARROW_VIEW_SHARE`] unless a test or a measurement sets another: 0 reads whole
    /// blocks for every concept, and any share above 1 reads views for every concept a
    /// narrow selection does not hold entirely. The answers are the same.
    #[doc(hidden)]
    pub narrow_view_share: f64,
}

impl Default for PartitionOptions {
    /// The default store options, consents required (the Java default), and
    /// [`NARROW_VIEW_SHARE`].
    fn default() -> Self {
        PartitionOptions {
            store: StoreOptions::default(),
            require_consents: true,
            narrow_view_share: NARROW_VIEW_SHARE,
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

/// One consent partition. Only its name is public; its data is read through the engine.
#[derive(Debug)]
pub struct Partition {
    name: String,
    patients: RoaringBitmap,
    tagged: RoaringBitmap,
    sparse: bool,
}

impl Partition {
    /// The partition name, or [`UNPARTITIONED_NAME`].
    pub fn name(&self) -> &str {
        &self.name
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

/// The store of a data directory and its consent partitions, with one cache of decoded
/// blocks and their partition views. Share it behind an `Arc`; every method takes `&self`.
///
/// Its public methods read only the dictionary, the partition names and the cache counters,
/// for search and health checks. Patient data is read only by the [`crate::Engine`], through
/// a [`Selection`] that [`PartitionSet::select`] builds from the query's consents, so every
/// read is scoped by consent. Neither a selection's blocks nor the store can be reached
/// from outside the crate:
///
/// ```compile_fail
/// fn blocks(selection: &hpds_query::Selection<'_>) {
///     let _ = selection.entry(&selection.concept("\\a\\").unwrap());
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
    overlap: RoaringBitmap,
    empty: RoaringBitmap,
    store: Store,
    narrow_view_share: f64,
    evaluating: AtomicUsize,
    cache: BlockCache,
    recent: Arc<RecentEntries>,
    turns: AtomicUsize,
    cache_capacity: u64,
    cache_counters: Arc<BlockCacheCounters>,
    guard_refusals: AtomicU64,
    scopes: Cache<Box<[u64]>, Arc<Scope>>,
    generation: u128,
    serving: ServingLock,
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
            .field("overlap", &self.overlap.len())
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
    /// being published. A directory holding [`PUBLISH_JOURNAL_FILE_NAME`], the journal of an
    /// `hpds-etl` publish that was interrupted, is refused until `hpds-etl recover` finishes
    /// it; the service never writes the data directory itself.
    ///
    /// The set holds a shared `flock` on the `pheno.index` it opened for as long as it lives
    /// ([`ServingLock`]), which makes the `hpds-etl` commands that replace store files refuse
    /// to run, and waits for one that is publishing to finish before reading.
    ///
    /// # Errors
    /// [`QueryError::Open`] for every refusal above, a key file that cannot be read, and a
    /// store whose index or blocks file is missing, corrupt, from another store, or
    /// protected differently from the key.
    pub fn open(dir: &Path, options: &PartitionOptions) -> Result<Self> {
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
        let (mut serving, index_bytes) = ServingLock::open(dir)?;
        if std::fs::symlink_metadata(dir.join(PUBLISH_JOURNAL_FILE_NAME)).is_ok() {
            return Err(open_error(
                dir,
                format!(
                    "it holds {PUBLISH_JOURNAL_FILE_NAME}: an hpds-etl command was interrupted \
                     while replacing the store files, which may not match. Run hpds-etl \
                     recover --store <this directory>, which finishes or rolls back the \
                     publish or says why it can do neither, then restart the service"
                ),
            ));
        }
        let files = StoreFiles::open(dir, &index_bytes, protection)?;
        drop(index_bytes);
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
        serving.bind(
            store_id,
            &store.blocks_metadata().map_err(|e| open_error(dir, e))?,
        );
        let partitions: Vec<Partition> = if partitioned {
            table
                .partitions
                .into_iter()
                .map(|p| Partition {
                    name: p.name,
                    sparse: is_sparse(&p.patients),
                    patients: p.patients,
                    tagged: p.tagged,
                })
                .collect()
        } else {
            vec![Partition {
                name: UNPARTITIONED_NAME.to_owned(),
                patients: store.patients().clone(),
                tagged: RoaringBitmap::new(),
                sparse: is_sparse(store.patients()),
            }]
        };
        let all_patients = store.patients().clone();
        let generation = xxhash_rust::xxh3::xxh3_128(store_id.as_bytes());
        let cache_counters = Arc::new(BlockCacheCounters::default());
        let recent = Arc::new(RecentEntries::new(dictionary.len()));
        Ok(PartitionSet {
            dir: dir.to_path_buf(),
            layout: if partitioned {
                Layout::Partitioned
            } else {
                Layout::Unpartitioned
            },
            require_consents: options.require_consents,
            partitions,
            cache: block_cache(
                options.store.cache_bytes,
                Arc::clone(&cache_counters),
                Arc::clone(&recent),
            ),
            recent,
            turns: AtomicUsize::new(0),
            dictionary,
            holder_ends,
            holders,
            index_holders: partitioned,
            all_patients,
            overlap: table.overlap,
            empty: RoaringBitmap::new(),
            store,
            narrow_view_share: options.narrow_view_share,
            evaluating: AtomicUsize::new(0),
            cache_capacity: options.store.cache_bytes,
            cache_counters,
            guard_refusals: AtomicU64::new(0),
            scopes: Cache::<Box<[u64]>, Arc<Scope>>::builder()
                .max_capacity(SCOPE_CACHE_BYTES)
                .weigher(|key, scope| scope_weight(key, scope))
                .build(),
            generation,
            serving,
        })
    }

    /// Fails once what this set serves may no longer be the store it opened (see
    /// [`ServingLock::check`]): a file it holds open changed in place, a publish into the
    /// directory began, or `pheno.index` was replaced within the same store. A whole store of
    /// another id moved in does not fail it; the set goes on serving its own open files. Every
    /// computation of the engine checks this first, and the service checks it on every
    /// route that reads the store.
    ///
    /// # Errors
    /// [`QueryError::StoreChanged`].
    pub fn check_unchanged(&self) -> Result<()> {
        self.serving.check(|| self.store.blocks_metadata())
    }

    /// True once another store (of another id) was moved into the data directory while
    /// this set goes on serving the store it opened from its open files. Health stays UP;
    /// the service reports it so orchestration can see a restart is due.
    pub fn store_superseded(&self) -> bool {
        self.serving.is_superseded()
    }

    /// A hash of the store id. Another load of the data directory gives another value.
    pub(crate) fn generation(&self) -> u128 {
        self.generation
    }

    /// The number of patients in the overlap set: patients in two or more partitions'
    /// patient sets, or with a row in a partition whose set lacks them. Their rows are read
    /// by partition tag. 0 for an unpartitioned store.
    pub fn overlap_patients(&self) -> u64 {
        self.overlap.len()
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

    /// The partitions holding the concept at dictionary position `index`, in partition
    /// order. An unpartitioned store's one partition holds every concept.
    fn holders_of(&self, index: usize) -> &[PartitionHolder] {
        let start = index
            .checked_sub(1)
            .map_or(0, |previous| self.holder_ends[previous] as usize);
        &self.holders[start..self.holder_ends[index] as usize]
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
        let full = members.len() == self.partitions.len();
        Ok(Selection {
            set: self,
            members,
            mask,
            full,
            scope: OnceLock::new(),
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

    /// Number of cached blocks, after applying the cache's pending inserts and evictions.
    pub fn cached_block_count(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache.entry_count()
    }

    /// Summed weight in bytes of the cached blocks and partition views, after applying the
    /// cache's pending inserts and evictions.
    pub fn cached_bytes(&self) -> u64 {
        self.cache.run_pending_tasks();
        self.cache.weighted_size()
    }

    /// The number of cached partition views and their summed weight in bytes, after
    /// applying the cache's pending inserts and evictions. Walks the cache.
    pub fn cached_views(&self) -> (u64, u64) {
        self.cache.run_pending_tasks();
        self.cache
            .iter()
            .filter_map(|(_, concept)| concept.view().map(|view| view.heap_size() as u64))
            .fold((0, 0), |(count, bytes), size| (count + 1, bytes + size))
    }

    /// The block cache's counters and what it holds, after applying its pending inserts
    /// and evictions. Walks the cache to weigh the partition views, so its cost grows with
    /// the cached concepts; meant for a metrics scrape, not a query.
    pub fn block_cache_stats(&self) -> BlockCacheStats {
        let (views, view_bytes) = self.cached_views();
        let counters = &self.cache_counters;
        BlockCacheStats {
            hits: counters.hits.get(),
            misses: counters.misses.get(),
            evictions: counters.evictions.load(Ordering::Relaxed),
            loads: counters.loads.load(Ordering::Relaxed),
            load_failures: counters.load_failures.load(Ordering::Relaxed),
            load_seconds: counters.load_nanos.load(Ordering::Relaxed) as f64 / 1e9,
            entries: self.cache.entry_count(),
            bytes: self.cache.weighted_size(),
            capacity: self.cache_capacity,
            views,
            view_bytes,
        }
    }

    /// Queries refused because a cohort held patients outside the selected partitions,
    /// since the store was opened.
    pub fn cohort_guard_refusals(&self) -> u64 {
        self.guard_refusals.load(Ordering::Relaxed)
    }

    /// Counts one query refused by the cohort guard.
    pub(crate) fn count_guard_refusal(&self) {
        self.guard_refusals.fetch_add(1, Ordering::Relaxed);
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

    /// Drops every cached block and view.
    pub fn clear_cache(&self) {
        self.cache.invalidate_all();
        self.cache.run_pending_tasks();
        self.recent.clear();
    }

    /// Reads and checks the block of the concept at `index` from the store, uncached.
    /// Blocking.
    fn read_block(&self, index: usize) -> Result<Block> {
        self.store.read_block(
            index as u32,
            &self.dictionary[index].name,
            self.index_holders_of(index),
        )
    }

    /// The cached entry of the concept at `index`, read and cached when missing. Two
    /// queries missing it at once read it once. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read.
    fn entry_at(&self, index: usize) -> Result<Arc<CachedConcept>> {
        match self.cached_entry(index) {
            Some(entry) => Ok(entry),
            None => self.load_entry(index),
        }
    }

    /// The cached entry of the concept at `index`, if any, counting a hit or a miss.
    fn cached_entry(&self, index: usize) -> Option<Arc<CachedConcept>> {
        let found = self.cache.get(&(index as u32));
        let counter = if found.is_some() {
            &self.cache_counters.hits
        } else {
            &self.cache_counters.misses
        };
        counter.add(1);
        found
    }

    /// The cached entries of the concepts at `indexes`, in order, counting a hit or a miss
    /// for each. Each call draws a turn ([`turn_of`]); the concepts whose index falls on it
    /// (one in [`RECORD_EVERY`]) are looked up in the cache, the others come from
    /// [`RecentEntries`] when it holds them, and from the cache otherwise. Every entry
    /// found in the cache is remembered there.
    fn cached_entries(&self, indexes: &[usize]) -> Vec<Option<Arc<CachedConcept>>> {
        let turn = turn_of(self.turns.fetch_add(1, Ordering::Relaxed));
        let mut entries = self.recent.get_all(indexes.iter().copied(), |index| {
            index % RECORD_EVERY == turn
        });
        if crate::cohort::fault::checks_recent_hits() {
            let dropped_when_served: Vec<bool> = entries
                .iter()
                .map(|entry| entry.as_ref().is_some_and(|served| served.is_dropped()))
                .collect();
            for ((entry, &index), dropped_when_served) in
                entries.iter().zip(indexes).zip(dropped_when_served)
            {
                if let Some(served) = entry {
                    let held = self
                        .cache
                        .iter()
                        .any(|(key, held)| *key == index as u32 && Arc::ptr_eq(&held, served));
                    let cached = !dropped_when_served && (held || served.is_dropped());
                    crate::cohort::fault::note_recent_hit(cached);
                }
            }
        }
        let recent_hits = entries.iter().filter(|entry| entry.is_some()).count();
        self.cache_counters.hits.add(recent_hits as u64);
        let mut looked_up = Vec::new();
        for (position, entry) in entries.iter_mut().enumerate() {
            if entry.is_none() {
                crate::cohort::fault::note_cache_lookup(indexes[position]);
                *entry = self.cached_entry(indexes[position]);
                if entry.is_some() {
                    looked_up.push(position);
                }
            }
        }
        self.recent.remember_all(
            looked_up
                .iter()
                .filter_map(|&position| Some((indexes[position], entries[position].as_ref()?))),
        );
        entries
    }

    /// The entry of the concept at `index` after a miss: read from the store and cached,
    /// or the entry another reader cached meanwhile. Counts and times the read when this
    /// call makes it. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read.
    fn load_entry(&self, index: usize) -> Result<Arc<CachedConcept>> {
        self.cache
            .try_get_with(index as u32, || {
                let started = Instant::now();
                let block = self.read_block(index);
                self.cache_counters
                    .record_load(started.elapsed(), block.is_ok());
                block.map(|block| Arc::new(CachedConcept::new(block)))
            })
            .map_err(|error| (*error).clone())
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

/// The turn of the `call`th multi-concept read: which residue modulo [`RECORD_EVERY`] of
/// the concept indexes it sends through the block cache. A plain `call % RECORD_EVERY`
/// would give a workload that repeats every few calls the same turns for the same read, so
/// some of its concepts would never reach the cache; scrambling the call number
/// (the `splitmix64` finalizer) spreads every read's turns over all residues.
fn turn_of(call: usize) -> usize {
    let mut x = (call as u64).wrapping_add(0x9e37_79b9_7f4a_7c15);
    x = (x ^ (x >> 30)).wrapping_mul(0xbf58_476d_1ce4_e5b9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94d0_49bb_1331_11eb);
    x ^= x >> 31;
    (x % RECORD_EVERY as u64) as usize
}

/// The weight of a cached [`Scope`] under `key`: its bitmaps and the key, in bytes.
fn scope_weight(key: &[u64], scope: &Scope) -> u32 {
    u32::try_from(key.len() * 8 + scope.heap_size()).unwrap_or(u32::MAX)
}

/// The patient sets of one narrow selection, cached by [`PartitionSet`] per selection so a
/// union over many partitions is paid once per distinct selection.
#[derive(Debug)]
pub(crate) struct Scope {
    patients: RoaringBitmap,
    sparse: bool,
    overlap: Option<(RoaringBitmap, RoaringBitmap, bool)>,
}

impl Scope {
    fn new(patients: RoaringBitmap, overlap: &RoaringBitmap) -> Self {
        let overlap = (!overlap.is_empty()).then(|| {
            let reach = &patients | overlap;
            let sparse = is_sparse(&reach);
            (&patients - overlap, reach, sparse)
        });
        Scope {
            sparse: is_sparse(&patients),
            patients,
            overlap,
        }
    }

    /// `P(S)`: the union of the selected partitions' patient sets.
    pub(crate) fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// `R(S)`: the selected partitions' patients outside the overlap set, each of whom has
    /// every row in one selected partition.
    pub(crate) fn routed(&self) -> &RoaringBitmap {
        self.overlap
            .as_ref()
            .map_or(&self.patients, |(routed, _, _)| routed)
    }

    /// `R(S)` together with the whole overlap set: every patient a clause evaluated on the
    /// selection can match.
    pub(crate) fn reach(&self) -> &RoaringBitmap {
        self.overlap
            .as_ref()
            .map_or(&self.patients, |(_, reach, _)| reach)
    }

    /// True when [`Scope::reach`] is mostly array containers ([`is_sparse`]).
    fn reach_sparse(&self) -> bool {
        self.overlap
            .as_ref()
            .map_or(self.sparse, |(_, _, sparse)| *sparse)
    }

    fn heap_size(&self) -> usize {
        let size = |b: &RoaringBitmap| b.serialized_size();
        size(&self.patients)
            + self
                .overlap
                .as_ref()
                .map_or(0, |(routed, reach, _)| size(routed) + size(reach))
    }
}

/// The partitions one query reads, in partition name order. Built only by
/// [`PartitionSet::select`] and [`PartitionSet::select_consents`]. Publicly it only names
/// the partitions; the engine reads patient data through it.
#[derive(Debug)]
pub struct Selection<'a> {
    set: &'a PartitionSet,
    members: Vec<&'a Partition>,
    mask: Vec<bool>,
    full: bool,
    scope: OnceLock<Arc<Scope>>,
}

/// How a selection reads one concept ([`Selection::concept`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ReadMode {
    /// The whole block, whose rows are all in the selected partitions: what it gives is
    /// exactly the selection's answer.
    Exact,
    /// The whole block, holding rows of unselected partitions too: what it gives agrees with
    /// the selection's answer only on the patients the selection routes, and the overlap
    /// patients' rows must be filtered by tag.
    Whole,
    /// The partition view, read for the selected partitions only: exact.
    View,
}

/// One concept a selection reads: its position in the index and how it is read.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ConceptRead {
    pub(crate) index: usize,
    pub(crate) mode: ReadMode,
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

    /// True when the partition at `partition` in the partition table is selected.
    pub(crate) fn selects(&self, partition: u16) -> bool {
        self.mask
            .get(usize::from(partition))
            .copied()
            .unwrap_or(false)
    }

    /// True when every partition of the set is selected, which every selection of an
    /// unpartitioned store does. Such a selection reads whole blocks and every answer
    /// comes straight from them.
    pub fn is_all(&self) -> bool {
        self.full
    }

    /// The overlap set of the store.
    pub(crate) fn overlap(&self) -> &'a RoaringBitmap {
        &self.set.overlap
    }

    /// The patient sets of this narrow selection, from the set's cache or built and cached.
    pub(crate) fn scope(&self) -> &Scope {
        self.scope.get_or_init(|| {
            let mut key = vec![0u64; self.mask.len().div_ceil(64)];
            for (i, _) in self.mask.iter().enumerate().filter(|(_, s)| **s) {
                key[i / 64] |= 1 << (i % 64);
            }
            let set = self.set;
            set.scopes.get_with(key.into_boxed_slice(), || {
                let patients = match self.members.as_slice() {
                    [one] => one.patients.clone(),
                    many => many.iter().map(|p| &p.patients).union(),
                };
                Arc::new(Scope::new(patients, &set.overlap))
            })
        })
    }

    /// True when [`Selection::member_patients`] is mostly array containers ([`is_sparse`]).
    fn members_sparse(&self) -> bool {
        match self.members.as_slice() {
            _ if self.full => false,
            [] => false,
            [one] => one.sparse,
            _ => self.scope().sparse,
        }
    }

    /// `R(S)`: the selected partitions' patients outside the overlap set
    /// ([`Scope::routed`]). Their patient set itself when the overlap set is empty.
    pub(crate) fn routed(&self) -> &RoaringBitmap {
        if self.set.overlap.is_empty() {
            self.member_patients()
        } else {
            self.scope().routed()
        }
    }

    /// `found` without the patients this narrow selection does not reach ([`Scope::reach`]).
    /// A result much smaller than the reach is filtered through the route table, one lookup
    /// per patient; a larger one is intersected with the reach.
    pub(crate) fn reached(&self, mut found: RoaringBitmap) -> RoaringBitmap {
        let (reach, sparse) = if self.set.overlap.is_empty() {
            (self.member_patients(), self.members_sparse())
        } else {
            (self.scope().reach(), self.scope().reach_sparse())
        };
        if !sparse || found.len().saturating_mul(ROUTE_WALK_RATIO) >= reach.len() {
            found &= reach;
            return found;
        }
        let membership = self.set.store.membership();
        let mut kept = Vec::with_capacity(found.len() as usize);
        found.iter().for_each(|patient| {
            let reached = match membership.partition_of(patient) {
                Some(partition) => self.selects(partition),
                None => reach.contains(patient),
            };
            if reached {
                kept.push(patient);
            }
        });
        RoaringBitmap::from_sorted_iter(kept).unwrap_or_default()
    }

    /// `P(S)`, the union of the selected partitions' patient sets, borrowed.
    pub(crate) fn member_patients(&self) -> &RoaringBitmap {
        match self.members.as_slice() {
            _ if self.full => &self.set.all_patients,
            [] => &self.set.empty,
            [one] => &one.patients,
            _ => self.scope().patients(),
        }
    }

    /// The union of the selected partitions' patients
    /// (`PartitionedPhenotypicObservationStore.getPatientIds`). Borrowed when one partition
    /// or every partition is selected.
    pub(crate) fn patients(&self) -> Cow<'a, RoaringBitmap> {
        match self.members.as_slice() {
            _ if self.full => Cow::Borrowed(&self.set.all_patients),
            [] => Cow::Owned(RoaringBitmap::new()),
            [one] => Cow::Borrowed(&one.patients),
            _ => Cow::Owned(self.scope().patients().clone()),
        }
    }

    /// The number of patients in `cohort` outside the selection's members: the selected
    /// partitions' patient sets, and the overlap patients the index records as having a row
    /// tagged with a selected partition. For a selection of every partition, every overlap
    /// patient is a member, since an overlap patient is one with a row. Every cohort of this
    /// selection should give 0.
    ///
    /// The cost follows the cohort rather than the members where that is cheaper. A cohort
    /// that is the members' set itself passes at once. A narrow selection's cohort much
    /// smaller than its members is walked through the route table, which settles every
    /// patient outside the overlap set with one lookup; the patients it does not settle are
    /// looked up in the patient sets and then the tagged sets. Any other cohort is compared
    /// with the members as bitmaps.
    pub(crate) fn check_members(&self, cohort: &RoaringBitmap) -> u64 {
        let patients = self.member_patients();
        if std::ptr::eq(cohort, patients) {
            return 0;
        }
        let mut outside = 0;
        let walk =
            self.members_sparse() && cohort.len().saturating_mul(ROUTE_WALK_RATIO) < patients.len();
        let unsettled = if walk {
            let membership = self.set.store.membership();
            let mut unsettled = Vec::new();
            cohort
                .iter()
                .for_each(|patient| match membership.partition_of(patient) {
                    Some(partition) if self.selects(partition) => {}
                    Some(_) => outside += 1,
                    None if patients.contains(patient) => {}
                    None => unsettled.push(patient),
                });
            RoaringBitmap::from_sorted_iter(unsettled).unwrap_or_default()
        } else if cohort.difference_len(patients) == 0 {
            return 0;
        } else {
            cohort - patients
        };
        if unsettled.is_empty() {
            return outside;
        }
        let overlap = &self.set.overlap;
        outside += unsettled.difference_len(overlap);
        if self.full {
            return outside;
        }
        let candidates = &unsettled & overlap;
        if candidates.is_empty() {
            return outside;
        }
        let tagged: RoaringBitmap = self.members.iter().map(|p| &p.tagged).union();
        outside + candidates.difference_len(&tagged)
    }

    /// The type of the concept at exactly `path`, or `None` when no selected partition holds
    /// it.
    pub fn concept_type(&self, path: &str) -> Option<ConceptType> {
        let index = self.set.concept_index(path)?;
        self.holds(index)
            .then(|| self.set.dictionary[index].concept_type())
    }

    /// True when a selected partition holds the concept at `index`.
    fn holds(&self, index: usize) -> bool {
        self.full
            || self
                .set
                .holders_of(index)
                .iter()
                .any(|h| self.selects(h.partition))
    }

    /// How the concept at exactly `path` is read, or `None` when no selected partition
    /// holds it, in which case it is unknown to the selection.
    pub(crate) fn concept(&self, path: &str) -> Option<ConceptRead> {
        self.read_at(self.set.concept_index(path)?)
    }

    /// How the concept at `index` is read, or `None` when no selected partition holds it.
    fn read_at(&self, index: usize) -> Option<ConceptRead> {
        if self.full {
            return Some(ConceptRead {
                index,
                mode: ReadMode::Exact,
            });
        }
        let (mut selected, mut total, mut held) = (0u64, 0u64, false);
        for holder in self.set.holders_of(index) {
            let rows = u64::from(holder.observations);
            total += rows;
            if self.selects(holder.partition) {
                selected += rows;
                held = true;
            }
        }
        let mode = if selected == total {
            ReadMode::Exact
        } else if (selected as f64) < self.set.narrow_view_share * total as f64 {
            ReadMode::View
        } else {
            ReadMode::Whole
        };
        held.then_some(ConceptRead { index, mode })
    }

    /// How each concept is read whose path starts with `prefix` and that has at least one
    /// row in a selected partition, the concept at `prefix` included, in index order.
    pub(crate) fn concepts_with_prefix(&self, prefix: &str) -> Vec<ConceptRead> {
        self.set
            .prefix_range(prefix)
            .filter(|&index| {
                self.set
                    .holders_of(index)
                    .iter()
                    .any(|h| h.observations > 0 && (self.full || self.selects(h.partition)))
            })
            .filter_map(|index| self.read_at(index))
            .collect()
    }

    /// The number of concepts whose path starts with `prefix` that a selected partition
    /// holds, counting no further than `limit`.
    pub(crate) fn count_with_prefix(&self, prefix: &str, limit: usize) -> usize {
        let range = self.set.prefix_range(prefix);
        if self.full {
            return range.len().min(limit);
        }
        range.filter(|&index| self.holds(index)).take(limit).count()
    }

    /// The cached block and view of the concept `read` names, the block read and cached when
    /// missing. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read.
    pub(crate) fn entry(&self, read: &ConceptRead) -> Result<Arc<CachedConcept>> {
        self.set.entry_at(read.index)
    }

    /// The entries of `reads`, in order. Looks each up in the cache (most of them through
    /// [`RecentEntries`], see [`PartitionSet::cached_entries`]), then reads every missing
    /// block on the rayon pool and remembers it. Every missing block is attempted; the
    /// first failure in order is returned. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be read.
    pub(crate) fn entries(&self, reads: &[ConceptRead]) -> Result<Vec<Arc<CachedConcept>>> {
        let indexes: Vec<usize> = reads.iter().map(|read| read.index).collect();
        let mut entries = self.set.cached_entries(&indexes);
        let missing: Vec<usize> = (0..reads.len()).filter(|&i| entries[i].is_none()).collect();
        let loaded: Vec<Result<Arc<CachedConcept>>> = if missing.len() < 2 {
            missing
                .iter()
                .map(|&i| self.set.load_entry(reads[i].index))
                .collect()
        } else {
            missing
                .par_iter()
                .map(|&i| self.set.load_entry(reads[i].index))
                .collect()
        };
        for (&i, entry) in missing.iter().zip(loaded) {
            entries[i] = Some(entry?);
        }
        self.set.recent.remember_all(
            missing
                .iter()
                .filter_map(|&i| Some((indexes[i], entries[i].as_ref()?))),
        );
        Ok(entries.into_iter().flatten().collect())
    }

    /// The partition view of the concept `read` names, whose entry is `entry`, built and
    /// weighed with its block when missing. The entry goes back into the cache, to be
    /// reweighed, only while the cache still holds this very entry: the check and the
    /// reinsert are one atomic step on the key ([`moka`]'s `and_compute_with`), so an
    /// entry the cache has dropped meanwhile is not put back, and an entry another read
    /// loaded for the concept meanwhile is never replaced. The view serves this read
    /// either way. Blocking.
    ///
    /// # Errors
    /// [`QueryError::StoreInconsistent`] when a row belongs to no holder.
    pub(crate) fn view(&self, read: &ConceptRead, entry: &Arc<CachedConcept>) -> Result<Arc<View>> {
        let (view, built) = entry.view_or_build(|block| {
            View::build(
                block,
                self.set.holders_of(read.index),
                self.set.store.membership(),
                &self.set.dictionary[read.index].name,
            )
        })?;
        if built {
            crate::cohort::fault::before_view_reinsert(self.set, read.index);
            self.set
                .cache
                .entry(read.index as u32)
                .and_compute_with(|held| match held {
                    Some(held) if Arc::ptr_eq(held.value(), entry) => Op::Put(Arc::clone(entry)),
                    _ => Op::Nop,
                });
        }
        Ok(view)
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

/// Crate tests reaching into the cache and the table of recent entries.
#[cfg(test)]
pub(crate) mod test_support {
    use super::*;

    /// Drops the concept at `index` from the block cache and loads it again, remembering
    /// the new entry, as a concurrent read that found it evicted would.
    pub(crate) fn reload(set: &PartitionSet, index: usize) {
        set.cache.invalidate(&(index as u32));
        set.cache.run_pending_tasks();
        let entry = set.load_entry(index).unwrap();
        set.recent.remember_all(std::iter::once((index, &entry)));
    }

    /// The entry the block cache holds for the concept at `index`, and the one the table of
    /// recent entries serves for it.
    pub(crate) fn held_and_served(
        set: &PartitionSet,
        index: usize,
    ) -> (Option<Arc<CachedConcept>>, Option<Arc<CachedConcept>>) {
        set.cache.run_pending_tasks();
        let served = set
            .recent
            .get_all(std::iter::once(index), |_| false)
            .remove(0);
        (set.cache.get(&(index as u32)), served)
    }
}
