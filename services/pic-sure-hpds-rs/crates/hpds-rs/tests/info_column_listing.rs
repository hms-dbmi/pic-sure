//! INFO_COLUMN_LISTING on the sync route answers `[]` without evaluating the query, as Java's
//! sync switch does, whatever the clause, filters or select hold, while JSON or content Jackson
//! itself refuses is still refused. The constructs Java reads but fails on when it evaluates a
//! clause are refused by every other result type with a message naming them.

mod common;

use axum::Router;
use axum::http::StatusCode;
use common::{AGE, SEX, fixture, post, wait};
use hpds_query::{
    NULL_AUTHORIZATION_FILTER_MESSAGE, NULL_CLAUSE_MESSAGE, NULL_CONCEPT_PATH_MESSAGE,
    NULL_FILTER_TYPE_MESSAGE, NULL_VALUE_MESSAGE,
};
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const QUERY: &str = "/PIC-SURE/v3/query";

/// The body sending `query` as a JSON object and the body sending it as a JSON string.
fn both_paths(query: &Value) -> [String; 2] {
    [
        json!({"query": query, "resourceUUID": null}).to_string(),
        json!({"query": query.to_string(), "resourceUUID": null}).to_string(),
    ]
}

async fn replies(app: &Router, path: &str, query: &Value) -> Vec<(StatusCode, String)> {
    let mut out = Vec::new();
    for body in both_paths(query) {
        let reply = post(app, path, body).await;
        out.push((reply.status, reply.body));
    }
    out
}

fn required_sex() -> Value {
    json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX})
}

#[tokio::test]
async fn info_column_listing_answers_without_evaluating_the_query() {
    let f = fixture();
    let clauses = [
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": null}),
        json!({"phenotypicFilterType": "REQUIRED"}),
        json!({"conceptPath": SEX}),
        json!({"phenotypicFilterType": null, "conceptPath": SEX}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": [null]}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "values": ["x"]}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "min": 1}),
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "\\no\\such\\"}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "values": ["x"], "min": 1}),
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "values": ["male"]}),
        json!({"phenotypicClauses": [required_sex()]}),
        json!({"operator": "AND", "phenotypicClauses": []}),
        json!({"operator": "AND", "phenotypicClauses": [null]}),
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "rs123"}),
    ];
    let mut queries: Vec<Value> = clauses
        .into_iter()
        .map(|clause| json!({"expectedResultType": "INFO_COLUMN_LISTING", "phenotypicClause": clause}))
        .collect();
    for extra in [
        json!({"genomicFilters": [{"key": "Gene_with_variant", "values": ["CHD8"]}]}),
        json!({"genomicFilters": [null]}),
        json!({"genomicFilters": [{"key": "k", "values": [null]}]}),
        json!({"authorizationFilters": [null]}),
        json!({"authorizationFilters": [{"conceptPath": AGE, "values": ["1"]}]}),
        json!({"authorizationFilters": [{"conceptPath": SEX, "values": []}]}),
        json!({"select": ["rs123", null]}),
    ] {
        let mut query = json!({"expectedResultType": "INFO_COLUMN_LISTING"});
        query
            .as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        queries.push(query);
    }
    for query in queries {
        for (status, body) in replies(&f.app, SYNC, &query).await {
            assert_eq!((status, body.as_str()), (StatusCode::OK, "[]"), "{query}");
        }
    }
}

#[tokio::test]
async fn info_column_listing_still_refuses_what_jackson_refuses() {
    let f = fixture();
    for clause in [
        json!({}),
        json!({"not": true}),
        json!({"operator": "AND", "conceptPath": SEX}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "min": "1d"}),
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "bogus": 1}),
    ] {
        let query =
            json!({"expectedResultType": "INFO_COLUMN_LISTING", "phenotypicClause": clause});
        for (status, body) in replies(&f.app, SYNC, &query).await {
            assert_eq!(status, StatusCode::BAD_REQUEST, "{query}: {body}");
        }
    }
    for query in [
        json!({"expectedResultType": "INFO_COLUMN_LISTING", "picsureId": "not-a-uuid"}),
        json!({"expectedResultType": "INFO_COLUMN_LISTING", "bogus": 1}),
    ] {
        for (status, body) in replies(&f.app, SYNC, &query).await {
            assert_eq!(status, StatusCode::BAD_REQUEST, "{query}: {body}");
        }
    }
}

#[tokio::test]
async fn other_result_types_refuse_the_deferred_constructs_by_name() {
    let f = fixture();
    let cases = [
        (
            json!({"phenotypicFilterType": "REQUIRED"}),
            NULL_CONCEPT_PATH_MESSAGE,
        ),
        (json!({"conceptPath": SEX}), NULL_FILTER_TYPE_MESSAGE),
        (
            json!({"phenotypicFilterType": null, "conceptPath": SEX}),
            NULL_FILTER_TYPE_MESSAGE,
        ),
        (
            json!({"operator": "OR", "phenotypicClauses": [required_sex(), null]}),
            NULL_CLAUSE_MESSAGE,
        ),
    ];
    for (clause, message) in cases {
        for (path, result_type) in [(SYNC, "COUNT"), (SYNC, "CROSS_COUNT"), (QUERY, "PATIENTS")] {
            let query = json!({"expectedResultType": result_type, "phenotypicClause": clause});
            for (status, body) in replies(&f.app, path, &query).await {
                assert_eq!(
                    (status, body.as_str()),
                    (StatusCode::BAD_REQUEST, message),
                    "{query}"
                );
            }
        }
    }
}

#[tokio::test]
async fn a_null_authorization_filter_is_ignored_on_sync_and_refused_on_post_query() {
    let f = fixture();
    let count = json!({"expectedResultType": "COUNT", "phenotypicClause": required_sex(), "authorizationFilters": [null]});
    for (status, body) in replies(&f.app, SYNC, &count).await {
        assert_eq!((status, body.as_str()), (StatusCode::OK, "4978"));
    }
    let patients = json!({"expectedResultType": "PATIENTS", "phenotypicClause": required_sex(), "authorizationFilters": [null]});
    for (status, body) in replies(&f.app, QUERY, &patients).await {
        assert_eq!(
            (status, body.as_str()),
            (StatusCode::BAD_REQUEST, NULL_AUTHORIZATION_FILTER_MESSAGE)
        );
    }
}

#[tokio::test]
async fn a_null_filter_value_is_refused_only_where_java_looks_it_up() {
    let f = fixture();
    let unknown = "\\no\\such\\";
    let count = |clause: Value| json!({"expectedResultType": "COUNT", "phenotypicClause": clause});
    for (clause, expected) in [
        (
            json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "values": [null]}),
            "4978",
        ),
        (
            json!({"phenotypicFilterType": "ANY_RECORD_OF", "conceptPath": SEX, "values": [null]}),
            "4978",
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null]}),
            "0",
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null, "x"]}),
            "0",
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null], "min": 1}),
            "0",
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "values": [null]}),
            "0",
        ),
        (
            json!({"operator": "OR", "phenotypicClauses": [required_sex(), {"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null]}]}),
            "4978",
        ),
    ] {
        for (status, body) in replies(&f.app, SYNC, &count(clause.clone())).await {
            assert_eq!(
                (status, body.as_str()),
                (StatusCode::OK, expected),
                "{clause}"
            );
        }
    }
    for clause in [
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": [null]}),
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["male", null]}),
    ] {
        for (status, body) in replies(&f.app, SYNC, &count(clause.clone())).await {
            assert_eq!(
                (status, body.as_str()),
                (StatusCode::BAD_REQUEST, NULL_VALUE_MESSAGE),
                "{clause}"
            );
        }
        let patients = json!({"expectedResultType": "PATIENTS", "phenotypicClause": clause});
        for (status, body) in replies(&f.app, QUERY, &patients).await {
            assert_eq!(status, StatusCode::OK, "{body}");
            let id: Value = serde_json::from_str(&body).unwrap();
            let done = wait(&f.app, id["resourceResultId"].as_str().unwrap()).await;
            assert_eq!(done["resourceStatus"], "ERROR", "{clause}");
        }
    }
    let unknown_patients = json!({"expectedResultType": "PATIENTS", "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null]}});
    for (status, body) in replies(&f.app, QUERY, &unknown_patients).await {
        assert_eq!(status, StatusCode::OK, "{body}");
        let id: Value = serde_json::from_str(&body).unwrap();
        let done = wait(&f.app, id["resourceResultId"].as_str().unwrap()).await;
        assert_eq!(done["resourceStatus"], "SUCCESS");
    }
    for (clause, message) in [
        (
            json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "values": [null]}),
            format!(
                "Required filter with concept path {SEX} cannot have categorical values or min/max set"
            ),
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "values": [null]}),
            format!("{AGE} is not a categorical variable"),
        ),
        (
            json!({"phenotypicFilterType": "FILTER", "conceptPath": unknown, "values": [null], "min": 1}),
            format!(
                "Filter with concept path {unknown} cannot have both categorical values and min/max set"
            ),
        ),
    ] {
        let patients = json!({"expectedResultType": "PATIENTS", "phenotypicClause": clause});
        for (status, body) in replies(&f.app, QUERY, &patients).await {
            assert_eq!(
                (status, body.as_str()),
                (StatusCode::BAD_REQUEST, message.as_str())
            );
        }
    }
}
