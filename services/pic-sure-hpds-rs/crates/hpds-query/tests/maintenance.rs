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
