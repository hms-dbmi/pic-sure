use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::column_meta::{ColumnMeta, is_genomic_path};
use crate::rng::Rng;
use crate::{BenchError, Result};

/// Concept path that carries consent values.
pub const CONSENTS_PATH: &str = "\\_consents\\";

/// Result types served on the request thread.
pub const SYNC_RESULT_TYPES: [&str; 5] = [
    "COUNT",
    "CROSS_COUNT",
    "CATEGORICAL_CROSS_COUNT",
    "CONTINUOUS_CROSS_COUNT",
    "OBSERVATION_CROSS_COUNT",
];

/// Result types served through submit, status and result.
pub const ASYNC_RESULT_TYPES: [&str; 2] = ["PATIENTS", "DATAFRAME"];

const SHAPES: [&str; 7] = [
    "categorical",
    "numeric-range",
    "required",
    "any-record-of",
    "nested",
    "conjunction",
    "authorization",
];

/// How a corpus entry is executed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Kind {
    /// One `POST /v3/query/sync` round trip.
    Sync,
    /// Submit, poll status, fetch result.
    Async,
}

/// One request in a corpus.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CorpusEntry {
    /// Unique, filesystem-safe name.
    pub name: String,
    /// Execution path.
    pub kind: Kind,
    /// Requested `ResultType`.
    #[serde(rename = "resultType")]
    pub result_type: String,
    /// The `{"query": ..., "resourceUUID": null}` envelope sent to the server.
    pub request: Value,
}

/// One manifest row pointing at an entry file.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ManifestRow {
    /// Entry name.
    pub name: String,
    /// Execution path.
    pub kind: Kind,
    /// Requested `ResultType`.
    #[serde(rename = "resultType")]
    pub result_type: String,
    /// Entry file name relative to the corpus directory.
    pub file: String,
}

/// The corpus index written next to the entry files.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Manifest {
    /// Seed the corpus was generated from.
    pub seed: u64,
    /// Number of generated entries (excluding the fixed ones).
    pub size: usize,
    /// All entries in replay order.
    pub entries: Vec<ManifestRow>,
}

struct Pools {
    categorical: Vec<ColumnMeta>,
    numeric: Vec<ColumnMeta>,
    all: Vec<ColumnMeta>,
    consents: Option<ColumnMeta>,
}

impl Pools {
    fn from(columns: &[ColumnMeta]) -> Result<Self> {
        let usable: Vec<ColumnMeta> = columns
            .iter()
            .filter(|c| !is_genomic_path(&c.name) && c.patient_count > 0)
            .cloned()
            .collect();
        let consents = usable
            .iter()
            .find(|c| c.name == CONSENTS_PATH && !c.categories.is_empty())
            .cloned();
        let regular: Vec<ColumnMeta> = usable
            .into_iter()
            .filter(|c| !c.name.starts_with(CONSENTS_PATH))
            .collect();
        let categorical: Vec<ColumnMeta> = regular
            .iter()
            .filter(|c| c.categorical && !c.categories.is_empty())
            .cloned()
            .collect();
        let numeric: Vec<ColumnMeta> = regular
            .iter()
            .filter(|c| {
                !c.categorical && matches!((c.min, c.max), (Some(lo), Some(hi)) if lo <= hi)
            })
            .cloned()
            .collect();
        if categorical.is_empty() && numeric.is_empty() {
            return Err(BenchError::Invalid(
                "columnMeta has no usable phenotype concepts".into(),
            ));
        }
        let all: Vec<ColumnMeta> = categorical.iter().chain(numeric.iter()).cloned().collect();
        Ok(Self {
            categorical,
            numeric,
            all,
            consents,
        })
    }
}

fn round2(value: f64) -> f64 {
    (value * 100.0).round() / 100.0
}

fn filter(kind: &str, path: &str) -> Map<String, Value> {
    let mut map = Map::new();
    map.insert("phenotypicFilterType".into(), json!(kind));
    map.insert("conceptPath".into(), json!(path));
    map
}

fn categorical_filter(rng: &mut Rng, column: &ColumnMeta, several: bool) -> Value {
    let count = if several { rng.between(2, 4) } else { 1 };
    let mut values = rng.sample(&column.categories, count);
    values.sort();
    let mut map = filter("FILTER", &column.name);
    map.insert("values".into(), json!(values));
    Value::Object(map)
}

fn numeric_filter(rng: &mut Rng, column: &ColumnMeta, variant: usize) -> Value {
    let (lo, hi) = (column.min.unwrap_or(0.0), column.max.unwrap_or(0.0));
    let span = hi - lo;
    let mut a = round2(lo + rng.unit() * span);
    let mut b = round2(lo + rng.unit() * span);
    if a > b {
        std::mem::swap(&mut a, &mut b);
    }
    let mut map = filter("FILTER", &column.name);
    match variant % 3 {
        0 => {
            map.insert("min".into(), json!(a));
        }
        1 => {
            map.insert("max".into(), json!(b));
        }
        _ => {
            map.insert("min".into(), json!(a));
            map.insert("max".into(), json!(b));
        }
    }
    Value::Object(map)
}

fn required_filter(column: &ColumnMeta) -> Value {
    Value::Object(filter("REQUIRED", &column.name))
}

fn leaf(rng: &mut Rng, pools: &Pools) -> Value {
    let numeric_turn =
        !pools.numeric.is_empty() && (pools.categorical.is_empty() || rng.below(2) == 0);
    if numeric_turn {
        let column = rng
            .pick(&pools.numeric)
            .cloned()
            .unwrap_or_else(|| pools.numeric[0].clone());
        let variant = rng.below(3);
        numeric_filter(rng, &column, variant)
    } else {
        let column = rng
            .pick(&pools.categorical)
            .cloned()
            .unwrap_or_else(|| pools.categorical[0].clone());
        let several = rng.below(2) == 0;
        categorical_filter(rng, &column, several)
    }
}

fn subquery(operator: &str, clauses: Vec<Value>) -> Value {
    json!({"phenotypicClauses": clauses, "operator": operator})
}

fn random_operator(rng: &mut Rng) -> &'static str {
    if rng.below(2) == 0 { "AND" } else { "OR" }
}

fn nested(rng: &mut Rng, pools: &Pools, depth: usize) -> Value {
    if depth == 0 {
        return leaf(rng, pools);
    }
    let operator = random_operator(rng);
    let width = rng.between(2, 3);
    let mut clauses = vec![nested(rng, pools, depth - 1)];
    for _ in 1..width {
        let sub_depth = rng.below(depth);
        clauses.push(nested(rng, pools, sub_depth));
    }
    subquery(operator, clauses)
}

fn parent_prefix(path: &str) -> Option<String> {
    let trimmed = path.strip_suffix('\\').unwrap_or(path);
    trimmed
        .rfind('\\')
        .map(|index| trimmed[..=index].to_owned())
}

fn common_prefix_len(a: &str, b: &str) -> usize {
    a.chars()
        .zip(b.chars())
        .take_while(|(x, y)| x == y)
        .map(|(x, _)| x.len_utf8())
        .sum()
}

fn any_record_of_prefix(rng: &mut Rng, pools: &Pools, partial: bool) -> String {
    let mut prefixes: Vec<String> = pools
        .all
        .iter()
        .filter_map(|c| parent_prefix(&c.name))
        .collect();
    prefixes.sort();
    prefixes.dedup();
    let with_children: Vec<String> = prefixes
        .iter()
        .filter(|p| {
            pools
                .all
                .iter()
                .filter(|c| c.name.starts_with(p.as_str()))
                .count()
                >= 2
        })
        .cloned()
        .collect();
    let candidates = if with_children.is_empty() {
        prefixes
    } else {
        with_children
    };
    let prefix = rng
        .pick(&candidates)
        .cloned()
        .unwrap_or_else(|| "\\".to_owned());
    if !partial {
        return prefix;
    }
    let children: Vec<&ColumnMeta> = pools
        .all
        .iter()
        .filter(|c| c.name.starts_with(&prefix))
        .collect();
    if children.len() < 2 {
        return prefix;
    }
    let first = children[rng.below(children.len())];
    let second = children[rng.below(children.len())];
    let len = common_prefix_len(&first.name, &second.name);
    if len > prefix.len() {
        first.name[..len].to_owned()
    } else {
        prefix
    }
}

fn select_list(rng: &mut Rng, pools: &Pools, max: usize, add_unknown: bool) -> Vec<String> {
    let count = rng.between(1, max);
    let mut paths: Vec<String> = rng
        .sample(&pools.all, count)
        .into_iter()
        .map(|c| c.name)
        .collect();
    if add_unknown {
        let at = rng.below(paths.len() + 1);
        paths.insert(at, "\\bench\\unknown\\NOT_A_CONCEPT\\".to_owned());
    }
    paths
}

fn clause_for_shape(rng: &mut Rng, pools: &Pools, shape: &str, ordinal: usize) -> Value {
    match shape {
        "categorical" => {
            let column = rng
                .pick(&pools.categorical)
                .or_else(|| pools.all.first())
                .cloned();
            match column {
                Some(c) if c.categorical => categorical_filter(rng, &c, ordinal.is_multiple_of(2)),
                _ => leaf(rng, pools),
            }
        }
        "numeric-range" => match rng.pick(&pools.numeric).cloned() {
            Some(c) => numeric_filter(rng, &c, ordinal),
            None => leaf(rng, pools),
        },
        "required" => {
            let column = rng
                .pick(&pools.all)
                .cloned()
                .unwrap_or_else(|| pools.all[0].clone());
            required_filter(&column)
        }
        "any-record-of" => {
            let prefix = any_record_of_prefix(rng, pools, ordinal % 2 == 1);
            Value::Object(filter("ANY_RECORD_OF", &prefix))
        }
        "nested" => {
            let depth = rng.between(2, 3);
            nested(rng, pools, depth)
        }
        _ => {
            let width = rng.between(2, 3);
            let clauses = (0..width).map(|_| leaf(rng, pools)).collect();
            subquery(random_operator(rng), clauses)
        }
    }
}

fn envelope(
    select: &[String],
    authorization: Option<Value>,
    clause: Option<Value>,
    result_type: &str,
) -> Value {
    let mut query = Map::new();
    query.insert("select".into(), json!(select));
    if let Some(auth) = authorization {
        query.insert("authorizationFilters".into(), auth);
    }
    if let Some(clause) = clause {
        query.insert("phenotypicClause".into(), clause);
    }
    query.insert("expectedResultType".into(), json!(result_type));
    json!({"query": Value::Object(query), "resourceUUID": null})
}

fn kind_of(result_type: &str) -> Kind {
    if ASYNC_RESULT_TYPES.contains(&result_type) {
        Kind::Async
    } else {
        Kind::Sync
    }
}

fn entry(name: String, result_type: &str, request: Value) -> CorpusEntry {
    CorpusEntry {
        name,
        kind: kind_of(result_type),
        result_type: result_type.to_owned(),
        request,
    }
}

/// Generates a deterministic corpus from column metadata.
///
/// `size` entries are produced by rotating over every filter shape and every result type, so the
/// first 49 entries already cover each combination. A few fixed entries (empty query, unknown
/// concepts) are prepended. The same inputs always give the same entries.
///
/// # Errors
///
/// Fails when the metadata holds no usable categorical or numeric phenotype concept.
pub fn generate(columns: &[ColumnMeta], seed: u64, size: usize) -> Result<Vec<CorpusEntry>> {
    let pools = Pools::from(columns)?;
    let mut rng = Rng::new(seed);
    let result_types: Vec<&str> = SYNC_RESULT_TYPES
        .iter()
        .chain(ASYNC_RESULT_TYPES.iter())
        .copied()
        .collect();
    let mut entries = Vec::new();

    entries.push(entry(
        "fixed-count-all".into(),
        "COUNT",
        envelope(&[], None, None, "COUNT"),
    ));
    let unknown = vec!["\\bench\\unknown\\NOT_A_CONCEPT\\".to_owned()];
    entries.push(entry(
        "fixed-cross-count-unknown".into(),
        "CROSS_COUNT",
        envelope(&unknown, None, None, "CROSS_COUNT"),
    ));
    let known = select_list(&mut rng, &pools, 2, true);
    entries.push(entry(
        "fixed-dataframe-unknown".into(),
        "DATAFRAME",
        envelope(&known, None, None, "DATAFRAME"),
    ));
    entries.push(entry(
        "fixed-patients-all".into(),
        "PATIENTS",
        envelope(&[], None, None, "PATIENTS"),
    ));

    for index in 0..size {
        let shape = SHAPES[index % SHAPES.len()];
        let result_type =
            result_types[(index / SHAPES.len() + index % SHAPES.len()) % result_types.len()];
        let ordinal = index / SHAPES.len();
        let (shape, authorization) = authorization_for(&mut rng, &pools, shape);
        let clause = clause_for_shape(&mut rng, &pools, shape, ordinal);
        let select = match result_type {
            "CROSS_COUNT" | "OBSERVATION_CROSS_COUNT" => {
                select_list(&mut rng, &pools, 5, ordinal % 5 == 4)
            }
            "DATAFRAME" => select_list(&mut rng, &pools, 8, ordinal % 4 == 3),
            _ => Vec::new(),
        };
        let name = format!(
            "{index:04}-{shape}-{}",
            result_type.to_lowercase().replace('_', "-")
        );
        entries.push(entry(
            name,
            result_type,
            envelope(&select, authorization, Some(clause), result_type),
        ));
    }
    Ok(entries)
}

fn authorization_for<'a>(rng: &mut Rng, pools: &Pools, shape: &'a str) -> (&'a str, Option<Value>) {
    if shape != "authorization" {
        return (shape, None);
    }
    match &pools.consents {
        Some(consents) => {
            let count = rng.between(1, 3);
            let mut values = rng.sample(&consents.categories, count);
            values.sort();
            (
                "authorization",
                Some(json!([{"conceptPath": consents.name, "values": values}])),
            )
        }
        None => ("disjunction", None),
    }
}

/// Writes entry files and `manifest.json` into `dir`, creating it when needed.
///
/// # Errors
///
/// Fails on any file system or serialization error.
pub fn write_corpus(
    dir: &Path,
    seed: u64,
    size: usize,
    entries: &[CorpusEntry],
) -> Result<Manifest> {
    std::fs::create_dir_all(dir).map_err(|e| BenchError::io(dir, e))?;
    let mut rows = Vec::new();
    for item in entries {
        let file = format!("{}.json", item.name);
        let text =
            serde_json::to_string_pretty(item).map_err(|e| BenchError::json(&item.name, e))?;
        let path = dir.join(&file);
        std::fs::write(&path, text).map_err(|e| BenchError::io(&path, e))?;
        rows.push(ManifestRow {
            name: item.name.clone(),
            kind: item.kind,
            result_type: item.result_type.clone(),
            file,
        });
    }
    let manifest = Manifest {
        seed,
        size,
        entries: rows,
    };
    let path = dir.join("manifest.json");
    let text =
        serde_json::to_string_pretty(&manifest).map_err(|e| BenchError::json("manifest", e))?;
    std::fs::write(&path, text).map_err(|e| BenchError::io(&path, e))?;
    Ok(manifest)
}

/// Loads every entry listed in `<dir>/manifest.json`, in manifest order.
///
/// # Errors
///
/// Fails when the manifest or an entry file is missing or malformed.
pub fn read_corpus(dir: &Path) -> Result<Vec<CorpusEntry>> {
    let manifest_path = dir.join("manifest.json");
    let text =
        std::fs::read_to_string(&manifest_path).map_err(|e| BenchError::io(&manifest_path, e))?;
    let manifest: Manifest =
        serde_json::from_str(&text).map_err(|e| BenchError::json("manifest.json", e))?;
    let mut entries = Vec::new();
    for row in manifest.entries {
        let path: PathBuf = dir.join(&row.file);
        let body = std::fs::read_to_string(&path).map_err(|e| BenchError::io(&path, e))?;
        entries
            .push(serde_json::from_str(&body).map_err(|e| BenchError::json(row.file.clone(), e))?);
    }
    Ok(entries)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn column(name: &str, categories: &[&str], range: Option<(f64, f64)>) -> ColumnMeta {
        ColumnMeta {
            name: name.into(),
            categorical: range.is_none(),
            categories: categories.iter().map(|s| (*s).to_owned()).collect(),
            min: range.map(|r| r.0),
            max: range.map(|r| r.1),
            observation_count: 10,
            patient_count: 10,
        }
    }

    fn sample_columns(with_consents: bool) -> Vec<ColumnMeta> {
        let mut columns = vec![
            column("\\study\\data\\SEX\\", &["female", "male"], None),
            column(
                "\\study\\data\\POPULATION NAME\\",
                &["Finnish", "Yoruba", "Colombian"],
                None,
            ),
            column(
                "\\study\\data\\POPULATION CODE\\",
                &["FIN", "YRI", "CLM"],
                None,
            ),
            column("\\study\\data\\AGE\\", &[], Some((31.0, 62.0))),
            column("\\study\\data\\HEIGHT\\", &[], Some((140.0, 199.0))),
            column("\\other\\VISIT\\", &["a", "b"], None),
            column("chr21,5032061,A,G", &["0/1"], None),
        ];
        if with_consents {
            columns.push(column(
                CONSENTS_PATH,
                &["phs1.c1", "phs1.c2", "phs2.c1"],
                None,
            ));
        }
        columns
    }

    #[test]
    fn same_seed_gives_identical_corpus_and_other_seed_differs() {
        let columns = sample_columns(true);
        let a = generate(&columns, 11, 120).unwrap();
        let b = generate(&columns, 11, 120).unwrap();
        let c = generate(&columns, 12, 120).unwrap();
        assert_eq!(a, b);
        assert_ne!(a, c);
    }

    #[test]
    fn covers_every_shape_and_result_type() {
        let entries = generate(&sample_columns(true), 5, 200).unwrap();
        let types: BTreeSet<&str> = entries.iter().map(|e| e.result_type.as_str()).collect();
        for expected in SYNC_RESULT_TYPES.iter().chain(ASYNC_RESULT_TYPES.iter()) {
            assert!(types.contains(expected), "missing {expected}");
        }
        for shape in [
            "categorical",
            "numeric-range",
            "required",
            "any-record-of",
            "nested",
            "conjunction",
            "authorization",
        ] {
            for result_type in SYNC_RESULT_TYPES.iter().chain(ASYNC_RESULT_TYPES.iter()) {
                let suffix = result_type.to_lowercase().replace('_', "-");
                assert!(
                    entries.iter().any(
                        |e| e.name.contains(&format!("-{shape}-")) && e.name.ends_with(&suffix)
                    ),
                    "missing {shape} x {result_type}"
                );
            }
        }
        let text = serde_json::to_string(&entries).unwrap();
        for needle in [
            "\"min\"",
            "\"max\"",
            "\"REQUIRED\"",
            "\"ANY_RECORD_OF\"",
            "\"OR\"",
            "\"AND\"",
            "authorizationFilters",
        ] {
            assert!(text.contains(needle), "missing {needle}");
        }
    }

    #[test]
    fn genomic_paths_never_appear() {
        let entries = generate(&sample_columns(false), 9, 150).unwrap();
        assert!(!serde_json::to_string(&entries).unwrap().contains("chr21"));
    }

    #[test]
    fn authorization_values_come_from_consent_categories() {
        let entries = generate(&sample_columns(true), 2, 100).unwrap();
        let mut seen = false;
        for item in &entries {
            if let Some(filters) = item.request["query"]["authorizationFilters"].as_array() {
                seen = true;
                assert_eq!(filters[0]["conceptPath"], CONSENTS_PATH);
                for value in filters[0]["values"].as_array().unwrap() {
                    assert!(["phs1.c1", "phs1.c2", "phs2.c1"].contains(&value.as_str().unwrap()));
                }
            }
        }
        assert!(seen);
        let without = generate(&sample_columns(false), 2, 100).unwrap();
        assert!(
            !serde_json::to_string(&without)
                .unwrap()
                .contains("authorizationFilters")
        );
    }

    #[test]
    fn numeric_bounds_stay_inside_column_range() {
        let entries = generate(&sample_columns(false), 4, 200).unwrap();
        let text = serde_json::to_string(&entries).unwrap();
        assert!(text.contains("\"min\""));
        for item in &entries {
            fn walk(value: &Value) {
                if let Some(object) = value.as_object() {
                    let path = object.get("conceptPath").and_then(Value::as_str);
                    for key in ["min", "max"] {
                        if let (Some(path), Some(bound)) =
                            (path, object.get(key).and_then(Value::as_f64))
                        {
                            let (lo, hi) = if path.ends_with("AGE\\") {
                                (31.0, 62.0)
                            } else {
                                (140.0, 199.0)
                            };
                            assert!((lo..=hi).contains(&bound), "{path} {bound}");
                        }
                    }
                    object.values().for_each(walk);
                } else if let Some(array) = value.as_array() {
                    array.iter().for_each(walk);
                }
            }
            walk(&item.request);
        }
    }

    #[test]
    fn unknown_paths_are_present_and_kinds_match_result_types() {
        let entries = generate(&sample_columns(false), 1, 100).unwrap();
        assert!(entries.iter().any(
            |e| e.result_type == "DATAFRAME" && e.request.to_string().contains("NOT_A_CONCEPT")
        ));
        for item in &entries {
            assert_eq!(
                item.kind == Kind::Async,
                ASYNC_RESULT_TYPES.contains(&item.result_type.as_str())
            );
        }
    }

    #[test]
    fn write_then_read_round_trips() {
        let entries = generate(&sample_columns(true), 3, 30).unwrap();
        let dir = tempfile::tempdir().unwrap();
        let manifest = write_corpus(dir.path(), 3, 30, &entries).unwrap();
        assert_eq!(manifest.entries.len(), entries.len());
        assert_eq!(read_corpus(dir.path()).unwrap(), entries);
    }

    #[test]
    fn empty_metadata_is_rejected() {
        assert!(generate(&[], 1, 5).is_err());
    }
}
