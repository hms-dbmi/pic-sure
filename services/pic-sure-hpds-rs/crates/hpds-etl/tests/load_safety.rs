//! What `hpds-etl load` refuses, the modes of what it writes, which CSV config it applies,
//! and what an interrupted run leaves behind.

mod common;

use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use common::{HEADER, MODES, load_files};
use hpds_etl::EtlError;
use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_format::{PhenoIndex, Protection};

fn mode_bits(path: &Path) -> u32 {
    std::fs::metadata(path).unwrap().permissions().mode() & 0o777
}

fn usage_message(result: Result<common::Store, EtlError>) -> String {
    match result {
        Err(EtlError::Usage(message)) => message,
        Err(other) => panic!("expected a usage error, got {other}"),
        Ok(_) => panic!("expected a usage error, the load succeeded"),
    }
}

#[test]
fn stores_are_written_owner_only() {
    let input = format!("{HEADER}1,\\a\\,1,,\n");
    for mode in MODES {
        let store = load_files(mode, &[("allConcepts.csv", input.as_bytes())], |_| {}).unwrap();
        assert_eq!(mode_bits(&store.out), 0o700, "{mode:?}");
        for name in [
            "pheno.index",
            "pheno.blocks",
            "columnMeta.csv",
            "etl-report.json",
        ] {
            assert_eq!(mode_bits(&store.out.join(name)), 0o600, "{mode:?} {name}");
        }
        assert!(
            !store.out.join(".lock").exists(),
            "{mode:?}: the staging lock file is not published"
        );
    }
}

#[test]
fn reloading_replaces_the_store_and_leaves_no_staging() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    let out = dir.path().join("store");
    let mut ids = Vec::new();
    for rows in ["1,\\a\\,1,,\n", "1,\\a\\,1,,\n2,\\b\\,,x,\n"] {
        std::fs::write(&input, format!("{HEADER}{rows}")).unwrap();
        let mut options = LoadOptions::new(Mode::NewSearch, vec![input.clone()], out.clone());
        options.key = KeySource::Plain;
        ids.push(load(&options).unwrap().store_id);
    }
    assert_ne!(ids[0], ids[1]);
    let index = PhenoIndex::read_file(out.join("pheno.index"), &Protection::Plain).unwrap();
    assert_eq!(index.store_id().to_string(), ids[1]);
    assert_eq!(index.concepts().len(), 2);
    for name in ["pheno.index", "pheno.blocks", "columnMeta.csv"] {
        assert_eq!(mode_bits(&out.join(name)), 0o600, "{name}");
    }
    let leftovers: Vec<_> = std::fs::read_dir(dir.path())
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains("staging") || n.contains("spill"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
}

#[test]
fn java_store_directories_are_refused() {
    let input = format!("{HEADER}1,\\a\\,1,,\n");
    for name in ["columnMeta.javabin", "allObservationsStore.javabin"] {
        let out = tempfile::tempdir().unwrap();
        std::fs::write(out.path().join(name), b"java").unwrap();
        let message = usage_message(load_files(
            Mode::Sequential,
            &[("a.csv", input.as_bytes())],
            |o| o.out = out.path().to_path_buf(),
        ));
        assert!(message.contains(name), "{message}");
        assert!(!out.path().join("pheno.index").exists());
    }
}

#[test]
fn plain_stores_are_refused_beside_an_encryption_key() {
    let input = format!("{HEADER}1,\\a\\,1,,\n");
    let out = tempfile::tempdir().unwrap();
    std::fs::write(out.path().join("encryption_key"), common::KEY).unwrap();
    let message = usage_message(load_files(
        Mode::NewSearch,
        &[("allConcepts.csv", input.as_bytes())],
        |o| {
            o.out = out.path().to_path_buf();
            o.key = KeySource::Plain;
        },
    ));
    assert!(
        message
            .contains("plain stores are for benchmarking; use an output directory without a key"),
        "{message}"
    );
}

#[test]
fn max_chunk_size_is_refused_in_new_search_mode() {
    let input = format!("{HEADER}1,\\a\\,1,,\n");
    let message = usage_message(load_files(
        Mode::NewSearch,
        &[("allConcepts.csv", input.as_bytes())],
        |o| o.max_chunk_gib = Some(1.0),
    ));
    assert!(message.contains("--max-chunk-gib"), "{message}");
}

#[test]
fn config_json_beside_an_input_file_applies_in_both_modes() {
    for mode in MODES {
        let dir = tempfile::tempdir().unwrap();
        let input = dir.path().join("allConcepts.csv");
        std::fs::write(&input, format!("{HEADER}1,\\a\\,1,,\n")).unwrap();
        std::fs::write(
            dir.path().join("config.json"),
            r#"{"allConcepts.csv": {"dataset_name": "ds", "dataset_name_as_root_node": true}}"#,
        )
        .unwrap();
        let store = load_files(mode, &[], |o| {
            o.inputs = vec![input.clone()];
        })
        .unwrap();
        assert_eq!(store.paths(), ["\\ds\\a\\"], "{mode:?}");

        let named = dir.path().join("other.json");
        std::fs::write(&named, "{}").unwrap();
        let store = load_files(mode, &[], |o| {
            o.inputs = vec![input.clone()];
            o.config = Some(named.clone());
        })
        .unwrap();
        assert_eq!(store.paths(), ["\\a\\"], "{mode:?}: --config wins");
    }
}

#[test]
fn load_requires_a_mode() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("a.csv");
    std::fs::write(&input, format!("{HEADER}1,\\a\\,1,,\n")).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(["load", "--plain", "--input"])
        .arg(&input)
        .arg("--out")
        .arg(dir.path().join("store"))
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(
        String::from_utf8_lossy(&output.stderr).contains("--mode"),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}

#[test]
fn spill_partitions_keep_their_deprecated_flag_name() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    std::fs::write(&input, format!("{HEADER}1,\\a\\,1,,\n2,\\b\\,2,,\n")).unwrap();
    for (flag, warns) in [("--spill-partitions", false), ("--partitions", true)] {
        let out = dir.path().join(flag.trim_start_matches('-'));
        let output = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
            .args([
                "load",
                "--mode",
                "new-search",
                "--plain",
                flag,
                "2",
                "--input",
            ])
            .arg(&input)
            .arg("--out")
            .arg(&out)
            .env("RUST_LOG", "warn")
            .output()
            .unwrap();
        assert!(output.status.success(), "{flag}: {output:?}");
        let stderr = String::from_utf8_lossy(&output.stderr);
        assert_eq!(
            stderr.contains("--partitions is deprecated"),
            warns,
            "{stderr}"
        );
        assert!(out.join("pheno.index").exists());
    }
}

#[test]
fn sigterm_removes_the_staging_and_spill_directories() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("big.csv");
    let mut file = std::io::BufWriter::new(std::fs::File::create(&input).unwrap());
    file.write_all(HEADER.as_bytes()).unwrap();
    for i in 0..2_000_000u32 {
        writeln!(file, "{},\\c\\v{}\\,{i},,{i}", i % 5000, i % 3000).unwrap();
    }
    file.into_inner().unwrap().sync_all().unwrap();
    let tmp = dir.path().join("tmp");
    let out = dir.path().join("store");
    let mut child = Command::new(env!("CARGO_BIN_EXE_hpds-etl"))
        .args(["load", "--mode", "sequential", "--plain", "--input"])
        .arg(&input)
        .arg("--out")
        .arg(&out)
        .arg("--tmp")
        .arg(&tmp)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .unwrap();
    let names = |path: &Path| -> Vec<String> {
        std::fs::read_dir(path)
            .map(|entries| {
                entries
                    .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default()
    };
    let deadline = Instant::now() + Duration::from_secs(30);
    while !names(&tmp)
        .iter()
        .any(|n| n.starts_with(".hpds-etl-spill-"))
    {
        assert!(
            Instant::now() < deadline,
            "the spill directory never appeared"
        );
        std::thread::sleep(Duration::from_millis(2));
    }
    assert!(
        names(dir.path())
            .iter()
            .any(|n| n.starts_with(".store.staging-"))
    );
    let killed = Command::new("kill")
        .args(["-TERM", &child.id().to_string()])
        .status()
        .unwrap();
    assert!(killed.success());
    let status = child.wait().unwrap();
    assert_eq!(
        status.code(),
        Some(143),
        "the load finished before the signal"
    );
    assert!(names(&tmp).is_empty(), "{:?}", names(&tmp));
    assert!(
        !names(dir.path()).iter().any(|n| n.contains("staging")),
        "{:?}",
        names(dir.path())
    );
    assert!(!out.exists());
}
