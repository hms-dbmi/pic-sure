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

/// Splits Rust's `{:e}` rendering of a finite positive double into its significant digits,
/// without trailing zeros, and its decimal exponent: the value is `d.ddd × 10^exponent`.
fn scientific_digits(rendered: &str) -> (String, i32) {
    let (mantissa, exponent) = rendered.split_once('e').unwrap_or((rendered, "0"));
    let mut digits: String = mantissa.chars().filter(|c| *c != '.').collect();
    while digits.len() > 1 && digits.ends_with('0') {
        digits.pop();
    }
    (digits, exponent.parse().unwrap_or(0))
}

/// Formats `value` exactly as JDK 19 and later `Double.toString` does, which is also how
/// Jackson writes a `Double` map key and how `String.valueOf` renders a boxed `Double`.
///
/// The digits are the shortest decimal that rounds to `value`, closest to it when several
/// qualify. When one digit would do, Java considers two-digit decimals as well and takes the
/// closest, which is why the smallest subnormal prints as `4.9E-324`. Magnitudes in
/// `[1e-3, 1e7)` print in plain notation with at least one fractional digit, everything else
/// as `d.dddE<n>`. NaN prints as `NaN` and the infinities as `Infinity` and `-Infinity`.
pub fn java_double_to_string(value: f64) -> String {
    if value.is_nan() {
        return "NaN".into();
    }
    if value.is_infinite() {
        return if value > 0.0 { "Infinity" } else { "-Infinity" }.into();
    }
    if value == 0.0 {
        return if value.is_sign_negative() {
            "-0.0"
        } else {
            "0.0"
        }
        .into();
    }
    let magnitude = value.abs();
    let (mut digits, mut exponent) = scientific_digits(&format!("{magnitude:e}"));
    if digits.len() == 1 {
        let two = format!("{magnitude:.1e}");
        if two.parse::<f64>() == Ok(magnitude) {
            (digits, exponent) = scientific_digits(&two);
        }
    }
    let mut out = String::with_capacity(digits.len() + 8);
    if value < 0.0 {
        out.push('-');
    }
    if (-3..7).contains(&exponent) {
        if exponent >= 0 {
            let int_len = exponent as usize + 1;
            if digits.len() <= int_len {
                out.push_str(&digits);
                out.extend(std::iter::repeat_n('0', int_len - digits.len()));
                out.push_str(".0");
            } else {
                out.push_str(&digits[..int_len]);
                out.push('.');
                out.push_str(&digits[int_len..]);
            }
        } else {
            out.push_str("0.");
            out.extend(std::iter::repeat_n('0', (-exponent - 1) as usize));
            out.push_str(&digits);
        }
    } else {
        out.push_str(&digits[..1]);
        out.push('.');
        out.push_str(if digits.len() > 1 { &digits[1..] } else { "0" });
        out.push('E');
        out.push_str(&exponent.to_string());
    }
    out
}

const DIGIT_PAIRS: &[u8; 200] = b"0001020304050607080910111213141516171819\
2021222324252627282930313233343536373839\
4041424344454647484950515253545556575859\
6061626364656667686970717273747576777879\
8081828384858687888990919293949596979899";

/// Appends the decimal digits of `value` to `out`, the bytes `value.to_string()` gives,
/// without going through `core::fmt`.
pub fn push_decimal_u32(value: u32, out: &mut Vec<u8>) {
    let mut buffer = [0u8; 10];
    let mut start = buffer.len();
    let mut rest = value;
    while rest >= 100 {
        let pair = (rest % 100) as usize * 2;
        rest /= 100;
        start -= 2;
        buffer[start..start + 2].copy_from_slice(&DIGIT_PAIRS[pair..pair + 2]);
    }
    if rest >= 10 {
        let pair = rest as usize * 2;
        start -= 2;
        buffer[start..start + 2].copy_from_slice(&DIGIT_PAIRS[pair..pair + 2]);
    } else {
        start -= 1;
        buffer[start] = b'0' + rest as u8;
    }
    out.extend_from_slice(&buffer[start..]);
}

/// A fixed buffer on the stack that `core::fmt` writes into, long enough for any `{:e}`
/// rendering of a finite double (at most 17 digits, a point, `e` and a four-character
/// exponent).
struct StackText {
    bytes: [u8; 32],
    len: usize,
}

impl StackText {
    fn render(args: std::fmt::Arguments<'_>) -> Self {
        use std::fmt::Write;
        let mut text = StackText {
            bytes: [0; 32],
            len: 0,
        };
        let _ = text.write_fmt(args);
        text
    }

    fn as_bytes(&self) -> &[u8] {
        &self.bytes[..self.len]
    }
}

impl std::fmt::Write for StackText {
    fn write_str(&mut self, s: &str) -> std::fmt::Result {
        let end = self.len + s.len();
        let target = self.bytes.get_mut(self.len..end).ok_or(std::fmt::Error)?;
        target.copy_from_slice(s.as_bytes());
        self.len = end;
        Ok(())
    }
}

/// The significant digits of a `{:e}` rendering without trailing zeros, and its decimal
/// exponent, as [`scientific_digits`] splits them, held on the stack.
struct Scientific {
    digits: [u8; 24],
    len: usize,
    exponent: i32,
}

impl Scientific {
    fn parse(rendered: &[u8]) -> Self {
        let split = rendered
            .iter()
            .position(|&b| b == b'e')
            .unwrap_or(rendered.len());
        let mut parsed = Scientific {
            digits: [0; 24],
            len: 0,
            exponent: 0,
        };
        for &b in rendered[..split].iter().filter(|&&b| b != b'.') {
            if let Some(slot) = parsed.digits.get_mut(parsed.len) {
                *slot = b;
                parsed.len += 1;
            }
        }
        while parsed.len > 1 && parsed.digits[parsed.len - 1] == b'0' {
            parsed.len -= 1;
        }
        let exponent = rendered.get(split + 1..).unwrap_or_default();
        parsed.exponent = std::str::from_utf8(exponent)
            .ok()
            .and_then(|e| e.parse().ok())
            .unwrap_or(0);
        parsed
    }

    fn digits(&self) -> &[u8] {
        &self.digits[..self.len]
    }
}

/// Appends [`java_double_to_string`]`(value)` to `out` without allocating: the same digits
/// and layout, rendered on the stack. Whole magnitudes in `[1, 1e7)` skip `core::fmt`
/// entirely, since their shortest decimal is the integer itself.
pub fn push_java_double(value: f64, out: &mut Vec<u8>) {
    if value.is_nan() {
        out.extend_from_slice(b"NaN");
        return;
    }
    if value.is_infinite() {
        out.extend_from_slice(if value > 0.0 {
            b"Infinity"
        } else {
            b"-Infinity"
        });
        return;
    }
    if value == 0.0 {
        out.extend_from_slice(if value.is_sign_negative() {
            b"-0.0"
        } else {
            b"0.0"
        });
        return;
    }
    let magnitude = value.abs();
    if value < 0.0 {
        out.push(b'-');
    }
    if (1.0..1e7).contains(&magnitude) && magnitude.fract() == 0.0 {
        push_decimal_u32(magnitude as u32, out);
        out.extend_from_slice(b".0");
        return;
    }
    let mut scientific =
        Scientific::parse(StackText::render(format_args!("{magnitude:e}")).as_bytes());
    if scientific.len == 1 {
        let two = StackText::render(format_args!("{magnitude:.1e}"));
        let round_trips = std::str::from_utf8(two.as_bytes())
            .ok()
            .and_then(|t| t.parse::<f64>().ok())
            == Some(magnitude);
        if round_trips {
            scientific = Scientific::parse(two.as_bytes());
        }
    }
    let digits = scientific.digits();
    let exponent = scientific.exponent;
    if (-3..7).contains(&exponent) {
        if exponent >= 0 {
            let int_len = exponent as usize + 1;
            if digits.len() <= int_len {
                out.extend_from_slice(digits);
                out.resize(out.len() + int_len - digits.len(), b'0');
                out.extend_from_slice(b".0");
            } else {
                out.extend_from_slice(&digits[..int_len]);
                out.push(b'.');
                out.extend_from_slice(&digits[int_len..]);
            }
        } else {
            out.extend_from_slice(b"0.");
            out.resize(out.len() + (-exponent - 1) as usize, b'0');
            out.extend_from_slice(digits);
        }
    } else {
        out.extend_from_slice(&digits[..1]);
        out.push(b'.');
        out.extend_from_slice(if digits.len() > 1 { &digits[1..] } else { b"0" });
        out.push(b'E');
        if exponent < 0 {
            out.push(b'-');
        }
        push_decimal_u32(exponent.unsigned_abs(), out);
    }
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
