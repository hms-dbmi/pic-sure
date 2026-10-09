use std::net::SocketAddr;
use std::time::Duration;

use axum::Router;
use axum::extract::Path;
use axum::extract::{Json, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::IntoResponse;
use axum::routing::post;
use hpds_bench::client::Client;
use std::sync::Arc;

use hpds_bench::compare::compare_corpus;
use hpds_bench::corpus::{CorpusEntry, Kind};
use hpds_bench::divergence::Divergences;
use hpds_bench::golden::{Expect, GoldenCase, GoldenFile, run_golden};
use hpds_bench::load::{LoadConfig, run_load};
use serde_json::{Value, json};

#[derive(Clone)]
struct Mock {
    count: i64,
    reverse_rows: bool,
    unknown_status: u16,
}

fn mock(count: i64, reverse_rows: bool) -> Mock {
    Mock {
        count,
        reverse_rows,
        unknown_status: 404,
    }
}

async fn search(Json(body): Json<Value>) -> impl IntoResponse {
    let term = body["query"].as_str().unwrap_or_default().to_owned();
    (
        [(header::CONTENT_TYPE, "application/json")],
        json!({"results": {"phenotypes": {"\\a\\SEX\\": {"min": 1.0}}, "info": {}}, "searchQuery": term}).to_string(),
    )
}

async fn unknown(State(mock): State<Mock>, Path(_id): Path<String>) -> impl IntoResponse {
    (
        StatusCode::from_u16(mock.unknown_status).unwrap(),
        [(header::CONTENT_TYPE, "application/json")],
        json!({"timestamp": mock.unknown_status}).to_string(),
    )
}

async fn sync(State(mock): State<Mock>, Json(body): Json<Value>) -> impl IntoResponse {
    let mut headers = HeaderMap::new();
    headers.insert(
        "queryMetadata",
        HeaderValue::from_static("11111111-1111-5111-8111-111111111111"),
    );
    match body["query"]["expectedResultType"].as_str() {
        Some("COUNT") => {
            headers.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_static("text/plain;charset=UTF-8"),
            );
            (StatusCode::OK, headers, mock.count.to_string())
        }
        Some("CROSS_COUNT") => {
            headers.insert(
                header::CONTENT_TYPE,
                HeaderValue::from_static("application/json"),
            );
            (
                StatusCode::OK,
                headers,
                format!("{{\"b\":2,\"a\":{}}}", mock.count),
            )
        }
        _ => {
            headers.insert(header::CONTENT_TYPE, HeaderValue::from_static("text/plain"));
            (
                StatusCode::BAD_REQUEST,
                HeaderMap::new(),
                "asynchronous".to_owned(),
            )
        }
    }
}

async fn submit(Json(_): Json<Value>) -> Json<Value> {
    Json(
        json!({"status": "QUEUED", "resourceStatus": "PENDING", "resourceResultId": "abc", "startTime": 7, "duration": 0}),
    )
}

async fn status() -> Json<Value> {
    Json(
        json!({"status": "AVAILABLE", "resourceStatus": "SUCCESS", "resourceResultId": "abc", "startTime": 99, "duration": 5}),
    )
}

async fn status_or_unknown(
    State(mock): State<Mock>,
    Path(id): Path<String>,
) -> axum::response::Response {
    if id == "abc" {
        status().await.into_response()
    } else {
        unknown(State(mock), Path(id)).await.into_response()
    }
}

async fn result_or_unknown(
    State(mock): State<Mock>,
    Path(id): Path<String>,
) -> axum::response::Response {
    if id == "abc" {
        result(State(mock)).await.into_response()
    } else {
        unknown(State(mock), Path(id)).await.into_response()
    }
}

async fn result(State(mock): State<Mock>) -> impl IntoResponse {
    let rows = if mock.reverse_rows {
        "PATIENT_NUM\n3\n2\n1\n"
    } else {
        "PATIENT_NUM\n1\n2\n3\n"
    };
    ([(header::CONTENT_TYPE, "text/plain;charset=UTF-8")], rows)
}

async fn spawn(mock: Mock) -> String {
    let app = Router::new()
        .route("/PIC-SURE/v3/query/sync", post(sync))
        .route("/PIC-SURE/v3/query", post(submit))
        .route("/PIC-SURE/v3/search", post(search))
        .route("/PIC-SURE/v3/query/{id}/status", post(status_or_unknown))
        .route("/PIC-SURE/v3/query/{id}/result", post(result_or_unknown))
        .with_state(mock);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr: SocketAddr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    format!("http://{addr}/PIC-SURE")
}

fn entry(name: &str, result_type: &str, kind: Kind) -> CorpusEntry {
    CorpusEntry {
        name: name.into(),
        kind,
        result_type: result_type.into(),
        request: json!({"query": {"select": [], "expectedResultType": result_type}, "resourceUUID": null}),
        raw_body: None,
        error_path: false,
    }
}

fn corpus() -> Vec<CorpusEntry> {
    vec![
        entry("count", "COUNT", Kind::Sync),
        entry("cross", "CROSS_COUNT", Kind::Sync),
        entry("patients", "PATIENTS", Kind::Async),
    ]
}

fn none() -> Arc<Divergences> {
    Arc::new(Divergences::default())
}

fn client(base: &str) -> Client {
    Client::new(base, Duration::from_secs(5), 4).unwrap()
}

#[tokio::test]
async fn identical_servers_have_no_mismatches_even_with_reordered_rows() {
    let a = spawn(mock(5, false)).await;
    let b = spawn(mock(5, true)).await;
    let report = compare_corpus(&client(&a), &client(&b), corpus(), 2, 0, none()).await;
    assert!(report.mismatches.is_empty(), "{:?}", report.mismatches);
}

#[tokio::test]
async fn differing_counts_are_reported_by_entry_name() {
    let a = spawn(mock(5, false)).await;
    let b = spawn(mock(6, false)).await;
    let report = compare_corpus(&client(&a), &client(&b), corpus(), 2, 1, none()).await;
    let names: Vec<&str> = report.mismatches.iter().map(|m| m.entry.as_str()).collect();
    assert_eq!(names, vec!["count", "cross"]);
    assert!(report.flaky.is_empty());
}

#[tokio::test]
async fn golden_cases_pass_and_fail_against_the_mock() {
    let base = spawn(mock(5, false)).await;
    let case = |name: &str, text: &str| GoldenCase {
        entry: entry(name, "COUNT", Kind::Sync),
        expect: Expect {
            text: Some(text.into()),
            ..Expect::default()
        },
    };
    let patients = GoldenCase {
        entry: entry("patients", "PATIENTS", Kind::Async),
        expect: Expect {
            header: Some("PATIENT_NUM".into()),
            rows: Some(3),
            ..Expect::default()
        },
    };
    let file = GoldenFile {
        dataset: "mock".into(),
        cases: vec![case("right", "5"), case("wrong", "9"), patients],
    };
    let failed = run_golden(&client(&base), file, 2).await;
    assert_eq!(failed.len(), 1);
    assert_eq!(failed[0].entry, "wrong");
}

#[tokio::test]
async fn load_reports_latency_per_result_type() {
    let base = spawn(mock(5, false)).await;
    let config = LoadConfig {
        concurrency: 3,
        duration: Duration::from_millis(400),
        warmup: Duration::from_millis(100),
        ..LoadConfig::default()
    };
    let report = run_load(&client(&base), corpus(), &config).await.unwrap();
    assert!(report.overall.requests > 0);
    assert_eq!(report.overall.errors, 0);
    assert!(report.per_type.contains_key("COUNT") && report.per_type.contains_key("PATIENTS"));

    let sync_only = LoadConfig {
        only_sync: true,
        ..config
    };
    let report = run_load(&client(&base), corpus(), &sync_only)
        .await
        .unwrap();
    assert!(!report.per_type.contains_key("PATIENTS"));

    let excluding = LoadConfig {
        only_sync: false,
        exclude: vec!["COUNT".into()],
        ..sync_only
    };
    let report = run_load(&client(&base), corpus(), &excluding)
        .await
        .unwrap();
    assert!(!report.per_type.contains_key("COUNT"));
    assert!(report.per_type.contains_key("CROSS_COUNT"));
}

fn probe(name: &str, kind: Kind) -> CorpusEntry {
    CorpusEntry {
        name: name.into(),
        kind,
        result_type: "UNKNOWN_ID".into(),
        request: json!({"query": null, "resourceUUID": null}),
        raw_body: None,
        error_path: true,
    }
}

fn search_entry() -> CorpusEntry {
    CorpusEntry {
        name: "search-path-0".into(),
        kind: Kind::Search,
        result_type: "SEARCH".into(),
        request: json!({"query": "sex", "resourceUUID": null}),
        raw_body: None,
        error_path: false,
    }
}

#[tokio::test]
async fn search_entries_compare_structurally_without_a_metadata_header() {
    let a = spawn(mock(5, false)).await;
    let b = spawn(mock(5, false)).await;
    let report = compare_corpus(&client(&a), &client(&b), vec![search_entry()], 1, 0, none()).await;
    assert!(report.mismatches.is_empty(), "{:?}", report.mismatches);
}

#[tokio::test]
async fn whitelisted_status_pairs_are_reported_apart_from_mismatches() {
    let a = spawn(mock(5, false)).await;
    let b = spawn(Mock {
        unknown_status: 500,
        ..mock(5, false)
    })
    .await;
    let entries = vec![
        probe("error-unknown-async-id-status", Kind::UnknownStatus),
        probe("other-unknown-async-id-result", Kind::UnknownResult),
    ];
    let rules: Divergences = serde_json::from_value(json!({"rules": [
        {"name": "unknown-id", "entry": "error-*", "statuses": [404, 500], "reason": "unknown ids"}
    ]}))
    .unwrap();
    let report = compare_corpus(&client(&a), &client(&b), entries, 1, 0, Arc::new(rules)).await;
    assert_eq!(report.whitelisted.len(), 1);
    assert_eq!(report.whitelisted[0].entry, "error-unknown-async-id-status");
    assert_eq!(report.whitelisted[0].reason, "unknown ids");
    assert_eq!(report.mismatches.len(), 1);
    assert_eq!(report.mismatches[0].entry, "other-unknown-async-id-result");
}

#[tokio::test]
async fn passes_report_each_pass_once_per_entry_and_skip_error_paths() {
    let base = spawn(mock(5, false)).await;
    let mut entries = corpus();
    entries.push(probe("error-unknown-async-id-status", Kind::UnknownStatus));
    let config = LoadConfig {
        concurrency: 2,
        passes: 3,
        ..LoadConfig::default()
    };
    let report = run_load(&client(&base), entries, &config).await.unwrap();
    assert_eq!(report.mode, "passes");
    assert_eq!(report.passes.len(), 3);
    assert!(report.passes[0].cold && !report.passes[1].cold);
    for pass in &report.passes {
        assert_eq!(pass.overall.requests, 3);
        assert_eq!(pass.overall.errors, 0);
        assert_eq!(pass.overall.timeouts, 0);
        assert!(pass.span_s > 0.0);
    }
    assert_eq!(report.overall.requests, 9);
    assert!(report.overall.bytes > 0);
    assert!(report.overall.service.p50_ms > 0.0);
}

#[tokio::test]
async fn async_timeouts_are_counted_apart_from_errors() {
    async fn never_done() -> Json<Value> {
        Json(json!({"status": "RUNNING", "resourceStatus": "RUNNING", "resourceResultId": "x"}))
    }
    let app = Router::new()
        .route("/PIC-SURE/v3/query", post(never_done))
        .route("/PIC-SURE/v3/query/{id}/status", post(never_done));
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
    let slow = Client::new(
        &format!("http://{addr}/PIC-SURE"),
        Duration::from_millis(60),
        2,
    )
    .unwrap();
    let config = LoadConfig {
        concurrency: 1,
        passes: 1,
        timeout: Duration::from_millis(60),
        ..LoadConfig::default()
    };
    let only_async = vec![entry("patients", "PATIENTS", Kind::Async)];
    let report = run_load(&slow, only_async, &config).await.unwrap();
    assert_eq!(report.overall.timeouts, 1);
    assert_eq!(report.overall.errors, 0);
    assert!(report.overall.with_timeouts.max_ms >= 59.0);
}
