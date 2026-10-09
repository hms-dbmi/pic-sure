//! `hpds-etl migrate`: a javabin store, exported by the Java `ExportForMigration` loader, to
//! a new store.
//!
//! The export holds every metastore entry in raw key order with its `ColumnMeta` fields and
//! its cube's observations in `sortedByKey` order, plus the patient ID set, both encrypted
//! with the javabin store's key (see [`EXPORT_CONCEPTS_FILE`] for the layout). Migration
//! applies no path normalization and no rollup. It keeps:
//!
//! - every observation, in the exported order, so within a patient the stored order of the
//!   javabin survives exactly;
//! - the concept type, observation count, patient count, category values, width and column
//!   offset Java recorded, after checking that they agree with the observations (the
//!   counts and category values). A concept that disagrees stops the migration, or with
//!   [`MigrateOptions::skip_inconsistent`] is left out and listed in the report;
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
//!
//! No error or log line names an observation value, a category value or a patient number.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlockLocation, BlocksReader, BlocksWriter, CategoricalRow,
    ConceptMeta, ConceptType, CryptoKey, FormatError, IndexHeader, NO_TIMESTAMP,
    NumericObservation, PhenoIndex, Protection, StoreId, decrypt_java_block,
    encode_interned_categorical_payload, encode_numeric_payload, encrypt_java_block,
    java_string_cmp,
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
/// Both export files are encrypted containers, written by `ExportForMigration`:
///
/// ```text
/// magic        8 bytes   EXPORT_MAGIC
/// version      i32       EXPORT_VERSION
/// export_id    16 bytes  random, the same in both files of one export
/// frames until the one marked final, each:
///   length     i32       byte length of the frame
///   frame      Crypto.encryptData(plaintext): i32 ivLength, iv, ciphertext and tag
/// ```
///
/// A frame's plaintext is `export_id`, a kind byte ([`ExportKind`]), the `i64` frame index
/// from 0, a final byte (1 on the last frame), then a slice of the file's content. Every
/// integer is big-endian. The frames are sealed with the javabin store's key and no
/// associated data, so [`decrypt_java_block`] opens them.
///
/// The content of this file is one record per metastore entry in raw key order, each a
/// tag byte `1` followed by:
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
/// obs_end        i64      ColumnMeta.allObservationsLength, the end position in the javabin
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

/// Name of the export file holding the patient ID set. Its content is one decimal ID per
/// line, in the container described at [`EXPORT_CONCEPTS_FILE`].
pub const EXPORT_PATIENTS_FILE: &str = "patients.txt";

/// Magic bytes at the start of both export files.
pub const EXPORT_MAGIC: &[u8; 8] = b"HPDSMIG2";

/// The export layout version this reads.
pub const EXPORT_VERSION: i32 = 2;

/// Length of the random id shared by the two files of one export.
pub const EXPORT_ID_LEN: usize = 16;

/// Largest frame accepted, well above the 8 MiB of content Java puts in one.
const MAX_FRAME_LEN: usize = 64 << 20;

const FRAME_HEADER_LEN: usize = EXPORT_ID_LEN + 1 + 8 + 1;

const BATCH_OBSERVATIONS: usize = 8 << 20;

/// Which export file a frame belongs to, the kind byte of its plaintext.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ExportKind {
    /// A frame of [`EXPORT_CONCEPTS_FILE`].
    Concepts = 1,
    /// A frame of [`EXPORT_PATIENTS_FILE`].
    Patients = 2,
}

/// Seals `content` into one export file as `ExportForMigration` writes it, in frames of at
/// most `frame_content` bytes. Used by tests and tools that build exports without Java.
///
/// # Errors
/// [`EtlError::Format`] when encryption fails, [`EtlError::Usage`] when `frame_content` is
/// zero.
pub fn seal_export(
    key: &CryptoKey,
    export_id: [u8; EXPORT_ID_LEN],
    kind: ExportKind,
    content: &[u8],
    frame_content: usize,
) -> Result<Vec<u8>> {
    if frame_content == 0 {
        return Err(EtlError::Usage("frames must carry content".into()));
    }
    let mut out = Vec::with_capacity(content.len() + 64);
    out.extend_from_slice(EXPORT_MAGIC);
    out.extend_from_slice(&EXPORT_VERSION.to_be_bytes());
    out.extend_from_slice(&export_id);
    let mut chunks: Vec<&[u8]> = content.chunks(frame_content).collect();
    if chunks.is_empty() {
        chunks.push(&[]);
    }
    let last = chunks.len() - 1;
    for (index, chunk) in chunks.into_iter().enumerate() {
        let mut plaintext = Vec::with_capacity(FRAME_HEADER_LEN + chunk.len());
        plaintext.extend_from_slice(&export_id);
        plaintext.push(kind as u8);
        plaintext.extend_from_slice(&(index as i64).to_be_bytes());
        plaintext.push(u8::from(index == last));
        plaintext.extend_from_slice(chunk);
        let frame = encrypt_java_block(key, &plaintext)?;
        out.extend_from_slice(&(frame.len() as i32).to_be_bytes());
        out.extend_from_slice(&frame);
    }
    Ok(out)
}

/// Everything `hpds-etl migrate` needs.
#[derive(Clone, Debug)]
pub struct MigrateOptions {
    /// Directory holding [`EXPORT_CONCEPTS_FILE`] and [`EXPORT_PATIENTS_FILE`].
    pub export: PathBuf,
    /// The store directory to create or replace.
    pub out: PathBuf,
    /// Where the key comes from; [`KeySource::OutputDirectoryKeyFile`] reads
    /// `<out>/encryption_key`. The key both opens the export, which Java encrypted with the
    /// javabin store's key, and encrypts the new store. [`KeySource::Plain`] is refused.
    pub key: KeySource,
    /// Worker threads for sealing blocks; `None` uses one per core.
    pub threads: Option<usize>,
    /// Leave out a concept whose recorded metadata disagrees with its observations, and list
    /// it in the report, instead of stopping the migration.
    pub skip_inconsistent: bool,
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

/// A concept left out because its recorded metadata disagrees with its observations.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SkippedConcept {
    /// The raw metastore key.
    pub key: String,
    /// What disagrees. Never names a value or a patient.
    pub reason: String,
}

/// A metastore entry whose `ColumnMeta.name` is not its raw key. The store uses the key.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct NameMismatch {
    /// The raw metastore key.
    pub key: String,
    /// The exported `ColumnMeta.name`, `None` when it was null.
    pub name: Option<String>,
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
    /// Concepts left out by [`MigrateOptions::skip_inconsistent`].
    pub skipped: Vec<SkippedConcept>,
    /// Entries whose `ColumnMeta.name` differs from the raw key.
    pub name_mismatches: Vec<NameMismatch>,
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
/// [`EtlError::Usage`] for a missing export, a plain key source or an unreadable key;
/// [`EtlError::Export`] for an export that does not decrypt with the key, is malformed or
/// truncated, holds a negative patient ID or a null value, or holds a concept whose
/// metadata disagrees with its observations when `skip_inconsistent` is off; I/O and
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
    if matches!(options.key, KeySource::Plain) {
        return Err(EtlError::Usage(
            "migrate needs the javabin store's key: the export is encrypted with it".into(),
        ));
    }
    check_output(&options.out, &options.key)?;
    let protection = resolve_protection(&options.key, &options.out)?;
    let Some(key) = protection.key().cloned() else {
        return Err(EtlError::Usage(
            "migrate needs the javabin store's key".into(),
        ));
    };
    let (patients, patients_export_id) = read_patients(&patients_path, &key)?;
    let pool = {
        let mut builder = rayon::ThreadPoolBuilder::new();
        if let Some(threads) = options.threads {
            builder = builder.num_threads(threads);
        }
        builder
            .build()
            .map_err(|e| EtlError::ThreadPool(e.to_string()))?
    };

    let mut reader = ExportReader::open(&concepts_path, &key)?;
    if reader.export_id() != patients_export_id {
        return Err(EtlError::Export {
            path: concepts_path,
            detail: format!(
                "belongs to a different export than {}",
                patients_path.display()
            ),
        });
    }
    let staging = Staging::create(&options.out)?;
    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(protection.clone(), store_id, true);
    let blocks_path = staging.path().join(BLOCKS_FILE_NAME);
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer = BlocksWriter::new(BufWriter::with_capacity(4 << 20, file), store_id)?;

    let mut state = MigrateState::new(options.skip_inconsistent, concepts_path.clone());
    let mut batch: Vec<ExportedConcept> = Vec::new();
    let mut batch_observations = 0usize;
    while let Some(concept) = reader.next_concept()? {
        batch_observations += concept.observations.len();
        batch.push(concept);
        if batch_observations >= BATCH_OBSERVATIONS {
            seal_batch(&pool, &codec, &mut batch, &mut writer, &mut state)?;
            batch_observations = 0;
        }
    }
    seal_batch(&pool, &codec, &mut batch, &mut writer, &mut state)?;
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

    let metas: Vec<ConceptMeta> = state.concepts.into_iter().map(|kept| kept.meta).collect();
    let observed = if state.collisions.is_empty() {
        state.observed
    } else {
        observed_patients(&blocks_path, store_id, &codec, &metas)?
    };
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
        skipped: state.skipped,
        name_mismatches: state.name_mismatches,
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
    if !report.name_mismatches.is_empty() {
        tracing::warn!(
            count = report.name_mismatches.len(),
            "metastore entries whose ColumnMeta.name differs from the key; the store uses the key"
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
        skipped = report.skipped.len(),
        seconds = report.elapsed_seconds,
        "store migrated"
    );
    Ok(report)
}

/// The patients the kept concepts cover, read back from the staged blocks. Used only when
/// a key collision replaced a concept whose patients were already counted while sealing.
fn observed_patients(
    blocks_path: &Path,
    store_id: StoreId,
    codec: &BlockCodec,
    metas: &[ConceptMeta],
) -> Result<RoaringBitmap> {
    let reader = BlocksReader::open(blocks_path, store_id)?;
    let mut observed = RoaringBitmap::new();
    for meta in metas {
        let patients = match reader.read_block(codec, meta)? {
            Block::Numeric(block) => block.patients().to_vec(),
            Block::Categorical(block) => block.patients().to_vec(),
        };
        observed.extend(patients);
    }
    Ok(observed)
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

/// Reads the patient ID set and the export id of [`EXPORT_PATIENTS_FILE`].
fn read_patients(path: &Path, key: &CryptoKey) -> Result<(RoaringBitmap, [u8; EXPORT_ID_LEN])> {
    let file = File::open(path).map_err(EtlError::io("opening", path))?;
    let decrypted = ExportDecryptor::open(file, key.clone(), ExportKind::Patients)
        .map_err(|e| export_io_error(path, e))?;
    let export_id = decrypted.export_id;
    let mut patients = RoaringBitmap::new();
    for (number, line) in BufReader::new(decrypted).lines().enumerate() {
        let line = line.map_err(|e| export_io_error(path, e))?;
        let invalid = |problem: &str| EtlError::Export {
            path: path.to_path_buf(),
            detail: format!("line {}: the patient ID {problem}", number + 1),
        };
        let id: i32 = line.parse().map_err(|_| invalid("is not an int"))?;
        let id = u32::try_from(id).map_err(|_| invalid("is negative"))?;
        patients.insert(id);
    }
    Ok((patients, export_id))
}

/// Turns an error from reading a decrypted export into an [`EtlError`]: a container problem
/// becomes [`EtlError::Export`] with its message, a premature end says the export is
/// incomplete, and anything else stays an I/O error.
fn export_io_error(path: &Path, error: std::io::Error) -> EtlError {
    match error.kind() {
        std::io::ErrorKind::InvalidData => EtlError::Export {
            path: path.to_path_buf(),
            detail: error.to_string(),
        },
        std::io::ErrorKind::UnexpectedEof => EtlError::Export {
            path: path.to_path_buf(),
            detail: "ends early; the export is incomplete".into(),
        },
        _ => EtlError::io("reading", path)(error),
    }
}

/// Reads the content of one encrypted export file, frame by frame, checking that every
/// frame belongs to this file and export, comes in order, and that the file ends with its
/// final frame. Container problems are [`std::io::ErrorKind::InvalidData`] errors.
struct ExportDecryptor<R: Read> {
    input: BufReader<R>,
    key: CryptoKey,
    kind: ExportKind,
    export_id: [u8; EXPORT_ID_LEN],
    next_index: i64,
    finished: bool,
    chunk: Vec<u8>,
    position: usize,
}

impl<R: Read> ExportDecryptor<R> {
    fn open(input: R, key: CryptoKey, kind: ExportKind) -> std::io::Result<Self> {
        let mut input = BufReader::with_capacity(1 << 20, input);
        let mut header = [0u8; 8 + 4 + EXPORT_ID_LEN];
        input.read_exact(&mut header)?;
        if &header[..8] != EXPORT_MAGIC {
            return Err(invalid(
                "does not start with HPDSMIG2; it is not an encrypted migration export (re-export with the current ExportForMigration)",
            ));
        }
        let version = i32::from_be_bytes(header[8..12].try_into().unwrap_or_default());
        if version != EXPORT_VERSION {
            return Err(invalid(&format!("unsupported export version {version}")));
        }
        let mut export_id = [0u8; EXPORT_ID_LEN];
        export_id.copy_from_slice(&header[12..]);
        Ok(ExportDecryptor {
            input,
            key,
            kind,
            export_id,
            next_index: 0,
            finished: false,
            chunk: Vec::new(),
            position: 0,
        })
    }

    fn next_frame(&mut self) -> std::io::Result<()> {
        let mut length = [0u8; 4];
        self.input.read_exact(&mut length)?;
        let length = i32::from_be_bytes(length);
        let length = usize::try_from(length)
            .ok()
            .filter(|&l| l <= MAX_FRAME_LEN)
            .ok_or_else(|| invalid(&format!("frame {} has length {length}", self.next_index)))?;
        let mut frame = vec![0u8; length];
        self.input.read_exact(&mut frame)?;
        let mut plaintext = decrypt_java_block(&self.key, &frame).map_err(|e| match e {
            FormatError::Authentication => invalid(
                "does not decrypt with the given key: the key is not the javabin store's encryption_key, or the export was altered",
            ),
            other => invalid(&format!("frame {}: {other}", self.next_index)),
        })?;
        if plaintext.len() < FRAME_HEADER_LEN {
            return Err(invalid(&format!(
                "frame {} is shorter than its header",
                self.next_index
            )));
        }
        if plaintext[..EXPORT_ID_LEN] != self.export_id {
            return Err(invalid(&format!(
                "frame {} belongs to another export",
                self.next_index
            )));
        }
        if plaintext[EXPORT_ID_LEN] != self.kind as u8 {
            return Err(invalid(&format!(
                "frame {} belongs to the other export file",
                self.next_index
            )));
        }
        let index = i64::from_be_bytes(
            plaintext[EXPORT_ID_LEN + 1..EXPORT_ID_LEN + 9]
                .try_into()
                .unwrap_or_default(),
        );
        if index != self.next_index {
            return Err(invalid(&format!(
                "frame {index} found where frame {} belongs",
                self.next_index
            )));
        }
        self.finished = match plaintext[EXPORT_ID_LEN + 9] {
            0 => false,
            1 => true,
            other => return Err(invalid(&format!("frame {index} has final flag {other}"))),
        };
        if self.finished {
            let mut rest = [0u8; 1];
            if self.input.read(&mut rest)? != 0 {
                return Err(invalid("holds bytes after its final frame"));
            }
        }
        self.next_index += 1;
        plaintext.drain(..FRAME_HEADER_LEN);
        self.chunk = plaintext;
        self.position = 0;
        Ok(())
    }
}

impl<R: Read> Read for ExportDecryptor<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        while self.position == self.chunk.len() {
            if self.finished {
                return Ok(0);
            }
            self.next_frame()?;
        }
        let n = buf.len().min(self.chunk.len() - self.position);
        buf[..n].copy_from_slice(&self.chunk[self.position..self.position + n]);
        self.position += n;
        Ok(n)
    }
}

fn invalid(detail: &str) -> std::io::Error {
    std::io::Error::new(std::io::ErrorKind::InvalidData, detail.to_string())
}

/// A concept kept in the store.
struct KeptConcept {
    raw_key: String,
    meta: ConceptMeta,
}

struct MigrateState {
    skip_inconsistent: bool,
    export_path: PathBuf,
    concepts: Vec<KeptConcept>,
    positions: HashMap<String, usize>,
    observed: RoaringBitmap,
    collisions: Vec<KeyCollision>,
    skipped: Vec<SkippedConcept>,
    name_mismatches: Vec<NameMismatch>,
    keys_with_replacement_character: u64,
    records: u64,
    records_observations: u64,
}

impl MigrateState {
    fn new(skip_inconsistent: bool, export_path: PathBuf) -> Self {
        MigrateState {
            skip_inconsistent,
            export_path,
            concepts: Vec::new(),
            positions: HashMap::new(),
            observed: RoaringBitmap::new(),
            collisions: Vec::new(),
            skipped: Vec::new(),
            name_mismatches: Vec::new(),
            keys_with_replacement_character: 0,
            records: 0,
            records_observations: 0,
        }
    }
}

/// What checking and encoding one concept produced.
enum Built {
    /// The concept's metadata, the patients it covers, and its sealed block.
    Sealed(ConceptMeta, RoaringBitmap, Vec<u8>),
    /// The concept's recorded metadata disagrees with its observations.
    Inconsistent(String),
}

/// Validates, encodes and seals a batch of concepts in parallel, then appends them in
/// export order. The patients of each sealed concept are added to the running set and
/// dropped.
fn seal_batch<W: Write>(
    pool: &rayon::ThreadPool,
    codec: &BlockCodec,
    batch: &mut Vec<ExportedConcept>,
    writer: &mut BlocksWriter<W>,
    state: &mut MigrateState,
) -> Result<()> {
    let export_path = state.export_path.clone();
    let built: Vec<(ExportedConcept, Built)> = pool.install(|| {
        batch
            .par_drain(..)
            .map(|concept| {
                let built = match build_concept(&concept, &export_path)? {
                    Ok((meta, patients, payload)) => {
                        let stored = codec.seal(&meta.path, &payload)?;
                        Built::Sealed(meta, patients, stored)
                    }
                    Err(reason) => Built::Inconsistent(reason),
                };
                Ok((concept, built))
            })
            .collect::<Result<Vec<_>>>()
    })?;
    for (concept, built) in built {
        state.records += 1;
        state.records_observations += concept.observations.len() as u64;
        if concept.key_has_replacement_character {
            state.keys_with_replacement_character += 1;
        }
        if concept.name.as_deref() != Some(concept.key.as_str()) {
            state.name_mismatches.push(NameMismatch {
                key: concept.key.clone(),
                name: concept.name.clone(),
            });
        }
        let (mut meta, patients, stored) = match built {
            Built::Sealed(meta, patients, stored) => (meta, patients, stored),
            Built::Inconsistent(reason) => {
                if !state.skip_inconsistent {
                    return Err(EtlError::Export {
                        path: export_path,
                        detail: format!(
                            "concept {:?}: {reason}. Fix the javabin store, or rerun with --skip-inconsistent to leave such concepts out (they are listed in {REPORT_FILE_NAME})",
                            concept.key
                        ),
                    });
                }
                tracing::warn!(key = %concept.key, %reason, "leaving out an inconsistent concept");
                state.skipped.push(SkippedConcept {
                    key: concept.key,
                    reason,
                });
                continue;
            }
        };
        state.observed |= patients;
        meta.block = writer.append_stored(&stored)?;
        let kept = KeptConcept {
            raw_key: concept.key,
            meta,
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

type BuiltConcept = std::result::Result<(ConceptMeta, RoaringBitmap, Vec<u8>), String>;

/// Checks one exported concept against its recorded metadata and builds its metadata and
/// block payload. The outer error is fatal (a negative patient number, an encoding
/// failure); the inner one names what disagrees, never a value or a patient.
fn build_concept(concept: &ExportedConcept, export_path: &Path) -> Result<BuiltConcept> {
    let meta = &concept.meta;
    let text = matches!(concept.observations, Observations::Text { .. });
    if meta.categorical != text {
        return Ok(Err(format!(
            "ColumnMeta.categorical is {} but the cube holds {} values",
            meta.categorical,
            if text { "String" } else { "Double" }
        )));
    }
    if !text && meta.category_values.is_some() {
        return Ok(Err("a numeric concept has ColumnMeta.categoryValues".into()));
    }
    let mut patients = RoaringBitmap::new();
    let mut previous: Option<u32> = None;
    let mut timestamp_range: Option<(i64, i64)> = None;
    let mut unordered_at: Option<usize> = None;
    let mut track = |position: usize, patient: i32, timestamp: i64| -> Result<u32> {
        let patient = u32::try_from(patient).map_err(|_| EtlError::Export {
            path: export_path.to_path_buf(),
            detail: format!(
                "concept {:?}: observation {position} has a negative patient number, which the store cannot hold",
                concept.key
            ),
        })?;
        if unordered_at.is_none() && previous.is_some_and(|p| p > patient) {
            unordered_at = Some(position);
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
            for (position, row) in rows.iter().enumerate() {
                let patient = track(position, row.patient, row.timestamp)?;
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
            if let Some(position) = unordered_at {
                return Ok(Err(unordered(position)));
            }
            encode_numeric_payload(&observations)?
        }
        Observations::Text { values, rows } => {
            let mut categorical = Vec::with_capacity(rows.len());
            for (position, row) in rows.iter().enumerate() {
                let patient = track(position, row.patient, row.timestamp)?;
                categorical.push(CategoricalRow {
                    patient,
                    value_id: row.value_id,
                    timestamp: row.timestamp,
                });
            }
            if let Some(position) = unordered_at {
                return Ok(Err(unordered(position)));
            }
            let mut distinct: Vec<&str> = values.iter().map(String::as_str).collect();
            distinct.sort_unstable_by(|a, b| java_string_cmp(a, b));
            let recorded: Option<Vec<&str>> = meta
                .category_values
                .as_ref()
                .map(|v| v.iter().map(String::as_str).collect());
            match &recorded {
                None => {
                    return Ok(Err(
                        "ColumnMeta.categoryValues is null for a categorical concept".into(),
                    ));
                }
                Some(recorded) if *recorded != distinct => {
                    return Ok(Err(format!(
                        "ColumnMeta.categoryValues lists {} values that are not the {} distinct values of the observations in Java String order",
                        recorded.len(),
                        distinct.len()
                    )));
                }
                Some(_) => {}
            }
            encode_interned_categorical_payload(values, &categorical)?
        }
    };
    let observation_count = concept.observations.len();
    if i64::from(meta.observation_count) != observation_count as i64 {
        return Ok(Err(format!(
            "ColumnMeta.observationCount is {} but the cube holds {observation_count} observations",
            meta.observation_count
        )));
    }
    if i64::from(meta.patient_count) != patients.len() as i64 {
        return Ok(Err(format!(
            "ColumnMeta.patientCount is {} but the cube covers {} patients",
            meta.patient_count,
            patients.len()
        )));
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
    Ok(Ok((concept_meta, patients, payload)))
}

fn unordered(position: usize) -> String {
    format!("observations are not ordered by patient at observation {position}")
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
    name: Option<String>,
    meta: ExportedMeta,
    observations: Observations,
}

/// Streams the content of [`EXPORT_CONCEPTS_FILE`].
struct ExportReader {
    path: PathBuf,
    input: BufReader<ExportDecryptor<File>>,
    trailer: (u64, u64),
}

impl ExportReader {
    fn open(path: &Path, key: &CryptoKey) -> Result<Self> {
        let file = File::open(path).map_err(EtlError::io("opening", path))?;
        let decrypted = ExportDecryptor::open(file, key.clone(), ExportKind::Concepts)
            .map_err(|e| export_io_error(path, e))?;
        Ok(ExportReader {
            path: path.to_path_buf(),
            input: BufReader::with_capacity(4 << 20, decrypted),
            trailer: (0, 0),
        })
    }

    fn export_id(&self) -> [u8; EXPORT_ID_LEN] {
        self.input.get_ref().export_id
    }

    fn malformed(&self, detail: &str) -> EtlError {
        EtlError::Export {
            path: self.path.clone(),
            detail: detail.to_string(),
        }
    }

    fn bytes(&mut self, buf: &mut [u8]) -> Result<()> {
        self.input
            .read_exact(buf)
            .map_err(|e| export_io_error(&self.path, e))
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
                    .map_err(|e| export_io_error(&self.path, e))?
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
        let name = self.nullable_string()?;
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
            name,
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
