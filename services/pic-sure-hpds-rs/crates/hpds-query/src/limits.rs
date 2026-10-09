//! Bounds on how much work and output one query may ask for, checked before any block is
//! loaded.

use std::collections::HashSet;

use hpds_format::{PhenoIndex, java_string_cmp};

use crate::error::{QueryError, Result};
use crate::model::{PhenotypicClause, PhenotypicFilterType, Query, ResultType};
use crate::validate::check_supported;

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
    /// concepts under its prefix. A prefix named by several filters counts once per filter,
    /// since each filter evaluates its prefix again.
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
    /// Checks `query` against the bounds without recursing, so a deep clause tree cannot
    /// overflow the stack. The clause tree is checked first, then `select`, then the number
    /// of concepts touched. Repeated `select` paths count once, except for a DATAFRAME, whose
    /// rows widen with every repeat. Messages carry counts and limits, never concept paths or
    /// values.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks.
    pub fn check(&self, query: &Query, index: &PhenoIndex) -> Result<()> {
        self.check_for(query, index, SelectUse::of(query.expected_result_type))
    }

    /// Checks `query` against the bounds for an evaluation that uses `select` as `select_use`
    /// says, then refuses unsupported genomic input, and returns the receipt every engine
    /// evaluation requires.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks, and the errors
    /// of [`check_supported`].
    pub(crate) fn admit<'q>(
        &self,
        query: &'q Query,
        index: &PhenoIndex,
        select_use: SelectUse,
    ) -> Result<CheckedQuery<'q>> {
        self.check_for(query, index, select_use)?;
        check_supported(query)?;
        Ok(CheckedQuery { query })
    }

    fn check_for(&self, query: &Query, index: &PhenoIndex, select_use: SelectUse) -> Result<()> {
        let mut concepts: HashSet<&str> = HashSet::new();
        let mut prefixes: Vec<&str> = Vec::new();
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
            match clause {
                PhenotypicClause::Subquery(subquery) => {
                    stack.extend(subquery.phenotypic_clauses.iter().map(|c| (c, depth + 1)));
                }
                PhenotypicClause::Filter(filter)
                    if filter.phenotypic_filter_type == PhenotypicFilterType::AnyRecordOf =>
                {
                    prefixes.push(filter.concept_path.as_str());
                }
                PhenotypicClause::Filter(filter) => {
                    concepts.insert(filter.concept_path.as_str());
                }
            }
        }
        self.check_select(query, select_use)?;
        concepts.extend(query.select.iter().map(String::as_str));
        concepts.extend(
            query
                .authorization_filters
                .iter()
                .map(|f| f.concept_path.as_str()),
        );
        let mut touched = concepts.len();
        for prefix in prefixes {
            touched =
                touched.saturating_add(count_with_prefix(index, prefix, self.max_query_concepts));
            if touched > self.max_query_concepts {
                break;
            }
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
    fn of(result_type: ResultType) -> Self {
        if result_type == ResultType::Dataframe {
            SelectUse::EveryEntry
        } else {
            SelectUse::Distinct
        }
    }
}

/// A query that passed [`QueryLimits`] and [`check_supported`]. Its field is private to this
/// module, so [`QueryLimits::admit`] is the only way to build one, and every evaluation in
/// [`crate::Engine`] and [`crate::cohort`] takes one. An evaluation that skips the checks
/// therefore does not compile.
#[derive(Debug, Clone, Copy)]
pub(crate) struct CheckedQuery<'q> {
    query: &'q Query,
}

impl<'q> CheckedQuery<'q> {
    /// The query that passed the checks.
    pub(crate) fn query(self) -> &'q Query {
        self.query
    }
}

/// The number of concepts whose path starts with `prefix`, counting no further than
/// `ceiling + 1`.
fn count_with_prefix(index: &PhenoIndex, prefix: &str, ceiling: usize) -> usize {
    let concepts = index.concepts();
    let start = concepts.partition_point(|c| java_string_cmp(&c.path, prefix).is_lt());
    concepts[start..]
        .iter()
        .take(ceiling.saturating_add(1))
        .take_while(|c| c.path.starts_with(prefix))
        .count()
}
