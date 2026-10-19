//! Genomic block payloads: mask, metadata and carrier chunks and info blocks. Round trips,
//! every check the decoders make, and no panic on damaged input.

use std::collections::BTreeSet;

use hpds_format::{
    AlignedBuf, CarrierChunk, CompressedRange, ExpectedInfoBlock, ExpectedMaskChunk, FormatError,
    InfoBlock, InfoKeyInput, JavaMaskInput, MaskChunk, MaskKind, MaskVariantInput, MetadataChunk,
    RangeBound, build_java_compressed_index, compare_specs, encode_carrier_chunk,
    encode_info_block, encode_mask_chunk, encode_metadata_chunk, is_short_spec,
};
use proptest::prelude::*;
use rand_chacha::ChaCha8Rng;
use rand_chacha::rand_core::{Rng, SeedableRng};
use roaring::RoaringBitmap;

/// Positions and whether Java holds the mask in the bitmask form.
type Mask = (Vec<u32>, bool);

#[derive(Clone, Debug)]
struct Variant {
    spec: String,
    record: Option<[Option<Mask>; 4]>,
}

fn random_positions(rng: &mut ChaCha8Rng, p: u32) -> Vec<u32> {
    let density = rng.next_u32() % 101;
    (0..p).filter(|_| rng.next_u32() % 100 < density).collect()
}

/// A chunk of `contig` starting at bucket `first_bucket`, its specs in store order.
fn random_chunk(rng: &mut ChaCha8Rng, p: u32, contig: &str, first_bucket: i32) -> Vec<Variant> {
    let buckets = 1 + rng.next_u32() % 6;
    let mut bucket = first_bucket;
    let mut variants = Vec::new();
    for _ in 0..buckets {
        let mut specs = BTreeSet::new();
        for _ in 0..1 + rng.next_u32() % 5 {
            let position = bucket * 1000 + (rng.next_u32() % 1000) as i32;
            let alt = ["A", "C", "G", "T"][(rng.next_u32() % 4) as usize];
            let spec = match rng.next_u32() % 10 {
                0 if position >= 0 => format!("{contig},0{position},A,{alt},GENE,missense"),
                1 => format!("{contig},{position},A,{alt},GE,NE,missense"),
                2 => format!("{contig},{position},A,{alt},GENE,"),
                _ => format!("{contig},{position},A,{alt},GENE,missense"),
            };
            specs.insert(spec);
        }
        let mut specs: Vec<String> = specs.into_iter().collect();
        specs.sort_by(|a, b| compare_specs(a, b).unwrap());
        for spec in specs {
            let record = (!rng.next_u32().is_multiple_of(8)).then(|| {
                std::array::from_fn(|_| {
                    (!rng.next_u32().is_multiple_of(3))
                        .then(|| (random_positions(rng, p), rng.next_u32().is_multiple_of(2)))
                })
            });
            variants.push(Variant { spec, record });
        }
        bucket += 1 + (rng.next_u32() % 3) as i32;
    }
    variants
}

fn inputs(variants: &[Variant]) -> Vec<MaskVariantInput<'_>> {
    variants
        .iter()
        .map(|v| MaskVariantInput {
            spec: &v.spec,
            record: v.record.as_ref().map(|r| {
                std::array::from_fn(|k| {
                    r[k].as_ref()
                        .map(|(positions, bitmask_form)| JavaMaskInput {
                            positions,
                            bitmask_form: *bitmask_form,
                        })
                })
            }),
        })
        .collect()
}

struct Encoded {
    payload: Vec<u8>,
    first_bucket: i32,
    last_bucket: i32,
    bucket_count: u32,
    short_specs: Vec<u32>,
}

fn encode(variants: &[Variant], first_variant: u32, p: u32) -> Encoded {
    let encoded = encode_mask_chunk(3, "chr1", first_variant, p, &inputs(variants)).unwrap();
    Encoded {
        payload: encoded.payload,
        first_bucket: encoded.first_bucket,
        last_bucket: encoded.last_bucket,
        bucket_count: encoded.bucket_count,
        short_specs: encoded.short_specs,
    }
}

fn expected<'a>(e: &'a Encoded, n: usize, first_variant: u32, p: u32) -> ExpectedMaskChunk<'a> {
    ExpectedMaskChunk {
        first_variant,
        variant_count: n as u32,
        bucket_count: e.bucket_count,
        contig: 3,
        contig_name: "chr1",
        first_bucket: e.first_bucket,
        last_bucket: e.last_bucket,
        patient_count: p,
        short_specs: &e.short_specs,
    }
}

fn decode(payload: &[u8], expected: &ExpectedMaskChunk<'_>) -> hpds_format::Result<MaskChunk> {
    MaskChunk::decode(AlignedBuf::copy_from(payload), expected)
}

fn check_chunk(chunk: &MaskChunk, variants: &[Variant], first_variant: u32) {
    assert_eq!(chunk.len(), variants.len());
    assert_eq!(chunk.first_variant(), first_variant);
    let mut covered = 0;
    for j in 0..chunk.bucket_count() {
        let (bucket, range) = chunk.bucket(j).unwrap();
        assert_eq!(range.start, covered);
        covered = range.end;
        assert_eq!(chunk.find_bucket(bucket), Some(range.clone()));
        for i in range {
            let spec = &variants[i].spec;
            let position: i32 = spec.split(',').nth(1).unwrap().parse().unwrap();
            assert_eq!(position / 1000, bucket);
        }
    }
    assert_eq!(covered, variants.len());
    assert_eq!(chunk.find_bucket(i32::MIN), None);
    for (i, v) in variants.iter().enumerate() {
        assert_eq!(chunk.spec(i), v.spec);
        assert_eq!(chunk.find_spec(&v.spec), Some(i));
        assert_eq!(chunk.has_record(i), v.record.is_some());
        for kind in MaskKind::ALL {
            let want = v.record.as_ref().and_then(|r| r[kind as usize].as_ref());
            let got = chunk.mask(i, kind);
            match (want, got) {
                (None, None) => {}
                (Some((positions, form)), Some(mask)) => {
                    assert_eq!(&mask.positions.iter().collect::<Vec<_>>(), positions);
                    assert_eq!(mask.bitmask_form, *form);
                }
                other => panic!("variant {i} mask {kind:?}: {other:?}"),
            }
        }
    }
    assert_eq!(chunk.find_spec("chr1,5,A,T,x,y"), None);
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(64))]

    #[test]
    fn mask_chunks_round_trip(seed in any::<u64>(), p in 0u32..300, first_variant in 0u32..1000) {
        let mut rng = ChaCha8Rng::seed_from_u64(seed);
        let first_bucket = (rng.next_u32() % 2000) as i32;
        let variants = random_chunk(&mut rng, p, "chr1", first_bucket);
        let e = encode(&variants, first_variant, p);
        let short: Vec<u32> = variants
            .iter()
            .enumerate()
            .filter(|(_, v)| is_short_spec(&v.spec))
            .map(|(i, _)| first_variant + i as u32)
            .collect();
        prop_assert_eq!(&e.short_specs, &short);
        let chunk = decode(&e.payload, &expected(&e, variants.len(), first_variant, p)).unwrap();
        check_chunk(&chunk, &variants, first_variant);
    }

    #[test]
    fn damaged_mask_chunks_never_panic(seed in any::<u64>(), flips in prop::collection::vec((any::<usize>(), any::<u8>()), 1..4)) {
        let mut rng = ChaCha8Rng::seed_from_u64(seed);
        let variants = random_chunk(&mut rng, 20, "chr1", 7);
        let e = encode(&variants, 0, 20);
        let mut payload = e.payload.clone();
        for (at, xor) in flips {
            let at = at % payload.len();
            payload[at] ^= xor;
        }
        if let Ok(chunk) = decode(&payload, &expected(&e, variants.len(), 0, 20)) {
            for i in 0..chunk.len() {
                for kind in MaskKind::ALL {
                    if let Some(mask) = chunk.mask(i, kind) {
                        prop_assert!(mask.positions.iter().all(|q| q < 20));
                    }
                }
            }
        }
    }

    #[test]
    fn arbitrary_payloads_never_panic(bytes in prop::collection::vec(any::<u8>(), 0..200)) {
        let shorts = [0u32, 1];
        let expected = ExpectedMaskChunk {
            first_variant: 0,
            variant_count: 2,
            bucket_count: 1,
            contig: 0,
            contig_name: "chr1",
            first_bucket: 0,
            last_bucket: 0,
            patient_count: 9,
            short_specs: &shorts,
        };
        let _ = MaskChunk::decode(AlignedBuf::copy_from(&bytes), &expected);
        let _ = MetadataChunk::decode(AlignedBuf::copy_from(&bytes), 2);
        let _ = CarrierChunk::decode(AlignedBuf::copy_from(&bytes), 1, 9);
        for continuous in [false, true] {
            let _ = InfoBlock::decode(
                AlignedBuf::copy_from(&bytes),
                &ExpectedInfoBlock { continuous, key_count: 1, variant_count: 3, patient_count: 9 },
            );
        }
    }
}

fn fixed_chunk() -> (Vec<Variant>, Encoded) {
    let variants = vec![
        Variant {
            spec: "chr1,5000,A,C,GENE,missense".into(),
            record: Some([Some((vec![0, 3], false)), None, None, None]),
        },
        Variant {
            spec: "chr1,5001,A,C,GENE,missense".into(),
            record: Some([None, None, None, None]),
        },
        Variant {
            spec: "chr1,5002,A,C,GENE,missense".into(),
            record: None,
        },
    ];
    let e = encode(&variants, 10, 8);
    (variants, e)
}

fn corrupt(result: hpds_format::Result<MaskChunk>) -> String {
    match result {
        Err(FormatError::Corrupt { detail, .. }) => detail,
        other => panic!("expected Corrupt, got {other:?}"),
    }
}

#[test]
fn a_chunk_disagreeing_with_its_index_entry_is_refused() {
    let (variants, e) = fixed_chunk();
    let good = expected(&e, variants.len(), 10, 8);
    assert!(decode(&e.payload, &good).is_ok());
    let cases: Vec<ExpectedMaskChunk<'_>> = vec![
        ExpectedMaskChunk {
            variant_count: 4,
            ..good
        },
        ExpectedMaskChunk {
            first_variant: 11,
            ..good
        },
        ExpectedMaskChunk {
            bucket_count: 2,
            ..good
        },
        ExpectedMaskChunk { contig: 2, ..good },
        ExpectedMaskChunk {
            patient_count: 9,
            ..good
        },
        ExpectedMaskChunk {
            contig_name: "chr2",
            ..good
        },
        ExpectedMaskChunk {
            first_bucket: 4,
            last_bucket: 4,
            ..good
        },
    ];
    for case in cases {
        corrupt(decode(&e.payload, &case));
    }
}

#[test]
fn a_spec_outside_its_bucket_is_refused() {
    let (variants, e) = fixed_chunk();
    let mut payload = e.payload.clone();
    payload[24..28].copy_from_slice(&6i32.to_le_bytes());
    let moved = ExpectedMaskChunk {
        first_bucket: 6,
        last_bucket: 6,
        ..expected(&e, variants.len(), 10, 8)
    };
    let detail = corrupt(decode(&payload, &moved));
    assert!(detail.contains("not in bucket 6"), "{detail}");
}

#[test]
fn specs_out_of_order_are_refused() {
    let (mut variants, _) = fixed_chunk();
    variants.swap(0, 1);
    assert!(matches!(
        encode_mask_chunk(3, "chr1", 10, 8, &inputs(&variants)),
        Err(FormatError::InvalidInput(_))
    ));

    let (variants, e) = fixed_chunk();
    let mut payload = e.payload.clone();
    let first = payload
        .windows(variants[0].spec.len())
        .position(|w| w == variants[0].spec.as_bytes())
        .unwrap();
    let len = variants[0].spec.len();
    let (a, b) = (
        payload[first..first + len].to_vec(),
        payload[first + len..first + 2 * len].to_vec(),
    );
    payload[first..first + len].copy_from_slice(&b);
    payload[first + len..first + 2 * len].copy_from_slice(&a);
    let detail = corrupt(decode(&payload, &expected(&e, variants.len(), 10, 8)));
    assert!(detail.contains("not ordered"), "{detail}");
}

#[test]
fn short_specs_must_match_the_node_list() {
    let variants = vec![
        Variant {
            spec: "chr1,100,A,C,GENE".into(),
            record: None,
        },
        Variant {
            spec: "chr1,100,A,G,GENE,missense".into(),
            record: None,
        },
    ];
    let e = encode(&variants, 0, 4);
    assert_eq!(e.short_specs, vec![0]);
    let good = expected(&e, 2, 0, 4);
    assert!(decode(&e.payload, &good).is_ok());
    let others = [0u32, 5, 9];
    assert!(
        decode(
            &e.payload,
            &ExpectedMaskChunk {
                short_specs: &others,
                ..good
            }
        )
        .is_ok()
    );
    for list in [&[][..], &[0, 1][..], &[1][..]] {
        corrupt(decode(
            &e.payload,
            &ExpectedMaskChunk {
                short_specs: list,
                ..good
            },
        ));
    }
}

#[test]
fn invalid_flags_are_refused() {
    let (variants, e) = fixed_chunk();
    let n = variants.len();
    let spec_bytes: usize = variants.iter().map(|v| v.spec.len()).sum();
    let flags_at = 24 + 8 + (4 * (n + 1)).next_multiple_of(8) + spec_bytes.next_multiple_of(8);
    let good = expected(&e, n, 10, 8);
    let flag = |payload: &[u8], i: usize| {
        u16::from_le_bytes([payload[flags_at + 2 * i], payload[flags_at + 2 * i + 1]])
    };
    assert_eq!(flag(&e.payload, 0), 0x101);
    assert_eq!(flag(&e.payload, 1), 0x100);
    assert_eq!(flag(&e.payload, 2), 0);
    for (variant, value) in [
        (1usize, 0x110u16),
        (1, 0x300),
        (2, 0x010),
        (0, 0x111 | 0x8000),
    ] {
        let mut payload = e.payload.clone();
        payload[flags_at + 2 * variant..flags_at + 2 * variant + 2]
            .copy_from_slice(&value.to_le_bytes());
        corrupt(decode(&payload, &good));
    }
}

#[test]
fn every_truncation_and_extension_is_refused() {
    let (variants, e) = fixed_chunk();
    let good = expected(&e, variants.len(), 10, 8);
    for len in 0..e.payload.len() {
        assert!(decode(&e.payload[..len], &good).is_err(), "prefix of {len}");
    }
    let mut longer = e.payload.clone();
    longer.extend_from_slice(&[0; 8]);
    assert!(decode(&longer, &good).is_err());
    for at in 0..e.payload.len() {
        let mut payload = e.payload.clone();
        payload[at] ^= 0x41;
        let _ = decode(&payload, &good);
    }
}

#[test]
fn encoder_refuses_what_the_decoder_would() {
    let none: Vec<MaskVariantInput<'_>> = Vec::new();
    assert!(encode_mask_chunk(0, "chr1", 0, 4, &none).is_err());
    for spec in ["chr2,100,A,C,g,c", "chr1,x,A,C,g,c", "chr1"] {
        let v = [MaskVariantInput { spec, record: None }];
        assert!(
            matches!(
                encode_mask_chunk(0, "chr1", 0, 4, &v),
                Err(FormatError::InvalidInput(_))
            ),
            "{spec}"
        );
    }
    let positions = [4u32];
    let v = [MaskVariantInput {
        spec: "chr1,100,A,C,g,c",
        record: Some([
            Some(JavaMaskInput {
                positions: &positions,
                bitmask_form: false,
            }),
            None,
            None,
            None,
        ]),
    }];
    assert!(encode_mask_chunk(0, "chr1", 0, 4, &v).is_err());
}

#[test]
fn metadata_chunks_round_trip_and_refuse_damage() {
    let variants: Vec<Vec<&str>> = vec![
        vec!["AC=1;Gene_with_variant=X", "AC=2"],
        vec![],
        vec!["\u{e9}t\u{e9}", "dup", "dup"],
    ];
    let payload = encode_metadata_chunk(&variants).unwrap();
    let chunk = MetadataChunk::decode(AlignedBuf::copy_from(&payload), 3).unwrap();
    assert_eq!(chunk.len(), 3);
    for (i, strings) in variants.iter().enumerate() {
        assert_eq!(chunk.strings(i).collect::<Vec<_>>(), *strings);
    }
    assert_eq!(chunk.strings(3).count(), 0);
    assert!(MetadataChunk::decode(AlignedBuf::copy_from(&payload), 2).is_err());
    for len in 0..payload.len() {
        assert!(MetadataChunk::decode(AlignedBuf::copy_from(&payload[..len]), 3).is_err());
    }
    let text = payload
        .windows(2)
        .position(|w| w == "\u{e9}".as_bytes())
        .unwrap();
    let mut bad = payload.clone();
    bad[text] = 0xff;
    assert!(matches!(
        MetadataChunk::decode(AlignedBuf::copy_from(&bad), 3),
        Err(FormatError::Corrupt { .. })
    ));
    let mut bad = payload.clone();
    bad[0] = 3;
    assert!(MetadataChunk::decode(AlignedBuf::copy_from(&bad), 3).is_err());
    let mut bad = payload.clone();
    bad[20] = 9;
    assert!(MetadataChunk::decode(AlignedBuf::copy_from(&bad), 3).is_err());
}

#[test]
fn carrier_chunks_round_trip_and_refuse_damage() {
    let buckets: Vec<Vec<u32>> = vec![vec![0, 5, 99], vec![], (0..100).collect()];
    let payload = encode_carrier_chunk(&buckets, 100).unwrap();
    let chunk = CarrierChunk::decode(AlignedBuf::copy_from(&payload), 3, 100).unwrap();
    assert_eq!(chunk.len(), 3);
    for (j, bucket) in buckets.iter().enumerate() {
        assert_eq!(&chunk.bucket(j).unwrap().iter().collect::<Vec<_>>(), bucket);
    }
    assert!(chunk.bucket(3).is_none());
    assert!(CarrierChunk::decode(AlignedBuf::copy_from(&payload), 2, 100).is_err());
    assert!(CarrierChunk::decode(AlignedBuf::copy_from(&payload), 3, 99).is_err());
    for len in 0..payload.len() {
        assert!(CarrierChunk::decode(AlignedBuf::copy_from(&payload[..len]), 3, 100).is_err());
    }
    assert!(encode_carrier_chunk(&[vec![100u32]], 100).is_err());
}

fn bitmap(ids: &[u32]) -> RoaringBitmap {
    ids.iter().copied().collect()
}

#[test]
fn categorical_info_blocks_round_trip() {
    let (a, b, c) = (bitmap(&[0, 4]), bitmap(&[]), bitmap(&[1, 2, 3]));
    let carriers: [&[u32]; 3] = [&[1, 2], &[], &[0, 6]];
    let keys = [
        InfoKeyInput {
            key: "BRCA1",
            variants: &a,
            carriers: Some(carriers[0]),
        },
        InfoKeyInput {
            key: "TP53",
            variants: &b,
            carriers: Some(carriers[1]),
        },
        InfoKeyInput {
            key: "\u{e9}",
            variants: &c,
            carriers: Some(carriers[2]),
        },
    ];
    let payload = encode_info_block(&keys, None, 5, 7).unwrap();
    let expected = ExpectedInfoBlock {
        continuous: false,
        key_count: 3,
        variant_count: 5,
        patient_count: 7,
    };
    let block = InfoBlock::decode(AlignedBuf::copy_from(&payload), &expected).unwrap();
    assert!(!block.is_continuous());
    assert!(block.compressed_index().is_none());
    assert_eq!(
        block.keys().collect::<Vec<_>>(),
        vec!["BRCA1", "TP53", "\u{e9}"]
    );
    for (i, key) in keys.iter().enumerate() {
        assert_eq!(block.find_key(key.key), Some(i));
        assert_eq!(&block.variants(i).unwrap(), key.variants);
        assert_eq!(
            block.carriers(i).unwrap().iter().collect::<Vec<_>>(),
            carriers[i]
        );
    }
    assert_eq!(block.find_key("brca1"), None);

    let refusals = [
        ExpectedInfoBlock {
            continuous: true,
            ..expected
        },
        ExpectedInfoBlock {
            key_count: 2,
            ..expected
        },
        ExpectedInfoBlock {
            variant_count: 4,
            ..expected
        },
        ExpectedInfoBlock {
            patient_count: 6,
            ..expected
        },
    ];
    for refusal in refusals {
        assert!(
            InfoBlock::decode(AlignedBuf::copy_from(&payload), &refusal).is_err(),
            "{refusal:?}"
        );
    }
    for len in 0..payload.len() {
        assert!(InfoBlock::decode(AlignedBuf::copy_from(&payload[..len]), &expected).is_err());
    }
    let empty = encode_info_block(&[], None, 0, 0).unwrap();
    let block = InfoBlock::decode(
        AlignedBuf::copy_from(&empty),
        &ExpectedInfoBlock {
            continuous: false,
            key_count: 0,
            variant_count: 0,
            patient_count: 0,
        },
    )
    .unwrap();
    assert!(block.is_empty());
}

#[test]
fn info_encoder_refuses_what_the_decoder_would() {
    let a = bitmap(&[0]);
    let unordered = [
        InfoKeyInput {
            key: "b",
            variants: &a,
            carriers: Some(&[]),
        },
        InfoKeyInput {
            key: "a",
            variants: &a,
            carriers: Some(&[]),
        },
    ];
    assert!(encode_info_block(&unordered, None, 1, 1).is_err());
    let missing = [InfoKeyInput {
        key: "a",
        variants: &a,
        carriers: None,
    }];
    assert!(encode_info_block(&missing, None, 1, 1).is_err());
    let present = [InfoKeyInput {
        key: "1.5",
        variants: &a,
        carriers: Some(&[]),
    }];
    let index = build_java_compressed_index(&["1.5"]).unwrap();
    assert!(encode_info_block(&present, Some(&index), 1, 1).is_err());
    let high = bitmap(&[1]);
    let beyond = [InfoKeyInput {
        key: "a",
        variants: &high,
        carriers: Some(&[]),
    }];
    assert!(encode_info_block(&beyond, None, 1, 1).is_err());
    let numeric = [InfoKeyInput {
        key: "1.5",
        variants: &a,
        carriers: None,
    }];
    assert!(encode_info_block(&numeric, Some(&[]), 1, 1).is_err());
    let bad_entries = vec![CompressedRange {
        lower: RangeBound::Open(1.0),
        upper: RangeBound::Closed(2.0),
        entries: vec![(2.0, vec![0]), (1.0, vec![0])],
    }];
    assert!(encode_info_block(&numeric, Some(&bad_entries), 1, 1).is_err());
    let bad_keys = vec![CompressedRange {
        lower: RangeBound::Open(1.0),
        upper: RangeBound::Closed(2.0),
        entries: vec![(1.0, vec![1])],
    }];
    assert!(encode_info_block(&numeric, Some(&bad_keys), 1, 1).is_err());
    let backwards = vec![CompressedRange {
        lower: RangeBound::Open(2.0),
        upper: RangeBound::Open(2.0),
        entries: vec![],
    }];
    assert!(encode_info_block(&numeric, Some(&backwards), 1, 1).is_err());
    let unordered_ranges = vec![
        CompressedRange {
            lower: RangeBound::Open(2.0),
            upper: RangeBound::Closed(3.0),
            entries: vec![],
        },
        CompressedRange {
            lower: RangeBound::Open(1.0),
            upper: RangeBound::Closed(3.0),
            entries: vec![],
        },
    ];
    assert!(encode_info_block(&numeric, Some(&unordered_ranges), 1, 1).is_err());
}

/// A continuous block over `keys`, every key holding variant 0.
fn continuous_block(keys: &[&str], index: &[CompressedRange]) -> InfoBlock {
    let variants = bitmap(&[0]);
    let inputs: Vec<InfoKeyInput<'_>> = keys
        .iter()
        .map(|key| InfoKeyInput {
            key,
            variants: &variants,
            carriers: None,
        })
        .collect();
    let payload = encode_info_block(&inputs, Some(index), 1, 3).unwrap();
    InfoBlock::decode(
        AlignedBuf::copy_from(&payload),
        &ExpectedInfoBlock {
            continuous: true,
            key_count: keys.len() as u32,
            variant_count: 1,
            patient_count: 3,
        },
    )
    .unwrap()
}

#[test]
fn the_compressed_index_follows_guava_cuts() {
    let keys = ["1", "2", "3"];
    let index = vec![CompressedRange {
        lower: RangeBound::Open(1.0),
        upper: RangeBound::Closed(3.0),
        entries: vec![(1.0, vec![0]), (2.0, vec![1])],
    }];
    let block = continuous_block(&keys, &index);
    let ci = block.compressed_index().unwrap();
    assert_eq!(ci.len(), 1);
    assert_eq!(
        ci.bounds(0),
        Some((RangeBound::Open(1.0), RangeBound::Closed(3.0)))
    );
    let cases: [(f32, f32, &[u32]); 9] = [
        (0.0, 5.0, &[0, 1]),
        (1.0, 3.0, &[0, 1]),
        (2.0, 2.0, &[1]),
        (1.0, 1.0, &[0]),
        (3.0, 4.0, &[]),
        (3.5, 4.0, &[]),
        (-1.0, 0.5, &[]),
        (1.5, 2.5, &[1]),
        (f32::NAN, f32::NAN, &[]),
    ];
    for (min, max, want) in cases {
        assert_eq!(ci.keys_in_range(min, max).unwrap(), want, "[{min}, {max}]");
    }
    assert!(ci.keys_in_range(2.0, 1.0).is_err());
    assert!(ci.keys_in_range(0.0, -0.0).is_err());
}

#[test]
fn unbounded_and_closed_ranges_order_and_match() {
    let keys = ["a", "b"];
    let index = vec![
        CompressedRange {
            lower: RangeBound::Unbounded,
            upper: RangeBound::Open(0.0),
            entries: vec![(-5.0, vec![0])],
        },
        CompressedRange {
            lower: RangeBound::Closed(0.0),
            upper: RangeBound::Unbounded,
            entries: vec![(f32::NAN, vec![1])],
        },
    ];
    let block = continuous_block(&keys, &index);
    let ci = block.compressed_index().unwrap();
    assert_eq!(
        ci.keys_in_range(f32::NEG_INFINITY, f32::INFINITY).unwrap(),
        vec![0]
    );
    assert_eq!(
        ci.keys_in_range(f32::NEG_INFINITY, f32::NAN).unwrap(),
        vec![0, 1]
    );
    assert_eq!(ci.keys_in_range(f32::NAN, f32::NAN).unwrap(), vec![1]);
    assert_eq!(ci.keys_in_range(-0.0, -0.0).unwrap(), Vec::<u32>::new());
}

#[test]
fn java_build_partitions_values_and_drops_each_partition_maximum() {
    let names: Vec<String> = (0..2500).map(|i| format!("{i}")).collect();
    let mut keys: Vec<&str> = names.iter().map(String::as_str).collect();
    keys.push("x,7.5,y");
    keys.push("not a number");
    keys.push("1,");
    keys.sort_by(|a, b| hpds_format::java_string_cmp(a, b));
    let index = build_java_compressed_index(&keys).unwrap();
    assert_eq!(index.len(), 4);
    let first_floats = |r: &CompressedRange| r.entries.iter().map(|e| e.0).collect::<Vec<_>>();
    assert_eq!(index[0].lower, RangeBound::Open(0.0));
    assert_eq!(index[0].upper, RangeBound::Closed(998.0));
    assert_eq!(index[1].upper, RangeBound::Closed(999.0));
    assert_eq!(first_floats(&index[0]), first_floats(&index[1]));
    assert_eq!(index[0].entries.len(), 999);
    assert!(index[0].entries.iter().all(|e| e.0 != 998.0));
    assert_eq!(index[3].lower, RangeBound::Open(1999.0));
    assert_eq!(index[3].upper, RangeBound::Closed(2499.0));
    assert_eq!(index[2].lower, RangeBound::Open(999.0));
    assert_eq!(index[2].upper, RangeBound::Closed(1999.0));
    assert!(index[3].entries.iter().all(|e| e.0 != 2499.0));
    let seven_and_half = keys.iter().position(|k| *k == "x,7.5,y").unwrap() as u32;
    let seven = keys.iter().position(|k| *k == "7").unwrap() as u32;
    let entry = |v: f32| {
        index[0]
            .entries
            .iter()
            .find(|e| e.0 == v)
            .unwrap()
            .1
            .clone()
    };
    assert_eq!(entry(7.5), vec![seven_and_half]);
    assert_eq!(entry(7.0), vec![seven]);
    assert!(
        index[0]
            .entries
            .iter()
            .all(|e| e.1.iter().all(|&k| keys[k as usize] != "1,"))
    );

    let block = continuous_block(&keys, &index);
    let ci = block.compressed_index().unwrap();
    assert_eq!(ci.keys_in_range(998.0, 998.0).unwrap(), Vec::<u32>::new());
    assert_eq!(ci.keys_in_range(7.5, 7.5).unwrap(), vec![seven_and_half]);

    let single = build_java_compressed_index(&["5"]).unwrap();
    assert_eq!(single.len(), 1);
    assert!(single[0].entries.is_empty());
    assert!(build_java_compressed_index(&["a", "b"]).is_none());
    let neg = build_java_compressed_index(&["-0", "0"]).unwrap();
    assert_eq!(neg[0].lower, RangeBound::Open(-0.0));
    assert_eq!(neg[0].entries.len(), 1);
    assert_eq!(neg[0].entries[0].0.to_bits(), (-0.0f32).to_bits());
}

#[test]
fn negative_positions_fall_in_java_int_division_buckets() {
    let variants = vec![
        Variant {
            spec: "chr1,-1500,A,C,g,c".into(),
            record: None,
        },
        Variant {
            spec: "chr1,-999,A,C,g,c".into(),
            record: None,
        },
        Variant {
            spec: "chr1,5,A,C,g,c".into(),
            record: None,
        },
        Variant {
            spec: "chr1,999,A,C,g,c".into(),
            record: None,
        },
    ];
    let e = encode(&variants, 0, 1);
    assert_eq!((e.first_bucket, e.last_bucket, e.bucket_count), (-1, 0, 2));
    let chunk = decode(&e.payload, &expected(&e, 4, 0, 1)).unwrap();
    assert_eq!(chunk.find_bucket(0), Some(1..4));
    assert_eq!(chunk.find_bucket(-1), Some(0..1));
}
