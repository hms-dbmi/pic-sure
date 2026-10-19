//! `hpds-etl genomic-migrate` and `genomic-dump` on exports built here in the layout the Java
//! `ExportGenomicForMigration` writes: what the store keeps, what the report counts, and what
//! is refused.

use std::path::{Path, PathBuf};

use hpds_etl::genomic::dump::dump_genomic;
use hpds_etl::genomic::migrate::{GenomicMigrateOptions, GenomicMigrateReport, genomic_migrate};
use hpds_etl::migrate::{ExportKind, seal_export};
use hpds_etl::{EtlError, Result};
use hpds_format::{
    CryptoKey, GENO_BLOCKS_FILE_NAME, GENO_INDEX_FILE_NAME, GenoBlocksReader, GenoIndex,
    Protection, RangeBound, build_java_compressed_index, java_string_cmp,
};

const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";
const EXPORT_ID: [u8; 16] = [7; 16];

/// Big-endian content as Java's `DataOutputStream` writes it.
#[derive(Default)]
struct Out(Vec<u8>);

impl Out {
    fn u8(&mut self, v: u8) {
        self.0.push(v);
    }
    fn i32(&mut self, v: i32) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn i64(&mut self, v: i64) {
        self.0.extend_from_slice(&v.to_be_bytes());
    }
    fn str(&mut self, s: &str) {
        self.i32(s.len() as i32);
        self.0.extend_from_slice(s.as_bytes());
    }
    fn list(&mut self, positions: &[u32]) {
        self.u8(0);
        self.i32(positions.len() as i32);
        for &p in positions {
            self.i32(p as i32);
        }
    }
    fn bitmap(&mut self, positions: &[u32], patients: u32) {
        self.u8(1);
        let mut bytes = vec![0u8; patients.div_ceil(8) as usize];
        for &p in positions {
            bytes[(p / 8) as usize] |= 1 << (p % 8);
        }
        self.i32(bytes.len() as i32);
        self.0.extend_from_slice(&bytes);
    }
}

/// One mask: Java's form (sparse or bitmask) and its positions.
#[derive(Clone)]
enum Mask {
    Sparse(Vec<u32>),
    Bitmask(Vec<u32>),
}

#[derive(Clone)]
enum Carriers {
    NotIndexed,
    Present(Vec<u32>),
    NoIndex,
}

#[derive(Clone)]
struct Bucket {
    contig: &'static str,
    bucket: i32,
    masks: Vec<(&'static str, [Option<Mask>; 4])>,
    metadata: Vec<(&'static str, Option<Vec<&'static str>>)>,
    specs: Vec<(i32, &'static str)>,
    carriers: Carriers,
}

#[derive(Clone)]
struct Range {
    lower: RangeBound,
    upper: RangeBound,
    entries: Vec<(f32, Vec<String>)>,
}

#[derive(Clone)]
struct Info {
    column: &'static str,
    description: &'static str,
    keys: Vec<(&'static str, Vec<i32>)>,
    ranges: Option<Vec<Range>>,
}

#[derive(Clone)]
struct Node {
    group: &'static str,
    dir: &'static str,
    patients: Vec<&'static str>,
    spec_count: i32,
    infos: Vec<Info>,
    buckets: Vec<Bucket>,
}

fn key() -> CryptoKey {
    CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap()
}

fn bound(out: &mut Out, bound: RangeBound) {
    match bound {
        RangeBound::Unbounded => {
            out.u8(0);
            out.i32(0);
        }
        RangeBound::Open(v) => {
            out.u8(1);
            out.i32(v.to_bits() as i32);
        }
        RangeBound::Closed(v) => {
            out.u8(2);
            out.i32(v.to_bits() as i32);
        }
    }
}

fn node_content(node: &Node) -> Vec<u8> {
    let p = node.patients.len() as u32;
    let mut out = Out::default();
    out.str(node.group);
    out.str(node.dir);
    out.i32(node.patients.len() as i32);
    for id in &node.patients {
        out.str(id);
    }
    out.i32(node.spec_count);
    let indexed = node
        .buckets
        .iter()
        .all(|b| !matches!(b.carriers, Carriers::NoIndex));
    out.u8(u8::from(indexed));
    out.i32(node.infos.len() as i32);
    for info in &node.infos {
        out.str(info.column);
        out.str(info.description);
        out.u8(u8::from(info.ranges.is_some()));
        out.i32(info.keys.len() as i32);
        for (k, ids) in &info.keys {
            out.str(k);
            out.i32(ids.len() as i32);
            for &id in ids {
                out.i32(id);
            }
        }
        match &info.ranges {
            None => out.i32(-1),
            Some(ranges) => {
                out.i32(ranges.len() as i32);
                for r in ranges {
                    bound(&mut out, r.lower);
                    bound(&mut out, r.upper);
                    out.i32(r.entries.len() as i32);
                    for (value, keys) in &r.entries {
                        out.i32(value.to_bits() as i32);
                        out.i32(keys.len() as i32);
                        for k in keys {
                            out.str(k);
                        }
                    }
                }
            }
        }
    }
    let mut records = 0i64;
    let mut metadata = 0i64;
    for b in &node.buckets {
        out.u8(1);
        out.str(b.contig);
        out.i32(b.bucket);
        out.i32(b.masks.len() as i32);
        for (spec, masks) in &b.masks {
            out.str(spec);
            for mask in masks {
                match mask {
                    None => out.u8(0),
                    Some(Mask::Sparse(positions)) => {
                        out.u8(1);
                        out.list(positions);
                    }
                    Some(Mask::Bitmask(positions)) => {
                        out.u8(2);
                        out.bitmap(positions, p);
                    }
                }
            }
            records += 1;
        }
        out.i32(b.metadata.len() as i32);
        for (spec, strings) in &b.metadata {
            out.str(spec);
            match strings {
                None => out.i32(-1),
                Some(strings) => {
                    out.i32(strings.len() as i32);
                    for s in strings {
                        out.str(s);
                    }
                }
            }
            metadata += 1;
        }
        out.i32(b.specs.len() as i32);
        for (id, spec) in &b.specs {
            out.i32(*id);
            out.str(spec);
        }
        match &b.carriers {
            Carriers::NotIndexed => out.u8(0),
            Carriers::Present(positions) => {
                out.u8(1);
                out.list(positions);
            }
            Carriers::NoIndex => out.u8(2),
        }
    }
    out.u8(0);
    out.i64(node.buckets.len() as i64);
    out.i64(records);
    out.i64(metadata);
    out.i64(0);
    out.0
}

/// Writes an export of `nodes` in `mode` into `dir`, as the Java exporter does.
fn write_export(dir: &Path, mode: &str, nodes: &[Node]) {
    std::fs::create_dir_all(dir).unwrap();
    let mut manifest = Out::default();
    manifest.str(mode);
    manifest.i32(nodes.len() as i32);
    for (i, node) in nodes.iter().enumerate() {
        let file = format!("node-{i:05}.bin");
        let sealed = seal_export(
            &key(),
            EXPORT_ID,
            ExportKind::GenomicNode,
            &node_content(node),
            97,
        )
        .unwrap();
        std::fs::write(dir.join(&file), sealed).unwrap();
        manifest.str(node.group);
        manifest.str(node.dir);
        manifest.str(&file);
        manifest.i32(node.infos.len() as i32);
        for info in &node.infos {
            manifest.str(info.column);
        }
    }
    let sealed = seal_export(
        &key(),
        EXPORT_ID,
        ExportKind::GenomicManifest,
        &manifest.0,
        1 << 20,
    )
    .unwrap();
    std::fs::write(dir.join("genomic.bin"), sealed).unwrap();
}

struct Run {
    _dir: tempfile::TempDir,
    out: PathBuf,
}

fn migrate(mode: &str, nodes: &[Node]) -> (Run, Result<GenomicMigrateReport>) {
    let dir = tempfile::tempdir().unwrap();
    let export = dir.path().join("export");
    write_export(&export, mode, nodes);
    let key_file = dir.path().join("encryption_key");
    std::fs::write(&key_file, format!("{KEY}\n")).unwrap();
    let out = dir.path().join("store");
    let result = genomic_migrate(&GenomicMigrateOptions {
        export,
        out: out.clone(),
        key_file,
        threads: Some(2),
    });
    (Run { _dir: dir, out }, result)
}

fn dump(store: &Path) -> String {
    let mut out = Vec::new();
    dump_genomic(store, &Protection::Encrypted(key()), false, &mut out).unwrap();
    String::from_utf8(out).unwrap()
}

fn export_error(result: Result<GenomicMigrateReport>) -> String {
    match result {
        Err(EtlError::Export { detail, .. }) => detail,
        Err(other) => panic!("expected an export error, got {other}"),
        Ok(_) => panic!("expected an export error, the migration succeeded"),
    }
}

const MISSENSE: &str = "chr1,1500,A,T,G1,missense";
const SHORT: &str = "chr1,1500,A,C,G1";
const SYNONYMOUS: &str = "chr1,1700,G,T,G2,synonymous";
const METADATA_ONLY: &str = "chr1,1999,C,G,G2,stop";
const FAR: &str = "chr1,3001,T,A,G3,missense";

/// The continuous column's ranges as Java builds them, with key strings.
fn java_ranges(keys: &[&str]) -> Vec<Range> {
    build_java_compressed_index(keys)
        .unwrap()
        .into_iter()
        .map(|r| Range {
            lower: r.lower,
            upper: r.upper,
            entries: r
                .entries
                .into_iter()
                .map(|(v, ks)| {
                    (
                        v,
                        ks.iter().map(|&k| keys[k as usize].to_string()).collect(),
                    )
                })
                .collect(),
        })
        .collect()
}

/// Group A's chromosome node: patients 10, 11 and 12; a short spec, a spec known only from
/// the spec index, one only from the metadata, a duplicated spec index entry, and a bucket
/// whose Java carriers disagree with its masks.
fn group_a_chr1() -> Node {
    let numeric_keys = ["0.1", "0.5", "2.5"];
    Node {
        group: "A",
        dir: "chr1",
        patients: vec!["12", "10", "11"],
        spec_count: 5,
        infos: vec![
            Info {
                column: "Gene_with_variant",
                description: "Description=\"gene\"",
                keys: vec![("G1", vec![0, 2, 3]), ("G2", vec![1]), ("G3", vec![4])],
                ranges: None,
            },
            Info {
                column: "Variant_frequency",
                description: "Description=\"frequency\"",
                keys: vec![("0.1", vec![0]), ("0.5", vec![1, 4]), ("2.5", vec![2])],
                ranges: Some(java_ranges(&numeric_keys)),
            },
        ],
        buckets: vec![
            Bucket {
                contig: "chr1",
                bucket: 1,
                masks: vec![
                    (SHORT, [Some(Mask::Sparse(vec![1])), None, None, None]),
                    (
                        MISSENSE,
                        [
                            Some(Mask::Sparse(vec![0, 2])),
                            Some(Mask::Bitmask(vec![1])),
                            None,
                            Some(Mask::Sparse(vec![])),
                        ],
                    ),
                ],
                metadata: vec![
                    (MISSENSE, Some(vec!["AC=1;Gene=G1", "AC=2\tdup"])),
                    (METADATA_ONLY, Some(vec!["only"])),
                    (SYNONYMOUS, None),
                ],
                specs: vec![(0, MISSENSE), (1, SYNONYMOUS), (2, SHORT), (3, MISSENSE)],
                carriers: Carriers::Present(vec![0, 1, 2]),
            },
            Bucket {
                contig: "chr1",
                bucket: 3,
                masks: vec![(
                    FAR,
                    [
                        None,
                        None,
                        Some(Mask::Sparse(vec![2])),
                        Some(Mask::Sparse(vec![0])),
                    ],
                )],
                metadata: vec![],
                specs: vec![(4, FAR)],
                carriers: Carriers::Present(vec![0]),
            },
        ],
    }
}

fn group_b_chr1() -> Node {
    Node {
        group: "B",
        dir: "chr1",
        patients: vec!["20", "21"],
        spec_count: 1,
        infos: vec![Info {
            column: "Gene_with_variant",
            description: "Description=\"gene\"",
            keys: vec![("G1", vec![0])],
            ranges: None,
        }],
        buckets: vec![Bucket {
            contig: "chr1",
            bucket: 1,
            masks: vec![(MISSENSE, [None, Some(Mask::Sparse(vec![1])), None, None])],
            metadata: vec![],
            specs: vec![(0, MISSENSE)],
            carriers: Carriers::NoIndex,
        }],
    }
}

#[test]
fn migrates_two_groups_and_dumps_java_data_as_stored() {
    let (run, result) = migrate("localPatientDistributed", &[group_a_chr1(), group_b_chr1()]);
    let report = result.unwrap();
    assert_eq!(report.node_count, 2);
    assert_eq!(report.patients, 5);
    let a = &report.nodes[0];
    assert_eq!(
        (
            a.variants,
            a.mask_records,
            a.variants_without_record,
            a.metadata_only_variants
        ),
        (5, 3, 2, 1)
    );
    assert_eq!(a.spec_index_duplicates, 1);
    assert_eq!(a.short_specs, 1);
    assert_eq!(a.buckets, 2);
    assert_eq!(
        a.carrier_disagreements, 1,
        "bucket 3: Java says 12, the masks hen 11"
    );
    assert!(a.compressed_index_port_differences.is_empty());
    assert!(!report.nodes[1].bucket_index);
    assert_eq!(report.carrier_disagreements, 1);
    assert_eq!(report.specs_in_two_nodes, 0);

    let text = dump(&run.out);
    let expected_a = [
        "M\tlocalPatientDistributed",
        "N\tA\tchr1\t3\t5",
        "P\t12,10,11",
        "B\tchr1\t1",
        "V\tchr1,1500,A,C,G1\t1\ts:10\t-\t-\t-\t0",
        "V\tchr1,1500,A,T,G1,missense\t1\ts:11,12\tb:10\t-\ts:\t2\tAC=1;Gene=G1\tAC=2\\tdup",
        "V\tchr1,1700,G,T,G2,synonymous\t0\t-\t-\t-\t-\t0",
        "V\tchr1,1999,C,G,G2,stop\t0\t-\t-\t-\t-\t1\tonly",
        "C\t10,11,12",
        "B\tchr1\t3",
        "V\tchr1,3001,T,A,G3,missense\t1\t-\t-\ts:11\ts:12\t0",
        "C\t12",
        "I\tGene_with_variant\t0\t3\tDescription=\"gene\"",
        "K\tG1\t2\tchr1,1500,A,C,G1\tchr1,1500,A,T,G1,missense",
        "K\tG2\t1\tchr1,1700,G,T,G2,synonymous",
        "K\tG3\t1\tchr1,3001,T,A,G3,missense",
        "I\tVariant_frequency\t1\t3\tDescription=\"frequency\"",
    ];
    let lines: Vec<&str> = text.lines().collect();
    assert_eq!(&lines[..expected_a.len()], &expected_a);
    assert!(lines.iter().any(|l| l.starts_with("R\to:")), "{text}");
    let b_start = lines.iter().position(|l| l.starts_with("N\tB")).unwrap();
    assert_eq!(
        &lines[b_start..b_start + 5],
        &[
            "N\tB\tchr1\t2\t1",
            "P\t20,21",
            "B\tchr1\t1",
            "V\tchr1,1500,A,T,G1,missense\t1\t-\ts:21\t-\t-\t0",
            "C\t21",
        ]
    );

    let protection = Protection::Encrypted(key());
    let index = GenoIndex::read_file(run.out.join(GENO_INDEX_FILE_NAME), &protection).unwrap();
    let codec = index.block_codec(protection).unwrap();
    let reader = GenoBlocksReader::open(run.out.join(GENO_BLOCKS_FILE_NAME), &index).unwrap();
    assert_eq!(index.groups()[0].patients, vec![12, 10, 11]);
    assert_eq!(index.nodes()[0].short_specs, vec![0]);
    let gene = index.column_index("Gene_with_variant").unwrap();
    let block = reader.read_info_block(&codec, &index, 0, gene).unwrap();
    let carriers: Vec<Vec<u32>> = (0..block.len())
        .map(|k| block.carriers(k).unwrap().iter().collect())
        .collect();
    assert_eq!(
        carriers,
        vec![vec![0, 1, 2], vec![], vec![]],
        "G1 holds he and ho of the missense and short specs; hen and hon never count"
    );
}

#[test]
fn refuses_patients_in_two_groups_with_their_count() {
    let mut b = group_b_chr1();
    b.patients = vec!["11", "12"];
    let (run, result) = migrate("localPatientDistributed", &[group_a_chr1(), b]);
    let detail = export_error(result);
    assert!(
        detail.contains("2 patient ids appear in more than one group"),
        "{detail}"
    );
    assert!(!run.out.exists());
}

#[test]
fn refuses_a_record_stored_under_another_bucket() {
    let mut a = group_a_chr1();
    a.buckets[1].masks[0].0 = MISSENSE;
    let (_run, result) = migrate("localPatientDistributed", &[a]);
    let detail = export_error(result);
    assert!(
        detail.contains("belongs to contig chr1 bucket 1"),
        "{detail}"
    );
}

#[test]
fn refuses_chromosome_nodes_of_one_group_with_different_patient_lists() {
    let mut a = group_a_chr1();
    a.group = "";
    let mut other = group_b_chr1();
    other.group = "";
    other.dir = "chr2";
    let (_run, result) = migrate("localDistributed", &[a, other]);
    let detail = export_error(result);
    assert!(detail.contains("another patient list"), "{detail}");
}

#[test]
fn refuses_a_negative_patient_id() {
    let mut b = group_b_chr1();
    b.patients = vec!["-4", "21"];
    let (_run, result) = migrate("localPatientDistributed", &[b]);
    assert!(export_error(result).contains("patient 0 is not an integer"));
}

#[test]
fn refuses_a_continuous_column_without_a_compressed_index() {
    let mut a = group_a_chr1();
    a.infos[1].ranges = Some(Vec::new());
    let (_run, result) = migrate("localPatientDistributed", &[a]);
    let detail = export_error(result);
    assert!(detail.contains("Variant_frequency"), "{detail}");
}

#[test]
fn reports_a_compressed_index_the_port_would_build_differently() {
    let mut a = group_a_chr1();
    if let Some(ranges) = &mut a.infos[1].ranges {
        ranges[0].upper = RangeBound::Closed(9.0);
    }
    let (_run, result) = migrate("localPatientDistributed", &[a]);
    assert_eq!(
        result.unwrap().compressed_index_port_differences,
        vec!["A/chr1:Variant_frequency".to_string()]
    );
}

#[test]
fn refuses_an_existing_output_directory() {
    let dir = tempfile::tempdir().unwrap();
    let export = dir.path().join("export");
    write_export(&export, "localPatientDistributed", &[group_b_chr1()]);
    let key_file = dir.path().join("encryption_key");
    std::fs::write(&key_file, format!("{KEY}\n")).unwrap();
    let result = genomic_migrate(&GenomicMigrateOptions {
        export,
        out: dir.path().to_path_buf(),
        key_file,
        threads: Some(1),
    });
    assert!(matches!(result, Err(EtlError::Usage(_))));
}

#[test]
fn counts_specs_two_chromosome_nodes_of_one_group_hold() {
    let mut first = group_b_chr1();
    first.group = "";
    first.dir = "part1";
    let mut second = first.clone();
    second.dir = "part2";
    second.buckets[0]
        .masks
        .push((SHORT, [Some(Mask::Sparse(vec![0])), None, None, None]));
    second.buckets[0]
        .masks
        .sort_by(|x, y| java_string_cmp(x.0, y.0));
    let (_run, result) = migrate("localDistributed", &[first, second]);
    let report = result.unwrap();
    assert_eq!(report.specs_in_two_nodes, 1);
    assert_eq!(report.nodes[1].variants, 2);
}

#[test]
fn a_bucket_the_index_lacks_keeps_no_carriers() {
    let mut a = group_a_chr1();
    a.buckets[1].carriers = Carriers::NotIndexed;
    let (run, result) = migrate("localPatientDistributed", &[a]);
    assert_eq!(result.unwrap().nodes[0].carrier_disagreements, 1);
    let text = dump(&run.out);
    let lines: Vec<&str> = text.lines().collect();
    let far = lines.iter().position(|l| *l == "B\tchr1\t3").unwrap();
    assert_eq!(lines[far + 2], "C\t");
}
