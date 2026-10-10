//! The combined store: which queries read it, that no narrower selection ever does, that
//! its answers are the partitions' answers byte for byte, and the refusals at open.
//!
//! The strongest check plants a "poisoned" combined store: one built from the same
//! patients, concepts, types and observation counts but different values, so it passes
//! every consistency check at open and any answer read from it is visibly wrong. Every
//! consent selection but the full one must still answer exactly as the partitions do.

mod common;

use std::path::Path;

use common::{
    PartitionedDir, Row, add_combined, any_record_of, csv, filter_range, filter_values,
    fixture_texts, harm_fixture, lenient, partitioned_rows, partitioned_texts, required, strict,
    subquery,
};
use hpds_query::{
    COMBINED_DIR_NAME, CombinedMode, CombinedStatus, Engine, Operator, PartitionOptions,
    PhenotypicClause, Query, QueryError, ResultType, SummaryColumnMeta,
};

const FIXTURE_PARTITIONS: [&str; 4] = ["partition1", "partition2", "partition3", "partition4"];

fn with_mode(options: PartitionOptions, combined: CombinedMode) -> PartitionOptions {
    PartitionOptions {
        combined,
        ..options
    }
}

fn open(dir: &Path, combined: CombinedMode) -> Engine {
    Engine::open(dir, &with_mode(lenient(), combined)).unwrap()
}

fn open_error(dir: &Path, options: PartitionOptions) -> String {
    match Engine::open(dir, &options) {
        Ok(_) => panic!("{} opened", dir.display()),
        Err(error) => {
            assert!(matches!(error, QueryError::Open { .. }), "{error}");
            error.to_string()
        }
    }
}

fn generation(dir: &Path) -> String {
    std::fs::read_to_string(dir.join(".generation")).unwrap()
}

/// Every consent list the routing must handle on `partitions`: each subset (the empty one
/// included), each full set with an unknown consent added, a list of only unknown
/// consents, and no consents at all.
fn consent_lists(partitions: &[&str]) -> Vec<Vec<String>> {
    let mut lists = Vec::new();
    for mask in 0..(1u32 << partitions.len()) {
        lists.push(
            partitions
                .iter()
                .enumerate()
                .filter(|(i, _)| mask & (1 << i) != 0)
                .map(|(_, p)| (*p).to_owned())
                .collect::<Vec<_>>(),
        );
    }
    let mut with_unknown: Vec<String> = partitions.iter().map(|p| (*p).to_owned()).collect();
    with_unknown.insert(1, "phs999999.c9".to_owned());
    lists.push(with_unknown);
    lists.push(vec![
        "phs999999.c9".to_owned(),
        "not-a-partition".to_owned(),
    ]);
    lists.retain(|l| !l.is_empty());
    lists.push(Vec::new());
    lists
}

fn selects_all(consents: &[String], partitions: &[&str]) -> bool {
    consents.is_empty() || partitions.iter().all(|p| consents.iter().any(|c| c == p))
}

/// The clauses exercised on a store: per concept a REQUIRED filter, a value filter on its
/// first category or a range over the middle of its values, an `ANY_RECORD_OF` on the
/// concept's first path segment, and their AND and OR.
fn clauses(dictionary: &[SummaryColumnMeta]) -> Vec<Option<PhenotypicClause>> {
    let mut out = vec![None];
    for concept in dictionary {
        out.push(Some(required(&concept.name)));
        let filter = if concept.categorical {
            let values: Vec<&str> = concept.category_values.iter().map(String::as_str).collect();
            filter_values(&concept.name, &values[..values.len().min(1)])
        } else {
            match (concept.min, concept.max) {
                (Some(min), Some(max)) => {
                    let mid = min + (max - min) / 2.0;
                    filter_range(&concept.name, Some(mid), None)
                }
                _ => filter_range(&concept.name, None, Some(0.0)),
            }
        };
        out.push(Some(filter.clone()));
        out.push(Some(subquery(
            Operator::And,
            vec![filter.clone(), required(&concept.name)],
        )));
        out.push(Some(subquery(
            Operator::Or,
            vec![filter, required(&dictionary[0].name)],
        )));
    }
    let mut prefixes: Vec<String> = dictionary
        .iter()
        .filter_map(|c| c.name.get(..c.name[1..].find('\\')? + 2).map(str::to_owned))
        .collect();
    prefixes.dedup();
    out.extend(prefixes.iter().map(|p| Some(any_record_of(p))));
    out.push(Some(any_record_of("\\")));
    out
}

/// Every answer `engine` gives for `consents`: each sync result type's body, the PATIENTS
/// ids and every DATAFRAME row, for a sample of the clauses of [`clauses`], or the error.
fn answers(
    engine: &Engine,
    consents: &[String],
    clauses: &[Option<PhenotypicClause>],
) -> Vec<String> {
    let dictionary = engine.partitions().dictionary();
    let select: Vec<String> = dictionary.iter().map(|c| c.name.clone()).collect();
    let mut out = Vec::new();
    for (i, clause) in clauses.iter().enumerate() {
        for result_type in [
            ResultType::Count,
            ResultType::CrossCount,
            ResultType::CategoricalCrossCount,
            ResultType::ContinuousCrossCount,
            ResultType::ObservationCrossCount,
            ResultType::Patients,
            ResultType::Dataframe,
        ] {
            if result_type == ResultType::Dataframe && i % 5 != 0 {
                continue;
            }
            let query = Query {
                phenotypic_clause: clause.clone(),
                select: select.clone(),
                ..Query::with_consents(result_type, consents.iter().map(String::as_str))
            };
            let answer = match result_type {
                ResultType::Patients => engine
                    .patients(&query)
                    .map(|p| format!("{:?}", p.iter().collect::<Vec<_>>())),
                ResultType::Dataframe => engine.dataframe(&query).map(|frame| {
                    let rows: Vec<String> = frame.rows.map(|row| row.join(",")).collect();
                    format!("{}\n{}", frame.header.join(","), rows.join("\n"))
                }),
                _ => engine.run_sync(&query).map(|r| r.body()),
            };
            out.push(format!(
                "{i} {result_type:?}: {}",
                answer.unwrap_or_else(|e| format!("error {e}"))
            ));
        }
    }
    out
}

/// The CSV text of `text` with every numeric value raised by 1000 and every text value
/// prefixed, so the same rows of the same patients and concepts hold other values.
fn poison(text: &str) -> String {
    let mut reader = csv::Reader::from_reader(text.as_bytes());
    let mut writer = csv::Writer::from_writer(Vec::new());
    writer.write_record(reader.headers().unwrap()).unwrap();
    for record in reader.records() {
        let record = record.unwrap();
        let mut fields: Vec<String> = record.iter().map(str::to_owned).collect();
        if fields[2].is_empty() {
            fields[3] = format!("POISON {}", fields[3]);
        } else {
            let value: f64 = fields[2].parse().unwrap();
            fields[2] = format!("{:?}", value + 1000.0);
        }
        writer.write_record(&fields).unwrap();
    }
    String::from_utf8(writer.into_inner().unwrap()).unwrap()
}

/// Replaces the combined store of `target` with `donor`'s and lists the donor's id in
/// `target`'s `.generation`, keeping its partition lines. Both share [`KEY`].
fn plant_combined(target: &Path, donor: &Path) {
    let donor_id = generation(donor)
        .lines()
        .find_map(|l| l.strip_suffix(" .combined").map(str::to_owned))
        .unwrap();
    let combined = target.join(COMBINED_DIR_NAME);
    std::fs::remove_dir_all(&combined).unwrap();
    std::fs::create_dir(&combined).unwrap();
    for entry in std::fs::read_dir(donor.join(COMBINED_DIR_NAME)).unwrap() {
        let entry = entry.unwrap();
        std::fs::copy(entry.path(), combined.join(entry.file_name())).unwrap();
    }
    let text: String = generation(target)
        .lines()
        .map(|l| {
            if l.ends_with(" .combined") {
                format!("{donor_id} .combined\n")
            } else {
                format!("{l}\n")
            }
        })
        .collect();
    std::fs::write(target.join(".generation"), text).unwrap();
}

/// The fixture with a combined store built from poisoned copies of its partitions.
fn poisoned_fixture() -> (PartitionedDir, PartitionedDir) {
    let real = harm_fixture();
    add_combined(&real.out);
    let poisoned: Vec<(String, String)> = fixture_texts()
        .into_iter()
        .map(|(name, text)| (name, poison(&text)))
        .collect();
    let donor = partitioned_texts(&poisoned);
    add_combined(&donor.out);
    plant_combined(&real.out, &donor.out);
    (real, donor)
}

#[test]
fn the_combined_directory_name_is_the_etls_and_never_a_partition() {
    assert_eq!(COMBINED_DIR_NAME, hpds_etl::combined::COMBINED_DIR_NAME);
    assert!(hpds_etl::partitions::check_partition_name(COMBINED_DIR_NAME).is_err());
    let dir = harm_fixture();
    add_combined(&dir.out);
    let engine = open(&dir.out, CombinedMode::Auto);
    let names: Vec<&str> = engine.partitions().partition_names().collect();
    assert_eq!(names, FIXTURE_PARTITIONS);
    let only_combined = Query::with_consents(ResultType::Count, [COMBINED_DIR_NAME]);
    assert_eq!(engine.count(&only_combined).unwrap(), 0);
    let plan = engine.read_plan(&only_combined).unwrap();
    assert!(plan.partitions.is_empty() && !plan.combined);
}

#[test]
fn only_a_selection_of_every_partition_reads_the_combined_store() {
    let dir = harm_fixture();
    add_combined(&dir.out);
    let auto = open(&dir.out, CombinedMode::Auto);
    let off = open(&dir.out, CombinedMode::Off);
    assert_eq!(auto.partitions().combined_status(), CombinedStatus::InUse);
    assert_eq!(off.partitions().combined_status(), CombinedStatus::Off);
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        for result_type in [
            ResultType::Count,
            ResultType::Dataframe,
            ResultType::Patients,
        ] {
            let query = Query::with_consents(result_type, consents.iter().map(String::as_str));
            let plan = auto.read_plan(&query).unwrap();
            let expected: Vec<&str> = if consents.is_empty() {
                FIXTURE_PARTITIONS.to_vec()
            } else {
                FIXTURE_PARTITIONS
                    .iter()
                    .copied()
                    .filter(|p| consents.iter().any(|c| c == p))
                    .collect()
            };
            assert_eq!(plan.partitions, expected, "{consents:?}");
            assert_eq!(
                plan.combined,
                selects_all(&consents, &FIXTURE_PARTITIONS),
                "{consents:?}"
            );
            assert!(!off.read_plan(&query).unwrap().combined, "{consents:?}");
        }
    }
    let strict_engine = Engine::open(&dir.out, &with_mode(strict(), CombinedMode::Auto)).unwrap();
    let none = Query::new(ResultType::Count);
    assert!(matches!(
        strict_engine.read_plan(&none),
        Err(QueryError::UserConsentsRequired)
    ));
    assert!(matches!(
        strict_engine.count(&none),
        Err(QueryError::UserConsentsRequired)
    ));
    let all = Query::with_consents(ResultType::Count, FIXTURE_PARTITIONS);
    assert!(strict_engine.read_plan(&all).unwrap().combined);
    let narrow = Query::with_consents(ResultType::Count, ["partition2"]);
    assert!(!strict_engine.read_plan(&narrow).unwrap().combined);
}

#[test]
fn a_poisoned_combined_store_never_answers_a_narrower_selection() {
    let (real, _donor) = poisoned_fixture();
    let auto = open(&real.out, CombinedMode::Auto);
    let off = open(&real.out, CombinedMode::Off);
    assert_eq!(auto.partitions().combined_status(), CombinedStatus::InUse);
    assert_eq!(
        auto.partitions().dictionary(),
        off.partitions().dictionary(),
        "the dictionary comes from the partitions alone"
    );
    let clauses = clauses(off.partitions().dictionary());
    let mut full_selections_differ = 0;
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        let from_auto = answers(&auto, &consents, &clauses);
        let from_off = answers(&off, &consents, &clauses);
        if selects_all(&consents, &FIXTURE_PARTITIONS) {
            assert_ne!(
                from_auto, from_off,
                "{consents:?}: the combined store was not read"
            );
            full_selections_differ += 1;
        } else {
            for (a, b) in from_auto.iter().zip(&from_off) {
                assert_eq!(a, b, "{consents:?}");
            }
            assert_eq!(from_auto.len(), from_off.len());
        }
    }
    assert_eq!(full_selections_differ, 3);
}

#[test]
fn every_answer_from_the_combined_store_matches_the_partitions() {
    let dir = harm_fixture();
    add_combined(&dir.out);
    let auto = open(&dir.out, CombinedMode::Auto);
    let off = open(&dir.out, CombinedMode::Off);
    let clauses = clauses(off.partitions().dictionary());
    for consents in consent_lists(&FIXTURE_PARTITIONS) {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
}

/// Rows for two partitions that share every patient and concept, with timestamps absent
/// and values that tell the partitions apart.
fn shared_patient_rows(tag: &'static str, base: f64) -> Vec<Row<'static>> {
    let mut rows = Vec::new();
    for patient in 1..=6u32 {
        let value = base + f64::from(patient);
        rows.push((patient, "\\s\\num\\", Some(value), None));
        rows.push((patient, "\\s\\num\\", Some(value + 0.5), None));
        rows.push((patient, "\\s\\cat\\", None, Some(tag)));
        if patient % 2 == 0 {
            rows.push((patient, "\\s\\cat\\", None, Some("shared")));
        }
    }
    rows
}

#[test]
fn ties_follow_java_partition_name_order_even_where_bytes_disagree() {
    let early = "\u{1F600}";
    let late = "\u{FF61}";
    assert!(late.as_bytes() < early.as_bytes());
    let d = partitioned_rows(&[
        (late, shared_patient_rows("late", 100.0)),
        (early, shared_patient_rows("early", 200.0)),
        ("zzz", shared_patient_rows("zzz", 300.0)),
    ]);
    add_combined(&d.out);
    let auto = open(&d.out, CombinedMode::Auto);
    let off = open(&d.out, CombinedMode::Off);
    let names: Vec<&str> = off.partitions().partition_names().collect();
    assert_eq!(names, ["zzz", early, late], "Java string order");
    let query = Query {
        select: vec!["\\s\\num\\".into(), "\\s\\cat\\".into()],
        ..Query::new(ResultType::Dataframe)
    };
    assert!(auto.read_plan(&query).unwrap().combined);
    assert!(!off.read_plan(&query).unwrap().combined);
    let frame = |engine: &Engine| engine.dataframe(&query).unwrap().rows.collect::<Vec<_>>();
    let combined = frame(&auto);
    assert_eq!(combined, frame(&off));
    assert_eq!(combined[1][1], "302.0\t302.5\t202.0\t202.5\t102.0\t102.5");
    assert_eq!(combined[1][2], "zzz\tshared\tearly\tshared\tlate\tshared");
    let clauses = clauses(off.partitions().dictionary());
    for consents in consent_lists(&[early, late, "zzz"]) {
        assert_eq!(
            answers(&auto, &consents, &clauses),
            answers(&off, &consents, &clauses),
            "{consents:?}"
        );
    }
}

#[test]
fn search_and_metadata_do_not_change() {
    let dir = harm_fixture();
    let before = Engine::open(&dir.out, &lenient()).unwrap();
    add_combined(&dir.out);
    let after = open(&dir.out, CombinedMode::Auto);
    assert_eq!(after.partitions().combined_status(), CombinedStatus::InUse);
    assert_eq!(
        before.partitions().dictionary(),
        after.partitions().dictionary()
    );
    assert_eq!(
        before.partitions().partition_names().collect::<Vec<_>>(),
        after.partitions().partition_names().collect::<Vec<_>>()
    );
    assert_eq!(
        before.partitions().concept_count(),
        after.partitions().concept_count()
    );
}

/// A small two-partition store with a combined store, and its rows.
fn small() -> (PartitionedDir, Vec<(&'static str, Vec<Row<'static>>)>) {
    let rows = vec![
        (
            "p1",
            vec![
                (1, "\\a\\", Some(1.0), None),
                (2, "\\a\\", Some(2.0), None),
                (1, "\\b\\", None, Some("x")),
            ],
        ),
        (
            "p2",
            vec![(3, "\\a\\", Some(3.0), None), (4, "\\b\\", None, Some("y"))],
        ),
    ];
    let d = partitioned_rows(&rows);
    add_combined(&d.out);
    (d, rows)
}

/// Builds a donor store from `rows` changed by `change`, and plants its combined store in
/// a fresh copy of [`small`].
fn small_with_planted(
    change: impl FnOnce(&mut Vec<(&'static str, Vec<Row<'static>>)>),
) -> PartitionedDir {
    let (target, mut rows) = small();
    change(&mut rows);
    let donor = partitioned_rows(&rows);
    add_combined(&donor.out);
    plant_combined(&target.out, &donor.out);
    target
}

#[test]
fn an_inconsistent_combined_store_refuses_the_open() {
    let cases: Vec<(&str, PartitionedDir)> = vec![
        (
            "concepts",
            small_with_planted(|rows| rows[0].1.push((1, "\\c\\", Some(5.0), None))),
        ),
        (
            "differs in type",
            small_with_planted(|rows| {
                for (_, partition) in rows.iter_mut() {
                    for row in partition.iter_mut().filter(|r| r.1 == "\\a\\") {
                        *row = (row.0, row.1, None, Some("text"));
                    }
                }
            }),
        ),
        (
            "observations",
            small_with_planted(|rows| rows[1].1.push((3, "\\a\\", Some(9.0), None))),
        ),
        (
            "patients",
            small_with_planted(|rows| rows[1].1[0] = (30, "\\a\\", Some(3.0), None)),
        ),
    ];
    for (needle, dir) in cases {
        let message = open_error(&dir.out, lenient());
        assert!(
            message.contains("disagrees with the partitions") && message.contains(needle),
            "{needle}: {message}"
        );
        let engine = open(&dir.out, CombinedMode::Off);
        assert_eq!(engine.partitions().combined_status(), CombinedStatus::Off);
        assert_eq!(engine.count(&Query::new(ResultType::Count)).unwrap(), 4);
    }
}

#[test]
fn a_missing_unlisted_or_mismatched_combined_store_refuses_the_open() {
    let (d, _) = small();
    let combined = d.out.join(COMBINED_DIR_NAME);
    let saved = generation(&d.out);
    let combined_line = saved.lines().find(|l| l.ends_with(" .combined")).unwrap();

    std::fs::write(
        d.out.join(".generation"),
        saved.replace(&format!("{combined_line}\n"), ""),
    )
    .unwrap();
    let message = open_error(&d.out, lenient());
    assert!(message.contains("does not list"), "{message}");

    std::fs::remove_file(d.out.join(".generation")).unwrap();
    let message = open_error(&d.out, lenient());
    assert!(message.contains("does not list"), "{message}");

    let other = format!("{} .combined", "0".repeat(32));
    std::fs::write(
        d.out.join(".generation"),
        saved.replace(combined_line, &other),
    )
    .unwrap();
    let message = open_error(&d.out, lenient());
    assert!(message.contains("partially published"), "{message}");

    std::fs::write(
        d.out.join(".generation"),
        format!("{saved}{combined_line}\n"),
    )
    .unwrap();
    let message = open_error(&d.out, lenient());
    assert!(
        message.contains("more than one combined store"),
        "{message}"
    );

    std::fs::write(d.out.join(".generation"), &saved).unwrap();
    let moved = d.dir.path().join("moved-combined");
    std::fs::rename(&combined, &moved).unwrap();
    let message = open_error(&d.out, lenient());
    assert!(message.contains("lists a combined store"), "{message}");

    let engine = open(&d.out, CombinedMode::Off);
    assert_eq!(engine.partitions().combined_status(), CombinedStatus::Off);
    std::fs::rename(&moved, &combined).unwrap();
    let engine = open(&d.out, CombinedMode::Auto);
    assert_eq!(engine.partitions().combined_status(), CombinedStatus::InUse);
}

#[test]
fn a_stale_partition_still_refuses_with_a_combined_store() {
    let (d, rows) = small();
    let fresh = partitioned_rows(&rows);
    std::fs::remove_dir_all(d.out.join("p2")).unwrap();
    std::fs::rename(fresh.out.join("p2"), d.out.join("p2")).unwrap();
    for mode in [CombinedMode::Auto, CombinedMode::Off] {
        let message = open_error(&d.out, with_mode(lenient(), mode));
        assert!(message.contains("partially published"), "{message}");
    }
}

#[test]
fn a_store_without_a_combined_store_reads_its_partitions() {
    let d = partitioned_rows(&[("p1", vec![(1, "\\a\\", Some(1.0), None)])]);
    let engine = open(&d.out, CombinedMode::Auto);
    assert_eq!(
        engine.partitions().combined_status(),
        CombinedStatus::Absent
    );
    assert!(
        !engine
            .read_plan(&Query::new(ResultType::Count))
            .unwrap()
            .combined
    );
}

#[test]
fn an_unpartitioned_store_with_a_combined_store_is_refused() {
    let s = common::load_csv(&csv(&[(1, "\\a\\", Some(1.0), None)]));
    let (d, _) = small();
    std::fs::rename(d.out.join(COMBINED_DIR_NAME), s.out.join(COMBINED_DIR_NAME)).unwrap();
    let message = open_error(&s.out, lenient());
    assert!(
        message.contains("unpartitioned store has no combined store"),
        "{message}"
    );
    let engine = open(&s.out, CombinedMode::Off);
    assert_eq!(engine.partitions().combined_status(), CombinedStatus::Off);
}

#[test]
fn removing_concepts_keeps_the_combined_store_consistent() {
    let (d, _) = small();
    let concepts = d.dir.path().join("remove.txt");
    std::fs::write(&concepts, "\\b\\\n").unwrap();
    let partitions = hpds_etl::partitions::partition_dirs(&d.out).unwrap();
    let combined = hpds_etl::combined::existing_combined_dir(&d.out).unwrap();

    hpds_etl::store_maintenance::remove_concepts(
        &partitions[0].dir,
        &concepts,
        &common::protection(),
        true,
    )
    .unwrap();
    let message = open_error(&d.out, lenient());
    assert!(
        message.contains("disagrees with the partitions"),
        "{message}"
    );

    let reports = hpds_etl::store_maintenance::remove_concepts_partitioned(
        &partitions,
        Some(&combined),
        &concepts,
        &common::protection(),
        true,
    )
    .unwrap();
    assert_eq!(reports[COMBINED_DIR_NAME].removed, 1);
    let engine = open(&d.out, CombinedMode::Auto);
    assert_eq!(engine.partitions().combined_status(), CombinedStatus::InUse);
    assert!(engine.partitions().summary("\\b\\").is_none());
    assert_eq!(
        engine
            .count(&Query {
                phenotypic_clause: Some(required("\\a\\")),
                ..Query::new(ResultType::Count)
            })
            .unwrap(),
        3
    );
}

#[test]
fn combine_remove_restores_a_store_without_one() {
    let (d, _) = small();
    assert!(generation(&d.out).contains(" .combined"));
    assert!(hpds_etl::combined::remove_combined(&d.out, &common::protection()).unwrap());
    assert!(!generation(&d.out).contains(".combined"));
    assert!(!d.out.join(COMBINED_DIR_NAME).exists());
    let engine = open(&d.out, CombinedMode::Auto);
    assert_eq!(
        engine.partitions().combined_status(),
        CombinedStatus::Absent
    );
    assert!(!hpds_etl::combined::remove_combined(&d.out, &common::protection()).unwrap());
    let leftovers: Vec<String> = std::fs::read_dir(&d.out)
        .unwrap()
        .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
        .filter(|n| n.contains("combined"))
        .collect();
    assert!(leftovers.is_empty(), "{leftovers:?}");
    add_combined(&d.out);
    assert_eq!(
        open(&d.out, CombinedMode::Auto)
            .partitions()
            .combined_status(),
        CombinedStatus::InUse
    );
}

#[test]
fn combine_refuses_a_store_whose_generation_disagrees() {
    let (d, rows) = small();
    let fresh = partitioned_rows(&rows);
    std::fs::remove_dir_all(d.out.join("p2")).unwrap();
    std::fs::rename(fresh.out.join("p2"), d.out.join("p2")).unwrap();
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(1)
        .build()
        .unwrap();
    let error =
        hpds_etl::combined::combine_in_place(&d.out, &common::protection(), &pool).unwrap_err();
    assert!(
        error.to_string().contains("publish is under way"),
        "{error}"
    );
}
