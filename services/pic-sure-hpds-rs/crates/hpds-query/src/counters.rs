//! Operational counters of the block cache and the cohort guard, cheap enough to keep on
//! every lookup: relaxed atomic adds, striped across cache lines where many threads count at
//! once. They hold counts, bytes and seconds only, never a patient, concept or consent.

use std::cell::Cell;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::Duration;

/// Cells of a [`StripedCounter`]. Threads spread over them, so concurrent adds rarely touch
/// the same cache line.
const STRIPES: usize = 16;

/// One counter cell alone on its cache line (128 bytes covers the adjacent-line prefetch of
/// x86 and the 128-byte lines of Apple silicon).
#[derive(Debug, Default)]
#[repr(align(128))]
struct Cell128(AtomicU64);

/// A monotonic counter whose adds from different threads land on different cache lines.
/// Reading it sums the cells, so a read is not atomic with respect to concurrent adds; each
/// add is counted exactly once.
#[derive(Debug, Default)]
pub(crate) struct StripedCounter {
    cells: [Cell128; STRIPES],
}

static NEXT_STRIPE: AtomicUsize = AtomicUsize::new(0);

thread_local! {
    static STRIPE: Cell<usize> = const { Cell::new(usize::MAX) };
}

/// The calling thread's cell index, assigned round robin on first use.
fn stripe() -> usize {
    STRIPE.with(|slot| {
        let mut index = slot.get();
        if index == usize::MAX {
            index = NEXT_STRIPE.fetch_add(1, Ordering::Relaxed) % STRIPES;
            slot.set(index);
        }
        index
    })
}

impl StripedCounter {
    /// Adds `n`.
    pub(crate) fn add(&self, n: u64) {
        self.cells[stripe()].0.fetch_add(n, Ordering::Relaxed);
    }

    /// The sum of every add so far.
    pub(crate) fn get(&self) -> u64 {
        self.cells
            .iter()
            .map(|cell| cell.0.load(Ordering::Relaxed))
            .fold(0, u64::wrapping_add)
    }
}

/// The block cache's event counters, shared with its eviction listener.
#[derive(Debug, Default)]
pub(crate) struct BlockCacheCounters {
    pub(crate) hits: StripedCounter,
    pub(crate) misses: StripedCounter,
    pub(crate) evictions: AtomicU64,
    pub(crate) loads: AtomicU64,
    pub(crate) load_failures: AtomicU64,
    pub(crate) load_nanos: AtomicU64,
}

impl BlockCacheCounters {
    /// Counts one block read from the store, taking `elapsed`: a load when `succeeded`, a
    /// failed load otherwise.
    pub(crate) fn record_load(&self, elapsed: Duration, succeeded: bool) {
        let counter = if succeeded {
            &self.loads
        } else {
            &self.load_failures
        };
        counter.fetch_add(1, Ordering::Relaxed);
        self.load_nanos.fetch_add(
            u64::try_from(elapsed.as_nanos()).unwrap_or(u64::MAX),
            Ordering::Relaxed,
        );
    }
}

/// What the block cache has done and holds. Counts are totals since the store was opened.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct BlockCacheStats {
    /// Lookups that found the concept's block cached.
    pub hits: u64,
    /// Lookups that did not, each followed by a read or by waiting on another reader's.
    pub misses: u64,
    /// Entries dropped to keep the cache within its byte bound.
    pub evictions: u64,
    /// Blocks read from the store, authenticated and decoded into the cache.
    pub loads: u64,
    /// Block reads that failed: unreadable, not authentic, undecodable or disagreeing with
    /// the index. Nothing is cached for them.
    pub load_failures: u64,
    /// Seconds spent on every block read, failed ones included, summed over the threads
    /// that ran them.
    pub load_seconds: f64,
    /// Entries held: one per concept, its block and any partition view.
    pub entries: u64,
    /// Summed weight of the entries held, blocks and views, in bytes.
    pub bytes: u64,
    /// The byte bound.
    pub capacity: u64,
    /// Entries that also hold a partition view.
    pub views: u64,
    /// Bytes of the partition views held, counted within `bytes`.
    pub view_bytes: u64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn striped_adds_from_many_threads_are_each_counted_once() {
        let counter = StripedCounter::default();
        std::thread::scope(|scope| {
            for _ in 0..32 {
                scope.spawn(|| {
                    for _ in 0..1000 {
                        counter.add(1);
                    }
                });
            }
        });
        assert_eq!(counter.get(), 32_000);
    }
}
