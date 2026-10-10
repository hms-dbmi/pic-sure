//! Per-item work that fans out over the rayon pool only when there is enough of it.
//!
//! Engine computations run on tokio's blocking threads, not on rayon workers, so even a
//! one-item parallel map hands its work to the pool, wakes workers and blocks until they
//! finish, and the woken workers then spin before they sleep again. A query touches only a
//! few concepts or clauses at each step, so short inputs are mapped on the calling thread.

use rayon::prelude::*;

/// Fewest items [`map`] hands to the rayon pool.
pub(crate) const MIN_PARALLEL_ITEMS: usize = 4;

/// `f` applied to each of `items`, in order: on the calling thread when there are fewer
/// than [`MIN_PARALLEL_ITEMS`], on the rayon pool otherwise.
pub(crate) fn map<T, R, F>(items: &[T], f: F) -> Vec<R>
where
    T: Sync,
    R: Send,
    F: Fn(&T) -> R + Sync + Send,
{
    if items.len() < MIN_PARALLEL_ITEMS {
        items.iter().map(f).collect()
    } else {
        items.par_iter().map(f).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn short_and_long_inputs_map_in_order() {
        for len in [0, 1, MIN_PARALLEL_ITEMS - 1, MIN_PARALLEL_ITEMS, 100] {
            let items: Vec<usize> = (0..len).collect();
            assert_eq!(
                map(&items, |i| i * 2),
                (0..len).map(|i| i * 2).collect::<Vec<_>>()
            );
        }
    }

    #[test]
    fn short_inputs_stay_on_the_calling_thread() {
        let caller = std::thread::current().id();
        let items = vec![(); MIN_PARALLEL_ITEMS - 1];
        assert!(
            map(&items, |()| std::thread::current().id())
                .iter()
                .all(|id| *id == caller)
        );
    }
}
