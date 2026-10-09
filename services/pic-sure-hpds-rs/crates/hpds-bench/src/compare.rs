use std::collections::BTreeMap;
use std::sync::Arc;

use serde_json::Value;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::client::{AsyncOutcome, Client, Outcome, SyncOutcome};
use crate::corpus::CorpusEntry;

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

fn walk_diff(path: &str, a: &Value, b: &Value, out: &mut Vec<String>) {
    if out.len() >= MAX_DIFF_LINES {
        return;
    }
    match (a, b) {
        (Value::Object(x), Value::Object(y)) => {
            for (key, left) in x {
                let child = format!("{path}.{key}");
                match y.get(key) {
                    Some(right) => walk_diff(&child, left, right, out),
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
                walk_diff(&format!("{path}[{index}]"), left, right, out);
            }
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

/// Structural JSON difference: object key order is ignored and numbers compare by value.
/// Returns at most a handful of lines.
pub fn json_diff(a: &Value, b: &Value) -> Vec<String> {
    let mut out = Vec::new();
    walk_diff("$", a, b, &mut out);
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
) -> Vec<String> {
    if is_json(ct_a)
        && is_json(ct_b)
        && let (Ok(x), Ok(y)) = (
            serde_json::from_str::<Value>(a),
            serde_json::from_str::<Value>(b),
        )
    {
        return json_diff(&x, &y)
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

fn parse_csv(text: &str) -> Vec<Vec<String>> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    reader
        .records()
        .filter_map(|r| r.ok())
        .map(|r| r.iter().map(str::to_owned).collect())
        .collect()
}

/// Compares two CSV result bodies: the header row exactly, the data rows as multisets.
///
/// Java writes patient rows in hash-set iteration order, so row order is not part of the contract.
pub fn csv_diff(a: &str, b: &str) -> Vec<String> {
    let (rows_a, rows_b) = (parse_csv(a), parse_csv(b));
    let mut out = Vec::new();
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

fn sync_diff(a: &SyncOutcome, b: &SyncOutcome) -> Vec<String> {
    let mut out = Vec::new();
    field_diff("status", &a.status, &b.status, &mut out);
    field_diff("content-type", &a.content_type, &b.content_type, &mut out);
    for (side, outcome) in [("A", a), ("B", b)] {
        if outcome.status == 200 && !outcome.has_query_metadata {
            out.push(format!("queryMetadata header missing on {side}"));
        }
    }
    out.extend(body_diff(
        "body",
        &a.content_type,
        &a.body,
        &b.content_type,
        &b.body,
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
pub fn compare_outcomes(a: &Outcome, b: &Outcome) -> Vec<String> {
    match (a, b) {
        (Outcome::Sync(x), Outcome::Sync(y)) => sync_diff(x, y),
        (Outcome::Async(x), Outcome::Async(y)) => async_diff(x, y),
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

/// Mismatches plus the entries that only agreed after a retry.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct CompareReport {
    /// Entries that still differed after every attempt, in corpus order.
    pub mismatches: Vec<Mismatch>,
    /// Entries that differed at first but agreed on a later attempt, in corpus order.
    pub flaky: Vec<String>,
}

/// Runs every entry against both servers, one after the other per entry, with at most
/// `concurrency` entries in flight.
///
/// An entry that differs is retried up to `retries` more times; it counts as a mismatch only if
/// every attempt differs, and is listed as flaky if a later attempt agrees. The Java service
/// keeps async results in an unsynchronized map, so concurrency above one can produce spurious
/// differences on a healthy server.
pub async fn compare_corpus(
    a: &Client,
    b: &Client,
    entries: Vec<CorpusEntry>,
    concurrency: usize,
    retries: usize,
) -> CompareReport {
    let gate = Arc::new(Semaphore::new(concurrency.max(1)));
    let mut tasks = JoinSet::new();
    for (index, entry) in entries.into_iter().enumerate() {
        let (a, b, gate) = (a.clone(), b.clone(), Arc::clone(&gate));
        tasks.spawn(async move {
            let _permit = gate.acquire().await;
            let mut details = Vec::new();
            for attempt in 0..=retries {
                let nonce = 2 * (index * (retries + 1) + attempt) as u64;
                let left = a.run(&entry, nonce).await;
                let right = b.run(&entry, nonce + 1).await;
                details = compare_outcomes(&left, &right);
                if details.is_empty() {
                    return (index, entry.name, details, attempt > 0);
                }
            }
            (index, entry.name, details, false)
        });
    }
    let mut mismatches = Vec::new();
    let mut flaky = Vec::new();
    while let Some(joined) = tasks.join_next().await {
        if let Ok((index, entry, details, recovered)) = joined {
            if !details.is_empty() {
                mismatches.push((index, Mismatch { entry, details }));
            } else if recovered {
                flaky.push((index, entry));
            }
        }
    }
    mismatches.sort_by_key(|(index, _)| *index);
    flaky.sort_by_key(|(index, _)| *index);
    CompareReport {
        mismatches: mismatches.into_iter().map(|(_, m)| m).collect(),
        flaky: flaky.into_iter().map(|(_, name)| name).collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

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
        assert!(csv_diff("h\n\"a\tb\",c\n", "h\n\"a\tb\",c\n").is_empty());
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
                &sync(200, "text/plain", "5", true)
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "text/plain", "6", true)
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "text/plain", "5", false)
            )
            .is_empty()
        );
        assert!(
            compare_outcomes(
                &sync(400, "text/plain", "no", false),
                &sync(400, "text/plain", "no", false)
            )
            .is_empty()
        );
        assert!(
            !compare_outcomes(
                &sync(200, "text/plain", "5", true),
                &sync(200, "application/json", "5", true)
            )
            .is_empty()
        );
    }

    #[test]
    fn sync_json_bodies_compare_structurally() {
        let a = sync(200, "application/json", r#"{"a":1,"b":2}"#, true);
        let b = sync(200, "application/json", r#"{"b":2.0,"a":1}"#, true);
        assert!(compare_outcomes(&a, &b).is_empty());
    }

    #[test]
    fn async_ignores_nothing_but_row_order() {
        let base = AsyncOutcome {
            submit_status: 200,
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
            compare_outcomes(&Outcome::Async(base.clone()), &Outcome::Async(reordered)).is_empty()
        );
        let errored = AsyncOutcome {
            resource_status: Some("ERROR".into()),
            ..base.clone()
        };
        assert!(!compare_outcomes(&Outcome::Async(base), &Outcome::Async(errored)).is_empty());
    }

    #[test]
    fn different_outcome_kinds_mismatch() {
        let diff = compare_outcomes(
            &Outcome::Failed("boom".into()),
            &sync(200, "text/plain", "1", true),
        );
        assert_eq!(diff.len(), 1);
    }
}
