//! The engine: one [`PartitionSet`] and the v3 result computations of `CountV3Processor`,
//! `PatientV3Processor` and `MultiValueQueryV3Processor`.

use std::borrow::Cow;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use hpds_format::{Block, ConceptType, java_double_cmp, java_string_cmp};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};

use crate::cohort::evaluate_cohort_cow;
use crate::error::{QueryError, Result};
use crate::limits::{CheckedQuery, QueryLimits, SelectUse};
use crate::model::{PhenotypicFilter, PhenotypicFilterType, Query, ResultType};
use crate::partition::{PartitionOptions, PartitionSet, Selection};
use crate::results::{
    CategoricalCrossCounts, ConceptCounts, ContinuousCrossCounts, Dataframe, DataframeRows,
    PATIENT_ID_FIELD_NAME, SyncResult,
};
use crate::validate::{QueryValidator, path_is_variant_spec};

/// Default cap on the summed decoded size of the blocks one DATAFRAME export may hold at
/// once: 2 GiB.
pub const DEFAULT_EXPORT_PIN_BYTES: u64 = 2 << 30;

/// The query engine over one [`PartitionSet`]. Share it behind an `Arc`.
///
/// Every computation is blocking: it may read and decrypt blocks, and it fans work out on
/// the rayon pool. From async code, call it inside `tokio::task::spawn_blocking`, or enable
/// the `tokio` feature and use [`Engine::run_blocking`].
///
/// Every computation goes through one admission step whose receipt every evaluation
/// requires: it checks the query's shape against the engine's [`QueryLimits`], refuses
/// unsupported genomic input with [`crate::check_supported`], selects the partitions the
/// query's consents grant ([`PartitionSet::select`]), which fails with
/// [`QueryError::UserConsentsRequired`] when consents are required and absent, and checks
/// the concepts it touches in those partitions against the limits, all before any block is
/// loaded. None of them applies [`QueryValidator`]; callers validate with
/// [`Engine::validate`] first, where Java does.
///
/// The engine is the only way to evaluate a query. The cohort evaluator is not public, so a
/// caller cannot evaluate a query without the checks or outside its consents:
///
/// ```compile_fail
/// fn unchecked(set: &hpds_query::PartitionSet, query: &hpds_query::Query) {
///     let _ = hpds_query::evaluate_cohort(set, query);
/// }
/// ```
///
/// ```compile_fail
/// fn unbounded_prefix(selection: &hpds_query::Selection<'_>) {
///     let _ = hpds_query::cohort::any_record_of(selection, "\\");
/// }
/// ```
#[derive(Debug)]
pub struct Engine {
    partitions: PartitionSet,
    export_pin_bytes: u64,
    limits: QueryLimits,
}

impl Engine {
    /// Opens the data directory `dir`; see [`PartitionSet::open`].
    ///
    /// # Errors
    /// The errors of [`PartitionSet::open`].
    pub fn open(dir: &Path, options: &PartitionOptions) -> Result<Self> {
        Ok(Engine::new(PartitionSet::open(dir, options)?))
    }

    /// An engine over an already open partition set.
    pub fn new(partitions: PartitionSet) -> Self {
        Engine {
            partitions,
            export_pin_bytes: DEFAULT_EXPORT_PIN_BYTES,
            limits: QueryLimits::default(),
        }
    }

    /// Sets the cap on the summed decoded size of the blocks one DATAFRAME export holds at
    /// once ([`DEFAULT_EXPORT_PIN_BYTES`] by default), counted over every partition. An
    /// export over the cap fails with [`QueryError::ExportTooWide`].
    #[must_use]
    pub fn with_export_pin_bytes(mut self, bytes: u64) -> Self {
        self.export_pin_bytes = bytes;
        self
    }

    /// Sets the per-query bounds ([`QueryLimits::default`] by default). A query over a bound
    /// fails with [`QueryError::LimitExceeded`] before any block is loaded, whichever
    /// computation it reaches.
    #[must_use]
    pub fn with_limits(mut self, limits: QueryLimits) -> Self {
        self.limits = limits;
        self
    }

    /// The per-query bounds.
    pub fn limits(&self) -> &QueryLimits {
        &self.limits
    }

    /// Checks `query` against the per-query bounds as its declared result type uses them,
    /// counting the concepts it touches in the partitions its consents select. Every
    /// computation repeats this check itself; callers use it to refuse a query before
    /// queuing work for it.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks, and
    /// [`QueryError::UserConsentsRequired`] when the shape is within bounds but consents are
    /// required and absent.
    pub fn check_limits(&self, query: &Query) -> Result<()> {
        self.limits.check_shape(query)?;
        let selection = self.partitions.select(query)?;
        self.limits.check_concepts(query, &selection)
    }

    /// The partitions.
    pub fn partitions(&self) -> &PartitionSet {
        &self.partitions
    }

    /// Validates `query` against the merged dictionary with the [`QueryValidator`] the
    /// partition set's flag gives: consents are required exactly when
    /// [`PartitionSet::requires_consents`] (the Java property
    /// `hpds.requireAuthorizationFilter`) says so.
    ///
    /// # Errors
    /// The errors of [`QueryValidator::validate`].
    pub fn validate(&self, query: &Query) -> Result<()> {
        QueryValidator {
            require_consents: self.partitions.requires_consents(),
        }
        .validate(query, &self.partitions)
    }

    /// Fails when consents are required and `query` carries no consent value, the check
    /// every computation makes before reading. Lets a caller refuse such a query before
    /// queuing work for it.
    ///
    /// # Errors
    /// [`QueryError::UserConsentsRequired`].
    pub fn check_consents(&self, query: &Query) -> Result<()> {
        self.partitions.check_consents(query)
    }

    /// The cohort: patients matching the phenotypic clause in the partitions the query's
    /// consents select (`QueryExecutor.getPatientSubsetForQuery` without genomic filters).
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the query breaks a bound,
    /// [`QueryError::Unsupported`] for genomic input, [`QueryError::UserConsentsRequired`]
    /// when consents are required and absent, [`QueryError::FilterWithoutCriteria`],
    /// [`QueryError::EmptySubquery`], [`QueryError::MissingOperator`] and
    /// [`QueryError::TypeMismatch`] where Java's executor throws,
    /// [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index, and
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub fn cohort(&self, query: &Query) -> Result<RoaringBitmap> {
        self.cohort_cow(query).map(Cow::into_owned)
    }

    /// [`Engine::cohort`] that borrows the partitions' patient set instead of copying it
    /// when the query selects every patient of one partition or of all of them.
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn cohort_cow(&self, query: &Query) -> Result<Cow<'_, RoaringBitmap>> {
        evaluate_cohort_cow(&self.admit(query, SelectUse::Distinct)?)
    }

    /// The single admission step every computation goes through (see
    /// [`QueryLimits::admit`]). The receipt it returns is the only way to reach the cohort
    /// evaluator and the result computations.
    fn admit<'q>(&self, query: &'q Query, select_use: SelectUse) -> Result<CheckedQuery<'q, '_>> {
        self.limits.admit(query, &self.partitions, select_use)
    }

    fn prepare<'q>(
        &self,
        query: &'q Query,
        select_use: SelectUse,
    ) -> Result<(CheckedQuery<'q, '_>, Cow<'_, RoaringBitmap>)> {
        let checked = self.admit(query, select_use)?;
        let cohort = evaluate_cohort_cow(&checked)?;
        Ok((checked, cohort))
    }

    /// COUNT: the cohort size (`CountV3Processor.runCounts`).
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn count(&self, query: &Query) -> Result<u64> {
        Ok(self.cohort_cow(query)?.len())
    }

    /// CROSS_COUNT (`CountV3Processor.runCrossCounts`): for each `select` path, the number
    /// of cohort patients with any value for it in a selected partition. An unknown path
    /// counts 0, and a path whose block cannot be loaded in some partition counts -1. Each
    /// path appears once, in first-occurrence order; Java returns a `ConcurrentHashMap`, so
    /// its key order is hash order and not part of the contract.
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn cross_count(&self, query: &Query) -> Result<ConceptCounts> {
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        Ok(cross_count_in(&checked, &cohort))
    }

    /// CATEGORICAL_CROSS_COUNT (`CountV3Processor.runCategoryCrossCounts`).
    ///
    /// The concepts are those of the phenotypic clause's filters that are not variant-spec
    /// paths and are either value filters (`FILTER` with non-empty `values`) or `REQUIRED`
    /// filters on concepts the merged dictionary calls categorical. Each concept appears
    /// once. For each, every category any selected partition holds is listed with the
    /// number of cohort patients holding it in some selected partition (Java's merged
    /// category map), when that number is above 0 or a value filter on the concept named the
    /// category. A named value no selected partition holds is not listed. A concept that is
    /// unknown in the selected partitions, or numeric, gets an empty map.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], and [`QueryError::Block`] when a block cannot be loaded.
    pub fn categorical_cross_count(&self, query: &Query) -> Result<CategoricalCrossCounts> {
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        self.categorical_cross_count_in(&checked, &cohort)
    }

    fn categorical_cross_count_in(
        &self,
        checked: &CheckedQuery<'_, '_>,
        cohort: &RoaringBitmap,
    ) -> Result<CategoricalCrossCounts> {
        let selection = checked.selection();
        let mut called_out: HashMap<&str, HashSet<&str>> = HashMap::new();
        for filter in checked.query().all_filters() {
            if !self.is_categorical_cross_count_filter(filter) {
                continue;
            }
            let values = called_out.entry(filter.concept_path.as_str()).or_default();
            if filter.is_categorical_filter() {
                values.extend(filter.values.iter().flatten().map(String::as_str));
            }
        }
        let budget = EntryBudget::new(self.limits.max_cross_count_entries);
        let mut concepts = called_out
            .par_iter()
            .map(|(path, named)| {
                let blocks = selection.blocks(path)?;
                let mut tally = budget.tally();
                let counts = category_counts(&blocks, cohort, named, &mut tally)?;
                tally.settle()?;
                Ok(((*path).to_owned(), counts))
            })
            .collect::<Result<Vec<_>>>()?;
        concepts.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        Ok(CategoricalCrossCounts { concepts })
    }

    fn is_categorical_cross_count_filter(&self, filter: &PhenotypicFilter) -> bool {
        if path_is_variant_spec(&filter.concept_path) {
            return false;
        }
        filter.is_categorical_filter()
            || (filter.phenotypic_filter_type == PhenotypicFilterType::Required
                && self.concept_type(&filter.concept_path) == Some(ConceptType::Categorical))
    }

    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.partitions.summary(path).map(|c| c.concept_type())
    }

    /// CONTINUOUS_CROSS_COUNT (`CountV3Processor.runContinuousCrossCounts`).
    ///
    /// The concepts are those of the phenotypic clause's filters, of any type, whose path
    /// the merged dictionary calls numeric. Each appears once. For each, every distinct
    /// value held by a cohort patient in a selected partition is listed with the number of
    /// cohort observations holding it, summed over the selected partitions (Java counts over
    /// the merged cube, so a patient present in two partitions counts from both). Filter
    /// bounds narrow the cohort, not the values listed. Values are distinct under Java
    /// `Double.compareTo`: `-0.0` and `0.0` are separate, every NaN is one value.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], and [`QueryError::Block`] when a block cannot be loaded.
    pub fn continuous_cross_count(&self, query: &Query) -> Result<ContinuousCrossCounts> {
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        self.continuous_cross_count_in(&checked, &cohort)
    }

    fn continuous_cross_count_in(
        &self,
        checked: &CheckedQuery<'_, '_>,
        cohort: &RoaringBitmap,
    ) -> Result<ContinuousCrossCounts> {
        let selection = checked.selection();
        let paths: BTreeSet<&str> = checked
            .query()
            .all_filters()
            .into_iter()
            .map(|f| f.concept_path.as_str())
            .filter(|path| self.concept_type(path) == Some(ConceptType::Numeric))
            .collect();
        let budget = EntryBudget::new(self.limits.max_cross_count_entries);
        let mut concepts = paths
            .par_iter()
            .map(|path| {
                let blocks = selection.blocks(path)?;
                let mut tally = budget.tally();
                let counts = value_counts(&blocks, cohort, &mut tally)?;
                tally.settle()?;
                Ok(((*path).to_owned(), counts))
            })
            .collect::<Result<Vec<_>>>()?;
        concepts.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        Ok(ContinuousCrossCounts { concepts })
    }

    /// OBSERVATION_CROSS_COUNT (`CountV3Processor.runObservationCrossCounts`): for each
    /// `select` path that names a concept in some selected partition, the number of its
    /// observations whose patient is in the cohort, summed over the selected partitions.
    /// Other paths are omitted, a block that cannot be loaded counts -1, and paths are in
    /// Java string order (Java's `TreeMap`).
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn observation_cross_count(&self, query: &Query) -> Result<ConceptCounts> {
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        Ok(observation_cross_count_in(&checked, &cohort))
    }

    /// The answer to a sync result type, dispatched on `query.expected_result_type`.
    ///
    /// # Errors
    /// [`QueryError::Unsupported`] for genomic result types, [`QueryError::NotSync`] for
    /// the export types (DATAFRAME, PATIENTS, DATAFRAME_TIMESERIES, DATAFRAME_PFB), and the
    /// errors of the computation, [`QueryError::UserConsentsRequired`] included.
    pub fn run_sync(&self, query: &Query) -> Result<SyncResult> {
        let result_type = query.expected_result_type;
        if !result_type.is_sync() {
            self.limits.check_shape(query)?;
            crate::validate::check_supported(query)?;
            return Err(QueryError::NotSync(result_type));
        }
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        match result_type {
            ResultType::Count => Ok(SyncResult::Count(cohort.len())),
            ResultType::CrossCount => Ok(SyncResult::CrossCount(cross_count_in(&checked, &cohort))),
            ResultType::CategoricalCrossCount => self
                .categorical_cross_count_in(&checked, &cohort)
                .map(SyncResult::CategoricalCrossCount),
            ResultType::ContinuousCrossCount => self
                .continuous_cross_count_in(&checked, &cohort)
                .map(SyncResult::ContinuousCrossCount),
            ResultType::ObservationCrossCount => Ok(SyncResult::ObservationCrossCount(
                observation_cross_count_in(&checked, &cohort),
            )),
            other => Err(QueryError::NotSync(other)),
        }
    }

    /// PATIENTS: the cohort, which iterates in ascending patient id order. A patient in two
    /// selected partitions appears once. Java writes the
    /// ids in the iteration order of whichever `Set` the executor produced, which is not a
    /// sorted order in general; ascending order is this engine's choice. The export header
    /// is [`crate::PATIENTS_HEADER`].
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn patients(&self, query: &Query) -> Result<RoaringBitmap> {
        self.cohort(query)
    }

    /// DATAFRAME: the header and a streaming iterator over one row per cohort patient,
    /// ascending (see [`DataframeRows`]). The blocks of every `select` path in every
    /// selected partition are loaded before this returns and held by the iterator, so
    /// iteration does no I/O. They are loaded in chunks of paths and the load stops as soon
    /// as their summed decoded size passes the export cap
    /// ([`Engine::with_export_pin_bytes`]). Every `select` entry is a column, so repeated
    /// paths count toward `max_select_paths` whatever result type the query declares.
    ///
    /// A cell merges the patient's values from every selected partition holding the
    /// concept, partition by partition in name order and in input order within one. Java
    /// merges cubes in an unspecified partition order.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], [`QueryError::Block`] when a block cannot be loaded, and
    /// [`QueryError::ExportTooWide`] when the blocks exceed the cap.
    pub fn dataframe(&self, query: &Query) -> Result<Dataframe> {
        let (checked, cohort) = self.prepare(query, SelectUse::EveryEntry)?;
        let selection = checked.selection();
        let cohort = cohort.into_owned();
        let paths = distinct(&query.select);
        let mut loaded: HashMap<&str, Vec<Arc<Block>>> = HashMap::with_capacity(paths.len());
        let mut pinned: u64 = 0;
        for chunk in paths.chunks(EXPORT_LOAD_CHUNK) {
            let blocks = chunk
                .par_iter()
                .map(|path| Ok((*path, selection.blocks(path)?)))
                .collect::<Result<Vec<_>>>()?;
            for (path, blocks) in blocks {
                pinned = blocks
                    .iter()
                    .fold(pinned, |sum, b| sum.saturating_add(b.heap_size() as u64));
                loaded.insert(path, blocks);
            }
            if pinned > self.export_pin_bytes {
                return Err(QueryError::ExportTooWide);
            }
        }
        let columns = query
            .select
            .iter()
            .map(|path| loaded.get(path.as_str()).cloned().unwrap_or_default())
            .collect();
        let header = std::iter::once(PATIENT_ID_FIELD_NAME.to_owned())
            .chain(query.select.iter().cloned())
            .collect();
        Ok(Dataframe {
            header,
            rows: DataframeRows::new(cohort, columns),
        })
    }

    /// Runs `work` on the blocking thread pool with this engine.
    ///
    /// # Errors
    /// The error `work` returns, and [`QueryError::Task`] when the task panics or is
    /// cancelled.
    #[cfg(feature = "tokio")]
    pub async fn run_blocking<R, F>(self: &Arc<Self>, work: F) -> Result<R>
    where
        R: Send + 'static,
        F: FnOnce(&Engine) -> Result<R> + Send + 'static,
    {
        let engine = Arc::clone(self);
        tokio::task::spawn_blocking(move || work(&engine))
            .await
            .map_err(|e| QueryError::Task(e.to_string()))?
    }
}

const EXPORT_LOAD_CHUNK: usize = 16;

fn distinct(paths: &[String]) -> Vec<&str> {
    let mut seen = HashSet::new();
    paths
        .iter()
        .map(String::as_str)
        .filter(|p| seen.insert(*p))
        .collect()
}

fn observation_patients(block: &Block) -> &[u32] {
    match block {
        Block::Numeric(b) => b.patients(),
        Block::Categorical(b) => b.patients(),
    }
}

/// The blocks of `path` in the selected partitions, or `None` after logging when one cannot
/// be loaded: the cross counts then report -1, as Java's catch block writes it.
fn blocks_or_log(selection: &Selection<'_>, path: &str, what: &str) -> Option<Vec<Arc<Block>>> {
    selection
        .blocks(path)
        .inspect_err(|e| tracing::warn!(concept = %path, error = %e, "{what} failed"))
        .ok()
}

fn cross_count_in(checked: &CheckedQuery<'_, '_>, cohort: &RoaringBitmap) -> ConceptCounts {
    let selection = checked.selection();
    let paths = distinct(&checked.query().select);
    let entries = paths
        .par_iter()
        .map(|path| {
            let count = match blocks_or_log(selection, path, "cross count").as_deref() {
                None => -1,
                Some([]) => 0,
                Some([one]) => one.all_patients().intersection_len(cohort) as i64,
                Some(many) => cohort_patients_in(many, cohort) as i64,
            };
            ((*path).to_owned(), count)
        })
        .collect();
    ConceptCounts { entries }
}

/// The number of `cohort` patients in any of `blocks`. When the cohort is smaller than the
/// average block, each block is intersected with it first and the small intersections are
/// unioned; otherwise the blocks are unioned once and the union counted against the cohort,
/// which allocates one bitmap instead of one per block.
fn cohort_patients_in(blocks: &[Arc<Block>], cohort: &RoaringBitmap) -> u64 {
    let observed: u64 = blocks.iter().map(|b| b.all_patients().len()).sum();
    if cohort.len().saturating_mul(blocks.len() as u64) < observed {
        blocks
            .iter()
            .map(|b| b.all_patients() & cohort)
            .union()
            .len()
    } else {
        blocks
            .iter()
            .map(|b| b.all_patients())
            .union()
            .intersection_len(cohort)
    }
}

fn observation_cross_count_in(
    checked: &CheckedQuery<'_, '_>,
    cohort: &RoaringBitmap,
) -> ConceptCounts {
    let selection = checked.selection();
    let mut entries: Vec<(String, i64)> = distinct(&checked.query().select)
        .par_iter()
        .filter_map(|path| {
            let count = match blocks_or_log(selection, path, "observation cross count") {
                None => -1,
                Some(blocks) if blocks.is_empty() => return None,
                Some(blocks) => blocks
                    .iter()
                    .map(|block| {
                        observation_patients(block)
                            .iter()
                            .filter(|&&p| cohort.contains(p))
                            .count() as i64
                    })
                    .sum(),
            };
            Some(((*path).to_owned(), count))
        })
        .collect();
    entries.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    ConceptCounts { entries }
}

/// Per category held by any of `blocks`, the cohort patients holding it in any of them,
/// listed when above 0 or `named`, in Java string order. Each listed category is drawn from
/// `tally` as it is listed, after the partitions' categories are merged.
///
/// # Errors
/// [`QueryError::LimitExceeded`] when the shared entry cap is exceeded.
fn category_counts(
    blocks: &[Arc<Block>],
    cohort: &RoaringBitmap,
    named: &HashSet<&str>,
    tally: &mut EntryTally<'_>,
) -> Result<Vec<(String, u64)>> {
    let mut categories: Vec<(&str, &RoaringBitmap)> = blocks
        .iter()
        .filter_map(|block| match block.as_ref() {
            Block::Categorical(b) => Some(b.dictionary().zip(b.bitmaps())),
            Block::Numeric(_) => None,
        })
        .flatten()
        .collect();
    categories.sort_by(|a, b| java_string_cmp(a.0, b.0));
    let mut counts = Vec::new();
    for group in categories.chunk_by(|a, b| a.0 == b.0) {
        let category = group[0].0;
        let count = match group {
            [(_, one)] => one.intersection_len(cohort),
            many => many
                .iter()
                .map(|(_, bitmap)| *bitmap)
                .union()
                .intersection_len(cohort),
        };
        if count > 0 || named.contains(category) {
            tally.take()?;
            counts.push((category.to_owned(), count));
        }
    }
    Ok(counts)
}

/// Per distinct value held by a cohort patient in any numeric block of `blocks`, the number
/// of such observations over all of them, in Java `Double.compareTo` order. Every distinct
/// value is drawn from `tally`: as it is found when one block holds the concept, and after
/// the merge otherwise. With several blocks, the build also stops as soon as one block alone
/// holds more distinct values for the cohort than the cap, since the merged list is at least
/// that long.
///
/// # Errors
/// [`QueryError::LimitExceeded`] when the shared entry cap is exceeded.
fn value_counts(
    blocks: &[Arc<Block>],
    cohort: &RoaringBitmap,
    tally: &mut EntryTally<'_>,
) -> Result<Vec<(f64, u64)>> {
    let single = blocks.len() <= 1;
    let cap = tally.limit();
    let mut counts: Vec<(f64, u64)> = Vec::new();
    for block in blocks {
        let Block::Numeric(block) = block.as_ref() else {
            continue;
        };
        let block_start = counts.len();
        for (&value, &patient) in block.values().iter().zip(block.patients()) {
            if !cohort.contains(patient) {
                continue;
            }
            match counts[block_start..].last_mut() {
                Some((last, count)) if java_double_cmp(*last, value).is_eq() => *count += 1,
                _ => {
                    if single {
                        tally.take()?;
                    } else if (counts.len() - block_start) as u64 >= cap {
                        return Err(tally.exceeded());
                    }
                    counts.push((value, 1));
                }
            }
        }
    }
    if !single {
        counts.sort_by(|a, b| java_double_cmp(a.0, b.0));
        counts.dedup_by(|later, earlier| {
            let same = java_double_cmp(later.0, earlier.0).is_eq();
            if same {
                earlier.1 += later.1;
            }
            same
        });
        for _ in &counts {
            tally.take()?;
        }
    }
    Ok(counts)
}

/// Entries a worker adds before it settles them against the shared cross count total.
const ENTRY_SETTLE_BATCH: u64 = 1024;

/// The cross count entry cap, shared by the workers that build one cross count. Workers
/// draw entries through an [`EntryTally`], so the cap stops a count while it is being built
/// rather than after every concept's list is complete.
struct EntryBudget {
    used: AtomicU64,
    limit: u64,
}

impl EntryBudget {
    fn new(limit: u64) -> Self {
        EntryBudget {
            used: AtomicU64::new(0),
            limit,
        }
    }

    /// A worker's view of the budget, settling into the shared total in batches.
    fn tally(&self) -> EntryTally<'_> {
        EntryTally {
            budget: self,
            pending: 0,
        }
    }

    fn exceeded(&self) -> QueryError {
        QueryError::LimitExceeded(format!(
            "the cross count would hold more than {} entries (HPDS_MAX_CROSS_COUNT_ENTRIES); \
             filter on fewer or narrower concepts",
            self.limit
        ))
    }
}

/// One worker's entries not yet added to its [`EntryBudget`].
struct EntryTally<'b> {
    budget: &'b EntryBudget,
    pending: u64,
}

impl EntryTally<'_> {
    /// The cap of the shared budget.
    fn limit(&self) -> u64 {
        self.budget.limit
    }

    /// The error a count over the cap fails with.
    fn exceeded(&self) -> QueryError {
        self.budget.exceeded()
    }

    /// Accounts for one more entry. Settles with the shared total every
    /// [`ENTRY_SETTLE_BATCH`] entries, and at once when this worker alone is over the cap.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the entries settled so far exceed the cap.
    fn take(&mut self) -> Result<()> {
        self.pending += 1;
        if self.pending >= ENTRY_SETTLE_BATCH || self.pending > self.budget.limit {
            self.settle()
        } else {
            Ok(())
        }
    }

    /// Adds the pending entries to the shared total.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the shared total exceeds the cap.
    fn settle(&mut self) -> Result<()> {
        let added = std::mem::take(&mut self.pending);
        let total = self
            .budget
            .used
            .fetch_add(added, Ordering::Relaxed)
            .saturating_add(added);
        if total > self.budget.limit {
            Err(self.budget.exceeded())
        } else {
            Ok(())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn is_limit(result: Result<()>) -> bool {
        matches!(result, Err(QueryError::LimitExceeded(_)))
    }

    #[test]
    fn a_tally_stops_at_the_cap_before_its_concept_is_complete() {
        let budget = EntryBudget::new(3);
        let mut tally = budget.tally();
        for _ in 0..3 {
            assert!(tally.take().is_ok());
        }
        assert!(is_limit(tally.take()));
    }

    #[test]
    fn a_large_cap_still_stops_one_batch_past_it() {
        let budget = EntryBudget::new(ENTRY_SETTLE_BATCH * 2);
        let mut tally = budget.tally();
        let taken = (0..ENTRY_SETTLE_BATCH * 10)
            .take_while(|_| tally.take().is_ok())
            .count() as u64;
        assert!(taken < ENTRY_SETTLE_BATCH * 3);
    }

    #[test]
    fn tallies_share_one_total() {
        let budget = EntryBudget::new(10);
        let mut first = budget.tally();
        let mut second = budget.tally();
        for _ in 0..6 {
            assert!(first.take().is_ok());
            assert!(second.take().is_ok());
        }
        assert!(first.settle().is_ok());
        assert!(is_limit(second.settle()));
    }
}
