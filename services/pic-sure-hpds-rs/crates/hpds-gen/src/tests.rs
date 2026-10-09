use std::collections::HashSet;
use std::hash::{DefaultHasher, Hasher};

use crate::edge::{write_edge_cases, write_negative_ids};
use crate::generate::{Shape, generate};

fn render(shape: Shape, seed: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    generate(shape, seed, &mut bytes).unwrap();
    bytes
}

fn digest(bytes: &[u8]) -> u64 {
    let mut hasher = DefaultHasher::new();
    hasher.write(bytes);
    hasher.finish()
}

#[test]
fn same_seed_is_byte_identical_and_other_seeds_differ() {
    let first = render(Shape::TINY, 1);
    let second = render(Shape::TINY, 1);
    let other = render(Shape::TINY, 2);
    assert_eq!(digest(&first), digest(&second));
    assert_eq!(first, second);
    assert_ne!(digest(&first), digest(&other));
}

#[test]
fn header_and_special_concepts_cover_every_patient() {
    let bytes = render(Shape::TINY, 7);
    let mut reader = csv::Reader::from_reader(bytes.as_slice());
    assert_eq!(
        reader.headers().unwrap().iter().collect::<Vec<_>>(),
        [
            "PATIENT_NUM",
            "CONCEPT_PATH",
            "NVAL_NUM",
            "TVAL_CHAR",
            "TIMESTAMP"
        ]
    );
    let mut consents = HashSet::new();
    let mut studies_consents = HashSet::new();
    let mut consent_rows = 0;
    for record in reader.records() {
        let record = record.unwrap();
        let patient: u64 = record[0].parse().unwrap();
        assert!((1..=1000).contains(&patient));
        match &record[1] {
            "\\_consents\\" => {
                consents.insert(patient);
                consent_rows += 1;
                assert!(record[3].starts_with("phs00000"));
            }
            "\\_studies_consents\\" => {
                studies_consents.insert(patient);
            }
            _ => {}
        }
    }
    assert_eq!(consents.len(), 1000);
    assert_eq!(consent_rows, 1000);
    assert_eq!(studies_consents.len(), 1000);
}

#[test]
fn observation_count_is_near_target() {
    let mut bytes = Vec::new();
    let rows = generate(Shape::TINY, 3, &mut bytes).unwrap();
    let target = Shape::TINY.observations as f64;
    assert!((rows as f64 - target).abs() / target < 0.25, "rows {rows}");
}

#[test]
fn edge_cases_are_deterministic_and_have_no_negative_ids() {
    let mut first = Vec::new();
    let mut second = Vec::new();
    write_edge_cases(&mut first).unwrap();
    write_edge_cases(&mut second).unwrap();
    assert_eq!(first, second);
    let mut reader = csv::ReaderBuilder::new()
        .flexible(true)
        .from_reader(first.as_slice());
    for record in reader.records() {
        assert!(!record.unwrap()[0].starts_with('-'));
    }
    let mut negative = Vec::new();
    write_negative_ids(&mut negative).unwrap();
    assert!(String::from_utf8(negative).unwrap().contains("\n-5,"));
}
