//! Bounds on how much work and output one query may ask for, checked before any block is
//! loaded.

use std::collections::HashSet;

use crate::error::{QueryError, Result};
use crate::model::{PhenotypicClause, PhenotypicFilterType, Query, ResultType};
use crate::partition::{PartitionSet, Selection};

/// Default most `select` paths one query may name, counted without repeats: 5,000.
pub const DEFAULT_MAX_SELECT_PATHS: usize = 5_000;

/// Default deepest nesting of phenotypic subqueries: 64.
pub const DEFAULT_MAX_CLAUSE_DEPTH: usize = 64;

/// Default most clauses (filters, subqueries and authorization filters) in one query: 10,000.
pub const DEFAULT_MAX_CLAUSE_NODES: usize = 10_000;

/// Default most concepts one query may touch in total: 20,000.
pub const DEFAULT_MAX_QUERY_CONCEPTS: usize = 20_000;

/// Default most entries a categorical or continuous cross count may hold in total: 5,000,000.
pub const DEFAULT_MAX_CROSS_COUNT_ENTRIES: u64 = 5_000_000;

/// The per-query bounds [`crate::Engine`] enforces. Every bound is inclusive: a query at the
/// limit passes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct QueryLimits {
    /// Most distinct `select` paths. A DATAFRAME export also refuses a `select` list that is
    /// longer than this once repeats are counted, since repeats widen every row.
    pub max_select_paths: usize,
    /// Deepest nesting of the phenotypic clause; the outermost clause is depth 1.
    pub max_clause_depth: usize,
    /// Most clauses in the phenotypic clause, plus the authorization filters.
    pub max_clause_nodes: usize,
    /// Most concepts the query touches: the distinct concept paths of `select`, the filters
    /// and the authorization filters, plus, for each `ANY_RECORD_OF` filter, the number of
    /// distinct concepts under its prefix in the union of the selected partitions'
    /// dictionaries. A concept held by several selected partitions counts once, but a prefix
    /// named by several filters counts once per filter, since each filter evaluates its
    /// prefix again.
    pub max_query_concepts: usize,
    /// Most entries, summed over every concept, in a categorical or continuous cross count.
    pub max_cross_count_entries: u64,
}

impl Default for QueryLimits {
    fn default() -> Self {
        QueryLimits {
            max_select_paths: DEFAULT_MAX_SELECT_PATHS,
            max_clause_depth: DEFAULT_MAX_CLAUSE_DEPTH,
            max_clause_nodes: DEFAULT_MAX_CLAUSE_NODES,
            max_query_concepts: DEFAULT_MAX_QUERY_CONCEPTS,
            max_cross_count_entries: DEFAULT_MAX_CROSS_COUNT_ENTRIES,
        }
    }
}

impl QueryLimits {
    /// Checks `query` against every bound: [`QueryLimits::check_shape`], then
    /// [`QueryLimits::check_concepts`] over `selection`. Reads only dictionaries, never a
    /// block.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks.
    pub fn check(&self, query: &Query, selection: &Selection<'_>) -> Result<()> {
        self.check_shape(query)?;
        self.check_concepts(query, selection)
    }

    /// Refuses a query whose phenotypic clause holds a construct Java fails on when it
    /// evaluates it ([`Query::java_failure`], as [`QueryError::Invalid`] with that message),
    /// then checks the bounds that need no dictionary: the clause tree's depth and node count,
    /// then `select`. Walks the tree without recursing, so a deep clause tree cannot
    /// overflow the stack. Repeated `select` paths count once, except for a DATAFRAME, whose
    /// rows widen with every repeat. Messages carry counts and limits, never concept paths
    /// or values.
    ///
    /// # Errors
    /// [`QueryError::Invalid`] for [`Query::java_failure`], and
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks.
    pub fn check_shape(&self, query: &Query) -> Result<()> {
        self.check_shape_for(query, SelectUse::of(query.expected_result_type))
    }

    /// The single admission step every engine evaluation goes through: the shape bounds for
    /// an evaluation that reads `select` as `select_use` says, the consent selection
    /// ([`PartitionSet::select`]), and the concept bound over that selection, in that order.
    /// Returns the receipt every evaluation requires, carrying the selection.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks, and
    /// [`QueryError::UserConsentsRequired`] when consents are required and absent.
    pub(crate) fn admit<'q, 's>(
        &self,
        query: &'q Query,
        set: &'s PartitionSet,
        select_use: SelectUse,
    ) -> Result<CheckedQuery<'q, 's>> {
        self.check_shape_for(query, select_use)?;
        let selection = set.select(query)?;
        self.check_concepts(query, &selection)?;
        Ok(CheckedQuery { query, selection })
    }

    fn check_shape_for(&self, query: &Query, select_use: SelectUse) -> Result<()> {
        if let Some(message) = &query.java_failure {
            return Err(QueryError::Invalid(message.clone()));
        }
        let mut nodes = query.authorization_filters.len();
        let mut stack: Vec<(&PhenotypicClause, usize)> = Vec::new();
        stack.extend(query.phenotypic_clause.as_ref().map(|clause| (clause, 1)));
        while let Some((clause, depth)) = stack.pop() {
            if depth > self.max_clause_depth {
                return Err(QueryError::LimitExceeded(format!(
                    "the phenotypic clause is nested deeper than {} levels (HPDS_MAX_CLAUSE_DEPTH)",
                    self.max_clause_depth
                )));
            }
            nodes += 1;
            if nodes > self.max_clause_nodes {
                return Err(QueryError::LimitExceeded(format!(
                    "the query has more than {} clauses (HPDS_MAX_CLAUSE_NODES)",
                    self.max_clause_nodes
                )));
            }
            if let PhenotypicClause::Subquery(subquery) = clause {
                stack.extend(subquery.phenotypic_clauses.iter().map(|c| (c, depth + 1)));
            }
        }
        self.check_select(query, select_use)
    }

    /// Checks the number of concepts `query` touches in `selection`: its distinct concept
    /// paths, plus, for each `ANY_RECORD_OF` filter, the distinct concepts under its prefix
    /// in the union of the selected partitions' dictionaries. A prefix named by several
    /// filters counts once per filter. The sum saturates, and counting stops past the
    /// bound. Expects a query that passed [`QueryLimits::check_shape`], whose clause count
    /// bounds the walk.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the query touches more than
    /// `max_query_concepts` concepts.
    pub fn check_concepts(&self, query: &Query, selection: &Selection<'_>) -> Result<()> {
        let mut concepts: HashSet<&str> = HashSet::new();
        let mut prefixes: Vec<&str> = Vec::new();
        for filter in query.all_filters() {
            if filter.phenotypic_filter_type == PhenotypicFilterType::AnyRecordOf {
                prefixes.push(filter.concept_path.as_str());
            } else {
                concepts.insert(filter.concept_path.as_str());
            }
        }
        concepts.extend(query.select.iter().map(String::as_str));
        concepts.extend(
            query
                .authorization_filters
                .iter()
                .filter_map(|f| f.concept_path.as_deref()),
        );
        let mut touched = concepts.len();
        for prefix in prefixes {
            if touched > self.max_query_concepts {
                break;
            }
            touched = touched.saturating_add(count_with_prefix(
                selection,
                prefix,
                self.max_query_concepts,
            ));
        }
        if touched > self.max_query_concepts {
            return Err(QueryError::LimitExceeded(format!(
                "the query touches more than {} concepts (HPDS_MAX_QUERY_CONCEPTS)",
                self.max_query_concepts
            )));
        }
        Ok(())
    }

    fn check_select(&self, query: &Query, select_use: SelectUse) -> Result<()> {
        let too_many = || {
            QueryError::LimitExceeded(format!(
                "the query selects more than {} concept paths (HPDS_MAX_SELECT_PATHS)",
                self.max_select_paths
            ))
        };
        if select_use == SelectUse::EveryEntry && query.select.len() > self.max_select_paths {
            return Err(too_many());
        }
        let mut seen = HashSet::new();
        for path in &query.select {
            if seen.insert(path.as_str()) && seen.len() > self.max_select_paths {
                return Err(too_many());
            }
        }
        Ok(())
    }
}

/// How an evaluation reads `select`, which decides whether repeated paths count.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SelectUse {
    /// Each distinct path is read once: the cohort and the cross counts.
    Distinct,
    /// Every entry becomes an output column, repeats included: DATAFRAME rows.
    EveryEntry,
}

impl SelectUse {
    /// The use a query declaring `result_type` makes of `select`.
    pub(crate) fn of(result_type: ResultType) -> Self {
        if result_type == ResultType::Dataframe {
            SelectUse::EveryEntry
        } else {
            SelectUse::Distinct
        }
    }
}

/// A query that passed [`QueryLimits`], with the partitions its
/// consents select. Its fields are private to this module, so [`QueryLimits::admit`] is the
/// only way to build one, and every evaluation in [`crate::Engine`] and the cohort module
/// takes one. An evaluation that skips the checks or the consent selection therefore does
/// not compile.
#[derive(Debug)]
pub(crate) struct CheckedQuery<'q, 's> {
    query: &'q Query,
    selection: Selection<'s>,
}

impl<'q, 's> CheckedQuery<'q, 's> {
    /// The query that passed the checks.
    pub(crate) fn query(&self) -> &'q Query {
        self.query
    }

    /// The partitions the query's consents select.
    pub(crate) fn selection(&self) -> &Selection<'s> {
        &self.selection
    }
}

/// The number of distinct concepts whose path starts with `prefix` in the union of the
/// selected partitions' dictionaries, counting no further than `ceiling + 1`.
fn count_with_prefix(selection: &Selection<'_>, prefix: &str, ceiling: usize) -> usize {
    selection.count_with_prefix(prefix, ceiling.saturating_add(1))
}
