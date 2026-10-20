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

    /// The query asks for something this engine does not do: genomic filters when it serves
    /// no genomic store. The message names the feature.
    #[error("{0} is not supported by this phenotype-only HPDS")]
    Unsupported(String),

    /// A genomic filter Java fails on when it evaluates it (a `null` key or value, a variant
    /// spec whose position is not a Java `int`, a numeric filter without both bounds or with
    /// `min` above `max`, and the data-dependent failures the genomic engine names), refused
    /// whether or not the phenotypic cohort is empty. Java answers these with an error or an
    /// empty 200; the message says what is wrong.
    #[error("{0}")]
    GenomicFilter(String),

    /// The store requires consents (`hpds.requireAuthorizationFilter` is on) and the query
    /// carries no consent value. Java throws `MissingConsentsException` from the store, which
    /// its sync endpoint answers with 500; this is a client error here.
    #[error("User consents are required for this query")]
    UserConsentsRequired,

    /// A `FILTER` with neither `values` nor `min`/`max`, which Java's executor refuses with
    /// "Either values or one of min/max must be set for a filter".
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

    /// A block's kind disagrees with the index entry for its concept. The store is corrupt
    /// or was assembled from mismatched files, so this is a server error.
    #[error("the store is inconsistent for {concept_path}: {detail}")]
    StoreInconsistent {
        /// Concept path of the offending block.
        concept_path: String,
        /// What disagrees.
        detail: &'static str,
    },

    /// A DATAFRAME export whose selected blocks together exceed the pinned memory cap.
    #[error("export too wide; select fewer concepts")]
    ExportTooWide,

    /// The query or its result is larger than a configured bound. The message names the
    /// bound and its setting, never a concept path or a value.
    #[error("{0}")]
    LimitExceeded(String),

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

    /// A cohort held patients outside the partitions the query's consents select, which
    /// only a fault in the engine can cause. The query is refused rather than answered from
    /// another partition's patients. The message counts them and names none.
    #[error("the cohort holds {patients} patients outside the selected partitions")]
    CohortOutsideSelection {
        /// How many patients were outside.
        patients: u64,
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

    /// A block of the genomic store could not be read, authenticated, or decoded.
    #[error("cannot load the genomic {what} of node {node}: {source}")]
    GenomicBlock {
        /// The node's group and directory, as `group/dir`.
        node: String,
        /// The block: its kind and ordinal.
        what: String,
        /// The format error.
        #[source]
        source: Arc<FormatError>,
    },

    /// Writing a streamed answer, such as an excerpt, to its destination failed.
    #[error("cannot write the answer: {0}")]
    Output(Arc<std::io::Error>),

    /// A blocking task did not finish, for example because it panicked.
    #[error("query task failed: {0}")]
    Task(String),

    /// The store files in the data directory were replaced, or a publish into it began,
    /// after the service opened it, so what the service holds in memory may include data
    /// the store no longer has. Every query is refused until the service restarts.
    #[error(
        "the store in {dir} changed on disk after this service opened it; restart the service to serve the new store"
    )]
    StoreChanged {
        /// The data directory.
        dir: PathBuf,
    },
}

impl QueryError {
    /// True when the request is at fault and retrying it unchanged cannot succeed: every
    /// variant except [`QueryError::Open`], [`QueryError::CohortOutsideSelection`],
    /// [`QueryError::Block`], [`QueryError::GenomicBlock`], [`QueryError::StoreInconsistent`],
    /// [`QueryError::Output`], [`QueryError::Task`] and [`QueryError::StoreChanged`].
    pub fn is_client_error(&self) -> bool {
        !matches!(
            self,
            QueryError::Open { .. }
                | QueryError::CohortOutsideSelection { .. }
                | QueryError::Block { .. }
                | QueryError::GenomicBlock { .. }
                | QueryError::StoreInconsistent { .. }
                | QueryError::Output(_)
                | QueryError::Task(_)
                | QueryError::StoreChanged { .. }
        )
    }
}

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, QueryError>;
