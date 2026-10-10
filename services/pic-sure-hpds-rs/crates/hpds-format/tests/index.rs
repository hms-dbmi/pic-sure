use std::cmp::Ordering;

use hpds_format::{
    BlockLocation, ConceptMeta, ConceptType, CryptoKey, FormatError, IndexBody, IndexHeader,
    PhenoIndex, Protection, StoreId, java_string_cmp,
};
use proptest::prelude::*;
use roaring::RoaringBitmap;
use xxhash_rust::xxh3::xxh3_64;

fn key() -> CryptoKey {
    CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap()
}

fn protection(encrypted: bool) -> Protection {
    if encrypted {
        Protection::Encrypted(key())
    } else {
        Protection::Plain
    }
}

const STORE: [u8; 16] = *b"store-id-for-tst";

const PREAMBLE_FIXED: usize = 8 + 2 + 2 + 16;

fn header(encrypted: bool, compressed: bool) -> IndexHeader {
    IndexHeader {
        format_version: 0,
        encrypted,
        compressed,
        key_name: if encrypted {
            "DEFAULT".into()
        } else {
            String::new()
        },
        created_at_millis: 1_760_000_000_000,
        etl_version: "hpds-etl 0.1.0".into(),
        store_id: StoreId::from_bytes(STORE),
    }
}

fn meta(path: &str) -> ConceptMeta {
    ConceptMeta {
        path: path.into(),
        concept_type: ConceptType::Numeric,
        category_values: None,
        min: Some(1.0),
        max: Some(2.0),
        observation_count: 3,
        patient_count: 2,
        has_timestamp: false,
        timestamp_min: None,
        timestamp_max: None,
        width_in_bytes: 8,
        column_offset: 0,
        block: BlockLocation {
            offset: 8,
            length: 10,
        },
    }
}

fn concept() -> impl Strategy<Value = ConceptMeta> {
    (
        "\\\\[a-c\u{e000}\u{1f600}]{0,3}\\\\[a-z]{0,3}",
        any::<bool>(),
        proptest::option::of(proptest::collection::vec("\\PC{0,5}", 0..4)),
        proptest::option::of(any::<f64>()),
        proptest::option::of(any::<f64>()),
        any::<(u32, u32, bool)>(),
        proptest::option::of(any::<i64>()),
        proptest::option::of(any::<i64>()),
        any::<(i32, i32, u64, u64)>(),
    )
        .prop_map(
            |(path, categorical, values, min, max, counts, ts_min, ts_max, layout)| ConceptMeta {
                path,
                concept_type: if categorical {
                    ConceptType::Categorical
                } else {
                    ConceptType::Numeric
                },
                category_values: values,
                min,
                max,
                observation_count: counts.0,
                patient_count: counts.1,
                has_timestamp: counts.2,
                timestamp_min: ts_min,
                timestamp_max: ts_max,
                width_in_bytes: layout.0,
                column_offset: layout.1,
                block: BlockLocation {
                    offset: layout.2,
                    length: layout.3,
                },
            },
        )
}

fn concepts() -> impl Strategy<Value = Vec<ConceptMeta>> {
    proptest::collection::vec(concept(), 0..30).prop_map(|mut v| {
        v.sort_by(|a, b| a.path.cmp(&b.path));
        v.dedup_by(|a, b| a.path == b.path);
        v
    })
}

fn same_meta(a: &ConceptMeta, b: &ConceptMeta) -> bool {
    let bits = |v: Option<f64>| v.map(f64::to_bits);
    a.path == b.path
        && a.concept_type == b.concept_type
        && a.category_values == b.category_values
        && bits(a.min) == bits(b.min)
        && bits(a.max) == bits(b.max)
        && a.observation_count == b.observation_count
        && a.patient_count == b.patient_count
        && a.has_timestamp == b.has_timestamp
        && a.timestamp_min == b.timestamp_min
        && a.timestamp_max == b.timestamp_max
        && a.width_in_bytes == b.width_in_bytes
        && a.column_offset == b.column_offset
        && a.block == b.block
}

proptest! {
    #[test]
    fn index_round_trips(
        list in concepts(),
        patients in proptest::collection::vec(any::<u32>(), 0..300),
        encrypted in any::<bool>(),
        compressed in any::<bool>(),
    ) {
        let patients: RoaringBitmap = patients.into_iter().collect();
        let index = PhenoIndex::new(header(encrypted, compressed), patients.clone(), list.clone()).unwrap();
        let protection = protection(encrypted);
        let bytes = index.encode(&protection).unwrap();
        let decoded = PhenoIndex::decode(&bytes, &protection).unwrap();
        prop_assert_eq!(decoded.header(), index.header());
        prop_assert_eq!(decoded.header().format_version, hpds_format::FORMAT_VERSION);
        prop_assert_eq!(decoded.patients(), &patients);
        prop_assert_eq!(decoded.concepts().len(), list.len());
        for w in decoded.concepts().windows(2) {
            prop_assert_eq!(java_string_cmp(&w[0].path, &w[1].path), Ordering::Less);
        }
        for c in &list {
            let found = decoded.get(&c.path).unwrap();
            prop_assert!(same_meta(found, c));
        }
        prop_assert!(decoded.get("\\missing\\").is_none());
        for c in &list {
            let prefix: String = c.path.chars().take(2).collect();
            let expected: Vec<&str> = list.iter().filter(|x| x.path.starts_with(&prefix)).map(|x| x.path.as_str()).collect();
            let mut actual: Vec<&str> = decoded.with_prefix(&prefix).iter().map(|x| x.path.as_str()).collect();
            let mut expected = expected;
            expected.sort();
            actual.sort();
            prop_assert_eq!(actual, expected);
        }
    }

    #[test]
    fn the_index_body_views_the_same_concepts(
        list in concepts(),
        patients in proptest::collection::vec(any::<u32>(), 0..300),
        encrypted in any::<bool>(),
        compressed in any::<bool>(),
    ) {
        let patients: RoaringBitmap = patients.into_iter().collect();
        let index = PhenoIndex::new(header(encrypted, compressed), patients.clone(), list).unwrap();
        let protection = protection(encrypted);
        let bytes = index.encode(&protection).unwrap();
        let body = IndexBody::decode(&bytes, &protection).unwrap();
        prop_assert_eq!(body.header(), index.header());
        prop_assert_eq!(body.patients(), &patients);
        prop_assert_eq!(body.concept_count(), index.concepts().len());
        let views = body.concepts().collect::<Result<Vec<_>, _>>().unwrap();
        prop_assert_eq!(views.len(), index.concepts().len());
        for (view, meta) in views.iter().zip(index.concepts()) {
            prop_assert!(same_meta(&view.to_meta(), meta));
            prop_assert_eq!(view.max_payload_len(), meta.max_payload_len());
            prop_assert_eq!(view.is_categorical(), meta.is_categorical());
        }
    }

    #[test]
    fn a_damaged_body_fails_its_concept_views_as_it_fails_decode(
        list in concepts(),
        flip in any::<prop::sample::Index>(),
        mask in 1u8..,
        cut in any::<prop::sample::Index>(),
    ) {
        let index = PhenoIndex::new(header(false, false), RoaringBitmap::from_iter([5u32]), list).unwrap();
        let bytes = index.encode(&Protection::Plain).unwrap();
        let body_end = bytes.len() - 8;
        let preamble_len = PREAMBLE_FIXED + 4 + 8 + 4 + "hpds-etl 0.1.0".len();
        let body_start = preamble_len + 8;
        let mut body = bytes[body_start..body_end].to_vec();
        let i = flip.index(body.len());
        body[i] ^= mask;
        body.truncate(cut.index(body.len() + 1));
        let mut rebuilt = bytes[..preamble_len].to_vec();
        rebuilt.extend_from_slice(&(body.len() as u64).to_le_bytes());
        rebuilt.extend_from_slice(&body);
        let checksum = xxh3_64(&rebuilt);
        rebuilt.extend_from_slice(&checksum.to_le_bytes());
        let decoded = PhenoIndex::decode(&rebuilt, &Protection::Plain);
        let viewed = IndexBody::decode(&rebuilt, &Protection::Plain)
            .and_then(|body| body.concepts().map(|c| c.map(|c| c.to_meta())).collect::<Result<Vec<_>, _>>());
        prop_assert_eq!(decoded.is_ok(), viewed.is_ok());
        if let (Ok(decoded), Ok(viewed)) = (decoded, viewed) {
            prop_assert_eq!(decoded.concepts().len(), viewed.len());
        }
    }

    #[test]
    fn damaged_index_is_rejected_without_panic(
        list in concepts(),
        encrypted in any::<bool>(),
        compressed in any::<bool>(),
        flip in any::<prop::sample::Index>(),
        mask in 1u8..,
        cut in any::<prop::sample::Index>(),
    ) {
        let index = PhenoIndex::new(header(encrypted, compressed), RoaringBitmap::from_iter([1u32, 2]), list).unwrap();
        let protection = protection(encrypted);
        let bytes = index.encode(&protection).unwrap();
        let mut flipped = bytes.clone();
        let i = flip.index(flipped.len());
        flipped[i] ^= mask;
        prop_assert!(PhenoIndex::decode(&flipped, &protection).is_err());
        prop_assert!(PhenoIndex::decode(&bytes[..cut.index(bytes.len())], &protection).is_err());
    }

    #[test]
    fn structurally_damaged_body_never_panics(
        list in concepts(),
        flip in any::<prop::sample::Index>(),
        mask in 1u8..,
        cut in any::<prop::sample::Index>(),
    ) {
        let index = PhenoIndex::new(header(false, false), RoaringBitmap::from_iter([5u32]), list).unwrap();
        let bytes = index.encode(&Protection::Plain).unwrap();
        let body_end = bytes.len() - 8;
        let preamble_len = PREAMBLE_FIXED + 4 + 8 + 4 + "hpds-etl 0.1.0".len();
        let body_start = preamble_len + 8;
        let mut body = bytes[body_start..body_end].to_vec();
        let i = flip.index(body.len());
        body[i] ^= mask;
        body.truncate(cut.index(body.len() + 1));
        let mut rebuilt = bytes[..preamble_len].to_vec();
        rebuilt.extend_from_slice(&(body.len() as u64).to_le_bytes());
        rebuilt.extend_from_slice(&body);
        let checksum = xxh3_64(&rebuilt);
        rebuilt.extend_from_slice(&checksum.to_le_bytes());
        let _ = PhenoIndex::decode(&rebuilt, &Protection::Plain);
    }
}

#[test]
fn lookup_and_prefix_follow_java_string_order() {
    let names = [
        "\\b\\",
        "\\a\\x\\",
        "\\a\\",
        "\\a\u{1f600}\\",
        "\\a\u{e000}\\",
        "\\ab\\",
        "\\c\\",
    ];
    let index = PhenoIndex::new(
        header(false, true),
        RoaringBitmap::new(),
        names.iter().map(|n| meta(n)).collect(),
    )
    .unwrap();
    let sorted: Vec<&str> = index.concepts().iter().map(|c| c.path.as_str()).collect();
    assert_eq!(
        sorted,
        [
            "\\a\\",
            "\\a\\x\\",
            "\\ab\\",
            "\\a\u{1f600}\\",
            "\\a\u{e000}\\",
            "\\b\\",
            "\\c\\"
        ]
    );
    assert_eq!(index.position("\\a\u{e000}\\"), Some(4));
    let under_a: Vec<&str> = index
        .with_prefix("\\a")
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    assert_eq!(
        under_a,
        [
            "\\a\\",
            "\\a\\x\\",
            "\\ab\\",
            "\\a\u{1f600}\\",
            "\\a\u{e000}\\"
        ]
    );
    let exact: Vec<&str> = index
        .with_prefix("\\a\\")
        .iter()
        .map(|c| c.path.as_str())
        .collect();
    assert_eq!(exact, ["\\a\\", "\\a\\x\\"]);
    assert_eq!(index.with_prefix("").len(), 7);
    assert!(index.with_prefix("\\z").is_empty());
    assert!(index.with_prefix("\\a\\x\\y").is_empty());
}

#[test]
fn duplicate_paths_are_rejected() {
    assert!(matches!(
        PhenoIndex::new(
            header(false, false),
            RoaringBitmap::new(),
            vec![meta("\\a\\"), meta("\\a\\")]
        ),
        Err(FormatError::InvalidInput(_))
    ));
}

#[test]
fn protection_must_match_header() {
    let plain = PhenoIndex::new(
        header(false, true),
        RoaringBitmap::new(),
        vec![meta("\\a\\")],
    )
    .unwrap();
    let encrypted = PhenoIndex::new(
        header(true, true),
        RoaringBitmap::new(),
        vec![meta("\\a\\")],
    )
    .unwrap();
    let with_key = Protection::Encrypted(key());
    assert!(matches!(
        plain.encode(&with_key),
        Err(FormatError::UnexpectedKey(_))
    ));
    assert!(matches!(
        encrypted.encode(&Protection::Plain),
        Err(FormatError::MissingKey(_))
    ));
    let bytes = encrypted.encode(&with_key).unwrap();
    assert!(matches!(
        PhenoIndex::decode(&bytes, &Protection::Plain),
        Err(FormatError::MissingKey(_))
    ));
    let plain_bytes = plain.encode(&Protection::Plain).unwrap();
    assert!(matches!(
        PhenoIndex::decode(&plain_bytes, &with_key),
        Err(FormatError::UnexpectedKey(_))
    ));
    let wrong = Protection::Encrypted(CryptoKey::from_bytes(&[3u8; 32]).unwrap());
    assert!(matches!(
        PhenoIndex::decode(&bytes, &wrong),
        Err(FormatError::Authentication)
    ));
    let header_only = PhenoIndex::decode_header(&bytes).unwrap();
    assert_eq!(header_only.key_name, "DEFAULT");
    assert!(header_only.encrypted);
    assert_eq!(header_only.store_id, StoreId::from_bytes(STORE));

    let decoded = PhenoIndex::decode(&bytes, &with_key).unwrap();
    assert_eq!(decoded.store_id(), StoreId::from_bytes(STORE));
    assert!(matches!(
        decoded.block_codec(Protection::Plain),
        Err(FormatError::MissingKey(_))
    ));
    let codec = decoded.block_codec(with_key.clone()).unwrap();
    assert!(codec.is_encrypted());
    assert_eq!(codec.store_id(), StoreId::from_bytes(STORE));
    assert!(matches!(
        plain.block_codec(with_key),
        Err(FormatError::UnexpectedKey(_))
    ));
    assert!(!plain.block_codec(Protection::Plain).unwrap().is_encrypted());
}

#[test]
fn forged_plain_index_is_refused_when_a_key_is_supplied() {
    let index = PhenoIndex::new(
        header(true, false),
        RoaringBitmap::from_iter([7u32]),
        vec![meta("\\a\\")],
    )
    .unwrap();
    let with_key = Protection::Encrypted(key());
    let genuine = index.encode(&with_key).unwrap();

    let mut forged_header = index.header().clone();
    forged_header.encrypted = false;
    let forged_index = PhenoIndex::new(
        forged_header,
        RoaringBitmap::from_iter([7u32, 8, 9]),
        vec![meta("\\a\\"), meta("\\injected\\")],
    )
    .unwrap();
    let forged = forged_index.encode(&Protection::Plain).unwrap();
    assert!(PhenoIndex::decode(&forged, &Protection::Plain).is_ok());
    assert!(matches!(
        PhenoIndex::decode(&forged, &with_key),
        Err(FormatError::UnexpectedKey(_))
    ));

    let mut flag_cleared = genuine.clone();
    flag_cleared[10] &= !1;
    let checksum = xxh3_64(&flag_cleared);
    flag_cleared.extend_from_slice(&checksum.to_le_bytes());
    assert!(matches!(
        PhenoIndex::decode(&flag_cleared, &with_key),
        Err(FormatError::UnexpectedKey(_))
    ));
    assert!(PhenoIndex::decode(&genuine, &with_key).is_ok());
}

#[test]
fn encrypted_header_is_authenticated() {
    let index = PhenoIndex::new(
        header(true, true),
        RoaringBitmap::new(),
        vec![meta("\\a\\")],
    )
    .unwrap();
    let with_key = Protection::Encrypted(key());
    let genuine = index.encode(&with_key).unwrap();
    let created = PREAMBLE_FIXED + 4 + "DEFAULT".len();
    let mut bytes = genuine.clone();
    bytes[created] ^= 1;
    assert!(matches!(
        PhenoIndex::decode(&bytes, &with_key),
        Err(FormatError::Authentication)
    ));
    let mut bytes = genuine;
    bytes[PREAMBLE_FIXED - 1] ^= 1;
    assert!(matches!(
        PhenoIndex::decode(&bytes, &with_key),
        Err(FormatError::Authentication)
    ));
}

#[test]
fn bad_magic_and_version_are_reported() {
    let index = PhenoIndex::new(header(false, false), RoaringBitmap::new(), vec![]).unwrap();
    let mut bytes = index.encode(&Protection::Plain).unwrap();
    bytes[0] = b'X';
    assert!(matches!(
        PhenoIndex::decode(&bytes, &Protection::Plain),
        Err(FormatError::BadMagic { .. })
    ));
    let mut bytes = index.encode(&Protection::Plain).unwrap();
    bytes[8] = 9;
    assert!(matches!(
        PhenoIndex::decode(&bytes, &Protection::Plain),
        Err(FormatError::UnsupportedVersion(9))
    ));
}

#[test]
fn index_file_round_trip() {
    let dir = std::env::temp_dir().join(format!("hpds-format-index-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(hpds_format::INDEX_FILE_NAME);
    let mut cat = meta("\\sex\\");
    cat.concept_type = ConceptType::Categorical;
    cat.category_values = Some(vec!["F".into(), "M".into()]);
    cat.min = None;
    cat.max = None;
    let index = PhenoIndex::new(
        header(true, true),
        RoaringBitmap::from_iter(0u32..1000),
        vec![cat, meta("\\age\\")],
    )
    .unwrap();
    let with_key = Protection::Encrypted(key());
    index.write_file(&path, &with_key).unwrap();
    let read = PhenoIndex::read_file(&path, &with_key).unwrap();
    assert_eq!(read, index);
    assert!(read.get("\\sex\\").unwrap().is_categorical());
    std::fs::remove_dir_all(&dir).unwrap();
}
