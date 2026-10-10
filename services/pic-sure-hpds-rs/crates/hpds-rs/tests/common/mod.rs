//! A router over the four-partition 1000 Genomes fixture of the harmonization branch, built
//! in-process with the Rust partitioned loader.

#![allow(dead_code)]

use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::Router;
use axum::body::Body;
use axum::http::{Request, Response, StatusCode};
use hpds_etl::load::{KeySource, PartitionedLoadOptions, load_partitioned};
use hpds_query::Engine;
use hpds_rs::{AppState, Config, router};
use http_body_util::BodyExt;
use serde_json::Value;
use tower::ServiceExt;

pub const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";
pub const SEX: &str = r"\open_access-1000Genomes\data\SEX\";
pub const AGE: &str = r"\open_access-1000Genomes\data\SYNTHETIC_AGE\";
pub const POPULATION: &str = r"\open_access-1000Genomes\data\POPULATION NAME\";

/// The fixture partitions, as the Java integration environment names them.
pub const PARTITIONS: [&str; 4] = ["partition1", "partition2", "partition3", "partition4"];

/// Patients per partition: 494, 1,000, 1,000 and 2,484, disjoint, 4,978 in all.
pub const PARTITION_PATIENTS: [u64; 4] = [494, 1000, 1000, 2484];

/// Patient ID range of each partition, end exclusive.
pub const PARTITION_RANGES: [(u32, u32); 4] = [
    (197_506, 198_000),
    (198_000, 199_000),
    (199_000, 200_000),
    (200_000, 202_484),
];

/// A fixture store and a router over it. The temporary directory is removed on drop.
pub struct Fixture {
    pub dir: tempfile::TempDir,
    pub engine: Arc<Engine>,
    pub config: Config,
    pub state: AppState,
    pub app: Router,
}

impl Fixture {
    /// The data directory holding `partition1` to `partition4` and the key.
    pub fn store(&self) -> PathBuf {
        self.dir.path().join("store")
    }
}

pub fn workspace() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// The directory of the Java fixture files `partition{1..4}-allConcepts.csv`.
pub fn java_fixture_dir() -> PathBuf {
    workspace().join("../pic-sure-hpds/service/src/test/resources/phenotypic")
}

/// Settings with defaults, the results directory inside `dir`, exports enabled, and
/// consents not required (the Java integration profile).
pub fn config(dir: &Path) -> Config {
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.results_dir = dir.join("results");
    config.partitions.require_consents = false;
    config.id_batch_size = 1000;
    config
}

/// Loads the four Java fixture files with the partitioned loader into `<dir>/store`,
/// encrypted with [`KEY`], and returns that directory.
pub fn build_store(dir: &Path) -> PathBuf {
    let input = dir.join("input");
    std::fs::create_dir_all(&input).unwrap();
    for name in PARTITIONS {
        let file = format!("{name}-allConcepts.csv");
        std::fs::copy(java_fixture_dir().join(&file), input.join(&file)).unwrap();
    }
    let key_file = dir.join("key");
    std::fs::write(&key_file, KEY).unwrap();
    let out = dir.join("store");
    let mut options = PartitionedLoadOptions::new(input, out.clone());
    options.key = KeySource::File(key_file);
    options.no_config = true;
    options.spill_partitions = Some(2);
    options.threads = Some(2);
    load_partitioned(&options).unwrap();
    out
}

/// Opens `store` as `main` does, with the partition options, limits, export cap and result
/// cache bound of `config`.
pub fn open_engine(store: &Path, config: &Config) -> Arc<Engine> {
    Arc::new(
        Engine::open(store, &config.partitions)
            .unwrap()
            .with_export_pin_bytes(config.export_pin_bytes)
            .with_limits(config.query_limits)
            .with_result_cache_bytes(config.result_cache_bytes),
    )
}

/// Builds the four-partition fixture and a router over it, with [`config`] changed by
/// `adjust`.
pub fn fixture_with(adjust: impl FnOnce(&mut Config)) -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let out = build_store(dir.path());
    let mut config = config(dir.path());
    adjust(&mut config);
    let engine = open_engine(&out, &config);
    let state = AppState::new(Some(Arc::clone(&engine)), &config).unwrap();
    let app = router(state.clone());
    Fixture {
        dir,
        engine,
        config,
        state,
        app,
    }
}

pub fn fixture() -> Fixture {
    fixture_with(|_| {})
}

/// The fixture with `hpds.requireAuthorizationFilter=true`.
pub fn strict_fixture() -> Fixture {
    fixture_with(|c| c.partitions.require_consents = true)
}

/// `[{"value": c}...]` for `consents`.
pub fn consents(values: &[&str]) -> Value {
    Value::Array(
        values
            .iter()
            .map(|v| serde_json::json!({ "value": v }))
            .collect(),
    )
}

/// A response with its body read.
pub struct Reply {
    pub status: StatusCode,
    pub headers: axum::http::HeaderMap,
    pub body: String,
}

impl Reply {
    pub fn json(&self) -> Value {
        serde_json::from_str(&self.body).unwrap_or_else(|e| panic!("{e}: {}", self.body))
    }

    pub fn content_type(&self) -> Option<&str> {
        self.headers
            .get("content-type")
            .map(|v| v.to_str().unwrap())
    }
}

async fn read(response: Response<Body>) -> Reply {
    let status = response.status();
    let headers = response.headers().clone();
    let bytes = response.into_body().collect().await.unwrap().to_bytes();
    Reply {
        status,
        headers,
        body: String::from_utf8(bytes.to_vec()).unwrap(),
    }
}

pub async fn post(app: &Router, path: &str, body: impl Into<String>) -> Reply {
    let request = Request::post(path)
        .header("content-type", "application/json")
        .body(Body::from(body.into()))
        .unwrap();
    read(app.clone().oneshot(request).await.unwrap()).await
}

pub async fn get(app: &Router, path: &str) -> Reply {
    let request = Request::get(path).body(Body::empty()).unwrap();
    read(app.clone().oneshot(request).await.unwrap()).await
}

/// `{"query": query, "resourceUUID": null}`.
pub fn envelope(query: Value) -> String {
    serde_json::json!({"query": query, "resourceUUID": null}).to_string()
}

/// Polls `/status` until the query leaves PENDING and RUNNING, and returns the last status.
pub async fn wait(app: &Router, id: &str) -> Value {
    for _ in 0..500 {
        let status = post(app, &format!("/PIC-SURE/v3/query/{id}/status"), "{}").await;
        assert_eq!(status.status, StatusCode::OK, "{}", status.body);
        let json = status.json();
        if !matches!(json["resourceStatus"].as_str(), Some("PENDING" | "RUNNING")) {
            return json;
        }
        tokio::time::sleep(std::time::Duration::from_millis(10)).await;
    }
    panic!("query {id} did not finish");
}
