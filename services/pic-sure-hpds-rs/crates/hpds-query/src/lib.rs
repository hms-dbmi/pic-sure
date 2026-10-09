//! HTTP service that answers PIC-SURE phenotype queries from the Rust store.

use std::path::{Path, PathBuf};

use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use hpds_format::INDEX_FILE_NAME;
use serde::Serialize;

/// Directory used when `HPDS_DATA_DIRECTORY` is not set.
pub const DEFAULT_DATA_DIRECTORY: &str = "/opt/local/hpds-rs/";

/// Body of the `/actuator/health` response.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// `UP` when the store is loaded, `DOWN` otherwise.
    pub status: &'static str,
}

/// Reports health for a data directory.
///
/// Returns 200 with `UP` when `pheno.index` exists in `data_dir`, and 503 with `DOWN` otherwise.
pub fn health_for(data_dir: &Path) -> (StatusCode, Health) {
    if data_dir.join(INDEX_FILE_NAME).is_file() {
        (StatusCode::OK, Health { status: "UP" })
    } else {
        (StatusCode::SERVICE_UNAVAILABLE, Health { status: "DOWN" })
    }
}

async fn health(State(data_dir): State<PathBuf>) -> (StatusCode, Json<Health>) {
    let (code, body) = health_for(&data_dir);
    (code, Json(body))
}

/// Builds the HTTP router serving the given data directory.
pub fn router(data_dir: PathBuf) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .with_state(data_dir)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_index_is_down() {
        let dir = std::env::temp_dir().join("hpds-rs-health-missing");
        std::fs::create_dir_all(&dir).unwrap();
        let _ = std::fs::remove_file(dir.join(INDEX_FILE_NAME));
        let (code, body) = health_for(&dir);
        assert_eq!(code, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(body, Health { status: "DOWN" });
    }

    #[test]
    fn present_index_is_up() {
        let dir = std::env::temp_dir().join("hpds-rs-health-present");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join(INDEX_FILE_NAME), b"").unwrap();
        let (code, body) = health_for(&dir);
        assert_eq!(code, StatusCode::OK);
        assert_eq!(body, Health { status: "UP" });
    }
}
