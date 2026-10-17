//! The process-wide budget of decoded bytes that reads of many concepts hold outside the block
//! cache: a weighted semaphore every `ANY_RECORD_OF` chunk takes before it loads and gives
//! back once it is merged.
//!
//! A reader takes one permit at a time and releases it before it asks for the next, and the
//! engine takes permits only on the threads that run the query's clauses, never on a rayon
//! worker (see [`crate::cohort`]), so no thread waits for bytes while it holds some. That rules out
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

#[cfg(test)]
thread_local! {
    /// Permits released between the calling thread's last wait taking its ticket and its
    /// grant, counted under the lock.
    static RELEASES_WHILE_QUEUED: Cell<Option<u64>> = const { Cell::new(None) };
}

/// The bytes held and the ticket queue of a [`ByteBudget`], under its lock.
#[derive(Debug, Default)]
struct State {
    held: u64,
    next_ticket: u64,
    serving: u64,
    #[cfg(test)]
    releases: u64,
    #[cfg(test)]
    wakes: u64,
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
        self.grant(bytes, true)
            .expect("a request that may wait is always granted")
    }

    /// A permit for `bytes`, capped at the whole budget, when it is granted without waiting:
    /// no earlier request waits and that many bytes are free. `None` otherwise, having
    /// neither waited nor queued. A request for 0 bytes, and a nested request (see
    /// [`ByteBudget::acquire`]), are always granted.
    pub(crate) fn try_acquire(&self, bytes: u64) -> Option<BudgetPermit<'_>> {
        self.grant(bytes, false)
    }

    /// A permit for `bytes`, capped at the whole budget: granted at once when nothing waits
    /// and the bytes are free, otherwise queued and waited for when `wait` is set, and
    /// `None` when it is not.
    fn grant(&self, bytes: u64, wait: bool) -> Option<BudgetPermit<'_>> {
        let bytes = bytes.min(self.capacity);
        if bytes == 0 {
            return Some(BudgetPermit {
                budget: self,
                bytes,
                _here: PhantomData,
            });
        }
        let nested = HELD_HERE.with(Cell::get) > 0;
        debug_assert!(
            !nested,
            "a thread holding a prefix read permit asked for another"
        );
        debug_assert!(
            rayon::current_thread_index().is_none(),
            "a prefix read permit was asked for on a rayon worker"
        );
        let mut state = self.state.lock().unwrap_or_else(PoisonError::into_inner);
        if !nested && (state.queued() || !self.fits(&state, bytes)) {
            if !wait {
                return None;
            }
            self.waits.fetch_add(1, Ordering::Relaxed);
            let started = Instant::now();
            let ticket = state.next_ticket;
            state.next_ticket += 1;
            #[cfg(test)]
            let released_before = state.releases;
            while state.serving != ticket || !self.fits(&state, bytes) {
                state = self
                    .released
                    .wait(state)
                    .unwrap_or_else(PoisonError::into_inner);
                #[cfg(test)]
                {
                    state.wakes += 1;
                }
            }
            #[cfg(test)]
            RELEASES_WHILE_QUEUED.with(|count| count.set(Some(state.releases - released_before)));
            state.serving += 1;
            self.wait_nanos.fetch_add(
                u64::try_from(started.elapsed().as_nanos()).unwrap_or(u64::MAX),
                Ordering::Relaxed,
            );
            if state.queued() {
                self.released.notify_all();
            }
        }
        state.held = state.held.saturating_add(bytes);
        self.peak.fetch_max(state.held, Ordering::Relaxed);
        drop(state);
        self.permits.fetch_add(1, Ordering::Relaxed);
        HELD_HERE.with(|count| count.set(count.get() + 1));
        Some(BudgetPermit {
            budget: self,
            bytes,
            _here: PhantomData,
        })
    }

    /// True when `bytes` more fit beside what `state` holds.
    fn fits(&self, state: &State, bytes: u64) -> bool {
        state
            .held
            .checked_add(bytes)
            .is_some_and(|total| total <= self.capacity)
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

impl BudgetPermit<'_> {
    /// True when this permit holds at least as much of its budget as a request for `bytes`
    /// would be granted.
    pub(crate) fn covers(&self, bytes: u64) -> bool {
        bytes.min(self.budget.capacity) <= self.bytes
    }
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
        state.held = state.held.saturating_sub(self.bytes);
        #[cfg(test)]
        {
            state.releases += 1;
        }
        let queued = state.queued();
        drop(state);
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
    fn a_try_never_waits_nor_queues() {
        let budget = ByteBudget::new(100);
        let first = budget.try_acquire(70).expect("70 is free");
        assert!(first.covers(70) && first.covers(0) && !first.covers(71));
        let budget = &budget;
        std::thread::scope(|scope| {
            let elsewhere = |bytes| scope.spawn(move || budget.try_acquire(bytes).is_some());
            assert!(!elsewhere(40).join().unwrap(), "40 does not fit beside 70");
            assert!(elsewhere(0).join().unwrap());
            let waiter = scope.spawn(|| drop(budget.acquire(40)));
            while budget.stats().waits < 1 {
                std::thread::sleep(Duration::from_micros(50));
            }
            assert!(
                !elsewhere(10).join().unwrap(),
                "10 fits beside 70 but a request waits ahead of it"
            );
            drop(first);
            waiter.join().unwrap();
        });
        let whole = budget
            .try_acquire(1_000)
            .expect("capped at the whole budget");
        assert!(whole.covers(1_000));
        drop(whole);
        let stats = budget.stats();
        assert_eq!((stats.held, stats.permits, stats.waits), (0, 3, 1));
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
        let released = std::thread::scope(|scope| {
            for _ in 0..STREAMS {
                scope.spawn(|| {
                    while !stop.load(Ordering::SeqCst) && Instant::now() < deadline {
                        let _small = budget.acquire(10);
                        std::thread::sleep(Duration::from_micros(200));
                    }
                });
            }
            std::thread::sleep(Duration::from_millis(50));
            RELEASES_WHILE_QUEUED.with(|count| count.set(None));
            let large = budget.acquire(100);
            let released = RELEASES_WHILE_QUEUED.with(Cell::get);
            assert_eq!(budget.stats().held, 100, "the large request runs alone");
            drop(large);
            stop.store(true, Ordering::SeqCst);
            released
        });
        let released = released.expect("the large request waits behind the small ones");
        assert!(
            released <= STREAMS,
            "granted after {released} releases; each of the {STREAMS} small streams holds or \
             queues at most one permit ahead of it"
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
                std::thread::sleep(Duration::from_micros(50));
            }
            let small = scope.spawn(|| {
                let _permit = budget.acquire(30);
                order.lock().unwrap().push("small");
            });
            while budget.stats().waits < 2 && Instant::now() < deadline {
                std::thread::sleep(Duration::from_micros(50));
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

    /// The test holds 60 bytes for a stand-in holder, queues a large and then a small waiter,
    /// frees the 60 bytes by hand and wakes one waiter at a time, each wake seen through
    /// before the next, until the large one is granted. A small waiter woken before that
    /// grant has gone back to sleep, so only the wake-up that follows the grant can reach it.
    #[test]
    fn a_later_waiter_that_fits_beside_a_new_grant_is_granted_at_once() {
        let budget = ByteBudget::new(100);
        budget.state.lock().unwrap().held = 60;
        let small_granted = AtomicBool::new(false);
        let release_large = AtomicBool::new(false);
        let deadline = Instant::now() + Duration::from_secs(5);
        std::thread::scope(|scope| {
            scope.spawn(|| {
                let _large = budget.acquire(80);
                while !release_large.load(Ordering::SeqCst) {
                    std::thread::sleep(Duration::from_millis(1));
                }
            });
            while budget.stats().waits < 1 {
                std::thread::sleep(Duration::from_micros(50));
            }
            scope.spawn(|| {
                let _small = budget.acquire(10);
                small_granted.store(true, Ordering::SeqCst);
            });
            while budget.stats().waits < 2 {
                std::thread::sleep(Duration::from_micros(50));
            }
            let mut released = false;
            loop {
                let mut state = budget.state.lock().unwrap();
                if state.serving >= 1 || Instant::now() >= deadline {
                    break;
                }
                if !released {
                    state.held -= 60;
                    released = true;
                }
                let wakes = state.wakes;
                budget.released.notify_one();
                drop(state);
                while budget.state.lock().unwrap().wakes == wakes && Instant::now() < deadline {
                    std::thread::sleep(Duration::from_micros(50));
                }
            }
            assert!(
                budget.state.lock().unwrap().serving >= 1,
                "the large waiter is granted"
            );
            while !small_granted.load(Ordering::SeqCst) && Instant::now() < deadline {
                std::thread::sleep(Duration::from_millis(1));
            }
            let granted = small_granted.load(Ordering::SeqCst);
            release_large.store(true, Ordering::SeqCst);
            assert!(
                granted,
                "10 fits beside 80 once the large waiter is granted, and no release follows \
                 to wake the small one"
            );
        });
        assert_eq!(budget.stats().held, 0);
    }

    #[test]
    #[cfg(not(debug_assertions))]
    fn a_nested_request_is_granted_past_a_full_budget_and_the_queue() {
        let budget = ByteBudget::new(100);
        let outer = budget.acquire(100);
        std::thread::scope(|scope| {
            let waiter = scope.spawn(|| drop(budget.acquire(10)));
            while budget.stats().waits < 1 {
                std::thread::sleep(Duration::from_micros(50));
            }
            let inner = budget.acquire(50);
            assert_eq!(budget.stats().held, 150);
            drop(inner);
            drop(outer);
            waiter.join().unwrap();
        });
        let stats = budget.stats();
        assert_eq!((stats.held, stats.permits, stats.waits), (0, 3, 1));
    }

    #[test]
    #[cfg(debug_assertions)]
    #[should_panic(expected = "asked for another")]
    fn a_nested_request_panics_in_a_debug_build() {
        let budget = ByteBudget::new(100);
        let _outer = budget.acquire(10);
        let _inner = budget.acquire(10);
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
