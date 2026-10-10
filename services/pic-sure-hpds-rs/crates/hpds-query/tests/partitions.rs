//! Port of `PartitionedPhenotypicObservationStoreTest` and the consent cases of
//! `PhenotypicQueryExecutorTest` and `CountV3ProcessorTest`, plus partitions that share
//! patients, a concept typed differently in two partitions, and the open rules.

mod common;

use common::*;
use hpds_query::{
    Engine, Layout, Operator, Query, QueryError, ResultType, StoreOptions, UNPARTITIONED_NAME,
};

const CONCEPT: &str = "\\demographics\\AGE\\";

fn numeric_rows(patients: &[u32]) -> Vec<Row<'static>> {
    patients
        .iter()
        .map(|&p| (p, CONCEPT, Some(f64::from(p)), None))
        .collect()
}

fn ids_of(bitmap: &roaring::RoaringBitmap) -> Vec<u32> {
    bitmap.iter().collect()
}

/// The cohort of a query with no clause in the partitions `consents` select: every patient
/// of those partitions.
fn consented_patients(engine: &Engine, consents: &[&str]) -> Vec<u32> {
    let q = Query::with_consents(ResultType::Count, consents.iter().copied());
    ids_of(&engine.cohort(&q).unwrap())
}

/// The patients with a value for [`CONCEPT`] in the partitions `consents` select
/// (`getAllKeys`), through the engine.
fn holding_concept(engine: &Engine, consents: &[&str]) -> Vec<u32> {
    let q = Query {
        phenotypic_clause: Some(required(CONCEPT)),
        ..Query::with_consents(ResultType::Count, consents.iter().copied())
    };
    ids_of(&engine.cohort(&q).unwrap())
}

#[test]
fn serves_an_unpartitioned_directory_when_consents_are_not_required() {
    let s = store(&numeric_rows(&[1, 2, 3]));
    let set = s.engine.partitions();
    assert_eq!(set.layout(), Layout::Unpartitioned);
    assert_eq!(
        set.partition_names().collect::<Vec<_>>(),
        [UNPARTITIONED_NAME]
    );
    assert!(set.summary(CONCEPT).is_some());
    assert_eq!(consented_patients(&s.engine, &["phs000001.c1"]), [1, 2, 3]);
    assert_eq!(
        holding_concept(&s.engine, &["some.consent.matching.no.partition"]),
        [1, 2, 3]
    );
    assert_eq!(
        holding_concept(&s.engine, &["phs000001.c1", "phs000002.c2", "phs000003.c3"]),
        [1, 2, 3]
    );
    assert_eq!(consented_patients(&s.engine, &[]), [1, 2, 3]);
    assert!(set.select_consents(&[]).unwrap().is_all());
}

#[test]
fn refuses_an_unpartitioned_directory_when_consents_are_required() {
    let s = store(&numeric_rows(&[1, 2, 3]));
    let error = Engine::open(&s.out, &strict()).unwrap_err();
    assert!(matches!(error, QueryError::Open { .. }), "{error}");
    assert!(
        error
            .to_string()
            .contains("hpds.requireAuthorizationFilter=false"),
        "{error}"
    );
}

#[test]
fn a_partitioned_directory_scopes_by_consent() {
    let d = partitioned_rows(&[
        ("phs000001.c1", numeric_rows(&[1, 2])),
        ("phs000002.c2", numeric_rows(&[3, 4])),
    ]);
    let engine = d.open(&strict()).unwrap();
    assert_eq!(engine.partitions().layout(), Layout::Partitioned);
    let patients = |consents: &[&str]| consented_patients(&engine, consents);
    assert_eq!(patients(&["phs000001.c1"]), [1, 2]);
    assert_eq!(patients(&["phs000002.c2"]), [3, 4]);
    assert_eq!(patients(&["phs000001.c1", "phs000002.c2"]), [1, 2, 3, 4]);
    assert!(patients(&["phs000003.c3"]).is_empty());
    assert_eq!(
        holding_concept(&engine, &["phs000001.c1", "phs000002.c2"]),
        [1, 2, 3, 4]
    );
}

#[test]
fn patients_in_several_partitions_are_returned_once() {
    let d = partitioned_rows(&[
        ("phs000001.c1", numeric_rows(&[1, 2, 3])),
        ("phs000002.c2", numeric_rows(&[2, 3, 4])),
    ]);
    let engine = d.open(&strict()).unwrap();
    let both = ["phs000001.c1", "phs000002.c2"];
    assert_eq!(holding_concept(&engine, &both), [1, 2, 3, 4]);
    let all = Query::with_consents(ResultType::Count, both);
    assert_eq!(ids_of(&engine.cohort(&all).unwrap()), [1, 2, 3, 4]);
}

#[test]
fn consents_are_required_when_the_flag_is_on() {
    let d = partitioned_rows(&[("phs000001.c1", numeric_rows(&[1]))]);
    let engine = d.open(&strict()).unwrap();
    assert!(matches!(
        engine.partitions().select_consents(&[]),
        Err(QueryError::UserConsentsRequired)
    ));
    let mut q = query(ResultType::Count, Some(required(CONCEPT)));
    for result in [
        engine.count(&q).map(|_| ()),
        engine.check_consents(&q),
        engine.run_sync(&q).map(|_| ()),
        engine.patients(&q).map(|_| ()),
        engine.dataframe(&q).map(|_| ()),
        engine.cohort(&q).map(|_| ()),
    ] {
        assert!(matches!(result, Err(QueryError::UserConsentsRequired)));
    }
    q.user_consents = vec![hpds_query::UserConsent { value: None }];
    assert!(matches!(
        engine.count(&q),
        Err(QueryError::UserConsentsRequired)
    ));
    let error = engine.check_consents(&q).unwrap_err();
    assert!(error.is_client_error());
    assert_eq!(
        error.to_string(),
        "User consents are required for this query"
    );
    q.user_consents = vec![hpds_query::UserConsent {
        value: Some("phs000001.c1".into()),
    }];
    engine.check_consents(&q).unwrap();
    assert_eq!(engine.count(&q).unwrap(), 1);
}

#[test]
fn no_consents_read_every_partition_when_the_flag_is_off() {
    let d = partitioned_rows(&[("a", numeric_rows(&[1])), ("b", numeric_rows(&[2]))]);
    let engine = d.open(&lenient()).unwrap();
    engine
        .check_consents(&query(ResultType::Count, None))
        .unwrap();
    assert_eq!(engine.count(&query(ResultType::Count, None)).unwrap(), 2);
    let selection = engine.partitions().select_consents(&[]).unwrap();
    assert!(selection.is_all());
}

#[test]
fn selection_skips_unknown_consents_and_keeps_name_order() {
    let d = partitioned_rows(&[
        ("partition2", numeric_rows(&[2])),
        ("partition1", numeric_rows(&[1])),
        ("partition3", numeric_rows(&[3])),
    ]);
    let engine = d.open(&strict()).unwrap();
    let set = engine.partitions();
    let names: Vec<&str> = set.partition_names().collect();
    assert_eq!(names, ["partition1", "partition2", "partition3"]);
    let q = hpds_query::Query::with_consents(
        ResultType::Count,
        ["partition3", "nope", "partition2", "partition2"],
    );
    let selection = set.select(&q).unwrap();
    assert_eq!(selection.names(), ["partition2", "partition3"]);
    assert!(!selection.is_all());
    assert_eq!(engine.count(&q).unwrap(), 2);
    let unknown = hpds_query::Query::with_consents(ResultType::Count, ["nope"]);
    assert_eq!(engine.count(&unknown).unwrap(), 0);
}

#[test]
fn the_cohort_with_no_clause_is_the_consented_partitions_patients() {
    let d = partitioned_rows(&[
        ("phs000001.c1", numeric_rows(&[7])),
        ("phs000002.c1", numeric_rows(&[8])),
        ("phs000003.c1", numeric_rows(&[9])),
    ]);
    let engine = d.open(&strict()).unwrap();
    let q = hpds_query::Query::with_consents(ResultType::Count, ["phs000001.c1", "phs000002.c1"]);
    assert_eq!(ids_of(&engine.cohort(&q).unwrap()), [7, 8]);
}

#[test]
fn cross_counts_read_only_the_consented_partitions() {
    let sex = "\\demographics\\SEX\\";
    let d = partitioned_rows(&[
        (
            "partition1",
            vec![(1, sex, None, Some("male")), (2, sex, None, Some("female"))],
        ),
        ("partition2", vec![(3, sex, None, Some("male"))]),
    ]);
    let engine = d.open(&strict()).unwrap();
    let mut q = hpds_query::Query::with_consents(ResultType::CrossCount, ["partition1"]);
    q.select = vec![sex.into()];
    assert_eq!(engine.cross_count(&q).unwrap().get(sex), Some(2));
    q.user_consents.push(hpds_query::UserConsent {
        value: Some("partition2".into()),
    });
    assert_eq!(engine.cross_count(&q).unwrap().get(sex), Some(3));
}

const SEX: &str = "\\demographics\\SEX\\";
const AGE: &str = "\\demographics\\AGE\\";
const NOTE: &str = "\\notes\\NOTE\\";

/// Two partitions sharing patient 2: SEX only in `p1`, AGE 40 in both, AGE 55 only in
/// `p2`, and NOTE values in both.
fn overlapping() -> PartitionedDir {
    partitioned_rows(&[
        (
            "p1",
            vec![
                (1, SEX, None, Some("male")),
                (2, SEX, None, Some("male")),
                (2, AGE, Some(40.0), None),
                (2, NOTE, None, Some("b")),
                (2, NOTE, None, Some("a")),
            ],
        ),
        (
            "p2",
            vec![
                (2, AGE, Some(40.0), None),
                (2, AGE, Some(55.0), None),
                (3, SEX, None, Some("female")),
                (3, AGE, Some(30.0), None),
                (2, NOTE, None, Some("c")),
                (4, NOTE, None, Some("d")),
            ],
        ),
    ])
}

#[test]
fn overlapping_patients_count_once_in_cohorts() {
    let d = overlapping();
    let engine = d.open(&lenient()).unwrap();
    assert_eq!(
        ids_of(&engine.cohort(&query(ResultType::Count, None)).unwrap()),
        [1, 2, 3, 4]
    );
    let required_age = query(ResultType::Count, Some(required(AGE)));
    assert_eq!(ids_of(&engine.cohort(&required_age).unwrap()), [2, 3]);
    let male_and_old = query(
        ResultType::Count,
        Some(subquery(
            Operator::And,
            vec![
                filter_values(SEX, &["male"]),
                filter_range(AGE, Some(50.0), None),
            ],
        )),
    );
    assert_eq!(ids_of(&engine.cohort(&male_and_old).unwrap()), [2]);
    let mut only_p2 = male_and_old.clone();
    only_p2.user_consents = vec![hpds_query::UserConsent {
        value: Some("p2".into()),
    }];
    assert!(engine.cohort(&only_p2).unwrap().is_empty());
    let any = query(ResultType::Count, Some(any_record_of("\\demographics\\")));
    assert_eq!(ids_of(&engine.cohort(&any).unwrap()), [1, 2, 3]);
}

#[test]
fn overlapping_patients_in_cross_counts() {
    let d = overlapping();
    let engine = d.open(&lenient()).unwrap();
    let mut cross = query(ResultType::CrossCount, None);
    cross.select = vec![AGE.into(), NOTE.into(), "\\missing\\".into()];
    assert_eq!(
        engine.cross_count(&cross).unwrap().entries,
        [
            (AGE.to_owned(), 2),
            (NOTE.to_owned(), 2),
            ("\\missing\\".to_owned(), 0)
        ]
    );
    let mut observations = cross.clone();
    observations.expected_result_type = ResultType::ObservationCrossCount;
    assert_eq!(
        engine
            .observation_cross_count(&observations)
            .unwrap()
            .entries,
        [(AGE.to_owned(), 4), (NOTE.to_owned(), 4)]
    );
    let categorical = query(
        ResultType::CategoricalCrossCount,
        Some(filter_values(SEX, &["male", "female"])),
    );
    let counts = engine.categorical_cross_count(&categorical).unwrap();
    assert_eq!(
        counts.get(SEX).unwrap(),
        [("female".to_owned(), 1), ("male".to_owned(), 2)]
    );
    let continuous = query(ResultType::ContinuousCrossCount, Some(required(AGE)));
    let counts = engine.continuous_cross_count(&continuous).unwrap();
    assert_eq!(counts.get(AGE).unwrap(), [(30.0, 1), (40.0, 2), (55.0, 1)]);
}

#[test]
fn overlapping_patients_in_the_dataframe_follow_partition_order() {
    let d = overlapping();
    let engine = d.open(&lenient()).unwrap();
    let mut q = query(ResultType::Dataframe, None);
    q.select = vec![NOTE.into(), AGE.into(), SEX.into()];
    let rows: Vec<Vec<String>> = engine.dataframe(&q).unwrap().rows.collect();
    assert_eq!(
        rows,
        [
            vec!["1", "", "", "male"],
            vec!["2", "b\ta\tc", "40.0\t40.0\t55.0", "male"],
            vec!["3", "", "30.0", "female"],
            vec!["4", "d", "", ""],
        ]
    );
    q.user_consents = vec![hpds_query::UserConsent {
        value: Some("p2".into()),
    }];
    let rows: Vec<Vec<String>> = engine.dataframe(&q).unwrap().rows.collect();
    assert_eq!(
        rows,
        [
            vec!["2", "c", "40.0\t55.0", ""],
            vec!["3", "", "30.0", "female"],
            vec!["4", "d", "", ""],
        ]
    );
    let patients = engine
        .patients(&query(ResultType::Patients, Some(required(NOTE))))
        .unwrap();
    assert_eq!(ids_of(&patients), [2, 4]);
}

#[test]
fn the_merged_dictionary_follows_summary_column_meta() {
    let d = overlapping();
    let engine = d.open(&lenient()).unwrap();
    let set = engine.partitions();
    let paths: Vec<&str> = set.dictionary().iter().map(|c| c.name.as_str()).collect();
    assert_eq!(paths, [AGE, SEX, NOTE]);
    let age = set.summary(AGE).unwrap();
    assert!(!age.categorical);
    assert_eq!((age.min, age.max), (Some(30.0), Some(55.0)));
    assert_eq!(age.patient_count, 3);
    assert!(age.category_values.is_empty());
    let note = set.summary(NOTE).unwrap();
    assert_eq!(note.category_values, ["a", "b", "c", "d"]);
    assert_eq!(note.patient_count, 3);
    assert_eq!((note.min, note.max), (None, None));
    let sex = set.summary(SEX).unwrap();
    assert_eq!(sex.category_values, ["female", "male"]);
    assert_eq!(
        sex.to_json(),
        format!(
            r#"{{"name":{},"widthInBytes":{},"categorical":true,"categoryValues":["female","male"],"min":null,"max":null,"patientCount":3,"timestampMin":null,"timestampMax":null}}"#,
            serde_json::to_string(SEX).unwrap(),
            sex.width_in_bytes
        )
    );
    let prefixed: Vec<&str> = set
        .dictionary_with_prefix("\\demographics\\")
        .iter()
        .map(|c| c.name.as_str())
        .collect();
    assert_eq!(prefixed, [AGE, SEX]);
    assert!(set.is_healthy());
}

#[test]
fn a_concept_typed_differently_in_two_partitions_refuses_to_open() {
    let mixed = "\\mixed\\";
    let d = partitioned_rows(&[
        ("p1", vec![(1, mixed, Some(5.0), None)]),
        ("p0", vec![(2, AGE, Some(1.0), None)]),
        ("p2", vec![(2, mixed, None, Some("y"))]),
    ]);
    let error = d.open(&lenient()).unwrap_err();
    match &error {
        QueryError::TypeConflict {
            concept_path,
            first_partition,
            first_type,
            second_partition,
            second_type,
        } => {
            assert_eq!(concept_path, mixed);
            assert_eq!((first_partition.as_str(), *first_type), ("p1", "numeric"));
            assert_eq!(
                (second_partition.as_str(), *second_type),
                ("p2", "categorical")
            );
        }
        other => panic!("expected TypeConflict, got {other:?}"),
    }
    assert!(!error.is_client_error());
    assert_eq!(
        error.to_string(),
        "concept \\mixed\\ is numeric in partition p1 but categorical in partition p2"
    );
}

#[test]
fn partition_key_files_must_match_the_shared_key() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1])), ("p2", numeric_rows(&[2]))]);
    std::fs::write(d.out.join("p1").join("encryption_key"), format!("{KEY}\n")).unwrap();
    d.open(&lenient()).unwrap();
    std::fs::write(
        d.out.join("p2").join("encryption_key"),
        "FEDCBA9876543210FEDCBA9876543210",
    )
    .unwrap();
    let error = d.open(&lenient()).unwrap_err();
    assert!(
        error.to_string().contains("differs from the shared key"),
        "{error}"
    );
}

#[test]
fn a_missing_shared_key_or_store_refuses_to_open() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1]))]);
    std::fs::remove_file(d.out.join("encryption_key")).unwrap();
    let error = d.open(&lenient()).unwrap_err();
    assert!(error.to_string().contains("no key file"), "{error}");
    let plain = hpds_query::PartitionOptions {
        store: StoreOptions {
            allow_plain: true,
            ..StoreOptions::default()
        },
        require_consents: false,
        combined: hpds_query::CombinedMode::Auto,
        per_partition_counts: hpds_query::PerPartitionCounts::Auto,
    };
    assert!(d.open(&plain).is_err());

    let empty = tempfile::tempdir().unwrap();
    std::fs::write(empty.path().join("encryption_key"), KEY).unwrap();
    std::fs::create_dir(empty.path().join("all")).unwrap();
    let error = Engine::open(empty.path(), &lenient()).unwrap_err();
    assert!(error.to_string().contains("no pheno.index"), "{error}");
}

#[test]
fn subdirectories_without_a_store_are_not_partitions() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1]))]);
    std::fs::create_dir(d.out.join("all")).unwrap();
    std::fs::create_dir(d.out.join("lost+found")).unwrap();
    let engine = d.open(&strict()).unwrap();
    let names: Vec<&str> = engine.partitions().partition_names().collect();
    assert_eq!(names, ["p1"]);
    let all = hpds_query::Query::with_consents(ResultType::Count, ["all"]);
    assert_eq!(engine.count(&all).unwrap(), 0);
}

/// The decoded size ([`hpds_format::Block::heap_size`]) of the block of `path` in the
/// store in `dir`, read with the ETL's reader, outside the engine.
fn block_heap_size(dir: &std::path::Path, path: &str) -> u64 {
    let store = hpds_etl::store::OpenStore::open(dir, protection()).unwrap();
    let concept = store.index.get(path).unwrap();
    store
        .reader
        .read_block(&store.codec, concept)
        .unwrap()
        .heap_size() as u64
}

#[test]
fn partitions_share_one_cache_budget() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1, 2])), ("p2", numeric_rows(&[3, 4]))]);
    let engine = d.open(&lenient()).unwrap();
    let set = engine.partitions();
    engine
        .count(&query(ResultType::Count, Some(required(CONCEPT))))
        .unwrap();
    assert_eq!(set.cached_block_count(), 2);
    let loaded =
        block_heap_size(&d.out.join("p1"), CONCEPT) + block_heap_size(&d.out.join("p2"), CONCEPT);
    assert!(
        set.cached_bytes() >= loaded,
        "the entry is weighed with both blocks: {} cached, {loaded} loaded",
        set.cached_bytes()
    );
    set.clear_cache();
    assert_eq!(set.cached_block_count(), 0);
    let only_p1 = Query {
        phenotypic_clause: Some(required(CONCEPT)),
        ..Query::with_consents(ResultType::Count, ["p1"])
    };
    engine.count(&only_p1).unwrap();
    assert_eq!(set.cached_block_count(), 1);
}

#[test]
fn a_small_cache_evicts_after_a_multi_partition_read() {
    const HEIGHT: &str = "\\demographics\\HEIGHT\\";
    let rows = |patients: &[u32]| -> Vec<Row<'static>> {
        numeric_rows(patients)
            .into_iter()
            .chain(
                patients
                    .iter()
                    .map(|&p| (p, HEIGHT, Some(f64::from(p) + 0.5), None)),
            )
            .collect()
    };
    let patients: Vec<u32> = (1..=400).collect();
    let (first, second) = patients.split_at(200);
    let d = partitioned_rows(&[("p1", rows(first)), ("p2", rows(second))]);
    let entry =
        |path| block_heap_size(&d.out.join("p1"), path) + block_heap_size(&d.out.join("p2"), path);
    let (age, height) = (entry(CONCEPT), entry(HEIGHT));
    let capacity = age.max(height) + age.min(height) / 2;
    let options = hpds_query::PartitionOptions {
        store: StoreOptions {
            cache_bytes: capacity,
            ..StoreOptions::default()
        },
        ..lenient()
    };
    let engine = d.open(&options).unwrap();
    let set = engine.partitions();
    for path in [CONCEPT, HEIGHT] {
        engine
            .count(&query(ResultType::Count, Some(required(path))))
            .unwrap();
    }
    assert!(
        set.cached_bytes() <= capacity,
        "{} cached, {capacity} allowed",
        set.cached_bytes()
    );
    assert_eq!(
        set.cached_block_count(),
        2,
        "one concept's two blocks were evicted or never admitted"
    );
}

#[test]
fn a_failed_block_load_still_weighs_the_blocks_it_read() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1, 2])), ("p2", numeric_rows(&[3, 4]))]);
    let engine = d.open(&lenient()).unwrap();
    let blocks = d.out.join("p2").join(hpds_format::BLOCKS_FILE_NAME);
    let len = std::fs::metadata(&blocks).unwrap().len();
    let file = std::fs::OpenOptions::new()
        .write(true)
        .open(&blocks)
        .unwrap();
    std::os::unix::fs::FileExt::write_all_at(&file, &[0xff; 8], len - 8).unwrap();
    drop(file);
    let set = engine.partitions();
    let error = engine
        .count(&query(ResultType::Count, Some(required(CONCEPT))))
        .unwrap_err();
    assert!(matches!(error, QueryError::Block { .. }), "{error}");
    assert_eq!(set.cached_block_count(), 1, "p1's block was read");
    let p1 = block_heap_size(&d.out.join("p1"), CONCEPT);
    assert!(
        set.cached_bytes() >= p1,
        "p1's block is weighed: {} cached, {p1} read",
        set.cached_bytes()
    );
}

#[test]
fn a_type_mismatch_is_raised_only_when_a_selected_partition_holds_the_concept() {
    let d = partitioned_rows(&[
        ("p1", vec![(1, SEX, None, Some("male"))]),
        ("p2", numeric_rows(&[2])),
    ]);
    let engine = d.open(&strict()).unwrap();
    let consented = |clause, consents: &[&str]| Query {
        phenotypic_clause: Some(clause),
        ..Query::with_consents(ResultType::Count, consents.iter().copied())
    };
    let numeric_on_sex = || filter_range(SEX, Some(0.0), None);
    let values_on_age = || filter_values(CONCEPT, &["40"]);
    assert_eq!(
        engine.count(&consented(numeric_on_sex(), &["p2"])).unwrap(),
        0
    );
    assert_eq!(
        engine.count(&consented(values_on_age(), &["p1"])).unwrap(),
        0
    );
    assert_eq!(
        engine
            .count(&consented(numeric_on_sex(), &["nope"]))
            .unwrap(),
        0
    );
    for (clause, consents) in [
        (numeric_on_sex(), ["p1", "p2"]),
        (values_on_age(), ["p2", "p1"]),
    ] {
        assert!(matches!(
            engine.count(&consented(clause, &consents)),
            Err(QueryError::TypeMismatch { .. })
        ));
    }
    assert!(matches!(
        engine.count(&consented(values_on_age(), &["p2"])),
        Err(QueryError::TypeMismatch { .. })
    ));
}

#[test]
fn a_half_migrated_partition_refuses_to_open() {
    for javabin in ["allObservationsStore.javabin", "columnMeta.javabin"] {
        let d = partitioned_rows(&[("p1", numeric_rows(&[1]))]);
        let half = d.out.join("phs000002.c1");
        std::fs::create_dir(&half).unwrap();
        std::fs::write(half.join(javabin), b"java").unwrap();
        let error = d.open(&lenient()).unwrap_err();
        let message = error.to_string();
        assert!(matches!(error, QueryError::Open { .. }), "{message}");
        assert!(message.contains("phs000002.c1"), "{message}");
        assert!(message.contains(javabin), "{message}");
        assert!(message.contains("half-migrated"), "{message}");
    }
}

#[test]
fn partition_discovery_ignores_dot_directories_and_reads_one_level() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1]))]);
    let hidden = d.out.join(".staging");
    std::fs::create_dir(&hidden).unwrap();
    std::fs::write(hidden.join("columnMeta.javabin"), b"java").unwrap();
    let nested = d.out.join("group").join("p2");
    std::fs::create_dir_all(&nested).unwrap();
    std::fs::write(nested.join("columnMeta.javabin"), b"java").unwrap();
    std::fs::write(d.out.join("p1").join("columnMeta.javabin"), b"java").unwrap();
    let engine = d.open(&lenient()).unwrap();
    assert_eq!(
        engine.partitions().partition_names().collect::<Vec<_>>(),
        ["p1"]
    );
}

fn store_id(d: &PartitionedDir, name: &str) -> String {
    let key = hpds_format::CryptoKey::load(d.out.join("encryption_key")).unwrap();
    let index = hpds_format::PhenoIndex::read_file(
        d.out.join(name).join(hpds_format::INDEX_FILE_NAME),
        &hpds_format::Protection::Encrypted(key),
    )
    .unwrap();
    index.store_id().to_string()
}

#[test]
fn a_generation_file_must_list_exactly_the_opened_stores() {
    let d = partitioned_rows(&[("p1", numeric_rows(&[1])), ("p2", numeric_rows(&[2]))]);
    let (p1, p2) = (store_id(&d, "p1"), store_id(&d, "p2"));
    let generation = d.out.join(".generation");
    std::fs::write(&generation, format!("{p1}\n\n{} p2\n", p2.to_uppercase())).unwrap();
    assert_eq!(
        d.open(&lenient())
            .unwrap()
            .count(&Query::new(ResultType::Count))
            .unwrap(),
        2
    );

    std::fs::write(&generation, format!("{p1}\n")).unwrap();
    let message = d.open(&lenient()).unwrap_err().to_string();
    assert!(message.contains("partially published"), "{message}");
    assert!(message.contains("p2"), "{message}");

    std::fs::write(&generation, format!("{p1}\n{p2}\n{}\n", "0".repeat(32))).unwrap();
    let message = d.open(&lenient()).unwrap_err().to_string();
    assert!(message.contains("partially published"), "{message}");

    std::fs::write(&generation, "not-an-id\n").unwrap();
    let message = d.open(&lenient()).unwrap_err().to_string();
    assert!(message.contains("not a store id"), "{message}");

    std::fs::remove_file(&generation).unwrap();
    d.open(&lenient()).unwrap();
}

#[test]
fn cross_counts_agree_for_narrow_and_broad_cohorts_over_shared_patients() {
    let wide: Vec<u32> = (1..=60).collect();
    let shifted: Vec<u32> = (41..=100).collect();
    let d = partitioned_rows(&[("p1", numeric_rows(&wide)), ("p2", numeric_rows(&shifted))]);
    let engine = d.open(&lenient()).unwrap();
    let mut q = query(
        ResultType::CrossCount,
        Some(filter_range(CONCEPT, Some(45.0), Some(46.0))),
    );
    q.select = vec![CONCEPT.into()];
    assert_eq!(engine.cross_count(&q).unwrap().get(CONCEPT), Some(2));
    q.phenotypic_clause = Some(filter_range(CONCEPT, Some(1.0), Some(100.0)));
    assert_eq!(engine.cross_count(&q).unwrap().get(CONCEPT), Some(100));
    q.phenotypic_clause = Some(filter_range(CONCEPT, Some(95.0), None));
    assert_eq!(engine.cross_count(&q).unwrap().get(CONCEPT), Some(6));
}
