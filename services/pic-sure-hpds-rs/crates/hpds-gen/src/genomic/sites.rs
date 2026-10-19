//! The per-chromosome stream of variant sites: positions, alleles, annotation and frequency.

use super::profile::{
    ConsequenceChoice, FrequencyText, Profile, VariantClass, fitted, format_frequency,
};
use super::profile_data::{
    FITTED_CHROM_VARIANTS, FITTED_VARIANTS, GENE_MISSING_COUNT, SAME_SITE_COUNTS,
};
use super::{CHROMOSOME_LENGTHS, CHROMOSOMES};
use crate::rng::{Source, mix};

const BASES: [u8; 4] = *b"ACGT";
const FIRST_POSITION: u64 = 10_000;
const SPAN_USED: f64 = 0.92;
const GENES_PER_CHROMOSOME: u64 = 1_000_000;
const SYMBOL_SPACE: u64 = 26 * 26 * 26;

/// One variant site: where it is, what it is, how it is annotated and how common it is.
#[derive(Clone, Debug)]
pub struct Site {
    /// Zero-based place in the chromosome's stream.
    pub index: u64,
    /// The index of the first record of this site: records that annotate the same site again
    /// share it, and with it every sample's genotype.
    pub genotype_key: u64,
    /// One-based position.
    pub position: u32,
    /// Reference allele.
    pub reference: String,
    /// Alternate allele; sites are biallelic.
    pub alternate: String,
    /// Gene symbol, or `None` when the record has no `Gene_with_variant` field.
    pub gene: Option<String>,
    /// Consequence, severity and class.
    pub consequence: ConsequenceChoice,
    /// The frequency text.
    pub text: FrequencyText,
    /// The gnomAD frequency as it is written in the INFO field.
    pub gnomad: String,
    /// The share of samples that carry the alternate allele; zero for a record nobody carries.
    pub carrier_fraction: f64,
}

impl Site {
    /// The position, reference and alternate allele that identify the site.
    pub fn site_key(&self) -> (u32, &str, &str) {
        (self.position, &self.reference, &self.alternate)
    }

    /// Appends the INFO column in the 1000 Genomes order, skipping the gene when it is absent.
    pub fn write_info(&self, out: &mut Vec<u8>) {
        if let Some(gene) = &self.gene {
            out.extend_from_slice(b"Gene_with_variant=");
            out.extend_from_slice(gene.as_bytes());
            out.push(b';');
        }
        for part in [
            "Variant_severity=",
            self.consequence.severity,
            ";Variant_consequence_calculated=",
            self.consequence.consequence,
            ";Variant_class=",
            self.consequence.class.as_str(),
            ";Variant_frequency_in_gnomAD=",
            &self.gnomad,
            ";Variant_frequency_as_text=",
            self.text.as_str(),
        ] {
            out.extend_from_slice(part.as_bytes());
        }
    }
}

/// Returns the gene symbol of gene number `id`: three letters, then a number once the three
/// letter space is used up. Distinct ids give distinct symbols.
pub fn gene_symbol(id: u64) -> String {
    let slot = (id % SYMBOL_SPACE * 7919 + 1234) % SYMBOL_SPACE;
    let round = id / SYMBOL_SPACE;
    let letters = [
        (b'A' + (slot / 676) as u8) as char,
        (b'A' + (slot / 26 % 26) as u8) as char,
        (b'A' + (slot % 26) as u8) as char,
    ];
    let mut symbol: String = letters.iter().collect();
    if round > 0 {
        symbol.push_str(&round.to_string());
    }
    symbol
}

/// Generates the sites of one chromosome in position order, the same sites for every group.
pub struct SiteStream {
    profile: &'static Profile,
    rng: Source,
    chromosome: usize,
    target: u64,
    emitted: u64,
    gap_scale: f64,
    position: u64,
    gene_ordinal: u64,
    gene_left: u64,
    gene_symbol: String,
    missing_gene_chance: f64,
    last_reference: u8,
    last_was_snv: bool,
    alternates_here: Vec<u8>,
    repeat_chance: f64,
    previous: Option<Site>,
}

/// How many sites of `chromosome` a dataset of `variants` sites holds, by the fitted shares.
pub fn chromosome_target(chromosome: usize, variants: u64) -> u64 {
    (variants as f64 * FITTED_CHROM_VARIANTS[chromosome] as f64 / FITTED_VARIANTS as f64).round()
        as u64
}

impl SiteStream {
    /// Starts the stream of `chromosome` (an index into [`CHROMOSOMES`]) holding `target`
    /// sites, seeded from `seed` and the chromosome alone.
    pub fn new(seed: u64, chromosome: usize, target: u64) -> Self {
        let profile = fitted();
        let length = f64::from(CHROMOSOME_LENGTHS[chromosome]);
        let gap_scale = SPAN_USED * length / (target.max(1) as f64 * profile.mean_gap());
        let mut rng = Source::new(mix(seed ^ mix(0x5173_0000_0000_0000 ^ chromosome as u64)));
        let first = FIRST_POSITION + (profile.gap(&mut rng) as f64 * gap_scale) as u64;
        Self {
            profile,
            rng,
            chromosome,
            target,
            emitted: 0,
            gap_scale,
            position: first,
            gene_ordinal: 0,
            gene_left: 0,
            gene_symbol: String::new(),
            missing_gene_chance: GENE_MISSING_COUNT as f64 / FITTED_VARIANTS as f64,
            last_reference: b'A',
            last_was_snv: false,
            alternates_here: Vec::new(),
            repeat_chance: SAME_SITE_COUNTS[0] as f64 / FITTED_VARIANTS as f64,
            previous: None,
        }
    }

    fn annotate_again(&mut self, previous: &Site) -> Site {
        let mut consequence = previous.consequence;
        for _ in 0..8 {
            let drawn = self.profile.consequence(&mut self.rng);
            if drawn.class == previous.consequence.class
                && drawn.consequence != previous.consequence.consequence
            {
                consequence = drawn;
                break;
            }
        }
        let overlapping = gene_symbol(
            self.chromosome as u64 * GENES_PER_CHROMOSOME
                + GENES_PER_CHROMOSOME / 2
                + self.gene_ordinal,
        );
        let gene = if consequence.consequence == previous.consequence.consequence
            || self.rng.chance(0.3)
        {
            Some(overlapping)
        } else {
            previous.gene.clone()
        };
        Site {
            index: self.emitted,
            gene,
            consequence,
            ..previous.clone()
        }
    }

    /// The chromosome name.
    pub fn name(&self) -> &'static str {
        CHROMOSOMES[self.chromosome]
    }

    fn next_gene(&mut self) {
        self.gene_left = self.profile.gene_size(&mut self.rng);
        let id = self.chromosome as u64 * GENES_PER_CHROMOSOME + self.gene_ordinal;
        self.gene_symbol = gene_symbol(id);
        self.gene_ordinal += 1;
    }

    fn random_bases(&mut self, count: usize) -> String {
        (0..count)
            .map(|_| BASES[(self.rng.bits() & 3) as usize] as char)
            .collect()
    }

    fn alleles(&mut self, class: VariantClass, same_position: bool) -> (String, String) {
        match class {
            VariantClass::Snv => {
                let mut tries = 0;
                loop {
                    let reference = if same_position && self.last_was_snv {
                        self.last_reference
                    } else {
                        BASES[self.profile.snv_reference(&mut self.rng)]
                    };
                    let from = BASES.iter().position(|b| *b == reference).unwrap_or(0);
                    let to = self.profile.snv_alternate(from, &mut self.rng);
                    let alternate = BASES[to];
                    tries += 1;
                    if !same_position || !self.alternates_here.contains(&alternate) || tries > 8 {
                        self.last_reference = reference;
                        self.last_was_snv = true;
                        return (
                            (reference as char).to_string(),
                            (alternate as char).to_string(),
                        );
                    }
                }
            }
            VariantClass::Insertion => {
                let anchor = BASES[(self.rng.bits() & 3) as usize] as char;
                let added = self.profile.insertion_length(&mut self.rng);
                let tail = self.random_bases(added);
                self.last_was_snv = false;
                (anchor.to_string(), format!("{anchor}{tail}"))
            }
            VariantClass::Deletion => {
                let anchor = BASES[(self.rng.bits() & 3) as usize] as char;
                let removed = self.profile.deletion_length(&mut self.rng);
                let tail = self.random_bases(removed);
                self.last_was_snv = false;
                (format!("{anchor}{tail}"), anchor.to_string())
            }
        }
    }
}

impl Iterator for SiteStream {
    type Item = Site;

    fn next(&mut self) -> Option<Site> {
        if self.emitted >= self.target {
            return None;
        }
        if let Some(previous) = self.previous.take()
            && self.rng.chance(self.repeat_chance)
        {
            let site = self.annotate_again(&previous);
            self.emitted += 1;
            self.previous = Some(site.clone());
            return Some(site);
        }
        if self.gene_left == 0 {
            self.next_gene();
        }
        self.gene_left -= 1;
        let consequence = self.profile.consequence(&mut self.rng);
        let frequency = self.profile.frequency(&mut self.rng);
        let has_gene = !self.rng.chance(self.missing_gene_chance);
        let mut same_position = false;
        if self.emitted > 0 {
            let gap = (self.profile.gap(&mut self.rng) as f64 * self.gap_scale) as u64;
            same_position = gap == 0;
            self.position += gap;
        }
        if !same_position {
            self.alternates_here.clear();
            self.last_was_snv = false;
        }
        let (reference, alternate) = self.alleles(consequence.class, same_position);
        if consequence.class == VariantClass::Snv {
            self.alternates_here.push(alternate.as_bytes()[0]);
        }
        let site = Site {
            index: self.emitted,
            genotype_key: self.emitted,
            position: self.position.min(u64::from(i32::MAX as u32)) as u32,
            reference,
            alternate,
            gene: has_gene.then(|| self.gene_symbol.clone()),
            consequence,
            text: frequency.text,
            gnomad: format_frequency(frequency.gnomad),
            carrier_fraction: frequency.carrier_fraction,
        };
        self.emitted += 1;
        self.previous = Some(site.clone());
        Some(site)
    }
}
