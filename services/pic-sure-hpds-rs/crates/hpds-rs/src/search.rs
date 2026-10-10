//! `POST /search` and `GET /search/values/`.

use std::collections::HashMap;
use std::ops::Range;
use std::sync::{Arc, OnceLock};

use axum::body::Bytes;
use axum::extract::{Query as QueryParams, State};
use axum::http::header::{CONTENT_ENCODING, CONTENT_TYPE, VARY};
use axum::http::{HeaderMap, HeaderValue};
use axum::response::{IntoResponse, Response};
use hpds_format::java_double_to_string;
use hpds_query::SummaryColumnMeta;
use serde_json::Value;

use crate::encoding::{GZIP_LEVEL, MIN_GZIP_BYTES, STORED_GZIP_LEVEL, accepts_gzip, gzip};
use crate::envelope::read_envelope;
use crate::error::ApiError;
use crate::state::AppState;

const JSON: &str = "application/json";

const BODY_START: &str = r#"{"results":{"phenotypes":"#;
const BODY_INFO: &str = r#","info":{}},"searchQuery":"#;

/// The merged dictionary prepared for search once at startup: lowercase copies of every
/// concept path and categorical value, so a search compares without allocating, and the two
/// answers that match every concept, rendered whole, from which every other answer copies
/// its concepts.
///
/// Java lowercases the path and the term with `Locale.ENGLISH` and the category values with
/// `String.toLowerCase()`, which uses the JVM's default locale. This uses Rust's
/// `str::to_lowercase`, the Unicode default case mapping (including the final-sigma rule, as
/// Java does) regardless of locale, which is what `Locale.ENGLISH` does. The category values
/// therefore agree with Java except under a default locale with its own rules for `I` and
/// `i`: Turkish or Azerbaijani map `I` to the dotless `ı`, and Lithuanian adds combining
/// dots. A container normally runs with an English or root default locale.
#[derive(Debug)]
pub struct SearchIndex {
    entries: Vec<SearchEntry>,
    every_concept: Bytes,
    whole_dictionary: Bytes,
    every_concept_gzip: OnceLock<Bytes>,
    whole_dictionary_gzip: OnceLock<Bytes>,
}

#[derive(Debug)]
struct SearchEntry {
    path: String,
    categories: Vec<String>,
    member: Range<usize>,
}

/// A `/search` answer, ready to send.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SearchBody {
    /// The body: the JSON, or its gzip form when `gzipped`.
    pub bytes: Bytes,
    /// True when `bytes` is gzip.
    pub gzipped: bool,
}

impl Default for SearchIndex {
    fn default() -> Self {
        SearchIndex::build(&[])
    }
}

impl SearchIndex {
    /// The index over `dictionary`, in the same order.
    ///
    /// Each concept is rendered once, as `"<path>":<SummaryColumnMeta>` by
    /// [`SummaryColumnMeta::write_json`] with `timestampMin` and `timestampMax` written as
    /// `null`. No Java loader sets a concept's timestamps, so the Java service always writes
    /// them as `null`; the Rust loaders record them, and writing them would make every
    /// concept with a `DATETIME` column differ from Java. The rendered concepts are kept
    /// only inside the answer to the empty term, which holds them all in order.
    pub fn build(dictionary: &[SummaryColumnMeta]) -> Self {
        let mut every_concept = String::from(BODY_START);
        every_concept.push('{');
        let mut entries = Vec::with_capacity(dictionary.len());
        for (i, concept) in dictionary.iter().enumerate() {
            if i > 0 {
                every_concept.push(',');
            }
            let start = every_concept.len();
            push_string(&mut every_concept, &concept.name);
            every_concept.push(':');
            SummaryColumnMeta {
                timestamp_min: None,
                timestamp_max: None,
                ..concept.clone()
            }
            .write_json(&mut every_concept);
            entries.push(SearchEntry {
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
                member: start..every_concept.len(),
            });
        }
        every_concept.push('}');
        every_concept.push_str(BODY_INFO);
        every_concept.push_str(r#""""#);
        every_concept.push('}');
        let mut whole_dictionary = String::with_capacity(every_concept.len() + 2 * entries.len());
        whole_dictionary.push_str(BODY_START);
        whole_dictionary.push('[');
        for (i, entry) in entries.iter().enumerate() {
            if i > 0 {
                whole_dictionary.push(',');
            }
            whole_dictionary.push('{');
            whole_dictionary.push_str(&every_concept[entry.member.clone()]);
            whole_dictionary.push('}');
        }
        whole_dictionary.push(']');
        whole_dictionary.push_str(BODY_INFO);
        whole_dictionary.push_str(r#""""#);
        whole_dictionary.push('}');
        SearchIndex {
            entries,
            every_concept: Bytes::from(every_concept),
            whole_dictionary: Bytes::from(whole_dictionary),
            every_concept_gzip: OnceLock::new(),
            whole_dictionary_gzip: OnceLock::new(),
        }
    }

    fn matches(entry: &SearchEntry, needle: &str) -> bool {
        entry.path.contains(needle) || entry.categories.iter().any(|value| value == needle)
    }

    /// The `SearchResults` body of [`search`] for `term`, `None` for a null or missing
    /// `query`. The answers to `None` and to the empty term are the ones built at startup,
    /// shared rather than copied.
    pub fn render(&self, term: Option<String>) -> Bytes {
        match term {
            None => self.whole_dictionary.clone(),
            Some(term) if term.is_empty() => self.every_concept.clone(),
            Some(term) => Bytes::from(self.render_matches(&term)),
        }
    }

    fn render_matches(&self, term: &str) -> Vec<u8> {
        let needle = term.to_lowercase();
        let matching: Vec<&SearchEntry> = self
            .entries
            .iter()
            .filter(|entry| Self::matches(entry, &needle))
            .collect();
        let mut query = String::new();
        push_string(&mut query, term);
        let members: usize = matching.iter().map(|entry| entry.member.len() + 1).sum();
        let mut out =
            Vec::with_capacity(BODY_START.len() + BODY_INFO.len() + members + query.len() + 3);
        out.extend_from_slice(BODY_START.as_bytes());
        out.push(b'{');
        for (i, entry) in matching.iter().enumerate() {
            if i > 0 {
                out.push(b',');
            }
            out.extend_from_slice(&self.every_concept[entry.member.clone()]);
        }
        out.push(b'}');
        out.extend_from_slice(BODY_INFO.as_bytes());
        out.extend_from_slice(query.as_bytes());
        out.push(b'}');
        out
    }

    /// The answer to `term` as [`SearchIndex::render`] writes it, gzipped when `gzip_accepted`
    /// is set and the body is at least [`MIN_GZIP_BYTES`] long. The two answers built at startup
    /// are compressed at [`STORED_GZIP_LEVEL`] the first time a client asks and kept; any
    /// other answer is compressed at [`GZIP_LEVEL`].
    pub fn answer(&self, term: Option<String>, gzip_accepted: bool) -> SearchBody {
        let stored = match &term {
            None => Some(&self.whole_dictionary_gzip),
            Some(term) if term.is_empty() => Some(&self.every_concept_gzip),
            Some(_) => None,
        };
        let bytes = self.render(term);
        if !gzip_accepted || bytes.len() < MIN_GZIP_BYTES {
            return SearchBody {
                bytes,
                gzipped: false,
            };
        }
        let packed = match stored {
            Some(cell) => cell
                .get_or_init(|| Bytes::from(gzip(&bytes, STORED_GZIP_LEVEL)))
                .clone(),
            None => Bytes::from(gzip(&bytes, GZIP_LEVEL)),
        };
        SearchBody {
            bytes: packed,
            gzipped: true,
        }
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
/// scan, the rendering and any compression run on tokio's blocking pool.
///
/// With a null or missing `query`, Java serializes its whole dictionary's entry set, so
/// `phenotypes` is an array of one-entry objects `{<path>:<SummaryColumnMeta>}` for every
/// concept and `searchQuery` is empty. That shape is reproduced.
///
/// Concepts come out in Java string order of their paths and category values in Java string
/// order. Java's order of both is unspecified: the dictionary is an immutable map whose
/// iteration order changes with every JVM start, the matches are collected into a `HashMap`,
/// and the category values are a `HashSet`.
///
/// A request whose `Accept-Encoding` accepts gzip ([`accepts_gzip`]) gets a body of at least
/// [`MIN_GZIP_BYTES`] gzipped, with `Content-Encoding: gzip`. A request that sends
/// `Accept-Encoding` gets `Vary: Accept-Encoding` on any body of that size, compressed or
/// not. A request without `Accept-Encoding` gets the same bytes and headers as before
/// compression existed.
pub async fn search(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Response, ApiError> {
    let mut fields = read_envelope(&body)?;
    state.engine()?;
    let index = Arc::clone(state.search_index());
    let term = match fields.remove("query") {
        None | Some(Value::Null) => None,
        Some(term) => Some(java_to_string(&term)),
    };
    let negotiated = headers.contains_key(axum::http::header::ACCEPT_ENCODING);
    let gzip_accepted = accepts_gzip(&headers);
    let answer = tokio::task::spawn_blocking(move || index.answer(term, gzip_accepted))
        .await
        .map_err(|error| {
            tracing::error!(%error, "search task failed");
            ApiError::internal()
        })?;
    let varies = negotiated && (answer.gzipped || answer.bytes.len() >= MIN_GZIP_BYTES);
    let mut response = ([(CONTENT_TYPE, JSON)], answer.bytes).into_response();
    let response_headers = response.headers_mut();
    if answer.gzipped {
        response_headers.insert(CONTENT_ENCODING, HeaderValue::from_static("gzip"));
    }
    if varies {
        response_headers.insert(VARY, HeaderValue::from_static("Accept-Encoding"));
    }
    Ok(response)
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
