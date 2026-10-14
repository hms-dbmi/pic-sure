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
        let written = text.write_fmt(args);
        debug_assert!(written.is_ok(), "a rendering overflowed the stack buffer");
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
            debug_assert!(parsed.len < parsed.digits.len(), "more than 24 digits");
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

/// The most digits a number token may hold, `StreamReadConstraints.DEFAULT_MAX_NUM_LEN`.
pub const MAX_NUMBER_DIGITS: usize = 1000;

/// A number longer than Jackson allows: a token with more than [`MAX_NUMBER_DIGITS`] digits,
/// or a string coerced to a double with more than [`MAX_NUMBER_DIGITS`] characters once
/// trimmed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NumberTooLong {
    /// The digits the token holds, or the characters of the trimmed string.
    pub digits: usize,
}

impl std::fmt::Display for NumberTooLong {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "Number value length ({}) exceeds the maximum allowed ({MAX_NUMBER_DIGITS}, from `StreamReadConstraints.getMaxNumberLength()`)",
            self.digits
        )
    }
}

impl std::error::Error for NumberTooLong {}

/// Why a string is not a double Jackson accepts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StringDoubleError {
    /// Not a number in the form Jackson parses.
    Invalid,
    /// Longer than [`MAX_NUMBER_DIGITS`] characters once trimmed.
    TooLong(NumberTooLong),
}

/// A `Double` field given as a JSON string, read as Jackson 2.21's `StdDeserializer` coerces
/// it (checked against Jackson 2.21.5 on every string of up to five characters over
/// `0 1 . e E + - n u l` and space, 177,156 strings).
///
/// - `NaN`, `Infinity`, `+Infinity`, `-Infinity`, `INF`, `+INF` and `-INF`, exactly as
///   written, are the special values; any other spelling (`-NaN`, `inf`, ` Infinity`) is not.
/// - Otherwise the text is trimmed of characters up to the space, as `String.trim` does; an
///   empty result, or exactly `null` (`_checkTextualNull`, with scalar coercion on as it is by
///   default), is `None`. `NULL` and `Null` are not null.
/// - The rest must be a plain decimal (`NumberInput.looksLikeValidNumber`): an optional sign,
///   then digits with an optional fraction and an optional exponent, digits and a trailing dot
///   (`1.`), or a dot and digits with an optional exponent (`.5e1`). Java suffixes (`1d`,
///   `1f`), hexadecimal (`0x1p3`), underscores and `1.e5` are refused, although
///   `Double.parseDouble` takes some of them. This check comes first, so a malformed text of
///   any length is refused as malformed.
/// - A plain decimal longer than [`MAX_NUMBER_DIGITS`] characters, sign and dot included, is
///   then refused as `StreamReadConstraints.validateFPLength` refuses it.
/// - The value is the correctly rounded double, overflow to infinity included.
///
/// # Errors
/// [`StringDoubleError::TooLong`] and [`StringDoubleError::Invalid`] as above.
pub fn jackson_string_double(text: &str) -> Result<Option<f64>, StringDoubleError> {
    match text {
        "NaN" => return Ok(Some(f64::NAN)),
        "Infinity" | "+Infinity" | "INF" | "+INF" => return Ok(Some(f64::INFINITY)),
        "-Infinity" | "-INF" => return Ok(Some(f64::NEG_INFINITY)),
        _ => {}
    }
    let trimmed = text.trim_matches(|c: char| c <= ' ');
    if trimmed.is_empty() || trimmed == "null" {
        return Ok(None);
    }
    if !is_plain_decimal(trimmed.as_bytes()) {
        return Err(StringDoubleError::Invalid);
    }
    let length = trimmed.len();
    if length > MAX_NUMBER_DIGITS {
        return Err(StringDoubleError::TooLong(NumberTooLong { digits: length }));
    }
    trimmed
        .parse::<f64>()
        .map(Some)
        .map_err(|_| StringDoubleError::Invalid)
}

/// Whether `text` matches `[+-]?(D+(\.D+)?([eE][+-]?D+)?|D+\.|\.D+([eE][+-]?D+)?)`, `D` an
/// ASCII digit.
fn is_plain_decimal(text: &[u8]) -> bool {
    let digits = |from: usize| {
        text[from..]
            .iter()
            .take_while(|b| b.is_ascii_digit())
            .count()
    };
    let mut i = usize::from(matches!(text.first(), Some(b'+' | b'-')));
    let integer = digits(i);
    i += integer;
    let mut fraction = 0;
    let mut dot = false;
    if text.get(i) == Some(&b'.') {
        dot = true;
        i += 1;
        fraction = digits(i);
        i += fraction;
    }
    if integer == 0 && fraction == 0 {
        return false;
    }
    if i == text.len() {
        return true;
    }
    if dot && fraction == 0 {
        return false;
    }
    if !matches!(text[i], b'e' | b'E') {
        return false;
    }
    i += 1;
    if matches!(text.get(i), Some(b'+' | b'-')) {
        i += 1;
    }
    let exponent = digits(i);
    exponent > 0 && i + exponent == text.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_coerce_to_doubles_as_jackson_coerces_them() {
        let ok = |text: &str| jackson_string_double(text).unwrap();
        let refused = |text: &str| jackson_string_double(text) == Err(StringDoubleError::Invalid);
        for text in [
            "1d",
            "1f",
            "1D",
            "1F",
            "-NaN",
            "+NaN",
            "inf",
            "nan",
            "0x1p3",
            "0X1P3",
            "1_000",
            "NULL",
            "Null",
            "nul",
            "null1",
            "1.e5",
            ".e5",
            "e5",
            "1e",
            "1e+",
            "-",
            "+",
            "--1",
            "+-1",
            "1.2.3",
            "1e5.5",
            "1ee5",
            " Infinity",
            "Infinity ",
            " NaN",
            "1,5",
            "1.5e-3d",
            "1\u{a0}",
        ] {
            assert!(refused(text), "{text:?}");
        }
        assert!(ok("NaN").unwrap().is_nan());
        for (text, value) in [
            ("Infinity", f64::INFINITY),
            ("+Infinity", f64::INFINITY),
            ("-Infinity", f64::NEG_INFINITY),
            ("INF", f64::INFINITY),
            ("+INF", f64::INFINITY),
            ("-INF", f64::NEG_INFINITY),
            ("+1", 1.0),
            (" 1", 1.0),
            ("1 ", 1.0),
            (" 1 ", 1.0),
            ("1.", 1.0),
            ("-1.", -1.0),
            (".5", 0.5),
            ("-.5", -0.5),
            ("+.5", 0.5),
            ("1e5", 1e5),
            ("1E+2", 100.0),
            ("+1e+5", 1e5),
            ("1e+05", 1e5),
            ("00012", 12.0),
            ("1e400", f64::INFINITY),
            ("-1e400", f64::NEG_INFINITY),
            ("1e-400", 0.0),
            ("2.4703282292062328e-324", 5e-324),
        ] {
            assert_eq!(
                ok(text).map(f64::to_bits),
                Some(value.to_bits()),
                "{text:?}"
            );
        }
        for (text, bits) in [
            ("-0", 0x8000_0000_0000_0000_u64),
            ("-0.0", 0x8000_0000_0000_0000),
            ("0e0", 0),
        ] {
            assert_eq!(ok(text).map(f64::to_bits), Some(bits), "{text:?}");
        }
        for text in ["", " ", "  ", "null", " null ", "\tnull\n"] {
            assert_eq!(ok(text), None, "{text:?}");
        }
    }

    #[test]
    fn coerced_strings_longer_than_the_limit_are_refused_counting_sign_and_dot() {
        let too_long = |text: &str| {
            matches!(
                jackson_string_double(text),
                Err(StringDoubleError::TooLong(NumberTooLong { digits: 1001 }))
            )
        };
        assert!(jackson_string_double(&"1".repeat(1000)).is_ok());
        for malformed in [
            format!("{}d", "1".repeat(1000)),
            format!("{}d", "1".repeat(1001)),
            "x".repeat(1001),
        ] {
            assert_eq!(
                jackson_string_double(&malformed),
                Err(StringDoubleError::Invalid)
            );
        }
        assert!(too_long(&"1".repeat(1001)));
        assert!(too_long(&format!("-{}", "1".repeat(1000))));
        assert!(too_long(&format!("1.{}", "1".repeat(999))));
        assert!(too_long(&format!("1e{}1", "0".repeat(998))));
        assert!(jackson_string_double(&format!(" {}", "1".repeat(1000))).is_ok());
        assert!(jackson_string_double(&format!("{} ", "1".repeat(1000))).is_ok());
    }
}
