//! The `QueryRequest` envelope every POST body arrives in, and the UUID helpers the routes
//! share.

use hpds_query::Query;
use serde_json::{Map, Value};

use crate::error::ApiError;

/// Reads a request body as a `QueryRequest`: a JSON object whose `query` is any JSON value and
/// whose `resourceUUID`, when present and not null, is a UUID string. Other top-level fields
/// are ignored, as `@JsonIgnoreProperties(ignoreUnknown = true)` ignores them.
///
/// # Errors
/// A 400 [`ApiError`] when the body is not JSON, not an object, or has an invalid
/// `resourceUUID`.
pub fn read_envelope(body: &[u8]) -> Result<Map<String, Value>, ApiError> {
    let value: Value = serde_json::from_slice(body)
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

/// Reads a request body as a `QueryRequest` and its `query` as a v3 [`Query`]. The query may
/// be a JSON object or a string holding one, as `convertIncomingQuery` accepts both, and is
/// read with the engine's strict model: unknown fields inside it are refused.
///
/// # Errors
/// A 400 [`ApiError`] for the envelope errors of [`read_envelope`], a missing or null
/// `query`, a `query` that is neither an object nor a string, and a query the model refuses.
pub fn read_query(body: &[u8]) -> Result<Query, ApiError> {
    let mut fields = read_envelope(body)?;
    let parsed = match fields.remove("query") {
        None | Some(Value::Null) => return Err(ApiError::bad_request("the request has no query")),
        Some(Value::String(text)) => serde_json::from_str(&text),
        Some(object @ Value::Object(_)) => serde_json::from_value(object),
        Some(_) => {
            return Err(ApiError::bad_request(
                "query must be a JSON object or a string holding one",
            ));
        }
    };
    parsed.map_err(|e| ApiError::bad_request(format!("the query is not valid: {e}")))
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
