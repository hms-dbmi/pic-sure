//! `QueryValidator` and `PhenotypicFilterValidator`, plus the check that refuses the
//! genomic features this engine does not implement.
//!
//! Java runs `QueryValidator` only when an asynchronous query is submitted (`POST /query`).
//! `/query/sync` validates nothing, but the store still refuses a query without consent
//! values when `hpds.requireAuthorizationFilter` is true; [`crate::Engine::check_consents`]
//! is that check on its own.

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

/// Refuses what a phenotype-only engine cannot answer: a genomic result type, a non-empty
/// `genomicFilters`, or a variant-spec path ([`path_is_variant_spec`]) in `select` or in a
/// filter. Authorization filters are never applied, so their paths are not checked here.
///
/// # Errors
/// [`QueryError::Unsupported`] naming the first unsupported feature found.
pub fn check_supported(query: &Query) -> Result<()> {
    if query.expected_result_type.is_genomic() {
        return Err(QueryError::Unsupported(format!(
            "result type {}",
            query.expected_result_type
        )));
    }
    if !query.genomic_filters.is_empty() {
        return Err(QueryError::Unsupported("genomicFilters".into()));
    }
    let paths = query.select.iter().map(String::as_str).chain(
        query
            .all_filters()
            .into_iter()
            .map(|f| f.concept_path.as_str()),
    );
    for path in paths {
        if path_is_variant_spec(path) {
            return Err(QueryError::Unsupported(format!(
                "variant-spec concept path {path:?}"
            )));
        }
    }
    Ok(())
}

/// `QueryValidator` with `PhenotypicFilterValidator`, plus [`check_supported`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct QueryValidator {
    /// `hpds.requireAuthorizationFilter`: refuse a query whose `userConsents` is empty.
    pub require_authorization_filter: bool,
}

impl Default for QueryValidator {
    /// Requires user consents, the Java default.
    fn default() -> Self {
        QueryValidator {
            require_authorization_filter: true,
        }
    }
}

impl QueryValidator {
    /// Validates `query` against `concepts` in Java's order: unsupported features first,
    /// then the consent requirement, then every filter of the phenotypic clause, then every
    /// (deprecated, never applied) authorization filter. Unknown `select` paths are allowed
    /// (Java only logs them).
    ///
    /// The consent requirement is Java's `userConsents().isEmpty()`: a list holding only
    /// null values passes here and is refused when the query runs
    /// ([`QueryError::UserConsentsRequired`]).
    ///
    /// # Errors
    /// [`QueryError::Unsupported`] from [`check_supported`], and [`QueryError::Invalid`]
    /// with Java's message for the first rule broken.
    pub fn validate(&self, query: &Query, concepts: &impl ConceptLookup) -> Result<()> {
        check_supported(query)?;
        if self.require_authorization_filter && query.user_consents.is_empty() {
            return Err(QueryError::Invalid(
                "User consents are required for this query".into(),
            ));
        }
        for filter in query.all_filters() {
            validate_filter(filter, concepts)?;
        }
        for filter in &query.authorization_filters {
            validate_authorization_filter(filter, concepts)?;
        }
        Ok(())
    }
}

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
            if has_bound && filter.values.as_ref().is_some_and(|v| !v.is_empty()) {
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

/// `PhenotypicFilterValidator.validate(AuthorizationFilter, metaStore)`: values must be
/// non-empty and the concept must exist and be categorical.
///
/// # Errors
/// [`QueryError::Invalid`] with Java's message.
pub fn validate_authorization_filter(
    filter: &AuthorizationFilter,
    concepts: &impl ConceptLookup,
) -> Result<()> {
    if filter.values.as_ref().is_none_or(|v| v.is_empty()) {
        return Err(QueryError::Invalid(
            "AuthorizationFilter values cannot be null or empty".into(),
        ));
    }
    let path = &filter.concept_path;
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
