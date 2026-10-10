//! `hpds-etl convert`, from stores of format 1 that the Rust HPDS v3 ETL wrote.
//!
//! `fixtures/format-1/` holds three such stores, encrypted with the test key:
//!
//! - `edge-bc`: the edge partitions `edgeB` and `edgeC` loaded in partitioned mode, with
//!   their combined store and `.generation` marker. Patient 3 is in both partitions.
//! - `not-indexed`: `edge-bc` with patient 3 removed from `edgeC`'s patient set, though
//!   `edgeC` holds a row of it, and the `patients-indexed` tag dropped from its marker line,
//!   as a migration from a Java store with that gap records it.
//! - `gap`: `not-indexed` with patient 3 also removed from `edgeB`'s patient set, so it has
//!   rows in both partitions and is in neither set.
//! - `unpartitioned`: the edge-case file loaded in new-search mode, with
//!   `unpartitioned.dump.csv`, the v3 `hpds-etl dump` of it.

mod common;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use hpds_etl::convert::{ConvertOptions, convert};
use hpds_etl::dump::{dump, split_by_partition, write_partition_dumps};
use hpds_etl::load::KeySource;
use hpds_etl::store::OpenStore;
use hpds_format::{CryptoKey, Protection};

fn format_1(name: &str) -> PathBuf {
    common::fixture("format-1").join(name)
}

fn protection() -> Protection {
    Protection::Encrypted(CryptoKey::from_key_file_bytes(common::KEY.as_bytes()).unwrap())
}

/// Copies the directory tree `from` to `to`.
fn copy_tree(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).unwrap();
    for entry in std::fs::read_dir(from).unwrap() {
        let entry = entry.unwrap();
        let target = to.join(entry.file_name());
        if entry.file_type().unwrap().is_dir() {
            copy_tree(&entry.path(), &target);
        } else {
            std::fs::copy(entry.path(), &target).unwrap();
        }
    }
}

/// Every file under `dir` with its bytes, by relative path.
fn snapshot(dir: &Path) -> BTreeMap<PathBuf, Vec<u8>> {
    let mut files = BTreeMap::new();
    let mut pending = vec![dir.to_path_buf()];
    while let Some(next) = pending.pop() {
        for entry in std::fs::read_dir(&next).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                pending.push(path);
            } else {
                let bytes = std::fs::read(&path).unwrap();
                files.insert(path.strip_prefix(dir).unwrap().to_path_buf(), bytes);
            }
        }
    }
    files
}

/// Copies the fixture `name` into a temporary directory and converts it into `<tmp>/out`.
fn convert_fixture(name: &str) -> (tempfile::TempDir, PathBuf, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join(name);
    copy_tree(&format_1(name), &source);
    let before = snapshot(&source);
    let out = dir.path().join("out");
    convert(&ConvertOptions {
        store: source.clone(),
        out: out.clone(),
        key: KeySource::StoreDirectory,
        threads: Some(2),
    })
    .unwrap();
    assert_eq!(snapshot(&source), before, "the source is never changed");
    (dir, source, out)
}

fn partition_dumps(store: &OpenStore) -> BTreeMap<String, String> {
    let tmp = tempfile::tempdir().unwrap();
    let split = split_by_partition(store, tmp.path()).unwrap();
    split
        .partitions
        .iter()
        .map(|partition| {
            let mut out = Vec::new();
            write_partition_dumps(&[partition], &mut out).unwrap();
            let text = String::from_utf8(out).unwrap();
            (
                partition.name.clone(),
                text.split_once("\r\n").unwrap().1.to_string(),
            )
        })
        .collect()
}

fn java_dump(partition: &str) -> String {
    std::fs::read_to_string(common::fixture(&format!(
        "partitioned/java/{partition}.dump.csv"
    )))
    .unwrap()
}

/// Name, `patients-indexed` flag and patient set of each partition of `store`.
fn table(store: &OpenStore) -> Vec<(String, bool, Vec<u32>)> {
    store
        .index
        .partition_table()
        .partitions
        .iter()
        .map(|p| {
            (
                p.name.clone(),
                p.patients_indexed,
                p.patients.iter().collect(),
            )
        })
        .collect()
}

#[test]
fn a_partitioned_store_converts_with_its_overlap_patient_tagged() {
    let (_dir, _, out) = convert_fixture("edge-bc");
    let store = OpenStore::open(&out, protection()).unwrap();
    let dumps = partition_dumps(&store);
    assert_eq!(dumps["edgeB"], java_dump("edgeB"));
    assert_eq!(dumps["edgeC"], java_dump("edgeC"));
    assert_eq!(
        table(&store),
        [
            ("edgeB".to_string(), true, vec![1, 2, 3, 40]),
            ("edgeC".to_string(), true, vec![3, 50]),
        ]
    );
    assert_eq!(
        store
            .index
            .partition_table()
            .overlap
            .iter()
            .collect::<Vec<_>>(),
        [3]
    );
    assert!(store.index.get("\\ds\\e\\shared\\").unwrap().tagged);
    let tagged: Vec<Vec<u32>> = store
        .index
        .partition_table()
        .partitions
        .iter()
        .map(|p| p.tagged.iter().collect())
        .collect();
    assert_eq!(tagged, [vec![3], vec![3]], "patient 3 has rows in both");
    assert_eq!(
        std::fs::read_to_string(out.join(".generation")).unwrap(),
        format!("{}\n", store.index.store_id())
    );
    assert_eq!(
        std::fs::read_to_string(out.join("encryption_key")).unwrap(),
        common::KEY
    );
    for concept in store.index.concepts() {
        store.read_block(concept).unwrap();
    }
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(out.join("etl-report.json")).unwrap()).unwrap();
    assert_eq!(
        report["source_partitions"],
        serde_json::json!(["edgeB", "edgeC"])
    );
    assert_eq!(report["store"]["overlap_patients"], 1);
}

#[test]
fn a_partition_missing_an_observed_patient_loses_the_patients_indexed_flag() {
    let (_dir, _, out) = convert_fixture("not-indexed");
    let store = OpenStore::open(&out, protection()).unwrap();
    assert_eq!(
        table(&store),
        [
            ("edgeB".to_string(), true, vec![1, 2, 3, 40]),
            ("edgeC".to_string(), false, vec![50]),
        ]
    );
    assert_eq!(
        store
            .index
            .partition_table()
            .overlap
            .iter()
            .collect::<Vec<_>>(),
        [3],
        "patient 3 has a row in edgeC, whose set lacks it"
    );
    let dumps = partition_dumps(&store);
    assert_eq!(dumps["edgeB"], java_dump("edgeB"));
    assert_eq!(dumps["edgeC"], java_dump("edgeC"));
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(out.join("etl-report.json")).unwrap()).unwrap();
    assert_eq!(
        report["store"]["partitions"][1]["observed_patients_missing_from_set"],
        1
    );
}

#[test]
fn an_unpartitioned_store_converts_to_an_unpartitioned_store() {
    let (_dir, _, out) = convert_fixture("unpartitioned");
    let store = OpenStore::open(&out, protection()).unwrap();
    assert!(!store.index.is_partitioned());
    let mut dumped = Vec::new();
    dump(&store, &mut dumped).unwrap();
    assert_eq!(
        String::from_utf8(dumped).unwrap(),
        std::fs::read_to_string(format_1("unpartitioned.dump.csv")).unwrap()
    );
}

#[test]
fn conversions_that_cannot_be_right_are_refused() {
    let (dir, source, out) = convert_fixture("edge-bc");
    let run = |store: &Path, target: &Path| {
        convert(&ConvertOptions {
            store: store.to_path_buf(),
            out: target.to_path_buf(),
            key: KeySource::StoreDirectory,
            threads: Some(1),
        })
        .map(|_| ())
        .unwrap_err()
        .to_string()
    };
    let again = run(&out, &dir.path().join("again"));
    assert!(again.contains("already in format 2"), "{again}");
    let inside = run(&source, &source.join("converted"));
    let ancestor = run(&source, dir.path());
    assert!(ancestor.contains("contains the store"), "{ancestor}");
    assert!(inside.contains("lies inside the store"), "{inside}");
    let itself = run(&source, &source);
    assert!(itself.contains("lies inside the store"), "{itself}");
    let empty = dir.path().join("empty");
    std::fs::create_dir(&empty).unwrap();
    std::fs::copy(source.join("encryption_key"), empty.join("encryption_key")).unwrap();
    let nothing = run(&empty, &dir.path().join("none"));
    assert!(nothing.contains("holds no store"), "{nothing}");
    std::fs::write(source.join(".generation"), format!("{}\n", "0".repeat(32))).unwrap();
    let publishing = run(&source, &dir.path().join("mid"));
    assert!(
        publishing.contains("a publish is under way"),
        "{publishing}"
    );
}

#[test]
fn every_reader_but_convert_refuses_a_format_1_store_naming_convert() {
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join("unpartitioned");
    copy_tree(&format_1("unpartitioned"), &source);
    let error = OpenStore::open(&source, protection())
        .map(|_| ())
        .unwrap_err()
        .to_string();
    assert!(error.contains("hpds-etl convert"), "{error}");
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(["dump", "--store", source.to_str().unwrap()])
        .env("RUST_LOG", "warn")
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(String::from_utf8_lossy(&output.stderr).contains("hpds-etl convert"));
}

#[test]
fn cli_converts_a_partitioned_store() {
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join("edge-bc");
    copy_tree(&format_1("edge-bc"), &source);
    let out = dir.path().join("out");
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args([
            "convert",
            "--store",
            source.to_str().unwrap(),
            "--out",
            out.to_str().unwrap(),
        ])
        .env("RUST_LOG", "warn")
        .output()
        .unwrap();
    assert!(output.status.success(), "{output:?}");
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["store"]["partitioned"], true);
    let dumps = dir.path().join("dumps");
    let dumped = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args([
            "dump",
            "--store",
            out.to_str().unwrap(),
            "--out",
            dumps.to_str().unwrap(),
        ])
        .env("RUST_LOG", "warn")
        .output()
        .unwrap();
    assert!(dumped.status.success(), "{dumped:?}");
    for partition in ["edgeB", "edgeC"] {
        let text = std::fs::read_to_string(dumps.join(format!("{partition}.csv"))).unwrap();
        assert_eq!(text.split_once("\r\n").unwrap().1, java_dump(partition));
    }
}

#[test]
fn a_patient_in_no_partition_set_converts_into_the_overlap_set() {
    let (_dir, _, out) = convert_fixture("gap");
    let store = OpenStore::open(&out, protection()).unwrap();
    assert_eq!(
        table(&store),
        [
            ("edgeB".to_string(), false, vec![1, 2, 40]),
            ("edgeC".to_string(), false, vec![50]),
        ]
    );
    assert_eq!(
        store.index.patients().iter().collect::<Vec<_>>(),
        [1, 2, 40, 50],
        "the store's patients stay the union of the partition sets"
    );
    assert_eq!(
        store
            .index
            .partition_table()
            .overlap
            .iter()
            .collect::<Vec<_>>(),
        [3]
    );
    let dumps = partition_dumps(&store);
    assert_eq!(dumps["edgeB"], java_dump("edgeB"));
    assert_eq!(dumps["edgeC"], java_dump("edgeC"));
}

#[test]
fn convert_waits_for_a_publish_holding_the_source_lock() {
    let dir = tempfile::tempdir().unwrap();
    let source = dir.path().join("edge-bc");
    copy_tree(&format_1("edge-bc"), &source);
    let lock = std::fs::File::open(&source).unwrap();
    lock.lock().unwrap();
    let out = dir.path().join("out");
    let options = ConvertOptions {
        store: source.clone(),
        out: out.clone(),
        key: KeySource::StoreDirectory,
        threads: Some(1),
    };
    let converting = std::thread::spawn(move || convert(&options).map(|_| ()));
    std::thread::sleep(std::time::Duration::from_millis(500));
    assert!(
        !converting.is_finished(),
        "convert waits for the source lock"
    );
    assert!(!out.exists());
    drop(lock);
    converting.join().unwrap().unwrap();
    assert!(out.join("pheno.index").exists());
}

#[test]
fn convert_publishes_into_an_existing_store_only_under_its_lock() {
    let (dir, source, out) = convert_fixture("edge-bc");
    let before = std::fs::read_to_string(out.join(".generation")).unwrap();
    let lock = std::fs::File::open(&out).unwrap();
    lock.lock().unwrap();
    let options = ConvertOptions {
        store: source,
        out: out.clone(),
        key: KeySource::StoreDirectory,
        threads: Some(1),
    };
    let converting = std::thread::spawn(move || convert(&options).map(|_| ()));
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while !std::fs::read_dir(dir.path()).unwrap().flatten().any(|e| {
        e.file_name().to_string_lossy().starts_with(".out.staging-")
            && e.path().join("etl-report.json").exists()
    }) {
        assert!(std::time::Instant::now() < deadline, "timed out");
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    std::thread::sleep(std::time::Duration::from_millis(500));
    assert!(!converting.is_finished(), "the publish waits for the lock");
    assert_eq!(
        std::fs::read_to_string(out.join(".generation")).unwrap(),
        before
    );
    drop(lock);
    converting.join().unwrap().unwrap();
    assert_ne!(
        std::fs::read_to_string(out.join(".generation")).unwrap(),
        before
    );
}
