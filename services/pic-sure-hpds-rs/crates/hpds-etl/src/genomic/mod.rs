//! The genomic store: [`migrate`] builds one from a Java genomic store exported by
//! `ExportGenomicForMigration` ([`export`] reads that export), [`writer`] seals the nodes'
//! blocks and writes `geno.blocks` and `geno.index`, and [`dump`] writes a store in the
//! canonical text form the Java `DumpGenomicEq` writes for a Java store.

pub mod dump;
pub mod export;
pub mod migrate;
pub mod writer;
