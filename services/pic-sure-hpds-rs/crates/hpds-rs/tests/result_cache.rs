//! The sync result cache over HTTP on the four-partition fixture: off by default, keyed on
//! the selected partitions so consent sets never share an entry, looked up only after
//! admission, answers identical to the uncached path, and bounded in bytes.

mod common;

use axum::Router;
use axum::http::StatusCode;
use common::{
    AGE, Fixture, POPULATION, Reply, SEX, consents, envelope, fixture, fixture_with, post,
};
use hpds_query::{CombinedMode, ResultCacheStats};
use hpds_rs::sync::QUERY_METADATA_HEADER;
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const CACHE_BYTES: u64 = 1 << 20;

fn cached() -> Fixture {
    fixture_with(|c| c.result_cache_bytes = CACHE_BYTES)
}

fn stats(f: &Fixture) -> ResultCacheStats {
    f.engine.result_cache_stats().expect("the cache is on")
}

fn age_count(min: f64, max: f64, user_consents: Option<&[&str]>) -> Value {
    let mut query = json!({
        "expectedResultType": "COUNT",
        "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": AGE, "min": min, "max": max},
    });
    if let Some(values) = user_consents {
        query["userConsents"] = consents(values);
    }
    query
}

async fn sync(app: &Router, query: &Value) -> Reply {
    post(app, SYNC, envelope(query.clone())).await
}

/// Asserts that two replies are the same response: status, body, content type and the set
/// of header names, with a `queryMetadata` header on both or on neither.
fn assert_same_response(name: &str, uncached: &Reply, cached: &Reply) {
    assert_eq!(uncached.status, cached.status, "{name}: status");
    assert_eq!(uncached.body, cached.body, "{name}: body");
    assert_eq!(
        uncached.content_type(),
        cached.content_type(),
        "{name}: content type"
    );
    let names = |reply: &Reply| {
        let mut names: Vec<String> = reply.headers.keys().map(|k| k.to_string()).collect();
        names.sort();
        names
    };
    assert_eq!(names(uncached), names(cached), "{name}: header names");
    assert_eq!(
        uncached.headers.contains_key(QUERY_METADATA_HEADER),
        cached.headers.contains_key(QUERY_METADATA_HEADER),
        "{name}: queryMetadata"
    );
}

#[tokio::test]
async fn the_cache_is_off_by_default() {
    let f = fixture();
    assert_eq!(f.config.result_cache_bytes, 0);
    assert_eq!(f.engine.result_cache_stats(), None);
    let query = age_count(35.0, 45.0, None);
    for _ in 0..2 {
        let reply = sync(&f.app, &query).await;
        assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, "562"));
    }
    assert_eq!(f.engine.result_cache_stats(), None);
}

#[tokio::test]
async fn two_consent_sets_never_share_an_entry() {
    let f = cached();
    let selections: [(&[&str], &str); 4] = [
        (&["partition1"], "494"),
        (&["partition4"], "2484"),
        (&["partition2", "partition3"], "2000"),
        (
            &["partition1", "partition2", "partition3", "partition4"],
            "4978",
        ),
    ];
    for round in 0..2 {
        for (values, expected) in selections {
            let query = json!({"expectedResultType": "COUNT", "userConsents": consents(values)});
            let reply = sync(&f.app, &query).await;
            assert_eq!(
                (reply.status, reply.body.as_str()),
                (StatusCode::OK, expected),
                "round {round}, {values:?}"
            );
        }
    }
    let after = stats(&f);
    assert_eq!((after.misses, after.hits, after.entries), (4, 4, 4));

    let reordered = json!({"expectedResultType": "COUNT",
        "userConsents": consents(&["partition3", "unknown.c9", "partition2"])});
    let reply = sync(&f.app, &reordered).await;
    assert_eq!(reply.body, "2000");
    assert_eq!(
        stats(&f).hits,
        5,
        "consents selecting the same partitions share the entry"
    );

    let unknown_only =
        json!({"expectedResultType": "COUNT", "userConsents": consents(&["unknown.c9"])});
    let reply = sync(&f.app, &unknown_only).await;
    assert_eq!(reply.body, "0");
    assert_eq!(stats(&f).entries, 5);
}

#[tokio::test]
async fn the_full_selection_has_one_entry_per_route() {
    let combined = cached();
    let partitions_only = fixture_with(|c| {
        c.result_cache_bytes = CACHE_BYTES;
        c.partitions.combined = CombinedMode::Off;
    });
    let query = age_count(35.0, 45.0, None);
    for f in [&combined, &partitions_only] {
        for _ in 0..2 {
            assert_eq!(sync(&f.app, &query).await.body, "562");
        }
        let after = stats(f);
        assert_eq!((after.misses, after.hits, after.entries), (1, 1, 1));
    }
}

#[tokio::test]
async fn a_refused_query_is_refused_with_the_cache_warm() {
    let f = fixture_with(|c| {
        c.result_cache_bytes = CACHE_BYTES;
        c.partitions.require_consents = true;
        c.query_limits.max_clause_nodes = 2;
    });
    let warm = age_count(35.0, 45.0, Some(&["partition2", "partition3"]));
    for _ in 0..2 {
        assert_eq!(sync(&f.app, &warm).await.body, "151");
    }
    assert_eq!(stats(&f).hits, 1);

    let mut over_limit = warm.clone();
    over_limit["authorizationFilters"] = json!([
        {"conceptPath": SEX, "values": ["female"]},
        {"conceptPath": SEX, "values": ["male"]},
    ]);
    let reply = sync(&f.app, &over_limit).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{}", reply.body);
    assert!(
        reply.body.contains("HPDS_MAX_CLAUSE_NODES"),
        "{}",
        reply.body
    );

    let mut no_consents = warm.clone();
    no_consents.as_object_mut().unwrap().remove("userConsents");
    let reply = sync(&f.app, &no_consents).await;
    assert_eq!(reply.status, StatusCode::BAD_REQUEST);
    assert_eq!(reply.body, "User consents are required for this query");

    let after = stats(&f);
    assert_eq!((after.hits, after.misses), (1, 1), "refusals never look up");
}

/// [`hpds_query::Engine::cached_sync`] itself refuses what admission refuses, with an entry
/// warm under the same key: the select and concept bounds (`select` is not in a COUNT's
/// key), the clause bound and missing consents.
#[test]
fn the_engine_lookup_refuses_what_admission_refuses() {
    let f = fixture_with(|c| {
        c.result_cache_bytes = CACHE_BYTES;
        c.partitions.require_consents = true;
        c.query_limits.max_select_paths = 3;
        c.query_limits.max_query_concepts = 3;
        c.query_limits.max_clause_nodes = 2;
    });
    let parse = |value: Value| -> hpds_query::Query { serde_json::from_value(value).unwrap() };
    let mut warm = age_count(35.0, 45.0, Some(&["partition2", "partition3"]));
    warm["select"] = json!([SEX]);
    let warm_query = parse(warm.clone());
    let computed = f.engine.answer_sync(&warm_query).unwrap();
    let hit = f.engine.cached_sync(&warm_query).unwrap();
    assert_eq!(hit.as_ref(), Some(&computed), "the entry is warm");

    let with = |field: &str, value: Value| {
        let mut query = warm.clone();
        query[field] = value;
        parse(query)
    };
    let mut no_consents = warm.clone();
    no_consents.as_object_mut().unwrap().remove("userConsents");
    let refused = [
        (
            "over the select bound",
            with("select", json!([SEX, POPULATION, r"\a\", r"\b\"])),
            "HPDS_MAX_SELECT_PATHS",
        ),
        (
            "over the concept bound",
            with("select", json!([SEX, POPULATION, r"\a\"])),
            "HPDS_MAX_QUERY_CONCEPTS",
        ),
        (
            "over the clause bound",
            with(
                "authorizationFilters",
                json!([
                    {"conceptPath": SEX, "values": ["female"]},
                    {"conceptPath": SEX, "values": ["male"]},
                ]),
            ),
            "HPDS_MAX_CLAUSE_NODES",
        ),
        (
            "without consents",
            parse(no_consents),
            "User consents are required",
        ),
    ];
    for (name, query, message) in refused {
        let error = f.engine.cached_sync(&query).unwrap_err();
        assert!(error.to_string().contains(message), "{name}: {error}");
    }
    let after = stats(&f);
    assert_eq!((after.hits, after.misses), (1, 0), "refusals never look up");
}

#[tokio::test]
async fn errors_are_not_cached() {
    let f = cached();
    let empty_filter = json!({"expectedResultType": "COUNT",
        "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": AGE}});
    for _ in 0..2 {
        let reply = sync(&f.app, &empty_filter).await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{}", reply.body);
    }
    let after = stats(&f);
    assert_eq!((after.hits, after.misses, after.entries), (0, 2, 0));
}

#[tokio::test]
async fn a_cross_count_holding_minus_one_is_not_stored() {
    let f = cached();
    let blocks = f.store().join("pheno.blocks");
    let len = usize::try_from(std::fs::metadata(&blocks).unwrap().len()).unwrap();
    std::fs::write(&blocks, vec![0xff; len]).unwrap();
    for result_type in ["CROSS_COUNT", "OBSERVATION_CROSS_COUNT"] {
        let query = json!({"expectedResultType": result_type, "select": [SEX, AGE],
            "userConsents": consents(&["partition1"])});
        for _ in 0..2 {
            let reply = sync(&f.app, &query).await;
            assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
            let counts = reply.json();
            assert_eq!(
                (&counts[SEX], &counts[AGE]),
                (&json!(-1), &json!(-1)),
                "{result_type}: {counts}"
            );
        }
    }
    let after = stats(&f);
    assert_eq!((after.hits, after.misses, after.entries), (0, 4, 0));

    let readable =
        json!({"expectedResultType": "COUNT", "userConsents": consents(&["partition1"])});
    for _ in 0..2 {
        assert_eq!(sync(&f.app, &readable).await.body, "494");
    }
    let after = stats(&f);
    assert_eq!(
        (after.hits, after.entries),
        (1, 1),
        "an answer that reads no block is stored"
    );
}

#[tokio::test]
async fn an_answer_over_a_sixteenth_of_the_bound_is_not_stored() {
    let capacity = 16 * 256;
    let f = fixture_with(|c| c.result_cache_bytes = capacity);
    let wide = json!({"expectedResultType": "CROSS_COUNT", "select": [SEX, AGE, POPULATION]});
    for _ in 0..2 {
        let reply = sync(&f.app, &wide).await;
        assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
        let paths: usize = [SEX, AGE, POPULATION].iter().map(|p| p.len()).sum();
        assert!(
            128 + paths + reply.body.len() > 256,
            "overhead, the select paths in the key and the body pass a sixteenth"
        );
    }
    let after = stats(&f);
    assert_eq!((after.hits, after.misses, after.entries), (0, 2, 0));

    let small = json!({"expectedResultType": "COUNT"});
    for _ in 0..2 {
        assert_eq!(sync(&f.app, &small).await.body, "4978");
    }
    let after = stats(&f);
    assert_eq!(
        (after.hits, after.entries),
        (1, 1),
        "a small answer is stored"
    );
    assert!(after.bytes <= 256, "{after:?}");
}

#[tokio::test]
async fn the_smallest_bound_stores_the_lightest_answer() {
    let min = hpds_query::MIN_RESULT_CACHE_BYTES;
    let lightest =
        json!({"expectedResultType": "COUNT", "userConsents": consents(&["unknown.c9"])});
    for (capacity, stored) in [(min - 1, 0), (min, 1)] {
        let f = fixture_with(|c| c.result_cache_bytes = capacity);
        for _ in 0..2 {
            assert_eq!(sync(&f.app, &lightest).await.body, "0");
        }
        let after = stats(&f);
        assert_eq!(
            (after.entries, after.hits),
            (stored, stored),
            "bound {capacity}: {after:?}"
        );
    }
}

#[tokio::test]
async fn the_byte_bound_is_honored() {
    let capacity = 16 << 10;
    let f = fixture_with(|c| c.result_cache_bytes = capacity);
    for low in 0..400 {
        let query = age_count(f64::from(low) / 10.0, 100.0, None);
        assert_eq!(sync(&f.app, &query).await.status, StatusCode::OK);
    }
    let after = stats(&f);
    assert_eq!(after.capacity, capacity);
    assert!(after.bytes <= capacity, "{after:?}");
    assert!(after.entries > 0 && after.entries < 400, "{after:?}");
    assert_eq!(after.misses, 400);
}

/// Every sync case of a golden corpus, plus an OBSERVATION_CROSS_COUNT twin of each
/// CROSS_COUNT case, as (name, query) pairs.
fn golden_sync_queries(file: &str) -> Vec<(String, Value)> {
    let path = common::workspace().join("bench/golden").join(file);
    let corpus: Value = serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
    let mut queries = Vec::new();
    for case in corpus["cases"].as_array().unwrap() {
        if case["kind"] != "sync" {
            continue;
        }
        let name = case["name"].as_str().unwrap().to_owned();
        let query = match &case["request"]["query"] {
            Value::String(text) => serde_json::from_str(text).unwrap(),
            other => other.clone(),
        };
        if query["expectedResultType"] == "CROSS_COUNT" {
            let mut twin = query.clone();
            twin["expectedResultType"] = json!("OBSERVATION_CROSS_COUNT");
            queries.push((format!("{name} as OBSERVATION_CROSS_COUNT"), twin));
        }
        queries.push((name, query));
    }
    queries
}

/// Replays `queries` against `uncached`, then twice against `cached`, and checks every
/// cached reply against the uncached one. Returns the number of 200 replies of a cached
/// type in one pass.
async fn replay_identical(
    uncached: &Fixture,
    cached: &Fixture,
    queries: &[(String, Value)],
) -> u64 {
    let cacheable = [
        "COUNT",
        "CROSS_COUNT",
        "CATEGORICAL_CROSS_COUNT",
        "CONTINUOUS_CROSS_COUNT",
        "OBSERVATION_CROSS_COUNT",
    ];
    let mut answered = 0;
    for pass in 0..2 {
        for (name, query) in queries {
            let expected = sync(&uncached.app, query).await;
            let got = sync(&cached.app, query).await;
            assert_same_response(&format!("{name}, pass {pass}"), &expected, &got);
            let result_type = query["expectedResultType"].as_str().unwrap();
            if pass == 0 && got.status == StatusCode::OK && cacheable.contains(&result_type) {
                answered += 1;
            }
        }
    }
    answered
}

#[tokio::test]
async fn cached_answers_match_uncached_on_the_golden_fixture() {
    let queries = golden_sync_queries("harm-fixture.json");
    for types in [
        "COUNT",
        "CROSS_COUNT",
        "CATEGORICAL_CROSS_COUNT",
        "CONTINUOUS_CROSS_COUNT",
        "OBSERVATION_CROSS_COUNT",
    ] {
        assert!(
            queries
                .iter()
                .any(|(_, q)| q["expectedResultType"] == types),
            "no {types} case"
        );
    }
    for combined in [CombinedMode::Auto, CombinedMode::Off] {
        let uncached = fixture_with(|c| c.partitions.combined = combined);
        let cached = fixture_with(|c| {
            c.partitions.combined = combined;
            c.result_cache_bytes = CACHE_BYTES;
        });
        let answered = replay_identical(&uncached, &cached, &queries).await;
        let after = stats(&cached);
        assert!(
            after.hits >= answered,
            "{combined:?}: {after:?}, {answered} answered"
        );
        assert!(after.entries > 0 && after.entries <= answered, "{after:?}");
    }
}

#[tokio::test]
async fn cached_answers_match_uncached_on_the_strict_golden_fixture() {
    let queries = golden_sync_queries("harm-fixture-strict.json");
    let uncached = fixture_with(|c| c.partitions.require_consents = true);
    let cached = fixture_with(|c| {
        c.partitions.require_consents = true;
        c.result_cache_bytes = CACHE_BYTES;
    });
    let answered = replay_identical(&uncached, &cached, &queries).await;
    assert!(answered > 0);
    assert!(stats(&cached).hits >= answered);
}

#[tokio::test]
async fn only_fields_that_change_the_answer_or_could_split_entries() {
    let f = cached();
    let base = age_count(35.0, 45.0, None);
    let mut with_ids = base.clone();
    with_ids["picsureId"] = json!("8bd5ad3c-35d6-4a3b-9b9a-35d5f3d8f6a1");
    with_ids["id"] = json!("0f1e2d3c-4b5a-4968-8776-655443322110");
    with_ids["select"] = json!([SEX]);
    for query in [&base, &with_ids] {
        assert_eq!(sync(&f.app, query).await.body, "562");
    }
    assert_eq!(stats(&f).entries, 1, "ids and select do not change a COUNT");

    let mut narrower = base.clone();
    narrower["phenotypicClause"]["max"] = json!(44.0);
    sync(&f.app, &narrower).await;
    assert_eq!(stats(&f).entries, 2);

    let cross = |select: Value| json!({"expectedResultType": "CROSS_COUNT", "select": select});
    let one = sync(&f.app, &cross(json!([SEX]))).await;
    let two = sync(&f.app, &cross(json!([SEX, AGE]))).await;
    assert_ne!(one.body, two.body);
    assert_eq!(stats(&f).entries, 4, "select splits a CROSS_COUNT");

    let mut authorized = base.clone();
    authorized["authorizationFilters"] = json!([
        {"conceptPath": SEX, "values": ["male", "female"]},
        {"conceptPath": AGE, "values": null},
    ]);
    assert_eq!(sync(&f.app, &authorized).await.body, "562");
    assert_eq!(
        stats(&f).entries,
        5,
        "authorization filters split entries, though never applied"
    );
    let hits = stats(&f).hits;
    assert_eq!(sync(&f.app, &authorized).await.body, "562");
    assert_eq!(
        (stats(&f).entries, stats(&f).hits),
        (5, hits + 1),
        "the same filters in the same order share the entry"
    );
    let variants = [
        json!([
            {"conceptPath": AGE, "values": null},
            {"conceptPath": SEX, "values": ["male", "female"]},
        ]),
        json!([
            {"conceptPath": SEX, "values": ["female", "male"]},
            {"conceptPath": AGE, "values": null},
        ]),
        json!([
            {"conceptPath": SEX, "values": ["male", "female", "male"]},
            {"conceptPath": AGE, "values": null},
        ]),
        json!([
            {"conceptPath": SEX, "values": ["male", "female"]},
            {"conceptPath": AGE, "values": null},
            {"conceptPath": AGE, "values": null},
        ]),
        json!([{"conceptPath": SEX, "values": ["male"]}, {"conceptPath": AGE, "values": null}]),
    ];
    for (n, filters) in variants.into_iter().enumerate() {
        let mut variant = base.clone();
        variant["authorizationFilters"] = filters;
        let hits = stats(&f).hits;
        assert_eq!(sync(&f.app, &variant).await.body, "562");
        assert_eq!(
            (stats(&f).entries, stats(&f).hits),
            (6 + n as u64, hits),
            "variant {n}: filter order, value order and repetition each split entries"
        );
    }
}

#[tokio::test]
async fn categorical_cross_counts_are_cached_per_selection() {
    let f = cached();
    let uncached = fixture();
    let categorical = |values: &[&str]| {
        json!({"expectedResultType": "CATEGORICAL_CROSS_COUNT",
            "userConsents": consents(values),
            "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": SEX,
                "values": ["male", "female"]}})
    };
    let selections: [&[&str]; 2] = [&["partition1"], &["partition2", "partition3"]];
    for round in 0..2 {
        for values in selections {
            let query = categorical(values);
            let expected = sync(&uncached.app, &query).await;
            let got = sync(&f.app, &query).await;
            assert_eq!(got.status, StatusCode::OK, "{}", got.body);
            assert_same_response(&format!("round {round}, {values:?}"), &expected, &got);
        }
    }
    let after = stats(&f);
    assert_eq!((after.misses, after.hits, after.entries), (2, 2, 2));
    let one = sync(&f.app, &categorical(selections[0])).await;
    let two = sync(&f.app, &categorical(selections[1])).await;
    assert_ne!(one.body, two.body, "the selections answer differently");
}
