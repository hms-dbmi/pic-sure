use std::collections::BTreeMap;
use std::sync::Arc;

use serde_json::Value;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::client::{AsyncOutcome, Client, Outcome, SyncOutcome};
use crate::corpus::{CorpusEntry, Kind};
use crate::divergence::Divergences;

const MAX_DIFF_LINES: usize = 6;

/// One entry whose two responses disagree, with a concise description of each difference.
#[derive(Debug, Clone, PartialEq)]
pub struct Mismatch {
    /// Corpus entry name.
    pub entry: String,
    /// Human-readable differences.
    pub details: Vec<String>,
}

fn shorten(text: &str) -> String {
    const LIMIT: usize = 110;
    if text.chars().count() <= LIMIT {
        return text.to_owned();
    }
    let head: String = text.chars().take(LIMIT).collect();
    format!("{head}...")
}

fn numbers_equal(a: &serde_json::Number, b: &serde_json::Number) -> bool {
    match (a.as_i64(), b.as_i64()) {
        (Some(x), Some(y)) => x == y,
        _ => match (a.as_u64(), b.as_u64()) {
            (Some(x), Some(y)) => x == y,
            _ => a.as_f64() == b.as_f64(),
        },
    }
}

/// What an entry's response is expected to carry, which decides how strictly it is compared.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Mode {
    /// Whether a 200 response must carry the `queryMetadata` header. Invalid requests are exempt:
    /// Java answers some of them with an empty 200 that skips the header.
    pub expect_metadata: bool,
    /// Whether every JSON number is a count that Java emits as an integer, so an integer on one
    /// side and a float on the other is a difference.
    pub count_valued: bool,
}

impl Mode {
    /// The mode for a corpus entry, from its path and result type.
    pub fn for_entry(entry: &CorpusEntry) -> Self {
        let sync = entry.kind == Kind::Sync;
        Self {
            expect_metadata: sync && !entry.error_path,
            count_valued: sync && COUNT_RESULT_TYPES.contains(&entry.result_type.as_str()),
        }
    }
}

const COUNT_RESULT_TYPES: [&str; 5] = [
    "COUNT",
    "CROSS_COUNT",
    "CATEGORICAL_CROSS_COUNT",
    "CONTINUOUS_CROSS_COUNT",
    "OBSERVATION_CROSS_COUNT",
];

fn walk_diff(path: &str, a: &Value, b: &Value, strict: bool, out: &mut Vec<String>) {
    if out.len() >= MAX_DIFF_LINES {
        return;
    }
    match (a, b) {
        (Value::Object(x), Value::Object(y)) => {
            for (key, left) in x {
                let child = format!("{path}.{key}");
                match y.get(key) {
                    Some(right) => walk_diff(&child, left, right, strict, out),
                    None => out.push(format!(
                        "{} only in A: {}",
                        shorten(&child),
                        shorten(&left.to_string())
                    )),
                }
            }
            for (key, right) in y {
                if !x.contains_key(key) {
                    out.push(format!(
                        "{} only in B: {}",
                        shorten(&format!("{path}.{key}")),
                        shorten(&right.to_string())
                    ));
                }
            }
        }
        (Value::Array(x), Value::Array(y)) => {
            if x.len() != y.len() {
                out.push(format!(
                    "{} length A={} B={}",
                    shorten(path),
                    x.len(),
                    y.len()
                ));
            }
            for (index, (left, right)) in x.iter().zip(y).enumerate() {
                walk_diff(&format!("{path}[{index}]"), left, right, strict, out);
            }
        }
        (Value::Number(x), Value::Number(y)) if strict && x.is_f64() != y.is_f64() => {
            out.push(format!(
                "{} number type A={} B={}",
                shorten(path),
                number_kind(x),
                number_kind(y)
            ));
        }
        (Value::Number(x), Value::Number(y)) if numbers_equal(x, y) => {}
        _ if a == b => {}
        _ => out.push(format!(
            "{} A={} B={}",
            shorten(path),
            shorten(&a.to_string()),
            shorten(&b.to_string())
        )),
    }
}

fn number_kind(number: &serde_json::Number) -> &'static str {
    if number.is_f64() { "float" } else { "integer" }
}

/// Structural JSON difference: object key order is ignored and numbers compare by value.
/// Returns at most a handful of lines.
pub fn json_diff(a: &Value, b: &Value) -> Vec<String> {
    json_diff_with(a, b, false)
}

/// Like [`json_diff`], and with `strict_numbers` an integer against a float is also a
/// difference even when the values are equal, as for count-valued fields.
pub fn json_diff_with(a: &Value, b: &Value, strict_numbers: bool) -> Vec<String> {
    let mut out = Vec::new();
    walk_diff("$", a, b, strict_numbers, &mut out);
    out
}

fn is_json(content_type: &Option<String>) -> bool {
    content_type.as_deref().is_some_and(|c| c.contains("json"))
}

fn body_diff(
    label: &str,
    ct_a: &Option<String>,
    a: &str,
    ct_b: &Option<String>,
    b: &str,
    strict_numbers: bool,
) -> Vec<String> {
    if is_json(ct_a)
        && is_json(ct_b)
        && let (Ok(x), Ok(y)) = (
            serde_json::from_str::<Value>(a),
            serde_json::from_str::<Value>(b),
        )
    {
        return json_diff_with(&x, &y, strict_numbers)
            .into_iter()
            .map(|d| format!("{label} {d}"))
            .collect();
    }
    if a == b {
        Vec::new()
    } else {
        vec![format!("{label} A={:?} B={:?}", shorten(a), shorten(b))]
    }
}

fn parse_csv(text: &str) -> (Vec<Vec<String>>, usize) {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    let mut rows: Vec<Vec<String>> = Vec::new();
    let mut malformed = 0;
    for record in reader.records() {
        match record {
            Ok(record) => rows.push(record.iter().map(str::to_owned).collect()),
            Err(_) => malformed += 1,
        }
    }
    if let Some(width) = rows.first().map(Vec::len) {
        malformed += rows.iter().skip(1).filter(|row| row.len() != width).count();
    }
    (rows, malformed)
}

/// Compares two CSV result bodies: the header row exactly, the data rows as multisets.
///
/// Java writes patient rows in hash-set iteration order, so row order is not part of the contract.
/// A row that fails to parse or whose field count differs from the header's is a difference.
pub fn csv_diff(a: &str, b: &str) -> Vec<String> {
    let ((rows_a, bad_a), (rows_b, bad_b)) = (parse_csv(a), parse_csv(b));
    let mut out = Vec::new();
    if bad_a > 0 || bad_b > 0 {
        out.push(format!("csv malformed rows A={bad_a} B={bad_b}"));
    }
    if rows_a.first() != rows_b.first() {
        out.push(format!(
            "csv header A={:?} B={:?}",
            rows_a.first(),
            rows_b.first()
        ));
        return out;
    }
    let mut counts: BTreeMap<&Vec<String>, i64> = BTreeMap::new();
    for row in rows_a.iter().skip(1) {
        *counts.entry(row).or_default() += 1;
    }
    for row in rows_b.iter().skip(1) {
        *counts.entry(row).or_default() -= 1;
    }
    let only_a: Vec<_> = counts.iter().filter(|(_, n)| **n > 0).collect();
    let only_b: Vec<_> = counts.iter().filter(|(_, n)| **n < 0).collect();
    if rows_a.len() != rows_b.len() {
        out.push(format!(
            "csv rows A={} B={}",
            rows_a.len().saturating_sub(1),
            rows_b.len().saturating_sub(1)
        ));
    }
    for (row, _) in only_a.iter().take(3) {
        out.push(format!("csv row only in A: {}", shorten(&row.join(","))));
    }
    for (row, _) in only_b.iter().take(3) {
        out.push(format!("csv row only in B: {}", shorten(&row.join(","))));
    }
    if out.is_empty() && (!only_a.is_empty() || !only_b.is_empty()) {
        out.push("csv rows differ".to_owned());
    }
    out
}

fn field_diff<T: PartialEq + std::fmt::Debug>(label: &str, a: &T, b: &T, out: &mut Vec<String>) {
    if a != b {
        out.push(format!("{label} A={a:?} B={b:?}"));
    }
}

fn sync_diff(a: &SyncOutcome, b: &SyncOutcome, mode: Mode) -> Vec<String> {
    let mut out = Vec::new();
    field_diff("status", &a.status, &b.status, &mut out);
    if a.status >= 400 && b.status >= 400 {
        return out;
    }
    field_diff("content-type", &a.content_type, &b.content_type, &mut out);
    for (side, outcome) in [("A", a), ("B", b)] {
        if mode.expect_metadata && outcome.status == 200 && !outcome.has_query_metadata {
            out.push(format!("queryMetadata header missing on {side}"));
        }
    }
    out.extend(body_diff(
        "body",
        &a.content_type,
        &a.body,
        &b.content_type,
        &b.body,
        mode.count_valued,
    ));
    out
}

fn async_diff(a: &AsyncOutcome, b: &AsyncOutcome) -> Vec<String> {
    let mut out = Vec::new();
    field_diff(
        "submit status",
        &a.submit_status,
        &b.submit_status,
        &mut out,
    );
    if a.submit_status != 200 && b.submit_status != 200 {
        return out;
    }
    if a.timed_out && b.timed_out {
        out.push("timed out on both".to_owned());
    }
    field_diff("timed out", &a.timed_out, &b.timed_out, &mut out);
    field_diff("query status", &a.status, &b.status, &mut out);
    field_diff(
        "resource status",
        &a.resource_status,
        &b.resource_status,
        &mut out,
    );
    field_diff(
        "result status",
        &a.result_status,
        &b.result_status,
        &mut out,
    );
    field_diff(
        "result content-type",
        &a.result_content_type,
        &b.result_content_type,
        &mut out,
    );
    if let (Some(x), Some(y)) = (&a.result_body, &b.result_body) {
        out.extend(csv_diff(x, y));
    }
    out
}

/// Compares two outcomes for the same entry after normalizing the parts allowed to differ:
/// `queryMetadata` values, result ids, timings, sizes, JSON key order and CSV row order.
/// An empty result means they agree.
///
/// Error responses (status 400 and above on both sides) are compared by status only, because
/// error text and timestamps are not part of the contract.
pub fn compare_outcomes(a: &Outcome, b: &Outcome, mode: Mode) -> Vec<String> {
    match (a, b) {
        (Outcome::Sync(x), Outcome::Sync(y)) => sync_diff(x, y, mode),
        (Outcome::Async(x), Outcome::Async(y)) => async_diff(x, y),
        (Outcome::TimedOut, Outcome::TimedOut) => vec!["timed out on both".to_owned()],
        (Outcome::Failed(x), Outcome::Failed(y)) => {
            vec![format!("both failed: A={} B={}", shorten(x), shorten(y))]
        }
        (x, y) => vec![format!(
            "outcome kinds differ: A={} B={}",
            shorten(&format!("{x:?}")),
            shorten(&format!("{y:?}"))
        )],
    }
}

/// An entry that differed at first but agreed on a later attempt.
#[derive(Debug, Clone, PartialEq)]
pub struct Flaky {
    /// Entry name.
    pub entry: String,
    /// Whether server A answered differently between attempts.
    pub a_changed: bool,
    /// Whether server B answered differently between attempts.
    pub b_changed: bool,
}

/// An entry whose difference matched a divergence rule.
#[derive(Debug, Clone, PartialEq)]
pub struct Whitelisted {
    /// Entry name.
    pub entry: String,
    /// Name of the rule that accepted it.
    pub rule: String,
    /// The rule's reason.
    pub reason: String,
    /// The differences that were accepted.
    pub details: Vec<String>,
}

/// Mismatches, whitelisted differences, and the entries that only agreed after a retry.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CompareReport {
    /// Entries that still differed after every attempt, in corpus order.
    pub mismatches: Vec<Mismatch>,
    /// Entries whose difference matched a divergence rule, in corpus order.
    pub whitelisted: Vec<Whitelisted>,
    /// Entries that differed at first but agreed on a later attempt, in corpus order.
    pub flaky: Vec<Flaky>,
}

enum Verdict {
    Agree,
    Flaky { a_changed: bool, b_changed: bool },
    Whitelisted(Whitelisted),
    Mismatch(Vec<String>),
}

fn changed(before: &Outcome, after: &Outcome, mode: Mode) -> bool {
    match (before, after) {
        (Outcome::Failed(x), Outcome::Failed(y)) => x != y,
        (Outcome::TimedOut, Outcome::TimedOut) => false,
        _ => {
            let same_side = Mode {
                expect_metadata: false,
                ..mode
            };
            !compare_outcomes(before, after, same_side).is_empty()
        }
    }
}

async fn judge(
    a: &Client,
    b: &Client,
    entry: &CorpusEntry,
    index: usize,
    retries: usize,
    divergences: &Divergences,
) -> Verdict {
    let mode = Mode::for_entry(entry);
    let mut previous: Option<(Outcome, Outcome)> = None;
    let (mut a_changed, mut b_changed) = (false, false);
    let mut details = Vec::new();
    for attempt in 0..=retries {
        let nonce = 2 * (index * (retries + 1) + attempt) as u64;
        let left = a.run(entry, nonce).await;
        let right = b.run(entry, nonce + 1).await;
        if let Some((before_left, before_right)) = &previous {
            a_changed |= changed(before_left, &left, mode);
            b_changed |= changed(before_right, &right, mode);
        }
        details = compare_outcomes(&left, &right, mode);
        if details.is_empty() {
            return if attempt > 0 {
                Verdict::Flaky {
                    a_changed,
                    b_changed,
                }
            } else {
                Verdict::Agree
            };
        }
        if let Some(rule) = divergences.find(entry, &left, &right) {
            return Verdict::Whitelisted(Whitelisted {
                entry: entry.name.clone(),
                rule: rule.name.clone(),
                reason: rule.reason.clone(),
                details,
            });
        }
        previous = Some((left, right));
    }
    if retries > 0 {
        details.push(format!(
            "between attempts: A {}, B {}",
            if a_changed { "changed" } else { "stable" },
            if b_changed { "changed" } else { "stable" },
        ));
    }
    Verdict::Mismatch(details)
}

/// Runs every entry against both servers, one after the other per entry, with at most
/// `concurrency` entries in flight.
///
/// A difference that a divergence rule accepts is reported as whitelisted and not retried. Any
/// other difference is retried up to `retries` more times; it counts as a mismatch only if every
/// attempt differs, and is listed as flaky if a later attempt agrees. Both lists say which side
/// answered differently between attempts. The Java service keeps async results in an
/// unsynchronized map, so concurrency above one can produce spurious differences on a healthy
/// server.
pub async fn compare_corpus(
    a: &Client,
    b: &Client,
    entries: Vec<CorpusEntry>,
    concurrency: usize,
    retries: usize,
    divergences: Arc<Divergences>,
) -> CompareReport {
    let gate = Arc::new(Semaphore::new(concurrency.max(1)));
    let mut tasks = JoinSet::new();
    for (index, entry) in entries.into_iter().enumerate() {
        let (a, b, gate) = (a.clone(), b.clone(), Arc::clone(&gate));
        let divergences = Arc::clone(&divergences);
        tasks.spawn(async move {
            let _permit = gate.acquire().await;
            let verdict = judge(&a, &b, &entry, index, retries, &divergences).await;
            (index, entry.name, verdict)
        });
    }
    let mut mismatches = Vec::new();
    let mut whitelisted = Vec::new();
    let mut flaky = Vec::new();
    while let Some(joined) = tasks.join_next().await {
        let Ok((index, entry, verdict)) = joined else {
            continue;
        };
        match verdict {
            Verdict::Agree => {}
            Verdict::Flaky {
                a_changed,
                b_changed,
            } => flaky.push((
                index,
                Flaky {
                    entry,
                    a_changed,
                    b_changed,
                },
            )),
            Verdict::Whitelisted(item) => whitelisted.push((index, item)),
            Verdict::Mismatch(details) => mismatches.push((index, Mismatch { entry, details })),
        }
    }
    mismatches.sort_by_key(|(index, _)| *index);
    whitelisted.sort_by_key(|(index, _)| *index);
    flaky.sort_by_key(|(index, _)| *index);
    CompareReport {
        mismatches: mismatches.into_iter().map(|(_, m)| m).collect(),
        whitelisted: whitelisted.into_iter().map(|(_, w)| w).collect(),
        flaky: flaky.into_iter().map(|(_, f)| f).collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    const META: Mode = Mode {
        expect_metadata: true,
        count_valued: false,
    };
    const COUNTS: Mode = Mode {
        expect_metadata: true,
        count_valued: true,
    };

    #[test]
    fn json_ignores_key_order_and_number_representation() {
        let a = json!({"x": 1, "y": {"a": 2.0, "b": [1, 2]}});
        let b: Value = serde_json::from_str(r#"{"y":{"b":[1,2],"a":2},"x":1.0}"#).unwrap();
        assert!(json_diff(&a, &b).is_empty());
    }

    #[test]
    fn json_reports_path_of_difference() {
        let a = json!({"p": {"male": 3}});
        let b = json!({"p": {"male": 4, "female": 1}});
        let diff = json_diff(&a, &b);
        assert!(
            diff.iter()
                .any(|d| d.contains("$.p.male") && d.contains("A=3") && d.contains("B=4"))
        );
        assert!(diff.iter().any(|d| d.contains("only in B")));
    }

    #[test]
    fn csv_rows_compare_as_multisets_but_header_is_exact() {
        assert!(csv_diff("patient_id,a\n1,x\n2,y\n", "patient_id,a\n2,y\n1,x\n").is_empty());
        assert!(!csv_diff("patient_id,a\n1,x\n", "patient_id,b\n1,x\n").is_empty());
        let diff = csv_diff("h\n1\n1\n2\n", "h\n1\n2\n2\n");
        assert!(!diff.is_empty());
        assert!(csv_diff("h,i\n\"a\tb\",c\n", "h,i\n\"a\tb\",c\n").is_empty());
    }

    fn sync(status: u16, ct: &str, body: &str, meta: bool) -> Outcome {
        Outcome::Sync(SyncOutcome {
            status,
            content_type: Some(ct.into()),
            body: body.into(),
            has_query_metadata: meta,
        })
    }

    #[test]
    fn sync_requires_metadata_on_success_and_exact_text() {
        assert!(
            compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "text/plain", "5", true),
                META
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "text/plain", "6", true),
                META
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "text/plain", "5", false),
                META
            )
            .is_empty()
        );
        assert!(
            compare_outcomes(
                &sync(400, "text/plain", "no", false),
                &sync(400, "text/plain", "no", false),
                META
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "application/json", "5", true),
                META
            )
            .is_empty()
        );
    }

    #[test]
    fn sync_json_bodies_compare_structurally() {
        let a = sync(200, "application/json", r#"{"a":1,"b":2}"#, true);
        let b = sync(200, "application/json", r#"{"b":2.0,"a":1}"#, true);
        assert!(compare_outcomes(&a, &b, META).is_empty());
    }

    #[test]
    fn async_ignores_nothing_but_row_order() {
        let base = AsyncOutcome {
            submit_status: 200,
            submit_body: None,
            status: Some("AVAILABLE".into()),
            resource_status: Some("SUCCESS".into()),
            result_status: Some(200),
            result_content_type: Some("text/plain".into()),
            result_body: Some("PATIENT_NUM\n1\n2\n".into()),
            timed_out: false,
        };
        let reordered = AsyncOutcome {
            result_body: Some("PATIENT_NUM\n2\n1\n".into()),
            ..base.clone()
        };
        assert!(
            compare_outcomes(
                &Outcome::Async(base.clone()),
                &Outcome::Async(reordered),
                META
            )
            .is_empty()
        );
        let errored = AsyncOutcome {
            resource_status: Some("ERROR".into()),
            ..base.clone()
        };
        assert!(
            !compare_outcomes(&Outcome::Async(base), &Outcome::Async(errored), META).is_empty()
        );
    }

    #[test]
    fn different_outcome_kinds_mismatch() {
        let diff = compare_outcomes(
            &Outcome::Failed("boom".into()),
            &sync(200, "text/plain", "1", true),
            META,
        );
        assert_eq!(diff.len(), 1);
    }

    #[test]
    fn count_valued_bodies_flag_integer_against_float() {
        let int = sync(200, "application/json", r#"{"a":1,"b":{"c":2}}"#, true);
        let float = sync(200, "application/json", r#"{"a":1.0,"b":{"c":2}}"#, true);
        assert!(compare_outcomes(&int, &float, META).is_empty());
        let diff = compare_outcomes(&int, &float, COUNTS);
        assert_eq!(diff.len(), 1);
        assert!(diff[0].contains("number type") && diff[0].contains("$.a"));
        assert!(compare_outcomes(&int, &int, COUNTS).is_empty());
    }

    #[test]
    fn error_statuses_compare_by_status_only() {
        let a = sync(500, "application/json", r#"{"timestamp":1}"#, false);
        let b = sync(500, "application/json", r#"{"timestamp":2}"#, false);
        assert!(compare_outcomes(&a, &b, META).is_empty());
        let c = sync(400, "application/json", r#"{"timestamp":2}"#, false);
        assert_eq!(compare_outcomes(&a, &c, META).len(), 1);
    }

    #[test]
    fn missing_metadata_is_ignored_when_not_expected() {
        let a = sync(200, "application/json", "{}", false);
        let none = Mode::default();
        assert!(compare_outcomes(&a, &a, none).is_empty());
        assert!(!compare_outcomes(&a, &a, META).is_empty());
    }

    #[test]
    fn malformed_csv_rows_are_mismatches() {
        assert!(csv_diff("h1,h2\n1,a\n", "h1,h2\n1,a\n").is_empty());
        let diff = csv_diff("h1,h2\n1,a\n2\n", "h1,h2\n1,a\n2\n");
        assert!(diff.iter().any(|d| d.contains("malformed")), "{diff:?}");
        let diff = csv_diff("h1,h2\n1,a\n", "h1,h2\n1,a,extra\n");
        assert!(diff.iter().any(|d| d.contains("malformed")), "{diff:?}");
    }

    #[test]
    fn both_timed_out_is_a_difference() {
        assert_eq!(
            compare_outcomes(&Outcome::TimedOut, &Outcome::TimedOut, META).len(),
            1
        );
    }

    #[test]
    fn mode_follows_kind_and_result_type() {
        let mut entry = CorpusEntry {
            name: "n".into(),
            kind: Kind::Sync,
            result_type: "CROSS_COUNT".into(),
            request: Value::Null,
            raw_body: None,
            error_path: false,
        };
        assert_eq!(Mode::for_entry(&entry), COUNTS);
        entry.error_path = true;
        assert!(!Mode::for_entry(&entry).expect_metadata);
        entry.error_path = false;
        entry.kind = Kind::Search;
        entry.result_type = "SEARCH".into();
        assert_eq!(Mode::for_entry(&entry), Mode::default());
    }
}
