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

/// A COUNT envelope on `path` with `min` and `max` written into the body text verbatim, so
/// the service, not a test-side JSON library, reads the numbers.
fn raw_range_body(path: &str, min: &str, max: &str) -> String {
    format!(
        r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"FILTER","conceptPath":{},"min":{min},"max":{max}}}}},"resourceUUID":null}}"#,
        serde_json::to_string(path).unwrap()
    )
}

#[tokio::test]
async fn a_bound_one_ulp_above_a_stored_height_counts_as_java_does() {
    let f = fixture();
    let height = r"\open_access-1000Genomes\data\SYNTHETIC_HEIGHT\";
    let reply = post(
        &f.app,
        SYNC,
        raw_range_body(height, "119.38000000000001", "1000"),
    )
    .await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "1125")
    );
    let reply = post(&f.app, SYNC, raw_range_body(height, "119.38", "1000")).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "1126")
    );
}

#[tokio::test]
async fn an_integer_bound_as_wide_as_double_max_is_accepted_as_jackson_accepts_it() {
    let f = fixture();
    let double_max = "179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368";
    let reply = post(&f.app, SYNC, raw_range_body(AGE, "0", double_max)).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "1126")
    );
}

#[tokio::test]
async fn an_overflowing_bound_is_infinity_and_counts_as_java_does() {
    let f = fixture();
    for (min, max, count) in [
        ("0", "1e309", "1126"),
        ("1e309", "1e310", "0"),
        ("-1e309", "1000", "1126"),
    ] {
        let reply = post(&f.app, SYNC, raw_range_body(AGE, min, max)).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, count),
            "{min} {max}"
        );
    }
}

#[tokio::test]
async fn a_number_longer_than_jacksons_limit_is_a_400() {
    let f = fixture();
    let long = format!("1{}", "0".repeat(1000));
    let reply = post(&f.app, SYNC, raw_range_body(AGE, &long, "1")).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert_eq!(
        reply.body,
        "the request body is not valid JSON: Number value length (1001) exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)"
    );
    let as_string = json!({
        "query": format!(r#"{{"expectedResultType":"COUNT","x":{long}}}"#),
        "resourceUUID": null
    });
    let reply = post(&f.app, SYNC, as_string.to_string()).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert!(
        reply.body.starts_with(
            "the query is not valid: Number value length (1001) exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)"
        ),
        "{}",
        reply.body
    );
}

#[tokio::test]
async fn an_overflowing_number_in_an_ignored_field_is_accepted() {
    let f = fixture();
    for extra in [r#""extra":1e309"#, r#""resourceCredentials":{"k":-1e309}"#] {
        let body =
            format!(r#"{{"query":{{"expectedResultType":"COUNT"}},"resourceUUID":null,{extra}}}"#);
        let reply = post(&f.app, SYNC, body).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "4978"),
            "{extra}"
        );
    }
}

#[tokio::test]
async fn a_string_bound_jackson_refuses_is_a_400() {
    let f = fixture();
    for min in [r#""1d""#, r#""0x1p3""#, r#""-NaN""#] {
        let reply = post(&f.app, SYNC, raw_range_body(AGE, min, "100")).await;
        assert_eq!(
            reply.status,
            StatusCode::BAD_REQUEST,
            "{min}: {}",
            reply.body
        );
    }
    let reply = post(&f.app, SYNC, raw_range_body(AGE, r#"" 40 ""#, r#""+100""#)).await;
    assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
    for null in [r#""null""#, r#"" null ""#] {
        let reply = post(&f.app, SYNC, raw_range_body(AGE, null, "100")).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "1126"),
            "{null}"
        );
    }
}

#[tokio::test]
async fn not_is_read_as_jacksons_boolean_coercion_reads_it() {
    let f = fixture();
    let body = |not: &str| {
        format!(
            r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"REQUIRED","conceptPath":{},"not":{not}}}}},"resourceUUID":null}}"#,
            serde_json::to_string(SEX).unwrap()
        )
    };
    for accepted in [
        r#""TRUE""#,
        r#"" true ""#,
        r#""null""#,
        r#""  ""#,
        "2",
        "0",
        "null",
    ] {
        let reply = post(&f.app, SYNC, body(accepted)).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "4978"),
            "{accepted}"
        );
    }
    for refused in [r#""tRuE""#, r#""yes""#, r#""1""#, "1.0"] {
        let reply = post(&f.app, SYNC, body(refused)).await;
        assert_eq!(
            reply.status,
            StatusCode::BAD_REQUEST,
            "{refused}: {}",
            reply.body
        );
    }
}

#[tokio::test]
async fn enums_are_read_as_jacksons_enum_coercion_reads_them() {
    let f = fixture();
    for result_type in [r#"" COUNT ""#, r#""0""#, "0"] {
        let body =
            format!(r#"{{"query":{{"expectedResultType":{result_type}}},"resourceUUID":null}}"#);
        let reply = post(&f.app, SYNC, body).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "4978"),
            "{result_type}"
        );
    }
    for result_type in [r#""count""#, r#""01""#, r#""null""#] {
        let body =
            format!(r#"{{"query":{{"expectedResultType":{result_type}}},"resourceUUID":null}}"#);
        let reply = post(&f.app, SYNC, body).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{result_type}");
    }
}

#[tokio::test]
async fn uuids_are_read_as_jacksons_uuid_coercion_reads_them() {
    let f = fixture();
    let uuid = "8694E3D4-5CB4-410F-8431-993445E6D3F6";
    for (field, value) in [
        ("picsureId", format!(r#"" {uuid} ""#)),
        ("picsureId", r#""""#.to_owned()),
        ("id", r#""  ""#.to_owned()),
        ("picsureId", r#""hpTj1Fy0QQ+EMZk0ReY99g==""#.to_owned()),
        ("id", r#"" hpTj1Fy0QQ-EMZk0ReY99g ""#.to_owned()),
    ] {
        let body = format!(
            r#"{{"query":{{"expectedResultType":"COUNT","{field}":{value}}},"resourceUUID":" "}}"#
        );
        let reply = post(&f.app, SYNC, body).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (StatusCode::OK, "4978"),
            "{field} {value}"
        );
    }
    let base64_resource =
        r#"{"query":{"expectedResultType":"COUNT"},"resourceUUID":"hpTj1Fy0QQ_EMZk0ReY99g"}"#;
    let reply = post(&f.app, SYNC, base64_resource).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "4978")
    );
    for refused in [
        r#"{"query":{"expectedResultType":"COUNT","picsureId":"null"},"resourceUUID":null}"#,
        r#"{"query":{"expectedResultType":"COUNT","picsureId":"hpTj1Fy0QQ+EMZk0ReY99g="},"resourceUUID":null}"#,
        r#"{"query":{"expectedResultType":"COUNT","id":"hpTj1Fy0QQ+EMZk0ReY99gAA"},"resourceUUID":null}"#,
        r#"{"query":{"expectedResultType":"COUNT"},"resourceUUID":"hpTj1Fy0QQ+EMZk0ReY99"}"#,
    ] {
        assert_eq!(
            post(&f.app, SYNC, refused).await.status,
            StatusCode::BAD_REQUEST,
            "{refused}"
        );
    }
}

#[tokio::test]
async fn scalars_in_string_fields_are_read_as_the_text_of_each_query_path() {
    let f = fixture();
    let query = r#"{"expectedResultType":"CROSS_COUNT","select":[1.50,1e3,-0,1e309,true]}"#;
    let cross_counts = |reply: common::Reply| {
        assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
        serde_json::from_str::<serde_json::Value>(&reply.body).unwrap()
    };
    let object = format!(r#"{{"query":{query},"resourceUUID":null}}"#);
    assert_eq!(
        cross_counts(post(&f.app, SYNC, object).await),
        json!({"1.5": 0, "1000.0": 0, "0": 0, "Infinity": 0, "true": 0})
    );
    let string = format!(
        r#"{{"query":{},"resourceUUID":null}}"#,
        serde_json::to_string(query).unwrap()
    );
    assert_eq!(
        cross_counts(post(&f.app, SYNC, string).await),
        json!({"1.50": 0, "1e3": 0, "-0": 0, "1e309": 0, "true": 0})
    );
    let wide_not = format!(
        r#"{{"query":{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"REQUIRED","conceptPath":{},"not":99999999999999999999}}}},"resourceUUID":null}}"#,
        serde_json::to_string(SEX).unwrap()
    );
    let reply = post(&f.app, SYNC, wide_not).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::OK, "4978")
    );
    let minus_zero_not = format!(
        r#"{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"REQUIRED","conceptPath":{},"not":-0}}}}"#,
        serde_json::to_string(SEX).unwrap()
    );
    let required = post(
        &f.app,
        SYNC,
        format!(r#"{{"query":{minus_zero_not},"resourceUUID":null}}"#),
    )
    .await;
    assert_eq!(required.status, StatusCode::OK, "{}", required.body);
    let as_string = post(
        &f.app,
        SYNC,
        format!(
            r#"{{"query":{},"resourceUUID":null}}"#,
            serde_json::to_string(&minus_zero_not).unwrap()
        ),
    )
    .await;
    assert_eq!(
        (as_string.status, as_string.body.as_str()),
        (StatusCode::OK, required.body.as_str())
    );
    let nested =
        r#"{"query":{"expectedResultType":"CROSS_COUNT","select":[[1]]},"resourceUUID":null}"#;
    assert_eq!(
        post(&f.app, SYNC, nested).await.status,
        StatusCode::BAD_REQUEST
    );
}
