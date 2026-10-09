//! Cohort evaluation: `PhenotypicQueryExecutor.getPatientSet` over roaring bitmaps, across
//! the partitions a query selects.
//!
//! Every filter is evaluated in each selected partition and the per-partition patient sets
//! are ORed, before filters combine. So a patient present in two partitions can satisfy one
//! filter from each, as in Java, where every store call unions over partitions.

use std::borrow::Cow;
use std::sync::Arc;

use hpds_format::{Block, ConceptType};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};

use crate::error::{QueryError, Result};
use crate::model::{
    Operator, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, PhenotypicSubquery, Query,
};
use crate::partition::{PartitionSet, Selection};

/// The patients matching `query`'s phenotypic clause within the partitions its consents
/// select. With no clause, the cohort is every patient of those partitions. Authorization
/// filters are never applied.
///
/// Genomic filters are ignored here; [`crate::Engine`] refuses them before evaluating.
///
/// # Errors
/// [`QueryError::UserConsentsRequired`] when consents are required and the query has none,
/// [`QueryError::FilterWithoutCriteria`], [`QueryError::EmptySubquery`],
/// [`QueryError::MissingOperator`] and [`QueryError::TypeMismatch`] where Java's executor
/// throws, [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index,
/// and [`QueryError::Block`] when a block cannot be loaded.
pub fn evaluate_cohort(set: &PartitionSet, query: &Query) -> Result<RoaringBitmap> {
    evaluate_cohort_cow(set, query).map(Cow::into_owned)
}

/// [`evaluate_cohort`] that borrows the partitions' patient set instead of copying it when
/// the query has no phenotypic clause and selects one partition or all of them.
///
/// # Errors
/// As for [`evaluate_cohort`].
pub fn evaluate_cohort_cow<'a>(
    set: &'a PartitionSet,
    query: &Query,
) -> Result<Cow<'a, RoaringBitmap>> {
    let selection = set.select(query)?;
    evaluate_selected(&selection, query)
}

/// The cohort of `query` within an explicit `selection`, consents aside.
///
/// # Errors
/// As for [`evaluate_cohort`], except [`QueryError::UserConsentsRequired`].
pub fn evaluate_selected<'a>(
    selection: &Selection<'a>,
    query: &Query,
) -> Result<Cow<'a, RoaringBitmap>> {
    match &query.phenotypic_clause {
        None => Ok(selection.patients()),
        Some(clause) => {
            check_structure(selection.set(), clause)?;
            evaluate_checked(selection, clause).map(|patients| Cow::Owned(patients.into_owned()))
        }
    }
}

/// A patient set that either borrows a cached block's patients or was computed for this
/// query, so unions and intersections over whole concepts read the cached bitmaps instead
/// of copying them first.
enum Patients {
    Block(Arc<Block>),
    Computed(RoaringBitmap),
}

impl Patients {
    /// The patient bitmap, borrowed.
    fn bitmap(&self) -> &RoaringBitmap {
        match self {
            Self::Block(block) => block.all_patients(),
            Self::Computed(bitmap) => bitmap,
        }
    }

    /// The patient bitmap, copied out of the block when it is borrowed.
    fn into_owned(self) -> RoaringBitmap {
        match self {
            Self::Block(block) => block.all_patients().clone(),
            Self::Computed(bitmap) => bitmap,
        }
    }

    /// The union of `sets`: the empty set for none, the one set as it is, and one
    /// multi-way union otherwise.
    fn union(mut sets: Vec<Patients>) -> Patients {
        if sets.len() <= 1 {
            return sets
                .pop()
                .unwrap_or_else(|| Patients::Computed(RoaringBitmap::new()));
        }
        Patients::Computed(sets.iter().map(Patients::bitmap).union())
    }
}

/// Patients matching one clause within `selection`.
///
/// # Errors
/// As for [`evaluate_selected`].
pub fn evaluate_clause(
    selection: &Selection<'_>,
    clause: &PhenotypicClause,
) -> Result<RoaringBitmap> {
    check_structure(selection.set(), clause)?;
    evaluate_checked(selection, clause).map(Patients::into_owned)
}

/// Finds, before any block is loaded, every condition under which Java's executor throws
/// on the request itself, so a bad request fails before it costs any I/O. Types come from
/// the merged dictionary.
fn check_structure(set: &PartitionSet, clause: &PhenotypicClause) -> Result<()> {
    match clause {
        PhenotypicClause::Subquery(PhenotypicSubquery {
            phenotypic_clauses,
            operator,
            ..
        }) => {
            if operator.is_none() {
                return Err(QueryError::MissingOperator);
            }
            if phenotypic_clauses.is_empty() {
                return Err(QueryError::EmptySubquery);
            }
            phenotypic_clauses
                .iter()
                .try_for_each(|c| check_structure(set, c))
        }
        PhenotypicClause::Filter(filter) => check_filter(set, filter),
    }
}

fn check_filter(set: &PartitionSet, filter: &PhenotypicFilter) -> Result<()> {
    if filter.phenotypic_filter_type != PhenotypicFilterType::Filter {
        return Ok(());
    }
    let concept_type = set.summary(&filter.concept_path).map(|c| c.concept_type());
    match &filter.values {
        Some(values) => {
            if !values.is_empty() && concept_type == Some(ConceptType::Numeric) {
                return Err(QueryError::TypeMismatch {
                    concept_path: filter.concept_path.clone(),
                    expected: "categorical",
                });
            }
        }
        None if filter.min.is_some() || filter.max.is_some() => {
            if concept_type == Some(ConceptType::Categorical) {
                return Err(QueryError::TypeMismatch {
                    concept_path: filter.concept_path.clone(),
                    expected: "numeric",
                });
            }
        }
        None => {
            return Err(QueryError::FilterWithoutCriteria {
                concept_path: filter.concept_path.clone(),
            });
        }
    }
    Ok(())
}

fn evaluate_checked(selection: &Selection<'_>, clause: &PhenotypicClause) -> Result<Patients> {
    match clause {
        PhenotypicClause::Filter(filter) => evaluate_filter(selection, filter),
        PhenotypicClause::Subquery(subquery) => {
            let operator = subquery.operator.ok_or(QueryError::MissingOperator)?;
            combine(selection, &subquery.phenotypic_clauses, operator)
        }
    }
}

/// Evaluates `clauses` in parallel and combines them with `operator`: one multi-way union
/// for OR, and for AND one multi-way intersection over the sets ordered smallest first,
/// which stops once the result is empty. Borrowed block bitmaps are read in place (an AND
/// copies only its starting operand), and a single clause is returned as it is.
fn combine(
    selection: &Selection<'_>,
    clauses: &[PhenotypicClause],
    operator: Operator,
) -> Result<Patients> {
    let mut sets = clauses
        .par_iter()
        .map(|clause| evaluate_checked(selection, clause))
        .collect::<Result<Vec<_>>>()?;
    if sets.len() <= 1 {
        return sets.pop().ok_or(QueryError::EmptySubquery);
    }
    let mut bitmaps: Vec<&RoaringBitmap> = sets.iter().map(Patients::bitmap).collect();
    Ok(Patients::Computed(match operator {
        Operator::Or => bitmaps.union(),
        Operator::And => {
            bitmaps.sort_unstable_by_key(|bitmap| bitmap.len());
            bitmaps.intersection()
        }
    }))
}

fn evaluate_filter(selection: &Selection<'_>, filter: &PhenotypicFilter) -> Result<Patients> {
    let path = filter.concept_path.as_str();
    match filter.phenotypic_filter_type {
        PhenotypicFilterType::Required => required_patients(selection, path),
        PhenotypicFilterType::AnyRecordOf => any_record_of(selection, path).map(Patients::Computed),
        PhenotypicFilterType::Filter => match &filter.values {
            Some(values) => patients_with_values(selection, path, values).map(Patients::Computed),
            None if filter.min.is_some() || filter.max.is_some() => {
                range_patients(selection, path, filter.min, filter.max)
            }
            None => Err(QueryError::FilterWithoutCriteria {
                concept_path: path.to_owned(),
            }),
        },
    }
}

fn required_patients(selection: &Selection<'_>, path: &str) -> Result<Patients> {
    let blocks = selection.blocks(path)?;
    Ok(Patients::union(
        blocks.into_iter().map(Patients::Block).collect(),
    ))
}

/// Every patient with a value for `path` in any selected partition, empty for an unknown
/// path (`PartitionedPhenotypicObservationStore.getAllKeys`).
///
/// # Errors
/// [`QueryError::Block`] when a block cannot be loaded.
pub fn all_patients(selection: &Selection<'_>, path: &str) -> Result<RoaringBitmap> {
    required_patients(selection, path).map(Patients::into_owned)
}

/// Patients with any value for any concept whose path starts with `prefix`, the concept at
/// `prefix` included, in any selected partition (`getChildConceptPaths` over the merged
/// dictionary, a `String.startsWith` test, then `getAllKeys` for each). Each partition reads
/// its own concepts under the prefix, which together are the merged dictionary's. Concepts
/// with no observations are skipped without loading their blocks.
///
/// # Errors
/// [`QueryError::Block`] when a block cannot be loaded.
pub fn any_record_of(selection: &Selection<'_>, prefix: &str) -> Result<RoaringBitmap> {
    let wanted: Vec<_> = selection
        .partitions()
        .iter()
        .flat_map(|partition| {
            partition
                .store()
                .index()
                .with_prefix(prefix)
                .iter()
                .filter(|concept| concept.observation_count > 0)
                .map(move |concept| (partition, concept.path.as_str()))
        })
        .collect();
    let blocks = wanted
        .par_iter()
        .map(|(partition, path)| partition.store().block(path))
        .collect::<Result<Vec<_>>>()?;
    Ok(blocks
        .iter()
        .flatten()
        .map(|block| block.all_patients())
        .union())
}

/// Patients with at least one of `values` for the categorical concept at `path` in any
/// selected partition (`PhenoCube.getKeysForValue`, exact match in Java string equality).
/// Values a partition does not hold contribute nothing; an unknown path or an empty
/// `values` gives the empty set.
///
/// # Errors
/// [`QueryError::StoreInconsistent`] when a block is numeric, which the structure check
/// has already ruled out through the dictionary, [`QueryError::Block`] when a block cannot
/// be loaded.
pub fn patients_with_values(
    selection: &Selection<'_>,
    path: &str,
    values: &[String],
) -> Result<RoaringBitmap> {
    if values.is_empty() {
        return Ok(RoaringBitmap::new());
    }
    let blocks = selection.blocks(path)?;
    let mut matched: Vec<&RoaringBitmap> = Vec::new();
    for block in &blocks {
        match block.as_ref() {
            Block::Categorical(block) => matched.extend(
                values
                    .iter()
                    .filter_map(|value| block.find_value(value))
                    .map(|i| block.bitmap(i)),
            ),
            Block::Numeric(_) => {
                return Err(QueryError::StoreInconsistent {
                    concept_path: path.to_owned(),
                    detail: "the index says categorical but the block is numeric",
                });
            }
        }
    }
    Ok(matched.union())
}

/// Patients with at least one value `v` of the numeric concept at `path`, in any selected
/// partition, such that `min <= v <= max` in Java `Double.compareTo` order
/// (`PhenoCube.getKeysForRange`). A `None` bound is open. An unknown path gives the empty
/// set.
///
/// Java's seek logic yields the same inclusive bounds, except that it throws where this
/// returns the empty set: when `min` exceeds every value (or the concept is empty), and
/// when some value lies strictly between `max` and a larger `min`.
///
/// # Errors
/// [`QueryError::StoreInconsistent`] when a block is categorical, which the structure check
/// has already ruled out through the dictionary, [`QueryError::Block`] when a block cannot
/// be loaded.
pub fn patients_in_range(
    selection: &Selection<'_>,
    path: &str,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<RoaringBitmap> {
    range_patients(selection, path, min, max).map(Patients::into_owned)
}

/// [`patients_in_range`], borrowing a block's patients when one partition holds the concept
/// and the range covers every value.
fn range_patients(
    selection: &Selection<'_>,
    path: &str,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<Patients> {
    let parts = selection
        .blocks(path)?
        .into_iter()
        .map(|block| block_range(path, block, min, max))
        .collect::<Result<Vec<_>>>()?;
    Ok(Patients::union(parts))
}

fn block_range(
    path: &str,
    block: Arc<Block>,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<Patients> {
    match block.as_ref() {
        Block::Numeric(numeric) => {
            let range = numeric.value_range(min, max);
            if range.len() == numeric.len() {
                return Ok(Patients::Block(block));
            }
            let mut patients = numeric.patients()[range].to_vec();
            patients.sort_unstable();
            patients.dedup();
            Ok(Patients::Computed(
                RoaringBitmap::from_sorted_iter(patients).unwrap_or_default(),
            ))
        }
        Block::Categorical(_) => Err(QueryError::StoreInconsistent {
            concept_path: path.to_owned(),
            detail: "the index says numeric but the block is categorical",
        }),
    }
}
