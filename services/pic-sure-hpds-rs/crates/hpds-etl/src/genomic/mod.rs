//! The genomic store: [`migrate`] builds one from a Java genomic store exported by
//! `ExportGenomicForMigration` ([`export`] reads that export), [`vcf_load`] builds one from
//! VCF index files and VCFs ([`vcf`] reads them, [`info_store`] converts INFO columns as
//! Java does), [`writer`] seals the nodes' blocks and writes `geno.blocks` and `geno.index`,
//! and [`dump`] writes a store in the canonical text form the Java `DumpGenomicEq` writes
//! for a Java store.

pub mod dump;
pub mod export;
pub mod info_store;
pub mod migrate;
pub mod vcf;
pub mod vcf_load;
pub mod writer;
