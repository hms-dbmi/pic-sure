//! `POST /search` and `GET /search/values/`.

use std::collections::HashMap;
use std::fmt::Write as _;

use axum::body::Bytes;
use axum::extract::{Query as QueryParams, State};
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};
use hpds_format::{ConceptMeta, ConceptType, java_double_to_string};
use serde_json::Value;

use crate::envelope::read_envelope;
use crate::error::ApiError;
use crate::state::AppState;

const JSON: &str = "application/json";

/// `POST /search`: the concepts matching the request's `query`, as `SearchResults`.
///
/// The term is `query` as Java's `toString` writes it, lowercased. A concept matches when its
/// lowercased path contains the term, or when it is categorical and one of its lowercased
/// category values equals the term. The body is
/// `{"results":{"phenotypes":{<path>:<ColumnMeta>...},"info":{}},"searchQuery":<term as sent>}`.
///
/// With a null or missing `query`, Java serializes its whole dictionary's entry set, so
/// `phenotypes` is an array of one-entry objects `{<path>:<ColumnMeta>}` for every concept and
/// `searchQuery` is empty. That shape is reproduced.
pub async fn search(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let mut fields = read_envelope(&body)?;
    let engine = state.engine()?;
    let concepts = engine.store().index().concepts();
    let mut out = String::from(r#"{"results":{"phenotypes":"#);
    let search_query = match fields.remove("query") {
        None | Some(Value::Null) => {
            out.push('[');
            for (i, concept) in concepts.iter().enumerate() {
                if i > 0 {
                    out.push(',');
                }
                out.push('{');
                push_string(&mut out, &concept.path);
                out.push(':');
                push_column_meta(&mut out, concept);
                out.push('}');
            }
            out.push(']');
            String::new()
        }
        Some(term) => {
            let term = java_to_string(&term);
            let needle = term.to_lowercase();
            out.push('{');
            let mut first = true;
            for concept in concepts.iter().filter(|c| matches(c, &needle)) {
                if !first {
                    out.push(',');
                }
                first = false;
                push_string(&mut out, &concept.path);
                out.push(':');
                push_column_meta(&mut out, concept);
            }
            out.push('}');
            term
        }
    };
    out.push_str(r#","info":{}},"searchQuery":"#);
    push_string(&mut out, &search_query);
    out.push('}');
    Ok(([(CONTENT_TYPE, JSON)], out).into_response())
}

fn matches(concept: &ConceptMeta, needle: &str) -> bool {
    concept.path.to_lowercase().contains(needle)
        || (concept.concept_type == ConceptType::Categorical
            && concept
                .category_values
                .iter()
                .flatten()
                .any(|value| value.to_lowercase() == needle))
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

/// Writes `concept` as Jackson writes `ColumnMeta`: fields in declaration order, `null`
/// fields omitted (`@JsonInclude(NON_NULL)`), and the `@JsonIgnore` and non-bean fields
/// (`widthInBytes`, `columnOffset`, the observation offsets, `hasTimestamp`) left out.
///
/// `timestampMin` and `timestampMax` are left out too. Java writes them only when set, and
/// no Java loader sets them, so Java's search never carries them; this store does record
/// them, and writing them would make every timestamped concept differ from Java.
/// Doubles are written as Java's `Double.toString` writes them, with NaN and the infinities
/// as strings, as Jackson writes them by default.
pub fn push_column_meta(out: &mut String, concept: &ConceptMeta) {
    out.push_str(r#"{"name":"#);
    push_string(out, &concept.path);
    let _ = write!(
        out,
        r#","categorical":{}"#,
        concept.concept_type == ConceptType::Categorical
    );
    if let Some(values) = &concept.category_values {
        out.push_str(r#","categoryValues":["#);
        for (i, value) in values.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            push_string(out, value);
        }
        out.push(']');
    }
    for (name, value) in [("min", concept.min), ("max", concept.max)] {
        if let Some(value) = value {
            let _ = write!(out, r#","{name}":"#);
            push_double(out, value);
        }
    }
    let _ = write!(
        out,
        r#","observationCount":{},"patientCount":{}"#,
        concept.observation_count, concept.patient_count
    );
    out.push('}');
}

fn push_double(out: &mut String, value: f64) {
    let text = java_double_to_string(value);
    if value.is_finite() {
        out.push_str(&text);
    } else {
        push_string(out, &text);
    }
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
