//! Migrates hand-built `ExportForMigration` exports and the golden export Java wrote, and
//! reads the stores back.

mod common;

use std::path::{Path, PathBuf};

use common::{KEY, Row, row};
use hpds_etl::EtlError;
use hpds_etl::dump::dump;
use hpds_etl::java::java_double_to_string;
use hpds_etl::load::KeySource;
use hpds_etl::migrate::{
    EXPORT_CONCEPTS_FILE, EXPORT_ID_LEN, EXPORT_PATIENTS_FILE, ExportKind, KeyCollision,
    MigrateOptions, MigrateReport, NameMismatch, SkippedConcept, migrate, migrate_partitioned,
    seal_export,
};
use hpds_etl::store::OpenStore;
use hpds_format::{Block, ConceptType, CryptoKey, NO_TIMESTAMP, Protection};

/// One observation as the exporter writes it.
enum Value {
    Number(f64),
    Text(&'static str),
}

/// One metastore entry as the exporter writes it.
struct Entry {
    key: &'static str,
    text: bool,
    categorical: bool,
    category_values: Option<Vec<&'static str>>,
    width: i32,
    column_offset: i32,
    observation_count: i32,
    patient_count: i32,
    observations: Vec<(i32, Value, Option<i64>)>,
}

impl Entry {
    fn numeric(key: &'static str, observations: Vec<(i32, f64, Option<i64>)>) -> Self {
        let mut entry = Entry {
            key,
            text: false,
            categorical: false,
            category_values: None,
            width: 8,
            column_offset: 0,
            observation_count: 0,
            patient_count: 0,
            observations: observations
                .into_iter()
                .map(|(p, v, t)| (p, Value::Number(v), t))
                .collect(),
        };
        entry.count();
        entry
    }

    fn text(key: &'static str, observations: Vec<(i32, &'static str, Option<i64>)>) -> Self {
        let mut values: Vec<&'static str> = observations.iter().map(|o| o.1).collect();
        values.sort_by(|a, b| hpds_format::java_string_cmp(a, b));
        values.dedup();
        let mut entry = Entry {
            key,
            text: true,
            categorical: true,
            category_values: Some(values),
            width: 3,
            column_offset: 0,
            observation_count: 0,
            patient_count: 0,
            observations: observations
                .into_iter()
                .map(|(p, v, t)| (p, Value::Text(v), t))
                .collect(),
        };
        entry.count();
        entry
    }

    fn count(&mut self) {
        self.observation_count = self.observations.len() as i32;
        let mut patients: Vec<i32> = self.observations.iter().map(|o| o.0).collect();
        patients.dedup();
        self.patient_count = patients.len() as i32;
    }
}

fn put_string(out: &mut Vec<u8>, value: &str) {
    out.extend_from_slice(&(value.len() as i32).to_be_bytes());
    out.extend_from_slice(value.as_bytes());
}

fn put_nullable_i64(out: &mut Vec<u8>, value: Option<i64>) {
    match value {
        Some(v) => {
            out.push(1);
            out.extend_from_slice(&v.to_be_bytes());
        }
        None => out.push(0),
    }
}

/// Encodes `entries` as the content of `concepts.bin`, with the end record's counts.
fn encode(entries: &[Entry]) -> Vec<u8> {
    let mut out = Vec::new();
    let mut observations = 0i64;
    for e in entries {
        out.push(1);
        put_string(&mut out, e.key);
        out.push(u8::from(e.key.contains('\u{FFFD}')));
        out.push(u8::from(e.text));
        put_string(&mut out, e.key);
        out.extend_from_slice(&e.width.to_be_bytes());
        out.extend_from_slice(&e.column_offset.to_be_bytes());
        out.push(u8::from(e.categorical));
        match &e.category_values {
            None => out.extend_from_slice(&(-1i32).to_be_bytes()),
            Some(values) => {
                out.extend_from_slice(&(values.len() as i32).to_be_bytes());
                for v in values {
                    put_string(&mut out, v);
                }
            }
        }
        put_nullable_i64(&mut out, Some(f64::MIN_POSITIVE.to_bits() as i64));
        put_nullable_i64(&mut out, Some(4.9e-324f64.to_bits() as i64));
        out.extend_from_slice(&0i64.to_be_bytes());
        out.extend_from_slice(&0i64.to_be_bytes());
        out.extend_from_slice(&e.observation_count.to_be_bytes());
        out.extend_from_slice(&e.patient_count.to_be_bytes());
        out.push(0);
        put_nullable_i64(&mut out, None);
        put_nullable_i64(&mut out, None);
        put_string(&mut out, e.key);
        out.extend_from_slice(&e.width.to_be_bytes());
        out.extend_from_slice(&(e.observations.len() as i32).to_be_bytes());
        for (patient, value, timestamp) in &e.observations {
            out.extend_from_slice(&patient.to_be_bytes());
            match value {
                Value::Number(v) => out.extend_from_slice(&v.to_bits().to_be_bytes()),
                Value::Text(t) => put_string(&mut out, t),
            }
            put_nullable_i64(&mut out, *timestamp);
        }
        observations += e.observations.len() as i64;
    }
    out.push(0);
    out.extend_from_slice(&(entries.len() as i64).to_be_bytes());
    out.extend_from_slice(&observations.to_be_bytes());
    out
}

struct Migrated {
    _dir: tempfile::TempDir,
    out: PathBuf,
    report: MigrateReport,
    store: OpenStore,
}

fn key_file(dir: &Path) -> PathBuf {
    let path = dir.join("key");
    std::fs::write(&path, format!("{KEY}\n")).unwrap();
    path
}

fn crypto_key() -> CryptoKey {
    CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap()
}

/// Seals content into an export file in 64-byte frames, so every test crosses frames.
fn seal(id: [u8; EXPORT_ID_LEN], kind: ExportKind, content: &[u8]) -> Vec<u8> {
    seal_export(&crypto_key(), id, kind, content, 64).unwrap()
}

fn write_raw_export(dir: &Path, concepts: &[u8], patients: &[u8]) -> PathBuf {
    let export = dir.join("export");
    std::fs::create_dir_all(&export).unwrap();
    std::fs::write(export.join(EXPORT_CONCEPTS_FILE), concepts).unwrap();
    std::fs::write(export.join(EXPORT_PATIENTS_FILE), patients).unwrap();
    export
}

fn write_export(dir: &Path, concepts: &[u8], patients: &str) -> PathBuf {
    let id = [7u8; EXPORT_ID_LEN];
    write_raw_export(
        dir,
        &seal(id, ExportKind::Concepts, concepts),
        &seal(id, ExportKind::Patients, patients.as_bytes()),
    )
}

fn options(export: PathBuf, out: PathBuf, key: KeySource) -> MigrateOptions {
    MigrateOptions {
        export,
        out,
        key,
        threads: Some(2),
        skip_inconsistent: false,
        combined: true,
    }
}

fn run(concepts: &[u8], patients: &str) -> Result<Migrated, EtlError> {
    run_with(concepts, patients, false)
}

fn run_with(
    concepts: &[u8],
    patients: &str,
    skip_inconsistent: bool,
) -> Result<Migrated, EtlError> {
    let dir = tempfile::tempdir().unwrap();
    let export = write_export(dir.path(), concepts, patients);
    run_export(dir, export, skip_inconsistent)
}

fn run_export(
    dir: tempfile::TempDir,
    export: PathBuf,
    skip_inconsistent: bool,
) -> Result<Migrated, EtlError> {
    let out = dir.path().join("store");
    let key = key_file(dir.path());
    let report = migrate(&MigrateOptions {
        skip_inconsistent,
        ..options(export, out.clone(), KeySource::File(key))
    })?;
    let store = OpenStore::open(&out, Protection::Encrypted(crypto_key())).unwrap();
    Ok(Migrated {
        _dir: dir,
        out,
        report,
        store,
    })
}

fn rows(store: &OpenStore, path: &str) -> Vec<Row> {
    let meta = store.index.get(path).unwrap();
    let ts = |t: i64| (t != NO_TIMESTAMP).then_some(t);
    match store.reader.read_block(&store.codec, meta).unwrap() {
        Block::Numeric(b) => b
            .by_key()
            .iter()
            .map(|&i| {
                let i = i as usize;
                (
                    b.patients()[i],
                    java_double_to_string(b.values()[i]),
                    ts(b.timestamp_at_value_pos(i)),
                )
            })
            .collect(),
        Block::Categorical(b) => (0..b.len())
            .map(|i| {
                let value = b.dictionary_value(b.value_indices()[i] as usize).unwrap();
                (
                    b.patients()[i],
                    value.to_string(),
                    ts(b.timestamp_at_key_pos(i)),
                )
            })
            .collect(),
    }
}

fn sample() -> Vec<Entry> {
    let mut negative = Entry::numeric(
        "\\a\\negative\\",
        vec![
            (1, -3.0, None),
            (1, -1.5, Some(i64::MIN)),
            (1, -2.0, Some(1_700_000_000_000)),
            (5, -0.0, Some(-5)),
        ],
    );
    negative.width = 0;
    negative.column_offset = 17;
    vec![
        negative,
        Entry::text(
            "\\b\\text\\",
            vec![
                (2, "z", None),
                (2, "a,\"b\"\nc", Some(10)),
                (2, "z", None),
                (7, "\u{1F600}", None),
            ],
        ),
        Entry::numeric("\\c\\empty\\", vec![]),
    ]
}

#[test]
fn migrate_keeps_observations_in_exported_order() {
    let migrated = run(&encode(&sample()), "1\n2\n5\n7\n99\n").unwrap();
    let store = &migrated.store;
    assert_eq!(
        rows(store, "\\a\\negative\\"),
        vec![
            row(1, "-3.0", None),
            row(1, "-1.5", None),
            row(1, "-2.0", Some(1_700_000_000_000)),
            row(5, "-0.0", Some(-5)),
        ]
    );
    assert_eq!(
        rows(store, "\\b\\text\\"),
        vec![
            row(2, "z", None),
            row(2, "a,\"b\"\nc", Some(10)),
            row(2, "z", None),
            row(7, "\u{1F600}", None),
        ]
    );
    assert!(rows(store, "\\c\\empty\\").is_empty());
    assert_eq!(
        store.index.patients().iter().collect::<Vec<_>>(),
        [1, 2, 5, 7, 99]
    );
    assert_eq!(migrated.report.patients_without_observations, 1);
    assert_eq!(migrated.report.observed_patients_missing_from_set, 0);
    assert_eq!(migrated.report.observations, 8);
}

#[test]
fn migrate_copies_java_metadata_and_recomputes_ranges() {
    let migrated = run(&encode(&sample()), "1\n2\n5\n7\n").unwrap();
    let negative = migrated.store.index.get("\\a\\negative\\").unwrap();
    assert_eq!(negative.concept_type, ConceptType::Numeric);
    assert_eq!(negative.min, Some(-3.0));
    assert_eq!(negative.max.map(f64::to_bits), Some((-0.0f64).to_bits()));
    assert_eq!(negative.observation_count, 4);
    assert_eq!(negative.patient_count, 2);
    assert_eq!(negative.width_in_bytes, 0);
    assert_eq!(negative.column_offset, 17);
    assert!(negative.has_timestamp);
    assert_eq!(negative.timestamp_min, Some(-5));
    assert_eq!(negative.timestamp_max, Some(1_700_000_000_000));
    assert_eq!(negative.category_values, None);

    let text = migrated.store.index.get("\\b\\text\\").unwrap();
    assert_eq!(text.concept_type, ConceptType::Categorical);
    assert_eq!(
        text.category_values.as_deref(),
        Some(&["a,\"b\"\nc".to_string(), "z".into(), "\u{1F600}".into()][..])
    );
    assert_eq!((text.min, text.max), (None, None));
    assert_eq!(text.width_in_bytes, 3);
    assert_eq!(text.timestamp_min, Some(10));

    let empty = migrated.store.index.get("\\c\\empty\\").unwrap();
    assert_eq!(
        (empty.min, empty.max, empty.has_timestamp),
        (None, None, false)
    );

    let csv = std::fs::read_to_string(migrated.out.join("columnMeta.csv")).unwrap();
    assert!(
        csv.starts_with("\\a\\negative\\,0,17,false,,-3.0,-0.0,"),
        "{csv}"
    );
    assert!(migrated.out.join("etl-report.json").is_file());
}

#[test]
fn migrate_strips_replacement_characters_and_keeps_the_later_colliding_key() {
    let entries = vec![
        Entry::numeric("\\k\\ab\\", vec![(1, 1.0, None)]),
        Entry::numeric("\\k\\a\u{FFFD}b\\", vec![(2, 2.0, None)]),
        Entry::numeric("\\k\\c\u{FFFD}\\", vec![(3, 3.0, None)]),
    ];
    let migrated = run(&encode(&entries), "1\n2\n3\n").unwrap();
    let paths: Vec<&str> = migrated
        .store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    assert_eq!(paths, ["\\k\\ab\\", "\\k\\c\\"]);
    assert_eq!(
        rows(&migrated.store, "\\k\\ab\\"),
        vec![row(2, "2.0", None)]
    );
    assert_eq!(migrated.report.keys_with_replacement_character, 2);
    assert_eq!(migrated.report.exported_concepts, 3);
    assert_eq!(migrated.report.patients_without_observations, 1);
    assert_eq!(
        migrated.report.collisions,
        vec![KeyCollision {
            path: "\\k\\ab\\".into(),
            replaced: "\\k\\ab\\".into(),
            kept: "\\k\\a\u{FFFD}b\\".into(),
        }]
    );
}

#[test]
fn migrated_store_dumps_in_java_order() {
    let migrated = run(&encode(&sample()), "1\n2\n5\n7\n").unwrap();
    let mut out = Vec::new();
    let counts = dump(&migrated.store, &mut out).unwrap();
    assert_eq!((counts.concepts, counts.observations), (3, 8));
    let expected = "PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,TIMESTAMP\r\n\
        1,\\a\\negative\\,-3.0,,\r\n\
        1,\\a\\negative\\,-1.5,,\r\n\
        1,\\a\\negative\\,-2.0,,1700000000000\r\n\
        5,\\a\\negative\\,-0.0,,-5\r\n\
        2,\\b\\text\\,,z,\r\n\
        2,\\b\\text\\,,\"a,\"\"b\"\"\nc\",10\r\n\
        2,\\b\\text\\,,z,\r\n\
        7,\\b\\text\\,,\u{1F600},\r\n";
    assert_eq!(String::from_utf8(out).unwrap(), expected);
}

fn refused(entries: &[Entry], patients: &str) -> String {
    match run(&encode(entries), patients) {
        Err(e) => e.to_string(),
        Ok(_) => panic!("migration succeeded"),
    }
}

#[test]
fn migrate_refuses_what_it_cannot_reproduce() {
    let message = refused(&[Entry::numeric("\\n\\", vec![(-1, 1.0, None)])], "1\n");
    assert!(message.contains("negative"), "{message}");

    let message = refused(
        &[Entry::numeric(
            "\\n\\",
            vec![(2, 1.0, None), (1, 1.0, None)],
        )],
        "1\n2\n",
    );
    assert!(message.contains("not ordered by patient"), "{message}");

    let mut wrong_count = Entry::numeric("\\n\\", vec![(1, 1.0, None)]);
    wrong_count.observation_count = 2;
    let message = refused(&[wrong_count], "1\n");
    assert!(message.contains("observationCount"), "{message}");

    let mut wrong_type = Entry::numeric("\\n\\", vec![(1, 1.0, None)]);
    wrong_type.categorical = true;
    let message = refused(&[wrong_type], "1\n");
    assert!(message.contains("categorical"), "{message}");

    let mut wrong_values = Entry::text("\\t\\", vec![(1, "a", None)]);
    wrong_values.category_values = Some(vec!["a", "b"]);
    let message = refused(&[wrong_values], "1\n");
    assert!(message.contains("categoryValues"), "{message}");

    let message = refused(&[Entry::numeric("\\n\\", vec![(1, 1.0, None)])], "-1\n");
    assert!(message.contains("negative"), "{message}");
}

#[test]
fn inconsistent_concepts_stop_the_migration_without_naming_values() {
    let mut wrong_values = Entry::text("\\t\\", vec![(4242, "secret-value", None)]);
    wrong_values.category_values = Some(vec!["other-secret"]);
    let message = refused(&[wrong_values], "4242\n");
    assert!(message.contains("categoryValues"), "{message}");
    assert!(message.contains("--skip-inconsistent"), "{message}");
    assert!(!message.contains("secret"), "{message}");
    assert!(!message.contains("4242"), "{message}");

    let message = refused(
        &[Entry::numeric(
            "\\n\\",
            vec![(4242, 1.0, None), (17, 1.0, None)],
        )],
        "17\n4242\n",
    );
    assert!(
        message.contains("not ordered by patient at observation 1"),
        "{message}"
    );
    assert!(
        !message.contains("4242") && !message.contains("17 "),
        "{message}"
    );

    let message = refused(&[Entry::numeric("\\n\\", vec![(-4242, 1.0, None)])], "1\n");
    assert!(message.contains("negative"), "{message}");
    assert!(!message.contains("4242"), "{message}");

    let message = refused(&[Entry::numeric("\\n\\", vec![(1, 1.0, None)])], "-4242\n");
    assert!(
        message.contains("line 1") && message.contains("negative"),
        "{message}"
    );
    assert!(!message.contains("4242"), "{message}");
}

#[test]
fn skip_inconsistent_leaves_concepts_out_and_reports_them() {
    let mut wrong_count = Entry::numeric("\\a\\wrong\\", vec![(1, 1.0, None)]);
    wrong_count.observation_count = 2;
    let entries = vec![
        wrong_count,
        Entry::numeric("\\b\\fine\\", vec![(2, 2.0, None)]),
    ];
    let migrated = run_with(&encode(&entries), "1\n2\n", true).unwrap();
    let paths: Vec<&str> = migrated
        .store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    assert_eq!(paths, ["\\b\\fine\\"]);
    assert_eq!(
        migrated.report.skipped,
        vec![SkippedConcept {
            key: "\\a\\wrong\\".into(),
            reason: "ColumnMeta.observationCount is 2 but the cube holds 1 observations".into(),
        }]
    );
    assert_eq!(migrated.report.exported_concepts, 2);
    assert_eq!(migrated.report.patients_without_observations, 1);
    let report: serde_json::Value =
        serde_json::from_slice(&std::fs::read(migrated.out.join("etl-report.json")).unwrap())
            .unwrap();
    assert_eq!(report["skipped"][0]["key"], "\\a\\wrong\\");
}

/// The export `ExportForMigrationTest.writesTheGoldenExportForRust` wrote with the real Java
/// classes, checked in with its key.
#[test]
fn migrate_reads_the_java_golden_export() {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/java-export");
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("store");
    let report = migrate(&options(
        fixture.clone(),
        out.clone(),
        KeySource::File(fixture.join("encryption_key")),
    ))
    .unwrap();
    let key = CryptoKey::load(fixture.join("encryption_key")).unwrap();
    let store = OpenStore::open(&out, Protection::Encrypted(key)).unwrap();

    let paths: Vec<&str> = store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    assert_eq!(
        paths,
        ["\\golden\\ab\\", "\\golden\\empty\\", "\\golden\\text\\"]
    );
    assert_eq!(
        rows(&store, "\\golden\\ab\\"),
        vec![
            row(2, "2.5", Some(1_700_000_000_000)),
            row(2, "-0.0", None),
            row(3, "1.0E10", None),
            row(4, "NaN", Some(-5)),
        ]
    );
    let awkward = "multi\nline, \"quoted\" \u{1F600} \u{B5}";
    assert_eq!(
        rows(&store, "\\golden\\text\\"),
        vec![
            row(0, "z", None),
            row(0, awkward, Some(10)),
            row(6, "z", None)
        ]
    );
    let text = store.index.get("\\golden\\text\\").unwrap();
    assert_eq!(
        text.category_values.as_deref(),
        Some(&[awkward.to_string(), "z".into()][..])
    );
    assert_eq!(text.width_in_bytes, 22);
    let ab = store.index.get("\\golden\\ab\\").unwrap();
    assert_eq!((ab.width_in_bytes, ab.column_offset), (8, 4));
    assert!(rows(&store, "\\golden\\empty\\").is_empty());
    assert_eq!(
        store.index.patients().iter().collect::<Vec<_>>(),
        [0, 1, 2, 3, 4, 6, 8]
    );

    assert_eq!(report.exported_concepts, 4);
    assert_eq!(report.keys_with_replacement_character, 1);
    assert_eq!(
        report.collisions,
        vec![KeyCollision {
            path: "\\golden\\ab\\".into(),
            replaced: "\\golden\\ab\\".into(),
            kept: "\\golden\\a\u{FFFD}b\\".into(),
        }]
    );
    assert_eq!(
        report.name_mismatches,
        vec![NameMismatch {
            key: "\\golden\\text\\".into(),
            name: Some("\\golden\\renamed\\".into()),
        }]
    );
    assert!(report.skipped.is_empty());
    assert_eq!(report.patients_without_observations, 2);
    assert_eq!(report.observed_patients_missing_from_set, 0);
}

#[test]
fn migrate_refuses_a_truncated_export() {
    let full = encode(&sample());
    for cut in [0, 20, full.len() / 2, full.len() - 1] {
        let message = match run(&full[..cut], "1\n") {
            Err(e) => e.to_string(),
            Ok(_) => panic!("a {cut}-byte export migrated"),
        };
        assert!(message.contains("incomplete"), "{message}");
    }
    let mut extra = full.clone();
    extra.push(0);
    assert!(run(&extra, "1\n").is_err());
}

fn refused_container(concepts: Vec<u8>, patients: Vec<u8>) -> String {
    let dir = tempfile::tempdir().unwrap();
    let export = write_raw_export(dir.path(), &concepts, &patients);
    match run_export(dir, export, false) {
        Err(e) => e.to_string(),
        Ok(_) => panic!("migration succeeded"),
    }
}

#[test]
fn migrate_refuses_an_altered_or_mismatched_container() {
    let id = [7u8; EXPORT_ID_LEN];
    let content = encode(&sample());
    let concepts = seal(id, ExportKind::Concepts, &content);
    let patients = seal(id, ExportKind::Patients, b"1\n2\n5\n7\n");

    let mut flipped = concepts.clone();
    let last = flipped.len() - 1;
    flipped[last] ^= 1;
    let message = refused_container(flipped, patients.clone());
    assert!(message.contains("does not decrypt"), "{message}");

    let cut = concepts[..concepts.len() - 10].to_vec();
    let message = refused_container(cut, patients.clone());
    assert!(message.contains("incomplete"), "{message}");

    let first_frame_end =
        28 + 4 + i32::from_be_bytes(concepts[28..32].try_into().unwrap()) as usize;
    let mut dropped = concepts[..28].to_vec();
    dropped.extend_from_slice(&concepts[first_frame_end..]);
    let message = refused_container(dropped, patients.clone());
    assert!(
        message.contains("frame 1 found where frame 0 belongs"),
        "{message}"
    );

    let message = refused_container(
        concepts.clone(),
        seal([8u8; EXPORT_ID_LEN], ExportKind::Patients, b"1\n"),
    );
    assert!(message.contains("different export"), "{message}");

    let message = refused_container(concepts.clone(), seal(id, ExportKind::Concepts, b"1\n"));
    assert!(message.contains("other export file"), "{message}");

    let message = refused_container(content, patients);
    assert!(message.contains("HPDSMIG2"), "{message}");
}

#[test]
fn migrate_refuses_the_wrong_key_and_a_plain_store() {
    let dir = tempfile::tempdir().unwrap();
    let export = write_export(dir.path(), &encode(&sample()), "1\n2\n5\n7\n");
    let wrong = dir.path().join("wrong");
    std::fs::write(&wrong, "FEDCBA9876543210FEDCBA9876543210\n").unwrap();
    let out = dir.path().join("store");
    let message = migrate(&options(
        export.clone(),
        out.clone(),
        KeySource::File(wrong),
    ))
    .unwrap_err()
    .to_string();
    assert!(
        message.contains("does not decrypt with the given key"),
        "{message}"
    );
    assert!(!out.exists());

    let message = migrate(&options(export, out.clone(), KeySource::Plain))
        .unwrap_err()
        .to_string();
    assert!(
        message.contains("needs the javabin store's key"),
        "{message}"
    );
    assert!(!out.exists());
}

#[test]
fn migrate_replaces_an_existing_store_and_leaves_the_export_alone() {
    let dir = tempfile::tempdir().unwrap();
    let export = write_export(dir.path(), &encode(&sample()), "1\n2\n5\n7\n");
    let concepts = std::fs::read(export.join(EXPORT_CONCEPTS_FILE)).unwrap();
    let out = dir.path().join("store");
    std::fs::create_dir(&out).unwrap();
    std::fs::write(out.join("encryption_key"), format!("{KEY}\n")).unwrap();
    for _ in 0..2 {
        migrate(&options(
            export.clone(),
            out.clone(),
            KeySource::OutputDirectoryKeyFile,
        ))
        .unwrap();
    }
    assert_eq!(
        std::fs::read(export.join(EXPORT_CONCEPTS_FILE)).unwrap(),
        concepts
    );
    let mut names: Vec<String> = std::fs::read_dir(&out)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .collect();
    names.sort();
    assert_eq!(
        names,
        [
            "columnMeta.csv",
            "encryption_key",
            "etl-report.json",
            "pheno.blocks",
            "pheno.index"
        ]
    );
}

#[test]
fn a_partition_with_observed_patients_missing_from_its_list_is_not_recorded_as_indexed() {
    let dir = tempfile::tempdir().unwrap();
    let export = dir.path().join("export");
    let partitions = [
        ("complete", vec![(1, 1.0, None), (2, 2.0, None)], "1\n2\n"),
        ("short", vec![(1, 3.0, None), (3, 4.0, None)], "3\n"),
    ];
    std::fs::create_dir(&export).unwrap();
    for (name, observations, patients) in partitions {
        let entries = [Entry::numeric("\\a\\", observations)];
        let written = write_export(&dir.path().join(name), &encode(&entries), patients);
        std::fs::rename(written, export.join(name)).unwrap();
    }
    let out = dir.path().join("store");
    let report = migrate_partitioned(&options(
        export,
        out.clone(),
        KeySource::File(key_file(dir.path())),
    ))
    .unwrap();
    let short = &report.partitions["short"];
    assert_eq!(short.observed_patients_missing_from_set, 1);
    assert_eq!(
        report.partitions["complete"].observed_patients_missing_from_set,
        0
    );
    let marker = std::fs::read_to_string(out.join(".generation")).unwrap();
    let lines: Vec<&str> = marker.lines().collect();
    assert_eq!(
        lines[..2],
        [
            format!(
                "{} patients-indexed",
                report.partitions["complete"].store_id
            ),
            short.store_id.clone(),
        ]
    );
}
