//! Query engine over the Rust phenotype store: the v3 query model, cohort evaluation over
//! roaring bitmaps, the Java validators, and the COUNT, cross count, PATIENTS and DATAFRAME
//! computations. No HTTP: the `hpds-rs` crate serves this.
//!
//! - The [`model`] module holds [`Query`] and its parts, deserialized as Jackson reads them.
//! - A [`PartitionSet`] is `PartitionedPhenotypicObservationStore`: the one store of a data
//!   directory (`pheno.index` in memory, blocks read on demand into a byte-bounded cache of
//!   decoded blocks and their partition views) with its consent partitions inside each
//!   block, the merged [`SummaryColumnMeta`] dictionary, and the choice of partitions a
//!   query's `userConsents` grant ([`Selection`]). Its public surface is that dictionary and
//!   the partition names; patient data is read only by the [`Engine`], within a query's
//!   consents. A query whose consents select every partition reads whole blocks; a narrower
//!   one reads whole blocks or partition views concept by concept ([`NARROW_VIEW_SHARE`]).
//! - Cohort evaluation, `PhenotypicQueryExecutor.getPatientSet`, runs only inside the
//!   [`Engine`], after the query passes its [`QueryLimits`] and its consents select the
//!   partitions. Every cohort is checked against the selected partitions' patients before
//!   any count or export uses it.
//! - [`QueryValidator`] and [`check_supported`] are `QueryValidator`,
//!   `PhenotypicFilterValidator`, and the refusal of genomic input.
//! - The [`Engine`] runs the result computations, each returning a typed value that carries
//!   Java's response form ([`SyncResult::body`], [`DataframeRows`]).
//!
//! Everything that reads blocks is blocking. Async callers wrap it in
//! `tokio::task::spawn_blocking`; the `tokio` feature adds [`Engine::run_blocking`] for
//! that.

mod cohort;
mod engine;
mod error;
mod fan_out;
mod limits;
pub mod model;
mod partition;
mod result_cache;
mod results;
mod store;
pub mod validate;
mod view;

pub use engine::{DEFAULT_EXPORT_PIN_BYTES, Engine, ReadPlan};
pub use error::{QueryError, Result};
pub use limits::{
    DEFAULT_MAX_CLAUSE_DEPTH, DEFAULT_MAX_CLAUSE_NODES, DEFAULT_MAX_CROSS_COUNT_ENTRIES,
    DEFAULT_MAX_QUERY_CONCEPTS, DEFAULT_MAX_SELECT_PATHS, QueryLimits,
};
pub use model::{
    AuthorizationFilter, GenomicFilter, Operator, PhenotypicClause, PhenotypicFilter,
    PhenotypicFilterType, PhenotypicSubquery, Query, ResultType, UserConsent, parse_strict,
};
pub use partition::{
    COMBINED_DIR_NAME, Layout, NARROW_VIEW_SHARE, Partition, PartitionOptions, PartitionSet,
    Selection, SummaryColumnMeta, UNPARTITIONED_NAME,
};
pub use result_cache::{MIN_RESULT_CACHE_BYTES, ResultCacheStats, SyncAnswer};
pub use results::{
    CategoricalCrossCounts, ConceptCounts, ContinuousCrossCounts, Dataframe, DataframeRows,
    PATIENT_ID_FIELD_NAME, PATIENTS_HEADER, RowBuffer, SyncResult,
};
pub use store::{DEFAULT_CACHE_BYTES, KEY_FILE_NAME, StoreOptions};
pub use validate::{ConceptLookup, QueryValidator, check_supported, path_is_variant_spec};

/// Directory used when `HPDS_DATA_DIRECTORY` is not set. Matches the Java service, so the
/// all-in-one stack bind-mounts the hpds-rs host directory at the same path.
pub const DEFAULT_DATA_DIRECTORY: &str = "/opt/local/hpds/";
