//! Cohort evaluation: `PhenotypicQueryExecutor.getPatientSet` over roaring bitmaps.

use hpds_format::Block;
use rayon::prelude::*;
use roaring::RoaringBitmap;

use crate::error::{QueryError, Result};
use crate::model::{
    Operator, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, PhenotypicSubquery, Query,
};
use crate::store::Store;

/// The patients matching `query`'s authorization filters and phenotypic clause.
///
/// Every authorization filter becomes a `FILTER` on its concept path and values. Those
/// filters and the phenotypic clause, when present, are ANDed together. With neither, the
/// cohort is every patient in the store.
///
/// Genomic filters are ignored here; [`crate::Engine`] refuses them before evaluating.
///
/// # Errors
/// [`QueryError::FilterWithoutCriteria`], [`QueryError::EmptySubquery`],
/// [`QueryError::MissingOperator`] and [`QueryError::TypeMismatch`] where Java's executor
/// throws, and [`QueryError::Block`] when a block cannot be loaded.
pub fn evaluate_cohort(store: &Store, query: &Query) -> Result<RoaringBitmap> {
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
        return Ok(store.index().patients().clone());
    }
    check_structure_all(store, &clauses)?;
    combine(store, &clauses, Operator::And)
}

/// Patients matching one clause.
///
/// # Errors
/// As for [`evaluate_cohort`].
pub fn evaluate_clause(store: &Store, clause: &PhenotypicClause) -> Result<RoaringBitmap> {
    check_structure(store, clause)?;
    evaluate_checked(store, clause)
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

fn evaluate_checked(store: &Store, clause: &PhenotypicClause) -> Result<RoaringBitmap> {
    match clause {
        PhenotypicClause::Filter(filter) => evaluate_filter(store, filter),
        PhenotypicClause::Subquery(subquery) => {
            let operator = subquery.operator.ok_or(QueryError::MissingOperator)?;
            let children: Vec<&PhenotypicClause> = subquery.phenotypic_clauses.iter().collect();
            combine(store, &children, operator)
        }
    }
}

fn combine(
    store: &Store,
    clauses: &[&PhenotypicClause],
    operator: Operator,
) -> Result<RoaringBitmap> {
    let sets = clauses
        .par_iter()
        .map(|clause| evaluate_checked(store, clause))
        .collect::<Result<Vec<_>>>()?;
    let mut sets = sets.into_iter();
    let first = sets.next().ok_or(QueryError::EmptySubquery)?;
    Ok(match operator {
        Operator::And => sets.fold(first, |acc, set| acc & set),
        Operator::Or => sets.fold(first, |acc, set| acc | set),
    })
}

fn evaluate_filter(store: &Store, filter: &PhenotypicFilter) -> Result<RoaringBitmap> {
    let path = filter.concept_path.as_str();
    match filter.phenotypic_filter_type {
        PhenotypicFilterType::Required => all_patients(store, path),
        PhenotypicFilterType::AnyRecordOf => any_record_of(store, path),
        PhenotypicFilterType::Filter => match &filter.values {
            Some(values) => patients_with_values(store, path, values),
            None if filter.min.is_some() || filter.max.is_some() => {
                patients_in_range(store, path, filter.min, filter.max)
            }
            None => Err(QueryError::FilterWithoutCriteria {
                concept_path: path.to_owned(),
            }),
        },
    }
}

/// Every patient with a value for `path`, empty for an unknown path
/// (`PhenotypicObservationStore.getAllKeys`).
///
/// # Errors
/// [`QueryError::Block`] when the block cannot be loaded.
pub fn all_patients(store: &Store, path: &str) -> Result<RoaringBitmap> {
    Ok(store
        .block(path)?
        .map(|block| block.all_patients().clone())
        .unwrap_or_default())
}

/// Patients with any value for any concept whose path starts with `prefix`, the concept at
/// `prefix` included (`PhenotypeMetaStore.getChildConceptPaths`, a `String.startsWith`
/// test over every concept path). Concepts with no observations are skipped without
/// loading their blocks.
///
/// # Errors
/// [`QueryError::Block`] when a block cannot be loaded.
pub fn any_record_of(store: &Store, prefix: &str) -> Result<RoaringBitmap> {
    let sets = store
        .index()
        .with_prefix(prefix)
        .par_iter()
        .filter(|concept| concept.observation_count > 0)
        .map(|concept| all_patients(store, &concept.path))
        .collect::<Result<Vec<_>>>()?;
    Ok(sets
        .into_iter()
        .fold(RoaringBitmap::new(), |acc, set| acc | set))
}

/// Patients with at least one of `values` for the categorical concept at `path`
/// (`PhenoCube.getKeysForValue`, exact match in Java string equality). Values the concept
/// does not hold contribute nothing; an unknown path or an empty `values` gives the empty
/// set.
///
/// # Errors
/// [`QueryError::TypeMismatch`] when `values` is non-empty and the concept is numeric,
/// [`QueryError::Block`] when the block cannot be loaded.
pub fn patients_with_values(store: &Store, path: &str, values: &[String]) -> Result<RoaringBitmap> {
    if values.is_empty() {
        return Ok(RoaringBitmap::new());
    }
    match store.block(path)?.as_deref() {
        None => Ok(RoaringBitmap::new()),
        Some(Block::Categorical(block)) => Ok(values
            .iter()
            .filter_map(|value| block.find_value(value))
            .fold(RoaringBitmap::new(), |acc, i| acc | block.bitmap(i))),
        Some(Block::Numeric(_)) => Err(QueryError::TypeMismatch {
            concept_path: path.to_owned(),
            expected: "categorical",
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
/// [`QueryError::TypeMismatch`] when the concept is categorical, [`QueryError::Block`]
/// when the block cannot be loaded.
pub fn patients_in_range(
    store: &Store,
    path: &str,
    min: Option<f64>,
    max: Option<f64>,
) -> Result<RoaringBitmap> {
    match store.block(path)?.as_deref() {
        None => Ok(RoaringBitmap::new()),
        Some(Block::Numeric(block)) => {
            let range = block.value_range(min, max);
            if range.len() == block.len() {
                return Ok(block.all_patients().clone());
            }
            let mut patients = block.patients()[range].to_vec();
            patients.sort_unstable();
            patients.dedup();
            Ok(RoaringBitmap::from_sorted_iter(patients).unwrap_or_default())
        }
        Some(Block::Categorical(_)) => Err(QueryError::TypeMismatch {
            concept_path: path.to_owned(),
            expected: "numeric",
        }),
    }
}
