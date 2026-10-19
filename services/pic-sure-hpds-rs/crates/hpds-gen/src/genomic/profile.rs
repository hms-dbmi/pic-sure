//! Samplers built from the fitted tables of [`profile_data`](super::profile_data).

use std::sync::OnceLock;

use super::profile_data::*;
use crate::rng::Source;

/// Severity of each consequence term, as the 1000 Genomes annotation pairs them.
const SEVERITY: [(&str, &str); 18] = [
    ("TFBS_ablation", "MODERATE"),
    ("frameshift_variant", "HIGH"),
    ("incomplete_terminal_codon_variant", "LOW"),
    ("inframe_deletion", "MODERATE"),
    ("inframe_insertion", "MODERATE"),
    ("missense_variant", "MODERATE"),
    ("protein_altering_variant", "MODERATE"),
    ("splice_acceptor_variant", "HIGH"),
    ("splice_donor_5th_base_variant", "LOW"),
    ("splice_donor_region_variant", "LOW"),
    ("splice_donor_variant", "HIGH"),
    ("splice_polypyrimidine_tract_variant", "LOW"),
    ("splice_region_variant", "LOW"),
    ("start_lost", "HIGH"),
    ("stop_gained", "HIGH"),
    ("stop_lost", "HIGH"),
    ("stop_retained_variant", "LOW"),
    ("synonymous_variant", "LOW"),
];

/// The three frequency texts of the annotation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FrequencyText {
    /// Below 1% in gnomAD.
    Rare,
    /// 1% or more in gnomAD.
    Common,
    /// Not in gnomAD.
    Novel,
}

impl FrequencyText {
    /// The text as it appears in the INFO field.
    pub fn as_str(self) -> &'static str {
        match self {
            FrequencyText::Rare => "Rare",
            FrequencyText::Common => "Common",
            FrequencyText::Novel => "Novel",
        }
    }
}

/// The kind of change a site makes.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum VariantClass {
    /// One base replaced by another.
    Snv,
    /// Bases added after the reference base.
    Insertion,
    /// Bases removed after the first reference base.
    Deletion,
}

impl VariantClass {
    /// The class as it appears in the INFO field.
    pub fn as_str(self) -> &'static str {
        match self {
            VariantClass::Snv => "SNV",
            VariantClass::Insertion => "insertion",
            VariantClass::Deletion => "deletion",
        }
    }
}

/// A cumulative-count sampler over a fixed list of weights.
#[derive(Clone, Debug)]
pub struct Weighted {
    cumulative: Vec<u64>,
}

impl Weighted {
    /// Builds a sampler whose option `i` has weight `weights[i]`.
    pub fn new(weights: &[u64]) -> Self {
        let mut total = 0;
        let cumulative = weights
            .iter()
            .map(|w| {
                total += w;
                total
            })
            .collect();
        Self { cumulative }
    }

    /// Draws an option index with probability proportional to its weight.
    pub fn draw(&self, rng: &mut Source) -> usize {
        let total = *self.cumulative.last().unwrap_or(&0);
        let target = rng.bits() % total.max(1);
        self.cumulative
            .partition_point(|c| *c <= target)
            .min(self.cumulative.len() - 1)
    }
}

/// One consequence and class combination.
#[derive(Clone, Copy, Debug)]
pub struct ConsequenceChoice {
    /// The Sequence Ontology term.
    pub consequence: &'static str,
    /// Its severity.
    pub severity: &'static str,
    /// The class of variant that carries it.
    pub class: VariantClass,
}

/// A frequency and the carrier fraction drawn with it.
#[derive(Clone, Copy, Debug)]
pub struct FrequencyDraw {
    /// Which text the site gets.
    pub text: FrequencyText,
    /// The gnomAD frequency, `-10` for a novel variant.
    pub gnomad: f64,
    /// The share of samples that carry the alternate allele, zero for a record nobody carries.
    pub carrier_fraction: f64,
}

/// Every sampler the site stream needs, built once from the fitted tables.
pub struct Profile {
    consequences: Weighted,
    consequence_choices: Vec<ConsequenceChoice>,
    text: Weighted,
    rare: Weighted,
    common: Weighted,
    gap: Weighted,
    gene_size: Weighted,
    snv_reference: Weighted,
    snv_alternate: [Weighted; 4],
    insertion: Weighted,
    deletion: Weighted,
    mean_gap: f64,
}

static PROFILE: OnceLock<Profile> = OnceLock::new();

/// The profile fitted to the 1000 Genomes files.
pub fn fitted() -> &'static Profile {
    PROFILE.get_or_init(Profile::build)
}

/// Interpolates a quantile row at level `u` in `[0, 1]`.
fn invert(row: &[f64], u: f64) -> f64 {
    let position = u * (row.len() - 1) as f64;
    let low = (position as usize).min(row.len() - 1);
    let high = (low + 1).min(row.len() - 1);
    let t = position - low as f64;
    row[low] + (row[high] - row[low]) * t
}

fn gap_range(bin: usize) -> (u64, u64) {
    if bin == 0 {
        (0, 0)
    } else {
        (1 << (bin - 1), (1 << bin) - 1)
    }
}

impl Profile {
    fn build() -> Self {
        let choices: Vec<ConsequenceChoice> = CONSEQUENCE_CLASS
            .iter()
            .map(|(term, class, _)| ConsequenceChoice {
                consequence: term,
                severity: SEVERITY
                    .iter()
                    .find(|(t, _)| t == term)
                    .map(|(_, s)| *s)
                    .unwrap_or("LOW"),
                class: match *class {
                    "insertion" => VariantClass::Insertion,
                    "deletion" => VariantClass::Deletion,
                    _ => VariantClass::Snv,
                },
            })
            .collect();
        let weights: Vec<u64> = CONSEQUENCE_CLASS.iter().map(|(_, _, n)| *n).collect();
        let mut rare = vec![RARE_ZERO_COUNT];
        rare.extend_from_slice(&RARE_COUNTS);
        let mut gap_counts = GAP_COUNTS;
        gap_counts[0] = gap_counts[0].saturating_sub(SAME_SITE_COUNTS[0]);
        let gap = Weighted::new(&gap_counts);
        let mut mean_total = 0.0;
        let mut count_total = 0.0;
        for (bin, count) in gap_counts.iter().enumerate() {
            let (lo, hi) = gap_range(bin);
            mean_total += *count as f64 * (lo + hi) as f64 / 2.0;
            count_total += *count as f64;
        }
        Self {
            consequences: Weighted::new(&weights),
            consequence_choices: choices,
            text: Weighted::new(&TEXT_COUNTS),
            rare: Weighted::new(&rare),
            common: Weighted::new(&COMMON_COUNTS),
            gap,
            gene_size: Weighted::new(&GENE_SIZE_COUNTS),
            snv_reference: Weighted::new(&SNV_COUNTS.map(|row| row.iter().sum())),
            snv_alternate: SNV_COUNTS.map(|row| Weighted::new(&row)),
            insertion: Weighted::new(&INSERTION_LENGTH_COUNTS),
            deletion: Weighted::new(&DELETION_LENGTH_COUNTS),
            mean_gap: mean_total / count_total,
        }
    }

    /// Mean gap between consecutive positions in the fitted files, in bases.
    pub fn mean_gap(&self) -> f64 {
        self.mean_gap
    }

    /// Draws a consequence with its severity and class.
    pub fn consequence(&self, rng: &mut Source) -> ConsequenceChoice {
        self.consequence_choices[self.consequences.draw(rng)]
    }

    /// Draws a gap to the next position in bases, before scaling.
    pub fn gap(&self, rng: &mut Source) -> u64 {
        let bin = self.gap.draw(rng);
        let (lo, hi) = gap_range(bin);
        lo + rng.bits() % (hi - lo + 1)
    }

    /// Draws how many sites a gene holds.
    pub fn gene_size(&self, rng: &mut Source) -> u64 {
        let bin = self.gene_size.draw(rng);
        (1u64 << bin) + rng.bits() % (1u64 << bin)
    }

    /// Draws a reference base index (A, C, G, T) for a substitution.
    pub fn snv_reference(&self, rng: &mut Source) -> usize {
        self.snv_reference.draw(rng)
    }

    /// Draws the alternate base index of a substitution from `reference`.
    pub fn snv_alternate(&self, reference: usize, rng: &mut Source) -> usize {
        self.snv_alternate[reference].draw(rng)
    }

    /// Draws how many bases an insertion adds.
    pub fn insertion_length(&self, rng: &mut Source) -> usize {
        let bin = self.insertion.draw(rng);
        if bin == INSERTION_LENGTH_COUNTS.len() - 1 {
            bin + 1 + (rng.bits() % 10) as usize
        } else {
            bin + 1
        }
    }

    /// Draws how many bases a deletion removes.
    pub fn deletion_length(&self, rng: &mut Source) -> usize {
        let bin = self.deletion.draw(rng);
        if bin == DELETION_LENGTH_COUNTS.len() - 1 {
            bin + 1 + (rng.bits() % 10) as usize
        } else {
            bin + 1
        }
    }

    /// Draws a frequency text, a gnomAD frequency and the carrier fraction that goes with them.
    pub fn frequency(&self, rng: &mut Source) -> FrequencyDraw {
        let text = match self.text.draw(rng) {
            0 => FrequencyText::Rare,
            1 => FrequencyText::Common,
            _ => FrequencyText::Novel,
        };
        let level = rng.unit();
        match text {
            FrequencyText::Rare => {
                let bin = self.rare.draw(rng);
                if bin == 0 {
                    FrequencyDraw {
                        text,
                        gnomad: 0.0,
                        carrier_fraction: invert(&RARE_ZERO_CARRIER_QUANTILES, level),
                    }
                } else {
                    let bin = bin - 1;
                    let exponent =
                        RARE_EDGES[bin] + (RARE_EDGES[bin + 1] - RARE_EDGES[bin]) * rng.unit();
                    FrequencyDraw {
                        text,
                        gnomad: libm::pow(10.0, exponent),
                        carrier_fraction: invert(&RARE_CARRIER_QUANTILES[bin], level),
                    }
                }
            }
            FrequencyText::Common => {
                let bin = self.common.draw(rng);
                let exponent =
                    COMMON_EDGES[bin] + (COMMON_EDGES[bin + 1] - COMMON_EDGES[bin]) * rng.unit();
                FrequencyDraw {
                    text,
                    gnomad: libm::pow(10.0, exponent).clamp(0.01, 1.0),
                    carrier_fraction: invert(&COMMON_CARRIER_QUANTILES[bin], level),
                }
            }
            FrequencyText::Novel => FrequencyDraw {
                text,
                gnomad: -10.0,
                carrier_fraction: invert(&NOVEL_CARRIER_QUANTILES, level),
            },
        }
    }
}

/// The chance that a carrier call is `1|1`, for a site whose carrier fraction is `fraction`.
pub fn hom_share(fraction: f64) -> f64 {
    if fraction <= 0.0 {
        return 0.0;
    }
    let x = libm::log10(fraction);
    let mut bin = 0;
    while bin < HOM_SHARE.len() - 1 && x >= CARRIER_EDGES[bin + 1] {
        bin += 1;
    }
    HOM_SHARE[bin]
}

/// Formats a frequency like R's four significant digit `%g`: `1.97e-05`, `0.0001379`, `0`.
pub fn format_frequency(value: f64) -> String {
    if value == 0.0 {
        return "0".to_string();
    }
    let scientific = format!("{:.3e}", value);
    let (mantissa, exponent) = scientific.split_once('e').unwrap_or((&scientific, "0"));
    let exponent: i32 = exponent.parse().unwrap_or(0);
    if !(-4..4).contains(&exponent) {
        let mantissa = trim_zeros(mantissa);
        let sign = if exponent < 0 { '-' } else { '+' };
        format!("{mantissa}e{sign}{:02}", exponent.abs())
    } else {
        let decimals = (3 - exponent).max(0) as usize;
        trim_zeros(&format!("{value:.decimals$}")).to_string()
    }
}

fn trim_zeros(text: &str) -> &str {
    if text.contains('.') {
        text.trim_end_matches('0').trim_end_matches('.')
    } else {
        text
    }
}
