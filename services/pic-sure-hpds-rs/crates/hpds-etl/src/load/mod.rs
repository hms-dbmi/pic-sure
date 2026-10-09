//! `hpds-etl load`: phenotype CSV to a new store, reproducing one of the two Java loaders
//! the all-in-one runs.
//!
//! [`Mode::NewSearch`] follows `CSVLoaderNewSearch` ("Load HPDS Data From CSV").
//! [`Mode::Sequential`] follows `SequentialLoader` ("Load HPDS Data From Multiple Files"),
//! which for an all-CSV input set runs `LowRAMMultiCSVLoader`. The rules both share, and
//! the places they differ, are in [`rules`]. Where reproducing Java would reproduce a
//! defect, the loader does the correct thing instead:
//!
//! - Every row of a concept is kept. Both Java loaders overwrite a concept's metadata when
//!   its rows are not contiguous in ingest order (a 16-entry cache in `LoadingStore`, a
//!   1-entry cache in `LowRAMLoadingStore`), losing the earlier rows.
//! - Numeric min and max are the real extremes, `null` for a concept with no values. Java
//!   starts max at `Double.MIN_VALUE` and min at `Double.MAX_VALUE`.
//! - `has_timestamp` and the timestamp range are set. Java never sets them.
//! - An unparseable number or timestamp drops the row or its timestamp in new-search mode.
//!   `CSVLoaderNewSearch` aborts the load.
//! - Every record is ingested in sequential mode. `LowRAMCSVProcessor` never ingests its
//!   last chunk when the file holds more lines than records (an embedded newline or a blank
//!   line), and fails on a record with a single field.
//!
//! The pipeline streams the inputs once into spill files partitioned by concept, builds
//! the partitions in parallel, then writes `pheno.blocks`, `pheno.index`, `columnMeta.csv`
//! and `etl-report.json` into a staging directory beside the output and moves them into
//! place.

mod build;
mod partition;
pub mod rules;
mod spill;

use std::fs::File;
use std::io::{BufWriter, Write};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hpds_format::{
    BLOCKS_FILE_NAME, BlockCodec, BlocksWriter, CryptoKey, INDEX_FILE_NAME, IndexHeader,
    PhenoIndex, Protection, StoreId, java_string_cmp,
};
use rayon::prelude::*;
use roaring::RoaringBitmap;
use serde::Serialize;

use self::build::{BuildContext, BuiltConcept, BuiltPartition, build_partition};
use self::partition::{InputFile, PartitionSettings, partition};
pub use self::rules::{ConfigMap, CsvConfig, Mode};
use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};

/// Name of the CSV the loader writes for dictionary hydration.
pub const COLUMN_META_CSV_NAME: &str = "columnMeta.csv";

/// Name of the JSON report of row counts.
pub const REPORT_FILE_NAME: &str = "etl-report.json";

/// Name of the key file looked for in the output directory when no key is named.
pub const DEFAULT_KEY_FILE_NAME: &str = "encryption_key";

/// The key name recorded in the index header of an encrypted store, `Crypto.DEFAULT_KEY_NAME`.
pub const DEFAULT_KEY_NAME: &str = "DEFAULT";

const DEFAULT_MAX_CHUNK_GIB: f64 = 5.0;
const INPUT_BYTES_PER_PARTITION: u64 = 32 << 20;
const MAX_DEFAULT_PARTITIONS: usize = 128;

/// How the store is protected.
#[derive(Clone, Debug)]
pub enum KeySource {
    /// Encrypt with the key in this file, loaded by `Crypto.loadKey` rules.
    File(PathBuf),
    /// Encrypt with the key in `<out>/encryption_key`.
    OutputDirectory,
    /// Do not encrypt. For local benchmarking only.
    Plain,
}

/// Everything `hpds-etl load` needs.
#[derive(Clone, Debug)]
pub struct LoadOptions {
    /// Which Java loader to reproduce.
    pub mode: Mode,
    /// Input CSV files, or in sequential mode directories whose `.csv` files are read in
    /// directory order, as `LowRAMMultiCSVLoader` lists `/opt/local/hpds_input`.
    pub inputs: Vec<PathBuf>,
    /// The store directory to create or replace.
    pub out: PathBuf,
    /// Where the key comes from.
    pub key: KeySource,
    /// Variable-name rollup. `None` takes the Java default: off in new-search mode (which
    /// cannot roll up), on in sequential mode (Java's `NO_ROLLUP` argument turns it off).
    pub rollup: Option<bool>,
    /// The CSV config file. In sequential mode, `config.json` in a directory input is used
    /// when this is `None`.
    pub config: Option<PathBuf>,
    /// Number of spill partitions; `None` picks one from the input size.
    pub partitions: Option<usize>,
    /// Worker threads for the build pass; `None` uses one per core.
    pub threads: Option<usize>,
    /// Directory for the spill files; `None` uses the staging directory.
    pub tmp: Option<PathBuf>,
    /// Sequential mode: the chunk size in GiB that `MAX_CHUNK_SIZE` sets in Java (default 5).
    pub max_chunk_gib: Option<f64>,
}

impl LoadOptions {
    /// Options with the defaults for everything but the mode, inputs and output.
    pub fn new(mode: Mode, inputs: Vec<PathBuf>, out: PathBuf) -> Self {
        LoadOptions {
            mode,
            inputs,
            out,
            key: KeySource::OutputDirectory,
            rollup: None,
            config: None,
            partitions: None,
            threads: None,
            tmp: None,
            max_chunk_gib: None,
        }
    }
}

/// Row and concept counts of one load, written to `etl-report.json`.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct LoadCounts {
    /// Data records read, header rows excluded.
    pub rows_read: u64,
    /// Observations stored.
    pub accepted: u64,
    /// Records with fewer than four fields.
    pub dropped_short_row: u64,
    /// Rows whose type (text or numeric) differs from the type their concept was fixed to.
    pub dropped_type_conflict: u64,
    /// Rows whose value is empty after trimming.
    pub dropped_blank_value: u64,
    /// Numeric rows whose value `Double.parseDouble` rejects.
    pub dropped_unparseable_number: u64,
    /// Stored observations whose timestamp field did not parse and that therefore have
    /// no timestamp.
    pub unparseable_timestamps: u64,
    /// Concepts in the store, including concepts with no stored observations.
    pub concepts: u64,
    /// Numeric concepts.
    pub numeric_concepts: u64,
    /// Categorical concepts.
    pub categorical_concepts: u64,
    /// Concepts every row of which was dropped. Java writes them too.
    pub empty_concepts: u64,
    /// Patients with at least one stored observation.
    pub patients: u64,
}

impl LoadCounts {
    fn add(&mut self, other: &LoadCounts) {
        self.accepted += other.accepted;
        self.dropped_type_conflict += other.dropped_type_conflict;
        self.dropped_blank_value += other.dropped_blank_value;
        self.dropped_unparseable_number += other.dropped_unparseable_number;
        self.unparseable_timestamps += other.unparseable_timestamps;
        self.concepts += other.concepts;
        self.numeric_concepts += other.numeric_concepts;
        self.categorical_concepts += other.categorical_concepts;
        self.empty_concepts += other.empty_concepts;
    }
}

/// What a load did, as written to `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct LoadReport {
    /// The loader reproduced.
    pub mode: Mode,
    /// The CSV files read, in order.
    pub inputs: Vec<PathBuf>,
    /// Whether rollup was on.
    pub rollup: bool,
    /// Whether the store is encrypted.
    pub encrypted: bool,
    /// The store id written into the index and blocks.
    pub store_id: String,
    /// Row and concept counts.
    pub counts: LoadCounts,
    /// Size of `pheno.blocks` in bytes.
    pub blocks_bytes: u64,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// Loads the inputs into a new store at `options.out`.
///
/// The store is written to a staging directory next to `out` and moved into place only
/// once complete. When `out` does not exist the staging directory is renamed to it; when it
/// does, its `pheno.index` is removed first and the new files are renamed in with
/// `pheno.index` last, so `out` never holds an index that does not match its blocks.
///
/// # Errors
/// [`EtlError::Usage`] for options the selected mode does not support, missing inputs or an
/// unreadable key; [`EtlError::InvalidPatient`] for the first row whose patient number Java
/// would fail on, or that is negative; I/O, CSV, config and format errors.
pub fn load(options: &LoadOptions) -> Result<LoadReport> {
    let started = Instant::now();
    let rollup = resolve_rollup(options)?;
    let inputs = resolve_inputs(options)?;
    let configs = resolve_config(options)?;
    let protection = resolve_protection(options)?;
    let staging = Staging::create(&options.out)?;
    let spill_dir = match &options.tmp {
        Some(tmp) => unique_dir(tmp, "hpds-etl-spill")?,
        None => {
            let dir = staging.path.join("spill");
            std::fs::create_dir(&dir).map_err(EtlError::io("creating", &dir))?;
            dir
        }
    };
    let _spill_cleanup = RemoveOnDrop(spill_dir.clone());

    let input_bytes: u64 = inputs
        .iter()
        .map(|i| std::fs::metadata(&i.path).map(|m| m.len()).unwrap_or(0))
        .sum();
    let partitions = options.partitions.unwrap_or_else(|| {
        (input_bytes.div_ceil(INPUT_BYTES_PER_PARTITION) as usize).clamp(1, MAX_DEFAULT_PARTITIONS)
    });
    if partitions == 0 {
        return Err(EtlError::Usage("--partitions must be at least 1".into()));
    }
    let max_chunk_gib = options.max_chunk_gib.unwrap_or(DEFAULT_MAX_CHUNK_GIB);
    if max_chunk_gib.is_nan() || max_chunk_gib <= 0.0 {
        return Err(EtlError::Usage("--max-chunk-gib must be positive".into()));
    }
    tracing::info!(
        mode = ?options.mode,
        files = inputs.len(),
        input_bytes,
        partitions,
        rollup,
        encrypted = protection.is_encrypted(),
        "loading"
    );

    let partitioned = partition(
        &inputs,
        &PartitionSettings {
            mode: options.mode,
            rollup,
            configs: &configs,
            spill_dir: &spill_dir,
            partitions,
            max_chunk_bytes: max_chunk_gib * (1u64 << 30) as f64,
        },
    )?;

    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(protection.clone(), store_id, true);
    let ctx = BuildContext {
        mode: options.mode,
        concepts: &partitioned.concepts,
        raw_path_ranks: &partitioned.raw_path_ranks,
        codec: &codec,
    };
    let pool = {
        let mut builder = rayon::ThreadPoolBuilder::new();
        if let Some(threads) = options.threads {
            builder = builder.num_threads(threads);
        }
        builder
            .build()
            .map_err(|e| EtlError::ThreadPool(e.to_string()))?
    };
    let build_started = Instant::now();
    let built: Vec<BuiltPartition> = pool.install(|| {
        partitioned
            .spill_files
            .par_iter()
            .enumerate()
            .map(|(i, spill)| {
                build_partition(&ctx, i, spill, spill_dir.join(format!("blocks-{i:05}.bin")))
            })
            .collect::<Result<Vec<_>>>()
    })?;

    if let Some(failure) = built
        .iter()
        .filter_map(|b| b.patient_failure.as_ref())
        .min_by_key(|f| f.seq)
    {
        let (start, file) = partitioned
            .file_starts
            .iter()
            .rev()
            .find(|(start, _)| *start <= failure.seq)
            .cloned()
            .unwrap_or_default();
        let problem = match rules::parse_patient(&failure.value) {
            Err(problem) => problem.describe(),
            Ok(_) => "could not be read",
        };
        return Err(EtlError::InvalidPatient {
            file,
            record: failure.seq - start + 1,
            value: failure.value.clone(),
            problem,
        });
    }

    let mut counts = LoadCounts {
        rows_read: partitioned.rows_read,
        dropped_short_row: partitioned.short_rows,
        ..LoadCounts::default()
    };
    let mut patients = RoaringBitmap::new();
    for partition in &built {
        counts.add(&partition.counts);
        patients |= &partition.patients;
    }
    counts.patients = patients.len();
    tracing::info!(
        concepts = counts.concepts,
        accepted = counts.accepted,
        dropped_short_row = counts.dropped_short_row,
        dropped_type_conflict = counts.dropped_type_conflict,
        dropped_blank_value = counts.dropped_blank_value,
        dropped_unparseable_number = counts.dropped_unparseable_number,
        unparseable_timestamps = counts.unparseable_timestamps,
        patients = counts.patients,
        seconds = build_started.elapsed().as_secs_f64(),
        "built"
    );

    let blocks_bytes = write_store(&staging.path, &built, store_id, patients, &protection)?;
    drop(built);
    std::fs::remove_dir_all(&spill_dir).map_err(EtlError::io("removing", &spill_dir))?;

    let report = LoadReport {
        mode: options.mode,
        inputs: inputs.into_iter().map(|i| i.path).collect(),
        rollup,
        encrypted: protection.is_encrypted(),
        store_id: store_id.to_string(),
        counts,
        blocks_bytes,
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    let report_path = staging.path.join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    std::fs::write(&report_path, json).map_err(EtlError::io("writing", &report_path))?;
    staging.publish(&options.out)?;
    tracing::info!(out = %options.out.display(), blocks_bytes, seconds = report.elapsed_seconds, "store written");
    Ok(report)
}

fn write_store(
    dir: &Path,
    built: &[BuiltPartition],
    store_id: StoreId,
    patients: RoaringBitmap,
    protection: &Protection,
) -> Result<u64> {
    let mut concepts: Vec<&BuiltConcept> = built.iter().flat_map(|b| &b.concepts).collect();
    concepts.sort_by(|a, b| java_string_cmp(&a.meta.path, &b.meta.path));
    let sources = built
        .iter()
        .map(|b| File::open(&b.blocks_file).map_err(EtlError::io("opening", &b.blocks_file)))
        .collect::<Result<Vec<_>>>()?;

    let blocks_path = dir.join(BLOCKS_FILE_NAME);
    let file = File::create(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer = BlocksWriter::new(BufWriter::with_capacity(4 << 20, file), store_id)?;
    let mut metas = Vec::with_capacity(concepts.len());
    let mut stored = Vec::new();
    for concept in concepts {
        let local = concept.meta.block;
        stored.resize(local.length as usize, 0);
        let source = &built[concept.partition].blocks_file;
        sources[concept.partition]
            .read_exact_at(&mut stored, local.offset)
            .map_err(EtlError::io("reading", source))?;
        let mut meta = concept.meta.clone();
        meta.block = writer.append_stored(&stored)?;
        metas.push(meta);
    }
    let blocks_bytes = writer.len();
    writer
        .finish()?
        .into_inner()
        .map_err(|e| EtlError::io("writing", &blocks_path)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("writing", &blocks_path))?;

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
    index.write_file(dir.join(INDEX_FILE_NAME), protection)?;

    let csv_path = dir.join(COLUMN_META_CSV_NAME);
    let file = File::create(&csv_path).map_err(EtlError::io("creating", &csv_path))?;
    let mut out = BufWriter::new(file);
    write_column_meta_csv(&mut out, index.concepts())
        .and_then(|()| out.flush())
        .map_err(EtlError::io("writing", &csv_path))?;
    Ok(blocks_bytes)
}

fn resolve_rollup(options: &LoadOptions) -> Result<bool> {
    match (options.mode, options.rollup) {
        (Mode::NewSearch, Some(true)) => Err(EtlError::Usage(
            "new-search mode never rolls up variable names (CSVLoaderNewSearch has no way to turn it on)".into(),
        )),
        (Mode::NewSearch, _) => Ok(false),
        (Mode::Sequential, rollup) => Ok(rollup.unwrap_or(true)),
    }
}

fn resolve_inputs(options: &LoadOptions) -> Result<Vec<InputFile>> {
    if options.inputs.is_empty() {
        return Err(EtlError::Usage("at least one --input is required".into()));
    }
    match options.mode {
        Mode::NewSearch => {
            let [path] = options.inputs.as_slice() else {
                return Err(EtlError::Usage(
                    "new-search mode reads exactly one input file".into(),
                ));
            };
            if !path.is_file() {
                return Err(EtlError::Usage(format!("{} is not a file", path.display())));
            }
            Ok(vec![InputFile {
                path: path.clone(),
                config_key: "allConcepts".into(),
            }])
        }
        Mode::Sequential => {
            let mut files = Vec::new();
            for input in &options.inputs {
                if input.is_dir() {
                    let entries =
                        std::fs::read_dir(input).map_err(EtlError::io("listing", input))?;
                    for entry in entries {
                        let path = entry.map_err(EtlError::io("listing", input))?.path();
                        let is_csv = path
                            .file_name()
                            .and_then(|n| n.to_str())
                            .is_some_and(|n| n.ends_with(".csv"));
                        if is_csv && path.is_file() {
                            files.push(path);
                        }
                    }
                } else if input.is_file() {
                    files.push(input.clone());
                } else {
                    return Err(EtlError::Usage(format!(
                        "{} does not exist",
                        input.display()
                    )));
                }
            }
            if files.is_empty() {
                return Err(EtlError::Usage("no .csv files found in the inputs".into()));
            }
            Ok(files
                .into_iter()
                .map(|path| {
                    let config_key = path
                        .file_name()
                        .map(|n| n.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    InputFile { path, config_key }
                })
                .collect())
        }
    }
}

fn resolve_config(options: &LoadOptions) -> Result<ConfigMap> {
    if let Some(path) = &options.config {
        return ConfigMap::load(path);
    }
    if options.mode == Mode::Sequential {
        for input in &options.inputs {
            let candidate = input.join("config.json");
            if input.is_dir() && candidate.is_file() {
                return ConfigMap::load(&candidate);
            }
        }
    }
    Ok(ConfigMap::default())
}

fn resolve_protection(options: &LoadOptions) -> Result<Protection> {
    let path = match &options.key {
        KeySource::Plain => return Ok(Protection::Plain),
        KeySource::File(path) => path.clone(),
        KeySource::OutputDirectory => options.out.join(DEFAULT_KEY_FILE_NAME),
    };
    CryptoKey::load(&path)
        .map(Protection::Encrypted)
        .map_err(|e| {
            EtlError::Usage(format!(
                "cannot load the encryption key {}: {e}",
                path.display()
            ))
        })
}

fn unique_dir(parent: &Path, prefix: &str) -> Result<PathBuf> {
    std::fs::create_dir_all(parent).map_err(EtlError::io("creating", parent))?;
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    let dir = parent.join(format!(".{prefix}-{}-{nanos}", std::process::id()));
    std::fs::create_dir(&dir).map_err(EtlError::io("creating", &dir))?;
    Ok(dir)
}

struct RemoveOnDrop(PathBuf);

impl Drop for RemoveOnDrop {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

/// The directory a store is written into before it is moved to its final place. Removed
/// on drop unless published.
struct Staging {
    path: PathBuf,
    published: bool,
}

impl Staging {
    fn create(out: &Path) -> Result<Self> {
        let parent = match out.parent() {
            Some(p) if !p.as_os_str().is_empty() => p.to_path_buf(),
            _ => PathBuf::from("."),
        };
        let name = out
            .file_name()
            .ok_or_else(|| EtlError::Usage(format!("{} has no directory name", out.display())))?
            .to_string_lossy()
            .into_owned();
        if out.exists() && !out.is_dir() {
            return Err(EtlError::Usage(format!(
                "{} exists and is not a directory",
                out.display()
            )));
        }
        Ok(Staging {
            path: unique_dir(&parent, &format!("{name}.staging"))?,
            published: false,
        })
    }

    fn publish(mut self, out: &Path) -> Result<()> {
        if !out.exists() {
            std::fs::rename(&self.path, out).map_err(EtlError::io("moving the store to", out))?;
            self.published = true;
            return Ok(());
        }
        let index = out.join(INDEX_FILE_NAME);
        if index.exists() {
            std::fs::remove_file(&index).map_err(EtlError::io("removing", &index))?;
        }
        for name in [
            BLOCKS_FILE_NAME,
            COLUMN_META_CSV_NAME,
            REPORT_FILE_NAME,
            INDEX_FILE_NAME,
        ] {
            let target = out.join(name);
            std::fs::rename(self.path.join(name), &target)
                .map_err(EtlError::io("moving into place", &target))?;
        }
        Ok(())
    }
}

impl Drop for Staging {
    fn drop(&mut self) {
        if !self.published {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
}
