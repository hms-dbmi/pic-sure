//! Runs the golden corpus `bench/golden/1000genomes.json` in-process against a store loaded
//! from the Java integration fixture.
//!
//! The Java numbers come from `CSVLoader`, which reads the fixture's first line as data. The
//! Rust new-search loader reads the first line as a header, as `CSVLoaderNewSearch` does, so
//! the test prepends a header line. On this fixture the two Java loaders otherwise agree: no
//! path contains `µ`, no path ends with its row's text value (so `CSVLoader`'s rollup never
//! fires), no text value parses as a number, and every concept's rows are contiguous.

mod common;

use std::path::PathBuf;

use common::load_file;
use hpds_bench::client::{AsyncOutcome, Outcome, SyncOutcome};
use hpds_bench::golden::{evaluate, read_golden};
use hpds_query::{Engine, PATIENTS_HEADER, Query, QueryValidator, ResultType};
use serde_json::Value;

fn workspace() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

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

fn run_sync(engine: &Engine, query: &Query) -> Outcome {
    let (status, body) = match engine.run_sync(query) {
        Ok(result) => (200, result.body()),
        Err(e) => (if e.is_client_error() { 400 } else { 500 }, e.to_string()),
    };
    Outcome::Sync(SyncOutcome {
        status,
        content_type: None,
        body,
        has_query_metadata: status == 200,
    })
}

fn run_async(engine: &Engine, query: &Query) -> Outcome {
    let validator = QueryValidator {
        require_authorization_filter: false,
    };
    let body = engine
        .validate(query, &validator)
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
        status: None,
        resource_status: Some(if ok { "SUCCESS" } else { "ERROR" }.into()),
        result_status: ok.then_some(200),
        result_content_type: None,
        result_body: body.ok(),
        timed_out: false,
    })
}

#[test]
fn golden_corpus_passes_in_process() {
    let fixture =
        workspace().join("../pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv");
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    let mut text = String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
    text.push_str(&std::fs::read_to_string(&fixture).unwrap());
    std::fs::write(&input, text).unwrap();
    let store = load_file(dir, &input);

    let golden = read_golden(&workspace().join("bench/golden/1000genomes.json")).unwrap();
    assert!(!golden.cases.is_empty());
    let mut failures = Vec::new();
    for case in &golden.cases {
        let query = parse_query(&case.entry.request);
        let outcome =
            if case.entry.result_type == "PATIENTS" || case.entry.result_type == "DATAFRAME" {
                run_async(&store.engine, &query)
            } else {
                run_sync(&store.engine, &query)
            };
        let problems = evaluate(&case.expect, &outcome);
        if !problems.is_empty() {
            failures.push(format!("{}: {problems:?}", case.entry.name));
        }
    }
    assert!(
        failures.is_empty(),
        "{} of {} golden cases failed:\n{}",
        failures.len(),
        golden.cases.len(),
        failures.join("\n")
    );
    eprintln!("{} golden cases passed", golden.cases.len());
}
