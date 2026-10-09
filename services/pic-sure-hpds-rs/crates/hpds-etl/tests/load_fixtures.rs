//! Loads the edge-case file and the 1000 Genomes test fixture and reads the stores back.

mod common;

use std::collections::{BTreeMap, BTreeSet};
use std::path::PathBuf;

use common::{MODES, Store, load_files};
use hpds_etl::column_meta_csv::write_commons_csv_record;
use hpds_etl::java::{normalize_concept_path, roll_up, rollup_text};
use hpds_etl::load::Mode;
use hpds_format::Block;

fn thousand_genomes() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../../pic-sure-hpds/service/src/test/resources/phenotypic/allConcepts.csv")
}

fn load_path(mode: Mode, path: &std::path::Path) -> Store {
    let content = std::fs::read(path).unwrap();
    load_files(mode, &[("allConcepts.csv", &content)], |o| {
        o.partitions = Some(7)
    })
    .unwrap()
}

/// Checks that every block decodes and agrees with its index entry, and that the patient
/// set is the union of the blocks' patients.
fn assert_consistent(store: &Store) {
    let mut patients = roaring::RoaringBitmap::new();
    let mut observations = 0u64;
    for meta in store.index.concepts() {
        let block = store.reader.read_block(&store.codec, meta).unwrap();
        assert_eq!(block.len() as u32, meta.observation_count, "{}", meta.path);
        assert_eq!(
            block.all_patients().len() as u32,
            meta.patient_count,
            "{}",
            meta.path
        );
        assert_eq!(
            matches!(block, Block::Categorical(_)),
            meta.is_categorical(),
            "{}",
            meta.path
        );
        patients |= block.all_patients();
        observations += block.len() as u64;
    }
    assert_eq!(&patients, store.index.patients());
    assert_eq!(observations, store.report.counts.accepted);
    assert_eq!(
        store.index.concepts().len() as u64,
        store.report.counts.concepts
    );
    let lines = store.column_meta.split_terminator("\r\n").count();
    assert_eq!(lines, store.index.concepts().len());
}

/// The Java LowRAM `columnMeta.csv` rows the Rust loader deliberately does not reproduce,
/// with the Rust values of fields 5 to 10 (min, max, offset and length skipped, counts).
fn edge_divergences() -> BTreeMap<&'static str, [&'static str; 4]> {
    BTreeMap::from([
        ("\\edge\\ffd\\var_a\\", ["1.0", "2.0", "2", "2"]),
        ("\\edge\\mu\\var_a\\", ["1.0", "2.0", "2", "2"]),
        ("\\edge\\spaces\\var_a\\", ["10.0", "12.0", "3", "3"]),
        ("\\edge\\num\\zero\\", ["-0.0", "0.0", "4", "4"]),
        ("\\edge\\rejected\\all_bad\\", ["null", "null", "0", "0"]),
    ])
}

fn parse_csv(text: &str) -> Vec<csv::StringRecord> {
    csv::ReaderBuilder::new()
        .has_headers(false)
        .from_reader(text.as_bytes())
        .records()
        .map(Result::unwrap)
        .collect()
}

#[test]
fn edge_sequential_column_meta_matches_java_lowram_except_listed_divergences() {
    let store = load_path(Mode::Sequential, &common::fixture("edge.csv"));
    assert_consistent(&store);
    let java_text = std::fs::read_to_string(common::fixture("edge-lowram-columnMeta.csv")).unwrap();
    let java_lines: Vec<&str> = java_text.split_terminator("\r\n").collect();
    let java = parse_csv(&java_text);
    let rust = parse_csv(&store.column_meta);
    assert_eq!(java.len(), rust.len());
    let divergences = edge_divergences();
    let mut diverged = BTreeSet::new();
    for ((java_record, rust_record), java_line) in java.iter().zip(&rust).zip(&java_lines) {
        assert_eq!(&java_record[0], &rust_record[0], "concept order");
        let mut fields: Vec<String> = rust_record.iter().map(str::to_string).collect();
        fields[7] = java_record[7].to_string();
        fields[8] = java_record[8].to_string();
        if let Some(expected) = divergences.get(&java_record[0]) {
            let rust_values = [&fields[5], &fields[6], &fields[9], &fields[10]];
            assert_eq!(
                rust_values,
                expected.map(String::from).each_ref(),
                "{}",
                &java_record[0]
            );
            diverged.insert(java_record[0].to_string());
            continue;
        }
        let mut line = Vec::new();
        write_commons_csv_record(&mut line, &fields).unwrap();
        assert_eq!(String::from_utf8(line).unwrap(), format!("{java_line}\r\n"));
    }
    assert_eq!(diverged.len(), divergences.len());
}

#[test]
fn edge_file_loads_in_both_modes() {
    for mode in MODES {
        let store = load_path(mode, &common::fixture("edge.csv"));
        assert_consistent(&store);
        let counts = &store.report.counts;
        assert_eq!(
            (counts.rows_read, counts.dropped_short_row),
            (167, 2),
            "{mode:?}"
        );
        assert_eq!(store.values("\\edge\\num\\special\\").len(), 10, "{mode:?}");
        assert_eq!(
            store.values("\\edge\\num\\hex\\"),
            ["3.0", "0.25", "-8.0"],
            "{mode:?}"
        );
        assert_eq!(
            store.values("\\edge\\mixed\\num_first\\"),
            ["5.0", "6.0"],
            "{mode:?}"
        );
        assert_eq!(
            store.values("\\edge\\mixed\\text_first\\"),
            ["kept", "kept_too"],
            "{mode:?}"
        );
        assert_eq!(
            store.values("\\edge\\quote\\cat\\"),
            ["a,b", "say \"hi\""],
            "{mode:?}"
        );
        assert_eq!(
            store.rows("\\edge\\dup\\timed\\"),
            [
                common::row(1442, "1.0", Some(1000)),
                common::row(1442, "1.0", Some(1000)),
                common::row(1442, "1.0", Some(2000)),
                common::row(1442, "2.0", Some(1500)),
            ]
        );
    }
    let new_search = load_path(Mode::NewSearch, &common::fixture("edge.csv"));
    assert!(
        new_search
            .index
            .get("\\edge\\rollup\\question\\Yes\\")
            .is_some()
    );
    assert!(
        !new_search
            .meta("\\edge\\blank\\ws_numeric\\")
            .is_categorical()
    );
    let sequential = load_path(Mode::Sequential, &common::fixture("edge.csv"));
    assert!(
        sequential
            .index
            .get("\\edge\\rollup\\question\\Yes\\")
            .is_none()
    );
    assert!(
        sequential
            .meta("\\edge\\blank\\ws_numeric\\")
            .is_categorical()
    );
}

/// Counts observations per concept with a plain sequential reading of the file, for
/// comparison with the partitioned pipeline.
fn tally(path: &std::path::Path, rollup: bool) -> BTreeMap<String, u32> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(true)
        .flexible(true)
        .from_path(path)
        .unwrap();
    let mut counts = BTreeMap::new();
    for record in reader.records() {
        let record = record.unwrap();
        let mut concept = normalize_concept_path(&record[1]);
        if rollup && let Some(rolled) = roll_up(&concept, &rollup_text(&record[3])) {
            concept = rolled;
        }
        let value = if record[2].trim().is_empty() {
            &record[3]
        } else {
            &record[2]
        };
        if !value.trim().is_empty() {
            *counts.entry(concept).or_insert(0) += 1;
        }
    }
    counts
}

#[test]
fn thousand_genomes_fixture_loads_in_both_modes() {
    let path = thousand_genomes();
    for mode in MODES {
        let store = load_path(mode, &path);
        assert_consistent(&store);
        let counts = &store.report.counts;
        assert_eq!(counts.rows_read, 121_632, "{mode:?}");
        assert_eq!(counts.accepted, 121_632, "{mode:?}");
        assert_eq!((counts.concepts, counts.patients), (29, 4978), "{mode:?}");
        let expected = tally(&path, mode == Mode::Sequential);
        let actual: BTreeMap<String, u32> = store
            .index
            .concepts()
            .iter()
            .map(|c| (c.path.clone(), c.observation_count))
            .collect();
        assert_eq!(actual, expected, "{mode:?}");
    }
}
