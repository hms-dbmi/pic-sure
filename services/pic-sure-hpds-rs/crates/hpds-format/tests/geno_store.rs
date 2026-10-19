//! The genomic store end to end: `geno.index` and `geno.blocks` written, reopened and read
//! back; every index invariant; and blocks refused when corrupt, truncated, under the wrong
//! key, moved, or read as another kind, node or ordinal.

use std::fs::File;
use std::io::BufWriter;
use std::path::Path;

use hpds_format::{
    CryptoKey, FormatError, GENO_BLOCKS_HEADER_LEN, GenoBlockCodec, GenoBlockKind,
    GenoBlocksReader, GenoBlocksWriter, GenoChunkEntry, GenoGroup, GenoIndex, GenoIndexHeader,
    GenoInfoEntry, GenoLocation, GenoMode, GenoNode, InfoKeyInput, JavaMaskInput,
    MAX_GENO_BLOCK_LEN, MaskKind, MaskVariantInput, Protection, StoreId,
    build_java_compressed_index, encode_carrier_chunk, encode_info_block, encode_mask_chunk,
    encode_metadata_chunk, geno_block_aad, seal_framed,
};
use roaring::RoaringBitmap;
use xxhash_rust::xxh3::xxh3_64;

fn key() -> CryptoKey {
    CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap()
}

fn other_key() -> CryptoKey {
    CryptoKey::from_bytes(&[9u8; 32]).unwrap()
}

fn store() -> StoreId {
    StoreId::from_bytes([0x5a; 16])
}

fn header(encrypted: bool, compressed: bool) -> GenoIndexHeader {
    GenoIndexHeader {
        format_version: 0,
        encrypted,
        compressed,
        store_id: store(),
        key_source: 0,
        key_name: if encrypted {
            "DEFAULT".into()
        } else {
            String::new()
        },
        created_at_millis: 1_760_000_000_000,
        etl_version: "test".into(),
    }
}

const COLUMNS: [&str; 2] = ["Gene_with_variant", "Variant_frequency_in_gnomAD"];

/// The specs of chunk `c` of a node on `contig`: two buckets of two variants.
fn chunk_specs(contig: &str, c: i32) -> Vec<String> {
    let base = c * 10;
    vec![
        format!("{contig},{},A,C,GENE{c},missense", base * 1000 + 1),
        format!("{contig},{},A,G,GENE{c},missense", base * 1000 + 1),
        format!("{contig},{},T,C,GENE{c},synonymous", (base + 3) * 1000 + 7),
        format!("{contig},{},T,C,GENE{c}", (base + 3) * 1000 + 8),
    ]
}

struct Built {
    index: GenoIndex,
    protection: Protection,
}

/// Writes a store of two groups (patients 10 to 14 and 20 to 22) in
/// `localPatientDistributed` mode: each group has nodes `chr1` and `chr2` with two chunks,
/// and the second group also an empty `chrX` node holding only empty info entries.
fn build(dir: &Path, protection: Protection, compressed: bool) -> Built {
    let codec = GenoBlockCodec::new(protection.clone(), store());
    let file = BufWriter::new(File::create(dir.join("geno.blocks")).unwrap());
    let mut writer = GenoBlocksWriter::new(file, store()).unwrap();
    let groups = vec![
        GenoGroup {
            name: "g1".into(),
            patients: vec![14, 10, 11, 12, 13],
        },
        GenoGroup {
            name: "g2".into(),
            patients: vec![20, 22, 21],
        },
    ];
    let mut nodes = Vec::new();
    for (g, group) in groups.iter().enumerate() {
        let p = group.patients.len() as u32;
        let dirs: &[&str] = if g == 0 {
            &["chr1", "chr2"]
        } else {
            &["chr1", "chr2", "chrX"]
        };
        for dir in dirs {
            let node_index = nodes.len() as u16;
            if *dir == "chrX" {
                let empty = encode_info_block(&[], None, 0, p).unwrap();
                let info = writer
                    .append(&codec, GenoBlockKind::Info, node_index, 0, &empty)
                    .unwrap();
                nodes.push(GenoNode {
                    group: g as u16,
                    dir_name: dir.to_string(),
                    contigs: vec![],
                    variant_count: 0,
                    chunks: vec![],
                    short_specs: vec![],
                    infos: vec![GenoInfoEntry {
                        column: 0,
                        continuous: false,
                        description: "Gene".into(),
                        key_count: 0,
                        info,
                    }],
                });
                continue;
            }
            let genes = RoaringBitmap::from_iter([0u32, 1, 4]);
            let others = RoaringBitmap::from_iter([2u32, 3, 5, 6, 7]);
            let gene_keys = [
                InfoKeyInput {
                    key: "GENE0",
                    variants: &genes,
                    carriers: Some(&[0, 1]),
                },
                InfoKeyInput {
                    key: "GENE1",
                    variants: &others,
                    carriers: Some(&[1]),
                },
            ];
            let gene = encode_info_block(&gene_keys, None, 8, p).unwrap();
            let freq_keys = ["0.25", "0.5"];
            let all = RoaringBitmap::from_iter(0u32..8);
            let freq_inputs: Vec<InfoKeyInput<'_>> = freq_keys
                .iter()
                .map(|k| InfoKeyInput {
                    key: k,
                    variants: &all,
                    carriers: None,
                })
                .collect();
            let ranges = build_java_compressed_index(&freq_keys).unwrap();
            let freq = encode_info_block(&freq_inputs, Some(&ranges), 8, p).unwrap();
            let gene_at = writer
                .append(&codec, GenoBlockKind::Info, node_index, 0, &gene)
                .unwrap();
            let freq_at = writer
                .append(&codec, GenoBlockKind::Info, node_index, 1, &freq)
                .unwrap();

            let mut chunks = Vec::new();
            let mut short_specs = Vec::new();
            for c in 0..2u32 {
                let specs = chunk_specs(dir, c as i32);
                let he: Vec<u32> = (0..p).step_by(2).collect();
                let ho = [p - 1];
                let variants: Vec<MaskVariantInput<'_>> = specs
                    .iter()
                    .map(|spec| MaskVariantInput {
                        spec,
                        record: Some([
                            Some(JavaMaskInput {
                                positions: &he,
                                bitmask_form: false,
                            }),
                            Some(JavaMaskInput {
                                positions: &ho,
                                bitmask_form: true,
                            }),
                            None,
                            None,
                        ]),
                    })
                    .collect();
                let first_variant = c * 4;
                let mask = encode_mask_chunk(0, dir, first_variant, p, &variants).unwrap();
                short_specs.extend(&mask.short_specs);
                let metadata = encode_metadata_chunk(
                    &specs
                        .iter()
                        .map(|s| vec![format!("INFO of {s}")])
                        .collect::<Vec<_>>(),
                )
                .unwrap();
                let carriers =
                    encode_carrier_chunk(&[(0..p).collect::<Vec<_>>(), vec![0]], p).unwrap();
                let carriers_at = writer
                    .append(&codec, GenoBlockKind::Carriers, node_index, c, &carriers)
                    .unwrap();
                let mask_at = writer
                    .append(&codec, GenoBlockKind::Mask, node_index, c, &mask.payload)
                    .unwrap();
                let metadata_at = writer
                    .append(&codec, GenoBlockKind::Metadata, node_index, c, &metadata)
                    .unwrap();
                chunks.push(GenoChunkEntry {
                    contig: 0,
                    first_bucket: mask.first_bucket,
                    last_bucket: mask.last_bucket,
                    first_variant,
                    variant_count: 4,
                    bucket_count: mask.bucket_count,
                    mask: mask_at,
                    metadata: metadata_at,
                    carriers: carriers_at,
                });
            }
            nodes.push(GenoNode {
                group: g as u16,
                dir_name: dir.to_string(),
                contigs: vec![dir.to_string()],
                variant_count: 8,
                chunks,
                short_specs,
                infos: vec![
                    GenoInfoEntry {
                        column: 0,
                        continuous: false,
                        description: "Gene".into(),
                        key_count: 2,
                        info: gene_at,
                    },
                    GenoInfoEntry {
                        column: 1,
                        continuous: true,
                        description: "Frequency".into(),
                        key_count: 2,
                        info: freq_at,
                    },
                ],
            });
        }
    }
    writer.finish().unwrap();
    let index = GenoIndex::new(
        header(protection.is_encrypted(), compressed),
        GenoMode::LocalPatientDistributed,
        groups,
        nodes,
        COLUMNS.iter().map(|c| c.to_string()).collect(),
    )
    .unwrap();
    index
        .write_file(dir.join("geno.index"), &protection)
        .unwrap();
    Built { index, protection }
}

fn protections() -> Vec<(Protection, bool)> {
    vec![
        (Protection::Plain, true),
        (Protection::Plain, false),
        (Protection::Encrypted(key()), true),
        (Protection::Encrypted(key()), false),
    ]
}

#[test]
fn a_store_round_trips_under_every_protection() {
    for (protection, compressed) in protections() {
        let dir = tempfile::tempdir().unwrap();
        let built = build(dir.path(), protection.clone(), compressed);
        let index = GenoIndex::read_file(dir.path().join("geno.index"), &protection).unwrap();
        assert_eq!(index, built.index);
        assert_eq!(index.header().format_version, 1);
        let codec = index.block_codec(protection.clone()).unwrap();
        let reader = GenoBlocksReader::open(dir.path().join("geno.blocks"), &index).unwrap();
        assert_eq!(index.column_index("Variant_frequency_in_gnomAD"), Some(1));
        assert_eq!(index.column_index("missing"), None);
        for (n, node) in index.nodes().iter().enumerate() {
            let n = n as u16;
            let p = index.groups()[usize::from(node.group)].patients.len() as u32;
            for entry in &node.infos {
                let block = reader
                    .read_info_block(&codec, &index, n, entry.column)
                    .unwrap();
                assert_eq!(block.len() as u32, entry.key_count);
                assert_eq!(block.is_continuous(), entry.continuous);
                if entry.continuous {
                    let ci = block.compressed_index().unwrap();
                    assert_eq!(ci.keys_in_range(0.25, 0.25).unwrap(), vec![0]);
                }
            }
            for (c, entry) in node.chunks.iter().enumerate() {
                let c = c as u32;
                let mask = reader.read_mask_chunk(&codec, &index, n, c).unwrap();
                let specs = chunk_specs(&node.dir_name, c as i32);
                for (i, spec) in specs.iter().enumerate() {
                    assert_eq!(mask.spec(i), spec);
                    let he = mask.mask(i, MaskKind::Heterozygous).unwrap();
                    assert_eq!(
                        he.positions.iter().collect::<Vec<_>>(),
                        (0..p).step_by(2).collect::<Vec<_>>()
                    );
                    assert!(mask.mask(i, MaskKind::Homozygous).unwrap().bitmask_form);
                    assert!(mask.mask(i, MaskKind::HeterozygousNoCall).is_none());
                }
                let metadata = reader.read_metadata_chunk(&codec, &index, n, c).unwrap();
                assert_eq!(
                    metadata.strings(3).collect::<Vec<_>>(),
                    vec![format!("INFO of {}", specs[3])]
                );
                let carriers = reader.read_carrier_chunk(&codec, &index, n, c).unwrap();
                assert_eq!(carriers.len() as u32, entry.bucket_count);
                assert_eq!(
                    carriers.bucket(1).unwrap().iter().collect::<Vec<_>>(),
                    vec![0]
                );
            }
        }
    }
}

#[test]
fn node_lookups_follow_the_chunk_table() {
    let dir = tempfile::tempdir().unwrap();
    let built = build(dir.path(), Protection::Plain, true);
    let node = &built.index.nodes()[0];
    assert_eq!(node.contig_index("chr1"), Some(0));
    assert_eq!(node.contig_index("chr2"), None);
    assert_eq!(
        (node.chunks[0].first_bucket, node.chunks[0].last_bucket),
        (0, 3)
    );
    assert_eq!(
        (node.chunks[1].first_bucket, node.chunks[1].last_bucket),
        (10, 13)
    );
    assert_eq!(node.chunk_for_bucket(0, 0), Some(0));
    assert_eq!(node.chunk_for_bucket(0, 2), Some(0));
    assert_eq!(node.chunk_for_bucket(0, 5), None);
    assert_eq!(node.chunk_for_bucket(0, 13), Some(1));
    assert_eq!(node.chunk_for_bucket(0, 14), None);
    assert_eq!(node.chunk_for_bucket(1, 0), None);
    assert_eq!(node.chunk_for_bucket(0, -1), None);
    assert_eq!(node.chunk_for_variant(0), Some(0));
    assert_eq!(node.chunk_for_variant(3), Some(0));
    assert_eq!(node.chunk_for_variant(4), Some(1));
    assert_eq!(node.chunk_for_variant(8), None);
    assert_eq!(node.short_specs, vec![3, 7]);
    assert!(node.is_short_spec(7));
    assert!(!node.is_short_spec(6));
    assert_eq!(node.info(1).map(|e| e.continuous), Some(true));
    let empty = &built.index.nodes()[4];
    assert_eq!(empty.chunk_for_variant(0), None);
    assert_eq!(empty.info(1), None);
}

#[test]
fn the_index_refuses_the_wrong_protection_or_key() {
    let dir = tempfile::tempdir().unwrap();
    let built = build(dir.path(), Protection::Encrypted(key()), true);
    let bytes = built.index.encode(&built.protection).unwrap();
    assert!(matches!(
        GenoIndex::decode(&bytes, &Protection::Plain),
        Err(FormatError::MissingKey(_))
    ));
    assert!(matches!(
        GenoIndex::decode(&bytes, &Protection::Encrypted(other_key())),
        Err(FormatError::Authentication)
    ));
    let plain_dir = tempfile::tempdir().unwrap();
    let plain = build(plain_dir.path(), Protection::Plain, true);
    let plain_bytes = plain.index.encode(&Protection::Plain).unwrap();
    assert!(matches!(
        GenoIndex::decode(&plain_bytes, &Protection::Encrypted(key())),
        Err(FormatError::UnexpectedKey(_))
    ));
    assert!(matches!(
        plain.index.block_codec(Protection::Encrypted(key())),
        Err(FormatError::UnexpectedKey(_))
    ));
    assert!(matches!(
        built.index.encode(&Protection::Plain),
        Err(FormatError::MissingKey(_))
    ));
}

#[test]
fn every_damaged_index_is_refused() {
    for (protection, compressed) in protections() {
        let dir = tempfile::tempdir().unwrap();
        let built = build(dir.path(), protection.clone(), compressed);
        let bytes = built.index.encode(&protection).unwrap();
        for len in 0..bytes.len() {
            assert!(
                GenoIndex::decode(&bytes[..len], &protection).is_err(),
                "prefix of {len}"
            );
        }
        let mut longer = bytes.clone();
        longer.push(0);
        assert!(GenoIndex::decode(&longer, &protection).is_err());
        for at in 0..bytes.len() {
            let mut damaged = bytes.clone();
            damaged[at] ^= 0x10;
            assert!(
                GenoIndex::decode(&damaged, &protection).is_err(),
                "flip at {at}"
            );
        }
    }
}

/// Encodes `index` plain and uncompressed, applies `patch` to the bytes before the
/// checksum, and recomputes the checksum.
fn patched(index: &GenoIndex, patch: impl Fn(&mut Vec<u8>)) -> Vec<u8> {
    let mut bytes = index.encode(&Protection::Plain).unwrap();
    bytes.truncate(bytes.len() - 8);
    patch(&mut bytes);
    let checksum = xxh3_64(&bytes);
    bytes.extend_from_slice(&checksum.to_le_bytes());
    bytes
}

fn find(bytes: &[u8], needle: &[u8]) -> usize {
    bytes
        .windows(needle.len())
        .position(|w| w == needle)
        .unwrap()
}

#[test]
fn decoding_checks_the_invariants_and_the_header() {
    let dir = tempfile::tempdir().unwrap();
    let built = build(dir.path(), Protection::Plain, false);
    let preamble = 8 + 2 + 2 + 16 + 1 + 4 + 8 + 4 + "test".len() + 8;
    type Patch = Box<dyn Fn(&mut Vec<u8>)>;
    let cases: Vec<(&str, &str, Patch)> = vec![
        (
            "duplicate patient",
            "patient 14 appears twice",
            Box::new(|b: &mut Vec<u8>| {
                let at = find(b, &[14, 0, 0, 0, 10, 0, 0, 0]);
                b[at + 4] = 14;
            }),
        ),
        (
            "mode byte",
            "unknown mode 4",
            Box::new(move |b: &mut Vec<u8>| b[preamble] = 4),
        ),
        (
            "mode",
            "2 groups",
            Box::new(move |b: &mut Vec<u8>| b[preamble] = 1),
        ),
        (
            "key source",
            "key source 1",
            Box::new(|b: &mut Vec<u8>| b[28] = 1),
        ),
        (
            "flags",
            "unknown flags",
            Box::new(|b: &mut Vec<u8>| b[10] = 4),
        ),
        (
            "version",
            "unsupported format version 2",
            Box::new(|b: &mut Vec<u8>| b[8] = 2),
        ),
        ("magic", "magic", Box::new(|b: &mut Vec<u8>| b[0] = b'X')),
        (
            "continuous byte",
            "continuous byte 2",
            Box::new(|b: &mut Vec<u8>| {
                let at = find(b, b"Frequency");
                b[at - 5] = 2;
            }),
        ),
        (
            "short spec order",
            "short specs",
            Box::new(|b: &mut Vec<u8>| {
                let at = find(b, &[2, 0, 0, 0, 3, 0, 0, 0, 7, 0, 0, 0]);
                b[at + 4] = 9;
            }),
        ),
    ];
    for (name, fragment, patch) in cases {
        let bytes = patched(&built.index, patch);
        let error = GenoIndex::decode(&bytes, &Protection::Plain).expect_err(name);
        assert!(error.to_string().contains(fragment), "{name}: {error}");
    }
    let untouched = patched(&built.index, |_| {});
    assert_eq!(
        GenoIndex::decode(&untouched, &Protection::Plain).unwrap(),
        built.index
    );
    let header = GenoIndex::decode_header(&untouched).unwrap();
    assert_eq!(header.etl_version, "test");
}

struct Parts {
    header: GenoIndexHeader,
    mode: GenoMode,
    groups: Vec<GenoGroup>,
    nodes: Vec<GenoNode>,
    columns: Vec<String>,
}

fn parts(index: &GenoIndex) -> Parts {
    Parts {
        header: index.header().clone(),
        mode: index.mode(),
        groups: index.groups().to_vec(),
        nodes: index.nodes().to_vec(),
        columns: index.columns().to_vec(),
    }
}

fn rebuild(p: Parts) -> hpds_format::Result<GenoIndex> {
    GenoIndex::new(p.header, p.mode, p.groups, p.nodes, p.columns)
}

#[test]
fn every_index_invariant_is_checked() {
    let dir = tempfile::tempdir().unwrap();
    let built = build(dir.path(), Protection::Plain, true);
    let base = || parts(&built.index);
    assert!(rebuild(base()).is_ok());
    let location = |offset: u64, stored_len: u32, decoded_len: u32| GenoLocation {
        offset,
        stored_len,
        decoded_len,
    };
    type Mutation = Box<dyn Fn(&mut Parts)>;
    let cases: Vec<(&str, &str, Mutation)> = vec![
        (
            "key source",
            "key source 1",
            Box::new(|p| p.header.key_source = 1),
        ),
        (
            "plain key name",
            "plain store",
            Box::new(|p| p.header.key_name = "DEFAULT".into()),
        ),
        (
            "one group in mode 1",
            "2 groups in mode localDistributed",
            Box::new(|p| p.mode = GenoMode::LocalDistributed),
        ),
        (
            "eleven groups",
            "11 groups",
            Box::new(|p| {
                for i in 0..9 {
                    p.groups.push(GenoGroup {
                        name: format!("h{i}"),
                        patients: vec![],
                    });
                }
            }),
        ),
        (
            "group order",
            "not ordered",
            Box::new(|p| p.groups.swap(0, 1)),
        ),
        (
            "group name in mode 2",
            "is named",
            Box::new(|p| p.groups[0].name = String::new()),
        ),
        (
            "patient above i32",
            "exceeds i32::MAX",
            Box::new(|p| p.groups[0].patients[0] = 1 << 31),
        ),
        (
            "patient in two groups",
            "patient 10 appears twice",
            Box::new(|p| p.groups[1].patients[0] = 10),
        ),
        (
            "patient twice in a group",
            "patient 21 appears twice",
            Box::new(|p| p.groups[1].patients[0] = 21),
        ),
        (
            "node group",
            "names group 2",
            Box::new(|p| p.nodes[4].group = 2),
        ),
        (
            "node order",
            "not ordered",
            Box::new(|p| p.nodes.swap(0, 1)),
        ),
        (
            "node dir name",
            "has directory",
            Box::new(|p| p.nodes[0].dir_name = String::new()),
        ),
        (
            "dir name in mode 3",
            "has directory",
            Box::new(|p| {
                p.mode = GenoMode::LocalPatientOnlyDistributed;
                p.nodes.truncate(3);
                p.nodes.remove(1);
            }),
        ),
        (
            "fifty-one nodes",
            "holds 52 nodes",
            Box::new(|p| {
                let template = p.nodes[4].clone();
                for i in 0..49 {
                    let mut node = template.clone();
                    node.dir_name = format!("chrZ{i:02}");
                    p.nodes.push(node);
                }
            }),
        ),
        (
            "contig order",
            "contig",
            Box::new(|p| p.nodes[0].contigs = vec!["chr2".into(), "chr1".into()]),
        ),
        (
            "chunks without contigs",
            "no contigs",
            Box::new(|p| p.nodes[0].contigs.clear()),
        ),
        (
            "chunk contig",
            "contig out of range",
            Box::new(|p| p.nodes[0].chunks[0].contig = 1),
        ),
        (
            "bucket range",
            "first bucket after last",
            Box::new(|p| p.nodes[0].chunks[0].first_bucket = 4),
        ),
        (
            "variant count zero",
            "count out of range",
            Box::new(|p| p.nodes[0].chunks[0].variant_count = 0),
        ),
        (
            "bucket count zero",
            "count out of range",
            Box::new(|p| p.nodes[0].chunks[0].bucket_count = 0),
        ),
        (
            "buckets above variants",
            "count out of range",
            Box::new(|p| p.nodes[0].chunks[0].bucket_count = 5),
        ),
        (
            "buckets above span",
            "does not fit",
            Box::new(|p| {
                let c = &mut p.nodes[0].chunks[0];
                c.last_bucket = c.first_bucket + 1;
                c.bucket_count = 3;
            }),
        ),
        (
            "one bucket over two",
            "does not fit",
            Box::new(|p| p.nodes[0].chunks[0].bucket_count = 1),
        ),
        (
            "first variant",
            "not contiguous",
            Box::new(|p| p.nodes[0].chunks[1].first_variant = 5),
        ),
        (
            "variant total",
            "hold 8 variants",
            Box::new(|p| p.nodes[0].variant_count = 9),
        ),
        (
            "chunk overlap",
            "overlapping",
            Box::new(|p| p.nodes[0].chunks[1].first_bucket = 3),
        ),
        (
            "chunk order",
            "overlapping",
            Box::new(|p| {
                p.nodes[0].chunks.swap(0, 1);
                p.nodes[0].chunks[0].first_variant = 0;
                p.nodes[0].chunks[1].first_variant = 4;
            }),
        ),
        (
            "location before the header",
            "not inside",
            Box::new(move |p| {
                p.nodes[0].chunks[0].mask = location(GENO_BLOCKS_HEADER_LEN - 1, 10, 10)
            }),
        ),
        (
            "location overflow",
            "not inside",
            Box::new(move |p| p.nodes[0].chunks[0].mask = location(u64::MAX - 2, 10, 10)),
        ),
        (
            "decoded above the limit",
            "decodes to more",
            Box::new(move |p| {
                p.nodes[0].chunks[0].mask = location(100, 10, MAX_GENO_BLOCK_LEN + 1)
            }),
        ),
        (
            "stored above the bound",
            "zstd bound",
            Box::new(move |p| p.nodes[0].chunks[0].metadata = location(100, 200, 10)),
        ),
        (
            "short spec order",
            "short specs",
            Box::new(|p| p.nodes[0].short_specs = vec![7, 3]),
        ),
        (
            "short spec range",
            "short specs",
            Box::new(|p| p.nodes[0].short_specs = vec![3, 8]),
        ),
        (
            "info column",
            "column out of range",
            Box::new(|p| p.nodes[0].infos[1].column = 2),
        ),
        (
            "info order",
            "previous entry",
            Box::new(|p| p.nodes[0].infos.swap(0, 1)),
        ),
        (
            "continuous without keys",
            "without keys",
            Box::new(|p| p.nodes[0].infos[1].key_count = 0),
        ),
        (
            "column order",
            "genomic index columns",
            Box::new(|p| p.columns.swap(0, 1)),
        ),
    ];
    for (name, fragment, mutate) in cases {
        let mut p = base();
        mutate(&mut p);
        match rebuild(p) {
            Err(FormatError::InvalidInput(detail)) => {
                assert!(detail.contains(fragment), "{name}: {detail}")
            }
            other => panic!("{name} was accepted or refused otherwise: {other:?}"),
        }
    }
    let mut empty_category = base();
    empty_category.nodes[0].infos[0].key_count = 0;
    assert!(rebuild(empty_category).is_ok());
    let mut empty_group = base();
    empty_group.groups[1].patients.clear();
    assert!(rebuild(empty_group).is_ok());
}

#[test]
fn blocks_read_as_another_kind_node_or_ordinal_fail_authentication() {
    for (protection, _) in protections().into_iter().step_by(2) {
        let dir = tempfile::tempdir().unwrap();
        let built = build(dir.path(), protection.clone(), true);
        let index = &built.index;
        let codec = index.block_codec(protection.clone()).unwrap();
        let reader = GenoBlocksReader::open(dir.path().join("geno.blocks"), index).unwrap();
        let mut blocks: Vec<(GenoBlockKind, u16, u32, GenoLocation)> = Vec::new();
        for (n, node) in index.nodes().iter().enumerate() {
            for (c, chunk) in node.chunks.iter().enumerate() {
                blocks.push((GenoBlockKind::Mask, n as u16, c as u32, chunk.mask));
                blocks.push((GenoBlockKind::Metadata, n as u16, c as u32, chunk.metadata));
                blocks.push((GenoBlockKind::Carriers, n as u16, c as u32, chunk.carriers));
            }
            for e in &node.infos {
                blocks.push((GenoBlockKind::Info, n as u16, u32::from(e.column), e.info));
            }
        }
        let kinds = [
            GenoBlockKind::Mask,
            GenoBlockKind::Metadata,
            GenoBlockKind::Carriers,
            GenoBlockKind::Info,
        ];
        for &(kind, node, ordinal, location) in &blocks {
            assert!(
                reader
                    .read_payload(&codec, kind, node, ordinal, location)
                    .is_ok()
            );
            let stored = reader.read_stored(location).unwrap();
            for other_kind in kinds {
                for other_node in 0..6u16 {
                    for other_ordinal in 0..3u32 {
                        if (other_kind, other_node, other_ordinal) == (kind, node, ordinal) {
                            continue;
                        }
                        let result = codec.open(
                            other_kind,
                            other_node,
                            other_ordinal,
                            &stored,
                            location.decoded_len,
                        );
                        let expected_failure = if protection.is_encrypted() {
                            matches!(result, Err(FormatError::Authentication))
                        } else {
                            matches!(result, Err(FormatError::Checksum(_)))
                        };
                        assert!(
                            expected_failure,
                            "{kind:?} {node} {ordinal} as {other_kind:?} {other_node} {other_ordinal}: {result:?}"
                        );
                    }
                }
            }
            let foreign = GenoBlockCodec::new(protection.clone(), StoreId::from_bytes([1; 16]));
            assert!(
                foreign
                    .open(kind, node, ordinal, &stored, location.decoded_len)
                    .is_err()
            );
        }
        let moved = index.nodes()[0].chunks[1].mask;
        assert!(
            reader
                .read_payload(&codec, GenoBlockKind::Mask, 0, 0, moved)
                .is_err()
        );
        let foreign = GenoBlockCodec::new(protection.clone(), StoreId::from_bytes([1; 16]));
        assert!(matches!(
            reader.read_mask_chunk(&foreign, index, 0, 0),
            Err(FormatError::StoreMismatch)
        ));
    }
}

#[test]
fn corrupt_truncated_and_wrong_key_blocks_are_refused() {
    for (protection, _) in protections().into_iter().step_by(2) {
        let dir = tempfile::tempdir().unwrap();
        let built = build(dir.path(), protection.clone(), true);
        let index = &built.index;
        let codec = index.block_codec(protection.clone()).unwrap();
        let reader = GenoBlocksReader::open(dir.path().join("geno.blocks"), index).unwrap();
        let location = index.nodes()[0].chunks[0].mask;
        let stored = reader.read_stored(location).unwrap();
        let open =
            |bytes: &[u8]| codec.open(GenoBlockKind::Mask, 0, 0, bytes, location.decoded_len);
        assert!(open(&stored).is_ok());
        for len in 0..stored.len() {
            assert!(open(&stored[..len]).is_err(), "prefix of {len}");
        }
        for at in 0..stored.len() {
            let mut damaged = stored.clone();
            damaged[at] ^= 0x01;
            assert!(open(&damaged).is_err(), "flip at {at}");
        }
        assert!(
            codec
                .open(GenoBlockKind::Mask, 0, 0, &stored, location.decoded_len + 1)
                .is_err()
        );
        assert!(
            codec
                .open(GenoBlockKind::Mask, 0, 0, &stored, location.decoded_len - 1)
                .is_err()
        );
        assert!(matches!(
            codec.open(GenoBlockKind::Mask, 0, 0, &stored, MAX_GENO_BLOCK_LEN + 1),
            Err(FormatError::PayloadTooLarge { .. })
        ));
        if protection.is_encrypted() {
            let wrong = GenoBlockCodec::new(Protection::Encrypted(other_key()), store());
            assert!(matches!(
                wrong.open(GenoBlockKind::Mask, 0, 0, &stored, location.decoded_len),
                Err(FormatError::Authentication)
            ));
            let plain = GenoBlockCodec::new(Protection::Plain, store());
            let plain_block = plain
                .seal(GenoBlockKind::Mask, 0, 0, b"\x01payload")
                .unwrap();
            assert!(
                codec
                    .open(GenoBlockKind::Mask, 0, 0, &plain_block, 8)
                    .is_err()
            );
        }
    }
}

/// Seals `body` as the codec would for kind 1, node 0, ordinal 0, without compressing it.
fn seal_raw(protection: &Protection, body: &[u8]) -> Vec<u8> {
    let aad = geno_block_aad(&store(), GenoBlockKind::Mask, 0, 0);
    match protection {
        Protection::Encrypted(key) => seal_framed(key, body, &aad).unwrap(),
        Protection::Plain => {
            let mut out = body.to_vec();
            let mut hashed = aad.to_vec();
            hashed.extend_from_slice(body);
            out.extend_from_slice(&xxh3_64(&hashed).to_le_bytes());
            out
        }
    }
}

#[test]
fn frames_must_declare_exactly_the_indexed_size() {
    let payload = vec![1u8; 4000];
    for (protection, _) in protections().into_iter().step_by(2) {
        let codec = GenoBlockCodec::new(protection.clone(), store());
        let mut compressor = zstd::bulk::Compressor::new(3).unwrap();
        compressor.include_contentsize(false).unwrap();
        let undeclared = compressor.compress(&payload).unwrap();
        let sealed = seal_raw(&protection, &undeclared);
        let result = codec.open(GenoBlockKind::Mask, 0, 0, &sealed, 4000);
        assert!(
            matches!(&result, Err(FormatError::Corrupt { detail, .. }) if detail.contains("declare")),
            "{result:?}"
        );

        let declared = zstd::bulk::compress(&payload, 3).unwrap();
        assert!(
            codec
                .open(
                    GenoBlockKind::Mask,
                    0,
                    0,
                    &seal_raw(&protection, &declared),
                    4000
                )
                .is_ok()
        );
        assert!(
            codec
                .open(
                    GenoBlockKind::Mask,
                    0,
                    0,
                    &seal_raw(&protection, &declared),
                    3999
                )
                .is_err()
        );

        let mut two_frames = declared.clone();
        two_frames.extend_from_slice(&zstd::bulk::compress(&[], 3).unwrap());
        assert!(
            codec
                .open(
                    GenoBlockKind::Mask,
                    0,
                    0,
                    &seal_raw(&protection, &two_frames),
                    4000
                )
                .is_err()
        );

        let raw = vec![1u8; 64];
        let oversized = seal_raw(&protection, &[raw.as_slice(), &[0u8; 200]].concat());
        assert!(matches!(
            codec.open(GenoBlockKind::Mask, 0, 0, &oversized, 1),
            Err(FormatError::Corrupt { .. })
        ));

        let carriers = encode_carrier_chunk(&[vec![0u32]], 1).unwrap();
        let forged = codec.seal(GenoBlockKind::Mask, 0, 0, &carriers).unwrap();
        let result = codec.open(GenoBlockKind::Mask, 0, 0, &forged, carriers.len() as u32);
        assert!(
            matches!(&result, Err(FormatError::Corrupt { detail, .. }) if detail.contains("kind")),
            "{result:?}"
        );
    }
}

#[test]
fn the_blocks_file_must_belong_to_the_index_and_hold_its_locations() {
    let dir = tempfile::tempdir().unwrap();
    let built = build(dir.path(), Protection::Plain, true);
    let path = dir.path().join("geno.blocks");
    let bytes = std::fs::read(&path).unwrap();

    let mut other = bytes.clone();
    other[8] ^= 1;
    std::fs::write(&path, &other).unwrap();
    assert!(matches!(
        GenoBlocksReader::open(&path, &built.index),
        Err(FormatError::StoreMismatch)
    ));

    let mut magic = bytes.clone();
    magic[0] = b'X';
    std::fs::write(&path, &magic).unwrap();
    assert!(matches!(
        GenoBlocksReader::open(&path, &built.index),
        Err(FormatError::BadMagic { .. })
    ));

    std::fs::write(&path, &bytes[..bytes.len() - 1]).unwrap();
    assert!(matches!(
        GenoBlocksReader::open(&path, &built.index),
        Err(FormatError::Corrupt { .. })
    ));

    std::fs::write(&path, &bytes[..10]).unwrap();
    assert!(GenoBlocksReader::open(&path, &built.index).is_err());

    std::fs::write(&path, &bytes).unwrap();
    let reader = GenoBlocksReader::open(&path, &built.index).unwrap();
    assert!(
        reader
            .read_stored(GenoLocation {
                offset: bytes.len() as u64,
                stored_len: 1,
                decoded_len: 1
            })
            .is_err()
    );
    assert!(
        reader
            .read_stored(GenoLocation {
                offset: 0,
                stored_len: 1,
                decoded_len: 1
            })
            .is_err()
    );
    let codec = built.index.block_codec(Protection::Plain).unwrap();
    assert!(matches!(
        reader.read_mask_chunk(&codec, &built.index, 9, 0),
        Err(FormatError::InvalidInput(_))
    ));
    assert!(matches!(
        reader.read_info_block(&codec, &built.index, 4, 1),
        Err(FormatError::InvalidInput(_))
    ));
}
