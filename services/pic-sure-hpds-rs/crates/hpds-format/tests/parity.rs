use std::cmp::Ordering;

use hpds_format::{
    canonical_nan, java_double_cmp, java_double_to_string, java_string_cmp, java_trim,
    parse_java_double, push_decimal_u32, push_java_double,
};
use proptest::prelude::*;

/// Expected `Double.doubleToRawLongBits(Double.parseDouble(input))`, or `None` where Java
/// throws `NumberFormatException`. Recorded from JDK 25 jshell.
const JAVA_PARSE_DOUBLE: &[(&str, Option<u64>)] = &[
    ("1", Some(0x3ff0000000000000)),
    (" 1 ", Some(0x3ff0000000000000)),
    ("\t1\n", Some(0x3ff0000000000000)),
    ("\u{a0}1", None),
    ("1\u{1}", Some(0x3ff0000000000000)),
    ("+1", Some(0x3ff0000000000000)),
    ("-1", Some(0xbff0000000000000)),
    ("++1", None),
    ("1.", Some(0x3ff0000000000000)),
    (".5", Some(0x3fe0000000000000)),
    (".", None),
    ("", None),
    (" ", None),
    ("1e5", Some(0x40f86a0000000000)),
    ("1E+5", Some(0x40f86a0000000000)),
    ("1e-5", Some(0x3ee4f8b588e368f1)),
    ("1e", None),
    ("e5", None),
    ("1.5f", Some(0x3ff8000000000000)),
    ("1.5F", Some(0x3ff8000000000000)),
    ("1.5d", Some(0x3ff8000000000000)),
    ("1.5D", Some(0x3ff8000000000000)),
    ("1.5x", None),
    ("1f ", Some(0x3ff0000000000000)),
    ("1 f", None),
    ("NaN", Some(0x7ff8000000000000)),
    ("-NaN", Some(0x7ff8000000000000)),
    ("+NaN", Some(0x7ff8000000000000)),
    ("nan", None),
    ("NaNd", None),
    ("NaN ", Some(0x7ff8000000000000)),
    ("Infinity", Some(0x7ff0000000000000)),
    ("-Infinity", Some(0xfff0000000000000)),
    ("+Infinity", Some(0x7ff0000000000000)),
    ("infinity", None),
    ("Infinityf", None),
    ("Inf", None),
    ("0x1p1", Some(0x4000000000000000)),
    ("0x1.8p1", Some(0x4008000000000000)),
    ("0X1.8P1", Some(0x4008000000000000)),
    ("0x.8p1", Some(0x3ff0000000000000)),
    ("0x1.p1", Some(0x4000000000000000)),
    ("0xp1", None),
    ("0x1", None),
    ("0x1.8p1f", Some(0x4008000000000000)),
    ("0x1.8p1d", Some(0x4008000000000000)),
    ("-0x1p-1074", Some(0x8000000000000001)),
    ("0x1p-1075", Some(0x0)),
    ("0x1.0000000000001p-1075", Some(0x1)),
    ("0x1.fffffffffffff8p1023", Some(0x7ff0000000000000)),
    ("0x1.fffffffffffff7ffp1023", Some(0x7fefffffffffffff)),
    ("0x1.00000000000008p0", Some(0x3ff0000000000000)),
    ("0x1.00000000000018p0", Some(0x3ff0000000000002)),
    ("0x1.000000000000080001p0", Some(0x3ff0000000000001)),
    ("1_0", None),
    ("1,0", None),
    ("-0", Some(0x8000000000000000)),
    ("-0.0", Some(0x8000000000000000)),
    ("1e400", Some(0x7ff0000000000000)),
    ("1e-400", Some(0x0)),
    ("4.9e-324", Some(0x1)),
    ("2.4703282292062328e-324", Some(0x1)),
    ("2.4703282292062327e-324", Some(0x0)),
    ("0x1p+1", Some(0x4000000000000000)),
    ("0x1p-0", Some(0x3ff0000000000000)),
    ("00012.5", Some(0x4029000000000000)),
    ("1e0005", Some(0x40f86a0000000000)),
    ("\u{ff11}", None),
    ("0x1P2D", Some(0x4010000000000000)),
    ("0x", None),
    ("1d1", None),
    ("1e1.5", None),
    ("+-1", None),
    ("0x-1p1", None),
    ("-0x1p1", Some(0xc000000000000000)),
    ("Infinity1", None),
    ("1.7976931348623158e308", Some(0x7fefffffffffffff)),
    ("1.7976931348623159e308", Some(0x7ff0000000000000)),
    ("0x0p0", Some(0x0)),
    ("0x0.0000000000001p-1022", Some(0x1)),
    ("0x1.fffffffffffffp-1023", Some(0x10000000000000)),
    ("1.e5", Some(0x40f86a0000000000)),
    ("1e5d", Some(0x40f86a0000000000)),
    ("0x1.8p1 ", Some(0x4008000000000000)),
    ("0x1.8.8p1", None),
    ("0x1p99999999999999999999", Some(0x7ff0000000000000)),
    ("0x1p-99999999999999999999", Some(0x0)),
];

#[test]
fn parse_java_double_matches_jdk_table() {
    for &(input, expected) in JAVA_PARSE_DOUBLE {
        let actual = parse_java_double(input).map(f64::to_bits);
        assert_eq!(actual, expected, "input {input:?}");
    }
}

proptest! {
    #[test]
    fn parse_java_double_round_trips_rust_formatting(bits in any::<u64>()) {
        let value = f64::from_bits(bits);
        prop_assume!(!value.is_nan());
        prop_assert_eq!(parse_java_double(&format!("{value:e}")).map(f64::to_bits), Some(bits));
        prop_assert_eq!(parse_java_double(&format!("{value:?}")).map(f64::to_bits), Some(bits));
    }

    #[test]
    fn hex_literals_match_rust_value(bits in any::<u64>()) {
        let value = f64::from_bits(bits);
        prop_assume!(value.is_finite() && value != 0.0);
        let sign = if value < 0.0 { "-" } else { "" };
        let exponent = ((bits >> 52) & 0x7ff) as i64;
        let fraction = bits & ((1u64 << 52) - 1);
        let literal = if exponent == 0 {
            format!("{sign}0x0.{fraction:013x}p-1022")
        } else {
            format!("{sign}0x1.{fraction:013x}p{}", exponent - 1023)
        };
        prop_assert_eq!(parse_java_double(&literal).map(f64::to_bits), Some(bits), "{}", literal);
    }

    #[test]
    fn java_string_cmp_matches_utf16_order(a in "\\PC{0,6}", b in "\\PC{0,6}") {
        let expected = a.encode_utf16().cmp(b.encode_utf16());
        prop_assert_eq!(java_string_cmp(&a, &b), expected);
    }

    #[test]
    fn java_string_cmp_matches_utf16_order_near_surrogates(
        a in proptest::collection::vec(prop_oneof![Just('a'), Just('\u{e000}'), Just('\u{ffff}'), Just('\u{10000}'), Just('\u{1f600}')], 0..5),
        b in proptest::collection::vec(prop_oneof![Just('a'), Just('\u{e000}'), Just('\u{ffff}'), Just('\u{10000}'), Just('\u{1f600}')], 0..5),
    ) {
        let (a, b): (String, String) = (a.into_iter().collect(), b.into_iter().collect());
        prop_assert_eq!(java_string_cmp(&a, &b), a.encode_utf16().cmp(b.encode_utf16()));
    }
}

#[test]
fn supplementary_characters_sort_before_private_use_area() {
    assert_eq!(java_string_cmp("\u{1f600}", "\u{e000}"), Ordering::Less);
    assert_eq!(java_string_cmp("\u{10000}", "\u{ffff}"), Ordering::Less);
    assert_eq!(java_string_cmp("x\u{1f600}", "x\u{fffd}"), Ordering::Less);
    assert_eq!(java_string_cmp("\u{e000}", "\u{1f600}"), Ordering::Greater);
    assert_eq!("\u{1f600}".cmp("\u{e000}"), Ordering::Greater);
    assert_eq!(java_string_cmp("\u{d7ff}", "\u{10000}"), Ordering::Less);
    assert_eq!(java_string_cmp("ab", "abc"), Ordering::Less);
    assert_eq!(java_string_cmp("", ""), Ordering::Equal);
    assert_eq!(java_string_cmp("\u{e9}", "\u{e8}"), Ordering::Greater);
}

#[test]
fn double_order_matches_double_compare_to() {
    let negative_nan = f64::from_bits(0xfff8000000000001);
    let payload_nan = f64::from_bits(0x7ff0000000000001);
    assert_eq!(java_double_cmp(-0.0, 0.0), Ordering::Less);
    assert_eq!(java_double_cmp(0.0, -0.0), Ordering::Greater);
    assert_eq!(java_double_cmp(f64::NAN, negative_nan), Ordering::Equal);
    assert_eq!(java_double_cmp(payload_nan, f64::NAN), Ordering::Equal);
    assert_eq!(
        java_double_cmp(negative_nan, f64::INFINITY),
        Ordering::Greater
    );
    assert_eq!(
        java_double_cmp(f64::NEG_INFINITY, -f64::MAX),
        Ordering::Less
    );
    assert_eq!(java_double_cmp(1.0, 2.0), Ordering::Less);
    assert_eq!(canonical_nan(negative_nan).to_bits(), 0x7ff8000000000000);

    let mut values = [
        f64::NAN,
        1.0,
        0.0,
        -0.0,
        negative_nan,
        f64::NEG_INFINITY,
        f64::INFINITY,
        -1.0,
    ];
    values.sort_by(|a, b| java_double_cmp(*a, *b));
    let bits: Vec<u64> = values.iter().map(|v| canonical_nan(*v).to_bits()).collect();
    let expected: Vec<u64> = [
        f64::NEG_INFINITY,
        -1.0,
        -0.0,
        0.0,
        1.0,
        f64::INFINITY,
        f64::NAN,
        f64::NAN,
    ]
    .iter()
    .map(|v| v.to_bits())
    .collect();
    assert_eq!(bits, expected);
}

#[test]
fn java_trim_strips_control_characters_but_not_nbsp() {
    assert_eq!(java_trim("\u{1}\t abc \n\u{0}"), "abc");
    assert_eq!(java_trim("\u{a0}abc\u{a0}"), "\u{a0}abc\u{a0}");
}

#[test]
fn java_double_to_string_matches_jdk() {
    let cases = [
        (1.0, "1.0"),
        (-7.25, "-7.25"),
        (1e7, "1.0E7"),
        (9_999_999.0, "9999999.0"),
        (0.001, "0.001"),
        (0.0001, "1.0E-4"),
        (31.0, "31.0"),
        (1e23, "1.0E23"),
        (f64::MAX, "1.7976931348623157E308"),
        (f64::from_bits(1), "4.9E-324"),
        (123_456_789.0, "1.23456789E8"),
        (-0.0, "-0.0"),
        (f64::NAN, "NaN"),
        (f64::INFINITY, "Infinity"),
        (f64::NEG_INFINITY, "-Infinity"),
    ];
    for (value, expected) in cases {
        assert_eq!(java_double_to_string(value), expected, "{value:e}");
    }
}

fn pushed_double(value: f64) -> String {
    let mut out = b"prefix".to_vec();
    push_java_double(value, &mut out);
    String::from_utf8(out).unwrap().split_off(6)
}

fn pushed_u32(value: u32) -> String {
    let mut out = b"prefix".to_vec();
    push_decimal_u32(value, &mut out);
    String::from_utf8(out).unwrap().split_off(6)
}

/// Every value next to a decimal layout or digit-count boundary: zeros, the infinities, NaN
/// payloads, the subnormal range, every power of ten and its neighbours (which cover the
/// `1e-3` and `1e7` switches between plain and scientific notation), whole numbers around
/// `1e7`, and the extremes.
fn double_edges() -> Vec<f64> {
    let mut values = vec![
        0.0,
        -0.0,
        f64::NAN,
        -f64::NAN,
        f64::from_bits(0x7ff0_0000_0000_0001),
        f64::from_bits(0xfff8_0000_0000_1234),
        f64::INFINITY,
        f64::NEG_INFINITY,
        f64::MIN_POSITIVE,
        f64::MAX,
        f64::MIN,
        f64::EPSILON,
        f64::from_bits(1),
        f64::from_bits(2),
        f64::from_bits(3),
        f64::from_bits(0x000f_ffff_ffff_ffff),
        f64::from_bits(0x0008_0000_0000_0000),
        1e23,
        9_999_999.0,
        9_999_999.5,
        10_000_001.0,
        4_294_967_295.0,
        4_294_967_296.0,
        0.1,
        0.2,
        0.3,
        2.0 / 3.0,
        5e-324,
        1.7976931348623157e308,
    ];
    for exponent in -325..=309 {
        let power: f64 = format!("1e{exponent}").parse().unwrap();
        for value in [
            power,
            power.next_up(),
            power.next_down(),
            5.0 * power,
            9.5 * power,
        ] {
            values.push(value);
        }
    }
    for whole in (0..2_000).chain(9_998_000..10_002_000) {
        values.push(f64::from(whole));
        values.push(f64::from(whole) + 0.5);
    }
    let negated: Vec<f64> = values.iter().map(|v| -v).collect();
    values.extend(negated);
    values
}

#[test]
fn push_java_double_matches_java_double_to_string_on_edges() {
    for value in double_edges() {
        assert_eq!(
            pushed_double(value),
            java_double_to_string(value),
            "bits {:#x}",
            value.to_bits()
        );
    }
}

#[test]
fn push_decimal_u32_matches_to_string_on_edges() {
    let mut values = vec![
        0,
        1,
        9,
        10,
        11,
        99,
        100,
        101,
        999,
        1_000,
        u32::MAX,
        u32::MAX - 1,
    ];
    for digits in 1..10 {
        let power = 10u32.pow(digits);
        values.extend([power - 1, power, power + 1]);
    }
    for value in values {
        assert_eq!(pushed_u32(value), value.to_string());
    }
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(20_000))]

    #[test]
    fn push_java_double_matches_java_double_to_string(bits in any::<u64>()) {
        let value = f64::from_bits(bits);
        prop_assert_eq!(pushed_double(value), java_double_to_string(value));
    }

    #[test]
    fn push_java_double_matches_on_whole_and_short_values(
        whole in -20_000_000i64..20_000_000,
        scale in 0u32..12,
    ) {
        let value = whole as f64 / 10f64.powi(scale as i32);
        prop_assert_eq!(pushed_double(value), java_double_to_string(value));
    }

    #[test]
    fn push_java_double_matches_on_subnormals(fraction in 1u64..(1u64 << 52), negative: bool) {
        let bits = fraction | if negative { 1 << 63 } else { 0 };
        let value = f64::from_bits(bits);
        prop_assert_eq!(pushed_double(value), java_double_to_string(value));
    }

    #[test]
    fn push_decimal_u32_matches_to_string(value in any::<u32>()) {
        prop_assert_eq!(pushed_u32(value), value.to_string());
    }
}
