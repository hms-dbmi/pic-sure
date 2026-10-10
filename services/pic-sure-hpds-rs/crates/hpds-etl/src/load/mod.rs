//! `hpds-etl load`: phenotype CSV to a new store, reproducing one of the two Java loaders
//! the all-in-one runs, or to one store per consent partition.
//!
//! [`Mode::NewSearch`] follows `CSVLoaderNewSearch` ("Load HPDS Data From CSV").
//! [`Mode::Sequential`] follows `SequentialLoader` ("Load HPDS Data From Multiple Files"),
//! which for an all-CSV input set runs `LowRAMMultiCSVLoader`. [`Mode::Partitioned`]
//! follows the harmonization branch's `CSVLoader`, one consent partition per
//! `<partition>-allConcepts.csv`, through [`load_partitioned`], into one store whose blocks
//! hold every partition's rows. The rules both share, and
//! the places they differ, are in [`rules`]. Where reproducing Java would reproduce a
//! defect, the loader does the correct thing instead:
//!
//! - Every row of a concept is kept. The Java loaders overwrite a concept's metadata when
//!   its rows are not contiguous in ingest order (a 16-entry cache in `LoadingStore`, a
//!   1-entry cache in `LowRAMLoadingStore`), losing the earlier rows.
//! - Numeric min and max are the real extremes, `null` for a concept with no values. Java
//!   starts max at `Double.MIN_VALUE` and min at `Double.MAX_VALUE`.
//! - `has_timestamp` and the timestamp range are set. Java never sets them.
//! - In new-search and partitioned mode an unparseable number drops its row, and an
//!   unparseable timestamp keeps the row and drops only the timestamp; both are counted.
//!   `CSVLoaderNewSearch` and `CSVLoader` abort the load on either.
//! - A UTF-8 byte order mark at the start of a file is skipped, and text after a quoted
//!   field's closing quote is kept as part of the field; both are counted. Commons CSV
//!   keeps the mark in the first field and fails on the text.
//! - Every record is ingested in sequential mode. `LowRAMCSVProcessor` never ingests its
//!   last chunk when the file holds more lines than records (an embedded newline or a blank
//!   line), and fails on a record with a single field.
//!
//! The pipeline streams the inputs once into spill files partitioned by concept, builds
//! the partitions in parallel, then writes `pheno.blocks`, `pheno.index`, `columnMeta.csv`
//! and `etl-report.json` into a staging directory beside the output and moves them into
//! place, writing the `.generation` marker last.
//!
//! Every directory the loader creates is mode `0700` and every file `0600` (see
//! [`crate::private_fs`]). Error messages and logs name a file, a record and line number,
//! and a field, never an observation's patient, value or timestamp.

mod build;
mod partitioned;
pub mod rules;
mod spill;
mod spill_route;

use std::fs::File;
use std::io::BufWriter;
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};
use std::time::Instant;

use hpds_format::{
    BLOCKS_FILE_NAME, BlockCodec, BlocksWriter, CryptoKey, INDEX_FILE_NAME, PhenoIndex, Protection,
    StoreId, java_string_cmp,
};
use rayon::prelude::*;
use roaring::RoaringBitmap;
use serde::Serialize;

use self::build::{BuildContext, BuiltConcept, BuiltPartition, build_partition};
use self::partitioned::PARTITION_CONFIG_KEY;
pub use self::partitioned::{
    PartitionedLoadOptions, PartitionedLoadReport, discover_partition_inputs, load_partitioned,
};
pub use self::rules::{ConfigMap, CsvConfig, Mode};
pub(crate) use self::spill_route::InputFile;
use self::spill_route::{SpillRouteSettings, route_to_spills};
use crate::cleanup::{self, TemporaryDir};
use crate::error::{EtlError, Result};
use crate::merge::{store_header, write_index_and_column_meta};
use crate::private_fs;
use crate::store::{lock_store, write_generation};

/// Name of the CSV the loader writes for dictionary hydration.
pub const COLUMN_META_CSV_NAME: &str = "columnMeta.csv";

/// Name of the JSON report of row counts.
pub const REPORT_FILE_NAME: &str = "etl-report.json";

/// Name of the key file looked for in the output directory when no key is named.
pub const DEFAULT_KEY_FILE_NAME: &str = "encryption_key";

/// The key name recorded in the index header of an encrypted store, `Crypto.DEFAULT_KEY_NAME`.
pub const DEFAULT_KEY_NAME: &str = "DEFAULT";

/// Name of the CSV config file looked for beside the inputs, as Java reads
/// `/opt/local/hpds_input/config.json`.
pub const CONFIG_FILE_NAME: &str = "config.json";

/// Name of the combined store directory a format 1 partitioned store may hold.
pub const LEGACY_COMBINED_DIR_NAME: &str = ".combined";

/// Files whose presence marks a directory as a Java javabin store, which a Rust store must
/// never be written into.
pub const JAVA_STORE_FILE_NAMES: [&str; 2] = ["columnMeta.javabin", "allObservationsStore.javabin"];

const DEFAULT_MAX_CHUNK_GIB: f64 = 5.0;
const INPUT_BYTES_PER_PARTITION: u64 = 32 << 20;
const MAX_DEFAULT_PARTITIONS: usize = 128;

/// How the store is protected.
#[derive(Clone, Debug)]
pub enum KeySource {
    /// Encrypt with the key in this file, loaded by `Crypto.loadKey` rules.
    File(PathBuf),
    /// Encrypt with the key in the `encryption_key` file of the output directory.
    OutputDirectoryKeyFile,
    /// Use the key in the `encryption_key` file of the existing store a command reads or
    /// changes in place. `load` treats it as [`KeySource::OutputDirectoryKeyFile`].
    StoreDirectory,
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
    /// The CSV config file. When `None`, the first `config.json` found in a directory input
    /// or in the directory of an input file is used, in input order, unless `no_config`.
    pub config: Option<PathBuf>,
    /// Apply no CSV config, not even a `config.json` beside the inputs.
    pub no_config: bool,
    /// Number of spill partitions; `None` picks one per 32 MiB of input, at most 128.
    /// Each build worker holds one partition's spill file and 40 bytes per row of it in
    /// memory at a time, so peak build memory is about `threads` times that for the largest
    /// partitions; more partitions lower it.
    pub partitions: Option<usize>,
    /// Worker threads for the build pass; `None` uses one per core. Each holds one
    /// partition in memory while it builds it.
    pub threads: Option<usize>,
    /// Directory for the spill files; `None` uses the staging directory.
    pub tmp: Option<PathBuf>,
    /// Sequential mode: the chunk size in GiB that `MAX_CHUNK_SIZE` sets in Java (default 5).
    /// Refused in new-search mode.
    pub max_chunk_gib: Option<f64>,
}

impl LoadOptions {
    /// Options with the defaults for everything but the mode, inputs and output.
    pub fn new(mode: Mode, inputs: Vec<PathBuf>, out: PathBuf) -> Self {
        LoadOptions {
            mode,
            inputs,
            out,
            key: KeySource::OutputDirectoryKeyFile,
            rollup: None,
            config: None,
            no_config: false,
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
    /// no timestamp. The row itself is kept.
    pub unparseable_timestamps: u64,
    /// Input files that start with a UTF-8 byte order mark, which is skipped. Java's CSV
    /// parser keeps it in the first field.
    pub inputs_with_byte_order_mark: u64,
    /// Records with text after a quoted field's closing quote, which is kept as part of
    /// the field. Java's CSV parser fails on them.
    pub records_with_text_after_closing_quote: u64,
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
    /// The store id, for publishing; [`LoadReport::store_id`] is its text.
    #[serde(skip)]
    pub id: StoreId,
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
/// once complete; see [`Staging::publish`].
///
/// # Errors
/// [`EtlError::Usage`] for options the selected mode does not support (partitioned mode
/// goes through [`load_partitioned`]), missing inputs, an unreadable key, or an `out` that
/// [`check_output`] refuses; [`EtlError::InvalidPatient`] for the first row whose patient
/// number Java would fail on, or that is negative; I/O, CSV, config and format errors.
pub fn load(options: &LoadOptions) -> Result<LoadReport> {
    if options.mode == Mode::Partitioned {
        return Err(EtlError::Usage(
            "partitioned mode loads a directory of <partition>-allConcepts.csv files; use --input-dir".into(),
        ));
    }
    if options.mode == Mode::NewSearch && options.max_chunk_gib.is_some() {
        return Err(EtlError::Usage(
            "--max-chunk-gib applies to sequential mode only".into(),
        ));
    }
    let rollup = resolve_rollup(options)?;
    let inputs = resolve_inputs(options)?;
    check_output(&options.out, &options.key)?;
    let configs = resolve_config(options)?;
    if options.mode == Mode::NewSearch {
        warn_without_all_concepts_entry(&configs);
    }
    let protection = resolve_protection(options)?;
    let settings = StoreSettings::new(
        options.mode,
        rollup,
        &configs.map,
        &protection,
        options.tmp.as_deref(),
        options.partitions,
        options.max_chunk_gib,
    )?;
    let staging = Staging::create(&options.out)?;
    let pool = thread_pool(options.threads)?;
    let report = pool.install(|| build_store(staging.path(), &inputs, &settings))?;
    staging.publish(&options.out, report.id)?;
    tracing::info!(out = %options.out.display(), blocks_bytes = report.blocks_bytes, seconds = report.elapsed_seconds, "store written");
    Ok(report)
}

/// Builds a rayon pool of `threads` workers, one per core when `None`.
///
/// # Errors
/// [`EtlError::ThreadPool`] when the pool cannot be started.
pub(crate) fn thread_pool(threads: Option<usize>) -> Result<rayon::ThreadPool> {
    let mut builder = rayon::ThreadPoolBuilder::new();
    if let Some(threads) = threads {
        builder = builder.num_threads(threads);
    }
    builder
        .build()
        .map_err(|e| EtlError::ThreadPool(e.to_string()))
}

/// Everything [`build_store`] needs besides its inputs and output directory.
pub(crate) struct StoreSettings<'a> {
    mode: Mode,
    rollup: bool,
    configs: &'a ConfigMap,
    protection: &'a Protection,
    tmp: Option<&'a Path>,
    partitions: Option<usize>,
    max_chunk_bytes: f64,
}

impl<'a> StoreSettings<'a> {
    /// Checks the spill partition count and chunk size and collects the settings.
    ///
    /// # Errors
    /// [`EtlError::Usage`] for zero spill partitions or a chunk size that is not positive.
    pub(crate) fn new(
        mode: Mode,
        rollup: bool,
        configs: &'a ConfigMap,
        protection: &'a Protection,
        tmp: Option<&'a Path>,
        partitions: Option<usize>,
        max_chunk_gib: Option<f64>,
    ) -> Result<Self> {
        if partitions == Some(0) {
            return Err(EtlError::Usage("--partitions must be at least 1".into()));
        }
        let max_chunk_gib = max_chunk_gib.unwrap_or(DEFAULT_MAX_CHUNK_GIB);
        if max_chunk_gib.is_nan() || max_chunk_gib <= 0.0 {
            return Err(EtlError::Usage("--max-chunk-gib must be positive".into()));
        }
        Ok(StoreSettings {
            mode,
            rollup,
            configs,
            protection,
            tmp,
            partitions,
            max_chunk_bytes: max_chunk_gib * (1u64 << 30) as f64,
        })
    }
}

/// Builds one store from `inputs` into the existing directory `dir`: `pheno.blocks`,
/// `pheno.index`, `columnMeta.csv` and `etl-report.json`, each synced. Spill files go to a
/// temporary directory under `settings.tmp` or `dir`, removed before returning. The build
/// pass runs on the current rayon pool.
///
/// # Errors
/// [`EtlError::InvalidPatient`] for the first row whose patient number Java would fail
/// on, or that is negative; I/O, CSV and format errors.
pub(crate) fn build_store(
    dir: &Path,
    inputs: &[InputFile],
    settings: &StoreSettings<'_>,
) -> Result<LoadReport> {
    let started = Instant::now();
    let spill = TemporaryDir::create(settings.tmp.unwrap_or(dir), "hpds-etl-spill")?;
    let spill_dir = spill.path().to_path_buf();

    let input_bytes: u64 = inputs
        .iter()
        .map(|i| std::fs::metadata(&i.path).map(|m| m.len()).unwrap_or(0))
        .sum();
    let partitions = settings.partitions.unwrap_or_else(|| {
        (input_bytes.div_ceil(INPUT_BYTES_PER_PARTITION) as usize).clamp(1, MAX_DEFAULT_PARTITIONS)
    });
    tracing::info!(
        mode = ?settings.mode,
        files = inputs.len(),
        input_bytes,
        partitions,
        rollup = settings.rollup,
        encrypted = settings.protection.is_encrypted(),
        "loading"
    );

    let partitioned = route_to_spills(
        inputs,
        &SpillRouteSettings {
            mode: settings.mode,
            rollup: settings.rollup,
            configs: settings.configs,
            spill_dir: &spill_dir,
            partitions,
            max_chunk_bytes: settings.max_chunk_bytes,
        },
    )?;

    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(settings.protection.clone(), store_id, true);
    let ctx = BuildContext {
        mode: settings.mode,
        concepts: &partitioned.concepts,
        raw_path_ranks: &partitioned.raw_path_ranks,
        chunk_starts: &partitioned.chunk_starts,
        codec: &codec,
    };
    let build_started = Instant::now();
    let built: Vec<BuiltPartition> = partitioned
        .spill_files
        .par_iter()
        .enumerate()
        .map(|(i, spill)| {
            build_partition(&ctx, i, spill, spill_dir.join(format!("blocks-{i:05}.bin")))
        })
        .collect::<Result<Vec<_>>>()?;

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
        return Err(EtlError::InvalidPatient {
            file,
            record: failure.seq - start + 1,
            line: failure.line,
        });
    }

    let mut counts = LoadCounts {
        rows_read: partitioned.rows_read,
        dropped_short_row: partitioned.short_rows,
        inputs_with_byte_order_mark: partitioned.inputs_with_byte_order_mark,
        records_with_text_after_closing_quote: partitioned.records_with_text_after_closing_quote,
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

    let blocks_bytes = write_store(dir, &built, store_id, patients, settings.protection)?;
    drop(built);
    drop(spill);

    let report = LoadReport {
        mode: settings.mode,
        inputs: inputs.iter().map(|i| i.path.clone()).collect(),
        rollup: settings.rollup,
        encrypted: settings.protection.is_encrypted(),
        store_id: store_id.to_string(),
        id: store_id,
        counts,
        blocks_bytes,
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    let report_path = dir.join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    private_fs::write_file(&report_path, &json).map_err(EtlError::io("writing", &report_path))?;
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
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
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

    let index = PhenoIndex::new(store_header(protection, store_id), patients, metas)?;
    write_index_and_column_meta(dir, &index, protection)?;
    Ok(blocks_bytes)
}

/// Refuses an output directory the loaders must not write into: one holding a Java store
/// (`columnMeta.javabin` or `allObservationsStore.javabin`), one holding a partitioned store
/// of format 1 (a store per partition directory, or a combined store), and, for a plain
/// store, one holding an `encryption_key`.
///
/// # Errors
/// [`EtlError::Usage`] naming what makes `out` unsuitable.
pub(crate) fn check_output(out: &Path, key: &KeySource) -> Result<()> {
    if out.exists() && !out.join(INDEX_FILE_NAME).exists() {
        let old_partitions = crate::partitions::partition_dirs(out)?;
        if !old_partitions.is_empty() || out.join(LEGACY_COMBINED_DIR_NAME).exists() {
            return Err(EtlError::Usage(format!(
                "{} holds a partitioned store of format 1 (one store per partition directory); write the new store to another directory, or convert the old one with hpds-etl convert",
                out.display()
            )));
        }
    }
    for name in JAVA_STORE_FILE_NAMES {
        if out.join(name).exists() {
            return Err(EtlError::Usage(format!(
                "{} holds a Java store ({name}); write the Rust store to another directory",
                out.display()
            )));
        }
    }
    if matches!(key, KeySource::Plain) && out.join(DEFAULT_KEY_FILE_NAME).exists() {
        return Err(EtlError::Usage(format!(
            "{} holds an {DEFAULT_KEY_FILE_NAME}; plain stores are for benchmarking; use an output directory without a key",
            out.display()
        )));
    }
    Ok(())
}

fn resolve_rollup(options: &LoadOptions) -> Result<bool> {
    match (options.mode, options.rollup) {
        (Mode::NewSearch, Some(true)) => Err(EtlError::Usage(
            "new-search mode never rolls up variable names (CSVLoaderNewSearch has no way to turn it on)".into(),
        )),
        (Mode::NewSearch, _) => Ok(false),
        (Mode::Sequential, rollup) => Ok(rollup.unwrap_or(true)),
        (Mode::Partitioned, Some(false)) => Err(EtlError::Usage(
            "partitioned mode always rolls up variable names, as CSVLoader does".into(),
        )),
        (Mode::Partitioned, _) => Ok(true),
    }
}

fn resolve_inputs(options: &LoadOptions) -> Result<Vec<InputFile>> {
    if options.inputs.is_empty() {
        return Err(EtlError::Usage("at least one --input is required".into()));
    }
    match options.mode {
        Mode::NewSearch | Mode::Partitioned => {
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

/// The CSV config a load applies, and the file it came from (`None` when none applies).
pub(crate) struct ResolvedConfig {
    pub(crate) map: ConfigMap,
    pub(crate) path: Option<PathBuf>,
}

/// Warns when a CSV config file applies but has no entry for `allConcepts`, the only name
/// new-search and partitioned mode look a config up under, so it changes nothing.
pub(crate) fn warn_without_all_concepts_entry(config: &ResolvedConfig) {
    if let Some(path) = &config.path
        && config.map.get(PARTITION_CONFIG_KEY).is_none()
    {
        tracing::warn!(
            config = %path.display(),
            "the CSV config has no allConcepts entry, the only one this mode reads, so no dataset settings apply"
        );
    }
}

fn resolve_config(options: &LoadOptions) -> Result<ResolvedConfig> {
    let mut beside_inputs: Vec<PathBuf> = Vec::new();
    for input in &options.inputs {
        let dir = if input.is_dir() {
            input.clone()
        } else {
            parent_dir(input)
        };
        let candidate = dir.join(CONFIG_FILE_NAME);
        if candidate.is_file() && !beside_inputs.iter().any(|c| same_file(c, &candidate)) {
            beside_inputs.push(candidate);
        }
    }
    if options.no_config {
        for unused in &beside_inputs {
            tracing::warn!(
                unused = %unused.display(),
                "a {CONFIG_FILE_NAME} beside the inputs is not applied (--no-config)"
            );
        }
        tracing::info!("no CSV config applied (--no-config)");
        return Ok(ResolvedConfig {
            map: ConfigMap::default(),
            path: None,
        });
    }
    let applied = match &options.config {
        Some(path) => path.clone(),
        None => match beside_inputs.first() {
            Some(first) => first.clone(),
            None => {
                tracing::info!(
                    "no CSV config applied: no --config and no {CONFIG_FILE_NAME} beside the inputs"
                );
                return Ok(ResolvedConfig {
                    map: ConfigMap::default(),
                    path: None,
                });
            }
        },
    };
    for unused in beside_inputs.iter().filter(|c| !same_file(c, &applied)) {
        tracing::warn!(
            unused = %unused.display(),
            applied = %applied.display(),
            "a {CONFIG_FILE_NAME} beside the inputs is not applied"
        );
    }
    tracing::info!(config = %applied.display(), "applying CSV config");
    Ok(ResolvedConfig {
        map: ConfigMap::load(&applied)?,
        path: Some(applied),
    })
}

fn same_file(a: &Path, b: &Path) -> bool {
    match (a.canonicalize(), b.canonicalize()) {
        (Ok(a), Ok(b)) => a == b,
        _ => a == b,
    }
}

fn resolve_protection(options: &LoadOptions) -> Result<Protection> {
    let path = match &options.key {
        KeySource::Plain => return Ok(Protection::Plain),
        KeySource::File(path) => path.clone(),
        KeySource::OutputDirectoryKeyFile | KeySource::StoreDirectory => {
            options.out.join(DEFAULT_KEY_FILE_NAME)
        }
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

/// The directory a store is written into before it is moved to its final place:
/// `.<out name>.staging-<unique>` beside `out`, mode `0700`. Removed on drop, and on
/// SIGTERM or SIGINT, unless published.
pub(crate) struct Staging {
    dir: TemporaryDir,
    key: bool,
}

impl Staging {
    /// Creates the staging directory for `out`, first removing any left beside it by a run
    /// that was killed.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `out` has no name, is not a directory, or is on another
    /// filesystem than its parent, where the staging directory goes; I/O errors.
    pub(crate) fn create(out: &Path) -> Result<Self> {
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
        let parent = parent_dir(out);
        crate::partitions::check_same_filesystem(&parent, &[out.to_path_buf()])?;
        Ok(Staging {
            dir: TemporaryDir::create(&parent, &format!("{name}.staging"))?,
            key: false,
        })
    }

    /// The staging directory.
    pub(crate) fn path(&self) -> &Path {
        self.dir.path()
    }

    /// Stages a copy of the key file `source` as the store's `encryption_key`, mode `0600`,
    /// to be published with it.
    ///
    /// # Errors
    /// I/O errors reading or writing the key.
    pub(crate) fn stage_key(&mut self, source: &Path) -> Result<()> {
        let bytes = std::fs::read(source).map_err(EtlError::io("reading", source))?;
        let target = self.dir.path().join(DEFAULT_KEY_FILE_NAME);
        private_fs::write_file(&target, &bytes).map_err(EtlError::io("writing", &target))?;
        self.key = true;
        Ok(())
    }

    /// Moves the staged store, whose id is `id`, to `out`, and writes its
    /// [`crate::store::GENERATION_FILE_NAME`] marker. Every staged file is already synced.
    ///
    /// When `out` does not exist the marker is staged too, and the staging directory is
    /// synced and renamed to `out`, so the whole store appears at once. When it does, the
    /// publish holds the store lock ([`lock_store`]): `pheno.blocks`, `columnMeta.csv` and
    /// `etl-report.json` are renamed over the old files and `pheno.index` last, so a reader
    /// that finds the new index finds the new blocks (until then the old index refuses the
    /// new blocks by store id), then the staged key, and the marker is replaced last. A
    /// reader sees the marker name the old id until the new store is complete. An interrupt
    /// waits until the moves are done.
    ///
    /// # Errors
    /// I/O errors syncing, renaming or writing the marker.
    pub(crate) fn publish(mut self, out: &Path, id: StoreId) -> Result<()> {
        let staged = self.dir.path().to_path_buf();
        let store_lock = if out.exists() {
            Some(lock_store(out)?)
        } else {
            None
        };
        let mut registered = cleanup::lock();
        if store_lock.is_none() {
            write_generation(&staged, id)?;
            self.dir.release_lock()?;
            private_fs::sync_dir(&staged).map_err(EtlError::io("syncing", &staged))?;
            std::fs::rename(&staged, out).map_err(EtlError::io("moving the store to", out))?;
            self.dir.disarm(&mut registered);
            drop(registered);
            let parent = parent_dir(out);
            return private_fs::sync_dir(&parent).map_err(EtlError::io("syncing", &parent));
        }
        private_fs::sync_dir(&staged).map_err(EtlError::io("syncing", &staged))?;
        let published = crate::partitions::replace_store_files(&staged, out).and_then(|()| {
            if self.key {
                let target = out.join(DEFAULT_KEY_FILE_NAME);
                std::fs::rename(staged.join(DEFAULT_KEY_FILE_NAME), &target)
                    .map_err(EtlError::io("moving into place", &target))?;
                private_fs::sync_dir(out).map_err(EtlError::io("syncing", out))?;
            }
            write_generation(out, id)
        });
        drop(registered);
        drop(store_lock);
        published
    }
}

fn parent_dir(path: &Path) -> PathBuf {
    match path.parent() {
        Some(p) if !p.as_os_str().is_empty() => p.to_path_buf(),
        _ => PathBuf::from("."),
    }
}
