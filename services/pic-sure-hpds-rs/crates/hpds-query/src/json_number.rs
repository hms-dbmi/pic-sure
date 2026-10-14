//! Number tokens of query JSON, read from their literal text the way Jackson 2.21 reads them
//! where `serde_json` would read them differently.
//!
//! `serde_json` with `float_roundtrip` already reads every finite number as Jackson does. Three
//! shapes remain, and [`java_numbers`] handles them on the raw text before `serde_json` sees
//! it:
//!
//! The service reads a query on one of two paths, and [`Source`] names them. A query sent as
//! a JSON object is read by Spring's `ObjectMapper` from the request bytes as an untyped value
//! (`Object`), written back to text and read again as a `Query`. A query sent as a JSON string
//! is read once, from that string's characters. The two paths differ in two rules below: the
//! digit count of the length limit, and the text a number in a `String` field becomes.
//!
//! - A number token with more than [`MAX_NUMBER_DIGITS`] digits, anywhere in the text, is
//!   refused, as Jackson's `StreamReadConstraints.getMaxNumberLength()` refuses it. Jackson's
//!   byte parser, which reads a request body, counts every digit of the token: integer part,
//!   fraction and exponent, with neither sign. Its character parser, which reads a string
//!   query, counts the same digits except a lone integer digit `0`, which it counts only when
//!   the token has both a fraction and an exponent.
//! - A `min` or `max` that overflows a double (`1e309`, a 310-digit integer) is `±Infinity` in
//!   Jackson, which `PhenotypicFilterValidator` never checks; `serde_json` refuses it. The token
//!   is rewritten to the string `"Infinity"` or `"-Infinity"`, which the model reads as the
//!   same double.
//! - A `min` or `max` written as the integer `-0` is `0.0` in Jackson, which reads it as an
//!   integer; `serde_json` reads `-0.0`. The token is rewritten to `0`.
//! - A number, `true` or `false` where the v3 model holds a Java `String` (an element of
//!   `select` or `values`, the value of `conceptPath`, `key` or a consent's `value`) is coerced
//!   by Jackson to its source text. `serde_json` would refuse it or lose the text, so the
//!   token is rewritten to a JSON string. In a string query the text is the token as written
//!   (`1.50`, `1e3`, `-0` and `1e309` stay as they are). In an object query it is the text the
//!   untyped value was written back as: an integer's decimal digits (`-0` becomes `0`), and a
//!   decimal or exponent token's `Double.toString` (`1.50` becomes `1.5`, `1e3` becomes
//!   `1000.0`, `1e309` becomes `Infinity`). Objects and arrays in those positions are left for
//!   the model to refuse, as Jackson refuses them.
//! - The integer `-0` as the value of `not` is `false` to Jackson, which reads it as the
//!   integer zero; `serde_json` reads the float `-0.0`, which the model refuses. The token is
//!   rewritten to `0`.
//! - An integer too wide for 64 bits as the value of `not` is true to Jackson's int to Boolean
//!   coercion (any non-zero integer is); `serde_json` would hand it over as a float, which the
//!   model refuses. The token is rewritten to `true`.
//! - Any other number that overflows a double is accepted by Jackson, which ignores it in a
//!   field it ignores; `serde_json` refuses the whole text. The token is rewritten to a JSON
//!   string holding its own text, so an ignored field stays ignored.
//!
//! The positions come from key names alone. That is sound for the v3 model: every key named
//! above is a field of that type wherever it may appear in a query, the model refuses unknown
//! fields inside a query, and fields outside it (the envelope's extras) are ignored either way.
//!
//! The length check applies to every number, since Jackson applies it while reading the whole
//! body.
//!
//! `hpds_format::jackson_string_double` is the other half: a `min` or `max` sent as a JSON
//! string, read as Jackson's `StdDeserializer` coerces a string to a `Double`.

use std::borrow::Cow;

use hpds_format::{MAX_NUMBER_DIGITS, NumberTooLong, java_double_to_string};

/// Where the JSON text being read comes from, which decides the two rules that differ between
/// the service's two ways of reading a query.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// A whole request body, read from its bytes, whose `query` (when it is an object) the
    /// service writes back to text before reading it as a `Query`.
    RequestBody,
    /// Query text read from characters: the contents of a string `query`, or a query read on
    /// its own.
    QueryText,
}

/// What the v3 model reads the value of a key as, for the keys this module acts on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum KeyKind {
    /// `min` or `max`: a `Double`.
    Bound,
    /// `conceptPath`, `key` or `value`: a `String`.
    Text,
    /// `select` or `values`: a list of `String`.
    TextList,
    /// `not`: a `Boolean`.
    Not,
    /// Any other key.
    Other,
}

/// Where the scanner is inside one open object or array.
#[derive(Debug, Clone, Copy)]
enum Frame {
    /// An object: whether the next string is a key, and what the current key's value is read
    /// as.
    Object { expects_key: bool, key: KeyKind },
    /// An array, and whether its elements are read as `String` (the array is the value of a
    /// [`KeyKind::TextList`] key).
    Array { text_items: bool },
}

impl Frame {
    /// What a scalar token directly inside this frame is read as.
    fn scalar_kind(self) -> KeyKind {
        match self {
            Frame::Object {
                expects_key: false,
                key,
            } => key,
            Frame::Array { text_items: true } => KeyKind::Text,
            _ => KeyKind::Other,
        }
    }
}

/// Applies the rules of the module docs to `json`, read as `source` says.
///
/// Returns the text unchanged (borrowed) when no token needs rewriting, which is the usual
/// case. Malformed JSON is passed through for `serde_json` to report; the scan never fails on
/// it.
///
/// # Errors
/// [`NumberTooLong`] for the first number token with more than [`MAX_NUMBER_DIGITS`]
/// digits.
pub fn java_numbers(json: &[u8], source: Source) -> Result<Cow<'_, [u8]>, NumberTooLong> {
    let mut frames: Vec<Frame> = Vec::new();
    let mut edits: Vec<(usize, usize, Replacement)> = Vec::new();
    let mut i = 0;
    while i < json.len() {
        match json[i] {
            b'"' => {
                let end = string_end(json, i);
                if let Some(Frame::Object { expects_key, key }) = frames.last_mut()
                    && *expects_key
                {
                    *key = key_kind(&json[i..end]);
                    *expects_key = false;
                }
                i = end;
            }
            b'{' => {
                frames.push(Frame::Object {
                    expects_key: true,
                    key: KeyKind::Other,
                });
                i += 1;
            }
            b'[' => {
                let text_items = frames
                    .last()
                    .is_some_and(|frame| frame.scalar_kind() == KeyKind::TextList);
                frames.push(Frame::Array { text_items });
                i += 1;
            }
            b'}' | b']' => {
                frames.pop();
                i += 1;
            }
            b',' => {
                if let Some(Frame::Object { expects_key, key }) = frames.last_mut() {
                    *expects_key = true;
                    *key = KeyKind::Other;
                }
                i += 1;
            }
            b't' | b'f' => {
                let start = i;
                while i < json.len() && json[i].is_ascii_alphabetic() {
                    i += 1;
                }
                let is_boolean = matches!(&json[start..i], b"true" | b"false");
                if is_boolean
                    && frames
                        .last()
                        .is_some_and(|frame| frame.scalar_kind() == KeyKind::Text)
                {
                    edits.push((start, i, Replacement::Quoted));
                }
            }
            b'-' | b'0'..=b'9' => {
                let start = i;
                while i < json.len()
                    && matches!(json[i], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E')
                {
                    i += 1;
                }
                let token = &json[start..i];
                let digits = counted_digits(token, source);
                if digits > MAX_NUMBER_DIGITS {
                    return Err(NumberTooLong { digits });
                }
                let kind = frames
                    .last()
                    .map_or(KeyKind::Other, |frame| frame.scalar_kind());
                let replacement = if !is_json_number(token) {
                    None
                } else {
                    match kind {
                        KeyKind::Bound => bound_replacement(token),
                        KeyKind::Text => Some(text_replacement(token, source)),
                        KeyKind::Not => not_replacement(token),
                        KeyKind::TextList | KeyKind::Other => {
                            overflow(token).map(|_| Replacement::Quoted)
                        }
                    }
                };
                if let Some(replacement) = replacement {
                    edits.push((start, i, replacement));
                }
            }
            _ => i += 1,
        }
    }
    if edits.is_empty() {
        return Ok(Cow::Borrowed(json));
    }
    let mut out = Vec::with_capacity(json.len() + 8 * edits.len());
    let mut copied = 0;
    for (start, end, replacement) in edits {
        out.extend_from_slice(&json[copied..start]);
        match replacement {
            Replacement::Text(text) => out.extend_from_slice(text),
            Replacement::Quoted => {
                out.push(b'"');
                out.extend_from_slice(&json[start..end]);
                out.push(b'"');
            }
            Replacement::QuotedText(text) => {
                out.push(b'"');
                out.extend_from_slice(text.as_bytes());
                out.push(b'"');
            }
        }
        copied = end;
    }
    out.extend_from_slice(&json[copied..]);
    Ok(Cow::Owned(out))
}

/// The index just past the string token that opens at `start`, or the end of `json` when it
/// is never closed. The closing quote is found with `memchr`; a quote preceded by an odd run
/// of backslashes is escaped and skipped.
fn string_end(json: &[u8], start: usize) -> usize {
    let mut from = start + 1;
    while let Some(offset) = memchr::memchr(b'"', &json[from..]) {
        let quote = from + offset;
        let backslashes = json[start + 1..quote]
            .iter()
            .rev()
            .take_while(|b| **b == b'\\')
            .count();
        if backslashes % 2 == 0 {
            return quote + 1;
        }
        from = quote + 1;
    }
    json.len()
}

/// What the value of the quoted key token is read as, escapes decoded.
fn key_kind(token: &[u8]) -> KeyKind {
    let kind = |key: &[u8]| match key {
        b"min" | b"max" => KeyKind::Bound,
        b"conceptPath" | b"key" | b"value" => KeyKind::Text,
        b"select" | b"values" => KeyKind::TextList,
        b"not" => KeyKind::Not,
        _ => KeyKind::Other,
    };
    if token.contains(&b'\\') {
        serde_json::from_slice::<String>(token).map_or(KeyKind::Other, |key| kind(key.as_bytes()))
    } else {
        kind(
            token
                .get(1..token.len().saturating_sub(1))
                .unwrap_or_default(),
        )
    }
}

/// Whether `token` is a number in JSON's grammar, `-?(0|[1-9]D*)(.D+)?([eE][+-]?D+)?`. A
/// malformed token is never rewritten, so `serde_json` still reports it.
fn is_json_number(token: &[u8]) -> bool {
    let digits = |from: usize| {
        token[from..]
            .iter()
            .take_while(|b| b.is_ascii_digit())
            .count()
    };
    let mut i = usize::from(token.first() == Some(&b'-'));
    let integer = digits(i);
    if integer == 0 || (integer > 1 && token[i] == b'0') {
        return false;
    }
    i += integer;
    if token.get(i) == Some(&b'.') {
        let fraction = digits(i + 1);
        if fraction == 0 {
            return false;
        }
        i += 1 + fraction;
    }
    if matches!(token.get(i), Some(b'e' | b'E')) {
        i += 1;
        if matches!(token.get(i), Some(b'+' | b'-')) {
            i += 1;
        }
        let exponent = digits(i);
        if exponent == 0 {
            return false;
        }
        i += exponent;
    }
    i == token.len()
}

/// Whether `token` is an integer (no fraction or exponent) too wide for 64 bits, which
/// `serde_json` reads as a float. Such an integer is never zero.
fn wide_integer(token: &[u8]) -> bool {
    !token.iter().any(|b| matches!(b, b'.' | b'e' | b'E'))
        && std::str::from_utf8(token)
            .is_ok_and(|text| text.parse::<i64>().is_err() && text.parse::<u64>().is_err())
}

/// The most integer digits a number without an exponent can have and still be finite:
/// `Double.MAX_VALUE` is below `10^309`.
const FINITE_INTEGER_DIGITS: usize = 308;

/// What a number token is rewritten to.
#[derive(Debug, Clone)]
enum Replacement {
    /// This text instead of the token.
    Text(&'static [u8]),
    /// The token's own text, quoted as a JSON string.
    Quoted,
    /// This text, quoted as a JSON string.
    QuotedText(String),
}

/// The digits of a number token that Jackson counts against [`MAX_NUMBER_DIGITS`] when
/// reading from `source`. A malformed token counts every digit.
fn counted_digits(token: &[u8], source: Source) -> usize {
    let all = token.iter().filter(|b| b.is_ascii_digit()).count();
    if source == Source::RequestBody || !is_json_number(token) {
        return all;
    }
    let unsigned = token.strip_prefix(b"-").unwrap_or(token);
    let lone_zero =
        unsigned.first() == Some(&b'0') && !unsigned.get(1).is_some_and(u8::is_ascii_digit);
    let fraction = unsigned.contains(&b'.');
    let exponent = unsigned.iter().any(|b| matches!(b, b'e' | b'E'));
    if lone_zero && !(fraction && exponent) {
        all - 1
    } else {
        all
    }
}

/// What a well-formed number token in a `String` position becomes, read from `source`: its
/// own text from query text, and the text Jackson writes its untyped value back as from a
/// request body.
fn text_replacement(token: &[u8], source: Source) -> Replacement {
    if source == Source::QueryText {
        return Replacement::Quoted;
    }
    if token.iter().any(|b| matches!(b, b'.' | b'e' | b'E')) {
        let value: f64 = std::str::from_utf8(token)
            .ok()
            .and_then(|text| text.parse().ok())
            .unwrap_or(f64::NAN);
        Replacement::QuotedText(java_double_to_string(value))
    } else if token == b"-0" {
        Replacement::QuotedText("0".to_owned())
    } else {
        Replacement::Quoted
    }
}

/// What a well-formed number token as the value of `not` becomes, when Jackson's Boolean
/// coercion reads it differently from how `serde_json` hands it to the model.
fn not_replacement(token: &[u8]) -> Option<Replacement> {
    if token == b"-0" {
        Some(Replacement::Text(b"0"))
    } else {
        wide_integer(token).then_some(Replacement::Text(b"true"))
    }
}

/// The infinity a number token overflows to, as its sign, or `None` when it is finite.
fn overflow(token: &[u8]) -> Option<f64> {
    if !token.iter().any(|b| matches!(b, b'e' | b'E')) {
        let integer_digits = token
            .iter()
            .take_while(|b| **b != b'.')
            .filter(|b| b.is_ascii_digit())
            .count();
        if integer_digits <= FINITE_INTEGER_DIGITS {
            return None;
        }
    }
    let value: f64 = std::str::from_utf8(token).ok()?.parse().ok()?;
    value.is_infinite().then_some(value)
}

/// What a `min` or `max` number token becomes, when Jackson reads it differently from
/// `serde_json`.
fn bound_replacement(token: &[u8]) -> Option<Replacement> {
    if token == b"-0" {
        return Some(Replacement::Text(b"0"));
    }
    overflow(token).map(|infinity| {
        Replacement::Text(if infinity > 0.0 {
            b"\"Infinity\""
        } else {
            b"\"-Infinity\""
        })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rewritten_from(json: &str, source: Source) -> String {
        String::from_utf8(java_numbers(json.as_bytes(), source).unwrap().into_owned()).unwrap()
    }

    fn rewritten(json: &str) -> String {
        let text = rewritten_from(json, Source::QueryText);
        assert_eq!(
            rewritten_from(json, Source::RequestBody),
            text,
            "both sources: {json}"
        );
        text
    }

    #[test]
    fn ordinary_text_is_borrowed_unchanged() {
        let json = br#"{"min":1.5,"max":-0.0,"values":["-0","1e309"],"n":[1e308,-0]}"#;
        for source in [Source::RequestBody, Source::QueryText] {
            assert!(matches!(java_numbers(json, source), Ok(Cow::Borrowed(_))));
        }
    }

    #[test]
    fn overflowing_bounds_become_infinite_strings() {
        assert_eq!(
            rewritten(r#"{"min": 1e309 ,"max":-1e309}"#),
            r#"{"min": "Infinity" ,"max":"-Infinity"}"#
        );
        let wide = format!(r#"{{"max":1{}}}"#, "0".repeat(309));
        assert_eq!(rewritten(&wide), r#"{"max":"Infinity"}"#);
        assert_eq!(
            rewritten(r#"{"a":{"min":1.7976931348623158e308},"max":1.7976931348623159e308}"#),
            r#"{"a":{"min":1.7976931348623158e308},"max":"Infinity"}"#
        );
    }

    #[test]
    fn integer_minus_zero_bounds_become_zero() {
        assert_eq!(
            rewritten(r#"{"min":-0,"max":-0.0,"x":-0}"#),
            r#"{"min":0,"max":-0.0,"x":-0}"#
        );
        assert_eq!(rewritten(r#"{"min":-0}"#), r#"{"min":0}"#);
    }

    #[test]
    fn only_values_of_a_bound_key_become_infinity_or_zero() {
        let json = r#"{"items":["min",-0],"min":"min","conceptPath":"max","other":-0}"#;
        assert_eq!(rewritten(json), json);
        assert_eq!(
            rewritten(r#"[{"min":[-0]},{"k":"x\"min","max":-0}]"#),
            r#"[{"min":[-0]},{"k":"x\"min","max":0}]"#
        );
    }

    #[test]
    fn other_overflowing_numbers_become_their_own_text() {
        assert_eq!(
            rewritten(r#"{"query":{"min":1},"extra":1e309,"resourceCredentials":{"k":-1E+400}}"#),
            r#"{"query":{"min":1},"extra":"1e309","resourceCredentials":{"k":"-1E+400"}}"#
        );
        assert_eq!(
            rewritten(r#"{"items":[1e309,2],"min":[1e309]}"#),
            r#"{"items":["1e309",2],"min":["1e309"]}"#
        );
        assert_eq!(
            rewritten(r#"{"x":1.7976931348623157e308}"#),
            r#"{"x":1.7976931348623157e308}"#
        );
    }

    #[test]
    fn scalars_in_string_positions_of_query_text_become_their_literal_text() {
        assert_eq!(
            rewritten_from(
                r#"{"select":[5,-0,1.50,1e3,1E+3,1e309,true,false,null,"a"],"phenotypicClause":{"conceptPath":1.50,"values":[0.000,true]},"userConsents":[{"value":5}],"genomicFilters":[{"key":false,"values":[1.0E-5]}]}"#,
                Source::QueryText
            ),
            r#"{"select":["5","-0","1.50","1e3","1E+3","1e309","true","false",null,"a"],"phenotypicClause":{"conceptPath":"1.50","values":["0.000","true"]},"userConsents":[{"value":"5"}],"genomicFilters":[{"key":"false","values":["1.0E-5"]}]}"#
        );
        let nested = r#"{"select":[[1],{"a":2}],"values":{"x":3},"conceptPath":[4]}"#;
        assert_eq!(rewritten(nested), nested);
        let elsewhere = r#"{"expectedResultType":1,"n":[true,2.50],"other":false}"#;
        assert_eq!(rewritten(elsewhere), elsewhere);
    }

    #[test]
    fn scalars_in_string_positions_of_a_request_body_become_the_text_jackson_writes_back() {
        assert_eq!(
            rewritten_from(
                r#"{"query":{"select":[5,-0,1.50,1e3,-1E+3,-0.0,0.000,1e309,-1e309,1e-400,2.5e-324,123456789012345678901234567890,true,null],"phenotypicClause":{"conceptPath":1.0E-5,"values":[1e7,true]},"userConsents":[{"value":-7}]}}"#,
                Source::RequestBody
            ),
            r#"{"query":{"select":["5","0","1.5","1000.0","-1000.0","-0.0","0.0","Infinity","-Infinity","0.0","4.9E-324","123456789012345678901234567890","true",null],"phenotypicClause":{"conceptPath":"1.0E-5","values":["1.0E7","true"]},"userConsents":[{"value":"-7"}]}}"#
        );
        assert_eq!(
            rewritten(r#"{"c\u006fnceptPath":7}"#),
            r#"{"c\u006fnceptPath":"7"}"#
        );
    }

    #[test]
    fn an_integer_too_wide_for_64_bits_in_not_becomes_true() {
        assert_eq!(
            rewritten(r#"{"not":99999999999999999999,"x":{"not":-99999999999999999999}}"#),
            r#"{"not":true,"x":{"not":true}}"#
        );
        let small = r#"{"not":2,"n":{"not":9223372036854775807},"m":{"not":1e30}}"#;
        assert_eq!(rewritten(small), small);
    }

    #[test]
    fn an_integer_minus_zero_in_not_becomes_zero() {
        assert_eq!(
            rewritten(r#"{"not":-0,"x":{"not":-0.0},"y":-0}"#),
            r#"{"not":0,"x":{"not":-0.0},"y":-0}"#
        );
    }

    #[test]
    fn malformed_numbers_are_never_rewritten() {
        for json in [
            r#"{"conceptPath":1.}"#,
            r#"{"conceptPath":01}"#,
            r#"{"select":[--1]}"#,
            r#"{"not":--99999999999999999999}"#,
            r#"{"x":1.e400}"#,
            r#"{"min":1e}"#,
            r#"{"conceptPath":tru}"#,
            r#"{"conceptPath":trueish}"#,
        ] {
            assert_eq!(rewritten(json), json);
        }
    }

    #[test]
    fn digits_past_the_limit_are_refused_as_jackson_counts_them() {
        let z = |n: usize| "0".repeat(n);
        let cases = [
            (format!("1{}", z(999)), None, None),
            (format!("1{}", z(1000)), Some(1001), Some(1001)),
            (format!("-1{}", z(999)), None, None),
            (format!("0.{}1", z(999)), Some(1001), None),
            (format!("-0.{}1", z(999)), Some(1001), None),
            (format!("0.{}1", z(1000)), Some(1002), Some(1001)),
            (format!("1.{}1", z(998)), None, None),
            (format!("1.{}1", z(999)), Some(1001), Some(1001)),
            (format!("0e{}1", z(999)), Some(1001), None),
            (format!("0.{}1e1", z(998)), Some(1001), Some(1001)),
            (format!("0.{}1e1", z(997)), None, None),
            (format!("1e{}1", z(998)), None, None),
            (format!("1.{}1e1", z(997)), None, None),
            (format!("1.{}1e+1", z(998)), Some(1001), Some(1001)),
        ];
        for (number, body, text) in cases {
            let json = format!(r#"{{"values":[{number}]}}"#);
            for (source, refused) in [(Source::RequestBody, body), (Source::QueryText, text)] {
                assert_eq!(
                    java_numbers(json.as_bytes(), source).err(),
                    refused.map(|digits| NumberTooLong { digits }),
                    "{} characters from {source:?}",
                    number.len()
                );
            }
        }
        assert_eq!(
            NumberTooLong { digits: 1001 }.to_string(),
            "Number value length (1001) exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)"
        );
    }

    #[test]
    fn escaped_quotes_keep_a_string_open() {
        let json = br#"{"k":"x\"min\":-0","l":"a\\","max":-0}"#;
        assert_eq!(
            String::from_utf8(java_numbers(json, Source::QueryText).unwrap().into_owned()).unwrap(),
            r#"{"k":"x\"min\":-0","l":"a\\","max":0}"#
        );
    }

    #[test]
    fn malformed_text_passes_through() {
        for json in [
            r#"{"min":"#,
            r#"{"min":"x"#,
            "[[[",
            r#"{"min":--1}"#,
            "\"\\",
        ] {
            assert!(java_numbers(json.as_bytes(), Source::RequestBody).is_ok());
        }
    }
}
