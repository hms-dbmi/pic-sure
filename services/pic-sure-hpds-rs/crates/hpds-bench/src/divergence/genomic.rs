//! The conditions of the genomic divergence rules: Rust's refusals where Java fails on a
//! genomic filter, Rust's refusals of genomic filters Java never evaluates, and the
//! normalizations of the variant result types, INFO_COLUMN_LISTING and the genomic search
//! routes, decided from the two outcomes and the request alone.

use std::collections::BTreeMap;

use serde_json::Value;

use super::{
    JavaService, empty_500, ended_in_success, entry_query, failed_quietly, refused_against,
    rust_refused_java_failed, submit_failed,
};
use crate::client::Outcome;
use crate::corpus::{CorpusEntry, Kind};

/// The first header of an excerpt's two count columns, after the INFO columns.
const EXCERPT_COUNT_HEADER: &str = "Patients with this variant in subset";

/// What Rust answers a query with genomic filters when it serves no genomic store.
pub(super) const NO_GENOMIC_STORE: &str =
    "genomicFilters is not supported: this HPDS serves no genomic store";

/// The fragments of Rust's refusals of a genomic filter, each with the rule that names it.
/// A message matches a fragment when it contains it.
const REFUSALS: [(&str, Refusal); 14] = [
    ("a genomic filter's key is null", Refusal::Null),
    ("a genomic filter is null", Refusal::Null),
    (" hold null", Refusal::Null),
    ("needs both min and max", Refusal::Range),
    ("above max", Refusal::Range),
    (
        "names a column that is categorical on some genomic node",
        Refusal::Range,
    ),
    ("has no position Java can read", Refusal::SpecKey),
    ("with fewer than six fields", Refusal::ShortSpec),
    ("holds a metadata string twice", Refusal::DuplicateMetadata),
    (
        "no node holds the masks of a listed variant",
        Refusal::MasksMissing,
    ),
    (
        "selects a value that Java cannot look up",
        Refusal::Delimiter,
    ),
    (
        "whose values Java cannot use as column headers",
        Refusal::IdCube,
    ),
    (NO_GENOMIC_STORE, Refusal::NoStore),
    ("is not held by every genomic node", Refusal::ColumnMissing),
];

/// The class of one of Rust's genomic refusals.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) enum Refusal {
    /// A `null` genomic filter, key, zygosity or categorical value.
    Null,
    /// A numeric genomic filter with one bound, `min` above `max`, or on a column some node
    /// holds as categorical.
    Range,
    /// A spec key whose position Java cannot read.
    SpecKey,
    /// A selected variant whose spec has fewer than six fields.
    ShortSpec,
    /// A selected variant whose metadata holds one string twice.
    DuplicateMetadata,
    /// A listed variant no node holds masks for.
    MasksMissing,
    /// A column or value Java cannot look up through its `_____` delimiter.
    Delimiter,
    /// `ID_CUBE_NAME` naming a numeric concept.
    IdCube,
    /// Genomic filters sent to an HPDS that serves no genomic store.
    NoStore,
    /// An INFO column some genomic node does not hold.
    ColumnMissing,
}

/// The class of a refusal message, if it is one of Rust's genomic refusals.
fn refusal_class(body: &str) -> Option<Refusal> {
    REFUSALS
        .iter()
        .find(|(fragment, _)| body.contains(fragment))
        .map(|(_, class)| *class)
}

/// Whether the outcome is how Java fails on a genomic filter: a sync 200 with an empty body
/// or a 500, an async query it ended in ERROR, or an async submit answered 200 with no status.
fn java_failed(outcome: &Outcome) -> bool {
    failed_quietly(outcome)
        || submit_failed(outcome)
        || empty_500(outcome)
        || matches!(outcome, Outcome::Sync(s) if s.status == 500)
}

/// The rules where Java fails on a genomic filter and Rust refuses it with 400: Rust's
/// refusal is of `class`, and the Java side failed ([`java_failed`]). For
/// [`Refusal::Delimiter`] Java's answer is not checked, since Java either fails or silently
/// reads another key's variants there.
pub(super) fn refused_where_java_fails(
    class: Refusal,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let refused = |body: &str| refusal_class(body) == Some(class);
    if class == Refusal::Delimiter {
        return rust_refused_java_failed(a, b, java, refused, |_| true);
    }
    rust_refused_java_failed(a, b, java, refused, java_failed)
}

/// Whether `value` is an answer over no patients or no variants: every number in it zero, a
/// JSON array or object of such values (strings allowed, as in VARIANT_COUNT_FOR_QUERY's
/// message), `[]` written as text, or `No Variants Found`.
fn empty_json(value: &Value) -> bool {
    match value {
        Value::Number(n) => n.as_f64() == Some(0.0),
        Value::String(_) | Value::Null => true,
        Value::Bool(_) => false,
        Value::Array(items) => items.iter().all(empty_json),
        Value::Object(map) => map.values().all(empty_json),
    }
}

/// Whether Java answered as over an empty cohort or an empty variant list: a sync 200 whose
/// body is [`empty_json`] or `No Variants Found`, or an async query in SUCCESS whose CSV holds
/// a header and no rows.
fn empty_answer(outcome: &Outcome) -> bool {
    match outcome {
        Outcome::Sync(s) if s.status == 200 && !s.body.is_empty() => {
            s.body == "No Variants Found\n"
                || serde_json::from_str::<Value>(&s.body).is_ok_and(|v| empty_json(&v))
        }
        Outcome::Async(x) if ended_in_success(x) => x
            .result_body
            .as_deref()
            .is_some_and(|body| body.lines().filter(|l| !l.is_empty()).count() <= 1),
        _ => false,
    }
}

/// [`super::Condition::InvalidGenomicFilterNotEvaluated`]: Rust refused with one of its
/// genomic refusals and Java answered as over an empty cohort or an empty variant list
/// ([`empty_answer`]), because it never evaluated the filter.
pub(super) fn invalid_filter_not_evaluated(
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    rust_refused_java_failed(
        a,
        b,
        java,
        |body| {
            refusal_class(body).is_some_and(|c| {
                !matches!(
                    c,
                    Refusal::NoStore | Refusal::ColumnMissing | Refusal::IdCube
                )
            })
        },
        empty_answer,
    )
}

/// [`super::Condition::GenomicResultSizeLimit`]: Rust refused an excerpt or a variant list
/// for its size (`HPDS_MAX_EXCERPT_BYTES`) and Java answered 200 with a body.
pub(super) fn result_size_limit(a: &Outcome, b: &Outcome) -> bool {
    refused_against(
        a,
        b,
        |body| body.ends_with("HPDS_MAX_EXCERPT_BYTES allows"),
        |o| matches!(o, Outcome::Sync(s) if s.status == 200 && !s.body.is_empty()),
    )
}

/// [`super::Condition::GenomicTypeOnAsyncRoute`]: an async entry for a genomic result type or
/// INFO_COLUMN_LISTING, whose submit Rust refused with exactly `result type <type> is not
/// supported by POST /query; send it to POST /query/sync`, while Java failed it: a submit
/// answered 200 with no status, or a job ended in ERROR.
pub(super) fn genomic_type_on_async_route(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let expected = format!(
        "result type {} is not supported by POST /query; send it to POST /query/sync",
        entry.result_type
    );
    entry.kind == Kind::Async
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == expected,
            |o| submit_failed(o) || failed_quietly(o),
        )
}

/// Whether the entry's query holds a `null` genomic filter, a `null` key, or a `null` inside a
/// filter's `values`.
fn holds_genomic_null(entry: &CorpusEntry) -> bool {
    entry_query(entry)
        .and_then(|query| query.get("genomicFilters").cloned())
        .and_then(|filters| filters.as_array().cloned())
        .is_some_and(|filters| {
            filters.iter().any(|filter| {
                filter.is_null()
                    || filter.get("key").is_some_and(Value::is_null)
                    || filter
                        .get("values")
                        .and_then(Value::as_array)
                        .is_some_and(|values| values.iter().any(Value::is_null))
            })
        })
}

/// [`super::Condition::GenomicNullRefused`]: [`refused_where_java_fails`] for
/// [`Refusal::Null`], with a `null` in the request's genomic filters.
pub(super) fn null_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    holds_genomic_null(entry) && refused_where_java_fails(Refusal::Null, a, b, java)
}

/// [`super::Condition::SearchValuesRefused`]: a `/search/values` entry Rust refused with 400
/// (a page or size below 1 or not an integer, a missing parameter, a page outside Java's int
/// range, a column some node lacks), where Java answered 500 or 200 with an empty body.
pub(super) fn search_values_refused(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    entry.kind == Kind::SearchValues
        && refused_against(
            a,
            b,
            |_| true,
            |o| matches!(o, Outcome::Sync(s) if s.status == 500 || (s.status == 200 && s.body.is_empty())),
        )
}

/// [`super::Condition::SearchInfoColumnMissingRefused`]: a `/search` entry Rust refused with
/// 400 because a categorical INFO column matching the term is missing on some node, where
/// Java answered 200 with an empty body.
pub(super) fn search_info_column_missing_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
) -> bool {
    entry.kind == Kind::Search
        && refused_against(
            a,
            b,
            |body| refusal_class(body) == Some(Refusal::ColumnMissing),
            |o| matches!(o, Outcome::Sync(s) if s.status == 200 && s.body.is_empty()),
        )
}

/// The two sync 200 bodies, when both sides answered 200.
fn both_200<'o>(a: &'o Outcome, b: &'o Outcome) -> Option<(&'o str, &'o str)> {
    match (a, b) {
        (Outcome::Sync(x), Outcome::Sync(y)) if x.status == 200 && y.status == 200 => {
            Some((&x.body, &y.body))
        }
        _ => None,
    }
}

/// The specs of a VARIANT_LIST_FOR_QUERY body (`[a, b, c]`), sorted, or `None` when the body
/// is not such a list.
fn variant_list(body: &str) -> Option<Vec<&str>> {
    let inner = body.strip_prefix('[')?.strip_suffix(']')?;
    let mut specs: Vec<&str> = if inner.is_empty() {
        Vec::new()
    } else {
        inner.split(", ").collect()
    };
    specs.sort_unstable();
    Some(specs)
}

/// [`super::Condition::VariantListSet`]: both VARIANT_LIST_FOR_QUERY answers are lists that
/// hold the same specs.
pub(super) fn variant_list_set(a: &Outcome, b: &Outcome) -> bool {
    both_200(a, b).is_some_and(
        |(x, y)| matches!((variant_list(x), variant_list(y)), (Some(p), Some(q)) if p == q),
    )
}

/// One excerpt, split into its parts for comparison.
struct Excerpt<'t> {
    info: Vec<&'t str>,
    counts: [&'t str; 2],
    patients: Vec<&'t str>,
    rows: Vec<Vec<&'t str>>,
}

/// Reads a VCF_EXCERPT or AGGREGATE_VCF_EXCERPT body: a header of `CHROM`, `POSITION`,
/// `REF`, `ALT`, the INFO columns, the two count columns and the patient columns, then rows of
/// as many tab-separated cells, each line ending in a newline.
fn excerpt(body: &str) -> Option<Excerpt<'_>> {
    let lines: Vec<&str> = body.strip_suffix('\n')?.split('\n').collect();
    let (header, rows) = lines.split_first()?;
    let header: Vec<&str> = header.split('\t').collect();
    if header.get(..4)? != ["CHROM", "POSITION", "REF", "ALT"] {
        return None;
    }
    let at = header.iter().position(|h| *h == EXCERPT_COUNT_HEADER)?;
    let rows: Vec<Vec<&str>> = rows.iter().map(|r| r.split('\t').collect()).collect();
    if rows.iter().any(|r| r.len() != header.len()) {
        return None;
    }
    Some(Excerpt {
        info: header[4..at].to_vec(),
        counts: [header[at], *header.get(at + 1)?],
        patients: header[at + 2..].to_vec(),
        rows,
    })
}

/// The comma-separated values of an INFO cell, sorted: Java joins a `HashSet` with `,`.
fn cell_values(cell: &str) -> Vec<&str> {
    let mut values: Vec<&str> = cell.split(',').collect();
    values.sort_unstable();
    values
}

/// The patient columns of an excerpt as a multiset of (header, genotype cells).
fn patient_columns<'t>(e: &Excerpt<'t>) -> BTreeMap<(&'t str, Vec<&'t str>), usize> {
    let first = 4 + e.info.len() + 2;
    let mut out = BTreeMap::new();
    for (i, header) in e.patients.iter().enumerate() {
        let column: Vec<&str> = e.rows.iter().map(|r| r[first + i]).collect();
        *out.entry((*header, column)).or_insert(0) += 1;
    }
    out
}

/// [`super::Condition::ExcerptNormalized`]: two excerpts equal once the INFO header columns
/// are compared as a set (their order follows a Java `HashSet`), each INFO cell as a multiset
/// of its comma-separated values, and the patient columns as a multiset of (header, column)
/// (Java concatenates groups in `File.listFiles` order). Rows, the fixed cells and the counts
/// must match exactly.
pub(super) fn excerpt_normalized(a: &Outcome, b: &Outcome) -> bool {
    let Some((x, y)) = both_200(a, b) else {
        return false;
    };
    let (Some(x), Some(y)) = (excerpt(x), excerpt(y)) else {
        return false;
    };
    let mut info_x = x.info.clone();
    let mut info_y = y.info.clone();
    info_x.sort_unstable();
    info_y.sort_unstable();
    if info_x != info_y || x.counts != y.counts || x.rows.len() != y.rows.len() {
        return false;
    }
    let width = x.info.len();
    for (rx, ry) in x.rows.iter().zip(&y.rows) {
        if rx[..4] != ry[..4] || rx[4 + width..4 + width + 2] != ry[4 + width..4 + width + 2] {
            return false;
        }
        for (i, column) in x.info.iter().enumerate() {
            let Some(j) = y.info.iter().position(|c| c == column) else {
                return false;
            };
            if cell_values(rx[4 + i]) != cell_values(ry[4 + j]) {
                return false;
            }
        }
    }
    patient_columns(&x) == patient_columns(&y)
}

/// One INFO_COLUMN_LISTING entry as a comparable string: its fields in key order, `min` and
/// `max` as the bits of the `f32` they denote.
fn listing_entry(entry: &Value) -> String {
    let Value::Object(map) = entry else {
        return entry.to_string();
    };
    let fields: BTreeMap<&str, String> = map
        .iter()
        .map(|(key, value)| {
            let text = match (key.as_str(), value.as_f64()) {
                ("min" | "max", Some(number)) => format!("{:08x}", (number as f32).to_bits()),
                _ => value.to_string(),
            };
            (key.as_str(), text)
        })
        .collect();
    format!("{fields:?}")
}

/// [`super::Condition::InfoListingMultiset`]: both INFO_COLUMN_LISTING answers are arrays
/// holding the same entries, in any order, `min` and `max` compared as `f32`.
pub(super) fn info_listing_multiset(a: &Outcome, b: &Outcome) -> bool {
    let Some((x, y)) = both_200(a, b) else {
        return false;
    };
    let entries = |text: &str| -> Option<Vec<String>> {
        let Value::Array(items) = serde_json::from_str::<Value>(text).ok()? else {
            return None;
        };
        let mut out: Vec<String> = items.iter().map(listing_entry).collect();
        out.sort_unstable();
        Some(out)
    };
    matches!((entries(x), entries(y)), (Some(p), Some(q)) if p == q)
}

/// Body normalization of [`super::Condition::SearchInfoValuesSet`]: sorts the `values` of
/// every column under `results.info` of a `/search` answer.
pub(super) fn sort_info_values(value: &mut Value) {
    if let Some(Value::Object(columns)) = value.pointer_mut("/results/info") {
        for column in columns.values_mut() {
            if let Some(Value::Array(values)) = column.get_mut("values") {
                values.sort_by_key(Value::to_string);
            }
        }
    }
}

/// Java's `String.hashCode` spread as `HashMap.hash` does, `h ^ (h >>> 16)`.
fn java_spread_hash(text: &str) -> u32 {
    let h = text.encode_utf16().fold(0u32, |h, unit| {
        h.wrapping_mul(31).wrapping_add(u32::from(unit))
    });
    h ^ (h >> 16)
}

/// The values of a `/search/values` page as runs of one Java hash bucket in any table of 16
/// or more (the same low four bits of the spread hash): (bucket bits, sorted values) per run.
fn bucket_runs(values: &[Value]) -> Option<Vec<(u32, Vec<&str>)>> {
    let mut runs: Vec<(u32, Vec<&str>)> = Vec::new();
    for value in values {
        let text = value.as_str()?;
        let bits = java_spread_hash(text) & 15;
        match runs.last_mut() {
            Some((last, members)) if *last == bits => members.push(text),
            _ => runs.push((bits, vec![text])),
        }
    }
    for (_, members) in &mut runs {
        members.sort_unstable();
    }
    Some(runs)
}

/// [`super::Condition::SearchValuesBucketSet`]: two `/search/values` pages with the same
/// `page` and `total` that differ only inside Java hash buckets: split into runs of one bucket
/// ([`bucket_runs`]), both pages have the same runs in the same order and of the same sizes,
/// every run but the first and the last holds the same values, and the first and last runs,
/// which a page boundary may cut out of a larger bucket, may hold other values of that bucket.
/// The order inside one bucket is unspecified in Java.
pub(super) fn search_values_bucket_set(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    if entry.kind != Kind::SearchValues {
        return false;
    }
    let Some((x, y)) = both_200(a, b) else {
        return false;
    };
    let (Ok(x), Ok(y)) = (
        serde_json::from_str::<Value>(x),
        serde_json::from_str::<Value>(y),
    ) else {
        return false;
    };
    if x.get("page") != y.get("page") || x.get("total") != y.get("total") {
        return false;
    }
    let (Some(p), Some(q)) = (
        x.get("results").and_then(Value::as_array),
        y.get("results").and_then(Value::as_array),
    ) else {
        return false;
    };
    let (Some(p), Some(q)) = (bucket_runs(p), bucket_runs(q)) else {
        return false;
    };
    if p.len() != q.len() {
        return false;
    }
    let last = p.len().saturating_sub(1);
    p.iter()
        .zip(&q)
        .enumerate()
        .all(|(i, ((bp, mp), (bq, mq)))| {
            bp == bq && mp.len() == mq.len() && (i == 0 || i == last || mp == mq)
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::client::{AsyncOutcome, SyncOutcome};
    use serde_json::json;

    fn sync(status: u16, body: &str) -> Outcome {
        Outcome::Sync(SyncOutcome {
            status,
            content_type: Some("text/plain".into()),
            body: body.into(),
            has_query_metadata: status == 200,
        })
    }

    fn entry(kind: Kind, result_type: &str, query: Value) -> CorpusEntry {
        CorpusEntry {
            name: "g".into(),
            kind,
            result_type: result_type.into(),
            request: json!({"query": query}),
            raw_body: None,
            error_path: false,
        }
    }

    #[test]
    fn refusals_need_their_class_and_a_java_failure() {
        let refused = sync(
            400,
            "the numeric genomic filter on X needs both min and max",
        );
        assert!(refused_where_java_fails(
            Refusal::Range,
            &sync(200, ""),
            &refused,
            None
        ));
        assert!(refused_where_java_fails(
            Refusal::Range,
            &refused,
            &sync(500, ""),
            None
        ));
        assert!(!refused_where_java_fails(
            Refusal::Range,
            &sync(200, "0"),
            &refused,
            None
        ));
        assert!(!refused_where_java_fails(
            Refusal::Null,
            &sync(200, ""),
            &refused,
            None
        ));
        let delimiter = sync(
            400,
            "the genomic filter on X selects a value that Java cannot look up, since the column \
             or the value holds _____ or the value is empty",
        );
        assert!(refused_where_java_fails(
            Refusal::Delimiter,
            &sync(200, "12"),
            &delimiter,
            None
        ));
    }

    #[test]
    fn a_null_refusal_needs_a_null_in_the_genomic_filters() {
        let refused = sync(400, "a genomic filter's key is null");
        let with = entry(
            Kind::Sync,
            "COUNT",
            json!({"genomicFilters": [{"key": null}]}),
        );
        let without = entry(
            Kind::Sync,
            "COUNT",
            json!({"genomicFilters": [{"key": "a"}]}),
        );
        assert!(null_refused(&with, &sync(200, ""), &refused, None));
        assert!(!null_refused(&without, &sync(200, ""), &refused, None));
    }

    #[test]
    fn a_filter_java_never_evaluates_needs_an_empty_java_answer() {
        let refused = sync(400, "the variant spec rs1 has no position Java can read: x");
        assert!(invalid_filter_not_evaluated(
            &sync(200, "0"),
            &refused,
            None
        ));
        assert!(invalid_filter_not_evaluated(
            &sync(200, "[]"),
            &refused,
            None
        ));
        assert!(invalid_filter_not_evaluated(
            &sync(200, "No Variants Found\n"),
            &refused,
            None
        ));
        assert!(invalid_filter_not_evaluated(
            &sync(200, r#"{"count":0,"message":"Query ran successfully"}"#),
            &refused,
            None
        ));
        assert!(!invalid_filter_not_evaluated(
            &sync(200, "3"),
            &refused,
            None
        ));
        assert!(!invalid_filter_not_evaluated(
            &sync(200, "0"),
            &sync(400, NO_GENOMIC_STORE),
            None
        ));
        let export = Outcome::Async(AsyncOutcome {
            submit_status: 200,
            resource_status: Some("SUCCESS".into()),
            result_body: Some("PATIENT_NUM\n".into()),
            ..AsyncOutcome::default()
        });
        assert!(invalid_filter_not_evaluated(&export, &refused, None));
    }

    #[test]
    fn variant_lists_compare_as_sets() {
        assert!(variant_list_set(&sync(200, "[a, b]"), &sync(200, "[b, a]")));
        assert!(!variant_list_set(
            &sync(200, "[a, b]"),
            &sync(200, "[a, c]")
        ));
        assert!(!variant_list_set(&sync(200, "[a]"), &sync(200, "a")));
    }

    #[test]
    fn excerpts_ignore_info_column_order_cell_order_and_patient_column_order() {
        let a = "CHROM\tPOSITION\tREF\tALT\tG\tC\tPatients with this variant in subset\t\
                 Patients with this variant NOT in subset\t1\t2\n\
                 chr1\t5\tA\tG\tx,y\tm\t1/4\t0/4\t0/1\t0/0\n";
        let b = "CHROM\tPOSITION\tREF\tALT\tC\tG\tPatients with this variant in subset\t\
                 Patients with this variant NOT in subset\t2\t1\n\
                 chr1\t5\tA\tG\tm\ty,x\t1/4\t0/4\t0/0\t0/1\n";
        assert!(excerpt_normalized(&sync(200, a), &sync(200, b)));
        let c = b.replace("0/0\t0/1", "0/1\t0/0");
        assert!(!excerpt_normalized(&sync(200, a), &sync(200, &c)));
        let d = b.replace("1/4", "2/4");
        assert!(!excerpt_normalized(&sync(200, a), &sync(200, &d)));
    }

    #[test]
    fn listings_compare_as_multisets_with_float_bounds() {
        let a = r#"[{"key":"a","min":3.4028235E38,"max":1.4E-45},{"key":"b"}]"#;
        let b =
            r#"[{"key":"b"},{"max":1.401298464324817e-45,"key":"a","min":3.4028234663852886e38}]"#;
        assert!(info_listing_multiset(&sync(200, a), &sync(200, b)));
        assert!(!info_listing_multiset(&sync(200, a), &sync(200, "[]")));
    }

    #[test]
    fn search_value_pages_may_differ_only_inside_one_bucket() {
        let e = entry(Kind::SearchValues, "SEARCH_VALUES", Value::Null);
        let page = |values: &[&str]| {
            sync(
                200,
                &json!({"results": values, "page": 1, "total": 9}).to_string(),
            )
        };
        assert!(search_values_bucket_set(
            &e,
            &page(&["a", "b"]),
            &page(&["a", "b"])
        ));
        assert!(!search_values_bucket_set(
            &e,
            &page(&["a", "b"]),
            &page(&["b", "a"])
        ));
        let same_bucket: Vec<String> = ('a'..='z')
            .flat_map(|c| ('a'..='z').map(move |d| format!("{c}{d}")))
            .collect();
        let first = &same_bucket[0];
        let other = same_bucket
            .iter()
            .skip(1)
            .find(|s| java_spread_hash(s) & 15 == java_spread_hash(first) & 15)
            .unwrap();
        assert!(search_values_bucket_set(
            &e,
            &page(&[first, other]),
            &page(&[other, first])
        ));
        let bits = java_spread_hash(first) & 15;
        let third = same_bucket
            .iter()
            .filter(|s| *s != first && *s != other)
            .find(|s| java_spread_hash(s) & 15 == bits)
            .unwrap();
        let before = same_bucket
            .iter()
            .find(|s| java_spread_hash(s) & 15 != bits)
            .unwrap();
        let after = same_bucket
            .iter()
            .find(|s| {
                let b = java_spread_hash(s) & 15;
                b != bits && b != java_spread_hash(before) & 15
            })
            .unwrap();
        assert!(search_values_bucket_set(
            &e,
            &page(&[before, first, other]),
            &page(&[before, first, third])
        ));
        assert!(!search_values_bucket_set(
            &e,
            &page(&[before, first, other, after]),
            &page(&[before, first, third, after])
        ));
        assert!(!search_values_bucket_set(
            &e,
            &page(&[first, other]),
            &page(&[first])
        ));
    }
}
