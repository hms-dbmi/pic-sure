use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::client::{AsyncOutcome, Outcome, SyncOutcome};
use crate::compare::{csv_diff, json_diff_with};
use crate::consents::has_consent_values;
use crate::corpus::{CorpusEntry, Kind};
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
    /// One side refused with 400 `<path> is not a categorical variable` (values on a numeric
    /// concept), and the other answered the sync request 200 with an empty body or ended the
    /// accepted async query in ERROR.
    ValuesOnNumericRefused,
    /// One side refused with 400 `<path> is not a numeric variable` (a range on a categorical
    /// concept), and the other answered the sync request 200 with an empty body or ended the
    /// accepted async query in ERROR.
    RangeOnCategoricalRefused,
    /// An async entry whose submit one side refused with 400 `Result type <type> is computed
    /// synchronously. Submit it with POST /query/sync.`, while the other accepted it (200).
    SyncTypeOnAsyncRoute,
    /// A sync entry for a genomic result type that one side refused with 400 `result type
    /// <type> is not supported by this phenotype-only HPDS`, while the other answered 200.
    GenomicTypeOnSyncRoute,
    /// One side refused with 400 `variant-spec concept path "<path>" is not supported by this
    /// phenotype-only HPDS`, while the other answered (a sync 200 or an accepted async
    /// submit).
    VariantSpecPathRefused,
    /// One side refused with 400 naming one of its request shape limits
    /// (`HPDS_MAX_SELECT_PATHS`, `HPDS_MAX_CLAUSE_DEPTH`, `HPDS_MAX_CLAUSE_NODES`,
    /// `HPDS_MAX_QUERY_CONCEPTS`) or the JSON nesting limit (`recursion limit exceeded`), while
    /// the other answered (a sync 200 or an accepted async submit). The result size limit
    /// `HPDS_MAX_CROSS_COUNT_ENTRIES` does not match: it refuses a valid query for what its
    /// answer would hold, not for its shape.
    RequestLimitRefused,
    /// One side refused with 400 because a number token holds more than 1,000 digits
    /// (`Number value length (<n>) exceeds the maximum allowed (1000, from
    /// `StreamReadConstraints.getMaxNumberLength()`)`, Jackson's own text), while the other
    /// answered 500. Jackson refuses the same token, with 400 for the request body and with
    /// 500 when the token sits in a query sent as a JSON string.
    NumberTooLongRefused,
    /// The entry's query holds a `min` or `max` sent as a JSON string that Jackson's double
    /// coercion refuses as malformed (`"1d"`, `"0x1p3"`, `"-NaN"`; see
    /// `hpds_query::jackson_string_double`), the Java side answered 500 with an empty body (the
    /// Jackson exception escapes `convertIncomingQuery`; the response carries no text), and the
    /// other side refused with 400 naming that string as an invalid value. A 500 with any body,
    /// a 400 for another reason, or a string Jackson accepts does not match. Strings refused only
    /// for their length are [`Condition::NumberTooLongRefused`].
    StringBoundRefused,
    /// Body normalization: every column meta object (one holding `name` and `categorical`)
    /// keeps only the fields both search shapes share, `name`, `categorical`, a non-empty
    /// `categoryValues`, a non-null `min` and `max`, and `patientCount`. The main branch
    /// serves `ColumnMeta` (with `observationCount`, without `widthInBytes` or timestamps),
    /// the consent branch and Rust `SummaryColumnMeta`.
    ColumnMetaShape,
    /// The entry's query carries a non-empty `authorizationFilters` list, both sides answered
    /// it (both sync requests 200, or both async queries in SUCCESS), and the Java side's
    /// answer is narrower than the other side's: a COUNT no greater, a cross count with the
    /// same top-level keys and every count under them no greater, a PATIENTS or DATAFRAME export
    /// whose header is the same and whose rows are a subset. Needs the Java side to be known
    /// ([`Divergences::for_java`]); without it the condition never holds.
    AuthorizationFiltersNarrow,
}

/// Which Java service a comparison runs against, for rules that hold for one branch only.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum JavaBranch {
    /// The original unpartitioned HPDS, `main`.
    Main,
    /// The consent-partitioned HPDS, `feature-harmonization`.
    Consent,
}

/// One of the two servers of a comparison.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Side {
    /// The server given as `--a`.
    A,
    /// The server given as `--b`.
    B,
}

/// What a comparison knows about its Java side.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct JavaService {
    /// Which server is the Java service.
    pub side: Side,
    /// Which Java branch it runs.
    pub branch: JavaBranch,
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
    /// The Java branch the rule holds for. A rule naming one applies only when the
    /// comparison names that branch ([`Divergences::for_java`]).
    #[serde(default, rename = "javaBranch")]
    pub java_branch: Option<JavaBranch>,
    /// Why the difference is accepted, citing the spec's divergence list.
    pub reason: String,
}

/// The set of accepted differences, read from `bench/divergences.json`.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
pub struct Divergences {
    /// Rules tried in order; the first match wins.
    pub rules: Vec<Rule>,
    /// The Java side of the comparison, when known.
    #[serde(skip)]
    pub java: Option<JavaService>,
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
            | Condition::ColumnMetaShape
    )
}

/// The fields both search shapes share.
const SHARED_COLUMN_META_FIELDS: [&str; 6] = [
    "name",
    "categorical",
    "categoryValues",
    "min",
    "max",
    "patientCount",
];

/// Reduces, on one side, every column meta object to the fields both search shapes share
/// (see [`Condition::ColumnMetaShape`]).
fn reduce_column_meta(value: &mut Value) {
    match value {
        Value::Object(map) => {
            if map.contains_key("name") && map.contains_key("categorical") {
                map.retain(|key, field| {
                    SHARED_COLUMN_META_FIELDS.contains(&key.as_str())
                        && !field.is_null()
                        && field.as_array().is_none_or(|items| !items.is_empty())
                });
            } else {
                map.values_mut().for_each(reduce_column_meta);
            }
        }
        Value::Array(items) => items.iter_mut().for_each(reduce_column_meta),
        _ => {}
    }
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
    if conditions.contains(&Condition::ColumnMetaShape) {
        reduce_column_meta(&mut left);
        reduce_column_meta(&mut right);
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

/// The message Rust answers a query with no consent value while consents are required.
const CONSENTS_REQUIRED: &str = "User consents are required for this query";

/// The 400 refusal body of a sync answer or an async submit, if the outcome is one.
fn refusal(outcome: &Outcome) -> Option<&str> {
    match outcome {
        Outcome::Sync(sync) if sync.status == 400 => Some(&sync.body),
        Outcome::Async(asynchronous) if asynchronous.submit_status == 400 => {
            asynchronous.submit_body.as_deref()
        }
        _ => None,
    }
}

/// How Java fails a query it cannot evaluate: a sync 200 with an empty body, or an async
/// query it accepted and ended in ERROR.
fn failed_quietly(outcome: &Outcome) -> bool {
    match outcome {
        Outcome::Sync(sync) => sync.status == 200 && sync.body.is_empty(),
        Outcome::Async(asynchronous) => ended_in_error(asynchronous),
        Outcome::TimedOut | Outcome::Failed(_) => false,
    }
}

/// Whether the outcome is an answer: a sync 200 or an async submit accepted with 200.
fn answered(outcome: &Outcome) -> bool {
    match outcome {
        Outcome::Sync(sync) => sync.status == 200,
        Outcome::Async(asynchronous) => asynchronous.submit_status == 200,
        Outcome::TimedOut | Outcome::Failed(_) => false,
    }
}

/// Whether one side refused with a body `refused` accepts and the other side's outcome
/// satisfies `other`, in either orientation.
fn refused_against(
    a: &Outcome,
    b: &Outcome,
    refused: impl Fn(&str) -> bool,
    other: impl Fn(&Outcome) -> bool,
) -> bool {
    let one_way = |x: &Outcome, y: &Outcome| refusal(x).is_some_and(&refused) && other(y);
    one_way(a, b) || one_way(b, a)
}

fn missing_consents_empty_200(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let (Outcome::Sync(_), Outcome::Sync(_)) = (a, b) else {
        return false;
    };
    let empty_200 =
        |o: &Outcome| matches!(o, Outcome::Sync(s) if s.status == 200 && s.body.is_empty());
    !has_consent_values(&entry.request)
        && refused_against(a, b, |body| body == CONSENTS_REQUIRED, empty_200)
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
    let (Outcome::Async(_), Outcome::Async(_)) = (a, b) else {
        return false;
    };
    let errored = |o: &Outcome| matches!(o, Outcome::Async(x) if ended_in_error(x));
    !has_consent_values(&entry.request)
        && refused_against(a, b, |body| body == CONSENTS_REQUIRED, errored)
}

fn sync_type_on_async_route(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let expected = format!(
        "Result type {} is computed synchronously. Submit it with POST /query/sync.",
        entry.result_type
    );
    entry.kind == Kind::Async
        && refused_against(
            a,
            b,
            |body| body == expected,
            |o| matches!(o, Outcome::Async(x) if x.submit_status == 200),
        )
}

fn genomic_type_on_sync_route(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let expected = format!(
        "result type {} is not supported by this phenotype-only HPDS",
        entry.result_type
    );
    entry.kind == Kind::Sync
        && refused_against(
            a,
            b,
            |body| body == expected,
            |o| matches!(o, Outcome::Sync(s) if s.status == 200),
        )
}

fn variant_spec_path_refused(a: &Outcome, b: &Outcome) -> bool {
    refused_against(
        a,
        b,
        |body| {
            body.starts_with("variant-spec concept path \"")
                && body.ends_with("\" is not supported by this phenotype-only HPDS")
        },
        answered,
    )
}

/// The request shape limits whose refusal [`Condition::RequestLimitRefused`] accepts.
const SHAPE_LIMITS: [&str; 4] = [
    "(HPDS_MAX_SELECT_PATHS)",
    "(HPDS_MAX_CLAUSE_DEPTH)",
    "(HPDS_MAX_CLAUSE_NODES)",
    "(HPDS_MAX_QUERY_CONCEPTS)",
];

fn request_limit_refused(a: &Outcome, b: &Outcome) -> bool {
    refused_against(
        a,
        b,
        |body| {
            SHAPE_LIMITS.iter().any(|limit| body.contains(limit))
                || body.starts_with("the request body is not valid JSON: recursion limit exceeded")
                || body.starts_with("the query is not valid: recursion limit exceeded")
        },
        answered,
    )
}

/// The request of an entry as JSON: its `request`, or its raw body when it has one.
fn request_value(entry: &CorpusEntry) -> Option<Value> {
    match &entry.raw_body {
        Some(text) => serde_json::from_str(text).ok(),
        None => Some(entry.request.clone()),
    }
}

/// Every `min` and `max` sent as a JSON string inside `clause`, at any depth.
fn string_bounds<'v>(clause: &'v Value, out: &mut Vec<&'v str>) {
    if let Some(children) = clause.get("phenotypicClauses").and_then(Value::as_array) {
        children.iter().for_each(|child| string_bounds(child, out));
    }
    for key in ["min", "max"] {
        if let Some(Value::String(text)) = clause.get(key) {
            out.push(text);
        }
    }
}

fn string_bound_refused(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    let Some(query) = request_value(entry).and_then(|request| query_object(&request)) else {
        return false;
    };
    let mut bounds = Vec::new();
    if let Some(clause) = query.get("phenotypicClause") {
        string_bounds(clause, &mut bounds);
    }
    let refused: Vec<&str> = bounds
        .into_iter()
        .filter(|text| {
            matches!(
                hpds_query::jackson_string_double(text),
                Err(hpds_query::StringDoubleError::Invalid)
            )
        })
        .collect();
    if refused.is_empty() {
        return false;
    }
    let names_a_refused_string = |body: &str| {
        refused.iter().any(|text| {
            serde_json::to_string(text)
                .is_ok_and(|quoted| body.contains(&format!("invalid value: string {quoted}")))
        })
    };
    let empty_500 = |other: &Outcome| {
        matches!(other, Outcome::Sync(s) if s.status == 500 && s.body.is_empty())
            || matches!(other, Outcome::Async(x) if x.submit_status == 500 && x.submit_body.as_deref().is_none_or(str::is_empty))
    };
    refused_against(a, b, names_a_refused_string, empty_500)
}

fn number_too_long_refused(a: &Outcome, b: &Outcome) -> bool {
    refused_against(
        a,
        b,
        |body| {
            body.contains("Number value length (")
                && body.contains(
                    ") exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)",
                )
        },
        |other| other.status_code() == 500,
    )
}

/// Whether `java` is at most `rust`: numbers no greater, objects whose every key the other
/// also holds with a value at most its own, anything else equal.
fn json_at_most(java: &Value, rust: &Value) -> bool {
    match (java, rust) {
        (Value::Number(x), Value::Number(y)) => match (x.as_f64(), y.as_f64()) {
            (Some(x), Some(y)) => x <= y,
            _ => false,
        },
        (Value::Object(x), Value::Object(y)) => x
            .iter()
            .all(|(key, value)| y.get(key).is_some_and(|other| json_at_most(value, other))),
        _ => java == rust,
    }
}

/// Whether a cross count `java` is narrower than `rust`: the same top-level keys (one per
/// selected concept, which no filter removes, so an empty `{}` never passes), and under each a
/// value [`json_at_most`] the other's.
fn same_keys_at_most(
    java: &serde_json::Map<String, Value>,
    rust: &serde_json::Map<String, Value>,
) -> bool {
    java.len() == rust.len()
        && java.iter().all(|(key, value)| {
            rust.get(key)
                .is_some_and(|other| json_at_most(value, other))
        })
}

/// The CSV records of an export body, the header first.
fn csv_records(text: &str) -> Option<Vec<Vec<String>>> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    reader
        .records()
        .map(|record| record.ok().map(|r| r.iter().map(str::to_owned).collect()))
        .collect()
}

/// Whether `java`'s answer is narrower than `rust`'s, as [`Condition::AuthorizationFiltersNarrow`]
/// defines it.
fn narrower(java: &Outcome, rust: &Outcome) -> bool {
    match (java, rust) {
        (Outcome::Sync(x), Outcome::Sync(y)) if x.status == 200 && y.status == 200 => {
            match (x.body.trim().parse::<u64>(), y.body.trim().parse::<u64>()) {
                (Ok(x), Ok(y)) => x <= y,
                _ => match (
                    serde_json::from_str::<Value>(&x.body),
                    serde_json::from_str::<Value>(&y.body),
                ) {
                    (Ok(Value::Object(x)), Ok(Value::Object(y))) => same_keys_at_most(&x, &y),
                    _ => false,
                },
            }
        }
        (Outcome::Async(x), Outcome::Async(y)) if ended_in_success(x) && ended_in_success(y) => {
            let (Some(x), Some(y)) = (&x.result_body, &y.result_body) else {
                return false;
            };
            let (Some(x), Some(y)) = (csv_records(x), csv_records(y)) else {
                return false;
            };
            let (Some((java_header, java_rows)), Some((rust_header, rust_rows))) =
                (x.split_first(), y.split_first())
            else {
                return false;
            };
            let rust_rows: std::collections::HashSet<&Vec<String>> = rust_rows.iter().collect();
            java_header == rust_header && java_rows.iter().all(|row| rust_rows.contains(row))
        }
        _ => false,
    }
}

fn authorization_filters_narrow(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let carries_filters = query_object(&entry.request).is_some_and(|query| {
        query
            .get("authorizationFilters")
            .and_then(Value::as_array)
            .is_some_and(|filters| !filters.is_empty())
    });
    let Some(java) = java else {
        return false;
    };
    let (java_outcome, rust_outcome) = match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    };
    carries_filters && narrower(java_outcome, rust_outcome)
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
    fn holds(
        self,
        entry: &CorpusEntry,
        a: &Outcome,
        b: &Outcome,
        java: Option<JavaService>,
    ) -> bool {
        match self {
            Condition::StrictSubsetOrTimeout => subset_or_timeout(a, b),
            Condition::SuccessBodyEmpty => empty_success_body(a, b),
            Condition::NumericMaxDoubleMinValue
            | Condition::CategoryValuesOrder
            | Condition::SearchConceptOrder
            | Condition::ColumnMetaShape => equal_after(a, b, &[self]),
            Condition::RangeMinSuccessBodyEmpty => range_min_with_one_empty_body(entry, a, b),
            Condition::MissingConsentsEmpty200 => missing_consents_empty_200(entry, a, b),
            Condition::MissingConsentsAsyncError => missing_consents_async_error(entry, a, b),
            Condition::DataframeCellOrder => dataframe_cell_order(a, b),
            Condition::RangeMinAsyncError => range_min_async_error(entry, a, b),
            Condition::ValuesOnNumericRefused => refused_against(
                a,
                b,
                |body| body.ends_with(" is not a categorical variable"),
                failed_quietly,
            ),
            Condition::RangeOnCategoricalRefused => refused_against(
                a,
                b,
                |body| body.ends_with(" is not a numeric variable"),
                failed_quietly,
            ),
            Condition::SyncTypeOnAsyncRoute => sync_type_on_async_route(entry, a, b),
            Condition::GenomicTypeOnSyncRoute => genomic_type_on_sync_route(entry, a, b),
            Condition::VariantSpecPathRefused => variant_spec_path_refused(a, b),
            Condition::RequestLimitRefused => request_limit_refused(a, b),
            Condition::NumberTooLongRefused => number_too_long_refused(a, b),
            Condition::StringBoundRefused => string_bound_refused(entry, a, b),
            Condition::AuthorizationFiltersNarrow => {
                authorization_filters_narrow(entry, a, b, java)
            }
        }
    }
}

impl Rule {
    fn matches(
        &self,
        entry: &CorpusEntry,
        a: &Outcome,
        b: &Outcome,
        java: Option<JavaService>,
    ) -> bool {
        if let Some(branch) = self.java_branch
            && java.is_none_or(|java| java.branch != branch)
        {
            return false;
        }
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
            Some(When::One(condition)) => condition.holds(entry, a, b, java),
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

    /// These rules for a comparison whose Java service is on `side` and runs `branch`: rules
    /// naming another branch stop applying, and conditions that need the Java side can hold.
    pub fn for_java(self, side: Side, branch: JavaBranch) -> Self {
        Divergences {
            java: Some(JavaService { side, branch }),
            ..self
        }
    }

    /// The first rule that accepts the difference between the two outcomes of one entry.
    pub fn find(&self, entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> Option<&Rule> {
        self.rules
            .iter()
            .find(|rule| rule.matches(entry, a, b, self.java))
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
            submit_body: None,
            status: resource.map(|r| if r == "SUCCESS" { "AVAILABLE" } else { r }.to_owned()),
            resource_status: resource.map(str::to_owned),
            result_status: body.map(|_| 200),
            result_content_type: body.map(|_| "text/plain".to_owned()),
            result_body: body.map(str::to_owned),
            timed_out: false,
        })
    }

    fn refused_submit(body: &str) -> Outcome {
        Outcome::Async(AsyncOutcome {
            submit_body: Some(body.into()),
            ..match async_outcome(400, None, None) {
                Outcome::Async(outcome) => outcome,
                _ => unreachable!("async_outcome builds an async outcome"),
            }
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
        let required = sync(400, CONSENTS_REQUIRED);
        for consents in [None, Some(json!([])), Some(json!([{"value": null}]))] {
            let e = consent_entry("0001-x-count-c-none", Kind::Sync, consents);
            assert!(set.find(&e, &sync(200, ""), &required).is_some());
            assert!(set.find(&e, &required, &sync(200, "")).is_some());
            assert!(set.find(&e, &sync(200, "12"), &required).is_none());
            let other_refusal = sync(400, "\\a\\ is not a categorical variable");
            assert!(set.find(&e, &sync(200, ""), &other_refusal).is_none());
        }
        let with = consent_entry(
            "0001-x-count-c-one",
            Kind::Sync,
            Some(json!([{"value": "p1"}])),
        );
        assert!(set.find(&with, &sync(200, ""), &required).is_none());
    }

    #[test]
    fn missing_consents_accept_an_async_error_against_a_refused_submit() {
        let set = rules(json!([
            {"name": "m", "when": "missing-consents-async-error", "reason": "why"}
        ]));
        let e = consent_entry("0005-x-patients-c-empty", Kind::Async, Some(json!([])));
        let java = async_outcome(200, Some("ERROR"), None);
        let rust = refused_submit(CONSENTS_REQUIRED);
        assert!(set.find(&e, &java, &rust).is_some());
        assert!(set.find(&e, &rust, &java).is_some());
        let sync_type = refused_submit(
            "Result type COUNT is computed synchronously. Submit it with POST /query/sync.",
        );
        assert!(set.find(&e, &java, &sync_type).is_none());
        assert!(
            set.find(&e, &java, &async_outcome(400, None, None))
                .is_none()
        );
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
    fn refusals_are_accepted_only_with_their_message_against_javas_answer() {
        let set = rules(json!([
            {"name": "values", "when": "values-on-numeric-refused", "reason": "why"},
            {"name": "range", "when": "range-on-categorical-refused", "reason": "why"},
            {"name": "route", "when": "sync-type-on-async-route", "reason": "why"},
            {"name": "genomic", "when": "genomic-type-on-sync-route", "reason": "why"},
            {"name": "variant", "when": "variant-spec-path-refused", "reason": "why"},
            {"name": "limit", "when": "request-limit-refused", "reason": "why"}
        ]));
        let name = |e: &CorpusEntry, a: &Outcome, b: &Outcome| {
            set.find(e, a, b).map(|rule| rule.name.clone())
        };
        let count = entry("0001-x-count", "COUNT");
        let empty = sync(200, "");
        let not_categorical = sync(400, "\\age\\ is not a categorical variable");
        assert_eq!(
            name(&count, &empty, &not_categorical).as_deref(),
            Some("values")
        );
        assert_eq!(
            name(&count, &not_categorical, &empty).as_deref(),
            Some("values")
        );
        assert_eq!(name(&count, &sync(200, "3"), &not_categorical), None);
        let not_numeric = sync(400, "\\sex\\ is not a numeric variable");
        assert_eq!(name(&count, &empty, &not_numeric).as_deref(), Some("range"));
        let patients = CorpusEntry {
            kind: Kind::Async,
            ..entry("0002-x-patients", "PATIENTS")
        };
        let errored = async_outcome(200, Some("ERROR"), None);
        let refused = refused_submit("\\age\\ is not a categorical variable");
        assert_eq!(
            name(&patients, &errored, &refused).as_deref(),
            Some("values")
        );
        let succeeded = async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n"));
        assert_eq!(name(&patients, &succeeded, &refused), None);

        let async_count = CorpusEntry {
            kind: Kind::Async,
            ..entry("0003-x-count-async", "COUNT")
        };
        let route = refused_submit(
            "Result type COUNT is computed synchronously. Submit it with POST /query/sync.",
        );
        assert_eq!(
            name(&async_count, &errored, &route).as_deref(),
            Some("route")
        );
        let no_status = async_outcome(200, None, None);
        assert_eq!(
            name(&async_count, &no_status, &route).as_deref(),
            Some("route")
        );
        assert_eq!(name(&patients, &errored, &route), None);

        let variant_list = entry("0004-x-variant-list", "VARIANT_LIST_FOR_QUERY");
        let genomic = sync(
            400,
            "result type VARIANT_LIST_FOR_QUERY is not supported by this phenotype-only HPDS",
        );
        let not_allowed = sync(200, "VARIANT_LIST query type not allowed");
        assert_eq!(
            name(&variant_list, &not_allowed, &genomic).as_deref(),
            Some("genomic")
        );
        assert_eq!(name(&count, &not_allowed, &genomic), None);

        let variant = sync(
            400,
            "variant-spec concept path \"rs123\" is not supported by this phenotype-only HPDS",
        );
        assert_eq!(
            name(&count, &sync(200, "0"), &variant).as_deref(),
            Some("variant")
        );
        assert_eq!(name(&count, &sync(500, ""), &variant), None);

        let limit = sync(
            400,
            "the query has more than 10000 clauses (HPDS_MAX_CLAUSE_NODES)",
        );
        assert_eq!(
            name(&count, &sync(200, "4978"), &limit).as_deref(),
            Some("limit")
        );
        let nesting = sync(
            400,
            "the request body is not valid JSON: recursion limit exceeded at line 1 column 2708",
        );
        assert_eq!(
            name(&count, &sync(200, "4978"), &nesting).as_deref(),
            Some("limit")
        );
        assert_eq!(name(&count, &sync(200, "4978"), &sync(400, "bad")), None);
        let result_size = sync(
            400,
            "the cross count would hold more than 5000000 entries (HPDS_MAX_CROSS_COUNT_ENTRIES)",
        );
        assert_eq!(name(&count, &sync(200, "{}"), &result_size), None);
    }

    #[test]
    fn column_meta_shape_compares_only_the_shared_fields() {
        let set = rules(json!([
            {"name": "shape", "entry": "search-*", "when": ["column-meta-shape"], "reason": "why"}
        ]));
        let e = entry("search-age", "SEARCH");
        let main = r#"{"results":{"phenotypes":{"p":{"name":"p","categorical":false,"min":31.0,"max":62.0,"observationCount":1126,"patientCount":1126}},"info":{}},"searchQuery":"p"}"#;
        let rust = r#"{"results":{"phenotypes":{"p":{"name":"p","widthInBytes":8,"categorical":false,"categoryValues":[],"min":31.0,"max":62.0,"patientCount":1126,"timestampMin":null,"timestampMax":null}},"info":{}},"searchQuery":"p"}"#;
        assert!(set.find(&e, &sync(200, main), &sync(200, rust)).is_some());
        let changed = rust.replace("\"patientCount\":1126", "\"patientCount\":1125");
        assert!(
            set.find(&e, &sync(200, main), &sync(200, &changed))
                .is_none()
        );
        let categorical_main = r#"{"results":{"phenotypes":[{"s":{"name":"s","categorical":true,"categoryValues":["a"],"observationCount":2,"patientCount":2}}]}}"#;
        let categorical_rust = r#"{"results":{"phenotypes":[{"s":{"name":"s","widthInBytes":1,"categorical":true,"categoryValues":["a"],"min":null,"max":null,"patientCount":2,"timestampMin":null,"timestampMax":null}}]}}"#;
        assert!(
            set.find(
                &e,
                &sync(200, categorical_main),
                &sync(200, categorical_rust)
            )
            .is_some()
        );
        let other_value = categorical_rust.replace("[\"a\"]", "[\"b\"]");
        assert!(
            set.find(&e, &sync(200, categorical_main), &sync(200, &other_value))
                .is_none()
        );
    }

    #[test]
    fn authorization_filters_accept_only_a_narrower_main_answer() {
        let file = rules(json!([
            {"name": "auth", "javaBranch": "main", "when": "authorization-filters-narrow", "reason": "why"}
        ]));
        let main_a = file.clone().for_java(Side::A, JavaBranch::Main);
        let main_b = file.clone().for_java(Side::B, JavaBranch::Main);
        let consent = file.clone().for_java(Side::A, JavaBranch::Consent);
        let filtered = |result_type: &str| CorpusEntry {
            request: json!({"query": {"expectedResultType": result_type, "authorizationFilters": [{"conceptPath": "\\a\\", "values": ["x"]}]}}),
            ..entry("0001-x", result_type)
        };
        let count = filtered("COUNT");
        let (java, rust) = (sync(200, "3202"), sync(200, "4978"));
        assert!(main_a.find(&count, &java, &rust).is_some());
        assert!(main_b.find(&count, &rust, &java).is_some());
        assert!(main_a.find(&count, &rust, &java).is_none());
        assert!(consent.find(&count, &java, &rust).is_none());
        assert!(file.find(&count, &java, &rust).is_none());
        assert!(
            main_a
                .find(&count, &sync(500, ""), &sync(200, "4978"))
                .is_none()
        );
        let unfiltered = CorpusEntry {
            request: json!({"query": {"expectedResultType": "COUNT", "authorizationFilters": []}}),
            ..entry("0001-x", "COUNT")
        };
        assert!(main_a.find(&unfiltered, &java, &rust).is_none());

        let cross = filtered("CATEGORICAL_CROSS_COUNT");
        let narrow = sync(200, r#"{"\\s\\":{"f":2}}"#);
        let wide = sync(200, r#"{"\\s\\":{"f":3,"m":4}}"#);
        assert!(main_a.find(&cross, &narrow, &wide).is_some());
        assert!(main_a.find(&cross, &wide, &narrow).is_none());
        assert!(main_a.find(&cross, &sync(200, "{}"), &wide).is_none());
        let other_concept = sync(200, r#"{"\\t\\":{"f":1}}"#);
        assert!(main_a.find(&cross, &other_concept, &wide).is_none());
        let counts = filtered("CROSS_COUNT");
        let two = sync(200, r#"{"\\a\\":2,"\\b\\":0}"#);
        let three = sync(200, r#"{"\\a\\":3,"\\b\\":1}"#);
        assert!(main_a.find(&counts, &two, &three).is_some());
        assert!(
            main_a
                .find(&counts, &sync(200, r#"{"\\a\\":2}"#), &three)
                .is_none()
        );

        let patients = CorpusEntry {
            kind: Kind::Async,
            ..filtered("PATIENTS")
        };
        let few = async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n2\n"));
        let many = async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n1\n2\n3\n"));
        let other = async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n4\n"));
        assert!(main_a.find(&patients, &few, &many).is_some());
        assert!(main_a.find(&patients, &many, &few).is_none());
        assert!(main_a.find(&patients, &other, &many).is_none());
    }

    #[test]
    fn branch_scoped_rules_apply_only_to_their_branch() {
        let file = rules(json!([
            {"name": "shape", "entry": "search-*", "javaBranch": "main", "when": ["column-meta-shape"], "reason": "why"}
        ]));
        let e = entry("search-age", "SEARCH");
        let main = r#"{"results":{"phenotypes":{"p":{"name":"p","categorical":false,"min":1.0,"max":2.0,"observationCount":3,"patientCount":3}}}}"#;
        let rust = r#"{"results":{"phenotypes":{"p":{"name":"p","widthInBytes":8,"categorical":false,"categoryValues":[],"min":1.0,"max":2.0,"patientCount":3,"timestampMin":null,"timestampMax":null}}}}"#;
        let (x, y) = (sync(200, main), sync(200, rust));
        assert!(file.find(&e, &x, &y).is_none());
        assert!(
            file.clone()
                .for_java(Side::A, JavaBranch::Consent)
                .find(&e, &x, &y)
                .is_none()
        );
        assert!(
            file.for_java(Side::A, JavaBranch::Main)
                .find(&e, &x, &y)
                .is_some()
        );
    }

    #[test]
    fn a_refused_string_bound_is_accepted_only_against_an_empty_java_500() {
        let set = rules(json!([
            {"name": "string", "when": "string-bound-refused", "reason": "why"}
        ]));
        let with_min = |min: Value| CorpusEntry {
            request: json!({"query": {"expectedResultType": "COUNT", "phenotypicClause": {
                "operator": "AND", "phenotypicClauses": [
                    {"phenotypicFilterType": "FILTER", "conceptPath": "\\a\\", "min": min}]}}}),
            ..entry("0001-x-count", "COUNT")
        };
        let refused = sync(
            400,
            "the query is not valid: invalid value: string \"1d\", expected a number, a numeric string, or null at line 1 column 90",
        );
        let java = sync(500, "");
        let e = with_min(json!("1d"));
        assert!(set.find(&e, &java, &refused).is_some());
        assert!(set.find(&e, &refused, &java).is_some());
        let unrelated = sync(500, "java.lang.NullPointerException at QueryV3Service");
        assert!(set.find(&e, &unrelated, &refused).is_none());
        assert!(set.find(&e, &sync(200, ""), &refused).is_none());
        let other_reason = sync(400, "the query is not valid: unknown field `bogus`");
        assert!(set.find(&e, &java, &other_reason).is_none());
        let accepted = with_min(json!("1"));
        let names_one = sync(
            400,
            "the query is not valid: invalid value: string \"1\", expected a number",
        );
        assert!(set.find(&accepted, &java, &names_one).is_none());
        assert!(set.find(&with_min(json!(1.5)), &java, &refused).is_none());
        let as_raw = CorpusEntry {
            request: Value::Null,
            raw_body: Some(
                r#"{"query":"{\"expectedResultType\":\"COUNT\",\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"x\",\"max\":\"0x1p3\"}}"}"#.into(),
            ),
            ..entry("0002-x-count", "COUNT")
        };
        let hex = sync(
            400,
            "the query is not valid: invalid value: string \"0x1p3\", expected a number, a numeric string, or null",
        );
        assert!(set.find(&as_raw, &java, &hex).is_some());
        let too_long = with_min(json!(format!("-{}", "1".repeat(1000))));
        assert!(set.find(&too_long, &java, &refused).is_none());
    }

    #[test]
    fn a_too_long_number_is_accepted_only_against_a_500() {
        let set = rules(json!([
            {"name": "long", "when": "number-too-long-refused", "reason": "why"}
        ]));
        let e = entry("0001-x-count", "COUNT");
        let refused = sync(
            400,
            "the query is not valid: Number value length (1001) exceeds the maximum allowed (1000, from `StreamReadConstraints.getMaxNumberLength()`)",
        );
        assert!(set.find(&e, &sync(500, ""), &refused).is_some());
        assert!(set.find(&e, &refused, &sync(500, "")).is_some());
        assert!(set.find(&e, &sync(200, "3"), &refused).is_none());
        assert!(set.find(&e, &sync(500, ""), &sync(400, "other")).is_none());
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
            "search-concept-and-category-values-order",
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
    fn the_checked_in_file_names_each_refusal_and_contract_rule() {
        let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bench/divergences.json");
        let set = Divergences::read(&file).unwrap();
        let count = entry("0001-x-count", "COUNT");
        let rule = |a: &Outcome, b: &Outcome| set.find(&count, a, b).map(|r| r.name.as_str());
        assert_eq!(
            rule(
                &sync(200, ""),
                &sync(400, "\\a\\ is not a categorical variable")
            ),
            Some("values-on-numeric-status")
        );
        assert_eq!(
            rule(&sync(200, ""), &sync(400, CONSENTS_REQUIRED)),
            Some("missing-consents-sync-empty-200")
        );
        assert_eq!(
            rule(
                &sync(200, ""),
                &sync(400, "\\a\\ is not a numeric variable")
            ),
            Some("range-on-categorical-status")
        );
        assert_eq!(rule(&sync(200, ""), &sync(400, "something else")), None);
        for name in [
            "values-on-numeric-status",
            "range-on-categorical-status",
            "sync-type-on-async-route",
            "genomic-type-on-sync-route",
            "variant-spec-path-status",
            "request-limit-status",
            "search-shape-main",
            "authorization-filters-main",
            "number-too-long-status",
            "string-bound-refused-status",
        ] {
            let found = set.rules.iter().find(|r| r.name == name);
            assert!(
                found.is_some_and(|r| !r.reason.is_empty()),
                "missing rule {name}"
            );
        }
        assert!(
            !set.rules
                .iter()
                .any(|r| r.name.contains("patient-id") || r.name.contains("json-number"))
        );
        for name in ["search-shape-main", "authorization-filters-main"] {
            let rule = set.rules.iter().find(|r| r.name == name).unwrap();
            assert_eq!(rule.java_branch, Some(JavaBranch::Main), "{name}");
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
