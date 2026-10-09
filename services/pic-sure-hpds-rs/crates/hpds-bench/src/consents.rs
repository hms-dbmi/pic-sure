//! `userConsents` variants for corpus entries, for services that select consent partitions.
//!
//! The harmonization HPDS reads only the partitions whose names a query's `userConsents`
//! values name. [`expand`] turns each query of a corpus into several entries that differ only
//! in those consents, so one corpus exercises partition selection on both engines.

use std::path::{Path, PathBuf};
use std::str::FromStr;

use serde_json::{Value, json};

use crate::corpus::{CorpusEntry, Kind};
use crate::rng::Rng;
use crate::{BenchError, Result};

/// A consent value no partition is named after, used by the unknown-consent variants.
pub const UNKNOWN_CONSENT: &str = "phs999999.c1";

/// Store files whose presence makes a subdirectory a partition: a Rust index, a Java
/// dictionary, or a `columnMeta.csv` from either engine.
const PARTITION_MARKERS: [&str; 3] = ["pheno.index", "columnMeta.javabin", "columnMeta.csv"];

/// One way of filling a query's `userConsents`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConsentVariant {
    /// Every partition.
    All,
    /// One partition.
    One,
    /// Two partitions.
    Two,
    /// A random non-empty subset, with the unknown consent mixed in half of the time.
    Subset,
    /// Only [`UNKNOWN_CONSENT`].
    Unknown,
    /// `"userConsents": []`, which reads every partition when consents are not required.
    Empty,
    /// No `userConsents` field.
    Missing,
}

impl ConsentVariant {
    /// Every variant, in the order entries cycle through them.
    pub const ALL: [ConsentVariant; 7] = [
        ConsentVariant::All,
        ConsentVariant::One,
        ConsentVariant::Two,
        ConsentVariant::Subset,
        ConsentVariant::Unknown,
        ConsentVariant::Empty,
        ConsentVariant::Missing,
    ];

    /// The label used on the command line and in entry names.
    pub fn label(self) -> &'static str {
        match self {
            ConsentVariant::All => "all",
            ConsentVariant::One => "one",
            ConsentVariant::Two => "two",
            ConsentVariant::Subset => "subset",
            ConsentVariant::Unknown => "unknown",
            ConsentVariant::Empty => "empty",
            ConsentVariant::Missing => "none",
        }
    }

    /// Whether the variant carries no consent value, so a service that requires consents
    /// refuses it.
    pub fn lacks_consents(self) -> bool {
        matches!(self, ConsentVariant::Empty | ConsentVariant::Missing)
    }
}

impl FromStr for ConsentVariant {
    type Err = BenchError;

    fn from_str(text: &str) -> Result<Self> {
        ConsentVariant::ALL
            .into_iter()
            .find(|v| v.label() == text)
            .ok_or_else(|| {
                BenchError::Invalid(format!(
                    "unknown consent variant '{text}'; expected one of all, one, two, subset, unknown, empty, none"
                ))
            })
    }
}

/// How a corpus fills `userConsents`.
#[derive(Debug, Clone, PartialEq)]
pub struct ConsentPlan {
    /// Partition names, sorted.
    pub partitions: Vec<String>,
    /// Variants generated entries cycle through.
    pub variants: Vec<ConsentVariant>,
    /// Variants per generated entry. Fixed entries get every variant.
    pub per_entry: usize,
}

/// Parses a comma-separated variant list such as `all,one,none`.
///
/// # Errors
///
/// Fails on an unknown label or an empty list.
pub fn parse_variants(text: &str) -> Result<Vec<ConsentVariant>> {
    let variants = text
        .split(',')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(ConsentVariant::from_str)
        .collect::<Result<Vec<_>>>()?;
    if variants.is_empty() {
        return Err(BenchError::Invalid("no consent variants given".into()));
    }
    Ok(variants)
}

/// The partition names of a partitioned store directory: its immediate subdirectories whose
/// names do not start with `.` and that hold `pheno.index`, `columnMeta.javabin` or
/// `columnMeta.csv`, sorted.
///
/// # Errors
///
/// Fails when the directory cannot be read or holds no partition.
pub fn partitions_in_dir(dir: &Path) -> Result<Vec<String>> {
    let entries = std::fs::read_dir(dir).map_err(|e| BenchError::io(dir, e))?;
    let mut names = Vec::new();
    for entry in entries {
        let entry = entry.map_err(|e| BenchError::io(dir, e))?;
        let path = entry.path();
        let Some(name) = entry.file_name().to_str().map(str::to_owned) else {
            continue;
        };
        if name.starts_with('.') || !path.is_dir() {
            continue;
        }
        if PARTITION_MARKERS.iter().any(|m| path.join(m).is_file()) {
            names.push(name);
        }
    }
    names.sort();
    if names.is_empty() {
        return Err(BenchError::Invalid(format!(
            "{} holds no partition subdirectory",
            dir.display()
        )));
    }
    Ok(names)
}

/// The partition names of per-partition `columnMeta.csv` files: the name of each file's
/// directory, sorted and deduplicated.
///
/// # Errors
///
/// Fails when a file has no named parent directory.
pub fn partitions_of_files(files: &[PathBuf]) -> Result<Vec<String>> {
    let mut names = files
        .iter()
        .map(|file| {
            file.parent()
                .and_then(Path::file_name)
                .and_then(|n| n.to_str())
                .map(str::to_owned)
                .ok_or_else(|| {
                    BenchError::Invalid(format!("{} has no partition directory", file.display()))
                })
        })
        .collect::<Result<Vec<_>>>()?;
    names.sort();
    names.dedup();
    Ok(names)
}

fn consent_values(rng: &mut Rng, partitions: &[String], variant: ConsentVariant) -> Option<Value> {
    let mut values: Vec<String> = match variant {
        ConsentVariant::All => partitions.to_vec(),
        ConsentVariant::One => rng.sample(partitions, 1),
        ConsentVariant::Two => rng.sample(partitions, 2),
        ConsentVariant::Subset => {
            let count = rng.between(1, partitions.len().max(1));
            let mut chosen = rng.sample(partitions, count);
            if rng.below(2) == 0 {
                chosen.push(UNKNOWN_CONSENT.to_owned());
            }
            chosen
        }
        ConsentVariant::Unknown => vec![UNKNOWN_CONSENT.to_owned()],
        ConsentVariant::Empty => Vec::new(),
        ConsentVariant::Missing => return None,
    };
    values.sort();
    Some(Value::Array(
        values.into_iter().map(|v| json!({"value": v})).collect(),
    ))
}

fn with_consents(
    entry: &CorpusEntry,
    consents: Option<Value>,
    suffix: Option<&str>,
) -> CorpusEntry {
    let mut out = entry.clone();
    if let Some(query) = out.request.get_mut("query").and_then(Value::as_object_mut) {
        match consents {
            Some(values) => {
                query.insert("userConsents".into(), values);
            }
            None => {
                query.remove("userConsents");
            }
        }
    }
    if let Some(suffix) = suffix {
        out.name = format!("{}-c-{suffix}", entry.name);
    }
    out
}

fn takes_consents(entry: &CorpusEntry) -> bool {
    matches!(entry.kind, Kind::Sync | Kind::Async)
        && entry.raw_body.is_none()
        && entry.request.get("query").is_some_and(Value::is_object)
}

fn duplicate_key_entries(partitions: &[String]) -> Vec<CorpusEntry> {
    let first = json!([{"value": partitions[0]}]).to_string();
    let every = Value::Array(partitions.iter().map(|p| json!({"value": p})).collect()).to_string();
    let entry = |name: &str, kind: Kind, result_type: &str, body: String| CorpusEntry {
        name: format!("error-duplicate-key-{name}"),
        kind,
        result_type: result_type.to_owned(),
        request: Value::Null,
        raw_body: Some(body),
        error_path: true,
    };
    let two_lists = |result_type: &str| {
        format!(
            r#"{{"query":{{"select":[],"expectedResultType":"{result_type}","userConsents":{first},"userConsents":{every}}},"resourceUUID":null}}"#
        )
    };
    let inner = format!(
        r#"{{"query":{{"select":[],"expectedResultType":"COUNT","userConsents":[{{"value":"{UNKNOWN_CONSENT}","value":"{}"}}]}},"resourceUUID":null}}"#,
        partitions[0]
    );
    vec![
        entry("consents", Kind::Sync, "COUNT", two_lists("COUNT")),
        entry(
            "consents-async",
            Kind::Async,
            "PATIENTS",
            two_lists("PATIENTS"),
        ),
        entry("consent-value", Kind::Sync, "COUNT", inner),
    ]
}

/// Expands a corpus into consent variants.
///
/// Fixed entries (named `fixed-*`) get every variant of the plan. Each generated entry gets
/// `per_entry` consecutive variants of the plan, starting at a rotating offset so every
/// variant meets every shape and result type. Their names gain `-c-<variant>`. Error-path
/// entries keep their name and carry every partition, so they probe the invalid request and
/// not the consents; search and unknown-id entries are unchanged. Three error-path entries
/// whose raw bodies repeat a key (`userConsents` twice, `value` twice) are added. The
/// variants draw from their own generator seeded from `seed`, so the queries themselves are
/// those of the corpus without consents.
///
/// # Errors
///
/// Fails when the plan has no partition, no variant or a zero `per_entry`.
pub fn expand(
    entries: Vec<CorpusEntry>,
    plan: &ConsentPlan,
    seed: u64,
) -> Result<Vec<CorpusEntry>> {
    if plan.partitions.is_empty() || plan.variants.is_empty() || plan.per_entry == 0 {
        return Err(BenchError::Invalid(
            "a consent plan needs partitions, variants and at least one variant per entry".into(),
        ));
    }
    let mut rng = Rng::new(seed ^ 0xC0_25E7_5EED);
    let every = consent_values(&mut rng, &plan.partitions, ConsentVariant::All);
    let mut out = Vec::new();
    let mut generated = 0usize;
    for entry in entries {
        if !takes_consents(&entry) {
            out.push(entry);
            continue;
        }
        if entry.error_path {
            out.push(with_consents(&entry, every.clone(), None));
            continue;
        }
        let variants: Vec<ConsentVariant> = if entry.name.starts_with("fixed-") {
            plan.variants.clone()
        } else {
            let start = generated % plan.variants.len();
            generated += 1;
            (0..plan.per_entry.min(plan.variants.len()))
                .map(|k| plan.variants[(start + k) % plan.variants.len()])
                .collect()
        };
        for variant in variants {
            let values = consent_values(&mut rng, &plan.partitions, variant);
            out.push(with_consents(&entry, values, Some(variant.label())));
        }
    }
    out.extend(duplicate_key_entries(&plan.partitions));
    Ok(out)
}

/// Whether a request's query carries at least one non-null `userConsents` value. A query
/// sent as a JSON string is parsed first.
pub fn has_consent_values(request: &Value) -> bool {
    let query = match request.get("query") {
        Some(Value::String(text)) => serde_json::from_str::<Value>(text).ok(),
        Some(query) => Some(query.clone()),
        None => None,
    };
    query
        .as_ref()
        .and_then(|q| q.get("userConsents"))
        .and_then(Value::as_array)
        .is_some_and(|values| {
            values
                .iter()
                .any(|v| v.get("value").is_some_and(Value::is_string))
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn query_entry(name: &str, kind: Kind, error_path: bool) -> CorpusEntry {
        CorpusEntry {
            name: name.into(),
            kind,
            result_type: if kind == Kind::Async {
                "PATIENTS"
            } else {
                "COUNT"
            }
            .into(),
            request: json!({"query": {"select": [], "expectedResultType": "COUNT"}, "resourceUUID": null}),
            raw_body: None,
            error_path,
        }
    }

    fn plan(per_entry: usize) -> ConsentPlan {
        ConsentPlan {
            partitions: vec!["p1".into(), "p2".into(), "p3".into(), "p4".into()],
            variants: ConsentVariant::ALL.to_vec(),
            per_entry,
        }
    }

    fn values(entry: &CorpusEntry) -> Option<Vec<String>> {
        entry.request["query"].get("userConsents").map(|v| {
            v.as_array()
                .unwrap()
                .iter()
                .map(|c| c["value"].as_str().unwrap().to_owned())
                .collect()
        })
    }

    #[test]
    fn variants_fill_consents_as_named() {
        let entries = vec![query_entry("fixed-count-all", Kind::Sync, false)];
        let out = expand(entries, &plan(3), 1).unwrap();
        let by_name = |suffix: &str| {
            out.iter()
                .find(|e| e.name == format!("fixed-count-all-c-{suffix}"))
                .unwrap()
        };
        assert_eq!(values(by_name("all")).unwrap(), ["p1", "p2", "p3", "p4"]);
        assert_eq!(values(by_name("one")).unwrap().len(), 1);
        assert_eq!(values(by_name("two")).unwrap().len(), 2);
        let subset = values(by_name("subset")).unwrap();
        assert!(
            !subset.is_empty()
                && subset
                    .iter()
                    .all(|v| v.starts_with('p') || v == UNKNOWN_CONSENT)
        );
        assert_eq!(values(by_name("unknown")).unwrap(), [UNKNOWN_CONSENT]);
        assert_eq!(values(by_name("empty")).unwrap(), Vec::<String>::new());
        assert_eq!(values(by_name("none")), None);
        assert!(has_consent_values(&by_name("unknown").request));
        assert!(!has_consent_values(&by_name("empty").request));
        assert!(!has_consent_values(&by_name("none").request));
    }

    #[test]
    fn generated_entries_rotate_through_every_variant() {
        let entries: Vec<CorpusEntry> = (0..14)
            .map(|i| query_entry(&format!("{i:04}-x-count"), Kind::Sync, false))
            .collect();
        let out = expand(entries, &plan(2), 7).unwrap();
        let generated: Vec<&CorpusEntry> = out.iter().filter(|e| !e.error_path).collect();
        assert_eq!(generated.len(), 28);
        let labels: BTreeSet<&str> = generated
            .iter()
            .map(|e| e.name.rsplit("-c-").next().unwrap())
            .collect();
        assert_eq!(labels.len(), 7, "{labels:?}");
        assert!(generated.iter().any(|e| e.name == "0000-x-count-c-all"));
        assert!(generated.iter().any(|e| e.name == "0000-x-count-c-one"));
        assert!(generated.iter().any(|e| e.name == "0001-x-count-c-one"));
    }

    #[test]
    fn error_entries_carry_every_partition_and_others_stay_unchanged() {
        let mut search = query_entry("search-path-0", Kind::Search, false);
        search.request = json!({"query": "AGE", "resourceUUID": null});
        let mut raw = query_entry("error-malformed-body", Kind::Sync, true);
        raw.raw_body = Some("{".into());
        let entries = vec![
            query_entry("error-empty-subquery", Kind::Sync, true),
            search.clone(),
            raw.clone(),
        ];
        let out = expand(entries, &plan(3), 1).unwrap();
        assert_eq!(out[0].name, "error-empty-subquery");
        assert_eq!(values(&out[0]).unwrap(), ["p1", "p2", "p3", "p4"]);
        assert_eq!(out[1], search);
        assert_eq!(out[2], raw);
        let duplicates: Vec<&CorpusEntry> = out
            .iter()
            .filter(|e| e.name.starts_with("error-duplicate-key-"))
            .collect();
        assert_eq!(duplicates.len(), 3);
        for entry in duplicates {
            let body = entry.raw_body.as_deref().unwrap();
            assert!(serde_json::from_str::<Value>(body).is_ok(), "{body}");
            assert!(
                body.matches("\"userConsents\"").count() == 2
                    || body.matches("\"value\"").count() == 2
            );
        }
    }

    #[test]
    fn expansion_is_deterministic() {
        let entries: Vec<CorpusEntry> = (0..10)
            .map(|i| query_entry(&format!("{i:04}-x-count"), Kind::Sync, false))
            .collect();
        assert_eq!(
            expand(entries.clone(), &plan(3), 4).unwrap(),
            expand(entries.clone(), &plan(3), 4).unwrap()
        );
        assert_ne!(
            expand(entries.clone(), &plan(3), 4).unwrap(),
            expand(entries, &plan(3), 5).unwrap()
        );
    }

    #[test]
    fn variant_labels_parse_and_unknown_ones_fail() {
        assert_eq!(
            parse_variants("all, none,unknown").unwrap(),
            vec![
                ConsentVariant::All,
                ConsentVariant::Missing,
                ConsentVariant::Unknown
            ]
        );
        assert!(parse_variants("all,most").is_err());
        assert!(parse_variants("").is_err());
        assert!(expand(Vec::new(), &plan(0), 1).is_err());
    }

    #[test]
    fn partitions_come_from_store_dirs_or_column_meta_parents() {
        let dir = tempfile::tempdir().unwrap();
        for (name, marker) in [
            ("b", "pheno.index"),
            ("a", "columnMeta.javabin"),
            ("c", "columnMeta.csv"),
            (".hidden", "pheno.index"),
            ("other", "notes.txt"),
        ] {
            std::fs::create_dir(dir.path().join(name)).unwrap();
            std::fs::write(dir.path().join(name).join(marker), b"").unwrap();
        }
        std::fs::write(dir.path().join("encryption_key"), b"k").unwrap();
        assert_eq!(partitions_in_dir(dir.path()).unwrap(), ["a", "b", "c"]);
        assert!(partitions_in_dir(&dir.path().join("other")).is_err());
        let files = vec![
            dir.path().join("p2/columnMeta.csv"),
            dir.path().join("p1/columnMeta.csv"),
        ];
        assert_eq!(partitions_of_files(&files).unwrap(), ["p1", "p2"]);
    }
}
