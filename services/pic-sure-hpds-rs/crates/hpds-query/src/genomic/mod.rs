//! Genomic queries over the Rust genomic store (`geno.index` and `geno.blocks` in
//! `HPDS_GENOMIC_DATA_DIRECTORY`), node by node as Java's `GenomicProcessorNodeImpl` answers
//! them, combined as the configured layout's parent processors combine nodes.
//!
//! - [`GenomicStore`] opens the store, holds the genomic block cache, the genomic read budget
//!   and the genomic rayon pool.
//! - The engine translates a query's `genomicFilters` as `QueryExecutor` does
//!   (`filters`), then narrows the phenotypic cohort to the patients the filters keep
//!   (`cohort`). COUNT, the cross counts, PATIENTS and DATAFRAME then run on the narrowed
//!   cohort.
//!
//! Genomic work runs on the genomic pool, entered from the request's blocking thread. Its
//! scans take permits from the genomic read budget on the pool's workers, one block at a time,
//! never in a frame that calls into rayon, so a genomic wait never parks a phenotype worker
//! and no cycle of waits can form.

mod cohort;
mod filters;
mod store;
#[cfg(test)]
mod tests;

pub(crate) use cohort::genomic_cohort;
pub(crate) use filters::GenomicFilters;
pub(crate) use store::on_genomic_worker;
pub use store::{
    DEFAULT_GENOMIC_CACHE_BYTES, DEFAULT_GENOMIC_READ_BUDGET_BYTES, GenomicCacheStats,
    GenomicOptions, GenomicStore,
};
