//! Deterministic generators of PIC-SURE test data: `allConcepts.csv` phenotype datasets and
//! genomic VCF datasets with their index files and matching phenotype CSVs.

pub mod bdc;
pub mod edge;
pub mod generate;
pub mod genomic;
pub mod model;
pub mod rng;

#[cfg(test)]
mod bdc_tests;
#[cfg(test)]
mod tests;
