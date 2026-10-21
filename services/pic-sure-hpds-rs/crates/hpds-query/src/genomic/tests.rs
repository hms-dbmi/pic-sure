//! Tests of the genomic engine's internals: where the genomic read budget grants permits, and
//! the carrier fast path against the mask path on the `g1kg` store when the bench data is
//! present.

use std::path::PathBuf;

use hpds_format::PosBitset;

use super::cohort::{carrier_mask, variant_mask};
use super::store::{GenomicOptions, GenomicStore, ReadPath, genomic_pool};
use crate::budget::{ByteBudget, PermitThreads};

#[test]
fn the_genomic_budget_grants_on_genomic_workers_and_outside_rayon() {
    let budget = ByteBudget::on_threads(100, PermitThreads::GenomicWorkers);
    drop(budget.acquire(10));
    let pool = genomic_pool(2).unwrap();
    pool.install(|| drop(budget.acquire(10)));
    pool.broadcast(|_| drop(budget.acquire(10)));
    assert_eq!(budget.stats().permits, 4);
}

#[test]
#[cfg(debug_assertions)]
#[should_panic(expected = "outside the pool its budget allows")]
fn the_genomic_budget_panics_on_a_main_pool_worker_in_a_debug_build() {
    let budget = ByteBudget::on_threads(100, PermitThreads::GenomicWorkers);
    let main = rayon::ThreadPoolBuilder::new()
        .num_threads(1)
        .build()
        .unwrap();
    main.install(|| drop(budget.acquire(10)));
}

#[test]
#[cfg(debug_assertions)]
#[should_panic(expected = "outside the pool its budget allows")]
fn the_phenotype_budget_still_panics_on_a_genomic_worker_in_a_debug_build() {
    let budget = ByteBudget::new(100);
    genomic_pool(1)
        .unwrap()
        .install(|| drop(budget.acquire(10)));
}

/// The bench data directory (`.worktrees/bench-data/genomic`), from `HPDS_GENOMIC_BENCH`, or
/// `None` when it is not set.
fn bench_dir() -> Option<PathBuf> {
    std::env::var_os("HPDS_GENOMIC_BENCH").map(PathBuf::from)
}

/// The positions of group 0 whose patient id lies in `ids`.
fn scope_of(store: &GenomicStore, ids: std::ops::RangeInclusive<u32>) -> PosBitset {
    let group = &store.groups()[0];
    let mut scope = PosBitset::new(group.count());
    for (pos, id) in group.ids_by_pos.iter().enumerate() {
        if ids.contains(id) {
            scope.insert(pos as u32);
        }
    }
    scope
}

/// On the migrated `g1kg` store, every categorical value of every node answers the same from
/// its precomputed carriers as from the masks of its variants, under the whole group and under
/// each consent half (`1000GenomesA` is patients 731234 to 733722, `1000GenomesB` 733723 to
/// 736211); so does each column's every value at once. Runs when `HPDS_GENOMIC_BENCH` names
/// the bench data directory.
#[test]
fn the_carrier_fast_path_equals_the_mask_path_on_every_categorical_value_of_g1kg() {
    let Some(bench) = bench_dir() else {
        eprintln!("HPDS_GENOMIC_BENCH is not set; skipping the g1kg carrier check");
        return;
    };
    let store = GenomicStore::open_with_key_file(
        &bench.join("g1kg/rust"),
        &bench.join("g1kg/pheno/encryption_key"),
        &GenomicOptions::default(),
    )
    .unwrap();
    let group = &store.groups()[0];
    let scopes = [
        ("all", PosBitset::full(group.count())),
        ("1000GenomesA", scope_of(&store, 731_234..=733_722)),
        ("1000GenomesB", scope_of(&store, 733_723..=736_211)),
    ];
    assert!(scopes.iter().all(|(_, s)| !s.is_empty()));
    let mut compared = 0u64;
    let mut values = 0u64;
    for n in 0..store.index().nodes().len() as u16 {
        let node = store.node(n);
        for entry in node.infos.iter().filter(|e| !e.continuous) {
            let block = store.info_block(n, entry.column).unwrap();
            let all_keys: Vec<usize> = (0..block.len()).collect();
            let mut all_variants = roaring::RoaringBitmap::new();
            for k in 0..block.len() {
                let variants = block.variants(k).unwrap();
                all_variants |= &variants;
                values += 1;
                for (name, scope) in &scopes {
                    let fast = carrier_mask(&store, n, entry.column, &[k], scope).unwrap();
                    let masks =
                        variant_mask(&store, n, &variants, scope, ReadPath::Cached).unwrap();
                    assert_eq!(
                        fast,
                        masks,
                        "node {}, column {}, value {:?}, scope {name}",
                        store.node_name(n),
                        store.index().columns()[usize::from(entry.column)],
                        block.key(k)
                    );
                    compared += 1;
                }
            }
            for (name, scope) in &scopes {
                let fast = carrier_mask(&store, n, entry.column, &all_keys, scope).unwrap();
                let masks =
                    variant_mask(&store, n, &all_variants, scope, ReadPath::Cached).unwrap();
                assert_eq!(
                    fast,
                    masks,
                    "node {}, every value, scope {name}",
                    store.node_name(n)
                );
                compared += 1;
            }
        }
    }
    eprintln!("g1kg: {values} categorical values, {compared} comparisons, all equal");
    assert!(values > 30_000, "{values} values");
}
