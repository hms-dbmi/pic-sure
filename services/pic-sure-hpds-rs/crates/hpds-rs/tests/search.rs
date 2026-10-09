mod common;

use axum::http::StatusCode;
use common::{SEX, fixture, get, post};
use serde_json::json;

#[tokio::test]
async fn search_matches_javas_bytes_for_a_numeric_concept() {
    let f = fixture();
    let reply = post(
        &f.app,
        "/PIC-SURE/v3/search",
        r#"{"query":"synthetic_age"}"#,
    )
    .await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.content_type(), Some("application/json"));
    assert_eq!(
        reply.body,
        r#"{"results":{"phenotypes":{"\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\":{"name":"\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\","categorical":false,"min":31.0,"max":62.0,"observationCount":1126,"patientCount":1126}},"info":{}},"searchQuery":"synthetic_age"}"#
    );
}

#[tokio::test]
async fn search_matches_category_values_on_the_unversioned_path() {
    let f = fixture();
    let reply = post(
        &f.app,
        "/PIC-SURE/search",
        r#"{"query":"MALE","resourceUUID":null}"#,
    )
    .await;
    assert_eq!(
        reply.body,
        r#"{"results":{"phenotypes":{"\\open_access-1000Genomes\\data\\SEX\\":{"name":"\\open_access-1000Genomes\\data\\SEX\\","categorical":true,"categoryValues":["female","male"],"observationCount":4978,"patientCount":4978}},"info":{}},"searchQuery":"MALE"}"#
    );
}

#[tokio::test]
async fn search_terms_are_java_to_string() {
    let f = fixture();
    let reply = post(&f.app, "/PIC-SURE/v3/search", r#"{"query":{"a":1}}"#).await;
    assert_eq!(
        reply.json(),
        json!({"results": {"phenotypes": {}, "info": {}}, "searchQuery": "{a=1}"})
    );
}

#[tokio::test]
async fn search_without_a_term_lists_every_concept_as_entries() {
    let f = fixture();
    let reply = post(&f.app, "/PIC-SURE/v3/search", "{}").await;
    let json = reply.json();
    assert_eq!(json["searchQuery"], "");
    let entries = json["results"]["phenotypes"].as_array().unwrap();
    assert_eq!(entries.len(), f.engine.store().index().concepts().len());
    assert!(entries.iter().any(|e| e.get(SEX).is_some()));
    assert!(entries.iter().all(|e| e.as_object().unwrap().len() == 1));
}

#[tokio::test]
async fn search_values_is_an_empty_page() {
    let f = fixture();
    for path in [
        "/PIC-SURE/v3/search/values/",
        "/PIC-SURE/v3/search/values",
        "/PIC-SURE/search/values/",
    ] {
        let reply = get(
            &f.app,
            &format!("{path}?genomicConceptPath=Gene_with_variant&query=a&page=3&size=10"),
        )
        .await;
        assert_eq!(reply.status, StatusCode::OK, "{path}");
        assert_eq!(reply.body, r#"{"results":[],"page":3,"total":0}"#);
    }
    for query in [
        "genomicConceptPath=g&query=a&page=0&size=10",
        "genomicConceptPath=g&query=a&page=1&size=0",
        "genomicConceptPath=g&query=a&page=x&size=1",
        "query=a&page=1&size=1",
    ] {
        let reply = get(&f.app, &format!("/PIC-SURE/v3/search/values/?{query}")).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{query}");
        assert!(!reply.body.is_empty(), "{query}");
    }
}

#[tokio::test]
async fn malformed_search_bodies_are_400() {
    let f = fixture();
    let reply = post(&f.app, "/PIC-SURE/v3/search", "nope").await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert_eq!(reply.content_type(), Some("text/plain; charset=utf-8"));
    assert!(!reply.body.is_empty());
}
