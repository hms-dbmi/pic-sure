//! The edge dataset: two groups and about 40 samples with hand-placed sites that exercise
//! every ETL and query rule of the genomic design, in files small enough to read.
//!
//! Group `edgeA` has 24 header samples, an indexed sample missing from its VCFs, and three
//! patients with phenotypes only. Group `edgeB` has 16 indexed samples, one unindexed header
//! sample, and one genotyped patient without phenotype rows. The layout:
//!
//! | file | group A | group B |
//! |---|---|---|
//! | `chr1` | BGZF | BGZF |
//! | `chr2` | none | plain text |
//! | `chr3` | BGZF | none |
//! | `chr4` | BGZF, 2,100 sites | BGZF, the sites B carries |
//! | `chrX` | plain text | none |

use std::fs::{self, File};
use std::io::{self, BufWriter, Write};
use std::ops::Range;
use std::path::Path;
use std::time::Instant;

use super::bgzf::BgzfWriter;
use super::cohort::{Cohort, Group, IndexedSample, write_phenotypes};
use super::profile::format_frequency;
use super::vcf::{Gt, HET_ALT_FIRST, HET_REF_FIRST, HOM_ALT, RowBuilder, write_header};
use super::writer::{Output, index_label, vcf_file_name, write_groups_summary};
use crate::rng::{Source, mix};

const FLAG_HEADER: &str =
    "##INFO=<ID=E_FLAG,Number=0,Type=Flag,Description=\"A flag field with no value.\">";
const GROUP_A: usize = 0;
const GROUP_B: usize = 1;
const A_BASE: u32 = 100_000;
const B_BASE: u32 = 200_000;
const A_SAMPLES: u32 = 24;
const B_SAMPLES: u32 = 16;
const B_EXTRA_AT: u32 = 8;
const FILLER_SITES: u32 = 2_100;

/// The rules the dataset covers, each asserted by a test.
pub const EDGE_ITEMS: [&str; 20] = [
    "flag field in INFO",
    "ampersand list in gene and consequence",
    "comma separated numeric value",
    "dot numeric value",
    "more than 1000 distinct numeric values in one node",
    "numeric spellings of one value",
    "bucket edges at 999 and 1000",
    "two annotations at one site",
    "prefix colliding alleles",
    "0/2 and 1/2 calls",
    "no-call forms",
    "every call class of the Java table",
    "more than 5 carriers",
    "exactly 5 and 0 carriers",
    "carrier form differs between groups",
    "index sample missing from its VCF",
    "same site in two groups",
    "site in one group only",
    "BGZF and plain files",
    "patients without genotypes or without phenotypes",
];

type Calls = Vec<(u32, Gt)>;

fn gt(text: &str) -> Gt {
    let b = text.as_bytes();
    [b[0], b[1], b[2]]
}

fn calls(parts: &[(Range<u32>, &str)]) -> Calls {
    parts
        .iter()
        .flat_map(|(range, text)| range.clone().map(move |i| (i, gt(text))))
        .collect()
}

fn het(range: Range<u32>) -> Calls {
    calls(&[(range, "0|1")])
}

enum Info {
    Standard {
        gene: Option<String>,
        consequence: &'static str,
        frequency: String,
        text: &'static str,
    },
    Raw(String),
}

struct Spec {
    position: u32,
    reference: &'static str,
    alternate: &'static str,
    info: Info,
    a: Option<Calls>,
    b: Option<Calls>,
}

fn severity(consequence: &str) -> &'static str {
    let high = [
        "frameshift_variant",
        "splice_acceptor_variant",
        "splice_donor_variant",
        "start_lost",
        "stop_gained",
        "stop_lost",
    ];
    if high.contains(&consequence) {
        "HIGH"
    } else if consequence == "missense_variant" || consequence.starts_with("inframe") {
        "MODERATE"
    } else {
        "LOW"
    }
}

impl Spec {
    fn new(
        position: u32,
        reference: &'static str,
        alternate: &'static str,
        gene: &str,
        consequence: &'static str,
        frequency: &str,
        text: &'static str,
    ) -> Self {
        Self {
            position,
            reference,
            alternate,
            info: Info::Standard {
                gene: Some(gene.to_string()),
                consequence,
                frequency: frequency.to_string(),
                text,
            },
            a: None,
            b: None,
        }
    }

    fn raw(position: u32, reference: &'static str, alternate: &'static str, info: &str) -> Self {
        Self {
            position,
            reference,
            alternate,
            info: Info::Raw(info.to_string()),
            a: None,
            b: None,
        }
    }

    fn without_gene(mut self) -> Self {
        if let Info::Standard { gene, .. } = &mut self.info {
            *gene = None;
        }
        self
    }

    fn a(mut self, calls: Calls) -> Self {
        self.a = Some(calls);
        self
    }

    fn b(mut self, calls: Calls) -> Self {
        self.b = Some(calls);
        self
    }

    fn info_text(&self) -> String {
        match &self.info {
            Info::Raw(text) => text.clone(),
            Info::Standard {
                gene,
                consequence,
                frequency,
                text,
            } => {
                let class = match self.reference.len().cmp(&self.alternate.len()) {
                    std::cmp::Ordering::Equal => "SNV",
                    std::cmp::Ordering::Less => "insertion",
                    std::cmp::Ordering::Greater => "deletion",
                };
                let mut out = String::new();
                if let Some(gene) = gene {
                    out.push_str(&format!("Gene_with_variant={gene};"));
                }
                if !consequence.is_empty() {
                    out.push_str(&format!(
                        "Variant_severity={};Variant_consequence_calculated={consequence};",
                        severity(consequence)
                    ));
                }
                out.push_str(&format!(
                    "Variant_class={class};Variant_frequency_in_gnomAD={frequency};Variant_frequency_as_text={text}"
                ));
                out
            }
        }
    }
}

fn chr1() -> Vec<Spec> {
    let missense = "missense_variant";
    let mut sites = vec![
        Spec::new(1, "C", "T", "EDG1", missense, "0.0001", "Rare").a(het(0..1)),
        Spec::new(999, "A", "G", "EDG1", missense, "0.0002", "Rare")
            .a(het(0..1))
            .b(het(0..1)),
        Spec::new(
            1000,
            "C",
            "T",
            "EDG1",
            "synonymous_variant",
            "0.0003",
            "Rare",
        )
        .a(calls(&[(1..3, "0|1"), (3..4, "1|1")]))
        .b(het(1..2)),
        Spec::new(1999, "G", "A", "EDG2", "stop_gained", "0.0004", "Rare")
            .a(het(4..5))
            .b(het(2..3)),
        Spec::new(
            2000,
            "T",
            "C",
            "EDG2",
            "splice_region_variant",
            "0.0005",
            "Rare",
        )
        .a(het(5..6))
        .b(het(3..4)),
        Spec::new(5000, "A", "G", "EDG4", missense, "0.001", "Rare")
            .a(het(0..3))
            .b(het(0..1)),
        Spec::new(
            5000,
            "A",
            "GT",
            "EDG4",
            "frameshift_variant",
            "0.002",
            "Rare",
        )
        .a(het(3..5))
        .b(het(1..2)),
        Spec::new(
            5000,
            "A",
            "GTC",
            "EDG4",
            "frameshift_variant",
            "0.003",
            "Rare",
        )
        .a(het(5..6)),
        Spec::new(
            5000,
            "A",
            "AG",
            "EDG4",
            "frameshift_variant",
            "0.004",
            "Rare",
        )
        .b(het(2..3)),
    ];
    let shared = || calls(&[(0..3, "0|1")]);
    sites.push(
        Spec::new(6000, "C", "T", "EDG5", missense, "0.005", "Rare")
            .a(shared())
            .b(het(2..3)),
    );
    sites.push(
        Spec::new(
            6000,
            "C",
            "T",
            "EDG6",
            "splice_region_variant",
            "0.005",
            "Rare",
        )
        .a(shared())
        .b(het(2..3)),
    );
    sites.push(Spec::new(6000, "C", "A", "EDG5", missense, "0.006", "Rare").a(het(3..4)));
    let tail = "Variant_class=SNV;Variant_frequency_in_gnomAD=";
    sites.push(
        Spec::raw(
            7000,
            "C",
            "T",
            &format!("E_FLAG;Gene_with_variant=EDG7;Variant_severity=HIGH;Variant_consequence_calculated=stop_gained;{tail}0.001;Variant_frequency_as_text=Rare"),
        )
        .a(het(5..7))
        .b(het(5..6)),
    );
    sites.push(
        Spec::raw(
            7100,
            "G",
            "A",
            &format!("Gene_with_variant=EDG8;Variant_severity=HIGH;E_FLAG;Variant_consequence_calculated=stop_gained;{tail}0.002;Variant_frequency_as_text=Rare"),
        )
        .a(het(7..8))
        .b(het(6..7)),
    );
    sites.push(
        Spec::raw(
            7200,
            "T",
            "G",
            &format!("Gene_with_variant=EDG9;Variant_severity=HIGH;Variant_consequence_calculated=stop_gained;{tail}0.003;Variant_frequency_as_text=Rare;E_FLAG"),
        )
        .a(het(8..9)),
    );
    sites.push(
        Spec::raw(
            8000,
            "A",
            "C",
            &format!("Gene_with_variant=EDG10&EDG11;Variant_severity=MODERATE;Variant_consequence_calculated=missense_variant&splice_region_variant;{tail}0.004;Variant_frequency_as_text=Rare"),
        )
        .a(het(9..11))
        .b(het(7..8)),
    );
    sites.push(
        Spec::raw(
            8100,
            "G",
            "T",
            &format!("Gene_with_variant=EDG12&EDG13;Variant_severity=HIGH;Variant_consequence_calculated=stop_gained;{tail}0.005;Variant_frequency_as_text=Rare"),
        )
        .a(het(11..12)),
    );
    let numeric = [
        (9000, "."),
        (9100, "0.1,0.2"),
        (9200, "1e-05"),
        (9300, "0.5"),
        (9400, "0.50"),
        (9500, "5e-1"),
        (9600, "-10"),
        (9700, "0"),
        (9800, "1"),
        (9900, "0.2,0.3,0.4"),
    ];
    for (i, (position, value)) in numeric.into_iter().enumerate() {
        let text = if value == "-10" { "Novel" } else { "Rare" };
        sites.push(
            Spec::new(position, "C", "G", "EDG14", missense, value, text)
                .a(het(i as u32..i as u32 + 1))
                .b(het(i as u32 % 4..i as u32 % 4 + 1)),
        );
    }
    let forms = [
        "0/1", "1/1", "./1", "./.", "0/0", "./0", "0/2", "1/2", "0|1", "1|0", "1|1", ".|1", ".|.",
        "1|.",
    ];
    let form_calls = |count: usize| -> Calls {
        forms
            .iter()
            .take(count)
            .enumerate()
            .map(|(i, f)| (i as u32, gt(f)))
            .collect()
    };
    sites.push(
        Spec::new(10_000, "A", "T", "EDG15", "stop_gained", "0.0001", "Rare")
            .a(form_calls(forms.len()))
            .b(form_calls(6)),
    );
    sites.push(
        Spec::new(10_100, "A", "T", "EDG15", "stop_gained", "0.0002", "Rare")
            .a(forms
                .iter()
                .rev()
                .enumerate()
                .map(|(i, f)| (i as u32, gt(f)))
                .collect())
            .b(forms
                .iter()
                .rev()
                .take(6)
                .enumerate()
                .map(|(i, f)| (i as u32, gt(f)))
                .collect()),
    );
    sites.push(
        Spec::new(11_000, "C", "G", "EDG16", missense, "0.0001", "Rare")
            .a(Vec::new())
            .b(Vec::new()),
    );
    sites.push(
        Spec::new(11_100, "C", "G", "EDG16", missense, "0.0002", "Rare")
            .a(het(0..1))
            .b(het(0..1)),
    );
    sites.push(
        Spec::new(11_200, "C", "G", "EDG16", missense, "0.0003", "Rare")
            .a(het(0..5))
            .b(het(0..5)),
    );
    sites.push(
        Spec::new(11_300, "C", "G", "EDG16", missense, "0.0004", "Rare")
            .a(het(0..6))
            .b(het(0..3)),
    );
    sites.push(
        Spec::new(11_400, "C", "G", "EDG16", missense, "0.005", "Rare")
            .a(calls(&[(0..7, "0|1"), (7..12, "1|1")]))
            .b(calls(&[(0..6, "0|1"), (6..9, "1|1")])),
    );
    sites.push(
        Spec::new(11_500, "C", "G", "EDG16", missense, "0.0005", "Rare")
            .a(calls(&[(0..3, "./.")]))
            .b(calls(&[(0..2, "./.")])),
    );
    sites.push(
        Spec::new(11_600, "C", "G", "EDG16", missense, "0.0006", "Rare")
            .a(calls(&[(0..4, "./1")]))
            .b(calls(&[(0..1, "./1")])),
    );
    sites.push(
        Spec::new(11_700, "C", "G", "EDG16", missense, "0.0007", "Rare")
            .a(calls(&[(0..4, "0/1"), (4..6, "1/1")]))
            .b(calls(&[(0..3, "0/1"), (3..4, "1/1")])),
    );
    sites.push(
        Spec::new(12_000, "G", "C", "EDG17", missense, "0.02", "Common")
            .a(het(0..9))
            .b(het(4..9)),
    );
    sites.push(Spec::new(12_100, "G", "C", "EDG17", missense, "0.0008", "Rare").a(het(2..3)));
    sites.push(Spec::new(12_200, "G", "C", "EDG17", missense, "0.0009", "Rare").b(het(2..3)));
    sites.push(
        Spec::new(
            13_000,
            "ACGT",
            "A",
            "EDG18",
            "frameshift_variant",
            "0.0001",
            "Rare",
        )
        .a(het(0..2))
        .b(het(0..1)),
    );
    sites.push(
        Spec::new(
            13_100,
            "A",
            "ACG",
            "EDG18",
            "frameshift_variant",
            "0.0002",
            "Rare",
        )
        .a(het(3..5))
        .b(het(1..2)),
    );
    sites.push(
        Spec::new(14_000, "T", "A", "", missense, "0.001", "Rare")
            .without_gene()
            .a(het(0..2))
            .b(het(0..2)),
    );
    sites.push(
        Spec::raw(
            14_100,
            "T",
            "A",
            "Gene_with_variant=EDG19;Variant_severity=LOW;Variant_class=SNV;Variant_frequency_in_gnomAD=0.01;Variant_frequency_as_text=Common",
        )
        .a(het(2..4))
        .b(het(2..4)),
    );
    sites.push(
        Spec::new(248_956_001, "G", "C", "EDG3", missense, "0.00001", "Rare")
            .a(het(0..2))
            .b(het(0..1)),
    );
    sites
}

fn chr2() -> Vec<Spec> {
    vec![
        Spec::new(999, "A", "C", "EDG20", "missense_variant", "0.001", "Rare").b(het(0..2)),
        Spec::new(1000, "A", "C", "EDG20", "missense_variant", "0.001", "Rare").b(het(2..4)),
        Spec::new(
            1999,
            "G",
            "T",
            "EDG20",
            "splice_region_variant",
            "0.002",
            "Rare",
        )
        .b(het(4..6)),
        Spec::new(
            2000,
            "G",
            "T",
            "EDG21",
            "synonymous_variant",
            "0.003",
            "Rare",
        )
        .b(het(6..8)),
        Spec::new(5000, "C", "A", "EDG21", "start_lost", "0.004", "Rare").b(het(8..15)),
    ]
}

fn chr3() -> Vec<Spec> {
    vec![
        Spec::new(500, "A", "G", "EDG22", "missense_variant", "0.001", "Rare").a(calls(&[
            (0..2, "0/2"),
            (2..3, "1/2"),
            (3..4, "0/1"),
        ])),
        Spec::new(1500, "A", "G", "EDG22", "missense_variant", "0.002", "Rare")
            .a(calls(&[(0..3, "./."), (3..4, "1/1")])),
        Spec::new(2500, "C", "T", "EDG23", "stop_gained", "0.003", "Rare").a(calls(&[
            (0..1, "1|0"),
            (1..2, "0|1"),
            (2..3, "1|1"),
        ])),
        Spec::new(3500, "C", "T", "EDG23", "stop_gained", "0.004", "Rare").a(Vec::new()),
    ]
}

fn chrx() -> Vec<Spec> {
    vec![
        Spec::new(999, "T", "C", "EDG24", "missense_variant", "0.001", "Rare")
            .a(calls(&[(0..2, "1|1")])),
        Spec::new(
            1000,
            "T",
            "C",
            "EDG24",
            "synonymous_variant",
            "0.002",
            "Rare",
        )
        .a(calls(&[(0..2, "1|1"), (2..4, "0|1")])),
        Spec::new(
            60_000,
            "G",
            "A",
            "EDG25",
            "splice_donor_variant",
            "0.003",
            "Rare",
        )
        .a(het(5..8)),
    ]
}

fn chr4(seed: u64) -> Vec<Spec> {
    (0..FILLER_SITES)
        .map(|k| {
            let value = format_frequency(f64::from(k + 1) / 100_000.0);
            let text = if f64::from(k + 1) / 100_000.0 >= 0.01 {
                "Common"
            } else {
                "Rare"
            };
            let consequences = [
                "missense_variant",
                "synonymous_variant",
                "splice_region_variant",
                "stop_gained",
                "splice_donor_variant",
                "start_lost",
            ];
            let mut spec = Spec::new(
                1000 + 300 * k,
                ["A", "C", "G", "T"][(k % 4) as usize],
                ["C", "G", "T", "A"][(k % 4) as usize],
                &format!("EDG4{:02}", k / 20),
                consequences[(k % 6) as usize],
                &value,
                text,
            );
            let a = filler_calls(seed, k, A_SAMPLES);
            let b = filler_calls(seed ^ 0xB, k, B_SAMPLES);
            spec.a = (!a.is_empty()).then_some(a);
            spec.b = (!b.is_empty()).then_some(b);
            spec
        })
        .collect()
}

fn filler_calls(seed: u64, site: u32, samples: u32) -> Calls {
    let mut rng = Source::new(mix(seed ^ mix(0xE4_0000_0000 ^ u64::from(site))));
    let chance = 0.04 + f64::from(site % 7) * 0.04;
    let mut calls = Vec::new();
    for i in 0..samples {
        if !rng.chance(chance) {
            continue;
        }
        let cell = if rng.chance(0.1) {
            HOM_ALT
        } else if rng.bits() & 1 == 0 {
            HET_REF_FIRST
        } else {
            HET_ALT_FIRST
        };
        calls.push((i, cell));
    }
    calls
}

struct EdgeFile {
    group: usize,
    chromosome: &'static str,
    gzipped: bool,
    sites: Vec<Spec>,
}

fn files(seed: u64) -> Vec<EdgeFile> {
    vec![
        EdgeFile {
            group: GROUP_A,
            chromosome: "chr1",
            gzipped: true,
            sites: chr1(),
        },
        EdgeFile {
            group: GROUP_B,
            chromosome: "chr1",
            gzipped: true,
            sites: chr1(),
        },
        EdgeFile {
            group: GROUP_B,
            chromosome: "chr2",
            gzipped: false,
            sites: chr2(),
        },
        EdgeFile {
            group: GROUP_A,
            chromosome: "chr3",
            gzipped: true,
            sites: chr3(),
        },
        EdgeFile {
            group: GROUP_A,
            chromosome: "chr4",
            gzipped: true,
            sites: chr4(seed),
        },
        EdgeFile {
            group: GROUP_B,
            chromosome: "chr4",
            gzipped: true,
            sites: chr4(seed),
        },
        EdgeFile {
            group: GROUP_A,
            chromosome: "chrX",
            gzipped: false,
            sites: chrx(),
        },
    ]
}

/// The two groups of the edge dataset, with the index order shuffled from `seed`.
pub fn cohort(seed: u64) -> Cohort {
    let shuffle = |mut list: Vec<IndexedSample>, salt: u64| {
        let mut rng = Source::new(mix(seed ^ salt));
        for i in (1..list.len()).rev() {
            let j = (rng.bits() % (i as u64 + 1)) as usize;
            list.swap(i, j);
        }
        list
    };
    let a_header: Vec<String> = (1..=A_SAMPLES).map(|n| format!("EDGEA{n:02}")).collect();
    let mut a_indexed: Vec<IndexedSample> = (1..=A_SAMPLES + 1)
        .map(|n| IndexedSample {
            sample: format!("EDGEA{n:02}"),
            patient: A_BASE + n,
        })
        .collect();
    a_indexed.sort_by_key(|s| s.patient);
    let mut a_pheno: Vec<u32> = a_indexed.iter().map(|s| s.patient).collect();
    a_pheno.extend([A_BASE + 101, A_BASE + 102, A_BASE + 103]);
    let mut b_header: Vec<String> = (1..=B_SAMPLES).map(|n| format!("EDGEB{n:02}")).collect();
    b_header.insert(B_EXTRA_AT as usize, "EDGEBXX".to_string());
    let b_indexed: Vec<IndexedSample> = (1..=B_SAMPLES)
        .map(|n| IndexedSample {
            sample: format!("EDGEB{n:02}"),
            patient: B_BASE + n,
        })
        .collect();
    let b_pheno: Vec<u32> = b_indexed
        .iter()
        .map(|s| s.patient)
        .filter(|p| *p != B_BASE + B_SAMPLES)
        .collect();
    Cohort {
        groups: vec![
            Group {
                name: "edgeA".to_string(),
                header_samples: a_header,
                indexed: shuffle(a_indexed, 0xA),
                phenotype_patients: a_pheno,
            },
            Group {
                name: "edgeB".to_string(),
                header_samples: b_header,
                indexed: shuffle(b_indexed, 0xB),
                phenotype_patients: b_pheno,
            },
        ],
    }
}

fn column(group: usize, sample: u32) -> u32 {
    if group == GROUP_B && sample >= B_EXTRA_AT {
        sample + 1
    } else {
        sample
    }
}

fn write_file(group: &Group, file: &EdgeFile, path: &Path) -> io::Result<u64> {
    let handle = BufWriter::new(File::create(path)?);
    let write_body = |out: &mut dyn Write| -> io::Result<u64> {
        let mut records = 0;
        write_header(
            &mut *out,
            file.chromosome,
            &group.header_samples,
            &[FLAG_HEADER],
        )?;
        let mut builder = RowBuilder::new(group.header_samples.len());
        for site in &file.sites {
            let chosen = if file.group == GROUP_A {
                &site.a
            } else {
                &site.b
            };
            let Some(site_calls) = chosen else { continue };
            let mapped: Calls = site_calls
                .iter()
                .map(|(i, cell)| (column(file.group, *i), *cell))
                .collect();
            let info = site.info_text();
            let row = builder.build(
                file.chromosome,
                site.position,
                site.reference,
                site.alternate,
                info.as_bytes(),
                &mapped,
            );
            out.write_all(row)?;
            records += 1;
        }
        Ok(records)
    };
    if file.gzipped {
        let mut out = BgzfWriter::new(handle, 6);
        let n = write_body(&mut out)?;
        out.finish()?;
        Ok(n)
    } else {
        let mut out = handle;
        let n = write_body(&mut out)?;
        out.flush()?;
        Ok(n)
    }
}

/// Writes the edge dataset under `dir` in the layout of a random dataset.
///
/// # Errors
/// Returns the first I/O error.
pub fn write_edge(dir: &Path, seed: u64) -> io::Result<Output> {
    let started = Instant::now();
    let cohort = cohort(seed);
    fs::create_dir_all(dir.join("vcf"))?;
    fs::create_dir_all(dir.join("index"))?;
    fs::create_dir_all(dir.join("pheno"))?;
    let mut output = Output::default();
    let all = files(seed);
    let mut per_group = vec![0usize; cohort.groups.len()];
    for file in &all {
        let group = &cohort.groups[file.group];
        let name = vcf_file_name(&group.name, file.chromosome, file.gzipped);
        let path = dir.join("vcf").join(&name);
        let records = write_file(group, file, &path)?;
        output.records += records;
        output.vcf_bytes += fs::metadata(&path)?.len();
        output.files += 1;
        per_group[file.group] += 1;
    }
    for (number, group) in cohort.groups.iter().enumerate() {
        let mut index = BufWriter::new(File::create(
            dir.join("index")
                .join(format!("{}.vcfIndex.tsv", group.name)),
        )?);
        writeln!(index, "{}", super::cohort::INDEX_HEADER)?;
        for file in all.iter().filter(|f| f.group == number) {
            let name = vcf_file_name(&group.name, file.chromosome, file.gzipped);
            group.write_index_line(
                &mut index,
                &format!("vcf/{name}"),
                index_label(file.chromosome),
                file.gzipped,
            )?;
        }
        index.flush()?;
        let mut pheno = BufWriter::new(File::create(
            dir.join("pheno")
                .join(format!("{}-allConcepts.csv", group.name)),
        )?);
        write_phenotypes(group, seed, &mut pheno)?;
        pheno.flush()?;
        output.files += 2;
    }
    write_groups_summary(&cohort, dir, &per_group)?;
    output.files += 1;
    output.seconds = started.elapsed().as_secs_f64();
    Ok(output)
}
