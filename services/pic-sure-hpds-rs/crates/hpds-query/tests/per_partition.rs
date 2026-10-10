//! Per-partition counts: on patient-disjoint partitions that carry the ETL's
//! `patients-indexed` record, cross counts over two or more partitions sum each
//! partition's count instead of counting the union. Every answer must be the answer of the
//! union (`HPDS_PER_PARTITION_COUNTS=off`), and partitions that share a patient, or whose
//! blocks may hold a patient outside their patient set, must keep counting the union.

mod common;

use std::path::Path;

use common::{
    add_combined, answers, clauses, consent_lists, harm_fixture, lenient, mark_patients_indexed,
    partitioned_rows, protection, required,
};
use hpds_query::{CombinedMode, Engine, PartitionOptions, PerPartitionCounts, Query, ResultType};

const FIXTURE_PARTITIONS: [&str; 4] = ["partition1", "partition2", "partition3", "partition4"];

fn options(per_partition_counts: PerPartitionCounts) -> PartitionOptions {
    PartitionOptions {
        per_partition_counts,
        ..lenient()
    }
}

fn selected(consents: &[String]) -> usize {
    if consents.is_empty() {
        FIXTURE_PARTITIONS.len()
    } else {
        FIXTURE_PARTITIONS
            .iter()
            .filter(|p| consents.iter().any(|c| c == *p))
            .count()
    }
}

#[test]
fn disjoint_partitions_answer_the_same_with_and_without_per_partition_counts() {
    let dir = harm_fixture();
    mark_patients_indexed(&dir.out);
    let auto = Engine::open(&dir.out, &options(PerPartitionCounts::Auto)).unwrap();
    let off = Engine::open(&dir.out, &options(PerPartitionCounts::Off)).unwrap();
    assert!(auto.partitions().partitions_disjoint());
    assert!(auto.partitions().counts_per_partition());
    assert!(!off.partitions().counts_per_partition());
    let clauses = clauses(auto.partitions().dictionary());
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        let query = Query::with_consents(ResultType::Count, consents.iter().map(String::as_str));
        assert_eq!(
            auto.read_plan(&query).unwrap().per_partition_counts,
            selected(&consents) > 1,
            "{consents:?}"
        );
        assert!(!off.read_plan(&query).unwrap().per_partition_counts);
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
}

#[test]
fn a_full_selection_reads_the_combined_store_instead() {
    let dir = harm_fixture();
    mark_patients_indexed(&dir.out);
    add_combined(&dir.out);
    let engine = Engine::open(
        &dir.out,
        &PartitionOptions {
            combined: CombinedMode::Auto,
            ..options(PerPartitionCounts::Auto)
        },
    )
    .unwrap();
    let all = Query::with_consents(ResultType::Count, FIXTURE_PARTITIONS);
    let plan = engine.read_plan(&all).unwrap();
    assert!(plan.combined && !plan.per_partition_counts);
    let two = Query::with_consents(ResultType::Count, ["partition1", "partition3"]);
    let plan = engine.read_plan(&two).unwrap();
    assert!(!plan.combined && plan.per_partition_counts);
}

#[test]
fn partitions_sharing_a_patient_keep_counting_the_union() {
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
    mark_patients_indexed(&dir.out);
    let auto = Engine::open(&dir.out, &options(PerPartitionCounts::Auto)).unwrap();
    let off = Engine::open(&dir.out, &options(PerPartitionCounts::Off)).unwrap();
    assert!(!auto.partitions().partitions_disjoint());
    assert!(!auto.partitions().counts_per_partition());
    let both = |result_type| Query {
        phenotypic_clause: Some(required("\\a\\")),
        select: vec!["\\a\\".to_owned(), "\\b\\".to_owned()],
        ..Query::with_consents(result_type, ["p1", "p2"])
    };
    assert!(
        !auto
            .read_plan(&both(ResultType::CrossCount))
            .unwrap()
            .per_partition_counts
    );
    let cross = auto.run_sync(&both(ResultType::CrossCount)).unwrap().body();
    assert_eq!(
        cross, r#"{"\\a\\":2,"\\b\\":1}"#,
        "patient 1 is in both partitions and counts once"
    );
    let clauses = clauses(auto.partitions().dictionary());
    for consents in [vec!["p1".to_owned(), "p2".to_owned()], Vec::new()] {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses)
        );
    }
}

#[test]
fn a_store_without_the_record_keeps_counting_the_union() {
    let dir = harm_fixture();
    let engine = Engine::open(&dir.out, &options(PerPartitionCounts::Auto)).unwrap();
    assert!(engine.partitions().partitions_disjoint());
    assert_eq!(engine.partitions().patients_indexed_partitions(), 0);
    assert!(!engine.partitions().counts_per_partition());
    let two = Query::with_consents(ResultType::Count, ["partition1", "partition3"]);
    assert!(!engine.read_plan(&two).unwrap().per_partition_counts);
}

/// Rewrites the index of the store in `dir` with `patients` as its patient set, keeping its
/// store id, concepts and blocks, as a migrated store whose exported patient list misses a
/// patient its observations hold.
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

#[test]
fn a_block_patient_missing_from_its_patient_set_keeps_counting_the_union() {
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
                (3, "\\b\\", None, Some("y")),
            ],
        ),
    ];
    let dir = partitioned_rows(&rows);
    replace_patient_set(&dir.out.join("p2"), &[3]);
    let p1_id = hpds_etl::store::read_index(&dir.out.join("p1"), &protection())
        .unwrap()
        .store_id();
    let p2_id = hpds_etl::store::read_index(&dir.out.join("p2"), &protection())
        .unwrap()
        .store_id();
    std::fs::write(
        dir.out.join(".generation"),
        format!("{p1_id} patients-indexed\n{p2_id}\n"),
    )
    .unwrap();
    let auto = Engine::open(&dir.out, &options(PerPartitionCounts::Auto)).unwrap();
    let off = Engine::open(&dir.out, &options(PerPartitionCounts::Off)).unwrap();
    let set = auto.partitions();
    assert!(
        set.partitions_disjoint(),
        "the patient sets are disjoint, the blocks are not"
    );
    assert_eq!(set.patients_indexed_partitions(), 1);
    assert!(!set.counts_per_partition());
    let both = |result_type| Query {
        select: vec!["\\a\\".to_owned(), "\\b\\".to_owned()],
        ..Query::with_consents(result_type, ["p1", "p2"])
    };
    for result_type in [ResultType::CrossCount, ResultType::CategoricalCrossCount] {
        assert!(
            !auto
                .read_plan(&both(result_type))
                .unwrap()
                .per_partition_counts
        );
        assert_eq!(
            auto.run_sync(&both(result_type)).unwrap().body(),
            off.run_sync(&both(result_type)).unwrap().body(),
            "{result_type:?}"
        );
    }
    assert_eq!(
        auto.run_sync(&both(ResultType::CrossCount)).unwrap().body(),
        r#"{"\\a\\":2,"\\b\\":2}"#,
        "patient 1 is in both partitions' blocks and counts once"
    );
    let clauses = clauses(set.dictionary());
    for consents in [vec!["p1".to_owned(), "p2".to_owned()], Vec::new()] {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses)
        );
    }
}
