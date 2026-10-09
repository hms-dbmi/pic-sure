//! Reproductions of the JDK and library behavior the Java loaders depend on, where Rust's
//! standard library behaves differently.

use hpds_format::java_trim;

/// The first code point of every run of ten decimal digits that `Character.digit(c, 10)`
/// accepts in the Basic Multilingual Plane (JDK 25). Supplementary digits are surrogate
/// pairs in Java, and `Character.digit` rejects a lone surrogate, so they are not digits
/// for `Integer.parseInt` and `Long.parseLong`.
const BMP_DIGIT_ZEROS: [u32; 37] = [
    0x0030, 0x0660, 0x06F0, 0x07C0, 0x0966, 0x09E6, 0x0A66, 0x0AE6, 0x0B66, 0x0BE6, 0x0C66, 0x0CE6,
    0x0D66, 0x0DE6, 0x0E50, 0x0ED0, 0x0F20, 0x1040, 0x1090, 0x17E0, 0x1810, 0x1946, 0x19D0, 0x1A80,
    0x1A90, 0x1B50, 0x1BB0, 0x1C40, 0x1C50, 0xA620, 0xA8D0, 0xA900, 0xA9D0, 0xA9F0, 0xAA50, 0xABF0,
    0xFF10,
];

/// The value of `c` as a decimal digit under `Character.digit(c, 10)`, or `None`.
fn java_decimal_digit(c: char) -> Option<u32> {
    let code = u32::from(c);
    if code > 0xFFFF {
        return None;
    }
    let zero = BMP_DIGIT_ZEROS
        .partition_point(|&z| z <= code)
        .checked_sub(1)?;
    let offset = code - BMP_DIGIT_ZEROS[zero];
    (offset < 10).then_some(offset)
}

/// True for the characters `Character.isWhitespace` accepts: the Unicode space, line and
/// paragraph separators other than the no-break spaces, plus U+0009 to U+000D and U+001C
/// to U+001F.
pub fn is_java_whitespace(c: char) -> bool {
    matches!(
        c,
        '\u{9}'..='\u{D}'
            | '\u{1C}'..='\u{20}'
            | '\u{1680}'
            | '\u{2000}'..='\u{2006}'
            | '\u{2008}'..='\u{200A}'
            | '\u{2028}'
            | '\u{2029}'
            | '\u{205F}'
            | '\u{3000}'
    )
}

/// True when `s` is empty or every character is [`is_java_whitespace`], as log4j's
/// `Strings.isBlank` decides.
pub fn is_log4j_blank(s: &str) -> bool {
    s.chars().all(is_java_whitespace)
}

/// Parses `s` with the grammar of `Long.parseLong`: an optional `+` or `-`, then one or
/// more decimal digits as `Character.digit` reads them, with no surrounding whitespace.
/// Returns `None` where Java throws `NumberFormatException`, including on overflow.
pub fn parse_java_long(s: &str) -> Option<i64> {
    let (negative, digits) = match s.as_bytes().first()? {
        b'-' => (true, &s[1..]),
        b'+' => (false, &s[1..]),
        _ => (false, s),
    };
    if digits.is_empty() {
        return None;
    }
    let mut value: i64 = 0;
    for c in digits.chars() {
        let d = i64::from(java_decimal_digit(c)?);
        value = value.checked_mul(10)?;
        value = if negative {
            value.checked_sub(d)?
        } else {
            value.checked_add(d)?
        };
    }
    Some(value)
}

/// Parses `s` with the grammar of `Integer.parseInt`; see [`parse_java_long`].
pub fn parse_java_int(s: &str) -> Option<i32> {
    i32::try_from(parse_java_long(s)?).ok()
}

/// `Double.min`: NaN when either argument is NaN, and `-0.0` below `0.0`.
pub fn java_double_min(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_negative() { a } else { b }
    } else if a <= b {
        a
    } else {
        b
    }
}

/// `Double.max`: NaN when either argument is NaN, and `0.0` above `-0.0`.
pub fn java_double_max(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        a
    } else if b.is_nan() {
        b
    } else if a == 0.0 && b == 0.0 {
        if a.is_sign_positive() { a } else { b }
    } else if a >= b {
        a
    } else {
        b
    }
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

/// Formats `value` exactly as JDK 19 and later `Double.toString` does.
///
/// The digits are the shortest decimal that rounds to `value`, closest to it when several
/// qualify. When one digit would do, Java considers two-digit decimals as well and takes the
/// closest, which is why the smallest subnormal prints as `4.9E-324`. Magnitudes in
/// `[1e-3, 1e7)` print in plain notation with at least one fractional digit, everything else
/// as `d.dddE<n>`.
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

/// Normalizes a raw `CONCEPT_PATH` the way `CSVParserUtil.parseConceptPath` does before
/// rollup: replaces `µ` with `\`, splits on `\` dropping trailing empty segments (Java
/// `String.split`), trims every segment with `String.trim`, rejoins with `\`, appends a
/// trailing `\`, and removes every U+FFFD.
pub fn normalize_concept_path(raw: &str) -> String {
    let replaced = raw.replace('µ', "\\");
    let mut segments: Vec<&str> = replaced.split('\\').collect();
    while segments.last() == Some(&"") {
        segments.pop();
    }
    let mut out = String::with_capacity(replaced.len() + 1);
    for (i, segment) in segments.iter().enumerate() {
        if i > 0 {
            out.push('\\');
        }
        out.push_str(java_trim(segment));
    }
    out.push('\\');
    out.retain(|c| c != '\u{FFFD}');
    out
}

/// The text value as the rollup rule compares it: trimmed with `String.trim`, then with
/// every U+FFFD removed.
pub fn rollup_text(text: &str) -> std::borrow::Cow<'_, str> {
    let trimmed = java_trim(text);
    if trimmed.contains('\u{FFFD}') {
        std::borrow::Cow::Owned(trimmed.replace('\u{FFFD}', ""))
    } else {
        std::borrow::Cow::Borrowed(trimmed)
    }
}

/// Applies the variable-name rollup to a normalized path: when `path` ends with
/// `\<text>\`, returns `path` with its last node removed (the regex
/// `\\[^\\]*\\$` replaced by `\`). `text` is the output of [`rollup_text`]. Returns `None`
/// when the rule does not apply.
pub fn roll_up(path: &str, text: &str) -> Option<String> {
    let body = path.strip_suffix('\\')?;
    let before = body.strip_suffix(text)?;
    if !before.ends_with('\\') {
        return None;
    }
    let cut = body.rfind('\\')?;
    Some(path[..=cut].to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decimal_digits_follow_character_digit() {
        assert_eq!(java_decimal_digit('7'), Some(7));
        assert_eq!(java_decimal_digit('\u{0669}'), Some(9));
        assert_eq!(java_decimal_digit('\u{FF15}'), Some(5));
        assert_eq!(java_decimal_digit('a'), None);
        assert_eq!(java_decimal_digit('\u{1D7CE}'), None);
        assert_eq!(java_decimal_digit('\u{065F}'), None);
    }

    #[test]
    fn parse_int_matches_integer_parse_int() {
        assert_eq!(parse_java_int("00900"), Some(900));
        assert_eq!(parse_java_int("+901"), Some(901));
        assert_eq!(parse_java_int("-5"), Some(-5));
        assert_eq!(parse_java_int("-0"), Some(0));
        assert_eq!(parse_java_int("2147483647"), Some(i32::MAX));
        assert_eq!(parse_java_int("-2147483648"), Some(i32::MIN));
        assert_eq!(parse_java_int("2147483648"), None);
        assert_eq!(parse_java_int("\u{0661}\u{0662}"), Some(12));
        for bad in ["", "+", "-", " 1", "1 ", "1.0", "1e3", "0x1", "++1", "1_0"] {
            assert_eq!(parse_java_int(bad), None, "{bad:?}");
        }
    }

    #[test]
    fn parse_long_matches_long_parse_long() {
        assert_eq!(parse_java_long("1700000000000"), Some(1_700_000_000_000));
        assert_eq!(parse_java_long("-5"), Some(-5));
        assert_eq!(parse_java_long("9223372036854775807"), Some(i64::MAX));
        assert_eq!(parse_java_long("-9223372036854775808"), Some(i64::MIN));
        assert_eq!(parse_java_long("12345678901234567890"), None);
        assert_eq!(parse_java_long(" 1000"), None);
        assert_eq!(parse_java_long("1.5"), None);
        assert_eq!(parse_java_long("abc"), None);
    }

    #[test]
    fn blank_follows_character_is_whitespace() {
        assert!(is_log4j_blank(""));
        assert!(is_log4j_blank(" \t\r\n\u{1C}\u{2003}\u{3000}"));
        assert!(!is_log4j_blank("\u{A0}"));
        assert!(!is_log4j_blank("\u{2007}"));
        assert!(!is_log4j_blank("\u{202F}"));
        assert!(!is_log4j_blank("\u{1}"));
        assert!(!is_log4j_blank(" x "));
    }

    #[test]
    fn min_and_max_follow_double_min_and_max() {
        assert!(java_double_min(1.0, f64::NAN).is_nan());
        assert!(java_double_max(f64::NAN, 1.0).is_nan());
        assert!(java_double_min(0.0, -0.0).is_sign_negative());
        assert!(java_double_min(-0.0, 0.0).is_sign_negative());
        assert!(java_double_max(-0.0, 0.0).is_sign_positive());
        assert!(java_double_max(0.0, -0.0).is_sign_positive());
        assert_eq!(java_double_min(-7.25, 42.0), -7.25);
        assert_eq!(java_double_max(-7.25, -42.0), -7.25);
    }

    #[test]
    fn double_to_string_examples() {
        let cases = [
            (1.0, "1.0"),
            (-7.25, "-7.25"),
            (1e7, "1.0E7"),
            (9_999_999.0, "9999999.0"),
            (0.001, "0.001"),
            (0.0001, "1.0E-4"),
            (100.0, "100.0"),
            (1e23, "1.0E23"),
            (f64::MAX, "1.7976931348623157E308"),
            (f64::from_bits(1), "4.9E-324"),
            (0.5, "0.5"),
            (60.5, "60.5"),
            (123_456_789.0, "1.23456789E8"),
            (-0.0, "-0.0"),
            (f64::NEG_INFINITY, "-Infinity"),
        ];
        for (value, expected) in cases {
            assert_eq!(java_double_to_string(value), expected, "{value:e}");
        }
    }

    #[test]
    fn concept_path_normalization_examples() {
        assert_eq!(
            normalize_concept_path("µedgeµmuµvar_a"),
            "\\edge\\mu\\var_a\\"
        );
        assert_eq!(
            normalize_concept_path("\\ edge \\  spaces\\var_a  \\"),
            "\\edge\\spaces\\var_a\\"
        );
        assert_eq!(normalize_concept_path(""), "\\");
        assert_eq!(normalize_concept_path("\\\\"), "\\");
        assert_eq!(normalize_concept_path("\\a\\ "), "\\a\\\\");
        assert_eq!(
            normalize_concept_path("\\edge\\ffd\\va\u{FFFD}r_a\\"),
            "\\edge\\ffd\\var_a\\"
        );
        assert_eq!(
            normalize_concept_path("\\a\\\u{A0}b\u{A0}\\"),
            "\\a\\\u{A0}b\u{A0}\\"
        );
    }

    #[test]
    fn rollup_examples() {
        assert_eq!(
            roll_up("\\a\\Yes\\", &rollup_text("  Yes  ")).as_deref(),
            Some("\\a\\")
        );
        assert_eq!(
            roll_up("\\a\\Maybe\\", &rollup_text("May\u{FFFD}be")).as_deref(),
            Some("\\a\\")
        );
        assert_eq!(roll_up("\\a\\b\\", "B"), None);
        assert_eq!(roll_up("\\a\\b\\c\\", "b\\c").as_deref(), Some("\\a\\b\\"));
        assert_eq!(roll_up("\\x\\", "x").as_deref(), Some("\\"));
        assert_eq!(roll_up("x\\", "x"), None);
        assert_eq!(roll_up("\\\\", "").as_deref(), Some("\\"));
        assert_eq!(roll_up("\\a\\\\b\\", "b").as_deref(), Some("\\a\\\\"));
    }
}
