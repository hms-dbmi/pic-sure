//! Runs the harmonization golden corpora in-process against a four-partition store built
//! from the Java integration fixture (`partition{1..4}-allConcepts.csv`, one partition per
//! file, named `partition1` to `partition4`).
//!
//! - `bench/golden/harm-fixture.json` with consents not required.
//! - `bench/golden/harm-fixture-strict.json` with consents required. Where Java answers a
//!   sync query that has no consent value with an empty 200, this engine fails with
//!   [`QueryError::UserConsentsRequired`] (the HTTP layer's 400), and the test asserts that
//!   instead.
//!
//! The Java numbers come from `CSVLoader`, which reads every line as data. Until the Rust
//! partitioned loader exists, each file is loaded with the new-search loader after a header
//! line is prepended. On this fixture the two loaders agree: no path contains `µ`, no path
//! ends with its row's text value (so `CSVLoader`'s rollup never fires), no text value parses
//! as a number, and every concept's rows are contiguous.
//!
//! `/search` cases run against the merged dictionary with the Java match rule and
//! [`SummaryColumnMeta::write_json`].
//!
//! Every corpus runs with narrow selections reading partition views below the service's
//! share of a concept's rows, always, and never, and every case must pass and give byte for
//! byte the same outcome all three ways.

mod common;

use common::{SHARES, harm_fixture as fixture, lenient, strict, with_share, workspace};
use hpds_bench::client::{AsyncOutcome, Outcome, SyncOutcome};
use hpds_bench::corpus::Kind;
use hpds_bench::golden::GoldenCase;
use hpds_bench::golden::{GoldenFile, evaluate, read_golden};
use hpds_query::{Engine, PATIENTS_HEADER, Query, QueryError, ResultType, SummaryColumnMeta};
use serde_json::Value;

fn csv_line<S: AsRef<str>>(fields: impl IntoIterator<Item = S>) -> String {
    let quoted: Vec<String> = fields
        .into_iter()
        .map(|f| {
            let f = f.as_ref();
            if f.contains([',', '"', '\r', '\n']) {
                format!("\"{}\"", f.replace('"', "\"\""))
            } else {
                f.to_owned()
            }
        })
        .collect();
    format!("{}\n", quoted.join(","))
}

fn parse_query(request: &Value) -> Query {
    match &request["query"] {
        Value::String(text) => serde_json::from_str(text).unwrap(),
        other => serde_json::from_value(other.clone()).unwrap(),
    }
}

fn sync_outcome(status: u16, body: String) -> Outcome {
    Outcome::Sync(SyncOutcome {
        status,
        content_type: None,
        body,
        has_query_metadata: status == 200,
    })
}

fn run_sync(engine: &Engine, query: &Query) -> Outcome {
    match engine.run_sync(query) {
        Ok(result) => sync_outcome(200, result.body()),
        Err(e) => sync_outcome(if e.is_client_error() { 400 } else { 500 }, e.to_string()),
    }
}

fn run_async(engine: &Engine, query: &Query) -> Outcome {
    let body = engine
        .validate(query)
        .and_then(|()| match query.expected_result_type {
            ResultType::Patients => {
                let mut out = csv_line([PATIENTS_HEADER]);
                for patient in &engine.patients(query)? {
                    out.push_str(&csv_line([patient.to_string()]));
                }
                Ok(out)
            }
            ResultType::Dataframe => {
                let frame = engine.dataframe(query)?;
                let mut out = csv_line(&frame.header);
                for row in frame.rows {
                    out.push_str(&csv_line(row));
                }
                Ok(out)
            }
            other => panic!("no async golden support for {other}"),
        });
    let ok = body.is_ok();
    Outcome::Async(AsyncOutcome {
        submit_status: 200,
        submit_body: None,
        status: None,
        resource_status: Some(if ok { "SUCCESS" } else { "ERROR" }.into()),
        result_status: ok.then_some(200),
        result_content_type: None,
        result_body: body.ok(),
        timed_out: false,
        error: None,
    })
}

fn json_string(value: &str) -> String {
    Value::String(value.to_owned()).to_string()
}

/// `POST /search` as the Java branch answers it: a concept matches when its lowercased path
/// contains the lowercased term, or when it is categorical and a lowercased category value
/// equals it. A null term returns the whole dictionary as an array of one-entry objects.
fn run_search(engine: &Engine, request: &Value) -> Outcome {
    let dictionary = engine.partitions().dictionary();
    let mut out = String::from(r#"{"results":{"phenotypes":"#);
    let search_query = match &request["query"] {
        Value::Null => {
            out.push('[');
            for (i, concept) in dictionary.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push('{');
                out.push_str(&json_string(&concept.name));
                out.push(':');
                concept.write_json(&mut out);
                out.push('}');
            }
            out.push(']');
            String::new()
        }
        term => {
            let term = match term {
                Value::String(s) => s.clone(),
                other => other.to_string(),
            };
            let needle = term.to_lowercase();
            let matches = |c: &&SummaryColumnMeta| {
                c.name.to_lowercase().contains(&needle)
                    || (c.categorical
                        && c.category_values.iter().any(|v| v.to_lowercase() == needle))
            };
            out.push('{');
            for (i, concept) in dictionary.iter().filter(matches).enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push_str(&json_string(&concept.name));
                out.push(':');
                concept.write_json(&mut out);
            }
            out.push('}');
            term
        }
    };
    out.push_str(r#","info":{}},"searchQuery":"#);
    out.push_str(&json_string(&search_query));
    out.push('}');
    sync_outcome(200, out)
}

/// Runs every case of `golden` and returns the failures. When the engine requires
/// consents, a sync case Java answers with an empty body is one Rust refuses: the engine
/// must fail with [`QueryError::UserConsentsRequired`], and [`Engine::check_consents`] must
/// agree.
fn run_golden(engine: &Engine, golden: &GoldenFile) -> Vec<String> {
    let require_consents = engine.partitions().requires_consents();
    let mut failures = Vec::new();
    for case in &golden.cases {
        let outcome = match case.entry.kind {
            Kind::Search => run_search(engine, &case.entry.request),
            Kind::Async => run_async(engine, &parse_query(&case.entry.request)),
            Kind::UnknownStatus | Kind::UnknownResult => {
                panic!("{}: no in-process support", case.entry.name)
            }
            Kind::Sync => {
                let query = parse_query(&case.entry.request);
                if require_consents && case.expect.text.as_deref() == Some("") {
                    let refused = matches!(
                        engine.run_sync(&query),
                        Err(QueryError::UserConsentsRequired)
                    ) && matches!(
                        engine.check_consents(&query),
                        Err(QueryError::UserConsentsRequired)
                    );
                    if !refused {
                        failures.push(format!(
                            "{}: expected UserConsentsRequired",
                            case.entry.name
                        ));
                    }
                    continue;
                }
                run_sync(engine, &query)
            }
        };
        let problems = evaluate(&case.expect, &outcome);
        if !problems.is_empty() {
            failures.push(format!("{}: {problems:?}", case.entry.name));
        }
    }
    failures
}

/// The outcome of one case as the HTTP layer would report it, without the golden file's
/// expectations: search, async exports, and sync queries (errors as 400 or 500).
fn case_outcome(engine: &Engine, case: &GoldenCase) -> Outcome {
    match case.entry.kind {
        Kind::Search => run_search(engine, &case.entry.request),
        Kind::Async => run_async(engine, &parse_query(&case.entry.request)),
        Kind::Sync => run_sync(engine, &parse_query(&case.entry.request)),
        Kind::UnknownStatus | Kind::UnknownResult => {
            panic!("{}: no in-process support", case.entry.name)
        }
    }
}

/// Runs `file` against the fixture under every one of [`SHARES`]: each must pass every
/// case and give the same outcome for every case. `full` is whether some case selects every
/// partition.
fn assert_golden(file: &str, require_consents: bool, cases: usize, full: bool) {
    let dir = fixture();
    let base = if require_consents {
        strict()
    } else {
        lenient()
    };
    let engines: Vec<(f64, Engine)> = SHARES
        .iter()
        .map(|&share| (share, dir.open(&with_share(base, share)).unwrap()))
        .collect();
    let golden = read_golden(&workspace().join("bench/golden").join(file)).unwrap();
    assert_eq!(golden.cases.len(), cases);
    for (share, engine) in &engines {
        assert_eq!(engine.partitions().requires_consents(), require_consents);
        let failures = run_golden(engine, &golden);
        assert!(
            failures.is_empty(),
            "{} of {} golden cases failed at share {share}:\n{}",
            failures.len(),
            golden.cases.len(),
            failures.join("\n")
        );
    }
    let (_, first) = &engines[0];
    let mut whole = 0;
    for case in &golden.cases {
        if case.entry.kind != Kind::Search {
            let query = parse_query(&case.entry.request);
            if first.read_plan(&query).is_ok_and(|plan| plan.full) {
                whole += 1;
            }
        }
        for (share, engine) in &engines[1..] {
            assert_eq!(
                case_outcome(first, case),
                case_outcome(engine, case),
                "{}: share {share} answers differently",
                case.entry.name
            );
        }
    }
    assert_eq!(
        whole > 0,
        full,
        "{whole} cases of {file} select every partition"
    );
    eprintln!(
        "{file}: {} golden cases passed under every share, {whole} of them selecting every partition",
        golden.cases.len()
    );
}

#[test]
fn harmonization_golden_corpus_passes_in_process() {
    assert_golden("harm-fixture.json", false, 73, true);
}

#[test]
fn strict_harmonization_golden_corpus_passes_in_process() {
    assert_golden("harm-fixture-strict.json", true, 12, false);
}

#[test]
fn fixture_partitions_and_patient_counts() {
    let dir = fixture();
    let engine = dir.open(&lenient()).unwrap();
    let set = engine.partitions();
    let names: Vec<&str> = set.partition_names().collect();
    assert_eq!(
        names,
        ["partition1", "partition2", "partition3", "partition4"]
    );
    let sizes: Vec<u64> = names
        .iter()
        .map(|name| {
            engine
                .count(&Query::with_consents(ResultType::Count, [*name]))
                .unwrap()
        })
        .collect();
    assert_eq!(sizes, [494, 1000, 1000, 2484]);
    assert_eq!(engine.count(&Query::new(ResultType::Count)).unwrap(), 4978);
    assert_eq!(set.concept_count(), 29);
    assert_eq!(set.dictionary().len(), 29);
}
