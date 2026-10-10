use std::time::Instant;

use hpds_format::{
    Block, BlockCodec, BlockLocation, ConceptMeta, ConceptType, CryptoKey, NumericObservation,
    Protection, StoreId, encode_numeric_payload,
};

/// Encodes, seals, opens and decodes a 10M-observation numeric block and prints the
/// timings. Run with `cargo test --release -p hpds-format --test throughput -- --ignored
/// --nocapture`.
#[test]
#[ignore = "throughput sanity check; run in release mode"]
fn ten_million_observation_numeric_block() {
    const N: u32 = 10_000_000;
    let mut state = 0x9E37_79B9_7F4A_7C15u64;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };
    let source: Vec<NumericObservation> = (0..N)
        .map(|i| NumericObservation {
            patient: i / 4,
            value: f64::from((next() % 100_000) as u32) / 100.0,
            timestamp: 1_500_000_000_000 + (next() % 1_000_000_000) as i64,
        })
        .collect();
    let key = CryptoKey::from_bytes(b"0123456789abcdef0123456789abcdef").unwrap();
    let store = StoreId::random().unwrap();
    let meta = ConceptMeta {
        path: "\\bench\\".into(),
        concept_type: ConceptType::Numeric,
        category_values: None,
        min: None,
        max: None,
        observation_count: N,
        patient_count: N / 4,
        has_timestamp: true,
        timestamp_min: None,
        timestamp_max: None,
        width_in_bytes: 0,
        column_offset: 0,
        block: BlockLocation::default(),
        holders: Vec::new(),
        tagged: false,
    };
    for (label, codec) in [
        (
            "plain+zstd",
            BlockCodec::new(Protection::Plain, store, true),
        ),
        (
            "aes256+zstd",
            BlockCodec::new(Protection::Encrypted(key.clone()), store, true),
        ),
        (
            "aes256 raw",
            BlockCodec::new(Protection::Encrypted(key), store, false),
        ),
    ] {
        let t = Instant::now();
        let payload = encode_numeric_payload(&source).unwrap();
        let encode = t.elapsed();
        let t = Instant::now();
        let stored = codec.seal("\\bench\\", &payload).unwrap();
        let seal = t.elapsed();
        let t = Instant::now();
        let block = codec
            .open("\\bench\\", &stored, meta.max_payload_len())
            .unwrap();
        let open = t.elapsed();
        let Block::Numeric(b) = block else { panic!() };
        assert_eq!(b.len(), N as usize);
        let mb = payload.len() as f64 / 1e6;
        println!(
            "{label}: payload {mb:.0} MB, stored {:.0} MB; encode+sort {encode:?}, seal {seal:?} ({:.0} MB/s), open+validate {open:?} ({:.0} MB/s, {:.1} M obs/s)",
            stored.len() as f64 / 1e6,
            mb / seal.as_secs_f64(),
            mb / open.as_secs_f64(),
            f64::from(N) / 1e6 / open.as_secs_f64(),
        );
    }
}
