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
use hpds_etl::maintenance_log::{MAINTENANCE_LOG_FILE_NAME, records, sha256_hex};
use hpds_etl::publish::{PublishStep, recover};
use hpds_etl::store::OpenStore;
use hpds_etl::store_maintenance::{
    RemoveOptions, RemoveReport, regenerate_column_meta_csv, remove_concepts,
};
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
        ..RemoveOptions::default()
    }
}

/// The names in the store directory `dir`, sorted, with each file's bytes, the maintenance
/// log left out.
fn snapshot(dir: &Path) -> Vec<(String, Vec<u8>)> {
    let mut files: Vec<(String, Vec<u8>)> = std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap())
        .filter(|e| e.file_type().unwrap().is_file())
        .filter(|e| e.file_name() != MAINTENANCE_LOG_FILE_NAME)
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
    let log = records(&store.out).unwrap();
    assert_eq!(log.len(), 1);
    let record = &log[0];
    assert_eq!(record["operation"], "remove-concepts");
    assert_eq!(record["result"], "published");
    assert_eq!(record["store_id"], store.index.store_id().to_string());
    assert_eq!(record["removed_paths"], serde_json::json!(removed));
    assert_eq!(record["compacted"], true);
    assert_eq!(record["removed_blocks_kept"], false);
    assert_eq!(
        record["concepts_file_sha256"],
        sha256_hex(&std::fs::read(&list_path).unwrap())
    );
    assert_eq!(
        record["index_sha256"],
        sha256_hex(&std::fs::read(store.out.join("pheno.index")).unwrap())
    );
    assert!(record["time"].as_str().unwrap().ends_with('Z'), "{record}");
    assert!(
        record["tool"].as_str().unwrap().starts_with("hpds-etl "),
        "{record}"
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
    assert_eq!(report["compacted"], true);
    let after = OpenStore::open(&store.out, protection()).unwrap();
    assert!(after.index.get(&first).is_none());

    let second = after.index.concepts()[0].path.clone();
    std::fs::write(&list_path, format!("{second}\n")).unwrap();
    let blocks_before = std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap();
    std::fs::write(store.out.join("encryption_key"), format!("{KEY}\n")).unwrap();
    let renamed = run_hpds_etl(&[
        "remove-concepts",
        "--store",
        store.out.to_str().unwrap(),
        "--concepts",
        list_path.to_str().unwrap(),
        "--no-compact",
    ]);
    assert!(!renamed.status.success());
    assert!(String::from_utf8_lossy(&renamed.stderr).contains("--keep-removed-blocks"));
    assert_eq!(
        std::fs::read(store.out.join(BLOCKS_FILE_NAME)).unwrap(),
        blocks_before
    );
    let output = hpds_etl(&[
        "remove-concepts",
        "--store",
        store.out.to_str().unwrap(),
        "--concepts",
        list_path.to_str().unwrap(),
        "--keep-removed-blocks",
    ]);
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["compacted"], false);
    assert_eq!(report["removed_blocks_kept"], true);
    assert!(String::from_utf8_lossy(&output.stderr).contains("encrypted but present"));
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
    let log = records(&store.out).unwrap();
    assert_eq!(log.len(), 1, "only the no-change run is recorded");
    assert_eq!(log[0]["result"], "no-change");
    assert_eq!(log[0]["lines_naming_no_concept"], 1);
    assert_eq!(
        log[0]["index_sha256"],
        sha256_hex(&std::fs::read(store.out.join("pheno.index")).unwrap())
    );
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

#[test]
fn a_dry_run_reports_exactly_what_a_run_removes_and_writes_nothing() {
    for keep_removed_blocks in [false, true] {
        let store = edge_store();
        let removed: Vec<String> = store.index.concepts()[1..4]
            .iter()
            .map(|c| c.path.clone())
            .collect();
        let mut patients: Vec<u32> = removed
            .iter()
            .flat_map(|p| store.rows(p).into_iter().map(|row| row.0))
            .collect();
        patients.sort_unstable();
        patients.dedup();
        let list_path = store.dir.path().join("conceptsToRemove.txt");
        std::fs::write(&list_path, removed.join("\n")).unwrap();
        let before = snapshot(&store.out);
        let options = RemoveOptions {
            keep_removed_blocks,
            dry_run: true,
            ..RemoveOptions::default()
        };

        let dry = remove_concepts(&store.out, &list_path, &protection(), &options).unwrap();

        assert_eq!(snapshot(&store.out), before);
        assert!(!store.out.join(MAINTENANCE_LOG_FILE_NAME).exists());
        assert!(dry.dry_run);
        assert_eq!(dry.removed_paths, removed);
        assert_eq!(dry.patients_with_removed_rows, Some(patients.len() as u64));
        assert_eq!(dry.unreadable_removed_blocks, 0);
        let real = remove_concepts(
            &store.out,
            &list_path,
            &protection(),
            &RemoveOptions {
                dry_run: false,
                ..options
            },
        )
        .unwrap();
        assert_eq!(
            RemoveReport {
                dry_run: true,
                ..real
            },
            dry,
            "keep_removed_blocks {keep_removed_blocks}"
        );
        let log = records(&store.out).unwrap();
        assert_eq!(log.len(), 1);
        assert_eq!(log[0]["removed_blocks_kept"], keep_removed_blocks);
        assert_eq!(log[0]["compacted"], !keep_removed_blocks);
        let text = std::fs::read_to_string(store.out.join(MAINTENANCE_LOG_FILE_NAME)).unwrap();
        for patient in &patients {
            assert!(
                !text.contains(&format!("\"{patient}\"")) && !text.contains(&format!("[{patient}")),
                "the log names no patient"
            );
        }
    }
}

#[test]
fn a_dry_run_through_the_binary_lists_missing_lines_and_fails() {
    let store = edge_store();
    let key = key_file(&store);
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\nmissing\n")).unwrap();
    let before = snapshot(&store.out);
    let output = run_hpds_etl(&[
        "remove-concepts",
        "--store",
        store.out.to_str().unwrap(),
        "--concepts",
        list_path.to_str().unwrap(),
        "--key-file",
        &key,
        "--dry-run",
    ]);
    assert!(!output.status.success());
    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["dry_run"], true);
    assert_eq!(report["removed"], 1);
    assert_eq!(report["not_found"], serde_json::json!(["missing"]));
    assert!(String::from_utf8_lossy(&output.stderr).contains("--allow-missing"));
    assert_eq!(snapshot(&store.out), before);
}

/// Names of the temporary files in `dir`.
fn temporary_files(dir: &Path) -> Vec<String> {
    std::fs::read_dir(dir)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains(".tmp-"))
        .collect()
}

/// Checks that the store in `dir` is `store` without `removed`, with every remaining block
/// readable and holding its rows, a matching `columnMeta.csv`, no journal or temporary
/// file, and `records_expected` maintenance log records, the last naming `last_removed`.
fn assert_removed(
    store: &Store,
    removed: &[String],
    last_removed: &[String],
    records_expected: usize,
) {
    let after = OpenStore::open(&store.out, protection()).unwrap();
    for concept in store.index.concepts() {
        match after.index.get(&concept.path) {
            Some(kept) => assert_eq!(
                open_store_rows(&after, &kept.path),
                store.rows(&concept.path),
                "{}",
                concept.path
            ),
            None => assert!(removed.contains(&concept.path), "{}", concept.path),
        }
    }
    for path in removed {
        assert!(after.index.get(path).is_none(), "{path}");
    }
    let mut csv = Vec::new();
    write_column_meta_csv(&mut csv, after.index.concepts()).unwrap();
    assert_eq!(
        std::fs::read(store.out.join("columnMeta.csv")).unwrap(),
        csv
    );
    assert!(!store.out.join(".publish-journal").exists());
    assert_eq!(temporary_files(&store.out), Vec::<String>::new());
    let log = records(&store.out).unwrap();
    assert_eq!(log.len(), records_expected);
    assert_eq!(
        log.last().unwrap()["removed_paths"],
        serde_json::json!(last_removed)
    );
    assert_eq!(
        log.last().unwrap()["index_sha256"],
        sha256_hex(&std::fs::read(store.out.join("pheno.index")).unwrap())
    );
}

#[test]
fn a_removal_stopped_at_any_step_is_left_whole_or_finished_by_recover() {
    let steps = [
        PublishStep::Staged,
        PublishStep::Journaled,
        PublishStep::BlocksMoved,
        PublishStep::IndexMoved,
        PublishStep::ColumnMetaWritten,
        PublishStep::Recorded,
    ];
    for keep_removed_blocks in [false, true] {
        for step in steps {
            if keep_removed_blocks && step == PublishStep::BlocksMoved {
                continue;
            }
            let context = format!("{step:?}, keep_removed_blocks {keep_removed_blocks}");
            let store = edge_store();
            let removed: Vec<String> = store.index.concepts()[..2]
                .iter()
                .map(|c| c.path.clone())
                .collect();
            let list_path = store.dir.path().join("conceptsToRemove.txt");
            std::fs::write(&list_path, removed.join("\n")).unwrap();
            let options = RemoveOptions {
                keep_removed_blocks,
                stop_after: Some(step),
                ..RemoveOptions::default()
            };

            let error = remove_concepts(&store.out, &list_path, &protection(), &options)
                .unwrap_err()
                .to_string();
            assert!(error.contains("publish stopped"), "{context}: {error}");

            let journal = store.out.join(".publish-journal");
            if step == PublishStep::Staged {
                assert!(!journal.exists(), "{context}");
                let opened = OpenStore::open(&store.out, protection()).unwrap();
                assert_eq!(
                    opened.index.concepts().len(),
                    store.index.concepts().len(),
                    "{context}: the old store is whole"
                );
                for concept in opened.index.concepts() {
                    opened.read_block(concept).unwrap();
                }
                assert!(!temporary_files(&store.out).is_empty(), "{context}");
                let report = recover(&store.out, &protection()).unwrap();
                assert_eq!(report.finished, None, "{context}");
                assert!(report.orphans_removed >= 1, "{context}");
                assert_eq!(temporary_files(&store.out), Vec::<String>::new());
                assert!(records(&store.out).unwrap().is_empty(), "{context}");
                continue;
            }
            assert!(journal.exists(), "{context}");
            let refused = OpenStore::open(&store.out, protection())
                .err()
                .unwrap()
                .to_string();
            assert!(refused.contains("hpds-etl recover"), "{context}: {refused}");
            let refused = regenerate_column_meta_csv(&store.out, None, &protection())
                .unwrap_err()
                .to_string();
            assert!(refused.contains("hpds-etl recover"), "{context}: {refused}");

            let report = recover(&store.out, &protection()).unwrap();

            assert_eq!(
                report.finished.as_deref(),
                Some("remove-concepts"),
                "{context}"
            );
            assert_eq!(report.recorded, step != PublishStep::Recorded, "{context}");
            assert_removed(&store, &removed, &removed, 1);
            let result = &records(&store.out).unwrap()[0]["result"];
            let expected = if step == PublishStep::Recorded {
                "published"
            } else {
                "completed-by-recover"
            };
            assert_eq!(result, expected, "{context}");
            assert_eq!(
                recover(&store.out, &protection()).unwrap(),
                Default::default(),
                "{context}: a second recover does nothing"
            );
        }
    }
}

#[test]
fn recover_refuses_a_staged_file_the_journal_does_not_describe() {
    let store = edge_store();
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\n")).unwrap();
    let options = RemoveOptions {
        stop_after: Some(PublishStep::Journaled),
        ..RemoveOptions::default()
    };
    remove_concepts(&store.out, &list_path, &protection(), &options).unwrap_err();
    let staged = temporary_files(&store.out)
        .into_iter()
        .find(|n| n.starts_with(".pheno.blocks.tmp-"))
        .unwrap();
    let staged = store.out.join(staged);
    let mut bytes = std::fs::read(&staged).unwrap();
    bytes.push(0);
    std::fs::write(&staged, bytes).unwrap();
    let before = snapshot(&store.out);

    let error = recover(&store.out, &protection()).unwrap_err().to_string();

    assert!(error.contains("is not the file"), "{error}");
    assert_eq!(snapshot(&store.out), before);
    assert!(store.out.join(".publish-journal").exists());
}

#[test]
fn remove_concepts_finishes_an_interrupted_removal_before_its_own() {
    let store = edge_store();
    let paths: Vec<String> = store
        .index
        .concepts()
        .iter()
        .map(|c| c.path.clone())
        .collect();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{}\n", paths[0])).unwrap();
    let stopped = RemoveOptions {
        stop_after: Some(PublishStep::BlocksMoved),
        ..RemoveOptions::default()
    };
    remove_concepts(&store.out, &list_path, &protection(), &stopped).unwrap_err();
    std::fs::write(&list_path, format!("{}\n", paths[1])).unwrap();

    let report = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions::default(),
    )
    .unwrap();

    assert_eq!(report.removed_paths, [paths[1].clone()]);
    let log = records(&store.out).unwrap();
    assert_eq!(log[0]["result"], "completed-by-recover");
    assert_eq!(log[0]["removed_paths"], serde_json::json!([paths[0]]));
    assert_removed(&store, &paths[..2], &paths[1..2], 2);
}

#[test]
fn remove_concepts_refuses_store_files_that_are_symbolic_links() {
    let store = edge_store();
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\n")).unwrap();
    let elsewhere = store.dir.path().join("columnMeta-elsewhere.csv");
    std::fs::rename(store.out.join("columnMeta.csv"), &elsewhere).unwrap();
    std::os::unix::fs::symlink(&elsewhere, store.out.join("columnMeta.csv")).unwrap();
    let before = snapshot(&store.out);

    let error = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions::default(),
    )
    .unwrap_err()
    .to_string();

    assert!(error.contains("symbolic link"), "{error}");
    assert_eq!(snapshot(&store.out), before);
}

#[test]
fn recover_through_the_binary() {
    let store = edge_store();
    let key = key_file(&store);
    let first = store.index.concepts()[0].path.clone();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, format!("{first}\n")).unwrap();
    let options = RemoveOptions {
        stop_after: Some(PublishStep::IndexMoved),
        ..RemoveOptions::default()
    };
    remove_concepts(&store.out, &list_path, &protection(), &options).unwrap_err();
    let dump = run_hpds_etl(&[
        "dump",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
    ]);
    assert!(!dump.status.success());
    assert!(String::from_utf8_lossy(&dump.stderr).contains("hpds-etl recover"));

    let output = hpds_etl(&[
        "recover",
        "--store",
        store.out.to_str().unwrap(),
        "--key-file",
        &key,
    ]);

    let report: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
    assert_eq!(report["finished"], "remove-concepts");
    let removed = [first];
    assert_removed(&store, &removed, &removed, 1);
}

#[test]
fn a_rerun_without_keep_removed_blocks_compacts_what_an_earlier_run_kept() {
    let store = edge_store();
    let removed: Vec<String> = store.index.concepts()[..2]
        .iter()
        .map(|c| c.path.clone())
        .collect();
    let removed_bytes: u64 = store.index.concepts()[..2]
        .iter()
        .map(|c| c.block.length)
        .sum();
    let list_path = store.dir.path().join("conceptsToRemove.txt");
    std::fs::write(&list_path, removed.join("\n")).unwrap();
    let blocks = store.out.join(BLOCKS_FILE_NAME);
    let before = std::fs::metadata(&blocks).unwrap().len();
    remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions {
            keep_removed_blocks: true,
            ..RemoveOptions::default()
        },
    )
    .unwrap();
    assert_eq!(std::fs::metadata(&blocks).unwrap().len(), before);
    let allow_missing = RemoveOptions {
        allow_missing: true,
        ..RemoveOptions::default()
    };
    let dry = remove_concepts(
        &store.out,
        &list_path,
        &protection(),
        &RemoveOptions {
            dry_run: true,
            ..allow_missing.clone()
        },
    )
    .unwrap();
    assert!(dry.compacted);
    assert_eq!(dry.unreferenced_bytes, removed_bytes);

    let report = remove_concepts(&store.out, &list_path, &protection(), &allow_missing).unwrap();

    assert_eq!(report.removed, 0);
    assert_eq!(report.not_found, removed);
    assert!(report.compacted);
    assert_eq!(report.unreferenced_bytes, removed_bytes);
    assert_eq!(report.blocks_bytes_after, before - removed_bytes);
    assert_eq!(
        std::fs::metadata(&blocks).unwrap().len(),
        before - removed_bytes
    );
    let log = records(&store.out).unwrap();
    assert_eq!(log.len(), 2);
    assert_eq!(log[1]["result"], "published");
    assert_eq!(log[1]["compacted"], true);
    assert_eq!(log[1]["unreferenced_bytes"], removed_bytes);
    assert_removed(&store, &removed, &[], 2);

    let again = remove_concepts(&store.out, &list_path, &protection(), &allow_missing).unwrap();
    assert!(!again.compacted);
    assert_eq!(records(&store.out).unwrap()[2]["result"], "no-change");
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
