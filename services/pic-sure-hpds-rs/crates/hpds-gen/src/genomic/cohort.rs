//! Groups, samples and patients, the index file, and the matching phenotype CSV.

use std::collections::HashSet;
use std::io::{self, Write};

use super::Shape;
use crate::rng::{Source, mix};

/// The patient id of the first sample; sample `n` (one-based) is patient `PATIENT_BASE + n`.
pub const PATIENT_BASE: u32 = 500_000;

/// The header of the index file, as the all-in-one's `vcfIndex.tsv` has it.
pub const INDEX_HEADER: &str = "vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\tsample_relationship\trelated_sample_ids";

/// One index entry: a sample name and the patient it stands for.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct IndexedSample {
    /// Sample name as the VCF header spells it (or would, for a missing sample).
    pub sample: String,
    /// Patient id.
    pub patient: u32,
}

/// One consent group: its samples, its index order and the patients its phenotype file holds.
#[derive(Clone, Debug)]
pub struct Group {
    /// Group name, also the phenotype partition and the `\_consents\` value.
    pub name: String,
    /// Sample names in VCF column order, unindexed ones included.
    pub header_samples: Vec<String>,
    /// The index file's sample list, in index order.
    pub indexed: Vec<IndexedSample>,
    /// Patients that get phenotype rows, ascending.
    pub phenotype_patients: Vec<u32>,
}

impl Group {
    /// The sample name list of the index file's `sample_ids` column.
    pub fn index_samples(&self) -> String {
        self.indexed
            .iter()
            .map(|s| s.sample.as_str())
            .collect::<Vec<_>>()
            .join(",")
    }

    /// The patient id list of the index file's `patient_ids` column.
    pub fn index_patients(&self) -> String {
        self.indexed
            .iter()
            .map(|s| s.patient.to_string())
            .collect::<Vec<_>>()
            .join(",")
    }

    /// Writes one index line for a VCF at `path` of `chromosome` (the index's own name for it,
    /// such as `1`), marked gzipped or not.
    ///
    /// # Errors
    /// Returns the writer's error.
    pub fn write_index_line(
        &self,
        out: &mut impl Write,
        path: &str,
        chromosome: &str,
        gzipped: bool,
    ) -> io::Result<()> {
        writeln!(
            out,
            "{path}\t{chromosome}\t1\t{}\t\"{}\"\t\"{}\"\t\t",
            u8::from(gzipped),
            self.index_samples(),
            self.index_patients()
        )
    }
}

/// All groups of a dataset; patients are disjoint across groups.
#[derive(Clone, Debug)]
pub struct Cohort {
    /// The groups in order.
    pub groups: Vec<Group>,
}

/// The letter that ends a group name: `A` for group 0.
pub fn group_letter(index: usize) -> char {
    (b'A' + index as u8) as char
}

fn shuffled(mut order: Vec<IndexedSample>, seed: u64) -> Vec<IndexedSample> {
    let mut rng = Source::new(mix(seed ^ 0x1D3C_0000_0000_0000));
    for i in (1..order.len()).rev() {
        let j = (rng.bits() % (i as u64 + 1)) as usize;
        order.swap(i, j);
    }
    order
}

impl Cohort {
    /// Lays out a random dataset: contiguous sample numbers over the groups, sample names from
    /// the dataset name, and an index order shuffled from `seed`.
    pub fn random(shape: &Shape, seed: u64) -> Self {
        let prefix = shape.name.to_uppercase();
        let mut next = 1u32;
        let groups = shape
            .group_samples
            .iter()
            .enumerate()
            .map(|(g, count)| {
                let numbers = next..next + count;
                next += count;
                let header_samples: Vec<String> =
                    numbers.clone().map(|n| format!("{prefix}{n:06}")).collect();
                let indexed: Vec<IndexedSample> = numbers
                    .map(|n| IndexedSample {
                        sample: format!("{prefix}{n:06}"),
                        patient: PATIENT_BASE + n,
                    })
                    .collect();
                let mut phenotype_patients: Vec<u32> = indexed.iter().map(|s| s.patient).collect();
                phenotype_patients.sort_unstable();
                Group {
                    name: format!("{}{}", shape.name, group_letter(g)),
                    header_samples,
                    indexed: shuffled(indexed, mix(seed ^ g as u64)),
                    phenotype_patients,
                }
            })
            .collect();
        Self { groups }
    }
}

const SEXES: [&str; 2] = ["Female", "Male"];
const POPULATIONS: [&str; 5] = ["AFR", "AMR", "EAS", "EUR", "SAS"];

/// Writes a group's phenotype CSV rows (no header, the harmonization loader's format): the
/// consent value, for patients with genotypes the same value as their `\_topmed_consents\`,
/// then sex, age, superpopulation, and for most patients a BMI and a smoker flag.
///
/// # Errors
/// Returns the writer's error.
pub fn write_phenotypes(group: &Group, seed: u64, out: &mut impl Write) -> io::Result<()> {
    let genotyped: HashSet<u32> = group.indexed.iter().map(|s| s.patient).collect();
    for patient in &group.phenotype_patients {
        let mut rng = Source::new(mix(seed ^ mix(0x9E7_0000_0000_0000 ^ u64::from(*patient))));
        let sex = SEXES[(rng.bits() & 1) as usize];
        let age = (52.0 + 16.0 * rng.normal()).round().clamp(18.0, 95.0) as i64;
        let population = POPULATIONS[(rng.bits() % 5) as usize];
        let has_bmi = rng.chance(0.85);
        let bmi = (27.0 + 5.0 * rng.normal()).clamp(15.0, 55.0);
        let smoker = rng.chance(0.28);
        let row = |out: &mut dyn Write, path: &str, number: &str, text: &str| {
            writeln!(
                out,
                "\"{patient}\",\"{path}\",\"{number}\",\"{text}\",\"0\""
            )
        };
        row(out, "\\_consents\\", "", &group.name)?;
        if genotyped.contains(patient) {
            row(out, "\\_topmed_consents\\", "", &group.name)?;
        }
        row(out, "\\gen\\demographics\\sex\\", "", sex)?;
        row(out, "\\gen\\demographics\\age\\", &age.to_string(), "")?;
        row(
            out,
            "\\gen\\demographics\\superpopulation\\",
            "",
            population,
        )?;
        if has_bmi {
            row(out, "\\gen\\measures\\bmi\\", &format!("{bmi:.1}"), "")?;
        }
        row(
            out,
            "\\gen\\behavior\\smoker\\",
            "",
            if smoker { "TRUE" } else { "FALSE" },
        )?;
    }
    Ok(())
}
