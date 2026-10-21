//! `QueryValidator` and `PhenotypicFilterValidator`, plus the check that refuses the
//! genomic result types this engine does not compute.
//!
//! Java runs `QueryValidator` only when an asynchronous query is submitted (`POST /query`).
//! `/query/sync` validates nothing, but the store still refuses a query without consent
//! values when `hpds.requireAuthorizationFilter` is true; [`crate::Engine::check_consents`]
//! is that check on its own. [`QueryValidator`] is the harmonization branch's validator,
//! [`MainQueryValidator`] Java main's, for the main consent contract.

use std::collections::{BTreeMap, HashMap};

use hpds_format::{ConceptType, PhenoIndex};

use crate::error::{QueryError, Result};
use crate::model::{AuthorizationFilter, PhenotypicFilter, PhenotypicFilterType, Query};

/// Whether a concept path exists and what type it has: the part of Java's metastore
/// (`Map<String, ColumnMeta>`) the validators read.
pub trait ConceptLookup {
    /// The type of the concept at exactly `path`, or `None` when there is no such concept.
    fn concept_type(&self, path: &str) -> Option<ConceptType>;
}

impl ConceptLookup for PhenoIndex {
    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.get(path).map(|c| c.concept_type)
    }
}

impl ConceptLookup for HashMap<String, ConceptType> {
    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.get(path).copied()
    }
}

impl ConceptLookup for BTreeMap<String, ConceptType> {
    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.get(path).copied()
    }
}

/// `VariantUtils.pathIsVariantSpec`: true when `key` fully matches one of
/// `rs[0-9]+.*`, `.*,[0-9\\.]+,[CATGcatg]*,[CATGcatg]*` or
/// `.*,[0-9\\.]+,[CATGcatg]*,[CATGcatg]*,[\w\d_]*,[\w\d_]*`, with Java regex semantics
/// (`.` does not match line terminators, `\w` is ASCII).
pub fn path_is_variant_spec(key: &str) -> bool {
    rs_number(key) || comma_spec(key, false) || comma_spec(key, true)
}

fn is_java_line_terminator(c: char) -> bool {
    matches!(c, '\n' | '\r' | '\u{85}' | '\u{2028}' | '\u{2029}')
}

fn rs_number(key: &str) -> bool {
    let Some(rest) = key.strip_prefix("rs") else {
        return false;
    };
    let mut chars = rest.chars();
    chars.next().is_some_and(|c| c.is_ascii_digit()) && !chars.any(is_java_line_terminator)
}

fn comma_spec(key: &str, with_annotations: bool) -> bool {
    let fields = if with_annotations { 5 } else { 3 };
    let mut parts = key.rsplitn(fields + 1, ',');
    let mut suffix: Vec<&str> = parts.by_ref().take(fields).collect();
    let Some(prefix) = parts.next() else {
        return false;
    };
    if suffix.len() != fields || prefix.chars().any(is_java_line_terminator) {
        return false;
    }
    suffix.reverse();
    let position = suffix[0];
    let bases = |s: &str| s.chars().all(|c| "CATGcatg".contains(c));
    let word = |s: &str| s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_');
    !position.is_empty()
        && position
            .chars()
            .all(|c| c.is_ascii_digit() || c == '\\' || c == '.')
        && bases(suffix[1])
        && bases(suffix[2])
        && (!with_annotations || (word(suffix[3]) && word(suffix[4])))
}

/// `QueryValidator` with `PhenotypicFilterValidator`. Genomic filters and result types are
/// not its concern, as in Java; a variant-spec path ([`path_is_variant_spec`]) in `select` or
/// in a phenotypic filter is a concept path like any other: an empty DATAFRAME column, a
/// concept no partition holds.
/// [`crate::Engine::validate`] builds one from its partition set's flag; build one directly
/// only to validate against another [`ConceptLookup`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct QueryValidator {
    /// The Java property `hpds.requireAuthorizationFilter`: refuse a query whose
    /// `userConsents` is empty.
    pub require_consents: bool,
}

impl Default for QueryValidator {
    /// Requires user consents, the Java default.
    fn default() -> Self {
        QueryValidator {
            require_consents: true,
        }
    }
}

impl QueryValidator {
    /// Validates `query` against `concepts` in Java's order: the consent requirement, then every filter of the phenotypic clause, then every
    /// (deprecated, never applied) authorization filter. Unknown `select` paths are allowed
    /// (Java only logs them).
    ///
    /// The consent requirement is Java's `userConsents().isEmpty()`: a list holding only
    /// null values passes here and is refused when the query runs
    /// ([`QueryError::UserConsentsRequired`]).
    ///
    /// # Errors
    /// [`QueryError::Invalid`] with Java's message for the first rule broken.
    pub fn validate(&self, query: &Query, concepts: &impl ConceptLookup) -> Result<()> {
        if self.require_consents && query.user_consents.is_empty() {
            return Err(QueryError::Invalid(
                "User consents are required for this query".into(),
            ));
        }
        for filter in query.all_filters() {
            validate_filter(filter, concepts)?;
        }
        if query.null_authorization_filters > 0 {
            return Err(QueryError::Invalid(
                NULL_AUTHORIZATION_FILTER_MESSAGE.into(),
            ));
        }
        for filter in &query.authorization_filters {
            validate_authorization_filter(filter, concepts)?;
        }
        Ok(())
    }
}

/// Java main's `QueryValidator` with `PhenotypicFilterValidator`, for the main consent
/// contract, where `POST /query` runs it before the job starts.
///
/// [`crate::Engine::validate`] builds one from its partition set's flag under the main
/// contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MainQueryValidator {
    /// The Java property `hpds.requireAuthorizationFilter`: refuse a query whose
    /// `authorizationFilters` is empty.
    pub require_authorization_filter: bool,
}

impl MainQueryValidator {
    /// Validates `query` against `concepts` in Java main's order: the requirement (with the
    /// flag on, an empty `authorizationFilters`, where `[null]` is not empty), then every
    /// filter of the phenotypic clause ([`validate_filter`]), then every authorization
    /// filter in request order ([`validate_main_authorization_filter`]). Unknown `select`
    /// paths are allowed (Java only logs them).
    ///
    /// Java ends the job in ERROR for every `IllegalArgumentException` its validator throws,
    /// and creates no job when another exception escapes it: a `null` authorization filter or
    /// a `null` `conceptPath` there. The two are told apart by the error.
    ///
    /// # Errors
    /// [`QueryError::Invalid`] with Java's message for the first rule broken, where Java ends
    /// the job in ERROR, and [`QueryError::AuthorizationFilterNull`] for a `null` filter or
    /// path reached first, where Java creates no job.
    pub fn validate(&self, query: &Query, concepts: &impl ConceptLookup) -> Result<()> {
        if self.require_authorization_filter && !query.has_authorization_filters() {
            return Err(QueryError::Invalid(
                AUTHORIZATION_FILTER_REQUIRED_JAVA_MESSAGE.into(),
            ));
        }
        for filter in query.all_filters() {
            validate_filter(filter, concepts)?;
        }
        for (position, filter) in query.authorization_filters.iter().enumerate() {
            if query.first_null_authorization_filter == Some(position) {
                return Err(QueryError::AuthorizationFilterNull(
                    NULL_AUTHORIZATION_FILTER_MESSAGE,
                ));
            }
            validate_main_authorization_filter(filter, concepts)?;
        }
        if query.first_null_authorization_filter.is_some() {
            return Err(QueryError::AuthorizationFilterNull(
                NULL_AUTHORIZATION_FILTER_MESSAGE,
            ));
        }
        Ok(())
    }
}

/// The message of Java main's validator for an empty `authorizationFilters` while
/// `hpds.requireAuthorizationFilter` is on.
pub const AUTHORIZATION_FILTER_REQUIRED_JAVA_MESSAGE: &str =
    "Authorization filter is required for this query";

/// Java main's `PhenotypicFilterValidator.validate(AuthorizationFilter, metaStore)`: values
/// must be non-empty (a list holding only `null` counts as non-empty, as in Java), and the
/// concept must exist and be categorical. A `null` concept path is refused with
/// [`NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE`] as [`QueryError::AuthorizationFilterNull`],
/// where Java's lookup of it in the dictionary throws.
///
/// # Errors
/// [`QueryError::Invalid`] with Java's message, or [`QueryError::AuthorizationFilterNull`].
pub fn validate_main_authorization_filter(
    filter: &AuthorizationFilter,
    concepts: &impl ConceptLookup,
) -> Result<()> {
    match validate_authorization_filter(filter, concepts) {
        Err(QueryError::Invalid(message)) if message == NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE => {
            Err(QueryError::AuthorizationFilterNull(
                NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
            ))
        }
        other => other,
    }
}

/// Refusal, under the main contract, of an authorization filter whose `values` is `null` or
/// missing, where Java main's executor throws "Either values or one of min/max must be set
/// for a filter".
pub const NULL_AUTHORIZATION_VALUES_MESSAGE: &str = "an authorization filter's values are null";

/// Refusal, under the main contract, of an authorization filter whose `values` hold `null` on
/// a categorical concept, where Java main's executor looks the `null` up in the concept's
/// category map, which throws.
pub const NULL_AUTHORIZATION_VALUE_MESSAGE: &str = "an authorization filter's values hold null";

/// `PhenotypicFilterValidator.validate(PhenotypicFilter, metaStore)`.
///
/// - `FILTER`: refuses both values (non-empty) and a bound, then, when the concept exists,
///   values on a numeric concept and bounds on a categorical one. An unknown concept is
///   allowed.
/// - `REQUIRED` and `ANY_RECORD_OF`: refuse `values` (even empty) and bounds.
///
/// # Errors
/// [`QueryError::Invalid`] with Java's message.
pub fn validate_filter(filter: &PhenotypicFilter, concepts: &impl ConceptLookup) -> Result<()> {
    let path = &filter.concept_path;
    let has_bound = filter.min.is_some() || filter.max.is_some();
    match filter.phenotypic_filter_type {
        PhenotypicFilterType::Filter => {
            if has_bound && filter.is_categorical_filter() {
                return Err(QueryError::Invalid(format!(
                    "Filter with concept path {path} cannot have both categorical values and min/max set"
                )));
            }
            match concepts.concept_type(path) {
                None => Ok(()),
                Some(ConceptType::Numeric) if filter.is_categorical_filter() => Err(
                    QueryError::Invalid(format!("{path} is not a categorical variable")),
                ),
                Some(ConceptType::Categorical) if filter.is_numeric_filter() => Err(
                    QueryError::Invalid(format!("{path} is not a numeric variable")),
                ),
                Some(_) => Ok(()),
            }
        }
        PhenotypicFilterType::Required | PhenotypicFilterType::AnyRecordOf => {
            if has_bound || filter.values.is_some() {
                Err(QueryError::Invalid(format!(
                    "Required filter with concept path {path} cannot have categorical values or min/max set"
                )))
            } else {
                Ok(())
            }
        }
    }
}

/// Refusal, on `POST /query`, of an `authorizationFilters` list holding `null`
/// ([`Query::null_authorization_filters`]). Java's validator throws on it.
pub const NULL_AUTHORIZATION_FILTER_MESSAGE: &str = "an authorization filter is null";

/// Refusal of an authorization filter whose `conceptPath` is `null` on `POST /query`.
pub const NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE: &str =
    "an authorization filter's conceptPath is null";

/// `PhenotypicFilterValidator.validate(AuthorizationFilter, metaStore)`: values must be
/// non-empty (a list holding only `null` counts as non-empty, as in Java) and the concept
/// must exist and be categorical. A `null` concept path is refused with
/// [`NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE`], where Java's lookup of it in the dictionary
/// throws.
///
/// # Errors
/// [`QueryError::Invalid`] with Java's message, or with
/// [`NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE`].
pub fn validate_authorization_filter(
    filter: &AuthorizationFilter,
    concepts: &impl ConceptLookup,
) -> Result<()> {
    if filter.values.as_ref().is_none_or(|v| v.is_empty()) {
        return Err(QueryError::Invalid(
            "AuthorizationFilter values cannot be null or empty".into(),
        ));
    }
    let Some(path) = &filter.concept_path else {
        return Err(QueryError::Invalid(
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE.into(),
        ));
    };
    match concepts.concept_type(path) {
        None => Err(QueryError::Invalid(format!(
            "{path} is not a valid concept path"
        ))),
        Some(ConceptType::Numeric) => Err(QueryError::Invalid(format!(
            "{path} is not a categorical variable. Authorization filters must be categorical"
        ))),
        Some(ConceptType::Categorical) => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn variant_specs_match_java_patterns() {
        for spec in [
            "rs123",
            "rs1abc",
            "chr1,12345,A,G",
            "1,12345,A,",
            "x,y,1.2,ACGT,tg",
            "4,9856624,CAAAAA,C,TVP23A,splice_acceptor_variant",
            "4,98\\56,,,a_1,",
        ] {
            assert!(path_is_variant_spec(spec), "{spec}");
        }
        for path in [
            "\\demographics\\AGE\\",
            "rs",
            "rsX1",
            "rs1\nx",
            "chr1,,A,G",
            "chr1,12345,A,N",
            "chr1,12345,A",
            "a\n,1,A,G",
            "4,98,C,C,a-b,x",
            "",
        ] {
            assert!(!path_is_variant_spec(path), "{path:?}");
        }
    }
}
