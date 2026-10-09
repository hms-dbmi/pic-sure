//! Times a COUNT with three filters, a study-level ANY_RECORD_OF COUNT and a CROSS_COUNT on
//! a generated store, and COUNT, CROSS_COUNT and DATAFRAME over 40 consent partitions.
//! Ignored by default; run with
//! `cargo test --release -p hpds-query --test microbench -- --ignored --nocapture`.
//! The single-store tests read the store directory from `HPDS_BENCH_STORE` and skip with a
//! message when it is unset or missing. The 40-partition test builds its data directory
//! in-process, under `HPDS_BENCH40_DIR` when set (and reuses one already built there), else
//! in a temporary directory.

mod common;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use common::{
    KEY, any_record_of, filter_range, filter_values, lenient, load_into, query, required, strict,
    subquery,
};
use hpds_gen::generate::{IdLayout, Order, Shape, generate};
use hpds_gen::model::CONSENTS_PATH;
use hpds_query::{Engine, Operator, Query, ResultType, RowBuffer, SummaryColumnMeta};

fn bench_store() -> Option<PathBuf> {
    let Some(dir) = std::env::var_os("HPDS_BENCH_STORE").map(PathBuf::from) else {
        eprintln!("skipping: set HPDS_BENCH_STORE to a store directory");
        return None;
    };
    if !dir.exists() {
        eprintln!("skipping: no store at {}", dir.display());
        return None;
    }
    Some(dir)
}

fn widest(concepts: &[SummaryColumnMeta], categorical: bool, skip: usize) -> &SummaryColumnMeta {
    let mut candidates: Vec<&SummaryColumnMeta> = concepts
        .iter()
        .filter(|c| c.categorical == categorical && c.patient_count > 0)
        .collect();
    candidates.sort_by_key(|c| std::cmp::Reverse(c.patient_count));
    candidates[skip]
}

#[test]
#[ignore = "needs a generated store; run explicitly for timings"]
fn count_with_three_filters_cold_and_warm() {
    let Some(dir) = bench_store() else {
        return;
    };
    let opened = Instant::now();
    let engine = Engine::open(&dir, &lenient()).unwrap();
    let open_time = opened.elapsed();
    let concepts = engine.partitions().dictionary();
    let category = widest(concepts, true, 0);
    let number = widest(concepts, false, 0);
    let other = widest(concepts, true, 1);
    let value = category.category_values[0].clone();
    let (min, max) = (number.min.unwrap(), number.max.unwrap());
    let q = query(
        ResultType::Count,
        Some(subquery(
            Operator::And,
            vec![
                filter_values(&category.name, &[value.as_str()]),
                filter_range(&number.name, Some(min), Some((min + max) / 2.0)),
                required(&other.name),
            ],
        )),
    );

    let cold = Instant::now();
    let count = engine.count(&q).unwrap();
    let cold = cold.elapsed();

    let mut warm: Vec<Duration> = (0..200)
        .map(|_| {
            let t = Instant::now();
            assert_eq!(engine.count(&q).unwrap(), count);
            t.elapsed()
        })
        .collect();
    warm.sort();
    let pct = |p: usize| warm[(warm.len() * p / 100).min(warm.len() - 1)];
    eprintln!(
        "store {} ({} concepts, {} patients), open {open_time:?}",
        dir.display(),
        concepts.len(),
        engine.count(&Query::new(ResultType::Count)).unwrap()
    );
    eprintln!(
        "filters: {} = {value:?} ({} patients); {} in [{min}, {}] ({} patients); REQUIRED {} ({} patients)",
        category.name,
        category.patient_count,
        number.name,
        (min + max) / 2.0,
        number.patient_count,
        other.name,
        other.patient_count
    );
    eprintln!(
        "count {count}: cold {cold:?}; warm p50 {:?} p95 {:?} min {:?} over {} runs; cache {} blocks, {} bytes",
        pct(50),
        pct(95),
        warm[0],
        warm.len(),
        engine.partitions().cached_block_count(),
        engine.partitions().cached_bytes()
    );
}

#[test]
#[ignore = "needs a generated store; run explicitly for timings"]
fn cross_count_over_300_concepts_warm() {
    let Some(dir) = bench_store() else {
        return;
    };
    let engine = Engine::open(&dir, &lenient()).unwrap();
    let mut concepts: Vec<&SummaryColumnMeta> = engine
        .partitions()
        .dictionary()
        .iter()
        .filter(|c| c.patient_count > 0)
        .collect();
    concepts.sort_by_key(|c| std::cmp::Reverse(c.patient_count));
    let selected: Vec<&str> = concepts.iter().take(300).map(|c| c.name.as_str()).collect();
    let filter_concept = widest(engine.partitions().dictionary(), true, 0);
    let value = filter_concept.category_values[0].clone();
    let mut q = query(
        ResultType::CrossCount,
        Some(filter_values(&filter_concept.name, &[value.as_str()])),
    );
    q.select = selected.iter().map(|p| (*p).to_owned()).collect();

    let cold = Instant::now();
    let first = engine.cross_count(&q).unwrap();
    let cold = cold.elapsed();
    let mut warm: Vec<Duration> = (0..20)
        .map(|_| {
            let t = Instant::now();
            assert_eq!(engine.cross_count(&q).unwrap(), first);
            t.elapsed()
        })
        .collect();
    warm.sort();
    eprintln!(
        "cross count over {} concepts: cold {cold:?}; warm p50 {:?} min {:?} over {} runs; cache {} blocks, {} bytes",
        q.select.len(),
        warm[warm.len() / 2],
        warm[0],
        warm.len(),
        engine.partitions().cached_block_count(),
        engine.partitions().cached_bytes()
    );
}

#[test]
#[ignore = "needs a generated store; run explicitly for timings"]
fn study_level_any_record_of_count_warm() {
    let Some(dir) = bench_store() else {
        return;
    };
    let prefix = std::env::var("HPDS_BENCH_PREFIX").unwrap_or_else(|_| "\\study_4\\".to_owned());
    let engine = Engine::open(&dir, &lenient()).unwrap();
    let children = engine.partitions().dictionary_with_prefix(&prefix).len();
    let q = query(ResultType::Count, Some(any_record_of(&prefix)));

    let cold = Instant::now();
    let count = engine.count(&q).unwrap();
    let cold = cold.elapsed();
    let mut warm: Vec<Duration> = (0..200)
        .map(|_| {
            let t = Instant::now();
            assert_eq!(engine.count(&q).unwrap(), count);
            t.elapsed()
        })
        .collect();
    warm.sort();
    let pct = |p: usize| warm[(warm.len() * p / 100).min(warm.len() - 1)];
    eprintln!(
        "ANY_RECORD_OF {prefix} over {children} concepts, count {count}: cold {cold:?}; warm p50 {:?} p95 {:?} min {:?} over {} runs",
        pct(50),
        pct(95),
        warm[0],
        warm.len()
    );
}

/// The generated dataset split into 40 partitions: 20 studies of two consent groups each.
const BENCH40_SHAPE: Shape = Shape {
    patients: 100_000,
    concepts: 1_000,
    observations: 5_000_000,
    studies: 20,
    high_cardinality: 0,
    ids: IdLayout::DENSE,
};

const BENCH40_HEADER: &str = "PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME";

/// Generates [`BENCH40_SHAPE`], splits its rows by each patient's `\_consents\` value into
/// one CSV per consent group, and loads each into `<root>/data/<consent>` with the shared
/// key. Returns the data directory; a complete one already under `root` is reused.
fn build_bench40(root: &Path) -> PathBuf {
    let data = root.join("data");
    let done = root.join("built");
    if done.exists() {
        return data;
    }
    std::fs::create_dir_all(root).unwrap();
    let source = root.join("allConcepts.csv");
    generate(
        BENCH40_SHAPE,
        7,
        Order::Concept,
        std::io::BufWriter::new(std::fs::File::create(&source).unwrap()),
    )
    .unwrap();
    let mut consent_of: HashMap<String, String> = HashMap::new();
    let mut reader = csv::Reader::from_path(&source).unwrap();
    for record in reader.records() {
        let record = record.unwrap();
        if &record[1] == CONSENTS_PATH {
            consent_of.insert(record[0].to_owned(), record[3].to_owned());
        }
    }
    let split = root.join("split");
    std::fs::create_dir_all(&split).unwrap();
    let mut writers: HashMap<String, csv::Writer<std::fs::File>> = HashMap::new();
    let mut reader = csv::Reader::from_path(&source).unwrap();
    for record in reader.records() {
        let record = record.unwrap();
        let consent = &consent_of[&record[0]];
        let writer = writers.entry(consent.clone()).or_insert_with(|| {
            let mut writer = csv::Writer::from_path(split.join(format!("{consent}.csv"))).unwrap();
            writer.write_record(BENCH40_HEADER.split(',')).unwrap();
            writer
        });
        writer.write_record(&record).unwrap();
    }
    assert_eq!(writers.len(), 40, "expected 40 consent groups");
    for writer in writers.values_mut() {
        writer.flush().unwrap();
    }
    let key_file = root.join("key");
    std::fs::write(&key_file, KEY).unwrap();
    std::fs::create_dir_all(&data).unwrap();
    for consent in writers.keys() {
        load_into(
            &split.join(format!("{consent}.csv")),
            &key_file,
            &data.join(consent),
        );
    }
    std::fs::copy(&key_file, data.join("encryption_key")).unwrap();
    std::fs::remove_file(&source).unwrap();
    std::fs::remove_dir_all(&split).unwrap();
    std::fs::write(&done, b"").unwrap();
    data
}

/// Runs `work` once cold and `runs` times warm, and prints the cold time and the warm p50,
/// p95 and minimum under `label`.
fn time_it<T: PartialEq + std::fmt::Debug>(label: &str, runs: usize, mut work: impl FnMut() -> T) {
    let cold = Instant::now();
    let first = work();
    let cold = cold.elapsed();
    let mut warm: Vec<Duration> = (0..runs)
        .map(|_| {
            let t = Instant::now();
            assert_eq!(work(), first);
            t.elapsed()
        })
        .collect();
    warm.sort();
    let pct = |p: usize| warm[(warm.len() * p / 100).min(warm.len() - 1)];
    eprintln!(
        "{label}: cold {cold:?}; warm p50 {:?} p95 {:?} min {:?} over {runs} runs",
        pct(50),
        pct(95),
        warm[0]
    );
}

#[test]
#[ignore = "builds a 40-partition store; run explicitly for timings"]
fn forty_partitions_count_cross_count_and_dataframe() {
    let temp = tempfile::tempdir().unwrap();
    let root = std::env::var_os("HPDS_BENCH40_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| temp.path().to_path_buf());
    let built = Instant::now();
    let data = build_bench40(&root);
    let built = built.elapsed();
    let opened = Instant::now();
    let engine = Engine::open(&data, &strict()).unwrap();
    let opened = opened.elapsed();
    let set = engine.partitions();
    let consents: Vec<String> = set.partition_names().map(str::to_owned).collect();
    assert_eq!(consents.len(), 40);
    let all = Query::with_consents(ResultType::Count, consents.iter().cloned());
    let dictionary = set.dictionary();
    eprintln!(
        "40 partitions at {}: built {built:?}, opened {opened:?}, {} concepts, {} patients",
        data.display(),
        set.concept_count(),
        engine.count(&all).unwrap()
    );

    let category = widest(dictionary, true, 0);
    let number = widest(dictionary, false, 0);
    let other = widest(dictionary, true, 1);
    let value = category.category_values[0].clone();
    let (min, max) = (number.min.unwrap(), number.max.unwrap());
    let consented = |result_type, clause| Query {
        phenotypic_clause: Some(clause),
        expected_result_type: result_type,
        ..all.clone()
    };
    let count = consented(
        ResultType::Count,
        subquery(
            Operator::And,
            vec![
                filter_values(&category.name, &[value.as_str()]),
                filter_range(&number.name, Some(min), Some((min + max) / 2.0)),
                required(&other.name),
            ],
        ),
    );
    time_it("COUNT, three filters, 40 consents", 200, || {
        engine.count(&count).unwrap()
    });
    let mut four = count.clone();
    four.user_consents.truncate(4);
    time_it("COUNT, three filters, 4 consents", 200, || {
        engine.count(&four).unwrap()
    });

    let mut by_patients: Vec<&SummaryColumnMeta> =
        dictionary.iter().filter(|c| c.patient_count > 0).collect();
    by_patients.sort_by_key(|c| std::cmp::Reverse(c.patient_count));
    let cohort = || filter_values(&category.name, &[value.as_str()]);
    let mut cross = consented(ResultType::CrossCount, cohort());
    cross.select = by_patients
        .iter()
        .take(50)
        .map(|c| c.name.clone())
        .collect();
    time_it("CROSS_COUNT over 50 concepts, 40 consents", 50, || {
        engine.cross_count(&cross).unwrap()
    });
    let mut narrow = cross.clone();
    narrow.phenotypic_clause = count.phenotypic_clause.clone();
    eprintln!("narrow cohort: {} patients", engine.count(&count).unwrap());
    time_it(
        "CROSS_COUNT over 50 concepts, narrow cohort, 40 consents",
        50,
        || engine.cross_count(&narrow).unwrap(),
    );

    let mut frame = consented(ResultType::Dataframe, cohort());
    frame.select = by_patients
        .iter()
        .take(10)
        .map(|c| c.name.clone())
        .collect();
    let rows = std::cell::Cell::new(0_u64);
    time_it("DATAFRAME of 10 columns, 40 consents", 10, || {
        let mut rows_seen = 0_u64;
        let mut bytes = 0_usize;
        let mut buffer = RowBuffer::new();
        let mut dataframe = engine.dataframe(&frame).unwrap();
        while dataframe.rows.next_into(&mut buffer) {
            rows_seen += 1;
            bytes += buffer.fields().map(str::len).sum::<usize>();
        }
        rows.set(rows_seen);
        (rows_seen, bytes)
    });
    eprintln!(
        "DATAFRAME rows {}; cache {} blocks, {} bytes",
        rows.get(),
        set.cached_block_count(),
        set.cached_bytes()
    );
}
