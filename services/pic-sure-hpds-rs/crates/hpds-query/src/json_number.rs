//! Number tokens of query JSON, read from their literal text the way Jackson 2.21 reads them
//! where `serde_json` would read them differently.
//!
//! `serde_json` with `float_roundtrip` already reads every finite number as Jackson does. Three
//! shapes remain, and [`java_numbers`] handles them on the raw text before `serde_json` sees
//! it:
//!
//! - A number token with more than [`MAX_NUMBER_DIGITS`] digits, anywhere in the text, is
//!   refused, as Jackson's `StreamReadConstraints.getMaxNumberLength()` refuses it. Jackson's
//!   byte parser counts every digit of the token: integer part, fraction and exponent, with
//!   neither sign.
//! - A `min` or `max` that overflows a double (`1e309`, a 310-digit integer) is `±Infinity` in
//!   Jackson, which `PhenotypicFilterValidator` never checks; `serde_json` refuses it. The token
//!   is rewritten to the string `"Infinity"` or `"-Infinity"`, which the model reads as the
//!   same double.
//! - A `min` or `max` written as the integer `-0` is `0.0` in Jackson, which reads it as an
//!   integer; `serde_json` reads `-0.0`. The token is rewritten to `0`.
//! - Any other number that overflows a double is accepted by Jackson, which ignores it in a
//!   field it ignores and coerces it to its text in a string field; `serde_json` refuses the
//!   whole text. The token is rewritten to a JSON string holding its own text, so an ignored
//!   field stays ignored and a string field gets the text Jackson gives it.
//!
//! The length check applies to every number, since Jackson applies it while reading the whole
//! body.

use std::borrow::Cow;

/// The most digits a number token may hold, `StreamReadConstraints.DEFAULT_MAX_NUM_LEN`.
pub const MAX_NUMBER_DIGITS: usize = 1000;

/// A number token longer than [`MAX_NUMBER_DIGITS`] digits.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NumberTooLong {
    /// The digits the token holds.
    pub digits: usize,
}

impl std::fmt::Display for NumberTooLong {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Number value length ({}) exceeds the maximum allowed ({MAX_NUMBER_DIGITS})",
            self.digits
        )
    }
}

impl std::error::Error for NumberTooLong {}

/// Where the scanner is inside one open object or array.
#[derive(Debug, Clone, Copy)]
enum Frame {
    /// An object: whether the next string is a key, and whether the current key is `min` or
    /// `max`.
    Object { expects_key: bool, bound: bool },
    /// An array.
    Array,
}

/// Applies the rules of the module docs to `json`.
///
/// Returns the text unchanged (borrowed) when no token needs rewriting, which is the usual
/// case. Malformed JSON is passed through for `serde_json` to report; the scan never fails on
/// it.
///
/// # Errors
/// [`NumberTooLong`] for the first number token with more than [`MAX_NUMBER_DIGITS`]
/// digits.
pub fn java_numbers(json: &[u8]) -> Result<Cow<'_, [u8]>, NumberTooLong> {
    let mut frames: Vec<Frame> = Vec::new();
    let mut edits: Vec<(usize, usize, Replacement)> = Vec::new();
    let mut i = 0;
    while i < json.len() {
        match json[i] {
            b'"' => {
                let end = string_end(json, i);
                if let Some(Frame::Object { expects_key, bound }) = frames.last_mut()
                    && *expects_key
                {
                    *bound = is_bound_key(&json[i..end]);
                    *expects_key = false;
                }
                i = end;
            }
            b'{' => {
                frames.push(Frame::Object {
                    expects_key: true,
                    bound: false,
                });
                i += 1;
            }
            b'[' => {
                frames.push(Frame::Array);
                i += 1;
            }
            b'}' | b']' => {
                frames.pop();
                i += 1;
            }
            b',' => {
                if let Some(Frame::Object { expects_key, bound }) = frames.last_mut() {
                    *expects_key = true;
                    *bound = false;
                }
                i += 1;
            }
            b'-' | b'0'..=b'9' => {
                let start = i;
                while i < json.len()
                    && matches!(json[i], b'0'..=b'9' | b'-' | b'+' | b'.' | b'e' | b'E')
                {
                    i += 1;
                }
                let token = &json[start..i];
                let digits = token.iter().filter(|b| b.is_ascii_digit()).count();
                if digits > MAX_NUMBER_DIGITS {
                    return Err(NumberTooLong { digits });
                }
                let is_bound = matches!(
                    frames.last(),
                    Some(Frame::Object {
                        expects_key: false,
                        bound: true,
                    })
                );
                let replacement = if is_bound {
                    bound_replacement(token)
                } else {
                    overflow(token).map(|_| Replacement::Quoted)
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

/// Whether the quoted key token is `min` or `max`, escapes decoded.
fn is_bound_key(token: &[u8]) -> bool {
    match token {
        b"\"min\"" | b"\"max\"" => true,
        _ if token.contains(&b'\\') => {
            serde_json::from_slice::<String>(token).is_ok_and(|key| key == "min" || key == "max")
        }
        _ => false,
    }
}

/// The most integer digits a number without an exponent can have and still be finite:
/// `Double.MAX_VALUE` is below `10^309`.
const FINITE_INTEGER_DIGITS: usize = 308;

/// What a number token is rewritten to.
#[derive(Debug, Clone, Copy)]
enum Replacement {
    /// This text instead of the token.
    Text(&'static [u8]),
    /// The token's own text, quoted as a JSON string.
    Quoted,
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

    fn rewritten(json: &str) -> String {
        String::from_utf8(java_numbers(json.as_bytes()).unwrap().into_owned()).unwrap()
    }

    #[test]
    fn ordinary_text_is_borrowed_unchanged() {
        let json = br#"{"min":1.5,"max":-0.0,"values":["-0","1e309"],"n":[1e308,-0]}"#;
        assert!(matches!(java_numbers(json), Ok(Cow::Borrowed(_))));
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
        let json = r#"{"values":["min",-0],"min":"min","conceptPath":"max","other":-0}"#;
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
            rewritten(r#"{"values":[1e309,2],"min":[1e309]}"#),
            r#"{"values":["1e309",2],"min":["1e309"]}"#
        );
        assert_eq!(
            rewritten(r#"{"x":1.7976931348623157e308}"#),
            r#"{"x":1.7976931348623157e308}"#
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
            assert!(java_numbers(json.as_bytes()).is_ok());
        }
    }
}
