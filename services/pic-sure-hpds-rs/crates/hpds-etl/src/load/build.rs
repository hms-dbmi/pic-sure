//! The build pass: turn one partition's spilled rows into sealed concept blocks and their
//! metadata, following one Java loader's per-concept rules.

use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use hpds_format::{
    BlockCodec, BlockLocation, CategoricalRow, ConceptMeta, ConceptType, NO_TIMESTAMP,
    NumericObservation, encode_interned_categorical_payload, encode_numeric_payload,
    java_string_cmp,
};
use roaring::RoaringBitmap;
use rustc_hash::FxHashMap;

use super::LoadCounts;
use super::rules::{Mode, ValueStatus};
use super::spill::{SpillRow, read_rows};
use crate::error::{EtlError, Result};
use crate::java::{java_double_max, java_double_min};
use crate::private_fs;

/// A row whose patient number Java would parse and fail on, found in new-search or
/// partitioned mode: its global record number and its line in its input file.
#[derive(Clone, Copy, Debug)]
pub(crate) struct PatientFailure {
    pub seq: u64,
    pub line: u64,
}

/// A concept built from one partition, with its block at `block.offset` in the
/// partition's block file.
pub(crate) struct BuiltConcept {
    pub meta: ConceptMeta,
    pub partition: usize,
}

/// Everything one partition produced.
pub(crate) struct BuiltPartition {
    pub concepts: Vec<BuiltConcept>,
    pub blocks_file: PathBuf,
    pub patients: RoaringBitmap,
    pub counts: LoadCounts,
    pub patient_failure: Option<PatientFailure>,
}

/// Shared inputs of every partition build.
pub(crate) struct BuildContext<'a> {
    pub mode: Mode,
    pub concepts: &'a [String],
    pub raw_path_ranks: &'a [u32],
    /// Sequential mode: the `seq` of the first record of every chunk, ascending.
    pub chunk_starts: &'a [u64],
    pub codec: &'a BlockCodec,
}

impl BuildContext<'_> {
    fn chunk_of(&self, seq: u64) -> usize {
        self.chunk_starts.partition_point(|&start| start <= seq)
    }
}

/// Builds partition `index` from `spill_file`, writing sealed blocks to `blocks_file`.
///
/// Rows of one concept are put in the order the Java loader ingests them: file order in
/// new-search and partitioned mode; chunk, then raw concept path in Java string order,
/// then file order in sequential mode. The first row in that order fixes the concept's type. The spill file is
/// deleted once read. The partition's spill file and its decoded rows (40 bytes each) are
/// held in memory together while it builds.
///
/// # Errors
/// I/O errors, a corrupt spill file, and encoding or sealing errors.
pub(crate) fn build_partition(
    ctx: &BuildContext<'_>,
    index: usize,
    spill_file: &Path,
    blocks_file: PathBuf,
) -> Result<BuiltPartition> {
    let buf = std::fs::read(spill_file).map_err(EtlError::io("reading", spill_file))?;
    std::fs::remove_file(spill_file).map_err(EtlError::io("removing", spill_file))?;
    let mut rows = read_rows(&buf, ctx.mode, ctx.raw_path_ranks)
        .map_err(EtlError::io("decoding", spill_file))?;
    rows.sort_unstable_by_key(|r| (r.concept, r.seq));
    if ctx.mode == Mode::Sequential {
        for concept_rows in rows.chunk_by_mut(|a, b| a.concept == b.concept) {
            for chunk_rows in
                concept_rows.chunk_by_mut(|a, b| ctx.chunk_of(a.seq) == ctx.chunk_of(b.seq))
            {
                chunk_rows.sort_by_key(|r| r.raw_path_rank);
            }
        }
    }

    let file =
        private_fs::create_file(&blocks_file).map_err(EtlError::io("creating", &blocks_file))?;
    let mut out = BufWriter::with_capacity(1 << 20, file);
    let mut written = 0u64;
    let mut built = BuiltPartition {
        concepts: Vec::new(),
        blocks_file,
        patients: RoaringBitmap::new(),
        counts: LoadCounts::default(),
        patient_failure: None,
    };
    for group in rows.chunk_by(|a, b| a.concept == b.concept) {
        let path = &ctx.concepts[group[0].concept as usize];
        let (mut meta, payload) = build_concept(ctx.mode, path, group, &buf, &mut built)?;
        let stored = ctx.codec.seal(path, &payload)?;
        out.write_all(&stored)
            .map_err(EtlError::io("writing", &built.blocks_file))?;
        meta.block = BlockLocation {
            offset: written,
            length: stored.len() as u64,
        };
        written += stored.len() as u64;
        built.concepts.push(BuiltConcept {
            meta,
            partition: index,
        });
    }
    out.into_inner()
        .map_err(|e| EtlError::io("writing", &built.blocks_file)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("writing", &built.blocks_file))?;
    Ok(built)
}

/// Running statistics over a concept's accepted observations.
struct Stats {
    patients: RoaringBitmap,
    observations: u32,
    timestamp_min: Option<i64>,
    timestamp_max: Option<i64>,
}

impl Stats {
    fn new() -> Self {
        Stats {
            patients: RoaringBitmap::new(),
            observations: 0,
            timestamp_min: None,
            timestamp_max: None,
        }
    }

    fn add(&mut self, patient: u32, timestamp: i64) {
        self.patients.insert(patient);
        self.observations += 1;
        if timestamp != NO_TIMESTAMP {
            self.timestamp_min = Some(self.timestamp_min.map_or(timestamp, |t| t.min(timestamp)));
            self.timestamp_max = Some(self.timestamp_max.map_or(timestamp, |t| t.max(timestamp)));
        }
    }
}

/// Decides one row of a concept whose type is `text`: returns true when the row is stored,
/// counting the reason otherwise, and records the first patient failure outside sequential
/// mode.
fn accept(mode: Mode, text: bool, row: &SpillRow, built: &mut BuiltPartition) -> bool {
    if row.is_text() != text {
        built.counts.dropped_type_conflict += 1;
        return false;
    }
    if row.status() == ValueStatus::Blank {
        built.counts.dropped_blank_value += 1;
        return false;
    }
    if mode != Mode::Sequential && row.bad_patient() {
        if built
            .patient_failure
            .as_ref()
            .is_none_or(|f| row.seq < f.seq)
        {
            built.patient_failure = Some(PatientFailure {
                seq: row.seq,
                line: row.line(),
            });
        }
        return false;
    }
    if row.status() == ValueStatus::Unparseable {
        built.counts.dropped_unparseable_number += 1;
        return false;
    }
    if row.bad_timestamp() {
        built.counts.unparseable_timestamps += 1;
    }
    built.counts.accepted += 1;
    true
}

fn build_concept(
    mode: Mode,
    path: &str,
    rows: &[SpillRow],
    buf: &[u8],
    built: &mut BuiltPartition,
) -> Result<(ConceptMeta, Vec<u8>)> {
    let text = rows[0].is_text();
    let mut stats = Stats::new();
    let (meta, payload) = if text {
        let mut ids: FxHashMap<&str, u32> = FxHashMap::default();
        let mut values: Vec<&str> = Vec::new();
        let mut observations = Vec::new();
        let mut width = 0usize;
        for row in rows {
            if !accept(mode, text, row, built) {
                continue;
            }
            let value = row.text(buf);
            width = width.max(value.len());
            let value_id = *ids.entry(value).or_insert_with(|| {
                values.push(value);
                (values.len() - 1) as u32
            });
            stats.add(row.patient, row.timestamp);
            observations.push(CategoricalRow {
                patient: row.patient,
                value_id,
                timestamp: row.timestamp,
            });
        }
        let payload = encode_interned_categorical_payload(&values, &observations)?;
        let mut category_values: Vec<String> = values.iter().map(|v| v.to_string()).collect();
        category_values.sort_unstable_by(|a, b| java_string_cmp(a, b));
        let meta = concept_meta(path, ConceptType::Categorical, &stats, width as i32);
        (
            ConceptMeta {
                category_values: Some(category_values),
                ..meta
            },
            payload,
        )
    } else {
        let mut observations = Vec::new();
        let mut range: Option<(f64, f64)> = None;
        for row in rows {
            if !accept(mode, text, row, built) {
                continue;
            }
            stats.add(row.patient, row.timestamp);
            let number = row.number();
            range = Some(match range {
                None => (number, number),
                Some((lo, hi)) => (java_double_min(lo, number), java_double_max(hi, number)),
            });
            observations.push(NumericObservation {
                patient: row.patient,
                value: number,
                timestamp: row.timestamp,
            });
        }
        let width = match mode {
            Mode::Sequential => 8,
            Mode::NewSearch | Mode::Partitioned if observations.is_empty() => 0,
            Mode::NewSearch | Mode::Partitioned => 8,
        };
        let payload = encode_numeric_payload(&observations)?;
        let meta = concept_meta(path, ConceptType::Numeric, &stats, width);
        (
            ConceptMeta {
                min: range.map(|r| r.0),
                max: range.map(|r| r.1),
                ..meta
            },
            payload,
        )
    };
    built.patients |= &stats.patients;
    built.counts.concepts += 1;
    if meta.observation_count == 0 {
        built.counts.empty_concepts += 1;
    }
    match meta.concept_type {
        ConceptType::Numeric => built.counts.numeric_concepts += 1,
        ConceptType::Categorical => built.counts.categorical_concepts += 1,
    }
    Ok((meta, payload))
}

fn concept_meta(
    path: &str,
    concept_type: ConceptType,
    stats: &Stats,
    width_in_bytes: i32,
) -> ConceptMeta {
    ConceptMeta {
        path: path.to_string(),
        concept_type,
        category_values: None,
        min: None,
        max: None,
        observation_count: stats.observations,
        patient_count: stats.patients.len() as u32,
        has_timestamp: stats.timestamp_min.is_some(),
        timestamp_min: stats.timestamp_min,
        timestamp_max: stats.timestamp_max,
        width_in_bytes,
        column_offset: 0,
        block: BlockLocation::default(),
    }
}
