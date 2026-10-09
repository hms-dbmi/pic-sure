//! Consent scoping over HTTP on the four-partition fixture, with
//! `hpds.requireAuthorizationFilter` off and on, and the strict request parsing every route
//! applies.

mod common;

use axum::Router;
use axum::http::StatusCode;
use common::{
    AGE, PARTITION_PATIENTS, PARTITION_RANGES, SEX, consents, envelope, fixture, fixture_with, get,
    post, strict_fixture, wait,
};
use hpds_rs::sync::QUERY_METADATA_HEADER;
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const QUERY: &str = "/PIC-SURE/v3/query";
const CONSENTS_REQUIRED: &str = "User consents are required for this query";

fn age_35_to_45() -> Value {
    json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "min": 35, "max": 45})
}

fn count(user_consents: Option<Value>, clause: Option<Value>) -> Value {
    let mut query = json!({"expectedResultType": "COUNT"});
    if let Some(consents) = user_consents {
        query["userConsents"] = consents;
    }
    if let Some(clause) = clause {
        query["phenotypicClause"] = clause;
    }
    query
}

async fn count_text(app: &Router, query: Value) -> (StatusCode, String) {
    let reply = post(app, SYNC, envelope(query)).await;
    (reply.status, reply.body)
}

/// Submits `query`, waits for it, and returns its CSV result.
async fn export(app: &Router, query: Value) -> String {
    let reply = post(app, QUERY, envelope(query)).await;
    assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
    let id = reply.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    let done = wait(app, &id).await;
    assert_eq!(done["resourceStatus"], "SUCCESS", "{done}");
    let result = post(app, &format!("{QUERY}/{id}/result"), "{}").await;
    assert_eq!(result.status, StatusCode::OK);
    result.body
}

fn patient_ids(csv: &str) -> Vec<u32> {
    csv.lines()
        .skip(1)
        .map(|line| line.split(',').next().unwrap().parse().unwrap())
        .collect()
}

fn in_partitions(id: u32, partitions: &[usize]) -> bool {
    partitions.iter().any(|&p| {
        let (start, end) = PARTITION_RANGES[p];
        (start..end).contains(&id)
    })
}

#[tokio::test]
async fn sync_counts_read_the_partitions_the_consents_select() {
    let f = fixture();
    let p = |names: &[&str]| Some(consents(names));
    for (user_consents, clause, expected) in [
        (None, None, "4978"),
        (Some(json!([])), None, "4978"),
        (p(&["partition1"]), None, "494"),
        (p(&["partition4"]), None, "2484"),
        (p(&["partition2", "partition3"]), None, "2000"),
        (p(&["phs999999.c1"]), None, "0"),
        (None, Some(age_35_to_45()), "562"),
        (p(&["partition1"]), Some(age_35_to_45()), "50"),
        (
            p(&["partition2", "partition3"]),
            Some(age_35_to_45()),
            "151",
        ),
        (
            p(&["partition1", "partition4"]),
            Some(age_35_to_45()),
            "411",
        ),
        (
            p(&["partition2", "partition3452345", "partition3"]),
            Some(age_35_to_45()),
            "151",
        ),
    ] {
        let query = count(user_consents, clause);
        assert_eq!(
            count_text(&f.app, query.clone()).await,
            (StatusCode::OK, expected.to_owned()),
            "{query}"
        );
    }
}

#[tokio::test]
async fn strict_sync_queries_need_a_consent_value() {
    let f = strict_fixture();
    for user_consents in [None, Some(json!([])), Some(json!([{"value": null}]))] {
        let query = count(user_consents, None);
        let reply = post(&f.app, SYNC, envelope(query.clone())).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{query}");
        assert_eq!(reply.body, CONSENTS_REQUIRED, "{query}");
        assert_eq!(reply.content_type(), Some("text/plain; charset=utf-8"));
        assert!(reply.headers.get(QUERY_METADATA_HEADER).is_none());
    }
    let cross = json!({"expectedResultType": "CROSS_COUNT", "select": [SEX]});
    let reply = post(&f.app, SYNC, envelope(cross)).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::BAD_REQUEST, CONSENTS_REQUIRED)
    );
    let scoped = count(Some(consents(&["partition1"])), Some(age_35_to_45()));
    let reply = post(&f.app, SYNC, envelope(scoped)).await;
    assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, "50"));
    assert!(reply.headers.get(QUERY_METADATA_HEADER).is_some());
    let off_node = count(Some(consents(&["phs999999.c1"])), None);
    assert_eq!(
        count_text(&f.app, off_node).await,
        (StatusCode::OK, "0".to_owned())
    );
    let listing = json!({"expectedResultType": "INFO_COLUMN_LISTING"});
    let reply = post(&f.app, SYNC, envelope(listing)).await;
    assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, "[]"));
}

#[tokio::test]
async fn the_limit_shape_is_checked_before_the_consents() {
    let f = fixture_with(|c| {
        c.partitions.require_consents = true;
        c.query_limits.max_select_paths = 1;
    });
    let wide = json!({"expectedResultType": "CROSS_COUNT", "select": [SEX, AGE]});
    let reply = post(&f.app, SYNC, envelope(wide.clone())).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(
        reply.body.contains("HPDS_MAX_SELECT_PATHS"),
        "{}",
        reply.body
    );
    let mut export = wide;
    export["expectedResultType"] = json!("DATAFRAME");
    let reply = post(&f.app, QUERY, envelope(export)).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(
        reply.body.contains("HPDS_MAX_SELECT_PATHS"),
        "{}",
        reply.body
    );
    let narrow = json!({"expectedResultType": "CROSS_COUNT", "select": [SEX]});
    let reply = post(&f.app, SYNC, envelope(narrow)).await;
    assert_eq!(reply.body, CONSENTS_REQUIRED);
}

#[tokio::test]
async fn strict_async_queries_need_a_consent_value() {
    let f = strict_fixture();
    for user_consents in [None, Some(json!([])), Some(json!([{"value": null}]))] {
        for result_type in ["PATIENTS", "DATAFRAME"] {
            let mut query = json!({"expectedResultType": result_type, "select": [AGE]});
            if let Some(consents) = &user_consents {
                query["userConsents"] = consents.clone();
            }
            let reply = post(&f.app, QUERY, envelope(query.clone())).await;
            assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{query}");
            assert_eq!(reply.body, CONSENTS_REQUIRED, "{query}");
        }
    }
    assert!(f.state.results().is_empty());
}

#[tokio::test]
async fn async_patients_are_scoped_by_consent() {
    for strict in [false, true] {
        let f = fixture_with(|c| c.partitions.require_consents = strict);
        for (names, partitions) in [
            (vec!["partition1"], vec![0]),
            (vec!["partition2", "partition3"], vec![1, 2]),
            (vec!["partition4", "phs999999.c1"], vec![3]),
        ] {
            let csv = export(
                &f.app,
                json!({"expectedResultType": "PATIENTS", "userConsents": consents(&names)}),
            )
            .await;
            assert_eq!(csv.lines().next(), Some("PATIENT_NUM"));
            let ids = patient_ids(&csv);
            let expected: u64 = partitions.iter().map(|&p| PARTITION_PATIENTS[p]).sum();
            assert_eq!(ids.len() as u64, expected, "{names:?}");
            assert!(ids.iter().all(|&id| in_partitions(id, &partitions)));
            assert!(ids.windows(2).all(|w| w[0] < w[1]));
        }
    }
    let lenient = fixture();
    let all = export(&lenient.app, json!({"expectedResultType": "PATIENTS"})).await;
    assert_eq!(patient_ids(&all).len(), 4978);
}

#[tokio::test]
async fn async_dataframes_are_scoped_by_consent() {
    let f = strict_fixture();
    let csv = export(
        &f.app,
        json!({
            "expectedResultType": "DATAFRAME",
            "select": [AGE],
            "phenotypicClause": age_35_to_45(),
            "userConsents": consents(&["partition1"]),
        }),
    )
    .await;
    assert_eq!(
        csv.lines().next(),
        Some(format!("patient_id,{AGE}").as_str())
    );
    let ids = patient_ids(&csv);
    assert_eq!(ids.len(), 50);
    assert!(ids.iter().all(|&id| in_partitions(id, &[0])));
    for line in csv.lines().skip(1) {
        let age: f64 = line.split(',').nth(1).unwrap().parse().unwrap();
        assert!((35.0..=45.0).contains(&age), "{line}");
    }

    let both = export(
        &f.app,
        json!({
            "expectedResultType": "DATAFRAME",
            "select": [SEX],
            "userConsents": consents(&["partition2", "partition3"]),
        }),
    )
    .await;
    let ids = patient_ids(&both);
    assert_eq!(ids.len(), 2000);
    assert!(ids.iter().all(|&id| in_partitions(id, &[1, 2])));
}

#[tokio::test]
async fn duplicate_keys_are_refused_on_every_query_route() {
    let f = fixture();
    let clause =
        r#"{"phenotypicFilterType":"REQUIRED","conceptPath":"\\a\\","conceptPath":"\\b\\"}"#;
    let bodies = [
        r#"{"query":{"expectedResultType":"COUNT"},"query":{"expectedResultType":"COUNT","userConsents":[{"value":"partition1"}]}}"#.to_owned(),
        r#"{"query":{"expectedResultType":"COUNT","userConsents":[{"value":"partition1"}],"userConsents":[{"value":"partition4"}]}}"#.to_owned(),
        format!(r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{clause}}}}}"#),
        format!(
            r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{{"operator":"AND","phenotypicClauses":[{clause}]}}}}}}"#
        ),
        r#"{"query":"{\"expectedResultType\":\"COUNT\",\"userConsents\":[{\"value\":\"partition1\"}],\"userConsents\":[]}"}"#.to_owned(),
        r#"{"query":"{\"expectedResultType\":\"COUNT\",\"userConsents\":[{\"value\":\"partition1\",\"value\":\"partition4\"}]}"}"#.to_owned(),
        r#"{"query":"{\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"a\",\"conceptPath\":\"b\"}}"}"#.to_owned(),
    ];
    for body in &bodies {
        let reply = post(&f.app, SYNC, body.clone()).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{body}");
        assert!(reply.body.contains("duplicate"), "{body}: {}", reply.body);
        let export = body.replace("COUNT", "PATIENTS");
        let reply = post(&f.app, QUERY, export.clone()).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{export}");
        assert!(reply.body.contains("duplicate"), "{export}: {}", reply.body);
    }
    assert!(f.state.results().is_empty());
}

#[tokio::test]
async fn every_route_answers_on_both_prefixes() {
    let f = strict_fixture();
    let p1 = consents(&["partition1"]);
    for prefix in ["/PIC-SURE/v3", "/PIC-SURE"] {
        let sync = post(
            &f.app,
            &format!("{prefix}/query/sync"),
            envelope(json!({"expectedResultType": "COUNT", "userConsents": p1})),
        )
        .await;
        assert_eq!((sync.status, sync.body.as_str()), (StatusCode::OK, "494"));
        let submitted = post(
            &f.app,
            &format!("{prefix}/query"),
            envelope(json!({"expectedResultType": "PATIENTS", "userConsents": p1})),
        )
        .await;
        assert_eq!(submitted.status, StatusCode::OK, "{}", submitted.body);
        let id = submitted.json()["resourceResultId"]
            .as_str()
            .unwrap()
            .to_owned();
        assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
        let status = post(&f.app, &format!("{prefix}/query/{id}/status"), "{}").await;
        assert_eq!(status.json()["resourceStatus"], "SUCCESS");
        let result = post(&f.app, &format!("{prefix}/query/{id}/result"), "{}").await;
        assert_eq!(result.body.lines().count(), 495);
        for route in [
            format!("{prefix}/query/{id}/signed-url"),
            format!("{prefix}/query/format"),
            format!("{prefix}/write/CSV"),
        ] {
            let reply = post(&f.app, &route, "{}").await;
            assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{route}");
        }
        let search = post(&f.app, &format!("{prefix}/search"), r#"{"query":"SEX"}"#).await;
        assert_eq!(search.status, StatusCode::OK);
        let values = get(
            &f.app,
            &format!("{prefix}/search/values/?genomicConceptPath=g&query=a&page=1&size=1"),
        )
        .await;
        assert_eq!(values.status, StatusCode::OK);
    }
    let health = get(&f.app, "/actuator/health").await;
    assert_eq!(health.body, r#"{"status":"UP"}"#);
}
