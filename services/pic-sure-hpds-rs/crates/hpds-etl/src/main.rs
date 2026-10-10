use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::ExitCode;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_etl::load::COLUMN_META_CSV_NAME;
use hpds_etl::load::{KeySource, LoadOptions, Mode, PartitionedLoadOptions};
use hpds_etl::migrate::{EXPORT_CONCEPTS_FILE, MigrateOptions};
use hpds_etl::output;
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
    /// Build, rebuild or remove the combined store of a partitioned store in place.
    Combine(CombineArgs),
}

#[derive(Args)]
struct CombineArgs {
    /// Partitioned store directory. Its partitions are read, never changed.
    #[arg(long)]
    store: PathBuf,
    /// Remove the combined store and its .generation line instead of building one, so a
    /// query service without combined store support opens the store again.
    #[arg(long)]
    remove: bool,
    /// Worker threads for merging blocks.
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
    /// Partitioned exports only: do not write the combined store of every partition, and
    /// remove one that <out> already has.
    #[arg(long)]
    no_combined: bool,
}

#[derive(Args)]
struct RemoveConceptsArgs {
    /// Store directory to change in place. A partitioned store has every partition changed,
    /// then its combined store.
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
    /// regenerated in place; its combined store is left as it is.
    #[arg(long)]
    store: PathBuf,
    /// File to write. Defaults to columnMeta.csv in the store directory. For a partitioned
    /// store, only with --merged.
    #[arg(long)]
    out: Option<PathBuf>,
    /// Partitioned stores only: write one columnMeta.csv with one row per concept, its
    /// metadata combined across partitions (counts summed, category values unioned), for
    /// dictionary hydration. Java produces no such file. Unlike dump --concat, which only
    /// concatenates the partitions' rows.
    #[arg(long)]
    merged: bool,
    #[command(flatten)]
    key: KeyArgs,
}

#[derive(Args)]
struct DumpArgs {
    /// Store directory to read. A partitioned store's combined store is never read, since
    /// it repeats the partitions' rows.
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
    /// Partitioned mode only: do not write the combined store of every partition, and
    /// remove one that <out> already has.
    #[arg(long)]
    no_combined: bool,
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
        options.combined = !self.no_combined;
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
        Command::RemoveConcepts(args) => run_remove_concepts(args),
        Command::ColumnMetaCsv(args) => run_column_meta_csv(args),
        Command::Dump(args) => run_dump(args),
        Command::Combine(args) => run_combine(args),
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
    if args.no_combined {
        return Err(EtlError::Usage(
            "--no-combined is for partitioned mode, the only one that writes a combined store"
                .into(),
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
        combined: !args.no_combined,
    };
    match StoreLayout::detect(&options.export, EXPORT_CONCEPTS_FILE)? {
        StoreLayout::Root if args.no_combined => {
            return Err(EtlError::Usage(
                "--no-combined is for a partitioned export, the only one that gets a combined store"
                    .into(),
            ));
        }
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
            print_json(&hpds_etl::store_maintenance::remove_concepts_partitioned(
                &partitions,
                hpds_etl::combined::existing_combined_dir(&args.store).as_deref(),
                &args.concepts,
                &protection,
                !args.no_compact,
            )?)
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
        StoreLayout::Root if args.concat => {
            return Err(EtlError::Usage(format!(
                "--concat needs a partitioned store; {} is not partitioned",
                args.store.display()
            )));
        }
        StoreLayout::Root => {
            if let Some(out) = &args.out {
                output::check_outside_stores(out, &[&args.store])?;
            }
            let store = OpenStore::open(&args.store, protection)?;
            return write_to(args.out.as_deref(), args.force, |out| {
                write_dump(std::slice::from_ref(&store), args.patients, out)
            });
        }
        StoreLayout::Partitioned(partitions) => partitions,
    };
    let protected: Vec<&Path> = std::iter::once(args.store.as_path())
        .chain(partitions.iter().map(|p| p.dir.as_path()))
        .collect();
    if let Some(out) = &args.out {
        output::check_outside_stores(out, &protected)?;
    }
    if args.concat {
        let stores = partitions
            .iter()
            .map(|p| OpenStore::open(&p.dir, protection.clone()))
            .collect::<Result<Vec<_>>>()?;
        return write_to(args.out.as_deref(), args.force, |out| {
            write_dump(&stores, args.patients, out)
        });
    }
    let Some(dir) = args.out else {
        return Err(EtlError::Usage(
            "a partitioned store dumps one file per partition: give --out <directory>, or --concat for one file".into(),
        ));
    };
    output::prepare_output_dir(&dir)?;
    for partition in partitions {
        let store = OpenStore::open(&partition.dir, protection.clone())?;
        let path = dir.join(format!("{}.csv", partition.name));
        write_to(Some(&path), args.force, |out| {
            write_dump(std::slice::from_ref(&store), args.patients, out)
        })?;
    }
    Ok(())
}

fn run_combine(args: CombineArgs) -> Result<()> {
    let (layout, protection) = open_layout(&args.store, &args.key)?;
    if layout == StoreLayout::Root {
        return Err(EtlError::Usage(format!(
            "{} is not partitioned; only a partitioned store has a combined store",
            args.store.display()
        )));
    }
    if args.remove {
        let removed = hpds_etl::combined::remove_combined(&args.store, &protection)?;
        tracing::info!(removed, store = %args.store.display(), "combined store removed");
        return Ok(());
    }
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(args.threads.unwrap_or(0))
        .build()
        .map_err(|e| EtlError::ThreadPool(e.to_string()))?;
    print_json(&hpds_etl::combined::combine_in_place(
        &args.store,
        &protection,
        &pool,
    )?);
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
