//! The variant result types against the Java answers the genomic equivalence corpora
//! recorded on `edge` and `g1kg`, which the oracle checked (Java correct, or Java throwing
//! where the oracle says it does). Every VARIANT_COUNT_FOR_QUERY, VARIANT_LIST_FOR_QUERY,
//! VCF_EXCERPT, AGGREGATE_VCF_EXCERPT and INFO_COLUMN_LISTING sync request of the chosen
//! layers is answered by the engine over the migrated genomic store and a phenotype store
//! loaded from the dataset's CSVs, with the settings Java ran with (`VCF_EXCERPT_ENABLED`,
//! consents not required), and compared:
//!
//! - VARIANT_COUNT_FOR_QUERY byte for byte; variant lists and listings as sets and multisets;
//! - excerpts as the `gfx` test compares them (INFO columns in bucket order, patient columns
//!   as a map from header to column), except that a row Java collapsed from two specs is
//!   compared on its key fields and counts are not, since which spec survives is unspecified
//!   in Java;
//! - a request Java fails on (a 500, an ERROR job or an empty 200) must be refused;
//! - a spec filter invalid as sent (a null zygosity, an `rs` key, a position that is not an
//!   `int`) in a request with no INFO filter is refused at admission, where Java's variant
//!   list never evaluates spec filters and answers empty: a named divergence, counted.
//!
//! When `HPDS_GENOMIC_ORACLE_ANSWERS` names a directory of `oracle-<dataset>-<layer>.jsonl`
//! files (the oracle's answers, `oracle/genomic.py`'s `answer_entry` per request), every Rust
//! answer is also compared with the oracle's, and a request Java fails on in its phenotypic
//! clause (v4's named Java failures, which Rust answers) must equal the oracle's answer.
//!
//! Runs when `HPDS_GENOMIC_EQUIVALENCE` names the bench data directory
//! (`.worktrees/bench-data`); `HPDS_GENOMIC_CORPUS_LIMIT` caps the requests per layer on
//! `g1kg` (default 300).

mod common;

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::io::BufRead;
use std::path::{Path, PathBuf};

use hpds_query::{
    Engine, GenomicOptions, GenomicStore, Query, QueryError, ResultType, VariantResultSettings,
    parse_request_body, path_is_variant_spec,
};
use serde_json::Value;

fn bench_dir() -> Option<PathBuf> {
    std::env::var_os("HPDS_GENOMIC_EQUIVALENCE").map(PathBuf::from)
}

/// The phenotype partitions of a dataset: one per CSV of its `pheno` list, named by the CSV's
/// stem without `-allConcepts`.
fn pheno_of(workspace: &Path, dataset: &Value) -> common::PartitionedDir {
    let texts: Vec<(String, String)> = dataset["pheno"]
        .as_array()
        .unwrap()
        .iter()
        .map(|p| {
            let path = workspace.join(p.as_str().unwrap());
            let stem = path.file_stem().unwrap().to_str().unwrap();
            let name = stem.trim_end_matches("-allConcepts").to_owned();
            let body = std::fs::read_to_string(&path).unwrap();
            (name, format!("{}{body}", common::FIXTURE_HEADER))
        })
        .collect();
    common::partitioned_texts(&texts)
}

fn gz_lines(path: &Path) -> Vec<Value> {
    let file = std::fs::File::open(path).unwrap();
    std::io::BufReader::new(flate2::read::GzDecoder::new(file))
        .lines()
        .map(|l| serde_json::from_str(&l.unwrap()).unwrap())
        .collect()
}

/// The query of a corpus entry, or `None` when the service could not read it.
fn query_of(entry: &Value) -> Option<Query> {
    let raw = match entry.get("rawBody").and_then(Value::as_str) {
        Some(raw) => raw.to_owned(),
        None => entry["request"].to_string(),
    };
    let body = parse_request_body(raw.as_bytes()).ok()?;
    match &body["query"] {
        Value::String(text) => Query::from_json(text.as_bytes()).ok(),
        object => serde_json::from_value(object.clone()).ok(),
    }
}

/// True when every genomic filter of `query` is a spec filter, so Java's variant list has no
/// INFO filter and returns empty without evaluating any of them.
fn never_evaluated(query: &Query) -> bool {
    query
        .genomic_filters
        .iter()
        .all(|f| f.key.as_deref().is_some_and(path_is_variant_spec))
}

/// Whether Rust's answer `rust` to a request of `result_type` equals the oracle's `result`
/// (`[kind, payload]`), compared as the oracle states it: JSON, a sorted spec list, text, an
/// INFO listing, or an excerpt's rows with each INFO cell as a set of values.
fn equals_oracle(result_type: ResultType, rust: &str, result: &Value) -> bool {
    let (kind, payload) = (result[0].as_str().unwrap_or_default(), &result[1]);
    match kind {
        "json" => serde_json::from_str::<Value>(rust).ok().as_ref() == Some(payload),
        "text" => payload.as_str() == Some(rust),
        "variant_list" => {
            let oracle: BTreeSet<String> = payload
                .as_array()
                .unwrap()
                .iter()
                .map(|v| v.as_str().unwrap().to_owned())
                .collect();
            spec_set(rust) == Some(oracle)
        }
        "info_listing" => {
            let triple = |v: &Value| {
                (
                    v["key"].as_str().unwrap_or_default().to_owned(),
                    v["description"].as_str().unwrap_or_default().to_owned(),
                    v["continuous"].as_bool(),
                )
            };
            let mut oracle: Vec<_> = payload.as_array().unwrap().iter().map(triple).collect();
            let mut ours: Vec<_> = serde_json::from_str::<Vec<Value>>(rust)
                .unwrap()
                .iter()
                .map(triple)
                .collect();
            oracle.sort();
            ours.sort();
            oracle == ours
        }
        "excerpt" => excerpt_equals_oracle(result_type, rust, payload),
        _ => false,
    }
}

fn excerpt_equals_oracle(result_type: ResultType, rust: &str, oracle: &Value) -> bool {
    let mut lines = rust.lines();
    let Some(header) = lines.next() else {
        return false;
    };
    let header: Vec<&str> = header.split('\t').collect();
    let fixed = header
        .iter()
        .position(|h| *h == "Patients with this variant NOT in subset")
        .map_or(0, |p| p + 1);
    let rows: Vec<Vec<&str>> = lines.map(|l| l.split('\t').collect()).collect();
    let oracle_rows = oracle["rows"].as_array().unwrap();
    if fixed == 0 || rows.len() != oracle_rows.len() {
        return false;
    }
    let info_columns = &header[4..fixed - 2];
    let mut oracle_columns: Vec<&str> = oracle["columns"]
        .as_array()
        .unwrap()
        .iter()
        .flat_map(|g| g.as_array().unwrap().iter().map(|c| c.as_str().unwrap()))
        .collect();
    let mut ours = info_columns.to_vec();
    oracle_columns.sort_unstable();
    ours.sort_unstable();
    if ours != oracle_columns {
        return false;
    }
    rows.iter().zip(oracle_rows).all(|(row, o)| {
        let fixed_equal = (0..4).all(|i| Some(row[i]) == o["fixed"][i].as_str());
        let info_equal = info_columns.iter().enumerate().all(|(c, column)| {
            let cell = row[4 + c];
            match o["info"][*column].as_array() {
                None => cell == "null",
                Some(values) => cell_holds(cell, values),
            }
        });
        let counts_equal =
            Some(row[fixed - 2]) == o["in"].as_str() && Some(row[fixed - 1]) == o["out"].as_str();
        let cells_equal = result_type != ResultType::VcfExcerpt
            || header[fixed..]
                .iter()
                .enumerate()
                .all(|(c, h)| o["cells"][*h].as_str() == Some(row[fixed + c]))
                && o["cells"].as_object().map_or(0, |m| m.len()) == header.len() - fixed;
        fixed_equal && info_equal && counts_equal && cells_equal
    })
}

/// True when the INFO cell `cell` is the oracle's distinct `values` joined with `,` in some
/// order. A value may hold a comma itself, so the cell is matched against the values rather
/// than split.
fn cell_holds(cell: &str, values: &[Value]) -> bool {
    let values: Vec<&str> = values.iter().map(|v| v.as_str().unwrap()).collect();
    if values.len() == 1 {
        return cell == values[0];
    }
    let joined_len: usize = values.iter().map(|v| v.len()).sum::<usize>() + values.len() - 1;
    cell.len() == joined_len && values.iter().all(|v| cell.contains(v))
}

fn spec_set(body: &str) -> Option<BTreeSet<String>> {
    let inner = body.strip_prefix('[')?.strip_suffix(']')?;
    Some(
        inner
            .split(", ")
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .collect(),
    )
}

/// An excerpt's rows with the patient cells cut off and the INFO columns in bucket then
/// name order, and its patient columns as a map; text answers are themselves.
type ExcerptForm = (Vec<Vec<String>>, BTreeMap<String, Vec<String>>);

fn excerpt_form(body: &str) -> ExcerptForm {
    let mut lines = body.lines();
    let Some(header) = lines.next().filter(|h| h.starts_with("CHROM\t")) else {
        return (vec![vec![body.to_owned()]], BTreeMap::new());
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
    let pick = |r: &[&str]| order.iter().map(|&c| r[c].to_owned()).collect::<Vec<_>>();
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

/// True when two excerpts differ only in rows whose spec Java collapsed with another: equal
/// row counts, and every differing row's chromosome and position agree.
fn differ_only_in_collapsed_rows(rust: &ExcerptForm, java: &ExcerptForm, list: &[String]) -> bool {
    let positions = |s: &str| {
        let f: Vec<&str> = s.split(',').collect();
        (
            f[0].to_owned(),
            f.get(1).and_then(|p| p.parse::<i64>().ok()),
        )
    };
    let collapsed: BTreeSet<_> = {
        let mut seen = BTreeMap::new();
        for s in list {
            *seen.entry(positions(s)).or_insert(0) += 1;
        }
        seen.into_iter()
            .filter(|(_, n)| *n > 1)
            .map(|(k, _)| k)
            .collect()
    };
    rust.0.len() == java.0.len()
        && rust.0.iter().zip(&java.0).skip(1).all(|(r, j)| {
            r == j
                || (r[0] == j[0]
                    && r[1].parse::<i64>().ok() == j[1].parse::<i64>().ok()
                    && collapsed.contains(&(r[0].clone(), r[1].parse().ok())))
        })
}

#[test]
fn variant_results_equal_the_java_answers_of_the_genomic_corpora() {
    let Some(bench) = bench_dir() else {
        eprintln!("HPDS_GENOMIC_EQUIVALENCE is not set; skipping the corpora");
        return;
    };
    let workspace = bench.join("../..");
    let oracle_dir = std::env::var_os("HPDS_GENOMIC_ORACLE_ANSWERS").map(PathBuf::from);
    let limit: usize = std::env::var("HPDS_GENOMIC_CORPUS_LIMIT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(300);
    let genomic_types = [
        ResultType::VariantCountForQuery,
        ResultType::VariantListForQuery,
        ResultType::VcfExcerpt,
        ResultType::AggregateVcfExcerpt,
        ResultType::InfoColumnListing,
    ];
    for (name, layers, cap) in [
        (
            "edge",
            vec!["G0", "G1", "G1P", "G2", "GC", "GF", "GS", "GX"],
            usize::MAX,
        ),
        ("g1kg", vec!["GT", "R501", "R511"], limit),
    ] {
        let dataset: Value = serde_json::from_str(
            &std::fs::read_to_string(workspace.join(format!(
                "brain/rust-hpds-phenotype/equivalence-harness/genomic/datasets/{name}.json"
            )))
            .unwrap(),
        )
        .unwrap();
        let pheno = pheno_of(&workspace, &dataset);
        let store = GenomicStore::open_with_key_file(
            &bench.join(format!("genomic/{name}/rust")),
            &bench.join(format!("genomic/{name}/pheno/encryption_key")),
            &GenomicOptions::default(),
        )
        .unwrap();
        let engine: Engine = pheno
            .open(&common::lenient())
            .unwrap()
            .with_genomic(store)
            .with_variant_settings(VariantResultSettings {
                vcf_excerpt_enabled: true,
                ..VariantResultSettings::default()
            });
        let mut tally: BTreeMap<&str, usize> = BTreeMap::new();
        let mut problems = Vec::new();
        for layer in layers {
            let corpus = bench.join(format!("equivalence-genomic/corpora/{name}/{layer}"));
            let java_path = bench.join(format!(
                "equivalence-genomic/out/{name}/{layer}-java.jsonl.gz"
            ));
            if !java_path.exists() {
                continue;
            }
            let java: HashMap<String, Value> = gz_lines(&java_path)
                .into_iter()
                .map(|d| (d["name"].as_str().unwrap().to_owned(), d["outcome"].clone()))
                .collect();
            let oracle: HashMap<String, Value> = oracle_dir
                .as_ref()
                .map(|d| d.join(format!("oracle-{name}-{layer}.jsonl")))
                .filter(|p| p.exists())
                .map(|p| {
                    std::fs::read_to_string(p)
                        .unwrap()
                        .lines()
                        .map(|l| {
                            let v: Value = serde_json::from_str(l).unwrap();
                            (v["name"].as_str().unwrap().to_owned(), v["answer"].clone())
                        })
                        .collect()
                })
                .unwrap_or_default();
            let mut files: Vec<PathBuf> = std::fs::read_dir(&corpus)
                .unwrap()
                .map(|e| e.unwrap().path())
                .filter(|p| p.extension().is_some_and(|x| x == "json"))
                .collect();
            files.sort();
            let mut taken = 0;
            for file in files {
                let entry: Value =
                    serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
                if entry["kind"] != "sync" {
                    continue;
                }
                let Some(outcome) = java.get(entry["name"].as_str().unwrap()) else {
                    continue;
                };
                let Some(query) = query_of(&entry) else {
                    *tally.entry("unreadable by both").or_default() += 1;
                    continue;
                };
                if !genomic_types.contains(&query.expected_result_type) {
                    continue;
                }
                if taken >= cap {
                    break;
                }
                taken += 1;
                let what = format!("{name} {}", entry["name"]);
                let java_body = outcome["body"].as_str().unwrap_or_default();
                let java_failed = outcome["status"] != 200 || java_body.is_empty();
                let rust = engine.answer_sync(&query);
                let oracle_result = oracle
                    .get(entry["name"].as_str().unwrap())
                    .map(|a| a["result"].clone())
                    .filter(|r| !r.is_null());
                if let (Ok(answer), Some(result)) = (&rust, &oracle_result) {
                    let body = String::from_utf8_lossy(answer.body());
                    if equals_oracle(query.expected_result_type, &body, result) {
                        *tally.entry("equal to the oracle").or_default() += 1;
                        if java_failed {
                            *tally
                                .entry(
                                    "Java fails in the phenotypic clause; Rust equals the oracle",
                                )
                                .or_default() += 1;
                            continue;
                        }
                    } else {
                        problems.push(format!(
                            "{what}: differs from the oracle\n  rust: {}\n  oracle: {}",
                            body.chars().take(600).collect::<String>(),
                            result.to_string().chars().take(600).collect::<String>()
                        ));
                        continue;
                    }
                }
                let rust = match (rust, java_failed) {
                    (Err(e), true) => {
                        assert!(e.is_client_error(), "{what}: {e}");
                        *tally.entry("both refuse").or_default() += 1;
                        continue;
                    }
                    (Err(QueryError::GenomicFilter(_)), false) if never_evaluated(&query) => {
                        *tally
                            .entry("Rust refuses a spec filter Java never evaluates")
                            .or_default() += 1;
                        continue;
                    }
                    (Err(e), false) => {
                        problems.push(format!("{what}: Rust refused ({e}), Java answered"));
                        continue;
                    }
                    (Ok(answer), true) => {
                        problems.push(format!(
                            "{what}: Java failed, Rust answered {:?}",
                            String::from_utf8_lossy(answer.body())
                                .chars()
                                .take(200)
                                .collect::<String>()
                        ));
                        continue;
                    }
                    (Ok(answer), false) => String::from_utf8(answer.body().to_vec()).unwrap(),
                };
                let equal = match query.expected_result_type {
                    ResultType::VariantListForQuery => spec_set(&rust) == spec_set(java_body),
                    ResultType::InfoColumnListing => {
                        let sorted = |b: &str| {
                            let mut v: Vec<String> = serde_json::from_str::<Vec<Value>>(b)
                                .unwrap()
                                .iter()
                                .map(Value::to_string)
                                .collect();
                            v.sort();
                            v
                        };
                        sorted(&rust) == sorted(java_body)
                    }
                    ResultType::VcfExcerpt | ResultType::AggregateVcfExcerpt => {
                        let (r, j) = (excerpt_form(&rust), excerpt_form(java_body));
                        if r == j {
                            true
                        } else {
                            let mut list_query = query.clone();
                            list_query.expected_result_type = ResultType::VariantListForQuery;
                            let list: Vec<String> = engine
                                .variant_list(&list_query)
                                .ok()
                                .and_then(|b| spec_set(&b))
                                .map(|s| s.into_iter().collect())
                                .unwrap_or_default();
                            let collapsed = differ_only_in_collapsed_rows(&r, &j, &list);
                            if collapsed {
                                *tally
                                    .entry("excerpt equal but for collapsed rows")
                                    .or_default() += 1;
                            }
                            collapsed && r.1.keys().eq(j.1.keys())
                        }
                    }
                    _ => rust == java_body,
                };
                if equal {
                    *tally.entry("equal").or_default() += 1;
                } else {
                    problems.push(format!(
                        "{what}: differs\n  rust: {}\n  java: {}",
                        rust.chars().take(600).collect::<String>(),
                        java_body.chars().take(600).collect::<String>()
                    ));
                }
            }
        }
        eprintln!("{name}: {tally:?}, {} problems", problems.len());
        for p in problems.iter().take(15) {
            eprintln!("{p}");
        }
        assert!(problems.is_empty(), "{name}: {} problems", problems.len());
        assert!(tally.get("equal").copied().unwrap_or(0) > 0);
    }
}
