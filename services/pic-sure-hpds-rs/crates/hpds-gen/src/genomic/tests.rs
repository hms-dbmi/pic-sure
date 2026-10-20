use std::collections::{BTreeMap, HashMap, HashSet};
use std::fs;
use std::io::Read;
use std::path::Path;

use flate2::read::MultiGzDecoder;

use super::edge::{EDGE_ITEMS, write_edge};
use super::profile::{FrequencyText, VariantClass};
use super::sites::{SiteStream, chromosome_target, gene_symbol};
use super::writer::write_dataset;
use super::{CHROMOSOMES, Preset, Shape};

fn small_shape() -> Shape {
    Shape {
        name: "tst".to_string(),
        group_samples: vec![30, 20, 11],
        variants: 60_000,
    }
}

fn read_all(dir: &Path) -> BTreeMap<String, Vec<u8>> {
    let mut out = BTreeMap::new();
    let mut stack = vec![dir.to_path_buf()];
    while let Some(next) = stack.pop() {
        for entry in fs::read_dir(&next).unwrap() {
            let path = entry.unwrap().path();
            if path.is_dir() {
                stack.push(path);
            } else {
                let name = path
                    .strip_prefix(dir)
                    .unwrap()
                    .to_string_lossy()
                    .to_string();
                out.insert(name, fs::read(&path).unwrap());
            }
        }
    }
    out
}

fn decode(bytes: &[u8]) -> String {
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let mut text = String::new();
        MultiGzDecoder::new(bytes)
            .read_to_string(&mut text)
            .unwrap();
        text
    } else {
        String::from_utf8(bytes.to_vec()).unwrap()
    }
}

struct Record {
    position: u32,
    reference: String,
    alternate: String,
    info: String,
    cells: Vec<String>,
}

impl Record {
    fn field(&self, key: &str) -> Option<&str> {
        self.info
            .split(';')
            .find_map(|t| t.strip_prefix(key).and_then(|r| r.strip_prefix('=')))
    }

    fn site(&self) -> (u32, &str, &str) {
        (self.position, &self.reference, &self.alternate)
    }

    fn carriers(&self, class: u8) -> usize {
        self.cells.iter().filter(|c| call_class(c) == class).count()
    }
}

struct Vcf {
    group: String,
    chromosome: String,
    gzipped: bool,
    samples: Vec<String>,
    records: Vec<Record>,
}

fn call_class(cell: &str) -> u8 {
    (cell.bytes().map(u32::from).sum::<u32>() % 7) as u8
}

const HE: u8 = 4;
const HO: u8 = 5;
const HEN: u8 = 2;
const HON: u8 = 6;

fn parse_vcf(name: &str, bytes: &[u8]) -> Vcf {
    let text = decode(bytes);
    let mut parts = name.trim_start_matches("vcf/").split('.');
    let group = parts.next().unwrap().to_string();
    let chromosome = parts.next().unwrap().to_string();
    let mut samples = Vec::new();
    let mut records = Vec::new();
    for line in text.lines() {
        if let Some(header) = line.strip_prefix("#CHROM") {
            samples = header.split('\t').skip(9).map(String::from).collect();
        } else if !line.starts_with('#') {
            let f: Vec<&str> = line.split('\t').collect();
            records.push(Record {
                position: f[1].parse().unwrap(),
                reference: f[3].to_string(),
                alternate: f[4].to_string(),
                info: f[7].to_string(),
                cells: f[9..].iter().map(|c| c.to_string()).collect(),
            });
        }
    }
    Vcf {
        group,
        chromosome,
        gzipped: bytes.starts_with(&[0x1f, 0x8b]),
        samples,
        records,
    }
}

fn vcfs(files: &BTreeMap<String, Vec<u8>>) -> Vec<Vcf> {
    files
        .iter()
        .filter(|(name, _)| name.starts_with("vcf/"))
        .map(|(name, bytes)| parse_vcf(name, bytes))
        .collect()
}

struct IndexLine {
    path: String,
    gzipped: bool,
    samples: Vec<String>,
    patients: Vec<u32>,
}

fn index_lines(files: &BTreeMap<String, Vec<u8>>, group: &str) -> Vec<IndexLine> {
    let text = String::from_utf8(files[&format!("index/{group}.vcfIndex.tsv")].clone()).unwrap();
    text.lines()
        .skip(1)
        .map(|line| {
            let f: Vec<&str> = line.split('\t').collect();
            IndexLine {
                path: f[0].to_string(),
                gzipped: f[3] == "1",
                samples: f[4]
                    .trim_matches('"')
                    .split(',')
                    .map(String::from)
                    .collect(),
                patients: f[5]
                    .trim_matches('"')
                    .split(',')
                    .map(|p| p.parse().unwrap())
                    .collect(),
            }
        })
        .collect()
}

fn phenotype_patients(files: &BTreeMap<String, Vec<u8>>, group: &str) -> HashSet<u32> {
    let text = String::from_utf8(files[&format!("pheno/{group}-allConcepts.csv")].clone()).unwrap();
    text.lines()
        .map(|l| {
            l.split(',')
                .next()
                .unwrap()
                .trim_matches('"')
                .parse()
                .unwrap()
        })
        .collect()
}

fn generate(shape: &Shape, seed: u64, threads: usize) -> BTreeMap<String, Vec<u8>> {
    let dir = tempfile::tempdir().unwrap();
    write_dataset(shape, seed, dir.path(), threads, false).unwrap();
    read_all(dir.path())
}

fn generate_edge(seed: u64) -> BTreeMap<String, Vec<u8>> {
    let dir = tempfile::tempdir().unwrap();
    write_edge(dir.path(), seed).unwrap();
    read_all(dir.path())
}

#[test]
fn same_seed_gives_identical_bytes_whatever_the_thread_count() {
    let first = generate(&small_shape(), 7, 1);
    let second = generate(&small_shape(), 7, 3);
    assert_eq!(first, second);
    assert_ne!(first, generate(&small_shape(), 8, 2));
    assert_eq!(first.len(), 3 * CHROMOSOMES.len() + 3 + 3 + 1);
}

#[test]
fn edge_same_seed_gives_identical_bytes() {
    let first = generate_edge(5);
    assert_eq!(first, generate_edge(5));
    assert_ne!(first, generate_edge(6));
}

#[test]
fn bgzf_files_are_blocked_gzip_ending_in_the_eof_block() {
    let files = generate(&small_shape(), 3, 2);
    let eof: [u8; 28] = [
        0x1f, 0x8b, 0x08, 0x04, 0, 0, 0, 0, 0, 0xff, 0x06, 0, 0x42, 0x43, 0x02, 0, 0x1b, 0, 0x03,
        0, 0, 0, 0, 0, 0, 0, 0, 0,
    ];
    let (name, bytes) = files
        .iter()
        .find(|(n, _)| n.ends_with("tstA.chr1.vcf.gz"))
        .unwrap();
    assert!(bytes.ends_with(&eof), "{name} lacks the EOF block");
    let mut at = 0;
    let mut blocks = 0;
    while at < bytes.len() {
        assert_eq!(&bytes[at..at + 4], &[0x1f, 0x8b, 0x08, 0x04]);
        assert_eq!(&bytes[at + 12..at + 14], b"BC");
        let size = u16::from_le_bytes([bytes[at + 16], bytes[at + 17]]) as usize + 1;
        at += size;
        blocks += 1;
    }
    assert_eq!(at, bytes.len());
    assert!(blocks >= 2);
    assert!(decode(bytes).starts_with("##fileformat=VCFv4.3\n"));
}

#[test]
fn random_vcfs_are_sorted_biallelic_and_list_only_present_sites() {
    let files = generate(&small_shape(), 11, 2);
    let all = vcfs(&files);
    assert_eq!(all.len(), 3 * CHROMOSOMES.len());
    let mut carried = 0usize;
    let mut records = 0usize;
    for vcf in &all {
        assert!(vcf.gzipped);
        let mut last = 0;
        for r in &vcf.records {
            assert!(
                r.position >= last,
                "{} {} unsorted",
                vcf.group,
                vcf.chromosome
            );
            last = r.position;
            assert!(!r.alternate.contains(','));
            assert_ne!(r.reference, r.alternate);
            assert_eq!(r.cells.len(), vcf.samples.len());
            assert!(r.cells.iter().all(|c| c.len() == 3));
            records += 1;
            carried += usize::from(r.cells.iter().any(|c| c != "0|0"));
        }
    }
    assert!(records > 3_000);
    let empty = records - carried;
    assert!(
        (empty as f64) < 0.05 * records as f64,
        "{empty} of {records} listed sites have no carrier"
    );
}

#[test]
fn groups_list_different_sites_and_share_some() {
    let files = generate(&small_shape(), 13, 2);
    let all = vcfs(&files);
    let sites = |group: &str| -> HashSet<(String, u32, String, String)> {
        all.iter()
            .filter(|v| v.group == group)
            .flat_map(|v| {
                v.records.iter().map(|r| {
                    (
                        v.chromosome.clone(),
                        r.position,
                        r.reference.clone(),
                        r.alternate.clone(),
                    )
                })
            })
            .collect()
    };
    let a = sites("tstA");
    let b = sites("tstB");
    assert!(a.intersection(&b).count() > 100);
    assert!(a.difference(&b).count() > 100);
    assert!(b.difference(&a).count() > 100);
}

#[test]
fn patients_are_disjoint_across_groups_and_the_index_matches_the_header() {
    let files = generate(&small_shape(), 17, 2);
    let mut seen = HashSet::new();
    for (group, expected) in [("tstA", 30), ("tstB", 20), ("tstC", 11)] {
        let lines = index_lines(&files, group);
        assert_eq!(lines.len(), CHROMOSOMES.len());
        for line in &lines {
            assert_eq!(line.samples.len(), expected);
            assert_eq!(line.patients.len(), expected);
            assert!(line.gzipped);
            assert!(line.path.starts_with("vcf/"));
        }
        let header: HashSet<String> = vcfs(&files)
            .into_iter()
            .find(|v| v.group == group && v.chromosome == "chr1")
            .unwrap()
            .samples
            .into_iter()
            .collect();
        assert_eq!(
            lines[0].samples.iter().cloned().collect::<HashSet<_>>(),
            header
        );
        let mut ordered = lines[0].samples.clone();
        ordered.sort();
        assert_ne!(
            ordered, lines[0].samples,
            "the index order should differ from the header's"
        );
        for p in &lines[0].patients {
            assert!(seen.insert(*p), "patient {p} is in two groups");
        }
        assert_eq!(
            phenotype_patients(&files, group),
            lines[0].patients.iter().copied().collect()
        );
    }
}

#[test]
fn the_site_stream_follows_the_fitted_profile() {
    let target = 250_000;
    let sites: Vec<_> = SiteStream::new(1, 0, target).collect();
    assert_eq!(sites.len() as u64, target);
    let share = |count: usize| count as f64 / sites.len() as f64;
    let rare = share(
        sites
            .iter()
            .filter(|s| s.text == FrequencyText::Rare)
            .count(),
    );
    let common = share(
        sites
            .iter()
            .filter(|s| s.text == FrequencyText::Common)
            .count(),
    );
    let novel = share(
        sites
            .iter()
            .filter(|s| s.text == FrequencyText::Novel)
            .count(),
    );
    assert!((rare - 0.8886).abs() < 0.01, "rare {rare}");
    assert!((common - 0.0929).abs() < 0.01, "common {common}");
    assert!((novel - 0.0185).abs() < 0.006, "novel {novel}");
    let missense = share(
        sites
            .iter()
            .filter(|s| s.consequence.consequence == "missense_variant")
            .count(),
    );
    assert!((missense - 0.478).abs() < 0.02, "missense {missense}");
    let snv = share(
        sites
            .iter()
            .filter(|s| s.consequence.class == VariantClass::Snv)
            .count(),
    );
    assert!((snv - 0.978).abs() < 0.006, "snv {snv}");
    let no_gene = share(sites.iter().filter(|s| s.gene.is_none()).count());
    assert!((no_gene - 0.0193).abs() < 0.004, "no gene {no_gene}");
    let repeats = share(
        sites
            .windows(2)
            .filter(|w| w[0].site_key() == w[1].site_key())
            .count(),
    );
    assert!((repeats - 0.054).abs() < 0.01, "repeats {repeats}");
    let genes: HashSet<_> = sites.iter().filter_map(|s| s.gene.clone()).collect();
    let per_gene = sites.len() as f64 / genes.len() as f64;
    assert!(
        (20.0..80.0).contains(&per_gene),
        "{per_gene} sites per gene"
    );
    let common_fractions: Vec<f64> = sites
        .iter()
        .filter(|s| s.text == FrequencyText::Common)
        .map(|s| s.carrier_fraction)
        .collect();
    let mean = common_fractions.iter().sum::<f64>() / common_fractions.len() as f64;
    assert!(
        (0.15..0.35).contains(&mean),
        "common carrier fraction {mean}"
    );
    assert!(sites.windows(2).all(|w| w[0].position <= w[1].position));
    assert!(sites.last().unwrap().position <= super::CHROMOSOME_LENGTHS[0]);
}

#[test]
fn chromosome_targets_follow_the_fitted_shares_and_gene_symbols_are_distinct() {
    let targets: Vec<u64> = (0..CHROMOSOMES.len())
        .map(|c| chromosome_target(c, 2_000_000))
        .collect();
    assert!((targets.iter().sum::<u64>() as i64 - 2_000_000).abs() < 30);
    assert!(targets[0] > targets[21] && targets[22] < 5_000);
    let symbols: HashSet<String> = (0..60_000).map(gene_symbol).collect();
    assert_eq!(symbols.len(), 60_000);
}

#[test]
fn presets_have_the_documented_sizes() {
    let gdev = Shape::of(Preset::Gdev).unwrap();
    assert_eq!(gdev.samples(), 10_000);
    assert_eq!(gdev.group_samples.len(), 4);
    assert_eq!(gdev.variants, 2_000_000);
    let gbench = Shape::of(Preset::Gbench).unwrap();
    assert_eq!(gbench.samples(), 50_000);
    assert_eq!(gbench.group_samples.len(), 10);
    assert_eq!(gbench.variants, 8_000_000);
    assert!(Shape::of(Preset::Edge).is_none());
    gdev.validate().unwrap();
    gbench.validate().unwrap();
}

struct EdgeData {
    files: BTreeMap<String, Vec<u8>>,
    vcfs: Vec<Vcf>,
}

impl EdgeData {
    fn load() -> Self {
        let files = generate_edge(1);
        let vcfs = vcfs(&files);
        Self { files, vcfs }
    }

    fn records(&self) -> impl Iterator<Item = (&Vcf, &Record)> {
        self.vcfs
            .iter()
            .flat_map(|v| v.records.iter().map(move |r| (v, r)))
    }

    fn file(&self, group: &str, chromosome: &str) -> &Vcf {
        self.vcfs
            .iter()
            .find(|v| v.group == group && v.chromosome == chromosome)
            .unwrap()
    }

    fn any_cell(&self, f: impl Fn(&str) -> bool) -> bool {
        self.records().any(|(_, r)| r.cells.iter().any(|c| f(c)))
    }

    fn sites(&self, group: &str, chromosome: &str) -> HashSet<(u32, String, String)> {
        self.file(group, chromosome)
            .records
            .iter()
            .map(|r| (r.position, r.reference.clone(), r.alternate.clone()))
            .collect()
    }
}

fn numeric(r: &Record) -> Option<&str> {
    r.field("Variant_frequency_in_gnomAD")
}

fn edge_checks(d: &EdgeData) -> Vec<(&'static str, bool)> {
    let class_set: HashSet<u8> = d
        .records()
        .flat_map(|(_, r)| r.cells.iter().map(|c| call_class(c)))
        .collect();
    let gnomad_values = |vcf: &Vcf| -> HashSet<String> {
        vcf.records
            .iter()
            .filter_map(|r| numeric(r).map(String::from))
            .collect()
    };
    let distinct_numeric = |vcf: &Vcf| {
        gnomad_values(vcf)
            .iter()
            .filter_map(|v| v.parse::<f32>().ok().map(f32::to_bits))
            .collect::<HashSet<_>>()
            .len()
    };
    let chr1_a = d.file("edgeA", "chr1");
    let chr1_b = d.file("edgeB", "chr1");
    let positions = |v: &Vcf| -> HashSet<u32> { v.records.iter().map(|r| r.position).collect() };
    let two_annotations = d.vcfs.iter().any(|v| {
        v.records.windows(2).any(|w| {
            w[0].site() == w[1].site()
                && w[0].info != w[1].info
                && w[0].cells == w[1].cells
                && w[0].field("Gene_with_variant") != w[1].field("Gene_with_variant")
        })
    });
    let prefix_collision = d.vcfs.iter().any(|v| {
        v.records.iter().any(|a| {
            v.records.iter().any(|b| {
                a.position == b.position
                    && a.reference == b.reference
                    && a.alternate.len() < b.alternate.len()
                    && b.alternate.starts_with(&a.alternate)
            })
        })
    });
    let site_forms = |v: &Vcf, p: u32| -> Option<usize> {
        v.records
            .iter()
            .find(|r| r.position == p)
            .map(|r| r.carriers(HE))
    };
    let form_differs = [11_300u32, 11_400, 11_500].iter().any(|p| {
        match (site_forms(chr1_a, *p), site_forms(chr1_b, *p)) {
            (Some(a), Some(b)) => (a > 5) != (b > 5) && a > 0 && b > 0,
            _ => false,
        }
    });
    let a_chr1 = d.sites("edgeA", "chr1");
    let b_chr1 = d.sites("edgeB", "chr1");
    let index_a = index_lines(&d.files, "edgeA");
    let missing_sample = index_a.iter().any(|line| {
        let name = line.path.trim_start_matches("vcf/");
        let vcf = d
            .vcfs
            .iter()
            .find(|v| name.starts_with(&format!("{}.{}.", v.group, v.chromosome)))
            .unwrap();
        line.samples.iter().any(|s| !vcf.samples.contains(s))
    });
    let unindexed_header_sample = {
        let line = &index_lines(&d.files, "edgeB")[0];
        d.file("edgeB", "chr1")
            .samples
            .iter()
            .any(|s| !line.samples.contains(s))
    };
    let index_b = index_lines(&d.files, "edgeB");
    let indexed: HashSet<u32> = index_a
        .iter()
        .chain(index_b.iter())
        .flat_map(|l| l.patients.iter().copied())
        .collect();
    let phenotyped: HashSet<u32> = phenotype_patients(&d.files, "edgeA")
        .union(&phenotype_patients(&d.files, "edgeB"))
        .copied()
        .collect();
    let flags_agree = index_a
        .iter()
        .chain(index_b.iter())
        .all(|l| l.gzipped == l.path.ends_with(".gz"));
    vec![
        (
            EDGE_ITEMS[0],
            d.records()
                .any(|(_, r)| r.info.split(';').any(|t| !t.contains('='))),
        ),
        (
            EDGE_ITEMS[1],
            d.records().any(|(_, r)| {
                r.field("Gene_with_variant")
                    .is_some_and(|g| g.contains('&'))
            }) && d.records().any(|(_, r)| {
                r.field("Variant_consequence_calculated")
                    .is_some_and(|c| c.contains('&'))
            }),
        ),
        (
            EDGE_ITEMS[2],
            d.records()
                .any(|(_, r)| numeric(r).is_some_and(|v| v.contains(','))),
        ),
        (
            EDGE_ITEMS[3],
            d.records().any(|(_, r)| numeric(r) == Some(".")),
        ),
        (
            EDGE_ITEMS[4],
            distinct_numeric(d.file("edgeA", "chr4")) > 1_000
                && distinct_numeric(d.file("edgeB", "chr4")) > 1_000,
        ),
        (EDGE_ITEMS[5], {
            let values = gnomad_values(chr1_a);
            ["0.5", "0.50", "5e-1"].iter().all(|v| values.contains(*v))
        }),
        (EDGE_ITEMS[6], {
            let p = positions(chr1_a);
            [999, 1000, 1999, 2000].iter().all(|x| p.contains(x))
                && [999, 1000]
                    .iter()
                    .all(|x| positions(d.file("edgeB", "chr2")).contains(x))
        }),
        (EDGE_ITEMS[7], two_annotations),
        (EDGE_ITEMS[8], prefix_collision),
        (
            EDGE_ITEMS[9],
            d.any_cell(|c| c == "0/2") && d.any_cell(|c| c == "1/2"),
        ),
        (
            EDGE_ITEMS[10],
            d.any_cell(|c| c == "./1") && d.any_cell(|c| c == "./.") && d.any_cell(|c| c == ".|."),
        ),
        (EDGE_ITEMS[11], (1..=6).all(|c| class_set.contains(&c))),
        (
            EDGE_ITEMS[12],
            d.records().any(|(_, r)| r.carriers(HE) > 5)
                && d.records().any(|(_, r)| r.carriers(HO) > 3),
        ),
        (
            EDGE_ITEMS[13],
            d.records().any(|(_, r)| {
                r.carriers(HE) == 5 && r.carriers(HO) + r.carriers(HEN) + r.carriers(HON) == 0
            }) && d
                .records()
                .any(|(v, r)| v.chromosome == "chr1" && r.cells.iter().all(|c| c == "0|0")),
        ),
        (EDGE_ITEMS[14], form_differs),
        (EDGE_ITEMS[15], missing_sample && unindexed_header_sample),
        (
            EDGE_ITEMS[16],
            a_chr1.intersection(&b_chr1).count() > 20
                && d.sites("edgeA", "chr4")
                    .intersection(&d.sites("edgeB", "chr4"))
                    .count()
                    > 1_000,
        ),
        (
            EDGE_ITEMS[17],
            a_chr1.difference(&b_chr1).count() > 0 && b_chr1.difference(&a_chr1).count() > 0,
        ),
        (
            EDGE_ITEMS[18],
            d.vcfs.iter().any(|v| v.gzipped) && d.vcfs.iter().any(|v| !v.gzipped) && flags_agree,
        ),
        (
            EDGE_ITEMS[19],
            indexed.difference(&phenotyped).count() >= 1
                && phenotyped.difference(&indexed).count() >= 3,
        ),
    ]
}

#[test]
fn edge_covers_every_item_of_the_edge_list() {
    let data = EdgeData::load();
    let checks = edge_checks(&data);
    let named: Vec<&str> = checks.iter().map(|(n, _)| *n).collect();
    assert_eq!(named, EDGE_ITEMS.to_vec());
    let failed: Vec<&str> = checks
        .iter()
        .filter(|(_, ok)| !ok)
        .map(|(n, _)| *n)
        .collect();
    assert!(failed.is_empty(), "edge does not cover: {failed:?}");
}

#[test]
fn edge_groups_hold_disjoint_patients_and_the_listed_sites_have_carriers() {
    let data = EdgeData::load();
    let a = index_lines(&data.files, "edgeA");
    let b = index_lines(&data.files, "edgeB");
    let a_patients: HashSet<u32> = a[0].patients.iter().copied().collect();
    assert!(b[0].patients.iter().all(|p| !a_patients.contains(p)));
    let by_group: HashMap<&str, usize> = data
        .vcfs
        .iter()
        .map(|v| (v.group.as_str(), v.samples.len()))
        .collect();
    assert_eq!(by_group["edgeA"], 24);
    assert_eq!(by_group["edgeB"], 17);
    for vcf in data.vcfs.iter().filter(|v| v.group == "edgeA") {
        let last = vcf.samples.len() - 1;
        assert_eq!(vcf.samples[last], "EDGEA24");
        assert!(
            vcf.records.iter().any(|r| r.cells[last] != "0|0"),
            "the last header sample of {} carries no call",
            vcf.chromosome
        );
    }
    assert!(
        !data
            .file("edgeA", "chr1")
            .records
            .iter()
            .any(|r| r.cells.iter().any(|c| c == "0/2" || c == "1/2"))
    );
    let chr4_b = data.file("edgeB", "chr4");
    assert!(
        chr4_b
            .records
            .iter()
            .all(|r| r.cells.iter().any(|c| c != "0|0"))
    );
    assert!(chr4_b.records.len() < data.file("edgeA", "chr4").records.len() + 100);
}

struct Spectrum {
    samples: usize,
    listed: usize,
    single: f64,
    double: f64,
    over_five: f64,
}

fn spectrum(shape: &Shape, chromosome: usize) -> Vec<Spectrum> {
    use super::vcf::Gt;
    use super::writer::{group_slots, view_site};
    let slots = group_slots(shape);
    let target = chromosome_target(chromosome, shape.variants);
    let sites: Vec<_> = SiteStream::new(1, chromosome, target).collect();
    shape
        .group_samples
        .iter()
        .enumerate()
        .map(|(g, samples)| {
            let samples = *samples as usize;
            let mut calls: Vec<(u32, Gt)> = Vec::new();
            let (mut listed, mut one, mut two, mut many) = (0usize, 0usize, 0usize, 0usize);
            for site in &sites {
                if view_site(1, chromosome, site, g, slots[g], &mut calls) {
                    listed += 1;
                    match calls.len() {
                        1 => one += 1,
                        2 => two += 1,
                        n if n > 5 => many += 1,
                        _ => {}
                    }
                }
            }
            let share = |n: usize| n as f64 / listed as f64;
            Spectrum {
                samples,
                listed,
                single: share(one),
                double: share(two),
                over_five: share(many),
            }
        })
        .collect()
}

#[test]
fn every_group_size_keeps_the_fitted_carrier_spectrum() {
    let whole = Shape {
        name: "spec".to_string(),
        group_samples: vec![10_000],
        variants: 1_000_000,
    };
    let gdev = Shape {
        variants: 1_000_000,
        ..Shape::of(Preset::Gdev).unwrap()
    };
    let gbench = Shape {
        variants: 500_000,
        ..Shape::of(Preset::Gbench).unwrap()
    };
    let cases = [
        ("whole", whole, 0, 0.04),
        ("gdev", gdev, 0, 0.15),
        ("gbench", gbench, 7_000, 0.16),
    ];
    for (label, shape, smallest, tolerance) in cases {
        for s in spectrum(&shape, 20) {
            eprintln!(
                "{label} group of {}: listed {} single {:.3} double {:.3} over five {:.3}",
                s.samples, s.listed, s.single, s.double, s.over_five
            );
            if s.samples < smallest {
                continue;
            }
            let fitted = (0.526, 0.114, 0.236);
            let what = format!("{label} {} samples", s.samples);
            assert!(
                (s.single - fitted.0).abs() < tolerance,
                "{what}: singletons {}",
                s.single
            );
            assert!(
                (s.double - fitted.1).abs() < 0.04,
                "{what}: doubles {}",
                s.double
            );
            assert!(
                (s.over_five - fitted.2).abs() < tolerance,
                "{what}: over five {}",
                s.over_five
            );
        }
    }
}

#[test]
fn no_node_repeats_a_spec_string() {
    let files = generate(&small_shape(), 21, 2);
    for vcf in vcfs(&files) {
        let mut seen = HashSet::new();
        for r in &vcf.records {
            let spec = (
                r.position,
                r.reference.clone(),
                r.alternate.clone(),
                r.field("Gene_with_variant").map(String::from),
                r.field("Variant_consequence_calculated").map(String::from),
            );
            assert!(
                seen.insert(spec),
                "{} {} repeats a spec",
                vcf.group,
                vcf.chromosome
            );
        }
    }
    let mut seen = HashSet::new();
    for site in SiteStream::new(2, 0, 250_000) {
        assert!(
            seen.insert((
                site.position,
                site.reference.clone(),
                site.alternate.clone(),
                site.gene.clone(),
                site.consequence.consequence
            )),
            "spec repeated at {}",
            site.position
        );
    }
}

fn pheno_values(
    files: &BTreeMap<String, Vec<u8>>,
    group: &str,
    path: &str,
) -> HashMap<u32, String> {
    let text = String::from_utf8(files[&format!("pheno/{group}-allConcepts.csv")].clone()).unwrap();
    let quoted = format!("\"{path}\"");
    text.lines()
        .filter_map(|line| {
            let f: Vec<&str> = line.split(',').collect();
            (f[1] == quoted).then(|| {
                (
                    f[0].trim_matches('"').parse().unwrap(),
                    f[3].trim_matches('"').to_string(),
                )
            })
        })
        .collect()
}

#[test]
fn topmed_consents_name_the_group_of_every_genotyped_patient() {
    let files = generate(&small_shape(), 31, 2);
    for group in ["tstA", "tstB", "tstC"] {
        let genotyped: HashSet<u32> = index_lines(&files, group)
            .iter()
            .flat_map(|l| l.patients.iter().copied())
            .collect();
        let topmed = pheno_values(&files, group, "\\_topmed_consents\\");
        let consents = pheno_values(&files, group, "\\_consents\\");
        assert_eq!(topmed.keys().copied().collect::<HashSet<_>>(), genotyped);
        assert!(topmed.values().all(|v| v == group));
        assert_eq!(consents.len(), genotyped.len());
    }
}

#[test]
fn topmed_consents_skip_patients_without_genotypes() {
    let files = generate_edge(33);
    let mut without_genotype = 0;
    for group in ["edgeA", "edgeB"] {
        let genotyped: HashSet<u32> = index_lines(&files, group)
            .iter()
            .flat_map(|l| l.patients.iter().copied())
            .collect();
        let topmed = pheno_values(&files, group, "\\_topmed_consents\\");
        let consents = pheno_values(&files, group, "\\_consents\\");
        assert!(topmed.keys().all(|p| genotyped.contains(p)));
        for patient in consents.keys() {
            assert_eq!(topmed.contains_key(patient), genotyped.contains(patient));
            if !genotyped.contains(patient) {
                without_genotype += 1;
            }
        }
    }
    assert!(without_genotype >= 3);
}
