/// Small deterministic generator (SplitMix64) so corpora are stable across dependency upgrades.
#[derive(Debug, Clone)]
pub struct Rng {
    state: u64,
}

impl Rng {
    /// Creates a generator from a seed.
    pub fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// Returns the next 64 random bits.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// Returns a value in `0..bound`. A zero bound yields zero.
    pub fn below(&mut self, bound: usize) -> usize {
        if bound == 0 {
            return 0;
        }
        (self.next_u64() % bound as u64) as usize
    }

    /// Returns a value in `lo..=hi`.
    pub fn between(&mut self, lo: usize, hi: usize) -> usize {
        lo + self.below(hi.saturating_sub(lo) + 1)
    }

    /// Returns a float in `[0, 1)`.
    pub fn unit(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }

    /// Picks one element, or `None` for an empty slice.
    pub fn pick<'a, T>(&mut self, items: &'a [T]) -> Option<&'a T> {
        if items.is_empty() {
            None
        } else {
            items.get(self.below(items.len()))
        }
    }

    /// Picks `count` distinct elements, clamped to the slice length.
    pub fn sample<T: Clone>(&mut self, items: &[T], count: usize) -> Vec<T> {
        let mut pool: Vec<T> = items.to_vec();
        let mut out = Vec::new();
        while out.len() < count && !pool.is_empty() {
            let index = self.below(pool.len());
            out.push(pool.swap_remove(index));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_seed_same_sequence() {
        let mut a = Rng::new(7);
        let mut b = Rng::new(7);
        for _ in 0..20 {
            assert_eq!(a.next_u64(), b.next_u64());
        }
    }

    #[test]
    fn sample_is_distinct_and_bounded() {
        let mut rng = Rng::new(1);
        let items: Vec<u32> = (0..10).collect();
        let picked = rng.sample(&items, 4);
        assert_eq!(picked.len(), 4);
        let mut sorted = picked.clone();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(sorted.len(), 4);
        assert_eq!(rng.sample(&items, 99).len(), 10);
    }

    #[test]
    fn unit_stays_in_range() {
        let mut rng = Rng::new(3);
        for _ in 0..1000 {
            let value = rng.unit();
            assert!((0.0..1.0).contains(&value));
        }
    }
}
