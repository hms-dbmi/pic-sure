use std::cmp::Ordering;
use std::collections::BTreeSet;

use hpds_format::{
    AlignedBuf, BLOCKS_HEADER_LEN, Block, BlockCodec, BlockLocation, BlocksReader, BlocksWriter,
    CategoricalObservation, CategoricalRow, ConceptMeta, ConceptType, CryptoKey, FormatError,
    NO_TIMESTAMP, NumericObservation, Protection, StoreId, canonical_nan,
    encode_categorical_payload, encode_interned_categorical_payload, encode_numeric_payload,
    java_double_cmp, java_string_cmp,
};
use proptest::prelude::*;

fn key() -> CryptoKey {
    CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap()
}

fn store() -> StoreId {
    StoreId::from_bytes([0x5a; 16])
}

fn encrypted() -> Protection {
    Protection::Encrypted(key())
}

fn codecs() -> Vec<BlockCodec> {
    vec![
        BlockCodec::new(Protection::Plain, store(), true),
        BlockCodec::new(Protection::Plain, store(), false),
        BlockCodec::new(encrypted(), store(), true),
        BlockCodec::new(encrypted(), store(), false),
    ]
}

fn meta(path: &str, concept_type: ConceptType, observations: usize) -> ConceptMeta {
    ConceptMeta {
        path: path.into(),
        concept_type,
        category_values: None,
        min: None,
        max: None,
        observation_count: observations as u32,
        patient_count: 0,
        has_timestamp: false,
        timestamp_min: None,
        timestamp_max: None,
        width_in_bytes: 0,
        column_offset: 0,
        block: BlockLocation::default(),
    }
}

fn numeric_meta(input: &[NumericObservation]) -> ConceptMeta {
    meta("\\n\\", ConceptType::Numeric, input.len())
}

fn categorical_meta(input: &[CategoricalObservation]) -> ConceptMeta {
    let mut m = meta("\\c\\", ConceptType::Categorical, input.len());
    let distinct: BTreeSet<String> = input.iter().map(|o| o.value.clone()).collect();
    m.category_values = Some(distinct.into_iter().collect());
    m
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

fn stable_by_patient<T: Clone>(input: &[T], patient: impl Fn(&T) -> u32) -> Vec<T> {
    let mut sorted = input.to_vec();
    sorted.sort_by_key(|o| patient(o));
    sorted
}

fn check_numeric(input: &[NumericObservation], block: &Block) {
    let Block::Numeric(b) = block else {
        panic!("expected numeric block")
    };
    assert_eq!(b.len(), input.len());
    let has_timestamp = input.iter().any(|o| o.timestamp != NO_TIMESTAMP);
    assert_eq!(b.has_timestamp(), has_timestamp);
    assert_eq!(b.timestamps().is_some(), has_timestamp);
    let (values, patients) = (b.values(), b.patients());
    for i in 1..b.len() {
        let order = java_double_cmp(values[i - 1], values[i])
            .then(patients[i - 1].cmp(&patients[i]))
            .then(
                b.timestamp_at_value_pos(i - 1)
                    .cmp(&b.timestamp_at_value_pos(i)),
            );
        assert_ne!(order, Ordering::Greater, "value order at {i}");
    }
    let walked: Vec<(u32, u64, i64)> = b
        .by_key()
        .iter()
        .map(|&i| {
            let i = i as usize;
            (
                patients[i],
                values[i].to_bits(),
                b.timestamp_at_value_pos(i),
            )
        })
        .collect();
    let expected: Vec<(u32, u64, i64)> = stable_by_patient(input, |o| o.patient)
        .iter()
        .map(|o| (o.patient, canonical_nan(o.value).to_bits(), o.timestamp))
        .collect();
    assert_eq!(walked, expected, "by_key is the stable sort on patient");
    let all: BTreeSet<u32> = input.iter().map(|o| o.patient).collect();
    assert_eq!(b.all_patients().iter().collect::<BTreeSet<u32>>(), all);
}

fn check_categorical(input: &[CategoricalObservation], block: &Block) {
    let Block::Categorical(b) = block else {
        panic!("expected categorical block")
    };
    assert_eq!(b.len(), input.len());
    let dictionary: Vec<&str> = b.dictionary().collect();
    assert_eq!(dictionary.len(), b.dictionary_len());
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
        assert_eq!(b.dictionary_value(i), Some(*value));
        let expected: BTreeSet<u32> = input
            .iter()
            .filter(|o| o.value == *value)
            .map(|o| o.patient)
            .collect();
        assert_eq!(b.bitmap(i).iter().collect::<BTreeSet<u32>>(), expected);
        let reparsed =
            roaring::RoaringBitmap::deserialize_from(b.serialized_bitmap(i).unwrap()).unwrap();
        assert_eq!(&reparsed, b.bitmap(i));
    }
    assert_eq!(b.bitmaps().len(), dictionary.len());
    assert_eq!(b.dictionary_value(dictionary.len()), None);
    assert_eq!(b.serialized_bitmap(dictionary.len()), None);
    assert_eq!(b.find_value("not a value \u{0}"), None);
    let all: BTreeSet<u32> = input.iter().map(|o| o.patient).collect();
    assert_eq!(b.all_patients().iter().collect::<BTreeSet<u32>>(), all);
    assert_eq!(
        b.timestamps().is_some(),
        input.iter().any(|o| o.timestamp != NO_TIMESTAMP)
    );
    let (patients, indices) = (b.patients(), b.value_indices());
    let walked: Vec<(u32, String, i64)> = (0..b.len())
        .map(|i| {
            (
                patients[i],
                dictionary[indices[i] as usize].to_string(),
                b.timestamp_at_key_pos(i),
            )
        })
        .collect();
    let expected: Vec<(u32, String, i64)> = stable_by_patient(input, |o| o.patient)
        .into_iter()
        .map(|o| (o.patient, o.value, o.timestamp))
        .collect();
    assert_eq!(walked, expected, "key order is the stable sort on patient");
}

proptest! {
    #[test]
    fn numeric_blocks_round_trip(input in numeric_observations()) {
        let payload = encode_numeric_payload(&input).unwrap();
        let limit = numeric_meta(&input).max_payload_len();
        prop_assert!(payload.len() as u64 <= limit);
        for codec in codecs() {
            let stored = codec.seal("\\demo\\age\\", &payload).unwrap();
            check_numeric(&input, &codec.open("\\demo\\age\\", &stored, limit).unwrap());
        }
    }

    #[test]
    fn categorical_blocks_round_trip(input in categorical_observations()) {
        let payload = encode_categorical_payload(&input).unwrap();
        let limit = categorical_meta(&input).max_payload_len();
        prop_assert!(payload.len() as u64 <= limit);
        for codec in codecs() {
            let stored = codec.seal("\\demo\\sex\\", &payload).unwrap();
            check_categorical(&input, &codec.open("\\demo\\sex\\", &stored, limit).unwrap());
        }
    }

    #[test]
    fn interned_encoding_matches_string_encoding(
        input in categorical_observations(),
        unused in proptest::collection::vec("[x-z]{3}", 0..3),
        rotate in any::<prop::sample::Index>(),
    ) {
        let mut values: Vec<String> = input
            .iter()
            .map(|o| o.value.clone())
            .collect::<BTreeSet<String>>()
            .into_iter()
            .chain(unused.into_iter().map(|u| format!("unused {u}")))
            .collect::<BTreeSet<String>>()
            .into_iter()
            .collect();
        if !values.is_empty() {
            let shift = rotate.index(values.len());
            values.rotate_left(shift);
        }
        let rows: Vec<CategoricalRow> = input
            .iter()
            .map(|o| CategoricalRow {
                patient: o.patient,
                value_id: values.iter().position(|v| *v == o.value).unwrap() as u32,
                timestamp: o.timestamp,
            })
            .collect();
        prop_assert_eq!(
            encode_interned_categorical_payload(&values, &rows).unwrap(),
            encode_categorical_payload(&input).unwrap()
        );
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
        for payload in [encode_numeric_payload(&input).unwrap(), encode_categorical_payload(&cat).unwrap()] {
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
        let payload = encode_numeric_payload(&input).unwrap();
        for codec in codecs() {
            let stored = codec.seal("\\p\\", &payload).unwrap();
            let mut flipped = stored.clone();
            let i = flip.index(flipped.len());
            flipped[i] ^= mask;
            prop_assert!(codec.open("\\p\\", &flipped, u64::MAX).is_err());
            let truncated = &stored[..cut.index(stored.len())];
            prop_assert!(codec.open("\\p\\", truncated, u64::MAX).is_err());
        }
    }
}

fn sample_input() -> Vec<NumericObservation> {
    (0..100)
        .map(|i| NumericObservation {
            patient: i,
            value: f64::from(i % 7),
            timestamp: NO_TIMESTAMP,
        })
        .collect()
}

fn sample_numeric() -> Vec<u8> {
    encode_numeric_payload(&sample_input()).unwrap()
}

fn open_numeric(input: &[NumericObservation]) -> hpds_format::NumericBlock {
    let codec = BlockCodec::new(Protection::Plain, store(), true);
    let stored = codec
        .seal("\\n\\", &encode_numeric_payload(input).unwrap())
        .unwrap();
    match codec.open("\\n\\", &stored, u64::MAX).unwrap() {
        Block::Numeric(b) => b,
        Block::Categorical(_) => panic!("expected numeric block"),
    }
}

fn obs(patient: u32, value: f64, timestamp: i64) -> NumericObservation {
    NumericObservation {
        patient,
        value,
        timestamp,
    }
}

#[test]
fn within_patient_order_is_file_order_not_timestamp_or_value() {
    let input = [
        obs(2, 9.0, 50),
        obs(1, 5.0, 300),
        obs(1, 1.0, 100),
        obs(2, 7.0, 10),
        obs(1, 3.0, 200),
        obs(1, 3.0, NO_TIMESTAMP),
    ];
    let b = open_numeric(&input);
    let walked: Vec<(u32, f64, i64)> = b
        .by_key()
        .iter()
        .map(|&i| {
            let i = i as usize;
            (b.patients()[i], b.values()[i], b.timestamp_at_value_pos(i))
        })
        .collect();
    assert_eq!(
        walked,
        [
            (1, 5.0, 300),
            (1, 1.0, 100),
            (1, 3.0, 200),
            (1, 3.0, NO_TIMESTAMP),
            (2, 9.0, 50),
            (2, 7.0, 10),
        ]
    );
    assert_eq!(b.values(), [1.0, 3.0, 3.0, 5.0, 7.0, 9.0]);
    assert_eq!(b.timestamp_at_value_pos(1), NO_TIMESTAMP);
    assert_eq!(b.timestamp_at_value_pos(2), 200);

    let cat: Vec<CategoricalObservation> = [(2, "b", 5), (1, "z", 9), (1, "a", 1), (1, "z", 3)]
        .iter()
        .map(|&(patient, value, timestamp)| CategoricalObservation {
            patient,
            value: value.into(),
            timestamp,
        })
        .collect();
    let codec = BlockCodec::new(Protection::Plain, store(), false);
    let stored = codec
        .seal("\\c\\", &encode_categorical_payload(&cat).unwrap())
        .unwrap();
    let Block::Categorical(c) = codec.open("\\c\\", &stored, u64::MAX).unwrap() else {
        panic!("expected categorical block")
    };
    let walked: Vec<(u32, &str, i64)> = (0..c.len())
        .map(|i| {
            (
                c.patients()[i],
                c.dictionary_value(c.value_indices()[i] as usize).unwrap(),
                c.timestamp_at_key_pos(i),
            )
        })
        .collect();
    assert_eq!(walked, [(1, "z", 9), (1, "a", 1), (1, "z", 3), (2, "b", 5)]);
}

#[test]
fn input_in_java_sorted_by_key_order_round_trips_unchanged() {
    let migrated: Vec<NumericObservation> = (0..40u32)
        .flat_map(|patient| {
            (0..5u32).map(move |j| {
                let wobble = (patient * 7 + j * 13) % 5;
                obs(
                    patient,
                    f64::from(wobble) - 2.0,
                    1_000 - i64::from(j * 100 + wobble),
                )
            })
        })
        .collect();
    let b = open_numeric(&migrated);
    let walked: Vec<NumericObservation> = b
        .by_key()
        .iter()
        .map(|&i| {
            let i = i as usize;
            obs(b.patients()[i], b.values()[i], b.timestamp_at_value_pos(i))
        })
        .collect();
    assert_eq!(walked, migrated);
}

#[test]
fn encoding_does_not_reorder_the_callers_observations() {
    let input = vec![obs(3, 2.0, 1), obs(1, 1.0, 2), obs(2, 0.0, 3)];
    let copy = input.clone();
    encode_numeric_payload(&input).unwrap();
    assert_eq!(input, copy);
}

#[test]
fn equal_observations_encode_deterministically() {
    let input = vec![obs(1, 2.0, 5), obs(1, 2.0, 5), obs(0, 2.0, 5)];
    assert_eq!(
        encode_numeric_payload(&input).unwrap(),
        encode_numeric_payload(&input).unwrap()
    );
}

#[test]
fn interned_encoder_rejects_bad_ids_and_duplicate_values() {
    let row = |value_id| CategoricalRow {
        patient: 1,
        value_id,
        timestamp: NO_TIMESTAMP,
    };
    assert!(matches!(
        encode_interned_categorical_payload(&["a"], &[row(1)]),
        Err(FormatError::InvalidInput(_))
    ));
    assert!(matches!(
        encode_interned_categorical_payload(&["a", "a"], &[row(0), row(1)]),
        Err(FormatError::InvalidInput(_))
    ));
    assert!(encode_interned_categorical_payload(&["a", "a"], &[row(0)]).is_ok());
}

#[test]
fn encrypted_block_moved_to_another_concept_fails_authentication() {
    let codec = BlockCodec::new(encrypted(), store(), true);
    let stored = codec.seal("\\a\\", &sample_numeric()).unwrap();
    assert!(codec.open("\\a\\", &stored, u64::MAX).is_ok());
    assert!(matches!(
        codec.open("\\b\\", &stored, u64::MAX),
        Err(FormatError::Authentication)
    ));
    assert!(matches!(
        codec.open("\\a\\x", &stored, u64::MAX),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn block_from_another_store_fails_authentication_under_the_same_key() {
    let ours = BlockCodec::new(encrypted(), store(), true);
    let theirs = BlockCodec::new(encrypted(), StoreId::random().unwrap(), true);
    let stored = theirs.seal("\\a\\", &sample_numeric()).unwrap();
    assert!(theirs.open("\\a\\", &stored, u64::MAX).is_ok());
    assert!(matches!(
        ours.open("\\a\\", &stored, u64::MAX),
        Err(FormatError::Authentication)
    ));
    let plain_ours = BlockCodec::new(Protection::Plain, store(), true);
    let plain_theirs = BlockCodec::new(Protection::Plain, theirs.store_id(), true);
    let stored = plain_theirs.seal("\\a\\", &sample_numeric()).unwrap();
    assert!(matches!(
        plain_ours.open("\\a\\", &stored, u64::MAX),
        Err(FormatError::Checksum(_))
    ));
}

#[test]
fn encrypted_codec_refuses_plain_blocks() {
    let plain = BlockCodec::new(Protection::Plain, store(), true);
    let stored = plain.seal("\\a\\", &sample_numeric()).unwrap();
    let encrypted = BlockCodec::new(encrypted(), store(), true);
    assert!(encrypted.open("\\a\\", &stored, u64::MAX).is_err());
    let mut framed = 12i32.to_be_bytes().to_vec();
    framed.extend_from_slice(&stored);
    assert!(matches!(
        encrypted.open("\\a\\", &framed, u64::MAX),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn wrong_key_fails_authentication() {
    let stored = BlockCodec::new(encrypted(), store(), true)
        .seal("\\a\\", &sample_numeric())
        .unwrap();
    let other = BlockCodec::new(
        Protection::Encrypted(CryptoKey::from_bytes(&[9u8; 32]).unwrap()),
        store(),
        true,
    );
    assert!(matches!(
        other.open("\\a\\", &stored, u64::MAX),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn plain_block_damage_fails_checksum() {
    let codec = BlockCodec::new(Protection::Plain, store(), true);
    let mut stored = codec.seal("\\a\\", &sample_numeric()).unwrap();
    stored[3] ^= 0x40;
    assert!(matches!(
        codec.open("\\a\\", &stored, u64::MAX),
        Err(FormatError::Checksum(_))
    ));
    assert!(matches!(
        codec.open("\\a\\", &stored[..12], u64::MAX),
        Err(FormatError::Checksum(_))
    ));
    assert!(matches!(
        codec.open("\\a\\", &[], u64::MAX),
        Err(FormatError::Truncated(_))
    ));
}

#[test]
fn payloads_above_the_metadata_bound_are_refused() {
    let input: Vec<NumericObservation> = (0..10_000)
        .map(|i| obs(i, f64::from(i % 13), i64::from(i)))
        .collect();
    let payload = encode_numeric_payload(&input).unwrap();
    let bound = numeric_meta(&input).max_payload_len();
    assert!(bound >= payload.len() as u64);
    let understated = numeric_meta(&input[..100]).max_payload_len();
    assert!(understated < payload.len() as u64);
    for codec in codecs() {
        let stored = codec.seal("\\a\\", &payload).unwrap();
        assert!(codec.open("\\a\\", &stored, bound).is_ok());
        assert!(matches!(
            codec.open("\\a\\", &stored, understated),
            Err(FormatError::PayloadTooLarge { declared, limit, .. })
                if declared == payload.len() as u64 && limit == understated
        ));
    }
}

#[test]
fn numeric_value_range_follows_double_compare_to() {
    let input: Vec<NumericObservation> = [-1.0, -0.0, 0.0, 0.0, 1.0, 2.5, f64::NAN]
        .iter()
        .enumerate()
        .map(|(i, &value)| obs(i as u32, value, NO_TIMESTAMP))
        .collect();
    let b = open_numeric(&input);
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
    let codec = BlockCodec::new(encrypted(), store(), true);
    let cat = vec![CategoricalObservation {
        patient: 3,
        value: "yes".into(),
        timestamp: 5,
    }];
    let mut writer = BlocksWriter::new(std::fs::File::create(&path).unwrap(), store()).unwrap();
    assert!(writer.is_empty());
    let mut a = meta("\\a\\", ConceptType::Numeric, 100);
    a.block = writer.append(&codec, "\\a\\", &sample_numeric()).unwrap();
    let mut b = categorical_meta(&cat);
    b.path = "\\b\\".into();
    b.block = writer
        .append(&codec, "\\b\\", &encode_categorical_payload(&cat).unwrap())
        .unwrap();
    assert_eq!(a.block.offset, BLOCKS_HEADER_LEN);
    assert_eq!(b.block.offset, a.block.offset + a.block.length);
    writer.finish().unwrap();

    assert!(matches!(
        BlocksReader::open(&path, StoreId::from_bytes([1; 16])),
        Err(FormatError::StoreMismatch)
    ));
    let reader = BlocksReader::open(&path, store()).unwrap();
    assert_eq!(reader.store_id(), store());
    check_numeric(&sample_input(), &reader.read_block(&codec, &a).unwrap());
    check_categorical(&cat, &reader.read_block(&codec, &b).unwrap());
    let mut swapped = a.clone();
    swapped.block = b.block;
    assert!(matches!(
        reader.read_block(&codec, &swapped),
        Err(FormatError::Authentication)
    ));
    let other_store = BlockCodec::new(encrypted(), StoreId::from_bytes([1; 16]), true);
    assert!(matches!(
        reader.read_block(&other_store, &a),
        Err(FormatError::StoreMismatch)
    ));
    let past_end = BlockLocation {
        offset: b.block.offset,
        length: b.block.length + 1,
    };
    assert!(matches!(
        reader.read_stored(past_end),
        Err(FormatError::Corrupt { .. })
    ));
    let overflow = BlockLocation {
        offset: u64::MAX,
        length: 2,
    };
    assert!(reader.read_stored(overflow).is_err());
    let in_header = BlockLocation {
        offset: 8,
        length: 4,
    };
    assert!(reader.read_stored(in_header).is_err());
    std::fs::write(dir.join("bad"), b"NOTBLOCKS").unwrap();
    assert!(matches!(
        BlocksReader::open(dir.join("bad"), store()),
        Err(FormatError::BadMagic { .. })
    ));
    std::fs::remove_dir_all(&dir).unwrap();
}

#[test]
fn typed_views_are_aligned_and_zero_copy() {
    let b = open_numeric(&sample_input());
    assert_eq!(b.values().as_ptr() as usize % 8, 0);
    assert_eq!(b.values().len(), 100);
    assert_eq!(b.patients().len(), 100);
    assert_eq!(b.by_key().len(), 100);
    assert!(b.timestamps().is_none());
    assert_eq!(b.all_patients().len(), 100);
}

#[test]
fn heap_size_covers_payload_and_bitmaps() {
    let payload_len = sample_numeric().len();
    let numeric = Block::Numeric(open_numeric(&sample_input()));
    assert!(numeric.heap_size() >= payload_len + numeric.all_patients().serialized_size());

    let cat: Vec<CategoricalObservation> = (0..1000)
        .map(|i| CategoricalObservation {
            patient: i,
            value: format!("v{}", i % 10),
            timestamp: NO_TIMESTAMP,
        })
        .collect();
    let payload = encode_categorical_payload(&cat).unwrap();
    let codec = BlockCodec::new(Protection::Plain, store(), false);
    let block = codec
        .open("\\c\\", &codec.seal("\\c\\", &payload).unwrap(), u64::MAX)
        .unwrap();
    let Block::Categorical(c) = &block else {
        panic!("expected categorical block")
    };
    let bitmaps: usize = c.bitmaps().iter().map(|b| b.serialized_size()).sum();
    assert!(block.heap_size() >= payload.len() + bitmaps);
}
