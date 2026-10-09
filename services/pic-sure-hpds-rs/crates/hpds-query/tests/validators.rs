//! Port of `QueryValidatorTest` and `PhenotypicFilterValidatorTest`.

mod common;

use std::collections::BTreeMap;

use common::*;
use hpds_format::ConceptType;
use hpds_query::validate::{validate_authorization_filter, validate_filter};
use hpds_query::{
    AuthorizationFilter, Operator, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType,
    QueryError, QueryValidator, ResultType,
};

const SEX: &str = "\\study123\\demographics\\sex\\";
const AGE: &str = "\\study123\\demographics\\age\\";
const NOT_REAL: &str = "\\study123\\demographics\\not_a_real_concept\\";

fn meta() -> BTreeMap<String, ConceptType> {
    BTreeMap::from([
        (SEX.to_owned(), ConceptType::Categorical),
        (AGE.to_owned(), ConceptType::Numeric),
    ])
}

fn filter(
    filter_type: PhenotypicFilterType,
    path: &str,
    values: Option<&[&str]>,
    min: Option<f64>,
    max: Option<f64>,
) -> PhenotypicFilter {
    PhenotypicFilter {
        values: values.map(|v| v.iter().map(|s| (*s).to_owned()).collect()),
        min,
        max,
        ..PhenotypicFilter::new(filter_type, path)
    }
}

fn auth(path: &str, values: Option<&[&str]>) -> AuthorizationFilter {
    AuthorizationFilter {
        concept_path: path.into(),
        values: values.map(|v| v.iter().map(|s| (*s).to_owned()).collect()),
    }
}

fn invalid(result: hpds_query::Result<()>) -> String {
    match result {
        Err(QueryError::Invalid(message)) => message,
        other => panic!("expected Invalid, got {other:?}"),
    }
}

use PhenotypicFilterType::{AnyRecordOf, Filter, Required};

#[test]
fn filter_validator_accepts_valid_filters() {
    let m = meta();
    for f in [
        filter(Filter, SEX, Some(&["male"]), None, None),
        filter(Filter, NOT_REAL, Some(&["male"]), None, None),
        filter(Filter, AGE, None, Some(20.0), Some(25.0)),
        filter(Filter, AGE, None, Some(20.0), None),
        filter(Filter, AGE, None, None, Some(45.0)),
        filter(Filter, NOT_REAL, None, Some(20.0), None),
        filter(Filter, NOT_REAL, Some(&[]), Some(1.0), None),
        filter(AnyRecordOf, "\\study123\\", None, None, None),
        filter(Required, "\\study123\\", None, None, None),
    ] {
        validate_filter(&f, &m).unwrap_or_else(|e| panic!("{f:?}: {e}"));
    }
}

#[test]
fn filter_validator_rejects_type_mismatches() {
    let m = meta();
    assert_eq!(
        invalid(validate_filter(
            &filter(Filter, AGE, Some(&["male"]), None, None),
            &m
        )),
        format!("{AGE} is not a categorical variable")
    );
    assert_eq!(
        invalid(validate_filter(
            &filter(Filter, SEX, None, None, Some(20.0)),
            &m
        )),
        format!("{SEX} is not a numeric variable")
    );
}

#[test]
fn filter_validator_rejects_values_with_bounds() {
    assert_eq!(
        invalid(validate_filter(
            &filter(Filter, SEX, Some(&["male"]), None, Some(20.0)),
            &meta()
        )),
        format!(
            "Filter with concept path {SEX} cannot have both categorical values and min/max set"
        )
    );
}

#[test]
fn any_record_of_and_required_reject_values_and_bounds() {
    let m = meta();
    for filter_type in [AnyRecordOf, Required] {
        for f in [
            filter(filter_type, "\\study123\\", Some(&["purple"]), None, None),
            filter(filter_type, "\\study123\\", Some(&[]), None, None),
            filter(filter_type, "\\study123\\", None, Some(42.0), None),
        ] {
            assert_eq!(
                invalid(validate_filter(&f, &m)),
                "Required filter with concept path \\study123\\ cannot have categorical values or min/max set"
            );
        }
    }
}

#[test]
fn authorization_filter_rules() {
    let m = meta();
    validate_authorization_filter(&auth(SEX, Some(&["male"])), &m).unwrap();
    for values in [Some(&[][..]), None] {
        assert_eq!(
            invalid(validate_authorization_filter(&auth(SEX, values), &m)),
            "AuthorizationFilter values cannot be null or empty"
        );
    }
    assert_eq!(
        invalid(validate_authorization_filter(
            &auth(AGE, Some(&["male"])),
            &m
        )),
        format!("{AGE} is not a categorical variable. Authorization filters must be categorical")
    );
    assert_eq!(
        invalid(validate_authorization_filter(
            &auth(NOT_REAL, Some(&["x"])),
            &m
        )),
        format!("{NOT_REAL} is not a valid concept path")
    );
}

fn clause(f: PhenotypicFilter) -> PhenotypicClause {
    PhenotypicClause::Filter(f)
}

const LENIENT: QueryValidator = QueryValidator {
    require_authorization_filter: false,
};

#[test]
fn query_validator_accepts_empty_and_valid_queries() {
    let m = meta();
    LENIENT
        .validate(&query(ResultType::Count, None), &m)
        .unwrap();
    let sex = clause(filter(Filter, SEX, Some(&["male"]), None, None));
    LENIENT
        .validate(&query(ResultType::Count, Some(sex.clone())), &m)
        .unwrap();
    let age = clause(filter(Filter, AGE, None, Some(42.0), None));
    let inner = subquery(Operator::And, vec![sex, age]);
    let nested = subquery(Operator::Or, vec![inner.clone(), inner]);
    LENIENT
        .validate(&query(ResultType::Count, Some(nested)), &m)
        .unwrap();
}

#[test]
fn query_validator_rejects_invalid_filters_at_any_depth() {
    let m = meta();
    let bad = clause(filter(Filter, AGE, Some(&["male"]), None, None));
    assert!(
        LENIENT
            .validate(&query(ResultType::Count, Some(bad.clone())), &m)
            .is_err()
    );
    let age = clause(filter(Filter, AGE, None, Some(42.0), None));
    let inner = subquery(Operator::And, vec![bad, age]);
    let nested = subquery(Operator::Or, vec![inner.clone(), inner]);
    assert!(
        LENIENT
            .validate(&query(ResultType::Count, Some(nested)), &m)
            .is_err()
    );
}

#[test]
fn query_validator_requires_authorization_filter_by_default() {
    let m = meta();
    let strict = QueryValidator::default();
    assert!(strict.require_authorization_filter);
    let sex = clause(filter(Filter, SEX, Some(&["male"]), None, None));
    let mut q = query(ResultType::Count, Some(sex));
    assert_eq!(
        invalid(strict.validate(&q, &m)),
        "Authorization filter is required for this query"
    );
    q.authorization_filters = vec![auth(SEX, Some(&["studyABC"]))];
    strict.validate(&q, &m).unwrap();
    q.authorization_filters = vec![auth("\\_consent\\", Some(&["studyABC"]))];
    assert!(strict.validate(&q, &m).is_err());
}

#[test]
fn query_validator_refuses_genomic_input() {
    let m = meta();
    let mut q = query(ResultType::Count, None);
    q.select = vec!["rs12345".into()];
    assert!(matches!(
        LENIENT.validate(&q, &m),
        Err(QueryError::Unsupported(_))
    ));
    let q = query(
        ResultType::Count,
        Some(clause(filter(Required, "chr1,12345,A,G", None, None, None))),
    );
    assert!(matches!(
        LENIENT.validate(&q, &m),
        Err(QueryError::Unsupported(_))
    ));
    for result_type in ResultType::ALL.iter().filter(|r| r.is_genomic()) {
        let q = query(*result_type, None);
        assert!(matches!(
            LENIENT.validate(&q, &m),
            Err(QueryError::Unsupported(_))
        ));
    }
}
