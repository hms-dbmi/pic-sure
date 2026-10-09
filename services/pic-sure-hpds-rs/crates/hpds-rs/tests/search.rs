//! `/search` against the captured Java responses of the harmonization branch
//! (`bench/golden/harm/search-*.body`), and `/search/values/`.

mod common;

use std::collections::BTreeMap;

use axum::http::StatusCode;
use common::{AGE, SEX, fixture, get, post, strict_fixture, workspace};
use serde::Deserialize;
use serde_json::value::RawValue;

#[derive(Deserialize)]
struct Body<'a> {
    #[serde(borrow)]
    results: Results<'a>,
    #[serde(rename = "searchQuery")]
    search_query: String,
}

#[derive(Deserialize)]
struct Results<'a> {
    #[serde(borrow)]
    phenotypes: &'a RawValue,
    #[serde(borrow)]
    info: &'a RawValue,
}

/// The members of a JSON object in the order sent, duplicates kept.
struct OrderedEntries<'a>(Vec<(String, &'a RawValue)>);

impl<'de: 'a, 'a> Deserialize<'de> for OrderedEntries<'a> {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Members;
        impl<'de> serde::de::Visitor<'de> for Members {
            type Value = OrderedEntries<'de>;
            fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str("a JSON object")
            }
            fn visit_map<A: serde::de::MapAccess<'de>>(
                self,
                mut map: A,
            ) -> Result<Self::Value, A::Error> {
                let mut members = Vec::new();
                while let Some(entry) = map.next_entry::<String, &'de RawValue>()? {
                    members.push(entry);
                }
                Ok(OrderedEntries(members))
            }
        }
        deserializer.deserialize_map(Members)
    }
}

/// One concept's JSON text exactly as sent, except that the items of its `categoryValues`
/// array are put in byte order of their text.
fn with_sorted_categories(concept: &str) -> String {
    const OPEN: &str = r#""categoryValues":["#;
    const CLOSE: &str = r#"],"min":"#;
    let start = concept.find(OPEN).expect("categoryValues") + OPEN.len();
    let end = start
        + concept[start..]
            .find(CLOSE)
            .expect("min after categoryValues");
    let array = format!("[{}]", &concept[start..end]);
    let mut items: Vec<&RawValue> = serde_json::from_str(&array).unwrap();
    items.sort_by(|a, b| a.get().cmp(b.get()));
    let joined: Vec<&str> = items.iter().map(|item| item.get()).collect();
    format!(
        "{}{}{}",
        &concept[..start],
        joined.join(","),
        &concept[end..]
    )
}

/// The body reduced to what Java fixes: shape, search query, `info` text, and each concept's
/// text keyed by path, with category values sorted. Concept order and category value order
/// are left out, since Java's are unspecified.
fn normalized(body: &str) -> (bool, String, String, BTreeMap<String, String>) {
    assert!(body.starts_with(r#"{"results":{"phenotypes":"#), "{body}");
    let parsed: Body<'_> = serde_json::from_str(body).unwrap();
    let phenotypes = parsed.results.phenotypes.get();
    let is_array = phenotypes.starts_with('[');
    let entries: Vec<(String, &RawValue)> = if is_array {
        let list: Vec<BTreeMap<String, &RawValue>> = serde_json::from_str(phenotypes).unwrap();
        list.into_iter()
            .map(|entry| {
                assert_eq!(entry.len(), 1);
                entry.into_iter().next().unwrap()
            })
            .collect()
    } else {
        let map: Vec<(String, &RawValue)> = serde_json::from_str::<OrderedEntries<'_>>(phenotypes)
            .unwrap()
            .0;
        map
    };
    let count = entries.len();
    let concepts: BTreeMap<String, String> = entries
        .into_iter()
        .map(|(path, raw)| (path, with_sorted_categories(raw.get())))
        .collect();
    assert_eq!(concepts.len(), count, "duplicate concept in {body}");
    (
        is_array,
        parsed.search_query,
        parsed.results.info.get().to_owned(),
        concepts,
    )
}

fn golden(name: &str) -> (String, String) {
    let dir = workspace().join("bench/golden/harm");
    let request = std::fs::read_to_string(dir.join(format!("{name}.request.json"))).unwrap();
    let body = std::fs::read_to_string(dir.join(format!("{name}.body"))).unwrap();
    (request, body)
}

#[tokio::test]
async fn search_matches_the_captured_java_bodies() {
    let f = fixture();
    let strict = strict_fixture();
    for (name, app) in [
        ("search-age", &f.app),
        ("search-category-value", &f.app),
        ("search-empty-string", &f.app),
        ("search-missing-term", &f.app),
        ("search-no-match", &f.app),
        ("search-null-term", &f.app),
        ("search-synthetic", &f.app),
        ("strict-search-synthetic", &strict.app),
    ] {
        let (request, java) = golden(name);
        for path in ["/PIC-SURE/v3/search", "/PIC-SURE/search"] {
            let reply = post(app, path, request.clone()).await;
            assert_eq!(reply.status, StatusCode::OK, "{name} {path}");
            assert_eq!(reply.content_type(), Some("application/json"), "{name}");
            assert_eq!(normalized(&reply.body), normalized(&java), "{name} {path}");
        }
    }
}

#[tokio::test]
async fn string_terms_answer_an_object_and_null_terms_an_entry_array() {
    let f = fixture();
    let dictionary = f.engine.partitions().dictionary().len();
    let empty = post(&f.app, "/PIC-SURE/v3/search", r#"{"query":""}"#).await;
    let phenotypes = &empty.json()["results"]["phenotypes"];
    assert_eq!(phenotypes.as_object().unwrap().len(), dictionary);
    for body in ["{}", r#"{"query":null}"#] {
        let reply = post(&f.app, "/PIC-SURE/v3/search", body).await;
        let json = reply.json();
        assert_eq!(json["searchQuery"], "");
        let entries = json["results"]["phenotypes"].as_array().unwrap();
        assert_eq!(entries.len(), dictionary);
        assert!(entries.iter().all(|e| e.as_object().unwrap().len() == 1));
    }
}

#[tokio::test]
async fn search_matches_paths_case_insensitively_and_category_values_exactly() {
    let f = fixture();
    let reply = post(
        &f.app,
        "/PIC-SURE/v3/search",
        r#"{"query":"synthetic_age"}"#,
    )
    .await;
    let json = reply.json();
    let found = json["results"]["phenotypes"].as_object().unwrap();
    assert_eq!(found.keys().collect::<Vec<_>>(), vec![AGE]);
    assert_eq!(json["searchQuery"], "synthetic_age");

    let reply = post(
        &f.app,
        "/PIC-SURE/search",
        r#"{"query":"MALE","resourceUUID":null}"#,
    )
    .await;
    let json = reply.json();
    let found = json["results"]["phenotypes"].as_object().unwrap();
    assert!(found.contains_key(SEX));
    assert_eq!(
        found[SEX]["categoryValues"],
        serde_json::json!(["female", "male"])
    );
    assert_eq!(found[SEX]["patientCount"], 4978);

    let partial = post(&f.app, "/PIC-SURE/v3/search", r#"{"query":"fema"}"#).await;
    assert!(
        !partial.json()["results"]["phenotypes"]
            .as_object()
            .unwrap()
            .contains_key(SEX)
    );
}

#[tokio::test]
async fn search_terms_are_java_to_string() {
    let f = fixture();
    let reply = post(&f.app, "/PIC-SURE/v3/search", r#"{"query":{"a":1}}"#).await;
    assert_eq!(
        reply.json(),
        serde_json::json!({"results": {"phenotypes": {}, "info": {}}, "searchQuery": "{a=1}"})
    );
}

#[tokio::test]
async fn search_bodies_with_duplicate_keys_are_refused() {
    let f = fixture();
    for body in [
        r#"{"query":"AGE","query":"SEX"}"#,
        r#"{"query":{"a":1,"a":2}}"#,
    ] {
        let reply = post(&f.app, "/PIC-SURE/v3/search", body).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{body}");
        assert!(reply.body.contains("duplicate"), "{body}: {}", reply.body);
    }
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
