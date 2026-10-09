use std::collections::{HashMap, HashSet};
use std::hash::{DefaultHasher, Hasher};
use std::io::{self, Write};

use sha2::{Digest, Sha256};

use crate::edge::{
    CONFIG_DATASET_NAME, CONFIG_JSON, write_core, write_edge_dirs, write_lowram, write_negative_ids,
};
use crate::generate::{IdLayout, Order, Shape, concept_table, generate};
use crate::model::Kind;

const TINY_SEED_1_SHA256: &str = "c965293a9977351bba2f7309db1d57a3dfbbe62a996369b1194f526d69917362";

fn render(shape: Shape, seed: u64, order: Order) -> Vec<u8> {
    let mut bytes = Vec::new();
    generate(shape, seed, order, &mut bytes).unwrap();
    bytes
}

/// A sink that keeps only per-line summaries, so multi-million row outputs stay cheap in tests.
#[derive(Default)]
struct LineSink {
    pending: Vec<u8>,
    header_seen: bool,
    rows: u64,
    concepts: HashSet<Vec<u8>>,
    hashes: Vec<u64>,
    keep_hashes: bool,
}

impl LineSink {
    fn hashing() -> Self {
        Self {
            keep_hashes: true,
            ..Self::default()
        }
    }

    fn line(&mut self, line: &[u8]) {
        if !self.header_seen {
            self.header_seen = true;
            return;
        }
        self.rows += 1;
        let path = line.split(|b| *b == b',').nth(1).unwrap();
        if !self.concepts.contains(path) {
            self.concepts.insert(path.to_vec());
        }
        if self.keep_hashes {
            let mut hasher = DefaultHasher::new();
            hasher.write(line);
            self.hashes.push(hasher.finish());
        }
    }
}

impl Write for LineSink {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.pending.extend_from_slice(buf);
        let mut start = 0;
        while let Some(end) = self.pending[start..].iter().position(|b| *b == b'\n') {
            let line = self.pending[start..start + end].to_vec();
            self.line(&line);
            start += end + 1;
        }
        self.pending.drain(..start);
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

fn summarize(shape: Shape, seed: u64, order: Order, mut sink: LineSink) -> LineSink {
    let rows = generate(shape, seed, order, &mut sink).unwrap();
    assert_eq!(rows, sink.rows);
    sink
}

fn scaled(base: Shape, patients: u64, per_patient: u64) -> Shape {
    Shape {
        patients,
        observations: patients * per_patient,
        ..base
    }
}

#[test]
fn same_seed_is_byte_identical_and_other_seeds_differ() {
    let first = render(Shape::TINY, 1, Order::Patient);
    let second = render(Shape::TINY, 1, Order::Patient);
    let other = render(Shape::TINY, 2, Order::Patient);
    assert_eq!(first, second);
    assert_ne!(first, other);
}

#[test]
fn tiny_seed_1_output_is_pinned() {
    let bytes = render(Shape::TINY, 1, Order::Patient);
    let digest: String = Sha256::digest(&bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect();
    assert_eq!(digest, TINY_SEED_1_SHA256);
}

#[test]
fn coverage_meets_targets_at_scaled_presets() {
    let shapes = [
        ("bench ratio", scaled(Shape::BENCH, 10_000, 250)),
        ("dev ratio", scaled(Shape::DEV, 5_000, 500)),
        (
            "bench, fewer observations",
            scaled(Shape::BENCH, 5_000, 180),
        ),
        ("bench, more observations", scaled(Shape::BENCH, 5_000, 400)),
        ("dev, fewer observations", scaled(Shape::DEV, 5_000, 150)),
        (
            "tiny, more observations",
            Shape {
                observations: 45_000,
                ..Shape::TINY
            },
        ),
        ("tiny", Shape::TINY),
    ];
    for (name, shape) in shapes {
        shape.validate().unwrap();
        let sink = summarize(shape, 11, Order::Patient, LineSink::default());
        let present = sink.concepts.len() as f64 / shape.concepts as f64;
        assert!(
            present >= 0.95,
            "{name}: only {present:.3} of concepts present"
        );
        let target = shape.observations as f64;
        let error = (sink.rows as f64 - target).abs() / target;
        assert!(
            error < 0.10,
            "{name}: {} rows for target {target}",
            sink.rows
        );
    }
}

#[test]
fn row_order_does_not_change_the_rows() {
    let shapes = [Shape::TINY, scaled(Shape::BENCH, 4_000, 250)];
    for shape in shapes {
        let mut by_patient = summarize(shape, 9, Order::Patient, LineSink::hashing()).hashes;
        let mut by_concept = summarize(shape, 9, Order::Concept, LineSink::hashing()).hashes;
        by_patient.sort_unstable();
        by_concept.sort_unstable();
        assert_eq!(by_patient.len(), by_concept.len());
        assert!(by_patient == by_concept, "row multisets differ");
    }
}

#[test]
fn concept_order_is_deterministic_and_grouped() {
    let first = render(Shape::TINY, 5, Order::Concept);
    let second = render(Shape::TINY, 5, Order::Concept);
    assert_eq!(first, second);
    let mut reader = csv::Reader::from_reader(first.as_slice());
    let mut finished = HashSet::new();
    let mut current = String::new();
    for record in reader.records() {
        let path = record.unwrap()[1].to_string();
        if path != current {
            assert!(finished.insert(current.clone()), "concept {current} split");
            current = path;
        }
    }
}

#[test]
fn consents_model_studies_and_consent_groups() {
    let bytes = render(Shape::TINY, 7, Order::Patient);
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
    let mut per_patient: HashMap<u64, Vec<(String, String)>> = HashMap::new();
    let mut own = 0u64;
    let mut generic = 0u64;
    let mut study_rows = Vec::new();
    for record in reader.records() {
        let record = record.unwrap();
        let patient: u64 = record[0].parse().unwrap();
        assert!((1..=1000).contains(&patient));
        let path = record[1].to_string();
        if path.starts_with("\\_") {
            per_patient
                .entry(patient)
                .or_default()
                .push((path, record[3].to_string()));
        } else {
            let study: u32 = path.split('\\').nth(1).unwrap()["study_".len()..]
                .parse()
                .unwrap();
            study_rows.push((patient, study));
        }
    }
    assert_eq!(per_patient.len(), 1000);
    let mut studies = HashSet::new();
    let mut groups = HashSet::new();
    let mut patient_study = HashMap::new();
    for (patient, rows) in &per_patient {
        assert_eq!(rows.len(), 5, "patient {patient}: {rows:?}");
        let value = |path: &str| {
            rows.iter()
                .find(|(p, _)| p == path)
                .map(|(_, v)| v.clone())
                .unwrap_or_else(|| panic!("patient {patient} lacks {path}"))
        };
        let consent = value("\\_consents\\");
        let study = value("\\_studies\\");
        let (phs, group) = consent.split_once('.').unwrap();
        assert_eq!(phs, study);
        assert!(group == "c1" || group == "c2");
        assert_eq!(value("\\_studies_consents\\"), consent);
        assert_eq!(value(&format!("\\_studies_consents\\{phs}\\")), consent);
        assert_eq!(
            value(&format!("\\_studies_consents\\{phs}\\{group}\\")),
            consent
        );
        studies.insert(study.clone());
        groups.insert(consent.clone());
        patient_study.insert(*patient, study["phs".len()..].parse::<u32>().unwrap());
    }
    assert_eq!(studies.len(), 4);
    assert_eq!(groups.len(), 8);
    for (patient, study) in study_rows {
        generic += 1;
        if patient_study[&patient] == study {
            own += 1;
        }
    }
    let share = own as f64 / generic as f64;
    assert!((0.75..0.85).contains(&share), "own study share {share}");
}

#[test]
fn patient_ids_follow_the_layout() {
    let ids_of = |shape: Shape| -> Vec<u64> {
        let bytes = render(shape, 3, Order::Patient);
        let mut seen = Vec::new();
        for line in bytes
            .split(|b| *b == b'\n')
            .skip(1)
            .filter(|l| !l.is_empty())
        {
            let id: u64 = std::str::from_utf8(line.split(|b| *b == b',').next().unwrap())
                .unwrap()
                .parse()
                .unwrap();
            if seen.last() != Some(&id) {
                seen.push(id);
            }
        }
        seen
    };
    let dense = ids_of(Shape::TINY);
    assert_eq!(dense, (1..=1000).collect::<Vec<_>>());
    let sparse_shape = scaled(Shape::BENCH, 5_000, 30);
    let sparse = ids_of(sparse_shape);
    assert_eq!(sparse.len(), 5_000);
    assert!(sparse.windows(2).all(|pair| pair[0] < pair[1]));
    assert!(sparse[0] >= 1 && *sparse.last().unwrap() < 15_001);
    let mean_gap = (sparse.last().unwrap() - sparse[0]) as f64 / 4_999.0;
    assert!((2.7..3.3).contains(&mean_gap), "mean gap {mean_gap}");
    let gaps: HashSet<u64> = sparse.windows(2).map(|pair| pair[1] - pair[0]).collect();
    assert!(gaps.len() > 3, "gaps are not random: {gaps:?}");
    let strided = ids_of(Shape {
        ids: IdLayout {
            stride: 7,
            gaps: false,
        },
        ..Shape::TINY
    });
    assert_eq!(strided, (0..1000).map(|n| 1 + 7 * n).collect::<Vec<_>>());
}

#[test]
fn shape_validation_rejects_ids_past_i32_max() {
    let too_wide = Shape {
        ids: IdLayout {
            stride: 3_000,
            gaps: true,
        },
        ..Shape::BENCH
    };
    assert!(too_wide.validate().is_err());
    assert!(Shape::BENCH.validate().is_ok());
    let too_few_concepts = Shape {
        concepts: 60,
        ..Shape::BENCH
    };
    assert!(too_few_concepts.validate().is_err());
}

#[test]
fn dev_and_bench_have_high_cardinality_concepts() {
    let high = |shape: Shape| {
        concept_table(&shape, 1)
            .iter()
            .filter(|c| matches!(c.kind, Kind::Categorical { cardinality } if cardinality >= 1_000))
            .count()
    };
    assert_eq!(high(Shape::TINY), 0);
    assert_eq!(high(Shape::DEV), 3);
    assert_eq!(high(Shape::BENCH), 5);
}

#[test]
fn edge_files_are_deterministic_and_split_by_loader() {
    let mut core = Vec::new();
    let mut again = Vec::new();
    let core_rows = write_core(&mut core).unwrap();
    write_core(&mut again).unwrap();
    assert_eq!(core, again);
    let mut lowram = Vec::new();
    let lowram_rows = write_lowram(&mut lowram).unwrap();
    assert!(lowram_rows > core_rows);
    assert!(lowram.starts_with(&core));
    let core_text = String::from_utf8(core.clone()).unwrap();
    for fatal in [
        ",abc,",
        ",not_a_number,",
        ",1.5\n",
        "12345678901234567890",
        ",oops,",
    ] {
        assert!(!core_text.contains(fatal), "core holds {fatal:?}");
    }
    let mut reader = csv::ReaderBuilder::new()
        .flexible(true)
        .from_reader(lowram.as_slice());
    let mut widths = HashSet::new();
    for record in reader.records() {
        let record = record.unwrap();
        assert!(!record[0].starts_with('-'));
        widths.insert(record.len());
    }
    assert!(widths.contains(&6) && widths.contains(&7) && widths.contains(&4));
    let mut negative = Vec::new();
    write_negative_ids(&mut negative).unwrap();
    assert!(String::from_utf8(negative).unwrap().contains("\n-5,"));
}

#[test]
fn edge_dirs_are_written() {
    let dir = tempfile::tempdir().unwrap();
    let written = write_edge_dirs(dir.path()).unwrap();
    for name in [
        "core",
        "lowram",
        "negative-ids",
        "embedded-newline",
        "config",
    ] {
        let path = dir.path().join(name).join("allConcepts.csv");
        assert!(written.contains(&path), "{name} missing");
        assert!(std::fs::metadata(&path).unwrap().len() > 0);
    }
    let json = std::fs::read_to_string(dir.path().join("config").join("config.json")).unwrap();
    assert_eq!(json, CONFIG_JSON);
    assert!(json.contains(&format!("\"dataset_name\": \"{CONFIG_DATASET_NAME}\"")));
    assert!(json.contains("\"dataset_name_as_root_node\": true"));
}
