//! The conditions of the main consent contract's divergence rules: Rust's refusals where Java
//! main answers an unscoped sync query, fails in Jackson on a field its model does not declare,
//! or fails on a `null` in an authorization filter.
//! Every condition here holds only when the comparison knows its Java side and the Rust side
//! serves [`ConsentContract::Main`].

use serde_json::Value;

use super::{
    ConsentContract, JavaService, Side, authorization_filters, empty_sync_200, entry_query,
    refusal, rust_refused_java_failed, submit_failed,
};
use crate::client::{AsyncOutcome, Outcome, SyncOutcome};
use crate::corpus::{CorpusEntry, Kind};

/// The fields of main's `Query`.
const QUERY_FIELDS: [&str; 7] = [
    "select",
    "authorizationFilters",
    "phenotypicClause",
    "genomicFilters",
    "expectedResultType",
    "picsureId",
    "id",
];

/// The fields of main's `AuthorizationFilter`.
const AUTHORIZATION_FILTER_FIELDS: [&str; 2] = ["conceptPath", "values"];

/// The fields of main's `PhenotypicFilter`.
const FILTER_FIELDS: [&str; 6] = [
    "phenotypicFilterType",
    "conceptPath",
    "values",
    "min",
    "max",
    "not",
];

/// The fields of main's `PhenotypicSubquery`.
const SUBQUERY_FIELDS: [&str; 3] = ["not", "phenotypicClauses", "operator"];

/// The fields of main's `GenomicFilter`.
const GENOMIC_FILTER_FIELDS: [&str; 4] = ["key", "values", "min", "max"];

/// Main mode's refusal of a `null` element of `authorizationFilters`, exactly: `hpds_query`'s
/// `NULL_AUTHORIZATION_FILTER_MESSAGE`.
const NULL_FILTER_REFUSAL: &str = "an authorization filter is null";

/// Main mode's refusal of a `null` or missing `conceptPath`: `hpds_query`'s
/// `NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE`.
const NULL_PATH_REFUSAL: &str = "an authorization filter's conceptPath is null";

/// Main mode's refusal of `null` or missing `values`: `hpds_query`'s
/// `NULL_AUTHORIZATION_VALUES_MESSAGE`.
const NULL_VALUES_REFUSAL: &str = "an authorization filter's values are null";

/// Main mode's refusal of a `null` value looked up in a categorical concept: `hpds_query`'s
/// `NULL_AUTHORIZATION_VALUE_MESSAGE`.
const NULL_VALUE_REFUSAL: &str = "an authorization filter's values hold null";

/// The refusal main mode answers an unscoped sync query with, exactly: `hpds_query`'s
/// `AUTHORIZATION_FILTER_REQUIRED_MESSAGE`.
const AUTHORIZATION_FILTER_REQUIRED: &str = "Authorization filter is required for this query: \
     authorizationFilters is missing or empty and hpds.requireAuthorizationFilter is on";

/// The query field whose emptiness the requirement refuses.
const AUTHORIZATION_FILTERS: &str = "authorizationFilters";

/// The Java side when the comparison knows it and the Rust side serves the main contract.
fn main_java(java: Option<JavaService>) -> Option<JavaService> {
    java.filter(|java| java.contract == ConsentContract::Main)
}

/// The Java outcome and the Rust outcome, in that order.
fn sides<'o>(a: &'o Outcome, b: &'o Outcome, java: JavaService) -> (&'o Outcome, &'o Outcome) {
    match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    }
}

/// Whether the outcome is an async submit answered 500 with no query status.
fn submit_500(outcome: &Outcome) -> bool {
    matches!(outcome, Outcome::Async(x) if x.submit_status == 500
        && x.status.is_none()
        && x.resource_status.is_none()
        && !x.timed_out)
}

/// Whether `outcome` is Java's audit-logging form of a failure `failed` accepts in its other
/// form: a sync 500 read as a sync 200 with an empty body, or an async submit answered 500
/// with no status read as one answered 200.
pub(super) fn failed_with_500(outcome: &Outcome, failed: impl Fn(&Outcome) -> bool) -> bool {
    match outcome {
        Outcome::Sync(sync) if sync.status == 500 => failed(&Outcome::Sync(SyncOutcome {
            status: 200,
            body: String::new(),
            ..sync.clone()
        })),
        Outcome::Async(asynchronous) if submit_500(outcome) => {
            failed(&Outcome::Async(AsyncOutcome {
                submit_status: 200,
                submit_body: None,
                ..asynchronous.clone()
            }))
        }
        _ => false,
    }
}

/// See [`super::Condition::SyncFlagRefusal`].
pub(super) fn sync_flag_refusal(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = main_java(java).filter(|java| java.require_authorization_filter) else {
        return false;
    };
    if entry.kind != Kind::Sync || entry.result_type == "INFO_COLUMN_LISTING" {
        return false;
    }
    let unscoped = entry_query(entry).is_some_and(|query| {
        query.get(AUTHORIZATION_FILTERS).is_none_or(|filters| {
            filters.is_null() || filters.as_array().is_some_and(Vec::is_empty)
        })
    });
    let (java_outcome, rust_outcome) = sides(a, b, java);
    unscoped
        && refusal(rust_outcome).is_some_and(|body| body == AUTHORIZATION_FILTER_REQUIRED)
        && matches!(java_outcome, Outcome::Sync(s) if s.status == 200 && !s.body.is_empty())
}

/// Every field name in `object` outside `declared`.
fn undeclared<'v>(object: &'v Value, declared: &[&str], out: &mut Vec<&'v str>) {
    if let Some(map) = object.as_object() {
        out.extend(
            map.keys()
                .map(String::as_str)
                .filter(|key| !declared.contains(key)),
        );
    }
}

/// The names Jackson cannot map in a phenotypic clause, at any depth: fields neither a filter
/// nor a subquery declares, and the subquery fields of a clause that also holds filter fields.
fn clause_unknown_fields<'v>(clause: &'v Value, out: &mut Vec<&'v str>) {
    let Some(map) = clause.as_object() else {
        return;
    };
    let is_filter_only =
        |key: &str| FILTER_FIELDS.contains(&key) && !SUBQUERY_FIELDS.contains(&key);
    let is_subquery_only =
        |key: &str| SUBQUERY_FIELDS.contains(&key) && !FILTER_FIELDS.contains(&key);
    let mut fields = map.keys().map(String::as_str);
    let unknown: Vec<&str> = fields
        .clone()
        .filter(|key| !FILTER_FIELDS.contains(key) && !SUBQUERY_FIELDS.contains(key))
        .collect();
    if !unknown.is_empty() {
        out.extend(unknown);
    } else if fields.clone().any(is_filter_only) {
        out.extend(fields.by_ref().filter(|key| is_subquery_only(key)));
    }
    if let Some(children) = map.get("phenotypicClauses").and_then(Value::as_array) {
        children
            .iter()
            .for_each(|child| clause_unknown_fields(child, out));
    }
}

/// Every field name of `query` that main's request model does not declare where it appears.
fn unknown_fields(query: &Value) -> Vec<&str> {
    let mut out = Vec::new();
    undeclared(query, &QUERY_FIELDS, &mut out);
    for filter in authorization_filters(query) {
        undeclared(filter, &AUTHORIZATION_FILTER_FIELDS, &mut out);
    }
    if let Some(clause) = query.get("phenotypicClause") {
        clause_unknown_fields(clause, &mut out);
    }
    if let Some(filters) = query.get("genomicFilters").and_then(Value::as_array) {
        for filter in filters {
            undeclared(filter, &GENOMIC_FILTER_FIELDS, &mut out);
        }
    }
    out
}

/// See [`super::Condition::UnknownFieldRefused`].
pub(super) fn unknown_field_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = main_java(java) else {
        return false;
    };
    let Some(query) = entry_query(entry) else {
        return false;
    };
    let names = unknown_fields(&query);
    let (java_outcome, rust_outcome) = sides(a, b, java);
    !names.is_empty()
        && java_outcome.status_code() == 500
        && refusal(rust_outcome).is_some_and(|body| {
            names
                .iter()
                .any(|name| body.contains(&format!("unknown field `{name}`")))
        })
}

/// The refusals the `null`s of one authorization filter can draw from main mode: for a `null`
/// filter its own; for an object, the path refusal when `conceptPath` is `null` or missing,
/// the values refusal when `values` is, and the value refusal when `values` holds a `null`.
fn null_refusals(filter: &Value, out: &mut Vec<&'static str>) {
    if filter.is_null() {
        out.push(NULL_FILTER_REFUSAL);
        return;
    }
    if !filter.is_object() {
        return;
    }
    if filter.get("conceptPath").is_none_or(Value::is_null) {
        out.push(NULL_PATH_REFUSAL);
    }
    match filter.get("values") {
        None | Some(Value::Null) => out.push(NULL_VALUES_REFUSAL),
        Some(Value::Array(values)) if values.iter().any(Value::is_null) => {
            out.push(NULL_VALUE_REFUSAL);
        }
        Some(_) => {}
    }
}

/// See [`super::Condition::NullAuthorizationFilterMainRefused`].
pub(super) fn null_authorization_filter_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = main_java(java) else {
        return false;
    };
    let Some(query) = entry_query(entry) else {
        return false;
    };
    let mut refusals = Vec::new();
    for filter in authorization_filters(&query) {
        null_refusals(filter, &mut refusals);
    }
    !refusals.is_empty()
        && rust_refused_java_failed(
            a,
            b,
            Some(java),
            |body| refusals.contains(&body),
            |o| empty_sync_200(o) || submit_failed(o),
        )
}
