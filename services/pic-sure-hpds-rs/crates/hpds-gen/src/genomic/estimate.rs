//! Sizes a dataset without writing it: the sites of a few chromosomes are drawn for every
//! group, a sample of the present rows is compressed as BGZF, and the totals are scaled up.

use std::io::{self, Write};

use super::bgzf::BgzfWriter;
use super::cohort::Cohort;
use super::sites::{SiteStream, chromosome_target};
use super::vcf::{Gt, RowBuilder, group_view, write_header};
use super::writer::group_site_rng;
use super::{CHROMOSOMES, Shape};

const SAMPLED_CHROMOSOMES: [usize; 2] = [20, 21];

/// The estimated size of one group's files.
#[derive(Clone, Debug)]
pub struct GroupEstimate {
    /// Group name.
    pub name: String,
    /// Samples in the group.
    pub samples: u32,
    /// Estimated VCF records over all chromosomes.
    pub records: u64,
    /// Estimated compressed bytes of the group's 23 VCFs.
    pub vcf_bytes: u64,
    /// Estimated uncompressed bytes of the group's 23 VCFs.
    pub text_bytes: u64,
}

struct Counter(u64);

impl Write for Counter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.0 += buf.len() as u64;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Estimates the dataset of `shape` by generating every site of chromosomes 21 and 22 and
/// compressing one in `stride` of the rows that groups carry.
///
/// # Errors
/// Returns an I/O error from the in-memory compression, which does not happen in practice.
pub fn estimate(shape: &Shape, seed: u64, stride: u64) -> io::Result<Vec<GroupEstimate>> {
    let cohort = Cohort::random(shape, seed);
    let total_sites: u64 = (0..CHROMOSOMES.len())
        .map(|c| chromosome_target(c, shape.variants))
        .sum();
    let sampled_sites: u64 = SAMPLED_CHROMOSOMES
        .iter()
        .map(|c| chromosome_target(*c, shape.variants))
        .sum();
    let scale = total_sites as f64 / sampled_sites as f64;
    let mut out = Vec::new();
    for (g, group) in cohort.groups.iter().enumerate() {
        let samples = group.header_samples.len();
        let mut present = 0u64;
        let mut sampled_rows = 0u64;
        let mut text = 0u64;
        let mut compressed = BgzfWriter::new(Counter(0), 3);
        let mut builder = RowBuilder::new(samples);
        let mut calls: Vec<(u32, Gt)> = Vec::new();
        let mut info = Vec::new();
        let mut header_bytes = 0u64;
        for chromosome in SAMPLED_CHROMOSOMES {
            let mut header = BgzfWriter::new(Counter(0), 3);
            write_header(
                &mut header,
                CHROMOSOMES[chromosome],
                &group.header_samples,
                &[],
            )?;
            header_bytes += header.finish()?.0;
            let target = chromosome_target(chromosome, shape.variants);
            let mut last_key = u64::MAX;
            let mut is_present = false;
            for site in SiteStream::new(seed, chromosome, target) {
                if site.genotype_key != last_key {
                    let mut rng = group_site_rng(seed, chromosome, site.genotype_key, g);
                    is_present = group_view(&mut rng, samples, site.carrier_fraction, &mut calls);
                    last_key = site.genotype_key;
                }
                if !is_present {
                    continue;
                }
                present += 1;
                if present.is_multiple_of(stride) {
                    info.clear();
                    site.write_info(&mut info);
                    let row = builder.build(
                        CHROMOSOMES[chromosome],
                        site.position,
                        &site.reference,
                        &site.alternate,
                        &info,
                        &calls,
                    );
                    text += row.len() as u64;
                    compressed.write_all(row)?;
                    sampled_rows += 1;
                }
            }
        }
        let rows_bytes = compressed.finish()?.0;
        let per_row = rows_bytes as f64 / sampled_rows.max(1) as f64;
        let text_per_row = text as f64 / sampled_rows.max(1) as f64;
        let records = present as f64 * scale;
        let per_file_header = header_bytes as f64 / SAMPLED_CHROMOSOMES.len() as f64;
        out.push(GroupEstimate {
            name: group.name.clone(),
            samples: samples as u32,
            records: records as u64,
            vcf_bytes: (records * per_row + per_file_header * CHROMOSOMES.len() as f64) as u64,
            text_bytes: (records * text_per_row) as u64,
        });
    }
    Ok(out)
}
