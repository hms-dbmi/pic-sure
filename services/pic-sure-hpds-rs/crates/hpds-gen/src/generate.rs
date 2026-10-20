//! Streaming generation of the observation rows.

use std::fmt::Write as _;
use std::io::Write;

use crate::bdc::{self, BdcRule, EDGE_PATIENTS, ExtraRow, ExtraValue, HarmonizedValues, Profile};
use crate::model::{
    Concept, ConsentValue, Kind, Membership, NumericShape, Population, TableShape, build_concepts,
    consent_concept_count, study_accession, study_consent,
};
use crate::rng::{Source, mix};

/// How patient IDs are laid out.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct IdLayout {
    /// Average distance between consecutive patient IDs; 1 means `1..=patients`.
    pub stride: u32,
    /// Whether the distance between consecutive IDs is random (averaging `stride`) rather
    /// than exactly `stride`.
    pub gaps: bool,
}

impl IdLayout {
    /// Consecutive IDs `1..=patients`.
    pub const DENSE: IdLayout = IdLayout {
        stride: 1,
        gaps: false,
    };
}

/// Size and layout of the dataset to generate.
#[derive(Clone, Copy, Debug)]
pub struct Shape {
    /// Number of patients.
    pub patients: u64,
    /// Number of concepts, consent concepts included.
    pub concepts: usize,
    /// Approximate number of observations to emit.
    pub observations: u64,
    /// Number of studies, each with two consent groups.
    pub studies: u32,
    /// Number of categorical concepts with 1,000 to 10,000 values.
    pub high_cardinality: usize,
    /// Patient ID layout.
    pub ids: IdLayout,
}

impl Shape {
    /// 1,000 patients, 200 concepts, 4 studies, consecutive IDs.
    pub const TINY: Shape = Shape {
        patients: 1_000,
        concepts: 200,
        observations: 30_000,
        studies: 4,
        high_cardinality: 0,
        ids: IdLayout::DENSE,
    };
    /// 100,000 patients, 10,000 concepts, about 50 million observations, 10 studies,
    /// consecutive IDs.
    pub const DEV: Shape = Shape {
        patients: 100_000,
        concepts: 10_000,
        observations: 50_000_000,
        studies: 10,
        high_cardinality: 3,
        ids: IdLayout::DENSE,
    };
    /// 3,000 patients, 400 concepts, about 150,000 observations, 8 studies, IDs with random gaps
    /// averaging 2. The fixture scale of the BDC profile.
    pub const BDCX: Shape = Shape {
        patients: 3_000,
        concepts: 400,
        observations: 150_000,
        studies: 8,
        high_cardinality: 0,
        ids: IdLayout {
            stride: 2,
            gaps: true,
        },
    };
    /// 100,000 patients, 10,000 concepts, about 50 million observations, 12 studies,
    /// consecutive IDs. The dev scale of the BDC profile.
    pub const BDCDEV: Shape = Shape {
        patients: 100_000,
        concepts: 10_000,
        observations: 50_000_000,
        studies: 12,
        high_cardinality: 3,
        ids: IdLayout::DENSE,
    };
    /// 1,000,000 patients, 20,000 concepts, about 250 million observations, 20 studies, IDs
    /// with random gaps averaging 3.
    pub const BENCH: Shape = Shape {
        patients: 1_000_000,
        concepts: 20_000,
        observations: 250_000_000,
        studies: 20,
        high_cardinality: 5,
        ids: IdLayout {
            stride: 3,
            gaps: true,
        },
    };

    /// Checks that the shape can be generated.
    ///
    /// # Errors
    /// Returns a message when there are no patients or studies, too few concepts for the
    /// consent concepts plus four generic ones, a zero ID stride, or IDs that would reach
    /// `i32::MAX`.
    pub fn validate(&self) -> Result<(), String> {
        if self.patients == 0 || self.studies == 0 {
            return Err("need at least 1 patient and 1 study".to_string());
        }
        let minimum = consent_concept_count(self.studies) + 4;
        if self.concepts < minimum {
            return Err(format!(
                "{} studies need at least {minimum} concepts",
                self.studies
            ));
        }
        if self.ids.stride == 0 {
            return Err("the ID stride must be at least 1".to_string());
        }
        if self.patients.saturating_mul(u64::from(self.ids.stride)) >= i32::MAX as u64 {
            return Err("patient IDs would reach i32::MAX".to_string());
        }
        Ok(())
    }

    /// The ID of the first hand-placed edge patient: the first ID past the generated range.
    pub fn first_extra_patient(&self) -> u32 {
        (1 + self.patients * u64::from(self.ids.stride)) as u32
    }

    /// Checks that the shape can be generated under `profile`, which the BDC profile limits to
    /// at least five studies and room for the hand-placed patients' IDs.
    ///
    /// # Errors
    /// Returns [`Shape::validate`]'s message, or one for too few studies or IDs that would
    /// reach `i32::MAX`.
    pub fn validate_profile(&self, profile: Profile) -> Result<(), String> {
        self.validate()?;
        if let Profile::Bdc { edge } = profile {
            if self.studies < 5 {
                return Err("the BDC profile needs at least 5 studies".to_string());
            }
            let last = u64::from(self.first_extra_patient()) + u64::from(EDGE_PATIENTS);
            if edge && last >= i32::MAX as u64 {
                return Err("the edge patients' IDs would reach i32::MAX".to_string());
            }
        }
        Ok(())
    }

    fn table(&self) -> TableShape {
        TableShape {
            patients: self.patients,
            concepts: self.concepts,
            observations: self.observations,
            studies: self.studies,
            high_cardinality: self.high_cardinality,
        }
    }
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
const POW10: [f64; 6] = [1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0];

#[derive(Clone, Copy)]
enum Cell {
    Number(f64),
    Category(u32),
    ConsentGroup(Membership),
    Consent(Membership),
    Label(&'static str),
}

struct Row {
    offset: u32,
    tiebreak: u32,
    concept: u32,
    seq: u32,
    timestamp: i64,
    cell: Cell,
}

/// One block of consecutive patient ordinals with their IDs and memberships.
struct Block {
    first: u64,
    ids: Vec<u32>,
    members: Vec<Membership>,
    by_study: Vec<Vec<u32>>,
}

/// Lays out the IDs of `span` patients starting at ordinal `first`.
///
/// Without gaps the ID of ordinal `n` is `1 + n * stride`. With gaps each block of
/// `BLOCK_PATIENTS` ordinals owns the ID range `[1 + first * stride, 1 + (first + span) *
/// stride)` and picks `span` IDs from it uniformly at random, so IDs are increasing, distinct
/// and spaced `stride` apart on average, and each block can be laid out on its own.
fn patient_ids(seed: u64, ids: IdLayout, block: u64, first: u64, span: u64) -> Vec<u32> {
    let stride = u64::from(ids.stride);
    let base = 1 + first * stride;
    if !ids.gaps || stride == 1 {
        return (0..span).map(|n| (base + n * stride) as u32).collect();
    }
    let candidates = span * stride;
    let mut rng = Source::new(mix(seed ^ mix(0x1D5_0000_0000_0000 ^ block)));
    let mut picked = Vec::with_capacity(span as usize);
    let mut needed = span;
    for candidate in 0..candidates {
        if needed == 0 {
            break;
        }
        let left = candidates - candidate;
        if (rng.bits() % left) < needed {
            picked.push((base + candidate) as u32);
            needed -= 1;
        }
    }
    picked
}

fn build_block(seed: u64, shape: &Shape, population: &Population, index: u64) -> Block {
    let first = index * BLOCK_PATIENTS;
    let span = BLOCK_PATIENTS.min(shape.patients - first);
    let ids = patient_ids(seed, shape.ids, index, first, span);
    let members: Vec<Membership> = (0..span)
        .map(|offset| population.assign(seed, first + offset))
        .collect();
    let mut by_study = vec![Vec::new(); population.studies() as usize];
    for (offset, member) in members.iter().enumerate() {
        by_study[member.study as usize].push(offset as u32);
    }
    Block {
        first,
        ids,
        members,
        by_study,
    }
}

fn block_count(patients: u64) -> u64 {
    patients.div_ceil(BLOCK_PATIENTS)
}

fn round_to(value: f64, decimals: u32) -> f64 {
    let factor = POW10[decimals as usize];
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

fn tiebreak(seed: u64, ordinal: u64, concept: u32, seq: u32) -> u32 {
    let salt = (u64::from(concept) << 32) | u64::from(seq);
    (mix(seed ^ mix(ordinal ^ mix(salt ^ 0x7E1B_0000_0000_0000))) >> 32) as u32
}

struct Emitter<'a> {
    seed: u64,
    block: &'a Block,
    index: u32,
    concept: &'a Concept,
}

impl Emitter<'_> {
    fn push(&self, rows: &mut Vec<Row>, offset: u32, rng: &mut Source) {
        let count = if self.concept.repeated {
            rng.range(2, 6)
        } else {
            1
        };
        let mut when = TIME_ORIGIN_MS + (rng.unit() * TIME_SPAN_MS as f64) as i64;
        let ordinal = self.block.first + u64::from(offset);
        for seq in 0..count as u32 {
            let cell = match &self.concept.kind {
                Kind::Numeric(shape) => Cell::Number(draw_number(shape, rng)),
                Kind::Categorical { cardinality } => {
                    let draw = rng.unit();
                    let skewed = draw * draw * f64::from(*cardinality);
                    Cell::Category((skewed as u32).min(cardinality - 1))
                }
                Kind::ConsentGroup { .. } | Kind::Bdc(_) => {
                    unreachable!("consent rows are not drawn")
                }
            };
            let timestamp = if self.concept.timestamped { when } else { -1 };
            rows.push(Row {
                offset,
                tiebreak: tiebreak(self.seed, ordinal, self.index, seq),
                concept: self.index,
                seq,
                timestamp,
                cell,
            });
            when += rng.range(1, 90) * DAY_MS;
        }
    }
}

/// Appends the rows of one concept for one block of patients.
///
/// All randomness comes from `rng`, the concept's own stream, so the rows depend only on the
/// seed, the concept and the block, never on what other concepts or blocks drew. Patients of
/// the home study are visited with the home coverage and the others with the away coverage,
/// each by geometric skipping so the cost follows the number of rows, not of patients.
fn sample_concept(
    seed: u64,
    block: &Block,
    index: u32,
    concept: &Concept,
    rng: &mut Source,
    rows: &mut Vec<Row>,
) {
    if let Kind::Bdc(rule) = concept.kind {
        sample_bdc(seed, block, index, rule, rows);
        return;
    }
    if let Kind::ConsentGroup { scope, .. } = concept.kind {
        let ordinal = |offset: usize| block.first + offset as u64;
        for (offset, member) in block.members.iter().enumerate() {
            if scope.includes(*member) {
                rows.push(Row {
                    offset: offset as u32,
                    tiebreak: tiebreak(seed, ordinal(offset), index, 0),
                    concept: index,
                    seq: 0,
                    timestamp: -1,
                    cell: Cell::ConsentGroup(*member),
                });
            }
        }
        return;
    }
    let emitter = Emitter {
        seed,
        block,
        index,
        concept,
    };
    let home = &block.by_study[concept.home as usize];
    let log_home = libm::log(1.0 - concept.home_coverage);
    let mut position = 0u64;
    while let Some(skip) = rng.geometric_skip(log_home) {
        if skip >= (home.len() as u64 - position) as f64 {
            break;
        }
        position += skip as u64;
        emitter.push(rows, home[position as usize], rng);
        position += 1;
    }
    let span = block.members.len() as u64;
    let log_away = libm::log(1.0 - concept.away_coverage);
    let mut position = 0u64;
    while let Some(skip) = rng.geometric_skip(log_away) {
        if skip >= (span - position) as f64 {
            break;
        }
        position += skip as u64;
        if block.members[position as usize].study != concept.home {
            emitter.push(rows, position as u32, rng);
        }
        position += 1;
    }
}

/// Appends the rows of one BDC concept for one block of patients, each decided by the patient's
/// ordinal alone.
fn sample_bdc(seed: u64, block: &Block, index: u32, rule: BdcRule, rows: &mut Vec<Row>) {
    for (offset, member) in block.members.iter().enumerate() {
        let ordinal = block.first + offset as u64;
        let cell = match rule {
            BdcRule::Consents => {
                if let Some(twin) = bdc::twin_consent(seed, ordinal, *member) {
                    rows.push(Row {
                        offset: offset as u32,
                        tiebreak: tiebreak(seed, ordinal, index, 1),
                        concept: index,
                        seq: 1,
                        timestamp: -1,
                        cell: Cell::Consent(twin),
                    });
                }
                Cell::Consent(*member)
            }
            BdcRule::Harmonized(which) => {
                let Some(mut rng) = bdc::harmonized_stream(seed, ordinal, which) else {
                    continue;
                };
                match bdc::harmonized_concepts()[which].values {
                    HarmonizedValues::Labels(labels) => {
                        let pick = (rng.unit() * labels.len() as f64) as usize;
                        Cell::Label(labels[pick.min(labels.len() - 1)])
                    }
                    HarmonizedValues::Number(shape) => Cell::Number(draw_number(shape, &mut rng)),
                }
            }
            BdcRule::HandPlaced => return,
            consent @ (BdcRule::TopmedConsent
            | BdcRule::HarmonizedConsent
            | BdcRule::ParentConsent) => match bdc::consent_of(consent, seed, ordinal, *member) {
                Some(held) => Cell::Consent(held),
                None => continue,
            },
        };
        rows.push(Row {
            offset: offset as u32,
            tiebreak: tiebreak(seed, ordinal, index, 0),
            concept: index,
            seq: 0,
            timestamp: -1,
            cell,
        });
    }
}

#[derive(Default)]
struct Scratch {
    patient: String,
    number: String,
    text: String,
    time: String,
}

fn write_row<W: Write>(
    out: &mut csv::Writer<W>,
    patient: u32,
    concept: &Concept,
    row: &Row,
    scratch: &mut Scratch,
) -> csv::Result<()> {
    scratch.patient.clear();
    scratch.number.clear();
    scratch.text.clear();
    scratch.time.clear();
    write!(scratch.patient, "{patient}").unwrap();
    match (&concept.kind, row.cell) {
        (Kind::Numeric(shape), Cell::Number(value)) => {
            write_number(&mut scratch.number, shape, value)
        }
        (Kind::Bdc(BdcRule::Harmonized(which)), Cell::Number(value)) => {
            let HarmonizedValues::Number(shape) = bdc::harmonized_concepts()[*which].values else {
                unreachable!("a label concept drew a number")
            };
            write_number(&mut scratch.number, shape, value)
        }
        (Kind::Bdc(_), Cell::Label(label)) => scratch.text.push_str(label),
        (Kind::Bdc(_), Cell::Consent(held)) => scratch
            .text
            .push_str(&study_consent(held.study, held.group)),
        (Kind::Categorical { .. }, Cell::Category(value)) => {
            write!(scratch.text, "cat_{value}").unwrap()
        }
        (Kind::ConsentGroup { value, .. }, Cell::ConsentGroup(member)) => match value {
            ConsentValue::Study => scratch.text.push_str(&study_accession(member.study)),
            ConsentValue::StudyConsent => scratch
                .text
                .push_str(&study_consent(member.study, member.group)),
        },
        _ => unreachable!("cell does not match concept kind"),
    }
    if row.timestamp >= 0 {
        write!(scratch.time, "{}", row.timestamp).unwrap();
    }
    out.write_record([
        scratch.patient.as_str(),
        concept.path.as_str(),
        scratch.number.as_str(),
        scratch.text.as_str(),
        scratch.time.as_str(),
    ])
}

fn write_number(out: &mut String, shape: &NumericShape, value: f64) {
    match shape {
        NumericShape::Integer { .. } => write!(out, "{}", value as i64).unwrap(),
        NumericShape::Normal { decimals, .. } | NumericShape::Uniform { decimals, .. } => {
            write!(out, "{:.*}", *decimals as usize, value).unwrap()
        }
    }
}

fn write_extra<W: Write>(out: &mut csv::Writer<W>, row: &ExtraRow) -> csv::Result<()> {
    let (number, text) = match &row.value {
        ExtraValue::Number(number) => (*number, ""),
        ExtraValue::Text(text) => ("", text.as_str()),
    };
    out.write_record([row.patient.to_string().as_str(), row.path, number, text, ""])
}

/// Row order of the generated file. Both orders hold exactly the same rows for the same seed
/// and shape; only their sequence differs.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Order {
    /// Blocks of 2,000 patients, each patient's rows together and interleaved across concepts,
    /// like a real export. `CSVLoaderNewSearch` keeps only 16 concepts in its cache and starts
    /// a concept afresh when it comes back after eviction, so it loses most rows in this
    /// order. The LowRAM loader sorts each chunk by concept path first, so it only loses rows
    /// when the file is larger than `MAX_CHUNK_SIZE` (5 GB by default) and splits into chunks.
    Patient,
    /// All rows of a concept are contiguous, in patient order. `CSVLoaderNewSearch` loads this
    /// order fully. The LowRAM loader's one-concept cache still loses the earlier rows of a
    /// concept that straddles a chunk boundary.
    Concept,
}

/// Writes the dataset to `sink` as CSV with the standard header and returns the observation
/// count.
///
/// Output is a pure function of `shape`, `seed` and `order`, and the two orders produce the
/// same multiset of rows. Patient order holds one block of patients in memory plus one random
/// stream per concept; concept order also keeps every patient's ID and membership (about 16
/// bytes per patient).
///
/// # Errors
/// Returns any I/O or CSV error from the sink. `shape` must pass [`Shape::validate`].
pub fn generate<W: Write>(shape: Shape, seed: u64, order: Order, sink: W) -> csv::Result<u64> {
    generate_profile(shape, Profile::Plain, seed, order, sink)
}

/// Writes the dataset under `profile` like [`generate`]. With [`Profile::Bdc`] the plain rows
/// are exactly those of the plain profile for the same shape and seed, and the BDC concepts'
/// rows come on top; with `edge` the hand-placed patients follow the generated ones, in patient
/// order after the last block or, in concept order, at the end of each concept's rows.
///
/// # Errors
/// Returns any I/O or CSV error from the sink. `shape` must pass [`Shape::validate_profile`].
pub fn generate_profile<W: Write>(
    shape: Shape,
    profile: Profile,
    seed: u64,
    order: Order,
    sink: W,
) -> csv::Result<u64> {
    let population = Population::new(shape.studies.max(1));
    let concepts = build_profile_concepts(seed, &shape, profile, &population);
    let extras = extra_rows(&shape, profile);
    let mut out = csv::WriterBuilder::new().from_writer(sink);
    out.write_record(HEADER)?;
    let total = match order {
        Order::Patient => {
            write_by_patient(&shape, seed, &population, &concepts, &extras, &mut out)?
        }
        Order::Concept => {
            write_by_concept(&shape, seed, &population, &concepts, &extras, &mut out)?
        }
    };
    out.flush()?;
    Ok(total)
}

fn build_profile_concepts(
    seed: u64,
    shape: &Shape,
    profile: Profile,
    population: &Population,
) -> Vec<Concept> {
    let mut concepts = build_concepts(seed, &shape.table(), population);
    if let Profile::Bdc { edge } = profile {
        concepts[0].kind = Kind::Bdc(BdcRule::Consents);
        concepts.extend(bdc::concepts(edge));
    }
    concepts
}

fn extra_rows(shape: &Shape, profile: Profile) -> Vec<ExtraRow> {
    match profile {
        Profile::Bdc { edge: true } => bdc::edge_rows(shape.first_extra_patient()),
        _ => Vec::new(),
    }
}

/// Builds the concept table for `shape` and `seed` exactly as [`generate`] does, so callers
/// can inspect coverage without writing rows.
pub fn concept_table(shape: &Shape, seed: u64) -> Vec<Concept> {
    concept_table_profile(shape, Profile::Plain, seed)
}

/// Builds the concept table as [`generate_profile`] does, the BDC concepts last.
pub fn concept_table_profile(shape: &Shape, profile: Profile, seed: u64) -> Vec<Concept> {
    let population = Population::new(shape.studies.max(1));
    build_profile_concepts(seed, shape, profile, &population)
}

fn concept_stream(seed: u64, index: usize) -> Source {
    Source::new(mix(seed ^ mix(0xC0C0_0000_0000_0000 ^ index as u64)))
}

fn write_by_concept<W: Write>(
    shape: &Shape,
    seed: u64,
    population: &Population,
    concepts: &[Concept],
    extras: &[ExtraRow],
    out: &mut csv::Writer<W>,
) -> csv::Result<u64> {
    let blocks: Vec<Block> = (0..block_count(shape.patients))
        .map(|index| build_block(seed, shape, population, index))
        .collect();
    let mut rows = Vec::new();
    let mut scratch = Scratch::default();
    let mut total = 0u64;
    for (index, concept) in concepts.iter().enumerate() {
        let mut rng = concept_stream(seed, index);
        for block in &blocks {
            rows.clear();
            sample_concept(seed, block, index as u32, concept, &mut rng, &mut rows);
            rows.sort_unstable_by_key(|row| (row.offset, row.seq));
            for row in &rows {
                write_row(
                    out,
                    block.ids[row.offset as usize],
                    concept,
                    row,
                    &mut scratch,
                )?;
            }
            total += rows.len() as u64;
        }
        for extra in extras.iter().filter(|extra| extra.path == concept.path) {
            write_extra(out, extra)?;
            total += 1;
        }
    }
    Ok(total)
}

fn write_by_patient<W: Write>(
    shape: &Shape,
    seed: u64,
    population: &Population,
    concepts: &[Concept],
    extras: &[ExtraRow],
    out: &mut csv::Writer<W>,
) -> csv::Result<u64> {
    let mut streams: Vec<Source> = (0..concepts.len())
        .map(|index| concept_stream(seed, index))
        .collect();
    let mut rows = Vec::new();
    let mut scratch = Scratch::default();
    let mut total = 0u64;
    for block_index in 0..block_count(shape.patients) {
        let block = build_block(seed, shape, population, block_index);
        rows.clear();
        for (index, (concept, rng)) in concepts.iter().zip(streams.iter_mut()).enumerate() {
            sample_concept(seed, &block, index as u32, concept, rng, &mut rows);
        }
        rows.sort_unstable_by_key(|row| (row.offset, row.tiebreak, row.concept, row.seq));
        for row in &rows {
            write_row(
                out,
                block.ids[row.offset as usize],
                &concepts[row.concept as usize],
                row,
                &mut scratch,
            )?;
        }
        total += rows.len() as u64;
    }
    for extra in extras {
        write_extra(out, extra)?;
        total += 1;
    }
    Ok(total)
}
