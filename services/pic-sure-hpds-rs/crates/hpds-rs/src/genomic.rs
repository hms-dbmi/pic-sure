//! Opening the genomic store at startup and the genomic part of the service's health.

use std::path::Path;

use hpds_query::{Engine, GenomicStore, KEY_FILE_NAME};

use crate::config::Config;

/// Health reason when `hpds.genomicProcessor.impl` is set and the genomic store could not be
/// opened.
pub const GENOMIC_NOT_OPENED_REASON: &str =
    "the genomic store could not be opened; see the server log";

/// Health reason when the genomic store's layout is not the one `hpds.genomicProcessor.impl`
/// names.
pub const GENOMIC_LAYOUT_REASON: &str = "the genomic store's layout is not the one hpds.genomicProcessor.impl names; see the server log";

/// Health reason when the genomic store files changed on disk after the service opened them.
pub const GENOMIC_CHANGED_REASON: &str =
    "the genomic store changed on disk after it was opened; restart the service";

/// What the service does with genomic data.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GenomicStatus {
    /// `hpds.genomicProcessor.impl` is unset: no genomic data is served, as with Java's no-op
    /// processor. Genomic filters are a 400, INFO_COLUMN_LISTING is `[]`.
    Off,
    /// The genomic store is open and attached to the engine.
    Served,
    /// `hpds.genomicProcessor.impl` is set and the store could not be served, for the reason
    /// given. Health is DOWN and every request that needs genomic data is a 503; phenotype
    /// requests are still answered.
    Down(&'static str),
}

/// Opens the genomic store `config` names and attaches it to `engine`, with the variant
/// result settings, which apply with or without a store.
///
/// With `hpds.genomicProcessor.impl` unset nothing is opened ([`GenomicStatus::Off`]); a
/// store found in the directory anyway is logged as ignored. Otherwise the store in
/// `HPDS_GENOMIC_DATA_DIRECTORY` is opened under the phenotype store's key file
/// (`<HPDS_DATA_DIRECTORY>/encryption_key`; a plain store when it is absent and plain stores
/// are allowed) and its layout must be the one the setting names. Every failure is logged
/// with its detail and reported as [`GenomicStatus::Down`]. Call it after the phenotype store
/// is open, so the main rayon pool's size, which the genomic pool copies, is settled.
pub fn attach_genomic(engine: Engine, config: &Config) -> (Engine, GenomicStatus) {
    let genomic = &config.genomic;
    let engine = engine.with_variant_settings(genomic.variant.clone());
    let Some(processor) = genomic.processor else {
        if genomic
            .data_dir
            .join(hpds_format::GENO_INDEX_FILE_NAME)
            .exists()
        {
            tracing::warn!(
                dir = %genomic.data_dir.display(),
                "the genomic data directory holds a genomic store, but hpds.genomicProcessor.impl is unset, so no genomic data is served"
            );
        }
        return (engine, GenomicStatus::Off);
    };
    let key_file = config.data_dir.join(KEY_FILE_NAME);
    if !key_file.exists() && !config.partitions.store.allow_plain {
        tracing::error!(
            key = %key_file.display(),
            "the genomic store is sealed with the phenotype store's key file, which is missing; serving health DOWN"
        );
        return (engine, GenomicStatus::Down(GENOMIC_NOT_OPENED_REASON));
    }
    match GenomicStore::open_with_key_file(&genomic.data_dir, &key_file, &genomic.options) {
        Ok(store) if store.mode() != processor => {
            tracing::error!(
                dir = %genomic.data_dir.display(),
                store = store.mode().java_name(),
                configured = processor.java_name(),
                "the genomic store was built for another hpds.genomicProcessor.impl; serving health DOWN"
            );
            (engine, GenomicStatus::Down(GENOMIC_LAYOUT_REASON))
        }
        Ok(store) => {
            log_opened(&store, &genomic.data_dir, config);
            (engine.with_genomic(store), GenomicStatus::Served)
        }
        Err(error) => {
            tracing::error!(%error, "cannot open the genomic store; serving health DOWN");
            (engine, GenomicStatus::Down(GENOMIC_NOT_OPENED_REASON))
        }
    }
}

fn log_opened(store: &GenomicStore, dir: &Path, config: &Config) {
    let shape = store.shape();
    let variant = &config.genomic.variant;
    tracing::info!(
        dir = %dir.display(),
        mode = store.mode().java_name(),
        groups = shape.groups,
        nodes = shape.nodes,
        patients = shape.patients,
        variants = shape.variants,
        info_columns = store.info_column_count(),
        cache_bytes = config.genomic.options.cache_bytes,
        read_budget_bytes = config.genomic.options.read_budget_bytes,
        largest_chunk_set_bytes = store.largest_chunk_set_bytes(),
        vcf_excerpt_enabled = variant.vcf_excerpt_enabled,
        aggregate_vcf_excerpt_enabled = variant.aggregate_vcf_excerpt_enabled,
        max_excerpt_bytes = variant.max_excerpt_bytes,
        "genomic store opened"
    );
}
