//! Port of `PhenotypicQueryExecutorTest`, against stores built by the Rust ETL instead of
//! mocks, plus the evaluation rules the Java test does not cover.

mod common;

use common::*;
use hpds_query::{
    AuthorizationFilter, Operator, PhenotypicClause, PhenotypicSubquery, QueryError, ResultType,
};

const POPULATION: &str = "\\open_access-1000Genomes\\data\\POPULATION NAME\\";
const SEX: &str = "\\open_access-1000Genomes\\data\\SEX\\";
const AGE: &str = "\\open_access-1000Genomes\\data\\SYNTHETIC_AGE\\";
const HEIGHT: &str = "\\open_access-1000Genomes\\data\\SYNTHETIC_HEIGHT\\";
const OTHER_AGE: &str = "\\synthea\\data\\SYNTHETIC_AGE\\";
const MISSING: &str = "\\open_access-1000Genomes\\data\\NOT_A_CONCEPT_PATH\\";

fn genomes() -> TestStore {
    store(&[
        (3, POPULATION, None, Some("Finnish")),
        (5, POPULATION, None, Some("Finnish")),
        (8, POPULATION, None, Some("Finnish")),
        (13, POPULATION, None, Some("Finnish")),
        (21, POPULATION, None, Some("Finnish")),
        (8, POPULATION, None, Some("Zapotec")),
        (34, POPULATION, None, Some("Zapotec")),
        (2, POPULATION, None, Some("Yoruba")),
        (10, SEX, None, Some("female")),
        (100, SEX, None, Some("female")),
        (1000, SEX, None, Some("female")),
        (2, SEX, None, Some("male")),
        (3, SEX, None, Some("male")),
        (5, SEX, None, Some("male")),
        (2, AGE, Some(50.0), None),
        (3, AGE, Some(42.0), None),
        (5, AGE, Some(60.0), None),
        (8, AGE, Some(43.5), None),
        (13, AGE, Some(99.0), None),
        (21, AGE, Some(41.9), None),
        (10, AGE, Some(30.0), None),
        (999, HEIGHT, Some(170.0), None),
        (1000, HEIGHT, Some(175.5), None),
        (10001, HEIGHT, Some(150.0), None),
        (10, HEIGHT, Some(180.0), None),
        (2, HEIGHT, Some(176.0), None),
        (7, OTHER_AGE, Some(20.0), None),
    ])
}

fn cohort(store: &TestStore, clause: PhenotypicClause) -> Vec<u32> {
    ids(&store
        .engine
        .cohort(&query(ResultType::Count, Some(clause)))
        .unwrap())
}

#[test]
fn no_filters_returns_all_patients() {
    let s = genomes();
    let all = s.engine.cohort(&query(ResultType::Count, None)).unwrap();
    assert_eq!(
        ids(&all),
        [2, 3, 5, 7, 8, 10, 13, 21, 34, 100, 999, 1000, 10001]
    );
}

#[test]
fn valid_numeric_filter_is_inclusive() {
    let s = genomes();
    assert_eq!(
        cohort(&s, filter_range(AGE, Some(35.0), Some(45.0))),
        [3, 8, 21]
    );
    assert_eq!(
        cohort(&s, filter_range(AGE, Some(42.0), Some(43.5))),
        [3, 8]
    );
    assert_eq!(
        cohort(&s, filter_range(AGE, Some(42.0), None)),
        [2, 3, 5, 8, 13]
    );
    assert_eq!(cohort(&s, filter_range(AGE, None, Some(42.0))), [3, 10, 21]);
}

#[test]
fn valid_categorical_filter_returns_patients() {
    let s = genomes();
    assert_eq!(
        cohort(&s, filter_values(POPULATION, &["Finnish"])),
        [3, 5, 8, 13, 21]
    );
}

#[test]
fn non_existent_categorical_filter_returns_no_patients() {
    let s = genomes();
    assert!(cohort(&s, filter_values(MISSING, &["Finnish"])).is_empty());
}

#[test]
fn non_existent_numeric_filter_returns_no_patients() {
    let s = genomes();
    assert!(cohort(&s, filter_range(MISSING, Some(42.0), None)).is_empty());
}

#[test]
fn complex_nested_filters_return_patients() {
    let s = genomes();
    let first = subquery(
        Operator::And,
        vec![
            filter_values(POPULATION, &["Finnish"]),
            filter_range(AGE, Some(42.0), None),
        ],
    );
    let second = subquery(
        Operator::And,
        vec![
            filter_values(SEX, &["female"]),
            filter_range(HEIGHT, None, Some(175.5)),
        ],
    );
    let top = subquery(Operator::Or, vec![first, second]);
    assert_eq!(cohort(&s, top), [3, 5, 8, 13, 1000]);
}

#[test]
fn categorical_filter_with_multiple_values_unions_them() {
    let s = genomes();
    assert_eq!(
        cohort(&s, filter_values(POPULATION, &["Finnish", "Zapotec"])),
        [3, 5, 8, 13, 21, 34]
    );
    assert_eq!(
        cohort(&s, filter_values(POPULATION, &["Zapotec", "Klingon"])),
        [8, 34]
    );
    assert!(cohort(&s, filter_values(POPULATION, &[])).is_empty());
}

#[test]
fn any_record_of_matches_child_paths_by_prefix() {
    let s = genomes();
    assert_eq!(
        cohort(&s, any_record_of("\\open_access-1000Genomes\\")),
        [2, 3, 5, 8, 10, 13, 21, 34, 100, 999, 1000, 10001]
    );
    assert_eq!(
        cohort(
            &s,
            any_record_of("\\open_access-1000Genomes\\data\\SYNTHETIC_")
        ),
        [2, 3, 5, 8, 10, 13, 21, 999, 1000, 10001]
    );
    assert_eq!(cohort(&s, any_record_of(AGE)), [2, 3, 5, 8, 10, 13, 21]);
}

#[test]
fn any_record_of_with_no_matches_returns_no_patients() {
    let s = genomes();
    assert!(cohort(&s, any_record_of("\\nothing\\")).is_empty());
}

#[test]
fn required_filter_returns_every_patient_with_a_value() {
    let s = genomes();
    assert_eq!(cohort(&s, required(POPULATION)), [2, 3, 5, 8, 13, 21, 34]);
    assert_eq!(cohort(&s, required(AGE)), [2, 3, 5, 8, 10, 13, 21]);
}

#[test]
fn not_found_required_filter_returns_no_patients() {
    let s = genomes();
    assert!(cohort(&s, required(MISSING)).is_empty());
}

#[test]
fn authorization_filters_are_anded_with_the_clause() {
    let s = genomes();
    let mut q = query(ResultType::Count, Some(required(AGE)));
    q.authorization_filters = vec![AuthorizationFilter {
        concept_path: POPULATION.into(),
        values: Some(vec!["Finnish".into(), "Yoruba".into()]),
    }];
    assert_eq!(ids(&s.engine.cohort(&q).unwrap()), [2, 3, 5, 8, 13, 21]);
    q.phenotypic_clause = None;
    assert_eq!(ids(&s.engine.cohort(&q).unwrap()), [2, 3, 5, 8, 13, 21]);
    q.authorization_filters[0].values = None;
    assert!(matches!(
        s.engine.cohort(&q),
        Err(QueryError::FilterWithoutCriteria { .. })
    ));
}

#[test]
fn malformed_trees_fail_as_java_does() {
    let s = genomes();
    let run = |clause| s.engine.count(&query(ResultType::Count, Some(clause)));
    let empty = PhenotypicClause::Subquery(PhenotypicSubquery {
        not: None,
        phenotypic_clauses: vec![],
        operator: Some(Operator::And),
    });
    assert!(matches!(run(empty), Err(QueryError::EmptySubquery)));
    let no_operator = PhenotypicClause::Subquery(PhenotypicSubquery {
        not: None,
        phenotypic_clauses: vec![required(AGE)],
        operator: None,
    });
    assert!(matches!(run(no_operator), Err(QueryError::MissingOperator)));
    assert!(matches!(
        run(filter_range(AGE, None, None)),
        Err(QueryError::FilterWithoutCriteria { .. })
    ));
    assert!(matches!(
        run(filter_values(AGE, &["x"])),
        Err(QueryError::TypeMismatch {
            expected: "categorical",
            ..
        })
    ));
    assert!(matches!(
        run(filter_range(SEX, Some(1.0), None)),
        Err(QueryError::TypeMismatch {
            expected: "numeric",
            ..
        })
    ));
    let nested_error = subquery(
        Operator::And,
        vec![
            filter_values(POPULATION, &["Nobody"]),
            filter_range(AGE, None, None),
        ],
    );
    assert!(run(nested_error).is_err());
}

#[test]
fn ranges_follow_java_double_order() {
    const V: &str = "\\v\\";
    let s = store(&[
        (1, V, Some(-0.0), None),
        (2, V, Some(0.0), None),
        (3, V, Some(f64::NAN), None),
        (4, V, Some(-5.0), None),
        (5, V, Some(1e300), None),
    ]);
    assert_eq!(cohort(&s, filter_range(V, Some(0.0), None)), [2, 3, 5]);
    assert_eq!(cohort(&s, filter_range(V, None, Some(-0.0))), [1, 4]);
    assert_eq!(cohort(&s, filter_range(V, Some(f64::NAN), None)), [3]);
    assert_eq!(
        cohort(&s, filter_range(V, Some(1.0), Some(f64::INFINITY))),
        [5]
    );
    assert!(cohort(&s, filter_range(V, Some(2.0), Some(1.0))).is_empty());
    assert!(cohort(&s, filter_range(V, Some(1e301), Some(1e302))).is_empty());
}

/// Patient `p` has a value for concept `c` when `p * 7 + c * 13` is a multiple of `c + 3`,
/// a pattern that gives every concept a different, overlapping set.
fn holds(patient: u32, concept: u32) -> bool {
    (patient * 7 + concept * 13).is_multiple_of(concept + 3)
}

fn wide_tree() -> (TestStore, Vec<String>) {
    let paths: Vec<String> = (0..60)
        .map(|c| format!("\\study\\group_{}\\variable_{c}\\", c % 4))
        .collect();
    let mut rows: Vec<Row<'_>> = Vec::new();
    for (c, path) in paths.iter().enumerate() {
        for patient in 1..400 {
            if holds(patient, c as u32) {
                if c.is_multiple_of(2) {
                    rows.push((patient, path, Some(f64::from(patient % 50)), None));
                } else {
                    rows.push((patient, path, None, Some("yes")));
                }
            }
        }
    }
    (store(&rows), paths)
}

fn expected(concepts: impl Iterator<Item = usize>, combine: fn(bool, bool) -> bool) -> Vec<u32> {
    let concepts: Vec<u32> = concepts.map(|c| c as u32).collect();
    (1..400)
        .filter(|&p| {
            let mut found = concepts.iter().map(|&c| holds(p, c));
            let first = found.next().unwrap();
            found.fold(first, combine)
        })
        .collect()
}

#[test]
fn many_way_unions_and_intersections_match_set_semantics() {
    let (s, paths) = wide_tree();
    assert_eq!(
        cohort(&s, any_record_of("\\study\\")),
        expected(0..60, |a, b| a || b)
    );
    assert_eq!(
        cohort(&s, any_record_of("\\study\\group_1\\")),
        expected((1..60).step_by(4), |a, b| a || b)
    );
    let required_all = |range: std::ops::Range<usize>| -> Vec<PhenotypicClause> {
        paths[range].iter().map(|p| required(p)).collect()
    };
    assert_eq!(
        cohort(&s, subquery(Operator::Or, required_all(0..60))),
        expected(0..60, |a, b| a || b)
    );
    assert_eq!(
        cohort(&s, subquery(Operator::And, required_all(0..3))),
        expected(0..3, |a, b| a && b)
    );
    assert!(cohort(&s, subquery(Operator::And, required_all(0..60))).is_empty());
    assert_eq!(
        cohort(&s, subquery(Operator::Or, required_all(5..6))),
        expected(5..6, |a, b| a || b)
    );
    assert_eq!(
        cohort(
            &s,
            subquery(
                Operator::And,
                vec![
                    subquery(Operator::Or, required_all(0..2)),
                    any_record_of("\\study\\group_2\\"),
                    filter_range(&paths[0], Some(0.0), None),
                ]
            )
        ),
        (1..400)
            .filter(|&p| (holds(p, 0) || holds(p, 1))
                && (2..60).step_by(4).any(|c| holds(p, c))
                && holds(p, 0))
            .collect::<Vec<_>>()
    );
}
