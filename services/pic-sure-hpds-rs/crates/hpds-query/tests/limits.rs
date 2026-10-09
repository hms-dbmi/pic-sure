//! The per-query bounds: select paths, clause depth and width, concepts touched, and the
//! size of a cross count, on one store and across consent partitions.

mod common;

use common::*;
use hpds_query::{
    Engine, Operator, PhenotypicClause, Query, QueryError, QueryLimits, ResultType, SyncResult,
};

const SEX: &str = "\\demographics\\SEX\\";
const AGE: &str = "\\demographics\\AGE\\";
const COHORT: &str = "\\demographics\\COHORT\\";
const LABS: &str = "\\labs\\";

fn data() -> TestStore {
    let mut rows = Vec::new();
    for p in 1..=6 {
        rows.push((
            p,
            SEX,
            None,
            Some(if p % 2 == 0 { "male" } else { "female" }),
        ));
        rows.push((p, AGE, Some(f64::from(p) * 10.0), None));
        rows.push((p, COHORT, None, Some("base")));
    }
    for (i, lab) in ["a", "b", "c", "d", "e"].iter().enumerate() {
        let path = format!("{LABS}{lab}\\");
        rows.push((
            1 + i as u32,
            Box::leak(path.into_boxed_str()) as &str,
            Some(1.0),
            None,
        ));
    }
    store(&rows)
}

fn limited(s: &TestStore, limits: QueryLimits) -> Engine {
    Engine::open(&s.out, &lenient())
        .unwrap()
        .with_limits(limits)
}

fn limit_message(result: Result<impl std::fmt::Debug, QueryError>) -> String {
    match result {
        Err(error @ QueryError::LimitExceeded(_)) => {
            assert!(error.is_client_error());
            error.to_string()
        }
        other => panic!("expected a limit error, got {other:?}"),
    }
}

fn nested(depth: usize) -> PhenotypicClause {
    let mut clause = filter_values(SEX, &["female"]);
    for _ in 1..depth {
        clause = subquery(Operator::And, vec![clause]);
    }
    clause
}

#[test]
fn repeated_select_paths_count_once() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_select_paths: 2,
            ..QueryLimits::default()
        },
    );
    let mut q = query(ResultType::CrossCount, None);
    q.select = vec![SEX.into(); 500];
    q.select.push(AGE.into());
    let SyncResult::CrossCount(counts) = engine.run_sync(&q).unwrap() else {
        panic!("not a cross count");
    };
    assert_eq!(counts.entries.len(), 2);
    q.select.push(COHORT.into());
    let message = limit_message(engine.run_sync(&q));
    assert!(message.contains("HPDS_MAX_SELECT_PATHS") && message.contains('2'));
    assert!(!message.contains("demographics"));
}

#[test]
fn a_dataframe_refuses_a_select_list_widened_by_repeats() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_select_paths: 3,
            ..QueryLimits::default()
        },
    );
    let mut q = query(ResultType::Dataframe, None);
    q.select = vec![SEX.into(), AGE.into(), SEX.into()];
    assert!(engine.dataframe(&q).is_ok());
    q.select.push(SEX.into());
    limit_message(engine.dataframe(&q).map(|_| ()));
}

#[test]
fn clause_depth_is_bounded_without_recursion() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_clause_depth: 3,
            ..QueryLimits::default()
        },
    );
    let at_limit = query(ResultType::Count, Some(nested(3)));
    assert_eq!(engine.count(&at_limit).unwrap(), 3);
    let over = query(ResultType::Count, Some(nested(4)));
    let message = limit_message(engine.count(&over));
    assert!(message.contains("HPDS_MAX_CLAUSE_DEPTH"));
    let deep = query(ResultType::Count, Some(nested(3_000)));
    limit_message(engine.count(&deep));
    limit_message(engine.run_sync(&deep));
    limit_message(engine.check_limits(&deep));
}

#[test]
fn clause_width_is_bounded() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_clause_nodes: 5,
            ..QueryLimits::default()
        },
    );
    let wide = |n: usize| {
        subquery(
            Operator::Or,
            (0..n).map(|_| filter_values(SEX, &["female"])).collect(),
        )
    };
    assert!(
        engine
            .count(&query(ResultType::Count, Some(wide(4))))
            .is_ok()
    );
    let message = limit_message(engine.count(&query(ResultType::Count, Some(wide(5)))));
    assert!(message.contains("HPDS_MAX_CLAUSE_NODES"));
}

#[test]
fn authorization_filters_count_as_clauses() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_clause_nodes: 2,
            ..QueryLimits::default()
        },
    );
    let mut q = query(ResultType::Count, Some(filter_values(SEX, &["female"])));
    q.authorization_filters = (0..2)
        .map(|_| hpds_query::AuthorizationFilter {
            concept_path: COHORT.into(),
            values: Some(vec!["base".into()]),
        })
        .collect();
    limit_message(engine.count(&q));
}

#[test]
fn any_record_of_expansions_count_toward_the_concept_total() {
    let s = data();
    let q = query(ResultType::Count, Some(any_record_of("\\")));
    let roomy = limited(&s, QueryLimits::default());
    assert_eq!(roomy.count(&q).unwrap(), 6);

    let tight = limited(
        &s,
        QueryLimits {
            max_query_concepts: 5,
            ..QueryLimits::default()
        },
    );
    let message = limit_message(tight.count(&q));
    assert!(message.contains("HPDS_MAX_QUERY_CONCEPTS"));
    let narrow = query(ResultType::Count, Some(any_record_of(LABS)));
    assert_eq!(tight.count(&narrow).unwrap(), 5);
    let narrow_and_more = query(
        ResultType::Count,
        Some(subquery(
            Operator::And,
            vec![any_record_of(LABS), filter_values(SEX, &["female"])],
        )),
    );
    limit_message(tight.count(&narrow_and_more));
}

#[test]
fn filters_select_and_authorization_paths_share_the_concept_total() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_query_concepts: 3,
            ..QueryLimits::default()
        },
    );
    let mut q = query(
        ResultType::CrossCount,
        Some(subquery(
            Operator::And,
            vec![filter_values(SEX, &["female"]), required(AGE)],
        )),
    );
    q.select = vec![SEX.into(), AGE.into(), COHORT.into()];
    assert!(engine.run_sync(&q).is_ok());
    q.select.push(format!("{LABS}a\\"));
    limit_message(engine.run_sync(&q));
}

#[test]
fn cross_counts_stop_at_the_entry_cap() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_cross_count_entries: 3,
            ..QueryLimits::default()
        },
    );
    let continuous = query(
        ResultType::ContinuousCrossCount,
        Some(filter_range(AGE, Some(0.0), None)),
    );
    let message = limit_message(engine.run_sync(&continuous));
    assert!(message.contains("HPDS_MAX_CROSS_COUNT_ENTRIES"));
    let categorical = query(
        ResultType::CategoricalCrossCount,
        Some(subquery(
            Operator::And,
            vec![
                filter_values(SEX, &["female"]),
                filter_values(COHORT, &["base"]),
            ],
        )),
    );
    assert!(engine.run_sync(&categorical).is_ok());
    let wide = limited(
        &s,
        QueryLimits {
            max_cross_count_entries: 1,
            ..QueryLimits::default()
        },
    );
    limit_message(wide.run_sync(&categorical).map(|_| ()));
}

const C1: &str = "phs000001.c1";
const C2: &str = "phs000002.c2";
const C3: &str = "phs000003.c3";

fn lab(name: &str) -> &'static str {
    Box::leak(format!("{LABS}{name}\\").into_boxed_str())
}

/// Three partitions: `c1` and `c2` share labs `a` to `c`, `c2` adds `d`, `c3` holds `e` to
/// `h`. Every lab holds patient 1's value 1.0 or 2.0, and SEX holds `female` in `c1` and
/// `male` and `female` in `c2`.
fn partitions() -> PartitionedDir {
    let labs = |names: &[&str], patient: u32, value: f64| -> Vec<Row<'static>> {
        names
            .iter()
            .map(|name| (patient, lab(name), Some(value), None))
            .collect()
    };
    let mut c1 = labs(&["a", "b", "c"], 1, 1.0);
    c1.push((1, SEX, None, Some("female")));
    let mut c2 = labs(&["a", "b", "c", "d"], 2, 2.0);
    c2.push((2, SEX, None, Some("male")));
    c2.push((3, SEX, None, Some("female")));
    let c3 = labs(&["e", "f", "g", "h"], 4, 1.0);
    partitioned_rows(&[(C1, c1), (C2, c2), (C3, c3)])
}

fn consented(result_type: ResultType, clause: PhenotypicClause, consents: &[&str]) -> Query {
    Query {
        phenotypic_clause: Some(clause),
        ..Query::with_consents(result_type, consents.iter().copied())
    }
}

#[test]
fn any_record_of_counts_distinct_concepts_over_the_selected_partitions() {
    let d = partitions();
    let engine = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_query_concepts: 4,
        ..QueryLimits::default()
    });
    let labs = || any_record_of(LABS);
    assert_eq!(
        engine
            .count(&consented(ResultType::Count, labs(), &[C1, C2]))
            .unwrap(),
        2
    );
    assert_eq!(
        engine
            .count(&consented(ResultType::Count, labs(), &[C3]))
            .unwrap(),
        1
    );
    let message = limit_message(engine.count(&consented(ResultType::Count, labs(), &[C2, C3])));
    assert!(message.contains("HPDS_MAX_QUERY_CONCEPTS"));
    limit_message(engine.count(&consented(ResultType::Count, labs(), &[C1, C2, C3])));
    limit_message(engine.check_limits(&consented(ResultType::Count, labs(), &[C1, C3])));
    assert!(
        engine
            .check_limits(&consented(ResultType::Count, labs(), &[C1, C2]))
            .is_ok()
    );
}

#[test]
fn an_unknown_consent_selects_nothing_to_expand() {
    let d = partitions();
    let engine = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_query_concepts: 1,
        ..QueryLimits::default()
    });
    let q = consented(ResultType::Count, any_record_of(LABS), &["phs999999.c9"]);
    assert_eq!(engine.count(&q).unwrap(), 0);
}

#[test]
fn shape_bounds_come_before_the_consent_check() {
    let d = partitions();
    let engine = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_clause_depth: 2,
        ..QueryLimits::default()
    });
    let mut deep = consented(ResultType::Count, nested(3), &[]);
    limit_message(engine.count(&deep));
    limit_message(engine.check_limits(&deep));
    deep.phenotypic_clause = Some(nested(2));
    assert!(matches!(
        engine.check_limits(&deep),
        Err(QueryError::UserConsentsRequired)
    ));
}

#[test]
fn cross_count_entries_are_counted_after_merging_partitions() {
    let d = partitions();
    let categorical = |engine: &Engine, consents: &[&str]| {
        engine.run_sync(&consented(
            ResultType::CategoricalCrossCount,
            filter_values(SEX, &["female", "male"]),
            consents,
        ))
    };
    let two = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_cross_count_entries: 2,
        ..QueryLimits::default()
    });
    let SyncResult::CategoricalCrossCount(counts) = categorical(&two, &[C1, C2]).unwrap() else {
        panic!("not a categorical cross count");
    };
    assert_eq!(counts.concepts[0].1.len(), 2);
    let one = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_cross_count_entries: 1,
        ..QueryLimits::default()
    });
    assert!(categorical(&one, &[C1]).is_ok());
    let message = limit_message(categorical(&one, &[C1, C2]).map(|_| ()));
    assert!(message.contains("HPDS_MAX_CROSS_COUNT_ENTRIES"));

    let continuous = |engine: &Engine, consents: &[&str]| {
        engine.run_sync(&consented(
            ResultType::ContinuousCrossCount,
            filter_range(lab("a"), Some(0.0), None),
            consents,
        ))
    };
    let SyncResult::ContinuousCrossCount(values) = continuous(&two, &[C1, C2]).unwrap() else {
        panic!("not a continuous cross count");
    };
    assert_eq!(values.concepts[0].1, [(1.0, 1), (2.0, 1)]);
    assert!(continuous(&one, &[C1]).is_ok());
    limit_message(continuous(&one, &[C1, C2]).map(|_| ()));
}

#[test]
fn every_any_record_of_counts_its_concepts_even_when_the_prefix_repeats() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_query_concepts: 6,
            ..QueryLimits::default()
        },
    );
    let once = query(ResultType::Count, Some(any_record_of(LABS)));
    assert_eq!(engine.count(&once).unwrap(), 5);
    let twice = query(
        ResultType::Count,
        Some(subquery(
            Operator::Or,
            vec![any_record_of(LABS), any_record_of(LABS)],
        )),
    );
    let message = limit_message(engine.count(&twice));
    assert!(message.contains("HPDS_MAX_QUERY_CONCEPTS"));
    limit_message(engine.check_limits(&twice));
}

#[test]
fn a_repeated_prefix_counts_once_per_filter_over_the_selected_partitions() {
    let d = partitions();
    let engine = d.open(&strict()).unwrap().with_limits(QueryLimits {
        max_query_concepts: 7,
        ..QueryLimits::default()
    });
    let twice = || subquery(Operator::Or, vec![any_record_of(LABS), any_record_of(LABS)]);
    assert_eq!(
        engine
            .count(&consented(ResultType::Count, twice(), &[C1]))
            .unwrap(),
        1
    );
    let message = limit_message(engine.count(&consented(ResultType::Count, twice(), &[C1, C2])));
    assert!(message.contains("HPDS_MAX_QUERY_CONCEPTS"));
}

#[test]
fn a_dataframe_counts_repeated_select_paths_whatever_the_declared_result_type() {
    let s = data();
    let engine = limited(
        &s,
        QueryLimits {
            max_select_paths: 3,
            ..QueryLimits::default()
        },
    );
    let mut q = query(ResultType::Count, None);
    q.select = vec![SEX.into(), AGE.into(), SEX.into(), SEX.into()];
    assert!(engine.count(&q).is_ok());
    let message = limit_message(engine.dataframe(&q).map(|_| ()));
    assert!(message.contains("HPDS_MAX_SELECT_PATHS"));
}
