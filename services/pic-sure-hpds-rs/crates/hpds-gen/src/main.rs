use std::fs::File;
use std::io::BufWriter;
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Instant;

use clap::{Args, Parser, Subcommand, ValueEnum};
use hpds_gen::bdc::Profile;
use hpds_gen::edge::write_edge_dirs;
use hpds_gen::generate::{IdLayout, Order, Shape, generate_profile};
use hpds_gen::genomic::edge::write_edge;
use hpds_gen::genomic::estimate::estimate;
use hpds_gen::genomic::writer::write_dataset;
use hpds_gen::genomic::{Preset as GenomicPreset, Shape as GenomicShape};

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
    /// Generate a genomic dataset: per group and chromosome VCFs, the index files the Java
    /// ETL reads, and a matching phenotype CSV per group
    Genomic(GenomicArgs),
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
    Bdcx,
    Bdcdev,
}

#[derive(Clone, Copy, Eq, PartialEq, ValueEnum)]
enum ProfileArg {
    Plain,
    Bdc,
    BdcEdge,
}

#[derive(Clone, Copy, ValueEnum)]
enum GenomicPresetArg {
    Edge,
    Gdev,
    Gbench,
}

#[derive(Args)]
struct GenomicArgs {
    /// Dataset preset: edge (hand-placed rule coverage), gdev (10,000 samples in 4 groups,
    /// 2 million sites) or gbench (50,000 samples in 10 groups, 8 million sites)
    #[arg(long, value_enum)]
    preset: GenomicPresetArg,
    /// Seed for the random stream
    #[arg(long, default_value_t = 1)]
    seed: u64,
    /// Output directory; vcf/, index/, pheno/ and groups.tsv are written below it
    #[arg(long)]
    out_dir: Option<PathBuf>,
    /// Worker threads; defaults to the available parallelism
    #[arg(long)]
    threads: Option<usize>,
    /// Write nothing: estimate the VCF size by compressing a sample of rows
    #[arg(long)]
    estimate: bool,
    /// With --estimate, compress one in this many present rows
    #[arg(long, default_value_t = 50)]
    estimate_stride: u64,
}

#[derive(Clone, Copy, ValueEnum)]
enum RowOrder {
    Patient,
    Concept,
}

#[derive(Args)]
struct GenerateArgs {
    /// Dataset size preset: tiny, dev, bench, or the BDC presets bdcx (3,000 patients, 8 studies,
    /// with the hand-placed edge patients) and bdcdev (dev scale, 12 studies)
    #[arg(long, value_enum)]
    preset: Preset,
    /// Consent profile: plain (the default for tiny, dev and bench), bdc (adds
    /// `\_topmed_consents\`, `\_harmonized_consent\`, the `\DCC Harmonized data set\` concepts and
    /// `\_parent_consents\`), or bdc-edge (bdc plus the hand-placed edge patients). The bdcx and
    /// bdcdev presets default to bdc-edge and bdc
    #[arg(long, value_enum)]
    profile: Option<ProfileArg>,
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
    let (base, preset_profile) = match args.preset {
        Preset::Tiny => (Shape::TINY, ProfileArg::Plain),
        Preset::Dev => (Shape::DEV, ProfileArg::Plain),
        Preset::Bench => (Shape::BENCH, ProfileArg::Plain),
        Preset::Bdcx => (Shape::BDCX, ProfileArg::BdcEdge),
        Preset::Bdcdev => (Shape::BDCDEV, ProfileArg::Bdc),
    };
    let profile = match args.profile.unwrap_or(preset_profile) {
        ProfileArg::Plain if preset_profile != ProfileArg::Plain => {
            return Err("the bdcx and bdcdev presets need a bdc profile".to_string());
        }
        ProfileArg::Plain => Profile::Plain,
        ProfileArg::Bdc => Profile::Bdc { edge: false },
        ProfileArg::BdcEdge => Profile::Bdc { edge: true },
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
    shape.validate_profile(profile)?;
    let order = match args.order {
        RowOrder::Patient => Order::Patient,
        RowOrder::Concept => Order::Concept,
    };
    let started = Instant::now();
    let rows = generate_profile(shape, profile, args.seed, order, create(&args.out)?)
        .map_err(|e| e.to_string())?;
    eprintln!(
        "wrote {rows} observations for {} patients and {} concepts to {} in {:.1}s",
        shape.patients,
        shape.concepts,
        args.out.display(),
        started.elapsed().as_secs_f64()
    );
    Ok(())
}

fn run_genomic(args: GenomicArgs) -> Result<(), String> {
    let preset = match args.preset {
        GenomicPresetArg::Edge => GenomicPreset::Edge,
        GenomicPresetArg::Gdev => GenomicPreset::Gdev,
        GenomicPresetArg::Gbench => GenomicPreset::Gbench,
    };
    let shape = GenomicShape::of(preset);
    if args.estimate {
        let shape =
            shape.ok_or("the edge preset is a few kilobytes; there is nothing to estimate")?;
        shape.validate()?;
        let started = Instant::now();
        let groups =
            estimate(&shape, args.seed, args.estimate_stride.max(1)).map_err(|e| e.to_string())?;
        let mut vcf = 0;
        let mut text = 0;
        let mut records = 0;
        for g in &groups {
            eprintln!(
                "{}: {} samples, about {} records, {:.2} GB gzipped, {:.1} GB text",
                g.name,
                g.samples,
                g.records,
                g.vcf_bytes as f64 / 1e9,
                g.text_bytes as f64 / 1e9
            );
            vcf += g.vcf_bytes;
            text += g.text_bytes;
            records += g.records;
        }
        eprintln!(
            "{}: about {records} records, {:.1} GB gzipped VCFs, {:.0} GB of text, estimated in {:.1}s",
            shape.name,
            vcf as f64 / 1e9,
            text as f64 / 1e9,
            started.elapsed().as_secs_f64()
        );
        return Ok(());
    }
    let dir = args
        .out_dir
        .ok_or("--out-dir is required unless --estimate is given")?;
    let threads = args
        .threads
        .unwrap_or_else(|| std::thread::available_parallelism().map_or(1, |n| n.get()));
    let output = match shape {
        None => write_edge(&dir, args.seed),
        Some(shape) => {
            shape.validate()?;
            write_dataset(&shape, args.seed, &dir, threads, true)
        }
    }
    .map_err(|e| format!("{}: {e}", dir.display()))?;
    eprintln!(
        "wrote {} files, {} records, {:.2} GB of VCFs to {} in {:.1}s",
        output.files,
        output.records,
        output.vcf_bytes as f64 / 1e9,
        dir.display(),
        output.seconds
    );
    Ok(())
}

fn main() -> ExitCode {
    let result = match Cli::parse().command {
        Command::Generate(args) => run_generate(args),
        Command::Genomic(args) => run_genomic(args),
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
