//! The BDC consent profile: the consent concepts a BioData Catalyst store carries beyond
//! `\_consents\`, derived from each patient's study and consent group.
//!
//! - `\_topmed_consents\` for the patients of TOPMed groups, valued with the patient's own
//!   study consent;
//! - `\_harmonized_consent\` for a fixed share of all patients, valued with the patient's own
//!   consent or, for a tenth of them, the other group of the same study;
//! - the `\DCC Harmonized data set\` concepts, held by that same subset only;
//! - `\_parent_consents\` for the patients of child studies, valued with the parent study's
//!   consent of the same group.
//!
//! With the edge flag the dataset also holds a block of hand-placed patients whose consent rows
//! break the regular pattern, so each edge of the authorization filter atoms has data to hit.

use crate::model::{
    CONSENT_GROUPS_PER_STUDY, CONSENTS_PATH, Concept, FIRST_GROUP_SHARE, Kind, Membership,
    NumericShape, Population, unit_from,
};
use crate::rng::{Source, mix};

/// Path of the genomic consent concept, valued `phsNNNNNN.cN`.
pub const TOPMED_CONSENTS_PATH: &str = "\\_topmed_consents\\";
/// Path of the harmonized data consent concept, valued `phsNNNNNN.cN`.
pub const HARMONIZED_CONSENT_PATH: &str = "\\_harmonized_consent\\";
/// Path of the parent study consent concept, valued `phsNNNNNN.cN`.
pub const PARENT_CONSENTS_PATH: &str = "\\_parent_consents\\";
/// Root of the harmonized phenotype concepts; a filter on any path below it makes the query
/// service add `\_harmonized_consent\` to the authorization filters.
pub const HARMONIZED_DATASET_ROOT: &str = "\\DCC Harmonized data set\\";
/// Path of the edge concept only hand-placed patients hold.
pub const UNCONSENTED_PATH: &str = "\\edge\\bdc\\unconsented\\";

const HARMONIZED_SHARE: f64 = 0.35;
const SIBLING_SHARE: f64 = 0.10;
const CHILD_STRIDE: u32 = 4;
const CHILD_OFFSET: u32 = 3;
const SUBSET_SALT: u64 = 0xBDC0_0001_0000_0000;
const SIBLING_SALT: u64 = 0xBDC0_0002_0000_0000;
const PRESENT_SALT: u64 = 0xBDC0_0003_0000_0000;
const VALUE_SALT: u64 = 0xBDC0_0004_0000_0000;

/// Which BDC concept a table entry is and so how its rows are drawn.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum BdcRule {
    /// `\_topmed_consents\`.
    TopmedConsent,
    /// `\_harmonized_consent\`.
    HarmonizedConsent,
    /// `\_parent_consents\`.
    ParentConsent,
    /// The harmonized phenotype concept at this index of [`harmonized_concepts`].
    Harmonized(usize),
    /// A concept only the hand-placed edge patients hold.
    HandPlaced,
}

/// Which profile the generator writes.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Profile {
    /// The plain dataset: no BDC concepts.
    #[default]
    Plain,
    /// The BDC consent concepts; `edge` adds the hand-placed patients and their concept.
    Bdc {
        /// Whether to add the hand-placed edge patients.
        edge: bool,
    },
}

/// How a harmonized phenotype concept draws its values.
#[derive(Clone, Copy, Debug)]
pub enum HarmonizedValues {
    /// One of these labels, uniformly.
    Labels(&'static [&'static str]),
    /// A number from this distribution.
    Number(&'static NumericShape),
}

/// One concept below `\DCC Harmonized data set\`.
#[derive(Clone, Copy, Debug)]
pub struct HarmonizedConcept {
    /// Backslash delimited path.
    pub path: &'static str,
    /// Value distribution.
    pub values: HarmonizedValues,
    /// Share of the harmonized subset that has the concept.
    pub coverage: f64,
}

static AGE: NumericShape = NumericShape::Integer { lo: 18, hi: 90 };
static BMI: NumericShape = NumericShape::Normal {
    mean: 28.0,
    sd: 6.0,
    decimals: 1,
};
static SYSTOLIC: NumericShape = NumericShape::Integer { lo: 90, hi: 190 };
static LDL: NumericShape = NumericShape::Normal {
    mean: 120.0,
    sd: 30.0,
    decimals: 1,
};

static HARMONIZED: [HarmonizedConcept; 8] = [
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\demographic_variables\\annotated_sex\\",
        values: HarmonizedValues::Labels(&["Female", "Male"]),
        coverage: 0.95,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\demographic_variables\\race_us\\",
        values: HarmonizedValues::Labels(&["Asian", "Black", "White", "Other", "Multiple"]),
        coverage: 0.90,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\demographic_variables\\hispanic_or_latino\\",
        values: HarmonizedValues::Labels(&["Hispanic or Latino", "Not Hispanic or Latino"]),
        coverage: 0.90,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\demographic_variables\\age_at_index\\",
        values: HarmonizedValues::Number(&AGE),
        coverage: 0.85,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\baseline_common_covariates\\bmi_baseline\\",
        values: HarmonizedValues::Number(&BMI),
        coverage: 0.70,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\baseline_common_covariates\\current_smoker_baseline\\",
        values: HarmonizedValues::Labels(&["Yes", "No"]),
        coverage: 0.60,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\blood_pressure\\bp_systolic_baseline\\",
        values: HarmonizedValues::Number(&SYSTOLIC),
        coverage: 0.65,
    },
    HarmonizedConcept {
        path: "\\DCC Harmonized data set\\lipids\\ldl_baseline\\",
        values: HarmonizedValues::Number(&LDL),
        coverage: 0.55,
    },
];

/// The harmonized phenotype concepts, in table order.
pub fn harmonized_concepts() -> &'static [HarmonizedConcept] {
    &HARMONIZED
}

/// Whether a consent group is a TOPMed group: the groups whose study index and group index sum
/// to an even number, so every study has exactly one.
pub fn is_topmed_group(study: u32, group: u32) -> bool {
    (study + group).is_multiple_of(2)
}

/// The parent of a child study, `None` for a study that has no parent. Studies 3, 7, 11 and so
/// on are children of the study three places before them.
pub fn parent_study(study: u32) -> Option<u32> {
    (study % CHILD_STRIDE == CHILD_OFFSET).then(|| study - CHILD_OFFSET)
}

/// Whether the patient with this ordinal is in the harmonized subset.
pub fn in_harmonized_subset(seed: u64, ordinal: u64) -> bool {
    unit_from(mix(seed ^ mix(ordinal ^ SUBSET_SALT))) < HARMONIZED_SHARE
}

/// The consent a patient of the harmonized subset holds in `\_harmonized_consent\`: their own,
/// or for a tenth of them the other group of their study.
pub fn harmonized_consent(seed: u64, ordinal: u64, member: Membership) -> Membership {
    if unit_from(mix(seed ^ mix(ordinal ^ SIBLING_SALT))) < SIBLING_SHARE {
        Membership {
            study: member.study,
            group: (member.group + 1) % CONSENT_GROUPS_PER_STUDY,
        }
    } else {
        member
    }
}

/// The consent a patient holds in the consent concept `rule`, or `None` when they have no row.
/// `rule` must be one of the three consent rules.
pub fn consent_of(
    rule: BdcRule,
    seed: u64,
    ordinal: u64,
    member: Membership,
) -> Option<Membership> {
    match rule {
        BdcRule::TopmedConsent => is_topmed_group(member.study, member.group).then_some(member),
        BdcRule::HarmonizedConsent => {
            in_harmonized_subset(seed, ordinal).then(|| harmonized_consent(seed, ordinal, member))
        }
        BdcRule::ParentConsent => parent_study(member.study).map(|study| Membership {
            study,
            group: member.group,
        }),
        BdcRule::Harmonized(_) | BdcRule::HandPlaced => None,
    }
}

/// The random stream of harmonized concept `index` for a patient, or `None` when the patient is
/// outside the subset or does not have the concept.
pub fn harmonized_stream(seed: u64, ordinal: u64, index: usize) -> Option<Source> {
    if !in_harmonized_subset(seed, ordinal) {
        return None;
    }
    let key = mix(seed ^ mix(ordinal ^ mix(index as u64 ^ PRESENT_SALT)));
    if unit_from(key) >= HARMONIZED[index].coverage {
        return None;
    }
    Some(Source::new(mix(key ^ VALUE_SALT)))
}

/// Expected share of all patients that have the concept `rule` describes.
pub fn expected_coverage(rule: BdcRule, population: &Population) -> f64 {
    let by_group = |keep: &dyn Fn(u32, u32) -> bool| -> f64 {
        (0..population.studies())
            .map(|study| {
                let first = if keep(study, 0) {
                    FIRST_GROUP_SHARE
                } else {
                    0.0
                };
                let second = if keep(study, 1) {
                    1.0 - FIRST_GROUP_SHARE
                } else {
                    0.0
                };
                population.share(study) * (first + second)
            })
            .sum()
    };
    match rule {
        BdcRule::TopmedConsent => by_group(&is_topmed_group),
        BdcRule::HarmonizedConsent => HARMONIZED_SHARE,
        BdcRule::ParentConsent => by_group(&|study, _| parent_study(study).is_some()),
        BdcRule::Harmonized(index) => HARMONIZED_SHARE * HARMONIZED[index].coverage,
        BdcRule::HandPlaced => 0.0,
    }
}

/// The BDC concepts appended after the plain table: the three consent concepts, the harmonized
/// phenotype concepts, and with `edge` the hand-placed concept.
pub fn concepts(edge: bool) -> Vec<Concept> {
    let make = |path: &str, kind| Concept {
        path: path.to_string(),
        kind,
        home: 0,
        home_coverage: 1.0,
        away_coverage: 1.0,
        repeated: false,
        timestamped: false,
    };
    let mut table = vec![
        make(TOPMED_CONSENTS_PATH, Kind::Bdc(BdcRule::TopmedConsent)),
        make(
            HARMONIZED_CONSENT_PATH,
            Kind::Bdc(BdcRule::HarmonizedConsent),
        ),
        make(PARENT_CONSENTS_PATH, Kind::Bdc(BdcRule::ParentConsent)),
    ];
    for (index, concept) in HARMONIZED.iter().enumerate() {
        table.push(make(concept.path, Kind::Bdc(BdcRule::Harmonized(index))));
    }
    if edge {
        table.push(make(UNCONSENTED_PATH, Kind::Bdc(BdcRule::HandPlaced)));
    }
    table
}

/// The value of a hand-placed row.
#[derive(Clone, Debug, Eq, PartialEq)]
pub enum ExtraValue {
    /// A text value.
    Text(String),
    /// A numeric value, written as given.
    Number(&'static str),
}

/// One hand-placed row.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ExtraRow {
    /// Patient ID.
    pub patient: u32,
    /// Concept path, one of the table's.
    pub path: &'static str,
    /// The value.
    pub value: ExtraValue,
}

/// How many hand-placed patients [`edge_rows`] writes, with IDs `first` to `first + 9`.
pub const EDGE_PATIENTS: u32 = 10;

/// The hand-placed rows, for patients `first` to `first + 9`, in patient order:
///
/// - `first`: two `\_consents\` values and a `\_topmed_consents\` value;
/// - `+1`: a `\_consents\` value no other patient holds;
/// - `+2`: `\_topmed_consents\` with no `\_consents\`;
/// - `+3`: a `\_topmed_consents\` value from another study than the `\_consents\` value;
/// - `+4`: `\_harmonized_consent\` with no harmonized phenotype rows;
/// - `+5`: harmonized phenotype rows with no `\_harmonized_consent\`;
/// - `+6`: a `\_parent_consents\` value that is not the parent of their `\_consents\` study;
/// - `+7`: a `\_consents\` value that differs from `phs000001.c1` only in case;
/// - `+8`: every consent concept and two harmonized phenotype rows;
/// - `+9`: a row of the hand-placed concept and no consent at all.
///
/// The consent values name studies 1 to 5, so the dataset needs at least five studies.
pub fn edge_rows(first: u32) -> Vec<ExtraRow> {
    let text = |offset: u32, path: &'static str, value: &str| ExtraRow {
        patient: first + offset,
        path,
        value: ExtraValue::Text(value.to_string()),
    };
    let number = |offset: u32, path: &'static str, value: &'static str| ExtraRow {
        patient: first + offset,
        path,
        value: ExtraValue::Number(value),
    };
    let sex = HARMONIZED[0].path;
    let age = HARMONIZED[3].path;
    vec![
        text(0, CONSENTS_PATH, "phs000001.c1"),
        text(0, CONSENTS_PATH, "phs000002.c2"),
        text(0, TOPMED_CONSENTS_PATH, "phs000001.c1"),
        text(1, CONSENTS_PATH, "phs000099.c1"),
        text(2, TOPMED_CONSENTS_PATH, "phs000001.c1"),
        text(3, CONSENTS_PATH, "phs000001.c1"),
        text(3, TOPMED_CONSENTS_PATH, "phs000002.c1"),
        text(4, HARMONIZED_CONSENT_PATH, "phs000001.c1"),
        text(5, CONSENTS_PATH, "phs000001.c1"),
        text(5, sex, "Female"),
        number(5, age, "57"),
        text(6, CONSENTS_PATH, "phs000005.c1"),
        text(6, PARENT_CONSENTS_PATH, "phs000001.c1"),
        text(7, CONSENTS_PATH, "PHS000001.C1"),
        text(8, CONSENTS_PATH, "phs000003.c1"),
        text(8, TOPMED_CONSENTS_PATH, "phs000003.c1"),
        text(8, HARMONIZED_CONSENT_PATH, "phs000003.c1"),
        text(8, PARENT_CONSENTS_PATH, "phs000003.c1"),
        text(8, sex, "Male"),
        number(8, age, "41"),
        number(9, UNCONSENTED_PATH, "7"),
    ]
}
