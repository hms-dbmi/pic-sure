//! The per-query bounds: select paths, clause depth and width, concepts touched, and the
//! size of a cross count.

mod common;

use common::*;
use hpds_query::{Engine, Operator, PhenotypicClause, QueryError, QueryLimits, ResultType};
use hpds_query::{StoreOptions, SyncResult};

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
    Engine::open(&s.out, &StoreOptions::default())
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
