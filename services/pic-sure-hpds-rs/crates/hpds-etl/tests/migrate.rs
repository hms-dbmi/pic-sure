//! Migrates hand-built `ExportForMigration` exports and reads the stores back.

mod common;

use std::path::{Path, PathBuf};

use common::{KEY, Row, row};
use hpds_etl::EtlError;
use hpds_etl::dump::dump;
use hpds_etl::java::java_double_to_string;
use hpds_etl::load::KeySource;
use hpds_etl::migrate::{
    EXPORT_CONCEPTS_FILE, EXPORT_MAGIC, EXPORT_PATIENTS_FILE, KeyCollision, MigrateOptions,
    MigrateReport, migrate,
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

/// Encodes `entries` in the `concepts.bin` layout, with the end record's counts.
fn encode(entries: &[Entry]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(EXPORT_MAGIC);
    out.extend_from_slice(&1i32.to_be_bytes());
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

fn write_export(dir: &Path, concepts: &[u8], patients: &str) -> PathBuf {
    let export = dir.join("export");
    std::fs::create_dir_all(&export).unwrap();
    std::fs::write(export.join(EXPORT_CONCEPTS_FILE), concepts).unwrap();
    std::fs::write(export.join(EXPORT_PATIENTS_FILE), patients).unwrap();
    export
}

fn run(concepts: &[u8], patients: &str) -> Result<Migrated, EtlError> {
    let dir = tempfile::tempdir().unwrap();
    let export = write_export(dir.path(), concepts, patients);
    let out = dir.path().join("store");
    let key = key_file(dir.path());
    let report = migrate(&MigrateOptions {
        export,
        out: out.clone(),
        key: KeySource::File(key),
        threads: Some(2),
    })?;
    let protection = Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap());
    let store = OpenStore::open(&out, protection).unwrap();
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
fn migrate_refuses_a_truncated_export() {
    let full = encode(&sample());
    for cut in [4, 20, full.len() / 2, full.len() - 1] {
        let message = match run(&full[..cut], "1\n") {
            Err(e) => e.to_string(),
            Ok(_) => panic!("a {cut}-byte export migrated"),
        };
        assert!(
            message.contains("incomplete") || message.contains("HPDSMIG1"),
            "{message}"
        );
    }
    let mut extra = full.clone();
    extra.push(0);
    assert!(run(&extra, "1\n").is_err());
}

#[test]
fn migrate_replaces_an_existing_store_and_leaves_the_export_alone() {
    let dir = tempfile::tempdir().unwrap();
    let concepts = encode(&sample());
    let export = write_export(dir.path(), &concepts, "1\n2\n5\n7\n");
    let out = dir.path().join("store");
    std::fs::create_dir(&out).unwrap();
    std::fs::write(out.join("encryption_key"), format!("{KEY}\n")).unwrap();
    for _ in 0..2 {
        migrate(&MigrateOptions {
            export: export.clone(),
            out: out.clone(),
            key: KeySource::OutputDirectoryKeyFile,
            threads: None,
        })
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
