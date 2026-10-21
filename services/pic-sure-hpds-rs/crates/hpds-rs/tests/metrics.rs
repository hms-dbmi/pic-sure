//! `GET /metrics`: off by default, the Prometheus text format when on, and no patient,
//! concept, consent or query content in any label.

mod common;

use std::collections::{BTreeMap, HashMap, HashSet};

use axum::http::StatusCode;
use common::{PARTITIONS, SEX, consents, envelope, fixture, fixture_with, get, post, wait};
use hpds_query::ResultType;
use hpds_rs::metrics::{METRICS_CONTENT_TYPE, ROUTES};
use serde_json::json;

/// One sample line: metric name, labels, value.
type Sample = (String, BTreeMap<String, String>, f64);

/// Parses `text` as the Prometheus text exposition format 0.0.4 and checks it: every line is
/// a `# HELP` or `# TYPE` comment or a sample; names are valid; each family is typed once
/// before its samples; label values are quoted with nothing to escape; values are numbers;
/// histogram buckets are cumulative and end in `+Inf` equal to the count. Returns the
/// samples.
fn parse(text: &str) -> Vec<Sample> {
    let name_ok = |name: &str| {
        let mut chars = name.chars();
        chars
            .next()
            .is_some_and(|c| c.is_ascii_alphabetic() || c == '_' || c == ':')
            && chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == ':')
    };
    assert!(text.ends_with('\n'), "the exposition ends with a newline");
    let mut types: HashMap<String, String> = HashMap::new();
    let mut samples = Vec::new();
    for line in text.lines() {
        if let Some(comment) = line.strip_prefix("# ") {
            let mut parts = comment.splitn(3, ' ');
            let keyword = parts.next().unwrap();
            let name = parts.next().expect("a metric name");
            let rest = parts.next().expect("help text or a type");
            assert!(name_ok(name), "{line}");
            match keyword {
                "HELP" => assert!(!rest.contains('\n') && !rest.contains('\\'), "{line}"),
                "TYPE" => {
                    assert!(["counter", "gauge", "histogram"].contains(&rest), "{line}");
                    assert!(
                        types.insert(name.to_owned(), rest.to_owned()).is_none(),
                        "{name} typed twice"
                    );
                }
                _ => panic!("unknown comment {line}"),
            }
            continue;
        }
        let (series, value) = line.rsplit_once(' ').expect("a sample value");
        let value: f64 = value.parse().unwrap_or_else(|_| panic!("{line}"));
        assert!(value.is_finite() && value >= 0.0, "{line}");
        let (name, labels) = match series.split_once('{') {
            None => (series, BTreeMap::new()),
            Some((name, rest)) => {
                let body = rest.strip_suffix('}').expect("closing brace");
                let labels = body
                    .split(',')
                    .map(|pair| {
                        let (key, quoted) = pair.split_once('=').expect("label=value");
                        assert!(name_ok(key), "{line}");
                        let inner = quoted
                            .strip_prefix('"')
                            .and_then(|q| q.strip_suffix('"'))
                            .expect("a quoted label value");
                        assert!(
                            !inner.contains(['"', '\\', '\n']),
                            "label value needing escapes: {line}"
                        );
                        (key.to_owned(), inner.to_owned())
                    })
                    .collect();
                (name, labels)
            }
        };
        assert!(name_ok(name), "{line}");
        let family = ["_bucket", "_sum", "_count"]
            .iter()
            .find_map(|suffix| {
                name.strip_suffix(suffix)
                    .filter(|base| types.get(*base).is_some_and(|t| t == "histogram"))
            })
            .unwrap_or(name);
        assert!(types.contains_key(family), "{name} has no TYPE before it");
        samples.push((name.to_owned(), labels, value));
    }
    check_histograms(&samples);
    samples
}

fn check_histograms(samples: &[Sample]) {
    let mut buckets: BTreeMap<(String, String), Vec<(String, f64)>> = BTreeMap::new();
    let mut counts: HashMap<(String, String), f64> = HashMap::new();
    for (name, labels, value) in samples {
        let route = labels.get("route").cloned().unwrap_or_default();
        if let Some(base) = name.strip_suffix("_bucket") {
            buckets
                .entry((base.to_owned(), route))
                .or_default()
                .push((labels["le"].clone(), *value));
        } else if let Some(base) = name.strip_suffix("_count") {
            counts.insert((base.to_owned(), route), *value);
        }
    }
    for (key, series) in buckets {
        assert_eq!(series.last().unwrap().0, "+Inf", "{key:?}");
        assert!(
            series.windows(2).all(|w| w[0].1 <= w[1].1),
            "{key:?} not cumulative"
        );
        let bounds: Vec<f64> = series[..series.len() - 1]
            .iter()
            .map(|(le, _)| le.parse().unwrap())
            .collect();
        assert!(bounds.windows(2).all(|w| w[0] < w[1]), "{key:?}");
        assert_eq!(series.last().unwrap().1, counts[&key], "{key:?}");
    }
}

fn value(samples: &[Sample], name: &str, labels: &[(&str, &str)]) -> Option<f64> {
    samples
        .iter()
        .find(|(n, l, _)| {
            n == name
                && l.len() == labels.len()
                && labels
                    .iter()
                    .all(|(k, v)| l.get(*k).map(String::as_str) == Some(*v))
        })
        .map(|(_, _, v)| *v)
}

fn metrics_on() -> common::Fixture {
    fixture_with(|c| c.metrics = true)
}

async fn scrape(f: &common::Fixture) -> (String, Vec<Sample>) {
    let reply = get(&f.app, "/metrics").await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.content_type(), Some(METRICS_CONTENT_TYPE));
    let samples = parse(&reply.body);
    (reply.body, samples)
}

#[tokio::test]
async fn metrics_are_off_by_default() {
    let f = fixture();
    assert!(!f.config.metrics);
    let reply = get(&f.app, "/metrics").await;
    assert_eq!(reply.status, StatusCode::NOT_FOUND);
    assert!(reply.body.is_empty());
    assert_eq!(
        get(&f.app, "/no-such-route").await.status,
        StatusCode::NOT_FOUND
    );
    let count = post(
        &f.app,
        "/PIC-SURE/v3/query/sync",
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(count.status, StatusCode::OK);
}

#[tokio::test]
async fn an_idle_service_exposes_valid_text() {
    let f = metrics_on();
    let (_, samples) = scrape(&f).await;
    assert_eq!(
        value(&samples, "hpds_block_cache_hits_total", &[]),
        Some(0.0)
    );
    assert_eq!(
        value(&samples, "hpds_cohort_guard_refusals_total", &[]),
        Some(0.0)
    );
    assert_eq!(value(&samples, "hpds_store_superseded", &[]), Some(0.0));
    assert_eq!(
        value(&samples, "hpds_block_cache_capacity_bytes", &[]),
        Some(f.config.partitions.store.cache_bytes as f64)
    );
    assert_eq!(
        value(&samples, "hpds_result_memory_budget_bytes", &[]),
        Some(f.config.result_memory_bytes as f64)
    );
    assert!(value(&samples, "hpds_result_cache_hits_total", &[]).is_none());
}

/// Every query route exercised with distinctive request content: a consent name, a concept
/// path, a search term, a result id and a body that fails to parse.
async fn exercise(f: &common::Fixture) {
    let female =
        json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["female"]});
    for _ in 0..2 {
        let count = post(
            &f.app,
            "/PIC-SURE/v3/query/sync",
            envelope(json!({"expectedResultType": "COUNT", "phenotypicClause": female, "userConsents": consents(&[PARTITIONS[0]])})),
        )
        .await;
        assert_eq!(count.status, StatusCode::OK);
    }
    let cross = post(
        &f.app,
        "/PIC-SURE/query/sync",
        envelope(json!({"expectedResultType": "CROSS_COUNT", "select": [SEX]})),
    )
    .await;
    assert_eq!(cross.status, StatusCode::OK);
    let submitted = post(
        &f.app,
        "/PIC-SURE/v3/query",
        envelope(json!({"expectedResultType": "PATIENTS", "phenotypicClause": female, "userConsents": consents(&[PARTITIONS[1]])})),
    )
    .await;
    assert_eq!(submitted.status, StatusCode::OK, "{}", submitted.body);
    let id = submitted.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
    let result = post(&f.app, &format!("/PIC-SURE/v3/query/{id}/result"), "{}").await;
    assert_eq!(result.status, StatusCode::OK);
    let search = post(
        &f.app,
        "/PIC-SURE/v3/search",
        r#"{"query":"SYNTHETIC_AGE"}"#,
    )
    .await;
    assert_eq!(search.status, StatusCode::OK);
    let bad = post(&f.app, "/PIC-SURE/v3/query/sync", "not json").await;
    assert_eq!(bad.status, StatusCode::BAD_REQUEST);
    let unknown = post(
        &f.app,
        "/PIC-SURE/v3/query/6f1c5bd2-0a4e-4f4b-9d6a-2b9c7e1d3a55/status",
        "{}",
    )
    .await;
    assert_eq!(unknown.status, StatusCode::NOT_FOUND);
    assert_eq!(
        get(&f.app, "/PIC-SURE/v3/nowhere").await.status,
        StatusCode::NOT_FOUND
    );
}

#[tokio::test]
async fn requests_and_store_counters_are_reported() {
    let f = metrics_on();
    exercise(&f).await;
    let (_, samples) = scrape(&f).await;
    let requests = |route: &str, result_type: &str| {
        value(
            &samples,
            "hpds_http_requests_total",
            &[("route", route), ("result_type", result_type)],
        )
    };
    assert_eq!(requests("/PIC-SURE/v3/query/sync", "COUNT"), Some(2.0));
    assert_eq!(requests("/PIC-SURE/query/sync", "CROSS_COUNT"), Some(1.0));
    assert_eq!(requests("/PIC-SURE/v3/query", "PATIENTS"), Some(1.0));
    assert_eq!(requests("/PIC-SURE/v3/query/sync", "none"), Some(1.0));
    assert_eq!(requests("/PIC-SURE/v3/search", "none"), Some(1.0));
    assert_eq!(
        requests("/PIC-SURE/v3/query/{id}/result", "none"),
        Some(1.0)
    );
    assert_eq!(requests("unmatched", "none"), Some(1.0));
    assert!(requests("/PIC-SURE/v3/query/{id}/status", "none").unwrap() >= 2.0);
    assert_eq!(
        value(
            &samples,
            "hpds_http_request_duration_seconds_count",
            &[("route", "/PIC-SURE/v3/query/sync")]
        ),
        Some(3.0)
    );
    assert_eq!(
        value(
            &samples,
            "hpds_http_errors_total",
            &[("kind", "bad_request")]
        ),
        Some(1.0)
    );
    assert_eq!(
        value(&samples, "hpds_http_errors_total", &[("kind", "not_found")]),
        Some(2.0)
    );
    assert_eq!(
        value(
            &samples,
            "hpds_exports_finished_total",
            &[("outcome", "memory")]
        ),
        Some(1.0)
    );
    let loads = value(&samples, "hpds_block_loads_total", &[]).unwrap();
    let misses = value(&samples, "hpds_block_cache_misses_total", &[]).unwrap();
    assert!(loads >= 1.0 && misses >= loads, "{loads} {misses}");
    assert!(value(&samples, "hpds_block_cache_hits_total", &[]).unwrap() >= 1.0);
    assert!(value(&samples, "hpds_block_cache_entries", &[]).unwrap() >= 1.0);
    assert!(value(&samples, "hpds_block_cache_bytes", &[]).unwrap() > 0.0);
    assert!(value(&samples, "hpds_block_load_seconds_total", &[]).unwrap() > 0.0);
    assert_eq!(
        value(&samples, "hpds_block_cache_evictions_total", &[]),
        Some(0.0)
    );
    assert_eq!(value(&samples, "hpds_async_results", &[]), Some(1.0));
}

#[tokio::test]
async fn labels_hold_only_fixed_values() {
    let f = metrics_on();
    exercise(&f).await;
    let (text, samples) = scrape(&f).await;
    let routes: HashSet<&str> = ROUTES.iter().copied().collect();
    let result_types: HashSet<&str> = ResultType::ALL
        .iter()
        .map(|t| t.java_name())
        .chain(["none"])
        .collect();
    let kinds: HashSet<&str> = [
        "bad_request",
        "not_found",
        "request_timeout",
        "payload_too_large",
        "internal",
        "unavailable",
        "other_client",
        "other_server",
    ]
    .into();
    let outcomes: HashSet<&str> = ["memory", "file", "error"].into();
    for (name, labels, _) in &samples {
        for (key, label) in labels {
            let allowed = match key.as_str() {
                "route" => routes.contains(label.as_str()),
                "result_type" => result_types.contains(label.as_str()),
                "kind" => kinds.contains(label.as_str()),
                "outcome" => outcomes.contains(label.as_str()),
                "end" => {
                    ["stalled", "expired", "aborted", "store_changed"].contains(&label.as_str())
                }
                "le" => label == "+Inf" || label.parse::<f64>().is_ok(),
                "contract" => hpds_query::CONSENT_CONTRACT_NAMES.contains(&label.as_str()),
                _ => false,
            };
            assert!(allowed, "{name} {key}={label}");
        }
    }
    for leak in [
        "1000Genomes",
        "SYNTHETIC_AGE",
        "SEX",
        "female",
        PARTITIONS[0],
        PARTITIONS[1],
        "6f1c5bd2",
        "not json",
        "197506",
    ] {
        assert!(!text.contains(leak), "{leak} in the exposition");
    }
}

#[tokio::test]
async fn result_cache_counters_appear_when_the_cache_is_on() {
    let f = fixture_with(|c| {
        c.metrics = true;
        c.result_cache_bytes = 1 << 20;
    });
    for _ in 0..3 {
        post(
            &f.app,
            "/PIC-SURE/v3/query/sync",
            envelope(json!({"expectedResultType": "CROSS_COUNT", "select": [SEX]})),
        )
        .await;
    }
    let (_, samples) = scrape(&f).await;
    assert_eq!(
        value(&samples, "hpds_result_cache_hits_total", &[]),
        Some(2.0)
    );
    assert_eq!(
        value(&samples, "hpds_result_cache_misses_total", &[]),
        Some(1.0)
    );
    assert_eq!(value(&samples, "hpds_result_cache_entries", &[]), Some(1.0));
    assert_eq!(
        value(&samples, "hpds_result_cache_capacity_bytes", &[]),
        Some(f64::from(1u32 << 20))
    );
    assert_eq!(
        value(&samples, "hpds_result_cache_evictions_total", &[]),
        Some(0.0)
    );
}

#[tokio::test]
async fn a_wrong_method_request_counts_under_its_route_template() {
    let f = metrics_on();
    let reply = get(&f.app, "/PIC-SURE/v3/query/sync").await;
    assert_eq!(reply.status, StatusCode::METHOD_NOT_ALLOWED);
    let (text, samples) = scrape(&f).await;
    assert_eq!(
        value(
            &samples,
            "hpds_http_requests_total",
            &[
                ("route", "/PIC-SURE/v3/query/sync"),
                ("result_type", "none")
            ]
        ),
        Some(1.0),
        "{text}"
    );
    assert_eq!(
        value(
            &samples,
            "hpds_http_requests_total",
            &[("route", "unmatched"), ("result_type", "none")]
        ),
        None
    );
    assert_eq!(
        value(
            &samples,
            "hpds_http_errors_total",
            &[("kind", "other_client")]
        ),
        Some(1.0)
    );
}

#[tokio::test]
async fn the_metrics_route_counts_itself() {
    let f = metrics_on();
    scrape(&f).await;
    let (_, samples) = scrape(&f).await;
    assert_eq!(
        value(
            &samples,
            "hpds_http_requests_total",
            &[("route", "/metrics"), ("result_type", "none")]
        ),
        Some(1.0)
    );
}

#[tokio::test]
async fn a_store_moved_in_is_reported_superseded_while_health_stays_up() {
    let f = metrics_on();
    let other = tempfile::tempdir().unwrap();
    let other_store = common::build_store(other.path());
    for name in ["pheno.blocks", "pheno.index"] {
        let staged = f.store().join(format!("{name}.next"));
        std::fs::copy(other_store.join(name), &staged).unwrap();
        std::fs::rename(&staged, f.store().join(name)).unwrap();
    }
    std::thread::sleep(std::time::Duration::from_millis(1100));

    let health = get(&f.app, "/actuator/health").await;

    assert_eq!(health.status, StatusCode::OK);
    assert_eq!(health.json()["status"], "UP");
    assert_eq!(health.json()["storeSuperseded"], true);
    let (_, samples) = scrape(&f).await;
    assert_eq!(value(&samples, "hpds_store_superseded", &[]), Some(1.0));
    let count = post(
        &f.app,
        "/PIC-SURE/v3/query/sync",
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(count.status, StatusCode::OK, "the old store still answers");
}
