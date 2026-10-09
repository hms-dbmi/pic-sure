//! Small helpers over a seeded ChaCha8 stream so every draw is reproducible across platforms.

use rand_chacha::ChaCha8Rng;
use rand_chacha::rand_core::{Rng, SeedableRng};

/// Deterministic random source built on ChaCha8, whose output is fixed by the seed alone.
pub struct Source {
    inner: ChaCha8Rng,
}

/// Scrambles a 64-bit value so that nearby inputs give unrelated outputs (splitmix64 finalizer).
pub fn mix(mut x: u64) -> u64 {
    x = x.wrapping_add(0x9E37_79B9_7F4A_7C15);
    x = (x ^ (x >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    x = (x ^ (x >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    x ^ (x >> 31)
}

impl Source {
    /// Creates a source from a 64-bit seed.
    pub fn new(seed: u64) -> Self {
        Self {
            inner: ChaCha8Rng::seed_from_u64(seed),
        }
    }

    /// Returns the next 64 random bits.
    pub fn bits(&mut self) -> u64 {
        self.inner.next_u64()
    }

    /// Returns the next 32 random bits.
    pub fn bits32(&mut self) -> u32 {
        self.inner.next_u32()
    }

    /// Returns a uniform value in `[0, 1)` with 53 bits of precision.
    pub fn unit(&mut self) -> f64 {
        (self.bits() >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
    }

    /// Returns a uniform integer in `[lo, hi]`.
    pub fn range(&mut self, lo: i64, hi: i64) -> i64 {
        let span = (hi - lo) as u64 + 1;
        lo + (self.bits() % span) as i64
    }

    /// Returns true with probability `p`.
    pub fn chance(&mut self, p: f64) -> bool {
        self.unit() < p
    }

    /// Returns a standard normal sample using the Box-Muller transform.
    ///
    /// The transcendental functions come from `libm`, so the result is the same on every
    /// platform rather than depending on the system math library.
    pub fn normal(&mut self) -> f64 {
        let u1 = 1.0 - self.unit();
        let u2 = self.unit();
        libm::sqrt(-2.0 * libm::log(u1)) * libm::cos(std::f64::consts::TAU * u2)
    }

    /// Returns how many trials to skip before the next success of a Bernoulli process whose
    /// per-trial miss probability has natural log `log_miss`, or `None` when no success can
    /// occur (a zero probability).
    pub fn geometric_skip(&mut self, log_miss: f64) -> Option<f64> {
        let skip = libm::floor(libm::log(1.0 - self.unit()) / log_miss);
        skip.is_finite().then_some(skip)
    }
}
