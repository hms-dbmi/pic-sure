//! The engine: one [`Store`] and the v3 result computations of `CountV3Processor`,
//! `PatientV3Processor` and `MultiValueQueryV3Processor`.

use std::borrow::Cow;
use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use hpds_format::{Block, ConceptType, java_double_cmp, java_string_cmp};
use rayon::prelude::*;
use roaring::RoaringBitmap;

use crate::cohort::evaluate_cohort_cow;
use crate::error::{QueryError, Result};
use crate::limits::{CheckedQuery, QueryLimits, SelectUse};
use crate::model::{PhenotypicFilter, PhenotypicFilterType, Query, ResultType};
use crate::results::{
    CategoricalCrossCounts, ConceptCounts, ContinuousCrossCounts, Dataframe, DataframeRows,
    PATIENT_ID_FIELD_NAME, SyncResult,
};
use crate::store::{Store, StoreOptions};
use crate::validate::{QueryValidator, path_is_variant_spec};

/// Default cap on the summed decoded size of the blocks one DATAFRAME export may hold at
/// once: 2 GiB.
pub const DEFAULT_EXPORT_PIN_BYTES: u64 = 2 << 30;

/// The query engine over one store. Share it behind an `Arc`.
///
/// Every computation is blocking: it may read and decrypt blocks, and it fans work out on
/// the rayon pool. From async code, call it inside `tokio::task::spawn_blocking`, or enable
/// the `tokio` feature and use [`Engine::run_blocking`].
///
/// Every computation first checks the query against the engine's [`QueryLimits`] and refuses
/// unsupported genomic input with [`crate::check_supported`], through one admission step whose
/// receipt every evaluation requires. None of them applies [`QueryValidator`]; callers
/// validate with [`Engine::validate`] first, where Java does.
///
/// The engine is the only way to evaluate a query. The cohort evaluator is not public, so a
/// caller cannot evaluate a query without the checks:
///
/// ```compile_fail
/// fn unchecked(store: &hpds_query::Store, query: &hpds_query::Query) {
///     let _ = hpds_query::evaluate_cohort(store, query);
/// }
/// ```
///
/// ```compile_fail
/// fn unbounded_prefix(store: &hpds_query::Store) {
///     let _ = hpds_query::cohort::any_record_of(store, "\\");
/// }
/// ```
#[derive(Debug)]
pub struct Engine {
    store: Store,
    export_pin_bytes: u64,
    limits: QueryLimits,
}

impl Engine {
    /// Opens the store in `dir`; see [`Store::open`].
    ///
    /// # Errors
    /// [`QueryError::Open`] when the store cannot be opened.
    pub fn open(dir: &Path, options: &StoreOptions) -> Result<Self> {
        Ok(Engine::new(Store::open(dir, options)?))
    }

    /// An engine over an already open store.
    pub fn new(store: Store) -> Self {
        Engine {
            store,
            export_pin_bytes: DEFAULT_EXPORT_PIN_BYTES,
            limits: QueryLimits::default(),
        }
    }

    /// Sets the cap on the summed decoded size of the blocks one DATAFRAME export holds at
    /// once ([`DEFAULT_EXPORT_PIN_BYTES`] by default). An export over the cap fails with
    /// [`QueryError::ExportTooWide`].
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

    /// Checks `query` against the per-query bounds, as its declared result type uses them.
    /// Every computation repeats this check itself; callers use it to refuse a query before
    /// queueing it.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks.
    pub fn check_limits(&self, query: &Query) -> Result<()> {
        self.limits.check(query, self.store.index())
    }

    /// The single admission step every computation goes through: the per-query bounds for
    /// `select_use`, then [`crate::check_supported`]. The receipt it returns is the only way to
    /// reach the cohort evaluator and the result computations.
    fn admit<'q>(&self, query: &'q Query, select_use: SelectUse) -> Result<CheckedQuery<'q>> {
        self.limits.admit(query, self.store.index(), select_use)
    }

    fn cohort_of(&self, checked: CheckedQuery<'_>) -> Result<Cow<'_, RoaringBitmap>> {
        evaluate_cohort_cow(&self.store, checked)
    }

    /// The store.
    pub fn store(&self) -> &Store {
        &self.store
    }

    /// Validates `query` with `validator` against this store's concepts.
    ///
    /// # Errors
    /// The errors of [`QueryValidator::validate`].
    pub fn validate(&self, query: &Query, validator: &QueryValidator) -> Result<()> {
        validator.validate(query, self.store.index())
    }

    /// The cohort: patients matching the authorization filters and the phenotypic clause
    /// (`QueryExecutor.getPatientSubsetForQuery` without genomic filters).
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the query breaks a bound,
    /// [`QueryError::Unsupported`] for genomic input, [`QueryError::FilterWithoutCriteria`],
    /// [`QueryError::EmptySubquery`], [`QueryError::MissingOperator`] and
    /// [`QueryError::TypeMismatch`] where Java's executor throws,
    /// [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index, and
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub fn cohort(&self, query: &Query) -> Result<RoaringBitmap> {
        self.cohort_cow(query).map(Cow::into_owned)
    }

    /// [`Engine::cohort`] that borrows the index's patient set instead of copying it when
    /// the query selects every patient.
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn cohort_cow(&self, query: &Query) -> Result<Cow<'_, RoaringBitmap>> {
        self.cohort_of(self.admit(query, SelectUse::Distinct)?)
    }

    /// COUNT: the cohort size (`CountV3Processor.runCounts`).
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn count(&self, query: &Query) -> Result<u64> {
        Ok(self.cohort_cow(query)?.len())
    }

    /// CROSS_COUNT (`CountV3Processor.runCrossCounts`): for each `select` path, the number
    /// of cohort patients with any value for it. An unknown path counts 0, and a path whose
    /// block cannot be loaded counts -1. Each path appears once, in first-occurrence order;
    /// Java returns a `ConcurrentHashMap`, so its key order is hash order and not part of
    /// the contract.
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn cross_count(&self, query: &Query) -> Result<ConceptCounts> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        let cohort = self.cohort_of(checked)?;
        self.cross_count_in(checked, &cohort)
    }

    fn cross_count_in(
        &self,
        checked: CheckedQuery<'_>,
        cohort: &RoaringBitmap,
    ) -> Result<ConceptCounts> {
        let paths = distinct(&checked.query().select);
        let entries = paths
            .par_iter()
            .map(|path| {
                let count = match self.store.block(path) {
                    Ok(None) => 0,
                    Ok(Some(block)) => block.all_patients().intersection_len(cohort) as i64,
                    Err(e) => {
                        tracing::warn!(concept = %path, error = %e, "cross count failed");
                        -1
                    }
                };
                ((*path).to_owned(), count)
            })
            .collect();
        Ok(ConceptCounts { entries })
    }

    /// CATEGORICAL_CROSS_COUNT (`CountV3Processor.runCategoryCrossCounts`).
    ///
    /// The concepts are those of the phenotypic clause's filters (authorization filters are
    /// not included) that are not variant-spec paths and are either value filters (`FILTER`
    /// with non-empty `values`) or `REQUIRED` filters on categorical concepts. Each concept
    /// appears once. For each, every category the concept holds is listed with the number
    /// of cohort patients holding it, when that number is above 0 or a value filter on the
    /// concept named the category. A named value the concept does not hold is not listed. A
    /// concept that is unknown or numeric gets an empty map.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], and [`QueryError::Block`] when a block cannot be loaded.
    pub fn categorical_cross_count(&self, query: &Query) -> Result<CategoricalCrossCounts> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        let cohort = self.cohort_of(checked)?;
        self.categorical_cross_count_in(checked, &cohort)
    }

    fn categorical_cross_count_in(
        &self,
        checked: CheckedQuery<'_>,
        cohort: &RoaringBitmap,
    ) -> Result<CategoricalCrossCounts> {
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
                let mut tally = budget.tally();
                let mut counts: Vec<(String, u64)> = Vec::new();
                if let Some(Block::Categorical(block)) = self.store.block(path)?.as_deref() {
                    for (category, patients) in block.dictionary().zip(block.bitmaps()) {
                        let count = patients.intersection_len(cohort);
                        if count > 0 || named.contains(category) {
                            tally.take()?;
                            counts.push((category.to_owned(), count));
                        }
                    }
                }
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
                && self
                    .store
                    .concept(&filter.concept_path)
                    .is_some_and(|c| c.concept_type == ConceptType::Categorical))
    }

    /// CONTINUOUS_CROSS_COUNT (`CountV3Processor.runContinuousCrossCounts`).
    ///
    /// The concepts are those of the phenotypic clause's filters, of any type, whose path
    /// names a numeric concept. Each appears once. For each, every distinct value held by a
    /// cohort patient is listed with the number of cohort observations holding it (a
    /// patient with the value twice counts twice). Filter bounds narrow the cohort, not the
    /// values listed. Values are distinct under Java `Double.compareTo`: `-0.0` and `0.0`
    /// are separate, every NaN is one value.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], and [`QueryError::Block`] when a block cannot be loaded.
    pub fn continuous_cross_count(&self, query: &Query) -> Result<ContinuousCrossCounts> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        let cohort = self.cohort_of(checked)?;
        self.continuous_cross_count_in(checked, &cohort)
    }

    fn continuous_cross_count_in(
        &self,
        checked: CheckedQuery<'_>,
        cohort: &RoaringBitmap,
    ) -> Result<ContinuousCrossCounts> {
        let paths: BTreeSet<&str> = checked
            .query()
            .all_filters()
            .into_iter()
            .map(|f| f.concept_path.as_str())
            .filter(|path| {
                self.store
                    .concept(path)
                    .is_some_and(|c| c.concept_type == ConceptType::Numeric)
            })
            .collect();
        let budget = EntryBudget::new(self.limits.max_cross_count_entries);
        let mut concepts = paths
            .par_iter()
            .map(|path| {
                let mut tally = budget.tally();
                let mut counts: Vec<(f64, u64)> = Vec::new();
                if let Some(Block::Numeric(block)) = self.store.block(path)?.as_deref() {
                    for (&value, &patient) in block.values().iter().zip(block.patients()) {
                        if !cohort.contains(patient) {
                            continue;
                        }
                        match counts.last_mut() {
                            Some((last, count)) if java_double_cmp(*last, value).is_eq() => {
                                *count += 1;
                            }
                            _ => {
                                tally.take()?;
                                counts.push((value, 1));
                            }
                        }
                    }
                }
                tally.settle()?;
                Ok(((*path).to_owned(), counts))
            })
            .collect::<Result<Vec<_>>>()?;
        concepts.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        Ok(ContinuousCrossCounts { concepts })
    }

    /// OBSERVATION_CROSS_COUNT (`CountV3Processor.runObservationCrossCounts`): for each
    /// `select` path that names a concept, the number of its observations whose patient is
    /// in the cohort. Unknown paths are omitted, a block that cannot be loaded counts -1,
    /// and paths are in Java string order (Java's `TreeMap`).
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn observation_cross_count(&self, query: &Query) -> Result<ConceptCounts> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        let cohort = self.cohort_of(checked)?;
        self.observation_cross_count_in(checked, &cohort)
    }

    fn observation_cross_count_in(
        &self,
        checked: CheckedQuery<'_>,
        cohort: &RoaringBitmap,
    ) -> Result<ConceptCounts> {
        let mut entries: Vec<(String, i64)> = distinct(&checked.query().select)
            .par_iter()
            .filter_map(|path| {
                let count = match self.store.block(path) {
                    Ok(None) => return None,
                    Ok(Some(block)) => observation_patients(&block)
                        .iter()
                        .filter(|&&p| cohort.contains(p))
                        .count() as i64,
                    Err(e) => {
                        tracing::warn!(concept = %path, error = %e, "observation cross count failed");
                        -1
                    }
                };
                Some(((*path).to_owned(), count))
            })
            .collect();
        entries.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        Ok(ConceptCounts { entries })
    }

    /// The answer to a sync result type, dispatched on `query.expected_result_type`.
    ///
    /// # Errors
    /// [`QueryError::Unsupported`] for genomic result types, [`QueryError::NotSync`] for
    /// the export types (DATAFRAME, PATIENTS, DATAFRAME_TIMESERIES, DATAFRAME_PFB), and the
    /// errors of the computation.
    pub fn run_sync(&self, query: &Query) -> Result<SyncResult> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        let result_type = query.expected_result_type;
        if !result_type.is_sync() {
            return Err(QueryError::NotSync(result_type));
        }
        let cohort = self.cohort_of(checked)?;
        match result_type {
            ResultType::Count => Ok(SyncResult::Count(cohort.len())),
            ResultType::CrossCount => self
                .cross_count_in(checked, &cohort)
                .map(SyncResult::CrossCount),
            ResultType::CategoricalCrossCount => self
                .categorical_cross_count_in(checked, &cohort)
                .map(SyncResult::CategoricalCrossCount),
            ResultType::ContinuousCrossCount => self
                .continuous_cross_count_in(checked, &cohort)
                .map(SyncResult::ContinuousCrossCount),
            ResultType::ObservationCrossCount => self
                .observation_cross_count_in(checked, &cohort)
                .map(SyncResult::ObservationCrossCount),
            other => Err(QueryError::NotSync(other)),
        }
    }

    /// PATIENTS: the cohort, which iterates in ascending patient id order. Java writes the
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
    /// ascending (see [`DataframeRows`]). The blocks of every known `select` path are
    /// loaded before this returns and held by the iterator, so iteration does no I/O. They
    /// are loaded in chunks and the load stops as soon as their summed decoded size passes
    /// the export cap ([`Engine::with_export_pin_bytes`]). Every `select` entry is a column,
    /// so repeated paths count toward `max_select_paths` whatever result type the query
    /// declares.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], [`QueryError::Block`] when a block cannot be loaded, and
    /// [`QueryError::ExportTooWide`] when the blocks exceed the cap.
    pub fn dataframe(&self, query: &Query) -> Result<Dataframe> {
        let checked = self.admit(query, SelectUse::EveryEntry)?;
        let cohort = self.cohort_of(checked)?.into_owned();
        let paths = distinct(&query.select);
        let mut loaded: HashMap<&str, Option<Arc<Block>>> = HashMap::with_capacity(paths.len());
        let mut pinned: u64 = 0;
        for chunk in paths.chunks(EXPORT_LOAD_CHUNK) {
            let blocks = chunk
                .par_iter()
                .map(|path| Ok((*path, self.store.block(path)?)))
                .collect::<Result<Vec<_>>>()?;
            for (path, block) in blocks {
                pinned =
                    pinned.saturating_add(block.as_deref().map_or(0, |b| b.heap_size() as u64));
                loaded.insert(path, block);
            }
            if pinned > self.export_pin_bytes {
                return Err(QueryError::ExportTooWide);
            }
        }
        let blocks = query
            .select
            .iter()
            .map(|path| loaded.get(path.as_str()).cloned().flatten())
            .collect();
        let header = std::iter::once(PATIENT_ID_FIELD_NAME.to_owned())
            .chain(query.select.iter().cloned())
            .collect();
        Ok(Dataframe {
            header,
            rows: DataframeRows::new(cohort, blocks),
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
