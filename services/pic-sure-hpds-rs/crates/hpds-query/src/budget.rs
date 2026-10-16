//! The process-wide budget of decoded bytes that reads of many concepts hold outside the block
//! cache: a weighted semaphore every `ANY_RECORD_OF` chunk takes before it loads and gives
//! back once it is merged.
//!
//! A reader takes one permit at a time and releases it before it asks for the next, and the
//! engine takes permits only on the thread that runs the query, never on a rayon worker
//! (see [`crate::cohort`]), so no thread waits for bytes while it holds some. That rules out
//! a cycle of readers each waiting on the others. A permit asks for at most the whole budget,
//! so a request larger than the budget waits for every other permit to end and then runs
//! alone.
//!
//! Grants are first come, first served. A request that finds others waiting, or too few
//! bytes free, takes a ticket and waits until it is the oldest waiter and its bytes fit; the
//! oldest waiter blocks every later one, even one that would fit, so a stream of small
//! requests cannot starve a large one. The ticket queue adds no wait for a thread that holds
//! a permit, since such a thread never asks again on the engine's paths, and the grant
//! that keeps a mistaken nested request from hanging skips the queue.

use std::cell::Cell;
use std::marker::PhantomData;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Condvar, Mutex, PoisonError};
use std::time::Instant;

/// What the prefix read budget holds and has done since the store was opened.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct PrefixReadStats {
    /// The budget in bytes.
    pub budget: u64,
    /// Bytes held by permits now.
    pub held: u64,
    /// The most bytes held at once.
    pub peak_held: u64,
    /// Permits granted.
    pub permits: u64,
    /// Permits that had to wait for bytes to be released.
    pub waits: u64,
    /// Seconds spent waiting, summed over the waiting threads.
    pub wait_seconds: f64,
}

thread_local! {
    static HELD_HERE: Cell<usize> = const { Cell::new(0) };
}

/// The bytes held and the ticket queue of a [`ByteBudget`], under its lock.
#[derive(Debug, Default)]
struct State {
    held: u64,
    next_ticket: u64,
    serving: u64,
}

impl State {
    /// True while some request holds a ticket and waits.
    fn queued(&self) -> bool {
        self.next_ticket != self.serving
    }
}

/// A weighted semaphore over bytes, granting in arrival order. See the module docs.
#[derive(Debug)]
pub(crate) struct ByteBudget {
    capacity: u64,
    state: Mutex<State>,
    released: Condvar,
    peak: AtomicU64,
    permits: AtomicU64,
    waits: AtomicU64,
    wait_nanos: AtomicU64,
    #[cfg(test)]
    releases: AtomicU64,
}

impl ByteBudget {
    /// A budget of `capacity` bytes, at least 1.
    pub(crate) fn new(capacity: u64) -> Self {
        ByteBudget {
            capacity: capacity.max(1),
            state: Mutex::new(State::default()),
            released: Condvar::new(),
            peak: AtomicU64::new(0),
            permits: AtomicU64::new(0),
            waits: AtomicU64::new(0),
            wait_nanos: AtomicU64::new(0),
            #[cfg(test)]
            releases: AtomicU64::new(0),
        }
    }

    /// A permit for `bytes`, capped at the whole budget, waiting until every earlier waiter
    /// has been granted and that many bytes are free. A request for 0 bytes returns at once
    /// and holds nothing.
    ///
    /// A thread that already holds a permit is granted the new one without waiting, since
    /// waiting there could wait on itself. The engine never asks twice on one thread; the
    /// grant keeps a mistake from hanging the service, and a debug build panics on it.
    pub(crate) fn acquire(&self, bytes: u64) -> BudgetPermit<'_> {
        let bytes = bytes.min(self.capacity);
        if bytes == 0 {
            return BudgetPermit {
                budget: self,
                bytes,
                _here: PhantomData,
            };
        }
        let nested = HELD_HERE.with(Cell::get) > 0;
        debug_assert!(
            !nested,
            "a thread holding a prefix read permit asked for another"
        );
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if !nested && (state.queued() || state.held + bytes > self.capacity) {
            self.waits.fetch_add(1, Ordering::Relaxed);
            let started = Instant::now();
            let ticket = state.next_ticket;
            state.next_ticket += 1;
            while state.serving != ticket || state.held + bytes > self.capacity {
                state = self
                    .released
                    .wait(state)
                    .unwrap_or_else(PoisonError::into_inner);
            }
            state.serving += 1;
            self.wait_nanos.fetch_add(
                u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX),
                Ordering::Relaxed,
            );
            if state.queued() {
                self.released.notify_all();
            }
        }
        state.held += bytes;
        self.peak.fetch_max(state.held, Ordering::Relaxed);
        drop(state);
        self.permits.fetch_add(1, Ordering::Relaxed);
        HELD_HERE.with(|count| count.set(count.get() + 1));
        BudgetPermit {
            budget: self,
            bytes,
            _here: PhantomData,
        }
    }

    /// The budget's capacity, what it holds and what it has done.
    pub(crate) fn stats(&self) -> PrefixReadStats {
        PrefixReadStats {
            budget: self.capacity,
            held: self
                .state
                .lock()
                .unwrap_or_else(PoisonError::into_inner)
                .held,
            peak_held: self.peak.load(Ordering::Relaxed),
            permits: self.permits.load(Ordering::Relaxed),
            waits: self.waits.load(Ordering::Relaxed),
            wait_seconds: self.wait_nanos.load(Ordering::Relaxed) as f64 / 1e9,
        }
    }
}

/// Bytes of a [`ByteBudget`] held until dropped, on the thread that took them: a permit is
/// not `Send`, so the per-thread count of held permits stays right.
#[derive(Debug)]
pub(crate) struct BudgetPermit<'a> {
    budget: &'a ByteBudget,
    bytes: u64,
    _here: PhantomData<*const ()>,
}

impl Drop for BudgetPermit<'_> {
    fn drop(&mut self) {
        if self.bytes == 0 {
            return;
        }
        HELD_HERE.with(|count| count.set(count.get().saturating_sub(1)));
        let mut state = self
            .budget
            .state
            .lock()
            .unwrap_or_else(PoisonError::into_inner);
        state.held -= self.bytes;
        let queued = state.queued();
        drop(state);
        #[cfg(test)]
        self.budget.releases.fetch_add(1, Ordering::SeqCst);
        if queued {
            self.budget.released.notify_all();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::AtomicBool;
    use std::time::Duration;

    #[test]
    fn a_request_above_the_budget_is_capped_and_granted() {
        let budget = ByteBudget::new(100);
        let permit = budget.acquire(1_000);
        assert_eq!(budget.stats().held, 100);
        drop(permit);
        assert_eq!(budget.stats().held, 0);
        assert_eq!(budget.acquire(0).bytes, 0);
        assert_eq!(budget.stats().permits, 1);
    }

    #[test]
    fn a_waiting_request_runs_once_bytes_are_released() {
        let budget = ByteBudget::new(100);
        let first = budget.acquire(70);
        let granted = AtomicBool::new(false);
        std::thread::scope(|scope| {
            let waiter = scope.spawn(|| {
                let _second = budget.acquire(70);
                granted.store(true, Ordering::SeqCst);
            });
            std::thread::sleep(Duration::from_millis(50));
            assert!(
                !granted.load(Ordering::SeqCst),
                "70 + 70 does not fit in 100"
            );
            drop(first);
            waiter.join().unwrap();
        });
        assert!(granted.load(Ordering::SeqCst));
        let stats = budget.stats();
        assert_eq!((stats.held, stats.peak_held, stats.waits), (0, 70, 1));
        assert!(stats.wait_seconds > 0.0);
    }

    #[test]
    fn a_large_request_behind_a_stream_of_small_ones_is_granted() {
        const STREAMS: u64 = 6;
        let budget = ByteBudget::new(100);
        let stop = AtomicBool::new(false);
        let deadline = Instant::now() + Duration::from_secs(3);
        let (before, after) = std::thread::scope(|scope| {
            for _ in 0..STREAMS {
                scope.spawn(|| {
                    while !stop.load(Ordering::SeqCst) && Instant::now() < deadline {
                        let _small = budget.acquire(10);
                        std::thread::sleep(Duration::from_micros(200));
                    }
                });
            }
            std::thread::sleep(Duration::from_millis(50));
            let before = budget.releases.load(Ordering::SeqCst);
            let large = budget.acquire(100);
            let after = budget.releases.load(Ordering::SeqCst);
            assert_eq!(budget.stats().held, 100, "the large request runs alone");
            drop(large);
            stop.store(true, Ordering::SeqCst);
            (before, after)
        });
        assert!(
            after - before <= 2 * STREAMS,
            "granted after {} releases; at most the {STREAMS} small permits held and \
             {STREAMS} queued ahead of it should go first",
            after - before
        );
        assert_eq!(budget.stats().held, 0);
    }

    #[test]
    fn the_oldest_waiter_blocks_later_ones_that_would_fit() {
        let budget = ByteBudget::new(100);
        let first = budget.acquire(60);
        let order = Mutex::new(Vec::new());
        let deadline = Instant::now() + Duration::from_secs(3);
        std::thread::scope(|scope| {
            let large = scope.spawn(|| {
                let _permit = budget.acquire(80);
                order.lock().unwrap().push("large");
            });
            while budget.stats().waits < 1 && Instant::now() < deadline {
                std::thread::yield_now();
            }
            let small = scope.spawn(|| {
                let _permit = budget.acquire(30);
                order.lock().unwrap().push("small");
            });
            while budget.stats().waits < 2 && Instant::now() < deadline {
                std::thread::yield_now();
            }
            std::thread::sleep(Duration::from_millis(30));
            assert!(
                order.lock().unwrap().is_empty(),
                "30 fits beside 60 but waits"
            );
            drop(first);
            large.join().unwrap();
            small.join().unwrap();
        });
        assert_eq!(*order.lock().unwrap(), ["large", "small"]);
    }

    #[test]
    fn many_threads_never_hold_more_than_the_budget() {
        let budget = ByteBudget::new(1_000);
        std::thread::scope(|scope| {
            for thread in 0..16u64 {
                let budget = &budget;
                scope.spawn(move || {
                    for round in 0..200u64 {
                        let _permit = budget.acquire(100 + (thread * 37 + round * 11) % 900);
                        std::hint::spin_loop();
                    }
                });
            }
        });
        let stats = budget.stats();
        assert_eq!(stats.held, 0);
        assert!(stats.peak_held <= 1_000);
        assert_eq!(stats.permits, 16 * 200);
    }
}
