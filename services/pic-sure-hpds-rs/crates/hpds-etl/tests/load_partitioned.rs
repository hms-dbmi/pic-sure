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
//!
//! A partitioned load writes one store; each partition's rows are read back with
//! [`split_by_partition`], which gives the dump a store of that partition alone would.
//! Partition `edgeA` types `\ds\e\first\` numeric and `edgeB` categorical, so they cannot
//! share a store: the edge tests load `edgeA` alone and `edgeB` with `edgeC`, which share
//! patient 3, the overlap case.

mod common;

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::Command;

use hpds_etl::EtlError;
use hpds_etl::dump::{split_by_partition, write_partition_dumps};
use hpds_etl::load::{KeySource, PartitionedLoadOptions, load_partitioned};
use hpds_etl::migrate::{MigrateOptions, migrate_partitioned};
use hpds_etl::store::OpenStore;
use hpds_format::{CryptoKey, Protection, java_string_cmp};

const EDGE_PARTITIONS: [&str; 3] = ["edgeA", "edgeB", "edgeC"];

/// The edge input files of each partition.
fn edge_file(partition: &str) -> &'static str {
    match partition {
        "edgeA" => "edgeA-allConcepts.csv",
        "edgeB" => "edgeB-allConcepts.csv",
        _ => "edgeC-extra-allConcepts.csv",
    }
}

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

/// Partitioned load options for `input_dir`, applying its `config.json` as `--config`
/// when it has one and `--no-config` otherwise.
fn options(input_dir: PathBuf, out: PathBuf) -> PartitionedLoadOptions {
    let config = input_dir.join("config.json");
    let mut options = PartitionedLoadOptions::new(input_dir, out);
    if config.is_file() {
        options.config = Some(config);
    } else {
        options.no_config = true;
    }
    options
}

/// A copy of the edge input holding only `partitions`' files and the config, in a new
/// directory under `dir`.
fn edge_input(dir: &Path, partitions: &[&str]) -> PathBuf {
    let input = dir.join(format!("input-{}", partitions.join("-")));
    std::fs::create_dir(&input).unwrap();
    std::fs::copy(fixture("input/config.json"), input.join("config.json")).unwrap();
    for partition in partitions {
        let file = edge_file(partition);
        std::fs::copy(fixture(&format!("input/{file}")), input.join(file)).unwrap();
    }
    input
}

/// Loads `input_dir` into `<tmp>/out` with the test key given as `--key-file`.
fn load(input_dir: &Path) -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    load_into(input_dir, &dir, &out);
    (dir, out)
}

fn load_into(input_dir: &Path, dir: &tempfile::TempDir, out: &Path) {
    let key = dir.path().join("key");
    write_key(&key);
    let mut options = options(input_dir.to_path_buf(), out.to_path_buf());
    options.key = KeySource::File(key);
    options.threads = Some(4);
    load_partitioned(&options).unwrap();
    assert!(
        !out.join(".lock").exists(),
        "the staging lock file is not published"
    );
}

/// Loads the edge partitions `partitions` into `<tmp>/out`.
fn load_edge(partitions: &[&str]) -> (tempfile::TempDir, PathBuf) {
    let dir = tempfile::tempdir().unwrap();
    let input = edge_input(dir.path(), partitions);
    let out = dir.path().join("out");
    load_into(&input, &dir, &out);
    (dir, out)
}

/// The `.generation` marker of the store `out`.
fn read_generation(out: &Path) -> String {
    std::fs::read_to_string(out.join(".generation")).unwrap()
}

fn open(dir: &Path) -> OpenStore {
    OpenStore::open(dir, test_protection()).unwrap()
}

/// Each partition's dump body (no header line), by partition name.
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
            let body = text.split_once("\r\n").unwrap().1.to_string();
            (partition.name.clone(), body)
        })
        .collect()
}

/// The patient set of partition `name`.
fn patients(store: &OpenStore, name: &str) -> Vec<u32> {
    let table = store.index.partition_table();
    let position = table.position(name).unwrap();
    table.partitions[position].patients.iter().collect()
}

fn csv_records(text: &str) -> Vec<Vec<String>> {
    csv::ReaderBuilder::new()
        .has_headers(false)
        .from_reader(text.as_bytes())
        .records()
        .map(|r| r.unwrap().iter().map(str::to_string).collect())
        .collect()
}

/// Merges per-partition `columnMeta.csv` files as Java's `SummaryColumnMeta` merges them:
/// the widest width, the first column offset, the union of category values in Java string
/// order, the smallest min and largest max (`null` skipped), and summed counts. The block
/// fields are left empty.
fn merge_java_column_meta(files: &[String]) -> Vec<Vec<String>> {
    let mut merged: Vec<Vec<String>> = Vec::new();
    let number = |text: &str| (text != "null").then(|| text.parse::<f64>().unwrap());
    for text in files {
        for row in csv_records(text) {
            let Some(into) = merged.iter_mut().find(|m| m[0] == row[0]) else {
                let mut start = row.clone();
                start[7].clear();
                start[8].clear();
                merged.push(start);
                continue;
            };
            let width = into[1].parse::<i32>().unwrap().max(row[1].parse().unwrap());
            into[1] = width.to_string();
            let mut values: Vec<String> = into[4]
                .split('µ')
                .chain(row[4].split('µ'))
                .filter(|v| !v.is_empty())
                .map(str::to_string)
                .collect();
            values.sort_by(|a, b| java_string_cmp(a, b));
            values.dedup();
            into[4] = values.join("µ");
            for (column, keep_lower) in [(5, true), (6, false)] {
                match (number(&into[column]), number(&row[column])) {
                    (None, Some(_)) => into[column] = row[column].clone(),
                    (Some(a), Some(b)) if (b < a) == keep_lower && b != a => {
                        into[column] = row[column].clone();
                    }
                    _ => {}
                }
            }
            for column in [9, 10] {
                let sum: u64 =
                    into[column].parse::<u64>().unwrap() + row[column].parse::<u64>().unwrap();
                into[column] = sum.to_string();
            }
        }
    }
    merged.sort_by(|a, b| java_string_cmp(&a[0], &b[0]));
    merged
}

/// Compares the expected rows with a store's `columnMeta.csv`, skipping the block offset
/// and length (fields 8 and 9), and returns the paths of rows that differ elsewhere.
fn column_meta_differences(expected: &[Vec<String>], rust: &str) -> Vec<(String, Vec<String>)> {
    let rust = csv_records(rust);
    assert_eq!(expected.len(), rust.len(), "row count");
    let mut differences = Vec::new();
    for (e, r) in expected.iter().zip(&rust) {
        assert_eq!(e[0], r[0], "concept order");
        let strip = |row: &Vec<String>| {
            let mut row = row.clone();
            row.drain(7..9);
            row
        };
        if strip(e) != strip(r) {
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
fn dump_digest(body: &str) -> Vec<(String, u64, String)> {
    let mut digest: Vec<(String, u64, u64)> = Vec::new();
    for line in body.split_terminator("\r\n") {
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

fn report(out: &Path) -> serde_json::Value {
    serde_json::from_slice(&std::fs::read(out.join("etl-report.json")).unwrap()).unwrap()
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
    let store = open(&out);
    let dumps = partition_dumps(&store);
    let mut java_metas = Vec::new();
    for (partition, java_digest) in &expected {
        assert_eq!(&dump_digest(&dumps[*partition]), java_digest, "{partition}");
        java_metas.push(
            std::fs::read_to_string(fixture(&format!("harm-fixture/{partition}.columnMeta.csv")))
                .unwrap(),
        );
        let ids = patients(&store, partition);
        let stats = format!(
            "{partition}\t{}\t{}\t{}\t{}",
            ids.len(),
            ids[0],
            ids[ids.len() - 1],
            ids.iter().map(|&p| u64::from(p)).sum::<u64>()
        );
        assert!(patient_text.lines().any(|l| l == stats), "{stats}");
    }
    let rust_meta = std::fs::read_to_string(out.join("columnMeta.csv")).unwrap();
    assert_eq!(
        column_meta_differences(&merge_java_column_meta(&java_metas), &rust_meta),
        Vec::new()
    );
    let mut names: Vec<String> = std::fs::read_dir(&out)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    names.sort();
    assert_eq!(
        names,
        [
            ".generation",
            "columnMeta.csv",
            "encryption_key",
            "etl-report.json",
            "pheno.blocks",
            "pheno.index",
        ]
    );
    assert_eq!(
        read_generation(&out),
        format!("{}\n", store.index.store_id())
    );
    assert_eq!(
        std::fs::read_to_string(out.join("encryption_key")).unwrap(),
        common::KEY
    );
    let report = report(&out);
    assert_eq!(report["store"]["overlap_patients"], 0);
    assert_eq!(report["store"]["tagged_concepts"], 0);
    assert_eq!(report["store"]["patients"], 4978);
    assert!(
        store
            .index
            .partition_table()
            .partitions
            .iter()
            .all(|p| p.patients_indexed)
    );
}

#[test]
fn edge_partitions_match_java_csv_loader_except_min_and_max() {
    for group in [&["edgeA"][..], &["edgeB", "edgeC"]] {
        let (_dir, out) = load_edge(group);
        let store = open(&out);
        let dumps = partition_dumps(&store);
        let mut java_metas = Vec::new();
        for partition in group {
            let java_dump =
                std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap();
            assert_eq!(dumps[*partition], java_dump, "{partition}");
            java_metas.push(
                std::fs::read_to_string(fixture(&format!("java/{partition}.columnMeta.csv")))
                    .unwrap(),
            );
            let java_patients: Vec<u32> =
                std::fs::read_to_string(fixture(&format!("java/{partition}.patients.txt")))
                    .unwrap()
                    .lines()
                    .map(|l| l.parse().unwrap())
                    .collect();
            assert_eq!(patients(&store, partition), java_patients, "{partition}");
        }
        let rust_meta = std::fs::read_to_string(out.join("columnMeta.csv")).unwrap();
        let differences = column_meta_differences(&merge_java_column_meta(&java_metas), &rust_meta);
        if group == ["edgeA"] {
            let paths: Vec<&str> = differences.iter().map(|d| d.0.as_str()).collect();
            assert_eq!(
                paths,
                EDGE_MIN_MAX_DIVERGENCES
                    .iter()
                    .map(|d| d.0)
                    .collect::<Vec<_>>()
            );
            for (path, min, max) in EDGE_MIN_MAX_DIVERGENCES {
                let row = &differences.iter().find(|d| d.0 == path).unwrap().1;
                assert_eq!((row[5].as_str(), row[6].as_str()), (min, max), "{path}");
            }
        } else {
            assert_eq!(differences, Vec::new());
        }
    }
}

#[test]
fn a_concept_typed_differently_in_two_partitions_fails_the_load() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    let key = dir.path().join("key");
    write_key(&key);
    let mut options = options(fixture("input"), out.clone());
    options.key = KeySource::File(key);
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(
        error.contains(
            "concept \"\\\\ds\\\\e\\\\first\\\\\" is numeric in partition edgeA and categorical in partition edgeB"
        ),
        "{error}"
    );
    assert!(!out.exists());
    assert_eq!(
        std::fs::read_dir(dir.path()).unwrap().count(),
        1,
        "no staging left"
    );
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
    let mut options = options(input, dir.path().join("out"));
    options.key = KeySource::File(key);
    let report = load_partitioned(&options).unwrap();
    let counts = &report.partitions["p1"].counts;
    assert_eq!(counts.rows_read, 4, "the first line is data");
    assert_eq!(counts.dropped_unparseable_number, 1);
    assert_eq!(counts.unparseable_timestamps, 1);
    assert_eq!(counts.accepted, 3);
}

#[test]
fn a_patient_in_two_partitions_goes_into_the_overlap_set_and_tags_its_blocks() {
    let (_dir, out) = load_edge(&["edgeB", "edgeC"]);
    let store = open(&out);
    let table = store.index.partition_table();
    assert_eq!(patients(&store, "edgeB"), [1, 2, 3, 40]);
    assert_eq!(patients(&store, "edgeC"), [3, 50]);
    assert_eq!(table.overlap.iter().collect::<Vec<_>>(), [3]);
    let tagged: Vec<Vec<u32>> = table
        .partitions
        .iter()
        .map(|p| p.tagged.iter().collect())
        .collect();
    assert_eq!(tagged, [vec![3], vec![3]], "patient 3 has rows in both");
    assert_eq!(
        store.index.patients().iter().collect::<Vec<_>>(),
        [1, 2, 3, 40, 50]
    );
    let shared = store.index.get("\\ds\\e\\shared\\").unwrap();
    assert!(shared.tagged);
    assert_eq!(shared.observation_count, 4);
    assert_eq!(shared.patient_count, 3);
    assert_eq!(shared.summed_patient_count(), 4);
    assert!(!store.index.get("\\ds\\e\\only_c\\").unwrap().tagged);
    assert!(!store.index.get("\\ds\\e\\first\\").unwrap().tagged);
    let report = report(&out);
    assert_eq!(report["store"]["overlap_patients"], 1);
    assert_eq!(report["store"]["overlap_observations"], 2);
    assert_eq!(report["store"]["tagged_concepts"], 1);
    assert_eq!(
        std::fs::read_to_string(out.join("columnMeta.csv"))
            .unwrap()
            .lines()
            .map(|line| {
                let mut fields: Vec<&str> = line.split(',').collect();
                fields.drain(7..9);
                fields.join(",")
            })
            .collect::<Vec<_>>(),
        [
            "\\ds\\e\\first\\,4,0,true,moreµtext,null,null,2,2",
            "\\ds\\e\\only_c\\,1,0,true,x,null,null,1,1",
            "\\ds\\e\\shared\\,8,0,false,,4.0,9.0,4,4",
        ]
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
    let mut options = options(input, out.clone());
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
fn a_failing_partition_publishes_nothing() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("good-allConcepts.csv"), "1,\\a\\,1,,\n").unwrap();
    std::fs::write(input.join("bad-allConcepts.csv"), "x,\\a\\,1,,\n").unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let error = load_partitioned(&options(input, out.clone())).unwrap_err();
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
fn reloading_replaces_the_whole_store() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("p1-allConcepts.csv"), "1,\\a\\,1,,\n").unwrap();
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let options = options(input.clone(), out.clone());
    load_partitioned(&options).unwrap();
    let first_id = open(&out).index.store_id();

    std::fs::write(
        input.join("p1-allConcepts.csv"),
        "1,\\a\\,1,,\n2,\\b\\,2,,\n",
    )
    .unwrap();
    std::fs::write(input.join("p2-allConcepts.csv"), "3,\\a\\,1,,\n").unwrap();
    load_partitioned(&options).unwrap();
    let store = open(&out);
    assert_ne!(store.index.store_id(), first_id);
    assert_eq!(patients(&store, "p1"), [1, 2]);
    assert_eq!(patients(&store, "p2"), [3]);

    std::fs::remove_file(input.join("p2-allConcepts.csv")).unwrap();
    load_partitioned(&options).unwrap();
    let store = open(&out);
    let names: Vec<&str> = store
        .index
        .partition_table()
        .partitions
        .iter()
        .map(|p| p.name.as_str())
        .collect();
    assert_eq!(names, ["p1"]);
    assert_eq!(
        read_generation(&out),
        format!("{}\n", store.index.store_id())
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
    let mut options = options(edge_input(dir.path(), &["edgeA"]), out);
    options.key = KeySource::File(key);
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(error.contains("the store keeps the key it has"), "{error}");
}

/// Copies the Java edge export of `partitions` into a fresh directory.
fn edge_export(dir: &Path, partitions: &[&str]) -> PathBuf {
    let export = dir.join("export");
    for partition in partitions {
        std::fs::create_dir_all(export.join(partition)).unwrap();
        for file in ["concepts.bin", "patients.txt"] {
            std::fs::copy(
                fixture(&format!("java-export/{partition}/{file}")),
                export.join(partition).join(file),
            )
            .unwrap();
        }
    }
    export
}

fn migrate_options(export: PathBuf, out: PathBuf) -> MigrateOptions {
    MigrateOptions {
        export,
        out,
        key: KeySource::File(fixture("java-export/encryption_key")),
        threads: Some(2),
        skip_inconsistent: false,
    }
}

#[test]
fn migrating_the_java_partitioned_store_reproduces_its_dumps() {
    for group in [&["edgeA"][..], &["edgeB", "edgeC"]] {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("out");
        let report = migrate_partitioned(&migrate_options(
            edge_export(dir.path(), group),
            out.clone(),
        ))
        .unwrap();
        assert_eq!(
            report
                .partitions
                .keys()
                .map(String::as_str)
                .collect::<Vec<_>>(),
            group
        );
        let store = open(&out);
        assert_eq!(
            read_generation(&out),
            format!("{}\n", store.index.store_id())
        );
        assert_eq!(report.store.store_id, store.index.store_id().to_string());
        assert_eq!(
            std::fs::read(out.join("encryption_key")).unwrap(),
            std::fs::read(fixture("java-export/encryption_key")).unwrap()
        );
        let dumps = partition_dumps(&store);
        for partition in group {
            assert_eq!(
                dumps[*partition],
                std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap(),
                "{partition}"
            );
            let java_patients: Vec<u32> =
                std::fs::read_to_string(fixture(&format!("java/{partition}.patients.txt")))
                    .unwrap()
                    .lines()
                    .map(|l| l.parse().unwrap())
                    .collect();
            assert_eq!(patients(&store, partition), java_patients, "{partition}");
        }
    }
}

#[test]
fn migrating_partitions_that_type_a_concept_differently_fails() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    let error = migrate_partitioned(&migrate_options(
        edge_export(dir.path(), &EDGE_PARTITIONS),
        out.clone(),
    ))
    .unwrap_err()
    .to_string();
    assert!(error.contains("is numeric in partition edgeA"), "{error}");
    assert!(!out.exists());
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
    let input = edge_input(dir.path(), &["edgeB", "edgeC"]);
    let out = dir.path().join("out");
    std::fs::create_dir(&out).unwrap();
    write_key(&out.join("encryption_key"));
    let loaded = hpds_etl(&[
        "load",
        "--mode",
        "partitioned",
        "--input-dir",
        path_str(&input),
        "--config",
        path_str(&input.join("config.json")),
        "--out",
        path_str(&out),
    ]);
    assert!(loaded.status.success(), "{loaded:?}");
    let counts: serde_json::Value = serde_json::from_slice(&loaded.stdout).unwrap();
    assert_eq!(counts["partitions"]["edgeB"]["accepted"], 5);
    assert_eq!(counts["store"]["overlap_patients"], 1);

    let dumps = dir.path().join("dumps");
    let dumped = hpds_etl(&["dump", "--store", path_str(&out), "--out", path_str(&dumps)]);
    assert!(dumped.status.success(), "{dumped:?}");
    for partition in ["edgeB", "edgeC"] {
        let text = std::fs::read_to_string(dumps.join(format!("{partition}.csv"))).unwrap();
        let body = text.split_once("\r\n").unwrap().1;
        assert_eq!(
            body,
            std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap()
        );
    }
    let merged = hpds_etl(&["dump", "--store", path_str(&out), "--concat"]);
    assert!(merged.status.success(), "{merged:?}");
    let mut expected =
        String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,TIMESTAMP\r\n");
    for partition in ["edgeB", "edgeC"] {
        expected.push_str(
            &std::fs::read_to_string(fixture(&format!("java/{partition}.dump.csv"))).unwrap(),
        );
    }
    assert_eq!(String::from_utf8(merged.stdout).unwrap(), expected);

    let no_out = hpds_etl(&["dump", "--store", path_str(&out)]);
    assert!(!no_out.status.success());
    assert!(String::from_utf8_lossy(&no_out.stderr).contains("--out <directory>"));

    let regenerated = hpds_etl(&["column-meta-csv", "--store", path_str(&out), "--merged"]);
    assert!(regenerated.status.success(), "{regenerated:?}");
}

#[test]
fn cli_maintenance_commands_change_the_one_store() {
    let (_dir, out) = load_edge(&["edgeB", "edgeC"]);
    let id = open(&out).index.store_id();
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
    assert_eq!(report["removed"], 1);
    let store = open(&out);
    assert!(store.index.get("\\ds\\e\\shared\\").is_none());
    assert_eq!(store.index.store_id(), id);
    assert_eq!(
        store
            .index
            .partition_table()
            .overlap
            .iter()
            .collect::<Vec<_>>(),
        [3],
        "the partition table is kept"
    );
    let tagged: Vec<u64> = store
        .index
        .partition_table()
        .partitions
        .iter()
        .map(|p| p.tagged.len())
        .collect();
    assert_eq!(
        tagged,
        [0, 0],
        "patient 3's only tagged rows were in the removed concept"
    );
    assert_eq!(report["tagged_patients_dropped"], 2);
    let csv_path = out.join("columnMeta.csv");
    let before = std::fs::read_to_string(&csv_path).unwrap();
    assert!(!before.contains("shared"));
    assert_eq!(read_generation(&out), format!("{id}\n"));

    std::fs::remove_file(&csv_path).unwrap();
    let regenerated = hpds_etl(&["column-meta-csv", "--store", path_str(&out)]);
    assert!(regenerated.status.success(), "{regenerated:?}");
    assert_eq!(std::fs::read_to_string(&csv_path).unwrap(), before);
    let elsewhere = out.parent().unwrap().join("x.csv");
    let with_out = hpds_etl(&[
        "column-meta-csv",
        "--store",
        path_str(&out),
        "--out",
        path_str(&elsewhere),
    ]);
    assert!(with_out.status.success(), "{with_out:?}");
    assert_eq!(std::fs::read_to_string(&elsewhere).unwrap(), before);
}

#[test]
fn cli_migrate_detects_a_partitioned_export() {
    let dir = tempfile::tempdir().unwrap();
    let export = edge_export(dir.path(), &["edgeB", "edgeC"]);
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
    assert_eq!(report["store"]["overlap_patients"], 1);
    let shared = std::fs::read_to_string(out.join("columnMeta.csv"))
        .unwrap()
        .lines()
        .find(|line| line.starts_with("\\ds\\e\\shared\\"))
        .unwrap()
        .to_string();
    let mut fields: Vec<&str> = shared.split(',').collect();
    fields.drain(7..9);
    assert_eq!(fields.join(","), "\\ds\\e\\shared\\,8,0,false,,4.0,9.0,4,4");
}

fn mode_bits(path: &Path) -> u32 {
    use std::os::unix::fs::PermissionsExt;
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn set_mode(path: &Path, mode: u32) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).unwrap();
}

fn stderr(output: &std::process::Output) -> String {
    String::from_utf8_lossy(&output.stderr).into_owned()
}

#[test]
fn partitioned_mode_needs_a_config_or_no_config() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("out");
    let key = dir.path().join("key");
    write_key(&key);
    let mut options = PartitionedLoadOptions::new(fixture("input"), out.clone());
    options.key = KeySource::File(key.clone());
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(error.contains("--config <file> or --no-config"), "{error}");
    assert!(
        error.contains("/opt/local/hpds_input/config.json"),
        "{error}"
    );
    assert!(!out.exists());

    let inputs = harmonization_inputs();
    let args = |extra: &[&str]| {
        let mut args = vec![
            "load",
            "--mode",
            "partitioned",
            "--input-dir",
            path_str(&inputs),
        ];
        args.extend_from_slice(extra);
        args.iter().map(|a| a.to_string()).collect::<Vec<_>>()
    };
    let run = |args: Vec<String>| {
        let refs: Vec<&str> = args.iter().map(String::as_str).collect();
        hpds_etl(&refs)
    };
    let refused = run(args(&[
        "--out",
        path_str(&out),
        "--key-file",
        path_str(&key),
    ]));
    assert!(!refused.status.success());
    assert!(stderr(&refused).contains("/opt/local/hpds_input/config.json"));
    let both = run(args(&[
        "--out",
        path_str(&out),
        "--config",
        path_str(&fixture("input/config.json")),
        "--no-config",
    ]));
    assert!(!both.status.success());
    let loaded = run(args(&[
        "--out",
        path_str(&out),
        "--key-file",
        path_str(&key),
        "--no-config",
    ]));
    assert!(loaded.status.success(), "{loaded:?}");

    let other = dir.path().join("config.json");
    std::fs::write(&other, r#"{"other.csv": {"dataset_name": "x"}}"#).unwrap();
    let out2 = dir.path().join("out2");
    let warned = run(args(&[
        "--out",
        path_str(&out2),
        "--key-file",
        path_str(&key),
        "--config",
        path_str(&other),
    ]));
    assert!(warned.status.success(), "{warned:?}");
    assert!(
        stderr(&warned).contains("no allConcepts entry"),
        "{}",
        stderr(&warned)
    );
}

#[test]
fn the_generation_marker_follows_every_publish() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("p1-allConcepts.csv"), "1,\\a\\,1,,\n").unwrap();
    std::fs::write(input.join("p2-allConcepts.csv"), "2,\\a\\,1,,\n").unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = options(input, out.clone());
    options.key = KeySource::File(key);
    let first = load_partitioned(&options).unwrap();
    assert_eq!(read_generation(&out), format!("{}\n", first.store.store_id));
    assert_eq!(mode_bits(&out.join(".generation")), 0o600);
    let second = load_partitioned(&options).unwrap();
    assert_eq!(
        read_generation(&out),
        format!("{}\n", second.store.store_id)
    );
    assert_ne!(first.store.store_id, second.store.store_id);
    let leftovers: Vec<String> = std::fs::read_dir(&out)
        .unwrap()
        .chain(std::fs::read_dir(dir.path()).unwrap())
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains("tmp") || n.contains("staging") || n.contains("partitions"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
}

#[test]
fn a_byte_order_mark_and_text_after_a_closing_quote_are_counted() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(
        input.join("p1-allConcepts.csv"),
        b"\xEF\xBB\xBF1,\\a\\,1,,\n2,\"\\b\\\"x,,t,\n3,\"\\b\\\",,\"u\" ,\n4,\\a\\,2,,\n",
    )
    .unwrap();
    std::fs::write(input.join("p2-allConcepts.csv"), "5,\\a\\,1,,\n").unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = options(input, out.clone());
    options.key = KeySource::File(key);
    let report = load_partitioned(&options).unwrap();
    let p1 = &report.partitions["p1"].counts;
    assert_eq!(p1.inputs_with_byte_order_mark, 1);
    assert_eq!(p1.records_with_text_after_closing_quote, 2);
    assert_eq!(p1.rows_read, 4);
    assert_eq!(p1.accepted, 4, "the row after the mark keeps its patient");
    let p2 = &report.partitions["p2"].counts;
    assert_eq!(
        (
            p2.inputs_with_byte_order_mark,
            p2.records_with_text_after_closing_quote
        ),
        (0, 0)
    );
    let written = self::report(&out);
    assert_eq!(
        written["partitions"]["p1"]["counts"]["inputs_with_byte_order_mark"],
        1
    );
    assert_eq!(
        written["partitions"]["p1"]["counts"]["records_with_text_after_closing_quote"],
        2
    );
    assert_eq!(patients(&open(&out), "p1"), [1, 2, 3, 4]);
}

/// Writes a format 1 partitioned layout into `dir`: one partition directory holding a
/// `pheno.index` (its content does not matter to the layout check).
fn write_format_1_layout(dir: &Path) {
    std::fs::create_dir_all(dir.join("p1")).unwrap();
    std::fs::write(dir.join("p1").join("pheno.index"), b"x").unwrap();
}

#[test]
fn a_format_1_partitioned_store_is_refused_naming_convert() {
    let dir = tempfile::tempdir().unwrap();
    let old = dir.path().join("old");
    write_format_1_layout(&old);
    write_key(&old.join("encryption_key"));
    let concepts = dir.path().join("concepts.txt");
    std::fs::write(&concepts, "\\a\\\n").unwrap();
    let dumps = dir.path().join("dumps");
    let commands: [Vec<&str>; 4] = [
        vec!["dump", "--store", path_str(&old), "--out", path_str(&dumps)],
        vec![
            "remove-concepts",
            "--store",
            path_str(&old),
            "--concepts",
            path_str(&concepts),
        ],
        vec!["column-meta-csv", "--store", path_str(&old)],
        vec!["dump", "--store", path_str(&old), "--concat"],
    ];
    for command in &commands {
        let ran = hpds_etl(command);
        assert!(!ran.status.success(), "{command:?}");
        assert!(
            stderr(&ran).contains("hpds-etl convert"),
            "{}",
            stderr(&ran)
        );
    }
    let mut options = options(edge_input(dir.path(), &["edgeA"]), old.clone());
    options.key = KeySource::OutputDirectoryKeyFile;
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(error.contains("hpds-etl convert"), "{error}");
    std::fs::remove_dir_all(old.join("p1")).unwrap();
    std::fs::create_dir(old.join(".combined")).unwrap();
    let error = load_partitioned(&options).unwrap_err().to_string();
    assert!(error.contains("hpds-etl convert"), "{error}");
}

#[test]
fn dump_outputs_are_private_new_and_outside_the_store() {
    let (dir, out) = load_edge(&["edgeB", "edgeC"]);
    let dumps = dir.path().join("dumps");
    let dump = |extra: &[&str]| {
        let mut args = vec!["dump", "--store", path_str(&out)];
        args.extend_from_slice(extra);
        hpds_etl(&args)
    };
    let written = dump(&["--out", path_str(&dumps)]);
    assert!(written.status.success(), "{written:?}");
    assert_eq!(mode_bits(&dumps), 0o700);
    for partition in ["edgeB", "edgeC"] {
        assert_eq!(mode_bits(&dumps.join(format!("{partition}.csv"))), 0o600);
    }
    let names: Vec<String> = std::fs::read_dir(&dumps)
        .unwrap()
        .map(|e| e.unwrap().file_name().into_string().unwrap())
        .collect();
    assert_eq!(names.len(), 2, "no split rows are left: {names:?}");

    let again = dump(&["--out", path_str(&dumps)]);
    assert!(!again.status.success());
    assert!(
        stderr(&again).contains("pass --force"),
        "{}",
        stderr(&again)
    );
    let forced = dump(&["--out", path_str(&dumps), "--force"]);
    assert!(forced.status.success(), "{forced:?}");

    set_mode(&dumps, 0o755);
    let open_dir = dump(&["--out", path_str(&dumps), "--force"]);
    assert!(!open_dir.status.success());
    assert!(stderr(&open_dir).contains("group or others"));
    set_mode(&dumps, 0o700);

    let concat = dir.path().join("all.csv");
    for flag in ["--concat", "--merged"] {
        let ran = dump(&[flag, "--patients", "--out", path_str(&concat), "--force"]);
        assert!(ran.status.success(), "{flag}: {ran:?}");
        assert_eq!(mode_bits(&concat), 0o600);
        assert_eq!(
            std::fs::read_to_string(&concat).unwrap(),
            "1\n2\n3\n40\n50\n"
        );
    }
    let concat_rows = dump(&["--concat", "--out", path_str(&concat), "--force"]);
    assert!(concat_rows.status.success(), "{concat_rows:?}");
    let leftovers: Vec<String> = std::fs::read_dir(dir.path())
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains("hpds-etl-dump"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");

    for target in [out.join("dumps"), out.join("x.csv")] {
        let concat = dump(&["--concat", "--out", path_str(&target)]);
        assert!(!concat.status.success(), "{}", target.display());
        assert!(
            stderr(&concat).contains("lies inside"),
            "{}",
            stderr(&concat)
        );
        let split = dump(&["--out", path_str(&target)]);
        assert!(!split.status.success(), "{}", target.display());
        assert!(!target.exists(), "{}", target.display());
    }
}

/// Waits up to a minute for `ready` to hold, then a little longer, so a thread that got
/// that far without blocking would have finished.
fn wait_until(mut ready: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
    while !ready() {
        assert!(std::time::Instant::now() < deadline, "timed out");
        std::thread::sleep(std::time::Duration::from_millis(20));
    }
    std::thread::sleep(std::time::Duration::from_millis(500));
}

/// Takes the exclusive `flock` on the directory `dir` that every command writing the store
/// takes.
fn hold_store_lock(dir: &Path) -> std::fs::File {
    let file = std::fs::File::open(dir).unwrap();
    file.lock().unwrap();
    file
}

/// True when some directory in `parent` whose name starts with `prefix` holds `file`.
fn staged(parent: &Path, prefix: &str, file: &str) -> bool {
    std::fs::read_dir(parent).unwrap().flatten().any(|e| {
        e.file_name().to_string_lossy().starts_with(prefix) && e.path().join(file).exists()
    })
}

#[test]
fn a_load_publishes_into_an_existing_store_only_under_the_store_lock() {
    let dir = tempfile::tempdir().unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = options(harmonization_inputs(), out.clone());
    options.key = KeySource::File(key);
    options.threads = Some(2);
    load_partitioned(&options).unwrap();
    let before = read_generation(&out);
    let lock = hold_store_lock(&out);
    let load = std::thread::spawn(move || load_partitioned(&options).map(|_| ()));
    let parent = dir.path().to_path_buf();
    wait_until(|| load.is_finished() || staged(&parent, ".out.staging-", "etl-report.json"));
    assert!(!load.is_finished(), "the publish waits for the lock");
    assert_eq!(read_generation(&out), before);
    drop(lock);
    load.join().unwrap().unwrap();
    let after = read_generation(&out);
    assert_ne!(after, before);
    assert_eq!(after, format!("{}\n", open(&out).index.store_id()));
}

#[test]
fn remove_concepts_runs_only_under_the_store_lock() {
    let (dir, out) = load_edge(&["edgeB", "edgeC"]);
    let concepts = dir.path().join("remove.txt");
    std::fs::write(&concepts, "\\ds\\e\\shared\\\n").unwrap();
    let lock = hold_store_lock(&out);
    let target = out.clone();
    let remove = std::thread::spawn(move || {
        hpds_etl::store_maintenance::remove_concepts(
            &target,
            &concepts,
            &test_protection(),
            &hpds_etl::store_maintenance::RemoveOptions::default(),
        )
    });
    std::thread::sleep(std::time::Duration::from_millis(500));
    assert!(!remove.is_finished(), "remove-concepts waits for the lock");
    assert!(open(&out).index.get("\\ds\\e\\shared\\").is_some());
    drop(lock);
    assert_eq!(remove.join().unwrap().unwrap().removed, 1);
    assert!(open(&out).index.get("\\ds\\e\\shared\\").is_none());
}

#[test]
fn column_meta_csv_writes_only_under_the_store_lock() {
    let (_dir, out) = load_edge(&["edgeB", "edgeC"]);
    let csv = out.join("columnMeta.csv");
    let before = std::fs::read_to_string(&csv).unwrap();
    std::fs::remove_file(&csv).unwrap();
    let lock = hold_store_lock(&out);
    let target = out.clone();
    let regenerate = std::thread::spawn(move || {
        hpds_etl::store_maintenance::regenerate_column_meta_csv(&target, None, &test_protection())
    });
    std::thread::sleep(std::time::Duration::from_millis(500));
    assert!(
        !regenerate.is_finished(),
        "column-meta-csv waits for the lock"
    );
    assert!(!csv.exists());
    drop(lock);
    assert_eq!(regenerate.join().unwrap().unwrap(), 3);
    assert_eq!(std::fs::read_to_string(&csv).unwrap(), before);
}

#[test]
fn a_load_into_a_store_a_service_holds_is_refused_before_it_builds() {
    let dir = tempfile::tempdir().unwrap();
    let key = dir.path().join("key");
    write_key(&key);
    let out = dir.path().join("out");
    let mut options = options(harmonization_inputs(), out.clone());
    options.key = KeySource::File(key);
    options.threads = Some(2);
    load_partitioned(&options).unwrap();
    let before = read_generation(&out);
    let service = std::fs::File::open(out.join("pheno.index")).unwrap();
    service.lock_shared().unwrap();

    let error = load_partitioned(&options).unwrap_err().to_string();

    assert!(error.contains("open in a running query service"), "{error}");
    assert_eq!(read_generation(&out), before);
    assert!(!staged(dir.path(), ".out.staging-", "pheno.index"));
    drop(service);
    load_partitioned(&options).unwrap();
    assert_ne!(read_generation(&out), before);
}
