use std::collections::BTreeMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_etl::load::COLUMN_META_CSV_NAME;
use hpds_etl::load::{KeySource, LoadOptions, Mode, PartitionedLoadOptions};
use hpds_etl::migrate::{EXPORT_CONCEPTS_FILE, MigrateOptions};
use hpds_etl::partitions::StoreLayout;
use hpds_etl::store::{OpenStore, resolve_protection};
use hpds_etl::{EtlError, Result};
use hpds_format::{INDEX_FILE_NAME, Protection};

/// Builds and maintains the Rust HPDS phenotype store.
#[derive(Parser)]
#[command(name = "hpds-etl", version)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Load phenotype CSV into a new store, following one of the Java loaders.
    Load(LoadArgs),
    /// Build a new store from a javabin store exported by the Java ExportForMigration loader,
    /// or one store per partition from a directory of per-partition exports.
    Migrate(MigrateArgs),
    /// Remove the concepts named in a file from a store, as RemoveConceptFromMetadata does.
    RemoveConcepts(RemoveConceptsArgs),
    /// Regenerate a store's columnMeta.csv from its index.
    ColumnMetaCsv(ColumnMetaCsvArgs),
    /// Write a store as canonical CSV in DumpSourceCSV layout and order, or its patient IDs.
    Dump(DumpArgs),
}

#[derive(Args)]
struct KeyArgs {
    /// Key file, read with Crypto.loadKey rules. Defaults to encryption_key in the store
    /// directory.
    #[arg(long, conflicts_with = "plain")]
    key_file: Option<PathBuf>,
    /// The store is unencrypted, for local benchmarking only.
    #[arg(long)]
    plain: bool,
}

impl KeyArgs {
    /// The key source these flags select, `default` when neither is given.
    fn source(&self, default: KeySource) -> KeySource {
        match (self.plain, &self.key_file) {
            (true, _) => KeySource::Plain,
            (false, Some(path)) => KeySource::File(path.clone()),
            (false, None) => default,
        }
    }
}

#[derive(Args)]
struct MigrateArgs {
    /// Directory holding the export's concepts.bin and patients.txt, or one such directory
    /// per partition, named after the partition, which migrates into <out>/<partition>/.
    #[arg(long)]
    export: PathBuf,
    /// Store directory to create or replace.
    #[arg(long)]
    out: PathBuf,
    /// The javabin store's key file, which opens the export and encrypts the new store.
    /// Defaults to encryption_key in the output directory.
    #[arg(long)]
    key_file: Option<PathBuf>,
    /// Worker threads for sealing blocks.
    #[arg(long)]
    threads: Option<usize>,
    /// Leave out concepts whose recorded metadata disagrees with their observations and
    /// list them in etl-report.json, instead of stopping.
    #[arg(long)]
    skip_inconsistent: bool,
}

#[derive(Args)]
struct RemoveConceptsArgs {
    /// Store directory to change in place. A partitioned store has every partition changed.
    #[arg(long)]
    store: PathBuf,
    /// File naming one concept path per line (conceptsToRemove.txt).
    #[arg(long)]
    concepts: PathBuf,
    /// Rewrite only pheno.index, leaving the removed concepts' blocks in pheno.blocks.
    #[arg(long)]
    no_compact: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct ColumnMetaCsvArgs {
    /// Store directory to read. A partitioned store has each partition's columnMeta.csv
    /// regenerated in place.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to columnMeta.csv in the store directory. For a partitioned
    /// store, only with --merged.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Partitioned stores only: write one columnMeta.csv merging every partition, for
    /// dictionary hydration. Java produces no such file.
    #[arg(long)]
    merged: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct DumpArgs {
    /// Store directory to read.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to standard output. For a partitioned store without
    /// --merged, a directory that receives one <partition>.csv per partition.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Write the patient ID set, one per line, instead of the observations.
    #[arg(long)]
    patients: bool,
    /// Partitioned stores only: write one file, the partitions' dumps concatenated in
    /// partition name order under one header, or with --patients the union of their
    /// patient ID sets.
    #[arg(long)]
    merged: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Clone, Copy, ValueEnum)]
enum ModeArg {
    /// CSVLoaderNewSearch: one allConcepts.csv in file order, no rollup.
    NewSearch,
    /// SequentialLoader on CSV input (LowRAMMultiCSVLoader): one or more files, rollup on.
    Sequential,
    /// CSVLoader on the harmonization branch: every <partition>-allConcepts.csv in
    /// --input-dir to its own store in <out>/<partition>/, headerless, rollup always on.
    Partitioned,
}

#[derive(Args)]
struct LoadArgs {
    /// Which Java loader to reproduce.
    #[arg(long, value_enum)]
    mode: ModeArg,
    /// Input CSV files, in load order. In sequential mode a directory loads its .csv files
    /// in directory order. Not used in partitioned mode.
    #[arg(long = "input", num_args = 1.., required_unless_present = "input_dir")]
    inputs: Vec<PathBuf>,
    /// Partitioned mode: directory holding the <partition>-allConcepts.csv files.
    #[arg(long, conflicts_with = "inputs")]
    input_dir: Option<PathBuf>,
    /// Store directory to create or replace. In partitioned mode, the directory the
    /// <partition>/ stores and the shared encryption_key go into.
    #[arg(long)]
    out: PathBuf,
    /// Key file, read with Crypto.loadKey rules. Defaults to <out>/encryption_key.
    #[arg(long, conflicts_with = "plain")]
    key_file: Option<PathBuf>,
    /// Write an unencrypted store, for local benchmarking only. Refused when <out> holds an
    /// encryption_key.
    #[arg(long)]
    plain: bool,
    /// Turn variable-name rollup on (the sequential default; refused in new-search mode).
    #[arg(long, conflicts_with = "no_rollup")]
    rollup: bool,
    /// Turn variable-name rollup off, as Java's NO_ROLLUP argument does.
    #[arg(long)]
    no_rollup: bool,
    /// CSV config JSON, mapping input file names to dataset settings. Defaults to the first
    /// config.json in a directory input or beside an input file.
    #[arg(long)]
    config: Option<PathBuf>,
    /// Number of spill partitions. Defaults to one per 32 MiB of input, at most 128.
    #[arg(long)]
    partitions: Option<usize>,
    /// Worker threads for the build pass. Each holds one partition in memory.
    #[arg(long)]
    threads: Option<usize>,
    /// Directory for spill files.
    #[arg(long)]
    tmp: Option<PathBuf>,
    /// Sequential mode only: chunk size in GiB, as Java's MAX_CHUNK_SIZE.
    #[arg(long)]
    max_chunk_gib: Option<f64>,
}

impl LoadArgs {
    /// The options of a partitioned load.
    ///
    /// # Errors
    /// [`EtlError::Usage`] for a missing --input-dir and for flags partitioned mode does not
    /// take.
    fn partitioned(self) -> Result<PartitionedLoadOptions> {
        let Some(input_dir) = self.input_dir else {
            return Err(EtlError::Usage(
                "partitioned mode reads --input-dir, a directory of <partition>-allConcepts.csv files".into(),
            ));
        };
        if self.no_rollup {
            return Err(EtlError::Usage(
                "partitioned mode always rolls up variable names, as CSVLoader does".into(),
            ));
        }
        if self.max_chunk_gib.is_some() {
            return Err(EtlError::Usage(
                "--max-chunk-gib applies to sequential mode only".into(),
            ));
        }
        let mut options = PartitionedLoadOptions::new(input_dir, self.out);
        options.key = match (self.plain, self.key_file) {
            (true, _) => KeySource::Plain,
            (false, Some(path)) => KeySource::File(path),
            (false, None) => KeySource::OutputDirectoryKeyFile,
        };
        options.config = self.config;
        options.spill_partitions = self.partitions;
        options.threads = self.threads;
        options.tmp = self.tmp;
        Ok(options)
    }
}

impl From<LoadArgs> for LoadOptions {
    fn from(args: LoadArgs) -> Self {
        let mode = match args.mode {
            ModeArg::NewSearch => Mode::NewSearch,
            ModeArg::Sequential => Mode::Sequential,
            ModeArg::Partitioned => Mode::Partitioned,
        };
        let mut options = LoadOptions::new(mode, args.inputs, args.out);
        options.key = match (args.plain, args.key_file) {
            (true, _) => KeySource::Plain,
            (false, Some(path)) => KeySource::File(path),
            (false, None) => KeySource::OutputDirectoryKeyFile,
        };
        options.rollup = match (args.rollup, args.no_rollup) {
            (true, _) => Some(true),
            (_, true) => Some(false),
            _ => None,
        };
        options.config = args.config;
        options.partitions = args.partitions;
        options.threads = args.threads;
        options.tmp = args.tmp;
        options.max_chunk_gib = args.max_chunk_gib;
        options
    }
}

fn main() -> ExitCode {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .with_writer(std::io::stderr)
        .init();
    tracing::info!("{}", hpds_etl::banner());
    let cli = Cli::parse();
    if let Err(e) = remove_temporary_dirs_on_interrupt() {
        eprintln!("hpds-etl: cannot install the SIGTERM and SIGINT handler: {e}");
        return ExitCode::FAILURE;
    }
    let result = match cli.command {
        Command::Load(args) => run_load(args),
        Command::Migrate(args) => run_migrate(args),
        Command::RemoveConcepts(args) => run_remove_concepts(args),
        Command::ColumnMetaCsv(args) => run_column_meta_csv(args),
        Command::Dump(args) => run_dump(args),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            tracing::error!("{e}");
            eprintln!("hpds-etl: {e}");
            ExitCode::FAILURE
        }
    }
}

/// Starts a thread that, on SIGTERM or SIGINT, removes every staging and spill directory
/// this run created and exits with status 128 plus the signal number.
///
/// # Errors
/// The I/O error of registering the handlers.
fn remove_temporary_dirs_on_interrupt() -> std::io::Result<()> {
    use signal_hook::consts::{SIGINT, SIGTERM};
    let mut signals = signal_hook::iterator::Signals::new([SIGINT, SIGTERM])?;
    std::thread::spawn(move || {
        if let Some(signal) = signals.forever().next() {
            tracing::warn!(signal, "interrupted; removing temporary directories");
            hpds_etl::cleanup::remove_registered();
            std::process::exit(128 + signal);
        }
    });
    Ok(())
}

fn run_load(args: LoadArgs) -> Result<()> {
    if matches!(args.mode, ModeArg::Partitioned) {
        let report = hpds_etl::load::load_partitioned(&args.partitioned()?)?;
        let counts: std::collections::BTreeMap<&str, _> = report
            .partitions
            .iter()
            .map(|(name, partition)| (name.as_str(), &partition.counts))
            .collect();
        print_json(&counts);
        return Ok(());
    }
    if args.input_dir.is_some() {
        return Err(EtlError::Usage(
            "--input-dir is for partitioned mode; use --input".into(),
        ));
    }
    let report = hpds_etl::load::load(&args.into())?;
    print_json(&report.counts);
    Ok(())
}

fn print_json<T: serde::Serialize>(value: &T) {
    if let Ok(json) = serde_json::to_string_pretty(value) {
        println!("{json}");
    }
}

fn run_migrate(args: MigrateArgs) -> Result<()> {
    let options = MigrateOptions {
        export: args.export,
        out: args.out,
        key: match args.key_file {
            Some(path) => KeySource::File(path),
            None => KeySource::OutputDirectoryKeyFile,
        },
        threads: args.threads,
        skip_inconsistent: args.skip_inconsistent,
    };
    match StoreLayout::detect(&options.export, EXPORT_CONCEPTS_FILE)? {
        StoreLayout::Root => print_json(&hpds_etl::migrate::migrate(&options)?),
        StoreLayout::Partitioned(_) => {
            print_json(&hpds_etl::migrate::migrate_partitioned(&options)?)
        }
    }
    Ok(())
}

/// The layout of the store a maintenance command reads, and the protection its key
/// selects: for a partitioned store the key defaults to the root encryption_key.
fn open_layout(store: &Path, key: &KeyArgs) -> Result<(StoreLayout, Protection)> {
    let layout = StoreLayout::detect(store, INDEX_FILE_NAME)?;
    let protection = resolve_protection(&key.source(KeySource::StoreDirectory), store)?;
    Ok((layout, protection))
}

fn run_remove_concepts(args: RemoveConceptsArgs) -> Result<()> {
    let (layout, protection) = open_layout(&args.store, &args.key)?;
    match layout {
        StoreLayout::Root => print_json(&hpds_etl::store_maintenance::remove_concepts(
            &args.store,
            &args.concepts,
            &protection,
            !args.no_compact,
        )?),
        StoreLayout::Partitioned(partitions) => {
            let mut reports = BTreeMap::new();
            for partition in partitions {
                let report = hpds_etl::store_maintenance::remove_concepts(
                    &partition.dir,
                    &args.concepts,
                    &protection,
                    !args.no_compact,
                )?;
                tracing::info!(partition = %partition.name, removed = report.removed, "concepts removed");
                reports.insert(partition.name, report);
            }
            print_json(&reports);
        }
    }
    Ok(())
}

fn run_column_meta_csv(args: ColumnMetaCsvArgs) -> Result<()> {
    let (layout, protection) = open_layout(&args.store, &args.key)?;
    match layout {
        StoreLayout::Root if args.merged => {
            return Err(EtlError::Usage(format!(
                "--merged needs a partitioned store; {} is not partitioned",
                args.store.display()
            )));
        }
        StoreLayout::Root => {
            let rows = hpds_etl::store_maintenance::regenerate_column_meta_csv(
                &args.store,
                args.out.as_deref(),
                &protection,
            )?;
            tracing::info!(rows, "columnMeta.csv written");
        }
        StoreLayout::Partitioned(partitions) if args.merged => {
            let dirs: Vec<(String, PathBuf)> =
                partitions.into_iter().map(|p| (p.name, p.dir)).collect();
            let target = args
                .out
                .unwrap_or_else(|| args.store.join(COLUMN_META_CSV_NAME));
            let rows = hpds_etl::store_maintenance::write_merged_column_meta_csv(
                &dirs,
                &target,
                &protection,
            )?;
            tracing::info!(rows, out = %target.display(), "merged columnMeta.csv written");
        }
        StoreLayout::Partitioned(partitions) => {
            if args.out.is_some() {
                return Err(EtlError::Usage(
                    "--out takes the merged file; without --merged each partition's columnMeta.csv is regenerated in place".into(),
                ));
            }
            for partition in partitions {
                let rows = hpds_etl::store_maintenance::regenerate_column_meta_csv(
                    &partition.dir,
                    None,
                    &protection,
                )?;
                tracing::info!(partition = %partition.name, rows, "columnMeta.csv written");
            }
        }
    }
    Ok(())
}

fn run_dump(args: DumpArgs) -> Result<()> {
    let (layout, protection) = open_layout(&args.store, &args.key)?;
    let partitions = match layout {
        StoreLayout::Root if args.merged => {
            return Err(EtlError::Usage(format!(
                "--merged needs a partitioned store; {} is not partitioned",
                args.store.display()
            )));
        }
        StoreLayout::Root => {
            let store = OpenStore::open(&args.store, protection)?;
            return write_to(args.out.as_deref(), |out| {
                write_dump(std::slice::from_ref(&store), args.patients, out)
            });
        }
        StoreLayout::Partitioned(partitions) => partitions,
    };
    if args.merged {
        let stores = partitions
            .iter()
            .map(|p| OpenStore::open(&p.dir, protection.clone()))
            .collect::<Result<Vec<_>>>()?;
        return write_to(args.out.as_deref(), |out| {
            write_dump(&stores, args.patients, out)
        });
    }
    let Some(dir) = args.out else {
        return Err(EtlError::Usage(
            "a partitioned store dumps one file per partition: give --out <directory>, or --merged for one file".into(),
        ));
    };
    std::fs::create_dir_all(&dir).map_err(|e| io_error("creating", &dir, e))?;
    for partition in partitions {
        let store = OpenStore::open(&partition.dir, protection.clone())?;
        let path = dir.join(format!("{}.csv", partition.name));
        write_to(Some(&path), |out| {
            write_dump(std::slice::from_ref(&store), args.patients, out)
        })?;
    }
    Ok(())
}

/// Calls `write` on a buffered writer to `path`, or to standard output when `None`.
fn write_to(path: Option<&Path>, write: impl FnOnce(&mut dyn Write) -> Result<()>) -> Result<()> {
    match path {
        Some(path) => {
            let file = File::create(path).map_err(|e| io_error("creating", path, e))?;
            let mut out = BufWriter::with_capacity(1 << 20, file);
            write(&mut out)?;
            out.flush().map_err(|e| io_error("writing", path, e))
        }
        None => write(&mut std::io::stdout().lock()),
    }
}

fn write_dump(stores: &[OpenStore], patients: bool, out: &mut dyn Write) -> Result<()> {
    if patients {
        let count = hpds_etl::dump::dump_patients_merged(stores, out)?;
        tracing::info!(patients = count, "patient IDs written");
    } else {
        let counts = hpds_etl::dump::dump_merged(stores, out)?;
        tracing::info!(
            concepts = counts.concepts,
            observations = counts.observations,
            "dump written"
        );
    }
    Ok(())
}

fn io_error(action: &'static str, path: &Path, source: std::io::Error) -> EtlError {
    EtlError::Io {
        action,
        path: path.to_path_buf(),
        source,
    }
}
