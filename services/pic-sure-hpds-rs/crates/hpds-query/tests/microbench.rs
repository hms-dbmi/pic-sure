//! Times a COUNT with three filters, a study-level ANY_RECORD_OF COUNT and a CROSS_COUNT on
//! a generated store. Ignored by default; run with
//! `cargo test --release -p hpds-query --test microbench -- --ignored --nocapture`.
//! The store directory comes from `HPDS_BENCH_STORE`; the tests skip with a message when it
//! is unset or missing.

mod common;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::{any_record_of, filter_range, filter_values, query, required, subquery};
use hpds_format::ConceptMeta;
use hpds_query::{Engine, Operator, ResultType, StoreOptions};

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

fn widest(concepts: &[ConceptMeta], categorical: bool, skip: usize) -> &ConceptMeta {
    let mut candidates: Vec<&ConceptMeta> = concepts
        .iter()
        .filter(|c| c.is_categorical() == categorical && c.observation_count > 0)
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
    let engine = Engine::open(&dir, &StoreOptions::default()).unwrap();
    let open_time = opened.elapsed();
    let concepts = engine.store().index().concepts();
    let category = widest(concepts, true, 0);
    let number = widest(concepts, false, 0);
    let other = widest(concepts, true, 1);
    let value = category.category_values.as_ref().unwrap()[0].clone();
    let (min, max) = (number.min.unwrap(), number.max.unwrap());
    let q = query(
        ResultType::Count,
        Some(subquery(
            Operator::And,
            vec![
                filter_values(&category.path, &[value.as_str()]),
                filter_range(&number.path, Some(min), Some((min + max) / 2.0)),
                required(&other.path),
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
        engine.store().index().patients().len()
    );
    eprintln!(
        "filters: {} = {value:?} ({} patients); {} in [{min}, {}] ({} patients, {} observations); REQUIRED {} ({} patients)",
        category.path,
        category.patient_count,
        number.path,
        (min + max) / 2.0,
        number.patient_count,
        number.observation_count,
        other.path,
        other.patient_count
    );
    eprintln!(
        "count {count}: cold {cold:?}; warm p50 {:?} p95 {:?} min {:?} over {} runs; cache {} blocks, {} bytes",
        pct(50),
        pct(95),
        warm[0],
        warm.len(),
        engine.store().cached_block_count(),
        engine.store().cached_bytes()
    );
}

#[test]
#[ignore = "needs a generated store; run explicitly for timings"]
fn cross_count_over_300_concepts_warm() {
    let Some(dir) = bench_store() else {
        return;
    };
    let engine = Engine::open(&dir, &StoreOptions::default()).unwrap();
    let mut concepts: Vec<&ConceptMeta> = engine
        .store()
        .index()
        .concepts()
        .iter()
        .filter(|c| c.observation_count > 0)
        .collect();
    concepts.sort_by_key(|c| std::cmp::Reverse(c.patient_count));
    let selected: Vec<&str> = concepts.iter().take(300).map(|c| c.path.as_str()).collect();
    let filter_concept = widest(engine.store().index().concepts(), true, 0);
    let value = filter_concept.category_values.as_ref().unwrap()[0].clone();
    let mut q = query(
        ResultType::CrossCount,
        Some(filter_values(&filter_concept.path, &[value.as_str()])),
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
        engine.store().cached_block_count(),
        engine.store().cached_bytes()
    );
}

#[test]
#[ignore = "needs a generated store; run explicitly for timings"]
fn study_level_any_record_of_count_warm() {
    let Some(dir) = bench_store() else {
        return;
    };
    let prefix = std::env::var("HPDS_BENCH_PREFIX").unwrap_or_else(|_| "\\study_4\\".to_owned());
    let engine = Engine::open(&dir, &StoreOptions::default()).unwrap();
    let children = engine.store().index().with_prefix(&prefix).len();
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
