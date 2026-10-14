//! The service's side of `hpds-etl remove-concepts`: a store whose publish was interrupted
//! is refused until `hpds-etl recover` finishes it.

mod common;

use common::*;
use hpds_etl::publish::{PublishStep, recover};
use hpds_etl::store_maintenance::{RemoveOptions, remove_concepts};
use hpds_query::{ResultType, UserConsent};

const AGE: &str = "\\demographics\\AGE\\";
const SEX: &str = "\\demographics\\SEX\\";

/// Writes `lines` as the concepts file of `d` and returns its path.
fn concepts_file(d: &PartitionedDir, lines: &[&str]) -> std::path::PathBuf {
    let path = d.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&path, lines.join("\n")).unwrap();
    path
}

/// Two partitions with no shared patient, each holding SEX and AGE.
fn two_partitions() -> PartitionedDir {
    partitioned_rows(&[
        (
            "p1",
            vec![(1, SEX, None, Some("male")), (1, AGE, Some(40.0), None)],
        ),
        (
            "p2",
            vec![(2, SEX, None, Some("female")), (2, AGE, Some(50.0), None)],
        ),
    ])
}

#[test]
fn an_interrupted_removal_keeps_the_service_down_until_recovered() {
    let d = two_partitions();
    let list = concepts_file(&d, &[AGE]);
    let options = RemoveOptions {
        stop_after: Some(PublishStep::BlocksMoved),
        ..RemoveOptions::default()
    };
    remove_concepts(&d.out, &list, &protection(), &options).unwrap_err();

    let message = d.open(&lenient()).unwrap_err().to_string();
    assert!(message.contains("hpds-etl recover"), "{message}");

    recover(&d.out, &protection()).unwrap();
    let engine = d.open(&lenient()).unwrap();
    let paths: Vec<&str> = engine
        .partitions()
        .dictionary()
        .iter()
        .map(|c| c.name.as_str())
        .collect();
    assert_eq!(paths, [SEX]);
    let mut count = query(ResultType::Count, Some(required(SEX)));
    count.user_consents = vec![UserConsent {
        value: Some("p2".into()),
    }];
    assert_eq!(engine.count(&count).unwrap(), 1);
}

#[test]
fn remove_concepts_refuses_a_store_a_service_holds_open() {
    let d = two_partitions();
    let list = concepts_file(&d, &[AGE]);
    let engine = d.open(&lenient()).unwrap();
    let index_before = std::fs::read(d.out.join("pheno.index")).unwrap();

    let error = remove_concepts(&d.out, &list, &protection(), &RemoveOptions::default())
        .unwrap_err()
        .to_string();

    assert!(error.contains("open in a running query service"), "{error}");
    assert!(error.contains("Stop the service"), "{error}");
    assert_eq!(
        std::fs::read(d.out.join("pheno.index")).unwrap(),
        index_before
    );
    let dry = remove_concepts(
        &d.out,
        &list,
        &protection(),
        &RemoveOptions {
            dry_run: true,
            ..RemoveOptions::default()
        },
    )
    .unwrap();
    assert!(dry.store_in_use);
    assert_eq!(
        engine
            .count(&query(ResultType::Count, Some(required(AGE))))
            .unwrap(),
        2
    );

    drop(engine);
    let report = remove_concepts(&d.out, &list, &protection(), &RemoveOptions::default()).unwrap();
    assert_eq!(report.removed, 1);
}

#[test]
fn a_service_opening_during_a_publish_waits_for_it() {
    let d = two_partitions();
    let index = d.out.join("pheno.index");
    let writer = std::fs::File::open(&index).unwrap();
    writer.lock().unwrap();
    let out = d.out.clone();
    let opening =
        std::thread::spawn(move || hpds_query::Engine::open(&out, &lenient()).map(|_| ()));
    std::thread::sleep(std::time::Duration::from_millis(300));
    assert!(!opening.is_finished(), "the open waits for the lock");
    drop(writer);
    opening.join().unwrap().unwrap();
}

#[test]
fn a_service_refuses_queries_once_its_store_is_replaced_or_a_publish_begins() {
    for replace in [true, false] {
        let d = two_partitions();
        let engine = d.open(&lenient()).unwrap();
        let age = query(ResultType::Count, Some(required(AGE)));
        assert_eq!(engine.count(&age).unwrap(), 2);
        if replace {
            let copy = d.out.join("index-copy");
            std::fs::copy(d.out.join("pheno.index"), &copy).unwrap();
            std::fs::rename(&copy, d.out.join("pheno.index")).unwrap();
        } else {
            std::fs::write(d.out.join(".publish-journal"), "{}").unwrap();
        }
        std::thread::sleep(std::time::Duration::from_millis(1100));

        let error = engine.count(&age).unwrap_err();

        assert!(
            matches!(error, hpds_query::QueryError::StoreChanged { .. }),
            "{error}"
        );
        assert!(!error.is_client_error());
        assert!(error.to_string().contains("restart the service"), "{error}");
        assert!(engine.partitions().check_unchanged().is_err());
    }
}

#[test]
fn removing_an_overlap_patients_rows_narrows_the_tagged_sets_the_guard_reads() {
    const NOTE: &str = "\\notes\\NOTE\\";
    let d = partitioned_rows(&[
        (
            "p1",
            vec![(1, SEX, None, Some("male")), (2, NOTE, None, Some("a"))],
        ),
        (
            "p2",
            vec![(2, AGE, Some(40.0), None), (3, AGE, Some(30.0), None)],
        ),
    ]);
    let tagged = |d: &PartitionedDir| -> Vec<Vec<u32>> {
        let index =
            hpds_format::PhenoIndex::read_file(d.out.join("pheno.index"), &protection()).unwrap();
        index
            .partition_table()
            .partitions
            .iter()
            .map(|p| p.tagged.iter().collect())
            .collect()
    };
    assert_eq!(tagged(&d), [vec![2], vec![2]]);
    let list = concepts_file(&d, &[NOTE]);

    let report = remove_concepts(&d.out, &list, &protection(), &RemoveOptions::default()).unwrap();

    assert_eq!(report.tagged_patients_dropped, 1);
    assert_eq!(tagged(&d), [vec![], vec![2]]);
    let engine = d.open(&lenient()).unwrap();
    assert_eq!(engine.partitions().overlap_patients(), 1);
    let consent = |name: &str| UserConsent {
        value: Some(name.into()),
    };
    let mut p1_sex = query(ResultType::Count, Some(required(SEX)));
    p1_sex.user_consents = vec![consent("p1")];
    assert_eq!(engine.count(&p1_sex).unwrap(), 1);
    let mut p1_all = query(ResultType::Count, None);
    p1_all.user_consents = vec![consent("p1")];
    assert_eq!(
        engine.count(&p1_all).unwrap(),
        2,
        "p1's patient set is kept"
    );
    let mut p2_age = query(ResultType::Count, Some(required(AGE)));
    p2_age.user_consents = vec![consent("p2")];
    assert_eq!(engine.count(&p2_age).unwrap(), 2);
    let mut p1_age = p2_age.clone();
    p1_age.user_consents = vec![consent("p1")];
    assert_eq!(engine.count(&p1_age).unwrap(), 0);
    assert_eq!(engine.partitions().cohort_guard_refusals(), 0);
}

/// A change made to the files of a data directory.
type StoreChange = fn(&std::path::Path);

#[test]
fn a_service_refuses_queries_once_a_file_it_holds_open_changes_in_place() {
    let changes: [(&str, StoreChange); 3] = [
        ("blocks appended in place", |out| {
            use std::io::Write;
            let mut blocks = std::fs::OpenOptions::new()
                .append(true)
                .open(out.join("pheno.blocks"))
                .unwrap();
            blocks.write_all(&[0]).unwrap();
        }),
        ("index copied over in place", |out| {
            let bytes = std::fs::read(out.join("pheno.index")).unwrap();
            let mut padded = bytes.clone();
            padded.push(0);
            std::fs::write(out.join("pheno.index"), padded).unwrap();
        }),
        ("index touched", |out| {
            let index = std::fs::File::options()
                .write(true)
                .open(out.join("pheno.index"))
                .unwrap();
            index
                .set_modified(std::time::SystemTime::now() + std::time::Duration::from_secs(5))
                .unwrap();
        }),
    ];
    for (what, change) in changes {
        let d = two_partitions();
        let engine = d.open(&lenient()).unwrap();
        let age = query(ResultType::Count, Some(required(AGE)));
        assert_eq!(engine.count(&age).unwrap(), 2, "{what}");
        change(&d.out);
        std::thread::sleep(std::time::Duration::from_millis(1100));

        let error = engine.count(&age).unwrap_err();

        assert!(
            matches!(error, hpds_query::QueryError::StoreChanged { .. }),
            "{what}: {error}"
        );
    }
}
