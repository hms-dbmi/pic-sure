//! Streaming generation of the observation rows.

use std::io::Write;

use crate::model::{CONSENT_GROUPS, Concept, Kind, NumericShape, build_concepts};
use crate::rng::{Source, mix};

/// Size of the dataset to generate.
#[derive(Clone, Copy, Debug)]
pub struct Shape {
    /// Number of patients, numbered `1..=patients`.
    pub patients: u64,
    /// Number of concepts, including the three special ones.
    pub concepts: usize,
    /// Approximate number of observations to emit.
    pub observations: u64,
}

impl Shape {
    /// 1,000 patients, 200 concepts.
    pub const TINY: Shape = Shape {
        patients: 1_000,
        concepts: 200,
        observations: 30_000,
    };
    /// 100,000 patients, 10,000 concepts, about 50 million observations.
    pub const DEV: Shape = Shape {
        patients: 100_000,
        concepts: 10_000,
        observations: 50_000_000,
    };
    /// 1,000,000 patients, 20,000 concepts, about 250 million observations.
    pub const BENCH: Shape = Shape {
        patients: 1_000_000,
        concepts: 20_000,
        observations: 250_000_000,
    };
}

/// The CSV header every output starts with.
pub const HEADER: [&str; 5] = [
    "PATIENT_NUM",
    "CONCEPT_PATH",
    "NVAL_NUM",
    "TVAL_CHAR",
    "TIMESTAMP",
];

const BLOCK_PATIENTS: u64 = 2_000;
const TIME_ORIGIN_MS: i64 = 946_684_800_000;
const TIME_SPAN_MS: i64 = 25 * 365 * 86_400_000;
const DAY_MS: i64 = 86_400_000;

#[derive(Clone, Copy)]
enum Cell {
    Number(f64),
    Category(u32),
}

struct Row {
    order: u64,
    concept: u32,
    timestamp: i64,
    cell: Cell,
}

fn round_to(value: f64, decimals: u32) -> f64 {
    let factor = 10f64.powi(decimals as i32);
    let rounded = (value * factor).round() / factor;
    if rounded == 0.0 { 0.0 } else { rounded }
}

fn draw_number(shape: &NumericShape, rng: &mut Source) -> f64 {
    match shape {
        NumericShape::Integer { lo, hi } => rng.range(*lo, *hi) as f64,
        NumericShape::Normal { mean, sd, decimals } => {
            round_to(mean + sd * rng.normal(), *decimals)
        }
        NumericShape::Uniform { lo, hi, decimals } => {
            round_to(lo + (hi - lo) * rng.unit(), *decimals)
        }
    }
}

fn push_observations(
    rows: &mut Vec<Row>,
    patient_order: u64,
    index: u32,
    concept: &Concept,
    rng: &mut Source,
) {
    let count = if concept.repeated { rng.range(2, 6) } else { 1 };
    let mut when = TIME_ORIGIN_MS + (rng.unit() * TIME_SPAN_MS as f64) as i64;
    for _ in 0..count {
        let cell = match &concept.kind {
            Kind::Numeric(shape) => Cell::Number(draw_number(shape, rng)),
            Kind::Categorical { cardinality } => {
                let skewed = rng.unit().powi(2) * f64::from(*cardinality);
                Cell::Category((skewed as u32).min(cardinality - 1))
            }
            Kind::PerPatient => unreachable!("special concepts are emitted separately"),
        };
        let timestamp = if concept.timestamped { when } else { -1 };
        let order = (patient_order << 32) | u64::from(rng.bits32());
        rows.push(Row {
            order,
            concept: index,
            timestamp,
            cell,
        });
        when += rng.range(1, 90) * DAY_MS;
    }
}

fn generate_block(
    seed: u64,
    block: u64,
    first: u64,
    last: u64,
    concepts: &[Concept],
    rows: &mut Vec<Row>,
) {
    rows.clear();
    let mut rng = Source::new(mix(seed ^ mix(block.wrapping_add(1))));
    let span = last - first;
    for offset in 0..span {
        let group = rng.range(0, CONSENT_GROUPS.len() as i64 - 1) as u32;
        for special in 0..3u32 {
            rows.push(Row {
                order: (offset << 32) | u64::from(rng.bits32()),
                concept: special,
                timestamp: -1,
                cell: Cell::Category(group),
            });
        }
    }
    for (index, concept) in concepts.iter().enumerate().skip(3) {
        let log_miss = (1.0 - concept.coverage).ln();
        let mut position = 0u64;
        loop {
            let draw = 1.0 - rng.unit();
            let skip = (draw.ln() / log_miss).floor();
            if !skip.is_finite() || skip >= (span - position) as f64 {
                break;
            }
            position += skip as u64;
            push_observations(rows, position, index as u32, concept, &mut rng);
            position += 1;
            if position >= span {
                break;
            }
        }
    }
    rows.sort_unstable_by_key(|row| (row.order, row.concept, row.timestamp));
}

fn write_row<W: Write>(
    out: &mut csv::Writer<W>,
    patient: &str,
    concept: &Concept,
    row: &Row,
    scratch: &mut Scratch,
) -> csv::Result<()> {
    scratch.number.clear();
    scratch.text.clear();
    scratch.time.clear();
    use std::fmt::Write as _;
    match (&concept.kind, row.cell) {
        (Kind::Numeric(shape), Cell::Number(value)) => match shape {
            NumericShape::Integer { .. } => write!(scratch.number, "{}", value as i64).unwrap(),
            NumericShape::Normal { decimals, .. } | NumericShape::Uniform { decimals, .. } => {
                write!(scratch.number, "{:.*}", *decimals as usize, value).unwrap()
            }
        },
        (Kind::Categorical { .. }, Cell::Category(value)) => {
            write!(scratch.text, "cat_{value}").unwrap()
        }
        (Kind::PerPatient, Cell::Category(group)) => {
            let (study, consent) = CONSENT_GROUPS[group as usize];
            let text = if concept.path == crate::model::STUDIES_PATH {
                study
            } else {
                consent
            };
            scratch.text.push_str(text);
        }
        _ => unreachable!("cell does not match concept kind"),
    }
    if row.timestamp >= 0 {
        write!(scratch.time, "{}", row.timestamp).unwrap();
    }
    out.write_record([
        patient,
        concept.path.as_str(),
        scratch.number.as_str(),
        scratch.text.as_str(),
        scratch.time.as_str(),
    ])
}

#[derive(Default)]
struct Scratch {
    number: String,
    text: String,
    time: String,
}

/// Writes the dataset to `sink` as CSV with the standard header and returns the observation
/// count.
///
/// Output is a pure function of `shape` and `seed`. Patients are processed in blocks of 2,000,
/// and rows inside a block are interleaved across concepts, so memory use is bounded by one
/// block regardless of the dataset size.
///
/// # Errors
/// Returns any I/O or CSV error from the sink.
pub fn generate<W: Write>(shape: Shape, seed: u64, sink: W) -> csv::Result<u64> {
    let concepts = build_concepts(seed, shape.concepts, shape.patients, shape.observations);
    let mut out = csv::WriterBuilder::new().from_writer(sink);
    out.write_record(HEADER)?;
    let mut rows = Vec::new();
    let mut scratch = Scratch::default();
    let mut total = 0u64;
    let mut patient_text = String::new();
    let mut first = 0u64;
    let mut block = 0u64;
    while first < shape.patients {
        let last = (first + BLOCK_PATIENTS).min(shape.patients);
        generate_block(seed, block, first, last, &concepts, &mut rows);
        for row in &rows {
            use std::fmt::Write as _;
            patient_text.clear();
            write!(patient_text, "{}", first + (row.order >> 32) + 1).unwrap();
            write_row(
                &mut out,
                &patient_text,
                &concepts[row.concept as usize],
                row,
                &mut scratch,
            )?;
        }
        total += rows.len() as u64;
        first = last;
        block += 1;
    }
    out.flush()?;
    Ok(total)
}
