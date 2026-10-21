//! What a multi-concept read leaves alive once it returns. This file holds one test, so it
//! runs as a process of its own with no other test pinning `crossbeam-epoch` beside it.

mod common;

use hpds_query::{
    ConsentContract, Engine, PartitionOptions, PhenotypicClause, PhenotypicFilter,
    PhenotypicFilterType, Query, ResultType, StoreOptions,
};

/// Reads measured; see the test's doc comment for the threshold.
const READS: usize = 200;

/// The most reads out of [`READS`] that may leave a decoded entry alive when they return.
const MOST_LEFT_ALIVE: usize = 10;

/// Concept paths `\c\00\` to `\c\39\` in partitions `p1` (patients 1 to 10), `p2` (11 to
/// 20) and `p3` (21 to 30), patient `p` holding concept `c` when `(p + c) % 7` is 0.
fn prefix_store() -> common::PartitionedDir {
    let concepts: Vec<String> = (0..40).map(|c| format!("\\c\\{c:02}\\")).collect();
    let rows = |patients: std::ops::RangeInclusive<u32>| -> Vec<common::Row<'_>> {
        let mut rows = Vec::new();
        for p in patients {
            for (c, path) in concepts.iter().enumerate() {
                if (p as usize + c).is_multiple_of(7) {
                    rows.push((p, path.as_str(), Some(f64::from(p)), None));
                }
            }
        }
        rows
    };
    common::partitioned_rows(&[
        ("p1", rows(1..=10)),
        ("p2", rows(11..=20)),
        ("p3", rows(21..=30)),
    ])
}

/// An engine with no block cache and 20,000-byte chunks, so every read loads every concept,
/// in several chunks, through `moka`'s shared loads.
fn engine(dir: &common::PartitionedDir, share: f64) -> Engine {
    dir.open(&PartitionOptions {
        require_consents: false,
        contract: ConsentContract::Harmonization,
        narrow_view_share: share,
        prefix_read_bytes: 20_000,
        prefix_read_budget_bytes: u64::MAX,
        store: StoreOptions {
            cache_bytes: 0,
            ..StoreOptions::default()
        },
    })
    .unwrap()
}

/// `moka` hands the copy each load shares with concurrent loaders to `crossbeam-epoch`,
/// which frees it only once its bag is pushed and the global epoch has advanced twice. A
/// read's last loads would leave such copies alive after it returns, until some later
/// collection; the read lets reclamation run after each chunk so they do not outlive it.
///
/// The test makes 200 single reads, one at a time on a dedicated thread, in a process with no
/// other test, and counts the reads that return with any decoded entry still alive. Without
/// the read's reclamation, 41, 46 and 50 of 200 reads did, a rate near 0.22; at that rate the
/// chance of 10 or fewer in 200 is negligible (below one in a billion), so losing the
/// reclamation always fails the test. With it, three runs of 200 left nothing alive in any
/// read, so allowing 10 is not a margin a working build comes near. The threshold catches
/// the loss of reclamation as a whole, not finer changes such as one collection instead of
/// four, which left 0 to 4 reads of 200 in the same measurement and make no practical
/// difference.
#[test]
fn reads_leave_no_decoded_entry_alive_when_they_return() {
    let dir = prefix_store();
    let query = Query {
        phenotypic_clause: Some(PhenotypicClause::Filter(PhenotypicFilter::new(
            PhenotypicFilterType::AnyRecordOf,
            "\\c\\",
        ))),
        ..Query::with_consents(ResultType::Patients, ["p1", "p2", "p3"])
    };
    let left_alive = std::thread::spawn(move || {
        let mut left_alive = 0;
        for read in 0..READS {
            let engine = engine(&dir, if read % 2 == 0 { 0.0 } else { 2.0 });
            assert!(!engine.patients(&query).unwrap().is_empty());
            if engine.partitions().live_entries() > 0 {
                left_alive += 1;
            }
        }
        left_alive
    })
    .join()
    .unwrap();
    assert!(
        left_alive <= MOST_LEFT_ALIVE,
        "{left_alive} of {READS} reads left a decoded entry alive when they returned"
    );
}
