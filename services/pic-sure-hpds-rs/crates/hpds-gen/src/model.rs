//! The concept table: every concept's path, value type, coverage and value distribution.

use crate::rng::{Source, mix};

/// Path of the per-patient consent group concept.
pub const CONSENTS_PATH: &str = "\\_consents\\";
/// Path of the per-patient study consent concept used by the open aggregate lookup.
pub const STUDIES_CONSENTS_PATH: &str = "\\_studies_consents\\";
/// Path of the per-patient study concept.
pub const STUDIES_PATH: &str = "\\_studies\\";

/// The study and consent group pairs that every patient is assigned to exactly one of.
pub const CONSENT_GROUPS: [(&str, &str); 6] = [
    ("phs000001", "phs000001.c1"),
    ("phs000001", "phs000001.c2"),
    ("phs000002", "phs000002.c1"),
    ("phs000003", "phs000003.c1"),
    ("phs000003", "phs000003.c2"),
    ("phs000004", "phs000004.c1"),
];

/// How a numeric concept draws its values.
#[derive(Clone, Debug)]
pub enum NumericShape {
    /// Whole numbers drawn uniformly from `[lo, hi]`.
    Integer { lo: i64, hi: i64 },
    /// Normally distributed values rounded to `decimals` places, which may be negative.
    Normal { mean: f64, sd: f64, decimals: u32 },
    /// Uniformly distributed values in `[lo, hi]` rounded to `decimals` places.
    Uniform { lo: f64, hi: f64, decimals: u32 },
}

/// The value type of a concept and how its values are drawn.
#[derive(Clone, Debug)]
pub enum Kind {
    /// Numeric concept.
    Numeric(NumericShape),
    /// Categorical concept with `cardinality` distinct values named `cat_<n>`.
    Categorical { cardinality: u32 },
    /// Special concept: every patient gets exactly one value, chosen by the generator.
    PerPatient,
}

/// One concept of the generated dataset.
#[derive(Clone, Debug)]
pub struct Concept {
    /// Backslash delimited path as written to the CSV.
    pub path: String,
    /// Value type and distribution.
    pub kind: Kind,
    /// Probability that a given patient has at least one observation of this concept.
    pub coverage: f64,
    /// Whether this concept repeats per patient with distinct timestamps.
    pub repeated: bool,
    /// Whether observations carry a timestamp.
    pub timestamped: bool,
}

impl Concept {
    /// Average observations per covered patient.
    pub fn observations_per_patient(&self) -> f64 {
        if self.repeated { 4.0 } else { 1.0 }
    }
}

const SPECIAL_COUNT: usize = 3;

fn dense_count(generic: usize) -> usize {
    (generic * 3 / 100).clamp(4, 400).min(generic)
}

fn build_path(index: usize, generic: usize, rng: &mut Source) -> String {
    let studies = (generic / 200).max(1);
    let per_study = generic.div_ceil(studies);
    let study = index / per_study + 1;
    let category = (index % per_study) / 20 + 1;
    let deep = rng.chance(0.10);
    let mut path = format!("\\study_{study}\\category_{category}\\");
    if deep {
        let extra = rng.range(1, 3);
        for level in 0..extra {
            path.push_str(&format!("group_{}_{}\\", level + 1, rng.range(1, 6)));
        }
    }
    path.push_str(&format!("variable_{index}\\"));
    path
}

fn numeric_shape(rng: &mut Source) -> NumericShape {
    match rng.range(0, 2) {
        0 => {
            let lo = if rng.chance(0.2) {
                -rng.range(1, 200)
            } else {
                rng.range(0, 50)
            };
            let hi = lo + rng.range(5, 5000);
            NumericShape::Integer { lo, hi }
        }
        1 => {
            let mean = if rng.chance(0.3) {
                -(rng.unit() * 50.0)
            } else {
                rng.unit() * 200.0
            };
            NumericShape::Normal {
                mean,
                sd: 1.0 + rng.unit() * 40.0,
                decimals: rng.range(1, 4) as u32,
            }
        }
        _ => {
            let lo = if rng.chance(0.25) {
                -(rng.unit() * 100.0)
            } else {
                rng.unit() * 10.0
            };
            NumericShape::Uniform {
                lo,
                hi: lo + 1.0 + rng.unit() * 500.0,
                decimals: rng.range(1, 3) as u32,
            }
        }
    }
}

fn cardinality(rng: &mut Source) -> u32 {
    let roll = rng.unit();
    if roll < 0.80 {
        rng.range(2, 8) as u32
    } else if roll < 0.97 {
        rng.range(9, 40) as u32
    } else {
        rng.range(100, 200) as u32
    }
}

const SPARSE_CAP: f64 = 0.5;

/// Builds the concept table for a dataset, fully determined by `seed`.
///
/// The first three entries are the per-patient special concepts. Of the remaining concepts, a
/// few hundred are dense (covering most patients) and the rest follow a power-law tail whose
/// scale is solved so the expected observation count approaches `observations`.
pub fn build_concepts(
    seed: u64,
    concepts: usize,
    patients: u64,
    observations: u64,
) -> Vec<Concept> {
    let mut rng = Source::new(mix(seed ^ 0xC0FF_EE00_0000_0001));
    let generic = concepts.saturating_sub(SPECIAL_COUNT);
    let mut table = Vec::with_capacity(concepts);
    for path in [CONSENTS_PATH, STUDIES_CONSENTS_PATH, STUDIES_PATH] {
        table.push(Concept {
            path: path.to_string(),
            kind: Kind::PerPatient,
            coverage: 1.0,
            repeated: false,
            timestamped: false,
        });
    }
    let dense = dense_count(generic);
    let mut dense_per_patient = 0.0;
    let mut sparse_shape = Vec::new();
    let mut sparse_weight = Vec::new();
    for index in 0..generic {
        let numeric = rng.chance(0.6);
        let kind = if numeric {
            Kind::Numeric(numeric_shape(&mut rng))
        } else {
            Kind::Categorical {
                cardinality: cardinality(&mut rng),
            }
        };
        let repeated = rng.chance(0.05);
        let timestamped = repeated || rng.chance(0.5);
        let path = build_path(index, generic, &mut rng);
        let mut concept = Concept {
            path,
            kind,
            coverage: 0.0,
            repeated,
            timestamped,
        };
        if index < dense {
            concept.coverage = 0.5 + rng.unit() * 0.45;
            dense_per_patient += concept.coverage * concept.observations_per_patient();
        } else {
            let rank = (index - dense) as f64;
            sparse_shape.push((1.0 + rank / 10.0).powf(-0.8));
            sparse_weight.push(concept.observations_per_patient());
        }
        table.push(concept);
    }
    let target_per_patient = observations as f64 / patients.max(1) as f64;
    let budget = (target_per_patient - SPECIAL_COUNT as f64 - dense_per_patient).max(0.0);
    let scale = solve_sparse_scale(&sparse_shape, &sparse_weight, budget);
    for (offset, concept) in table[SPECIAL_COUNT + dense..].iter_mut().enumerate() {
        concept.coverage = (sparse_shape[offset] * scale).min(SPARSE_CAP);
    }
    table
}

/// Finds the multiplier on the sparse power-law shape whose capped expected observations per
/// patient equal `budget`, by bisection.
fn solve_sparse_scale(shape: &[f64], weight: &[f64], budget: f64) -> f64 {
    let total_at = |scale: f64| -> f64 {
        shape
            .iter()
            .zip(weight)
            .map(|(s, w)| (s * scale).min(SPARSE_CAP) * w)
            .sum()
    };
    let (mut lo, mut hi) = (0.0f64, 1.0e6f64);
    for _ in 0..60 {
        let mid = (lo + hi) / 2.0;
        if total_at(mid) < budget {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    (lo + hi) / 2.0
}
