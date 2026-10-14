//! Numbers in query JSON reach the engine as the double Jackson reads, so a range bound one
//! ulp from a stored value selects what Java and the reference oracle select.

mod common;

use common::*;
use hpds_query::{PhenotypicClause, Query, parse_strict};

const HEIGHT: &str = "\\demographics\\HEIGHT\\";

/// The 309-digit integer literal of `Double.MAX_VALUE`, as a Java client may send a bound.
const DOUBLE_MAX_INTEGER: &str = "179769313486231570814527423731704356798070567525844996598917476803157260780028538760589558632766878171540458953514382464234321326889464182768467546703537516986049910576551282076245490090389328944075868508455133942304583236903222948165808559332123348274797826204144723168738177180919299881250404026184124858368";

/// A COUNT query on [`HEIGHT`] with `min` and `max` written into the JSON text verbatim.
fn range_json(min: &str, max: &str) -> String {
    format!(
        r#"{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"FILTER","conceptPath":{},"min":{min},"max":{max}}}}}"#,
        serde_json::to_string(HEIGHT).unwrap()
    )
}

/// The bounds of the single filter in `query`.
fn bounds(query: &Query) -> (Option<f64>, Option<f64>) {
    match &query.phenotypic_clause {
        Some(PhenotypicClause::Filter(filter)) => (filter.min, filter.max),
        other => panic!("expected one filter, got {other:?}"),
    }
}

/// Reads `json` both ways the service does: directly, and through [`parse_strict`] into a
/// value first, as the HTTP envelope does.
fn read_both_ways(json: &str) -> [Query; 2] {
    let direct = Query::from_json(json.as_bytes()).unwrap();
    let through_value = serde_json::from_value(parse_strict(json.as_bytes()).unwrap()).unwrap();
    [direct, through_value]
}

#[test]
fn seventeen_digit_decimals_keep_their_exact_double() {
    let above = 119.38_f64.next_up();
    assert_eq!(above.to_string(), "119.38000000000001");
    for query in read_both_ways(&range_json("119.38000000000001", "126.99999999999999")) {
        let (min, max) = bounds(&query);
        assert_eq!(min.unwrap().to_bits(), above.to_bits());
        assert_eq!(max.unwrap().to_bits(), 127.0_f64.next_down().to_bits());
    }
}

#[test]
fn bounds_one_ulp_from_a_stored_value_count_as_the_oracle_does() {
    let s = store(&[
        (1, HEIGHT, Some(119.38), None),
        (2, HEIGHT, Some(119.38_f64.next_up()), None),
        (3, HEIGHT, Some(120.0), None),
        (4, HEIGHT, Some(127.0_f64.next_down()), None),
        (5, HEIGHT, Some(127.0), None),
    ]);
    let cases = [
        (119.38_f64.next_up(), 127.0_f64.next_down(), 3),
        (119.38_f64.next_down(), 127.0_f64.next_up(), 5),
        (119.38, 127.0, 5),
        (119.38_f64.next_up(), 120.0_f64.next_down(), 1),
    ];
    for (min, max, expected) in cases {
        let json = range_json(&min.to_string(), &max.to_string());
        for query in read_both_ways(&json) {
            assert_eq!(s.engine.count(&query).unwrap(), expected, "{json}");
        }
    }
}

#[test]
fn an_integer_literal_as_wide_as_double_max_reads_as_double_max() {
    for query in read_both_ways(&range_json("0", DOUBLE_MAX_INTEGER)) {
        assert_eq!(bounds(&query), (Some(0.0), Some(f64::MAX)));
    }
    let s = store(&[(1, HEIGHT, Some(0.5), None), (2, HEIGHT, Some(1e300), None)]);
    let [query, _] = read_both_ways(&range_json("0", DOUBLE_MAX_INTEGER));
    assert_eq!(s.engine.count(&query).unwrap(), 2);
}

const ZERO: &str = "\\demographics\\ZERO\\";

/// A COUNT query on `path` with the bounds written into the JSON text verbatim.
fn bounds_json(path: &str, bounds: &[(&str, &str)]) -> String {
    let fields: String = bounds
        .iter()
        .map(|(k, v)| format!(r#","{k}":{v}"#))
        .collect();
    format!(
        r#"{{"expectedResultType":"COUNT","phenotypicClause":{{"phenotypicFilterType":"FILTER","conceptPath":{}{fields}}}}}"#,
        serde_json::to_string(path).unwrap()
    )
}

#[test]
fn bounds_that_overflow_a_double_read_as_jackson_reads_them() {
    let wide = format!("1{}", "0".repeat(309));
    let thousand_digits = format!("1{}", "0".repeat(999));
    let midpoint_above_max = "179769313486231580793728971405303415079934132710037826936173778980444968292764750946649017977587207096330286416692887910946555547851940402630657488671505820681908902000708383676273854845817711531764475730270069855571366959622842914819860834936475292719074168444365510704342711559699508093042880177904174497792";
    let cases = [
        ("1e309", f64::INFINITY),
        ("-1e309", f64::NEG_INFINITY),
        ("1.7976931348623159e308", f64::INFINITY),
        ("1.7976931348623158e308", f64::MAX),
        (wide.as_str(), f64::INFINITY),
        (&format!("-{wide}"), f64::NEG_INFINITY),
        (thousand_digits.as_str(), f64::INFINITY),
        (midpoint_above_max, f64::INFINITY),
        (DOUBLE_MAX_INTEGER, f64::MAX),
        ("1e-400", 0.0),
    ];
    for (text, jackson) in cases {
        for query in read_both_ways(&bounds_json(HEIGHT, &[("min", text)])) {
            let (min, _) = bounds(&query);
            assert_eq!(min.unwrap().to_bits(), jackson.to_bits(), "{text}");
        }
    }
}

#[test]
fn minus_zero_reads_as_jackson_reads_it() {
    for (text, jackson) in [("-0", 0.0_f64), ("-0.0", -0.0), ("-0e0", -0.0), ("0", 0.0)] {
        for query in read_both_ways(&bounds_json(ZERO, &[("max", text)])) {
            let (_, max) = bounds(&query);
            assert_eq!(max.unwrap().to_bits(), jackson.to_bits(), "{text}");
        }
    }
}

#[test]
fn infinite_and_zero_bounds_count_as_java_does() {
    let s = store(&[
        (1, ZERO, Some(-0.0), None),
        (2, ZERO, Some(-0.0), None),
        (3, ZERO, Some(0.0), None),
        (4, ZERO, Some(0.0), None),
        (5, HEIGHT, Some(0.0), None),
        (6, HEIGHT, Some(5e-324), None),
        (7, HEIGHT, Some(9007199254740992.0), None),
        (8, HEIGHT, Some(f64::MAX), None),
    ]);
    let cases = [
        (ZERO, vec![("min", "-0")], 2),
        (ZERO, vec![("max", "-0")], 4),
        (ZERO, vec![("min", "-0"), ("max", "-0")], 2),
        (ZERO, vec![("min", "-0.0")], 4),
        (ZERO, vec![("max", "-0.0")], 2),
        (ZERO, vec![("min", "-0e0")], 4),
        (HEIGHT, vec![("max", "1e309")], 4),
        (HEIGHT, vec![("min", "0"), ("max", "1e309")], 4),
        (HEIGHT, vec![("min", "-1e309")], 4),
        (HEIGHT, vec![("max", "-1e309")], 0),
        (HEIGHT, vec![("min", DOUBLE_MAX_INTEGER)], 1),
    ];
    for (path, bounds, expected) in cases {
        let json = bounds_json(path, &bounds);
        for query in read_both_ways(&json) {
            assert_eq!(s.engine.count(&query).unwrap(), expected, "{json}");
        }
    }
}

#[test]
fn numbers_longer_than_jacksons_limit_are_refused() {
    let message = "Number value length (1001) exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)";
    for text in [
        format!("1{}", "0".repeat(1000)),
        format!("0.{}1", "0".repeat(999)),
        format!("1e{}1", "0".repeat(999)),
    ] {
        let json = bounds_json(HEIGHT, &[("min", &text)]);
        let direct = Query::from_json(json.as_bytes()).unwrap_err().to_string();
        let strict = parse_strict(json.as_bytes()).unwrap_err().to_string();
        assert!(direct.starts_with(message), "{direct}");
        assert!(strict.starts_with(message), "{strict}");
    }
    let accepted = bounds_json(HEIGHT, &[("max", &format!("1.{}1", "0".repeat(998)))]);
    assert!(Query::from_json(accepted.as_bytes()).is_ok());
}

#[test]
fn overflowing_numbers_outside_bounds_are_kept_as_their_text() {
    let body = format!(
        r#"{{"query":{},"resourceUUID":null,"extra":1e309,"resourceCredentials":{{"k":-1e309}}}}"#,
        bounds_json(HEIGHT, &[("min", "1")])
    );
    let envelope = parse_strict(body.as_bytes()).unwrap();
    assert_eq!(envelope["extra"], "1e309");
    assert_eq!(envelope["resourceCredentials"]["k"], "-1e309");
    let query: Query = serde_json::from_value(envelope["query"].clone()).unwrap();
    assert_eq!(bounds(&query), (Some(1.0), None));
}

#[test]
fn string_bounds_coerce_as_jackson_does() {
    for refused in [
        "\"1d\"",
        "\"1f\"",
        "\"-NaN\"",
        "\"0x1p3\"",
        "\"1_000\"",
        "\"1.e5\"",
        "\" Infinity\"",
    ] {
        let json = bounds_json(HEIGHT, &[("min", refused)]);
        assert!(Query::from_json(json.as_bytes()).is_err(), "{refused}");
    }
    for (accepted, value) in [
        ("\"+1\"", Some(1.0)),
        ("\" 1\"", Some(1.0)),
        ("\"Infinity\"", Some(f64::INFINITY)),
        ("\"-Infinity\"", Some(f64::NEG_INFINITY)),
        ("\"\"", None),
        ("\"  \"", None),
    ] {
        for query in read_both_ways(&bounds_json(HEIGHT, &[("min", accepted)])) {
            assert_eq!(bounds(&query).0, value, "{accepted}");
        }
    }
    let nan = Query::from_json(bounds_json(HEIGHT, &[("min", "\"NaN\"")]).as_bytes()).unwrap();
    assert!(bounds(&nan).0.unwrap().is_nan());
    let long = format!("\"-{}\"", "1".repeat(1000));
    let error = Query::from_json(bounds_json(HEIGHT, &[("min", &long)]).as_bytes())
        .unwrap_err()
        .to_string();
    assert!(
        error.starts_with("Number value length (1001) exceeds the maximum allowed (1000"),
        "{error}"
    );
}
