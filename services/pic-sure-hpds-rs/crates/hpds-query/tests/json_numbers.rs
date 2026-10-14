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
