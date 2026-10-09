use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_gen::edge::{write_edge_cases, write_embedded_newline, write_negative_ids};
use hpds_gen::generate::{Shape, generate};

const WRITE_BUFFER_BYTES: usize = 8 * 1024 * 1024;

#[derive(Parser)]
#[command(
    name = "hpds-gen",
    about = "Generate deterministic allConcepts.csv datasets"
)]
struct Cli {
    #[command(subcommand)]
    command: Option<Command>,
    #[command(flatten)]
    synthetic: SyntheticArgs,
}

#[derive(Subcommand)]
enum Command {
    /// Write the small file that exercises every ETL and parity rule
    EdgeCases {
        /// Output CSV path, siblings `<out>.negative-ids.csv` and `<out>.embedded-newline.csv` are written too
        #[arg(long)]
        out: PathBuf,
    },
}

#[derive(Clone, Copy, ValueEnum)]
enum Preset {
    Tiny,
    Dev,
    Bench,
}

#[derive(Args)]
struct SyntheticArgs {
    /// Dataset size preset
    #[arg(long, value_enum)]
    preset: Option<Preset>,
    /// Seed for the random stream
    #[arg(long, default_value_t = 1)]
    seed: u64,
    /// Output CSV path
    #[arg(long)]
    out: Option<PathBuf>,
    /// Override the preset patient count
    #[arg(long)]
    patients: Option<u64>,
    /// Override the preset concept count
    #[arg(long)]
    concepts: Option<usize>,
    /// Override the preset approximate observation count
    #[arg(long)]
    observations: Option<u64>,
}

fn create(path: &Path) -> Result<BufWriter<File>, String> {
    if let Some(parent) = path.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent).map_err(|e| format!("{}: {e}", parent.display()))?;
    }
    let file = File::create(path).map_err(|e| format!("{}: {e}", path.display()))?;
    Ok(BufWriter::with_capacity(WRITE_BUFFER_BYTES, file))
}

fn sibling(out: &Path, suffix: &str) -> PathBuf {
    let mut name = out.as_os_str().to_owned();
    name.push(suffix);
    PathBuf::from(name)
}

fn run_edge_cases(out: &Path) -> Result<(), String> {
    let records = write_edge_cases(create(out)?).map_err(|e| e.to_string())?;
    let negative = sibling(out, ".negative-ids.csv");
    write_negative_ids(create(&negative)?).map_err(|e| e.to_string())?;
    let newline = sibling(out, ".embedded-newline.csv");
    write_embedded_newline(create(&newline)?).map_err(|e| e.to_string())?;
    eprintln!("wrote {records} edge-case records to {}", out.display());
    eprintln!("wrote {}", negative.display());
    eprintln!("wrote {}", newline.display());
    Ok(())
}

fn run_synthetic(args: SyntheticArgs) -> Result<(), String> {
    let preset = args.preset.ok_or("--preset is required")?;
    let out = args.out.ok_or("--out is required")?;
    let base = match preset {
        Preset::Tiny => Shape::TINY,
        Preset::Dev => Shape::DEV,
        Preset::Bench => Shape::BENCH,
    };
    let shape = Shape {
        patients: args.patients.unwrap_or(base.patients),
        concepts: args.concepts.unwrap_or(base.concepts),
        observations: args.observations.unwrap_or(base.observations),
    };
    if shape.patients == 0 || shape.concepts < 4 {
        return Err("need at least 1 patient and 4 concepts".to_string());
    }
    let started = Instant::now();
    let rows = generate(shape, args.seed, create(&out)?).map_err(|e| e.to_string())?;
    eprintln!(
        "wrote {rows} observations for {} patients and {} concepts to {} in {:.1}s",
        shape.patients,
        shape.concepts,
        out.display(),
        started.elapsed().as_secs_f64()
    );
    Ok(())
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    let result = match cli.command {
        Some(Command::EdgeCases { out }) => run_edge_cases(&out),
        None => run_synthetic(cli.synthetic),
    };
    match result {
        Ok(()) => ExitCode::SUCCESS,
        Err(message) => {
            eprintln!("hpds-gen: {message}");
            ExitCode::FAILURE
        }
    }
}
