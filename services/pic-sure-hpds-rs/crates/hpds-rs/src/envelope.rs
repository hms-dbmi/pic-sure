//! The `QueryRequest` envelope every POST body arrives in, and the UUID helpers the routes
//! share.

use hpds_query::{Query, parse_strict};
use serde_json::{Map, Value};

use crate::error::ApiError;

/// Reads a request body as a `QueryRequest`: a JSON object whose `query` is any JSON value and
/// whose `resourceUUID`, when present and not null, is a UUID string. Other top-level fields
/// are ignored, as `@JsonIgnoreProperties(ignoreUnknown = true)` ignores them.
///
/// The body is read with [`parse_strict`], so an object holding the same key twice anywhere
/// in it, the envelope and an object `query` included, is refused. A lenient parse would keep
/// the last copy, and a caller could then smuggle a second `userConsents` past a proxy that
/// rewrote the first.
///
/// # Errors
/// A 400 [`ApiError`] when the body is not JSON, holds a duplicate key, is not an object, or
/// has an invalid `resourceUUID`.
pub fn read_envelope(body: &[u8]) -> Result<Map<String, Value>, ApiError> {
    let value = parse_strict(body)
        .map_err(|e| ApiError::bad_request(format!("the request body is not valid JSON: {e}")))?;
    let Value::Object(fields) = value else {
        return Err(ApiError::bad_request(
            "the request body must be a JSON object",
        ));
    };
    match fields.get("resourceUUID") {
        None | Some(Value::Null) => {}
        Some(Value::String(id)) if canonical_uuid(id).is_some() => {}
        Some(_) => return Err(ApiError::bad_request("resourceUUID must be a UUID")),
    }
    Ok(fields)
}

/// Message answered for a query written in the v1 model.
pub const V1_QUERY_MESSAGE: &str = "v1 queries are not supported; use /PIC-SURE/v3";

/// Top-level keys of the v1 `Query` that the v3 model does not have.
const V1_QUERY_KEYS: [&str; 8] = [
    "fields",
    "crossCountFields",
    "requiredFields",
    "anyRecordOf",
    "anyRecordOfMulti",
    "numericFilters",
    "categoryFilters",
    "variantInfoFilters",
];

fn has_v1_keys(query: &Value) -> bool {
    query
        .as_object()
        .is_some_and(|fields| V1_QUERY_KEYS.iter().any(|key| fields.contains_key(*key)))
}

fn invalid_query(error: &serde_json::Error) -> ApiError {
    ApiError::bad_request(format!("the query is not valid: {error}"))
}

fn query_from_value(value: Value) -> Result<Query, ApiError> {
    if has_v1_keys(&value) {
        return Err(ApiError::bad_request(V1_QUERY_MESSAGE));
    }
    serde_json::from_value(value).map_err(|e| invalid_query(&e))
}

/// Reads a request body as a `QueryRequest` and its `query` as a v3 [`Query`]. The query may
/// be a JSON object or a string holding one, as `convertIncomingQuery` accepts both, and is
/// read with the engine's strict model: unknown fields inside it are refused.
///
/// Duplicate keys are refused at every level: in the body by [`read_envelope`], and in a
/// string `query` by one [`parse_strict`] of the string's contents, whose value then builds
/// the model.
///
/// # Errors
/// A 400 [`ApiError`] for the envelope errors of [`read_envelope`], a missing or null
/// `query`, a `query` that is neither an object nor a string, a query with v1 keys
/// ([`V1_QUERY_MESSAGE`]), a duplicate key, and a query the model refuses.
pub fn read_query(body: &[u8]) -> Result<Query, ApiError> {
    let mut fields = read_envelope(body)?;
    match fields.remove("query") {
        None | Some(Value::Null) => Err(ApiError::bad_request("the request has no query")),
        Some(Value::String(text)) => {
            let value = parse_strict(text.as_bytes()).map_err(|e| invalid_query(&e))?;
            query_from_value(value)
        }
        Some(object @ Value::Object(_)) => query_from_value(object),
        Some(_) => Err(ApiError::bad_request(
            "query must be a JSON object or a string holding one",
        )),
    }
}

/// The lowercase canonical form of `text` when it is a UUID in the `8-4-4-4-12` hexadecimal
/// form, the form `UUID.toString` writes and the result registry keys on.
pub fn canonical_uuid(text: &str) -> Option<String> {
    let valid = text.len() == 36
        && text.char_indices().all(|(i, c)| match i {
            8 | 13 | 18 | 23 => c == '-',
            _ => c.is_ascii_hexdigit(),
        });
    valid.then(|| text.to_ascii_lowercase())
}

/// A random (version 4) UUID in canonical lowercase form.
///
/// # Panics
/// When the operating system's random number source fails.
pub fn random_uuid() -> String {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes)
        .unwrap_or_else(|e| panic!("the operating system random source failed: {e}"));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use hpds_query::ResultType;

    #[test]
    fn query_may_be_an_object_or_a_string_and_unknown_envelope_fields_are_ignored() {
        let object = read_query(br#"{"query":{"expectedResultType":"COUNT"},"extra":1}"#);
        let string = read_query(br#"{"query":"{\"expectedResultType\":\"COUNT\"}"}"#);
        assert_eq!(object.unwrap().expected_result_type, ResultType::Count);
        assert_eq!(string.unwrap().expected_result_type, ResultType::Count);
    }

    #[test]
    fn bad_envelopes_are_client_errors() {
        for body in [
            &b"{\"query\": "[..],
            b"[]",
            br#"{"resourceUUID":"x","query":{"expectedResultType":"COUNT"}}"#,
            br#"{"resourceUUID":null}"#,
            br#"{"query":5}"#,
            br#"{"query":{"expectedResultType":"COUNT","bogus":1}}"#,
            br#"{"query":"not json"}"#,
        ] {
            let error = read_query(body).unwrap_err();
            assert_eq!(error.status, 400, "{}", String::from_utf8_lossy(body));
        }
    }

    #[test]
    fn duplicate_keys_are_refused_at_every_level() {
        for body in [
            r#"{"query":{"expectedResultType":"COUNT"},"query":{"expectedResultType":"COUNT"}}"#,
            r#"{"query":{"expectedResultType":"COUNT","userConsents":[{"value":"a"}],"userConsents":[]}}"#,
            r#"{"query":{"expectedResultType":"COUNT","phenotypicClause":{"phenotypicFilterType":"REQUIRED","conceptPath":"\\a\\","conceptPath":"\\b\\"}}}"#,
            r#"{"query":"{\"expectedResultType\":\"COUNT\",\"userConsents\":[{\"value\":\"a\"}],\"userConsents\":[]}"}"#,
            r#"{"query":"{\"expectedResultType\":\"COUNT\",\"userConsents\":[{\"value\":\"a\",\"value\":\"b\"}]}"}"#,
            r#"{"resourceUUID":null,"resourceUUID":null,"query":{"expectedResultType":"COUNT"}}"#,
        ] {
            let error = read_query(body.as_bytes()).unwrap_err();
            assert_eq!(error.status, 400, "{body}");
            assert!(
                error.message.contains("duplicate"),
                "{body}: {}",
                error.message
            );
        }
        assert!(read_envelope(br#"{"query":"a","query":"b"}"#).is_err());
    }

    #[test]
    fn v1_queries_are_refused_with_one_message() {
        for query in [
            r#"{"query":{"fields":["\\a\\"],"expectedResultType":"COUNT"}}"#,
            r#"{"query":{"categoryFilters":{},"expectedResultType":"COUNT"}}"#,
            r#"{"query":"{\"requiredFields\":[],\"expectedResultType\":\"COUNT\"}"}"#,
        ] {
            let error = read_query(query.as_bytes()).unwrap_err();
            assert_eq!(error.status, 400);
            assert_eq!(error.message, V1_QUERY_MESSAGE);
        }
    }

    #[test]
    fn random_uuids_are_canonical_version_4() {
        let id = random_uuid();
        assert_eq!(canonical_uuid(&id).as_deref(), Some(id.as_str()));
        assert_eq!(&id[14..15], "4");
        assert_ne!(id, random_uuid());
        assert_eq!(
            canonical_uuid("8694E3D4-5CB4-410F-8431-993445E6D3F6").as_deref(),
            Some("8694e3d4-5cb4-410f-8431-993445e6d3f6")
        );
        assert!(canonical_uuid("not-a-uuid").is_none());
    }
}
