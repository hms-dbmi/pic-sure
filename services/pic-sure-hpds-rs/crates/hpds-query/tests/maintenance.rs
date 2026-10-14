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
