//! One test per loader rule, each run in both modes with the expected result for each.

mod common;

use common::{HEADER, MODES, load_files, load_str, row};
use hpds_etl::EtlError;
use hpds_etl::load::{KeySource, Mode};
use hpds_format::ConceptType;

fn csv(rows: &str) -> String {
    format!("{HEADER}{rows}")
}

#[test]
fn concept_paths_are_normalized_and_merged() {
    let input = csv("1,µaµbµvar,1,,\n\
         2,\\a\\b\\var\\,2,,\n\
         3, \\ a \\b\\ var ,3,,\n\
         4,\\a\\b\\va\u{FFFD}r\\,4,,\n\
         5,\\a\\c,,x,\n");
    for mode in MODES {
        let store = load_str(mode, &input);
        assert_eq!(store.paths(), ["\\a\\b\\var\\", "\\a\\c\\"], "{mode:?}");
        assert_eq!(store.meta("\\a\\b\\var\\").observation_count, 4, "{mode:?}");
    }
}

#[test]
fn rollup_is_never_on_in_new_search_and_on_by_default_in_sequential() {
    let input = csv(
        "1,\\q\\Yes\\,,Yes,\n2,\\q\\No\\,,  No  ,\n3,\\q\\Maybe\\,,May\u{FFFD}be,\n4,\\q\\Other\\,,x,\n",
    );
    let new_search = load_str(Mode::NewSearch, &input);
    assert_eq!(
        new_search.paths(),
        ["\\q\\Maybe\\", "\\q\\No\\", "\\q\\Other\\", "\\q\\Yes\\"]
    );

    let sequential = load_str(Mode::Sequential, &input);
    assert_eq!(sequential.paths(), ["\\q\\", "\\q\\Other\\"]);
    assert_eq!(
        sequential.meta("\\q\\").category_values.as_deref().unwrap(),
        ["May\u{FFFD}be", "No", "Yes"]
    );

    let files: &[(&str, &[u8])] = &[("allConcepts.csv", input.as_bytes())];
    let off = load_files(Mode::Sequential, files, |o| o.rollup = Some(false)).unwrap();
    assert_eq!(off.paths().len(), 4);
    let refused = load_files(Mode::NewSearch, files, |o| o.rollup = Some(true));
    assert!(matches!(refused, Err(EtlError::Usage(_))));
}

#[test]
fn dataset_root_config_is_looked_up_per_mode() {
    let input = csv("1,\\a\\,1,,\n");
    for mode in MODES {
        for (file, key, expect_prefix) in [
            ("allConcepts.csv", "allConcepts.csv", true),
            ("other.csv", "allConcepts", mode == Mode::NewSearch),
            ("other.csv", "other.csv", mode == Mode::Sequential),
        ] {
            let dir = tempfile::tempdir().unwrap();
            let config_path = dir.path().join("config.json");
            let json = format!(
                r#"{{"{key}": {{"dataset_name": "phs1", "dataset_name_as_root_node": true}}}}"#
            );
            std::fs::write(&config_path, json).unwrap();
            let store = load_files(mode, &[(file, input.as_bytes())], |o| {
                o.config = Some(config_path.clone());
            })
            .unwrap();
            let expected = if expect_prefix {
                "\\phs1\\a\\"
            } else {
                "\\a\\"
            };
            assert_eq!(store.paths(), [expected], "{mode:?} {file} {key}");
        }
    }
}

#[test]
fn sequential_directory_input_reads_its_config_json() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("hpds_input");
    std::fs::create_dir(&input).unwrap();
    std::fs::write(input.join("data.csv"), csv("1,\\a\\,1,,\n")).unwrap();
    std::fs::write(
        input.join("config.json"),
        r#"{"data.csv": {"dataset_name": "ds", "dataset_name_as_root_node": true}}"#,
    )
    .unwrap();
    std::fs::write(input.join("notes.txt"), "not csv").unwrap();
    let store = load_files(Mode::Sequential, &[], |o| {
        o.inputs = vec![input.clone()];
        o.key = KeySource::Plain;
    })
    .unwrap();
    assert_eq!(store.paths(), ["\\ds\\a\\"]);
}

#[test]
fn whitespace_numeric_field_is_numeric_in_new_search_and_text_in_sequential() {
    let input = csv("1,\\c\\,   ,text_wins,\n2,\\c\\,,other,\n");
    let new_search = load_str(Mode::NewSearch, &input);
    let meta = new_search.meta("\\c\\");
    assert_eq!(
        (meta.concept_type, meta.observation_count),
        (ConceptType::Numeric, 0)
    );
    assert_eq!(new_search.report.counts.dropped_blank_value, 1);
    assert_eq!(new_search.report.counts.dropped_type_conflict, 1);

    let sequential = load_str(Mode::Sequential, &input);
    assert_eq!(sequential.values("\\c\\"), ["text_wins", "other"]);
}

#[test]
fn first_row_fixes_the_type_in_each_loaders_order() {
    let input = csv("1,\\t\\v\\,,text,\n2,\\t\\v,5,,\n3,\\t\\v\\,6,,\n");
    let new_search = load_str(Mode::NewSearch, &input);
    assert!(new_search.meta("\\t\\v\\").is_categorical());
    assert_eq!(new_search.values("\\t\\v\\"), ["text"]);
    assert_eq!(new_search.report.counts.dropped_type_conflict, 2);

    let sequential = load_str(Mode::Sequential, &input);
    assert!(!sequential.meta("\\t\\v\\").is_categorical());
    assert_eq!(sequential.values("\\t\\v\\"), ["5.0", "6.0"]);
    assert_eq!(sequential.report.counts.dropped_type_conflict, 1);
}

#[test]
fn rows_dropped_for_blank_or_unparseable_values_still_create_their_concept() {
    let input = csv("1,\\blank\\,,  ,\n2,\\bad\\,oops,,\n3,\\bad\\,also,,\n4,\\ok\\,1,,\n");
    for mode in MODES {
        let store = load_str(mode, &input);
        let blank = store.meta("\\blank\\");
        assert!(blank.is_categorical());
        assert_eq!(blank.observation_count, 0);
        assert_eq!(blank.category_values.as_deref(), Some(&[][..]));
        assert_eq!(
            store.csv_line("\\blank\\"),
            format!(
                "\\blank\\,0,0,true,,null,null,{},0,0",
                csv_tail(&store, "\\blank\\")
            )
        );
        let bad = store.meta("\\bad\\");
        assert!(!bad.is_categorical());
        assert_eq!((bad.observation_count, bad.min, bad.max), (0, None, None));
        let width = if mode == Mode::Sequential { 8 } else { 0 };
        assert_eq!(bad.width_in_bytes, width, "{mode:?}");
        assert_eq!(store.report.counts.dropped_unparseable_number, 2);
        assert_eq!(store.report.counts.empty_concepts, 2);
        assert_eq!(store.patients(), [4]);
    }
}

fn csv_tail(store: &common::Store, path: &str) -> String {
    let block = store.meta(path).block;
    format!("{},{}", block.offset, block.length)
}

#[test]
fn short_rows_are_skipped_and_four_field_rows_have_no_timestamp() {
    let input = csv("1,\\s\\\n2,\\s\\,5\n3,\\s\\,6,,\n4,\\f\\,7,\n");
    for mode in MODES {
        let store = load_str(mode, &input);
        assert_eq!(store.rows("\\s\\"), [row(3, "6.0", None)]);
        assert_eq!(store.rows("\\f\\"), [row(4, "7.0", None)]);
        assert_eq!(store.report.counts.dropped_short_row, 2);
        assert_eq!(store.report.counts.rows_read, 4);
    }
}

#[test]
fn timestamps_parse_as_longs_and_bad_ones_become_no_timestamp() {
    let input = csv(
        "1,\\t\\,0,,1700000000000\n2,\\t\\,1,,abc\n3,\\t\\,2,, 1000\n4,\\t\\,3,,-5\n5,\\t\\,4,,\n",
    );
    for mode in MODES {
        let store = load_str(mode, &input);
        assert_eq!(
            store.rows("\\t\\"),
            [
                row(1, "0.0", Some(1_700_000_000_000)),
                row(2, "1.0", None),
                row(3, "2.0", None),
                row(4, "3.0", Some(-5)),
                row(5, "4.0", None),
            ]
        );
        let meta = store.meta("\\t\\");
        assert!(meta.has_timestamp);
        assert_eq!(
            (meta.timestamp_min, meta.timestamp_max),
            (Some(-5), Some(1_700_000_000_000))
        );
        assert_eq!(store.report.counts.unparseable_timestamps, 2);
    }
    let untimed = load_str(Mode::Sequential, &csv("1,\\u\\,,x,\n"));
    let meta = untimed.meta("\\u\\");
    assert!(!meta.has_timestamp);
    assert_eq!((meta.timestamp_min, meta.timestamp_max), (None, None));
}

#[test]
fn patient_numbers_parse_as_java_ints() {
    let input = csv("00900,\\p\\,1,,\n+901,\\p\\,2,,\n2147483647,\\p\\,3,,\n");
    for mode in MODES {
        let store = load_str(mode, &input);
        assert_eq!(store.patients(), [900, 901, 2_147_483_647]);
    }
}

#[test]
fn negative_patients_are_rejected_naming_the_row() {
    let input = std::fs::read(common::fixture("edge-negative-ids.csv")).unwrap();
    for mode in MODES {
        let error = load_files(mode, &[("allConcepts.csv", &input)], |_| {})
            .err()
            .unwrap();
        match &error {
            EtlError::InvalidPatient { record, line, .. } => {
                assert_eq!((*record, *line), (1, 2), "{mode:?}");
            }
            other => panic!("{mode:?}: {other}"),
        }
        let message = error.to_string();
        assert!(
            message.ends_with(
                "record 1 (line 2): PATIENT_NUM is not an integer between 0 and 2147483647"
            ),
            "{message}"
        );
        assert!(!message.contains("-5"), "{message}");
    }
}

#[test]
fn unparseable_patients_fail_where_java_parses_them() {
    let blank_row = csv("1,\\a\\,1,,\nx7,\\a\\,,,\n");
    assert!(load_files(Mode::NewSearch, &[("a.csv", blank_row.as_bytes())], |_| {}).is_ok());
    let error = load_files(Mode::Sequential, &[("a.csv", blank_row.as_bytes())], |_| {})
        .err()
        .unwrap();
    assert!(
        matches!(error, EtlError::InvalidPatient { record: 2, .. }),
        "{error}"
    );

    let type_conflict = csv("1,\\a\\,1,,\nx7,\\a\\,,text,\n");
    assert!(
        load_files(
            Mode::NewSearch,
            &[("a.csv", type_conflict.as_bytes())],
            |_| {}
        )
        .is_ok()
    );

    let accepted = csv("1,\\a\\,1,,\n2,\\b\\,,t,\nx7,\\b\\,,u,\ny8,\\a\\,2,,\n");
    for mode in MODES {
        let error = load_files(mode, &[("a.csv", accepted.as_bytes())], |o| {
            o.partitions = Some(5)
        })
        .err()
        .unwrap();
        match &error {
            EtlError::InvalidPatient { record, line, .. } => {
                assert_eq!((*record, *line), (3, 4), "{mode:?}")
            }
            other => panic!("{mode:?}: {other}"),
        }
        assert!(!error.to_string().contains("x7"), "{error}");
    }
}

#[test]
fn patient_set_holds_only_patients_with_stored_rows() {
    let input = csv("1,\\a\\,1,,\n2,\\a\\,,,\n3,\\a\\,,text,\n4,\\b\\,,x,\n5,\\c\\,bad,,\n");
    for mode in MODES {
        let store = load_str(mode, &input);
        assert_eq!(store.patients(), [1, 4]);
        assert_eq!(store.report.counts.patients, 2);
    }
}

#[test]
fn within_patient_order_follows_each_loaders_ingest_order() {
    let input = csv("1,\\o\\v\\,3,,\n1,\\o\\v,1,,\n1,\\o\\v\\,2,,\n2,\\o\\v,9,,\n");
    let new_search = load_str(Mode::NewSearch, &input);
    assert_eq!(
        new_search.rows("\\o\\v\\"),
        [
            row(1, "3.0", None),
            row(1, "1.0", None),
            row(1, "2.0", None),
            row(2, "9.0", None)
        ]
    );
    let sequential = load_str(Mode::Sequential, &input);
    assert_eq!(
        sequential.rows("\\o\\v\\"),
        [
            row(1, "1.0", None),
            row(1, "3.0", None),
            row(1, "2.0", None),
            row(2, "9.0", None)
        ]
    );
}

#[test]
fn numeric_statistics_follow_double_min_and_max() {
    let cases = [
        ("-0.0\n0\n", "-0.0", "0.0"),
        ("-3\n-1.5\n", "-3.0", "-1.5"),
        ("1\nNaN\n", "NaN", "NaN"),
        ("Infinity\n1e999\n", "Infinity", "Infinity"),
        ("0x1.8p1\n4.9E-324\n", "4.9E-324", "3.0"),
    ];
    for mode in MODES {
        for (values, min, max) in cases {
            let rows: String = values
                .lines()
                .enumerate()
                .map(|(i, v)| format!("{i},\\n\\,{v},,\n"))
                .collect();
            let store = load_str(mode, &csv(&rows));
            let line = store.csv_line("\\n\\");
            let fields: Vec<&str> = line.split(',').collect();
            assert_eq!((fields[5], fields[6]), (min, max), "{mode:?} {values:?}");
        }
    }
}

#[test]
fn categorical_statistics_follow_java() {
    let input = csv(
        "1,\\c\\,,z,\n2,\\c\\,,\u{E000},\n3,\\c\\,,\u{10000},\n3,\\c\\,,z,\n4,\\c\\,,  é  ,\n5,\\c\\,,a\"b,c,\n",
    );
    for mode in MODES {
        let store = load_str(mode, &input);
        let meta = store.meta("\\c\\");
        assert_eq!(
            meta.category_values.as_deref().unwrap(),
            ["a\"b", "z", "é", "\u{10000}", "\u{E000}"]
        );
        assert_eq!((meta.observation_count, meta.patient_count), (6, 5));
        assert_eq!(meta.width_in_bytes, 4);
        assert_eq!((meta.min, meta.max), (None, None));
    }
}

#[test]
fn several_sequential_files_load_in_order_and_merge() {
    let first = csv("1,\\m\\,,first,\n");
    let second = csv("1,\\m\\,,second,\n1,\\m\\,5,,\n2,\\n\\,1,,\n");
    let store = load_files(
        Mode::Sequential,
        &[("b.csv", first.as_bytes()), ("a.csv", second.as_bytes())],
        |_| {},
    )
    .unwrap();
    assert_eq!(store.values("\\m\\"), ["first", "second"]);
    assert_eq!(store.report.counts.rows_read, 4);
    assert_eq!(store.patients(), [1, 2]);

    let refused = load_files(
        Mode::NewSearch,
        &[("b.csv", first.as_bytes()), ("a.csv", second.as_bytes())],
        |_| {},
    );
    assert!(matches!(refused, Err(EtlError::Usage(_))));
}

#[test]
fn sequential_chunks_order_rows_by_chunk_then_raw_path() {
    let input = csv("1,\\k\\v\\,,a,\n1,\\k\\v,,b,\n1,\\k\\v\\,,c,\n1,\\k\\v,,d,\n");
    let one_chunk = load_str(Mode::Sequential, &input);
    assert_eq!(one_chunk.values("\\k\\v\\"), ["b", "d", "a", "c"]);
    let size = input.len() as f64;
    let two_chunks = load_files(Mode::Sequential, &[("x.csv", input.as_bytes())], |o| {
        o.max_chunk_gib = Some(size / 2.0 / (1u64 << 30) as f64);
    })
    .unwrap();
    assert_eq!(two_chunks.values("\\k\\v\\"), ["b", "a", "c", "d"]);
}

#[test]
fn embedded_newlines_load_every_record() {
    let input = std::fs::read(common::fixture("edge-embedded-newline.csv")).unwrap();
    for mode in MODES {
        let store = load_files(mode, &[("allConcepts.csv", &input)], |_| {}).unwrap();
        assert_eq!(
            store.values("\\edge\\newline\\cat\\"),
            ["line1\nline2", "plain", "plain"]
        );
        assert_eq!(
            store
                .csv_line("\\edge\\newline\\cat\\")
                .split(',')
                .take(5)
                .collect::<Vec<_>>(),
            [
                "\\edge\\newline\\cat\\",
                "11",
                "0",
                "true",
                "\"line1\nline2µplain\""
            ]
        );
    }
}

#[test]
fn invalid_utf8_decodes_to_replacement_characters() {
    let mut input = HEADER.as_bytes().to_vec();
    input.extend_from_slice(b"1,\\x\\v\xffa\\,,t\xffu,\n");
    for mode in MODES {
        let store = load_files(mode, &[("allConcepts.csv", &input)], |_| {}).unwrap();
        assert_eq!(store.values("\\x\\va\\"), ["t\u{FFFD}u"]);
    }
}

#[test]
fn plain_and_encrypted_stores_reject_the_other_protection() {
    let input = csv("1,\\a\\,1,,\n");
    let plain = load_files(Mode::NewSearch, &[("a.csv", input.as_bytes())], |o| {
        o.key = KeySource::Plain
    })
    .unwrap();
    assert!(!plain.report.encrypted);
    let key = hpds_format::Protection::Encrypted(
        hpds_format::CryptoKey::from_key_file_bytes(common::KEY.as_bytes()).unwrap(),
    );
    assert!(hpds_format::PhenoIndex::read_file(plain.out.join("pheno.index"), &key).is_err());
    let encrypted = load_str(Mode::NewSearch, &input);
    assert!(encrypted.report.encrypted);
    let plain_protection = hpds_format::Protection::Plain;
    assert!(
        hpds_format::PhenoIndex::read_file(encrypted.out.join("pheno.index"), &plain_protection)
            .is_err()
    );
}

#[test]
fn existing_output_directory_keeps_its_key_and_gets_a_fresh_store() {
    let dir = tempfile::tempdir().unwrap();
    let out = dir.path().join("hpds");
    std::fs::create_dir(&out).unwrap();
    std::fs::write(out.join("encryption_key"), common::KEY).unwrap();
    std::fs::write(out.join("pheno.index"), b"stale").unwrap();
    let input = dir.path().join("allConcepts.csv");
    for value in ["1", "2"] {
        std::fs::write(&input, csv(&format!("1,\\a\\,{value},,\n"))).unwrap();
        let options =
            hpds_etl::load::LoadOptions::new(Mode::NewSearch, vec![input.clone()], out.clone());
        let report = hpds_etl::load::load(&options).unwrap();
        let reopened = common::open(tempfile::tempdir().unwrap(), out.clone(), report);
        assert_eq!(reopened.values("\\a\\"), [format!("{value}.0")]);
    }
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
    let leftovers = std::fs::read_dir(dir.path()).unwrap().count();
    assert_eq!(leftovers, 2);
}

#[test]
fn a_failed_load_leaves_no_store_and_no_staging() {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    std::fs::write(&input, csv("-1,\\a\\,1,,\n")).unwrap();
    let out = dir.path().join("store");
    let mut options = hpds_etl::load::LoadOptions::new(Mode::NewSearch, vec![input], out.clone());
    options.key = KeySource::Plain;
    assert!(hpds_etl::load::load(&options).is_err());
    assert!(!out.exists());
    assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
}
