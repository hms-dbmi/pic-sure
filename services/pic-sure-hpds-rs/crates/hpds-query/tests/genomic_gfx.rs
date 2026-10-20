//! The Java genomic integration tests' cohort answers on `gfx`, in both layouts: every COUNT,
//! cross count and PATIENTS request `check_integration.py` sent to the harmonization service,
//! answered by the engine over the fixture phenotype partitions and the genomic store migrated
//! from Java's, and compared with Java's recorded response (cross counts as JSON values,
//! PATIENTS rows as sets), and every variant list and excerpt request with the excerpt
//! settings Java ran with (`VCF_EXCERPT_ENABLED=true`): variant lists as sets of specs,
//! excerpts with the rows in order, the INFO columns in Java's `HashSet` bucket order (within
//! a bucket Java's order follows the node directory listing, so it is compared as a set), and
//! the patient columns as a map from header to column, since Java's group order follows
//! `File.listFiles`.
//!
//! Runs when `HPDS_GENOMIC_BENCH` names the bench data directory
//! (`.worktrees/bench-data/genomic`), which holds `gfx/{ld,lpd}/rust`,
//! `gfx/pheno/encryption_key` and `evidence/gfx-{ld,lpd}-integration.jsonl`.

mod common;

use std::collections::BTreeSet;
use std::path::PathBuf;

use std::collections::BTreeMap;

use hpds_query::{
    GenomicOptions, GenomicStore, Query, ResultType, VariantResultSettings, parse_request_body,
};
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
        let engine = pheno
            .open(&common::lenient())
            .unwrap()
            .with_genomic(store)
            .with_variant_settings(VariantResultSettings {
                vcf_excerpt_enabled: true,
                ..VariantResultSettings::default()
            });
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
            let what = format!("{layout} entry {i}: {}", entry["request"]);
            if result_type.is_genomic() {
                assert_eq!(entry["status"], 200, "{what}");
                let java = entry["body"].as_str().unwrap();
                let rust = engine.answer_sync(&query).unwrap();
                let rust = std::str::from_utf8(rust.body()).unwrap();
                match result_type {
                    ResultType::VariantListForQuery => {
                        assert_eq!(spec_set(rust), spec_set(java), "{what}")
                    }
                    ResultType::VcfExcerpt | ResultType::AggregateVcfExcerpt => {
                        assert_eq!(excerpt_form(rust), excerpt_form(java), "{what}")
                    }
                    _ => assert_eq!(rust, java, "{what}"),
                }
                variant_types += 1;
                continue;
            }
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
             {variant_types} variant result answers equal Java's"
        );
        assert!(compared >= 60 && patients >= 25, "{compared} {patients}");
        assert_eq!(variant_types, 12, "the variant list and excerpt requests");
        assert_eq!(engine.partitions().cohort_guard_refusals(), 0);
    }
}

/// The specs of a variant list body, `[a, b]`, as a set.
fn spec_set(body: &str) -> BTreeSet<String> {
    let inner = body
        .strip_prefix('[')
        .and_then(|b| b.strip_suffix(']'))
        .unwrap_or_else(|| panic!("not a variant list: {body}"));
    inner
        .split(", ")
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect()
}

/// An excerpt as the comparison reads it: the lines with the patient columns cut off, and the
/// patient columns as a map from header to column. A text answer is itself.
type ExcerptForm = (Vec<String>, BTreeMap<String, Vec<String>>);

fn excerpt_form(body: &str) -> ExcerptForm {
    let mut lines = body.lines();
    let Some(header) = lines.next().filter(|h| h.starts_with("CHROM\t")) else {
        return (vec![body.to_owned()], BTreeMap::new());
    };
    let header: Vec<&str> = header.split('\t').collect();
    let fixed = header
        .iter()
        .position(|h| *h == "Patients with this variant NOT in subset")
        .unwrap()
        + 1;
    let rows: Vec<Vec<&str>> = lines.map(|l| l.split('\t').collect()).collect();
    let info = &header[4..fixed - 2];
    let layout =
        hpds_format::java_hash_layout(info.iter().copied(), hpds_format::JavaHashCapacity::Default);
    let bucket = |name: &str| layout.entries.iter().find(|e| e.1 == name).unwrap().0;
    let mut order: Vec<usize> = (0..fixed).collect();
    order[4..fixed - 2].sort_by_key(|&c| (bucket(header[c]), header[c]));
    let pick = |r: &[&str]| order.iter().map(|&c| r[c]).collect::<Vec<_>>().join("\t");
    let mut kept = vec![pick(&header)];
    kept.extend(rows.iter().map(|r| pick(r)));
    let columns = header[fixed..]
        .iter()
        .enumerate()
        .map(|(c, h)| {
            let cells = rows.iter().map(|r| r[fixed + c].to_owned()).collect();
            ((*h).to_owned(), cells)
        })
        .collect();
    (kept, columns)
}
