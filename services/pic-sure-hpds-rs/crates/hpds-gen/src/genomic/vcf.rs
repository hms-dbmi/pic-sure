//! VCF text: the header the 1000 Genomes files carry, and records of three-character `GT`
//! cells, the form the Java loader's fast path reads.

use std::io::{self, Write};

use super::profile::hom_share;
use crate::rng::Source;

/// One genotype cell.
pub type Gt = [u8; 3];

/// Phased heterozygous call, reference allele first.
pub const HET_REF_FIRST: Gt = *b"0|1";
/// Phased heterozygous call, alternate allele first.
pub const HET_ALT_FIRST: Gt = *b"1|0";
/// Homozygous alternate call.
pub const HOM_ALT: Gt = *b"1|1";
/// Homozygous reference call, the cell every non-carrier gets.
pub const HOM_REF: Gt = *b"0|0";

/// The six `##INFO` header lines of the 1000 Genomes files, in their order.
pub const INFO_HEADER_LINES: [&str; 6] = [
    "##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"The official symbol for a gene affected by a variant.\">",
    "##INFO=<ID=Variant_severity,Number=.,Type=String,Description=\"The severity for the calculated consequence of a variant on a gene. Possible values: HIGH (frameshift, splice disrupting, or truncating variants), MODERATE (non-frameshift insertions or deletions, variants altering protein sequencing without affecting its length), LOW (other coding sequence variants including synonymous variants).\">",
    "##INFO=<ID=Variant_consequence_calculated,Number=.,Type=String,Description=\"A standardized term from the Sequence Ontology (http://www.sequenceontology.org) to describe the calculated consequence of a variant.\">",
    "##INFO=<ID=Variant_class,Number=1,Type=String,Description=\"A standardized term from the Sequence Ontology (http://www.sequenceontology.org) to describe the type of a variant. Possible values: SNV, deletion, insertion.\">",
    "##INFO=<ID=Variant_frequency_in_gnomAD,Number=1,Type=Float,Description=\"The variant allele frequency in gnomAD combined population.\">",
    "##INFO=<ID=Variant_frequency_as_text,Number=1,Type=String,Description=\"The variant allele frequency in gnomAD combined population as discrete text categories. Possible values: Novel, Rare (variant frequency less than 1%), Common (variant frequency greater than or equal to 1%).\">",
];

/// Writes the header lines and the `#CHROM` line for `samples`. `extra_info` lines go after the
/// six standard ones.
///
/// # Errors
/// Returns the writer's error.
pub fn write_header(
    out: &mut (impl Write + ?Sized),
    chromosome: &str,
    samples: &[String],
    extra_info: &[&str],
) -> io::Result<()> {
    writeln!(out, "##fileformat=VCFv4.3")?;
    writeln!(out, "##FILTER=<ID=PASS,Description=\"All filters passed\">")?;
    writeln!(
        out,
        "##FORMAT=<ID=GT,Number=1,Type=String,Description=\"Phased Genotype\">"
    )?;
    writeln!(out, "##contig=<ID={chromosome}>")?;
    for line in INFO_HEADER_LINES.iter().chain(extra_info) {
        writeln!(out, "{line}")?;
    }
    write!(out, "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT")?;
    for sample in samples {
        write!(out, "\t{sample}")?;
    }
    writeln!(out)
}

/// Builds records over a template of `0|0` cells, overwriting the carriers' cells.
pub struct RowBuilder {
    template: Vec<u8>,
    row: Vec<u8>,
}

impl RowBuilder {
    /// A builder for rows of `samples` cells.
    pub fn new(samples: usize) -> Self {
        let mut template = Vec::with_capacity(samples * 4);
        for _ in 0..samples {
            template.extend_from_slice(&HOM_REF);
            template.push(b'\t');
        }
        if let Some(last) = template.last_mut() {
            *last = b'\n';
        }
        Self {
            template,
            row: Vec::with_capacity(samples * 4 + 512),
        }
    }

    /// Builds the record line of a site: the fixed columns, `info`, `GT`, and one cell per
    /// sample with `calls` (sample index, cell) overwriting the reference cells.
    pub fn build(
        &mut self,
        chromosome: &str,
        position: u32,
        reference: &str,
        alternate: &str,
        info: &[u8],
        calls: &[(u32, Gt)],
    ) -> &[u8] {
        self.row.clear();
        self.row.extend_from_slice(chromosome.as_bytes());
        self.row.push(b'\t');
        self.row.extend_from_slice(position.to_string().as_bytes());
        self.row.extend_from_slice(b"\t.\t");
        self.row.extend_from_slice(reference.as_bytes());
        self.row.push(b'\t');
        self.row.extend_from_slice(alternate.as_bytes());
        self.row.extend_from_slice(b"\t.\tPASS\t");
        self.row.extend_from_slice(info);
        self.row.extend_from_slice(b"\tGT\t");
        let cells = self.row.len();
        self.row.extend_from_slice(&self.template);
        for (sample, cell) in calls {
            let at = cells + *sample as usize * 4;
            self.row[at..at + 3].copy_from_slice(cell);
        }
        &self.row
    }
}

/// The chance that a group of `samples` samples lists a site of the dataset.
///
/// Larger groups list more of the sites, as larger cohorts observe more variants.
pub fn listing_chance(samples: usize) -> f64 {
    (0.5 * libm::pow(samples as f64 / 2548.0, 0.35)).min(0.95)
}

/// Draws whether a group of `samples` samples lists a site, and if so fills `calls` with its
/// carriers through [`draw_calls`]. Sites nobody carries (a zero `fraction`) are listed with
/// no carriers.
pub fn group_view(
    rng: &mut Source,
    samples: usize,
    fraction: f64,
    calls: &mut Vec<(u32, Gt)>,
) -> bool {
    if !rng.chance(listing_chance(samples)) {
        calls.clear();
        return false;
    }
    draw_calls(rng, samples, fraction, calls);
    true
}

/// Draws which of `samples` samples carry the alternate allele of a site a group lists, and how.
///
/// A listed site has at least one carrier: one sample chosen uniformly, and every other sample
/// independently with the probability that makes the expected carrier count `fraction * samples`
/// (or one carrier, when that is smaller). A carrier is homozygous with the fitted share for
/// that fraction and otherwise a phased heterozygote of either phase. `calls` is cleared and
/// filled with `(sample index, cell)` in ascending sample order. A site with a zero `fraction`
/// has no carriers.
pub fn draw_calls(rng: &mut Source, samples: usize, fraction: f64, calls: &mut Vec<(u32, Gt)>) {
    calls.clear();
    if fraction <= 0.0 || samples == 0 {
        return;
    }
    let hom = hom_share(fraction.min(1.0));
    let probability =
        ((fraction * samples as f64 - 1.0).max(0.0) / (samples as f64 - 1.0).max(1.0)).min(1.0);
    let forced = (rng.bits() % samples as u64) as u32;
    let cell = |rng: &mut Source| {
        if rng.chance(hom) {
            HOM_ALT
        } else if rng.bits() & 1 == 0 {
            HET_REF_FIRST
        } else {
            HET_ALT_FIRST
        }
    };
    if probability <= 0.0 {
    } else if probability < 0.2 {
        let log_miss = libm::log1p(-probability);
        let mut at = 0.0f64;
        while let Some(skip) = rng.geometric_skip(log_miss) {
            at += skip;
            if at >= samples as f64 {
                break;
            }
            let index = at as u32;
            if index != forced {
                calls.push((index, cell(rng)));
            }
            at += 1.0;
        }
    } else {
        let threshold = (probability * 4_294_967_296.0) as u64;
        for index in 0..samples as u32 {
            if index != forced && u64::from(rng.bits32()) < threshold {
                calls.push((index, cell(rng)));
            }
        }
    }
    let at = calls.partition_point(|(i, _)| *i < forced);
    calls.insert(at, (forced, cell(rng)));
}
