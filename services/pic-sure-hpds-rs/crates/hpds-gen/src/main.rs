use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_gen::edge::write_edge_dirs;
use hpds_gen::generate::{IdLayout, Order, Shape, generate};

const WRITE_BUFFER_BYTES: usize = 8 * 1024 * 1024;

#[derive(Parser)]
#[command(
    name = "hpds-gen",
    about = "Generate deterministic allConcepts.csv datasets",
    arg_required_else_help = true
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Generate a synthetic dataset from a size preset
    Generate(GenerateArgs),
    /// Write the edge-case datasets that exercise every ETL and parity rule
    EdgeCases {
        /// Output directory; core/, lowram/, negative-ids/, embedded-newline/ and config/ are
        /// written below it, each with an allConcepts.csv
        #[arg(long)]
        out_dir: PathBuf,
    },
}

#[derive(Clone, Copy, ValueEnum)]
enum Preset {
    Tiny,
    Dev,
    Bench,
}

#[derive(Clone, Copy, ValueEnum)]
enum RowOrder {
    Patient,
    Concept,
}

#[derive(Args)]
struct GenerateArgs {
    /// Dataset size preset
    #[arg(long, value_enum)]
    preset: Preset,
    /// Seed for the random stream
    #[arg(long, default_value_t = 1)]
    seed: u64,
    /// Output CSV path
    #[arg(long)]
    out: PathBuf,
    /// Row order: interleaved by patient block, or grouped by concept; both hold the same rows
    #[arg(long, value_enum, default_value_t = RowOrder::Patient)]
    order: RowOrder,
    /// Override the preset patient count
    #[arg(long)]
    patients: Option<u64>,
    /// Override the preset concept count
    #[arg(long)]
    concepts: Option<usize>,
    /// Override the preset approximate observation count
    #[arg(long)]
    observations: Option<u64>,
    /// Override the preset study count
    #[arg(long)]
    studies: Option<u32>,
    /// Override the preset average distance between patient IDs
    #[arg(long)]
    id_stride: Option<u32>,
    /// Space patient IDs by random gaps averaging the stride (`--id-gaps false` turns the
    /// bench preset's gaps off)
    #[arg(long, num_args = 0..=1, default_missing_value = "true")]
    id_gaps: Option<bool>,
}

fn create(path: &Path) -> Result<BufWriter<File>, String> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let file = File::create(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(BufWriter::with_capacity(WRITE_BUFFER_BYTES, file))
}

fn run_edge_cases(out_dir: &Path) -> Result<(), String> {
    let written = write_edge_dirs(out_dir).map_err(|e| format!("{}: {e}", out_dir.display()))?;
    for path in written {
        eprintln!("wrote {}", path.display());
    }
    Ok(())
}

fn run_generate(args: GenerateArgs) -> Result<(), String> {
    let base = match args.preset {
        Preset::Tiny => Shape::TINY,
        Preset::Dev => Shape::DEV,
        Preset::Bench => Shape::BENCH,
    };
    let shape = Shape {
        patients: args.patients.unwrap_or(base.patients),
        concepts: args.concepts.unwrap_or(base.concepts),
        observations: args.observations.unwrap_or(base.observations),
        studies: args.studies.unwrap_or(base.studies),
        high_cardinality: base.high_cardinality,
        ids: IdLayout {
            stride: args.id_stride.unwrap_or(base.ids.stride),
            gaps: args.id_gaps.unwrap_or(base.ids.gaps),
        },
    };
    shape.validate()?;
    let order = match args.order {
        RowOrder::Patient => Order::Patient,
        RowOrder::Concept => Order::Concept,
    };
    let started = Instant::now();
    let rows = generate(shape, args.seed, order, create(&args.out)?).map_err(|e| e.to_string())?;
    eprintln!(
        "wrote {rows} observations for {} patients and {} concepts to {} in {:.1}s",
        shape.patients,
        shape.concepts,
        args.out.display(),
        started.elapsed().as_secs_f64()
    );
    Ok(())
}

fn main() -> ExitCode {
    let result = match Cli::parse().command {
        Command::Generate(args) => run_generate(args),
        Command::EdgeCases { out_dir } => run_edge_cases(&out_dir),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("hpds-gen: {message}");
            ExitCode::FAILURE
        }
    }
}
