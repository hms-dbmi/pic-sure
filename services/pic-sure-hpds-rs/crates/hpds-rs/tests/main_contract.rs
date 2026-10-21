//! The main consent contract over HTTP: the required setting's effects on health and
//! `/metrics`, Java main's request model, the requirement flag on both routes, the async
//! validator's ERROR jobs, the refusals of `null` authorization filters, and Java main's
//! search shape, on a small unpartitioned store.

mod common;

use std::sync::Arc;

use axum::Router;
use axum::http::StatusCode;
use common::{Reply, config, envelope, get, open_engine, post, wait};
use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_query::{
    AUTHORIZATION_FILTER_REQUIRED_MESSAGE, ConsentContract,
    NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE, NULL_AUTHORIZATION_FILTER_MESSAGE,
    NULL_AUTHORIZATION_VALUE_MESSAGE, NULL_AUTHORIZATION_VALUES_MESSAGE,
};
use hpds_rs::{AppState, router};
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const QUERY: &str = "/PIC-SURE/v3/query";
const CONSENTS: &str = "\\_consents\\";
const SEX: &str = "\\demo\\sex\\";
const AGE: &str = "\\demo\\age\\";

/// Patients 1 to 10: `\_consents\` phs1.c1 for 1 to 6 and phs1.c2 for 7 to 9, none for 10;
/// sex F for odd patients and M for even; age ten times the patient id.
fn csv() -> String {
    let mut out = String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
    let quote = |s: &str| format!("\"{}\"", s.replace('"', "\"\""));
    for p in 1..=10u32 {
        let consent = match p {
            1..=6 => Some("phs1.c1"),
            7..=9 => Some("phs1.c2"),
            _ => None,
        };
        if let Some(consent) = consent {
            out.push_str(&format!("{p},{},,{},\n", quote(CONSENTS), quote(consent)));
        }
        let sex = if p % 2 == 1 { "F" } else { "M" };
        out.push_str(&format!("{p},{},,{},\n", quote(SEX), quote(sex)));
        out.push_str(&format!("{p},{},{}.0,,\n", quote(AGE), p * 10));
    }
    out
}

/// The store of [`csv`] served under the main contract.
struct MainFixture {
    _dir: tempfile::TempDir,
    app: Router,
}

fn main_fixture(required: bool) -> MainFixture {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    std::fs::write(&input, csv()).unwrap();
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, common::KEY).unwrap();
    let out = dir.path().join("store");
    let mut options = LoadOptions::new(Mode::NewSearch, vec![input], out.clone());
    options.key = KeySource::File(key_file.clone());
    options.threads = Some(2);
    load(&options).unwrap();
    std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
    let mut config = config(dir.path());
    config.partitions.contract = ConsentContract::Main;
    config.partitions.require_consents = required;
    config.metrics = true;
    let engine = open_engine(&out, &config);
    let state = AppState::new(Some(Arc::clone(&engine)), &config).unwrap();
    MainFixture {
        _dir: dir,
        app: router(state),
    }
}

fn consents(values: &[&str]) -> Value {
    json!([{"conceptPath": CONSENTS, "values": values}])
}

fn query(result_type: &str, filters: Option<Value>) -> Value {
    let mut query = json!({"expectedResultType": result_type});
    if let Some(filters) = filters {
        query["authorizationFilters"] = filters;
    }
    query
}

async fn sync(app: &Router, query: Value) -> Reply {
    post(app, SYNC, envelope(query)).await
}

async fn submit(app: &Router, query: Value) -> Reply {
    post(app, QUERY, envelope(query)).await
}

/// Submits `query`, which must be accepted, and returns its final status.
async fn finished(app: &Router, query: Value) -> Value {
    let reply = submit(app, query).await;
    assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
    let id = reply.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    wait(app, &id).await
}

#[tokio::test]
async fn health_and_metrics_name_the_contract() {
    let f = main_fixture(true);
    let health = get(&f.app, "/actuator/health").await;
    assert_eq!(health.status, StatusCode::OK);
    assert_eq!(health.body, r#"{"status":"UP","consentContract":"main"}"#);
    let metrics = get(&f.app, "/metrics").await;
    assert!(
        metrics
            .body
            .contains("hpds_consent_contract{contract=\"main\"} 1\n"),
        "{}",
        metrics.body
    );
}

#[tokio::test]
async fn sync_scopes_every_count_by_the_authorization_filters() {
    let f = main_fixture(false);
    for (filters, expected) in [
        (None, "10"),
        (Some(json!(null)), "10"),
        (Some(json!([])), "10"),
        (Some(consents(&["phs1.c1"])), "6"),
        (Some(consents(&["phs1.c1", "phs1.c2"])), "9"),
        (Some(consents(&["phs9.c9"])), "0"),
        (
            Some(json!([{"conceptPath": "\\unknown\\", "values": ["a"]}])),
            "0",
        ),
    ] {
        let reply = sync(&f.app, query("COUNT", filters.clone())).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, expected),
            "{filters:?}"
        );
    }
    let mut cross = query("CROSS_COUNT", Some(consents(&["phs1.c2"])));
    cross["select"] = json!([SEX, CONSENTS]);
    let reply = sync(&f.app, cross).await;
    assert_eq!(reply.json(), json!({SEX: 3, CONSENTS: 3}));
}

#[tokio::test]
async fn with_the_flag_on_sync_refuses_an_unscoped_query_but_info_column_listing() {
    let f = main_fixture(true);
    for filters in [None, Some(json!(null)), Some(json!([]))] {
        for result_type in ["COUNT", "CROSS_COUNT", "VARIANT_COUNT_FOR_QUERY"] {
            let reply = sync(&f.app, query(result_type, filters.clone())).await;
            assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{result_type}");
            assert_eq!(reply.body, AUTHORIZATION_FILTER_REQUIRED_MESSAGE);
        }
    }
    let listing = sync(&f.app, query("INFO_COLUMN_LISTING", None)).await;
    assert_eq!(
        (listing.status, listing.body.as_str()),
        (StatusCode::OK, "[]")
    );
    let scoped = sync(&f.app, query("COUNT", Some(consents(&["phs1.c1"])))).await;
    assert_eq!(scoped.body, "6");
    let patients = sync(&f.app, query("PATIENTS", None)).await;
    assert!(patients.body.contains("POST /query"), "{}", patients.body);
}

#[tokio::test]
async fn the_async_validator_ends_jobs_in_error_as_java_does() {
    let f = main_fixture(true);
    for filters in [
        None,
        Some(json!([])),
        Some(json!([{"conceptPath": CONSENTS, "values": []}])),
        Some(json!([{"conceptPath": CONSENTS, "values": null}])),
        Some(json!([{"conceptPath": "\\unknown\\", "values": ["a"]}])),
        Some(json!([{"conceptPath": AGE, "values": ["10"]}])),
        Some(json!([{"conceptPath": "\\unknown\\", "values": ["a"]}, null])),
    ] {
        let reply = submit(&f.app, query("PATIENTS", filters.clone())).await;
        assert_eq!(reply.status, StatusCode::OK, "{filters:?}: {}", reply.body);
        let status = reply.json();
        assert_eq!(status["status"], "ERROR", "{filters:?}");
        assert_eq!(status["resourceStatus"], "ERROR", "{filters:?}");
    }
    let mut invalid_clause = query("PATIENTS", Some(consents(&["phs1.c1"])));
    invalid_clause["phenotypicClause"] =
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "min": 1});
    assert_eq!(
        submit(&f.app, invalid_clause).await.json()["status"],
        "ERROR"
    );
    let done = finished(&f.app, query("PATIENTS", Some(consents(&["phs1.c2"])))).await;
    assert_eq!(done["status"], "AVAILABLE", "{done}");
    let off = main_fixture(false);
    let done = finished(&off.app, query("PATIENTS", None)).await;
    assert_eq!(done["status"], "AVAILABLE", "{done}");
}

#[tokio::test]
async fn async_counts_pass_the_validator_first() {
    let f = main_fixture(true);
    for result_type in ["COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT"] {
        let unscoped = submit(&f.app, query(result_type, None)).await;
        assert_eq!(unscoped.status, StatusCode::OK, "{result_type}");
        assert_eq!(unscoped.json()["status"], "ERROR", "{result_type}");
        let scoped = submit(&f.app, query(result_type, Some(consents(&["phs1.c1"])))).await;
        assert_eq!(scoped.status, StatusCode::BAD_REQUEST, "{result_type}");
    }
    let genomic = submit(&f.app, query("VARIANT_COUNT_FOR_QUERY", None)).await;
    assert_eq!(genomic.status, StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn a_null_filter_or_path_is_refused_where_java_creates_no_job() {
    let f = main_fixture(true);
    for (filters, message) in [
        (json!([null]), NULL_AUTHORIZATION_FILTER_MESSAGE),
        (
            json!([null, {"conceptPath": "\\unknown\\", "values": ["a"]}]),
            NULL_AUTHORIZATION_FILTER_MESSAGE,
        ),
        (
            json!([{"conceptPath": null, "values": ["a"]}]),
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
        ),
    ] {
        let reply = submit(&f.app, query("PATIENTS", Some(filters.clone()))).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::BAD_REQUEST, message),
            "{filters}"
        );
    }
}

#[tokio::test]
async fn sync_refuses_nulls_in_authorization_filters_where_java_fails() {
    let f = main_fixture(false);
    for (filters, message) in [
        (json!([null]), NULL_AUTHORIZATION_FILTER_MESSAGE),
        (
            json!([{"conceptPath": CONSENTS}]),
            NULL_AUTHORIZATION_VALUES_MESSAGE,
        ),
        (
            json!([{"conceptPath": null, "values": ["phs1.c1"]}]),
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!([{"conceptPath": CONSENTS, "values": [null]}]),
            NULL_AUTHORIZATION_VALUE_MESSAGE,
        ),
    ] {
        let reply = sync(&f.app, query("COUNT", Some(filters.clone()))).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::BAD_REQUEST, message),
            "{filters}"
        );
    }
    let numeric = sync(
        &f.app,
        query(
            "COUNT",
            Some(json!([{"conceptPath": AGE, "values": ["10"]}])),
        ),
    )
    .await;
    assert_eq!(numeric.status, StatusCode::BAD_REQUEST);
    assert!(
        numeric.body.contains("is not a categorical variable"),
        "{}",
        numeric.body
    );
}

#[tokio::test]
async fn unknown_fields_are_refused_naming_them() {
    let f = main_fixture(false);
    for (query, field) in [
        (
            json!({"expectedResultType": "COUNT", "userConsents": [{"value": "phs1.c1"}]}),
            "userConsents",
        ),
        (
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": CONSENTS, "values": ["phs1.c1"], "bogus": 1}]}),
            "bogus",
        ),
        (
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"conceptPath": SEX, "values": ["F"], "phenotypicFilterType": "FILTER", "operator": "AND"}}),
            "operator",
        ),
    ] {
        for route in [SYNC, QUERY] {
            let reply = post(&f.app, route, envelope(query.clone())).await;
            assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{route} {query}");
            assert!(
                reply.body.contains(&format!("unknown field `{field}`")),
                "{route}: {}",
                reply.body
            );
        }
    }
}

#[tokio::test]
async fn a_repeated_authorization_filters_key_keeps_its_last_value() {
    let f = main_fixture(true);
    let c = serde_json::to_string(CONSENTS).unwrap();
    let inner = format!(
        r#"{{"expectedResultType":"COUNT","authorizationFilters":[{{"conceptPath":{c},"values":["phs1.c1"]}}],"authorizationFilters":[{{"conceptPath":{c},"values":["phs1.c2"]}}]}}"#
    );
    let object = format!(r#"{{"query":{inner}}}"#);
    let string = serde_json::to_string(&json!({"query": inner})).unwrap();
    for body in [object, string] {
        let reply = post(&f.app, SYNC, body.clone()).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "3"),
            "{body}"
        );
    }
    let emptied = format!(
        r#"{{"query":{{"expectedResultType":"COUNT","authorizationFilters":[{{"conceptPath":{c},"values":["phs1.c1"]}}],"authorizationFilters":[]}}}}"#
    );
    let reply = post(&f.app, SYNC, emptied).await;
    assert_eq!(reply.body, AUTHORIZATION_FILTER_REQUIRED_MESSAGE);
    let other = r#"{"query":{"expectedResultType":"COUNT","expectedResultType":"COUNT"}}"#;
    let reply = post(&f.app, SYNC, other).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(reply.body.contains("duplicate key"), "{}", reply.body);
}

#[tokio::test]
async fn search_answers_javas_column_meta() {
    let f = main_fixture(true);
    let reply = post(&f.app, "/PIC-SURE/search", r#"{"query":"sex"}"#).await;
    assert_eq!(reply.status, StatusCode::OK);
    let sex = serde_json::to_string(SEX).unwrap();
    assert_eq!(
        reply.body,
        format!(
            r#"{{"results":{{"phenotypes":{{{sex}:{{"name":{sex},"categorical":true,"categoryValues":["F","M"],"observationCount":10,"patientCount":10}}}},"info":{{}}}},"searchQuery":"sex"}}"#
        )
    );
    let age = post(&f.app, "/PIC-SURE/v3/search", r#"{"query":"age"}"#).await;
    assert_eq!(
        age.json()["results"]["phenotypes"][AGE],
        json!({"name": AGE, "categorical": false, "min": 10.0, "max": 100.0, "observationCount": 10, "patientCount": 10})
    );
    let all = post(&f.app, "/PIC-SURE/search", r#"{"query":null}"#).await;
    let phenotypes = all.json()["results"]["phenotypes"].clone();
    assert_eq!(phenotypes.as_array().unwrap().len(), 3, "{phenotypes}");
    assert_eq!(
        phenotypes[0][CONSENTS]["categoryValues"],
        json!(["phs1.c1", "phs1.c2"])
    );
}
