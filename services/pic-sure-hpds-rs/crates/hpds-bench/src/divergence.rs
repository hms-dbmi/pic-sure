use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::client::{Outcome, SyncOutcome};
use crate::compare::json_diff_with;
use crate::corpus::CorpusEntry;
use crate::{BenchError, Result};

/// A condition on the two outcomes that must hold for a rule to apply, beyond name and status.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum Condition {
    /// One side timed out, or its JSON object holds a strict subset of the other side's keys
    /// and every key both sides hold has an equal value.
    StrictSubsetOrTimeout,
    /// Every side that answered 200 sent an empty body, and at least one side answered 200.
    SuccessBodyEmpty,
    /// Both sides answered 200 with JSON that is equal once every `max` field reporting
    /// `4.9E-324` (Java's `Double.MIN_VALUE`) on one side, against a value of zero or below on
    /// the other, takes the other side's value. At least one such field must differ.
    NumericMaxDoubleMinValue,
    /// Both sides answered a sync request with 200, exactly one of them with an empty body,
    /// and the entry's phenotypic clause holds a `FILTER` with a numeric `min`.
    RangeMinSuccessBodyEmpty,
}

/// Java's `Double.MIN_VALUE`, the smallest positive double, printed as `4.9E-324`.
const JAVA_DOUBLE_MIN_VALUE: f64 = f64::from_bits(1);

/// One known and accepted difference between the Java and Rust services.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Rule {
    /// Short identifier printed next to the reason.
    pub name: String,
    /// Glob over entry names; `*` matches any run of characters.
    #[serde(default)]
    pub entry: Option<String>,
    /// Exact corpus entry names the rule is limited to.
    #[serde(default)]
    pub entries: Option<Vec<String>>,
    /// Requested result type the rule is limited to.
    #[serde(default, rename = "resultType")]
    pub result_type: Option<String>,
    /// Pair of HTTP statuses, matched in either order against the two servers.
    #[serde(default)]
    pub statuses: Option<[u16; 2]>,
    /// Extra condition on the outcomes.
    #[serde(default)]
    pub when: Option<Condition>,
    /// Why the difference is accepted, citing the spec's divergence list.
    pub reason: String,
}

/// The set of accepted differences, read from `bench/divergences.json`.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct Divergences {
    /// Rules tried in order; the first match wins.
    pub rules: Vec<Rule>,
}

/// Whether `text` matches `pattern`, where `*` matches any run of characters.
pub fn glob_match(pattern: &str, text: &str) -> bool {
    let parts: Vec<&str> = pattern.split('*').collect();
    let Some((first, rest)) = parts.split_first() else {
        return pattern == text;
    };
    if rest.is_empty() {
        return pattern == text;
    }
    let Some(mut remaining) = text.strip_prefix(first) else {
        return false;
    };
    let (last, middle) = rest.split_last().unwrap_or((&"", &[]));
    for part in middle {
        match remaining.find(part) {
            Some(at) => remaining = &remaining[at + part.len()..],
            None => return false,
        }
    }
    remaining.ends_with(last)
}

fn json_object(outcome: &SyncOutcome) -> Option<serde_json::Map<String, Value>> {
    if outcome.status != 200 {
        return None;
    }
    match serde_json::from_str::<Value>(&outcome.body).ok()? {
        Value::Object(map) => Some(map),
        _ => None,
    }
}

fn strict_subset(
    small: &serde_json::Map<String, Value>,
    large: &serde_json::Map<String, Value>,
) -> bool {
    small.len() < large.len()
        && small.iter().all(|(key, value)| {
            large
                .get(key)
                .is_some_and(|other| json_diff_with(value, other, true).is_empty())
        })
}

fn empty_success_body(a: &Outcome, b: &Outcome) -> bool {
    let bodies = [a, b]
        .into_iter()
        .filter(|outcome| outcome.status_code() == 200)
        .map(|outcome| match outcome {
            Outcome::Sync(sync) => sync.body.is_empty(),
            Outcome::Async(asynchronous) => asynchronous
                .result_body
                .as_deref()
                .is_none_or(str::is_empty),
            Outcome::TimedOut | Outcome::Failed(_) => false,
        })
        .collect::<Vec<_>>();
    !bodies.is_empty() && bodies.into_iter().all(|empty| empty)
}

fn subset_or_timeout(a: &Outcome, b: &Outcome) -> bool {
    if a.is_timeout() || b.is_timeout() {
        return true;
    }
    let (Outcome::Sync(x), Outcome::Sync(y)) = (a, b) else {
        return false;
    };
    match (json_object(x), json_object(y)) {
        (Some(left), Some(right)) => strict_subset(&left, &right) || strict_subset(&right, &left),
        _ => false,
    }
}

fn is_java_min_value(value: &Value) -> bool {
    value.as_f64() == Some(JAVA_DOUBLE_MIN_VALUE)
}

fn is_zero_or_below(value: &Value) -> bool {
    value.as_f64().is_some_and(|number| number <= 0.0)
}

/// Copies onto `a` and `b` the real `max` wherever one side holds `Double.MIN_VALUE` and the
/// other a value of zero or below. Returns how many fields it aligned.
fn align_min_value_max(a: &mut Value, b: &mut Value) -> usize {
    match (a, b) {
        (Value::Object(left), Value::Object(right)) => {
            let mut aligned = 0;
            for (key, left_value) in left.iter_mut() {
                let Some(right_value) = right.get_mut(key) else {
                    continue;
                };
                if key == "max" {
                    if is_java_min_value(left_value) && is_zero_or_below(right_value) {
                        *left_value = right_value.clone();
                        aligned += 1;
                    } else if is_java_min_value(right_value) && is_zero_or_below(left_value) {
                        *right_value = left_value.clone();
                        aligned += 1;
                    }
                } else {
                    aligned += align_min_value_max(left_value, right_value);
                }
            }
            aligned
        }
        (Value::Array(left), Value::Array(right)) => left
            .iter_mut()
            .zip(right.iter_mut())
            .map(|(x, y)| align_min_value_max(x, y))
            .sum(),
        _ => 0,
    }
}

fn differs_only_in_min_value_max(a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Sync(x), Outcome::Sync(y)) = (a, b) else {
        return false;
    };
    if x.status != 200 || y.status != 200 {
        return false;
    }
    let (Ok(mut left), Ok(mut right)) = (
        serde_json::from_str::<Value>(&x.body),
        serde_json::from_str::<Value>(&y.body),
    ) else {
        return false;
    };
    align_min_value_max(&mut left, &mut right) > 0
        && json_diff_with(&left, &right, false).is_empty()
}

fn query_object(request: &Value) -> Option<Value> {
    match request.get("query")? {
        Value::String(text) => serde_json::from_str(text).ok(),
        query => Some(query.clone()),
    }
}

fn holds_range_min_filter(clause: &Value) -> bool {
    if let Some(children) = clause.get("phenotypicClauses").and_then(Value::as_array) {
        return children.iter().any(holds_range_min_filter);
    }
    clause.get("phenotypicFilterType").and_then(Value::as_str) == Some("FILTER")
        && clause.get("min").is_some_and(Value::is_number)
}

fn range_min_with_one_empty_body(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Sync(x), Outcome::Sync(y)) = (a, b) else {
        return false;
    };
    x.status == 200
        && y.status == 200
        && x.body.is_empty() != y.body.is_empty()
        && query_object(&entry.request)
            .and_then(|query| query.get("phenotypicClause").cloned())
            .is_some_and(|clause| holds_range_min_filter(&clause))
}

impl Rule {
    fn matches(&self, entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
        if let Some(pattern) = &self.entry
            && !glob_match(pattern, &entry.name)
        {
            return false;
        }
        if let Some(names) = &self.entries
            && !names.contains(&entry.name)
        {
            return false;
        }
        if let Some(result_type) = &self.result_type
            && result_type != &entry.result_type
        {
            return false;
        }
        if let Some([first, second]) = self.statuses {
            let (x, y) = (a.status_code(), b.status_code());
            if !((x == first && y == second) || (x == second && y == first)) {
                return false;
            }
        }
        match self.when {
            Some(Condition::StrictSubsetOrTimeout) => subset_or_timeout(a, b),
            Some(Condition::SuccessBodyEmpty) => empty_success_body(a, b),
            Some(Condition::NumericMaxDoubleMinValue) => differs_only_in_min_value_max(a, b),
            Some(Condition::RangeMinSuccessBodyEmpty) => range_min_with_one_empty_body(entry, a, b),
            None => true,
        }
    }
}

impl Divergences {
    /// Reads and validates a divergence file. Every rule must constrain something.
    ///
    /// # Errors
    ///
    /// Fails when the file is missing, malformed, or holds a rule with no constraint.
    pub fn read(path: &Path) -> Result<Self> {
        let text = std::fs::read_to_string(path).map_err(|e| BenchError::io(path, e))?;
        let parsed: Self = serde_json::from_str(&text)
            .map_err(|e| BenchError::json(path.display().to_string(), e))?;
        for rule in &parsed.rules {
            if rule.entry.is_none()
                && rule.entries.is_none()
                && rule.result_type.is_none()
                && rule.statuses.is_none()
                && rule.when.is_none()
            {
                return Err(BenchError::Invalid(format!(
                    "divergence rule '{}' has no entry, entries, resultType, statuses or when",
                    rule.name
                )));
            }
        }
        Ok(parsed)
    }

    /// The first rule that accepts the difference between the two outcomes of one entry.
    pub fn find(&self, entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> Option<&Rule> {
        self.rules.iter().find(|rule| rule.matches(entry, a, b))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::corpus::Kind;
    use serde_json::json;

    fn entry(name: &str, result_type: &str) -> CorpusEntry {
        CorpusEntry {
            name: name.into(),
            kind: Kind::Sync,
            result_type: result_type.into(),
            request: Value::Null,
            raw_body: None,
            error_path: false,
        }
    }

    fn sync(status: u16, body: &str) -> Outcome {
        Outcome::Sync(SyncOutcome {
            status,
            content_type: Some("application/json".into()),
            body: body.into(),
            has_query_metadata: status == 200,
        })
    }

    fn rules(value: Value) -> Divergences {
        serde_json::from_value(json!({"rules": value})).unwrap()
    }

    #[test]
    fn glob_handles_prefix_suffix_and_middle_wildcards() {
        assert!(glob_match("error-*", "error-unknown-field"));
        assert!(!glob_match("error-*", "search-x"));
        assert!(glob_match("*-count", "0001-nested-count"));
        assert!(glob_match("a*c*e", "abcde"));
        assert!(!glob_match("a*c*e", "abde"));
        assert!(glob_match("exact", "exact"));
        assert!(!glob_match("exact", "exactly"));
    }

    #[test]
    fn status_pairs_match_in_either_orientation_and_only_for_the_glob() {
        let set = rules(json!([
            {"name": "r", "entry": "error-*", "statuses": [400, 500], "reason": "why"}
        ]));
        let e = entry("error-unknown-field", "COUNT");
        assert!(set.find(&e, &sync(400, "{}"), &sync(500, "")).is_some());
        assert!(set.find(&e, &sync(500, ""), &sync(400, "{}")).is_some());
        assert!(set.find(&e, &sync(200, "1"), &sync(500, "")).is_none());
        assert!(
            set.find(
                &entry("0001-nested-count", "COUNT"),
                &sync(400, ""),
                &sync(500, "")
            )
            .is_none()
        );
    }

    #[test]
    fn subset_rule_accepts_dropped_keys_and_timeouts_but_not_value_changes() {
        let set = rules(json!([
            {"name": "obs", "resultType": "OBSERVATION_CROSS_COUNT", "when": "strict-subset-or-timeout", "reason": "why"}
        ]));
        let e = entry("0007-x-observation-cross-count", "OBSERVATION_CROSS_COUNT");
        let full = sync(200, r#"{"a":1,"b":2,"c":3}"#);
        assert!(set.find(&e, &sync(200, r#"{"a":1}"#), &full).is_some());
        assert!(set.find(&e, &full, &sync(200, r#"{"a":1}"#)).is_some());
        assert!(set.find(&e, &sync(200, r#"{"a":9}"#), &full).is_none());
        assert!(set.find(&e, &full, &full).is_none());
        assert!(set.find(&e, &Outcome::TimedOut, &full).is_some());
        assert!(set.find(&e, &sync(200, r#"{"a":1.0}"#), &full).is_none());
        let other = entry("0007-x-cross-count", "CROSS_COUNT");
        assert!(set.find(&other, &Outcome::TimedOut, &full).is_none());
    }

    #[test]
    fn named_entries_with_an_empty_success_body_are_accepted() {
        let set = rules(json!([
            {"name": "empty", "entries": ["error-a", "error-b"], "statuses": [200, 400],
             "when": "success-body-empty", "reason": "why"}
        ]));
        let a = entry("error-a", "COUNT");
        assert!(set.find(&a, &sync(200, ""), &sync(400, "msg")).is_some());
        assert!(set.find(&a, &sync(400, "msg"), &sync(200, "")).is_some());
        assert!(set.find(&a, &sync(200, "7"), &sync(400, "msg")).is_none());
        assert!(
            set.find(&entry("error-c", "COUNT"), &sync(200, ""), &sync(400, "m"))
                .is_none()
        );
        assert!(
            set.find(&entry("error-b", "COUNT"), &sync(500, ""), &sync(400, "m"))
                .is_none()
        );
    }

    #[test]
    fn java_min_value_max_is_accepted_only_against_a_max_of_zero_or_below() {
        let set = rules(json!([
            {"name": "max", "entry": "search-*", "when": "numeric-max-double-min-value", "reason": "why"}
        ]));
        let e = entry("search-path-0", "SEARCH");
        let java =
            r#"{"results":{"phenotypes":{"p":{"min":-63.4,"max":4.9E-324,"patientCount":3}}}}"#;
        let rust = r#"{"results":{"phenotypes":{"p":{"min":-63.4,"max":-45.5,"patientCount":3}}}}"#;
        assert!(set.find(&e, &sync(200, java), &sync(200, rust)).is_some());
        assert!(set.find(&e, &sync(200, rust), &sync(200, java)).is_some());
        let zero = r#"{"results":{"phenotypes":{"p":{"min":-63.4,"max":0,"patientCount":3}}}}"#;
        assert!(set.find(&e, &sync(200, java), &sync(200, zero)).is_some());
        let positive =
            r#"{"results":{"phenotypes":{"p":{"min":-63.4,"max":2.5,"patientCount":3}}}}"#;
        assert!(
            set.find(&e, &sync(200, java), &sync(200, positive))
                .is_none()
        );
        let other_field =
            r#"{"results":{"phenotypes":{"p":{"min":-63.4,"max":-45.5,"patientCount":4}}}}"#;
        assert!(
            set.find(&e, &sync(200, java), &sync(200, other_field))
                .is_none()
        );
        assert!(set.find(&e, &sync(200, rust), &sync(200, rust)).is_none());
        assert!(
            set.find(
                &entry("0001-count", "COUNT"),
                &sync(200, java),
                &sync(200, rust)
            )
            .is_none()
        );
    }

    fn with_clause(name: &str, clause: Value) -> CorpusEntry {
        CorpusEntry {
            request: json!({"query": {"phenotypicClause": clause, "select": []}, "resourceUUID": null}),
            ..entry(name, "CONTINUOUS_CROSS_COUNT")
        }
    }

    #[test]
    fn an_empty_200_is_accepted_only_for_a_range_filter_with_a_min() {
        let set = rules(json!([
            {"name": "range", "statuses": [200, 200], "when": "range-min-success-body-empty", "reason": "why"}
        ]));
        let above_every_value = json!({"operator": "AND", "phenotypicClauses": [
            {"conceptPath": "\\a\\", "max": -37.31, "phenotypicFilterType": "FILTER"},
            {"conceptPath": "\\b\\", "min": -33.37, "phenotypicFilterType": "FILTER"}
        ]});
        let e = with_clause(
            "0113-conjunction-continuous-cross-count",
            above_every_value.clone(),
        );
        let rust = sync(200, r#"{"\\a\\":{}}"#);
        assert!(set.find(&e, &sync(200, ""), &rust).is_some());
        assert!(set.find(&e, &rust, &sync(200, "")).is_some());
        assert!(set.find(&e, &sync(200, ""), &sync(200, "")).is_none());
        assert!(set.find(&e, &sync(200, "{}"), &rust).is_none());
        assert!(set.find(&e, &sync(200, ""), &sync(400, "msg")).is_none());
        assert!(set.find(&e, &sync(500, ""), &rust).is_none());

        let as_string = CorpusEntry {
            request: json!({"query": json!({"phenotypicClause": above_every_value}).to_string()}),
            ..e.clone()
        };
        assert!(set.find(&as_string, &sync(200, ""), &rust).is_some());

        let max_only = with_clause(
            "0001-numeric-range-cross-count",
            json!({"conceptPath": "\\a\\", "max": 3.0, "phenotypicFilterType": "FILTER"}),
        );
        assert!(set.find(&max_only, &sync(200, ""), &rust).is_none());
        let categorical = with_clause(
            "0000-categorical-count",
            json!({"conceptPath": "\\a\\", "values": ["x"], "phenotypicFilterType": "FILTER"}),
        );
        assert!(set.find(&categorical, &sync(200, ""), &rust).is_none());
        let required_with_min = with_clause(
            "0002-required-count",
            json!({"conceptPath": "\\a\\", "min": 1.0, "phenotypicFilterType": "REQUIRED"}),
        );
        assert!(
            set.find(&required_with_min, &sync(200, ""), &rust)
                .is_none()
        );
    }

    #[test]
    fn the_checked_in_file_accepts_an_empty_200_for_a_min_above_every_value() {
        let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bench/divergences.json");
        let set = Divergences::read(&file).unwrap();
        let e = with_clause(
            "0113-conjunction-continuous-cross-count",
            json!({"operator": "AND", "phenotypicClauses": [
                {"conceptPath": "\\b\\", "min": -33.37, "phenotypicFilterType": "FILTER"}
            ]}),
        );
        let rule = set
            .find(&e, &sync(200, ""), &sync(200, r#"{"\\b\\":{}}"#))
            .unwrap();
        assert_eq!(rule.name, "range-min-above-every-value-empty-200");
        assert!(rule.reason.contains("seekForMinIndex"));
    }

    #[test]
    fn rules_without_constraints_are_rejected() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("d.json");
        std::fs::write(&file, r#"{"rules":[{"name":"x","reason":"y"}]}"#).unwrap();
        assert!(Divergences::read(&file).is_err());
        std::fs::write(
            &file,
            r#"{"rules":[{"name":"x","entry":"a*","reason":"y"}]}"#,
        )
        .unwrap();
        assert_eq!(Divergences::read(&file).unwrap().rules.len(), 1);
    }
}
