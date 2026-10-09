//! Query engine over the Rust phenotype store: the v3 query model, cohort evaluation over
//! roaring bitmaps, the Java validators, and the COUNT, cross count, PATIENTS and DATAFRAME
//! computations. No HTTP: the `hpds-rs` crate serves this.
//!
//! - The [`model`] module holds [`Query`] and its parts, deserialized as Jackson reads them.
//! - A [`Store`] keeps `pheno.index` in memory and a byte-bounded cache of decoded blocks.
//! - [`evaluate_cohort`] is `PhenotypicQueryExecutor.getPatientSet`.
//! - [`QueryValidator`] and [`check_supported`] are `QueryValidator`,
//!   `PhenotypicFilterValidator`, and the refusal of genomic input.
//! - The [`Engine`] runs the result computations, each returning a typed value that carries
//!   Java's response form ([`SyncResult::body`], [`DataframeRows`]).
//!
//! Everything that reads blocks is blocking. Async callers wrap it in
//! `tokio::task::spawn_blocking`; the `tokio` feature adds [`Engine::run_blocking`] and
//! [`Store::block_async`] for that.

use std::path::Path;

use hpds_format::INDEX_FILE_NAME;

pub mod cohort;
mod engine;
mod error;
pub mod model;
mod results;
mod store;
pub mod validate;

pub use cohort::evaluate_cohort;
pub use engine::Engine;
pub use error::{QueryError, Result};
pub use model::{
    AuthorizationFilter, GenomicFilter, Operator, PhenotypicClause, PhenotypicFilter,
    PhenotypicFilterType, PhenotypicSubquery, Query, ResultType,
};
pub use results::{
    CategoricalCrossCounts, ConceptCounts, ContinuousCrossCounts, Dataframe, DataframeRows,
    PATIENT_ID_FIELD_NAME, PATIENTS_HEADER, SyncResult,
};
pub use store::{DEFAULT_CACHE_BYTES, KEY_FILE_NAME, Store, StoreOptions};
pub use validate::{ConceptLookup, QueryValidator, check_supported, path_is_variant_spec};

/// Directory used when `HPDS_DATA_DIRECTORY` is not set. Matches the Java service, so the
/// all-in-one stack bind-mounts the hpds-rs host directory at the same path.
pub const DEFAULT_DATA_DIRECTORY: &str = "/opt/local/hpds/";

/// Whether the store in a data directory can serve queries.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HealthState {
    /// The store is present and queries can be served.
    Up,
    /// The store is missing or not usable.
    Down,
}

/// Reports the health of the store in `data_dir` without opening it.
///
/// Returns `Up` when `pheno.index` exists in `data_dir` and `Down` otherwise. A server that
/// holds an open [`Store`] should use [`Store::is_healthy`] instead, which requires the
/// index to hold at least one concept.
pub fn health_for(data_dir: &Path) -> HealthState {
    if data_dir.join(INDEX_FILE_NAME).is_file() {
        HealthState::Up
    } else {
        HealthState::Down
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_index_is_down() {
        let dir = tempfile::tempdir().unwrap();
        assert_eq!(health_for(dir.path()), HealthState::Down);
    }

    #[test]
    fn present_index_is_up() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join(INDEX_FILE_NAME), b"").unwrap();
        assert_eq!(health_for(dir.path()), HealthState::Up);
    }
}
