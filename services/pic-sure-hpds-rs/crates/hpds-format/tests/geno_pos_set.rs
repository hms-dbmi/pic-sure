//! Position sets: round trips against a reference set, the choice of form, and every way an
//! encoded set can be corrupt.

use std::collections::BTreeSet;

use hpds_format::{FormatError, PosBitset, PosSet, encode_pos_set};
use proptest::prelude::*;
use rand_chacha::ChaCha8Rng;
use rand_chacha::rand_core::{Rng, SeedableRng};

fn encode(positions: &[u32], p: u32) -> Vec<u8> {
    let mut out = Vec::new();
    encode_pos_set(positions, p, &mut out).unwrap();
    out
}

fn varint_len(v: u32) -> usize {
    let mut len = 1;
    let mut v = v >> 7;
    while v > 0 {
        len += 1;
        v >>= 7;
    }
    len
}

fn sparse_len(positions: &[u32]) -> usize {
    let mut len = 1 + varint_len(positions.len() as u32);
    let mut previous = 0;
    for (i, &p) in positions.iter().enumerate() {
        len += varint_len(if i == 0 { p } else { p - previous });
        previous = p;
    }
    len
}

/// Checks one set against its reference through every operation.
fn check(reference: &BTreeSet<u32>, p: u32, other: &BTreeSet<u32>) {
    let positions: Vec<u32> = reference.iter().copied().collect();
    let bytes = encode(&positions, p);
    let dense_len = 1 + (p as usize).div_ceil(8);
    let sparse = sparse_len(&positions);
    assert_eq!(bytes.len(), sparse.min(dense_len), "the shorter form");
    assert_eq!(bytes[0] == 0, sparse <= dense_len, "sparse on a tie");

    let set = PosSet::parse(&bytes, p).unwrap();
    assert_eq!(set.len(), reference.len());
    assert_eq!(set.is_empty(), reference.is_empty());
    assert_eq!(set.iter().collect::<Vec<_>>(), positions);

    let mut bitset = PosBitset::new(p);
    bitset.insert(p.saturating_sub(1));
    set.decode_into(&mut bitset);
    assert_eq!(bitset.iter().collect::<Vec<_>>(), positions);
    assert_eq!(bitset.count(), reference.len());

    let mut union = PosBitset::new(p);
    for &o in other {
        union.insert(o);
    }
    let mut other_bits = union.clone();
    set.union_into(&mut union);
    let expected: Vec<u32> = reference.union(other).copied().collect();
    assert_eq!(union.iter().collect::<Vec<_>>(), expected);

    assert_eq!(
        set.intersects(&other_bits),
        reference.intersection(other).next().is_some()
    );
    other_bits.intersect_with(&bitset);
    let expected: Vec<u32> = reference.intersection(other).copied().collect();
    assert_eq!(other_bits.iter().collect::<Vec<_>>(), expected);
}

fn random_set(rng: &mut ChaCha8Rng, p: u32) -> BTreeSet<u32> {
    let density = rng.next_u32() % 101;
    (0..p).filter(|_| rng.next_u32() % 100 < density).collect()
}

#[test]
fn every_patient_count_up_to_200_round_trips() {
    let mut rng = ChaCha8Rng::seed_from_u64(7);
    for p in 1..=200u32 {
        for _ in 0..20 {
            let set = random_set(&mut rng, p);
            let other = random_set(&mut rng, p);
            check(&set, p, &other);
        }
        check(&BTreeSet::new(), p, &BTreeSet::new());
        check(&(0..p).collect(), p, &BTreeSet::from([0]));
    }
}

#[test]
fn an_empty_group_holds_only_the_empty_set() {
    let bytes = encode(&[], 0);
    let set = PosSet::parse(&bytes, 0).unwrap();
    assert!(set.is_empty());
    let mut bits = PosBitset::new(0);
    set.decode_into(&mut bits);
    assert!(bits.is_empty());
    assert!(encode_pos_set(&[0], 0, &mut Vec::new()).is_err());
}

#[test]
fn dense_and_sparse_tie_goes_to_sparse() {
    let p = 64;
    let dense = 1 + 8;
    let positions = [0u32, 1, 2, 3, 4, 5, 6];
    assert_eq!(sparse_len(&positions), dense);
    let bytes = encode(&positions, p);
    assert_eq!(bytes[0], 0);
    let longer = [0u32, 1, 2, 3, 4, 5, 6, 7];
    assert_eq!(encode(&longer, p)[0], 1);
}

#[test]
fn encoder_refuses_unordered_or_out_of_range_positions() {
    for bad in [&[2u32, 1][..], &[1, 1], &[10]] {
        assert!(matches!(
            encode_pos_set(bad, 10, &mut Vec::new()),
            Err(FormatError::InvalidInput(_))
        ));
    }
}

fn refused(bytes: &[u8], p: u32) -> FormatError {
    PosSet::parse(bytes, p).expect_err("refused")
}

#[test]
fn every_corrupt_set_is_refused() {
    assert!(matches!(refused(&[], 8), FormatError::Truncated(_)));
    assert!(matches!(refused(&[2], 8), FormatError::Corrupt { .. }));
    assert!(matches!(refused(&[0], 8), FormatError::Truncated(_)));
    assert!(matches!(
        refused(&[0, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01], 8),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 1, 0x80, 0x80, 0x80, 0x80, 0x10], u32::MAX),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 1, 0x80], 8),
        FormatError::Truncated(_)
    ));
    assert!(matches!(refused(&[0, 3, 1], 8), FormatError::Truncated(_)));
    assert!(matches!(
        refused(&[0, 1, 8], 8),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 2, 1, 0], 8),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 2, 1, 0xff, 0xff, 0xff, 0xff, 0x0f], u32::MAX),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 1, 1, 0], 8),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[0, 0, 0], 8),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(refused(&[1], 9), FormatError::Truncated(_)));
    assert!(matches!(refused(&[1, 0], 9), FormatError::Truncated(_)));
    assert!(matches!(
        refused(&[1, 0, 0, 0], 9),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[1, 0, 2], 9),
        FormatError::Corrupt { .. }
    ));
    assert!(matches!(
        refused(&[1, 0x80], 7),
        FormatError::Corrupt { .. }
    ));
    assert!(PosSet::parse(&[1, 0x7f], 7).is_ok());
    assert!(PosSet::parse(&[1, 0, 1], 9).is_ok());
}

proptest! {
    #![proptest_config(ProptestConfig::with_cases(48))]

    #[test]
    fn large_groups_round_trip(
        p in 200u32..200_000,
        seed in any::<u64>(),
    ) {
        let mut rng = ChaCha8Rng::seed_from_u64(seed);
        let density = rng.next_u32() % 1000;
        let set: BTreeSet<u32> = (0..p).filter(|_| rng.next_u32() % 100_000 < density).collect();
        let other: BTreeSet<u32> = (0..p).filter(|_| rng.next_u32() % 1000 == 0).collect();
        check(&set, p, &other);
    }

    #[test]
    fn arbitrary_bytes_never_panic(bytes in proptest::collection::vec(any::<u8>(), 0..64), p in 0u32..300) {
        if let Ok(set) = PosSet::parse(&bytes, p) {
            let positions: Vec<u32> = set.iter().collect();
            prop_assert_eq!(positions.len(), set.len());
            prop_assert!(positions.windows(2).all(|w| w[0] < w[1]));
            prop_assert!(positions.iter().all(|&q| q < p));
            let mut again = Vec::new();
            encode_pos_set(&positions, p, &mut again).unwrap();
            let reparsed: Vec<u32> = PosSet::parse(&again, p).unwrap().iter().collect();
            prop_assert_eq!(reparsed, positions);
        }
    }
}
