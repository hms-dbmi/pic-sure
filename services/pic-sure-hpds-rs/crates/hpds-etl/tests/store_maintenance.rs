//! `remove-concepts`, `column-meta-csv` and `dump` on stores built by `load`, through the
//! library and through the `hpds-etl` binary.

mod common;

use std::path::Path;
use std::process::Command;

use common::{KEY, Store, fixture, load_files};
use hpds_etl::column_meta_csv::write_column_meta_csv;
use hpds_etl::dump::{dump, dump_patients};
use hpds_etl::java::java_double_to_string;
use hpds_etl::load::Mode;
use hpds_etl::store::OpenStore;
use hpds_etl::store_maintenance::{RemoveOptions, regenerate_column_meta_csv, remove_concepts};
use hpds_format::{BLOCKS_FILE_NAME, Block, CryptoKey, NO_TIMESTAMP, Protection};

fn protection() -> Protection {
    Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap())
}

fn edge_store() -> Store {
    let content = std::fs::read(fixture("edge.csv")).unwrap();
    load_files(Mode::NewSearch, &[("allConcepts.csv", &content)], |_| {}).unwrap()
}

fn hpds_etl(args: &[&str]) -> std::process::Output {
    let output = run_hpds_etl(args);
    assert!(
        output.status.success(),
        "hpds-etl {args:?}: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    output
}

fn run_hpds_etl(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(args)
        .output()
        .unwrap()
}

/// The rows of `path` read from an opened store, in the shape of [`Store::rows`].
fn open_store_rows(store: &OpenStore, path: &str) -> Vec<common::Row> {
    let ts = |t: i64| (t != NO_TIMESTAMP).then_some(t);
    match store.read_block(store.index.get(path).unwrap()).unwrap() {
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

fn keep_blocks_allow_missing() -> RemoveOptions {
    RemoveOptions {
        allow_missing: true,
        keep_removed_blocks: true,
    }
}

/// The names in the store directory `dir`, sorted, with each file's bytes.
fn snapshot(dir: &Path) -> Vec<(String, Vec<u8>)> {
    let mut files: Vec<(String, Vec<u8>)> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap())
        .filter(|e| e.file_type().unwrap().is_file())
        .map(|e| {
            (
                e.file_name().to_string_lossy().into_owned(),
                std::fs::read(e.path()).unwrap(),
            )
        })
        .collect();
    files.sort();
    files
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
fn remove_concepts_keeping_blocks_drops_exact_lines_from_the_index_only() {
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

    let report = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &keep_blocks_allow_missing(),
    )
    .unwrap();

    assert!(!report.compacted);
    assert!(report.removed_blocks_kept);
    assert_eq!(report.blocks_bytes_before, report.blocks_bytes_after);
    assert_eq!(report.lines, 7);
    assert_eq!(report.blank_lines, 1);
    assert_eq!(report.removed, 3);
    assert_eq!(report.removed_paths, before[..3]);
    assert_eq!(report.remaining as usize, before.len() - 3);
    assert_eq!(
        report.not_found,
        [prefix.as_str(), padded.as_str(), "not a concept"]
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
        after.read_block(concept).unwrap();
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
fn remove_concepts_compacts_the_blocks_by_default() {
    let store = edge_store();
    let before = OpenStore::open(&store.out, protection()).unwrap();
    let removed: Vec<String> = before.index.concepts()[..3]
        .iter()
        .map(|c| c.path.clone())
        .collect();
    let kept_rows: Vec<_> = before.index.concepts()[3..]
        .iter()
        .map(|c| (c.path.clone(), store.rows(&c.path)))
        .collect();
    let removed_bytes: u64 = before.index.concepts()[..3]
        .iter()
        .map(|c| c.block.length)
        .sum();
    drop(before);
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, removed.join("\n")).unwrap();
    let blocks_before = std::fs::metadata(store.out.join(BLOCKS_FILE_NAME))
        .unwrap()
        .len();

    let report = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions::default(),
    )
    .unwrap();

    assert!(report.compacted);
    assert!(!report.removed_blocks_kept);
    assert_eq!(report.removed, 3);
    assert_eq!(
        report.observations_removed,
        removed
            .iter()
            .map(|p| store.rows(p).len() as u64)
            .sum::<u64>()
    );
    assert_eq!(report.blocks_bytes_before, blocks_before);
    assert_eq!(report.blocks_bytes_after, blocks_before - removed_bytes);
    assert_eq!(
        std::fs::metadata(store.out.join(BLOCKS_FILE_NAME))
            .unwrap()
            .len(),
        report.blocks_bytes_after
    );
    let after = OpenStore::open(&store.out, protection()).unwrap();
    assert_eq!(after.index.store_id(), store.index.store_id());
    assert_eq!(after.index.patients(), store.index.patients());
    for (path, rows) in &kept_rows {
        assert_eq!(&open_store_rows(&after, path), rows, "{path}");
    }
    for path in &removed {
        assert!(after.index.get(path).is_none());
    }
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
    assert_eq!(report["compacted"], true);
    let after = OpenStore::open(&store.out, protection()).unwrap();
    assert!(after.index.get(&first).is_none());

    let second = after.index.concepts()[0].path.clone();
    std::fs::write(&list_path, format!("{second}\n")).unwrap();
    let blocks_before = std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap();
    std::fs::write(store.out.join("encryption_key"), format!("{KEY}\n")).unwrap();
    let output = hpds_etl(&[
        "remove-concepts",
        "--store",
        store.out.to_str().unwrap(),
        "--concepts",
        list_path.to_str().unwrap(),
        "--no-compact",
    ]);
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["compacted"], false);
    assert_eq!(
        std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap(),
        blocks_before
    );
}

#[test]
fn a_line_naming_no_concept_fails_before_anything_is_written() {
    let store = edge_store();
    let paths: Vec<String> = store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.clone())
        .collect();
    let trailing_space = format!("{} ", paths[1]);
    let unframed = paths[2].trim_matches('\\').to_string();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(
        &list_path,
        format!(
            "{}\n{trailing_space}\n\n{unframed}\nno such concept\n",
            paths[0]
        ),
    )
    .unwrap();
    let before = snapshot(&store.out);

    let error = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions::default(),
    )
    .unwrap_err()
    .to_string();

    assert!(error.contains("3 of the lines"), "{error}");
    assert!(error.contains("nothing was removed"), "{error}");
    assert!(
        error.contains(&format!(
            "line 2: {trailing_space:?} (a concept matches it without the surrounding whitespace)"
        )),
        "{error}"
    );
    assert!(
        error.contains(&format!(
            "line 4: {unframed:?} (a concept matches it with a backslash at each end)"
        )),
        "{error}"
    );
    assert!(error.contains("line 5: \"no such concept\". "), "{error}");
    assert!(error.contains("--allow-missing"), "{error}");
    assert_eq!(snapshot(&store.out), before);

    let report = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions {
            allow_missing: true,
            ..RemoveOptions::default()
        },
    )
    .unwrap();
    assert_eq!(report.removed_paths, [paths[0].clone()]);
    assert_eq!(report.not_found.len(), 3);
}

#[test]
fn an_empty_concepts_file_fails_and_a_rerun_with_nothing_left_changes_nothing() {
    let store = edge_store();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    let before = snapshot(&store.out);
    for empty in ["", "\n", "\r\n\n"] {
        std::fs::write(&list_path, empty).unwrap();
        let error = remove_concepts(
            &store.out,
            &list_path,
            &protection(),
            &RemoveOptions {
                allow_missing: true,
                ..RemoveOptions::default()
            },
        )
        .unwrap_err()
        .to_string();
        assert!(error.contains("names no concept"), "{empty:?}: {error}");
    }
    let error = remove_concepts(
        &store.out,
        store.dir.path(),
        &protection(),
        &RemoveOptions::default(),
    )
    .unwrap_err()
    .to_string();
    assert!(error.contains("is not a regular file"), "{error}");

    std::fs::write(&list_path, "\\gone\\\n").unwrap();
    let report = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions {
            allow_missing: true,
            ..RemoveOptions::default()
        },
    )
    .unwrap();
    assert_eq!(report.removed, 0);
    assert!(!report.compacted);
    assert_eq!(report.not_found, ["\\gone\\"]);
    assert_eq!(snapshot(&store.out), before);
}

#[test]
fn remove_concepts_checks_the_key_as_load_does() {
    let store = edge_store();
    std::fs::write(store.out.join("encryption_key"), format!("{KEY}\n")).unwrap();
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\n")).unwrap();
    let other_key = store.dir.path().join("other_key");
    std::fs::write(&other_key, format!("{KEY}\n\n")).unwrap();
    let before = snapshot(&store.out);
    for extra in [
        vec!["--plain"],
        vec!["--key-file", other_key.to_str().unwrap()],
    ] {
        let mut args = vec![
            "remove-concepts",
            "--store",
            store.out.to_str().unwrap(),
            "--concepts",
            list_path.to_str().unwrap(),
        ];
        args.extend(extra.iter().copied());
        let output = run_hpds_etl(&args);
        assert!(!output.status.success(), "{extra:?}");
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert!(
            stderr.contains("plain stores are for benchmarking")
                || stderr.contains("the store keeps the key it has"),
            "{stderr}"
        );
    }
    assert_eq!(snapshot(&store.out), before);
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
        dump_patients(opened.index.patients(), &mut patients).unwrap(),
        store.patients().len() as u64
    );
}

#[test]
fn a_single_store_dump_is_private_new_and_outside_the_store() {
    use std::os::unix::fs::PermissionsExt;
    let store = edge_store();
    let key = key_file(&store);
    let dump = |out: &Path, extra: &[&str]| {
        let mut args = vec![
            "dump",
            "--store",
            store.out.to_str().unwrap(),
            "--key-file",
            &key,
            "--out",
            out.to_str().unwrap(),
        ];
        args.extend_from_slice(extra);
        run_hpds_etl(&args)
    };
    let out = store.dir.path().join("dump.csv");
    assert!(dump(&out, &[]).status.success());
    let mode = std::fs::metadata(&out).unwrap().permissions().mode() & 0o777;
    assert_eq!(mode, 0o600);
    std::fs::write(&out, "old").unwrap();
    let refused = dump(&out, &["--patients"]);
    assert!(!refused.status.success());
    assert!(String::from_utf8_lossy(&refused.stderr).contains("pass --force"));
    assert_eq!(std::fs::read_to_string(&out).unwrap(), "old");
    assert!(dump(&out, &["--patients", "--force"]).status.success());
    assert_ne!(std::fs::read_to_string(&out).unwrap(), "old");
    for inside in [store.out.join("dump.csv"), store.out.join("pheno.index")] {
        let before = std::fs::read(&inside).ok();
        let refused = dump(&inside, &["--force"]);
        assert!(!refused.status.success(), "{}", inside.display());
        assert!(
            String::from_utf8_lossy(&refused.stderr).contains("lies inside"),
            "{}",
            String::from_utf8_lossy(&refused.stderr)
        );
        assert_eq!(std::fs::read(&inside).ok(), before);
    }
    let concat = run_hpds_etl(&[
        "dump",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
        "--concat",
    ]);
    assert!(!concat.status.success());
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
