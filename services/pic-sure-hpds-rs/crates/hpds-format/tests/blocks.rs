use std::cmp::Ordering;
use std::collections::BTreeSet;

use hpds_format::{
    AlignedBuf, Block, BlockCodec, BlocksReader, BlocksWriter, CategoricalObservation, CryptoKey,
    FormatError, NO_TIMESTAMP, NumericObservation, encode_categorical_payload,
    encode_numeric_payload, java_double_cmp, java_string_cmp,
};
use proptest::prelude::*;

fn key() -> CryptoKey {
    CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap()
}

fn codecs() -> Vec<BlockCodec> {
    vec![
        BlockCodec::new(None, true),
        BlockCodec::new(None, false),
        BlockCodec::new(Some(key()), true),
        BlockCodec::new(Some(key()), false),
    ]
}

fn timestamp() -> impl Strategy<Value = i64> {
    prop_oneof![Just(NO_TIMESTAMP), any::<i64>(), 0i64..5]
}

fn numeric_value() -> impl Strategy<Value = f64> {
    prop_oneof![
        any::<f64>(),
        Just(0.0),
        Just(-0.0),
        Just(f64::NAN),
        Just(f64::from_bits(0xfff8000000000001)),
        (-3i32..3).prop_map(f64::from),
    ]
}

fn numeric_observations() -> impl Strategy<Value = Vec<NumericObservation>> {
    proptest::collection::vec(
        (0u32..50, numeric_value(), timestamp()).prop_map(|(patient, value, timestamp)| {
            NumericObservation {
                patient,
                value,
                timestamp,
            }
        }),
        0..200,
    )
}

fn categorical_observations() -> impl Strategy<Value = Vec<CategoricalObservation>> {
    let value = prop_oneof![
        "[a-c]{0,2}",
        Just("\u{e000}".to_string()),
        Just("\u{1f600}".to_string()),
        "\\PC{0,4}",
    ];
    proptest::collection::vec(
        (0u32..50, value, timestamp()).prop_map(|(patient, value, timestamp)| {
            CategoricalObservation {
                patient,
                value,
                timestamp,
            }
        }),
        0..200,
    )
}

type Key = (u32, i64, u64);

fn check_numeric(input: &[NumericObservation], block: &Block) {
    let Block::Numeric(b) = block else {
        panic!("expected numeric block")
    };
    assert_eq!(b.len(), input.len());
    let has_timestamp = input.iter().any(|o| o.timestamp != NO_TIMESTAMP);
    assert_eq!(b.has_timestamp(), has_timestamp);
    assert_eq!(b.timestamps().is_some(), has_timestamp);
    let (values, patients) = (b.values(), b.patients());
    let mut decoded: Vec<Key> = (0..b.len())
        .map(|i| (patients[i], b.timestamp_at(i), values[i].to_bits()))
        .collect();
    let mut expected: Vec<Key> = input
        .iter()
        .map(|o| {
            (
                o.patient,
                o.timestamp,
                hpds_format::canonical_nan(o.value).to_bits(),
            )
        })
        .collect();
    decoded.sort_unstable();
    expected.sort_unstable();
    assert_eq!(decoded, expected);
    for i in 1..b.len() {
        let order = java_double_cmp(values[i - 1], values[i])
            .then(patients[i - 1].cmp(&patients[i]))
            .then(b.timestamp_at(i - 1).cmp(&b.timestamp_at(i)));
        assert_ne!(order, Ordering::Greater, "value order at {i}");
    }
    let by_key = b.by_key();
    assert_eq!(
        by_key.iter().copied().collect::<BTreeSet<u32>>().len(),
        b.len()
    );
    for w in by_key.windows(2) {
        let (x, y) = (w[0] as usize, w[1] as usize);
        assert_ne!(
            (patients[x], b.timestamp_at(x)).cmp(&(patients[y], b.timestamp_at(y))),
            Ordering::Greater
        );
    }
}

fn check_categorical(input: &[CategoricalObservation], block: &Block) {
    let Block::Categorical(b) = block else {
        panic!("expected categorical block")
    };
    assert_eq!(b.len(), input.len());
    let dictionary: Vec<&str> = b.dictionary().collect();
    for w in dictionary.windows(2) {
        assert_eq!(java_string_cmp(w[0], w[1]), Ordering::Less);
    }
    let distinct: BTreeSet<&str> = input.iter().map(|o| o.value.as_str()).collect();
    assert_eq!(
        dictionary.iter().copied().collect::<BTreeSet<&str>>(),
        distinct
    );
    for (i, value) in dictionary.iter().enumerate() {
        assert_eq!(b.find_value(value), Some(i));
        let expected: BTreeSet<u32> = input
            .iter()
            .filter(|o| o.value == *value)
            .map(|o| o.patient)
            .collect();
        assert_eq!(
            b.bitmap(i).unwrap().iter().collect::<BTreeSet<u32>>(),
            expected
        );
    }
    assert_eq!(b.find_value("not a value \u{0}"), None);
    let all: BTreeSet<u32> = input.iter().map(|o| o.patient).collect();
    assert_eq!(
        b.all_patients().unwrap().iter().collect::<BTreeSet<u32>>(),
        all
    );
    assert_eq!(
        b.timestamps().is_some(),
        input.iter().any(|o| o.timestamp != NO_TIMESTAMP)
    );
    let (patients, indices) = (b.patients(), b.value_indices());
    let mut decoded: Vec<(u32, i64, String)> = (0..b.len())
        .map(|i| {
            (
                patients[i],
                b.timestamp_at(i),
                dictionary[indices[i] as usize].to_string(),
            )
        })
        .collect();
    for w in decoded.windows(2) {
        assert_ne!((w[0].0, w[0].1).cmp(&(w[1].0, w[1].1)), Ordering::Greater);
    }
    let mut expected: Vec<(u32, i64, String)> = input
        .iter()
        .map(|o| (o.patient, o.timestamp, o.value.clone()))
        .collect();
    decoded.sort();
    expected.sort();
    assert_eq!(decoded, expected);
}

proptest! {
    #[test]
    fn numeric_blocks_round_trip(input in numeric_observations()) {
        let payload = encode_numeric_payload(&mut input.clone()).unwrap();
        for codec in codecs() {
            let stored = codec.seal("\\demo\\age\\", &payload).unwrap();
            check_numeric(&input, &codec.open("\\demo\\age\\", &stored).unwrap());
        }
    }

    #[test]
    fn categorical_blocks_round_trip(input in categorical_observations()) {
        let payload = encode_categorical_payload(&input).unwrap();
        for codec in codecs() {
            let stored = codec.seal("\\demo\\sex\\", &payload).unwrap();
            check_categorical(&input, &codec.open("\\demo\\sex\\", &stored).unwrap());
        }
    }

    #[test]
    fn arbitrary_payloads_never_panic(bytes in proptest::collection::vec(any::<u8>(), 0..512), kind in 0u8..3) {
        let mut bytes = bytes;
        if bytes.len() > 5 {
            bytes[5] = kind;
        }
        let _ = Block::decode(AlignedBuf::copy_from(&bytes));
    }

    #[test]
    fn damaged_payloads_never_panic(
        input in numeric_observations(),
        cat in categorical_observations(),
        cut in any::<prop::sample::Index>(),
        flip in any::<prop::sample::Index>(),
        mask in 1u8..,
    ) {
        for payload in [encode_numeric_payload(&mut input.clone()).unwrap(), encode_categorical_payload(&cat).unwrap()] {
            let truncated = &payload[..cut.index(payload.len() + 1)];
            if truncated.len() < payload.len() {
                prop_assert!(Block::decode(AlignedBuf::copy_from(truncated)).is_err());
            }
            let mut flipped = payload.clone();
            let i = flip.index(flipped.len());
            flipped[i] ^= mask;
            let _ = Block::decode(AlignedBuf::copy_from(&flipped));
        }
    }

    #[test]
    fn stored_block_damage_is_detected(
        input in numeric_observations(),
        flip in any::<prop::sample::Index>(),
        mask in 1u8..,
        cut in any::<prop::sample::Index>(),
    ) {
        let payload = encode_numeric_payload(&mut input.clone()).unwrap();
        for codec in codecs() {
            let stored = codec.seal("\\p\\", &payload).unwrap();
            let mut flipped = stored.clone();
            let i = flip.index(flipped.len());
            flipped[i] ^= mask;
            prop_assert!(codec.open("\\p\\", &flipped).is_err());
            let truncated = &stored[..cut.index(stored.len())];
            prop_assert!(codec.open("\\p\\", truncated).is_err());
        }
    }
}

fn sample_numeric() -> Vec<u8> {
    let mut obs: Vec<NumericObservation> = (0..100)
        .map(|i| NumericObservation {
            patient: i,
            value: f64::from(i % 7),
            timestamp: NO_TIMESTAMP,
        })
        .collect();
    encode_numeric_payload(&mut obs).unwrap()
}

#[test]
fn encrypted_block_moved_to_another_concept_fails_authentication() {
    let codec = BlockCodec::new(Some(key()), true);
    let stored = codec.seal("\\a\\", &sample_numeric()).unwrap();
    assert!(codec.open("\\a\\", &stored).is_ok());
    assert!(matches!(
        codec.open("\\b\\", &stored),
        Err(FormatError::Authentication)
    ));
    assert!(matches!(
        codec.open("\\a\\x", &stored),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn wrong_key_fails_authentication() {
    let stored = BlockCodec::new(Some(key()), true)
        .seal("\\a\\", &sample_numeric())
        .unwrap();
    let other = BlockCodec::new(Some(CryptoKey::from_bytes(&[9u8; 32]).unwrap()), true);
    assert!(matches!(
        other.open("\\a\\", &stored),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn plain_block_damage_fails_checksum() {
    let codec = BlockCodec::new(None, true);
    let mut stored = codec.seal("\\a\\", &sample_numeric()).unwrap();
    stored[3] ^= 0x40;
    assert!(matches!(
        codec.open("\\a\\", &stored),
        Err(FormatError::Checksum(_))
    ));
    assert!(matches!(
        codec.open("\\a\\", &stored[..12]),
        Err(FormatError::Checksum(_))
    ));
    assert!(matches!(
        codec.open("\\a\\", &[]),
        Err(FormatError::Truncated(_))
    ));
}

#[test]
fn numeric_value_range_follows_double_compare_to() {
    let mut obs: Vec<NumericObservation> = [-1.0, -0.0, 0.0, 0.0, 1.0, 2.5, f64::NAN]
        .iter()
        .enumerate()
        .map(|(i, &value)| NumericObservation {
            patient: i as u32,
            value,
            timestamp: NO_TIMESTAMP,
        })
        .collect();
    let codec = BlockCodec::new(None, false);
    let block = codec
        .open(
            "\\n\\",
            &codec
                .seal("\\n\\", &encode_numeric_payload(&mut obs).unwrap())
                .unwrap(),
        )
        .unwrap();
    let Block::Numeric(b) = block else { panic!() };
    assert_eq!(b.value_range(Some(0.0), Some(1.0)), 2..5);
    assert_eq!(b.value_range(Some(-0.0), Some(0.0)), 1..4);
    assert_eq!(b.value_range(None, Some(-0.0)), 0..2);
    assert_eq!(b.value_range(Some(3.0), None), 6..7);
    assert_eq!(b.value_range(Some(2.0), Some(1.0)), 5..5);
    assert_eq!(b.value_range(None, None), 0..7);
}

#[test]
fn blocks_file_round_trip_and_bounds() {
    let dir = std::env::temp_dir().join(format!("hpds-format-blocks-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(hpds_format::BLOCKS_FILE_NAME);
    let codec = BlockCodec::new(Some(key()), true);
    let cat = vec![CategoricalObservation {
        patient: 3,
        value: "yes".into(),
        timestamp: 5,
    }];
    let mut writer = BlocksWriter::new(std::fs::File::create(&path).unwrap()).unwrap();
    assert!(writer.is_empty());
    let a = writer.append(&codec, "\\a\\", &sample_numeric()).unwrap();
    let b = writer
        .append(&codec, "\\b\\", &encode_categorical_payload(&cat).unwrap())
        .unwrap();
    assert_eq!(a.offset, 8);
    assert_eq!(b.offset, a.offset + a.length);
    writer.finish().unwrap();

    let reader = BlocksReader::open(&path).unwrap();
    assert!(matches!(
        reader.read_block(&codec, "\\a\\", a).unwrap(),
        Block::Numeric(_)
    ));
    check_categorical(&cat, &reader.read_block(&codec, "\\b\\", b).unwrap());
    assert!(matches!(
        reader.read_block(&codec, "\\a\\", b),
        Err(FormatError::Authentication)
    ));
    let past_end = hpds_format::BlockLocation {
        offset: b.offset,
        length: b.length + 1,
    };
    assert!(matches!(
        reader.read_stored(past_end),
        Err(FormatError::Corrupt { .. })
    ));
    let overflow = hpds_format::BlockLocation {
        offset: u64::MAX,
        length: 2,
    };
    assert!(reader.read_stored(overflow).is_err());
    std::fs::write(dir.join("bad"), b"NOTBLOCKS").unwrap();
    assert!(matches!(
        BlocksReader::open(dir.join("bad")),
        Err(FormatError::BadMagic { .. })
    ));
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn typed_views_are_aligned_and_zero_copy() {
    let codec = BlockCodec::new(None, true);
    let block = codec
        .open("\\a\\", &codec.seal("\\a\\", &sample_numeric()).unwrap())
        .unwrap();
    let Block::Numeric(b) = block else { panic!() };
    assert_eq!(b.values().as_ptr() as usize % 8, 0);
    assert_eq!(b.values().len(), 100);
    assert_eq!(b.patients().len(), 100);
    assert_eq!(b.by_key().len(), 100);
    assert!(b.timestamps().is_none());
}
