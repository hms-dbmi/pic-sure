//! gzip response compression, negotiated from the request's `Accept-Encoding`.
//!
//! Only `/search` compresses ([`crate::search::search`]). A request without
//! `Accept-Encoding` gets exactly the bytes and headers it got before compression existed.

use std::io::Write;

use axum::http::HeaderMap;
use axum::http::header::ACCEPT_ENCODING;
use flate2::Compression;
use flate2::write::GzEncoder;

/// gzip level of a body compressed per request: the fastest. Higher levels make a
/// `/search` body about a third smaller again for about half again the CPU, which pays only
/// on a link slower than the query service's own network to this service.
pub const GZIP_LEVEL: u32 = 1;

/// gzip level of a body compressed once and kept ([`crate::search::SearchIndex`]'s whole
/// dictionary answers): the smallest, since its CPU is paid once.
pub const STORED_GZIP_LEVEL: u32 = 9;

/// Bodies shorter than this many bytes are never compressed. Setting up a gzip stream has a
/// fixed cost whatever the size, and a body this short travels with its headers in about
/// one TCP segment anyway, so compressing it saves no segment.
pub const MIN_GZIP_BYTES: usize = 1400;

/// True when `headers` accept a gzip response under RFC 9110 section 12.5.3: some
/// `Accept-Encoding` element names `gzip` (or its alias `x-gzip`) with a qvalue above 0, or,
/// when no element names gzip, a `*` element does. Coding and parameter names compare
/// case-insensitively. A qvalue that does not parse counts as 0. No `Accept-Encoding` header,
/// or one that is empty, accepts only the identity encoding.
pub fn accepts_gzip(headers: &HeaderMap) -> bool {
    let mut gzip: Option<bool> = None;
    let mut any: Option<bool> = None;
    for value in headers.get_all(ACCEPT_ENCODING) {
        let Ok(text) = value.to_str() else {
            continue;
        };
        for element in text.split(',') {
            let mut parts = element.split(';');
            let coding = parts.next().unwrap_or_default().trim();
            let acceptable = parts
                .filter_map(|parameter| parameter.split_once('='))
                .find(|(name, _)| name.trim().eq_ignore_ascii_case("q"))
                .is_none_or(|(_, q)| qvalue_is_positive(q.trim()));
            let slot =
                if coding.eq_ignore_ascii_case("gzip") || coding.eq_ignore_ascii_case("x-gzip") {
                    &mut gzip
                } else if coding == "*" {
                    &mut any
                } else {
                    continue;
                };
            *slot = Some(slot.unwrap_or(false) || acceptable);
        }
    }
    gzip.or(any).unwrap_or(false)
}

/// True when `q` is a qvalue (`0` to `1`, at most three decimals) above 0.
fn qvalue_is_positive(q: &str) -> bool {
    let (whole, fraction) = q.split_once('.').unwrap_or((q, ""));
    let well_formed = matches!(whole, "0" | "1")
        && fraction.len() <= 3
        && fraction.bytes().all(|b| b.is_ascii_digit())
        && (whole == "0" || fraction.bytes().all(|b| b == b'0'));
    well_formed && (whole == "1" || fraction.bytes().any(|b| b != b'0'))
}

/// `body` as a gzip member at `level` (0 to 9).
pub fn gzip(body: &[u8], level: u32) -> Vec<u8> {
    let mut encoder = GzEncoder::new(
        Vec::with_capacity(body.len() / 4 + 64),
        Compression::new(level),
    );
    encoder
        .write_all(body)
        .and_then(|()| encoder.finish())
        .unwrap_or_else(|_| unreachable!("writing to a Vec cannot fail"))
}

#[cfg(test)]
mod tests {
    use std::io::Read;

    use axum::http::HeaderValue;
    use flate2::read::GzDecoder;

    use super::*;

    fn accepts(values: &[&str]) -> bool {
        let mut headers = HeaderMap::new();
        for value in values {
            headers.append(ACCEPT_ENCODING, HeaderValue::from_str(value).unwrap());
        }
        accepts_gzip(&headers)
    }

    #[test]
    fn gzip_is_negotiated_as_rfc_9110_says() {
        assert!(!accepts(&[]));
        assert!(!accepts(&[""]));
        assert!(accepts(&["gzip"]));
        assert!(accepts(&["GZip"]));
        assert!(accepts(&["x-gzip"]));
        assert!(accepts(&["gzip, deflate, x-gzip"]));
        assert!(accepts(&["deflate, gzip;q=0.5"]));
        assert!(accepts(&["br", "gzip"]));
        assert!(!accepts(&["gzip;q=0"]));
        assert!(!accepts(&["gzip; Q=0.000"]));
        assert!(!accepts(&["identity"]));
        assert!(!accepts(&["identity;q=1, deflate"]));
        assert!(accepts(&["*"]));
        assert!(accepts(&["identity;q=0, *"]));
        assert!(!accepts(&["*;q=0"]));
        assert!(!accepts(&["gzip;q=0, *"]));
        assert!(accepts(&["*;q=0, gzip"]));
        assert!(accepts(&["gzip;q=0.001"]));
        assert!(accepts(&["gzip;q=1.000"]));
        assert!(!accepts(&["gzip;q=1.5"]));
        assert!(!accepts(&["gzip;q=abc"]));
        assert!(!accepts(&["gzip;q=0.0001"]));
        assert!(accepts(&[" gzip ; q = 0.5 "]));
        assert!(accepts(&["gzip;level=1"]));
    }

    #[test]
    fn a_gzip_body_decompresses_to_the_input() {
        let body: Vec<u8> = (0..100_000u32)
            .flat_map(|i| (i % 251).to_le_bytes())
            .collect();
        for level in [GZIP_LEVEL, STORED_GZIP_LEVEL] {
            let packed = gzip(&body, level);
            let mut unpacked = Vec::new();
            GzDecoder::new(packed.as_slice())
                .read_to_end(&mut unpacked)
                .unwrap();
            assert_eq!(unpacked, body);
        }
    }
}
