use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_etl::load::{KeySource, LoadOptions, Mode};

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
    #[arg(long, value_enum, default_value = "new-search")]
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
    /// Write an unencrypted store, for local benchmarking only.
    #[arg(long)]
    plain: bool,
    /// Turn variable-name rollup on (the sequential default; refused in new-search mode).
    #[arg(long, conflicts_with = "no_rollup")]
    rollup: bool,
    /// Turn variable-name rollup off, as Java's NO_ROLLUP argument does.
    #[arg(long)]
    no_rollup: bool,
    /// CSV config JSON (config.json), mapping input file names to dataset settings.
    #[arg(long)]
    config: Option<PathBuf>,
    /// Number of spill partitions.
    #[arg(long)]
    partitions: Option<usize>,
    /// Worker threads for the build pass.
    #[arg(long)]
    threads: Option<usize>,
    /// Directory for spill files.
    #[arg(long)]
    tmp: Option<PathBuf>,
    /// Sequential mode: chunk size in GiB, as Java's MAX_CHUNK_SIZE.
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
            (false, None) => KeySource::OutputDirectory,
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
    let result = match Cli::parse().command {
        Command::Load(args) => hpds_etl::load::load(&args.into()).map(|report| {
            if let Ok(json) = serde_json::to_string_pretty(&report.counts) {
                println!("{json}");
            }
        }),
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
