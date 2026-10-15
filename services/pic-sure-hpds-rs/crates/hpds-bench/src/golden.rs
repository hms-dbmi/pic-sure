use std::path::Path;
use std::sync::Arc;

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

use crate::client::{Client, Outcome};
use crate::compare::{Mismatch, json_diff};
use crate::corpus::CorpusEntry;
use crate::{BenchError, Result};

/// One assertion on a value found by walking object keys from the JSON response root.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Check {
    /// Object keys leading to the value.
    pub at: Vec<String>,
    /// The value must equal this JSON value (numbers by value).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub eq: Option<Value>,
    /// The value must be a number greater than this.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gt: Option<f64>,
    /// The value must be a number less than this.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub lt: Option<f64>,
    /// The value must be an object with exactly these keys.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub keys: Option<Vec<String>>,
    /// The value must be an object with at least this many keys.
    #[serde(default, rename = "minKeys", skip_serializing_if = "Option::is_none")]
    pub min_keys: Option<usize>,
    /// The value must be an object with a key that parses as a number below this.
    #[serde(default, rename = "anyKeyLt", skip_serializing_if = "Option::is_none")]
    pub any_key_lt: Option<f64>,
    /// `true` requires the path to exist, `false` requires it to be absent.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub present: Option<bool>,
}

/// What a golden case expects back.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Expect {
    /// Expected HTTP status for sync cases. Defaults to 200.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    /// Exact trimmed text body, for COUNT.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    /// Exact JSON body.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub json: Option<Value>,
    /// Individual assertions on a JSON body.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub checks: Vec<Check>,
    /// Expected CSV header line for async cases.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub header: Option<String>,
    /// Expected number of CSV data rows for async cases.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rows: Option<usize>,
}

/// A corpus entry together with its expectation.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GoldenCase {
    /// The request to send.
    #[serde(flatten)]
    pub entry: CorpusEntry,
    /// What must come back.
    pub expect: Expect,
}

/// A named list of golden cases.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GoldenFile {
    /// Dataset the numbers belong to.
    pub dataset: String,
    /// All cases.
    pub cases: Vec<GoldenCase>,
}

/// Reads a golden file.
///
/// # Errors
///
/// Fails when the file is missing or does not match the golden schema.
pub fn read_golden(path: &Path) -> Result<GoldenFile> {
    let text = std::fs::read_to_string(path).map_err(|e| BenchError::io(path, e))?;
    serde_json::from_str(&text).map_err(|e| BenchError::json(path.display().to_string(), e))
}

fn lookup<'a>(root: &'a Value, at: &[String]) -> Option<&'a Value> {
    at.iter()
        .try_fold(root, |value, key| value.get(key.as_str()))
}

fn numbers_match(expected: &Value, actual: &Value) -> bool {
    json_diff(expected, actual).is_empty()
}

fn check_value(check: &Check, root: &Value) -> Vec<String> {
    let label = check.at.join(" > ");
    let found = lookup(root, &check.at);
    if let Some(present) = check.present {
        return if found.is_some() == present {
            Vec::new()
        } else {
            vec![format!("{label}: expected present={present}")]
        };
    }
    let Some(value) = found else {
        return vec![format!("{label}: missing")];
    };
    let mut out = Vec::new();
    if let Some(expected) = &check.eq
        && !numbers_match(expected, value)
    {
        out.push(format!("{label}: expected {expected} got {value}"));
    }
    if let Some(bound) = check.gt
        && !value.as_f64().is_some_and(|v| v > bound)
    {
        out.push(format!("{label}: expected > {bound} got {value}"));
    }
    if let Some(bound) = check.lt
        && !value.as_f64().is_some_and(|v| v < bound)
    {
        out.push(format!("{label}: expected < {bound} got {value}"));
    }
    let object = value.as_object();
    if let Some(expected) = &check.keys {
        let mut want = expected.clone();
        want.sort();
        let mut got: Vec<String> = object
            .map(|o| o.keys().cloned().collect())
            .unwrap_or_default();
        got.sort();
        if want != got {
            out.push(format!("{label}: expected keys {want:?} got {got:?}"));
        }
    }
    if let Some(min) = check.min_keys
        && object.is_none_or(|o| o.len() < min)
    {
        out.push(format!("{label}: expected at least {min} keys got {value}"));
    }
    if let Some(bound) = check.any_key_lt {
        let any =
            object.is_some_and(|o| o.keys().any(|k| k.parse::<f64>().is_ok_and(|n| n < bound)));
        if !any {
            out.push(format!("{label}: expected a key below {bound}"));
        }
    }
    out
}

fn evaluate_sync(expect: &Expect, outcome: &crate::client::SyncOutcome) -> Vec<String> {
    let mut out = Vec::new();
    let want = expect.status.unwrap_or(200);
    if outcome.status != want {
        out.push(format!(
            "status expected {want} got {} body {:?}",
            outcome.status,
            outcome.body.chars().take(100).collect::<String>()
        ));
        return out;
    }
    if let Some(text) = &expect.text
        && outcome.body.trim() != text
    {
        out.push(format!(
            "body expected {text:?} got {:?}",
            outcome.body.trim().chars().take(100).collect::<String>()
        ));
    }
    if expect.json.is_some() || !expect.checks.is_empty() {
        match serde_json::from_str::<Value>(&outcome.body) {
            Ok(body) => {
                if let Some(expected) = &expect.json {
                    out.extend(
                        json_diff(expected, &body)
                            .into_iter()
                            .map(|d| format!("json {d}")),
                    );
                }
                for check in &expect.checks {
                    out.extend(check_value(check, &body));
                }
            }
            Err(e) => out.push(format!("body is not JSON: {e}")),
        }
    }
    out
}

fn evaluate_async(expect: &Expect, outcome: &crate::client::AsyncOutcome) -> Vec<String> {
    let mut out = Vec::new();
    if outcome.resource_status.as_deref() != Some("SUCCESS") || outcome.result_status != Some(200) {
        out.push(format!(
            "expected SUCCESS and 200, got {:?} and {:?}",
            outcome.resource_status, outcome.result_status
        ));
        return out;
    }
    let body = outcome.result_body.clone().unwrap_or_default();
    let mut lines = body.lines();
    let header = lines.next().unwrap_or_default();
    if let Some(want) = &expect.header
        && header != want
    {
        out.push(format!("header expected {want:?} got {header:?}"));
    }
    if let Some(rows) = expect.rows {
        let got = lines.filter(|l| !l.is_empty()).count();
        if got != rows {
            out.push(format!("rows expected {rows} got {got}"));
        }
    }
    out
}

/// Evaluates one outcome against an expectation. An empty result means it passed.
pub fn evaluate(expect: &Expect, outcome: &Outcome) -> Vec<String> {
    match outcome {
        Outcome::Sync(s) => evaluate_sync(expect, s),
        Outcome::Async(a) => evaluate_async(expect, a),
        Outcome::TimedOut => vec!["request timed out".to_owned()],
        Outcome::Failed(e) => vec![format!("request failed: {e}")],
    }
}

/// Runs every golden case against one server and returns the failures in file order.
pub async fn run_golden(client: &Client, file: GoldenFile, concurrency: usize) -> Vec<Mismatch> {
    let gate = Arc::new(Semaphore::new(concurrency.max(1)));
    let mut tasks = JoinSet::new();
    for (index, case) in file.cases.into_iter().enumerate() {
        let (client, gate) = (client.clone(), Arc::clone(&gate));
        tasks.spawn(async move {
            let _permit = gate.acquire().await;
            let outcome = client.run(&case.entry, index as u64).await;
            (index, case.entry.name, evaluate(&case.expect, &outcome))
        });
    }
    let mut failed = Vec::new();
    while let Some(joined) = tasks.join_next().await {
        if let Ok((index, entry, details)) = joined
            && !details.is_empty()
        {
            failed.push((index, Mismatch { entry, details }));
        }
    }
    failed.sort_by_key(|(index, _)| *index);
    failed.into_iter().map(|(_, m)| m).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::SyncOutcome;
    use serde_json::json;

    fn sync(body: &str) -> Outcome {
        Outcome::Sync(SyncOutcome {
            status: 200,
            content_type: Some("application/json".into()),
            body: body.into(),
            has_query_metadata: true,
        })
    }

    fn check(at: &[&str]) -> Check {
        Check {
            at: at.iter().map(|s| (*s).to_owned()).collect(),
            ..Check::default()
        }
    }

    #[test]
    fn text_and_json_expectations() {
        let text = Expect {
            text: Some("562".into()),
            ..Expect::default()
        };
        assert!(evaluate(&text, &sync(" 562\n")).is_empty());
        assert!(!evaluate(&text, &sync("563")).is_empty());
        let exact = Expect {
            json: Some(json!({"a": 1})),
            ..Expect::default()
        };
        assert!(evaluate(&exact, &sync(r#"{"a":1.0}"#)).is_empty());
        assert!(!evaluate(&exact, &sync(r#"{"a":2}"#)).is_empty());
    }

    #[test]
    fn checks_cover_every_operator() {
        let body = sync(r#"{"p":{"male":10,"female":5},"q":{"31.0":1,"62.0":2}}"#);
        let ok = Expect {
            checks: vec![
                Check {
                    eq: Some(json!(10)),
                    ..check(&["p", "male"])
                },
                Check {
                    gt: Some(0.0),
                    ..check(&["p", "female"])
                },
                Check {
                    lt: Some(6.0),
                    ..check(&["p", "female"])
                },
                Check {
                    keys: Some(vec!["female".into(), "male".into()]),
                    ..check(&["p"])
                },
                Check {
                    min_keys: Some(2),
                    ..check(&["q"])
                },
                Check {
                    any_key_lt: Some(32.0),
                    ..check(&["q"])
                },
                Check {
                    present: Some(false),
                    ..check(&["q", "44.0"])
                },
                Check {
                    present: Some(true),
                    ..check(&["q", "62.0"])
                },
            ],
            ..Expect::default()
        };
        assert_eq!(evaluate(&ok, &body), Vec::<String>::new());
        let bad = Expect {
            checks: vec![
                Check {
                    eq: Some(json!(11)),
                    ..check(&["p", "male"])
                },
                Check {
                    keys: Some(vec!["male".into()]),
                    ..check(&["p"])
                },
                Check {
                    present: Some(true),
                    ..check(&["q", "44.0"])
                },
                Check {
                    eq: Some(json!(1)),
                    ..check(&["missing"])
                },
            ],
            ..Expect::default()
        };
        assert_eq!(evaluate(&bad, &body).len(), 4);
    }

    #[test]
    fn status_mismatch_short_circuits() {
        let outcome = Outcome::Sync(SyncOutcome {
            status: 400,
            content_type: None,
            body: "bad".into(),
            has_query_metadata: false,
        });
        assert_eq!(evaluate(&Expect::default(), &outcome).len(), 1);
        let expect_400 = Expect {
            status: Some(400),
            ..Expect::default()
        };
        assert!(evaluate(&expect_400, &outcome).is_empty());
    }

    #[test]
    fn async_header_and_row_count() {
        let outcome = Outcome::Async(crate::client::AsyncOutcome {
            submit_status: 200,
            submit_body: None,
            status: Some("AVAILABLE".into()),
            resource_status: Some("SUCCESS".into()),
            result_status: Some(200),
            result_content_type: None,
            result_body: Some("PATIENT_NUM\n1\n2\n3\n".into()),
            timed_out: false,
            error: None,
        });
        let expect = Expect {
            header: Some("PATIENT_NUM".into()),
            rows: Some(3),
            ..Expect::default()
        };
        assert!(evaluate(&expect, &outcome).is_empty());
        let wrong = Expect {
            rows: Some(4),
            ..Expect::default()
        };
        assert_eq!(evaluate(&wrong, &outcome).len(), 1);
    }
}
