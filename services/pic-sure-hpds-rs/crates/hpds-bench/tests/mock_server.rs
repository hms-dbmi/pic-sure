use std::net::SocketAddr;
use std::time::Duration;

use axum::Router;
use axum::extract::{Json, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode, header};
use axum::response::IntoResponse;
use axum::routing::post;
use hpds_bench::client::Client;
use hpds_bench::compare::compare_corpus;
use hpds_bench::corpus::{CorpusEntry, Kind};
use hpds_bench::golden::{Expect, GoldenCase, GoldenFile, run_golden};
use hpds_bench::load::{LoadConfig, run_load};
use serde_json::{Value, json};

#[derive(Clone)]
struct Mock {
    count: i64,
    reverse_rows: bool,
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
        .route("/PIC-SURE/v3/query/{id}/status", post(status))
        .route("/PIC-SURE/v3/query/{id}/result", post(result))
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
    }
}

fn corpus() -> Vec<CorpusEntry> {
    vec![
        entry("count", "COUNT", Kind::Sync),
        entry("cross", "CROSS_COUNT", Kind::Sync),
        entry("patients", "PATIENTS", Kind::Async),
    ]
}

fn client(base: &str) -> Client {
    Client::new(base, Duration::from_secs(5), 4).unwrap()
}

#[tokio::test]
async fn identical_servers_have_no_mismatches_even_with_reordered_rows() {
    let a = spawn(Mock {
        count: 5,
        reverse_rows: false,
    })
    .await;
    let b = spawn(Mock {
        count: 5,
        reverse_rows: true,
    })
    .await;
    let report = compare_corpus(&client(&a), &client(&b), corpus(), 2, 0).await;
    assert!(report.mismatches.is_empty(), "{:?}", report.mismatches);
}

#[tokio::test]
async fn differing_counts_are_reported_by_entry_name() {
    let a = spawn(Mock {
        count: 5,
        reverse_rows: false,
    })
    .await;
    let b = spawn(Mock {
        count: 6,
        reverse_rows: false,
    })
    .await;
    let report = compare_corpus(&client(&a), &client(&b), corpus(), 2, 1).await;
    let names: Vec<&str> = report.mismatches.iter().map(|m| m.entry.as_str()).collect();
    assert_eq!(names, vec!["count", "cross"]);
    assert!(report.flaky.is_empty());
}

#[tokio::test]
async fn golden_cases_pass_and_fail_against_the_mock() {
    let base = spawn(Mock {
        count: 5,
        reverse_rows: false,
    })
    .await;
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
    let base = spawn(Mock {
        count: 5,
        reverse_rows: false,
    })
    .await;
    let config = LoadConfig {
        concurrency: 3,
        duration: Duration::from_millis(400),
        warmup: Duration::from_millis(100),
        only_sync: false,
        exclude: Vec::new(),
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
