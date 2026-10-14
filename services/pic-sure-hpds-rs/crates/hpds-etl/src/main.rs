use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_etl::convert::ConvertOptions;
use hpds_etl::load::{
    KeySource, LEGACY_COMBINED_DIR_NAME, LoadOptions, Mode, PartitionedLoadOptions,
};
use hpds_etl::migrate::{EXPORT_CONCEPTS_FILE, MigrateOptions};
use hpds_etl::output;
use hpds_etl::partitions::{StoreLayout, partition_dirs};
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
    /// or a consent-partitioned store from a directory of per-partition exports.
    Migrate(MigrateArgs),
    /// Build a new store from a store written by Rust HPDS v1 to v3 (format 1), one store
    /// per partition directory or one unpartitioned store.
    Convert(ConvertArgs),
    /// Remove the concepts named in a file from a store, as RemoveConceptFromMetadata does.
    RemoveConcepts(RemoveConceptsArgs),
    /// Finish a publish into a store that a crash or kill interrupted, and remove the
    /// temporary files it left. Does nothing to a store with no interrupted publish.
    Recover(RecoverArgs),
    /// Regenerate a store's columnMeta.csv from its index.
    ColumnMetaCsv(ColumnMetaCsvArgs),
    /// Write a store as canonical CSV in DumpSourceCSV layout and order, or its patient IDs.
    Dump(DumpArgs),
}

#[derive(Args)]
struct ConvertArgs {
    /// The format 1 store directory to read. It is never changed, and its combined store is
    /// never read.
    #[arg(long)]
    store: PathBuf,
    /// Store directory to create or replace. Not the store itself or inside it.
    #[arg(long)]
    out: PathBuf,
    /// Worker threads for reading and sealing blocks.
    #[arg(long)]
    threads: Option<usize>,
    #[command(flatten)]
    key: KeyArgs,
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
    /// per partition, named after the partition, which becomes a consent partition of the
    /// store.
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
    /// Store directory to change in place, under its store lock.
    #[arg(long)]
    store: PathBuf,
    /// File naming one concept path per line (conceptsToRemove.txt). Each line must equal
    /// a concept path exactly; a line naming no concept fails the command before anything
    /// is written.
    #[arg(long)]
    concepts: PathBuf,
    /// Remove the concepts that exist even when some lines name no concept (for example a
    /// rerun after the concepts are gone), and list those lines in the report.
    #[arg(long)]
    allow_missing: bool,
    /// Report what would be removed (paths, observations, patients, blocks file size) and
    /// write nothing. Fails after the report when a run would fail on lines naming no
    /// concept.
    #[arg(long)]
    dry_run: bool,
    /// Rewrite only pheno.index and leave the removed concepts' observations in
    /// pheno.blocks, encrypted but present, as unreferenced bytes. Unsafe when withdrawing
    /// data: the default compaction is what takes them out of the store's files.
    #[arg(long)]
    keep_removed_blocks: bool,
    /// Replaced by --keep-removed-blocks; refused with a message naming it.
    #[arg(long, hide = true)]
    no_compact: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct RecoverArgs {
    /// Store directory to recover, under its store lock.
    #[arg(long)]
    store: PathBuf,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct ColumnMetaCsvArgs {
    /// Store directory to read. For a partitioned store, each concept's row combines its
    /// metadata across partitions (counts summed, category values unioned), as the query
    /// service's /search does; no per-partition file is written.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to columnMeta.csv in the store directory.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Partitioned stores only, and the default for them: kept so earlier invocations run
    /// unchanged.
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
    /// File to write, mode 0600. Defaults to standard output. For a partitioned store
    /// without --concat, a directory that receives one <partition>.csv per partition: a new
    /// one is created mode 0700, an existing one must grant group and others nothing. Never
    /// inside the store or any of its partition directories.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Replace output files that already exist, through a temporary file renamed over each.
    /// Without it an existing output file is refused.
    #[arg(long)]
    force: bool,
    /// Write the patient ID set, one per line, instead of the observations.
    #[arg(long)]
    patients: bool,
    /// Partitioned stores only: write one file, the partitions' observation dumps
    /// concatenated in partition name order under one header (every row kept, nothing
    /// combined), or with --patients the union of their patient ID sets. Unlike
    /// column-meta-csv --merged, which combines each concept's metadata across partitions.
    #[arg(long, alias = "merged")]
    concat: bool,
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
    /// --input-dir to a consent partition of the store, headerless, rollup always on.
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
    /// config.json in a directory input or beside an input file. Partitioned mode needs
    /// this or --no-config: Java's CSVLoader reads /opt/local/hpds_input/config.json, so
    /// pass the file mounted there.
    #[arg(long, conflicts_with = "no_config")]
    config: Option<PathBuf>,
    /// Apply no CSV config, not even a config.json beside the inputs.
    #[arg(long)]
    no_config: bool,
    /// Number of spill files the rows are routed into by concept (unrelated to consent
    /// partitions). Defaults to one per 32 MiB of input, at most 128. --partitions is a
    /// deprecated name for it.
    #[arg(long = "spill-partitions", alias = "partitions")]
    spill_partitions: Option<usize>,
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
        options.no_config = self.no_config;
        options.spill_partitions = self.spill_partitions;
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
        options.no_config = args.no_config;
        options.partitions = args.spill_partitions;
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
    if std::env::args_os().any(|arg| {
        arg.to_str()
            .is_some_and(|a| a == "--partitions" || a.starts_with("--partitions="))
    }) {
        tracing::warn!("--partitions is deprecated; use --spill-partitions");
    }
    if let Err(e) = remove_temporary_dirs_on_interrupt() {
        eprintln!("hpds-etl: cannot install the SIGTERM and SIGINT handler: {e}");
        return ExitCode::FAILURE;
    }
    let result = match cli.command {
        Command::Load(args) => run_load(args),
        Command::Migrate(args) => run_migrate(args),
        Command::Convert(args) => run_convert(args),
        Command::RemoveConcepts(args) => run_remove_concepts(args),
        Command::Recover(args) => run_recover(args),
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
        print_json(&serde_json::json!({ "partitions": counts, "store": report.store }));
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

fn run_convert(args: ConvertArgs) -> Result<()> {
    let options = ConvertOptions {
        key: args.key.source(KeySource::StoreDirectory),
        store: args.store,
        out: args.out,
        threads: args.threads,
    };
    print_json(&hpds_etl::convert::convert(&options)?);
    Ok(())
}

/// Checks that `store` holds a store a maintenance command can read, and resolves the
/// protection its key selects (the key defaults to the store's `encryption_key`). A
/// directory holding a partitioned store of format 1 (no root `pheno.index`, but partition
/// directories or a combined store) is refused naming `hpds-etl convert`; a format 1 root
/// index is refused when it is read.
///
/// # Errors
/// [`EtlError::Usage`] for a missing or format 1 partitioned store, and key errors.
fn open_layout(store: &Path, key: &KeyArgs) -> Result<Protection> {
    if !store.join(INDEX_FILE_NAME).is_file() {
        let old = store.is_dir()
            && (store.join(LEGACY_COMBINED_DIR_NAME).exists()
                || !partition_dirs(store)?.is_empty());
        return Err(EtlError::Usage(if old {
            format!(
                "{} holds a partitioned store of format 1 (one store per partition directory), which only hpds-etl convert reads; convert it with hpds-etl convert --store {} --out <dir>",
                store.display(),
                store.display()
            )
        } else {
            format!("{} holds no {INDEX_FILE_NAME}", store.display())
        }));
    }
    resolve_protection(&key.source(KeySource::StoreDirectory), store)
}

fn run_remove_concepts(args: RemoveConceptsArgs) -> Result<()> {
    if args.no_compact {
        return Err(EtlError::Usage(
            "--no-compact is now --keep-removed-blocks: it leaves the removed concepts' observations in pheno.blocks, so name that explicitly, or drop it to compact".into(),
        ));
    }
    if args.keep_removed_blocks {
        tracing::warn!(
            "--keep-removed-blocks: the removed concepts' observations stay in pheno.blocks, encrypted but present, until a run without it compacts the store"
        );
    }
    open_layout(&args.store, &args.key)?;
    let (protection, _) = hpds_etl::partitions::resolve_shared_key(
        &args.key.source(KeySource::StoreDirectory),
        &args.store,
    )?;
    let options = hpds_etl::store_maintenance::RemoveOptions {
        allow_missing: args.allow_missing,
        keep_removed_blocks: args.keep_removed_blocks,
        dry_run: args.dry_run,
        ..Default::default()
    };
    let report = hpds_etl::store_maintenance::remove_concepts(
        &args.store,
        &args.concepts,
        &protection,
        &options,
    )?;
    print_json(&report);
    if report.dry_run && !report.not_found.is_empty() && !args.allow_missing {
        return Err(EtlError::Usage(format!(
            "dry run: {} lines of {} name no concept (listed under not_found), so a run would remove nothing; fix the file or pass --allow-missing",
            report.not_found.len(),
            args.concepts.display()
        )));
    }
    Ok(())
}

fn run_recover(args: RecoverArgs) -> Result<()> {
    open_layout(&args.store, &args.key)?;
    let (protection, _) = hpds_etl::partitions::resolve_shared_key(
        &args.key.source(KeySource::StoreDirectory),
        &args.store,
    )?;
    let _lock = hpds_etl::store::lock_store(&args.store)?;
    print_json(&hpds_etl::publish::recover(&args.store, &protection)?);
    Ok(())
}

fn run_column_meta_csv(args: ColumnMetaCsvArgs) -> Result<()> {
    let protection = open_layout(&args.store, &args.key)?;
    if args.merged && !hpds_etl::store::read_index(&args.store, &protection)?.is_partitioned() {
        return Err(EtlError::Usage(format!(
            "--merged needs a partitioned store; {} is not partitioned",
            args.store.display()
        )));
    }
    let rows = hpds_etl::store_maintenance::regenerate_column_meta_csv(
        &args.store,
        args.out.as_deref(),
        &protection,
    )?;
    tracing::info!(rows, "columnMeta.csv written");
    Ok(())
}

fn run_dump(args: DumpArgs) -> Result<()> {
    let protection = open_layout(&args.store, &args.key)?;
    if let Some(out) = &args.out {
        output::check_outside_stores(out, &[&args.store])?;
    }
    let store = OpenStore::open(&args.store, protection)?;
    let table = store.index.partition_table();
    if table.is_empty() {
        if args.concat {
            return Err(EtlError::Usage(format!(
                "--concat needs a partitioned store; {} is not partitioned",
                args.store.display()
            )));
        }
        return write_to(args.out.as_deref(), args.force, |out| {
            if args.patients {
                let count = hpds_etl::dump::dump_patients(store.index.patients(), out)?;
                tracing::info!(patients = count, "patient IDs written");
            } else {
                let counts = hpds_etl::dump::dump(&store, out)?;
                tracing::info!(
                    concepts = counts.concepts,
                    observations = counts.observations,
                    "dump written"
                );
            }
            Ok(())
        });
    }
    let mut names: Vec<(usize, &str)> = table
        .partitions
        .iter()
        .enumerate()
        .map(|(i, p)| (i, p.name.as_str()))
        .collect();
    names.sort_by(|a, b| a.1.cmp(b.1));
    if args.concat {
        if args.patients {
            return write_to(args.out.as_deref(), args.force, |out| {
                let count = hpds_etl::dump::dump_patients(store.index.patients(), out)?;
                tracing::info!(patients = count, "patient IDs written");
                Ok(())
            });
        }
        let parent = match &args.out {
            Some(out) => out
                .parent()
                .filter(|p| !p.as_os_str().is_empty())
                .map_or_else(|| PathBuf::from("."), Path::to_path_buf),
            None => std::env::temp_dir(),
        };
        let split = hpds_etl::dump::split_by_partition(&store, &parent)?;
        let ordered: Vec<&hpds_etl::dump::PartitionRows> =
            names.iter().map(|(i, _)| &split.partitions[*i]).collect();
        return write_to(args.out.as_deref(), args.force, |out| {
            let counts = hpds_etl::dump::write_partition_dumps(&ordered, out)?;
            tracing::info!(
                concepts = counts.concepts,
                observations = counts.observations,
                "dump written"
            );
            Ok(())
        });
    }
    let Some(dir) = args.out else {
        return Err(EtlError::Usage(
            "a partitioned store dumps one file per partition: give --out <directory>, or --concat for one file".into(),
        ));
    };
    output::prepare_output_dir(&dir)?;
    if args.patients {
        for (i, name) in &names {
            let path = dir.join(format!("{name}.csv"));
            write_to(Some(&path), args.force, |out| {
                hpds_etl::dump::dump_patients(&table.partitions[*i].patients, out).map(|_| ())
            })?;
        }
        return Ok(());
    }
    let split = hpds_etl::dump::split_by_partition(&store, &dir)?;
    for (i, name) in &names {
        let path = dir.join(format!("{name}.csv"));
        write_to(Some(&path), args.force, |out| {
            let counts = hpds_etl::dump::write_partition_dumps(&[&split.partitions[*i]], out)?;
            tracing::info!(
                partition = %name,
                concepts = counts.concepts,
                observations = counts.observations,
                "dump written"
            );
            Ok(())
        })?;
    }
    Ok(())
}

/// Calls `write` on a writer to the output file `path` (see
/// [`output::write_output_file`]), or to standard output when `None`.
fn write_to(
    path: Option<&Path>,
    force: bool,
    write: impl FnOnce(&mut dyn Write) -> Result<()>,
) -> Result<()> {
    match path {
        Some(path) => output::write_output_file(path, force, write),
        None => write(&mut std::io::stdout().lock()),
    }
}
