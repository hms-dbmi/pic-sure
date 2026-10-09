//! A router over the 1000 Genomes fixture, built in-process with the Rust ETL.

#![allow(dead_code)]

use std::path::PathBuf;
use std::sync::Arc;

use axum::Router;
use axum::body::Body;
use axum::http::{Request, Response, StatusCode};
use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_query::{Engine, StoreOptions};
use hpds_rs::{AppState, Config, router};
use http_body_util::BodyExt;
use serde_json::Value;
use tower::ServiceExt;

pub const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";
pub const SEX: &str = r"\open_access-1000Genomes\data\SEX\";
pub const AGE: &str = r"\open_access-1000Genomes\data\SYNTHETIC_AGE\";
pub const POPULATION: &str = r"\open_access-1000Genomes\data\POPULATION NAME\";

/// A fixture store and a router over it. The temporary directory is removed on drop.
pub struct Fixture {
    pub dir: tempfile::TempDir,
    pub engine: Arc<Engine>,
    pub config: Config,
    pub state: AppState,
    pub app: Router,
}

fn workspace() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// Settings with defaults, the results directory inside `dir`, and no authorization
/// filter required.
pub fn config(dir: &std::path::Path) -> Config {
    let mut config = Config::from_lookup(|_| None).unwrap();
    config.results_dir = dir.join("results");
    config.require_authorization_filter = false;
    config
}

/// Loads the fixture CSV, with a header line prepended, into an encrypted store.
pub fn fixture_with(adjust: impl FnOnce(&mut Config)) -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let source =
        workspace().join("../pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv");
    let input = dir.path().join("allConcepts.csv");
    let mut text = String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
    text.push_str(&std::fs::read_to_string(source).unwrap());
    std::fs::write(&input, text).unwrap();
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, KEY).unwrap();
    let out = dir.path().join("store");
    let mut options = LoadOptions::new(Mode::NewSearch, vec![input], out.clone());
    options.key = KeySource::File(key_file.clone());
    options.partitions = Some(2);
    options.threads = Some(2);
    load(&options).unwrap();
    std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
    let engine = Arc::new(Engine::open(&out, &StoreOptions::default()).unwrap());
    let mut config = config(dir.path());
    adjust(&mut config);
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
