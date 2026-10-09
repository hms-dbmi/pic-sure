mod common;

use axum::http::StatusCode;
use common::{AGE, POPULATION, SEX, envelope, fixture, post};
use serde_json::json;

const SYNC: &str = "/PIC-SURE/v3/query/sync";

fn female() -> serde_json::Value {
    json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["female"]})
}

#[tokio::test]
async fn count_is_plain_text_with_query_metadata_on_both_prefixes() {
    let f = fixture();
    for path in [SYNC, "/PIC-SURE/query/sync"] {
        let reply = post(
            &f.app,
            path,
            envelope(json!({"expectedResultType": "COUNT", "phenotypicClause": female()})),
        )
        .await;
        assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
        assert_eq!(reply.body, "2330");
        assert_eq!(reply.content_type(), Some("text/plain"));
        let id = reply.headers["querymetadata"].to_str().unwrap();
        assert!(hpds_rs::envelope::canonical_uuid(id).is_some());
    }
}

#[tokio::test]
async fn query_may_be_a_json_string_and_unknown_envelope_fields_are_ignored() {
    let f = fixture();
    let body = json!({"query": json!({"expectedResultType": "COUNT"}).to_string(), "resourceUUID": null, "extra": [1]});
    let reply = post(&f.app, SYNC, body.to_string()).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "4978")
    );
}

#[tokio::test]
async fn cross_counts_are_json() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "CROSS_COUNT", "select": [SEX, AGE]})),
    )
    .await;
    assert_eq!(reply.content_type(), Some("application/json"));
    assert_eq!(reply.json(), json!({SEX: 4978, AGE: 1126}));
    assert!(reply.headers.contains_key("querymetadata"));

    let categorical = post(
        &f.app,
        SYNC,
        envelope(
            json!({"expectedResultType": "CATEGORICAL_CROSS_COUNT", "phenotypicClause": female()}),
        ),
    )
    .await;
    assert_eq!(categorical.json(), json!({SEX: {"female": 2330}}));

    let range = json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "min": 35, "max": 45});
    for result_type in ["CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT"] {
        let reply = post(
            &f.app,
            SYNC,
            envelope(json!({"expectedResultType": result_type, "select": [AGE], "phenotypicClause": range})),
        )
        .await;
        assert_eq!(
            reply.status,
            StatusCode::OK,
            "{result_type}: {}",
            reply.body
        );
        assert_eq!(reply.content_type(), Some("application/json"));
    }
}

#[tokio::test]
async fn export_types_get_javas_text_message() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "PATIENTS"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert_eq!(reply.content_type(), Some("text/plain"));
    assert_eq!(
        reply.body,
        "Result type PATIENTS is served asynchronously. Submit it with POST /query, poll \
         /query/{resourceQueryId}/status, then collect it from /query/{resourceQueryId}/result."
    );
}

#[tokio::test]
async fn invalid_requests_are_json_400s() {
    let f = fixture();
    let filter_without_criteria = json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX});
    let cases = [
        "{\"query\": ".to_owned(),
        "[]".to_owned(),
        envelope(json!("{\"expectedResultType\": ")),
        envelope(json!({"expectedResultType": "COUNT", "bogusField": 1})),
        envelope(
            json!({"expectedResultType": "COUNT", "phenotypicClause": filter_without_criteria}),
        ),
        envelope(
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": "AND", "phenotypicClauses": []}}),
        ),
        envelope(
            json!({"expectedResultType": "COUNT", "genomicFilters": [{"key": "Gene_with_variant", "values": ["APOE"]}]}),
        ),
        envelope(json!({"expectedResultType": "VCF_EXCERPT"})),
        envelope(
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": AGE, "values": ["31"]}}),
        ),
    ];
    for body in cases {
        let reply = post(&f.app, SYNC, body.clone()).await;
        assert_eq!(
            reply.status,
            StatusCode::BAD_REQUEST,
            "{body}: {}",
            reply.body
        );
        assert_eq!(reply.content_type(), Some("application/json"), "{body}");
        let json = reply.json();
        assert_eq!(json["error"], "Bad Request");
        assert!(json["message"].as_str().is_some_and(|m| !m.is_empty()));
        assert!(!reply.headers.contains_key("querymetadata"));
    }
}

#[tokio::test]
async fn sync_queries_are_not_validated() {
    let f = fixture();
    let missing_consents = json!({
        "expectedResultType": "COUNT",
        "authorizationFilters": [{"conceptPath": "\\_consents\\", "values": ["phs000001.c1"]}]
    });
    let reply = post(&f.app, SYNC, envelope(missing_consents)).await;
    assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, "0"));

    let required_with_values = json!({
        "expectedResultType": "COUNT",
        "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": AGE, "values": []}
    });
    let reply = post(&f.app, SYNC, envelope(required_with_values)).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "1126")
    );

    let values_and_bounds = json!({
        "expectedResultType": "COUNT",
        "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["female"], "min": 0, "max": 1}
    });
    let reply = post(&f.app, SYNC, envelope(values_and_bounds)).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "2330")
    );

    let empty_authorization = json!({
        "expectedResultType": "COUNT",
        "authorizationFilters": [{"conceptPath": POPULATION, "values": []}]
    });
    let reply = post(&f.app, SYNC, envelope(empty_authorization)).await;
    assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, "0"));
}

#[tokio::test]
async fn oversized_bodies_are_refused() {
    let f = fixture();
    let reply = post(&f.app, SYNC, "x".repeat(3 << 20)).await;
    assert_eq!(reply.status, StatusCode::PAYLOAD_TOO_LARGE);
}
