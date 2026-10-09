//! `remove-concepts`, `column-meta-csv` and `dump` on stores built by `load`, through the
//! library and through the `hpds-etl` binary.

mod common;

use std::path::Path;
use std::process::Command;

use common::{KEY, Store, fixture, load_files};
use hpds_etl::column_meta_csv::write_column_meta_csv;
use hpds_etl::dump::{dump, dump_patients};
use hpds_etl::load::Mode;
use hpds_etl::maintain::{regenerate_column_meta_csv, remove_concepts};
use hpds_etl::store::OpenStore;
use hpds_format::{BLOCKS_FILE_NAME, CryptoKey, Protection};

fn protection() -> Protection {
    Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap())
}

fn edge_store() -> Store {
    let content = std::fs::read(fixture("edge.csv")).unwrap();
    load_files(Mode::NewSearch, &[("allConcepts.csv", &content)], |_| {}).unwrap()
}

fn hpds_etl(args: &[&str]) -> std::process::Output {
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(args)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "hpds-etl {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

fn key_file(store: &Store) -> String {
    let path = store.dir.path().join("key");
    std::fs::write(&path, format!("{KEY}\n")).unwrap();
    path.to_string_lossy().into_owned()
}

#[test]
fn column_meta_csv_regenerates_what_load_wrote() {
    let store = edge_store();
    let csv = store.out.join("columnMeta.csv");
    let original = std::fs::read(&csv).unwrap();
    std::fs::remove_file(&csv).unwrap();
    let rows = regenerate_column_meta_csv(&store.out, None, &protection()).unwrap();
    assert_eq!(rows as usize, store.index.concepts().len());
    assert_eq!(std::fs::read(&csv).unwrap(), original);

    let elsewhere = store.dir.path().join("elsewhere.csv");
    let key = key_file(&store);
    hpds_etl(&[
        "column-meta-csv",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
        "--out",
        elsewhere.to_str().unwrap(),
    ]);
    assert_eq!(std::fs::read(&elsewhere).unwrap(), original);
}

#[test]
fn remove_concepts_drops_exact_lines_from_the_index_only() {
    let store = edge_store();
    let before: Vec<String> = store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.clone())
        .collect();
    let first = before[0].clone();
    let second = before[1].clone();
    let with_replacement = format!("{}\u{FFFD}", before[2]);
    let prefix = before[3][..before[3].len() - 1].to_string();
    let padded = format!(" {}", before[4]);
    let list =
        format!("{first}\r\n{second}\n\n{with_replacement}\r{prefix}\n{padded}\nnot a concept");
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, list).unwrap();
    let blocks_before = std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap();

    let report = remove_concepts(&store.out, &list_path, &protection()).unwrap();

    assert_eq!(report.lines, 7);
    assert_eq!(report.removed, 3);
    assert_eq!(report.remaining as usize, before.len() - 3);
    assert_eq!(
        report.not_found,
        ["", prefix.as_str(), padded.as_str(), "not a concept"]
    );
    let after = OpenStore::open(&store.out, protection()).unwrap();
    let remaining: Vec<&str> = after
        .index
        .concepts()
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    let expected: Vec<&str> = before[3..].iter().map(String::as_str).collect();
    assert_eq!(remaining, expected);
    assert_eq!(after.index.patients(), store.index.patients());
    assert_eq!(after.index.store_id(), store.index.store_id());
    assert_eq!(
        std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap(),
        blocks_before
    );
    for concept in after.index.concepts() {
        after.reader.read_block(&after.codec, concept).unwrap();
    }
    let mut csv = Vec::new();
    write_column_meta_csv(&mut csv, after.index.concepts()).unwrap();
    assert_eq!(
        std::fs::read(store.out.join("columnMeta.csv")).unwrap(),
        csv
    );
    let leftovers: Vec<String> = std::fs::read_dir(&store.out)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains(".tmp"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
}

#[test]
fn remove_concepts_through_the_binary() {
    let store = edge_store();
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\n")).unwrap();
    let key = key_file(&store);
    let output = hpds_etl(&[
        "remove-concepts",
        "--store",
        store.out.to_str().unwrap(),
        "--concepts",
        list_path.to_str().unwrap(),
        "--key-file",
        &key,
    ]);
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["removed"], 1);
    let after = OpenStore::open(&store.out, protection()).unwrap();
    assert!(after.index.get(&first).is_none());
}

fn dump_text(dir: &Path) -> String {
    let store = OpenStore::open(dir, protection()).unwrap();
    let mut out = Vec::new();
    dump(&store, &mut out).unwrap();
    String::from_utf8(out).unwrap()
}

#[test]
fn dump_writes_every_observation_in_concept_then_by_key_order() {
    let store = edge_store();
    let text = dump_text(&store.out);
    let mut lines = text.split_terminator("\r\n");
    assert_eq!(
        lines.next(),
        Some("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,TIMESTAMP")
    );
    let mut expected = Vec::new();
    for concept in store.index.concepts() {
        for (patient, value, timestamp) in store.rows(&concept.path) {
            let (numeric, text) = if concept.is_categorical() {
                (String::new(), value)
            } else {
                (value, String::new())
            };
            let timestamp = timestamp.map(|t| t.to_string()).unwrap_or_default();
            hpds_etl::column_meta_csv::write_commons_csv_record(
                &mut expected,
                &[
                    patient.to_string(),
                    concept.path.clone(),
                    numeric,
                    text,
                    timestamp,
                ],
            )
            .unwrap();
        }
    }
    let body = text.split_once("\r\n").unwrap().1;
    assert_eq!(body, String::from_utf8(expected).unwrap());
    assert_eq!(
        body.matches("\r\n").count() as u64,
        store.report.counts.accepted
    );
}

#[test]
fn dump_and_patients_through_the_binary() {
    let store = edge_store();
    let key = key_file(&store);
    let out = store.dir.path().join("dump.csv");
    hpds_etl(&[
        "dump",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
        "--out",
        out.to_str().unwrap(),
    ]);
    assert_eq!(
        std::fs::read_to_string(&out).unwrap(),
        dump_text(&store.out)
    );

    let output = hpds_etl(&[
        "dump",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
        "--patients",
    ]);
    let listed: Vec<u32> = String::from_utf8(output.stdout)
        .unwrap()
        .lines()
        .map(|l| l.parse().unwrap())
        .collect();
    assert_eq!(listed, store.patients());

    let opened = OpenStore::open(&store.out, protection()).unwrap();
    let mut patients = Vec::new();
    assert_eq!(
        dump_patients(&opened, &mut patients).unwrap(),
        store.patients().len() as u64
    );
}

#[test]
fn store_commands_refuse_a_wrong_or_missing_key() {
    let store = edge_store();
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(["dump", "--store", store.out.to_str().unwrap(), "--plain"])
        .output()
        .unwrap();
    assert!(!output.status.success());
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(["column-meta-csv", "--store", store.out.to_str().unwrap()])
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("encryption key"),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
