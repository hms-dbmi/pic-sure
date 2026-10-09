mod common;

use axum::http::StatusCode;
use common::{AGE, POPULATION, SEX, envelope, fixture, fixture_with, post};
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
    assert_eq!(reply.content_type(), Some("text/plain; charset=utf-8"));
    assert_eq!(
        reply.body,
        "Result type PATIENTS is served asynchronously. Submit it with POST /query, poll \
         /query/{resourceQueryId}/status, then collect it from /query/{resourceQueryId}/result."
    );
}

#[tokio::test]
async fn invalid_requests_are_plain_text_400s() {
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
        assert_eq!(
            reply.content_type(),
            Some("text/plain; charset=utf-8"),
            "{body}"
        );
        assert!(!reply.body.is_empty(), "{body}");
        assert!(serde_json::from_str::<serde_json::Value>(&reply.body).is_err());
        assert!(!reply.headers.contains_key("querymetadata"));
    }
}

#[tokio::test]
async fn v1_queries_are_refused() {
    let f = fixture();
    let v1 = [
        json!({"fields": [SEX], "expectedResultType": "COUNT"}),
        json!({"categoryFilters": {SEX: ["female"]}, "expectedResultType": "COUNT"}),
        json!({"numericFilters": {}, "requiredFields": [SEX], "expectedResultType": "COUNT"}),
        json!({"anyRecordOf": [SEX], "expectedResultType": "COUNT"}),
        json!({"variantInfoFilters": [], "expectedResultType": "COUNT"}),
        json!({"crossCountFields": [SEX], "expectedResultType": "COUNT"}),
    ];
    for query in v1 {
        for path in [SYNC, "/PIC-SURE/v3/query"] {
            let reply = post(&f.app, path, envelope(query.clone())).await;
            assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{query}");
            assert_eq!(reply.body, "v1 queries are not supported; use /PIC-SURE/v3");
        }
    }
}

#[tokio::test]
async fn info_column_listing_is_an_empty_array() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "INFO_COLUMN_LISTING"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.content_type(), Some("application/json"));
    assert_eq!(reply.body, "[]");
}

#[tokio::test]
async fn other_genomic_types_name_the_unsupported_feature() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "VCF_EXCERPT"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(reply.body.contains("not supported"), "{}", reply.body);
}

#[tokio::test]
async fn variant_count_without_genomic_filters_is_javas_empty_answer() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "VARIANT_COUNT_FOR_QUERY"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.content_type(), Some("application/json"));
    assert_eq!(
        reply.body,
        r#"{"count":"0","message":"No variant filters were supplied, so no query was run."}"#
    );
    assert!(reply.headers.get("queryMetadata").is_some());
}

#[tokio::test]
async fn variant_count_with_genomic_filters_is_unsupported() {
    let f = fixture();
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({
            "expectedResultType": "VARIANT_COUNT_FOR_QUERY",
            "genomicFilters": [{"key": "k", "values": ["v"]}]
        })),
    )
    .await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(reply.body.contains("not supported"), "{}", reply.body);
}

#[tokio::test]
async fn variant_count_still_requires_consents_when_the_flag_is_on() {
    let f = fixture_with(|c| c.partitions.require_consents = true);
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "VARIANT_COUNT_FOR_QUERY"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(
        reply.body.contains("consents are required"),
        "{}",
        reply.body
    );
}

#[tokio::test]
async fn a_sync_query_that_outlasts_the_timeout_is_a_503() {
    let f = fixture_with(|c| c.sync_timeout = std::time::Duration::from_millis(100));
    let mut held = Vec::new();
    for _ in 0..f.config.engine_concurrency {
        held.push(f.state.engine_slot().await);
    }
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::SERVICE_UNAVAILABLE);
    assert!(reply.body.contains("did not finish"), "{}", reply.body);
    drop(held);
    let reply = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(reply.status, StatusCode::OK);
}

#[tokio::test]
async fn sync_queries_are_not_validated_and_authorization_filters_are_ignored() {
    let f = fixture();
    let unknown_authorization = json!({
        "expectedResultType": "COUNT",
        "authorizationFilters": [{"conceptPath": "\\_consents\\", "values": ["phs000001.c1"]}]
    });
    let reply = post(&f.app, SYNC, envelope(unknown_authorization)).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "4978")
    );

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
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "4978")
    );
}

#[tokio::test]
async fn oversized_bodies_are_refused() {
    let f = fixture();
    let reply = post(&f.app, SYNC, "x".repeat(3 << 20)).await;
    assert_eq!(reply.status, StatusCode::PAYLOAD_TOO_LARGE);
}
