//! Bounds on how much work and output one query may ask for, checked before any block is
//! loaded.

use std::collections::HashSet;

use hpds_format::java_string_cmp;

use crate::error::{QueryError, Result};
use crate::model::{PhenotypicClause, PhenotypicFilterType, Query, ResultType};
use crate::partition::Selection;

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
    /// and the authorization filters, plus, for each distinct `ANY_RECORD_OF` prefix, the
    /// number of distinct concepts under it in the union of the selected partitions'
    /// dictionaries. A concept held by several selected partitions counts once.
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

    /// Checks the bounds that need no dictionary: the clause tree's depth and node count,
    /// then `select`. Walks the tree without recursing, so a deep clause tree cannot
    /// overflow the stack. Messages carry counts and limits, never concept paths or values.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] naming the first bound the query breaks.
    pub fn check_shape(&self, query: &Query) -> Result<()> {
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
        self.check_select(query)
    }

    /// Checks the number of concepts `query` touches in `selection`: its distinct concept
    /// paths, plus the distinct concepts under each distinct `ANY_RECORD_OF` prefix in the
    /// union of the selected partitions' dictionaries. Stops counting a prefix past the
    /// bound. Expects a query that passed [`QueryLimits::check_shape`], whose clause count
    /// bounds the walk.
    ///
    /// # Errors
    /// [`QueryError::LimitExceeded`] when the query touches more than
    /// `max_query_concepts` concepts.
    pub fn check_concepts(&self, query: &Query, selection: &Selection<'_>) -> Result<()> {
        let mut concepts: HashSet<&str> = HashSet::new();
        let mut prefixes: HashSet<&str> = HashSet::new();
        for filter in query.all_filters() {
            if filter.phenotypic_filter_type == PhenotypicFilterType::AnyRecordOf {
                prefixes.insert(filter.concept_path.as_str());
            } else {
                concepts.insert(filter.concept_path.as_str());
            }
        }
        concepts.extend(query.select.iter().map(String::as_str));
        concepts.extend(
            query
                .authorization_filters
                .iter()
                .map(|f| f.concept_path.as_str()),
        );
        let mut touched = concepts.len();
        for prefix in prefixes {
            if touched > self.max_query_concepts {
                break;
            }
            touched += count_with_prefix(selection, prefix, self.max_query_concepts);
        }
        if touched > self.max_query_concepts {
            return Err(QueryError::LimitExceeded(format!(
                "the query touches more than {} concepts (HPDS_MAX_QUERY_CONCEPTS)",
                self.max_query_concepts
            )));
        }
        Ok(())
    }

    fn check_select(&self, query: &Query) -> Result<()> {
        let too_many = || {
            QueryError::LimitExceeded(format!(
                "the query selects more than {} concept paths (HPDS_MAX_SELECT_PATHS)",
                self.max_select_paths
            ))
        };
        if query.expected_result_type == ResultType::Dataframe
            && query.select.len() > self.max_select_paths
        {
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

/// The number of distinct concepts whose path starts with `prefix` in the union of the
/// selected partitions' dictionaries, counting no further than `ceiling + 1`. Every
/// partition's concepts are sorted in Java string order, so each one's run under the prefix
/// is contiguous.
fn count_with_prefix(selection: &Selection<'_>, prefix: &str, ceiling: usize) -> usize {
    let limit = ceiling.saturating_add(1);
    if selection.is_all() {
        let dictionary = selection.set().dictionary();
        let start = dictionary.partition_point(|c| java_string_cmp(&c.name, prefix).is_lt());
        return dictionary[start..]
            .iter()
            .take(limit)
            .take_while(|c| c.name.starts_with(prefix))
            .count();
    }
    let mut distinct: HashSet<&str> = HashSet::new();
    for partition in selection.partitions() {
        let concepts = partition.store().index().concepts();
        let start = concepts.partition_point(|c| java_string_cmp(&c.path, prefix).is_lt());
        for concept in concepts[start..]
            .iter()
            .take(limit)
            .take_while(|c| c.path.starts_with(prefix))
        {
            distinct.insert(concept.path.as_str());
            if distinct.len() >= limit {
                return limit;
            }
        }
    }
    distinct.len()
}
