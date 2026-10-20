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
mod excerpt;
mod filters;
mod listing;
mod store;
#[cfg(test)]
mod tests;
mod variants;

pub(crate) use cohort::{CheckedFilters, genomic_cohort};
pub use excerpt::{
    DEFAULT_MAX_EXCERPT_BYTES, ExcerptAnswer, NO_VARIANTS_FOUND, VariantResultSettings, VcfExcerpt,
};
pub(crate) use excerpt::{excerpt_columns, java_binary_search, not_allowed, subset_column_ids};
pub use listing::InfoColumnMeta;
pub(crate) use listing::{info_column_listing, info_column_listing_json};
pub(crate) use store::on_genomic_worker;
pub use store::{
    DEFAULT_GENOMIC_CACHE_BYTES, DEFAULT_GENOMIC_READ_BUDGET_BYTES, GenomicCacheStats,
    GenomicOptions, GenomicStore,
};
pub(crate) use variants::{VariantLists, for_each_listed_spec, variant_lists};
