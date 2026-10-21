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
use crate::contract::ConsentContract;
use crate::error::{QueryError, Result};
use crate::fan_out;
use crate::genomic::{
    CheckedFilters, ExcerptAnswer, GenomicStore, InfoColumnMeta, InfoSearchEntry,
    NO_VARIANTS_FOUND, VariantLists, VariantResultSettings, VcfExcerpt, excerpt_columns,
    for_each_listed_spec, genomic_cohort, info_column_listing, java_binary_search, not_allowed,
    search_info, search_values, subset_column_ids, variant_lists,
};
use crate::limits::{CheckedQuery, QueryLimits, SelectUse};
use crate::model::{PhenotypicFilter, PhenotypicFilterType, Query, ResultType};
use crate::partition::{ConceptRead, PartitionOptions, PartitionSet, ReadMode, Selection};
use crate::result_cache::{self, ResultCache, ResultCacheStats, SyncAnswer};
use crate::results::{
    CategoricalCrossCounts, ColumnBlock, ConceptCounts, ContinuousCrossCounts, Dataframe,
    DataframeColumn, DataframeRows, PATIENT_ID_FIELD_NAME, SyncResult,
};
use crate::store::CachedConcept;
use crate::validate::{MainQueryValidator, QueryValidator, path_is_variant_spec};
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
/// genomic filters when no [`GenomicStore`] is attached, selects the partitions the
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
    genomic: Option<GenomicStore>,
    variant_settings: VariantResultSettings,
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
            genomic: None,
            variant_settings: VariantResultSettings::default(),
            export_pin_bytes: DEFAULT_EXPORT_PIN_BYTES,
            limits: QueryLimits::default(),
            result_cache: None,
        }
    }

    /// Attaches the genomic store, which answers `genomicFilters`: every cohort is narrowed to
    /// the patients the filters keep (see [`Engine::cohort`]). Without one, a query with
    /// genomic filters is refused with [`QueryError::Unsupported`].
    #[must_use]
    pub fn with_genomic(mut self, store: GenomicStore) -> Self {
        self.genomic = Some(store);
        self
    }

    /// The attached genomic store.
    pub fn genomic(&self) -> Option<&GenomicStore> {
        self.genomic.as_ref()
    }

    /// Sets the settings of the variant result types: the excerpt gates, the id concept, the
    /// INFO column order and the excerpt size bound ([`VariantResultSettings::default`], Java's
    /// defaults, until set).
    #[must_use]
    pub fn with_variant_settings(mut self, settings: VariantResultSettings) -> Self {
        self.variant_settings = settings;
        self
    }

    /// The settings of the variant result types.
    pub fn variant_settings(&self) -> &VariantResultSettings {
        &self.variant_settings
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

    /// Validates `query` against the merged dictionary with the validator of the partition
    /// set's contract and flag: under the harmonization contract the [`QueryValidator`], with
    /// consents required exactly when [`PartitionSet::requires_consents`] (the Java property
    /// `hpds.requireAuthorizationFilter`) says so; under the main contract the
    /// [`MainQueryValidator`], with authorization filters required on the same flag.
    ///
    /// Genomic filters are not validated here, as Java's validator does not look at them;
    /// they are refused when no genomic store is attached.
    ///
    /// # Errors
    /// The errors of [`QueryValidator::validate`] or [`MainQueryValidator::validate`], and
    /// [`QueryError::Unsupported`] for genomic filters without a genomic store.
    pub fn validate(&self, query: &Query) -> Result<()> {
        let required = self.partitions.requires_consents();
        match self.partitions.contract() {
            ConsentContract::Harmonization => QueryValidator {
                require_consents: required,
            }
            .validate(query, &self.partitions)?,
            ConsentContract::Main => MainQueryValidator {
                require_authorization_filter: required,
            }
            .validate(query, &self.partitions)?,
        }
        self.check_genomic_store(query)
    }

    /// Fails when `query` reads the attached genomic store (it has genomic filters or a
    /// genomic result type) and that store changed on disk since it was opened
    /// ([`GenomicStore::check_unchanged`]).
    fn check_genomic_unchanged(&self, query: &Query) -> Result<()> {
        match &self.genomic {
            Some(store)
                if !query.genomic_filters.is_empty() || query.expected_result_type.is_genomic() =>
            {
                store.check_unchanged()
            }
            _ => Ok(()),
        }
    }

    /// Refuses genomic filters when no genomic store is attached. Java fails on them there
    /// with a `NullPointerException`.
    fn check_genomic_store(&self, query: &Query) -> Result<()> {
        if !query.genomic_filters.is_empty() && self.genomic.is_none() {
            return Err(QueryError::Unsupported("genomicFilters".into()));
        }
        Ok(())
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

    /// The cohort (`QueryExecutor.getPatientSubsetForQuery`): patients matching the phenotypic
    /// clause in the partitions the query's consents select, narrowed by the genomic filters.
    ///
    /// The genomic filters are translated as `QueryExecutor` translates them; when none
    /// remains, the phenotypic cohort is the answer. Otherwise they are checked (a refusal does
    /// not depend on the cohort, though Java skips the genomic step for an empty one), and a
    /// non-empty cohort is narrowed to the patients every node's filters keep, unioned over
    /// nodes and groups (see `crate::genomic`). The narrowed cohort passes the same member
    /// check as the phenotypic one.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the query breaks a bound,
    /// [`QueryError::Unsupported`] for genomic filters without a genomic store, [`QueryError::UserConsentsRequired`] when consents are required and
    /// absent, [`QueryError::FilterWithoutCriteria`], [`QueryError::EmptySubquery`],
    /// [`QueryError::MissingOperator`] and [`QueryError::TypeMismatch`] where Java's executor
    /// throws, [`QueryError::GenomicFilter`] for a genomic filter Java fails on,
    /// [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index,
    /// [`QueryError::Block`] and [`QueryError::GenomicBlock`] when a block cannot be loaded,
    /// and [`QueryError::CohortOutsideSelection`] when the cohort holds a patient outside the
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
        self.evaluate(&self.admit(query, SelectUse::Distinct)?)
    }

    /// The cohort of an admitted query: the phenotypic cohort, narrowed by the genomic
    /// filters. Under the main contract the narrowed cohort must lie inside the phenotypic
    /// one, which lies inside the authorized set, so the genomic result types answer over
    /// the authorized set as every other type does.
    fn evaluate<'s>(&'s self, checked: &CheckedQuery<'_, 's>) -> Result<Cow<'s, RoaringBitmap>> {
        let cohort = evaluate_cohort_cow(checked)?;
        let query = checked.query();
        if query.genomic_filters.is_empty() {
            return Ok(cohort);
        }
        let (Some(store), Some(filters)) = (&self.genomic, checked.genomic()) else {
            return Err(QueryError::Unsupported("genomicFilters".into()));
        };
        if !filters.filters.has_filters() {
            return Ok(cohort);
        }
        let found = genomic_cohort(store, filters, &cohort)?;
        crate::cohort::guard(checked.selection(), &found)?;
        if self.partitions.contract() == ConsentContract::Main {
            crate::cohort::guard_within(checked.selection(), &cohort, &found)?;
        }
        Ok(Cow::Owned(found))
    }

    /// The single admission step every computation goes through (see
    /// [`QueryLimits::admit`]), then the refusal of genomic filters without a genomic store,
    /// then the translation of the genomic filters and their checks against the genomic
    /// index ([`CheckedFilters`]), so a filter Java fails on as sent is refused before any
    /// block is read. The receipt it returns is the only way to reach the cohort evaluator
    /// and the result computations.
    fn admit<'q>(&self, query: &'q Query, select_use: SelectUse) -> Result<CheckedQuery<'q, '_>> {
        self.admit_genomic(self.admit_phenotypic(query, select_use)?)
    }

    /// The first part of [`Engine::admit`]: the phenotype store unchanged on disk, then the
    /// limits and the consent selection ([`QueryLimits::admit`]). The excerpt and variant
    /// list gates sit between it and [`Engine::admit_genomic`], since Java checks them before
    /// it reads the query's genomic filters, so a closed gate answers its text even while
    /// the genomic store is refused.
    fn admit_phenotypic<'q>(
        &self,
        query: &'q Query,
        select_use: SelectUse,
    ) -> Result<CheckedQuery<'q, '_>> {
        self.partitions.check_unchanged()?;
        self.limits.admit(query, &self.partitions, select_use)
    }

    /// The second part of [`Engine::admit`]: the genomic store unchanged on disk when the
    /// query reads it, the refusal of genomic filters without a genomic store, then their
    /// translation and checks against the genomic index.
    fn admit_genomic<'q, 's>(
        &'s self,
        checked: CheckedQuery<'q, 's>,
    ) -> Result<CheckedQuery<'q, 's>> {
        let query = checked.query();
        self.check_genomic_unchanged(query)?;
        self.check_genomic_store(query)?;
        let genomic = match &self.genomic {
            Some(store) if !query.genomic_filters.is_empty() => {
                Some(CheckedFilters::new(store, query)?)
            }
            _ => None,
        };
        Ok(checked.with_genomic(genomic))
    }

    fn prepare<'q>(
        &self,
        query: &'q Query,
        select_use: SelectUse,
    ) -> Result<(CheckedQuery<'q, '_>, Cow<'_, RoaringBitmap>)> {
        let checked = self.admit(query, select_use)?;
        let cohort = self.evaluate(&checked)?;
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

    /// The answer to a sync result type, dispatched on `query.expected_result_type`: COUNT,
    /// the cross counts, and the genomic result types ([`Engine::info_column_listing`],
    /// [`Engine::variant_count`], [`Engine::variant_list`], and [`Engine::vcf_excerpt`] written
    /// whole).
    ///
    /// # Errors
    /// [`QueryError::NotSync`] for the export types (DATAFRAME, PATIENTS, DATAFRAME_TIMESERIES, DATAFRAME_PFB), and the
    /// errors of the computation, [`QueryError::UserConsentsRequired`] included.
    pub fn run_sync(&self, query: &Query) -> Result<SyncResult> {
        let result_type = query.expected_result_type;
        if !result_type.is_sync() {
            self.limits.check_shape(query)?;
            return Err(QueryError::NotSync(result_type));
        }
        if result_type.is_genomic() {
            return self.genomic_sync(query);
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
        if !result_type.is_sync() || result_type.is_genomic() {
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

    /// The answer to a genomic result type, dispatched on `query.expected_result_type`.
    /// An excerpt is written whole into the answer; [`Engine::vcf_excerpt`] streams it.
    fn genomic_sync(&self, query: &Query) -> Result<SyncResult> {
        match query.expected_result_type {
            ResultType::InfoColumnListing => {
                Ok(SyncResult::InfoColumnListing(self.info_column_listing()?))
            }
            ResultType::VariantCountForQuery => {
                Ok(SyncResult::VariantCount(self.variant_count(query)?))
            }
            ResultType::VariantListForQuery => {
                Ok(SyncResult::VariantList(self.variant_list(query)?))
            }
            _ => match self.vcf_excerpt(query)? {
                ExcerptAnswer::Text(text) => Ok(SyncResult::Excerpt(text)),
                ExcerptAnswer::Rows(excerpt) => {
                    let mut body = Vec::with_capacity(usize::try_from(excerpt.len()).unwrap_or(0));
                    excerpt.write_to(&mut body)?;
                    Ok(SyncResult::Excerpt(String::from_utf8(body).unwrap_or_else(
                        |e| String::from_utf8_lossy(e.as_bytes()).into_owned(),
                    )))
                }
            },
        }
    }

    /// INFO_COLUMN_LISTING (`QueryExecutor.getInfoStoreMeta`): the genomic store's INFO
    /// columns, repeated per node or per group as the store's layout repeats them, or nothing
    /// without a genomic store. Java answers it without evaluating the query, so nothing in
    /// the query is checked.
    ///
    /// # Errors
    /// [`QueryError::GenomicStoreChanged`] when the genomic store changed on disk since it
    /// was opened.
    pub fn info_column_listing(&self) -> Result<Vec<InfoColumnMeta>> {
        let Some(store) = &self.genomic else {
            return Ok(Vec::new());
        };
        store.check_unchanged()?;
        Ok(info_column_listing(store))
    }

    /// The genomic `info` block of `POST /search` for `term` (Java's `PicSureV3Service.search`):
    /// the INFO columns whose description or key contains the term, ignoring case, one per
    /// key in Java `String` order, each with its values when categorical. Empty without a
    /// genomic store. Not scoped by consent, as in Java.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] when a matching categorical column is missing on some
    /// node, where Java fails; [`QueryError::GenomicStoreChanged`]; and
    /// [`QueryError::GenomicBlock`] when an info block cannot be read.
    pub fn search_info(&self, term: &str) -> Result<Vec<InfoSearchEntry>> {
        let Some(store) = &self.genomic else {
            return Ok(Vec::new());
        };
        store.check_unchanged()?;
        search_info(store, term)
    }

    /// True when some INFO column's description or key contains `term`, ignoring case, the
    /// match [`Engine::search_info`] makes. Reads only the genomic index held in memory, so it
    /// answers even after the genomic store changed on disk. False without a genomic store.
    pub fn search_info_matches(&self, term: &str) -> bool {
        let Some(store) = &self.genomic else {
            return false;
        };
        let needle = term.to_lowercase();
        info_column_listing(store).iter().any(|meta| {
            meta.description.to_lowercase().contains(&needle)
                || meta.key.to_lowercase().contains(&needle)
        })
    }

    /// The values of INFO column `column` containing `query`, ignoring case, in Java's
    /// `HashSet` order (`QueryExecutor.searchInfoConceptValues`), the list `GET
    /// /search/values/` pages. Empty without a genomic store, as Java's no-op processor
    /// answers.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] when the column is missing on some node (an unknown
    /// column included), where Java fails; [`QueryError::GenomicStoreChanged`]; and
    /// [`QueryError::GenomicBlock`] when an info block cannot be read.
    pub fn search_info_values(&self, column: &str, query: &str) -> Result<Vec<String>> {
        let Some(store) = &self.genomic else {
            return Ok(Vec::new());
        };
        store.check_unchanged()?;
        search_values(store, column, query)
    }

    /// VARIANT_COUNT_FOR_QUERY (`CountV3Processor.runVariantCount`): the number of distinct
    /// variant specs the query lists (see [`Engine::variant_list`]), or `None` when the query
    /// has no `genomicFilters`, where Java answers that no query was run. Java has no gate on
    /// it.
    ///
    /// # Errors
    /// The errors of admission (as for [`Engine::cohort`]), of the phenotypic cohort and of
    /// the genomic filters.
    pub fn variant_count(&self, query: &Query) -> Result<Option<u64>> {
        let checked = self.admit(query, SelectUse::Distinct)?;
        if query.genomic_filters.is_empty() {
            return Ok(None);
        }
        let mut count = 0u64;
        if let Some((store, lists, _)) = self.variant_lists_in(&checked)? {
            for_each_listed_spec(store, &lists, |_, _| {
                count += 1;
                Ok(())
            })?;
        }
        Ok(Some(count))
    }

    /// VARIANT_LIST_FOR_QUERY (`VariantListV3Processor.runVariantListQuery`): the body Java
    /// writes, `[` and the listed variant specs joined with `, ` and `]`, or
    /// `VARIANT_LIST query type not allowed` when neither excerpt setting is on. The gate is
    /// checked after the limits and consents and before the genomic filters, as Java checks
    /// it first: with the gate off, a genomic filter Java fails on, or genomic filters without
    /// a genomic store, still answer the gate's text.
    ///
    /// Each node lists variants as `runGetVariantList` does (the INFO seed, the bucket
    /// prefilter count, the per-node 100,000 threshold above which the seed comes back
    /// unfiltered, `he`, `ho` or `hen` meeting the node's patients); a spec two nodes list
    /// appears once. Specs are in the store's spec order; Java's is a hash order.
    ///
    /// # Errors
    /// As for [`Engine::variant_count`], and [`QueryError::LimitExceeded`] when the body
    /// would exceed [`VariantResultSettings::max_excerpt_bytes`].
    pub fn variant_list(&self, query: &Query) -> Result<String> {
        let checked = self.admit_phenotypic(query, SelectUse::Distinct)?;
        if !self.variant_settings.variant_list_enabled() {
            return Ok(not_allowed("VARIANT_LIST"));
        }
        let checked = self.admit_genomic(checked)?;
        crate::cohort::check_authorization_nulls(checked.selection(), query)?;
        let max = self.variant_settings.max_excerpt_bytes;
        let mut body = String::from("[");
        if let Some((store, lists, _)) = self.variant_lists_in(&checked)? {
            for_each_listed_spec(store, &lists, |spec, _| {
                if body.len() > 1 {
                    body.push_str(", ");
                }
                body.push_str(spec);
                if body.len() as u64 >= max {
                    return Err(QueryError::LimitExceeded(format!(
                        "the variant list is longer than the {max} bytes \
                         HPDS_MAX_EXCERPT_BYTES allows"
                    )));
                }
                Ok(())
            })?;
        }
        body.push(']');
        Ok(body)
    }

    /// VCF_EXCERPT and AGGREGATE_VCF_EXCERPT (`VariantListV3Processor.runVcfExcerptQuery`),
    /// by `query.expected_result_type`: VCF_EXCERPT when it is that type, the aggregate form
    /// otherwise. Answers Java's text when the type's setting is off
    /// (`VCF_EXCERPT query type not allowed`, `AGGREGATE_VCF_EXCERPT query type not allowed`)
    /// or the variant list is empty ([`NO_VARIANTS_FOUND`]); the gate is checked before the
    /// genomic filters, as for [`Engine::variant_list`]. Otherwise the excerpt over the
    /// listed variants and the patients the query's genomic filters keep (see
    /// [`VcfExcerpt`]), measured and checked before any of it is written.
    ///
    /// Column 2 counts carriers outside the subset, patients outside the query's consents
    /// included, as Java does; the subset itself passes the cohort guard, and under the main
    /// contract must lie inside the phenotypic cohort, so inside the authorized set.
    ///
    /// # Errors
    /// As for [`Engine::variant_count`]; [`QueryError::LimitExceeded`] for an excerpt over
    /// [`VariantResultSettings::max_excerpt_bytes`]; [`QueryError::GenomicFilter`] where
    /// Java throws while building the excerpt (see [`VcfExcerpt`]) and when `ID_CUBE_NAME`
    /// names a numeric concept some subset patient holds; and [`QueryError::Block`] when
    /// the id concept's block cannot be loaded.
    pub fn vcf_excerpt(&self, query: &Query) -> Result<ExcerptAnswer<'_>> {
        let include_patients = query.expected_result_type == ResultType::VcfExcerpt;
        let checked = self.admit_phenotypic(query, SelectUse::Distinct)?;
        if !self.variant_settings.excerpt_enabled(include_patients) {
            let name = if include_patients {
                ResultType::VcfExcerpt
            } else {
                ResultType::AggregateVcfExcerpt
            };
            return Ok(ExcerptAnswer::Text(not_allowed(&name.to_string())));
        }
        let checked = self.admit_genomic(checked)?;
        crate::cohort::check_authorization_nulls(checked.selection(), query)?;
        let Some((store, lists, cohort)) = self.variant_lists_in(&checked)? else {
            return Ok(ExcerptAnswer::Text(NO_VARIANTS_FOUND.to_owned()));
        };
        let Some(evaluation) = lists.evaluation.as_ref().filter(|_| !lists.is_empty()) else {
            return Ok(ExcerptAnswer::Text(NO_VARIANTS_FOUND.to_owned()));
        };
        let subset = crate::cohort::fault::widened(checked.selection(), evaluation.patients(store));
        crate::cohort::guard(checked.selection(), &subset)?;
        if self.partitions.contract() == ConsentContract::Main {
            crate::cohort::guard_within(checked.selection(), &cohort, &subset)?;
        }
        let columns = excerpt_columns(store, &self.variant_settings.info_column_order);
        let labels = if include_patients {
            self.patient_labels(&checked, &subset_column_ids(store, &subset))?
        } else {
            Vec::new()
        };
        Ok(ExcerptAnswer::Rows(VcfExcerpt::new(
            store,
            lists,
            &subset,
            include_patients,
            columns,
            labels,
            self.variant_settings.max_excerpt_bytes,
        )?))
    }

    /// Every node's variant list for an admitted query, with the phenotypic cohort it was
    /// listed over, or `None` when it has no `genomicFilters`, so nothing is listed.
    fn variant_lists_in<'s>(
        &'s self,
        checked: &CheckedQuery<'_, 's>,
    ) -> Result<Option<(&'s GenomicStore, VariantLists, Cow<'s, RoaringBitmap>)>> {
        let query = checked.query();
        if query.genomic_filters.is_empty() {
            return Ok(None);
        }
        let (Some(store), Some(filters)) = (&self.genomic, checked.genomic()) else {
            return Err(QueryError::Unsupported("genomicFilters".into()));
        };
        let cohort = evaluate_cohort_cow(checked)?;
        let lists = variant_lists(store, filters, &cohort)?;
        Ok(Some((store, lists, cohort)))
    }

    /// The headers of VCF_EXCERPT's patient columns for the patients `ids`: each id, or with
    /// `ID_CUBE_NAME` set the value of that concept for the patient in the selected
    /// partitions, as `PhenoCube.getValueForKey` finds it (a binary search over the rows in
    /// patient order, so a patient with several values gets the one the search lands on), or
    /// the id when the patient has none.
    ///
    /// # Errors
    /// [`QueryError::GenomicFilter`] when the concept is numeric and some patient of `ids`
    /// holds a value (Java's cast to `String` throws), and [`QueryError::Block`] when its
    /// block cannot be loaded.
    fn patient_labels(&self, checked: &CheckedQuery<'_, '_>, ids: &[u32]) -> Result<Vec<String>> {
        let as_ids = || ids.iter().map(u32::to_string).collect();
        let Some(name) = &self.variant_settings.id_cube_name else {
            return Ok(as_ids());
        };
        let selection = checked.selection();
        let Some(read) = selection.concept(name) else {
            return Ok(as_ids());
        };
        let entry = selection.entry(&read)?;
        let block = entry.block();
        let mask = selection.mask();
        let filtered = read.mode != ReadMode::Exact && block.partitions().is_tagged();
        let selected = |tag: Option<u16>| {
            !filtered || tag.is_some_and(|t| mask.get(usize::from(t)).copied().unwrap_or(false))
        };
        match block.as_ref() {
            Block::Categorical(b) => {
                let rows: Vec<usize> = (0..b.patients().len())
                    .filter(|&k| selected(b.tag_at_key_pos(k)))
                    .collect();
                let keys: Vec<u32> = rows.iter().map(|&k| b.patients()[k]).collect();
                Ok(ids
                    .iter()
                    .map(|&id| {
                        java_binary_search(&keys, id)
                            .and_then(|i| b.dictionary_value(b.value_indices()[rows[i]] as usize))
                            .map_or_else(|| id.to_string(), str::to_owned)
                    })
                    .collect())
            }
            Block::Numeric(b) => {
                let (by_key, patients) = (b.by_key(), b.patients());
                let wanted: std::collections::HashSet<u32> = ids.iter().copied().collect();
                let held = by_key.iter().any(|&i| {
                    wanted.contains(&patients[i as usize])
                        && selected(b.tag_at_value_pos(i as usize))
                });
                if held {
                    return Err(QueryError::GenomicFilter(format!(
                        "ID_CUBE_NAME names the numeric concept {name}, whose values Java cannot \
                         use as column headers"
                    )));
                }
                Ok(as_ids())
            }
        }
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
    /// cubes in an unspecified partition order. A `select` entry equal to `patient_id` holds
    /// the patient id, as `MultiValueQueryV3Processor.runQuery` writes it.
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
            .filter(|path| *path != PATIENT_ID_FIELD_NAME)
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
            .map(|path| {
                if path == PATIENT_ID_FIELD_NAME {
                    DataframeColumn::PatientId
                } else {
                    loaded
                        .get(path.as_str())
                        .cloned()
                        .map_or(DataframeColumn::Empty, DataframeColumn::Values)
                }
            })
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
        use roaring::RoaringBitmap;

        use crate::model::{Operator, PhenotypicSubquery};
        use crate::{Engine, NARROW_VIEW_SHARE, PartitionOptions, Query, QueryError, ResultType};

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
            let rows: Vec<(&str, Vec<(u32, &str)>)> = partitions
                .iter()
                .map(|(name, patients)| (*name, patients.iter().map(|&p| (p, "\\a\\")).collect()))
                .collect();
            store_with_rows(&rows)
        }

        /// A store holding each `(name, rows)` partition, one row per `(patient, concept)`
        /// with the patient's id as its value, in a directory removed with the returned guard.
        pub(super) fn store_with_rows(
            partitions: &[(&str, Vec<(u32, &str)>)],
        ) -> (tempfile::TempDir, PathBuf) {
            let dir = tempfile::tempdir().unwrap();
            let key_file = dir.path().join("key");
            std::fs::write(&key_file, KEY).unwrap();
            let protection =
                || Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap());
            let mut sources = Vec::new();
            for (name, rows) in partitions {
                let input = dir.path().join(format!("{name}.csv"));
                let mut text =
                    String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
                for (patient, concept) in rows {
                    text.push_str(&format!("{patient},\"{concept}\",\"{patient}.0\",\"\",\n"));
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

        /// A COUNT of `ANY_RECORD_OF` on `\a\` under consent `p1`.
        fn any_record_in_p1() -> Query {
            Query {
                phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::AnyRecordOf,
                    "\\a\\",
                ))),
                ..Query::with_consents(ResultType::Count, ["p1"])
            }
        }

        /// A COUNT of `ANY_RECORD_OF` on `\a\` under the one consent `consent`.
        fn any_record_in(consent: &str) -> Query {
            Query {
                phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::AnyRecordOf,
                    "\\a\\",
                ))),
                ..Query::with_consents(ResultType::Count, [consent])
            }
        }

        #[test]
        fn any_record_of_uses_a_view_another_query_builds_between_its_reads() {
            let (_dir, out) = two_partitions();
            let engine = engine(&out, 2.0);
            let (counted, fallbacks) = fault::counting_view_fallbacks(|| {
                fault::building_views_between_reads(|| engine.count(&any_record_in_p1()))
            });
            assert_eq!(counted.unwrap(), 10);
            assert_eq!(fallbacks, 0, "the view built between the reads is used");
            assert_eq!(engine.partitions().cohort_guard_refusals(), 0);
        }

        #[test]
        fn any_record_of_without_a_view_stays_within_the_selection() {
            let (_dir, out) = two_partitions();
            let two = engine(&out, 2.0);
            let (counted, fallbacks) = fault::counting_view_fallbacks(|| {
                fault::forgetting_views(|| two.count(&any_record_in_p1()))
            });
            assert_eq!(counted.unwrap(), 10);
            assert_eq!(fallbacks, 1);
            assert_eq!(two.partitions().cohort_guard_refusals(), 0);
            assert_eq!(two.count(&any_record_in_p1()).unwrap(), 10);

            let a = |p| (p, "\\a\\");
            let b = |p| (p, "\\b\\");
            let outside: Vec<(u32, &str)> = (21..=29).map(a).collect();
            let member: Vec<(u32, &str)> = outside.iter().copied().chain([b(30)]).collect();
            for (case, c_rows) in [("30 outside c", outside), ("30 a member of c", member)] {
                let (_dir, out) = store_with_rows(&[
                    ("a", (1..=10).chain([30]).map(a).collect()),
                    ("b", (11..=20).chain([30]).map(a).collect()),
                    ("c", c_rows),
                ]);
                let engine = engine(&out, 2.0);
                assert_eq!(engine.partitions().overlap_patients(), 1, "{case}");
                let (counted, fallbacks) = fault::counting_view_fallbacks(|| {
                    fault::forgetting_views(|| engine.count(&any_record_in("c")))
                });
                assert_eq!(counted.unwrap(), 9, "{case}: 30 has no \\a\\ row in c");
                assert_eq!(fallbacks, 1, "{case}");
                assert_eq!(engine.count(&any_record_in("c")).unwrap(), 9, "{case}");
                assert_eq!(engine.partitions().cohort_guard_refusals(), 0, "{case}");
            }
        }

        /// The concept paths `\c\00\` to `\c\39\`.
        fn prefix_concepts() -> Vec<String> {
            (0..40).map(|c| format!("\\c\\{c:02}\\")).collect()
        }

        /// A store of 40 concepts under `\c\` in partitions `p1` (patients 1 to 10), `p2`
        /// (11 to 20) and `p3` (21 to 30), patient `p` holding concept `c` when `(p + c) % 7`
        /// is 0, and patient 31 holding `\c\03\` in `p1` and `\c\05\` in `p3`, so
        /// two blocks are tagged, in a directory removed with the returned guard.
        fn prefix_store(concepts: &[String]) -> (tempfile::TempDir, PathBuf) {
            let rows = |patients: std::ops::RangeInclusive<u32>, extra: Option<usize>| {
                let mut rows: Vec<(u32, &str)> = Vec::new();
                for p in patients {
                    for (c, path) in concepts.iter().enumerate() {
                        if (p as usize + c).is_multiple_of(7) {
                            rows.push((p, path.as_str()));
                        }
                    }
                }
                if let Some(c) = extra {
                    rows.push((31, concepts[c].as_str()));
                }
                rows
            };
            store_with_rows(&[
                ("p1", rows(1..=10, Some(3))),
                ("p2", rows(11..=20, None)),
                ("p3", rows(21..=30, Some(5))),
            ])
        }

        fn any_record_of_prefix(prefix: &str, consents: &[&str]) -> Query {
            Query {
                phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::AnyRecordOf,
                    prefix,
                ))),
                ..Query::with_consents(ResultType::Patients, consents.iter().copied())
            }
        }

        fn engine_with(out: &std::path::Path, share: f64, budget: u64, cache: u64) -> Engine {
            engine_budgeted(out, share, budget, u64::MAX, cache)
        }

        fn engine_budgeted(
            out: &std::path::Path,
            share: f64,
            chunk: u64,
            budget: u64,
            cache: u64,
        ) -> Engine {
            let options = PartitionOptions {
                require_consents: false,
                contract: crate::ConsentContract::Harmonization,
                narrow_view_share: share,
                prefix_read_bytes: chunk,
                prefix_read_budget_bytes: budget,
                store: crate::StoreOptions {
                    cache_bytes: cache,
                    ..crate::StoreOptions::default()
                },
            };
            Engine::open(out, &options).unwrap()
        }

        #[test]
        fn any_record_of_in_chunks_answers_as_one_read() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let selections: [&[&str]; 5] = [&["p1"], &["p3"], &["p1", "p2"], &["p2", "p3"], &[]];
            for share in [0.0, NARROW_VIEW_SHARE, 2.0] {
                let whole = engine_with(&out, share, u64::MAX, 0);
                for consents in selections {
                    for prefix in ["\\c\\", "\\c\\0", "\\c\\1", "\\c\\05\\"] {
                        let query = any_record_of_prefix(prefix, consents);
                        let expected = whole.patients(&query).unwrap();
                        for budget in [1, 9_000, 40_000] {
                            let chunked = engine_with(&out, share, budget, 0);
                            let selection = chunked.partitions().select_consents(consents).unwrap();
                            let reads = selection.concepts_with_prefix(prefix);
                            if budget == 1 {
                                assert_eq!(selection.read_chunks(&reads).len(), reads.len());
                            }
                            assert_eq!(
                                chunked.patients(&query).unwrap(),
                                expected,
                                "share {share}, {consents:?}, {prefix}, budget {budget}"
                            );
                            let cached = engine_with(&out, share, budget, 1 << 30);
                            for _ in 0..2 {
                                assert_eq!(cached.patients(&query).unwrap(), expected);
                            }
                            assert_eq!(chunked.partitions().cohort_guard_refusals(), 0);
                        }
                    }
                }
            }
            let one = engine_with(&out, 2.0, 1, 0);
            let in_p3 = one.patients(&any_record_of_prefix("\\c\\05\\", &["p3"]));
            let expected: RoaringBitmap = (21..=30u32)
                .filter(|p| (p + 5).is_multiple_of(7))
                .chain([31])
                .collect();
            assert_eq!(in_p3.unwrap(), expected);
        }

        /// A PATIENTS query, under `consents`, of `clause`.
        fn patients_of(clause: PhenotypicClause, consents: &[&str]) -> Query {
            Query {
                phenotypic_clause: Some(clause),
                ..Query::with_consents(ResultType::Patients, consents.iter().copied())
            }
        }

        fn any_record(prefix: &str) -> PhenotypicClause {
            PhenotypicClause::Filter(PhenotypicFilter::new(
                PhenotypicFilterType::AnyRecordOf,
                prefix,
            ))
        }

        fn subquery(operator: Operator, clauses: Vec<PhenotypicClause>) -> PhenotypicClause {
            PhenotypicClause::Subquery(PhenotypicSubquery {
                phenotypic_clauses: clauses,
                operator: Some(operator),
                not: None,
            })
        }

        /// An OR of eight broad `ANY_RECORD_OF` filters, an AND of two nested in it, and a
        /// value filter evaluated beside them.
        fn broad_or() -> PhenotypicClause {
            let mut clauses: Vec<PhenotypicClause> = ["\\c\\0", "\\c\\1", "\\c\\2", "\\c\\3"]
                .iter()
                .chain(&["\\c\\", "\\c\\0", "\\c\\1", "\\c\\2"])
                .map(|prefix| any_record(prefix))
                .collect();
            clauses.push(subquery(
                Operator::And,
                vec![any_record("\\c\\3"), any_record("\\c\\")],
            ));
            let mut range = PhenotypicFilter::new(PhenotypicFilterType::Filter, "\\c\\05\\");
            range.min = Some(0.0);
            clauses.push(PhenotypicClause::Filter(range));
            subquery(Operator::Or, clauses)
        }

        #[test]
        fn parallel_broad_filters_finish_within_the_process_budget() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let consents: [&[&str]; 2] = [&["p1"], &["p1", "p2", "p3"]];
            let unbounded = engine_with(&out, NARROW_VIEW_SHARE, u64::MAX, 0);
            let expected: Vec<RoaringBitmap> = consents
                .iter()
                .map(|c| unbounded.patients(&patients_of(broad_or(), c)).unwrap())
                .collect();
            assert!(expected.iter().all(|found| !found.is_empty()));
            let engine = engine_budgeted(&out, NARROW_VIEW_SHARE, 20_000, 30_000, 0);
            let set = engine.partitions();
            let selection = set.select_consents(&["p1"]).unwrap();
            let one_prefix: u64 = selection
                .concepts_with_prefix("\\c\\0")
                .iter()
                .map(|read| crate::partition::test_support::decoded_bound(set, read.index))
                .sum();
            assert!(
                one_prefix * 8 > 3 * 30_000,
                "eight filters need several times the budget"
            );
            std::thread::scope(|scope| {
                for thread in 0..6 {
                    let (engine, expected, consents) = (&engine, &expected, &consents);
                    scope.spawn(move || {
                        for round in 0..3 {
                            let which = (thread + round) % 2;
                            let query = patients_of(broad_or(), consents[which]);
                            assert_eq!(engine.patients(&query).unwrap(), expected[which]);
                        }
                    });
                }
            });
            let stats = set.prefix_read_stats();
            assert_eq!(stats.held, 0, "every permit is released");
            assert!(stats.peak_held <= 30_000, "{stats:?}");
            assert!(stats.peak_held > 0);
            assert!(
                stats.waits > 0,
                "six queries at once wait for each other: {stats:?}"
            );
            assert_eq!(set.cohort_guard_refusals(), 0);
        }

        #[test]
        fn broad_filters_run_on_threads_of_their_own() {
            use crate::partition::test_support::take_chunk_threads;
            let _slots = crate::cohort::clause_threads_to_itself();
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            for (cache, consents) in [(0, &["p1"][..]), (1 << 30, &["p1", "p2", "p3"][..])] {
                let query = patients_of(broad_or(), consents);
                let expected = engine_with(&out, NARROW_VIEW_SHARE, u64::MAX, 0)
                    .patients(&query)
                    .unwrap();
                let engine = engine_budgeted(&out, NARROW_VIEW_SHARE, 20_000, 30_000, cache);
                let set = engine.partitions();
                take_chunk_threads(set);
                let mut most_threads = 0;
                for _ in 0..10 {
                    assert_eq!(engine.patients(&query).unwrap(), expected);
                    let (threads, on_workers) = take_chunk_threads(set);
                    assert_eq!(on_workers, 0, "no chunk is read on a rayon worker");
                    most_threads = most_threads.max(threads);
                    if most_threads > 1 {
                        break;
                    }
                }
                assert!(
                    most_threads > 1 || set.spare_parallelism() <= 1,
                    "the filters run on several threads, given free clause threads"
                );
                let stats = set.prefix_read_stats();
                assert_eq!(stats.held, 0);
                assert!(stats.peak_held <= 30_000, "{stats:?}");
                assert_eq!(set.cohort_guard_refusals(), 0);
            }
        }

        #[test]
        fn concurrent_queries_stay_under_the_clause_thread_cap() {
            use crate::cohort::{CLAUSE_THREADS_PEAK, clause_thread_cap};
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let query = patients_of(broad_or(), &["p1"]);
            let expected = engine_with(&out, NARROW_VIEW_SHARE, u64::MAX, 0)
                .patients(&query)
                .unwrap();
            let engine = engine_budgeted(&out, NARROW_VIEW_SHARE, 20_000, 30_000, 1 << 30);
            std::thread::scope(|scope| {
                for _ in 0..8 {
                    scope.spawn(|| {
                        for _ in 0..2 {
                            assert_eq!(engine.patients(&query).unwrap(), expected);
                        }
                    });
                }
            });
            let peak = CLAUSE_THREADS_PEAK.load(std::sync::atomic::Ordering::SeqCst);
            assert!(peak <= clause_thread_cap(), "{peak} clause threads at once");
        }

        #[test]
        fn nested_broad_filters_spawn_threads_at_one_level_only() {
            use crate::partition::test_support::take_chunk_threads;
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let half = |prefixes: [&str; 3]| {
                subquery(
                    Operator::Or,
                    prefixes.iter().map(|p| any_record(p)).collect(),
                )
            };
            let clause = subquery(
                Operator::Or,
                vec![
                    half(["\\c\\0", "\\c\\1", "\\c\\2"]),
                    half(["\\c\\3", "\\c\\0", "\\c\\1"]),
                ],
            );
            let query = patients_of(clause, &["p1"]);
            let expected = engine_with(&out, NARROW_VIEW_SHARE, u64::MAX, 0)
                .patients(&query)
                .unwrap();
            let engine = engine_budgeted(&out, NARROW_VIEW_SHARE, 20_000, 30_000, 0);
            let set = engine.partitions();
            take_chunk_threads(set);
            assert_eq!(engine.patients(&query).unwrap(), expected);
            let (threads, on_workers) = take_chunk_threads(set);
            assert_eq!(on_workers, 0);
            assert!(
                threads <= 2,
                "{threads} threads read chunks; two top-level clauses use at most two"
            );
        }

        #[test]
        fn the_first_failing_clause_in_order_fails_the_query() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let engine = engine_budgeted(&out, NARROW_VIEW_SHARE, 20_000, 30_000, 1 << 30);
            let set = engine.partitions();
            let selection = set.select_consents(&["p1"]).unwrap();
            let blocks = out.join(hpds_format::BLOCKS_FILE_NAME);
            let meta = std::fs::metadata(&blocks).unwrap();
            let file = std::fs::OpenOptions::new()
                .read(true)
                .write(true)
                .open(&blocks)
                .unwrap();
            for path in ["\\c\\05\\", "\\c\\25\\"] {
                let index = selection.concept(path).unwrap().index;
                let location = crate::partition::test_support::block_location(set, index);
                let at = location.offset + location.length / 2;
                let mut bytes = [0u8; 8];
                std::os::unix::fs::FileExt::read_exact_at(&file, &mut bytes, at).unwrap();
                let flipped = bytes.map(|b| !b);
                std::os::unix::fs::FileExt::write_all_at(&file, &flipped, at).unwrap();
            }
            file.set_modified(meta.modified().unwrap()).unwrap();
            drop(file);
            let failing = |clauses: Vec<PhenotypicClause>| match engine
                .patients(&patients_of(subquery(Operator::Or, clauses), &["p1"]))
            {
                Err(QueryError::Block { concept_path, .. }) => concept_path,
                other => panic!("{other:?}"),
            };
            let required = |path: &str| {
                PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::Required,
                    path,
                ))
            };
            let clauses = || {
                vec![
                    any_record("\\c\\0"),
                    any_record("\\c\\1"),
                    required("\\c\\25\\"),
                    any_record("\\c\\3"),
                ]
            };
            assert_eq!(
                failing(clauses()),
                "\\c\\05\\",
                "the first clause's error is returned, though the third, run alone on the calling thread, fails before it"
            );
            let mut reversed = clauses();
            reversed.swap(0, 2);
            assert_eq!(failing(reversed), "\\c\\25\\");
            assert_eq!(set.prefix_read_stats().held, 0);
        }

        #[test]
        fn a_chunk_larger_than_the_budget_runs_alone() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let unbounded = engine_with(&out, 2.0, u64::MAX, 0);
            let query = patients_of(broad_or(), &["p3"]);
            let expected = unbounded.patients(&query).unwrap();
            let engine = engine_budgeted(&out, 2.0, u64::MAX, 1, 0);
            std::thread::scope(|scope| {
                for _ in 0..4 {
                    scope.spawn(|| assert_eq!(engine.patients(&query).unwrap(), expected));
                }
            });
            let stats = engine.partitions().prefix_read_stats();
            assert_eq!((stats.held, stats.peak_held), (0, 1));
        }

        #[test]
        fn a_read_waiting_for_the_budget_holds_no_cached_entry() {
            use crate::partition::test_support::{evict, hold_budget, live, settle};
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let query = any_record_of_prefix("\\c\\", &["p1"]);
            let expected = engine_with(&out, 2.0, 20_000, 0).patients(&query).unwrap();
            let engine = engine_budgeted(&out, 2.0, 20_000, 30_000, 1 << 30);
            let set = engine.partitions();
            assert_eq!(engine.patients(&query).unwrap(), expected);
            let selection = set.select_consents(&["p1"]).unwrap();
            let reads = selection.concepts_with_prefix("\\c\\");
            let chunks = selection.read_chunks(&reads);
            let first = &reads[chunks[0].clone()];
            assert!(first.len() > 1);
            evict(set, first[0].index);
            let live = live(set);
            assert!(
                live.live() >= first.len() - 1,
                "the rest of the first chunk is cached"
            );
            let permits = set.prefix_read_stats().permits;
            let held = hold_budget(set, 30_000);
            let waits = set.prefix_read_stats().waits;
            std::thread::scope(|scope| {
                let reader = scope.spawn(|| engine.patients(&query));
                while set.prefix_read_stats().waits == waits {
                    std::thread::sleep(std::time::Duration::from_micros(50));
                }
                for read in &reads {
                    evict(set, read.index);
                }
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
                while live.live() > 0 && std::time::Instant::now() < deadline {
                    settle(set);
                    std::thread::sleep(std::time::Duration::from_millis(1));
                }
                assert_eq!(
                    live.live(),
                    0,
                    "a read waiting for the budget pins none of the entries the cache dropped"
                );
                drop(held);
                assert_eq!(reader.join().unwrap().unwrap(), expected);
            });
            let stats = set.prefix_read_stats();
            assert_eq!(
                stats.permits - permits,
                chunks.len() as u64 + 1,
                "the test's permit and one per chunk, though the first chunk, whose entries \
                 the cache dropped while it waited, asked again for a larger permit"
            );
            assert_eq!(
                stats.waits - waits,
                1,
                "the first chunk's read waited once, however many times it asked"
            );
            assert_eq!(stats.held, 0);
            assert!(stats.peak_held <= 30_000, "{stats:?}");
            assert_eq!(set.cohort_guard_refusals(), 0);
        }

        #[test]
        fn a_read_larger_than_the_cache_builds_no_views() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let query = any_record_of_prefix("\\c\\", &["p1"]);
            let roomy = engine_with(&out, 2.0, 20_000, 1 << 30);
            let selection = roomy.partitions().select_consents(&["p1"]).unwrap();
            assert!(selection.fits_cache(&selection.concepts_with_prefix("\\c\\")));
            let expected = roomy.patients(&query).unwrap();
            assert!(roomy.partitions().cached_views().0 > 0);
            let tight = engine_with(&out, 2.0, 20_000, 100_000);
            let selection = tight.partitions().select_consents(&["p1"]).unwrap();
            assert!(!selection.fits_cache(&selection.concepts_with_prefix("\\c\\")));
            let (found, fallbacks) =
                fault::counting_view_fallbacks(|| tight.patients(&query).unwrap());
            assert_eq!(found, expected);
            assert_eq!(fallbacks, 0, "skipping a view is not a fallback");
            assert_eq!(tight.partitions().cached_views().0, 0);
            assert_eq!(tight.partitions().cohort_guard_refusals(), 0);
        }

        #[test]
        fn chunks_stay_within_the_byte_bound() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            let engine = engine_with(&out, NARROW_VIEW_SHARE, 20_000, 0);
            let selection = engine.partitions().select_consents(&["p1"]).unwrap();
            let reads = selection.concepts_with_prefix("\\c\\");
            let chunks = selection.read_chunks(&reads);
            assert_eq!(chunks.first().map(|c| c.start), Some(0));
            assert_eq!(chunks.last().map(|c| c.end), Some(reads.len()));
            for pair in chunks.windows(2) {
                assert_eq!(pair[0].end, pair[1].start);
            }
            let set = engine.partitions();
            for chunk in &chunks {
                let bytes: u64 = reads[chunk.clone()]
                    .iter()
                    .map(|read| crate::partition::test_support::decoded_bound(set, read.index))
                    .sum();
                assert!(
                    bytes <= 20_000 || chunk.len() == 1,
                    "{chunk:?} holds {bytes} bytes"
                );
            }
            assert!(chunks.len() > 1);
            assert!(selection.read_chunks(&[]).is_empty());
        }

        /// Counts decoded entries alive by object, so it must not count an entry that no
        /// read holds but that `crossbeam-epoch` has not yet freed: `moka` shares each load
        /// with concurrent loaders through a waiter it unlinks with deferred reclamation,
        /// and that waiter keeps a clone of the entry until the global epoch advances. The
        /// count is therefore taken after reclaiming, before each chunk loads and at the end.
        #[test]
        fn peak_live_entries_stay_under_the_chunk_bound() {
            let concepts = prefix_concepts();
            let (_dir, out) = prefix_store(&concepts);
            for share in [0.0, 2.0] {
                for consents in [&["p1"][..], &["p1", "p2", "p3"][..]] {
                    let engine = engine_with(&out, share, 20_000, 0);
                    let set = engine.partitions();
                    let selection = set.select_consents(consents).unwrap();
                    let reads = selection.concepts_with_prefix("\\c\\");
                    let largest = selection
                        .read_chunks(&reads)
                        .iter()
                        .map(std::ops::Range::len)
                        .max()
                        .unwrap();
                    assert!(largest * 4 <= reads.len(), "{largest} of {}", reads.len());
                    let live = crate::partition::test_support::live(set);
                    live.reset_peak();
                    let query = any_record_of_prefix("\\c\\", consents);
                    let (found, held) = fault::watching_chunks(|| engine.patients(&query));
                    assert!(!found.unwrap().is_empty());
                    assert!(
                        held.is_some_and(|held| held <= largest),
                        "{held:?} > {largest}"
                    );
                    assert!(
                        live.peak() <= largest,
                        "share {share}, {consents:?}: {} entries alive at once, chunks of {largest}",
                        live.peak()
                    );
                    assert!(live.peak() > 0);
                    assert_eq!(
                        live.live_after_reclaim(std::time::Duration::from_secs(2)),
                        0,
                        "every entry is dropped once merged"
                    );
                }
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
            assert_eq!(engine.partitions().cohort_guard_refusals(), 7);
        }

        /// `out`, the two-partition store, opened under the main contract with `required` as
        /// `hpds.requireAuthorizationFilter`, with a genomic store beside it in `dir`: one node
        /// of patients 1 to 20 holding `chr1,100,A,G` in gene `G1`, carried by 1, 2 and 15,
        /// and every variant result type allowed.
        fn main_with_genomic(
            out: &std::path::Path,
            dir: &std::path::Path,
            required: bool,
        ) -> Engine {
            use crate::test_genomic::{GroupVcfs, NodeVcf, load_genomic, rec};
            let key = out.join("encryption_key");
            let store_dir = dir.join("genomic");
            load_genomic(
                &dir.join("work"),
                &key,
                hpds_format::GenoMode::LocalDistributed,
                &[GroupVcfs {
                    name: "",
                    nodes: vec![NodeVcf {
                        chrom: "chr1",
                        patients: (1..=20).collect(),
                        records: vec![rec(
                            "chr1",
                            100,
                            "G",
                            "G1",
                            "missense_variant",
                            "0.1",
                            &[(1, "0|1"), (2, "1|1"), (15, "0|1")],
                        )],
                    }],
                }],
                &store_dir,
            );
            let genomic = crate::GenomicStore::open_with_key_file(
                &store_dir,
                &key,
                &crate::GenomicOptions::default(),
            )
            .unwrap();
            let options = PartitionOptions {
                require_consents: required,
                contract: crate::ConsentContract::Main,
                ..PartitionOptions::default()
            };
            Engine::open(out, &options)
                .unwrap()
                .with_genomic(genomic)
                .with_variant_settings(crate::VariantResultSettings {
                    vcf_excerpt_enabled: true,
                    ..crate::VariantResultSettings::default()
                })
        }

        /// The genomic filter keeping the carriers of gene `G1`.
        fn gene_g1() -> crate::GenomicFilter {
            crate::GenomicFilter {
                key: Some("Gene_with_variant".into()),
                values: Some(vec![Some("G1".into())]),
                ..crate::GenomicFilter::default()
            }
        }

        /// Runs `query` as its result type is served, discarding the answer.
        fn run_any(engine: &Engine, query: &Query) -> crate::Result<()> {
            match query.expected_result_type {
                ResultType::Patients => engine.patients(query).map(|_| ()),
                ResultType::Dataframe => engine.dataframe(query).map(|_| ()),
                ResultType::VcfExcerpt | ResultType::AggregateVcfExcerpt => {
                    engine.vcf_excerpt(query).map(|_| ())
                }
                _ => engine.answer_sync(query).map(|_| ()),
            }
        }

        #[test]
        fn under_the_main_contract_a_cohort_outside_the_authorized_set_fails_every_result_type() {
            let (dir, out) = two_partitions();
            let engine = main_with_genomic(&out, dir.path(), true);
            let query = |result_type: ResultType| Query {
                authorization_filters: vec![crate::AuthorizationFilter {
                    concept_path: Some("\\consent\\".into()),
                    values: Some(vec![Some("c1".into())]),
                }],
                genomic_filters: if result_type.is_genomic() {
                    vec![gene_g1()]
                } else {
                    Vec::new()
                },
                ..required_in_p1(result_type)
            };
            assert_eq!(engine.count(&query(ResultType::Count)).unwrap(), 0);
            let result_types = [
                ResultType::Count,
                ResultType::CrossCount,
                ResultType::CategoricalCrossCount,
                ResultType::ContinuousCrossCount,
                ResultType::ObservationCrossCount,
                ResultType::Patients,
                ResultType::Dataframe,
                ResultType::VariantCountForQuery,
                ResultType::VariantListForQuery,
                ResultType::VcfExcerpt,
                ResultType::AggregateVcfExcerpt,
            ];
            for result_type in result_types {
                let query = query(result_type);
                assert!(run_any(&engine, &query).is_ok(), "{result_type:?}");
                let error = fault::skipping_authorization(|| run_any(&engine, &query)).unwrap_err();
                assert!(
                    matches!(
                        error,
                        QueryError::CohortOutsideAuthorizedSet { patients: 20 }
                    ),
                    "{result_type:?}: {error}"
                );
                assert!(!error.is_client_error());
            }
            assert_eq!(
                engine.partitions().cohort_guard_refusals(),
                result_types.len() as u64
            );
        }

        #[test]
        fn under_the_main_contract_an_excerpt_subset_outside_its_cohort_is_refused() {
            let (dir, out) = two_partitions();
            let engine = main_with_genomic(&out, dir.path(), false);
            for result_type in [ResultType::VcfExcerpt, ResultType::AggregateVcfExcerpt] {
                let query = Query {
                    phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter {
                        min: Some(1.0),
                        max: Some(5.0),
                        ..PhenotypicFilter::new(PhenotypicFilterType::Filter, "\\a\\")
                    })),
                    genomic_filters: vec![gene_g1()],
                    ..Query::new(result_type)
                };
                assert!(matches!(
                    engine.vcf_excerpt(&query),
                    Ok(crate::ExcerptAnswer::Rows(_))
                ));
                let error =
                    fault::widening_subsets(|| engine.vcf_excerpt(&query).map(|_| ())).unwrap_err();
                assert!(
                    matches!(
                        error,
                        QueryError::CohortOutsideAuthorizedSet { patients: 15 }
                    ),
                    "{result_type:?}: {error}"
                );
            }
            assert_eq!(engine.partitions().cohort_guard_refusals(), 2);
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

    /// The table of recent block cache entries under study-level reads: it serves only
    /// entries the cache holds, keeps views, and lets every concept reach the cache.
    mod recent_entries {
        use std::path::PathBuf;

        use super::guard::store_with_rows;
        use crate::cohort::fault;
        use crate::model::{PhenotypicClause, PhenotypicFilter, PhenotypicFilterType};
        use crate::{Engine, PartitionOptions, Query, ResultType, StoreOptions};

        /// Concept paths `\<study>\c<n>\` for `n` below `count`.
        fn paths(study: &str, count: usize) -> Vec<String> {
            (0..count)
                .map(|n| format!("\\{study}\\c{n:02}\\"))
                .collect()
        }

        /// A store of partitions `p1` (patients 1 to 10) and `p2` (11 to 20), every patient
        /// with a row of every path in `paths`.
        fn store(paths: &[String]) -> (tempfile::TempDir, PathBuf) {
            let rows = |patients: std::ops::RangeInclusive<u32>| -> Vec<(u32, &str)> {
                paths
                    .iter()
                    .flat_map(|path| patients.clone().map(move |p| (p, path.as_str())))
                    .collect()
            };
            store_with_rows(&[("p1", rows(1..=10)), ("p2", rows(11..=20))])
        }

        fn engine(out: &std::path::Path, cache_bytes: u64, share: f64) -> Engine {
            let options = PartitionOptions {
                require_consents: false,
                narrow_view_share: share,
                store: StoreOptions {
                    cache_bytes,
                    ..StoreOptions::default()
                },
                ..PartitionOptions::default()
            };
            Engine::open(out, &options).unwrap()
        }

        /// A COUNT of `ANY_RECORD_OF` on `prefix`, under `consents` or every partition.
        fn any_record_of(prefix: &str, consents: &[&str]) -> Query {
            Query {
                phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
                    PhenotypicFilterType::AnyRecordOf,
                    prefix,
                ))),
                ..Query::with_consents(ResultType::Count, consents.iter().copied())
            }
        }

        /// The bytes one cached concept of `paths` weighs, read from a roomy cache.
        fn entry_weight(out: &std::path::Path, count: usize) -> u64 {
            let roomy = engine(out, 1 << 30, 0.5);
            roomy.count(&any_record_of("\\s\\", &[])).unwrap();
            roomy.partitions().cached_bytes() / count as u64
        }

        /// Every concept the table of recent entries serves must be one the cache holds,
        /// or one it drops only after the read looked it up, as a lookup through the cache
        /// could equally have returned. An entry the cache has dropped carries the drop
        /// clock's reading, so one dropped before the read looked is told apart from one
        /// dropped while it was being checked; and an entry the cache was removing when
        /// checked must carry its drop mark once every read has returned.
        #[test]
        fn concurrent_study_level_reads_on_a_small_cache_serve_only_cached_entries() {
            let concepts = 40;
            let (_dir, out) = store(&paths("s", concepts));
            let weight = entry_weight(&out, concepts);
            let engine = engine(&out, weight * 3, 2.0);
            let hits = fault::checking_recent_hits(|| {
                std::thread::scope(|scope| {
                    for consents in [&[][..], &["p1"][..]] {
                        let engine = &engine;
                        scope.spawn(move || {
                            let expected = if consents.is_empty() { 20 } else { 10 };
                            for _ in 0..200 {
                                let query = any_record_of("\\s\\", consents);
                                assert_eq!(engine.count(&query).unwrap(), expected);
                            }
                        });
                    }
                });
            });
            let set = engine.partitions();
            assert!(set.cached_bytes() <= weight * 3, "{}", set.cached_bytes());
            assert!(set.block_cache_stats().evictions > 0);
            assert!(hits.served > 0, "{hits:?}");
            assert_eq!(
                hits.dropped_before_serving, 0,
                "{} of {} table hits were entries the cache had dropped before the read",
                hits.dropped_before_serving, hits.served
            );
            for (index, entry) in &hits.unconfirmed {
                let (held, _) = crate::partition::test_support::held_and_served(set, *index);
                assert!(
                    entry.is_dropped()
                        || held.is_some_and(|held| std::sync::Arc::ptr_eq(&held, entry)),
                    "concept {index} was served an entry the cache never dropped and does not hold"
                );
            }
        }

        #[test]
        fn a_view_built_by_one_read_is_served_with_its_entry_to_the_next() {
            let (_dir, out) = store(&paths("s", 1));
            let engine = engine(&out, 1 << 30, 2.0);
            let set = engine.partitions();
            assert_eq!(engine.count(&any_record_of("\\s\\", &[])).unwrap(), 20);
            let block_only = set.cached_bytes();
            let narrow = any_record_of("\\s\\", &["p1"]);
            for _ in 0..5 {
                assert_eq!(engine.count(&narrow).unwrap(), 10);
            }
            let (views, view_bytes) = set.cached_views();
            assert_eq!(views, 1);
            assert!(view_bytes > 0);
            let stats = set.block_cache_stats();
            assert_eq!(
                (stats.loads, stats.misses, stats.hits),
                (1, 1, 5),
                "{stats:?}"
            );
            let cached = set.cached_bytes();
            assert!(
                cached >= block_only + view_bytes,
                "the view is weighed with its block: {cached} cached, block {block_only}, \
                 view {view_bytes}"
            );
        }

        #[test]
        fn a_view_reinsert_never_replaces_an_entry_another_read_loaded_meanwhile() {
            let (_dir, out) = store(&paths("s", 1));
            let engine = engine(&out, 1 << 30, 2.0);
            let narrow = any_record_of("\\s\\", &["p1"]);
            let counted =
                fault::hooking_view_reinsert(crate::partition::test_support::reload, || {
                    engine.count(&narrow)
                });
            assert_eq!(counted.unwrap(), 10);
            let (held, served) =
                crate::partition::test_support::held_and_served(engine.partitions(), 0);
            let held = held.expect("the reloaded entry is cached");
            assert!(
                !held.is_dropped(),
                "the cache holds the entry the other read loaded"
            );
            let served = served.expect("the table serves the reloaded entry");
            assert!(std::sync::Arc::ptr_eq(&held, &served));
            assert_eq!(engine.count(&narrow).unwrap(), 10);
        }

        #[test]
        fn alternating_study_level_reads_send_every_concept_through_the_cache() {
            let per_study = 6;
            let mut all = paths("x", per_study);
            all.extend(paths("y", per_study));
            let (_dir, out) = store(&all);
            let engine = engine(&out, 1 << 30, 0.5);
            let (x, y) = (any_record_of("\\x\\", &[]), any_record_of("\\y\\", &[]));
            engine.count(&x).unwrap();
            engine.count(&y).unwrap();
            let ((), looked_up) = fault::recording_cache_lookups(|| {
                for _ in 0..1_000 {
                    engine.count(&x).unwrap();
                    engine.count(&y).unwrap();
                }
            });
            let mut reached: Vec<usize> = looked_up;
            reached.sort_unstable();
            reached.dedup();
            assert_eq!(reached, (0..2 * per_study).collect::<Vec<_>>());
        }
    }
}
