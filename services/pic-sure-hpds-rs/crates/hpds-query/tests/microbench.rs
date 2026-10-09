//! Times a COUNT with three filters, a study-level ANY_RECORD_OF COUNT and a CROSS_COUNT on
//! a generated store. Ignored by default; run with
//! `cargo test --release -p hpds-query --test microbench -- --ignored --nocapture`.
//! The store directory comes from `HPDS_BENCH_STORE`; the tests skip with a message when it
//! is unset or missing.

mod common;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::{any_record_of, filter_range, filter_values, lenient, query, required, subquery};
use hpds_query::{Engine, Operator, Query, ResultType, SummaryColumnMeta};

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
