//! The v3 query model, deserialized the way the Java service's plain Jackson `ObjectMapper`
//! reads `edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query`.
//!
//! Rules carried over from Jackson:
//!
//! - A JSON object with the same key twice, anywhere in the query, is rejected. Jackson keeps
//!   the last value instead; refusing keeps a consent list from being read differently here
//!   than by the query service that overwrote it. [`parse_strict`] and
//!   [`parse_request_body`] apply the same rule to query text and to a whole request body.
//! - Field names match exactly (`userConsents`, never `UserConsents` or `user_consents`).
//! - Unknown fields anywhere inside the query are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`).
//!   The two `@JsonIgnore` properties of `PhenotypicFilter`, `categoricalFilter` and
//!   `numericFilter`, are accepted and ignored.
//! - A [`PhenotypicClause`] is chosen by deduction from the field names present, as
//!   `@JsonTypeInfo(use = DEDUCTION)` does: `phenotypicClauses` or `operator` make it a
//!   subquery, `phenotypicFilterType`, `conceptPath`, `values`, `min` or `max` make it a
//!   filter. An object with fields of both kinds, or with neither (`{}` or only `not`), is
//!   rejected, as Jackson rejects it either while deducing or while binding.
//! - `null` or missing lists (`select`, `authorizationFilters`, `userConsents`,
//!   `genomicFilters`, `phenotypicClauses`) are empty lists, as the record accessors return
//!   them.
//! - Doubles accept JSON numbers and strings (`"5"`, `" 2.5 "`, `"NaN"`, `"Infinity"`), and an
//!   empty or blank string is `null`, as Jackson's scalar coercion does. Strings follow
//!   Jackson's coercion, not `Double.parseDouble`: `"1d"`, `"0x1p3"` and `"-NaN"` are refused
//!   (see `hpds_format::jackson_string_double`).
//! - Booleans (`not`) accept `true` and `false`, a JSON integer (0 is false, any other is true,
//!   as Jackson's int to Boolean coercion reads it; a float is refused), and a string trimmed as
//!   `String.trim` does: blank or `null` is null, `true`, `TRUE` and `True` are true, `false`,
//!   `FALSE` and `False` are false, and any other text (`tRuE`, `yes`, `1`) is refused.
//! - Numbers are read from their literal text as Jackson 2.21 reads them: a number with more
//!   than 1,000 counted digits is refused (`StreamReadConstraints`; the count differs between
//!   query text and a request body, see `json_number`), a `min` or `max` that
//!   overflows a double is `±Infinity`, and the integer `-0` is zero (a bound of `0.0`, a `not`
//!   of `false`, an enum's first constant). Every
//!   other number is the correctly rounded double, as `serde_json` with `float_roundtrip`
//!   reads it.
//! - A genomic filter's `min` and `max` are Java `Float`s. A string is coerced as for a
//!   `Double` and rounded once to the nearest float. A number is rounded once from the text
//!   Jackson parses it from, which for a decimal in an object query is the double's
//!   `Double.toString` (see `json_number`).
//! - A JSON number, `true` or `false` where the model holds a Java `String` (an element of
//!   `select` or `values`, a `conceptPath`, a genomic filter `key`, a consent `value`) reads as
//!   Jackson coerces a scalar into a `String`. In query text that is the literal text (`1.50`,
//!   `1e3` and `-0` as written). In an object `query` of a request body, which the service
//!   reads untyped and writes back before reading it as a `Query`, it is the written-back text
//!   (`1.5`, `1000.0`, `0`). Objects and arrays there are refused, as Jackson refuses them.
//! - Enums accept their exact Java name, the name with surrounding whitespace, their ordinal as
//!   a JSON integer, or the ordinal written as a string (`"1"`), as Jackson's enum coercion
//!   does; names are case sensitive.
//! - UUIDs must be in the canonical 36-character form or 16 bytes of Base64 (22 characters,
//!   or 24 ending in `==`), after trimming as `String.trim` does, and are kept in lowercase
//!   canonical form; an empty or blank string is null.
//!
//! Where Jackson accepts a value that Java only fails on later, the query is still read, as
//! Java answers INFO_COLUMN_LISTING without evaluating it: a `null` or missing
//! `phenotypicFilterType` or `conceptPath`, a `null` among a subquery's `phenotypicClauses`
//! ([`Query::java_failure`], refused by every computation), a `null` among a filter's
//! `values` (dropped; on a `FILTER` marked by [`PhenotypicFilter::null_values`] and refused
//! only where Java looks it up), a `null` element of `select` ([`Query::select_nulls`]) or of `authorizationFilters`
//! ([`Query::null_authorization_filters`]), and a `null` genomic filter or genomic value
//! (kept, and refused when the engine translates the genomic filters). A missing or `null` `expectedResultType` is refused here
//! ([`NULL_RESULT_TYPE_MESSAGE`]), since no route can answer without it.

use std::fmt;

use hpds_format::{jackson_uuid, java_trim};
use serde::de::{self, Deserializer, Visitor};
use serde::{Deserialize, Serialize, Serializer};
use serde_json::{Map, Value};

use crate::contract::ConsentContract;
use crate::json_number::Source;

macro_rules! java_enum {
    ($(#[$meta:meta])* $name:ident { $($(#[$vmeta:meta])* $variant:ident = $java:literal,)+ }) => {
        $(#[$meta])*
        #[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
        pub enum $name {
            $($(#[$vmeta])* $variant,)+
        }

        impl $name {
            /// Every variant in Java declaration order, so the position is the Java ordinal.
            pub const ALL: &'static [$name] = &[$($name::$variant,)+];

            /// The Java constant name.
            pub fn java_name(self) -> &'static str {
                match self {
                    $($name::$variant => $java,)+
                }
            }

            /// The variant with Java constant name `name`, matched exactly.
            pub fn from_java_name(name: &str) -> Option<Self> {
                match name {
                    $($java => Some($name::$variant),)+
                    _ => None,
                }
            }

            /// The variant a JSON string names, as Jackson 2.21's `EnumDeserializer` reads it
            /// with the default mapper: the exact constant name, else the text trimmed as
            /// `String.trim` does and matched again, else a trimmed decimal ordinal without a
            /// sign or leading zero (`"1"`, `" 13 "`, not `"01"` or `"+1"`). The match is case
            /// sensitive and an empty or blank text is refused.
            pub fn from_java_text(text: &str) -> Option<Self> {
                $name::from_java_name(text).or_else(|| {
                    let trimmed = java_trim(text);
                    $name::from_java_name(trimmed).or_else(|| {
                        let canonical = trimmed == "0"
                            || (trimmed.starts_with(|c: char| ('1'..='9').contains(&c))
                                && trimmed.bytes().all(|b| b.is_ascii_digit()));
                        canonical
                            .then(|| trimmed.parse::<usize>().ok())
                            .flatten()
                            .and_then(|index| $name::ALL.get(index).copied())
                    })
                })
            }
        }

        impl fmt::Display for $name {
            fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                f.write_str(self.java_name())
            }
        }

        impl Serialize for $name {
            fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
                serializer.serialize_str(self.java_name())
            }
        }

        impl<'de> Deserialize<'de> for $name {
            fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
                struct EnumVisitor;
                impl Visitor<'_> for EnumVisitor {
                    type Value = $name;

                    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
                        write!(f, "one of {:?}", $name::ALL.iter().map(|v| v.java_name()).collect::<Vec<_>>())
                    }

                    fn visit_str<E: de::Error>(self, value: &str) -> Result<$name, E> {
                        $name::from_java_text(value)
                            .ok_or_else(|| E::invalid_value(de::Unexpected::Str(value), &self))
                    }

                    fn visit_u64<E: de::Error>(self, value: u64) -> Result<$name, E> {
                        usize::try_from(value)
                            .ok()
                            .and_then(|i| $name::ALL.get(i).copied())
                            .ok_or_else(|| E::invalid_value(de::Unexpected::Unsigned(value), &self))
                    }

                    fn visit_i64<E: de::Error>(self, value: i64) -> Result<$name, E> {
                        u64::try_from(value)
                            .map_err(|_| E::invalid_value(de::Unexpected::Signed(value), &self))
                            .and_then(|v| self.visit_u64(v))
                    }
                }
                deserializer.deserialize_any(EnumVisitor)
            }
        }
    };
}

java_enum! {
    /// `edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType`, every Java constant in
    /// declaration order.
    ResultType {
        /// Number of matching patients.
        Count = "COUNT",
        /// CSV of the selected concepts' values per matching patient.
        Dataframe = "DATAFRAME",
        /// Matching patients with any value, per selected concept.
        CrossCount = "CROSS_COUNT",
        /// Matching patients per category of each categorical concept in the filters.
        CategoricalCrossCount = "CATEGORICAL_CROSS_COUNT",
        /// Matching observations per value of each numeric concept in the filters.
        ContinuousCrossCount = "CONTINUOUS_CROSS_COUNT",
        /// Variant info column metadata. Genomic.
        InfoColumnListing = "INFO_COLUMN_LISTING",
        /// Matching observations per selected concept.
        ObservationCrossCount = "OBSERVATION_CROSS_COUNT",
        /// Number of variants the query selects. Genomic.
        VariantCountForQuery = "VARIANT_COUNT_FOR_QUERY",
        /// Variants the query selects. Genomic.
        VariantListForQuery = "VARIANT_LIST_FOR_QUERY",
        /// Quasi-VCF lines for the query's variants. Genomic.
        VcfExcerpt = "VCF_EXCERPT",
        /// Quasi-VCF lines without patient data. Genomic.
        AggregateVcfExcerpt = "AGGREGATE_VCF_EXCERPT",
        /// Export in the CSV loader's format.
        DataframeTimeseries = "DATAFRAME_TIMESERIES",
        /// Export as PFB.
        DataframePfb = "DATAFRAME_PFB",
        /// The matching patient ids.
        Patients = "PATIENTS",
    }
}

impl ResultType {
    /// True for the result types that need genomic data: `INFO_COLUMN_LISTING`,
    /// `VARIANT_COUNT_FOR_QUERY`, `VARIANT_LIST_FOR_QUERY`, `VCF_EXCERPT` and
    /// `AGGREGATE_VCF_EXCERPT`.
    pub fn is_genomic(self) -> bool {
        matches!(
            self,
            ResultType::InfoColumnListing
                | ResultType::VariantCountForQuery
                | ResultType::VariantListForQuery
                | ResultType::VcfExcerpt
                | ResultType::AggregateVcfExcerpt
        )
    }

    /// True for the result types Java's `/query/sync` computes on the request thread:
    /// `COUNT` and the four cross counts, plus the genomic sync types.
    pub fn is_sync(self) -> bool {
        matches!(
            self,
            ResultType::Count
                | ResultType::CrossCount
                | ResultType::CategoricalCrossCount
                | ResultType::ContinuousCrossCount
                | ResultType::ObservationCrossCount
        ) || self.is_genomic()
    }
}

java_enum! {
    /// How a [`PhenotypicSubquery`] combines its clauses.
    Operator {
        /// Intersection.
        And = "AND",
        /// Union.
        Or = "OR",
    }
}

java_enum! {
    /// How a [`PhenotypicFilter`] matches its concept path, in Java declaration order.
    PhenotypicFilterType {
        /// Patients with any value for the concept.
        Required = "REQUIRED",
        /// Patients with one of `values`, or a value within `min`..=`max`.
        Filter = "FILTER",
        /// Patients with any value for the concept or any concept whose path starts with it.
        AnyRecordOf = "ANY_RECORD_OF",
    }
}

/// `v3.Query`: which patients to match and what to return about them.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(try_from = "QueryFields")]
pub struct Query {
    /// Concept paths to return, for the result types that return fields. A `null` element of
    /// the request's `select` is not kept here; [`Query::select_nulls`] counts them.
    pub select: Vec<String>,
    /// How many `null` elements the request's `select` held. Java keeps them in the list:
    /// COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and INFO_COLUMN_LISTING never
    /// read `select` and answer as without them, while CROSS_COUNT, OBSERVATION_CROSS_COUNT
    /// and every query submitted to `POST /query` (whose `QueryValidator` looks each path up)
    /// fail on them. The service refuses those.
    pub select_nulls: usize,
    /// Deprecated consent filters. Parsed and checked by [`crate::QueryValidator`], but never
    /// applied to a cohort: consents select partitions through `user_consents` instead.
    pub authorization_filters: Vec<AuthorizationFilter>,
    /// The caller's consents, in request order. Each names a partition to read; see
    /// [`Query::consent_values`].
    pub user_consents: Vec<UserConsent>,
    /// The filter tree, or `None` for no filter.
    pub phenotypic_clause: Option<PhenotypicClause>,
    /// Variant filters, which the engine evaluates over its genomic store.
    pub genomic_filters: Vec<GenomicFilter>,
    /// What the query returns.
    pub expected_result_type: ResultType,
    /// An externally assigned UUID, lowercase.
    pub picsure_id: Option<String>,
    /// An internally assigned UUID, lowercase.
    pub id: Option<String>,
    /// The refusal for a construct of the phenotypic clause that Jackson reads but Java fails
    /// on as soon as it evaluates the clause: a filter whose `conceptPath` or
    /// `phenotypicFilterType` is `null` or missing ([`NULL_CONCEPT_PATH_MESSAGE`],
    /// [`NULL_FILTER_TYPE_MESSAGE`]), or a `null` among a subquery's `phenotypicClauses`
    /// ([`NULL_CLAUSE_MESSAGE`]). Java answers INFO_COLUMN_LISTING without evaluating the
    /// clause, so the query is read, with the construct replaced (a placeholder path or type,
    /// the `null` element dropped), and every computation refuses it with this message
    /// ([`crate::QueryLimits::check_shape`]). `None` when the clause holds none.
    pub java_failure: Option<String>,
    /// How many `null` elements `authorizationFilters` held; they are not in
    /// `authorization_filters`. The sync route never reads authorization filters, as on the
    /// consent branch; `POST /query`'s validator refuses them
    /// ([`crate::NULL_AUTHORIZATION_FILTER_MESSAGE`]), where Java's throws.
    pub null_authorization_filters: usize,
    /// The position in the request's `authorizationFilters` of its first `null` element, or
    /// `None` when it held none. Java's validator walks the list in order and fails on the
    /// first `null` it reaches, after any filter before it.
    pub first_null_authorization_filter: Option<usize>,
}

/// The JSON fields of a [`Query`] as read, before the `null` elements of `select` are
/// counted out and a missing or `null` `expectedResultType` is refused.
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct QueryFields {
    #[serde(default, deserialize_with = "null_as_default")]
    select: Vec<Option<String>>,
    #[serde(default, deserialize_with = "null_as_default")]
    authorization_filters: Vec<Option<AuthorizationFilter>>,
    #[serde(default, deserialize_with = "null_as_default")]
    user_consents: Vec<UserConsent>,
    #[serde(default)]
    phenotypic_clause: Option<StrictValue>,
    #[serde(default, deserialize_with = "null_as_default")]
    genomic_filters: Vec<Option<GenomicFilter>>,
    #[serde(default)]
    expected_result_type: Option<ResultType>,
    #[serde(default, deserialize_with = "java_uuid")]
    picsure_id: Option<String>,
    #[serde(default, deserialize_with = "java_uuid")]
    id: Option<String>,
}

/// The JSON fields of main's `Query`, the seven Java main declares: [`QueryFields`] without
/// `userConsents`, which is unknown here as it is to Java main's mapper.
#[derive(Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct MainQueryFields {
    #[serde(default, deserialize_with = "null_as_default")]
    select: Vec<Option<String>>,
    #[serde(default, deserialize_with = "null_as_default")]
    authorization_filters: Vec<Option<AuthorizationFilter>>,
    #[serde(default)]
    phenotypic_clause: Option<StrictValue>,
    #[serde(default, deserialize_with = "null_as_default")]
    genomic_filters: Vec<Option<GenomicFilter>>,
    #[serde(default)]
    expected_result_type: Option<ResultType>,
    #[serde(default, deserialize_with = "java_uuid")]
    picsure_id: Option<String>,
    #[serde(default, deserialize_with = "java_uuid")]
    id: Option<String>,
}

impl From<MainQueryFields> for QueryFields {
    fn from(fields: MainQueryFields) -> Self {
        QueryFields {
            select: fields.select,
            authorization_filters: fields.authorization_filters,
            user_consents: Vec::new(),
            phenotypic_clause: fields.phenotypic_clause,
            genomic_filters: fields.genomic_filters,
            expected_result_type: fields.expected_result_type,
            picsure_id: fields.picsure_id,
            id: fields.id,
        }
    }
}

/// Refusal of a query whose `select` holds `null` ([`Query::select_nulls`]) where Java fails
/// on it: a CROSS_COUNT or OBSERVATION_CROSS_COUNT on the sync route, and any query on
/// `POST /query`.
pub const NULL_SELECT_MESSAGE: &str = "select holds null, which Java cannot answer here";

/// Refusal of a query whose `expectedResultType` is missing or `null`. Java reads it as
/// `null` and fails on it whatever the route: the sync route switches on it, and
/// `POST /query` picks a processor by it.
pub const NULL_RESULT_TYPE_MESSAGE: &str = "expectedResultType is null";

/// Refusal of a phenotypic filter whose `phenotypicFilterType` is `null` or missing. Java
/// switches on it when it validates or evaluates the filter.
pub const NULL_FILTER_TYPE_MESSAGE: &str = "a phenotypic filter's phenotypicFilterType is null";

/// Refusal of a subquery whose `phenotypicClauses` holds a `null`. Java fails on it when it
/// evaluates the subquery.
pub const NULL_CLAUSE_MESSAGE: &str = "a phenotypic subquery's phenotypicClauses hold null";

/// What [`defer_java_failures`] found in a phenotypic clause.
#[derive(Default)]
struct Deferred {
    /// The refusal for the first construct Java fails on whenever it evaluates the clause.
    failure: Option<&'static str>,
    /// Filters seen so far, in depth-first order.
    filters: usize,
    /// The depth-first indices of the `FILTER`s whose `values` held `null`.
    null_value_filters: Vec<usize>,
}

impl Deferred {
    fn note(&mut self, message: &'static str) {
        self.failure.get_or_insert(message);
    }
}

/// Replaces, in the phenotypic clause `clause`, each construct Jackson reads but Java fails on
/// when it evaluates the clause, and records it in `deferred` (see [`Query::java_failure`]).
/// A clause is deduced as in [`PhenotypicClause`]'s reader; one that is neither a filter nor a
/// subquery, or both, is left for that reader to refuse, as Jackson refuses it.
///
/// A `null` among a filter's `values` is dropped. Java fails on it only where it looks the
/// value up: a `FILTER` whose concept is categorical in a selected partition (a `TreeMap` key
/// lookup). It ignores `values` on `REQUIRED` and `ANY_RECORD_OF`, finds nothing for an unknown
/// concept, and answers nothing for `null` on a numeric one. So a `FILTER` that held one is
/// marked ([`PhenotypicFilter::null_values`]) for the evaluator to decide, and the other types
/// drop it silently.
fn defer_java_failures(clause: &mut Value, deferred: &mut Deferred) {
    let Value::Object(fields) = clause else {
        return;
    };
    let subquery = fields
        .keys()
        .any(|k| SUBQUERY_ONLY_FIELDS.contains(&k.as_str()));
    let filter = fields
        .keys()
        .any(|k| FILTER_ONLY_FIELDS.contains(&k.as_str()));
    match (subquery, filter) {
        (true, false) => {
            if let Some(Value::Array(children)) = fields.get_mut("phenotypicClauses") {
                let before = children.len();
                children.retain(|child| !child.is_null());
                if children.len() < before {
                    deferred.note(NULL_CLAUSE_MESSAGE);
                }
                for child in children.iter_mut() {
                    defer_java_failures(child, deferred);
                }
            }
        }
        (false, true) => {
            let index = deferred.filters;
            deferred.filters += 1;
            if fields
                .get("phenotypicFilterType")
                .is_none_or(Value::is_null)
            {
                fields.insert("phenotypicFilterType".into(), Value::from("REQUIRED"));
                deferred.note(NULL_FILTER_TYPE_MESSAGE);
            }
            if fields.get("conceptPath").is_none_or(Value::is_null) {
                fields.insert("conceptPath".into(), Value::from(""));
                deferred.note(NULL_CONCEPT_PATH_MESSAGE);
            }
            let is_filter = fields.get("phenotypicFilterType") == Some(&Value::from("FILTER"));
            if let Some(Value::Array(values)) = fields.get_mut("values") {
                let before = values.len();
                values.retain(|value| !value.is_null());
                if values.len() < before && is_filter {
                    deferred.null_value_filters.push(index);
                }
            }
        }
        _ => {}
    }
}

/// Sets [`PhenotypicFilter::null_values`] on the filters of `clause` whose depth-first index
/// is in `marked`, counting filters in `seen` in the order [`defer_java_failures`] did.
fn mark_null_values(clause: &mut PhenotypicClause, seen: &mut usize, marked: &[usize]) {
    match clause {
        PhenotypicClause::Filter(filter) => {
            filter.null_values = marked.contains(seen);
            *seen += 1;
        }
        PhenotypicClause::Subquery(subquery) => {
            for child in &mut subquery.phenotypic_clauses {
                mark_null_values(child, seen, marked);
            }
        }
    }
}

impl TryFrom<QueryFields> for Query {
    type Error = String;

    fn try_from(fields: QueryFields) -> std::result::Result<Self, Self::Error> {
        let expected_result_type = fields
            .expected_result_type
            .ok_or_else(|| NULL_RESULT_TYPE_MESSAGE.to_owned())?;
        let select_nulls = fields.select.iter().filter(|path| path.is_none()).count();
        let null_authorization_filters = fields
            .authorization_filters
            .iter()
            .filter(|filter| filter.is_none())
            .count();
        let first_null_authorization_filter = fields
            .authorization_filters
            .iter()
            .position(Option::is_none);
        let (phenotypic_clause, java_failure) = match fields.phenotypic_clause {
            None | Some(StrictValue(Value::Null)) => (None, None),
            Some(StrictValue(mut clause)) => {
                let mut deferred = Deferred::default();
                defer_java_failures(&mut clause, &mut deferred);
                let mut clause = serde_json::from_value::<PhenotypicClause>(clause)
                    .map_err(|e| e.to_string())?;
                mark_null_values(&mut clause, &mut 0, &deferred.null_value_filters);
                (Some(clause), deferred.failure)
            }
        };
        Ok(Query {
            select: fields.select.into_iter().flatten().collect(),
            select_nulls,
            authorization_filters: fields.authorization_filters.into_iter().flatten().collect(),
            user_consents: fields.user_consents,
            phenotypic_clause,
            genomic_filters: fields
                .genomic_filters
                .into_iter()
                .map(Option::unwrap_or_default)
                .collect(),
            expected_result_type,
            picsure_id: fields.picsure_id,
            id: fields.id,
            java_failure: java_failure.map(str::to_owned),
            null_authorization_filters,
            first_null_authorization_filter,
        })
    }
}

impl Query {
    /// Whether Java fails on the `null` elements of this query's `select` when it is sent to
    /// the sync route (`asynchronous` false) or to `POST /query` (true): always on
    /// `POST /query`, and on the sync route for the two result types that read `select`,
    /// CROSS_COUNT and OBSERVATION_CROSS_COUNT. False when `select` held no `null`.
    pub fn select_nulls_fail(&self, asynchronous: bool) -> bool {
        self.select_nulls > 0
            && (asynchronous
                || matches!(
                    self.expected_result_type,
                    ResultType::CrossCount | ResultType::ObservationCrossCount
                ))
    }

    /// A query of `result_type` with no select, no filters and no ids.
    pub fn new(result_type: ResultType) -> Self {
        Query {
            select: Vec::new(),
            select_nulls: 0,
            java_failure: None,
            null_authorization_filters: 0,
            first_null_authorization_filter: None,
            authorization_filters: Vec::new(),
            user_consents: Vec::new(),
            phenotypic_clause: None,
            genomic_filters: Vec::new(),
            expected_result_type: result_type,
            picsure_id: None,
            id: None,
        }
    }

    /// A query of `result_type` with no select and no filters, carrying `consents` as its
    /// user consents.
    pub fn with_consents<S: Into<String>>(
        result_type: ResultType,
        consents: impl IntoIterator<Item = S>,
    ) -> Self {
        Query {
            user_consents: consents
                .into_iter()
                .map(|value| UserConsent {
                    value: Some(value.into()),
                })
                .collect(),
            ..Query::new(result_type)
        }
    }

    /// `Query.consentValues()`: the distinct non-null consent values, sorted. A `null`
    /// element of `userConsents` and a `null` value are both dropped.
    pub fn consent_values(&self) -> Vec<&str> {
        let mut values: Vec<&str> = self
            .user_consents
            .iter()
            .filter_map(|consent| consent.value.as_deref())
            .collect();
        values.sort_unstable();
        values.dedup();
        values
    }

    /// Whether the request's `authorizationFilters` held any element, `null` included, as
    /// Java's `authorizationFilters().isEmpty()` is false for `[null]`.
    pub fn has_authorization_filters(&self) -> bool {
        !self.authorization_filters.is_empty() || self.null_authorization_filters > 0
    }

    /// Reads a query from a JSON value with the request model of `contract`: under the
    /// harmonization contract the eight fields of the consent branch's `Query`, under the
    /// main contract the seven fields of main's (`userConsents` is then an unknown field,
    /// refused naming it). Every other rule of this module applies to both.
    ///
    /// # Errors
    /// The `serde_json` error for a query the model refuses.
    pub fn from_value(value: Value, contract: ConsentContract) -> serde_json::Result<Query> {
        match contract {
            ConsentContract::Harmonization => serde_json::from_value(value),
            ConsentContract::Main => {
                let fields: MainQueryFields = serde_json::from_value(value)?;
                Query::try_from(QueryFields::from(fields)).map_err(de::Error::custom)
            }
        }
    }

    /// Every [`PhenotypicFilter`] in the phenotypic clause, depth first in clause order,
    /// as `Query.allFilters()` flattens it. Authorization filters are not included.
    pub fn all_filters(&self) -> Vec<&PhenotypicFilter> {
        fn walk<'a>(clause: &'a PhenotypicClause, out: &mut Vec<&'a PhenotypicFilter>) {
            match clause {
                PhenotypicClause::Filter(filter) => out.push(filter),
                PhenotypicClause::Subquery(subquery) => {
                    for child in &subquery.phenotypic_clauses {
                        walk(child, out);
                    }
                }
            }
        }
        let mut out = Vec::new();
        if let Some(clause) = &self.phenotypic_clause {
            walk(clause, &mut out);
        }
        out
    }
}

/// `v3.AuthorizationFilter`: patients must have one of `values` for `concept_path`.
///
/// Both fields may be `null`, and so may each value, as Java reads them: the sync route
/// never looks at authorization filters on the consent branch and answers the query, so this
/// service, which never applies them either, accepts them all. `POST /query` checks them
/// ([`crate::QueryValidator`]).
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AuthorizationFilter {
    /// The categorical concept to match; `None` when the JSON held `null` or omitted it.
    #[serde(default)]
    pub concept_path: Option<String>,
    /// Accepted values; `None` when the JSON held `null` or omitted it, and a `None` element
    /// for each `null` in the list.
    #[serde(default)]
    pub values: Option<Vec<Option<String>>>,
}

/// `v3.UserConsent`: one consent the caller holds, naming a partition.
///
/// A `null` element of the `userConsents` array reads as a consent with no value, so it
/// still makes the list non-empty (Java's `userConsents().isEmpty()` is false for it) but
/// contributes no consent value. A number or boolean `value` reads as Jackson's scalar
/// coercion into a `String` reads it: `{"value":1.50}` is the consent `"1.50"` in query text
/// and `"1.5"` in an object `query` of a request body. [`parse_strict`],
/// [`parse_request_body`] and [`Query::from_json`] quote such a token before `serde_json`
/// reads it, since `serde_json` keeps no number text; a float that reaches this type unquoted
/// (a query built from a `Value` some other way) is refused rather than read with different
/// text. Objects and arrays are refused.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct UserConsent {
    /// The consent, for example `phs000007.c1`; `None` when null or absent.
    pub value: Option<String>,
}

impl<'de> Deserialize<'de> for UserConsent {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Deserialize)]
        #[serde(deny_unknown_fields)]
        struct Fields {
            #[serde(default, deserialize_with = "scalar_text")]
            value: Option<String>,
        }
        Ok(Option::<Fields>::deserialize(deserializer)?
            .map(|fields| UserConsent {
                value: fields.value,
            })
            .unwrap_or_default())
    }
}

/// A JSON string, number or boolean as text, `null` as `None`: Jackson's coercion of a
/// scalar into a `String` field.
fn scalar_text<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    struct ScalarText;

    impl<'de> Visitor<'de> for ScalarText {
        type Value = Option<String>;

        fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            f.write_str("a string, integer, boolean or null")
        }

        fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
            Ok(None)
        }

        fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
            Ok(None)
        }

        fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
            d.deserialize_any(self)
        }

        fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
            Ok(Some(value.to_owned()))
        }

        fn visit_string<E: de::Error>(self, value: String) -> Result<Self::Value, E> {
            Ok(Some(value))
        }

        fn visit_bool<E: de::Error>(self, value: bool) -> Result<Self::Value, E> {
            Ok(Some(value.to_string()))
        }

        fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
            Ok(Some(value.to_string()))
        }

        fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
            Ok(Some(value.to_string()))
        }

        fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
            Err(E::invalid_type(de::Unexpected::Float(value), &self))
        }
    }

    deserializer.deserialize_any(ScalarText)
}

/// `v3.GenomicFilter`, read as Jackson reads it. Java fails on some of what Jackson accepts,
/// and the engine refuses those filters when it translates them.
///
/// A `null` element of `genomicFilters` reads as a filter with every field `null`, and a
/// `null` among `values` is kept, as Jackson keeps both.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenomicFilter {
    /// A variant spec (`chr,pos,ref,alt[,gene,consequence]`) or an INFO column.
    #[serde(default)]
    pub key: Option<String>,
    /// Zygosities of a variant spec, or values of a categorical INFO column; `None` when null
    /// or absent. A `null` element is `None`.
    #[serde(default)]
    pub values: Option<Vec<Option<String>>>,
    /// Lower bound of a numeric INFO column, a Java `Float`: a JSON number or numeric string
    /// read as Jackson reads it into a `Float` (see the module docs).
    #[serde(default, deserialize_with = "java_float")]
    pub min: Option<f32>,
    /// Upper bound of a numeric INFO column, read as `min` is.
    #[serde(default, deserialize_with = "java_float")]
    pub max: Option<f32>,
}

/// `v3.PhenotypicClause`: a filter or a subquery, chosen by deduction (see the module
/// docs).
#[derive(Debug, Clone, PartialEq)]
pub enum PhenotypicClause {
    /// A filter on one concept path.
    Filter(PhenotypicFilter),
    /// Clauses combined with one operator.
    Subquery(PhenotypicSubquery),
}

/// `v3.PhenotypicFilter`: a filter on one concept path.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct PhenotypicFilter {
    /// How the concept path is matched.
    pub phenotypic_filter_type: PhenotypicFilterType,
    /// The concept path. A `null` one is refused ([`NULL_CONCEPT_PATH_MESSAGE`]).
    #[serde(deserialize_with = "concept_path")]
    pub concept_path: String,
    /// Values to match, for `FILTER`; `None` when null or absent. A `null` element is refused
    /// ([`NULL_VALUE_MESSAGE`]).
    #[serde(default, deserialize_with = "filter_values")]
    pub values: Option<Vec<String>>,
    /// Inclusive lower bound, for `FILTER`.
    #[serde(default, deserialize_with = "java_double")]
    pub min: Option<f64>,
    /// Inclusive upper bound, for `FILTER`.
    #[serde(default, deserialize_with = "java_double")]
    pub max: Option<f64>,
    /// Accepted and ignored, as in Java.
    #[serde(default, deserialize_with = "java_boolean")]
    pub not: Option<bool>,
    /// Whether the request's `values` held `null` elements, which are not in `values`. Set
    /// only on a `FILTER`, by the query reader. Java counts them toward a non-empty `values`
    /// (so the filter is still a categorical one) and fails when it looks one up in a
    /// categorical concept; the evaluator refuses that case ([`NULL_VALUE_MESSAGE`]) and
    /// otherwise answers as Java does.
    #[serde(skip)]
    pub null_values: bool,
}

/// Refusal of a phenotypic filter whose `conceptPath` is `null`. Java fails on it on every
/// route: the executor looks the path up on the sync route, and `QueryValidator` (or the
/// executor, for `ANY_RECORD_OF`) on `POST /query`.
pub const NULL_CONCEPT_PATH_MESSAGE: &str = "a phenotypic filter's conceptPath is null";

/// Refusal of a `FILTER` whose `values` held a `null`, when the concept is categorical in a
/// selected partition: Java's executor looks the `null` up in the concept's category map,
/// which throws. Raised by the evaluator, on every route.
pub const NULL_VALUE_MESSAGE: &str = "a phenotypic filter's values hold null";

fn concept_path<'de, D: Deserializer<'de>>(deserializer: D) -> Result<String, D::Error> {
    Option::<String>::deserialize(deserializer)?
        .ok_or_else(|| de::Error::custom(NULL_CONCEPT_PATH_MESSAGE))
}

fn filter_values<'de, D: Deserializer<'de>>(
    deserializer: D,
) -> Result<Option<Vec<String>>, D::Error> {
    Option::<Vec<Option<String>>>::deserialize(deserializer)?
        .map(|values| values.into_iter().collect::<Option<Vec<String>>>())
        .map(|values| values.ok_or_else(|| de::Error::custom(NULL_VALUE_MESSAGE)))
        .transpose()
}

impl PhenotypicFilter {
    /// A filter of `filter_type` on `concept_path` with no values and no bounds.
    pub fn new(filter_type: PhenotypicFilterType, concept_path: impl Into<String>) -> Self {
        PhenotypicFilter {
            phenotypic_filter_type: filter_type,
            concept_path: concept_path.into(),
            values: None,
            min: None,
            max: None,
            not: None,
            null_values: false,
        }
    }

    /// `PhenotypicFilter.isCategoricalFilter`: a `FILTER` with a non-empty `values`, a
    /// `null` element counting as one ([`PhenotypicFilter::null_values`]).
    pub fn is_categorical_filter(&self) -> bool {
        self.phenotypic_filter_type == PhenotypicFilterType::Filter
            && (self.null_values || self.values.as_ref().is_some_and(|v| !v.is_empty()))
    }

    /// `PhenotypicFilter.isNumericFilter`: a `FILTER` with `min` or `max` set.
    pub fn is_numeric_filter(&self) -> bool {
        self.phenotypic_filter_type == PhenotypicFilterType::Filter
            && (self.min.is_some() || self.max.is_some())
    }
}

/// `v3.PhenotypicSubquery`: clauses combined with one operator.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct PhenotypicSubquery {
    /// Accepted and ignored, as in Java.
    #[serde(default, deserialize_with = "java_boolean")]
    pub not: Option<bool>,
    /// The clauses; empty when null or absent.
    #[serde(default, deserialize_with = "null_as_default")]
    pub phenotypic_clauses: Vec<PhenotypicClause>,
    /// How the clauses combine; `None` when null or absent, which fails evaluation.
    #[serde(default)]
    pub operator: Option<Operator>,
}

const SUBQUERY_ONLY_FIELDS: [&str; 2] = ["phenotypicClauses", "operator"];
const FILTER_ONLY_FIELDS: [&str; 5] = [
    "phenotypicFilterType",
    "conceptPath",
    "values",
    "min",
    "max",
];
const FILTER_IGNORED_FIELDS: [&str; 2] = ["categoricalFilter", "numericFilter"];

impl<'de> Deserialize<'de> for PhenotypicClause {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        let Value::Object(mut fields) = StrictValue::deserialize(deserializer)?.0 else {
            return Err(de::Error::custom(
                "a phenotypic clause must be a JSON object",
            ));
        };
        let subquery = fields
            .keys()
            .any(|k| SUBQUERY_ONLY_FIELDS.contains(&k.as_str()));
        let filter = fields
            .keys()
            .any(|k| FILTER_ONLY_FIELDS.contains(&k.as_str()));
        match (subquery, filter) {
            (true, false) => serde_json::from_value(Value::Object(fields))
                .map(PhenotypicClause::Subquery)
                .map_err(de::Error::custom),
            (false, true) => {
                for ignored in FILTER_IGNORED_FIELDS {
                    fields.remove(ignored);
                }
                serde_json::from_value(Value::Object(fields))
                    .map(PhenotypicClause::Filter)
                    .map_err(de::Error::custom)
            }
            (true, true) => {
                let mut mixed: Vec<&str> = fields
                    .keys()
                    .map(String::as_str)
                    .filter(|k| SUBQUERY_ONLY_FIELDS.contains(k))
                    .collect();
                mixed.sort_unstable();
                Err(de::Error::custom(format_args!(
                    "a phenotypic clause cannot mix subquery fields (phenotypicClauses, operator) \
                     with filter fields (phenotypicFilterType, conceptPath, values, min, max): \
                     unknown field `{}` for a filter",
                    mixed[0]
                )))
            }
            (false, false) => Err(de::Error::custom(
                "cannot deduce the phenotypic clause type: expected phenotypicClauses or \
                 operator for a subquery, or phenotypicFilterType, conceptPath, values, min or \
                 max for a filter",
            )),
        }
    }
}

/// A JSON value read like [`serde_json::Value`], except that an object holding the same key
/// twice, at any depth, is an error rather than keeping the last value.
struct StrictValue(Value);

impl<'de> Deserialize<'de> for StrictValue {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        deserializer
            .deserialize_any(StrictValueVisitor { last_wins: &[] })
            .map(StrictValue)
    }
}

/// The keys an object may repeat, keeping the last value as Jackson does, under `contract`:
/// `authorizationFilters` under the main contract, where Java main and the query service
/// read the last copy, and none under the harmonization contract.
fn repeatable_keys(contract: ConsentContract) -> &'static [&'static str] {
    match contract {
        ConsentContract::Main => &["authorizationFilters"],
        ConsentContract::Harmonization => &[],
    }
}

/// Reads a JSON value as [`StrictValue`] does, except that the keys in `last_wins` may repeat
/// in an object and keep their last value.
#[derive(Clone, Copy)]
struct StrictValueVisitor {
    last_wins: &'static [&'static str],
}

impl<'de> de::DeserializeSeed<'de> for StrictValueVisitor {
    type Value = Value;

    fn deserialize<D: Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        deserializer.deserialize_any(self)
    }
}

impl<'de> Visitor<'de> for StrictValueVisitor {
    type Value = Value;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a JSON value")
    }

    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_none<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }

    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Value, D::Error> {
        d.deserialize_any(self)
    }

    fn visit_bool<E: de::Error>(self, value: bool) -> Result<Value, E> {
        Ok(Value::Bool(value))
    }

    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Value, E> {
        Ok(Value::from(value))
    }

    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Value, E> {
        Ok(Value::from(value))
    }

    fn visit_f64<E: de::Error>(self, value: f64) -> Result<Value, E> {
        Ok(Value::from(value))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Value, E> {
        Ok(Value::String(value.to_owned()))
    }

    fn visit_string<E: de::Error>(self, value: String) -> Result<Value, E> {
        Ok(Value::String(value))
    }

    fn visit_seq<A: de::SeqAccess<'de>>(self, mut seq: A) -> Result<Value, A::Error> {
        let mut items = Vec::new();
        while let Some(item) = seq.next_element_seed(self)? {
            items.push(item);
        }
        Ok(Value::Array(items))
    }

    fn visit_map<A: de::MapAccess<'de>>(self, mut map: A) -> Result<Value, A::Error> {
        let mut fields = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if fields.contains_key(&key) && !self.last_wins.contains(&key.as_str()) {
                return Err(de::Error::custom(format_args!("duplicate key {key:?}")));
            }
            let value = map.next_value_seed(self)?;
            fields.insert(key, value);
        }
        Ok(Value::Object(fields))
    }
}

/// Parses `json` as a [`serde_json::Value`], refusing any object that holds the same key
/// twice at any depth (where `serde_json` and Jackson keep the last value). For reading query
/// text, such as the contents of a string `query`; a whole request body goes through
/// [`parse_request_body`].
///
/// Numbers are read as Jackson's character parser reads them, through the number scan of
/// `json_number`: a number with more than 1,000 counted digits is refused, a `min` or `max`
/// that overflows a double is read as `±Infinity`, the integer `-0` as zero, and a number in
/// a `String` field as its literal text.
///
/// # Errors
/// The `serde_json` error for invalid JSON (a byte order mark, comments, trailing commas
/// and `NaN` literals included), a number with more than 1,000 counted digits, or a duplicate
/// key.
pub fn parse_strict(json: &[u8]) -> serde_json::Result<Value> {
    parse_strict_from(json, Source::QueryText, ConsentContract::Harmonization)
}

/// [`parse_strict`] for a service answering by `contract`: under the main contract a
/// repeated `authorizationFilters` keeps its last value, as Jackson keeps it, and every other
/// repeated key is refused.
///
/// # Errors
/// As [`parse_strict`].
pub fn parse_strict_for(json: &[u8], contract: ConsentContract) -> serde_json::Result<Value> {
    parse_strict_from(json, Source::QueryText, contract)
}

/// [`parse_strict`] for a whole request body, envelope included. A body parsed with plain
/// `serde_json::from_slice` has already dropped an earlier duplicate key.
///
/// Numbers are read as the service reads an object `query`: Jackson's byte parser counts
/// every digit against the 1,000-digit limit, and a number in a `String` field becomes the
/// text Jackson writes its untyped value back as (`1.50` becomes `1.5`, `-0` becomes `0`).
///
/// # Errors
/// As [`parse_strict`].
pub fn parse_request_body(json: &[u8]) -> serde_json::Result<Value> {
    parse_strict_from(json, Source::RequestBody, ConsentContract::Harmonization)
}

/// [`parse_request_body`] for a service answering by `contract`, with the repeated keys of
/// [`parse_strict_for`].
///
/// # Errors
/// As [`parse_strict`].
pub fn parse_request_body_for(json: &[u8], contract: ConsentContract) -> serde_json::Result<Value> {
    parse_strict_from(json, Source::RequestBody, contract)
}

fn parse_strict_from(
    json: &[u8],
    source: Source,
    contract: ConsentContract,
) -> serde_json::Result<Value> {
    let json = java_number_text(json, source)?;
    let mut deserializer = serde_json::Deserializer::from_slice(&json);
    let value = de::DeserializeSeed::deserialize(
        StrictValueVisitor {
            last_wins: repeatable_keys(contract),
        },
        &mut deserializer,
    )?;
    deserializer.end()?;
    Ok(value)
}

/// `json` with its number tokens prepared as `json_number::java_numbers` prepares them for
/// `source`, the refusal of an over-long number given as a `serde_json` error.
fn java_number_text(json: &[u8], source: Source) -> serde_json::Result<std::borrow::Cow<'_, [u8]>> {
    crate::json_number::java_numbers(json, source).map_err(<serde_json::Error as de::Error>::custom)
}

impl Query {
    /// Reads a query from JSON text with every rule of this module, duplicate keys
    /// included, and numbers read as [`parse_strict`] reads them. Otherwise the same as
    /// `serde_json::from_slice::<Query>`.
    ///
    /// # Errors
    /// The `serde_json` error for invalid JSON, a number with more than 1,000 digits, or a
    /// query the model refuses.
    pub fn from_json(json: &[u8]) -> serde_json::Result<Query> {
        serde_json::from_slice(&java_number_text(json, Source::QueryText)?)
    }
}

fn null_as_default<'de, D, T>(deserializer: D) -> Result<T, D::Error>
where
    D: Deserializer<'de>,
    T: Default + Deserialize<'de>,
{
    Ok(Option::<T>::deserialize(deserializer)?.unwrap_or_default())
}

struct JavaDouble;

impl<'de> Visitor<'de> for JavaDouble {
    type Value = Option<f64>;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a number, a numeric string, or null")
    }

    fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_any(self)
    }

    fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
        Ok(Some(value))
    }

    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
        Ok(Some(value as f64))
    }

    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
        Ok(Some(value as f64))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
        hpds_format::jackson_string_double(value).map_err(|error| match error {
            hpds_format::StringDoubleError::TooLong(too_long) => E::custom(too_long),
            hpds_format::StringDoubleError::Invalid => {
                E::invalid_value(de::Unexpected::Str(value), &self)
            }
        })
    }
}

fn java_double<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<f64>, D::Error> {
    deserializer.deserialize_any(JavaDouble)
}

/// `Float` as Jackson 2.21's `NumberDeserializers.FloatDeserializer` reads it. The query
/// readers rewrite every genomic bound number token to a string holding the exact float Jackson
/// reads from it (`json_number`), so a number reaches this visitor only from a query built
/// another way; it is read as an object query's number is, through the double's
/// `Double.toString`.
struct JavaFloat;

impl<'de> Visitor<'de> for JavaFloat {
    type Value = Option<f32>;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a number, a numeric string, or null")
    }

    fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_any(self)
    }

    fn visit_f64<E: de::Error>(self, value: f64) -> Result<Self::Value, E> {
        Ok(hpds_format::parse_java_float(
            &hpds_format::java_double_to_string(value),
        ))
    }

    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
        Ok(Some(value as f32))
    }

    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
        Ok(Some(value as f32))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
        hpds_format::jackson_string_float(value).map_err(|error| match error {
            hpds_format::StringDoubleError::TooLong(too_long) => E::custom(too_long),
            hpds_format::StringDoubleError::Invalid => {
                E::invalid_value(de::Unexpected::Str(value), &self)
            }
        })
    }
}

fn java_float<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<f32>, D::Error> {
    deserializer.deserialize_any(JavaFloat)
}

/// `Boolean` as Jackson 2.21's `NumberDeserializers.BooleanDeserializer` reads it with the
/// default mapper (see the module docs). An integer beyond 64 bits, which Jackson reads as true,
/// is rewritten to `true` by the request number scan before `serde_json` sees it.
struct JavaBoolean;

impl<'de> Visitor<'de> for JavaBoolean {
    type Value = Option<bool>;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a boolean, an integer, \"true\", \"false\", or null")
    }

    fn visit_unit<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_none<E: de::Error>(self) -> Result<Self::Value, E> {
        Ok(None)
    }

    fn visit_some<D: Deserializer<'de>>(self, d: D) -> Result<Self::Value, D::Error> {
        d.deserialize_any(self)
    }

    fn visit_bool<E: de::Error>(self, value: bool) -> Result<Self::Value, E> {
        Ok(Some(value))
    }

    fn visit_i64<E: de::Error>(self, value: i64) -> Result<Self::Value, E> {
        Ok(Some(value != 0))
    }

    fn visit_u64<E: de::Error>(self, value: u64) -> Result<Self::Value, E> {
        Ok(Some(value != 0))
    }

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
        let trimmed = java_trim(value);
        match trimmed {
            "" | "null" => Ok(None),
            "true" | "TRUE" | "True" => Ok(Some(true)),
            "false" | "FALSE" | "False" => Ok(Some(false)),
            _ => Err(E::invalid_value(de::Unexpected::Str(value), &self)),
        }
    }
}

fn java_boolean<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<bool>, D::Error> {
    deserializer.deserialize_any(JavaBoolean)
}

/// A `UUID` field as Jackson 2.21's `UUIDDeserializer` reads it: the text trimmed as
/// `String.trim` does, blank as null, otherwise the canonical 36-character form in either case
/// or the 16 bytes in Base64 (see `hpds_format::jackson_uuid`), kept in lowercase canonical
/// form. `"null"` and every other form are refused.
fn java_uuid<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    let Some(value) = Option::<String>::deserialize(deserializer)? else {
        return Ok(None);
    };
    let trimmed = java_trim(&value);
    if trimmed.is_empty() {
        return Ok(None);
    }
    jackson_uuid(trimmed).map(Some).ok_or_else(|| {
        de::Error::custom(format!(
            "{value:?} is not a UUID in the canonical 8-4-4-4-12 form or 16 bytes of Base64"
        ))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn parse(value: Value) -> Result<Query, serde_json::Error> {
        serde_json::from_value(value)
    }

    #[test]
    fn minimal_query_has_empty_lists() {
        let q =
            parse(json!({"expectedResultType": "COUNT", "select": null, "genomicFilters": null}))
                .unwrap();
        assert_eq!(q, Query::new(ResultType::Count));
    }

    #[test]
    fn nulls_are_kept_where_java_can_answer_and_refused_where_it_cannot() {
        let q = parse(json!({"expectedResultType": "COUNT", "select": [null, "a", null]})).unwrap();
        assert_eq!(
            (q.select.clone(), q.select_nulls),
            (vec!["a".to_owned()], 2)
        );
        assert!(!q.select_nulls_fail(false));
        assert!(q.select_nulls_fail(true));
        for result_type in ["CROSS_COUNT", "OBSERVATION_CROSS_COUNT"] {
            let q = parse(json!({"expectedResultType": result_type, "select": [null]})).unwrap();
            assert!(q.select_nulls_fail(false), "{result_type}");
        }
        let q = parse(json!({"expectedResultType": "CROSS_COUNT", "select": ["a"]})).unwrap();
        assert!(!q.select_nulls_fail(false) && !q.select_nulls_fail(true));
        let q = parse(json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": null, "values": [null, "x"]}]})).unwrap();
        assert_eq!(
            q.authorization_filters,
            [AuthorizationFilter {
                concept_path: None,
                values: Some(vec![None, Some("x".to_owned())]),
            }]
        );
        for query in [json!({"expectedResultType": null}), json!({"select": []})] {
            let error = parse(query.clone()).unwrap_err().to_string();
            assert_eq!(error, NULL_RESULT_TYPE_MESSAGE, "{query}");
        }
    }

    #[test]
    fn clause_constructs_java_fails_on_are_read_and_deferred() {
        let read = |clause: Value| {
            parse(json!({"expectedResultType": "INFO_COLUMN_LISTING", "phenotypicClause": clause}))
                .unwrap()
        };
        let required = json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "a"});
        for (clause, message) in [
            (
                json!({"phenotypicFilterType": "REQUIRED", "conceptPath": null}),
                NULL_CONCEPT_PATH_MESSAGE,
            ),
            (
                json!({"phenotypicFilterType": "ANY_RECORD_OF"}),
                NULL_CONCEPT_PATH_MESSAGE,
            ),
            (json!({"conceptPath": "a"}), NULL_FILTER_TYPE_MESSAGE),
            (
                json!({"phenotypicFilterType": null, "conceptPath": "a"}),
                NULL_FILTER_TYPE_MESSAGE,
            ),
            (
                json!({"operator": "AND", "phenotypicClauses": [required, null]}),
                NULL_CLAUSE_MESSAGE,
            ),
            (
                json!({"operator": "OR", "phenotypicClauses": [{"operator": "AND", "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED"}]}]}),
                NULL_CONCEPT_PATH_MESSAGE,
            ),
        ] {
            assert_eq!(
                read(clause.clone()).java_failure.as_deref(),
                Some(message),
                "{clause}"
            );
        }
        assert_eq!(read(required.clone()).java_failure, None);
        let subquery = read(json!({"operator": "AND", "phenotypicClauses": [required, null]}));
        match subquery.phenotypic_clause {
            Some(PhenotypicClause::Subquery(s)) => assert_eq!(s.phenotypic_clauses.len(), 1),
            other => panic!("expected a subquery, got {other:?}"),
        }
        for clause in [
            json!({}),
            json!({"not": true}),
            json!({"operator": "AND", "conceptPath": "a"}),
            json!({"phenotypicFilterType": "REQUIRED", "conceptPath": "a", "bogus": 1}),
            json!({"phenotypicFilterType": "FILTER", "conceptPath": "a", "min": "1d"}),
        ] {
            assert!(
                parse(
                    json!({"expectedResultType": "INFO_COLUMN_LISTING", "phenotypicClause": clause})
                )
                .is_err(),
                "{clause}"
            );
        }
        let other = parse(json!({
            "expectedResultType": "INFO_COLUMN_LISTING",
            "authorizationFilters": [null, {"conceptPath": "a", "values": ["x"]}],
            "genomicFilters": [null, {"key": "k", "values": [null, "v"]}]
        }))
        .unwrap();
        assert_eq!(other.null_authorization_filters, 1);
        assert_eq!(other.authorization_filters.len(), 1);
        assert_eq!(
            other.genomic_filters,
            [
                GenomicFilter::default(),
                GenomicFilter {
                    key: Some("k".into()),
                    values: Some(vec![None, Some("v".into())]),
                    ..GenomicFilter::default()
                }
            ]
        );
        assert_eq!(other.java_failure, None);
    }

    #[test]
    fn a_null_filter_value_marks_only_a_filter_and_is_dropped() {
        let read = |clause: Value| {
            parse(json!({"expectedResultType": "COUNT", "phenotypicClause": clause})).unwrap()
        };
        let filters = |query: &Query| -> Vec<PhenotypicFilter> {
            query.all_filters().into_iter().cloned().collect()
        };
        let q = read(
            json!({"phenotypicFilterType": "FILTER", "conceptPath": "a", "values": ["x", null]}),
        );
        assert_eq!(q.java_failure, None);
        let [f] = filters(&q).try_into().unwrap();
        assert!(f.null_values && f.is_categorical_filter());
        assert_eq!(f.values.as_deref(), Some(&["x".to_owned()][..]));
        let only_null =
            read(json!({"phenotypicFilterType": "FILTER", "conceptPath": "a", "values": [null]}));
        let [f] = filters(&only_null).try_into().unwrap();
        assert!(f.null_values && f.is_categorical_filter());
        assert_eq!(f.values.as_deref(), Some(&[][..]));
        for filter_type in ["REQUIRED", "ANY_RECORD_OF"] {
            let q = read(
                json!({"phenotypicFilterType": filter_type, "conceptPath": "a", "values": [null]}),
            );
            assert_eq!(q.java_failure, None, "{filter_type}");
            let [f] = filters(&q).try_into().unwrap();
            assert!(!f.null_values, "{filter_type}");
            assert_eq!(f.values.as_deref(), Some(&[][..]), "{filter_type}");
        }
        let nested = read(json!({"operator": "OR", "phenotypicClauses": [
            {"phenotypicFilterType": "FILTER", "conceptPath": "a", "values": ["x"]},
            null,
            {"operator": "AND", "phenotypicClauses": [
                {"phenotypicFilterType": "REQUIRED", "conceptPath": "b", "values": [null]},
                {"phenotypicFilterType": "FILTER", "conceptPath": "c", "values": [null, "y"]}
            ]}
        ]}));
        let marks: Vec<(String, bool)> = filters(&nested)
            .into_iter()
            .map(|f| (f.concept_path, f.null_values))
            .collect();
        assert_eq!(
            marks,
            [("a".into(), false), ("b".into(), false), ("c".into(), true)]
        );
        assert_eq!(nested.java_failure.as_deref(), Some(NULL_CLAUSE_MESSAGE));
    }

    #[test]
    fn missing_result_type_is_rejected() {
        assert!(parse(json!({"select": []})).is_err());
    }

    #[test]
    fn unknown_query_field_is_rejected() {
        let err = parse(json!({"expectedResultType": "COUNT", "bogus": 1})).unwrap_err();
        assert!(err.to_string().contains("bogus"), "{err}");
    }

    #[test]
    fn enums_accept_names_and_ordinals_only() {
        assert_eq!(
            parse(json!({"expectedResultType": 13}))
                .unwrap()
                .expected_result_type,
            ResultType::Patients
        );
        assert!(parse(json!({"expectedResultType": 14})).is_err());
        assert!(parse(json!({"expectedResultType": "count"})).is_err());
    }

    #[test]
    fn clause_is_deduced_from_fields() {
        let q = parse(json!({
            "expectedResultType": "COUNT",
            "phenotypicClause": {
                "operator": "OR",
                "not": false,
                "phenotypicClauses": [
                    {"phenotypicFilterType": "FILTER", "conceptPath": "\\a\\", "values": ["x"], "categoricalFilter": true},
                    {"conceptPath": "\\b\\", "phenotypicFilterType": "FILTER", "min": "5", "max": 7}
                ]
            }
        }))
        .unwrap();
        let PhenotypicClause::Subquery(sub) = q.phenotypic_clause.unwrap() else {
            panic!("expected a subquery");
        };
        assert_eq!(sub.operator, Some(Operator::Or));
        let PhenotypicClause::Filter(second) = &sub.phenotypic_clauses[1] else {
            panic!("expected a filter");
        };
        assert_eq!((second.min, second.max), (Some(5.0), Some(7.0)));
    }

    #[test]
    fn ambiguous_or_mixed_clauses_are_rejected() {
        for clause in [
            json!({}),
            json!({"not": true}),
            json!({"operator": "AND", "conceptPath": "\\a\\"}),
            json!({"phenotypicFilterType": "FILTER", "conceptPath": "\\a\\", "extra": 1}),
            json!({"operator": "AND", "phenotypicClauses": [], "categoricalFilter": true}),
        ] {
            assert!(
                parse(json!({"expectedResultType": "COUNT", "phenotypicClause": clause})).is_err(),
                "{clause}"
            );
        }
    }

    #[test]
    fn subquery_without_clauses_or_operator_parses() {
        let q = parse(
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicClauses": null}}),
        )
        .unwrap();
        let Some(PhenotypicClause::Subquery(sub)) = q.phenotypic_clause else {
            panic!("expected a subquery");
        };
        assert!(sub.phenotypic_clauses.is_empty());
        assert_eq!(sub.operator, None);
    }

    #[test]
    fn enums_follow_jacksons_enum_coercion() {
        let result_type = |value: Value| {
            parse(json!({"expectedResultType": value})).map(|q| q.expected_result_type)
        };
        for (value, expected) in [
            (json!("COUNT"), Some(ResultType::Count)),
            (json!(" COUNT "), Some(ResultType::Count)),
            (json!("\tCOUNT\n"), Some(ResultType::Count)),
            (json!("COUNT\u{0}"), Some(ResultType::Count)),
            (json!("0"), Some(ResultType::Count)),
            (json!("1"), Some(ResultType::Dataframe)),
            (json!(" 13 "), Some(ResultType::Patients)),
            (json!(1), Some(ResultType::Dataframe)),
            (json!("count"), None),
            (json!("Count"), None),
            (json!("null"), None),
            (json!(""), None),
            (json!("  "), None),
            (json!("01"), None),
            (json!("+1"), None),
            (json!("-0"), None),
            (json!("1.0"), None),
            (json!("14"), None),
            (json!("9999999999"), None),
            (json!(99), None),
            (json!(1.0), None),
            (json!(true), None),
        ] {
            assert_eq!(result_type(value.clone()).ok(), expected, "{value}");
        }
        let operator = |value: Value| {
            parse(
                json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": value,
                "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED", "conceptPath": "x"}]}}),
            )
            .map(|q| match q.phenotypic_clause {
                Some(PhenotypicClause::Subquery(s)) => s.operator,
                _ => panic!("expected a subquery"),
            })
        };
        assert_eq!(operator(json!(" AND ")).ok(), Some(Some(Operator::And)));
        assert_eq!(operator(json!("1")).ok(), Some(Some(Operator::Or)));
        assert!(operator(json!("and")).is_err());
        assert!(operator(json!("2")).is_err());
        let filter_type = |value: Value| {
            parse(json!({"expectedResultType": "COUNT", "phenotypicClause":
                {"phenotypicFilterType": value, "conceptPath": "x"}}))
            .map(|q| match q.phenotypic_clause {
                Some(PhenotypicClause::Filter(f)) => f.phenotypic_filter_type,
                _ => panic!("expected a filter"),
            })
        };
        assert_eq!(
            filter_type(json!(" REQUIRED ")).ok(),
            Some(PhenotypicFilterType::Required)
        );
        assert_eq!(
            filter_type(json!("0")).ok(),
            Some(PhenotypicFilterType::Required)
        );
        assert!(filter_type(json!("filter")).is_err());
        assert!(filter_type(json!("null")).is_err());
    }

    #[test]
    fn not_follows_jacksons_boolean_coercion() {
        let filter_not = |not: Value| {
            parse(json!({"expectedResultType": "COUNT", "phenotypicClause":
                {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\", "not": not}}))
            .map(|q| match q.phenotypic_clause {
                Some(PhenotypicClause::Filter(f)) => f.not,
                _ => panic!("expected a filter"),
            })
        };
        let subquery_not = |not: Value| {
            parse(json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": "AND", "not": not,
                "phenotypicClauses": [{"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"}]}}))
            .map(|q| match q.phenotypic_clause {
                Some(PhenotypicClause::Subquery(s)) => s.not,
                _ => panic!("expected a subquery"),
            })
        };
        let jackson = [
            (json!("null"), Some(None)),
            (json!(" null "), Some(None)),
            (json!("TRUE"), Some(Some(true))),
            (json!("True"), Some(Some(true))),
            (json!(" true "), Some(Some(true))),
            (json!("\ttrue\n"), Some(Some(true))),
            (json!("False"), Some(Some(false))),
            (json!(" FALSE "), Some(Some(false))),
            (json!("  "), Some(None)),
            (json!(""), Some(None)),
            (json!("tRuE"), None),
            (json!("yes"), None),
            (json!("1"), None),
            (json!("0"), None),
            (json!("NULL"), None),
            (json!(1), Some(Some(true))),
            (json!(0), Some(Some(false))),
            (json!(2), Some(Some(true))),
            (json!(-1), Some(Some(true))),
            (json!(1.0), None),
            (json!(0.0), None),
            (json!(null), Some(None)),
            (json!(true), Some(Some(true))),
            (json!(false), Some(Some(false))),
        ];
        for (not, expected) in jackson {
            assert_eq!(filter_not(not.clone()).ok(), expected, "filter not {not}");
            assert_eq!(
                subquery_not(not.clone()).ok(),
                expected,
                "subquery not {not}"
            );
        }
    }

    #[test]
    fn doubles_follow_jackson_coercion() {
        let filter = |min: Value| {
            parse(json!({"expectedResultType": "COUNT", "phenotypicClause":
                {"phenotypicFilterType": "FILTER", "conceptPath": "\\a\\", "min": min}}))
            .map(|q| match q.phenotypic_clause {
                Some(PhenotypicClause::Filter(f)) => f.min,
                _ => panic!("expected a filter"),
            })
        };
        assert_eq!(filter(json!(3)).unwrap(), Some(3.0));
        assert_eq!(filter(json!(" 2.5 ")).unwrap(), Some(2.5));
        assert_eq!(filter(json!("")).unwrap(), None);
        assert_eq!(filter(json!(null)).unwrap(), None);
        assert!(filter(json!("NaN")).unwrap().unwrap().is_nan());
        assert_eq!(filter(json!("-Infinity")).unwrap(), Some(f64::NEG_INFINITY));
        assert!(filter(json!("abc")).is_err());
        assert!(filter(json!(true)).is_err());
    }

    #[test]
    fn uuids_follow_jacksons_uuid_coercion() {
        let q = parse(json!({"expectedResultType": "COUNT", "picsureId": "8694E3D4-5CB4-410F-8431-993445E6D3F6"})).unwrap();
        assert_eq!(
            q.picsure_id.as_deref(),
            Some("8694e3d4-5cb4-410f-8431-993445e6d3f6")
        );
        assert!(parse(json!({"expectedResultType": "COUNT", "id": "1-2-3-4-5"})).is_err());
        let uuid = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
        let id =
            |value: Value| parse(json!({"expectedResultType": "COUNT", "id": value})).map(|q| q.id);
        for (value, expected) in [
            (json!(format!(" {uuid} ")), Some(Some(uuid.to_owned()))),
            (
                json!(format!("\t{}\n", uuid.to_uppercase())),
                Some(Some(uuid.to_owned())),
            ),
            (json!(""), Some(None)),
            (json!("  "), Some(None)),
            (json!(null), Some(None)),
            (json!("null"), None),
            (json!(uuid.replace('-', "")), None),
            (json!(format!("{{{uuid}}}")), None),
            (json!(format!("{uuid} x")), None),
            (json!(123), None),
        ] {
            assert_eq!(id(value.clone()).ok(), expected, "{value}");
        }
    }

    #[test]
    fn user_consents_parse_as_java_reads_them() {
        let q = parse(json!({"expectedResultType": "COUNT", "userConsents": null})).unwrap();
        assert!(q.user_consents.is_empty());
        let q = parse(json!({"expectedResultType": "COUNT", "userConsents": [
            {"value": "p2"}, {"value": null}, null, {}, {"value": "p1"}, {"value": "p2"}
        ]}))
        .unwrap();
        assert_eq!(q.user_consents.len(), 6);
        assert_eq!(q.consent_values(), ["p1", "p2"]);
        assert!(
            parse(json!({"expectedResultType": "COUNT", "userConsents": [{"value": "a", "x": 1}]}))
                .is_err()
        );
        let q = parse(json!({"expectedResultType": "COUNT", "userConsents": [
            {"value": 1}, {"value": true}, {"value": -7}
        ]}))
        .unwrap();
        assert_eq!(q.consent_values(), ["-7", "1", "true"]);
        for number in [
            "2.5",
            "1e2",
            "1.0",
            "1.50",
            "-0",
            "123456789012345678901234567890",
        ] {
            let text = format!(
                r#"{{"expectedResultType":"COUNT","userConsents":[{{"value":{number}}}]}}"#
            );
            let q = Query::from_json(text.as_bytes()).unwrap();
            assert_eq!(q.consent_values(), [number], "{number}");
        }
        assert!(
            parse(json!({"expectedResultType": "COUNT", "userConsents": [{"value": 2.5}]}))
                .is_err()
        );
        let q: Query = serde_json::from_str(
            r#"{"expectedResultType":"COUNT","userConsents":[{"value":12345678901234567890}]}"#,
        )
        .unwrap();
        assert_eq!(q.consent_values(), ["12345678901234567890"]);
        for bad in [json!({"value": [1]}), json!({"value": {"a": 1}})] {
            assert!(parse(json!({"expectedResultType": "COUNT", "userConsents": [bad]})).is_err());
        }
        let q = Query::with_consents(ResultType::Count, ["b", "a"]);
        assert_eq!(q.consent_values(), ["a", "b"]);
    }

    #[test]
    fn all_filters_flattens_depth_first() {
        let q = parse(json!({"expectedResultType": "COUNT", "phenotypicClause": {"operator": "AND", "phenotypicClauses": [
            {"operator": "OR", "phenotypicClauses": [
                {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"},
                {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\b\\"}]},
            {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\c\\"}]}}))
        .unwrap();
        let paths: Vec<_> = q
            .all_filters()
            .iter()
            .map(|f| f.concept_path.as_str())
            .collect();
        assert_eq!(paths, ["\\a\\", "\\b\\", "\\c\\"]);
    }

    fn refused(text: &str) -> String {
        match Query::from_json(text.as_bytes()) {
            Ok(q) => panic!("accepted {text}: {q:?}"),
            Err(e) => e.to_string(),
        }
    }

    #[test]
    fn duplicate_keys_are_refused_at_any_depth() {
        let filter = r#"{"phenotypicFilterType":"REQUIRED","conceptPath":"\\a\\"}"#;
        for text in [
            r#"{"expectedResultType":"COUNT","userConsents":[{"value":"a"}],"userConsents":[{"value":"b"}]}"#.to_owned(),
            r#"{"expectedResultType":"COUNT","userConsents":[{"value":"a","value":"b"}]}"#.to_owned(),
            r#"{"expectedResultType":"COUNT","expectedResultType":"COUNT"}"#.to_owned(),
            r#"{"expectedResultType":"COUNT","authorizationFilters":[{"conceptPath":"a","conceptPath":"b","values":["x"]}]}"#.to_owned(),
            r#"{"expectedResultType":"COUNT","phenotypicClause":{"phenotypicFilterType":"REQUIRED","conceptPath":"a","conceptPath":"b"}}"#.to_owned(),
            format!(
                r#"{{"expectedResultType":"COUNT","phenotypicClause":{{"operator":"AND","phenotypicClauses":[{filter},{{"operator":"OR","operator":"AND","phenotypicClauses":[{filter}]}}]}}}}"#
            ),
            r#"{"expectedResultType":"COUNT","phenotypicClause":{"operator":"AND","phenotypicClauses":[{"operator":"OR","phenotypicClauses":[{"phenotypicFilterType":"FILTER","conceptPath":"a","values":["x"],"values":["y"]}]}]}}"#.to_owned(),
        ] {
            let message = refused(&text);
            assert!(message.contains("duplicate"), "{text}: {message}");
        }
        let nested = format!(
            r#"{{"expectedResultType":"COUNT","userConsents":[{{"value":"a"}}],"phenotypicClause":{{"operator":"AND","phenotypicClauses":[{filter}]}}}}"#
        );
        assert_eq!(
            Query::from_json(nested.as_bytes())
                .unwrap()
                .consent_values(),
            ["a"]
        );
    }

    #[test]
    fn consent_field_names_match_exactly() {
        for name in [
            "userconsents",
            "UserConsents",
            "user_consents",
            "userConsent",
            "USERCONSENTS",
        ] {
            let text = format!(r#"{{"expectedResultType":"COUNT","{name}":[{{"value":"a"}}]}}"#);
            assert!(refused(&text).contains("unknown field"), "{name}");
        }
        for name in ["Value", "VALUE", "consent"] {
            let text =
                format!(r#"{{"expectedResultType":"COUNT","userConsents":[{{"{name}":"a"}}]}}"#);
            assert!(refused(&text).contains("unknown field"), "{name}");
        }
    }

    #[test]
    fn consents_nested_outside_the_top_level_are_refused() {
        for clause in [
            r#"{"phenotypicFilterType":"REQUIRED","conceptPath":"a","userConsents":[{"value":"a"}]}"#,
            r#"{"operator":"AND","phenotypicClauses":[{"phenotypicFilterType":"REQUIRED","conceptPath":"a"}],"userConsents":[{"value":"a"}]}"#,
        ] {
            let text = format!(r#"{{"expectedResultType":"COUNT","phenotypicClause":{clause}}}"#);
            refused(&text);
        }
        refused(
            r#"{"expectedResultType":"COUNT","authorizationFilters":[{"conceptPath":"a","values":["x"],"userConsents":[{"value":"a"}]}]}"#,
        );
        refused(
            r#"{"expectedResultType":"COUNT","genomicFilters":[{"key":"k","userConsents":[{"value":"a"}]}]}"#,
        );
    }

    #[test]
    fn non_standard_json_is_refused() {
        for text in [
            "\u{feff}{\"expectedResultType\":\"COUNT\"}",
            r#"{"expectedResultType":"COUNT" /* c */}"#,
            "{\"expectedResultType\":\"COUNT\"} // c",
            r#"{"expectedResultType":"COUNT",}"#,
            r#"{"expectedResultType":"COUNT","userConsents":[{"value":"a"},]}"#,
            r#"{"expectedResultType":"COUNT","phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"a","min":NaN}}"#,
            r#"{"expectedResultType":"COUNT","phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"a","min":Infinity}}"#,
            r#"{'expectedResultType':'COUNT'}"#,
        ] {
            refused(text);
            assert!(parse_strict(text.as_bytes()).is_err(), "{text}");
        }
    }

    /// The genomic bounds of `query`'s only genomic filter as float bits, read from a request
    /// body holding `query` as an object and from `query` sent as a string.
    fn genomic_bounds(query: &str) -> [(Option<u32>, Option<u32>); 2] {
        let bits = |q: Query| {
            let f = &q.genomic_filters[0];
            (f.min.map(f32::to_bits), f.max.map(f32::to_bits))
        };
        let envelope = parse_request_body(format!(r#"{{"query":{query}}}"#).as_bytes()).unwrap();
        let object: Query = serde_json::from_value(envelope["query"].clone()).unwrap();
        [
            bits(object),
            bits(Query::from_json(query.as_bytes()).unwrap()),
        ]
    }

    /// Mirrors probes sent to the harmonization service: a decimal near the
    /// midpoint above the stored key `0x39ae6e3d` (`0.0003327`) reads as the key in an object
    /// query, through the double's `Double.toString`, and as the float above it in a string
    /// query or as a string; just below the midpoint it reads as the key everywhere.
    #[test]
    fn genomic_bounds_read_as_java_floats_on_both_paths() {
        let key = 0x39ae_6e3d_u32;
        let above = "0.0003327000158606097102165222167968750001";
        let below = "0.0003327000158606097102165222167968749999";
        let filter = |min: &str, max: &str| {
            format!(
                r#"{{"expectedResultType":"COUNT","genomicFilters":[{{"key":"Variant_frequency_in_gnomAD","min":{min},"max":{max}}}]}}"#
            )
        };
        assert_eq!(
            genomic_bounds(&filter(above, "1")),
            [
                (Some(key), Some(1.0_f32.to_bits())),
                (Some(key + 1), Some(1.0_f32.to_bits()))
            ]
        );
        assert_eq!(
            genomic_bounds(&filter(below, "-0")),
            [(Some(key), Some(0)), (Some(key), Some(0))]
        );
        let quoted = format!("\"{above}\"");
        assert_eq!(
            genomic_bounds(&filter(&quoted, "-0.0")),
            [
                (Some(key + 1), Some(0x8000_0000)),
                (Some(key + 1), Some(0x8000_0000))
            ]
        );
        let infinity = f32::INFINITY.to_bits();
        for (min, max) in [
            ("1e39", "1e309"),
            ("\"Infinity\"", "340282357000000000000000000000000000000"),
        ] {
            assert_eq!(
                genomic_bounds(&filter(min, max)),
                [(Some(infinity), Some(infinity)); 2],
                "{min} {max}"
            );
        }
        assert_eq!(
            genomic_bounds(&filter("16777217", "null")),
            [(Some(16_777_216.0_f32.to_bits()), None); 2]
        );
        let text = r#"{"expectedResultType":"COUNT","phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"a","min":0.1},"genomicFilters":[{"key":"k","min":0.1}]}"#;
        let q = Query::from_json(text.as_bytes()).unwrap();
        let Some(PhenotypicClause::Filter(f)) = &q.phenotypic_clause else {
            panic!("{q:?}")
        };
        assert_eq!(f.min, Some(0.1_f64), "a phenotypic bound stays a double");
        assert_eq!(q.genomic_filters[0].min, Some(0.1_f32));
        refused(r#"{"expectedResultType":"COUNT","genomicFilters":[{"key":"k","min":"1f"}]}"#);
    }

    #[test]
    fn strict_parse_refuses_duplicates_in_the_envelope_and_the_string_query() {
        let inner = r#"{"expectedResultType":"COUNT","userConsents":[{"value":"a"}]}"#;
        assert!(
            parse_strict(format!(r#"{{"query":{inner},"query":{inner}}}"#).as_bytes()).is_err()
        );
        assert!(
            parse_strict(br#"{"query":{"userConsents":[],"userConsents":[{"value":"a"}]}}"#)
                .is_err()
        );
        assert!(parse_strict(br#"{"query":{}} {}"#).is_err());
        let as_object = parse_strict(format!(r#"{{"query":{inner}}}"#).as_bytes()).unwrap();
        let as_string =
            parse_strict(format!(r#"{{"query":{}}}"#, Value::String(inner.to_owned())).as_bytes())
                .unwrap();
        let from_object: Query = serde_json::from_value(as_object["query"].clone()).unwrap();
        let from_string =
            Query::from_json(as_string["query"].as_str().unwrap().as_bytes()).unwrap();
        assert_eq!(from_object, from_string);
        let duplicated =
            r#"{"expectedResultType":"COUNT","userConsents":[{"value":"a"}],"userConsents":[]}"#;
        let envelope = parse_strict(
            format!(r#"{{"query":{}}}"#, Value::String(duplicated.to_owned())).as_bytes(),
        )
        .unwrap();
        assert!(Query::from_json(envelope["query"].as_str().unwrap().as_bytes()).is_err());
    }
}
