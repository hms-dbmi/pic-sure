//! Partitioned loading, migration and maintenance, checked against stores the
//! harmonization branch's Java `CSVLoader` built from the same input.
//!
//! `fixtures/partitioned/java/` holds, for each edge partition, the Java `DumpSourceCSV`
//! output without its header line, Java's `columnMeta.csv`, and the Java `allIds` set (read
//! by migrating the Java store). `fixtures/partitioned/java-export/` is the Java
//! `ExportForMigration` export of that store, one directory per partition, encrypted with
//! the test key. `fixtures/partitioned/harm-fixture/` holds, for the four partition files of
//! the HPDS integration fixture, a per-concept digest of the Java dumps, Java's
//! `columnMeta.csv` files and the patient sets as count, min, max and sum.

mod common;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use hpds_etl::EtlError;
use hpds_etl::dump::{dump, dump_patients};
use hpds_etl::load::{KeySource, PartitionedLoadOptions, load_partitioned};
use hpds_etl::migrate::{MigrateOptions, migrate_partitioned};
use hpds_etl::store::OpenStore;
use hpds_format::{CryptoKey, Protection};

const EDGE_PARTITIONS: [&str; 3] = ["edgeA", "edgeB", "edgeC"];

/// `columnMeta.csv` rows where the Rust loader deliberately differs from Java: Java
/// starts max at `Double.MIN_VALUE` and min at `Double.MAX_VALUE`, so an all-negative
/// concept reports a max of `4.9E-324` and a concept with no values reports both bounds;
/// the Rust loader writes the real extremes, or `null`. Values are Rust's min and max.
const EDGE_MIN_MAX_DIVERGENCES: [(&str, &str, &str); 3] = [
    ("\\ds\\e\\coerce\\inf\\", "-Infinity", "-Infinity"),
    ("\\ds\\e\\neg\\", "-5.0", "-3.0"),
    ("\\ds\\e\\ws\\", "null", "null"),
];

fn fixture(name: &str) -> PathBuf {
    common::fixture("partitioned").join(name)
}

fn harmonization_inputs() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../pic-sure-hpds/service/src/test/resources/phenotypic")
}

fn test_protection() -> Protection {
    Protection::Encrypted(CryptoKey::from_key_file_bytes(common::KEY.as_bytes()).unwrap())
}

fn write_key(path: &Path) {
    std::fs::write(path, common::KEY).unwrap();
}

/// Loads `input_dir` into `<tmp>/out` with the test key given as `--key-file`.
fn load(input_dir: &Path) -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = PartitionedLoadOptions::new(input_dir.to_path_buf(), out.clone());
    options.key = KeySource::File(key);
    options.threads = Some(4);
    load_partitioned(&options).unwrap();
    (dir, out)
}

fn open(dir: &Path) -> OpenStore {
    OpenStore::open(dir, test_protection()).unwrap()
}

/// The store's dump without its header line, as bytes.
fn dump_body(store: &OpenStore) -> Vec<u8> {
    let mut out = Vec::new();
    dump(store, &mut out).unwrap();
    let header_end = out.windows(2).position(|w| w == b"\r\n").unwrap() + 2;
    out.split_off(header_end)
}

fn patients(store: &OpenStore) -> Vec<u32> {
    let mut out = Vec::new();
    dump_patients(store, &mut out).unwrap();
    String::from_utf8(out)
        .unwrap()
        .lines()
        .map(|l| l.parse().unwrap())
        .collect()
}

fn csv_records(text: &str) -> Vec<Vec<String>> {
    csv::ReaderBuilder::new()
        .has_headers(false)
        .from_reader(text.as_bytes())
        .records()
        .map(|r| r.unwrap().iter().map(str::to_string).collect())
        .collect()
}

/// Compares two `columnMeta.csv` files field by field, skipping the block offset and
/// length (fields 8 and 9), and returns the paths of rows that differ elsewhere.
fn column_meta_differences(java: &str, rust: &str) -> Vec<(String, Vec<String>)> {
    let java = csv_records(java);
    let rust = csv_records(rust);
    assert_eq!(java.len(), rust.len(), "row count");
    let mut differences = Vec::new();
    for (j, r) in java.iter().zip(&rust) {
        assert_eq!(j[0], r[0], "concept order");
        let strip = |row: &Vec<String>| {
            let mut row = row.clone();
            row.drain(7..9);
            row
        };
        if strip(j) != strip(r) {
            differences.push((r[0].clone(), r.clone()));
        }
    }
    differences
}

fn fnv(hash: u64, bytes: &[u8]) -> u64 {
    bytes.iter().fold(hash, |h, &b| {
        (h ^ u64::from(b)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

/// Per concept, in dump order: the path, its observation count, and the FNV-1a 64 hash of
/// its dump lines with their `\r\n` terminators.
fn dump_digest(body: &[u8]) -> Vec<(String, u64, String)> {
    let mut digest: Vec<(String, u64, u64)> = Vec::new();
    let text = std::str::from_utf8(body).unwrap();
    for line in text.split_terminator("\r\n") {
        let concept = csv_records(line).remove(0).remove(1);
        if digest.last().is_none_or(|d| d.0 != concept) {
            digest.push((concept, 0, 0xcbf2_9ce4_8422_2325));
        }
        let last = digest.last_mut().unwrap();
        last.1 += 1;
        last.2 = fnv(fnv(last.2, line.as_bytes()), b"\r\n");
    }
    digest
        .into_iter()
        .map(|(c, n, h)| (c, n, format!("{h:016x}")))
        .collect()
}

#[test]
fn harmonization_fixture_matches_java_csv_loader() {
    let (_dir, out) = load(&harmonization_inputs());
    let digest_text = std::fs::read_to_string(fixture("harm-fixture/dump-digest.tsv")).unwrap();
    let mut expected: BTreeMap<&str, Vec<(String, u64, String)>> = BTreeMap::new();
    for line in digest_text.lines() {
        let [partition, concept, count, hash] = line.split('\t').collect::<Vec<_>>()[..] else {
            panic!("bad digest line {line}");
        };
        expected.entry(partition).or_default().push((
            concept.to_string(),
            count.parse().unwrap(),
            hash.to_string(),
        ));
    }
    let patient_text = std::fs::read_to_string(fixture("harm-fixture/patients.tsv")).unwrap();
    assert_eq!(expected.len(), 4);
    for (partition, java_digest) in &expected {
        let store = open(&out.join(partition));
        assert_eq!(&dump_digest(&dump_body(&store)), java_digest, "{partition}");
        let java_meta =
            std::fs::read_to_string(fixture(&format!("harm-fixture/{partition}.columnMeta.csv")))
                .unwrap();
        let rust_meta =
            std::fs::read_to_string(out.join(partition).join("columnMeta.csv")).unwrap();
        assert_eq!(
            column_meta_differences(&java_meta, &rust_meta),
            Vec::new(),
            "{partition}"
        );
        let ids = patients(&store);
        let stats = format!(
            "{partition}\t{}\t{}\t{}\t{}",
            ids.len(),
            ids[0],
            ids[ids.len() - 1],
            ids.iter().map(|&p| u64::from(p)).sum::<u64>()
        );
        assert!(patient_text.lines().any(|l| l == stats), "{stats}");
    }
    let mut names: Vec<String> = std::fs::read_dir(&out)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    assert_eq!(
        names,
        [
            "encryption_key",
            "partition1",
            "partition2",
            "partition3",
            "partition4"
        ]
    );
    assert_eq!(
        std::fs::read_to_string(out.join("encryption_key")).unwrap(),
        common::KEY
    );
}

#[test]
fn edge_partitions_match_java_csv_loader_except_min_and_max() {
    let (_dir, out) = load(&fixture("input"));
    for partition in EDGE_PARTITIONS {
        let store = open(&out.join(partition));
        let java_dump = std::fs::read(fixture(&format!("java/{partition}.dump.csv"))).unwrap();
        assert_eq!(
            String::from_utf8(dump_body(&store)).unwrap(),
            String::from_utf8(java_dump).unwrap(),
            "{partition}"
        );
        let java_meta =
            std::fs::read_to_string(fixture(&format!("java/{partition}.columnMeta.csv"))).unwrap();
        let rust_meta =
            std::fs::read_to_string(out.join(partition).join("columnMeta.csv")).unwrap();
        let differences = column_meta_differences(&java_meta, &rust_meta);
        let expected: Vec<(String, Vec<String>)> = if partition == "edgeA" {
            EDGE_MIN_MAX_DIVERGENCES
                .iter()
                .map(|(path, _, _)| {
                    let row = differences
                        .iter()
                        .find(|d| d.0 == *path)
                        .unwrap_or_else(|| panic!("{path} should differ"));
                    (path.to_string(), row.1.clone())
                })
                .collect()
        } else {
            Vec::new()
        };
        assert_eq!(differences, expected, "{partition}");
        for (path, min, max) in EDGE_MIN_MAX_DIVERGENCES {
            if let Some((_, row)) = differences.iter().find(|d| d.0 == path) {
                assert_eq!((row[5].as_str(), row[6].as_str()), (min, max), "{path}");
            }
        }
        let java_patients: Vec<u32> =
            std::fs::read_to_string(fixture(&format!("java/{partition}.patients.txt")))
                .unwrap()
                .lines()
                .map(|l| l.parse().unwrap())
                .collect();
        assert_eq!(patients(&store), java_patients, "{partition}");
    }
}

#[test]
fn edge_load_counts_drops_instead_of_aborting() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(
        input.join("p1-allConcepts.csv"),
        "1,\\a\\,abc,,\n2,\\a\\,3,,\n3,\\a\\,4,,notatime\n4,\\b\\,,x,\n",
    )
    .unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let mut options = PartitionedLoadOptions::new(input, dir.path().join("out"));
    options.key = KeySource::File(key);
    let report = load_partitioned(&options).unwrap();
    let counts = &report.partitions["p1"].counts;
    assert_eq!(counts.rows_read, 4, "the first line is data");
    assert_eq!(counts.dropped_unparseable_number, 1);
    assert_eq!(counts.unparseable_timestamps, 1);
    assert_eq!(counts.accepted, 3);
}

#[test]
fn overlapping_patients_and_type_conflicts_load_into_their_partitions() {
    let (_dir, out) = load(&fixture("input"));
    let a = open(&out.join("edgeA"));
    let b = open(&out.join("edgeB"));
    let c = open(&out.join("edgeC"));
    let first = "\\ds\\e\\first\\";
    assert!(!a.index.get(first).unwrap().is_categorical());
    assert!(b.index.get(first).unwrap().is_categorical());
    assert_eq!(patients(&b), [1, 2, 3, 40]);
    assert_eq!(patients(&c), [3, 50]);

    let mut merged = Vec::new();
    hpds_etl::dump::dump_patients_merged(&[a, b, c], &mut merged).unwrap();
    let merged: Vec<u32> = String::from_utf8(merged)
        .unwrap()
        .lines()
        .map(|l| l.parse().unwrap())
        .collect();
    assert_eq!(merged.len(), 32);
    assert!(merged.windows(2).all(|w| w[0] < w[1]));
    assert!(merged.contains(&40) && merged.contains(&50) && merged.contains(&3));

    let dirs: Vec<(String, PathBuf)> = EDGE_PARTITIONS
        .iter()
        .map(|p| (p.to_string(), out.join(p)))
        .collect();
    let target = out.join("columnMeta.csv");
    let error = hpds_etl::store_maintenance::write_merged_column_meta_csv(
        &dirs,
        &target,
        &test_protection(),
    )
    .unwrap_err()
    .to_string();
    assert_eq!(
        error,
        "concept \"\\\\ds\\\\e\\\\first\\\\\" is numeric in partition edgeA and categorical in partition edgeB"
    );
    assert!(!target.exists());
}

#[test]
fn merged_column_meta_sums_counts_and_unions_values() {
    let (_dir, out) = load(&fixture("input"));
    let dirs: Vec<(String, PathBuf)> = ["edgeB", "edgeC"]
        .iter()
        .map(|p| (p.to_string(), out.join(p)))
        .collect();
    let target = out.join("merged.csv");
    let rows = hpds_etl::store_maintenance::write_merged_column_meta_csv(
        &dirs,
        &target,
        &test_protection(),
    )
    .unwrap();
    assert_eq!(rows, 3);
    assert_eq!(
        std::fs::read_to_string(&target).unwrap(),
        concat!(
            "\\ds\\e\\first\\,4,0,true,moreµtext,null,null,0,0,2,2\r\n",
            "\\ds\\e\\only_c\\,1,0,true,x,null,null,0,0,1,1\r\n",
            "\\ds\\e\\shared\\,8,0,false,,4.0,9.0,0,0,4,4\r\n",
        )
    );
}

fn load_error(files: &[&str]) -> (String, PathBuf, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    for file in files {
        std::fs::write(input.join(file), "1,\\a\\,1,,\n").unwrap();
    }
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = PartitionedLoadOptions::new(input, out.clone());
    options.key = KeySource::File(key);
    let error = load_partitioned(&options).unwrap_err();
    assert!(matches!(error, EtlError::Usage(_)), "{error}");
    (error.to_string(), out, dir)
}

#[test]
fn a_bare_all_concepts_file_is_refused_before_anything_is_written() {
    let (error, out, dir) = load_error(&["p1-allConcepts.csv", "allConcepts.csv"]);
    assert!(
        error.contains("allConcepts.csv has no partition prefix"),
        "{error}"
    );
    assert!(!out.exists());
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 2);
}

#[test]
fn a_duplicate_prefix_is_refused_before_anything_is_written() {
    let (error, out, _dir) = load_error(&["p1-a-allConcepts.csv", "p1-b-allConcepts.csv"]);
    assert!(
        error.contains("partition p1 has more than one file"),
        "{error}"
    );
    assert!(!out.exists());
}

#[test]
fn a_failing_partition_publishes_none() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("good-allConcepts.csv"), "1,\\a\\,1,,\n").unwrap();
    std::fs::write(input.join("bad-allConcepts.csv"), "x,\\a\\,1,,\n").unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let error = load_partitioned(&PartitionedLoadOptions::new(input, out.clone())).unwrap_err();
    assert!(
        matches!(
            &error,
            EtlError::InvalidPatient {
                record: 1,
                line: 1,
                ..
            }
        ),
        "{error}"
    );
    let left: Vec<_> = std::fs::read_dir(&out).unwrap().collect();
    assert_eq!(left.len(), 1, "only the key is left");
    assert_eq!(
        std::fs::read_dir(dir.path()).unwrap().count(),
        2,
        "no staging left"
    );
}

#[test]
fn reloading_replaces_partitions_and_refuses_stale_ones() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("p1-allConcepts.csv"), "1,\\a\\,1,,\n").unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let options = PartitionedLoadOptions::new(input.clone(), out.clone());
    load_partitioned(&options).unwrap();
    let first_id = open(&out.join("p1")).index.store_id();

    std::fs::write(
        input.join("p1-allConcepts.csv"),
        "1,\\a\\,1,,\n2,\\b\\,2,,\n",
    )
    .unwrap();
    std::fs::write(input.join("p2-allConcepts.csv"), "3,\\a\\,1,,\n").unwrap();
    load_partitioned(&options).unwrap();
    let p1 = open(&out.join("p1"));
    assert_ne!(p1.index.store_id(), first_id);
    assert_eq!(patients(&p1), [1, 2]);
    assert_ne!(open(&out.join("p2")).index.store_id(), p1.index.store_id());

    std::fs::remove_file(input.join("p2-allConcepts.csv")).unwrap();
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(
        error.contains("partitions this run does not write (p2)"),
        "{error}"
    );
}

#[test]
fn a_key_file_that_differs_from_the_installed_key_is_refused() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    std::fs::write(
        out.join("encryption_key"),
        "FEDCBA9876543210FEDCBA9876543210",
    )
    .unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let mut options = PartitionedLoadOptions::new(fixture("input"), out);
    options.key = KeySource::File(key);
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(
        error.contains("every partition shares the root key"),
        "{error}"
    );
}

/// Copies the Java edge export into a fresh directory, migrates it, and returns the
/// temporary directory and the output.
fn migrate_edge_export() -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let export = dir.path().join("export");
    for partition in EDGE_PARTITIONS {
        std::fs::create_dir_all(export.join(partition)).unwrap();
        for file in ["concepts.bin", "patients.txt"] {
            std::fs::copy(
                fixture(&format!("java-export/{partition}/{file}")),
                export.join(partition).join(file),
            )
            .unwrap();
        }
    }
    let out = dir.path().join("out");
    let report = migrate_partitioned(&MigrateOptions {
        export,
        out: out.clone(),
        key: KeySource::File(fixture("java-export/encryption_key")),
        threads: Some(2),
        skip_inconsistent: false,
    })
    .unwrap();
    assert_eq!(
        report.partitions.keys().collect::<Vec<_>>(),
        EDGE_PARTITIONS.iter().collect::<Vec<_>>()
    );
    (dir, out)
}

#[test]
fn migrating_the_java_partitioned_store_reproduces_its_dumps() {
    let (_dir, out) = migrate_edge_export();
    assert_eq!(
        std::fs::read(out.join("encryption_key")).unwrap(),
        std::fs::read(fixture("java-export/encryption_key")).unwrap()
    );
    for partition in EDGE_PARTITIONS {
        let store = open(&out.join(partition));
        assert_eq!(
            String::from_utf8(dump_body(&store)).unwrap(),
            std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap(),
            "{partition}"
        );
        let java_patients: Vec<u32> =
            std::fs::read_to_string(fixture(&format!("java/{partition}.patients.txt")))
                .unwrap()
                .lines()
                .map(|l| l.parse().unwrap())
                .collect();
        assert_eq!(patients(&store), java_patients, "{partition}");
    }
}

fn hpds_etl(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(args)
        .env("RUST_LOG", "warn")
        .output()
        .unwrap()
}

fn path_str(path: &Path) -> &str {
    path.to_str().unwrap()
}

#[test]
fn cli_loads_migrates_and_dumps_per_partition() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let loaded = hpds_etl(&[
        "load",
        "--mode",
        "partitioned",
        "--input-dir",
        path_str(&fixture("input")),
        "--out",
        path_str(&out),
    ]);
    assert!(loaded.status.success(), "{loaded:?}");
    let counts: serde_json::Value = serde_json::from_slice(&loaded.stdout).unwrap();
    assert_eq!(counts["edgeB"]["accepted"], 5);

    let dumps = dir.path().join("dumps");
    let dumped = hpds_etl(&["dump", "--store", path_str(&out), "--out", path_str(&dumps)]);
    assert!(dumped.status.success(), "{dumped:?}");
    for partition in EDGE_PARTITIONS {
        let text = std::fs::read_to_string(dumps.join(format!("{partition}.csv"))).unwrap();
        let body = text.split_once("\r\n").unwrap().1;
        assert_eq!(
            body,
            std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap()
        );
    }
    let merged = hpds_etl(&["dump", "--store", path_str(&out), "--merged"]);
    assert!(merged.status.success(), "{merged:?}");
    let mut expected =
        String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,TIMESTAMP\r\n");
    for partition in EDGE_PARTITIONS {
        expected.push_str(
            &std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap(),
        );
    }
    assert_eq!(String::from_utf8(merged.stdout).unwrap(), expected);

    let no_out = hpds_etl(&["dump", "--store", path_str(&out)]);
    assert!(!no_out.status.success());
    assert!(String::from_utf8_lossy(&no_out.stderr).contains("--out <directory>"));

    let conflict = hpds_etl(&["column-meta-csv", "--store", path_str(&out), "--merged"]);
    assert!(!conflict.status.success());
    assert!(String::from_utf8_lossy(&conflict.stderr).contains("is numeric in partition edgeA"));
}

#[test]
fn cli_maintenance_commands_act_on_every_partition() {
    let (_dir, out) = load(&fixture("input"));
    let concepts = out.join("conceptsToRemove.txt");
    std::fs::write(&concepts, "\\ds\\e\\shared\\\n").unwrap();
    let removed = hpds_etl(&[
        "remove-concepts",
        "--store",
        path_str(&out),
        "--concepts",
        path_str(&concepts),
    ]);
    assert!(removed.status.success(), "{removed:?}");
    let report: serde_json::Value = serde_json::from_slice(&removed.stdout).unwrap();
    assert_eq!(report["edgeA"]["removed"], 0);
    assert_eq!(report["edgeB"]["removed"], 1);
    assert_eq!(report["edgeC"]["removed"], 1);
    for partition in ["edgeB", "edgeC"] {
        let store = open(&out.join(partition));
        assert!(store.index.get("\\ds\\e\\shared\\").is_none());
        let csv = std::fs::read_to_string(out.join(partition).join("columnMeta.csv")).unwrap();
        assert!(!csv.contains("shared"), "{partition}");
    }

    let edge_c_meta = out.join("edgeC").join("columnMeta.csv");
    let before = std::fs::read_to_string(&edge_c_meta).unwrap();
    std::fs::remove_file(&edge_c_meta).unwrap();
    let regenerated = hpds_etl(&["column-meta-csv", "--store", path_str(&out)]);
    assert!(regenerated.status.success(), "{regenerated:?}");
    assert_eq!(std::fs::read_to_string(&edge_c_meta).unwrap(), before);
    let with_out = hpds_etl(&[
        "column-meta-csv",
        "--store",
        path_str(&out),
        "--out",
        path_str(&out.join("x.csv")),
    ]);
    assert!(!with_out.status.success());
}

#[test]
fn cli_migrate_detects_a_partitioned_export() {
    let dir = tempfile::tempdir().unwrap();
    let export = dir.path().join("export");
    std::fs::create_dir(&export).unwrap();
    for partition in ["edgeB", "edgeC"] {
        std::fs::create_dir(export.join(partition)).unwrap();
        for file in ["concepts.bin", "patients.txt"] {
            std::fs::copy(
                fixture(&format!("java-export/{partition}/{file}")),
                export.join(partition).join(file),
            )
            .unwrap();
        }
    }
    let out = dir.path().join("out");
    let migrated = hpds_etl(&[
        "migrate",
        "--export",
        path_str(&export),
        "--out",
        path_str(&out),
        "--key-file",
        path_str(&fixture("java-export/encryption_key")),
    ]);
    assert!(migrated.status.success(), "{migrated:?}");
    let report: serde_json::Value = serde_json::from_slice(&migrated.stdout).unwrap();
    assert_eq!(report["partitions"]["edgeB"]["observations"], 5);
    assert_eq!(report["partitions"]["edgeC"]["patients"], 2);
    let rows = hpds_etl(&["column-meta-csv", "--store", path_str(&out), "--merged"]);
    assert!(rows.status.success(), "{rows:?}");
    assert!(
        std::fs::read_to_string(out.join("columnMeta.csv"))
            .unwrap()
            .contains("\\ds\\e\\shared\\,8,0,false,,4.0,9.0,0,0,4,4\r\n")
    );
}
