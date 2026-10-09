use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value, json};

use crate::column_meta::{ColumnMeta, is_genomic_path};
use crate::rng::Rng;
use crate::{BenchError, Result};

/// Concept path that carries consent values.
pub const CONSENTS_PATH: &str = "\\_consents\\";

/// Concept path that carries genomic consent values.
pub const TOPMED_CONSENTS_PATH: &str = "\\_topmed_consents\\";

/// Concept path that carries harmonized dataset consent values.
pub const HARMONIZED_CONSENT_PATH: &str = "\\_harmonized_consent\\";

/// Concept path that carries study-level consent values.
pub const STUDIES_CONSENTS_PATH: &str = "\\_studies_consents\\";

/// Result type label of search entries.
pub const SEARCH_RESULT_TYPE: &str = "SEARCH";

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

const SHAPES: [&str; 9] = [
    "categorical",
    "numeric-range",
    "required",
    "any-record-of",
    "nested",
    "conjunction",
    "disjunction",
    "authorization",
    "authorization-multi",
];

const AUTH_FALLBACK_MAX_CATEGORIES: usize = 12;

/// How a corpus entry is executed.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Kind {
    /// One `POST /v3/query/sync` round trip.
    Sync,
    /// Submit, poll status, fetch result.
    Async,
    /// One `POST /v3/search` round trip.
    Search,
    /// `POST /v3/query/{id}/status` for an id the server never issued.
    UnknownStatus,
    /// `POST /v3/query/{id}/result` for an id the server never issued.
    UnknownResult,
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
    /// Text sent verbatim as the body instead of `request`, for payloads that are not valid JSON.
    #[serde(default, rename = "rawBody", skip_serializing_if = "Option::is_none")]
    pub raw_body: Option<String>,
    /// Whether the entry probes an invalid request. Load mode skips these.
    #[serde(default, rename = "errorPath", skip_serializing_if = "is_false")]
    pub error_path: bool,
}

fn is_false(value: &bool) -> bool {
    !*value
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
    topmed: Option<ColumnMeta>,
    harmonized: Option<ColumnMeta>,
    studies: Option<ColumnMeta>,
    auth_fallback: Option<ColumnMeta>,
}

const CONSENT_PATHS: [&str; 4] = [
    CONSENTS_PATH,
    TOPMED_CONSENTS_PATH,
    HARMONIZED_CONSENT_PATH,
    STUDIES_CONSENTS_PATH,
];

fn consent_column(usable: &[ColumnMeta], path: &str) -> Option<ColumnMeta> {
    usable
        .iter()
        .find(|c| c.name == path && !c.categories.is_empty())
        .cloned()
}

fn fallback_authorization_column(categorical: &[ColumnMeta]) -> Option<ColumnMeta> {
    let few: Vec<&ColumnMeta> = categorical
        .iter()
        .filter(|c| c.categories.len() <= AUTH_FALLBACK_MAX_CATEGORIES)
        .collect();
    let pool: Vec<&ColumnMeta> = if few.is_empty() {
        categorical.iter().collect()
    } else {
        few
    };
    pool.into_iter()
        .max_by(|a, b| {
            a.patient_count
                .cmp(&b.patient_count)
                .then(b.categories.len().cmp(&a.categories.len()))
                .then(b.name.cmp(&a.name))
        })
        .cloned()
}

impl Pools {
    fn from(columns: &[ColumnMeta]) -> Result<Self> {
        let usable: Vec<ColumnMeta> = columns
            .iter()
            .filter(|c| !is_genomic_path(&c.name) && c.patient_count > 0)
            .cloned()
            .collect();
        let consents = consent_column(&usable, CONSENTS_PATH);
        let topmed = consent_column(&usable, TOPMED_CONSENTS_PATH);
        let harmonized = consent_column(&usable, HARMONIZED_CONSENT_PATH);
        let studies = consent_column(&usable, STUDIES_CONSENTS_PATH);
        let regular: Vec<ColumnMeta> = usable
            .into_iter()
            .filter(|c| !CONSENT_PATHS.iter().any(|p| c.name.starts_with(p)))
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
        let auth_fallback = fallback_authorization_column(&categorical);
        Ok(Self {
            categorical,
            numeric,
            all,
            consents,
            topmed,
            harmonized,
            studies,
            auth_fallback,
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

fn concept_nodes(path: &str) -> Vec<&str> {
    path.split('\\').filter(|node| !node.is_empty()).collect()
}

fn any_record_of_prefix(rng: &mut Rng, pools: &Pools) -> String {
    let Some(column) = rng.pick(&pools.all) else {
        return "\\".to_owned();
    };
    let nodes = concept_nodes(&column.name);
    if nodes.is_empty() {
        return column.name.clone();
    }
    let depth = rng.between(1, nodes.len());
    format!("\\{}\\", nodes[..depth].join("\\"))
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
            let prefix = any_record_of_prefix(rng, pools);
            Value::Object(filter("ANY_RECORD_OF", &prefix))
        }
        "nested" => {
            let depth = rng.between(2, 3);
            nested(rng, pools, depth)
        }
        "conjunction" => subquery("AND", leaves(rng, pools)),
        "disjunction" => subquery("OR", leaves(rng, pools)),
        _ => {
            let clauses = leaves(rng, pools);
            subquery(random_operator(rng), clauses)
        }
    }
}

fn leaves(rng: &mut Rng, pools: &Pools) -> Vec<Value> {
    let width = rng.between(2, 3);
    (0..width).map(|_| leaf(rng, pools)).collect()
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
        raw_body: None,
        error_path: false,
    }
}

fn error_entry(name: &str, kind: Kind, result_type: &str, request: Value) -> CorpusEntry {
    CorpusEntry {
        name: format!("error-{name}"),
        kind,
        result_type: result_type.to_owned(),
        request,
        raw_body: None,
        error_path: true,
    }
}

fn search_entry(name: String, term: &str) -> CorpusEntry {
    CorpusEntry {
        name: format!("search-{name}"),
        kind: Kind::Search,
        result_type: SEARCH_RESULT_TYPE.to_owned(),
        request: json!({"query": term, "resourceUUID": null}),
        raw_body: None,
        error_path: false,
    }
}

fn query_envelope(query: Value) -> Value {
    json!({"query": query, "resourceUUID": null})
}

fn error_entries(pools: &Pools) -> Vec<CorpusEntry> {
    let concept = pools
        .categorical
        .first()
        .or_else(|| pools.all.first())
        .cloned()
        .unwrap_or_else(|| pools.all[0].clone());
    let value = concept.categories.first().cloned().unwrap_or_default();
    let path = concept.name.clone();
    let with_clause =
        |clause: Value, result_type: &str| envelope(&[], None, Some(clause), result_type);
    let empty_filter = Value::Object(filter("FILTER", &path));
    let mixed_filter = {
        let mut map = filter("FILTER", &path);
        map.insert("values".into(), json!([value]));
        map.insert("min".into(), json!(0.0));
        map.insert("max".into(), json!(1.0));
        Value::Object(map)
    };
    let required_with_values = {
        let mut map = filter("REQUIRED", &path);
        map.insert("values".into(), json!([value]));
        Value::Object(map)
    };
    let empty_subquery = subquery("AND", Vec::new());
    let mut unknown_field = with_clause(Value::Object(filter("REQUIRED", &path)), "COUNT");
    unknown_field["query"]["bogusField"] = json!(1);
    let mut unknown_field_async = unknown_field.clone();
    unknown_field_async["query"]["expectedResultType"] = json!("PATIENTS");
    let genomic = json!({
        "genomicFilters": [{"key": "Gene_with_variant", "values": ["APOE"]}],
        "expectedResultType": "COUNT"
    });
    let mut malformed_body = error_entry("malformed-body", Kind::Sync, "COUNT", Value::Null);
    malformed_body.raw_body = Some("{\"query\": ".to_owned());
    vec![
        error_entry(
            "filter-without-values-or-range",
            Kind::Sync,
            "COUNT",
            with_clause(empty_filter.clone(), "COUNT"),
        ),
        error_entry(
            "filter-without-values-or-range-async",
            Kind::Async,
            "PATIENTS",
            with_clause(empty_filter, "PATIENTS"),
        ),
        error_entry(
            "filter-values-with-range",
            Kind::Sync,
            "COUNT",
            with_clause(mixed_filter, "COUNT"),
        ),
        error_entry(
            "required-with-values",
            Kind::Sync,
            "COUNT",
            with_clause(required_with_values, "COUNT"),
        ),
        error_entry(
            "empty-subquery",
            Kind::Sync,
            "COUNT",
            with_clause(empty_subquery.clone(), "COUNT"),
        ),
        error_entry(
            "empty-subquery-async",
            Kind::Async,
            "PATIENTS",
            with_clause(empty_subquery, "PATIENTS"),
        ),
        error_entry(
            "patients-on-sync-endpoint",
            Kind::Sync,
            "PATIENTS",
            envelope(&[], None, None, "PATIENTS"),
        ),
        error_entry(
            "dataframe-on-sync-endpoint",
            Kind::Sync,
            "DATAFRAME",
            envelope(&[], None, None, "DATAFRAME"),
        ),
        error_entry(
            "unknown-async-id-status",
            Kind::UnknownStatus,
            "UNKNOWN_ID_STATUS",
            query_envelope(Value::Null),
        ),
        error_entry(
            "unknown-async-id-result",
            Kind::UnknownResult,
            "UNKNOWN_ID_RESULT",
            query_envelope(Value::Null),
        ),
        error_entry(
            "malformed-query-string",
            Kind::Sync,
            "COUNT",
            query_envelope(json!("{\"expectedResultType\": ")),
        ),
        error_entry(
            "malformed-query-string-async",
            Kind::Async,
            "PATIENTS",
            query_envelope(json!("not json at all")),
        ),
        malformed_body,
        error_entry("unknown-field", Kind::Sync, "COUNT", unknown_field),
        error_entry(
            "unknown-field-async",
            Kind::Async,
            "PATIENTS",
            unknown_field_async,
        ),
        error_entry(
            "genomic-filters-without-genotype-data",
            Kind::Sync,
            "COUNT",
            query_envelope(genomic),
        ),
    ]
}

fn search_terms(rng: &mut Rng, pools: &Pools) -> Vec<(String, String)> {
    let mut terms = Vec::new();
    for index in 0..4 {
        if let Some(column) = rng.pick(&pools.all) {
            let nodes = concept_nodes(&column.name);
            let tail = &nodes[nodes.len().saturating_sub(2)..];
            if let Some(node) = rng.pick(tail) {
                let term = if index % 2 == 1 {
                    node.to_uppercase()
                } else {
                    (*node).to_owned()
                };
                terms.push((format!("path-{index}"), term));
            }
        }
    }
    for index in 0..4 {
        if let Some(column) = rng.pick(&pools.categorical)
            && let Some(category) = rng.pick(&column.categories)
        {
            let term = if index % 2 == 1 {
                category.to_lowercase()
            } else {
                category.clone()
            };
            terms.push((format!("value-{index}"), term));
        }
    }
    if let Some(studies) = &pools.studies {
        terms.push((
            "studies-consents-path".to_owned(),
            "studies_consents".to_owned(),
        ));
        if let Some(category) = rng.pick(&studies.categories) {
            terms.push(("studies-consents-value".to_owned(), category.clone()));
        }
    }
    terms.push(("no-match".to_owned(), "no-such-concept-xyzzy".to_owned()));
    terms
}

/// Generates a deterministic corpus from column metadata.
///
/// `size` entries are produced by rotating over every filter shape and every result type, so the
/// first 63 entries already cover each combination. Fixed entries come first: an empty query,
/// unknown concepts, a set of invalid requests flagged as error paths, and `POST /search`
/// entries, one of them with a null term (every concept, as an array). The same inputs always
/// give the same entries.
///
/// Authorization entries filter on `\_consents\` when the metadata has it, with
/// `\_topmed_consents\` and `\_harmonized_consent\` added in the multi-filter variant when those
/// exist. Without `\_consents\` they filter on the categorical concept with few categories that
/// covers the most patients. ANY_RECORD_OF prefixes are cut at a uniformly chosen depth from the
/// study node down to the leaf.
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

    entries.extend(error_entries(&pools));
    for (label, term) in search_terms(&mut rng, &pools) {
        entries.push(search_entry(label, &term));
    }
    entries.push(CorpusEntry {
        request: json!({"query": null, "resourceUUID": null}),
        ..search_entry("null-term".into(), "")
    });

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

fn authorization_filter(rng: &mut Rng, column: &ColumnMeta) -> Value {
    let count = rng.between(1, column.categories.len().min(3));
    let mut values = rng.sample(&column.categories, count);
    values.sort();
    json!({"conceptPath": column.name, "values": values})
}

fn authorization_for<'a>(rng: &mut Rng, pools: &Pools, shape: &'a str) -> (&'a str, Option<Value>) {
    if shape != "authorization" && shape != "authorization-multi" {
        return (shape, None);
    }
    let primary = pools.consents.as_ref().or(pools.auth_fallback.as_ref());
    let Some(primary) = primary else {
        return ("subquery", None);
    };
    let mut columns = vec![primary];
    if shape == "authorization-multi" && pools.consents.is_some() {
        columns.extend(pools.topmed.iter().chain(pools.harmonized.iter()));
    }
    let label = if columns.len() > 1 {
        "authorization-multi"
    } else {
        "authorization"
    };
    let filters: Vec<Value> = columns
        .into_iter()
        .map(|column| authorization_filter(rng, column))
        .collect();
    (label, Some(Value::Array(filters)))
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
            columns.push(column(TOPMED_CONSENTS_PATH, &["phs1.c1", "phs3.c1"], None));
            columns.push(column(HARMONIZED_CONSENT_PATH, &["phs9.c1"], None));
            columns.push(column(STUDIES_CONSENTS_PATH, &["phs1", "phs2"], None));
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
            "disjunction",
            "authorization",
            "authorization-multi",
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

    fn auth_filters(entries: &[CorpusEntry]) -> Vec<&Vec<Value>> {
        entries
            .iter()
            .filter_map(|e| e.request["query"]["authorizationFilters"].as_array())
            .collect()
    }

    #[test]
    fn authorization_values_come_from_consent_categories() {
        let entries = generate(&sample_columns(true), 2, 100).unwrap();
        let all = auth_filters(&entries);
        assert!(!all.is_empty());
        let allowed = |path: &str| -> Vec<&str> {
            match path {
                CONSENTS_PATH => vec!["phs1.c1", "phs1.c2", "phs2.c1"],
                TOPMED_CONSENTS_PATH => vec!["phs1.c1", "phs3.c1"],
                HARMONIZED_CONSENT_PATH => vec!["phs9.c1"],
                other => panic!("unexpected authorization path {other}"),
            }
        };
        for filters in &all {
            assert_eq!(filters[0]["conceptPath"], CONSENTS_PATH);
            for filter in filters.iter() {
                let allowed = allowed(filter["conceptPath"].as_str().unwrap());
                for value in filter["values"].as_array().unwrap() {
                    assert!(allowed.contains(&value.as_str().unwrap()));
                }
            }
        }
        assert!(all.iter().any(|f| f.len() == 3));
        assert!(all.iter().any(|f| f.len() == 1));
    }

    #[test]
    fn authorization_falls_back_to_a_wide_categorical_concept() {
        let entries = generate(&sample_columns(false), 2, 100).unwrap();
        let all = auth_filters(&entries);
        assert!(!all.is_empty());
        for filters in all {
            assert_eq!(filters.len(), 1);
            assert!(
                filters[0]["conceptPath"]
                    .as_str()
                    .unwrap()
                    .starts_with('\\')
            );
            assert_ne!(filters[0]["conceptPath"], CONSENTS_PATH);
        }
    }

    #[test]
    fn fallback_prefers_few_categories_and_many_patients() {
        let mut wide = column("\\a\\WIDE\\", &["x", "y", "z"], None);
        wide.patient_count = 100;
        let mut narrow = column("\\a\\NARROW\\", &["x", "y"], None);
        narrow.patient_count = 100;
        let mut sparse = column("\\a\\SPARSE\\", &["x"], None);
        sparse.patient_count = 5;
        let mut huge = column("\\a\\HUGE\\", &[], None);
        huge.categories = (0..50).map(|i| i.to_string()).collect();
        huge.patient_count = 1000;
        let chosen = fallback_authorization_column(&[wide, narrow, sparse, huge]).unwrap();
        assert_eq!(chosen.name, "\\a\\NARROW\\");
    }

    #[test]
    fn any_record_of_prefixes_span_every_depth_including_study_nodes() {
        let entries = generate(&sample_columns(false), 6, 400).unwrap();
        let mut depths = BTreeSet::new();
        for item in &entries {
            let text = item.request.to_string();
            if !text.contains("ANY_RECORD_OF") {
                continue;
            }
            let path = item.request["query"]["phenotypicClause"]["conceptPath"]
                .as_str()
                .or_else(|| {
                    item.request["query"]["phenotypicClause"]["phenotypicClauses"][0]["conceptPath"]
                        .as_str()
                })
                .unwrap();
            assert!(path.starts_with('\\') && path.ends_with('\\'));
            depths.insert(concept_nodes(path).len());
        }
        assert!(depths.contains(&1), "{depths:?}");
        assert!(depths.contains(&2), "{depths:?}");
        assert!(depths.contains(&3), "{depths:?}");
    }

    #[test]
    fn error_entries_cover_every_invalid_request_and_are_flagged() {
        let entries = generate(&sample_columns(false), 1, 20).unwrap();
        let errors: Vec<&CorpusEntry> = entries.iter().filter(|e| e.error_path).collect();
        for needle in [
            "filter-without-values-or-range",
            "filter-values-with-range",
            "empty-subquery",
            "patients-on-sync-endpoint",
            "unknown-async-id-status",
            "unknown-async-id-result",
            "malformed-query-string",
            "malformed-body",
            "unknown-field",
            "genomic-filters",
            "required-with-values",
        ] {
            assert!(
                errors.iter().any(|e| e.name.contains(needle)),
                "missing {needle}"
            );
        }
        assert!(errors.iter().all(|e| e.name.starts_with("error-")));
        assert!(errors.iter().any(|e| e.raw_body.is_some()));
        assert!(
            errors
                .iter()
                .any(|e| e.kind == Kind::Sync && e.result_type == "PATIENTS")
        );
        assert!(
            entries
                .iter()
                .filter(|e| !e.error_path)
                .all(|e| e.raw_body.is_none())
        );
    }

    #[test]
    fn search_entries_use_path_value_and_studies_consent_terms() {
        let with = generate(&sample_columns(true), 1, 5).unwrap();
        let searches: Vec<&CorpusEntry> = with.iter().filter(|e| e.kind == Kind::Search).collect();
        assert!(searches.iter().any(|e| e.name.contains("path-")));
        assert!(searches.iter().any(|e| e.name.contains("value-")));
        assert!(
            searches
                .iter()
                .any(|e| e.name.contains("studies-consents-path"))
        );
        assert!(
            searches
                .iter()
                .any(|e| e.name.contains("studies-consents-value"))
        );
        assert!(
            searches
                .iter()
                .all(|e| e.request["query"].is_string() || e.name == "search-null-term")
        );
        assert!(
            searches
                .iter()
                .any(|e| e.name == "search-null-term" && e.request["query"].is_null())
        );
        let without = generate(&sample_columns(false), 1, 5).unwrap();
        assert!(!without.iter().any(|e| e.name.contains("studies-consents")));
        assert!(without.iter().any(|e| e.kind == Kind::Search));
    }

    #[test]
    fn numeric_bounds_stay_inside_column_range() {
        let entries = generate(&sample_columns(false), 4, 200).unwrap();
        let text = serde_json::to_string(&entries).unwrap();
        assert!(text.contains("\"min\""));
        for item in entries.iter().filter(|e| !e.error_path) {
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
        for item in entries
            .iter()
            .filter(|e| !e.error_path && e.kind != Kind::Search)
        {
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
