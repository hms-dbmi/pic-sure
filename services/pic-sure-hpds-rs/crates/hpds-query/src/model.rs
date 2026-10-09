//! The v3 query model, deserialized the way the Java service's plain Jackson `ObjectMapper`
//! reads `edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query`.
//!
//! Rules carried over from Jackson:
//!
//! - Unknown fields anywhere inside the query are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`).
//!   The two `@JsonIgnore` properties of `PhenotypicFilter`, `categoricalFilter` and
//!   `numericFilter`, are accepted and ignored.
//! - A [`PhenotypicClause`] is chosen by deduction from the field names present, as
//!   `@JsonTypeInfo(use = DEDUCTION)` does: `phenotypicClauses` or `operator` make it a
//!   subquery, `phenotypicFilterType`, `conceptPath`, `values`, `min` or `max` make it a
//!   filter. An object with fields of both kinds, or with neither (`{}` or only `not`), is
//!   rejected, as Jackson rejects it either while deducing or while binding.
//! - `null` or missing lists (`select`, `authorizationFilters`, `genomicFilters`,
//!   `phenotypicClauses`) are empty lists, as the record accessors return them.
//! - Doubles accept JSON numbers and strings (`"5"`, `"NaN"`, `"Infinity"`), and an empty
//!   string is `null`, as Jackson's scalar coercion does. Booleans accept `true`, `false`
//!   and the strings `"true"` and `"false"`.
//! - Enums accept their exact Java name or their ordinal as a JSON integer.
//! - UUIDs must be in the canonical 36-character form and are kept in lowercase.
//!
//! Where Jackson would accept a value that only fails later (a missing
//! `expectedResultType`, `phenotypicFilterType` or `conceptPath`, a `null` inside a string
//! list), deserialization fails here instead.

use std::fmt;

use serde::de::{self, Deserializer, Visitor};
use serde::{Deserialize, Serialize, Serializer};
use serde_json::{Map, Value};

use hpds_format::parse_java_double;

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
                        $name::from_java_name(value)
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
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct Query {
    /// Concept paths to return, for the result types that return fields.
    #[serde(default, deserialize_with = "null_as_default")]
    pub select: Vec<String>,
    /// Consent filters, each ANDed with the phenotypic clause.
    #[serde(default, deserialize_with = "null_as_default")]
    pub authorization_filters: Vec<AuthorizationFilter>,
    /// The filter tree, or `None` for no filter.
    #[serde(default)]
    pub phenotypic_clause: Option<PhenotypicClause>,
    /// Variant filters. Always unsupported here when non-empty.
    #[serde(default, deserialize_with = "null_as_default")]
    pub genomic_filters: Vec<GenomicFilter>,
    /// What the query returns.
    pub expected_result_type: ResultType,
    /// An externally assigned UUID, lowercase.
    #[serde(default, deserialize_with = "java_uuid")]
    pub picsure_id: Option<String>,
    /// An internally assigned UUID, lowercase.
    #[serde(default, deserialize_with = "java_uuid")]
    pub id: Option<String>,
}

impl Query {
    /// A query of `result_type` with no select, no filters and no ids.
    pub fn new(result_type: ResultType) -> Self {
        Query {
            select: Vec::new(),
            authorization_filters: Vec::new(),
            phenotypic_clause: None,
            genomic_filters: Vec::new(),
            expected_result_type: result_type,
            picsure_id: None,
            id: None,
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
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AuthorizationFilter {
    /// The categorical concept to match.
    pub concept_path: String,
    /// Accepted values; `None` when the JSON held `null` or omitted it.
    #[serde(default)]
    pub values: Option<Vec<String>>,
}

/// `v3.GenomicFilter`. Parsed so that the query is accepted as Java accepts it, then
/// refused as unsupported when present.
#[derive(Debug, Clone, PartialEq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct GenomicFilter {
    /// The variant annotation key.
    #[serde(default)]
    pub key: Option<String>,
    /// Values to match.
    #[serde(default)]
    pub values: Option<Vec<String>>,
    /// Lower bound.
    #[serde(default, deserialize_with = "java_double")]
    pub min: Option<f64>,
    /// Upper bound.
    #[serde(default, deserialize_with = "java_double")]
    pub max: Option<f64>,
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
    /// The concept path.
    pub concept_path: String,
    /// Values to match, for `FILTER`; `None` when null or absent.
    #[serde(default)]
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
        }
    }

    /// `PhenotypicFilter.isCategoricalFilter`: a `FILTER` with a non-empty `values`.
    pub fn is_categorical_filter(&self) -> bool {
        self.phenotypic_filter_type == PhenotypicFilterType::Filter
            && self.values.as_ref().is_some_and(|v| !v.is_empty())
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
        let mut fields = Map::<String, Value>::deserialize(deserializer)?;
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
            (true, true) => Err(de::Error::custom(
                "a phenotypic clause cannot mix subquery fields (phenotypicClauses, operator) \
                 with filter fields (phenotypicFilterType, conceptPath, values, min, max)",
            )),
            (false, false) => Err(de::Error::custom(
                "cannot deduce the phenotypic clause type: expected phenotypicClauses or \
                 operator for a subquery, or phenotypicFilterType, conceptPath, values, min or \
                 max for a filter",
            )),
        }
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
        match value {
            "" => Ok(None),
            "INF" => Ok(Some(f64::INFINITY)),
            "-INF" => Ok(Some(f64::NEG_INFINITY)),
            _ => parse_java_double(value)
                .map(Some)
                .ok_or_else(|| E::invalid_value(de::Unexpected::Str(value), &self)),
        }
    }
}

fn java_double<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<f64>, D::Error> {
    deserializer.deserialize_any(JavaDouble)
}

struct JavaBoolean;

impl<'de> Visitor<'de> for JavaBoolean {
    type Value = Option<bool>;

    fn expecting(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("a boolean, \"true\", \"false\", or null")
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

    fn visit_str<E: de::Error>(self, value: &str) -> Result<Self::Value, E> {
        match value {
            "true" => Ok(Some(true)),
            "false" => Ok(Some(false)),
            "" => Ok(None),
            _ => Err(E::invalid_value(de::Unexpected::Str(value), &self)),
        }
    }
}

fn java_boolean<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<bool>, D::Error> {
    deserializer.deserialize_any(JavaBoolean)
}

/// True when `value` is a UUID in the canonical `8-4-4-4-12` hexadecimal form.
fn is_canonical_uuid(value: &str) -> bool {
    value.len() == 36
        && value.char_indices().all(|(i, c)| {
            if matches!(i, 8 | 13 | 18 | 23) {
                c == '-'
            } else {
                c.is_ascii_hexdigit()
            }
        })
}

fn java_uuid<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Option<String>, D::Error> {
    match Option::<String>::deserialize(deserializer)? {
        None => Ok(None),
        Some(value) if is_canonical_uuid(&value) => Ok(Some(value.to_ascii_lowercase())),
        Some(value) => Err(de::Error::custom(format!(
            "{value:?} is not a UUID in the canonical 8-4-4-4-12 form"
        ))),
    }
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
    fn uuids_must_be_canonical() {
        let q = parse(json!({"expectedResultType": "COUNT", "picsureId": "8694E3D4-5CB4-410F-8431-993445E6D3F6"})).unwrap();
        assert_eq!(
            q.picsure_id.as_deref(),
            Some("8694e3d4-5cb4-410f-8431-993445e6d3f6")
        );
        assert!(parse(json!({"expectedResultType": "COUNT", "id": "1-2-3-4-5"})).is_err());
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
}
