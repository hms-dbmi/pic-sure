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
        let mut digits = [0u8; 10];
        let mut at = digits.len();
        let mut rest = position;
        loop {
            at -= 1;
            digits[at] = b'0' + (rest % 10) as u8;
            rest /= 10;
            if rest == 0 {
                break;
            }
        }
        self.row.extend_from_slice(&digits[at..]);
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

/// Draws which of a group's `samples` samples carry the alternate allele of a site, each
/// independently with probability `probability`, and how.
///
/// A carrier is homozygous with the fitted share for the site's `fraction` and otherwise a
/// phased heterozygote of either phase. `calls` is cleared and filled with `(sample index,
/// cell)` in ascending sample order.
pub fn draw_calls(
    rng: &mut Source,
    samples: usize,
    fraction: f64,
    probability: f64,
    calls: &mut Vec<(u32, Gt)>,
) {
    calls.clear();
    let probability = probability.min(1.0);
    if probability <= 0.0 || samples == 0 {
        return;
    }
    let hom = hom_share(fraction.min(1.0));
    let cell = |rng: &mut Source| {
        if rng.chance(hom) {
            HOM_ALT
        } else if rng.bits() & 1 == 0 {
            HET_REF_FIRST
        } else {
            HET_ALT_FIRST
        }
    };
    if probability < 0.2 {
        let log_miss = libm::log1p(-probability);
        let mut at = 0.0f64;
        while let Some(skip) = rng.geometric_skip(log_miss) {
            at += skip;
            if at >= samples as f64 {
                break;
            }
            calls.push((at as u32, cell(rng)));
            at += 1.0;
        }
    } else {
        let threshold = (probability * 4_294_967_296.0) as u64;
        for index in 0..samples as u32 {
            if u64::from(rng.bits32()) < threshold {
                calls.push((index, cell(rng)));
            }
        }
    }
}

/// Where a group sits in the dataset's sample numbering.
#[derive(Clone, Copy, Debug)]
pub struct GroupSlot {
    /// Index of the group's first sample among all the dataset's samples.
    pub first: u64,
    /// Samples in the group.
    pub samples: usize,
    /// Samples in the whole dataset.
    pub total: u64,
}

const EXACT_CARRIER_LIMIT: u32 = 64;

/// Draws a group's view of a site and fills `calls` with its carriers in the group.
///
/// A rare or novel site has [`Site::dataset_carriers`](super::sites::Site::dataset_carriers)
/// carriers among all the dataset's samples, chosen from `dataset_rng`, which depends on the
/// site alone, so groups agree on who carries it; the group sees the ones that fall in its range.
/// A common site, or one with too many carriers to pick exactly, has carriers drawn from
/// `group_rng` independently in each sample with the site's `fraction`. A group lists a site when
/// it has a carrier; a site nobody carries (a zero `fraction`) is listed with none. Returns
/// whether the group lists the site.
pub fn group_view(
    dataset_rng: &mut Source,
    group_rng: &mut Source,
    slot: GroupSlot,
    fraction: f64,
    dataset_carriers: Option<u32>,
    calls: &mut Vec<(u32, Gt)>,
) -> bool {
    calls.clear();
    if fraction <= 0.0 {
        return true;
    }
    let exact =
        dataset_carriers.filter(|k| *k <= EXACT_CARRIER_LIMIT && u64::from(*k) * 2 <= slot.total);
    match exact {
        Some(k) => {
            let hom = hom_share(fraction.min(1.0));
            let mut picked: Vec<u64> = Vec::with_capacity(k as usize);
            while picked.len() < k as usize {
                let sample = dataset_rng.bits() % slot.total;
                if picked.contains(&sample) {
                    continue;
                }
                picked.push(sample);
                let cell = if dataset_rng.chance(hom) {
                    HOM_ALT
                } else if dataset_rng.bits() & 1 == 0 {
                    HET_REF_FIRST
                } else {
                    HET_ALT_FIRST
                };
                if sample >= slot.first && sample < slot.first + slot.samples as u64 {
                    calls.push(((sample - slot.first) as u32, cell));
                }
            }
            calls.sort_unstable_by_key(|(i, _)| *i);
        }
        None => {
            let probability = match dataset_carriers {
                Some(k) => f64::from(k) / slot.total as f64,
                None => fraction,
            };
            draw_calls(group_rng, slot.samples, fraction, probability, calls);
        }
    }
    !calls.is_empty()
}
