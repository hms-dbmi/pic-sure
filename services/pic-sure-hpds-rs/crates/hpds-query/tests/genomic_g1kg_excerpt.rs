//! An AGGREGATE_VCF_EXCERPT of every `g1kg` variant, streamed to a sink that only counts, so
//! the process's peak memory shows what the excerpt holds: run the test binary under a
//! memory meter (`/usr/bin/time -l`) with the genomic cache and read budget set small.
//!
//! Runs when `HPDS_G1KG_EXCERPT` names the bench data directory (`.worktrees/bench-data`).
//! `HPDS_GENOMIC_CACHE_BYTES` and `HPDS_GENOMIC_READ_BUDGET_BYTES` size the cache and the
//! budget (the defaults when unset).

mod common;

use std::io::Write;
use std::path::PathBuf;
use std::time::Instant;

use hpds_query::{
    ExcerptAnswer, GenomicFilter, GenomicOptions, GenomicStore, Query, ResultType,
    VariantResultSettings,
};

/// A writer that counts bytes and lines and keeps nothing.
#[derive(Default)]
struct Counting {
    bytes: u64,
    lines: u64,
}

impl Write for Counting {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.bytes += buf.len() as u64;
        self.lines += buf.iter().filter(|&&b| b == b'\n').count() as u64;
        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn env_bytes(name: &str, default: u64) -> u64 {
    std::env::var(name)
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(default)
}

#[test]
fn an_excerpt_of_every_g1kg_variant_streams() {
    let Some(bench) = std::env::var_os("HPDS_G1KG_EXCERPT").map(PathBuf::from) else {
        eprintln!("HPDS_G1KG_EXCERPT is not set; skipping the g1kg excerpt");
        return;
    };
    let csvs = bench.join("equivalence-genomic/inputs/g1kg");
    let texts: Vec<(String, String)> = ["1000GenomesA", "1000GenomesB"]
        .iter()
        .map(|name| {
            let body =
                std::fs::read_to_string(csvs.join(format!("{name}-allConcepts.csv"))).unwrap();
            (
                (*name).to_owned(),
                format!("{}{body}", common::FIXTURE_HEADER),
            )
        })
        .collect();
    let pheno = common::partitioned_texts(&texts);
    let options = GenomicOptions {
        cache_bytes: env_bytes(
            "HPDS_GENOMIC_CACHE_BYTES",
            hpds_query::DEFAULT_GENOMIC_CACHE_BYTES,
        ),
        read_budget_bytes: env_bytes(
            "HPDS_GENOMIC_READ_BUDGET_BYTES",
            hpds_query::DEFAULT_GENOMIC_READ_BUDGET_BYTES,
        ),
        threads: None,
    };
    let store = GenomicStore::open_with_key_file(
        &bench.join("genomic/g1kg/rust"),
        &bench.join("genomic/g1kg/pheno/encryption_key"),
        &options,
    )
    .unwrap();
    let engine = pheno
        .open(&common::lenient())
        .unwrap()
        .with_genomic(store)
        .with_variant_settings(VariantResultSettings {
            aggregate_vcf_excerpt_enabled: true,
            max_excerpt_bytes: 4 << 30,
            ..VariantResultSettings::default()
        });
    let mut query = Query::new(ResultType::VariantCountForQuery);
    query.genomic_filters = vec![GenomicFilter {
        key: Some("Variant_class".into()),
        values: Some(vec![
            Some("SNV".into()),
            Some("insertion".into()),
            Some("deletion".into()),
        ]),
        min: None,
        max: None,
    }];
    let started = Instant::now();
    let count = engine.variant_count(&query).unwrap().unwrap();
    let counted = started.elapsed();
    query.expected_result_type = ResultType::AggregateVcfExcerpt;
    let started = Instant::now();
    let Ok(ExcerptAnswer::Rows(excerpt)) = engine.vcf_excerpt(&query) else {
        panic!("an excerpt");
    };
    let measured = started.elapsed();
    let mut sink = Counting::default();
    excerpt.write_to(&mut sink).unwrap();
    let written = started.elapsed() - measured;
    let store = engine.genomic().unwrap();
    eprintln!(
        "variants {count} (counted in {counted:?}); excerpt {} bytes, {} rows, measured in \
         {measured:?}, written in {written:?}; cache {:?}; budget {:?}",
        sink.bytes,
        sink.lines - 1,
        store.cache_stats(),
        store.read_stats()
    );
    assert_eq!(sink.bytes, excerpt.len());
    assert_eq!(
        sink.lines - 1,
        count,
        "one row per listed variant, none collapsed on g1kg"
    );
    assert!(count > 1_300_000, "{count}");
}
