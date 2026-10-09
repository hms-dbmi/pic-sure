use std::time::Instant;

use hpds_format::{Block, BlockCodec, CryptoKey, NumericObservation, encode_numeric_payload};

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
    for (label, codec) in [
        ("plain+zstd", BlockCodec::new(None, true)),
        ("aes256+zstd", BlockCodec::new(Some(key.clone()), true)),
        ("aes256 raw", BlockCodec::new(Some(key), false)),
    ] {
        let mut obs = source.clone();
        let t = Instant::now();
        let payload = encode_numeric_payload(&mut obs).unwrap();
        let encode = t.elapsed();
        let t = Instant::now();
        let stored = codec.seal("\\bench\\", &payload).unwrap();
        let seal = t.elapsed();
        let t = Instant::now();
        let block = codec.open("\\bench\\", &stored).unwrap();
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
