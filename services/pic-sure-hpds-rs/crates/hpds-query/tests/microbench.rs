//! Times a COUNT with three filters on a generated store, cold and warm. Ignored by default;
//! run with
//! `cargo test --release -p hpds-query --test microbench -- --ignored --nocapture`.
//! The store directory comes from `HPDS_BENCH_STORE`, defaulting to the `dev` store the
//! phase 1 runs built.

mod common;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use common::{filter_range, filter_values, query, required, subquery};
use hpds_format::ConceptMeta;
use hpds_query::{Engine, Operator, ResultType, StoreOptions};

const DEFAULT_STORE: &str = "/private/tmp/claude-503/-Users-george-code-workspaces-Rust-HPDS/020ed886-02e8-4890-af29-48173a4664b5/scratchpad/data/dev/rust";

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
    let dir = std::env::var_os("HPDS_BENCH_STORE")
        .map_or_else(|| PathBuf::from(DEFAULT_STORE), PathBuf::from);
    if !dir.exists() {
        eprintln!("skipping: no store at {}", dir.display());
        return;
    }
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
        engine.store().cached_blocks(),
        engine.store().cached_bytes()
    );
}
