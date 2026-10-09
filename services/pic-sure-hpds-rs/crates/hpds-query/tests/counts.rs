//! Port of `CountV3ProcessorTest`, against stores built by the Rust ETL, plus the
//! inclusion and ordering rules of the cross counts.

mod common;

use std::os::unix::fs::FileExt;

use common::*;
use hpds_format::{BLOCKS_FILE_NAME, CryptoKey, INDEX_FILE_NAME, PhenoIndex, Protection};
use hpds_query::{Engine, GenomicFilter, Operator, QueryError, ResultType, SyncResult};

const CONSENT_A: &str = "\\_studies_consents\\phs001194\\HMB\\";
const CONSENT_B: &str = "\\_studies_consents\\phs000007\\HMB-IRB-MDS\\";
const SEX: &str = "\\demographics\\SEX\\";
const AGE: &str = "\\demographics\\AGE\\";
const COHORT: &str = "\\demographics\\COHORT\\";

fn consents() -> TestStore {
    let mut rows = Vec::new();
    for p in [1, 2, 3, 5, 8] {
        rows.push((p, CONSENT_A, None, Some("yes")));
    }
    for p in [8, 13, 21, 34] {
        rows.push((p, CONSENT_B, None, Some("yes")));
    }
    for p in [2, 3, 5, 8, 13, 21] {
        rows.push((p, COHORT, None, Some("base")));
    }
    for p in [1000, 1001, 1002, 1003] {
        rows.push((p, COHORT, None, Some("other")));
    }
    store(&rows)
}

fn base(values: &[&str], select: &[&str], result_type: ResultType) -> hpds_query::Query {
    let mut q = query(result_type, Some(filter_values(COHORT, values)));
    q.select = select.iter().map(|s| (*s).to_owned()).collect();
    q
}

#[test]
fn cross_counts_valid_paths_return_patient_counts() {
    let s = consents();
    let counts = s
        .engine
        .cross_count(&base(
            &["base"],
            &[CONSENT_A, CONSENT_B],
            ResultType::CrossCount,
        ))
        .unwrap();
    assert_eq!(
        counts.entries,
        [(CONSENT_A.to_owned(), 4), (CONSENT_B.to_owned(), 3)]
    );
}

#[test]
fn cross_counts_with_no_matching_patients_return_zeros() {
    let s = consents();
    let counts = s
        .engine
        .cross_count(&base(
            &["other"],
            &[CONSENT_A, CONSENT_B, "\\nope\\"],
            ResultType::CrossCount,
        ))
        .unwrap();
    assert_eq!(
        counts.entries,
        [
            (CONSENT_A.to_owned(), 0),
            (CONSENT_B.to_owned(), 0),
            ("\\nope\\".to_owned(), 0)
        ]
    );
}

#[test]
fn cross_counts_with_unreadable_block_return_negative_one() {
    let s = consents();
    let protection = Protection::Encrypted(CryptoKey::load(s.out.join("encryption_key")).unwrap());
    let index = PhenoIndex::read_file(s.out.join(INDEX_FILE_NAME), &protection).unwrap();
    let location = index.get(CONSENT_B).unwrap().block;
    let blocks = std::fs::OpenOptions::new()
        .write(true)
        .open(s.out.join(BLOCKS_FILE_NAME))
        .unwrap();
    blocks
        .write_all_at(&[0xAA; 4], location.offset + location.length - 8)
        .unwrap();
    let engine = Engine::open(&s.out, &lenient()).unwrap();
    let q = base(
        &["base"],
        &[CONSENT_A, CONSENT_B, CONSENT_A],
        ResultType::CrossCount,
    );
    let counts = engine.cross_count(&q).unwrap();
    assert_eq!(
        counts.entries,
        [(CONSENT_A.to_owned(), 4), (CONSENT_B.to_owned(), -1)]
    );
    let observations = engine.observation_cross_count(&q).unwrap();
    assert_eq!(observations.get(CONSENT_B), Some(-1));
    assert!(matches!(
        engine.dataframe(&q),
        Err(QueryError::Block { .. })
    ));
}

fn demographics() -> TestStore {
    store(&[
        (1, SEX, None, Some("male")),
        (2, SEX, None, Some("male")),
        (3, SEX, None, Some("male")),
        (4, SEX, None, Some("female")),
        (5, SEX, None, Some("female")),
        (1, AGE, Some(15.0), None),
        (2, AGE, Some(40.0), None),
        (3, AGE, Some(65.0), None),
        (4, AGE, Some(19.0), None),
        (4, AGE, Some(19.0), None),
        (1, COHORT, None, Some("a")),
        (4, COHORT, None, Some("a")),
        (2, COHORT, None, Some("b")),
    ])
}

#[test]
fn continuous_cross_counts_for_required_numeric_concept_count_observations() {
    let s = demographics();
    let q = query(
        ResultType::ContinuousCrossCount,
        Some(subquery(
            Operator::And,
            vec![required(AGE), filter_values(COHORT, &["a"])],
        )),
    );
    let counts = s.engine.continuous_cross_count(&q).unwrap();
    assert_eq!(
        counts.concepts,
        [(AGE.to_owned(), vec![(15.0, 1), (19.0, 2)])]
    );
}

#[test]
fn categorical_cross_counts_merge_value_filters_on_the_same_path() {
    let s = demographics();
    let q = query(
        ResultType::CategoricalCrossCount,
        Some(subquery(
            Operator::Or,
            vec![
                filter_values(SEX, &["male"]),
                filter_values(SEX, &["female"]),
            ],
        )),
    );
    let counts = s.engine.categorical_cross_count(&q).unwrap();
    assert_eq!(
        counts.concepts,
        [(
            SEX.to_owned(),
            vec![("female".to_owned(), 2), ("male".to_owned(), 3)]
        )]
    );
}

#[test]
fn categorical_cross_counts_for_required_filter_count_each_patient_once() {
    let s = demographics();
    let q = query(
        ResultType::CategoricalCrossCount,
        Some(subquery(
            Operator::And,
            vec![required(SEX), filter_values(COHORT, &["a"])],
        )),
    );
    let counts = s.engine.categorical_cross_count(&q).unwrap();
    assert_eq!(
        counts.get(SEX).unwrap(),
        [("female".to_owned(), 1), ("male".to_owned(), 1)]
    );
    assert_eq!(counts.get(COHORT).unwrap(), [("a".to_owned(), 2)]);
}

#[test]
fn categorical_cross_counts_list_named_categories_even_at_zero() {
    let s = demographics();
    let q = query(
        ResultType::CategoricalCrossCount,
        Some(subquery(
            Operator::And,
            vec![
                filter_values(SEX, &["male", "unicorn"]),
                filter_values(COHORT, &["b"]),
                filter_values("\\nope\\", &["x"]),
                required(AGE),
            ],
        )),
    );
    let counts = s.engine.categorical_cross_count(&q).unwrap();
    assert_eq!(
        counts.concepts,
        [
            (COHORT.to_owned(), vec![("b".to_owned(), 0)]),
            (SEX.to_owned(), vec![("male".to_owned(), 0)]),
            ("\\nope\\".to_owned(), vec![]),
        ]
    );
}

#[test]
fn continuous_cross_counts_show_cohort_values_outside_the_filter_range() {
    let s = demographics();
    let q = query(
        ResultType::ContinuousCrossCount,
        Some(subquery(
            Operator::Or,
            vec![
                filter_range(AGE, Some(10.0), Some(16.0)),
                filter_values(COHORT, &["b"]),
            ],
        )),
    );
    let counts = s.engine.continuous_cross_count(&q).unwrap();
    assert_eq!(counts.get(AGE).unwrap(), [(15.0, 1), (40.0, 1)]);
    assert!(counts.get(SEX).is_none());
}

#[test]
fn observation_cross_counts_skip_unknown_paths_and_sort() {
    let s = demographics();
    let mut q = query(
        ResultType::ObservationCrossCount,
        Some(filter_values(COHORT, &["a"])),
    );
    q.select = vec![SEX.into(), "\\nope\\".into(), AGE.into()];
    let counts = s.engine.observation_cross_count(&q).unwrap();
    assert_eq!(counts.entries, [(AGE.to_owned(), 3), (SEX.to_owned(), 2)]);
}

#[test]
fn sync_dispatch_and_bodies() {
    let s = demographics();
    let mut q = query(ResultType::Count, Some(required(AGE)));
    assert_eq!(s.engine.run_sync(&q).unwrap(), SyncResult::Count(4));
    assert_eq!(s.engine.run_sync(&q).unwrap().body(), "4");
    q.expected_result_type = ResultType::ContinuousCrossCount;
    assert_eq!(
        s.engine.run_sync(&q).unwrap().body(),
        r#"{"\\demographics\\AGE\\":{"15.0":1,"19.0":2,"40.0":1,"65.0":1}}"#
    );
    q.expected_result_type = ResultType::Dataframe;
    assert!(matches!(
        s.engine.run_sync(&q),
        Err(QueryError::NotSync(ResultType::Dataframe))
    ));
    q.expected_result_type = ResultType::VcfExcerpt;
    assert!(matches!(
        s.engine.run_sync(&q),
        Err(QueryError::Unsupported(_))
    ));
    q.expected_result_type = ResultType::Count;
    q.genomic_filters = vec![GenomicFilter {
        key: Some("Gene_with_variant".into()),
        values: None,
        min: None,
        max: None,
    }];
    assert!(matches!(
        s.engine.count(&q),
        Err(QueryError::Unsupported(_))
    ));
}

#[test]
fn patients_and_dataframe_rows() {
    let s = demographics();
    let mut q = query(
        ResultType::Dataframe,
        Some(filter_values(COHORT, &["a", "b"])),
    );
    assert_eq!(ids(&s.engine.patients(&q).unwrap()), [1, 2, 4]);
    q.select = vec![AGE.into(), "\\nope\\".into(), SEX.into(), AGE.into()];
    let frame = s.engine.dataframe(&q).unwrap();
    assert_eq!(frame.header, ["patient_id", AGE, "\\nope\\", SEX, AGE]);
    assert_eq!(frame.rows.size_hint(), (3, Some(3)));
    let rows: Vec<Vec<String>> = frame.rows.collect();
    assert_eq!(
        rows,
        [
            ["1", "15.0", "", "male", "15.0"],
            ["2", "40.0", "", "male", "40.0"],
            ["4", "19.0\t19.0", "", "female", "19.0\t19.0"],
        ]
    );
}

#[test]
fn dataframe_keeps_input_order_within_a_patient() {
    const NOTE: &str = "\\note\\";
    const LAB: &str = "\\lab\\";
    let s = store(&[
        (9, NOTE, None, Some("zeta")),
        (3, NOTE, None, Some("b")),
        (9, NOTE, None, Some("alpha")),
        (9, NOTE, None, Some("zeta")),
        (9, LAB, Some(2.5), None),
        (9, LAB, Some(-1.0), None),
        (9, LAB, Some(1e7), None),
    ]);
    let mut q = query(ResultType::Dataframe, None);
    q.select = vec![NOTE.into(), LAB.into()];
    let rows: Vec<Vec<String>> = s.engine.dataframe(&q).unwrap().rows.collect();
    assert_eq!(
        rows,
        [
            vec!["3".to_owned(), "b".into(), String::new()],
            vec![
                "9".to_owned(),
                "zeta\talpha\tzeta".into(),
                "2.5\t-1.0\t1.0E7".into()
            ],
        ]
    );
}

#[test]
fn row_buffer_matches_the_iterator_and_is_reused() {
    let s = demographics();
    let mut q = query(ResultType::Dataframe, None);
    q.select = vec![AGE.into(), SEX.into()];
    let expected: Vec<Vec<String>> = s.engine.dataframe(&q).unwrap().rows.collect();
    let mut rows = s.engine.dataframe(&q).unwrap().rows;
    let mut buffer = hpds_query::RowBuffer::new();
    let mut rendered = Vec::new();
    while rows.next_into(&mut buffer) {
        rendered.push(buffer.fields().map(str::to_owned).collect::<Vec<_>>());
    }
    assert_eq!(rendered, expected);
}

#[test]
fn a_dataframe_over_the_pin_cap_is_a_client_error() {
    let s = demographics();
    let engine = Engine::open(&s.out, &lenient())
        .unwrap()
        .with_export_pin_bytes(1);
    let mut q = query(ResultType::Dataframe, None);
    q.select = vec![AGE.into()];
    let error = engine.dataframe(&q).err().unwrap();
    assert!(matches!(error, hpds_query::QueryError::ExportTooWide));
    assert!(error.is_client_error());
    assert_eq!(error.to_string(), "export too wide; select fewer concepts");
}

#[cfg(feature = "tokio")]
#[tokio::test]
async fn blocking_wrappers_run_on_the_blocking_pool() {
    let s = demographics();
    let engine = std::sync::Arc::new(Engine::open(&s.out, &lenient()).unwrap());
    let q = query(ResultType::Count, Some(required(AGE)));
    assert_eq!(engine.run_blocking(move |e| e.count(&q)).await.unwrap(), 4);
}
