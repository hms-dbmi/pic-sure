use std::path::PathBuf;
use std::sync::Arc;

use hpds_format::FormatError;
use thiserror::Error;

use crate::model::ResultType;

/// Every failure the engine reports.
///
/// [`QueryError::is_client_error`] separates problems with the request (the HTTP layer
/// answers 400) from problems with the store or the server (500).
#[derive(Debug, Clone, Error)]
pub enum QueryError {
    /// The query breaks a `QueryValidator` or `PhenotypicFilterValidator` rule. The message
    /// is the one Java puts in its `IllegalArgumentException`.
    #[error("{0}")]
    Invalid(String),

    /// The query asks for something this phenotype-only engine does not do: genomic filters,
    /// variant-spec concept paths, or a genomic result type. The message names the feature.
    #[error("{0} is not supported by this phenotype-only HPDS")]
    Unsupported(String),

    /// A `FILTER` with neither `values` nor `min`/`max`, which Java's executor refuses with
    /// "Either values or one of min/max must be set for a filter". Also raised for an
    /// authorization filter whose `values` is null, since Java turns it into such a filter.
    #[error("Either values or one of min/max must be set for a filter on {concept_path}")]
    FilterWithoutCriteria {
        /// Concept path of the offending filter.
        concept_path: String,
    },

    /// A subquery with no clauses. Java fails here with `NoSuchElementException` from
    /// `Optional.get()` on an empty reduction.
    #[error("a phenotypic subquery must contain at least one clause")]
    EmptySubquery,

    /// A subquery with no `operator`. Java fails here with a `NullPointerException`.
    #[error("a phenotypic subquery must name an operator (AND or OR)")]
    MissingOperator,

    /// A value filter on a numeric concept or a range filter on a categorical one. Java
    /// fails here with a `ClassCastException` when it compares the filter's value with the
    /// concept's values.
    #[error("{concept_path} is not a {expected} variable")]
    TypeMismatch {
        /// Concept path of the offending filter.
        concept_path: String,
        /// `categorical` or `numeric`: what the filter needs the concept to be.
        expected: &'static str,
    },

    /// A result type that `/query/sync` does not serve because it is produced by an
    /// asynchronous job.
    #[error("Result type {0} is served asynchronously")]
    NotSync(ResultType),

    /// The data directory cannot be opened as a store: the key is missing or invalid, or
    /// the index or blocks file is missing, corrupt, or from another store.
    #[error("cannot open the store in {dir}: {detail}")]
    Open {
        /// The data directory.
        dir: PathBuf,
        /// What went wrong.
        detail: String,
    },

    /// A concept's block could not be read, authenticated, or decoded.
    #[error("cannot load the block of {concept_path}: {source}")]
    Block {
        /// Concept path of the block.
        concept_path: String,
        /// The format error.
        #[source]
        source: Arc<FormatError>,
    },

    /// A blocking task did not finish, for example because it panicked.
    #[error("query task failed: {0}")]
    Task(String),
}

impl QueryError {
    /// True when the request is at fault and retrying it unchanged cannot succeed: every
    /// variant except [`QueryError::Open`], [`QueryError::Block`] and [`QueryError::Task`].
    pub fn is_client_error(&self) -> bool {
        !matches!(
            self,
            QueryError::Open { .. } | QueryError::Block { .. } | QueryError::Task(_)
        )
    }
}

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, QueryError>;
