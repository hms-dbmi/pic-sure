mod common;

use std::sync::Arc;

use axum::http::StatusCode;
use common::{AGE, SEX, config, envelope, fixture, fixture_with, post, wait};
use hpds_query::{Engine, QueryLimits, StoreOptions};
use hpds_rs::{AppState, router};
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const QUERY: &str = "/PIC-SURE/v3/query";

fn female() -> Value {
    json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["female"]})
}

fn nested(depth: usize) -> Value {
    let mut clause = female();
    for _ in 1..depth {
        clause = json!({"operator": "AND", "phenotypicClauses": [clause]});
    }
    clause
}

fn limited_app(f: &common::Fixture, limits: QueryLimits) -> axum::Router {
    let engine = Engine::open(&f.dir.path().join("store"), &StoreOptions::default())
        .unwrap()
        .with_limits(limits);
    let mut settings = config(f.dir.path());
    settings.results_dir = f.dir.path().join("limited-results");
    router(AppState::new(Some(Arc::new(engine)), &settings).unwrap())
}

#[tokio::test]
async fn too_many_select_paths_is_a_plain_text_400_on_both_routes() {
    let f = fixture();
    let app = limited_app(
        &f,
        QueryLimits {
            max_select_paths: 2,
            ..QueryLimits::default()
        },
    );
    let repeated = vec![SEX; 50];
    let ok = post(
        &app,
        SYNC,
        envelope(json!({"expectedResultType": "CROSS_COUNT", "select": repeated})),
    )
    .await;
    assert_eq!(ok.status, StatusCode::OK, "{}", ok.body);
    for (route, result_type) in [(SYNC, "CROSS_COUNT"), (QUERY, "DATAFRAME")] {
        let reply = post(
            &app,
            route,
            envelope(json!({"expectedResultType": result_type, "select": [SEX, AGE, "\\x\\"]})),
        )
        .await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{}", reply.body);
        assert_eq!(reply.content_type(), Some("text/plain; charset=utf-8"));
        assert!(
            reply.body.contains("HPDS_MAX_SELECT_PATHS"),
            "{}",
            reply.body
        );
        assert!(!reply.body.contains(SEX));
    }
}

#[tokio::test]
async fn deep_and_wide_clauses_are_400s_not_crashes() {
    let f = fixture();
    let app = limited_app(&f, QueryLimits::default());
    let fine = post(
        &app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT", "phenotypicClause": nested(40)})),
    )
    .await;
    assert_eq!((fine.status, fine.body.as_str()), (StatusCode::OK, "2330"));

    for depth in [200, 5_000] {
        let body = format!(
            r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{}}}}}"#,
            nested_text(depth)
        );
        let reply = post(&app, SYNC, body).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{depth}");
        assert_eq!(reply.content_type(), Some("text/plain; charset=utf-8"));
        assert!(reply.body.contains("recursion limit"), "{}", reply.body);
    }

    let shallow = limited_app(
        &f,
        QueryLimits {
            max_clause_depth: 3,
            max_clause_nodes: 6,
            ..QueryLimits::default()
        },
    );
    let too_deep = post(
        &shallow,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT", "phenotypicClause": nested(4)})),
    )
    .await;
    assert_eq!(too_deep.status, StatusCode::BAD_REQUEST);
    assert!(
        too_deep.body.contains("HPDS_MAX_CLAUSE_DEPTH"),
        "{}",
        too_deep.body
    );
    let clauses: Vec<Value> = (0..7).map(|_| female()).collect();
    let too_wide = post(
        &shallow,
        QUERY,
        envelope(json!({
            "expectedResultType": "PATIENTS",
            "phenotypicClause": {"operator": "OR", "phenotypicClauses": clauses}
        })),
    )
    .await;
    assert_eq!(too_wide.status, StatusCode::BAD_REQUEST);
    assert!(
        too_wide.body.contains("HPDS_MAX_CLAUSE_NODES"),
        "{}",
        too_wide.body
    );
}

fn nested_text(depth: usize) -> String {
    let leaf = female().to_string();
    let open = r#"{"operator":"AND","phenotypicClauses":["#.repeat(depth);
    let close = "]}".repeat(depth);
    format!("{open}{leaf}{close}")
}

#[tokio::test]
async fn any_record_of_on_a_broad_prefix_is_bounded() {
    let f = fixture();
    let app = limited_app(
        &f,
        QueryLimits {
            max_query_concepts: 3,
            ..QueryLimits::default()
        },
    );
    let broad = json!({"phenotypicFilterType": "ANY_RECORD_OF", "conceptPath": "\\"});
    for (route, result_type) in [(SYNC, "COUNT"), (QUERY, "PATIENTS")] {
        let reply = post(
            &app,
            route,
            envelope(json!({"expectedResultType": result_type, "phenotypicClause": broad})),
        )
        .await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{}", reply.body);
        assert!(
            reply.body.contains("HPDS_MAX_QUERY_CONCEPTS"),
            "{}",
            reply.body
        );
    }
}

async fn export_size(f: &common::Fixture) -> u64 {
    let reply = post(
        &f.app,
        QUERY,
        envelope(json!({"expectedResultType": "PATIENTS"})),
    )
    .await;
    let id = reply.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    let done = wait(&f.app, &id).await;
    assert_eq!(done["resourceStatus"], "SUCCESS");
    done["sizeInBytes"].as_u64().unwrap()
}

#[tokio::test]
async fn an_export_is_cut_off_at_the_file_cap_counting_the_header() {
    let reference = fixture();
    let size = export_size(&reference).await;
    assert!(size > 1000);

    let at_cap = fixture_with(|c| c.result_max_file_bytes = size);
    assert_eq!(export_size(&at_cap).await, size);

    for (result_type, select) in [("PATIENTS", json!([])), ("DATAFRAME", json!([SEX, AGE]))] {
        let over = fixture_with(|c| c.result_max_file_bytes = size / 2);
        let reply = post(
            &over.app,
            QUERY,
            envelope(json!({"expectedResultType": result_type, "select": select})),
        )
        .await;
        let id = reply.json()["resourceResultId"]
            .as_str()
            .unwrap()
            .to_owned();
        let done = wait(&over.app, &id).await;
        assert_eq!(done["status"], "ERROR", "{result_type}");
        let message = done["resultMetadata"]["error"].as_str().unwrap();
        assert!(message.contains("HPDS_RESULT_MAX_FILE_BYTES"), "{message}");
        assert_eq!(done["sizeInBytes"], 0);
        let leftover = std::fs::read_dir(&over.config.results_dir).unwrap().count();
        assert_eq!(leftover, 0, "{result_type} left a partial file");
    }

    let header_only = fixture_with(|c| c.result_max_file_bytes = 5);
    let reply = post(
        &header_only.app,
        QUERY,
        envelope(json!({"expectedResultType": "PATIENTS"})),
    )
    .await;
    let id = reply.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(wait(&header_only.app, &id).await["status"], "ERROR");
}
