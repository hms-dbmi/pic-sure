//! The Java genomic integration tests' cohort answers on `gfx`, in both layouts: every COUNT,
//! cross count and PATIENTS request `check_integration.py` sent to the harmonization service,
//! answered by the engine over the fixture phenotype partitions and the genomic store migrated
//! from Java's, and compared with Java's recorded response (cross counts as JSON values,
//! PATIENTS rows as sets). The variant list and excerpt requests belong to the variant result
//! types and are counted, not compared.
//!
//! Runs when `HPDS_GENOMIC_BENCH` names the bench data directory
//! (`.worktrees/bench-data/genomic`), which holds `gfx/{ld,lpd}/rust`,
//! `gfx/pheno/encryption_key` and `evidence/gfx-{ld,lpd}-integration.jsonl`.

mod common;

use std::collections::BTreeSet;
use std::path::PathBuf;

use hpds_query::{GenomicOptions, GenomicStore, Query, ResultType, parse_request_body};
use serde_json::Value;

fn bench_dir() -> Option<PathBuf> {
    std::env::var_os("HPDS_GENOMIC_BENCH").map(PathBuf::from)
}

/// The query of a recorded request body, read as the service reads an envelope.
fn query_of(request: &Value) -> Query {
    let body = parse_request_body(request.to_string().as_bytes()).unwrap();
    match &body["query"] {
        Value::String(text) => Query::from_json(text.as_bytes()).unwrap(),
        object => serde_json::from_value(object.clone()).unwrap(),
    }
}

#[test]
fn java_integration_cohort_answers_on_gfx_in_both_layouts() {
    let Some(bench) = bench_dir() else {
        eprintln!("HPDS_GENOMIC_BENCH is not set; skipping the gfx integration answers");
        return;
    };
    let pheno = common::harm_fixture();
    for layout in ["ld", "lpd"] {
        let store = GenomicStore::open_with_key_file(
            &bench.join(format!("gfx/{layout}/rust")),
            &bench.join("gfx/pheno/encryption_key"),
            &GenomicOptions::default(),
        )
        .unwrap();
        let engine = pheno.open(&common::lenient()).unwrap().with_genomic(store);
        let log =
            std::fs::read_to_string(bench.join(format!("evidence/gfx-{layout}-integration.jsonl")))
                .unwrap();
        let entries: Vec<Value> = log
            .lines()
            .map(|l| serde_json::from_str(l).unwrap())
            .collect();
        let (mut compared, mut patients, mut variant_types) = (0, 0, 0);
        for (i, entry) in entries.iter().enumerate() {
            let path = entry["path"].as_str().unwrap();
            if path != "query/sync" && path != "query" {
                continue;
            }
            let query = query_of(&entry["request"]);
            let result_type = query.expected_result_type;
            if result_type.is_genomic() {
                variant_types += 1;
                continue;
            }
            let what = format!("{layout} entry {i}: {}", entry["request"]);
            if path == "query/sync" {
                assert_eq!(entry["status"], 200, "{what}");
                let java = entry["body"].as_str().unwrap();
                let rust = engine.answer_sync(&query).unwrap();
                let rust = std::str::from_utf8(rust.body()).unwrap();
                if result_type == ResultType::Count {
                    assert_eq!(rust, java, "{what}");
                } else {
                    let java: Value = serde_json::from_str(java).unwrap();
                    let rust: Value = serde_json::from_str(rust).unwrap();
                    assert_eq!(rust, java, "{what}");
                }
                compared += 1;
            } else {
                assert_eq!(result_type, ResultType::Patients, "{what}");
                let status: Value = serde_json::from_str(entry["body"].as_str().unwrap()).unwrap();
                let id = status["resourceResultId"].as_str().unwrap();
                let result_path = format!("query/{id}/result");
                let result = entries[i..]
                    .iter()
                    .find(|e| e["path"] == result_path.as_str())
                    .unwrap_or_else(|| panic!("no result for {what}"));
                let java: BTreeSet<u32> = result["body"]
                    .as_str()
                    .unwrap()
                    .lines()
                    .skip(1)
                    .map(|l| l.parse().unwrap())
                    .collect();
                let rust: BTreeSet<u32> = engine.patients(&query).unwrap().iter().collect();
                assert_eq!(rust, java, "{what}");
                patients += 1;
            }
        }
        eprintln!(
            "gfx {layout}: {compared} sync answers and {patients} PATIENTS exports equal Java's; \
             {variant_types} variant result requests left to the variant engine"
        );
        assert!(compared >= 60 && patients >= 25, "{compared} {patients}");
        assert_eq!(engine.partitions().cohort_guard_refusals(), 0);
    }
}
