//! The concept table and the patient population: every concept's path, value type, coverage
//! and value distribution, and every patient's study and consent group.

use crate::bdc::{self, BdcRule};
use crate::rng::{Source, mix};

/// Path of the flat consent concept the authorization filters read, valued `phsNNNNNN.cN`.
pub const CONSENTS_PATH: &str = "\\_consents\\";
/// Root of the study consent tree the open aggregate fan-out searches for.
pub const STUDIES_CONSENTS_PATH: &str = "\\_studies_consents\\";
/// Path of the per-patient study concept, valued `phsNNNNNN`.
pub const STUDIES_PATH: &str = "\\_studies\\";

/// Consent groups per study, named `c1`, `c2` and so on.
pub const CONSENT_GROUPS_PER_STUDY: u32 = 2;

/// Rows every patient gets from the consent concepts: `\_consents\`, `\_studies\`, the
/// `\_studies_consents\` root, its study node and its consent node.
pub const CONSENT_ROWS_PER_PATIENT: f64 = 5.0;

/// Share of a study's patients in its first consent group; the rest are in the second.
pub(crate) const FIRST_GROUP_SHARE: f64 = 0.7;

/// Share of a patient's generic observations that come from concepts of their own study.
const OWN_STUDY_SHARE: f64 = 0.8;

/// Largest share of all patients a sparse concept may cover.
const SPARSE_CAP: f64 = 0.5;

/// Smallest share of all patients a sparse concept covers, before the 20 patient floor.
const SPARSE_FLOOR_SHARE: f64 = 1.0e-4;

/// Fewest patients a sparse concept is expected to cover.
const SPARSE_FLOOR_PATIENTS: f64 = 20.0;

/// Returns the dbGaP style accession of a zero-based study index, `phs000001` for study 0.
pub fn study_accession(study: u32) -> String {
    format!("phs{:06}", study + 1)
}

/// Returns the name of a zero-based consent group, `c1` for group 0.
pub fn consent_name(group: u32) -> String {
    format!("c{}", group + 1)
}

/// Returns the study consent string of a study and consent group, `phs000001.c1` for (0, 0).
pub fn study_consent(study: u32, group: u32) -> String {
    format!("{}.{}", study_accession(study), consent_name(group))
}

/// The study and consent group one patient belongs to.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct Membership {
    /// Zero-based study index.
    pub study: u32,
    /// Zero-based consent group within the study.
    pub group: u32,
}

/// How patients are spread over studies: unequal study sizes, two consent groups each.
#[derive(Clone, Debug)]
pub struct Population {
    shares: Vec<f64>,
    cumulative: Vec<f64>,
}

impl Population {
    /// Builds a population of `studies` studies whose sizes fall off as `1 / (index + 3)`, so
    /// the largest study is a few times the smallest. `studies` must be at least 1.
    pub fn new(studies: u32) -> Self {
        let raw: Vec<f64> = (0..studies).map(|s| 1.0 / (f64::from(s) + 3.0)).collect();
        let total: f64 = raw.iter().sum();
        let shares: Vec<f64> = raw.iter().map(|r| r / total).collect();
        let mut running = 0.0;
        let cumulative = shares
            .iter()
            .map(|share| {
                running += share;
                running
            })
            .collect();
        Self { shares, cumulative }
    }

    /// Number of studies.
    pub fn studies(&self) -> u32 {
        self.shares.len() as u32
    }

    /// Expected share of all patients that belong to `study`.
    pub fn share(&self, study: u32) -> f64 {
        self.shares[study as usize]
    }

    /// Assigns the patient with zero-based ordinal `ordinal` to a study and consent group,
    /// as a pure function of `seed` and `ordinal`.
    pub fn assign(&self, seed: u64, ordinal: u64) -> Membership {
        let first = mix(seed ^ mix(ordinal ^ 0x5EED_0C0D_E000_0000));
        let second = mix(first);
        let study_draw = unit_from(first);
        let study = self
            .cumulative
            .iter()
            .position(|edge| study_draw < *edge)
            .unwrap_or(self.shares.len() - 1) as u32;
        let group = if unit_from(second) < FIRST_GROUP_SHARE {
            0
        } else {
            1
        };
        Membership { study, group }
    }
}

pub(crate) fn unit_from(bits: u64) -> f64 {
    (bits >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
}

/// How a numeric concept draws its values.
#[derive(Clone, Debug)]
pub enum NumericShape {
    /// Whole numbers drawn uniformly from `[lo, hi]`.
    Integer { lo: i64, hi: i64 },
    /// Normally distributed values with mean `mean` and standard deviation `sd`, rounded to
    /// `decimals` places after the decimal point. The mean may be negative.
    Normal { mean: f64, sd: f64, decimals: u32 },
    /// Uniformly distributed values in `[lo, hi]`, rounded to `decimals` places after the
    /// decimal point.
    Uniform { lo: f64, hi: f64, decimals: u32 },
}

/// Which text a consent concept stores for a patient.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConsentValue {
    /// The study consent string, `phs000001.c1`.
    StudyConsent,
    /// The study accession, `phs000001`.
    Study,
}

/// Which patients carry a consent concept.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ConsentScope {
    /// Every patient.
    All,
    /// Patients of one study.
    Study(u32),
    /// Patients of one consent group of one study.
    StudyConsent { study: u32, group: u32 },
}

impl ConsentScope {
    /// Whether a patient with this membership carries the concept.
    pub fn includes(self, member: Membership) -> bool {
        match self {
            ConsentScope::All => true,
            ConsentScope::Study(study) => member.study == study,
            ConsentScope::StudyConsent { study, group } => {
                member.study == study && member.group == group
            }
        }
    }
}

/// The value type of a concept and how its values are drawn.
#[derive(Clone, Debug)]
pub enum Kind {
    /// Concept of the BDC consent profile, see [`crate::bdc`].
    Bdc(BdcRule),
    /// Numeric concept.
    Numeric(NumericShape),
    /// Categorical concept with `cardinality` distinct values named `cat_<n>`.
    Categorical { cardinality: u32 },
    /// Consent concept: one row per patient in `scope`, valued from the patient's consent group.
    ConsentGroup {
        value: ConsentValue,
        scope: ConsentScope,
    },
}

/// One concept of the generated dataset.
#[derive(Clone, Debug)]
pub struct Concept {
    /// Backslash delimited path as written to the CSV.
    pub path: String,
    /// Value type and distribution.
    pub kind: Kind,
    /// Zero-based study whose `\study_N\` subtree holds the concept.
    pub home: u32,
    /// Probability that a patient of the home study has the concept.
    pub home_coverage: f64,
    /// Probability that a patient of any other study has the concept.
    pub away_coverage: f64,
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

    /// Expected share of all patients that have this concept.
    pub fn expected_coverage(&self, population: &Population) -> f64 {
        match self.kind {
            Kind::ConsentGroup { scope, .. } => match scope {
                ConsentScope::All => 1.0,
                ConsentScope::Study(study) => population.share(study),
                ConsentScope::StudyConsent { study, group } => {
                    let first = if group == 0 {
                        FIRST_GROUP_SHARE
                    } else {
                        1.0 - FIRST_GROUP_SHARE
                    };
                    population.share(study) * first
                }
            },
            Kind::Bdc(rule) => bdc::expected_coverage(rule, population),
            _ => {
                let share = population.share(self.home);
                share * self.home_coverage + (1.0 - share) * self.away_coverage
            }
        }
    }
}

/// The size parameters the concept table is built from.
#[derive(Clone, Copy, Debug)]
pub struct TableShape {
    /// Number of patients.
    pub patients: u64,
    /// Number of concepts, consent concepts included.
    pub concepts: usize,
    /// Target number of observations.
    pub observations: u64,
    /// Number of studies.
    pub studies: u32,
    /// Number of dense categorical concepts with 1,000 to 10,000 values.
    pub high_cardinality: usize,
}

/// Number of consent concepts for `studies` studies: the flat `\_consents\` and `\_studies\`,
/// the `\_studies_consents\` root, and one node per study and per study consent group.
pub fn consent_concept_count(studies: u32) -> usize {
    3 + studies as usize * (1 + CONSENT_GROUPS_PER_STUDY as usize)
}

/// Builds the consent concepts.
///
/// `\_consents\` stays flat. `\_studies_consents\` is a tree like production: the root, then
/// `\<phs>\` and `\<phs>\<consent>\` below it, so the open aggregate fan-out (a search for
/// `\_studies_consents\` followed by one cross count per hit) gets one count per study and
/// per study consent. Every level stores the study consent string. The study and consent nodes
/// never store their own last path node as a value, so the LowRAM loader's variable rollup
/// cannot fold them into their parent.
fn consent_concepts(studies: u32) -> Vec<Concept> {
    let concept = |path: String, value, scope| Concept {
        path,
        kind: Kind::ConsentGroup { value, scope },
        home: 0,
        home_coverage: 1.0,
        away_coverage: 1.0,
        repeated: false,
        timestamped: false,
    };
    let mut table = vec![
        concept(
            CONSENTS_PATH.to_string(),
            ConsentValue::StudyConsent,
            ConsentScope::All,
        ),
        concept(
            STUDIES_PATH.to_string(),
            ConsentValue::Study,
            ConsentScope::All,
        ),
        concept(
            STUDIES_CONSENTS_PATH.to_string(),
            ConsentValue::StudyConsent,
            ConsentScope::All,
        ),
    ];
    for study in 0..studies {
        let study_path = format!("{STUDIES_CONSENTS_PATH}{}\\", study_accession(study));
        for group in 0..CONSENT_GROUPS_PER_STUDY {
            table.push(concept(
                format!("{study_path}{}\\", consent_name(group)),
                ConsentValue::StudyConsent,
                ConsentScope::StudyConsent { study, group },
            ));
        }
        table.push(concept(
            study_path,
            ConsentValue::StudyConsent,
            ConsentScope::Study(study),
        ));
    }
    table
}

fn dense_count(generic: usize) -> usize {
    (generic * 3 / 100).clamp(4, 400).min(generic)
}

fn build_path(index: usize, home: u32, local: usize, rng: &mut Source) -> String {
    let category = local / 20 + 1;
    let deep = rng.chance(0.10);
    let mut path = format!("\\study_{}\\category_{category}\\", home + 1);
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

#[derive(Clone, Copy, PartialEq)]
enum Tier {
    Global,
    Dense,
    Sparse,
}

struct Draft {
    tier: Tier,
    share: f64,
    weight: f64,
    base: f64,
    home_coverage: f64,
    coverage: f64,
}

fn reach(share: f64, ratio: f64) -> f64 {
    share + (1.0 - share) * ratio
}

fn bisect(mut lo: f64, mut hi: f64, below: impl Fn(f64) -> bool) -> f64 {
    for _ in 0..80 {
        let mid = (lo + hi) / 2.0;
        if below(mid) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    (lo + hi) / 2.0
}

/// Solves every generic concept's coverage.
///
/// Dense concepts (global ones on every study, the rest mostly on their home study) keep
/// their drawn coverage unless together they would use more than half the per-patient
/// budget, in which case they are scaled down to exactly half. Sparse concepts follow a power
/// law between a floor of `max(20 patients, 0.01%)` and a cap of 50% of all patients, scaled
/// so the expected total meets the budget. The away ratio, the chance of a concept on a
/// patient outside its home study relative to inside it, is solved so 80% of a patient's
/// generic observations come from their own study. The three solves depend on one another,
/// so they are iterated to a fixed point.
fn solve_coverage(drafts: &mut [Draft], studies: u32, patients: u64, budget: f64) -> f64 {
    let floor = (SPARSE_FLOOR_PATIENTS / patients.max(1) as f64).max(SPARSE_FLOOR_SHARE);
    let mut ratio = if studies > 1 {
        1.0 / (4.0 * f64::from(studies - 1))
    } else {
        1.0
    };
    let ratio_of = |tier: Tier, ratio: f64| if tier == Tier::Global { 1.0 } else { ratio };
    for _ in 0..12 {
        let dense_at_base: f64 = drafts
            .iter()
            .filter(|d| d.tier != Tier::Sparse)
            .map(|d| d.base * reach(d.share, ratio_of(d.tier, ratio)) * d.weight)
            .sum();
        let dense_scale = if dense_at_base > budget / 2.0 {
            budget / 2.0 / dense_at_base
        } else {
            1.0
        };
        let mut dense_total = 0.0;
        for draft in drafts.iter_mut().filter(|d| d.tier != Tier::Sparse) {
            draft.home_coverage = draft.base * dense_scale;
            draft.coverage = draft.home_coverage * reach(draft.share, ratio_of(draft.tier, ratio));
            dense_total += draft.coverage * draft.weight;
        }
        let sparse_budget = (budget - dense_total).max(0.0);
        let bounds = |d: &Draft| {
            let cap = SPARSE_CAP.min(reach(d.share, ratio));
            (floor.min(cap), cap)
        };
        let sparse_at = |scale: f64| -> f64 {
            drafts
                .iter()
                .filter(|d| d.tier == Tier::Sparse)
                .map(|d| {
                    let (lo, hi) = bounds(d);
                    (d.base * scale).clamp(lo, hi) * d.weight
                })
                .sum()
        };
        let scale = bisect(0.0, 1.0e7, |s| sparse_at(s) < sparse_budget);
        for draft in drafts.iter_mut().filter(|d| d.tier == Tier::Sparse) {
            let (lo, hi) = bounds(draft);
            draft.coverage = (draft.base * scale).clamp(lo, hi);
        }
        if studies > 1 {
            let own_share_at = |r: f64| -> f64 {
                let mut own = 0.0;
                let mut total = 0.0;
                for d in drafts.iter() {
                    let (home, coverage) = if d.tier == Tier::Sparse {
                        (d.coverage / reach(d.share, r), d.coverage)
                    } else {
                        let home = d.home_coverage;
                        (home, home * reach(d.share, ratio_of(d.tier, r)))
                    };
                    own += home * d.share * d.weight;
                    total += coverage * d.weight;
                }
                if total > 0.0 { own / total } else { 1.0 }
            };
            ratio = bisect(0.0, 1.0, |r| own_share_at(r) > OWN_STUDY_SHARE);
        }
    }
    ratio
}

/// Builds the concept table for a dataset, fully determined by `seed` and `shape`.
///
/// The consent concepts come first (see [`consent_concept_count`]). Every other concept
/// belongs to one study, round robin, so dense concepts are spread over all studies. About 3%
/// of them, at most 400, are dense: a tenth of those cover every study alike, the rest mostly
/// their home study, and the last `high_cardinality` of them are categorical with 1,000 to
/// 10,000 values. The remaining concepts follow a power-law tail. Coverage is solved so the
/// expected observation count matches `shape.observations`, see `solve_coverage`.
pub fn build_concepts(seed: u64, shape: &TableShape, population: &Population) -> Vec<Concept> {
    let mut rng = Source::new(mix(seed ^ 0xC0FF_EE00_0000_0001));
    let studies = shape.studies.max(1);
    let mut table = consent_concepts(studies);
    let generic = shape.concepts.saturating_sub(table.len());
    let dense = dense_count(generic);
    let global = (dense / 10).max(1).min(dense);
    let high_cardinality = shape.high_cardinality.min(dense - global);
    let mut drafts = Vec::with_capacity(generic);
    for index in 0..generic {
        let home = (index % studies as usize) as u32;
        let local = index / studies as usize;
        let tier = if index < global {
            Tier::Global
        } else if index < dense {
            Tier::Dense
        } else {
            Tier::Sparse
        };
        let kind = if index >= dense - high_cardinality && index < dense {
            Kind::Categorical {
                cardinality: rng.range(1_000, 10_000) as u32,
            }
        } else if rng.chance(0.6) {
            Kind::Numeric(numeric_shape(&mut rng))
        } else {
            Kind::Categorical {
                cardinality: cardinality(&mut rng),
            }
        };
        let repeated = rng.chance(0.05);
        let timestamped = repeated || rng.chance(0.5);
        let path = build_path(index, home, local, &mut rng);
        let base = if tier == Tier::Sparse {
            libm::pow(1.0 + (index - dense) as f64 / 10.0, -0.8)
        } else {
            0.5 + rng.unit() * 0.45
        };
        let concept = Concept {
            path,
            kind,
            home,
            home_coverage: 0.0,
            away_coverage: 0.0,
            repeated,
            timestamped,
        };
        drafts.push(Draft {
            tier,
            share: population.share(home),
            weight: concept.observations_per_patient(),
            base,
            home_coverage: 0.0,
            coverage: 0.0,
        });
        table.push(concept);
    }
    let target = shape.observations as f64 / shape.patients.max(1) as f64;
    let budget = (target - CONSENT_ROWS_PER_PATIENT).max(0.0);
    let ratio = solve_coverage(&mut drafts, studies, shape.patients, budget);
    let offset = table.len() - generic;
    for (concept, draft) in table[offset..].iter_mut().zip(&drafts) {
        let away_ratio = if draft.tier == Tier::Global {
            1.0
        } else {
            ratio
        };
        let home = if draft.tier == Tier::Sparse {
            draft.coverage / reach(draft.share, away_ratio)
        } else {
            draft.home_coverage
        };
        concept.home_coverage = home.min(1.0);
        concept.away_coverage = (home * away_ratio).min(1.0);
    }
    table
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table(patients: u64, concepts: usize, per_patient: u64, studies: u32) -> TableShape {
        TableShape {
            patients,
            concepts,
            observations: patients * per_patient,
            studies,
            high_cardinality: 0,
        }
    }

    #[test]
    fn dense_concepts_use_at_most_half_the_budget_and_span_every_study() {
        for (shape, studies) in [
            (table(1_000_000, 20_000, 250, 20), 20),
            (table(100_000, 10_000, 500, 10), 10),
            (table(10_000, 20_000, 250, 20), 20),
            (table(1_000, 200, 30, 4), 4),
        ] {
            let population = Population::new(studies);
            let concepts = build_concepts(1, &shape, &population);
            let consent = consent_concept_count(studies);
            let dense = dense_count(concepts.len() - consent);
            let dense_concepts = &concepts[consent..consent + dense];
            let per_patient: f64 = dense_concepts
                .iter()
                .map(|c| c.expected_coverage(&population) * c.observations_per_patient())
                .sum();
            let budget =
                shape.observations as f64 / shape.patients as f64 - CONSENT_ROWS_PER_PATIENT;
            assert!(
                per_patient <= budget / 2.0 + 1e-6,
                "{per_patient} of {budget}"
            );
            let homes: std::collections::HashSet<u32> =
                dense_concepts.iter().map(|c| c.home).collect();
            assert_eq!(homes.len(), studies as usize);
        }
    }

    #[test]
    fn every_sparse_concept_reaches_the_coverage_floor() {
        let shape = table(1_000_000, 20_000, 250, 20);
        let population = Population::new(20);
        for concept in build_concepts(1, &shape, &population) {
            let patients = concept.expected_coverage(&population) * 1_000_000.0;
            assert!(patients >= 99.0, "{} covers {patients}", concept.path);
        }
    }

    #[test]
    fn consent_values_never_match_their_last_node() {
        let concepts = consent_concepts(3);
        assert_eq!(concepts.len(), consent_concept_count(3));
        for concept in concepts {
            let Kind::ConsentGroup { value, scope } = concept.kind else {
                panic!("{} is not a consent concept", concept.path);
            };
            let last = concept
                .path
                .trim_end_matches('\\')
                .rsplit('\\')
                .next()
                .unwrap();
            for study in 0..3 {
                for group in 0..CONSENT_GROUPS_PER_STUDY {
                    let member = Membership { study, group };
                    if !scope.includes(member) {
                        continue;
                    }
                    let text = match value {
                        ConsentValue::Study => study_accession(study),
                        ConsentValue::StudyConsent => study_consent(study, group),
                    };
                    assert_ne!(text, last, "{} would roll up", concept.path);
                }
            }
        }
    }
}
