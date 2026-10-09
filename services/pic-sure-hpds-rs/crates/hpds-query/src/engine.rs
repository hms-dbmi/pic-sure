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

use crate::cohort::evaluate_selected;
use crate::error::{QueryError, Result};
use crate::limits::QueryLimits;
use crate::model::{PhenotypicFilter, PhenotypicFilterType, Query, ResultType};
use crate::partition::{PartitionOptions, PartitionSet, Selection};
use crate::results::{
    CategoricalCrossCounts, ConceptCounts, ContinuousCrossCounts, Dataframe, DataframeRows,
    PATIENT_ID_FIELD_NAME, SyncResult,
};
use crate::validate::{QueryValidator, check_supported, path_is_variant_spec};

/// Default cap on the summed decoded size of the blocks one DATAFRAME export may hold at
/// once: 2 GiB.
pub const DEFAULT_EXPORT_PIN_BYTES: u64 = 2 << 30;

/// The query engine over one [`PartitionSet`]. Share it behind an `Arc`.
///
/// Every computation is blocking: it may read and decrypt blocks, and it fans work out on
/// the rayon pool. From async code, call it inside `tokio::task::spawn_blocking`, or enable
/// the `tokio` feature and use [`Engine::run_blocking`].
///
/// Every computation first checks the query's shape against its [`QueryLimits`], refuses
/// unsupported genomic input with [`check_supported`], selects the partitions the query's
/// consents grant ([`PartitionSet::select`]), which fails with
/// [`QueryError::UserConsentsRequired`] when consents are required and absent, and checks
/// the concepts it touches in those partitions against the limits, all before any block is
/// loaded. None of
/// them applies [`QueryValidator`]; callers validate with [`Engine::validate`] first, where
/// Java does.
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
    /// fails with [`QueryError::LimitExceeded`] before any block is loaded.
    #[must_use]
    pub fn with_limits(mut self, limits: QueryLimits) -> Self {
        self.limits = limits;
        self
    }

    /// The per-query bounds.
    pub fn limits(&self) -> &QueryLimits {
        &self.limits
    }

    /// Checks `query` against the per-query bounds, counting the concepts it touches in the
    /// partitions its consents select. Lets a caller refuse such a query before queuing work
    /// for it.
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

    /// Validates `query` with `validator` against the merged dictionary.
    ///
    /// # Errors
    /// The errors of [`QueryValidator::validate`].
    pub fn validate(&self, query: &Query, validator: &QueryValidator) -> Result<()> {
        validator.validate(query, &self.partitions)
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
    /// [`QueryError::Unsupported`] for genomic input, and the errors of
    /// [`crate::evaluate_cohort`].
    pub fn cohort(&self, query: &Query) -> Result<RoaringBitmap> {
        self.cohort_cow(query).map(Cow::into_owned)
    }

    /// [`Engine::cohort`] that borrows the partitions' patient set instead of copying it
    /// when the query selects every patient of one partition or of all of them.
    ///
    /// # Errors
    /// As for [`Engine::cohort`].
    pub fn cohort_cow(&self, query: &Query) -> Result<Cow<'_, RoaringBitmap>> {
        let selection = self.select_within_limits(query)?;
        evaluate_selected(&selection, query)
    }

    /// The selection `query` reads, after the shape bounds, the genomic refusal, consent
    /// selection and the concept bound, in that order.
    fn select_within_limits(&self, query: &Query) -> Result<Selection<'_>> {
        self.limits.check_shape(query)?;
        check_supported(query)?;
        let selection = self.partitions.select(query)?;
        self.limits.check_concepts(query, &selection)?;
        Ok(selection)
    }

    fn prepare(&self, query: &Query) -> Result<(Selection<'_>, Cow<'_, RoaringBitmap>)> {
        let selection = self.select_within_limits(query)?;
        let cohort = evaluate_selected(&selection, query)?;
        Ok((selection, cohort))
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
        let (selection, cohort) = self.prepare(query)?;
        Ok(cross_count_in(&selection, query, &cohort))
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
        let (selection, cohort) = self.prepare(query)?;
        self.categorical_cross_count_in(&selection, query, &cohort)
    }

    fn categorical_cross_count_in(
        &self,
        selection: &Selection<'_>,
        query: &Query,
        cohort: &RoaringBitmap,
    ) -> Result<CategoricalCrossCounts> {
        let mut called_out: HashMap<&str, HashSet<&str>> = HashMap::new();
        for filter in query.all_filters() {
            if !self.is_categorical_cross_count_filter(filter) {
                continue;
            }
            let values = called_out.entry(filter.concept_path.as_str()).or_default();
            if filter.is_categorical_filter() {
                values.extend(filter.values.iter().flatten().map(String::as_str));
            }
        }
        let entries = AtomicU64::new(0);
        let mut concepts = called_out
            .par_iter()
            .map(|(path, named)| {
                let blocks = selection.blocks(path)?;
                let counts = category_counts(&blocks, cohort, named);
                self.reserve_entries(&entries, counts.len())?;
                Ok(((*path).to_owned(), counts))
            })
            .collect::<Result<Vec<_>>>()?;
        concepts.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
        Ok(CategoricalCrossCounts { concepts })
    }

    fn reserve_entries(&self, total: &AtomicU64, added: usize) -> Result<()> {
        let added = added as u64;
        if total
            .fetch_add(added, Ordering::Relaxed)
            .saturating_add(added)
            > self.limits.max_cross_count_entries
        {
            return Err(self.too_many_entries());
        }
        Ok(())
    }

    fn too_many_entries(&self) -> QueryError {
        QueryError::LimitExceeded(format!(
            "the cross count would hold more than {} entries (HPDS_MAX_CROSS_COUNT_ENTRIES); \
             filter on fewer or narrower concepts",
            self.limits.max_cross_count_entries
        ))
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
        let (selection, cohort) = self.prepare(query)?;
        self.continuous_cross_count_in(&selection, query, &cohort)
    }

    fn continuous_cross_count_in(
        &self,
        selection: &Selection<'_>,
        query: &Query,
        cohort: &RoaringBitmap,
    ) -> Result<ContinuousCrossCounts> {
        let paths: BTreeSet<&str> = query
            .all_filters()
            .into_iter()
            .map(|f| f.concept_path.as_str())
            .filter(|path| self.concept_type(path) == Some(ConceptType::Numeric))
            .collect();
        let entries = AtomicU64::new(0);
        let cap = self.limits.max_cross_count_entries;
        let mut concepts = paths
            .par_iter()
            .map(|path| {
                let blocks = selection.blocks(path)?;
                let counts =
                    value_counts(&blocks, cohort, cap).ok_or_else(|| self.too_many_entries())?;
                self.reserve_entries(&entries, counts.len())?;
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
        let (selection, cohort) = self.prepare(query)?;
        Ok(observation_cross_count_in(&selection, query, &cohort))
    }

    /// The answer to a sync result type, dispatched on `query.expected_result_type`.
    ///
    /// # Errors
    /// [`QueryError::Unsupported`] for genomic result types, [`QueryError::NotSync`] for
    /// the export types (DATAFRAME, PATIENTS, DATAFRAME_TIMESERIES, DATAFRAME_PFB), and the
    /// errors of the computation, [`QueryError::UserConsentsRequired`] included.
    pub fn run_sync(&self, query: &Query) -> Result<SyncResult> {
        self.limits.check_shape(query)?;
        check_supported(query)?;
        let result_type = query.expected_result_type;
        if !result_type.is_sync() {
            return Err(QueryError::NotSync(result_type));
        }
        let selection = self.partitions.select(query)?;
        self.limits.check_concepts(query, &selection)?;
        let cohort = evaluate_selected(&selection, query)?;
        match result_type {
            ResultType::Count => Ok(SyncResult::Count(cohort.len())),
            ResultType::CrossCount => Ok(SyncResult::CrossCount(cross_count_in(
                &selection, query, &cohort,
            ))),
            ResultType::CategoricalCrossCount => self
                .categorical_cross_count_in(&selection, query, &cohort)
                .map(SyncResult::CategoricalCrossCount),
            ResultType::ContinuousCrossCount => self
                .continuous_cross_count_in(&selection, query, &cohort)
                .map(SyncResult::ContinuousCrossCount),
            ResultType::ObservationCrossCount => Ok(SyncResult::ObservationCrossCount(
                observation_cross_count_in(&selection, query, &cohort),
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
    /// ([`Engine::with_export_pin_bytes`]).
    ///
    /// A cell merges the patient's values from every selected partition holding the
    /// concept, partition by partition in name order and in input order within one. Java
    /// merges cubes in an unspecified partition order.
    ///
    /// # Errors
    /// As for [`Engine::cohort`], [`QueryError::Block`] when a block cannot be loaded, and
    /// [`QueryError::ExportTooWide`] when the blocks exceed the cap.
    pub fn dataframe(&self, query: &Query) -> Result<Dataframe> {
        let (selection, cohort) = self.prepare(query)?;
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

fn cross_count_in(
    selection: &Selection<'_>,
    query: &Query,
    cohort: &RoaringBitmap,
) -> ConceptCounts {
    let paths = distinct(&query.select);
    let entries = paths
        .par_iter()
        .map(|path| {
            let count = match blocks_or_log(selection, path, "cross count").as_deref() {
                None => -1,
                Some([]) => 0,
                Some([one]) => one.all_patients().intersection_len(cohort) as i64,
                Some(many) => {
                    let patients = many.iter().map(|b| b.all_patients()).union();
                    patients.intersection_len(cohort) as i64
                }
            };
            ((*path).to_owned(), count)
        })
        .collect();
    ConceptCounts { entries }
}

fn observation_cross_count_in(
    selection: &Selection<'_>,
    query: &Query,
    cohort: &RoaringBitmap,
) -> ConceptCounts {
    let mut entries: Vec<(String, i64)> = distinct(&query.select)
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
/// listed when above 0 or `named`, in Java string order.
fn category_counts(
    blocks: &[Arc<Block>],
    cohort: &RoaringBitmap,
    named: &HashSet<&str>,
) -> Vec<(String, u64)> {
    let mut categories: Vec<(&str, &RoaringBitmap)> = blocks
        .iter()
        .filter_map(|block| match block.as_ref() {
            Block::Categorical(b) => Some(b.dictionary().zip(b.bitmaps())),
            Block::Numeric(_) => None,
        })
        .flatten()
        .collect();
    categories.sort_by(|a, b| java_string_cmp(a.0, b.0));
    categories
        .chunk_by(|a, b| a.0 == b.0)
        .filter_map(|group| {
            let category = group[0].0;
            let count = match group {
                [(_, one)] => one.intersection_len(cohort),
                many => many
                    .iter()
                    .map(|(_, bitmap)| *bitmap)
                    .union()
                    .intersection_len(cohort),
            };
            (count > 0 || named.contains(category)).then(|| (category.to_owned(), count))
        })
        .collect()
}

/// Per distinct value held by a cohort patient in any numeric block of `blocks`, the number
/// of such observations over all of them, in Java `Double.compareTo` order. `None` as soon
/// as one block alone holds more than `cap` distinct values for the cohort, since the merged
/// list is at least that long; the caller checks the merged length against the cap.
fn value_counts(
    blocks: &[Arc<Block>],
    cohort: &RoaringBitmap,
    cap: u64,
) -> Option<Vec<(f64, u64)>> {
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
                    counts.push((value, 1));
                    if (counts.len() - block_start) as u64 > cap {
                        return None;
                    }
                }
            }
        }
    }
    if blocks.len() > 1 {
        counts.sort_by(|a, b| java_double_cmp(a.0, b.0));
        counts.dedup_by(|later, earlier| {
            let same = java_double_cmp(later.0, earlier.0).is_eq();
            if same {
                earlier.1 += later.1;
            }
            same
        });
    }
    Some(counts)
}
