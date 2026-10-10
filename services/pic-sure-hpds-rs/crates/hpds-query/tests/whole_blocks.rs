//! Whole blocks and partitions: which queries read a concept's whole block, that the rows a
//! narrower selection reads are exactly the rows of its partitions, and that both answer as
//! the partitions' own stores would, byte for byte, overlapping patients included.

mod common;

use std::path::Path;

use common::{
    PartitionedDir, Row, answers, answers_selecting, clauses, consent_lists, fixture_texts,
    harm_fixture, lenient, load_csv, partitioned_rows, strict,
};
use hpds_query::{
    COMBINED_DIR_NAME, CombinedMode, CombinedStatus, Engine, PartitionOptions, Query, QueryError,
    ResultType,
};

const FIXTURE_PARTITIONS: [&str; 4] = ["partition1", "partition2", "partition3", "partition4"];

fn with_mode(options: PartitionOptions, combined: CombinedMode) -> PartitionOptions {
    PartitionOptions {
        combined,
        ..options
    }
}

fn open(dir: &Path, combined: CombinedMode) -> Engine {
    Engine::open(dir, &with_mode(lenient(), combined)).unwrap()
}

fn selects_all(consents: &[String], partitions: &[&str]) -> bool {
    consents.is_empty() || partitions.iter().all(|p| consents.iter().any(|c| c == p))
}

#[test]
fn the_format_1_combined_directory_name_selects_nothing() {
    assert!(hpds_etl::partitions::check_partition_name(COMBINED_DIR_NAME).is_err());
    let dir = harm_fixture();
    let engine = open(&dir.out, CombinedMode::Auto);
    let names: Vec<&str> = engine.partitions().partition_names().collect();
    assert_eq!(names, FIXTURE_PARTITIONS);
    let only_combined = Query::with_consents(ResultType::Count, [COMBINED_DIR_NAME]);
    assert_eq!(engine.count(&only_combined).unwrap(), 0);
    let plan = engine.read_plan(&only_combined).unwrap();
    assert!(plan.partitions.is_empty() && !plan.combined);
}

#[test]
fn only_a_selection_of_every_partition_reads_whole_blocks() {
    let dir = harm_fixture();
    let auto = open(&dir.out, CombinedMode::Auto);
    let off = open(&dir.out, CombinedMode::Off);
    assert_eq!(auto.partitions().combined_status(), CombinedStatus::InUse);
    assert_eq!(off.partitions().combined_status(), CombinedStatus::Off);
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        for result_type in [
            ResultType::Count,
            ResultType::Dataframe,
            ResultType::Patients,
        ] {
            let query = Query::with_consents(result_type, consents.iter().map(String::as_str));
            let plan = auto.read_plan(&query).unwrap();
            let expected: Vec<&str> = if consents.is_empty() {
                FIXTURE_PARTITIONS.to_vec()
            } else {
                FIXTURE_PARTITIONS
                    .iter()
                    .copied()
                    .filter(|p| consents.iter().any(|c| c == p))
                    .collect()
            };
            assert_eq!(plan.partitions, expected, "{consents:?}");
            assert_eq!(
                plan.combined,
                selects_all(&consents, &FIXTURE_PARTITIONS),
                "{consents:?}"
            );
            assert!(!off.read_plan(&query).unwrap().combined, "{consents:?}");
        }
    }
    let strict_engine = Engine::open(&dir.out, &with_mode(strict(), CombinedMode::Auto)).unwrap();
    let none = Query::new(ResultType::Count);
    assert!(matches!(
        strict_engine.read_plan(&none),
        Err(QueryError::UserConsentsRequired)
    ));
    let all = Query::with_consents(ResultType::Count, FIXTURE_PARTITIONS);
    assert!(strict_engine.read_plan(&all).unwrap().combined);
    let narrow = Query::with_consents(ResultType::Count, ["partition2"]);
    assert!(!strict_engine.read_plan(&narrow).unwrap().combined);
}

#[test]
fn every_answer_from_whole_blocks_matches_the_partitions() {
    let dir = harm_fixture();
    let auto = open(&dir.out, CombinedMode::Auto);
    let off = open(&dir.out, CombinedMode::Off);
    let clauses = clauses(off.partitions().dictionary());
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
}

#[test]
fn a_selection_of_one_partition_answers_as_that_partition_alone() {
    let dir = harm_fixture();
    let engine = open(&dir.out, CombinedMode::Auto);
    for (name, text) in fixture_texts() {
        let alone = load_csv(&text);
        let dictionary = alone.engine.partitions().dictionary();
        let select: Vec<String> = dictionary.iter().map(|c| c.name.clone()).collect();
        let clauses = clauses(dictionary);
        assert_eq!(
            answers_selecting(&engine, std::slice::from_ref(&name), &clauses, &select),
            answers_selecting(&alone.engine, &[], &clauses, &select),
            "{name}"
        );
    }
}

/// Rows for two partitions that share every patient and concept, with timestamps absent
/// and values that tell the partitions apart.
fn shared_patient_rows(tag: &'static str, base: f64) -> Vec<Row<'static>> {
    let mut rows = Vec::new();
    for patient in 1..=6u32 {
        let value = base + f64::from(patient);
        rows.push((patient, "\\s\\num\\", Some(value), None));
        rows.push((patient, "\\s\\num\\", Some(value + 0.5), None));
        rows.push((patient, "\\s\\cat\\", None, Some(tag)));
        if patient % 2 == 0 {
            rows.push((patient, "\\s\\cat\\", None, Some("shared")));
        }
    }
    rows
}

#[test]
fn ties_follow_java_partition_name_order_even_where_bytes_disagree() {
    let early = "\u{1F600}";
    let late = "\u{FF61}";
    assert!(late.as_bytes() < early.as_bytes());
    let d = partitioned_rows(&[
        (late, shared_patient_rows("late", 100.0)),
        (early, shared_patient_rows("early", 200.0)),
        ("zzz", shared_patient_rows("zzz", 300.0)),
    ]);
    let auto = open(&d.out, CombinedMode::Auto);
    let off = open(&d.out, CombinedMode::Off);
    let names: Vec<&str> = off.partitions().partition_names().collect();
    assert_eq!(names, ["zzz", early, late], "Java string order");
    let query = Query {
        select: vec!["\\s\\num\\".into(), "\\s\\cat\\".into()],
        ..Query::new(ResultType::Dataframe)
    };
    assert!(auto.read_plan(&query).unwrap().combined);
    assert!(!off.read_plan(&query).unwrap().combined);
    let frame = |engine: &Engine| engine.dataframe(&query).unwrap().rows.collect::<Vec<_>>();
    let whole = frame(&auto);
    assert_eq!(whole, frame(&off));
    assert_eq!(whole[1][1], "302.0\t302.5\t202.0\t202.5\t102.0\t102.5");
    assert_eq!(whole[1][2], "zzz\tshared\tearly\tshared\tlate\tshared");
    let clauses = clauses(off.partitions().dictionary());
    for consents in consent_lists(&[early, late, "zzz"]) {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
}

/// Three partitions where patients 2 and 5 are in two each, with rows that differ by
/// partition: the overlap set is {2, 5}, and the blocks holding their rows are tagged.
fn overlapping() -> (PartitionedDir, Vec<(&'static str, Vec<Row<'static>>)>) {
    let rows = vec![
        (
            "a",
            vec![
                (1, "\\o\\num\\", Some(1.0), None),
                (2, "\\o\\num\\", Some(2.0), None),
                (2, "\\o\\cat\\", None, Some("x")),
                (3, "\\o\\only_a\\", None, Some("q")),
            ],
        ),
        (
            "b",
            vec![
                (2, "\\o\\num\\", Some(20.0), None),
                (2, "\\o\\cat\\", None, Some("y")),
                (4, "\\o\\cat\\", None, Some("x")),
                (5, "\\o\\num\\", Some(5.0), None),
            ],
        ),
        (
            "c",
            vec![
                (5, "\\o\\num\\", Some(50.0), None),
                (5, "\\o\\cat\\", None, Some("z")),
                (6, "\\o\\num\\", Some(6.0), None),
            ],
        ),
    ];
    (partitioned_rows(&rows), rows)
}

#[test]
fn overlapping_patients_answer_as_their_partitions_alone_in_every_selection() {
    let (dir, rows) = overlapping();
    let store = hpds_etl::store::OpenStore::open(&dir.out, common::protection()).unwrap();
    let table = store.index.partition_table();
    assert_eq!(table.overlap.iter().collect::<Vec<_>>(), [2, 5]);
    assert!(store.index.get("\\o\\num\\").unwrap().tagged);
    assert!(store.index.get("\\o\\cat\\").unwrap().tagged);
    assert!(!store.index.get("\\o\\only_a\\").unwrap().tagged);
    let auto = open(&dir.out, CombinedMode::Auto);
    let off = open(&dir.out, CombinedMode::Off);
    assert!(!auto.partitions().partitions_disjoint());
    let clauses = clauses(off.partitions().dictionary());
    for consents in consent_lists(&["a", "b", "c"]) {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
    let select: Vec<String> = off
        .partitions()
        .dictionary()
        .iter()
        .map(|c| c.name.clone())
        .collect();
    for (name, partition_rows) in &rows {
        let alone = common::store(partition_rows);
        let own: Vec<String> = alone
            .engine
            .partitions()
            .dictionary()
            .iter()
            .map(|c| c.name.clone())
            .collect();
        let own_clauses = clauses_of(&alone.engine);
        assert_eq!(
            answers_selecting(&auto, &[(*name).to_owned()], &own_clauses, &own),
            answers_selecting(&alone.engine, &[], &own_clauses, &own),
            "{name}"
        );
    }
    let query = Query {
        select,
        ..Query::with_consents(ResultType::Dataframe, ["b", "c"])
    };
    let frame: Vec<Vec<String>> = auto.dataframe(&query).unwrap().rows.collect();
    let patients: Vec<&str> = frame.iter().map(|row| row[0].as_str()).collect();
    assert_eq!(patients, ["2", "4", "5", "6"]);
}

fn clauses_of(engine: &Engine) -> Vec<Option<hpds_query::PhenotypicClause>> {
    clauses(engine.partitions().dictionary())
}
