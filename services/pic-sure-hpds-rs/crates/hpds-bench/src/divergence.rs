use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::client::{AsyncOutcome, Outcome, SyncOutcome};
use crate::compare::{csv_diff, json_diff_with};
use crate::consents::has_consent_values;
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
    /// Both sides answered 200 with JSON that differs, and is equal once every
    /// `categoryValues` array is sorted on both sides.
    CategoryValuesOrder,
    /// Both sides answered 200 with JSON that differs, and is equal once every array whose
    /// elements are all one-key objects (the null-term `/search` answer) is sorted by key.
    SearchConceptOrder,
    /// The entry's query carries no `userConsents` value, one side answered the sync request
    /// 200 with an empty body and the other 400.
    #[serde(rename = "missing-consents-empty-200")]
    MissingConsentsEmpty200,
    /// The entry's query carries no `userConsents` value, one side accepted the async submit
    /// (200) and ended in ERROR, and the other refused the submit with 400.
    MissingConsentsAsyncError,
    /// Both async results succeeded with CSV bodies that differ, and are equal once the
    /// tab-separated values inside every cell are sorted.
    DataframeCellOrder,
    /// Both sides accepted an async submit, one ended in ERROR and the other in SUCCESS, and
    /// the entry's phenotypic clause holds a `FILTER` with a numeric `min`.
    RangeMinAsyncError,
}

/// When a rule applies: one condition, or a list of body normalizations
/// ([`Condition::NumericMaxDoubleMinValue`], [`Condition::CategoryValuesOrder`],
/// [`Condition::SearchConceptOrder`]) that together must make two differing 200 JSON bodies
/// equal.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
#[serde(untagged)]
pub enum When {
    /// A single condition.
    One(Condition),
    /// Normalizations applied together.
    All(Vec<Condition>),
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
    pub when: Option<When>,
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

/// Sorts, on one side, every array stored under `categoryValues`.
fn sort_category_values(value: &mut Value) {
    match value {
        Value::Object(map) => {
            for (key, child) in map.iter_mut() {
                if key == "categoryValues"
                    && let Value::Array(items) = child
                {
                    items.sort_by_key(Value::to_string);
                } else {
                    sort_category_values(child);
                }
            }
        }
        Value::Array(items) => items.iter_mut().for_each(sort_category_values),
        _ => {}
    }
}

fn single_key(value: &Value) -> Option<&str> {
    match value {
        Value::Object(map) if map.len() == 1 => map.keys().next().map(String::as_str),
        _ => None,
    }
}

/// Sorts, on one side, every non-empty array whose elements are all one-key objects, by key.
fn sort_one_key_object_arrays(value: &mut Value) {
    match value {
        Value::Object(map) => map.values_mut().for_each(sort_one_key_object_arrays),
        Value::Array(items) => {
            items.iter_mut().for_each(sort_one_key_object_arrays);
            if !items.is_empty() && items.iter().all(|item| single_key(item).is_some()) {
                items.sort_by(|x, y| single_key(x).cmp(&single_key(y)));
            }
        }
        _ => {}
    }
}

fn is_normalization(condition: Condition) -> bool {
    matches!(
        condition,
        Condition::NumericMaxDoubleMinValue
            | Condition::CategoryValuesOrder
            | Condition::SearchConceptOrder
    )
}

/// Whether two 200 JSON bodies differ and become equal once every normalization in
/// `conditions` is applied to both. Orders are normalized before the max is aligned, because
/// the alignment pairs array elements by position.
fn equal_after(a: &Outcome, b: &Outcome, conditions: &[Condition]) -> bool {
    if conditions.is_empty() || !conditions.iter().all(|c| is_normalization(*c)) {
        return false;
    }
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
    if json_diff_with(&left, &right, false).is_empty() {
        return false;
    }
    if conditions.contains(&Condition::SearchConceptOrder) {
        sort_one_key_object_arrays(&mut left);
        sort_one_key_object_arrays(&mut right);
    }
    if conditions.contains(&Condition::CategoryValuesOrder) {
        sort_category_values(&mut left);
        sort_category_values(&mut right);
    }
    if conditions.contains(&Condition::NumericMaxDoubleMinValue) {
        align_min_value_max(&mut left, &mut right);
    }
    json_diff_with(&left, &right, false).is_empty()
}

fn missing_consents_empty_200(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Sync(x), Outcome::Sync(y)) = (a, b) else {
        return false;
    };
    let empty_200 = |s: &SyncOutcome| s.status == 200 && s.body.is_empty();
    !has_consent_values(&entry.request)
        && ((empty_200(x) && y.status == 400) || (empty_200(y) && x.status == 400))
}

fn ended_in_error(outcome: &AsyncOutcome) -> bool {
    outcome.submit_status == 200
        && (outcome.resource_status.as_deref() == Some("ERROR")
            || outcome.status.as_deref() == Some("ERROR"))
}

fn ended_in_success(outcome: &AsyncOutcome) -> bool {
    outcome.submit_status == 200 && outcome.resource_status.as_deref() == Some("SUCCESS")
}

fn missing_consents_async_error(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Async(x), Outcome::Async(y)) = (a, b) else {
        return false;
    };
    !has_consent_values(&entry.request)
        && ((ended_in_error(x) && y.submit_status == 400)
            || (ended_in_error(y) && x.submit_status == 400))
}

/// Rewrites a CSV body with the tab-separated values inside every cell sorted.
fn sort_cell_values(text: &str) -> Option<String> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    let mut writer = csv::Writer::from_writer(Vec::new());
    for record in reader.records() {
        let record = record.ok()?;
        let cells: Vec<String> = record
            .iter()
            .map(|cell| {
                let mut values: Vec<&str> = cell.split('\t').collect();
                values.sort_unstable();
                values.join("\t")
            })
            .collect();
        writer.write_record(&cells).ok()?;
    }
    String::from_utf8(writer.into_inner().ok()?).ok()
}

fn dataframe_cell_order(a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Async(x), Outcome::Async(y)) = (a, b) else {
        return false;
    };
    if !ended_in_success(x) || !ended_in_success(y) {
        return false;
    }
    let (Some(left), Some(right)) = (&x.result_body, &y.result_body) else {
        return false;
    };
    if csv_diff(left, right).is_empty() {
        return false;
    }
    match (sort_cell_values(left), sort_cell_values(right)) {
        (Some(l), Some(r)) => csv_diff(&l, &r).is_empty(),
        _ => false,
    }
}

fn range_min_async_error(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Async(x), Outcome::Async(y)) = (a, b) else {
        return false;
    };
    ((ended_in_error(x) && ended_in_success(y)) || (ended_in_error(y) && ended_in_success(x)))
        && query_object(&entry.request)
            .and_then(|query| query.get("phenotypicClause").cloned())
            .is_some_and(|clause| holds_range_min_filter(&clause))
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

impl Condition {
    fn holds(self, entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
        match self {
            Condition::StrictSubsetOrTimeout => subset_or_timeout(a, b),
            Condition::SuccessBodyEmpty => empty_success_body(a, b),
            Condition::NumericMaxDoubleMinValue
            | Condition::CategoryValuesOrder
            | Condition::SearchConceptOrder => equal_after(a, b, &[self]),
            Condition::RangeMinSuccessBodyEmpty => range_min_with_one_empty_body(entry, a, b),
            Condition::MissingConsentsEmpty200 => missing_consents_empty_200(entry, a, b),
            Condition::MissingConsentsAsyncError => missing_consents_async_error(entry, a, b),
            Condition::DataframeCellOrder => dataframe_cell_order(a, b),
            Condition::RangeMinAsyncError => range_min_async_error(entry, a, b),
        }
    }
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
        match &self.when {
            Some(When::One(condition)) => condition.holds(entry, a, b),
            Some(When::All(conditions)) => equal_after(a, b, conditions),
            None => true,
        }
    }
}

impl Divergences {
    /// Reads and validates a divergence file. Every rule must constrain something.
    ///
    /// # Errors
    ///
    /// Fails when the file is missing, malformed, or holds a rule with no constraint, or a
    /// condition list holding an empty list or a condition that is not a body normalization.
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
            if let Some(When::All(conditions)) = &rule.when
                && (conditions.is_empty() || !conditions.iter().all(|c| is_normalization(*c)))
            {
                return Err(BenchError::Invalid(format!(
                    "divergence rule '{}' lists conditions that are not all body normalizations",
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

    fn async_outcome(submit: u16, resource: Option<&str>, body: Option<&str>) -> Outcome {
        Outcome::Async(AsyncOutcome {
            submit_status: submit,
            status: resource.map(|r| if r == "SUCCESS" { "AVAILABLE" } else { r }.to_owned()),
            resource_status: resource.map(str::to_owned),
            result_status: body.map(|_| 200),
            result_content_type: body.map(|_| "text/plain".to_owned()),
            result_body: body.map(str::to_owned),
            timed_out: false,
        })
    }

    fn consent_entry(name: &str, kind: Kind, consents: Option<Value>) -> CorpusEntry {
        let mut query = json!({"select": [], "expectedResultType": "COUNT"});
        if let Some(values) = consents {
            query["userConsents"] = values;
        }
        CorpusEntry {
            kind,
            request: json!({"query": query, "resourceUUID": null}),
            ..entry(name, "COUNT")
        }
    }

    #[test]
    fn missing_consents_accept_an_empty_200_against_a_400_only_without_values() {
        let set = rules(json!([
            {"name": "m", "statuses": [200, 400], "when": "missing-consents-empty-200", "reason": "why"}
        ]));
        for consents in [None, Some(json!([])), Some(json!([{"value": null}]))] {
            let e = consent_entry("0001-x-count-c-none", Kind::Sync, consents);
            assert!(set.find(&e, &sync(200, ""), &sync(400, "msg")).is_some());
            assert!(set.find(&e, &sync(400, "msg"), &sync(200, "")).is_some());
            assert!(set.find(&e, &sync(200, "12"), &sync(400, "msg")).is_none());
        }
        let with = consent_entry(
            "0001-x-count-c-one",
            Kind::Sync,
            Some(json!([{"value": "p1"}])),
        );
        assert!(set.find(&with, &sync(200, ""), &sync(400, "msg")).is_none());
    }

    #[test]
    fn missing_consents_accept_an_async_error_against_a_refused_submit() {
        let set = rules(json!([
            {"name": "m", "when": "missing-consents-async-error", "reason": "why"}
        ]));
        let e = consent_entry("0005-x-patients-c-empty", Kind::Async, Some(json!([])));
        let java = async_outcome(200, Some("ERROR"), None);
        let rust = async_outcome(400, None, None);
        assert!(set.find(&e, &java, &rust).is_some());
        assert!(set.find(&e, &rust, &java).is_some());
        let success = async_outcome(200, Some("SUCCESS"), Some("patient_id\n1\n"));
        assert!(set.find(&e, &success, &rust).is_none());
        let with = consent_entry(
            "0005-x-patients-c-all",
            Kind::Async,
            Some(json!([{"value": "p"}])),
        );
        assert!(set.find(&with, &java, &rust).is_none());
    }

    #[test]
    fn category_values_and_concept_order_are_accepted_only_when_order_is_the_difference() {
        let set = rules(json!([
            {"name": "cat", "entry": "search-*", "when": "category-values-order", "reason": "why"},
            {"name": "concepts", "entry": "search-*", "when": "search-concept-order", "reason": "why"},
            {"name": "both", "entry": "search-*", "when": ["search-concept-order", "category-values-order", "numeric-max-double-min-value"], "reason": "why"}
        ]));
        let e = entry("search-value-0", "SEARCH");
        let java =
            r#"{"results":{"phenotypes":{"p":{"categoryValues":["b","a"],"patientCount":3}}}}"#;
        let rust =
            r#"{"results":{"phenotypes":{"p":{"categoryValues":["a","b"],"patientCount":3}}}}"#;
        assert_eq!(
            set.find(&e, &sync(200, java), &sync(200, rust))
                .unwrap()
                .name,
            "cat"
        );
        let other =
            r#"{"results":{"phenotypes":{"p":{"categoryValues":["a","c"],"patientCount":3}}}}"#;
        assert!(set.find(&e, &sync(200, java), &sync(200, other)).is_none());

        let java = r#"{"results":{"phenotypes":[{"\\b\\":{"max":1}},{"\\a\\":{"max":2}}]}}"#;
        let rust = r#"{"results":{"phenotypes":[{"\\a\\":{"max":2}},{"\\b\\":{"max":1}}]}}"#;
        assert_eq!(
            set.find(&e, &sync(200, java), &sync(200, rust))
                .unwrap()
                .name,
            "concepts"
        );
        let changed = r#"{"results":{"phenotypes":[{"\\a\\":{"max":3}},{"\\b\\":{"max":1}}]}}"#;
        assert!(
            set.find(&e, &sync(200, java), &sync(200, changed))
                .is_none()
        );

        let java = r#"{"r":[{"\\b\\":{"categoryValues":["y","x"]}},{"\\a\\":{"max":4.9E-324}}]}"#;
        let rust = r#"{"r":[{"\\a\\":{"max":-2.0}},{"\\b\\":{"categoryValues":["x","y"]}}]}"#;
        assert_eq!(
            set.find(&e, &sync(200, java), &sync(200, rust))
                .unwrap()
                .name,
            "both"
        );
        assert!(set.find(&e, &sync(200, rust), &sync(200, rust)).is_none());
    }

    #[test]
    fn dataframe_cell_order_accepts_reordered_values_inside_a_cell_only() {
        let set = rules(json!([
            {"name": "df", "resultType": "DATAFRAME", "when": "dataframe-cell-order", "reason": "why"}
        ]));
        let e = entry("0006-x-dataframe-c-all", "DATAFRAME");
        let java = async_outcome(
            200,
            Some("SUCCESS"),
            Some("patient_id,\\a\\\n1,x\ty\n2,z\n"),
        );
        let rust = async_outcome(
            200,
            Some("SUCCESS"),
            Some("patient_id,\\a\\\n1,y\tx\n2,z\n"),
        );
        assert!(set.find(&e, &java, &rust).is_some());
        let lost = async_outcome(200, Some("SUCCESS"), Some("patient_id,\\a\\\n1,y\n2,z\n"));
        assert!(set.find(&e, &java, &lost).is_none());
        assert!(set.find(&e, &java, &java).is_none());
        let failed = async_outcome(200, Some("ERROR"), None);
        assert!(set.find(&e, &java, &failed).is_none());
    }

    #[test]
    fn async_error_is_accepted_only_for_a_range_filter_with_a_min() {
        let set = rules(json!([
            {"name": "range", "when": "range-min-async-error", "reason": "why"}
        ]));
        let with_min = CorpusEntry {
            kind: Kind::Async,
            ..with_clause(
                "0008-numeric-range-patients",
                json!({"conceptPath": "\\a\\", "min": 5.0, "phenotypicFilterType": "FILTER"}),
            )
        };
        let java = async_outcome(200, Some("ERROR"), None);
        let rust = async_outcome(200, Some("SUCCESS"), Some("patient_id\n"));
        assert!(set.find(&with_min, &java, &rust).is_some());
        assert!(set.find(&with_min, &rust, &java).is_some());
        assert!(
            set.find(&with_min, &java, &async_outcome(400, None, None))
                .is_none()
        );
        let categorical = CorpusEntry {
            kind: Kind::Async,
            ..with_clause(
                "0000-categorical-patients",
                json!({"conceptPath": "\\a\\", "values": ["x"], "phenotypicFilterType": "FILTER"}),
            )
        };
        assert!(set.find(&categorical, &java, &rust).is_none());
    }

    #[test]
    fn condition_lists_must_hold_only_normalizations() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("d.json");
        std::fs::write(
            &file,
            r#"{"rules":[{"name":"x","when":["category-values-order","success-body-empty"],"reason":"y"}]}"#,
        )
        .unwrap();
        assert!(Divergences::read(&file).is_err());
        std::fs::write(&file, r#"{"rules":[{"name":"x","when":[],"reason":"y"}]}"#).unwrap();
        assert!(Divergences::read(&file).is_err());
    }

    #[test]
    fn harmonization_rules_are_checked_in_and_cite_a_source() {
        let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bench/divergences.json");
        let set = Divergences::read(&file).unwrap();
        let harmonization = [
            "missing-consents-sync-empty-200",
            "missing-consents-async-error",
            "search-category-values-order",
            "search-concept-order",
            "search-order-and-double-min-value",
            "dataframe-tie-order-across-partitions",
            "range-min-above-partition-values-async-error",
            "duplicate-key-last-wins",
        ];
        for name in harmonization {
            let rule = set
                .rules
                .iter()
                .find(|r| r.name == name)
                .unwrap_or_else(|| panic!("missing rule {name}"));
            assert!(
                rule.reason.contains("README")
                    || rule.reason.contains("Decision")
                    || rule.reason.contains("Spec"),
                "rule {name} cites no source"
            );
        }
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
