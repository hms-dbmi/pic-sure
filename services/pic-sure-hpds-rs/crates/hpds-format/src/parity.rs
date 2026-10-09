//! Helpers that reproduce Java semantics where Rust's defaults differ.

use std::cmp::Ordering;

/// Compares two strings the way Java `String.compareTo` does: lexicographically by UTF-16
/// code unit.
///
/// Rust `str` ordering is by code point, which disagrees with Java only when a character
/// above U+FFFF (encoded as a surrogate pair starting at 0xD800) meets a character in
/// U+E000..=U+FFFF at the first point of difference. Java puts the supplementary character
/// first.
pub fn java_string_cmp(a: &str, b: &str) -> Ordering {
    let (ab, bb) = (a.as_bytes(), b.as_bytes());
    let Some(diff) = ab.iter().zip(bb).position(|(x, y)| x != y) else {
        return ab.len().cmp(&bb.len());
    };
    let mut start = diff;
    while start > 0 && (ab[start] & 0xC0) == 0x80 {
        start -= 1;
    }
    let ca = a.get(start..).and_then(|s| s.chars().next());
    let cb = b.get(start..).and_then(|s| s.chars().next());
    match (ca, cb) {
        (Some(ca), Some(cb)) => {
            let mut ua = [0u16; 2];
            let mut ub = [0u16; 2];
            ca.encode_utf16(&mut ua).cmp(&cb.encode_utf16(&mut ub))
        }
        _ => ab.cmp(bb),
    }
}

/// Returns `value` with every NaN replaced by the canonical positive quiet NaN, the value
/// Java's `Double.doubleToLongBits` collapses all NaNs to.
pub fn canonical_nan(value: f64) -> f64 {
    if value.is_nan() { f64::NAN } else { value }
}

/// Compares two doubles the way Java `Double.compareTo` does: `-0.0` sorts before `0.0`,
/// and every NaN is equal to every other NaN and greater than positive infinity.
pub fn java_double_cmp(a: f64, b: f64) -> Ordering {
    canonical_nan(a).total_cmp(&canonical_nan(b))
}

/// Removes leading and trailing characters at or below U+0020, as Java `String.trim` does.
///
/// This differs from Rust `str::trim`, which removes Unicode whitespace (including U+00A0)
/// but keeps ASCII control characters such as U+0001.
pub fn java_trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// Parses `input` with the grammar and rounding of Java `Double.parseDouble`.
///
/// Accepted, after [`java_trim`]: an optional sign, then `NaN`, `Infinity`, a decimal
/// literal (digits with an optional fraction and at least one digit, an optional `e`/`E`
/// exponent), or a hexadecimal literal (`0x`/`0X`, hex digits with an optional fraction,
/// and a mandatory `p`/`P` binary exponent). Decimal and hexadecimal literals may end in
/// one of `f`, `F`, `d`, `D`. Results are correctly rounded to the nearest double, ties to
/// even, overflowing to infinity and underflowing to zero as Java does.
///
/// Returns `None` for anything else, where Java would throw `NumberFormatException`.
pub fn parse_java_double(input: &str) -> Option<f64> {
    let trimmed = java_trim(input);
    let (negative, rest) = match trimmed.as_bytes().first()? {
        b'+' => (false, &trimmed[1..]),
        b'-' => (true, &trimmed[1..]),
        _ => (false, trimmed),
    };
    let magnitude = match rest {
        "NaN" => return Some(f64::NAN),
        "Infinity" => f64::INFINITY,
        _ if rest.starts_with("0x") || rest.starts_with("0X") => parse_hex_magnitude(&rest[2..])?,
        _ => parse_decimal_magnitude(rest)?,
    };
    Some(if negative { -magnitude } else { magnitude })
}

fn strip_type_suffix(s: &str) -> &str {
    match s.as_bytes().last() {
        Some(b'f' | b'F' | b'd' | b'D') => &s[..s.len() - 1],
        _ => s,
    }
}

fn count_while(bytes: &[u8], pred: impl Fn(u8) -> bool) -> usize {
    bytes.iter().take_while(|&&b| pred(b)).count()
}

fn parse_decimal_magnitude(s: &str) -> Option<f64> {
    let core = strip_type_suffix(s);
    let bytes = core.as_bytes();
    let mut pos = count_while(bytes, |b| b.is_ascii_digit());
    let mut digits = pos;
    if bytes.get(pos) == Some(&b'.') {
        pos += 1;
        let fraction = count_while(&bytes[pos..], |b| b.is_ascii_digit());
        pos += fraction;
        digits += fraction;
    }
    if digits == 0 {
        return None;
    }
    if matches!(bytes.get(pos), Some(b'e' | b'E')) {
        pos += 1;
        if matches!(bytes.get(pos), Some(b'+' | b'-')) {
            pos += 1;
        }
        let exponent = count_while(&bytes[pos..], |b| b.is_ascii_digit());
        if exponent == 0 {
            return None;
        }
        pos += exponent;
    }
    if pos != bytes.len() {
        return None;
    }
    core.parse::<f64>().ok()
}

fn parse_hex_magnitude(s: &str) -> Option<f64> {
    let core = strip_type_suffix(s);
    let p = core.find(['p', 'P'])?;
    let (mantissa, exponent) = (&core[..p], &core[p + 1..]);

    let mut significand: u64 = 0;
    let mut scale: i64 = 0;
    let mut sticky = false;
    let mut seen_dot = false;
    let mut digits = 0usize;
    for c in mantissa.chars() {
        if c == '.' {
            if seen_dot {
                return None;
            }
            seen_dot = true;
            continue;
        }
        let d = u64::from(c.to_digit(16)?);
        digits += 1;
        if significand >> 60 == 0 {
            significand = (significand << 4) | d;
            if seen_dot {
                scale -= 4;
            }
        } else {
            sticky |= d != 0;
            if !seen_dot {
                scale += 4;
            }
        }
    }
    if digits == 0 {
        return None;
    }

    let exp_bytes = exponent.as_bytes();
    let (exp_negative, exp_digits) = match exp_bytes.first()? {
        b'+' => (false, &exp_bytes[1..]),
        b'-' => (true, &exp_bytes[1..]),
        _ => (false, exp_bytes),
    };
    if exp_digits.is_empty() || !exp_digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let exp_value = exp_digits.iter().fold(0i64, |acc, &b| {
        (acc * 10 + i64::from(b - b'0')).min(100_000_000)
    });
    let binary_exponent = scale + if exp_negative { -exp_value } else { exp_value };

    if significand == 0 {
        return Some(0.0);
    }
    Some(round_binary(significand, binary_exponent, sticky))
}

/// Rounds `significand * 2^exponent`, plus a nonzero tail below the significand's last bit
/// when `sticky` is set, to the nearest double with ties to even.
fn round_binary(significand: u64, exponent: i64, sticky: bool) -> f64 {
    let bits = 64 - i64::from(significand.leading_zeros());
    let top = exponent + bits - 1;
    if top > 1023 {
        return f64::INFINITY;
    }
    let normal = top >= -1022;
    let shift = if normal { bits - 53 } else { -1074 - exponent };
    let rounded = if shift <= 0 {
        significand << (-shift)
    } else if shift > 64 {
        0
    } else {
        let kept = if shift == 64 { 0 } else { significand >> shift };
        let dropped = if shift == 64 {
            significand
        } else {
            significand & ((1u64 << shift) - 1)
        };
        let half = 1u64 << (shift - 1);
        let round_up = dropped > half || (dropped == half && (sticky || kept & 1 == 1));
        kept + u64::from(round_up)
    };
    if !normal {
        return f64::from_bits(rounded);
    }
    let (mantissa, scale) = if rounded == 1u64 << 53 {
        (rounded >> 1, exponent + shift + 1)
    } else {
        (rounded, exponent + shift)
    };
    let biased = scale + 52 + 1023;
    if biased >= 2047 {
        return f64::INFINITY;
    }
    f64::from_bits(((biased as u64) << 52) | (mantissa & ((1u64 << 52) - 1)))
}
