//! Query engine over the Rust phenotype store.

use std::path::Path;

use hpds_format::INDEX_FILE_NAME;

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

/// Reports the health of the store in `data_dir`.
///
/// Returns `Up` when `pheno.index` exists in `data_dir` and `Down` otherwise. This existence
/// check is a placeholder: once the store exists, health means the index loads and holds at
/// least one concept, and that check replaces this one.
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
