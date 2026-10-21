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
use hpds_query::{InfoSearchEntry, SummaryColumnMeta};
use serde_json::Value;

use crate::encoding::{GZIP_LEVEL, MIN_GZIP_BYTES, STORED_GZIP_LEVEL, accepts_gzip, gzip};
use crate::envelope::read_envelope;
use crate::error::ApiError;
use crate::genomic::GenomicStatus;
use crate::state::AppState;

const JSON: &str = "application/json";

const BODY_START: &str = r#"{"results":{"phenotypes":"#;
const BODY_INFO: &str = r#","info":{}},"searchQuery":"#;
const INFO_START: &str = r#","info":"#;
const INFO_END: &str = r#"},"searchQuery":"#;

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
    /// `query`, with an empty `info` block. The answers to `None` and to the empty term are
    /// the ones built at startup, shared rather than copied.
    pub fn render(&self, term: Option<String>) -> Bytes {
        self.render_with_info(term, None)
    }

    /// [`SearchIndex::render`] with `info`, the rendered JSON object of the genomic `info`
    /// block ([`render_info`]), in place of the empty one. A null term never has one.
    pub fn render_with_info(&self, term: Option<String>, info: Option<&str>) -> Bytes {
        match (term, info) {
            (None, _) => self.whole_dictionary.clone(),
            (Some(term), None) if term.is_empty() => self.every_concept.clone(),
            (Some(term), info) => Bytes::from(self.render_matches(&term, info.unwrap_or("{}"))),
        }
    }

    fn render_matches(&self, term: &str, info: &str) -> Vec<u8> {
        let needle = term.to_lowercase();
        let matching: Vec<&SearchEntry> = self
            .entries
            .iter()
            .filter(|entry| Self::matches(entry, &needle))
            .collect();
        let mut query = String::new();
        push_string(&mut query, term);
        let members: usize = matching.iter().map(|entry| entry.member.len() + 1).sum();
        let mut out = Vec::with_capacity(
            BODY_START.len() + BODY_INFO.len() + info.len() + members + query.len() + 3,
        );
        out.extend_from_slice(BODY_START.as_bytes());
        out.push(b'{');
        for (i, entry) in matching.iter().enumerate() {
            if i > 0 {
                out.push(b',');
            }
            out.extend_from_slice(&self.every_concept[entry.member.clone()]);
        }
        out.push(b'}');
        out.extend_from_slice(INFO_START.as_bytes());
        out.extend_from_slice(info.as_bytes());
        out.extend_from_slice(INFO_END.as_bytes());
        out.extend_from_slice(query.as_bytes());
        out.push(b'}');
        out
    }

    /// The answer to `term` as [`SearchIndex::render`] writes it, gzipped when `gzip_accepted`
    /// is set and the body is at least [`MIN_GZIP_BYTES`] long. The two answers built at startup
    /// are compressed at [`STORED_GZIP_LEVEL`] the first time a client asks and kept; any
    /// other answer is compressed at [`GZIP_LEVEL`].
    pub fn answer(&self, term: Option<String>, gzip_accepted: bool) -> SearchBody {
        self.answer_with_info(term, None, gzip_accepted)
    }

    /// [`SearchIndex::answer`] with the genomic `info` block `info`
    /// ([`SearchIndex::render_with_info`]). An answer with an `info` block is never one of
    /// the two stored ones.
    pub fn answer_with_info(
        &self,
        term: Option<String>,
        info: Option<&str>,
        gzip_accepted: bool,
    ) -> SearchBody {
        let stored = match (&term, info) {
            (None, _) => Some(&self.whole_dictionary_gzip),
            (Some(term), None) if term.is_empty() => Some(&self.every_concept_gzip),
            _ => None,
        };
        let bytes = self.render_with_info(term, info);
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

/// The genomic `info` block as Java's `TreeMap` of `ImmutableMap`s writes it:
/// `{<key>:{"description":...,"values":[...],"continuous":...},...}` in the entries' order.
pub fn render_info(entries: &[InfoSearchEntry]) -> String {
    let mut out = String::from("{");
    for (i, entry) in entries.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        push_string(&mut out, &entry.key);
        out.push_str(r#":{"description":"#);
        push_string(&mut out, &entry.description);
        out.push_str(r#","values":["#);
        for (j, value) in entry.values.iter().enumerate() {
            if j > 0 {
                out.push(',');
            }
            push_string(&mut out, value);
        }
        out.push_str(r#"],"continuous":"#);
        out.push_str(if entry.continuous { "true" } else { "false" });
        out.push('}');
    }
    out.push('}');
    out
}

/// `POST /search`: the concepts of the merged dictionary matching the request's `query`, as
/// `SearchResults`, and the genomic INFO columns matching it. Not scoped by consent, as in
/// Java: a caller sees concepts, category values and INFO values of every partition and
/// every genomic node.
///
/// The term is `query` as Java's `toString` writes it, lowercased. A concept matches when its
/// lowercased path contains the term, or when it is categorical and one of its lowercased
/// category values equals the term; the empty string therefore matches every concept. The
/// body is `{"results":{"phenotypes":{<path>:<SummaryColumnMeta>...},"info":{}},
/// "searchQuery":<term as sent>}`, each concept as [`SearchIndex::build`] renders it. The
/// scan, the rendering and any compression run on tokio's blocking pool.
///
/// With a genomic store served and a term given (the empty one included), `info` holds each
/// INFO column whose description or key contains the lowercased term
/// ([`hpds_query::Engine::search_info`]): its description, its values in Java's `HashSet`
/// order (none for a continuous column) and whether it is continuous, keyed in Java string
/// order. A matching categorical column some genomic node lacks is a 400, where Java fails.
/// With genomic data configured but not opened, a request with a term is a 503, since which
/// INFO columns match it cannot be known. Once the genomic store changed on disk, a term
/// that matches no INFO column in the index held in memory answers with an empty `info`,
/// and one that matches some column is a 503, since its values may no longer be read.
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
/// While `HPDS_SEARCH_GZIP` is on (it is off by default), a request whose `Accept-Encoding`
/// accepts gzip ([`accepts_gzip`]) gets a body of at least [`MIN_GZIP_BYTES`] gzipped, with
/// `Content-Encoding: gzip`, and a request that sends `Accept-Encoding` gets
/// `Vary: Accept-Encoding` on any body of that size, compressed or not. A request without
/// `Accept-Encoding`, or any request while the setting is off, gets the same bytes and
/// headers as before compression existed.
pub async fn search(
    State(state): State<AppState>,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Response, ApiError> {
    let mut fields = read_envelope(&body)?;
    let engine = Arc::clone(state.engine()?);
    let index = Arc::clone(state.search_index());
    let term = match fields.remove("query") {
        None | Some(Value::Null) => None,
        Some(term) => Some(java_to_string(&term)),
    };
    state.check_genomic(term.is_some())?;
    let with_info = term.is_some() && state.genomic_status() == GenomicStatus::Served;
    let negotiated =
        state.search_gzip() && headers.contains_key(axum::http::header::ACCEPT_ENCODING);
    let gzip_accepted = negotiated && accepts_gzip(&headers);
    let answer = tokio::task::spawn_blocking(move || {
        let info = match &term {
            Some(term) if with_info => Some(render_info(&match engine.search_info(term) {
                Err(hpds_query::QueryError::GenomicStoreChanged { .. })
                    if !engine.search_info_matches(term) =>
                {
                    Vec::new()
                }
                found => found?,
            })),
            _ => None,
        };
        Ok::<_, hpds_query::QueryError>(index.answer_with_info(
            term,
            info.as_deref(),
            gzip_accepted,
        ))
    })
    .await
    .map_err(|error| {
        tracing::error!(%error, "search task failed");
        ApiError::internal()
    })??;
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

/// `GET /search/values/`: the values of the INFO column `genomicConceptPath` containing
/// `query`, ignoring case, as Java's `PaginatedSearchResult`:
/// `{"results":[...],"page":<page>,"total":<matching values>}`.
///
/// The values are in Java's `HashSet` order ([`hpds_query::Engine::search_info_values`]) and
/// paged as Java's `Paginator` pages them, with its `int` arithmetic: page `p` of size `s`
/// is the values from `(p - 1) * s` to `p * s`, each capped at the total. Where those
/// products overflow `int` and Java's `subList` throws, the request is a 400; where they
/// overflow and still form a range, that range is answered, as Java answers it.
///
/// Java requires the `genomicConceptPath`, `query`, `page` and `size` parameters; a missing
/// one, a page or size that is not an integer, or one below 1 is a 400. A column some
/// genomic node lacks, an unknown one included, is a 400, where Java fails. Without genomic
/// data configured the answer is `{"results":[],"page":<page>,"total":0}`, as Java's no-op
/// processor gives; with genomic data configured but not served it is a 503.
pub async fn search_values(
    State(state): State<AppState>,
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
    let size = positive_param(&params, "size", "Size")?;
    state.check_genomic(true)?;
    if state.genomic_status() == GenomicStatus::Off {
        let body = format!(r#"{{"results":[],"page":{page},"total":0}}"#);
        return Ok(([(CONTENT_TYPE, JSON)], body).into_response());
    }
    let engine = Arc::clone(state.engine()?);
    let column = params["genomicConceptPath"].clone();
    let query = params["query"].clone();
    let values = tokio::task::spawn_blocking(move || engine.search_info_values(&column, &query))
        .await
        .map_err(|error| {
            tracing::error!(%error, "search values task failed");
            ApiError::internal()
        })??;
    let range = java_page(values.len(), page, size).ok_or_else(|| {
        ApiError::bad_request(format!(
            "page {page} of size {size} is outside the int range Java pages in"
        ))
    })?;
    let mut body = String::from(r#"{"results":["#);
    for (i, value) in values[range].iter().enumerate() {
        if i > 0 {
            body.push(',');
        }
        push_string(&mut body, value);
    }
    body.push_str(&format!(r#"],"page":{page},"total":{}}}"#, values.len()));
    Ok(([(CONTENT_TYPE, JSON)], body).into_response())
}

/// The range `Paginator.paginate` takes from a list of `len` values for `page` and `size`,
/// both at least 1, with Java's wrapping `int` products, or `None` where Java's
/// `List.subList` throws (a negative start, or an end before the start).
pub fn java_page(len: usize, page: i32, size: i32) -> Option<std::ops::Range<usize>> {
    let len = i32::try_from(len).unwrap_or(i32::MAX);
    let start = page.wrapping_sub(1).wrapping_mul(size).min(len);
    let end = page.wrapping_mul(size).min(len);
    if start < 0 || end < start {
        return None;
    }
    Some(start as usize..end as usize)
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
    fn pages_follow_javas_int_arithmetic() {
        assert_eq!(java_page(10, 1, 3), Some(0..3));
        assert_eq!(java_page(10, 4, 3), Some(9..10));
        assert_eq!(java_page(10, 5, 3), Some(10..10));
        assert_eq!(java_page(10, 1, i32::MAX), Some(0..10));
        assert_eq!(
            java_page(10, 2, i32::MAX),
            None,
            "page * size wraps negative, below the start"
        );
        assert_eq!(
            java_page(10, 3, 1 << 30),
            None,
            "(page - 1) * size wraps to i32::MIN"
        );
        assert_eq!(
            java_page(10, 65_537, 65_536),
            Some(0..10),
            "(page - 1) * size wraps to 0 and page * size to 65,536"
        );
    }

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
