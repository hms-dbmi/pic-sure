use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_etl::load::{KeySource, LoadOptions, Mode};
use hpds_etl::migrate::MigrateOptions;
use hpds_etl::store::{OpenStore, resolve_protection};
use hpds_etl::{EtlError, Result};

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
    /// Build a new store from a javabin store exported by the Java ExportForMigration loader.
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
    fn source(&self) -> KeySource {
        match (self.plain, &self.key_file) {
            (true, _) => KeySource::Plain,
            (false, Some(path)) => KeySource::File(path.clone()),
            (false, None) => KeySource::OutputDirectoryKeyFile,
        }
    }
}

#[derive(Args)]
struct MigrateArgs {
    /// Directory holding the export's concepts.bin and patients.txt.
    #[arg(long)]
    export: PathBuf,
    /// Store directory to create or replace.
    #[arg(long)]
    out: PathBuf,
    #[command(flatten)]
    key: KeyArgs,
    /// Worker threads for sealing blocks.
    #[arg(long)]
    threads: Option<usize>,
}

#[derive(Args)]
struct RemoveConceptsArgs {
    /// Store directory to change in place.
    #[arg(long)]
    store: PathBuf,
    /// File naming one concept path per line (conceptsToRemove.txt).
    #[arg(long)]
    concepts: PathBuf,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct ColumnMetaCsvArgs {
    /// Store directory to read.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to columnMeta.csv in the store directory.
    #[arg(long)]
    out: Option<PathBuf>,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct DumpArgs {
    /// Store directory to read.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to standard output.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Write the patient ID set, one per line, instead of the observations.
    #[arg(long)]
    patients: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Clone, Copy, ValueEnum)]
enum ModeArg {
    /// CSVLoaderNewSearch: one allConcepts.csv in file order, no rollup.
    NewSearch,
    /// SequentialLoader on CSV input (LowRAMMultiCSVLoader): one or more files, rollup on.
    Sequential,
}

#[derive(Args)]
struct LoadArgs {
    /// Which Java loader to reproduce.
    #[arg(long, value_enum)]
    mode: ModeArg,
    /// Input CSV files, in load order. In sequential mode a directory loads its .csv files
    /// in directory order.
    #[arg(long = "input", required = true, num_args = 1..)]
    inputs: Vec<PathBuf>,
    /// Store directory to create or replace.
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

impl From<LoadArgs> for LoadOptions {
    fn from(args: LoadArgs) -> Self {
        let mode = match args.mode {
            ModeArg::NewSearch => Mode::NewSearch,
            ModeArg::Sequential => Mode::Sequential,
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
        Command::Load(args) => hpds_etl::load::load(&args.into()).map(|report| {
            if let Ok(json) = serde_json::to_string_pretty(&report.counts) {
                println!("{json}");
            }
        }),
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

fn print_json<T: serde::Serialize>(value: &T) {
    if let Ok(json) = serde_json::to_string_pretty(value) {
        println!("{json}");
    }
}

fn run_migrate(args: MigrateArgs) -> Result<()> {
    let report = hpds_etl::migrate::migrate(&MigrateOptions {
        export: args.export,
        out: args.out,
        key: args.key.source(),
        threads: args.threads,
    })?;
    print_json(&report);
    Ok(())
}

fn run_remove_concepts(args: RemoveConceptsArgs) -> Result<()> {
    let protection = resolve_protection(&args.key.source(), &args.store)?;
    let report = hpds_etl::maintain::remove_concepts(&args.store, &args.concepts, &protection)?;
    print_json(&report);
    Ok(())
}

fn run_column_meta_csv(args: ColumnMetaCsvArgs) -> Result<()> {
    let protection = resolve_protection(&args.key.source(), &args.store)?;
    let rows = hpds_etl::maintain::regenerate_column_meta_csv(
        &args.store,
        args.out.as_deref(),
        &protection,
    )?;
    tracing::info!(rows, "columnMeta.csv written");
    Ok(())
}

fn run_dump(args: DumpArgs) -> Result<()> {
    let protection = resolve_protection(&args.key.source(), &args.store)?;
    let store = OpenStore::open(&args.store, protection)?;
    match &args.out {
        Some(path) => {
            let file = File::create(path).map_err(|e| io_error("creating", path, e))?;
            write_dump(
                &store,
                args.patients,
                &mut BufWriter::with_capacity(1 << 20, file),
            )
        }
        None => write_dump(&store, args.patients, &mut std::io::stdout().lock()),
    }
}

fn write_dump(store: &OpenStore, patients: bool, out: &mut impl Write) -> Result<()> {
    if patients {
        let count = hpds_etl::dump::dump_patients(store, out)?;
        tracing::info!(patients = count, "patient IDs written");
    } else {
        let counts = hpds_etl::dump::dump(store, out)?;
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
