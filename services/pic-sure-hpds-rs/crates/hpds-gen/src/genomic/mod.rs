//! Deterministic generator of genomic datasets: per group and chromosome VCFs, the index files
//! the Java `SplitChromosomeVcfLoader` reads, and a matching phenotype CSV per group.
//!
//! Frequency, annotation and layout distributions are fitted to the 1000 Genomes VCFs by
//! `tools/fit_profile.py` and held in [`profile_data`]. Each group's VCFs list only the sites
//! present in that group, and patients are disjoint across groups.

pub mod bgzf;
pub mod cohort;
pub mod edge;
pub mod estimate;
pub mod profile;
pub mod profile_data;
pub mod sites;
pub mod vcf;
pub mod writer;

#[cfg(test)]
mod tests;

pub use cohort::{Cohort, Group};
pub use writer::{Output, write_dataset};

/// The chromosome names every generated VCF uses, in file order.
pub const CHROMOSOMES: [&str; 23] = [
    "chr1", "chr2", "chr3", "chr4", "chr5", "chr6", "chr7", "chr8", "chr9", "chr10", "chr11",
    "chr12", "chr13", "chr14", "chr15", "chr16", "chr17", "chr18", "chr19", "chr20", "chr21",
    "chr22", "chrX",
];

/// GRCh38 chromosome lengths in bases, in the order of [`CHROMOSOMES`].
pub const CHROMOSOME_LENGTHS: [u32; 23] = [
    248_956_422,
    242_193_529,
    198_295_559,
    190_214_555,
    181_538_259,
    170_805_979,
    159_345_973,
    145_138_636,
    138_394_717,
    133_797_422,
    135_086_622,
    133_275_309,
    114_364_328,
    107_043_718,
    101_991_189,
    90_338_345,
    83_257_441,
    80_373_285,
    58_617_616,
    64_444_167,
    46_709_983,
    50_818_468,
    156_040_895,
];

/// A generation preset: how many groups and samples, and how many variant sites.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Preset {
    /// About 40 samples in two groups with hand-placed sites for every ETL and query rule.
    Edge,
    /// 10,000 samples in four groups and 2 million sites.
    Gdev,
    /// 50,000 samples in ten groups and 8 million sites.
    Gbench,
}

/// The size of a randomly generated dataset.
#[derive(Clone, Debug)]
pub struct Shape {
    /// Prefix of the group names, which also names the dataset: `gdev` gives `gdevA`, `gdevB`.
    pub name: String,
    /// Samples in each group, in group order.
    pub group_samples: Vec<u32>,
    /// Distinct sites over all chromosomes, shared out by the fitted chromosome shares.
    pub variants: u64,
}

impl Shape {
    /// The shape of a random preset, or `None` for [`Preset::Edge`], which is hand-placed.
    pub fn of(preset: Preset) -> Option<Shape> {
        match preset {
            Preset::Edge => None,
            Preset::Gdev => Some(Shape {
                name: "gdev".to_string(),
                group_samples: vec![4_000, 3_000, 2_000, 1_000],
                variants: 2_000_000,
            }),
            Preset::Gbench => Some(Shape {
                name: "gbench".to_string(),
                group_samples: vec![
                    12_000, 9_000, 7_000, 6_000, 5_000, 4_000, 3_000, 2_000, 1_500, 500,
                ],
                variants: 8_000_000,
            }),
        }
    }

    /// Total samples over all groups.
    pub fn samples(&self) -> u64 {
        self.group_samples.iter().map(|n| u64::from(*n)).sum()
    }

    /// Checks that the shape can be generated.
    ///
    /// # Errors
    /// Returns a message for an empty name, no groups, a group without samples, more than 26
    /// groups, or sample counts whose patient ids would pass `i32::MAX`.
    pub fn validate(&self) -> Result<(), String> {
        if self.name.is_empty() || self.name.contains('-') {
            return Err("the dataset name must be non-empty and hold no '-'".to_string());
        }
        if self.group_samples.is_empty() || self.group_samples.len() > 26 {
            return Err("need between 1 and 26 groups".to_string());
        }
        if self.group_samples.contains(&0) {
            return Err("every group needs at least one sample".to_string());
        }
        if cohort::PATIENT_BASE as u64 + self.samples() >= i32::MAX as u64 {
            return Err("patient ids would reach i32::MAX".to_string());
        }
        Ok(())
    }
}
