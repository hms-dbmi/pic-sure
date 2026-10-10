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

use crate::cohort::{evaluate_cohort_cow, for_each_row_of};
use crate::error::{QueryError, Result};
use crate::fan_out;
use crate::limits::{CheckedQuery, QueryLimits, SelectUse};
use crate::model::{PhenotypicFilter, PhenotypicFilterType, Query, ResultType};
use crate::partition::{ConceptRead, PartitionOptions, PartitionSet, ReadMode, Selection};
use crate::result_cache::{self, ResultCache, ResultCacheStats, SyncAnswer};
use crate::results::{
    CategoricalCrossCounts, ColumnBlock, ConceptCounts, ContinuousCrossCounts, Dataframe,
    DataframeRows, PATIENT_ID_FIELD_NAME, SyncResult,
};
use crate::store::CachedConcept;
use crate::validate::{QueryValidator, path_is_variant_spec};
use crate::view::View;

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
    result_cache: Option<ResultCache>,
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
            result_cache: None,
        }
    }

    /// Turns on the result cache, bounded at `bytes`, or leaves it off when `bytes` is 0
    /// (the default). It holds the answers of COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT,
    /// CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT for [`Engine::cached_sync`] and
    /// [`Engine::answer_sync`]; see [`Engine::cached_sync`] for how entries are keyed. The
    /// cache lives as long as the engine and is never written to disk.
    #[must_use]
    pub fn with_result_cache_bytes(mut self, bytes: u64) -> Self {
        self.result_cache = (bytes > 0).then(|| ResultCache::new(bytes));
        self
    }

    /// The result cache's counters, or `None` when the cache is off.
    pub fn result_cache_stats(&self) -> Option<ResultCacheStats> {
        self.result_cache.as_ref().map(ResultCache::stats)
    }

    /// Sets the cap on the summed decoded size of the blocks one DATAFRAME export holds at
    /// once ([`DEFAULT_EXPORT_PIN_BYTES`] by default). Each concept's whole block counts,
    /// every partition's rows, since that is what the export holds. An export over the cap
    /// fails with [`QueryError::ExportTooWide`].
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

    /// What a computation of `query` would read, decided by the same admission step every
    /// computation goes through (for the select use its declared result type implies): the
    /// partitions its consents select, and whether that is every partition. Reads no block.
    ///
    /// # Errors
    /// The errors of admission: [`QueryError::LimitExceeded`], [`QueryError::Unsupported`]
    /// and [`QueryError::UserConsentsRequired`].
    pub fn read_plan(&self, query: &Query) -> Result<ReadPlan> {
        let checked = self.admit(query, SelectUse::of(query.expected_result_type))?;
        let selection = checked.selection();
        Ok(ReadPlan {
            partitions: selection.names().into_iter().map(str::to_owned).collect(),
            full: selection.is_all(),
        })
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
    /// [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index,
    /// [`QueryError::Block`] when a block cannot be loaded, and
    /// [`QueryError::CohortOutsideSelection`] when the cohort holds a patient outside the
    /// selected partitions, which only a fault in the engine can cause.
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
        let called_out: Vec<(&str, HashSet<&str>)> = called_out.into_iter().collect();
        let mut concepts = fan_out::map(&called_out, |(path, named)| {
            let mut tally = budget.tally();
            let counts = match selection.concept(path) {
                None => Vec::new(),
                Some(read) => {
                    let entry = selection.entry(&read)?;
                    category_counts(selection, &read, &entry, cohort, named, &mut tally)?
                }
            };
            tally.settle()?;
            Ok(((*path).to_owned(), counts))
        })
        .into_iter()
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
        let paths: Vec<&str> = paths.into_iter().collect();
        let mut concepts = fan_out::map(&paths, |path| {
            let mut tally = budget.tally();
            let counts = match selection.concept(path) {
                None => Vec::new(),
                Some(read) => {
                    let entry = selection.entry(&read)?;
                    value_counts(selection, &read, &entry, cohort, &mut tally)?
                }
            };
            tally.settle()?;
            Ok(((*path).to_owned(), counts))
        })
        .into_iter()
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
        self.sync_result_in(&checked, &cohort)
    }

    /// The cached answer to `query`, when the result cache is on, the query's result type is
    /// one it holds, and an entry matches. Reads no block.
    ///
    /// The lookup runs only after `query` passes the same admission step as every
    /// computation (limits, genomic input, consents), so a query that would be refused is
    /// refused here with the same error, warm cache or not. The key is the store
    /// generation, the result type, the partitions the consents select after admission, and
    /// a canonical form of the fields that decide the answer: the phenotypic clause, `select`
    /// for CROSS_COUNT and OBSERVATION_CROSS_COUNT, and `authorizationFilters` as their exact
    /// canonical JSON
    /// (never applied, so any difference there, order included, splits entries). Ids and
    /// the consent values themselves are not in it. So two queries whose consents select
    /// different partitions never share an entry, and one answer serves every request that
    /// selects the same partitions. Each lookup of a cacheable query counts a hit or a miss.
    ///
    /// # Errors
    /// The errors of admission, as for [`Engine::run_sync`].
    pub fn cached_sync(&self, query: &Query) -> Result<Option<SyncAnswer>> {
        let Some(cache) = &self.result_cache else {
            return Ok(None);
        };
        if !result_cache::is_cacheable(query.expected_result_type) {
            return Ok(None);
        }
        let checked = self.admit(query, SelectUse::Distinct)?;
        Ok(result_cache::key_of(&checked, self.partitions.generation())
            .and_then(|key| cache.get(&key)))
    }

    /// [`Engine::run_sync`], rendered as the HTTP layer sends it. When the result cache is
    /// on, a cacheable answer is stored for [`Engine::cached_sync`]; errors are not, and
    /// neither is a cross count holding -1 for a block that could not be loaded. Does not
    /// look the cache up itself.
    ///
    /// # Errors
    /// As for [`Engine::run_sync`].
    pub fn answer_sync(&self, query: &Query) -> Result<SyncAnswer> {
        let result_type = query.expected_result_type;
        if !result_type.is_sync() {
            return self.run_sync(query).map(|result| SyncAnswer::of(&result));
        }
        let (checked, cohort) = self.prepare(query, SelectUse::Distinct)?;
        let result = self.sync_result_in(&checked, &cohort)?;
        let answer = SyncAnswer::of(&result);
        if let Some(cache) = &self.result_cache
            && result_cache::is_storable(&result)
            && let Some(key) = result_cache::key_of(&checked, self.partitions.generation())
        {
            cache.insert(key, answer.clone());
        }
        Ok(answer)
    }

    fn sync_result_in(
        &self,
        checked: &CheckedQuery<'_, '_>,
        cohort: &RoaringBitmap,
    ) -> Result<SyncResult> {
        let result_type = checked.query().expected_result_type;
        match result_type {
            ResultType::Count => Ok(SyncResult::Count(cohort.len())),
            ResultType::CrossCount => Ok(SyncResult::CrossCount(cross_count_in(checked, cohort))),
            ResultType::CategoricalCrossCount => self
                .categorical_cross_count_in(checked, cohort)
                .map(SyncResult::CategoricalCrossCount),
            ResultType::ContinuousCrossCount => self
                .continuous_cross_count_in(checked, cohort)
                .map(SyncResult::ContinuousCrossCount),
            ResultType::ObservationCrossCount => Ok(SyncResult::ObservationCrossCount(
                observation_cross_count_in(checked, cohort),
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
    /// ascending (see [`DataframeRows`]). The block of every `select` path a selected
    /// partition holds is loaded before this returns and held by the iterator, so iteration
    /// does no I/O. They are loaded in chunks of paths and the load stops as soon as their
    /// summed decoded size passes the export cap ([`Engine::with_export_pin_bytes`]). Every
    /// `select` entry is a column, so repeated paths count toward `max_select_paths`
    /// whatever result type the query declares.
    ///
    /// A cell holds the patient's values from every selected partition holding the concept,
    /// partition by partition in name order and in input order within one. Java merges
    /// cubes in an unspecified partition order.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], [`QueryError::Block`] when a block cannot be loaded, and
    /// [`QueryError::ExportTooWide`] when the blocks exceed the cap.
    pub fn dataframe(&self, query: &Query) -> Result<Dataframe> {
        let (checked, cohort) = self.prepare(query, SelectUse::EveryEntry)?;
        let selection = checked.selection();
        let cohort = cohort.into_owned();
        let paths: Vec<(&str, ConceptRead)> = distinct(&query.select)
            .into_iter()
            .filter_map(|path| Some((path, selection.concept(path)?)))
            .collect();
        let partitions: Arc<[bool]> = selection.mask().into();
        let mut loaded: HashMap<&str, ColumnBlock> = HashMap::with_capacity(paths.len());
        let mut pinned: u64 = 0;
        for chunk in paths.chunks(EXPORT_LOAD_CHUNK) {
            let entries = chunk
                .par_iter()
                .map(|(path, read)| Ok((*path, read, selection.entry(read)?)))
                .collect::<Result<Vec<_>>>()?;
            for (path, read, entry) in entries {
                let block = Arc::clone(entry.block());
                pinned = pinned.saturating_add(block.heap_size() as u64);
                let filtered = read.mode != ReadMode::Exact && block.partitions().is_tagged();
                let column = ColumnBlock::new(block, filtered.then(|| Arc::clone(&partitions)));
                loaded.insert(path, column);
            }
            if pinned > self.export_pin_bytes {
                return Err(QueryError::ExportTooWide);
            }
        }
        let columns = query
            .select
            .iter()
            .map(|path| loaded.get(path.as_str()).cloned())
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

/// What one query reads, from [`Engine::read_plan`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadPlan {
    /// The partitions its consents select, in partition name order.
    pub partitions: Vec<String>,
    /// True when they are every partition, so every answer comes straight from whole
    /// blocks.
    pub full: bool,
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

/// The entry of the concept `read` names, or `None` after logging when its block cannot be
/// loaded: the cross counts then report -1, as Java's catch block writes it.
fn entry_or_log(
    selection: &Selection<'_>,
    read: &ConceptRead,
    path: &str,
    what: &str,
) -> Option<Arc<CachedConcept>> {
    selection
        .entry(read)
        .inspect_err(|e| tracing::warn!(concept = %path, error = %e, "{what} failed"))
        .ok()
}

/// A predicate on a row's tag: true when the row lies in a selected partition, for a
/// whole block of a narrow selection whose rows are tagged. Every row of a cohort patient
/// outside the overlap set lies in a selected partition, so an untagged block, or a block
/// read exactly, needs no test.
fn row_filter<'s>(
    selection: &'s Selection<'_>,
    read: &ConceptRead,
    block: &Block,
) -> impl Fn(Option<u16>) -> bool + 's {
    let filtered = read.mode != ReadMode::Exact && block.partitions().is_tagged();
    move |tag| !filtered || tag.is_some_and(|tag| selection.selects(tag))
}

fn cross_count_in(checked: &CheckedQuery<'_, '_>, cohort: &RoaringBitmap) -> ConceptCounts {
    let selection = checked.selection();
    let paths = distinct(&checked.query().select);
    let entries = fan_out::map(&paths, |path| {
        let count = match selection.concept(path) {
            None => 0,
            Some(read) => match entry_or_log(selection, &read, path, "cross count") {
                None => -1,
                Some(entry) if read.mode == ReadMode::View => match selection.view(&read, &entry) {
                    Ok(view) => view_cohort_patients_in(selection, &view, entry.block(), cohort),
                    Err(error) => {
                        tracing::warn!(concept = %path, %error, "cross count failed");
                        -1
                    }
                },
                Some(entry) => cohort_patients_in(selection, &read, entry.block(), cohort) as i64,
            },
        };
        ((*path).to_owned(), count)
    });
    ConceptCounts { entries }
}

/// The number of `cohort` patients with a row of `block` in a selected partition. A
/// cohort patient outside the overlap set has every row in a selected partition, so the
/// block's patients answer for them; an overlap patient of a tagged block counts only with
/// a row tagged with a selected partition.
fn cohort_patients_in(
    selection: &Selection<'_>,
    read: &ConceptRead,
    block: &Block,
    cohort: &RoaringBitmap,
) -> u64 {
    let count = block.all_patients().intersection_len(cohort);
    if read.mode == ReadMode::Exact || !block.partitions().is_tagged() {
        return count;
    }
    let overlap_cohort = &(block.all_patients() & cohort) & selection.overlap();
    let mut held = RoaringBitmap::new();
    let in_selection = row_filter(selection, read, block);
    for_each_row_of(block, &overlap_cohort, |patient, _, tag| {
        if in_selection(tag) {
            held.insert(patient);
        }
    });
    count - (overlap_cohort.len() - held.len())
}

/// [`cohort_patients_in`] through the concept's partition view: the cohort patients of each
/// selected partition, summed when the block is untagged (each patient's rows lie in one
/// partition) and unioned first otherwise.
fn view_cohort_patients_in(
    selection: &Selection<'_>,
    view: &View,
    block: &Block,
    cohort: &RoaringBitmap,
) -> i64 {
    let slots = view.selected_slots(selection.mask());
    let count = if block.partitions().is_tagged() {
        slots
            .map(|slot| view.patients(slot))
            .union()
            .intersection_len(cohort)
    } else {
        slots
            .map(|slot| view.patients(slot).intersection_len(cohort))
            .sum()
    };
    count as i64
}

/// Cohort patients per block row above which counting a block's cohort rows walks every
/// row instead of seeking each patient's rows.
const SEEK_ROWS_PER_PATIENT: usize = 16;

fn observation_cross_count_in(
    checked: &CheckedQuery<'_, '_>,
    cohort: &RoaringBitmap,
) -> ConceptCounts {
    let selection = checked.selection();
    let paths = distinct(&checked.query().select);
    let mut entries: Vec<(String, i64)> = fan_out::map(&paths, |path| {
        let read = selection.concept(path)?;
        let count = match entry_or_log(selection, &read, path, "observation cross count") {
            None => -1,
            Some(entry) => cohort_rows_in(selection, &read, entry.block(), cohort) as i64,
        };
        Some(((*path).to_owned(), count))
    })
    .into_iter()
    .flatten()
    .collect();
    entries.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    ConceptCounts { entries }
}

/// The number of rows of `block` in a selected partition whose patient is in `cohort`. A
/// selection of every partition walks every row; a narrower one seeks the rows of the
/// cohort patients the block holds when they are few, and filters tagged rows by tag.
fn cohort_rows_in(
    selection: &Selection<'_>,
    read: &ConceptRead,
    block: &Block,
    cohort: &RoaringBitmap,
) -> usize {
    let patients = observation_patients(block);
    if read.mode == ReadMode::Exact {
        return patients.iter().filter(|&&p| cohort.contains(p)).count();
    }
    let in_selection = row_filter(selection, read, block);
    let held = block.all_patients() & cohort;
    if (held.len() as usize).saturating_mul(SEEK_ROWS_PER_PATIENT) < patients.len() {
        let mut count = 0;
        for_each_row_of(block, &held, |_, _, tag| {
            count += usize::from(in_selection(tag))
        });
        return count;
    }
    match block {
        Block::Numeric(b) => (0..patients.len())
            .filter(|&i| held.contains(patients[i]) && in_selection(b.tag_at_value_pos(i)))
            .count(),
        Block::Categorical(b) => (0..patients.len())
            .filter(|&k| held.contains(patients[k]) && in_selection(b.tag_at_key_pos(k)))
            .count(),
    }
}

/// Per category of the categorical block in `entry` held by a selected partition, the
/// cohort patients holding it in a selected partition, listed when above 0 or `named`, in
/// Java string order. Each listed category is drawn from `tally` as it is listed.
///
/// A category is held by a selected partition when a patient the selection routes holds it
/// (all their rows lie in a selected partition) or an overlap patient holds it in a row
/// tagged with a selected partition. For a block read exactly, every category of the block
/// is held.
///
/// # Errors
/// [`QueryError::LimitExceeded`] when the shared entry cap is exceeded.
fn category_counts(
    selection: &Selection<'_>,
    read: &ConceptRead,
    entry: &Arc<CachedConcept>,
    cohort: &RoaringBitmap,
    named: &HashSet<&str>,
    tally: &mut EntryTally<'_>,
) -> Result<Vec<(String, u64)>> {
    let block = entry.block();
    let Block::Categorical(categorical) = block.as_ref() else {
        return Ok(Vec::new());
    };
    if read.mode == ReadMode::View {
        return view_category_counts(selection, read, entry, cohort, named, tally);
    }
    let narrow = read.mode != ReadMode::Exact;
    let tagged = narrow && block.partitions().is_tagged();
    let mut overlap_values: HashMap<u32, RoaringBitmap> = HashMap::new();
    let mut overlap_cohort = RoaringBitmap::new();
    if tagged {
        let candidates = block.all_patients() & selection.overlap();
        let indices = categorical.value_indices();
        for_each_row_of(block, &candidates, |patient, key, tag| {
            if tag.is_some_and(|tag| selection.selects(tag)) {
                overlap_values
                    .entry(indices[key])
                    .or_default()
                    .insert(patient);
            }
        });
        overlap_cohort = &candidates & cohort;
    }
    let mut counts = Vec::new();
    for (value, (category, bitmap)) in categorical
        .dictionary()
        .zip(categorical.bitmaps())
        .enumerate()
    {
        let overlap_held = overlap_values.get(&(value as u32));
        let mut count = bitmap.intersection_len(cohort);
        if !overlap_cohort.is_empty() {
            count -= bitmap.intersection_len(&overlap_cohort);
            count += overlap_held.map_or(0, |held| held.intersection_len(&overlap_cohort));
        }
        let listed = count > 0
            || (named.contains(category)
                && (!narrow
                    || !bitmap.is_disjoint(selection.routed())
                    || overlap_held.is_some_and(|held| !held.is_empty())));
        if listed {
            tally.take()?;
            counts.push((category.to_owned(), count));
        }
    }
    Ok(counts)
}

/// [`category_counts`] through the concept's partition view: per category, the cohort
/// patients holding it in each selected partition, summed when the block is untagged (each
/// patient's rows lie in one partition) and unioned first otherwise. A category is held by a
/// selected partition exactly when the view has patients for it there.
///
/// # Errors
/// [`QueryError::LimitExceeded`] when the shared entry cap is exceeded, and
/// [`QueryError::StoreInconsistent`] when the view cannot be built.
fn view_category_counts(
    selection: &Selection<'_>,
    read: &ConceptRead,
    entry: &Arc<CachedConcept>,
    cohort: &RoaringBitmap,
    named: &HashSet<&str>,
    tally: &mut EntryTally<'_>,
) -> Result<Vec<(String, u64)>> {
    let Block::Categorical(categorical) = entry.block().as_ref() else {
        return Ok(Vec::new());
    };
    let view = selection.view(read, entry)?;
    let tagged = categorical.partitions().is_tagged();
    let mut counts = Vec::new();
    for (value, category) in categorical.dictionary().enumerate() {
        let parts: Vec<&RoaringBitmap> = view
            .parts_of(value)
            .map(|part| view.part(part))
            .filter(|(slot, _)| selection.selects(view.partition(*slot)))
            .map(|(_, patients)| patients)
            .collect();
        if parts.is_empty() {
            continue;
        }
        let count = match parts.as_slice() {
            [one] => one.intersection_len(cohort),
            many if tagged => many.iter().copied().union().intersection_len(cohort),
            many => many.iter().map(|part| part.intersection_len(cohort)).sum(),
        };
        if count > 0 || named.contains(category) {
            tally.take()?;
            counts.push((category.to_owned(), count));
        }
    }
    Ok(counts)
}

/// Per distinct value held by a cohort patient in a selected partition of the numeric
/// concept in `entry`, the number of such observations, in Java `Double.compareTo` order.
/// A block read whole is walked once in value order and each distinct value drawn from
/// `tally` as it is found. A concept read through its partition view walks each selected
/// partition's rows in value order and merges them as one block per partition would be
/// merged: one partition draws as it goes; several stop as soon as one alone holds more
/// distinct values for the cohort than the cap, and draw after the merge.
///
/// # Errors
/// [`QueryError::LimitExceeded`] when the shared entry cap is exceeded, and
/// [`QueryError::StoreInconsistent`] when a view cannot be built.
fn value_counts(
    selection: &Selection<'_>,
    read: &ConceptRead,
    entry: &Arc<CachedConcept>,
    cohort: &RoaringBitmap,
    tally: &mut EntryTally<'_>,
) -> Result<Vec<(f64, u64)>> {
    let Block::Numeric(block) = entry.block().as_ref() else {
        return Ok(Vec::new());
    };
    let (values, patients) = (block.values(), block.patients());
    if read.mode != ReadMode::View {
        let in_selection = row_filter(selection, read, entry.block());
        let mut counts: Vec<(f64, u64)> = Vec::new();
        for (position, (&value, &patient)) in values.iter().zip(patients).enumerate() {
            if !cohort.contains(patient) || !in_selection(block.tag_at_value_pos(position)) {
                continue;
            }
            match counts.last_mut() {
                Some((last, count)) if java_double_cmp(*last, value).is_eq() => *count += 1,
                _ => {
                    tally.take()?;
                    counts.push((value, 1));
                }
            }
        }
        return Ok(counts);
    }
    let view = selection.view(read, entry)?;
    let slots: Vec<usize> = view.selected_slots(selection.mask()).collect();
    let single = slots.len() <= 1;
    let cap = tally.limit();
    let mut counts: Vec<(f64, u64)> = Vec::new();
    for &slot in &slots {
        let group_start = counts.len();
        for &position in view.group(slot) {
            let position = position as usize;
            if !cohort.contains(patients[position]) {
                continue;
            }
            let value = values[position];
            match counts[group_start..].last_mut() {
                Some((last, count)) if java_double_cmp(*last, value).is_eq() => *count += 1,
                _ => {
                    if single {
                        tally.take()?;
                    } else if (counts.len() - group_start) as u64 >= cap {
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

    mod guard {
        use std::path::PathBuf;

        use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
        use hpds_etl::merge::{MergeSource, SourceStore, write_store};
        use hpds_format::{CryptoKey, Protection};

        use crate::cohort::fault;
        use crate::model::{PhenotypicClause, PhenotypicFilter, PhenotypicFilterType};
        use crate::{Engine, PartitionOptions, Query, QueryError, ResultType};

        const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";

        /// A store of partitions `p1` (patients 1 to 10) and `p2` (11 to 20), every patient
        /// with one row of `\a\`, in a directory removed with the returned guard.
        fn two_partitions() -> (tempfile::TempDir, PathBuf) {
            store_of(&[("p1", (1..=10).collect()), ("p2", (11..=20).collect())])
        }

        /// A store holding each `(name, patients)` partition, every patient with one row of
        /// `\a\` in each partition listing them, in a directory removed with the returned
        /// guard.
        fn store_of(partitions: &[(&str, Vec<u32>)]) -> (tempfile::TempDir, PathBuf) {
            let dir = tempfile::tempdir().unwrap();
            let key_file = dir.path().join("key");
            std::fs::write(&key_file, KEY).unwrap();
            let protection =
                || Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap());
            let mut sources = Vec::new();
            for (name, patients) in partitions {
                let input = dir.path().join(format!("{name}.csv"));
                let mut text =
                    String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
                for patient in patients {
                    text.push_str(&format!("{patient},\"\\a\\\",\"{patient}.0\",\"\",\n"));
                }
                std::fs::write(&input, text).unwrap();
                let part = dir.path().join(name);
                let mut options = LoadOptions::new(Mode::NewSearch, vec![input], part.clone());
                options.key = KeySource::File(key_file.clone());
                options.threads = Some(1);
                load(&options).unwrap();
                let store = SourceStore::open(&part, protection()).unwrap();
                let observed = Some(store.patients().clone());
                sources.push(MergeSource {
                    name: (*name).to_owned(),
                    store,
                    observed,
                });
            }
            let out = dir.path().join("data");
            std::fs::create_dir_all(&out).unwrap();
            let pool = rayon::ThreadPoolBuilder::new()
                .num_threads(1)
                .build()
                .unwrap();
            write_store(sources, true, &out, &protection(), &pool).unwrap();
            std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
            (dir, out)
        }

        fn engine(out: &std::path::Path, share: f64) -> Engine {
            let options = PartitionOptions {
                require_consents: false,
                narrow_view_share: share,
                ..PartitionOptions::default()
            };
            Engine::open(out, &options).unwrap()
        }

        fn required_in_p1(result_type: ResultType) -> Query {
            Query {
                phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::Required,
                    "\\a\\",
                ))),
                select: vec!["\\a\\".to_owned()],
                ..Query::with_consents(result_type, ["p1"])
            }
        }

        #[test]
        fn a_cohort_left_holding_another_partition_fails_every_result_type() {
            let (_dir, out) = two_partitions();
            let engine = engine(&out, 0.0);
            assert_eq!(
                engine.count(&required_in_p1(ResultType::Count)).unwrap(),
                10
            );
            for result_type in [
                ResultType::Count,
                ResultType::CrossCount,
                ResultType::CategoricalCrossCount,
                ResultType::ContinuousCrossCount,
                ResultType::ObservationCrossCount,
                ResultType::Patients,
                ResultType::Dataframe,
            ] {
                let query = required_in_p1(result_type);
                let broken = fault::skipping_reach(|| match result_type {
                    ResultType::Patients => engine.patients(&query).map(|_| ()),
                    ResultType::Dataframe => engine.dataframe(&query).map(|_| ()),
                    _ => engine.answer_sync(&query).map(|_| ()),
                });
                let error = broken.unwrap_err();
                assert!(
                    matches!(error, QueryError::CohortOutsideSelection { patients: 10 }),
                    "{result_type:?}: {error}"
                );
                assert!(!error.is_client_error());
                assert!(!error.to_string().contains("11"), "no patient id: {error}");
            }
        }

        #[test]
        fn an_overlap_patient_tagged_only_in_unselected_partitions_is_outside() {
            let (_dir, out) = store_of(&[
                ("a", (1..=10).chain([30]).collect()),
                ("b", (11..=20).chain([30]).collect()),
                ("c", (21..=29).collect()),
            ]);
            let engine = engine(&out, crate::NARROW_VIEW_SHARE);
            let set = engine.partitions();
            assert_eq!(set.overlap_patients(), 1);
            let c = set.select_consents(&["c"]).unwrap();
            let walked: roaring::RoaringBitmap = [21, 30].into_iter().collect();
            assert_eq!(c.check_members(&walked), 1, "walked by route");
            let compared: roaring::RoaringBitmap = (21..=30).collect();
            assert_eq!(c.check_members(&compared), 1, "compared as bitmaps");
            let a = set.select_consents(&["a"]).unwrap();
            let in_a: roaring::RoaringBitmap = [1, 30].into_iter().collect();
            assert_eq!(a.check_members(&in_a), 0);
            let all = set.select_consents(&[]).unwrap();
            assert_eq!(all.check_members(&compared), 0);
        }

        #[test]
        fn the_member_check_covers_full_and_narrow_selections() {
            let (_dir, out) = two_partitions();
            let engine = engine(&out, crate::NARROW_VIEW_SHARE);
            let set = engine.partitions();
            let full = set.select_consents(&[]).unwrap();
            let mut cohort = full.patients().into_owned();
            assert_eq!(full.check_members(&cohort), 0);
            cohort.insert(99);
            assert_eq!(full.check_members(&cohort), 1);
            let p1 = set.select_consents(&["p1"]).unwrap();
            let one_of_each: roaring::RoaringBitmap = [1, 13].into_iter().collect();
            assert_eq!(p1.check_members(&one_of_each), 1, "walked by route");
            let wide: roaring::RoaringBitmap = (1..=12).collect();
            assert_eq!(p1.check_members(&wide), 2, "compared as bitmaps");
            let unknown: roaring::RoaringBitmap = [1, 99].into_iter().collect();
            assert_eq!(p1.check_members(&unknown), 1, "in no partition");
            let both = set.select_consents(&["p1", "p2"]).unwrap();
            assert_eq!(both.check_members(&one_of_each), 0);
            assert_eq!(both.check_members(&unknown), 1);
        }
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
