use std::collections::{BTreeMap, HashMap, HashSet};

use crate::bdc::{
    self, BdcRule, EDGE_PATIENTS, HARMONIZED_CONSENT_PATH, HARMONIZED_DATASET_ROOT,
    PARENT_CONSENTS_PATH, Profile, TOPMED_CONSENTS_PATH, UNCONSENTED_PATH,
};
use crate::generate::{Order, Shape, concept_table_profile, generate, generate_profile};
use crate::model::{CONSENTS_PATH, Kind};

const EDGE: Profile = Profile::Bdc { edge: true };
const REGULAR: Profile = Profile::Bdc { edge: false };

fn render(shape: Shape, profile: Profile, seed: u64, order: Order) -> Vec<u8> {
    let mut bytes = Vec::new();
    generate_profile(shape, profile, seed, order, &mut bytes).unwrap();
    bytes
}

type Rows = BTreeMap<u64, Vec<(String, String, String)>>;

fn by_patient(bytes: &[u8]) -> Rows {
    let mut rows = Rows::new();
    for record in csv::Reader::from_reader(bytes).records() {
        let record = record.unwrap();
        rows.entry(record[0].parse().unwrap()).or_default().push((
            record[1].to_string(),
            record[2].to_string(),
            record[3].to_string(),
        ));
    }
    rows
}

fn texts<'a>(rows: &'a [(String, String, String)], path: &str) -> Vec<&'a str> {
    rows.iter()
        .filter(|(p, _, _)| p == path)
        .map(|(_, _, text)| text.as_str())
        .collect()
}

fn group_of(consent: &str) -> (u32, u32) {
    let study = consent[3..9].parse::<u32>().unwrap() - 1;
    let group = consent[11..].parse::<u32>().unwrap() - 1;
    (study, group)
}

fn generated(
    rows: &Rows,
    first_extra: u32,
) -> impl Iterator<Item = (&u64, &Vec<(String, String, String)>)> {
    rows.iter()
        .filter(move |(id, _)| **id < u64::from(first_extra))
}

#[test]
fn same_seed_is_byte_identical_and_other_seeds_differ() {
    for (profile, order) in [
        (EDGE, Order::Patient),
        (EDGE, Order::Concept),
        (REGULAR, Order::Patient),
    ] {
        let first = render(Shape::BDCX, profile, 3, order);
        assert_eq!(first, render(Shape::BDCX, profile, 3, order));
        assert_ne!(first, render(Shape::BDCX, profile, 4, order));
    }
}

#[test]
fn both_row_orders_hold_the_same_rows_and_concepts_stay_contiguous() {
    let mut patient: Vec<String> = String::from_utf8(render(Shape::BDCX, EDGE, 5, Order::Patient))
        .unwrap()
        .lines()
        .map(String::from)
        .collect();
    let concept_bytes = render(Shape::BDCX, EDGE, 5, Order::Concept);
    let mut concept: Vec<String> = String::from_utf8(concept_bytes.clone())
        .unwrap()
        .lines()
        .map(String::from)
        .collect();
    patient.sort();
    concept.sort();
    assert_eq!(patient, concept);
    let mut finished = HashSet::new();
    let mut current = String::new();
    for record in csv::Reader::from_reader(concept_bytes.as_slice()).records() {
        let path = record.unwrap()[1].to_string();
        if path != current {
            assert!(finished.insert(current.clone()), "concept {current} split");
            current = path;
        }
    }
}

#[test]
fn plain_rows_are_the_plain_profile_rows() {
    let plain = String::from_utf8(render(Shape::BDCX, Profile::Plain, 6, Order::Concept)).unwrap();
    let bdc = String::from_utf8(render(Shape::BDCX, EDGE, 6, Order::Concept)).unwrap();
    let bdc_paths: Vec<String> = concept_table_profile(&Shape::BDCX, EDGE, 6)
        .into_iter()
        .filter(|c| matches!(c.kind, Kind::Bdc(_)))
        .map(|c| c.path)
        .collect();
    let mut plain_rows: Vec<&str> = plain.lines().collect();
    let first_extra = Shape::BDCX.first_extra_patient();
    let mut rest: Vec<&str> = bdc
        .lines()
        .filter(|line| {
            let mut fields = line.split(',');
            let patient = fields.next().unwrap();
            let path = fields.next().unwrap();
            !bdc_paths.iter().any(|p| p == path)
                && patient.parse::<u32>().is_ok_and(|id| id < first_extra)
                || patient == "PATIENT_NUM"
        })
        .collect();
    plain_rows.sort_unstable();
    rest.sort_unstable();
    assert!(plain_rows.len() > 100_000);
    assert_eq!(plain_rows.len(), rest.len());
    assert!(
        plain_rows == rest,
        "plain rows changed under the BDC profile"
    );
    let mut again = Vec::new();
    generate(Shape::BDCX, 6, Order::Concept, &mut again).unwrap();
    assert_eq!(plain.as_bytes(), again.as_slice());
}

#[test]
fn topmed_consents_cover_exactly_the_patients_of_topmed_groups_with_their_own_consent() {
    let first_extra = Shape::BDCX.first_extra_patient();
    let rows = by_patient(&render(Shape::BDCX, EDGE, 7, Order::Patient));
    let mut with = 0;
    let mut without = 0;
    for (_, patient) in generated(&rows, first_extra) {
        let consent = texts(patient, CONSENTS_PATH);
        assert_eq!(consent.len(), 1);
        let (study, group) = group_of(consent[0]);
        let topmed = texts(patient, TOPMED_CONSENTS_PATH);
        if bdc::is_topmed_group(study, group) {
            assert_eq!(topmed, consent);
            with += 1;
        } else {
            assert!(topmed.is_empty());
            without += 1;
        }
    }
    assert!(
        with > 500 && without > 500,
        "{with} with, {without} without"
    );
}

#[test]
fn harmonized_consent_marks_a_subset_whose_values_stay_in_their_study() {
    let first_extra = Shape::BDCX.first_extra_patient();
    let rows = by_patient(&render(Shape::BDCX, EDGE, 8, Order::Patient));
    let mut subset = 0usize;
    let mut sibling = 0usize;
    let mut total = 0usize;
    for (_, patient) in generated(&rows, first_extra) {
        total += 1;
        let harmonized = texts(patient, HARMONIZED_CONSENT_PATH);
        if harmonized.is_empty() {
            continue;
        }
        assert_eq!(harmonized.len(), 1);
        subset += 1;
        let own = group_of(texts(patient, CONSENTS_PATH)[0]);
        let held = group_of(harmonized[0]);
        assert_eq!(held.0, own.0);
        if held.1 != own.1 {
            sibling += 1;
        }
    }
    let share = subset as f64 / total as f64;
    assert!((0.30..0.40).contains(&share), "subset share {share}");
    let sibling_share = sibling as f64 / subset as f64;
    assert!(
        (0.05..0.16).contains(&sibling_share),
        "sibling share {sibling_share}"
    );
}

#[test]
fn harmonized_phenotype_concepts_belong_to_the_subset_only() {
    let first_extra = Shape::BDCX.first_extra_patient();
    let table = concept_table_profile(&Shape::BDCX, EDGE, 9);
    let harmonized: Vec<_> = table
        .iter()
        .filter(|c| c.path.starts_with(HARMONIZED_DATASET_ROOT))
        .collect();
    assert_eq!(harmonized.len(), bdc::harmonized_concepts().len());
    assert!(harmonized.len() >= 8);
    let rows = by_patient(&render(Shape::BDCX, EDGE, 9, Order::Patient));
    let mut numeric = 0;
    let mut categorical = 0;
    let mut seen = HashSet::new();
    for (_, patient) in generated(&rows, first_extra) {
        let in_subset = !texts(patient, HARMONIZED_CONSENT_PATH).is_empty();
        for (path, number, text) in patient {
            if path.starts_with(HARMONIZED_DATASET_ROOT) {
                assert!(in_subset, "{path} outside the subset");
                seen.insert(path.clone());
                if number.is_empty() {
                    assert!(!text.is_empty());
                    categorical += 1;
                } else {
                    assert!(text.is_empty());
                    number.parse::<f64>().unwrap();
                    numeric += 1;
                }
            }
        }
    }
    assert_eq!(seen.len(), harmonized.len());
    assert!(numeric > 1_000 && categorical > 1_000);
}

#[test]
fn parent_consents_exist_only_in_child_studies_and_name_the_parent() {
    let first_extra = Shape::BDCX.first_extra_patient();
    let rows = by_patient(&render(Shape::BDCX, EDGE, 10, Order::Patient));
    let mut children = 0;
    for (_, patient) in generated(&rows, first_extra) {
        let (study, group) = group_of(texts(patient, CONSENTS_PATH)[0]);
        let parent = texts(patient, PARENT_CONSENTS_PATH);
        match bdc::parent_study(study) {
            Some(expected) => {
                assert_eq!(parent.len(), 1);
                assert_eq!(group_of(parent[0]), (expected, group));
                children += 1;
            }
            None => assert!(parent.is_empty()),
        }
    }
    assert!(children > 100);
    assert_eq!(bdc::parent_study(3), Some(0));
    assert_eq!(bdc::parent_study(7), Some(4));
    assert_eq!(bdc::parent_study(2), None);
}

#[test]
fn regular_profile_has_no_hand_placed_patients() {
    let rows = by_patient(&render(Shape::BDCX, REGULAR, 11, Order::Patient));
    assert!(
        rows.keys()
            .all(|id| *id < u64::from(Shape::BDCX.first_extra_patient()))
    );
    let table = concept_table_profile(&Shape::BDCX, REGULAR, 11);
    assert!(table.iter().all(|c| c.path != UNCONSENTED_PATH));
}

#[test]
fn hand_placed_patients_cover_each_consent_edge() {
    let first = Shape::BDCX.first_extra_patient();
    let rows = by_patient(&render(Shape::BDCX, EDGE, 12, Order::Concept));
    let edge = |offset: u32| &rows[&u64::from(first + offset)];
    assert!(rows.keys().all(|id| *id < u64::from(first + EDGE_PATIENTS)));
    assert_eq!(
        texts(edge(0), CONSENTS_PATH),
        ["phs000001.c1", "phs000002.c2"]
    );
    assert_eq!(texts(edge(1), CONSENTS_PATH), ["phs000099.c1"]);
    let everyone_else: HashSet<&str> = generated(&rows, first)
        .flat_map(|(_, p)| texts(p, CONSENTS_PATH))
        .collect();
    assert!(!everyone_else.contains("phs000099.c1"));
    assert!(texts(edge(2), CONSENTS_PATH).is_empty());
    assert_eq!(texts(edge(2), TOPMED_CONSENTS_PATH), ["phs000001.c1"]);
    assert_ne!(
        texts(edge(3), CONSENTS_PATH),
        texts(edge(3), TOPMED_CONSENTS_PATH)
    );
    assert_eq!(texts(edge(4), HARMONIZED_CONSENT_PATH), ["phs000001.c1"]);
    assert!(
        edge(4)
            .iter()
            .all(|(p, _, _)| !p.starts_with(HARMONIZED_DATASET_ROOT))
    );
    assert!(texts(edge(5), HARMONIZED_CONSENT_PATH).is_empty());
    assert_eq!(
        edge(5)
            .iter()
            .filter(|(p, _, _)| p.starts_with(HARMONIZED_DATASET_ROOT))
            .count(),
        2
    );
    assert_eq!(texts(edge(6), PARENT_CONSENTS_PATH), ["phs000001.c1"]);
    assert_eq!(texts(edge(7), CONSENTS_PATH), ["PHS000001.C1"]);
    for path in [
        CONSENTS_PATH,
        TOPMED_CONSENTS_PATH,
        HARMONIZED_CONSENT_PATH,
        PARENT_CONSENTS_PATH,
    ] {
        assert_eq!(texts(edge(8), path), ["phs000003.c1"], "{path}");
    }
    assert_eq!(edge(9).len(), 1);
    assert_eq!(edge(9)[0].0, UNCONSENTED_PATH);
}

#[test]
fn hand_placed_ids_never_collide_with_generated_ones() {
    for shape in [Shape::BDCX, Shape::BDCDEV] {
        let first = shape.first_extra_patient();
        assert!(u64::from(first) > shape.patients);
        shape.validate_profile(EDGE).unwrap();
    }
    let rows = by_patient(&render(Shape::BDCX, EDGE, 13, Order::Patient));
    let generated_ids = generated(&rows, Shape::BDCX.first_extra_patient()).count();
    assert_eq!(generated_ids, 3_000);
}

#[test]
fn bdc_shapes_are_valid_and_the_profile_needs_five_studies() {
    Shape::BDCX.validate_profile(EDGE).unwrap();
    Shape::BDCDEV.validate_profile(REGULAR).unwrap();
    assert_eq!(Shape::BDCDEV.studies, 12);
    let few = Shape {
        studies: 4,
        ..Shape::TINY
    };
    assert!(few.validate_profile(REGULAR).is_err());
    assert!(few.validate_profile(Profile::Plain).is_ok());
}

#[test]
fn a_scaled_dev_shape_carries_every_bdc_concept() {
    let shape = Shape {
        patients: 6_000,
        observations: 6_000 * 400,
        ..Shape::BDCDEV
    };
    let rows = by_patient(&render(shape, REGULAR, 14, Order::Concept));
    let mut paths: HashMap<&str, usize> = HashMap::new();
    for patient in rows.values() {
        for (path, _, _) in patient {
            *paths.entry(path.as_str()).or_default() += 1;
        }
    }
    for rule in [
        TOPMED_CONSENTS_PATH,
        HARMONIZED_CONSENT_PATH,
        PARENT_CONSENTS_PATH,
    ] {
        assert!(paths.get(rule).copied().unwrap_or(0) > 100, "{rule}");
    }
    let table = concept_table_profile(&shape, REGULAR, 14);
    for concept in &table {
        if let Kind::Bdc(rule) = concept.kind {
            assert_ne!(rule, BdcRule::HandPlaced);
            assert!(
                paths.contains_key(concept.path.as_str()),
                "{}",
                concept.path
            );
        }
    }
}
