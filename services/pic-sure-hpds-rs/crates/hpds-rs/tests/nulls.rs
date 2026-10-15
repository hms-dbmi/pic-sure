//! A JSON `null` where the v3 model holds a String, on the sync route and on `POST /query`,
//! sent as an object query and as a string query. Each case answers what Java answers when
//! Java answers, and is refused with a named message where Java fails.

mod common;

use axum::Router;
use axum::http::StatusCode;
use common::{AGE, POPULATION, SEX, fixture, post, wait};
use hpds_query::{
    NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE, NULL_CONCEPT_PATH_MESSAGE, NULL_RESULT_TYPE_MESSAGE,
    NULL_SELECT_MESSAGE, NULL_VALUE_MESSAGE,
};
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const QUERY: &str = "/PIC-SURE/v3/query";

fn required_sex() -> Value {
    json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX})
}

/// The body sending `query` as a JSON object and the body sending it as a JSON string.
fn both_paths(query: &Value) -> [String; 2] {
    [
        json!({"query": query, "resourceUUID": null}).to_string(),
        json!({"query": query.to_string(), "resourceUUID": null}).to_string(),
    ]
}

/// Status and body of `query` posted to `path` both ways, checked to have the same status and,
/// for a 200, the same body.
async fn answer(app: &Router, path: &str, query: &Value) -> (StatusCode, String) {
    let [object, string] = both_paths(query);
    let first = post(app, path, object).await;
    let second = post(app, path, string).await;
    assert_eq!(first.status, second.status, "{query}");
    if first.status == StatusCode::OK && path == SYNC {
        assert_eq!(first.body, second.body, "{query}");
    }
    (first.status, first.body)
}

/// Asserts that `query` is refused with 400 and exactly `message` as its body, both ways.
async fn refused(app: &Router, path: &str, query: Value, message: &str) {
    for body in both_paths(&query) {
        let reply = post(app, path, body).await;
        assert_eq!(
            reply.status,
            StatusCode::BAD_REQUEST,
            "{query}: {}",
            reply.body
        );
        assert_eq!(reply.body, message, "{query}");
    }
}

#[tokio::test]
async fn a_null_select_element_is_ignored_where_java_never_reads_select() {
    let f = fixture();
    for result_type in [
        "COUNT",
        "CATEGORICAL_CROSS_COUNT",
        "CONTINUOUS_CROSS_COUNT",
        "INFO_COLUMN_LISTING",
    ] {
        let plain = json!({"expectedResultType": result_type, "phenotypicClause": required_sex(), "select": [AGE]});
        let with_null = json!({"expectedResultType": result_type, "phenotypicClause": required_sex(), "select": [null, AGE, null]});
        let expected = answer(&f.app, SYNC, &plain).await;
        assert_eq!(expected.0, StatusCode::OK, "{result_type}: {}", expected.1);
        let got = answer(&f.app, SYNC, &with_null).await;
        assert_eq!(got, expected, "{result_type}");
    }
}

#[tokio::test]
async fn a_null_select_element_is_refused_where_java_fails_on_it() {
    let f = fixture();
    for result_type in ["CROSS_COUNT", "OBSERVATION_CROSS_COUNT"] {
        for select in [json!([null]), json!([AGE, null])] {
            let query = json!({"expectedResultType": result_type, "select": select});
            refused(&f.app, SYNC, query, NULL_SELECT_MESSAGE).await;
        }
    }
    for result_type in ["PATIENTS", "DATAFRAME", "COUNT"] {
        let query = json!({"expectedResultType": result_type, "phenotypicClause": required_sex(), "select": [AGE, null]});
        refused(&f.app, QUERY, query, NULL_SELECT_MESSAGE).await;
    }
    for result_type in ["DATAFRAME", "PATIENTS"] {
        let query = json!({"expectedResultType": result_type, "select": [null]});
        let (status, body) = answer(&f.app, SYNC, &query).await;
        assert_eq!(status, StatusCode::BAD_REQUEST);
        assert!(body.contains("is served asynchronously"), "{body}");
    }
}

#[tokio::test]
async fn nulls_in_authorization_filters_are_ignored_on_the_sync_route() {
    let f = fixture();
    let plain = json!({"expectedResultType": "COUNT", "phenotypicClause": required_sex()});
    let expected = answer(&f.app, SYNC, &plain).await;
    assert_eq!(expected, (StatusCode::OK, "4978".to_owned()));
    for filter in [
        json!({"conceptPath": POPULATION, "values": [null]}),
        json!({"conceptPath": POPULATION, "values": [null, "Finnish"]}),
        json!({"conceptPath": null, "values": ["Finnish"]}),
    ] {
        let query = json!({"expectedResultType": "COUNT", "phenotypicClause": required_sex(), "authorizationFilters": [filter]});
        assert_eq!(answer(&f.app, SYNC, &query).await, expected, "{filter}");
    }
}

#[tokio::test]
async fn nulls_in_authorization_filters_on_post_query_follow_the_validator() {
    let f = fixture();
    let plain = json!({"expectedResultType": "PATIENTS", "phenotypicClause": required_sex()});
    let with_null_value = json!({"expectedResultType": "PATIENTS", "phenotypicClause": required_sex(), "authorizationFilters": [{"conceptPath": POPULATION, "values": [null]}]});
    let mut exports = Vec::new();
    for query in [&plain, &with_null_value] {
        for body in both_paths(query) {
            let submitted = post(&f.app, QUERY, body).await;
            assert_eq!(submitted.status, StatusCode::OK, "{}", submitted.body);
            let id = submitted.json()["resourceResultId"]
                .as_str()
                .unwrap()
                .to_owned();
            assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
            let result = post(&f.app, &format!("{QUERY}/{id}/result"), "{}").await;
            exports.push(result.body);
        }
    }
    assert!(exports.windows(2).all(|w| w[0] == w[1]));
    let null_path = json!({"expectedResultType": "PATIENTS", "phenotypicClause": required_sex(), "authorizationFilters": [{"conceptPath": null, "values": ["Finnish"]}]});
    refused(
        &f.app,
        QUERY,
        null_path,
        NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
    )
    .await;
}

#[tokio::test]
async fn null_concept_paths_values_and_result_types_are_refused_on_both_routes() {
    let f = fixture();
    let cases = [
        (
            json!({"phenotypicFilterType": "REQUIRED", "conceptPath": null}),
            NULL_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!({"phenotypicFilterType": "ANY_RECORD_OF", "conceptPath": null}),
            NULL_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": null, "min": 1}),
            NULL_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!({"operator": "OR", "phenotypicClauses": [required_sex(), {"phenotypicFilterType": "REQUIRED", "conceptPath": null}]}),
            NULL_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": [null]}),
            NULL_VALUE_MESSAGE,
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": [null, "female"]}),
            NULL_VALUE_MESSAGE,
        ),
    ];
    for (clause, message) in cases {
        for (path, result_type) in [(SYNC, "COUNT"), (QUERY, "PATIENTS")] {
            if path == QUERY && message == NULL_VALUE_MESSAGE {
                continue;
            }
            let query = json!({"expectedResultType": result_type, "phenotypicClause": clause});
            refused(&f.app, path, query, message).await;
        }
    }
    for path in [SYNC, QUERY] {
        for query in [
            json!({"expectedResultType": null, "phenotypicClause": required_sex()}),
            json!({"phenotypicClause": required_sex()}),
        ] {
            refused(
                &f.app,
                path,
                query,
                &format!("the query is not valid: {NULL_RESULT_TYPE_MESSAGE}"),
            )
            .await;
        }
    }
}
