use std::path::Path;

use serde::Deserialize;
use serde_json::Value;

use crate::client::{AsyncOutcome, Outcome, SyncOutcome};
use crate::compare::{csv_diff, json_diff_with};
use crate::consents::has_consent_values;
use crate::corpus::{CorpusEntry, Kind};
use crate::{BenchError, Result};

mod genomic;
mod main_contract;

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
    /// Both sides answered a sync request with 200, the Java side (which must be known,
    /// [`Divergences::for_java`]) with an empty body and the other with a body, and the entry's
    /// phenotypic clause holds a `FILTER` with a `min`: a number, or a string Jackson coerces
    /// to one (`"NaN"`, `"5"`). The request may be a raw body. An empty 200 from Rust never
    /// matches.
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
    /// Both sides accepted an async submit, the Java side (which must be known,
    /// [`Divergences::for_java`]) ended in ERROR and the other in SUCCESS, and the entry's
    /// phenotypic clause holds a `FILTER` with a `min` (as for
    /// [`Condition::RangeMinSuccessBodyEmpty`]). An ERROR from Rust never matches.
    RangeMinAsyncError,
    /// One side refused with 400 `<path> is not a categorical variable` (values on a numeric
    /// concept), and the other answered the sync request 200 with an empty body or ended the
    /// accepted async query in ERROR.
    ValuesOnNumericRefused,
    /// One side refused with 400 `<path> is not a numeric variable` (a range on a categorical
    /// concept), and the other answered the sync request 200 with an empty body or ended the
    /// accepted async query in ERROR.
    RangeOnCategoricalRefused,
    /// The entry's query holds a filter whose shape Java's `PhenotypicFilterValidator` refuses
    /// (a REQUIRED or ANY_RECORD_OF filter with `values` or a bound, or a FILTER with a
    /// non-empty `values` and a bound); the Rust side refused with exactly that filter's
    /// message (`Required filter with concept path <path> cannot have categorical values or
    /// min/max set`, or `Filter with concept path <path> cannot have both categorical values
    /// and min/max set`), and the Java side answered the sync request 200 with an empty body or
    /// ended the accepted async query in ERROR. With the Java side unknown, either side may be
    /// Java.
    FilterShapeRefused,
    /// An async entry whose submit one side refused with 400 `Result type <type> is computed
    /// synchronously. Submit it with POST /query/sync.`, while the other accepted it (200).
    SyncTypeOnAsyncRoute,
    /// An async entry for a genomic result type or INFO_COLUMN_LISTING, which POST /query
    /// serves in neither service: the Rust side refused the submit with exactly `result type
    /// <type> is not supported by POST /query; send it to POST /query/sync`, while the Java
    /// side answered the submit 200 with no status or ended the job in ERROR
    /// (`initializeResult` has no processor for the type). With the Java side unknown, either
    /// side may be Java.
    GenomicTypeOnAsyncRoute,
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
    /// `hpds_format::jackson_string_double`), the Java side answered 500 with an empty body (the
    /// Jackson exception escapes `convertIncomingQuery`; the response carries no text), and the
    /// other side refused with 400 naming that string as an invalid value. A 500 with any body,
    /// a 400 for another reason, or a string Jackson accepts does not match. Strings refused only
    /// for their length are [`Condition::NumberTooLongRefused`].
    StringBoundRefused,
    /// The entry's query holds a `FILTER` with no criterion (no `values` and no `min` or `max`,
    /// a blank string bound counting as none, as Jackson reads it), one side refused with 400
    /// `Either values or one of min/max must be set for a filter on <path>`, and the other
    /// answered the sync request 200 with an empty body or ended the async query in ERROR,
    /// as Java does when `PhenotypicQueryExecutor` throws on such a filter.
    FilterWithoutCriteriaRefused,
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
    /// The entry's query holds `null` in `select`, the Rust side refused with 400 `select holds
    /// null, which Java cannot answer here`, and the Java side failed: a sync 200 with an empty
    /// body (CROSS_COUNT and OBSERVATION_CROSS_COUNT put the null path in a map that refuses
    /// null keys), or an async submit answered 200 with no status (`QueryValidator` looks the
    /// null path up in a `TreeMap`). With the Java side unknown, either side may be Java.
    NullSelectRefused,
    /// The entry's query holds a phenotypic filter, at any depth, whose `conceptPath` is
    /// `null` or missing, or an authorization filter whose `conceptPath` is `null`; the Rust
    /// side refused with exactly `a phenotypic filter's conceptPath is null` or `an
    /// authorization filter's conceptPath is null`, and the Java side failed: a sync 200 with an empty body, an async
    /// submit answered 200 with no status, or an async query that ended in ERROR.
    NullConceptPathRefused,
    /// The entry's query holds a phenotypic filter, at any depth, whose `values` holds `null`;
    /// the Rust side refused with exactly `a phenotypic filter's values hold null`, and the Java
    /// side failed: a sync 200 with an empty body or an async query that ended in ERROR.
    NullFilterValueRefused,
    /// The entry's query has a missing or `null` `expectedResultType`; the Rust side refused
    /// with exactly `the query is not valid: expectedResultType is null`, and the Java side
    /// failed: a sync 200 with an
    /// empty body or an async submit answered 200 with no status.
    NullResultTypeRefused,
    /// The entry's query holds an authorization filter that is `null`, or whose `conceptPath`
    /// or one of whose `values` is `null`; the Java side (which must be known) failed, a sync 200 with an
    /// empty body or an async query that ended in ERROR, while the Rust side answered, a sync
    /// 200 with a body or an async query that ended in SUCCESS. The main branch applies
    /// authorization filters and fails on the null; Rust ignores them, as the consent branch
    /// does on the sync route.
    NullAuthorizationFilterIgnored,
    /// The entry's query holds a phenotypic filter, at any depth, whose `phenotypicFilterType`
    /// is `null` or missing; the Rust side refused with exactly `a phenotypic filter's
    /// phenotypicFilterType is null`, and the Java side failed: a sync 200 with an empty body
    /// or an async submit answered 200 with no status (Java switches on the type).
    NullFilterTypeRefused,
    /// The entry's query holds a subquery, at any depth, whose `phenotypicClauses` holds
    /// `null`; the Rust side refused with exactly `a phenotypic subquery's phenotypicClauses
    /// hold null`, and the Java side failed: a sync 200 with an empty body or an async query
    /// that ended in ERROR.
    NullClauseRefused,
    /// An async entry whose query's `authorizationFilters` holds a `null` element; the Rust
    /// side refused the submit with exactly `an authorization filter is null`, and the Java
    /// side answered the submit 200 with no status (its validator throws on the element).
    NullAuthorizationFilterRefused,
    /// The entry's query is sent as a JSON string holding an object with a repeated key; the
    /// Java side answered (Jackson keeps the last value: a sync 200 with a body, or an accepted
    /// async submit), and the Rust side refused with 400 `the query is not valid: duplicate
    /// key "<key>" at line <n> column <m>` naming that key. Under the main contract the key
    /// is not `authorizationFilters`, whose last value main keeps.
    DuplicateKeyInStringQuery,
    /// The entry's query names an `expectedResultType` string Jackson's enum coercion refuses
    /// (not a name of Java's `ResultType`, trimmed or not, nor its ordinal); the Java side
    /// answered 500 with an empty body, and the Rust side refused with exactly `the query is
    /// not valid: invalid value: string "<value>", expected one of [<the 14 names>]`.
    UnknownResultTypeRefused,
    /// The entry's query holds a `picsureId` or `id` string Jackson's `UUIDDeserializer`
    /// refuses (see `hpds_format::jackson_uuid`); the Java side answered 500 with an empty
    /// body, and the Rust side refused with exactly `the query is not valid: "<value>" is not
    /// a UUID in the canonical 8-4-4-4-12 form or 16 bytes of Base64`.
    InvalidUuidRefused,
    /// The entry's query holds a subquery, at any depth, with no `operator` (or `null`); the
    /// Java side failed (a sync 200 with an empty body, or an async query that ended in ERROR,
    /// from the switch on the missing operator), and the Rust side refused with exactly `a
    /// phenotypic subquery must name an operator (AND or OR)`.
    MissingOperatorRefused,
    /// The entry's query holds a `userConsents` object with a field other than `value`; the
    /// Java side answered 500 with an empty body (Jackson refuses the unknown field, and the
    /// main branch knows no `userConsents` at all), and the Rust side refused with exactly
    /// `the query is not valid: unknown field `<name>`, expected `value`` naming one of those
    /// fields.
    ConsentExtraFieldRefused,
    /// An async DATAFRAME_TIMESERIES or DATAFRAME_PFB entry: the Java side (which must be
    /// known) ended the query in SUCCESS and the Rust side accepted the submit and ended it in
    /// ERROR with `resultMetadata.error` exactly `<type> exports are not supported by this
    /// phenotype-only HPDS`, as Rust does for these two export types it does not produce.
    ExportTypeOutOfScope,
    /// The entry's query holds a subquery, at any depth, whose `phenotypicClauses` is empty;
    /// the Rust side refused with exactly `a phenotypic subquery must contain at least one
    /// clause`, and the Java side answered the sync request 200 with an empty body (its
    /// executor fails on the empty clause list).
    EmptySubqueryRefused,
    /// The entry's genomic filters hold a `null` (a filter, a key, or an element of `values`);
    /// the Rust side refused with its `null` refusal and the Java side failed: a sync 200 with
    /// an empty body or a 500, an async job in ERROR, or an async submit answered 200 with no
    /// status.
    GenomicNullRefused,
    /// The Rust side refused a numeric genomic filter with one bound, a `min` above its `max`,
    /// or one on a column some node holds as categorical, and the Java side failed as for
    /// [`Condition::GenomicNullRefused`] (Guava's `Range.closed` throws, or a `null`
    /// continuous index).
    GenomicRangeRefused,
    /// The Rust side refused a spec key whose position is not a Java `int` (`the variant spec
    /// <key> has no position Java can read`), and the Java side failed.
    GenomicSpecKeyRefused,
    /// The Rust side refused because a selected variant's spec has fewer than six fields,
    /// which Java cannot parse into a `VariantSpec`, and the Java side failed.
    GenomicShortSpecRefused,
    /// The Rust side refused because a listed variant's metadata holds one string twice,
    /// where Java's `Set.of` throws, and the Java side failed.
    GenomicDuplicateMetadataRefused,
    /// The Rust side refused an excerpt listing a variant no node holds masks for, where
    /// Java's `Optional.get` throws, and the Java side failed.
    ExcerptMasksMissingRefused,
    /// The Rust side refused a selected column or value that Java looks up through its
    /// `_____` delimiter and cannot split back into itself, and the request holds a key or
    /// value of that shape. Java either fails or silently reads another key's variants, so its
    /// answer is not checked.
    InfoKeyDelimiterRefused,
    /// The Rust side refused a VCF_EXCERPT because `ID_CUBE_NAME` names a numeric concept,
    /// whose values Java casts to `String` for the patient column headers and fails on, and
    /// the Java side failed.
    IdCubeNumericRefused,
    /// The Rust side refused genomic filters with exactly `genomicFilters is not supported:
    /// this HPDS serves no genomic store`, and the Java side, whose no-op genomic processor
    /// fails on them, failed.
    GenomicNoStoreRefused,
    /// The Rust side refused with one of its refusals of a genomic filter invalid as sent,
    /// the request holds that filter, and the Java side answered as over an empty cohort or an
    /// empty variant list (a count of 0, cross counts of 0, `[]`, `No Variants Found`, an
    /// export with no rows), because it never evaluated the filter: the phenotype cohort was
    /// empty, or a variant result type had no INFO filter. Rust decides a filter's validity
    /// from the request alone.
    InvalidGenomicFilterNotEvaluated,
    /// A `/search/values` entry the Rust side refused with 400 and the Java side answered
    /// 500 or 200 with an empty body.
    SearchValuesRefused,
    /// A `/search` entry the Rust side refused with 400 because a categorical INFO column
    /// matching the term is missing on some node, and the Java side answered 200 with an
    /// empty body.
    SearchInfoColumnMissingRefused,
    /// The Rust side refused an excerpt or a variant list for its size, naming
    /// `HPDS_MAX_EXCERPT_BYTES`, and the Java side answered 200 with a body.
    GenomicResultSizeLimit,
    /// Both VARIANT_LIST_FOR_QUERY answers are lists that hold the same specs.
    VariantListSet,
    /// Both excerpt answers are equal once INFO header columns compare as a set, INFO cells
    /// as multisets of their comma-separated values, and patient columns as a multiset of
    /// (header, column).
    ExcerptNormalized,
    /// Both INFO_COLUMN_LISTING answers hold the same entries in any order, `min` and `max`
    /// compared as `f32`.
    InfoListingMultiset,
    /// Body normalization: the `values` of every column under `results.info` of a `/search`
    /// answer are sorted.
    SearchInfoValuesSet,
    /// Two `/search/values` pages that differ only in the order of values sharing one Java
    /// hash bucket.
    SearchValuesBucketSet,
    /// Main consent contract only. A `/query/sync` entry of any result type but
    /// INFO_COLUMN_LISTING whose query carries no authorization filter (`authorizationFilters`
    /// missing, `null` or `[]`, a repeated key keeping its last value), with the Rust side
    /// running with `HPDS_REQUIRE_AUTHORIZATION_FILTER` on; the Rust side refused
    /// with a 400 naming `authorizationFilters` that is not a refusal of the query reader, and the Java side (which must be known)
    /// answered 200 with a body over every patient.
    SyncFlagRefusal,
    /// Always under the harmonization contract. Under the main contract, the entry's raw body
    /// or string query repeats a key other than `authorizationFilters`, whose repetition main
    /// accepts as Jackson does, keeping the last value, and the Rust side (the Java side must
    /// be known) refused with a 400 holding `duplicate key "<key>"` for one such key.
    RepeatedKeyRefusable,
    /// Main consent contract only. The entry's query holds a field that main's `Query`,
    /// `AuthorizationFilter`, `PhenotypicFilter`, `PhenotypicSubquery` or `GenomicFilter` does
    /// not declare, or a clause mixing filter and subquery fields; the Rust side refused with a
    /// 400 holding ``unknown field `<name>` `` for one such field, and the Java side (which
    /// must be known) answered 500, Jackson's failure in `convertIncomingQuery`.
    UnknownFieldRefused,
    /// Main consent contract only. The entry's query holds an authorization filter that is
    /// `null`, or whose `conceptPath` or `values` is `null` or missing; the Rust side refused
    /// with a 400 whose body starts with `an authorization filter`, and the Java side (which
    /// must be known) failed while evaluating: a sync 200 with an empty body or a sync 500, or
    /// an async submit answered 200 or 500 with no status.
    NullAuthorizationFilterMainRefused,
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

/// The consent contract the Rust service serves, its `HPDS_CONSENT_CONTRACT`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum ConsentContract {
    /// The consent-partitioned contract of `feature-harmonization`, the only one served before
    /// the setting existed.
    #[default]
    Harmonization,
    /// The contract of the original HPDS, `main`.
    Main,
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
    /// The consent contract the Rust side serves.
    pub contract: ConsentContract,
    /// Whether the Rust side runs with `HPDS_REQUIRE_AUTHORIZATION_FILTER` on.
    pub require_authorization_filter: bool,
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
    /// The status the Java side must have answered. Applies only when the comparison knows
    /// which side is Java ([`Divergences::for_java`]); without it the rule matches either
    /// orientation of [`Rule::statuses`].
    #[serde(default, rename = "javaStatus")]
    pub java_status: Option<u16>,
    /// Extra condition on the outcomes.
    #[serde(default)]
    pub when: Option<When>,
    /// The Java branch the rule holds for. A rule naming one applies only when the
    /// comparison names that branch ([`Divergences::for_java`]).
    #[serde(default, rename = "javaBranch")]
    pub java_branch: Option<JavaBranch>,
    /// The consent contract the rule holds for. A rule naming one applies only when the
    /// comparison's Rust side serves that contract, which is
    /// [`ConsentContract::Harmonization`] unless [`Divergences::with_contract`] says otherwise.
    #[serde(default, rename = "consentContract")]
    pub consent_contract: Option<ConsentContract>,
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
            | Condition::SearchInfoValuesSet
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
    if conditions.contains(&Condition::SearchInfoValuesSet) {
        genomic::sort_info_values(&mut left);
        genomic::sort_info_values(&mut right);
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
                hpds_format::jackson_string_double(text),
                Err(hpds_format::StringDoubleError::Invalid)
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

fn range_min_async_error(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = java else {
        return false;
    };
    let (java_outcome, rust_outcome) = match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    };
    let (Outcome::Async(java_answer), Outcome::Async(rust_answer)) = (java_outcome, rust_outcome)
    else {
        return false;
    };
    ended_in_error(java_answer)
        && ended_in_success(rust_answer)
        && request_value(entry)
            .and_then(|request| query_object(&request))
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
        && clause.get("min").is_some_and(|min| {
            min.is_number()
                || min.as_str().is_some_and(|text| {
                    matches!(hpds_format::jackson_string_double(text), Ok(Some(_)))
                })
        })
}

/// Whether `clause` holds, at any depth, a `FILTER` with no criterion: `values` absent or null
/// and each of `min` and `max` absent, null, or a string Jackson coerces to null (empty or
/// blank).
fn holds_filter_without_criteria(clause: &Value) -> bool {
    if let Some(children) = clause.get("phenotypicClauses").and_then(Value::as_array) {
        return children.iter().any(holds_filter_without_criteria);
    }
    let no_bound = |key: &str| match clause.get(key) {
        None | Some(Value::Null) => true,
        Some(Value::String(text)) => matches!(hpds_format::jackson_string_double(text), Ok(None)),
        Some(_) => false,
    };
    clause.get("phenotypicFilterType").and_then(Value::as_str) == Some("FILTER")
        && clause.get("values").is_none_or(Value::is_null)
        && no_bound("min")
        && no_bound("max")
}

fn filter_without_criteria_refused(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    request_value(entry)
        .and_then(|request| query_object(&request))
        .and_then(|query| query.get("phenotypicClause").cloned())
        .is_some_and(|clause| holds_filter_without_criteria(&clause))
        && refused_against(
            a,
            b,
            |body| body.starts_with("Either values or one of min/max must be set for a filter on "),
            failed_quietly,
        )
}

fn range_min_with_one_empty_body(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = java else {
        return false;
    };
    let (java_outcome, rust_outcome) = match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    };
    let (Outcome::Sync(java_answer), Outcome::Sync(rust_answer)) = (java_outcome, rust_outcome)
    else {
        return false;
    };
    java_answer.status == 200
        && rust_answer.status == 200
        && java_answer.body.is_empty()
        && !rust_answer.body.is_empty()
        && request_value(entry)
            .and_then(|request| query_object(&request))
            .and_then(|query| query.get("phenotypicClause").cloned())
            .is_some_and(|clause| holds_range_min_filter(&clause))
}

/// The message Rust refuses a `null` select element with, where Java fails on it.
const NULL_SELECT: &str = "select holds null, which Java cannot answer here";

/// The messages Rust refuses a `null` concept path with.
const NULL_CONCEPT_PATHS: [&str; 2] = [
    "a phenotypic filter's conceptPath is null",
    "an authorization filter's conceptPath is null",
];

/// The message Rust refuses a `null` among a phenotypic filter's values with.
const NULL_FILTER_VALUE: &str = "a phenotypic filter's values hold null";

/// The body Rust refuses a missing or `null` result type with: the query reader's refusal,
/// which no route can get past without a result type.
const NULL_RESULT_TYPE_REFUSAL: &str = "the query is not valid: expectedResultType is null";

/// Every phenotypic filter object of `clause`, at any depth; `null` children are skipped.
fn filters<'v>(clause: &'v Value, out: &mut Vec<&'v Value>) {
    match clause.get("phenotypicClauses").and_then(Value::as_array) {
        Some(children) => children.iter().for_each(|child| filters(child, out)),
        None if clause.is_object() && clause.get("operator").is_none() => out.push(clause),
        None => {}
    }
}

/// Whether `clause` holds, at any depth, a subquery whose `phenotypicClauses` holds `null`.
fn holds_null_clause(clause: &Value) -> bool {
    clause
        .get("phenotypicClauses")
        .and_then(Value::as_array)
        .is_some_and(|children| {
            children
                .iter()
                .any(|child| child.is_null() || holds_null_clause(child))
        })
}

fn holds_empty_subquery(clause: &Value) -> bool {
    clause
        .get("phenotypicClauses")
        .and_then(Value::as_array)
        .is_some_and(|children| children.is_empty() || children.iter().any(holds_empty_subquery))
}

/// The message Rust refuses an empty subquery with.
const EMPTY_SUBQUERY: &str = "a phenotypic subquery must contain at least one clause";

fn empty_subquery_refused(entry: &CorpusEntry, a: &Outcome, b: &Outcome) -> bool {
    entry_query(entry)
        .and_then(|query| query.get("phenotypicClause").cloned())
        .is_some_and(|clause| holds_empty_subquery(&clause))
        && refused_against(a, b, |body| body == EMPTY_SUBQUERY, empty_sync_200)
}

/// The entry's query as JSON, read from its raw body when it has one, a string `query`
/// parsed.
fn entry_query(entry: &CorpusEntry) -> Option<Value> {
    request_value(entry).and_then(|request| query_object(&request))
}

fn holds_null(list: Option<&Value>) -> bool {
    list.and_then(Value::as_array)
        .is_some_and(|items| items.iter().any(Value::is_null))
}

fn phenotypic_filters(query: &Value) -> Vec<&Value> {
    let mut out = Vec::new();
    if let Some(clause) = query.get("phenotypicClause").filter(|c| !c.is_null()) {
        filters(clause, &mut out);
    }
    out
}

fn authorization_filters(query: &Value) -> &[Value] {
    query
        .get("authorizationFilters")
        .and_then(Value::as_array)
        .map_or(&[], Vec::as_slice)
}

/// An async submit Java answered 200 with no query status: the body was empty because an
/// exception escaped the handler.
fn submit_failed(outcome: &Outcome) -> bool {
    matches!(outcome, Outcome::Async(x) if x.submit_status == 200
        && x.status.is_none()
        && x.resource_status.is_none()
        && !x.timed_out)
}

fn empty_sync_200(outcome: &Outcome) -> bool {
    matches!(outcome, Outcome::Sync(s) if s.status == 200 && s.body.is_empty())
}

fn async_error(outcome: &Outcome) -> bool {
    matches!(outcome, Outcome::Async(x) if ended_in_error(x))
}

/// Whether the Rust side refused with a body `refused` accepts and the Java side's outcome
/// satisfies `failed`. With the Java side unknown, either side may be Java. Under the main
/// consent contract Java's failure may also take its audit-logging form, a sync 500 or an
/// async submit answered 500 with no status, wherever `failed` accepts the matching 200 form.
fn rust_refused_java_failed(
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
    refused: impl Fn(&str) -> bool,
    failed: impl Fn(&Outcome) -> bool,
) -> bool {
    match java {
        Some(java) => {
            let (java_outcome, rust_outcome) = match java.side {
                Side::A => (a, b),
                Side::B => (b, a),
            };
            refusal(rust_outcome).is_some_and(&refused)
                && (failed(java_outcome)
                    || (java.contract == ConsentContract::Main
                        && main_contract::failed_with_500(java_outcome, &failed)))
        }
        None => refused_against(a, b, refused, failed),
    }
}

fn null_select_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry).is_some_and(|query| holds_null(query.get("select")))
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == NULL_SELECT,
            |o| empty_sync_200(o) || submit_failed(o),
        )
}

fn null_concept_path_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let main = java.is_some_and(|java| java.contract == ConsentContract::Main);
    entry_query(entry).is_some_and(|query| {
        phenotypic_filters(&query)
            .into_iter()
            .any(|filter| filter.get("conceptPath").is_none_or(Value::is_null))
            || (!main
                && authorization_filters(&query)
                    .iter()
                    .any(|filter| filter.get("conceptPath").is_some_and(Value::is_null)))
    }) && rust_refused_java_failed(
        a,
        b,
        java,
        |body| {
            if main {
                body == NULL_CONCEPT_PATHS[0]
            } else {
                NULL_CONCEPT_PATHS.contains(&body)
            }
        },
        |o| empty_sync_200(o) || submit_failed(o) || async_error(o),
    )
}

fn null_filter_value_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry).is_some_and(|query| {
        phenotypic_filters(&query)
            .iter()
            .any(|filter| holds_null(filter.get("values")))
    }) && rust_refused_java_failed(
        a,
        b,
        java,
        |body| body == NULL_FILTER_VALUE,
        |o| empty_sync_200(o) || async_error(o),
    )
}

fn null_result_type_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry)
        .is_some_and(|query| query.get("expectedResultType").is_none_or(Value::is_null))
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == NULL_RESULT_TYPE_REFUSAL,
            |o| empty_sync_200(o) || submit_failed(o),
        )
}

fn null_authorization_filter_ignored(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = java else {
        return false;
    };
    let (java_outcome, rust_outcome) = match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    };
    let rust_answered = match rust_outcome {
        Outcome::Sync(s) => s.status == 200 && !s.body.is_empty(),
        Outcome::Async(x) => ended_in_success(x),
        Outcome::TimedOut | Outcome::Failed(_) => false,
    };
    entry_query(entry).is_some_and(|query| {
        authorization_filters(&query).iter().any(|filter| {
            filter.is_null()
                || filter.get("conceptPath").is_some_and(Value::is_null)
                || holds_null(filter.get("values"))
        })
    }) && (empty_sync_200(java_outcome) || async_error(java_outcome))
        && rust_answered
}

/// The message Rust refuses a `null` or missing `phenotypicFilterType` with.
const NULL_FILTER_TYPE: &str = "a phenotypic filter's phenotypicFilterType is null";

/// The message Rust refuses a `null` among a subquery's `phenotypicClauses` with.
const NULL_CLAUSE: &str = "a phenotypic subquery's phenotypicClauses hold null";

/// The message Rust refuses a `null` element of `authorizationFilters` with on `POST /query`.
const NULL_AUTHORIZATION_FILTER: &str = "an authorization filter is null";

fn null_filter_type_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry).is_some_and(|query| {
        phenotypic_filters(&query).iter().any(|filter| {
            filter
                .get("phenotypicFilterType")
                .is_none_or(Value::is_null)
        })
    }) && rust_refused_java_failed(
        a,
        b,
        java,
        |body| body == NULL_FILTER_TYPE,
        |o| empty_sync_200(o) || submit_failed(o),
    )
}

fn null_clause_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry)
        .is_some_and(|query| query.get("phenotypicClause").is_some_and(holds_null_clause))
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == NULL_CLAUSE,
            |o| empty_sync_200(o) || async_error(o),
        )
}

fn null_authorization_filter_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry.kind == Kind::Async
        && entry_query(entry)
            .is_some_and(|query| authorization_filters(&query).iter().any(Value::is_null))
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == NULL_AUTHORIZATION_FILTER,
            submit_failed,
        )
}

/// The names of Java's `ResultType`, in declaration order, as Rust's refusal of an unknown
/// one lists them.
const RESULT_TYPE_NAMES: [&str; 14] = [
    "COUNT",
    "DATAFRAME",
    "CROSS_COUNT",
    "CATEGORICAL_CROSS_COUNT",
    "CONTINUOUS_CROSS_COUNT",
    "INFO_COLUMN_LISTING",
    "OBSERVATION_CROSS_COUNT",
    "VARIANT_COUNT_FOR_QUERY",
    "VARIANT_LIST_FOR_QUERY",
    "VCF_EXCERPT",
    "AGGREGATE_VCF_EXCERPT",
    "DATAFRAME_TIMESERIES",
    "DATAFRAME_PFB",
    "PATIENTS",
];

/// Whether the outcome is Java's answer to a query Jackson refused while reading it: a sync
/// or async submit 500 with an empty body.
fn empty_500(outcome: &Outcome) -> bool {
    matches!(outcome, Outcome::Sync(s) if s.status == 500 && s.body.is_empty())
        || matches!(outcome, Outcome::Async(x) if x.submit_status == 500
            && x.submit_body.as_deref().is_none_or(str::is_empty))
}

/// Whether the outcome answered: a sync 200 with a body, or an async submit accepted with 200.
fn answered_with_body(outcome: &Outcome) -> bool {
    match outcome {
        Outcome::Sync(s) => s.status == 200 && !s.body.is_empty(),
        Outcome::Async(x) => x.submit_status == 200,
        Outcome::TimedOut | Outcome::Failed(_) => false,
    }
}

/// Every key that appears twice in one object of the JSON `text`, at any depth, in the order
/// the second copies appear. Empty when `text` is not JSON.
fn repeated_keys(text: &str) -> Vec<String> {
    struct Keys<'a>(&'a mut Vec<String>);

    impl<'de> serde::de::DeserializeSeed<'de> for Keys<'_> {
        type Value = ();

        fn deserialize<D: serde::Deserializer<'de>>(
            self,
            d: D,
        ) -> std::result::Result<(), D::Error> {
            d.deserialize_any(self)
        }
    }

    impl<'de> serde::de::Visitor<'de> for Keys<'_> {
        type Value = ();

        fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            f.write_str("JSON")
        }

        fn visit_bool<E>(self, _: bool) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_i64<E>(self, _: i64) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_u64<E>(self, _: u64) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_f64<E>(self, _: f64) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_str<E>(self, _: &str) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_unit<E>(self) -> std::result::Result<(), E> {
            Ok(())
        }

        fn visit_seq<A: serde::de::SeqAccess<'de>>(
            self,
            mut seq: A,
        ) -> std::result::Result<(), A::Error> {
            while seq.next_element_seed(Keys(&mut *self.0))?.is_some() {}
            Ok(())
        }

        fn visit_map<A: serde::de::MapAccess<'de>>(
            self,
            mut map: A,
        ) -> std::result::Result<(), A::Error> {
            let mut seen = std::collections::HashSet::new();
            while let Some(key) = map.next_key::<String>()? {
                if !seen.insert(key.clone()) {
                    self.0.push(key);
                }
                map.next_value_seed(Keys(&mut *self.0))?;
            }
            Ok(())
        }
    }

    let mut keys = Vec::new();
    let mut deserializer = serde_json::Deserializer::from_str(text);
    if serde::de::DeserializeSeed::deserialize(Keys(&mut keys), &mut deserializer).is_err() {
        return Vec::new();
    }
    keys
}

fn duplicate_key_in_string_query(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(Value::String(text)) = request_value(entry).and_then(|r| r.get("query").cloned())
    else {
        return false;
    };
    let keys = refusable_repeated_keys(&text, java);
    let names_a_repeated_key = |body: &str| {
        keys.iter().any(|key| {
            serde_json::to_string(key).is_ok_and(|quoted| {
                body.strip_prefix(&format!(
                    "the query is not valid: duplicate key {quoted} at line "
                ))
                .and_then(|rest| rest.split_once(" column "))
                .is_some_and(|(line, column)| {
                    !line.is_empty()
                        && !column.is_empty()
                        && line.bytes().all(|b| b.is_ascii_digit())
                        && column.bytes().all(|b| b.is_ascii_digit())
                })
            })
        })
    };
    !keys.is_empty()
        && rust_refused_java_failed(a, b, java, names_a_repeated_key, answered_with_body)
}

/// The keys repeated in the JSON `text` that the Rust side may refuse: every one under the
/// harmonization contract, every one but `authorizationFilters` under the main contract, which
/// keeps its last value as Jackson does.
fn refusable_repeated_keys(text: &str, java: Option<JavaService>) -> Vec<String> {
    let main = java.is_some_and(|java| java.contract == ConsentContract::Main);
    repeated_keys(text)
        .into_iter()
        .filter(|key| !main || key != "authorizationFilters")
        .collect()
}

/// See [`Condition::RepeatedKeyRefusable`].
fn repeated_key_refusable(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = java.filter(|java| java.contract == ConsentContract::Main) else {
        return true;
    };
    let rust_outcome = match java.side {
        Side::A => b,
        Side::B => a,
    };
    let text = match &entry.raw_body {
        Some(text) => Some(text.clone()),
        None => match entry.request.get("query") {
            Some(Value::String(text)) => Some(text.clone()),
            _ => None,
        },
    };
    let keys = text.map_or_else(Vec::new, |text| refusable_repeated_keys(&text, Some(java)));
    refusal(rust_outcome).is_some_and(|body| {
        keys.iter().any(|key| {
            serde_json::to_string(key)
                .is_ok_and(|quoted| body.contains(&format!("duplicate key {quoted}")))
        })
    })
}

/// Whether Jackson's enum coercion reads `text` as one of Java's `ResultType` constants: a
/// name, trimmed as `String.trim` does, or a trimmed decimal ordinal without sign or leading
/// zero.
fn jackson_reads_result_type(text: &str) -> bool {
    let trimmed = hpds_format::java_trim(text);
    RESULT_TYPE_NAMES.contains(&trimmed)
        || (!trimmed.is_empty()
            && trimmed.bytes().all(|b| b.is_ascii_digit())
            && (trimmed == "0" || !trimmed.starts_with('0'))
            && trimmed
                .parse::<usize>()
                .is_ok_and(|i| i < RESULT_TYPE_NAMES.len()))
}

fn unknown_result_type_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(Value::String(text)) =
        entry_query(entry).and_then(|q| q.get("expectedResultType").cloned())
    else {
        return false;
    };
    if jackson_reads_result_type(&text) {
        return false;
    }
    let names: Vec<String> = RESULT_TYPE_NAMES.iter().map(|n| format!("{n:?}")).collect();
    let Ok(quoted) = serde_json::to_string(&text) else {
        return false;
    };
    let expected = format!(
        "the query is not valid: invalid value: string {quoted}, expected one of [{}]",
        names.join(", ")
    );
    rust_refused_java_failed(a, b, java, |body| body == expected, empty_500)
}

fn invalid_uuid_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(query) = entry_query(entry) else {
        return false;
    };
    let refused: Vec<String> = ["picsureId", "id"]
        .iter()
        .filter_map(|key| query.get(*key).and_then(Value::as_str))
        .filter(|text| {
            let trimmed = hpds_format::java_trim(text);
            !trimmed.is_empty() && hpds_format::jackson_uuid(trimmed).is_none()
        })
        .map(|text| {
            format!(
                "the query is not valid: {text:?} is not a UUID in the canonical 8-4-4-4-12 form or 16 bytes of Base64"
            )
        })
        .collect();
    !refused.is_empty()
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| refused.iter().any(|m| m == body),
            empty_500,
        )
}

/// Whether `clause` holds, at any depth, a subquery whose `operator` is missing or `null`.
fn holds_subquery_without_operator(clause: &Value) -> bool {
    clause
        .get("phenotypicClauses")
        .and_then(Value::as_array)
        .is_some_and(|children| {
            clause.get("operator").is_none_or(Value::is_null)
                || children.iter().any(holds_subquery_without_operator)
        })
}

fn missing_operator_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    entry_query(entry)
        .and_then(|q| q.get("phenotypicClause").cloned())
        .is_some_and(|clause| holds_subquery_without_operator(&clause))
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| body == "a phenotypic subquery must name an operator (AND or OR)",
            |o| empty_sync_200(o) || async_error(o),
        )
}

fn consent_extra_field_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let extra: Vec<String> = entry_query(entry)
        .and_then(|q| q.get("userConsents").and_then(Value::as_array).cloned())
        .unwrap_or_default()
        .iter()
        .filter_map(Value::as_object)
        .flat_map(|consent| {
            consent
                .keys()
                .filter(|k| *k != "value")
                .cloned()
                .collect::<Vec<_>>()
        })
        .collect();
    !extra.is_empty()
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| {
                extra.iter().any(|name| {
                    body == format!(
                        "the query is not valid: unknown field `{name}`, expected `value`"
                    )
                })
            },
            empty_500,
        )
}

fn export_type_out_of_scope(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(java) = java else {
        return false;
    };
    let (java_outcome, rust_outcome) = match java.side {
        Side::A => (a, b),
        Side::B => (b, a),
    };
    entry.kind == Kind::Async
        && matches!(
            entry.result_type.as_str(),
            "DATAFRAME_TIMESERIES" | "DATAFRAME_PFB"
        )
        && matches!(java_outcome, Outcome::Async(x) if ended_in_success(x))
        && matches!(rust_outcome, Outcome::Async(x) if ended_in_error(x)
            && x.error.as_deref() == Some(format!(
                "{} exports are not supported by this phenotype-only HPDS",
                entry.result_type
            ).as_str()))
}

/// The refusal Java's `PhenotypicFilterValidator` gives `filter`, if its shape is refused.
fn filter_shape_refusal(filter: &Value) -> Option<String> {
    let path = filter.get("conceptPath").and_then(Value::as_str)?;
    let set = |key: &str| filter.get(key).is_some_and(|v| !v.is_null());
    let has_bound = set("min") || set("max");
    match filter.get("phenotypicFilterType").and_then(Value::as_str)? {
        "REQUIRED" | "ANY_RECORD_OF" if has_bound || set("values") => Some(format!(
            "Required filter with concept path {path} cannot have categorical values or min/max set"
        )),
        "FILTER"
            if has_bound
                && filter
                    .get("values")
                    .and_then(Value::as_array)
                    .is_some_and(|v| !v.is_empty()) =>
        {
            Some(format!(
                "Filter with concept path {path} cannot have both categorical values and min/max set"
            ))
        }
        _ => None,
    }
}

fn filter_shape_refused(
    entry: &CorpusEntry,
    a: &Outcome,
    b: &Outcome,
    java: Option<JavaService>,
) -> bool {
    let Some(query) = entry_query(entry) else {
        return false;
    };
    let refusals: Vec<String> = phenotypic_filters(&query)
        .into_iter()
        .filter_map(filter_shape_refusal)
        .collect();
    !refusals.is_empty()
        && rust_refused_java_failed(
            a,
            b,
            java,
            |body| refusals.iter().any(|m| m == body),
            failed_quietly,
        )
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
            Condition::RangeMinSuccessBodyEmpty => range_min_with_one_empty_body(entry, a, b, java),
            Condition::MissingConsentsEmpty200 => missing_consents_empty_200(entry, a, b),
            Condition::MissingConsentsAsyncError => missing_consents_async_error(entry, a, b),
            Condition::DataframeCellOrder => dataframe_cell_order(a, b),
            Condition::RangeMinAsyncError => range_min_async_error(entry, a, b, java),
            Condition::ValuesOnNumericRefused => refused_against(
                a,
                b,
                |body| body.ends_with(" is not a categorical variable"),
                failed_quietly,
            ),
            Condition::FilterShapeRefused => filter_shape_refused(entry, a, b, java),
            Condition::RangeOnCategoricalRefused => refused_against(
                a,
                b,
                |body| body.ends_with(" is not a numeric variable"),
                failed_quietly,
            ),
            Condition::SyncTypeOnAsyncRoute => sync_type_on_async_route(entry, a, b),
            Condition::GenomicTypeOnAsyncRoute => {
                genomic::genomic_type_on_async_route(entry, a, b, java)
            }
            Condition::RequestLimitRefused => request_limit_refused(a, b),
            Condition::NumberTooLongRefused => number_too_long_refused(a, b),
            Condition::StringBoundRefused => string_bound_refused(entry, a, b),
            Condition::FilterWithoutCriteriaRefused => filter_without_criteria_refused(entry, a, b),
            Condition::AuthorizationFiltersNarrow => {
                authorization_filters_narrow(entry, a, b, java)
            }
            Condition::NullSelectRefused => null_select_refused(entry, a, b, java),
            Condition::NullConceptPathRefused => null_concept_path_refused(entry, a, b, java),
            Condition::NullFilterValueRefused => null_filter_value_refused(entry, a, b, java),
            Condition::NullResultTypeRefused => null_result_type_refused(entry, a, b, java),
            Condition::NullAuthorizationFilterIgnored => {
                null_authorization_filter_ignored(entry, a, b, java)
            }
            Condition::NullFilterTypeRefused => null_filter_type_refused(entry, a, b, java),
            Condition::NullClauseRefused => null_clause_refused(entry, a, b, java),
            Condition::NullAuthorizationFilterRefused => {
                null_authorization_filter_refused(entry, a, b, java)
            }
            Condition::DuplicateKeyInStringQuery => {
                duplicate_key_in_string_query(entry, a, b, java)
            }
            Condition::UnknownResultTypeRefused => unknown_result_type_refused(entry, a, b, java),
            Condition::InvalidUuidRefused => invalid_uuid_refused(entry, a, b, java),
            Condition::MissingOperatorRefused => missing_operator_refused(entry, a, b, java),
            Condition::ConsentExtraFieldRefused => consent_extra_field_refused(entry, a, b, java),
            Condition::ExportTypeOutOfScope => export_type_out_of_scope(entry, a, b, java),
            Condition::EmptySubqueryRefused => empty_subquery_refused(entry, a, b),
            Condition::GenomicNullRefused => genomic::null_refused(entry, a, b, java),
            Condition::GenomicRangeRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::Range, a, b, java)
            }
            Condition::GenomicSpecKeyRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::SpecKey, a, b, java)
            }
            Condition::GenomicShortSpecRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::ShortSpec, a, b, java)
            }
            Condition::GenomicDuplicateMetadataRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::DuplicateMetadata, a, b, java)
            }
            Condition::ExcerptMasksMissingRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::MasksMissing, a, b, java)
            }
            Condition::InfoKeyDelimiterRefused => genomic::delimiter_refused(entry, a, b, java),
            Condition::IdCubeNumericRefused => {
                entry.result_type == "VCF_EXCERPT"
                    && genomic::refused_where_java_fails(genomic::Refusal::IdCube, a, b, java)
            }
            Condition::GenomicNoStoreRefused => {
                genomic::refused_where_java_fails(genomic::Refusal::NoStore, a, b, java)
            }
            Condition::InvalidGenomicFilterNotEvaluated => {
                genomic::invalid_filter_not_evaluated(entry, a, b, java)
            }
            Condition::SearchValuesRefused => genomic::search_values_refused(entry, a, b),
            Condition::SearchInfoColumnMissingRefused => {
                genomic::search_info_column_missing_refused(entry, a, b)
            }
            Condition::GenomicResultSizeLimit => genomic::result_size_limit(a, b),
            Condition::VariantListSet => genomic::variant_list_set(a, b),
            Condition::ExcerptNormalized => genomic::excerpt_normalized(a, b),
            Condition::InfoListingMultiset => genomic::info_listing_multiset(a, b),
            Condition::SearchInfoValuesSet => equal_after(a, b, &[self]),
            Condition::SearchValuesBucketSet => genomic::search_values_bucket_set(entry, a, b),
            Condition::SyncFlagRefusal => main_contract::sync_flag_refusal(entry, a, b, java),
            Condition::RepeatedKeyRefusable => repeated_key_refusable(entry, a, b, java),
            Condition::UnknownFieldRefused => {
                main_contract::unknown_field_refused(entry, a, b, java)
            }
            Condition::NullAuthorizationFilterMainRefused => {
                main_contract::null_authorization_filter_refused(entry, a, b, java)
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
        if let Some(contract) = self.consent_contract
            && java.map_or(ConsentContract::default(), |java| java.contract) != contract
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
        if let (Some(code), Some(java)) = (self.java_status, java) {
            let java_outcome = match java.side {
                Side::A => a,
                Side::B => b,
            };
            if java_outcome.status_code() != code {
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
    /// The Rust side serves [`ConsentContract::Harmonization`] until
    /// [`Divergences::with_contract`] says otherwise.
    pub fn for_java(self, side: Side, branch: JavaBranch) -> Self {
        Divergences {
            java: Some(JavaService {
                side,
                branch,
                contract: ConsentContract::default(),
                require_authorization_filter: true,
            }),
            ..self
        }
    }

    /// These rules for a comparison whose Rust side serves `contract`: rules naming another
    /// contract stop applying, and the main contract's conditions can hold. The contract is
    /// part of what the comparison knows about its Java side, so it takes effect only after
    /// [`Divergences::for_java`].
    pub fn with_contract(mut self, contract: ConsentContract) -> Self {
        if let Some(java) = &mut self.java {
            java.contract = contract;
        }
        self
    }

    /// These rules for a comparison whose Rust side runs with
    /// `HPDS_REQUIRE_AUTHORIZATION_FILTER` set to `required` (on unless said otherwise). With
    /// it off, the main contract's refusal of an unscoped sync query is never accepted. Like the
    /// contract, it takes effect only after [`Divergences::for_java`].
    pub fn with_required_authorization_filter(mut self, required: bool) -> Self {
        if let Some(java) = &mut self.java {
            java.require_authorization_filter = required;
        }
        self
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
        let file = rules(json!([
            {"name": "range", "statuses": [200, 200], "when": "range-min-success-body-empty", "reason": "why"}
        ]));
        let set = file.clone().for_java(Side::A, JavaBranch::Consent);
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
        assert!(set.find(&e, &rust, &sync(200, "")).is_none());
        assert!(
            file.clone()
                .for_java(Side::B, JavaBranch::Main)
                .find(&e, &rust, &sync(200, ""))
                .is_some()
        );
        assert!(file.find(&e, &sync(200, ""), &rust).is_none());
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
        let set = set.for_java(Side::A, JavaBranch::Consent);
        let rule = set
            .find(&e, &sync(200, ""), &sync(200, r#"{"\\b\\":{}}"#))
            .unwrap();
        assert_eq!(rule.name, "range-min-above-every-value-empty-200");
        assert!(rule.reason.contains("seekForMinIndex"));
        let count = with_clause(
            "0114-numeric-range-count",
            json!({"conceptPath": "\\b\\", "min": 5.0, "phenotypicFilterType": "FILTER"}),
        );
        let java_count = sync(200, "12");
        let rust_empty = sync(200, "");
        assert!(set.find(&count, &java_count, &rust_empty).is_none());
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
            error: None,
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
        let file = rules(json!([
            {"name": "range", "when": "range-min-async-error", "reason": "why"}
        ]));
        let set = file.clone().for_java(Side::A, JavaBranch::Consent);
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
        assert!(set.find(&with_min, &rust, &java).is_none());
        assert!(
            file.clone()
                .for_java(Side::B, JavaBranch::Main)
                .find(&with_min, &rust, &java)
                .is_some()
        );
        assert!(file.find(&with_min, &java, &rust).is_none());
        let java_success = async_outcome(200, Some("SUCCESS"), Some("patient_id\n7\n"));
        let rust_error = async_outcome(200, Some("ERROR"), None);
        assert!(set.find(&with_min, &java_success, &rust_error).is_none());
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
    fn a_filter_without_criteria_is_accepted_only_against_javas_quiet_failure() {
        let set = rules(json!([
            {"name": "none", "when": "filter-without-criteria-refused", "reason": "why"},
            {"name": "range", "statuses": [200, 200], "when": "range-min-success-body-empty", "reason": "why"}
        ]))
        .for_java(Side::A, JavaBranch::Main);
        let filter = |extra: Value| {
            let mut clause = json!({"phenotypicFilterType": "FILTER", "conceptPath": "\\a\\"});
            for (key, value) in extra.as_object().unwrap() {
                clause[key] = value.clone();
            }
            CorpusEntry {
                request: json!({"query": {"expectedResultType": "COUNT", "phenotypicClause": clause}}),
                ..entry("0001-x-count", "COUNT")
            }
        };
        let refused = sync(
            400,
            "Either values or one of min/max must be set for a filter on \\a\\",
        );
        let name =
            |e: &CorpusEntry, a: &Outcome, b: &Outcome| set.find(e, a, b).map(|r| r.name.clone());
        for extra in [
            json!({}),
            json!({"min": ""}),
            json!({"min": "  ", "max": null}),
            json!({"min": "null"}),
            json!({"max": " null "}),
            json!({"values": null}),
        ] {
            assert_eq!(
                name(&filter(extra.clone()), &sync(200, ""), &refused).as_deref(),
                Some("none"),
                "{extra}"
            );
        }
        assert_eq!(
            name(&filter(json!({"min": 1})), &sync(200, ""), &refused),
            None
        );
        assert_eq!(name(&filter(json!({})), &sync(500, ""), &refused), None);
        assert_eq!(
            name(&filter(json!({})), &sync(200, ""), &sync(400, "other")),
            None
        );

        let nan = filter(json!({"min": "NaN"}));
        assert_eq!(
            name(&nan, &sync(200, ""), &sync(200, "0")).as_deref(),
            Some("range")
        );
        let refused_string = filter(json!({"min": "1d"}));
        assert_eq!(name(&refused_string, &sync(200, ""), &sync(200, "0")), None);
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
            "genomic-type-on-async-route",
            "genomic-no-store-refused",
            "invalid-genomic-filter-not-evaluated",
            "request-limit-status",
            "search-shape-main",
            "authorization-filters-main",
            "number-too-long-status",
            "string-bound-refused-status",
            "filter-without-criteria-status",
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

    fn query_entry(name: &str, kind: Kind, query: Value) -> CorpusEntry {
        CorpusEntry {
            name: name.into(),
            kind,
            result_type: query["expectedResultType"]
                .as_str()
                .unwrap_or("COUNT")
                .into(),
            request: json!({"query": query, "resourceUUID": null}),
            raw_body: None,
            error_path: false,
        }
    }

    fn checked_in() -> Divergences {
        let file = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../bench/divergences.json");
        Divergences::read(&file).unwrap()
    }

    fn bodiless_submit() -> Outcome {
        async_outcome(200, None, None)
    }

    fn rule_name(set: &Divergences, e: &CorpusEntry, a: &Outcome, b: &Outcome) -> Option<String> {
        set.find(e, a, b).map(|rule| rule.name.clone())
    }

    #[test]
    fn null_select_is_accepted_only_with_the_null_and_java_failing() {
        let set = checked_in();
        let refusal = sync(400, NULL_SELECT);
        let cross = query_entry(
            "select-path-and-null-cross-count",
            Kind::Sync,
            json!({"expectedResultType": "CROSS_COUNT", "select": ["\\a\\", null]}),
        );
        for branch in [JavaBranch::Main, JavaBranch::Consent] {
            let set = set.clone().for_java(Side::A, branch);
            assert_eq!(
                rule_name(&set, &cross, &sync(200, ""), &refusal).as_deref(),
                Some("null-select-status")
            );
            assert_eq!(rule_name(&set, &cross, &refusal, &sync(200, "")), None);
            assert_eq!(rule_name(&set, &cross, &sync(200, "{}"), &refusal), None);
        }
        let set = set.for_java(Side::A, JavaBranch::Main);
        let without_null = query_entry(
            "select-path-cross-count",
            Kind::Sync,
            json!({"expectedResultType": "CROSS_COUNT", "select": ["\\a\\"]}),
        );
        assert_eq!(
            rule_name(&set, &without_null, &sync(200, ""), &refusal),
            None
        );
        let patients = query_entry(
            "select-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "select": [null]}),
        );
        assert_eq!(
            rule_name(
                &set,
                &patients,
                &bodiless_submit(),
                &refused_submit(NULL_SELECT)
            )
            .as_deref(),
            Some("null-select-status")
        );
        assert_eq!(
            rule_name(
                &set,
                &patients,
                &async_outcome(200, Some("ERROR"), None),
                &refused_submit(NULL_SELECT)
            ),
            None
        );
        assert_eq!(
            rule_name(
                &set,
                &patients,
                &bodiless_submit(),
                &refused_submit("other")
            ),
            None
        );
    }

    #[test]
    fn null_concept_paths_values_and_result_types_need_the_null_and_java_failing() {
        let set = checked_in().for_java(Side::B, JavaBranch::Consent);
        let required_null = json!({"phenotypicFilterType": "REQUIRED", "conceptPath": null});
        let nested = query_entry(
            "conceptpath-null-in-subquery",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": "OR", "phenotypicClauses": [required_null]}}),
        );
        let path_refusal = sync(400, "a phenotypic filter's conceptPath is null");
        assert_eq!(
            rule_name(&set, &nested, &path_refusal, &sync(200, "")).as_deref(),
            Some("null-concept-path-status")
        );
        assert_eq!(
            rule_name(&set, &nested, &path_refusal, &sync(200, "3")),
            None
        );
        let authz = query_entry(
            "authz-conceptpath-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [{"conceptPath": null, "values": ["x"]}]}),
        );
        let authz_refusal = refused_submit("an authorization filter's conceptPath is null");
        assert_eq!(
            rule_name(&set, &authz, &authz_refusal, &bodiless_submit()).as_deref(),
            Some("null-concept-path-status")
        );
        let any_record = query_entry(
            "conceptpath-null-any-record-of",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "phenotypicClause": {"phenotypicFilterType": "ANY_RECORD_OF", "conceptPath": null}}),
        );
        assert_eq!(
            rule_name(
                &set,
                &any_record,
                &refused_submit("a phenotypic filter's conceptPath is null"),
                &async_outcome(200, Some("ERROR"), None)
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
        let values = query_entry(
            "values-null",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": "\\s\\", "values": [null, "x"]}}),
        );
        let value_refusal = sync(400, "a phenotypic filter's values hold null");
        assert_eq!(
            rule_name(&set, &values, &value_refusal, &sync(200, "")).as_deref(),
            Some("null-filter-value-status")
        );
        assert_eq!(
            rule_name(&set, &values, &path_refusal, &sync(200, "")),
            None
        );
        let result_type = query_entry(
            "result-type-null",
            Kind::Sync,
            json!({"expectedResultType": null}),
        );
        let type_refusal = sync(400, "the query is not valid: expectedResultType is null");
        assert_eq!(
            rule_name(&set, &result_type, &type_refusal, &sync(200, "")).as_deref(),
            Some("null-result-type-status")
        );
        let typed = query_entry("count", Kind::Sync, json!({"expectedResultType": "COUNT"}));
        assert_eq!(rule_name(&set, &typed, &type_refusal, &sync(200, "")), None);
    }

    #[test]
    fn a_null_in_authorization_filters_is_accepted_only_against_main_failing() {
        let set = checked_in();
        let sync_entry = query_entry(
            "authz-values-null-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": [null]}]}),
        );
        let main = set.clone().for_java(Side::A, JavaBranch::Main);
        assert_eq!(
            rule_name(&main, &sync_entry, &sync(200, ""), &sync(200, "3")).as_deref(),
            Some("null-authorization-filter-main")
        );
        assert_eq!(
            rule_name(&main, &sync_entry, &sync(200, "3"), &sync(200, "")),
            None
        );
        let consent = set.clone().for_java(Side::A, JavaBranch::Consent);
        assert_eq!(
            rule_name(&consent, &sync_entry, &sync(200, ""), &sync(200, "3")),
            None
        );
        let async_entry = query_entry(
            "authz-values-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x", null]}]}),
        );
        assert_eq!(
            rule_name(
                &main,
                &async_entry,
                &async_outcome(200, Some("ERROR"), None),
                &async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n1\n"))
            )
            .as_deref(),
            Some("null-authorization-filter-main")
        );
        let clean = query_entry(
            "authz-control-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x"]}]}),
        );
        assert_eq!(
            rule_name(&main, &clean, &sync(200, ""), &sync(200, "3")),
            None
        );
    }

    #[test]
    fn invalid_request_status_needs_the_500_on_java_when_java_is_known() {
        let set = checked_in();
        let e = entry("error-unknown-field", "COUNT");
        let (java_500, rust_400) = (sync(500, ""), sync(400, "bad"));
        let java_on_a = set.clone().for_java(Side::A, JavaBranch::Main);
        assert_eq!(
            rule_name(&java_on_a, &e, &java_500, &rust_400).as_deref(),
            Some("invalid-request-status")
        );
        let java_on_b = set.clone().for_java(Side::B, JavaBranch::Main);
        assert_eq!(
            rule_name(&java_on_b, &e, &rust_400, &java_500).as_deref(),
            Some("invalid-request-status")
        );
        let known = set.clone().for_java(Side::A, JavaBranch::Main);
        assert_eq!(
            rule_name(&known, &e, &sync(400, "bad"), &sync(500, "")),
            None
        );
        let consent = set.clone().for_java(Side::B, JavaBranch::Consent);
        assert_eq!(
            rule_name(&consent, &e, &sync(500, ""), &sync(400, "bad")),
            None
        );
        assert_eq!(
            rule_name(&set, &e, &sync(400, "bad"), &sync(500, "")).as_deref(),
            Some("invalid-request-status")
        );
    }

    #[test]
    fn deferred_null_shapes_are_accepted_only_with_their_exact_refusal() {
        let set = checked_in().for_java(Side::A, JavaBranch::Consent);
        let count = |clause: Value| {
            query_entry(
                "shape-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT", "phenotypicClause": clause}),
            )
        };
        let patients = |clause: Value| {
            query_entry(
                "shape-patients",
                Kind::Async,
                json!({"expectedResultType": "PATIENTS", "phenotypicClause": clause}),
            )
        };
        let missing_type = json!({"conceptPath": "\\s\\"});
        assert_eq!(
            rule_name(
                &set,
                &count(missing_type.clone()),
                &sync(200, ""),
                &sync(400, NULL_FILTER_TYPE)
            )
            .as_deref(),
            Some("null-filter-type-status")
        );
        assert_eq!(
            rule_name(
                &set,
                &patients(missing_type.clone()),
                &bodiless_submit(),
                &refused_submit(NULL_FILTER_TYPE)
            )
            .as_deref(),
            Some("null-filter-type-status")
        );
        assert_eq!(
            rule_name(
                &set,
                &count(missing_type),
                &sync(200, ""),
                &sync(
                    400,
                    "the query is not valid: a phenotypic filter's phenotypicFilterType is null"
                )
            ),
            None
        );
        let typed = json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "\\s\\"});
        assert_eq!(
            rule_name(
                &set,
                &count(typed.clone()),
                &sync(200, ""),
                &sync(400, NULL_FILTER_TYPE)
            ),
            None
        );
        let null_child = json!({"operator": "OR", "phenotypicClauses": [typed.clone(), null]});
        assert_eq!(
            rule_name(
                &set,
                &count(null_child.clone()),
                &sync(200, ""),
                &sync(400, NULL_CLAUSE)
            )
            .as_deref(),
            Some("null-clause-status")
        );
        assert_eq!(
            rule_name(
                &set,
                &patients(null_child),
                &async_outcome(200, Some("ERROR"), None),
                &refused_submit(NULL_CLAUSE)
            )
            .as_deref(),
            Some("null-clause-status")
        );
        let missing_path =
            json!({"operator": "OR", "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED"}]});
        assert_eq!(
            rule_name(
                &set,
                &count(missing_path),
                &sync(200, ""),
                &sync(400, "a phenotypic filter's conceptPath is null")
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
        let authz = query_entry(
            "authz-null-element-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [null]}),
        );
        assert_eq!(
            rule_name(
                &set,
                &authz,
                &bodiless_submit(),
                &refused_submit(NULL_AUTHORIZATION_FILTER)
            )
            .as_deref(),
            Some("null-authorization-filter-status")
        );
        assert_eq!(
            rule_name(
                &set,
                &authz,
                &async_outcome(200, Some("ERROR"), None),
                &refused_submit(NULL_AUTHORIZATION_FILTER)
            ),
            None
        );
        let main = checked_in().for_java(Side::A, JavaBranch::Main);
        let authz_sync = query_entry(
            "authz-null-element-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [null]}),
        );
        assert_eq!(
            rule_name(&main, &authz_sync, &sync(200, ""), &sync(200, "3")).as_deref(),
            Some("null-authorization-filter-main")
        );
    }

    #[test]
    fn null_refusals_match_only_the_exact_message() {
        let set = checked_in().for_java(Side::A, JavaBranch::Consent);
        let entry = |query: Value| query_entry("exact", Kind::Sync, query);
        let path = entry(
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": null}}),
        );
        let value = entry(
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "FILTER", "conceptPath": "\\s\\", "values": [null]}}),
        );
        let result_type = entry(json!({"expectedResultType": null}));
        for (e, exact) in [
            (&path, "a phenotypic filter's conceptPath is null"),
            (&value, "a phenotypic filter's values hold null"),
            (
                &result_type,
                "the query is not valid: expectedResultType is null",
            ),
        ] {
            assert!(
                set.find(e, &sync(200, ""), &sync(400, exact)).is_some(),
                "{exact}"
            );
            for near in [
                format!("the query is not valid: {exact}"),
                format!("{exact} at line 1 column 9"),
                format!("{exact}."),
            ] {
                assert!(
                    set.find(e, &sync(200, ""), &sync(400, &near)).is_none(),
                    "{near}"
                );
            }
        }
    }

    #[test]
    fn an_unserved_type_on_post_query_is_accepted_only_against_javas_failure() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let entry = query_entry(
            "icl-control-async-object",
            Kind::Async,
            json!({"expectedResultType": "INFO_COLUMN_LISTING"}),
        );
        let refusal = refused_submit(
            "result type INFO_COLUMN_LISTING is not supported by POST /query; send it to POST /query/sync",
        );
        assert_eq!(
            rule_name(&set, &entry, &bodiless_submit(), &refusal).as_deref(),
            Some("genomic-type-on-async-route")
        );
        assert_eq!(rule_name(&set, &entry, &refusal, &bodiless_submit()), None);
        assert_eq!(
            rule_name(
                &set,
                &entry,
                &async_outcome(200, Some("ERROR"), None),
                &refusal
            )
            .as_deref(),
            Some("genomic-type-on-async-route")
        );
        assert_eq!(
            rule_name(
                &set,
                &entry,
                &async_outcome(200, Some("SUCCESS"), Some("[]")),
                &refusal
            ),
            None
        );
        let other_type = refused_submit(
            "result type COUNT is not supported by POST /query; send it to POST /query/sync",
        );
        assert_eq!(
            rule_name(&set, &entry, &bodiless_submit(), &other_type),
            None
        );
    }

    #[test]
    fn filter_shape_refusals_are_accepted_against_javas_error() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let shaped = |clause: Value| {
            query_entry(
                "filter-shape-patients",
                Kind::Async,
                json!({"expectedResultType": "PATIENTS", "phenotypicClause": clause}),
            )
        };
        let error = async_outcome(200, Some("ERROR"), None);
        let required = shaped(
            json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "\\s\\", "values": []}),
        );
        let both = shaped(
            json!({"phenotypicFilterType": "FILTER", "conceptPath": "\\s\\", "values": ["x"], "min": 1}),
        );
        let plain = shaped(json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "\\s\\"}));
        for (e, message) in [
            (
                &required,
                "Required filter with concept path \\s\\ cannot have categorical values or min/max set",
            ),
            (
                &both,
                "Filter with concept path \\s\\ cannot have both categorical values and min/max set",
            ),
        ] {
            assert_eq!(
                rule_name(&set, e, &error, &refused_submit(message)).as_deref(),
                Some("filter-shape-status"),
                "{message}"
            );
            assert_eq!(rule_name(&set, e, &refused_submit(message), &error), None);
            assert_eq!(
                rule_name(
                    &set,
                    e,
                    &async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n")),
                    &refused_submit(message)
                ),
                None
            );
            assert_eq!(
                rule_name(&set, &plain, &error, &refused_submit(message)),
                None
            );
        }
        assert_eq!(
            rule_name(&set, &required, &error, &refused_submit("other")),
            None
        );
    }

    fn raw_entry(name: &str, request: Value) -> CorpusEntry {
        CorpusEntry {
            name: name.into(),
            kind: Kind::Sync,
            result_type: "COUNT".into(),
            request,
            raw_body: None,
            error_path: false,
        }
    }

    #[test]
    fn a_repeated_key_in_a_string_query_is_accepted_only_naming_that_key() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let e = raw_entry(
            "query-as-string-duplicate-key",
            json!({"query": r#"{"expectedResultType":"COUNT","expectedResultType":"COUNT"}"#, "resourceUUID": null}),
        );
        let refusal = sync(
            400,
            r#"the query is not valid: duplicate key "expectedResultType" at line 1 column 50"#,
        );
        assert_eq!(
            rule_name(&set, &e, &sync(200, "4978"), &refusal).as_deref(),
            Some("duplicate-key-in-string-query")
        );
        assert_eq!(rule_name(&set, &e, &refusal, &sync(200, "4978")), None);
        let other_key = sync(
            400,
            r#"the query is not valid: duplicate key "select" at line 1 column 50"#,
        );
        assert_eq!(rule_name(&set, &e, &sync(200, "4978"), &other_key), None);
        let no_repeat = raw_entry(
            "query-as-string",
            json!({"query": r#"{"expectedResultType":"COUNT"}"#, "resourceUUID": null}),
        );
        assert_eq!(
            rule_name(&set, &no_repeat, &sync(200, "4978"), &refusal),
            None
        );
        let as_object = raw_entry(
            "query-as-object",
            json!({"query": {"expectedResultType": "COUNT"}, "resourceUUID": null}),
        );
        assert_eq!(
            rule_name(&set, &as_object, &sync(200, "4978"), &refusal),
            None
        );
    }

    #[test]
    fn an_unknown_result_type_is_accepted_only_with_the_exact_refusal() {
        let set = checked_in().for_java(Side::A, JavaBranch::Consent);
        let names = RESULT_TYPE_NAMES
            .iter()
            .map(|n| format!("{n:?}"))
            .collect::<Vec<_>>()
            .join(", ");
        let e = raw_entry(
            "unknown-result-type",
            json!({"query": {"expectedResultType": "FOO"}, "resourceUUID": null}),
        );
        let exact = sync(
            400,
            &format!(
                r#"the query is not valid: invalid value: string "FOO", expected one of [{names}]"#
            ),
        );
        assert_eq!(
            rule_name(&set, &e, &sync(500, ""), &exact).as_deref(),
            Some("unknown-result-type-status")
        );
        assert_eq!(rule_name(&set, &e, &sync(500, "boom"), &exact), None);
        assert_eq!(rule_name(&set, &e, &exact, &sync(500, "")), None);
        let known = raw_entry(
            "padded-result-type",
            json!({"query": {"expectedResultType": " COUNT "}, "resourceUUID": null}),
        );
        assert_eq!(rule_name(&set, &known, &sync(500, ""), &exact), None);
        assert!(jackson_reads_result_type("13") && !jackson_reads_result_type("14"));
        assert!(!jackson_reads_result_type("01") && jackson_reads_result_type(" 0 "));
    }

    #[test]
    fn an_invalid_uuid_is_accepted_only_with_the_exact_refusal() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let e = raw_entry(
            "picsureid-not-uuid",
            json!({"query": {"expectedResultType": "COUNT", "picsureId": "not-a-uuid"}, "resourceUUID": null}),
        );
        let exact = sync(
            400,
            r#"the query is not valid: "not-a-uuid" is not a UUID in the canonical 8-4-4-4-12 form or 16 bytes of Base64"#,
        );
        assert_eq!(
            rule_name(&set, &e, &sync(500, ""), &exact).as_deref(),
            Some("invalid-uuid-status")
        );
        assert_eq!(rule_name(&set, &e, &sync(200, "3"), &exact), None);
        let base64 = raw_entry(
            "picsureid-base64",
            json!({"query": {"expectedResultType": "COUNT", "picsureId": "hpTj1Fy0QQ+EMZk0ReY99g=="}, "resourceUUID": null}),
        );
        assert_eq!(rule_name(&set, &base64, &sync(500, ""), &exact), None);
    }

    #[test]
    fn a_missing_operator_is_accepted_only_against_javas_failure() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let message = "a phenotypic subquery must name an operator (AND or OR)";
        let e = raw_entry(
            "subquery-without-operator",
            json!({"query": {"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicClauses": [{"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"}]}}, "resourceUUID": null}),
        );
        assert_eq!(
            rule_name(&set, &e, &sync(200, ""), &sync(400, message)).as_deref(),
            Some("missing-operator-status")
        );
        assert_eq!(
            rule_name(&set, &e, &sync(200, "3"), &sync(400, message)),
            None
        );
        let with_operator = raw_entry(
            "subquery-with-operator",
            json!({"query": {"expectedResultType": "COUNT", "phenotypicClause": {"operator": "AND", "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"}]}}, "resourceUUID": null}),
        );
        assert_eq!(
            rule_name(&set, &with_operator, &sync(200, ""), &sync(400, message)),
            None
        );
    }

    #[test]
    fn an_extra_consent_field_is_accepted_only_naming_it() {
        let set = checked_in().for_java(Side::A, JavaBranch::Consent);
        let e = raw_entry(
            "consent-extra-field",
            json!({"query": {"expectedResultType": "COUNT", "userConsents": [{"value": "x", "other": 1}]}, "resourceUUID": null}),
        );
        let exact = sync(
            400,
            "the query is not valid: unknown field `other`, expected `value`",
        );
        assert_eq!(
            rule_name(&set, &e, &sync(500, ""), &exact).as_deref(),
            Some("consent-extra-field-status")
        );
        let wrong = sync(
            400,
            "the query is not valid: unknown field `bogus`, expected `value`",
        );
        assert_eq!(rule_name(&set, &e, &sync(500, ""), &wrong), None);
        let clean = raw_entry(
            "consent",
            json!({"query": {"expectedResultType": "COUNT", "userConsents": [{"value": "x"}]}, "resourceUUID": null}),
        );
        assert_eq!(rule_name(&set, &clean, &sync(500, ""), &exact), None);
    }

    #[test]
    fn an_out_of_scope_export_is_accepted_only_as_javas_success_against_rusts_error() {
        let set = checked_in().for_java(Side::A, JavaBranch::Main);
        let mut e = query_entry(
            "error-l0-dataframe_pfb-async-all",
            Kind::Async,
            json!({"expectedResultType": "DATAFRAME_PFB"}),
        );
        let success = async_outcome(200, Some("SUCCESS"), Some("rows"));
        let with_error = |text: &str| match async_outcome(200, Some("ERROR"), None) {
            Outcome::Async(x) => Outcome::Async(AsyncOutcome {
                error: Some(text.to_owned()),
                ..x
            }),
            other => other,
        };
        let error =
            with_error("DATAFRAME_PFB exports are not supported by this phenotype-only HPDS");
        assert_eq!(
            rule_name(&set, &e, &success, &with_error("something else")),
            None
        );
        assert_eq!(
            rule_name(&set, &e, &success, &async_outcome(200, Some("ERROR"), None)),
            None
        );
        assert_eq!(
            rule_name(&set, &e, &success, &error).as_deref(),
            Some("export-type-out-of-scope")
        );
        assert_eq!(rule_name(&set, &e, &error, &success), None);
        e.result_type = "DATAFRAME".into();
        assert_eq!(rule_name(&set, &e, &success, &error), None);
        e.result_type = "DATAFRAME_TIMESERIES".into();
        assert_eq!(rule_name(&set, &e, &success, &error), None);
        let unknown_side = checked_in();
        e.result_type = "DATAFRAME_TIMESERIES".into();
        assert_eq!(rule_name(&unknown_side, &e, &success, &error), None);
    }

    fn main_contract() -> Divergences {
        checked_in()
            .for_java(Side::A, JavaBranch::Main)
            .with_contract(ConsentContract::Main)
    }

    fn harmonization_contract() -> Divergences {
        checked_in().for_java(Side::A, JavaBranch::Main)
    }

    #[test]
    fn the_harmonization_rules_against_main_stop_applying_under_the_main_contract() {
        let set = checked_in();
        for name in [
            "search-shape-main",
            "authorization-filters-main",
            "null-authorization-filter-main",
        ] {
            let rule = set.rules.iter().find(|r| r.name == name).unwrap();
            assert_eq!(
                rule.consent_contract,
                Some(ConsentContract::Harmonization),
                "{name}"
            );
        }
        for name in [
            "sync-requires-authorization-filter-main",
            "unknown-field-main",
            "null-authorization-filter-main-status",
        ] {
            let rule = set.rules.iter().find(|r| r.name == name).unwrap();
            assert_eq!(rule.consent_contract, Some(ConsentContract::Main), "{name}");
            assert_eq!(rule.java_branch, Some(JavaBranch::Main), "{name}");
        }
        let e = query_entry(
            "authz-values-null-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": [null]}]}),
        );
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &e,
                &sync(200, ""),
                &sync(200, "3")
            )
            .as_deref(),
            Some("null-authorization-filter-main")
        );
        assert_eq!(
            rule_name(&main_contract(), &e, &sync(200, ""), &sync(200, "3")),
            None
        );
        let unknown_java = checked_in().with_contract(ConsentContract::Main);
        assert_eq!(unknown_java.java, None);
    }

    #[test]
    fn an_unscoped_sync_query_is_accepted_only_under_main_with_a_refusal_naming_the_filters() {
        let refusal = sync(400, "authorizationFilters are required for this query");
        let answer = sync(200, "3");
        for query in [
            json!({"expectedResultType": "COUNT"}),
            json!({"expectedResultType": "COUNT", "authorizationFilters": null}),
            json!({"expectedResultType": "COUNT", "authorizationFilters": []}),
        ] {
            let e = query_entry("unscoped-count", Kind::Sync, query);
            assert_eq!(
                rule_name(&main_contract(), &e, &answer, &refusal).as_deref(),
                Some("sync-requires-authorization-filter-main")
            );
            assert_eq!(
                rule_name(&harmonization_contract(), &e, &answer, &refusal),
                None
            );
            assert_eq!(rule_name(&main_contract(), &e, &refusal, &answer), None);
            assert_eq!(
                rule_name(&main_contract(), &e, &sync(200, ""), &refusal),
                None
            );
            assert_eq!(
                rule_name(&main_contract(), &e, &answer, &sync(400, "something else")),
                None
            );
        }
        let repeated = raw_entry(
            "unscoped-repeated-count",
            json!({"query": r#"{"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x"]}], "authorizationFilters": []}"#}),
        );
        assert_eq!(
            rule_name(&main_contract(), &repeated, &answer, &refusal).as_deref(),
            Some("sync-requires-authorization-filter-main")
        );
        let scoped = query_entry(
            "scoped-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x"]}]}),
        );
        assert_eq!(
            rule_name(&main_contract(), &scoped, &answer, &refusal),
            None
        );
        let listing = query_entry(
            "unscoped-listing",
            Kind::Sync,
            json!({"expectedResultType": "INFO_COLUMN_LISTING"}),
        );
        assert_eq!(
            rule_name(&main_contract(), &listing, &answer, &refusal),
            None
        );
        let submit = query_entry(
            "unscoped-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS"}),
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &submit,
                &async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n1\n")),
                &refused_submit("authorizationFilters are required for this query")
            ),
            None
        );
    }

    #[test]
    fn an_unknown_field_is_accepted_only_under_main_naming_it_against_javas_500() {
        let java = sync(500, "");
        let cases = [
            (
                json!({"expectedResultType": "COUNT", "userConsents": {}}),
                "userConsents",
            ),
            (
                json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x"], "extra": 1}]}),
                "extra",
            ),
            (
                json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": "AND", "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\", "size": 1}]}}),
                "size",
            ),
            (
                json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\", "operator": "AND"}}),
                "operator",
            ),
            (
                json!({"expectedResultType": "COUNT", "genomicFilters": [{"key": "Gene", "values": ["A"], "other": true}]}),
                "other",
            ),
        ];
        for (query, field) in cases {
            let e = query_entry("unknown-field-count", Kind::Sync, query);
            let refusal = sync(
                400,
                &format!("the query is not valid: unknown field `{field}`, expected one of `x`"),
            );
            assert_eq!(
                rule_name(&main_contract(), &e, &java, &refusal).as_deref(),
                Some("unknown-field-main"),
                "{field}"
            );
            assert_eq!(
                rule_name(&harmonization_contract(), &e, &java, &refusal),
                None
            );
            assert_eq!(
                rule_name(&main_contract(), &e, &sync(200, ""), &refusal),
                None
            );
            assert_eq!(
                rule_name(
                    &main_contract(),
                    &e,
                    &java,
                    &sync(400, "the query is not valid: unknown field `elsewhere`")
                ),
                None
            );
        }
        let known = query_entry(
            "known-fields-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\", "not": false}}),
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &known,
                &java,
                &sync(400, "the query is not valid: unknown field `not`")
            ),
            None
        );
        let submit = query_entry(
            "unknown-field-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "userConsents": {}}),
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &submit,
                &async_outcome(500, None, None),
                &refused_submit("the query is not valid: unknown field `userConsents`")
            )
            .as_deref(),
            Some("unknown-field-main")
        );
    }

    #[test]
    fn a_null_in_an_authorization_filter_is_refused_under_main_against_either_java_failure() {
        let sync_cases = [
            json!([null]),
            json!([{"values": ["x"]}]),
            json!([{"conceptPath": null, "values": ["x"]}]),
            json!([{"conceptPath": "\\s\\"}]),
            json!([{"conceptPath": "\\s\\", "values": null}]),
        ];
        let refusal = sync(400, "an authorization filter's values are null");
        for filters in sync_cases {
            let e = query_entry(
                "authz-null-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT", "authorizationFilters": filters}),
            );
            for java in [sync(200, ""), sync(500, ""), sync(500, "{\"error\":1}")] {
                assert_eq!(
                    rule_name(&main_contract(), &e, &java, &refusal).as_deref(),
                    Some("null-authorization-filter-main-status"),
                    "{filters}"
                );
            }
            assert_eq!(
                rule_name(&main_contract(), &e, &sync(200, "3"), &refusal),
                None
            );
            assert_eq!(
                rule_name(
                    &main_contract(),
                    &e,
                    &sync(200, ""),
                    &sync(400, "a phenotypic filter's values hold null")
                ),
                None
            );
        }
        let clean = query_entry(
            "authz-clean-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "\\s\\", "values": ["x"]}]}),
        );
        assert_eq!(
            rule_name(&main_contract(), &clean, &sync(200, ""), &refusal),
            None
        );
        let submit = query_entry(
            "authz-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [null]}),
        );
        let refused = refused_submit(NULL_AUTHORIZATION_FILTER);
        for java in [bodiless_submit(), async_outcome(500, None, None)] {
            assert_eq!(
                rule_name(&main_contract(), &submit, &java, &refused).as_deref(),
                Some("null-authorization-filter-main-status")
            );
        }
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &submit,
                &bodiless_submit(),
                &refused
            )
            .as_deref(),
            Some("null-authorization-filter-status")
        );
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &submit,
                &async_outcome(500, None, None),
                &refused
            ),
            None
        );
    }

    #[test]
    fn a_null_concept_path_covers_only_phenotypic_filters_under_main() {
        let authorization = query_entry(
            "authz-path-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [{"conceptPath": null, "values": ["x"]}]}),
        );
        let refused = refused_submit(NULL_CONCEPT_PATHS[1]);
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &authorization,
                &bodiless_submit(),
                &refused
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &authorization,
                &bodiless_submit(),
                &refused
            )
            .as_deref(),
            Some("null-authorization-filter-main-status")
        );
        let mut narrowed = main_contract();
        narrowed
            .rules
            .retain(|rule| rule.name != "null-authorization-filter-main-status");
        assert_eq!(
            rule_name(&narrowed, &authorization, &bodiless_submit(), &refused),
            None
        );
        let phenotypic_message = refused_submit(NULL_CONCEPT_PATHS[0]);
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &authorization,
                &bodiless_submit(),
                &phenotypic_message
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &authorization,
                &bodiless_submit(),
                &phenotypic_message
            ),
            None
        );
        let phenotypic = query_entry(
            "path-null-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": null}}),
        );
        let refusal = sync(400, NULL_CONCEPT_PATHS[0]);
        assert_eq!(
            rule_name(&main_contract(), &phenotypic, &sync(500, ""), &refusal).as_deref(),
            Some("null-concept-path-status")
        );
        let authorization_message = sync(400, NULL_CONCEPT_PATHS[1]);
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &phenotypic,
                &sync(200, ""),
                &authorization_message
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &phenotypic,
                &sync(200, ""),
                &authorization_message
            ),
            None
        );
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &phenotypic,
                &sync(500, ""),
                &refusal
            ),
            None
        );
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &phenotypic,
                &sync(200, ""),
                &refusal
            )
            .as_deref(),
            Some("null-concept-path-status")
        );
    }

    #[test]
    fn a_rust_500_is_never_accepted_under_main() {
        let entries = [
            query_entry(
                "plain-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT"}),
            ),
            query_entry(
                "authz-null-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT", "authorizationFilters": [null]}),
            ),
            query_entry(
                "unknown-field-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT", "userConsents": {}}),
            ),
            query_entry(
                "error-path-null-count",
                Kind::Sync,
                json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": null}}),
            ),
        ];
        for e in &entries {
            for java in [sync(200, ""), sync(200, "3"), sync(400, "refused")] {
                for rust in [sync(500, ""), sync(500, "panic")] {
                    assert_eq!(
                        rule_name(&main_contract(), e, &java, &rust),
                        None,
                        "{}",
                        e.name
                    );
                }
            }
        }
        let submit = query_entry(
            "authz-null-patients",
            Kind::Async,
            json!({"expectedResultType": "PATIENTS", "authorizationFilters": [null]}),
        );
        for java in [
            bodiless_submit(),
            async_outcome(200, Some("ERROR"), None),
            async_outcome(200, Some("SUCCESS"), Some("PATIENT_NUM\n1\n")),
        ] {
            assert_eq!(
                rule_name(
                    &main_contract(),
                    &submit,
                    &java,
                    &async_outcome(500, None, None)
                ),
                None
            );
        }
        assert!(
            !checked_in()
                .rules
                .iter()
                .any(|r| r.name == "java-failure-empty-200-or-500")
        );
    }

    #[test]
    fn an_unscoped_sync_refusal_is_accepted_only_with_the_flag_on() {
        let e = query_entry(
            "unscoped-count",
            Kind::Sync,
            json!({"expectedResultType": "COUNT"}),
        );
        let refusal = sync(400, "authorizationFilters are required for this query");
        let answer = sync(200, "3");
        assert_eq!(
            rule_name(
                &main_contract().with_required_authorization_filter(true),
                &e,
                &answer,
                &refusal
            )
            .as_deref(),
            Some("sync-requires-authorization-filter-main")
        );
        assert_eq!(
            rule_name(
                &main_contract().with_required_authorization_filter(false),
                &e,
                &answer,
                &refusal
            ),
            None
        );
    }

    fn raw_body_entry(name: &str, body: &str) -> CorpusEntry {
        CorpusEntry {
            raw_body: Some(body.into()),
            ..entry(name, "COUNT")
        }
    }

    #[test]
    fn a_refused_repetition_of_authorization_filters_is_reported_under_main() {
        let answer = sync(200, "3");
        let string_query = |key: &str| {
            raw_entry(
                "repeated-string-count",
                json!({"query": format!(r#"{{"expectedResultType": "COUNT", "{key}": [], "{key}": []}}"#)}),
            )
        };
        let refusal = |key: &str| {
            sync(
                400,
                &format!("the query is not valid: duplicate key \"{key}\" at line 1 column 60"),
            )
        };
        let authz = string_query("authorizationFilters");
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &authz,
                &answer,
                &refusal("authorizationFilters")
            )
            .as_deref(),
            Some("duplicate-key-in-string-query")
        );
        assert_eq!(
            rule_name(
                &main_contract(),
                &authz,
                &answer,
                &refusal("authorizationFilters")
            ),
            None
        );
        let select = string_query("select");
        assert_eq!(
            rule_name(&main_contract(), &select, &answer, &refusal("select")).as_deref(),
            Some("duplicate-key-in-string-query")
        );
        let body = |key: &str| {
            format!(r#"{{"query": {{"expectedResultType": "COUNT", "{key}": [], "{key}": []}}}}"#)
        };
        let raw_authz = raw_body_entry("error-duplicate-key-authz", &body("authorizationFilters"));
        let any_refusal = sync(400, "duplicate key");
        let names_select = sync(
            400,
            "the query is not valid: duplicate key \"select\" at line 1 column 40",
        );
        assert_eq!(
            rule_name(&harmonization_contract(), &raw_authz, &answer, &any_refusal).as_deref(),
            Some("duplicate-key-last-wins")
        );
        assert_eq!(
            rule_name(&main_contract(), &raw_authz, &answer, &any_refusal),
            None
        );
        let raw_select = raw_body_entry("error-duplicate-key-select", &body("select"));
        assert_eq!(
            rule_name(&main_contract(), &raw_select, &answer, &names_select).as_deref(),
            Some("duplicate-key-last-wins")
        );
        assert_eq!(
            rule_name(&main_contract(), &raw_select, &answer, &any_refusal),
            None
        );
        assert_eq!(
            rule_name(
                &harmonization_contract(),
                &raw_select,
                &answer,
                &any_refusal
            )
            .as_deref(),
            Some("duplicate-key-last-wins")
        );
        let names_authz = sync(
            400,
            "the query is not valid: duplicate key \"authorizationFilters\" at line 1 column 40",
        );
        assert_eq!(
            rule_name(&main_contract(), &raw_authz, &answer, &names_authz),
            None
        );
    }

    #[test]
    fn the_user_consents_and_harmonization_search_rules_stop_applying_under_main() {
        let set = checked_in();
        for name in [
            "missing-consents-sync-empty-200",
            "missing-consents-async-error",
            "consent-extra-field-status",
            "search-order-and-double-min-value",
            "search-concept-order",
            "search-max-double-min-value",
            "search-concept-and-category-values-order",
        ] {
            let rule = set.rules.iter().find(|r| r.name == name).unwrap();
            assert_eq!(
                rule.consent_contract,
                Some(ConsentContract::Harmonization),
                "{name}"
            );
        }
        let e = consent_entry("0001-x-count-c-none", Kind::Sync, None);
        let required = sync(400, CONSENTS_REQUIRED);
        assert_eq!(
            rule_name(&harmonization_contract(), &e, &sync(200, ""), &required).as_deref(),
            Some("missing-consents-sync-empty-200")
        );
        assert_eq!(
            rule_name(&main_contract(), &e, &sync(200, ""), &required),
            None
        );
    }
}
