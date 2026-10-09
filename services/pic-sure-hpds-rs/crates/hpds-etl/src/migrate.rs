//! `hpds-etl migrate`: a javabin store, exported by the Java `ExportForMigration` loader, to
//! a new store.
//!
//! The export holds every metastore entry in raw key order with its `ColumnMeta` fields and
//! its cube's observations in `sortedByKey` order, plus the patient ID set (see
//! [`EXPORT_CONCEPTS_FILE`] for the layout). Migration applies no path normalization and no
//! rollup. It keeps:
//!
//! - every observation, in the exported order, so within a patient the stored order of the
//!   javabin survives exactly;
//! - the concept type, observation count, patient count, category values, width and column
//!   offset Java recorded, after checking that they agree with the observations (the
//!   counts and category values) and refusing the concept when they do not;
//! - the patient ID set from `patients.txt`, not the patients seen in the observations.
//!
//! It recomputes the numeric min and max and the timestamp statistics from the
//! observations, the deliberate divergence the Rust loader also makes (Java's LowRAM loader
//! reports a max of `Double.MIN_VALUE` for all-negative concepts and never sets timestamp
//! statistics). A `Long.MIN_VALUE` timestamp and a null one both become no timestamp.
//!
//! Concept paths have U+FFFD removed, as `PhenotypeMetaStore` removes it from every key on
//! load, so the store holds the paths the Java service answers to. When two raw keys strip
//! to the same path, the later one in raw key order wins, as the Java service's `TreeMap`
//! put does; the collision is logged and reported, and the losing block stays in
//! `pheno.blocks` unreferenced.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hpds_format::{
    BLOCKS_FILE_NAME, BlockCodec, BlockLocation, BlocksWriter, CategoricalRow, ConceptMeta,
    ConceptType, IndexHeader, NO_TIMESTAMP, NumericObservation, PhenoIndex, Protection, StoreId,
    encode_interned_categorical_payload, encode_numeric_payload, java_string_cmp,
};
use rayon::prelude::*;
use roaring::RoaringBitmap;
use rustc_hash::FxHashMap;
use serde::Serialize;

use crate::error::{EtlError, Result};
use crate::java::{java_double_max, java_double_min};
use crate::load::{
    DEFAULT_KEY_NAME, KeySource, REPORT_FILE_NAME, Staging, check_output,
    write_index_and_column_meta,
};
use crate::private_fs;
use crate::store::resolve_protection;

/// Name of the export file holding the concepts and their observations.
///
/// A big-endian stream: the magic [`EXPORT_MAGIC`], an `i32` version
/// ([`EXPORT_VERSION`]), then one record per metastore entry in raw key order, each a tag
/// byte `1` followed by:
///
/// ```text
/// key            string   the metastore key as stored
/// key_flags      u8       bit 0: the key contains U+FFFD
/// value_type     u8       0 Double cube, 1 String cube
/// name           string   ColumnMeta.name
/// width          i32      ColumnMeta.widthInBytes
/// column_offset  i32      ColumnMeta.columnOffset
/// categorical    u8       ColumnMeta.categorical
/// category_count i32      -1 when categoryValues is null, then that many strings
/// min, max       nullable f64
/// obs_offset     i64      ColumnMeta.allObservationsOffset
/// obs_end        i64      ColumnMeta.allObservationsLength
/// obs_count      i32      ColumnMeta.observationCount
/// patient_count  i32      ColumnMeta.patientCount
/// has_timestamp  u8       ColumnMeta.hasTimestamp
/// ts_min, ts_max nullable i64
/// cube_name      string   PhenoCube.name
/// column_width   i32      PhenoCube.columnWidth
/// n              i32      observations, then n times:
///   patient      i32
///   value        i64 (raw bits of the double) for a Double cube, string for a String cube
///   timestamp    nullable i64
/// ```
///
/// and finally a tag byte `0`, the `i64` number of records and the `i64` number of
/// observations. A string is an `i32` UTF-8 byte length (`-1` for null) and the bytes; a
/// nullable value is a byte, `0` for null or `1` followed by the value.
pub const EXPORT_CONCEPTS_FILE: &str = "concepts.bin";

/// Name of the export file holding the patient ID set, one decimal ID per line.
pub const EXPORT_PATIENTS_FILE: &str = "patients.txt";

/// Magic bytes at the start of [`EXPORT_CONCEPTS_FILE`].
pub const EXPORT_MAGIC: &[u8; 8] = b"HPDSMIG1";

/// The [`EXPORT_CONCEPTS_FILE`] layout version this reads.
pub const EXPORT_VERSION: i32 = 1;

const BATCH_OBSERVATIONS: usize = 8 << 20;

/// Everything `hpds-etl migrate` needs.
#[derive(Clone, Debug)]
pub struct MigrateOptions {
    /// Directory holding [`EXPORT_CONCEPTS_FILE`] and [`EXPORT_PATIENTS_FILE`].
    pub export: PathBuf,
    /// The store directory to create or replace.
    pub out: PathBuf,
    /// Where the key comes from; [`KeySource::OutputDirectoryKeyFile`] reads `<out>/encryption_key`.
    pub key: KeySource,
    /// Worker threads for sealing blocks; `None` uses one per core.
    pub threads: Option<usize>,
}

/// Two raw keys that strip to the same path. The later one in raw key order is kept.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct KeyCollision {
    /// The path both keys strip to.
    pub path: String,
    /// The raw key whose concept was replaced.
    pub replaced: String,
    /// The raw key whose concept was kept.
    pub kept: String,
}

/// What a migration did, written to `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct MigrateReport {
    /// The export directory read.
    pub export: PathBuf,
    /// Whether the store is encrypted.
    pub encrypted: bool,
    /// The store id written into the index and blocks.
    pub store_id: String,
    /// Metastore entries read from the export.
    pub exported_concepts: u64,
    /// Concepts in the store.
    pub concepts: u64,
    /// Numeric concepts in the store.
    pub numeric_concepts: u64,
    /// Categorical concepts in the store.
    pub categorical_concepts: u64,
    /// Observations in the store.
    pub observations: u64,
    /// Patients in the store's patient ID set.
    pub patients: u64,
    /// Raw keys that contained U+FFFD.
    pub keys_with_replacement_character: u64,
    /// Raw keys that stripped to the same path as an earlier one.
    pub collisions: Vec<KeyCollision>,
    /// Patients with an observation that are missing from the exported patient ID set.
    pub observed_patients_missing_from_set: u64,
    /// Patients in the exported patient ID set with no observation in any stored concept.
    pub patients_without_observations: u64,
    /// Size of `pheno.blocks` in bytes.
    pub blocks_bytes: u64,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// Migrates the export at `options.export` into a new store at `options.out`.
///
/// The store is written to a staging directory beside `out` and moved into place as
/// [`crate::load::load`] does, so `out` never holds an index that does not match its
/// blocks.
///
/// # Errors
/// [`EtlError::Usage`] for a missing export or unreadable key; [`EtlError::Export`] for a
/// malformed or truncated export, a negative patient ID, observations not ordered by
/// patient, a type or count that disagrees with the observations, or a null value; I/O and
/// format errors.
pub fn migrate(options: &MigrateOptions) -> Result<MigrateReport> {
    let started = Instant::now();
    let concepts_path = options.export.join(EXPORT_CONCEPTS_FILE);
    let patients_path = options.export.join(EXPORT_PATIENTS_FILE);
    for path in [&concepts_path, &patients_path] {
        if !path.is_file() {
            return Err(EtlError::Usage(format!(
                "{} does not exist",
                path.display()
            )));
        }
    }
    check_output(&options.out, &options.key)?;
    let protection = resolve_protection(&options.key, &options.out)?;
    let patients = read_patients(&patients_path)?;
    let pool = {
        let mut builder = rayon::ThreadPoolBuilder::new();
        if let Some(threads) = options.threads {
            builder = builder.num_threads(threads);
        }
        builder
            .build()
            .map_err(|e| EtlError::ThreadPool(e.to_string()))?
    };

    let staging = Staging::create(&options.out)?;
    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(protection.clone(), store_id, true);
    let blocks_path = staging.path().join(BLOCKS_FILE_NAME);
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer = BlocksWriter::new(BufWriter::with_capacity(4 << 20, file), store_id)?;

    let mut reader = ExportReader::open(&concepts_path)?;
    let mut state = MigrateState::default();
    let mut batch: Vec<ExportedConcept> = Vec::new();
    let mut batch_observations = 0usize;
    while let Some(concept) = reader.next_concept()? {
        batch_observations += concept.observations.len();
        batch.push(concept);
        if batch_observations >= BATCH_OBSERVATIONS {
            seal_batch(
                &pool,
                &codec,
                &mut batch,
                &mut writer,
                &mut state,
                &concepts_path,
            )?;
            batch_observations = 0;
        }
    }
    seal_batch(
        &pool,
        &codec,
        &mut batch,
        &mut writer,
        &mut state,
        &concepts_path,
    )?;
    let (exported_concepts, exported_observations) = reader.trailer;
    if exported_concepts != state.records {
        return Err(EtlError::Export {
            path: concepts_path,
            detail: format!(
                "trailer counts {exported_concepts} records, {} were read",
                state.records
            ),
        });
    }
    if exported_observations != state.records_observations {
        return Err(EtlError::Export {
            path: concepts_path,
            detail: format!(
                "trailer counts {exported_observations} observations, {} were read",
                state.records_observations
            ),
        });
    }

    let blocks_bytes = writer.len();
    writer
        .finish()?
        .into_inner()
        .map_err(|e| EtlError::io("writing", &blocks_path)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("writing", &blocks_path))?;

    let mut observed = RoaringBitmap::new();
    let metas: Vec<ConceptMeta> = state
        .concepts
        .into_iter()
        .map(|kept| {
            observed |= &kept.patients;
            kept.meta
        })
        .collect();
    let mut report = MigrateReport {
        export: options.export.clone(),
        encrypted: protection.is_encrypted(),
        store_id: store_id.to_string(),
        exported_concepts,
        concepts: metas.len() as u64,
        numeric_concepts: metas.iter().filter(|m| !m.is_categorical()).count() as u64,
        categorical_concepts: metas.iter().filter(|m| m.is_categorical()).count() as u64,
        observations: metas.iter().map(|m| u64::from(m.observation_count)).sum(),
        patients: patients.len(),
        keys_with_replacement_character: state.keys_with_replacement_character,
        collisions: state.collisions,
        observed_patients_missing_from_set: (&observed - &patients).len(),
        patients_without_observations: (&patients - &observed).len(),
        blocks_bytes,
        elapsed_seconds: 0.0,
    };
    if report.observed_patients_missing_from_set > 0 {
        tracing::warn!(
            count = report.observed_patients_missing_from_set,
            "patients with observations are missing from the exported patient ID set"
        );
    }
    write_index_and_csv(staging.path(), store_id, patients, metas, &protection)?;
    report.elapsed_seconds = started.elapsed().as_secs_f64();
    let report_path = staging.path().join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    private_fs::write_file(&report_path, &json).map_err(EtlError::io("writing", &report_path))?;
    staging.publish(&options.out)?;
    tracing::info!(
        out = %options.out.display(),
        concepts = report.concepts,
        observations = report.observations,
        patients = report.patients,
        collisions = report.collisions.len(),
        seconds = report.elapsed_seconds,
        "store migrated"
    );
    Ok(report)
}

fn write_index_and_csv(
    dir: &Path,
    store_id: StoreId,
    patients: RoaringBitmap,
    metas: Vec<ConceptMeta>,
    protection: &Protection,
) -> Result<()> {
    let header = IndexHeader {
        format_version: hpds_format::FORMAT_VERSION,
        encrypted: protection.is_encrypted(),
        compressed: true,
        key_name: if protection.is_encrypted() {
            DEFAULT_KEY_NAME.into()
        } else {
            String::new()
        },
        created_at_millis: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0),
        etl_version: crate::banner(),
        store_id,
    };
    let index = PhenoIndex::new(header, patients, metas)?;
    write_index_and_column_meta(dir, &index, protection)
}

fn read_patients(path: &Path) -> Result<RoaringBitmap> {
    let file = File::open(path).map_err(EtlError::io("opening", path))?;
    let mut patients = RoaringBitmap::new();
    for (number, line) in BufReader::new(file).lines().enumerate() {
        let line = line.map_err(EtlError::io("reading", path))?;
        let invalid = |problem: &str| EtlError::Export {
            path: path.to_path_buf(),
            detail: format!("line {}: patient {line:?} {problem}", number + 1),
        };
        let id: i32 = line.parse().map_err(|_| invalid("is not an int"))?;
        let id = u32::try_from(id).map_err(|_| invalid("is negative"))?;
        patients.insert(id);
    }
    Ok(patients)
}

/// A concept kept in the store, with the patients its observations cover.
struct KeptConcept {
    raw_key: String,
    meta: ConceptMeta,
    patients: RoaringBitmap,
}

#[derive(Default)]
struct MigrateState {
    concepts: Vec<KeptConcept>,
    positions: HashMap<String, usize>,
    collisions: Vec<KeyCollision>,
    keys_with_replacement_character: u64,
    records: u64,
    records_observations: u64,
}

/// Validates, encodes and seals a batch of concepts in parallel, then appends them in
/// export order.
fn seal_batch<W: Write>(
    pool: &rayon::ThreadPool,
    codec: &BlockCodec,
    batch: &mut Vec<ExportedConcept>,
    writer: &mut BlocksWriter<W>,
    state: &mut MigrateState,
    export_path: &Path,
) -> Result<()> {
    let sealed: Vec<(ExportedConcept, ConceptMeta, RoaringBitmap, Vec<u8>)> =
        pool.install(|| {
            batch
                .par_drain(..)
                .map(|concept| {
                    let (meta, patients, payload) = build_concept(&concept, export_path)?;
                    let stored = codec.seal(&meta.path, &payload)?;
                    Ok((concept, meta, patients, stored))
                })
                .collect::<Result<Vec<_>>>()
        })?;
    for (concept, mut meta, patients, stored) in sealed {
        state.records += 1;
        state.records_observations += concept.observations.len() as u64;
        if concept.key_has_replacement_character {
            state.keys_with_replacement_character += 1;
        }
        meta.block = writer.append_stored(&stored)?;
        let kept = KeptConcept {
            raw_key: concept.key,
            meta,
            patients,
        };
        match state.positions.get(&kept.meta.path) {
            Some(&position) => {
                let replaced = std::mem::replace(&mut state.concepts[position], kept);
                let kept = &state.concepts[position];
                tracing::warn!(
                    path = %kept.meta.path,
                    replaced = %replaced.raw_key,
                    kept = %kept.raw_key,
                    "two keys strip to the same path; keeping the later one as Java does"
                );
                state.collisions.push(KeyCollision {
                    path: kept.meta.path.clone(),
                    replaced: replaced.raw_key,
                    kept: kept.raw_key.clone(),
                });
            }
            None => {
                state
                    .positions
                    .insert(kept.meta.path.clone(), state.concepts.len());
                state.concepts.push(kept);
            }
        }
    }
    Ok(())
}

/// Checks one exported concept against its recorded metadata and builds its metadata and
/// block payload.
fn build_concept(
    concept: &ExportedConcept,
    export_path: &Path,
) -> Result<(ConceptMeta, RoaringBitmap, Vec<u8>)> {
    let refuse = |detail: String| EtlError::Export {
        path: export_path.to_path_buf(),
        detail: format!("concept {:?}: {detail}", concept.key),
    };
    let meta = &concept.meta;
    let text = matches!(concept.observations, Observations::Text { .. });
    if meta.categorical != text {
        return Err(refuse(format!(
            "ColumnMeta.categorical is {} but the cube holds {} values",
            meta.categorical,
            if text { "String" } else { "Double" }
        )));
    }
    let mut patients = RoaringBitmap::new();
    let mut previous: Option<u32> = None;
    let mut timestamp_range: Option<(i64, i64)> = None;
    let mut track = |patient: i32, timestamp: i64| -> Result<u32> {
        let patient = u32::try_from(patient)
            .map_err(|_| refuse(format!("patient number {patient} is negative")))?;
        if previous.is_some_and(|p| p > patient) {
            return Err(refuse(format!(
                "observations are not ordered by patient ({} before {patient})",
                previous.unwrap_or_default()
            )));
        }
        previous = Some(patient);
        patients.insert(patient);
        if timestamp != NO_TIMESTAMP {
            timestamp_range = Some(match timestamp_range {
                None => (timestamp, timestamp),
                Some((lo, hi)) => (lo.min(timestamp), hi.max(timestamp)),
            });
        }
        Ok(patient)
    };

    let mut range: Option<(f64, f64)> = None;
    let payload = match &concept.observations {
        Observations::Numeric(rows) => {
            let mut observations = Vec::with_capacity(rows.len());
            for row in rows {
                let patient = track(row.patient, row.timestamp)?;
                range = Some(match range {
                    None => (row.value, row.value),
                    Some((lo, hi)) => (
                        java_double_min(lo, row.value),
                        java_double_max(hi, row.value),
                    ),
                });
                observations.push(NumericObservation {
                    patient,
                    value: row.value,
                    timestamp: row.timestamp,
                });
            }
            encode_numeric_payload(&observations)?
        }
        Observations::Text { values, rows } => {
            let mut categorical = Vec::with_capacity(rows.len());
            for row in rows {
                let patient = track(row.patient, row.timestamp)?;
                categorical.push(CategoricalRow {
                    patient,
                    value_id: row.value_id,
                    timestamp: row.timestamp,
                });
            }
            let mut distinct: Vec<&str> = values.iter().map(String::as_str).collect();
            distinct.sort_unstable_by(|a, b| java_string_cmp(a, b));
            let recorded: Option<Vec<&str>> = meta
                .category_values
                .as_ref()
                .map(|v| v.iter().map(String::as_str).collect());
            if recorded.as_ref() != Some(&distinct) {
                return Err(refuse(format!(
                    "ColumnMeta.categoryValues {recorded:?} are not the distinct values {distinct:?}"
                )));
            }
            encode_interned_categorical_payload(values, &categorical)?
        }
    };
    let observation_count = concept.observations.len();
    if i64::from(meta.observation_count) != observation_count as i64 {
        return Err(refuse(format!(
            "ColumnMeta.observationCount is {} but the cube holds {observation_count} observations",
            meta.observation_count
        )));
    }
    if i64::from(meta.patient_count) != patients.len() as i64 {
        return Err(refuse(format!(
            "ColumnMeta.patientCount is {} but the cube covers {} patients",
            meta.patient_count,
            patients.len()
        )));
    }
    if !text && meta.category_values.is_some() {
        return Err(refuse(
            "a numeric concept has ColumnMeta.categoryValues".into(),
        ));
    }
    let concept_meta = ConceptMeta {
        path: concept.key.replace('\u{FFFD}', ""),
        concept_type: if text {
            ConceptType::Categorical
        } else {
            ConceptType::Numeric
        },
        category_values: meta.category_values.clone(),
        min: range.map(|r| r.0),
        max: range.map(|r| r.1),
        observation_count: observation_count as u32,
        patient_count: patients.len() as u32,
        has_timestamp: timestamp_range.is_some(),
        timestamp_min: timestamp_range.map(|r| r.0),
        timestamp_max: timestamp_range.map(|r| r.1),
        width_in_bytes: meta.width_in_bytes,
        column_offset: meta.column_offset,
        block: BlockLocation::default(),
    };
    Ok((concept_meta, patients, payload))
}

/// The `ColumnMeta` fields of one exported concept that migration uses.
struct ExportedMeta {
    width_in_bytes: i32,
    column_offset: i32,
    categorical: bool,
    category_values: Option<Vec<String>>,
    observation_count: i32,
    patient_count: i32,
}

struct NumericRow {
    patient: i32,
    value: f64,
    timestamp: i64,
}

struct TextRow {
    patient: i32,
    value_id: u32,
    timestamp: i64,
}

enum Observations {
    Numeric(Vec<NumericRow>),
    Text {
        values: Vec<String>,
        rows: Vec<TextRow>,
    },
}

impl Observations {
    fn len(&self) -> usize {
        match self {
            Observations::Numeric(rows) => rows.len(),
            Observations::Text { rows, .. } => rows.len(),
        }
    }
}

/// One metastore entry of the export.
struct ExportedConcept {
    key: String,
    key_has_replacement_character: bool,
    meta: ExportedMeta,
    observations: Observations,
}

/// Streams [`EXPORT_CONCEPTS_FILE`].
struct ExportReader {
    path: PathBuf,
    input: BufReader<File>,
    trailer: (u64, u64),
}

impl ExportReader {
    fn open(path: &Path) -> Result<Self> {
        let file = File::open(path).map_err(EtlError::io("opening", path))?;
        let mut reader = ExportReader {
            path: path.to_path_buf(),
            input: BufReader::with_capacity(4 << 20, file),
            trailer: (0, 0),
        };
        let mut magic = [0u8; 8];
        reader.bytes(&mut magic)?;
        if &magic != EXPORT_MAGIC {
            return Err(reader.malformed("does not start with HPDSMIG1"));
        }
        let version = reader.i32()?;
        if version != EXPORT_VERSION {
            return Err(reader.malformed(&format!("unsupported version {version}")));
        }
        Ok(reader)
    }

    fn malformed(&self, detail: &str) -> EtlError {
        EtlError::Export {
            path: self.path.clone(),
            detail: detail.to_string(),
        }
    }

    fn bytes(&mut self, buf: &mut [u8]) -> Result<()> {
        self.input.read_exact(buf).map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                self.malformed("ends early; the export is incomplete")
            } else {
                EtlError::io("reading", &self.path)(e)
            }
        })
    }

    fn u8(&mut self) -> Result<u8> {
        let mut b = [0u8; 1];
        self.bytes(&mut b)?;
        Ok(b[0])
    }

    fn bool(&mut self) -> Result<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            other => Err(self.malformed(&format!("flag byte {other} is not 0 or 1"))),
        }
    }

    fn i32(&mut self) -> Result<i32> {
        let mut b = [0u8; 4];
        self.bytes(&mut b)?;
        Ok(i32::from_be_bytes(b))
    }

    fn i64(&mut self) -> Result<i64> {
        let mut b = [0u8; 8];
        self.bytes(&mut b)?;
        Ok(i64::from_be_bytes(b))
    }

    fn nullable_i64(&mut self) -> Result<Option<i64>> {
        Ok(if self.bool()? {
            Some(self.i64()?)
        } else {
            None
        })
    }

    fn nullable_f64(&mut self) -> Result<Option<f64>> {
        Ok(self.nullable_i64()?.map(|bits| f64::from_bits(bits as u64)))
    }

    fn nullable_string(&mut self) -> Result<Option<String>> {
        let len = self.i32()?;
        if len == -1 {
            return Ok(None);
        }
        let len = usize::try_from(len)
            .map_err(|_| self.malformed(&format!("string length {len} is negative")))?;
        let mut buf = vec![0u8; len];
        self.bytes(&mut buf)?;
        String::from_utf8(buf)
            .map(Some)
            .map_err(|_| self.malformed("a string is not UTF-8"))
    }

    fn string(&mut self, what: &str) -> Result<String> {
        self.nullable_string()?
            .ok_or_else(|| self.malformed(&format!("{what} is null")))
    }

    fn count(&mut self, what: &str) -> Result<usize> {
        let n = self.i32()?;
        usize::try_from(n).map_err(|_| self.malformed(&format!("{what} {n} is negative")))
    }

    /// Reads the next concept, or `None` at the end record, whose counts it keeps in
    /// `trailer`.
    fn next_concept(&mut self) -> Result<Option<ExportedConcept>> {
        match self.u8()? {
            0 => {
                let concepts = self.i64()?;
                let observations = self.i64()?;
                self.trailer = (concepts as u64, observations as u64);
                let mut rest = [0u8; 1];
                if self
                    .input
                    .read(&mut rest)
                    .map_err(EtlError::io("reading", &self.path))?
                    != 0
                {
                    return Err(self.malformed("holds bytes after the end record"));
                }
                return Ok(None);
            }
            1 => {}
            other => return Err(self.malformed(&format!("unknown record tag {other}"))),
        }
        let key = self.string("a metastore key")?;
        let key_has_replacement_character = self.u8()? & 1 == 1;
        if key_has_replacement_character != key.contains('\u{FFFD}') {
            return Err(self.malformed(&format!(
                "the U+FFFD flag of key {key:?} disagrees with the key"
            )));
        }
        let text = match self.u8()? {
            0 => false,
            1 => true,
            other => return Err(self.malformed(&format!("unknown value type {other}"))),
        };
        let _name = self.nullable_string()?;
        let width_in_bytes = self.i32()?;
        let column_offset = self.i32()?;
        let categorical = self.bool()?;
        let category_count = self.i32()?;
        let category_values = if category_count == -1 {
            None
        } else {
            let n = usize::try_from(category_count).map_err(|_| {
                self.malformed(&format!("category count {category_count} is negative"))
            })?;
            let mut values = Vec::with_capacity(n.min(1 << 20));
            for _ in 0..n {
                values.push(self.string("a category value")?);
            }
            Some(values)
        };
        let _min = self.nullable_f64()?;
        let _max = self.nullable_f64()?;
        let _offset = self.i64()?;
        let _end = self.i64()?;
        let observation_count = self.i32()?;
        let patient_count = self.i32()?;
        let _has_timestamp = self.bool()?;
        let _timestamp_min = self.nullable_i64()?;
        let _timestamp_max = self.nullable_i64()?;
        let _cube_name = self.nullable_string()?;
        let _column_width = self.i32()?;
        let n = self.count("observation count")?;
        let observations = if text {
            let mut ids: FxHashMap<String, u32> = FxHashMap::default();
            let mut values = Vec::new();
            let mut rows = Vec::with_capacity(n.min(1 << 24));
            for _ in 0..n {
                let patient = self.i32()?;
                let value = self.string("a value")?;
                let timestamp = self.nullable_i64()?.unwrap_or(NO_TIMESTAMP);
                let value_id = match ids.get(&value) {
                    Some(&id) => id,
                    None => {
                        let id = values.len() as u32;
                        values.push(value.clone());
                        ids.insert(value, id);
                        id
                    }
                };
                rows.push(TextRow {
                    patient,
                    value_id,
                    timestamp,
                });
            }
            Observations::Text { values, rows }
        } else {
            let mut rows = Vec::with_capacity(n.min(1 << 24));
            for _ in 0..n {
                let patient = self.i32()?;
                let value = f64::from_bits(self.i64()? as u64);
                let timestamp = self.nullable_i64()?.unwrap_or(NO_TIMESTAMP);
                rows.push(NumericRow {
                    patient,
                    value,
                    timestamp,
                });
            }
            Observations::Numeric(rows)
        };
        Ok(Some(ExportedConcept {
            key,
            key_has_replacement_character,
            meta: ExportedMeta {
                width_in_bytes,
                column_offset,
                categorical,
                category_values,
                observation_count,
                patient_count,
            },
            observations,
        }))
    }
}
