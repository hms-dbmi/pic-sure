//! Cohort evaluation: `PhenotypicQueryExecutor.getPatientSet` over roaring bitmaps.

use std::borrow::Cow;
use std::sync::Arc;

use hpds_format::Block;
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};

use crate::error::{QueryError, Result};
use crate::limits::CheckedQuery;
use crate::model::{
    Operator, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, PhenotypicSubquery,
};
use crate::store::Store;

/// The patients matching `checked`'s authorization filters and phenotypic clause, borrowing
/// the store's patient set instead of copying it when the query has neither.
///
/// Every authorization filter becomes a `FILTER` on its concept path and values. Those
/// filters and the phenotypic clause, when present, are ANDed together. With neither, the
/// cohort is every patient in the store.
///
/// Genomic filters are ignored here; admission into a [`CheckedQuery`] has refused them.
///
/// # Errors
/// [`QueryError::FilterWithoutCriteria`], [`QueryError::EmptySubquery`],
/// [`QueryError::MissingOperator`] and [`QueryError::TypeMismatch`] where Java's executor
/// throws, [`QueryError::StoreInconsistent`] when a block's kind disagrees with the index,
/// and [`QueryError::Block`] when a block cannot be loaded.
pub(crate) fn evaluate_cohort_cow<'a>(
    store: &'a Store,
    checked: CheckedQuery<'_>,
) -> Result<Cow<'a, RoaringBitmap>> {
    let query = checked.query();
    let authorization: Vec<PhenotypicClause> = query
        .authorization_filters
        .iter()
        .map(|filter| {
            PhenotypicClause::Filter(PhenotypicFilter {
                values: filter.values.clone(),
                ..PhenotypicFilter::new(PhenotypicFilterType::Filter, filter.concept_path.clone())
            })
        })
        .collect();
    let mut clauses: Vec<&PhenotypicClause> = authorization.iter().collect();
    clauses.extend(query.phenotypic_clause.as_ref());
    if clauses.is_empty() {
        return Ok(Cow::Borrowed(store.index().patients()));
    }
    check_structure_all(store, &clauses)?;
    combine(store, &clauses, Operator::And).map(|patients| Cow::Owned(patients.into_owned()))
}

/// A patient set that either borrows a cached block's patients or was computed for this
/// query, so unions and intersections over whole concepts read the cached bitmaps instead
/// of copying them first.
enum Patients {
    Block(Arc<Block>),
    Computed(RoaringBitmap),
}

impl Patients {
    /// The patients of `block`, or the empty set when there is no block.
    fn of_block(block: Option<Arc<Block>>) -> Self {
        block.map_or_else(|| Self::Computed(RoaringBitmap::new()), Self::Block)
    }

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
}

fn check_structure_all(store: &Store, clauses: &[&PhenotypicClause]) -> Result<()> {
    clauses.iter().try_for_each(|c| check_structure(store, c))
}

/// Finds, before any block is loaded, every condition under which Java's executor throws
/// on the request itself, so a bad request fails before it costs any I/O.
fn check_structure(store: &Store, clause: &PhenotypicClause) -> Result<()> {
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
                .try_for_each(|c| check_structure(store, c))
        }
        PhenotypicClause::Filter(filter) => check_filter(store, filter),
    }
}

fn check_filter(store: &Store, filter: &PhenotypicFilter) -> Result<()> {
    if filter.phenotypic_filter_type != PhenotypicFilterType::Filter {
        return Ok(());
    }
    let concept = store.concept(&filter.concept_path);
    match &filter.values {
        Some(values) => {
            if !values.is_empty() && concept.is_some_and(|c| !c.is_categorical()) {
                return Err(QueryError::TypeMismatch {
                    concept_path: filter.concept_path.clone(),
                    expected: "categorical",
                });
            }
        }
        None if filter.min.is_some() || filter.max.is_some() => {
            if concept.is_some_and(|c| c.is_categorical()) {
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

fn evaluate_checked(store: &Store, clause: &PhenotypicClause) -> Result<Patients> {
    match clause {
        PhenotypicClause::Filter(filter) => evaluate_filter(store, filter),
        PhenotypicClause::Subquery(subquery) => {
            let operator = subquery.operator.ok_or(QueryError::MissingOperator)?;
            let children: Vec<&PhenotypicClause> = subquery.phenotypic_clauses.iter().collect();
            combine(store, &children, operator)
        }
    }
}

/// Evaluates `clauses` in parallel and combines them with `operator`: one multi-way union
/// for OR, and for AND one multi-way intersection over the sets ordered smallest first,
/// which stops once the result is empty. Borrowed block bitmaps are read in place (an AND
/// copies only its starting operand), and a single clause is returned as it is.
fn combine(store: &Store, clauses: &[&PhenotypicClause], operator: Operator) -> Result<Patients> {
    let mut sets = clauses
        .par_iter()
        .map(|clause| evaluate_checked(store, clause))
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

fn evaluate_filter(store: &Store, filter: &PhenotypicFilter) -> Result<Patients> {
    let path = filter.concept_path.as_str();
    match filter.phenotypic_filter_type {
        PhenotypicFilterType::Required => store.block(path).map(Patients::of_block),
        PhenotypicFilterType::AnyRecordOf => any_record_of(store, path).map(Patients::Computed),
        PhenotypicFilterType::Filter => match &filter.values {
            Some(values) => patients_with_values(store, path, values).map(Patients::Computed),
            None if filter.min.is_some() || filter.max.is_some() => {
                range_patients(store, path, filter.min, filter.max)
            }
            None => Err(QueryError::FilterWithoutCriteria {
                concept_path: path.to_owned(),
            }),
        },
    }
}

/// Patients with any value for any concept whose path starts with `prefix`, the concept at
/// `prefix` included (`PhenotypeMetaStore.getChildConceptPaths`, a `String.startsWith`
/// test over every concept path). Concepts with no observations are skipped without
/// loading their blocks.
///
/// # Errors
/// [`QueryError::Block`] when a block cannot be loaded.
pub(crate) fn any_record_of(store: &Store, prefix: &str) -> Result<RoaringBitmap> {
    let blocks = store
        .index()
        .with_prefix(prefix)
        .par_iter()
        .filter(|concept| concept.observation_count > 0)
        .map(|concept| store.block(&concept.path))
        .collect::<Result<Vec<_>>>()?;
    Ok(blocks
        .iter()
        .flatten()
        .map(|block| block.all_patients())
        .union())
}

/// Patients with at least one of `values` for the categorical concept at `path`
/// (`PhenoCube.getKeysForValue`, exact match in Java string equality). Values the concept
/// does not hold contribute nothing; an unknown path or an empty `values` gives the empty
/// set.
///
/// # Errors
/// [`QueryError::StoreInconsistent`] when the block is numeric, which the structure check
/// has already ruled out through the index, [`QueryError::Block`] when the block cannot be
/// loaded.
pub(crate) fn patients_with_values(
    store: &Store,
    path: &str,
    values: &[String],
) -> Result<RoaringBitmap> {
    if values.is_empty() {
        return Ok(RoaringBitmap::new());
    }
    match store.block(path)?.as_deref() {
        None => Ok(RoaringBitmap::new()),
        Some(Block::Categorical(block)) => Ok(values
            .iter()
            .filter_map(|value| block.find_value(value))
            .fold(RoaringBitmap::new(), |acc, i| acc | block.bitmap(i))),
        Some(Block::Numeric(_)) => Err(QueryError::StoreInconsistent {
            concept_path: path.to_owned(),
            detail: "the index says categorical but the block is numeric",
        }),
    }
}

/// Patients with at least one value `v` of the numeric concept at `path` such that
/// `min <= v <= max` in Java `Double.compareTo` order (`PhenoCube.getKeysForRange`). A
/// `None` bound is open. An unknown path gives the empty set.
///
/// Java's seek logic yields the same inclusive bounds, except that it throws where this
/// returns the empty set: when `min` exceeds every value (or the concept is empty), and
/// when some value lies strictly between `max` and a larger `min`.
///
/// # Errors
/// [`QueryError::StoreInconsistent`] when the block is categorical, which the structure
/// check has already ruled out through the index, [`QueryError::Block`] when the block
/// cannot be loaded. Borrows the block's patients when the range covers every value.
fn range_patients(
    store: &Store,
    path: &str,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<Patients> {
    let Some(block) = store.block(path)? else {
        return Ok(Patients::Computed(RoaringBitmap::new()));
    };
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
