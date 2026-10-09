//! `POST /search` and `GET /search/values/`.

use std::collections::HashMap;
use std::sync::Arc;

use axum::body::Bytes;
use axum::extract::{Query as QueryParams, State};
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};
use hpds_format::java_double_to_string;
use hpds_query::SummaryColumnMeta;
use serde_json::Value;

use crate::envelope::read_envelope;
use crate::error::ApiError;
use crate::state::AppState;

const JSON: &str = "application/json";

/// The merged dictionary prepared for search once at startup: lowercase copies of every
/// concept path and categorical value, so a search compares without allocating, and each
/// concept's rendered `"<path>":<SummaryColumnMeta>` member, so a search only concatenates.
///
/// Java lowercases the path and the term with `Locale.ENGLISH` and the category values with
/// `String.toLowerCase()`, which uses the JVM's default locale. This uses Rust's
/// `str::to_lowercase`, the Unicode default case mapping (including the final-sigma rule, as
/// Java does) regardless of locale, which is what `Locale.ENGLISH` does. The category values
/// therefore agree with Java except under a default locale with its own rules for `I` and
/// `i`: Turkish or Azerbaijani map `I` to the dotless `ı`, and Lithuanian adds combining
/// dots. A container normally runs with an English or root default locale.
#[derive(Debug, Default)]
pub struct SearchIndex {
    entries: Vec<SearchEntry>,
}

#[derive(Debug)]
struct SearchEntry {
    path: String,
    categories: Vec<String>,
    member: String,
}

impl SearchIndex {
    /// The index over `dictionary`, in the same order.
    ///
    /// Each concept is rendered by [`SummaryColumnMeta::write_json`] with `timestampMin` and
    /// `timestampMax` written as `null`. No Java loader sets a concept's timestamps, so the
    /// Java service always writes them as `null`; the Rust loaders record them, and writing
    /// them would make every concept with a `DATETIME` column differ from Java.
    pub fn build(dictionary: &[SummaryColumnMeta]) -> Self {
        let entries = dictionary
            .iter()
            .map(|concept| {
                let mut member = String::new();
                push_string(&mut member, &concept.name);
                member.push(':');
                SummaryColumnMeta {
                    timestamp_min: None,
                    timestamp_max: None,
                    ..concept.clone()
                }
                .write_json(&mut member);
                SearchEntry {
                    path: concept.name.to_lowercase(),
                    categories: if concept.categorical {
                        concept
                            .category_values
                            .iter()
                            .map(|value| value.to_lowercase())
                            .collect()
                    } else {
                        Vec::new()
                    },
                    member,
                }
            })
            .collect();
        SearchIndex { entries }
    }

    fn matches(entry: &SearchEntry, needle: &str) -> bool {
        entry.path.contains(needle) || entry.categories.iter().any(|value| value == needle)
    }

    /// The `SearchResults` body of [`search`] for `term`, `None` for a null or missing
    /// `query`.
    pub fn render(&self, term: Option<String>) -> String {
        let mut out = String::from(r#"{"results":{"phenotypes":"#);
        let search_query = match term {
            None => {
                out.push('[');
                for (i, entry) in self.entries.iter().enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    out.push('{');
                    out.push_str(&entry.member);
                    out.push('}');
                }
                out.push(']');
                String::new()
            }
            Some(term) => {
                let needle = term.to_lowercase();
                out.push('{');
                let matching = self
                    .entries
                    .iter()
                    .filter(|entry| Self::matches(entry, &needle));
                for (i, entry) in matching.enumerate() {
                    if i > 0 {
                        out.push(',');
                    }
                    out.push_str(&entry.member);
                }
                out.push('}');
                term
            }
        };
        out.push_str(r#","info":{}},"searchQuery":"#);
        push_string(&mut out, &search_query);
        out.push('}');
        out
    }
}

/// `POST /search`: the concepts of the merged dictionary matching the request's `query`, as
/// `SearchResults`. Not scoped by consent, as in Java: a caller sees concepts and category
/// values of every partition.
///
/// The term is `query` as Java's `toString` writes it, lowercased. A concept matches when its
/// lowercased path contains the term, or when it is categorical and one of its lowercased
/// category values equals the term; the empty string therefore matches every concept. The
/// body is `{"results":{"phenotypes":{<path>:<SummaryColumnMeta>...},"info":{}},
/// "searchQuery":<term as sent>}`, each concept as [`SearchIndex::build`] renders it. The
/// scan and the rendering run on tokio's blocking pool.
///
/// With a null or missing `query`, Java serializes its whole dictionary's entry set, so
/// `phenotypes` is an array of one-entry objects `{<path>:<SummaryColumnMeta>}` for every
/// concept and `searchQuery` is empty. That shape is reproduced.
///
/// Concepts come out in Java string order of their paths and category values in Java string
/// order. Java's order of both is unspecified: the dictionary is an immutable map whose
/// iteration order changes with every JVM start, the matches are collected into a `HashMap`,
/// and the category values are a `HashSet`.
pub async fn search(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let mut fields = read_envelope(&body)?;
    state.engine()?;
    let index = Arc::clone(state.search_index());
    let term = match fields.remove("query") {
        None | Some(Value::Null) => None,
        Some(term) => Some(java_to_string(&term)),
    };
    let out = tokio::task::spawn_blocking(move || index.render(term))
        .await
        .map_err(|error| {
            tracing::error!(%error, "search task failed");
            ApiError::internal()
        })?;
    Ok(([(CONTENT_TYPE, JSON)], out).into_response())
}

/// `GET /search/values/`: genomic concept values. This engine holds no genomic data, so the
/// answer is always an empty `PaginatedSearchResult`, `{"results":[],"page":<page>,"total":0}`.
///
/// Java requires the `genomicConceptPath`, `query`, `page` and `size` parameters; a missing
/// one, a page or size that is not an integer, or one below 1 is a 400.
pub async fn search_values(
    QueryParams(params): QueryParams<HashMap<String, String>>,
) -> Result<Response, ApiError> {
    for name in ["genomicConceptPath", "query"] {
        if !params.contains_key(name) {
            return Err(ApiError::bad_request(format!(
                "the {name} parameter is required"
            )));
        }
    }
    let page = positive_param(&params, "page", "Page")?;
    positive_param(&params, "size", "Size")?;
    let body = format!(r#"{{"results":[],"page":{page},"total":0}}"#);
    Ok(([(CONTENT_TYPE, JSON)], body).into_response())
}

fn positive_param(
    params: &HashMap<String, String>,
    name: &str,
    label: &str,
) -> Result<i32, ApiError> {
    let value = params
        .get(name)
        .ok_or_else(|| ApiError::bad_request(format!("the {name} parameter is required")))?;
    let number: i32 = value
        .trim()
        .parse()
        .map_err(|_| ApiError::bad_request(format!("the {name} parameter must be an integer")))?;
    if number < 1 {
        return Err(ApiError::bad_request(format!(
            "{label} must be greater than 0"
        )));
    }
    Ok(number)
}

fn push_string(out: &mut String, value: &str) {
    out.push_str(&Value::String(value.to_owned()).to_string());
}

/// `Object.toString` of the value Jackson binds an untyped JSON value to: a string as is, an
/// integer as `Integer` or `Long` writes it, a fraction as `Double.toString` writes it, an
/// object as `LinkedHashMap.toString` (`{a=1, b=[x, y]}`), an array as `ArrayList.toString`,
/// and null as `null`.
pub fn java_to_string(value: &Value) -> String {
    match value {
        Value::Null => "null".to_owned(),
        Value::Bool(b) => b.to_string(),
        Value::String(s) => s.clone(),
        Value::Number(n) => match (n.as_i64(), n.as_u64(), n.as_f64()) {
            (Some(i), _, _) => i.to_string(),
            (None, Some(u), _) => u.to_string(),
            (None, None, Some(f)) => java_double_to_string(f),
            (None, None, None) => n.to_string(),
        },
        Value::Array(items) => {
            let parts: Vec<String> = items.iter().map(java_to_string).collect();
            format!("[{}]", parts.join(", "))
        }
        Value::Object(fields) => {
            let parts: Vec<String> = fields
                .iter()
                .map(|(k, v)| format!("{k}={}", java_to_string(v)))
                .collect();
            format!("{{{}}}", parts.join(", "))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn to_string_follows_java_collections() {
        assert_eq!(
            java_to_string(&json!({"a": 1, "b": ["x", 2.5]})),
            "{a=1, b=[x, 2.5]}"
        );
        assert_eq!(java_to_string(&json!(5)), "5");
        assert_eq!(java_to_string(&json!(1e10)), "1.0E10");
        assert_eq!(java_to_string(&json!("Age")), "Age");
    }
}
