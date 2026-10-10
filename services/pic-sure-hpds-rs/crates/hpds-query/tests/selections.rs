//! Consent selections over one store: a selection of every partition reads whole blocks,
//! and every narrower selection, whether it reads whole blocks or partition views, answers
//! byte for byte as a store of its partitions alone would, overlapping patients and patients
//! missing from their partition's patient set included.

mod common;

use std::path::Path;

use common::{
    PartitionedDir, Row, SHARES, answers_selecting, assert_selections_match_their_partitions,
    clauses, consent_lists, csv, fixture_texts, harm_fixture, lenient, load_csv,
    partitioned_adjusted, partitioned_rows, protection, required, strict, with_share,
};
use hpds_query::{COMBINED_DIR_NAME, Engine, Query, QueryError, ResultType};

const FIXTURE_PARTITIONS: [&str; 4] = ["partition1", "partition2", "partition3", "partition4"];

fn open(dir: &Path) -> Engine {
    Engine::open(dir, &lenient()).unwrap()
}

fn selects_all(consents: &[String], partitions: &[&str]) -> bool {
    consents.is_empty() || partitions.iter().all(|p| consents.iter().any(|c| c == p))
}

fn texts_of<'a>(rows: &[(&'a str, Vec<Row<'_>>)]) -> Vec<(&'a str, String)> {
    rows.iter().map(|(name, rows)| (*name, csv(rows))).collect()
}

#[test]
fn the_format_1_combined_directory_name_selects_nothing() {
    assert!(hpds_etl::partitions::check_partition_name(COMBINED_DIR_NAME).is_err());
    let dir = harm_fixture();
    let engine = open(&dir.out);
    let names: Vec<&str> = engine.partitions().partition_names().collect();
    assert_eq!(names, FIXTURE_PARTITIONS);
    let only_combined = Query::with_consents(ResultType::Count, [COMBINED_DIR_NAME]);
    assert_eq!(engine.count(&only_combined).unwrap(), 0);
    let plan = engine.read_plan(&only_combined).unwrap();
    assert!(plan.partitions.is_empty() && !plan.full);
}

#[test]
fn only_a_selection_of_every_partition_is_full() {
    let dir = harm_fixture();
    let engine = open(&dir.out);
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        for result_type in [
            ResultType::Count,
            ResultType::Dataframe,
            ResultType::Patients,
        ] {
            let query = Query::with_consents(result_type, consents.iter().map(String::as_str));
            let plan = engine.read_plan(&query).unwrap();
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
                plan.full,
                selects_all(&consents, &FIXTURE_PARTITIONS),
                "{consents:?}"
            );
        }
    }
    let strict_engine = Engine::open(&dir.out, &strict()).unwrap();
    let none = Query::new(ResultType::Count);
    assert!(matches!(
        strict_engine.read_plan(&none),
        Err(QueryError::UserConsentsRequired)
    ));
    let all = Query::with_consents(ResultType::Count, FIXTURE_PARTITIONS);
    assert!(strict_engine.read_plan(&all).unwrap().full);
    let narrow = Query::with_consents(ResultType::Count, ["partition2"]);
    assert!(!strict_engine.read_plan(&narrow).unwrap().full);
}

#[test]
fn every_selection_of_the_fixture_answers_as_its_partitions_alone() {
    let texts = fixture_texts();
    let refs: Vec<(&str, String)> = texts.iter().map(|(n, t)| (n.as_str(), t.clone())).collect();
    let dir = harm_fixture();
    assert_selections_match_their_partitions(&dir, &refs, |_, _| false);
}

#[test]
fn a_selection_of_one_partition_answers_as_that_partition_alone() {
    let dir = harm_fixture();
    for share in SHARES {
        let engine = Engine::open(&dir.out, &with_share(lenient(), share)).unwrap();
        for (name, text) in fixture_texts() {
            let alone = load_csv(&text);
            let dictionary = alone.engine.partitions().dictionary();
            let select: Vec<String> = dictionary.iter().map(|c| c.name.clone()).collect();
            let clauses = clauses(dictionary);
            assert_eq!(
                answers_selecting(&engine, std::slice::from_ref(&name), &clauses, &select),
                answers_selecting(&alone.engine, &[], &clauses, &select),
                "{name} at share {share}"
            );
        }
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
    let rows = [
        (late, shared_patient_rows("late", 100.0)),
        (early, shared_patient_rows("early", 200.0)),
        ("zzz", shared_patient_rows("zzz", 300.0)),
    ];
    let d = partitioned_rows(&rows);
    let engine = open(&d.out);
    let names: Vec<&str> = engine.partitions().partition_names().collect();
    assert_eq!(names, ["zzz", early, late], "Java string order");
    let query = Query {
        select: vec!["\\s\\num\\".into(), "\\s\\cat\\".into()],
        ..Query::new(ResultType::Dataframe)
    };
    assert!(engine.read_plan(&query).unwrap().full);
    let whole: Vec<Vec<String>> = engine.dataframe(&query).unwrap().rows.collect();
    assert_eq!(whole[1][1], "302.0\t302.5\t202.0\t202.5\t102.0\t102.5");
    assert_eq!(whole[1][2], "zzz\tshared\tearly\tshared\tlate\tshared");
    let narrow = Query {
        select: query.select.clone(),
        ..Query::with_consents(ResultType::Dataframe, ["zzz", late])
    };
    for share in SHARES {
        let engine = Engine::open(&d.out, &with_share(lenient(), share)).unwrap();
        let frame: Vec<Vec<String>> = engine.dataframe(&narrow).unwrap().rows.collect();
        assert_eq!(frame[1][1], "302.0\t302.5\t102.0\t102.5", "share {share}");
        assert_eq!(frame[1][2], "zzz\tshared\tlate\tshared", "share {share}");
    }
    assert_selections_match_their_partitions(&d, &texts_of(&rows), |_, _| false);
}

/// Three partitions where patients 2 and 5 are in two each, with rows that differ by
/// partition: the overlap set is {2, 5}, and the blocks holding their rows are tagged.
fn overlapping() -> Vec<(&'static str, Vec<Row<'static>>)> {
    vec![
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
    ]
}

#[test]
fn overlapping_patients_answer_as_their_partitions_alone_in_every_selection() {
    let rows = overlapping();
    let dir = partitioned_rows(&rows);
    let store = hpds_etl::store::OpenStore::open(&dir.out, protection()).unwrap();
    let table = store.index.partition_table();
    assert_eq!(table.overlap.iter().collect::<Vec<_>>(), [2, 5]);
    assert!(store.index.get("\\o\\num\\").unwrap().tagged);
    assert!(store.index.get("\\o\\cat\\").unwrap().tagged);
    assert!(!store.index.get("\\o\\only_a\\").unwrap().tagged);
    let engine = open(&dir.out);
    assert_eq!(engine.partitions().overlap_patients(), 2);
    assert_selections_match_their_partitions(&dir, &texts_of(&rows), |_, _| false);
    for share in SHARES {
        let engine = Engine::open(&dir.out, &with_share(lenient(), share)).unwrap();
        for (name, partition_rows) in &rows {
            let alone = common::store(partition_rows);
            let dictionary = alone.engine.partitions().dictionary();
            let own: Vec<String> = dictionary.iter().map(|c| c.name.clone()).collect();
            let own_clauses = clauses(dictionary);
            assert_eq!(
                answers_selecting(&engine, &[(*name).to_owned()], &own_clauses, &own),
                answers_selecting(&alone.engine, &[], &own_clauses, &own),
                "{name} at share {share}"
            );
        }
        let select: Vec<String> = engine
            .partitions()
            .dictionary()
            .iter()
            .map(|c| c.name.clone())
            .collect();
        let query = Query {
            select,
            ..Query::with_consents(ResultType::Dataframe, ["b", "c"])
        };
        let frame: Vec<Vec<String>> = engine.dataframe(&query).unwrap().rows.collect();
        let patients: Vec<&str> = frame.iter().map(|row| row[0].as_str()).collect();
        assert_eq!(patients, ["2", "4", "5", "6"], "share {share}");
        assert_eq!(
            frame[0][2], "20.0",
            "patient 2's row of partition a is left out"
        );
    }
}

#[test]
fn a_patient_in_two_partitions_counts_once() {
    let rows = vec![
        (
            "p1",
            vec![
                (1, "\\a\\", Some(1.0), None),
                (1, "\\b\\", None, Some("x")),
                (2, "\\a\\", Some(6.0), None),
            ],
        ),
        (
            "p2",
            vec![
                (1, "\\a\\", Some(5.0), None),
                (1, "\\b\\", None, Some("x")),
                (3, "\\b\\", None, Some("x")),
            ],
        ),
    ];
    let dir = partitioned_rows(&rows);
    let both = Query {
        phenotypic_clause: Some(required("\\a\\")),
        select: vec!["\\a\\".to_owned(), "\\b\\".to_owned()],
        ..Query::with_consents(ResultType::CrossCount, ["p1", "p2"])
    };
    assert_eq!(
        open(&dir.out).run_sync(&both).unwrap().body(),
        r#"{"\\a\\":2,"\\b\\":1}"#,
        "patient 1 is in both partitions and counts once"
    );
    assert_selections_match_their_partitions(&dir, &texts_of(&rows), |_, _| false);
}

/// Rewrites the index of the unpartitioned store in `dir` with `patients` as its patient
/// set, keeping its store id, concepts and blocks, as a migrated store whose exported
/// patient list misses a patient its observations hold.
fn replace_patient_set(dir: &Path, patients: &[u32]) {
    let index = hpds_etl::store::read_index(dir, &protection()).unwrap();
    let rewritten = hpds_format::PhenoIndex::new(
        index.header().clone(),
        patients.iter().copied().collect(),
        index.concepts().to_vec(),
    )
    .unwrap();
    std::fs::write(
        dir.join(hpds_format::INDEX_FILE_NAME),
        rewritten.encode(&protection()).unwrap(),
    )
    .unwrap();
}

/// Partition p2's patient set loses patient 1, whose rows it keeps, and patient 4, who is
/// in no other partition's set.
fn with_gaps(name: &str, part: &Path) -> bool {
    if name == "p2" {
        replace_patient_set(part, &[3]);
    }
    name == "p2"
}

#[test]
fn patients_missing_from_their_partition_set_answer_as_their_partitions_alone() {
    let rows = [
        (
            "p1",
            vec![
                (1, "\\a\\", Some(1.0), None),
                (1, "\\b\\", None, Some("x")),
                (2, "\\a\\", Some(6.0), None),
            ],
        ),
        (
            "p2",
            vec![
                (1, "\\a\\", Some(5.0), None),
                (1, "\\b\\", None, Some("x")),
                (3, "\\b\\", None, Some("y")),
                (4, "\\a\\", Some(9.0), None),
            ],
        ),
    ];
    let texts = texts_of(&rows);
    let dir: PartitionedDir = partitioned_adjusted(&texts, with_gaps);
    let engine = open(&dir.out);
    assert_eq!(engine.partitions().overlap_patients(), 2);
    let both = Query {
        select: vec!["\\a\\".to_owned(), "\\b\\".to_owned()],
        ..Query::with_consents(ResultType::CrossCount, ["p1", "p2"])
    };
    assert_eq!(
        engine.run_sync(&both).unwrap().body(),
        r#"{"\\a\\":2,"\\b\\":2}"#,
        "the cohort is the partitions' patient sets; patient 1 counts once"
    );
    let only_p2 = Query {
        phenotypic_clause: Some(required("\\a\\")),
        ..Query::with_consents(ResultType::Patients, ["p2"])
    };
    for share in SHARES {
        let engine = Engine::open(&dir.out, &with_share(lenient(), share)).unwrap();
        let cohort: Vec<u32> = engine.patients(&only_p2).unwrap().iter().collect();
        assert_eq!(cohort, [1, 4], "rows tagged p2 put their patients in it");
    }
    assert_selections_match_their_partitions(&dir, &texts, with_gaps);
}
