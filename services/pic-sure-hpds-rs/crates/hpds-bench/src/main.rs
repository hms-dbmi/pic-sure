use std::path::PathBuf;
use std::process::ExitCode;

use clap::{Parser, Subcommand};
use hpds_bench::client::Client;
use hpds_bench::column_meta::read_column_meta;
use hpds_bench::compare::{Mismatch, compare_corpus};
use hpds_bench::corpus::{generate, read_corpus, write_corpus};
use hpds_bench::duration::parse_duration;
use hpds_bench::golden::{read_golden, run_golden};
use hpds_bench::load::{LoadConfig, render_table, run_load};
use hpds_bench::{BenchError, Result};

/// Corpus, compare and load tooling for HPDS v3 services.
#[derive(Parser)]
#[command(name = "hpds-bench", version)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Generate a deterministic query corpus from a columnMeta.csv.
    Corpus {
        /// Path to columnMeta.csv.
        #[arg(long)]
        column_meta: PathBuf,
        /// Seed; the same seed and metadata give the same corpus.
        #[arg(long, default_value_t = 1)]
        seed: u64,
        /// Output directory.
        #[arg(long)]
        out: PathBuf,
        /// Number of generated entries, besides the fixed ones.
        #[arg(long, default_value_t = 196)]
        size: usize,
    },
    /// Diff two servers on a corpus, or check one server against a golden file.
    Compare {
        /// First base URL, such as http://host:8080/PIC-SURE.
        #[arg(long)]
        a: String,
        /// Second base URL; required with --corpus.
        #[arg(long)]
        b: Option<String>,
        /// Corpus directory written by the corpus command.
        #[arg(long)]
        corpus: Option<PathBuf>,
        /// Golden file checked against --a.
        #[arg(long)]
        golden: Option<PathBuf>,
        /// Entries in flight at once. Keep at 1 against the Java service, whose async result map is not thread safe.
        #[arg(long, default_value_t = 1)]
        concurrency: usize,
        /// Longest wait for an async query to finish.
        #[arg(long, default_value = "120s")]
        timeout: String,
        /// Extra attempts for an entry that differs; entries that recover are listed as flaky.
        #[arg(long, default_value_t = 0)]
        retries: usize,
    },
    /// Replay a corpus against one server and report latency and throughput.
    Load {
        /// Base URL, such as http://host:8080/PIC-SURE.
        #[arg(long)]
        url: String,
        /// Corpus directory written by the corpus command.
        #[arg(long)]
        corpus: PathBuf,
        /// Concurrent request loops.
        #[arg(long, default_value_t = 8)]
        concurrency: usize,
        /// Measured window, such as 60s.
        #[arg(long, default_value = "60s")]
        duration: String,
        /// Unmeasured lead-in, such as 10s.
        #[arg(long, default_value = "0s")]
        warmup: String,
        /// Restrict the replay: `sync` skips async entries.
        #[arg(long)]
        only: Option<String>,
        /// Longest wait for an async query to finish.
        #[arg(long, default_value = "120s")]
        timeout: String,
        /// Result types to leave out, comma separated. The Java service hangs under concurrent
        /// OBSERVATION_CROSS_COUNT requests, so Java baselines exclude it.
        #[arg(long, value_delimiter = ',')]
        exclude: Vec<String>,
    },
}

fn report(title: &str, mismatches: &[Mismatch], total: usize) -> bool {
    for mismatch in mismatches {
        println!("MISMATCH {}", mismatch.entry);
        for line in &mismatch.details {
            println!("    {line}");
        }
    }
    println!("{title}: {} of {total} entries differ", mismatches.len());
    mismatches.is_empty()
}

async fn run(cli: Cli) -> Result<bool> {
    match cli.command {
        Command::Corpus {
            column_meta,
            seed,
            out,
            size,
        } => {
            let columns = read_column_meta(&column_meta)?;
            let entries = generate(&columns, seed, size)?;
            let manifest = write_corpus(&out, seed, size, &entries)?;
            println!(
                "wrote {} entries to {}",
                manifest.entries.len(),
                out.display()
            );
            Ok(true)
        }
        Command::Compare {
            a,
            b,
            corpus,
            golden,
            concurrency,
            timeout,
            retries,
        } => {
            if corpus.is_none() && golden.is_none() {
                return Err(BenchError::Invalid(
                    "compare needs --corpus, --golden, or both".into(),
                ));
            }
            let timeout = parse_duration(&timeout)?;
            let client_a = Client::new(&a, timeout, concurrency)?;
            let mut ok = true;
            if let Some(path) = golden {
                let file = read_golden(&path)?;
                let total = file.cases.len();
                let failed = run_golden(&client_a, file, concurrency).await;
                ok &= report("golden", &failed, total);
            }
            if let Some(dir) = corpus {
                let b = b.ok_or_else(|| BenchError::Invalid("--corpus needs --b".into()))?;
                let client_b = Client::new(&b, timeout, concurrency)?;
                let entries = read_corpus(&dir)?;
                let total = entries.len();
                let found =
                    compare_corpus(&client_a, &client_b, entries, concurrency, retries).await;
                for name in &found.flaky {
                    println!("FLAKY {name} (agreed only after a retry)");
                }
                ok &= report("compare", &found.mismatches, total);
            }
            Ok(ok)
        }
        Command::Load {
            url,
            corpus,
            concurrency,
            duration,
            warmup,
            only,
            timeout,
            exclude,
        } => {
            let only_sync = match only.as_deref() {
                None => false,
                Some("sync") => true,
                Some(other) => {
                    return Err(BenchError::Invalid(format!(
                        "--only accepts 'sync', got '{other}'"
                    )));
                }
            };
            let config = LoadConfig {
                concurrency,
                duration: parse_duration(&duration)?,
                warmup: parse_duration(&warmup)?,
                only_sync,
                exclude,
            };
            let client = Client::new(&url, parse_duration(&timeout)?, concurrency)?;
            let loaded = run_load(&client, read_corpus(&corpus)?, &config).await?;
            eprint!("{}", render_table(&loaded));
            println!(
                "{}",
                serde_json::to_string_pretty(&loaded).map_err(|e| BenchError::json("report", e))?
            );
            Ok(true)
        }
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    match run(Cli::parse()).await {
        Ok(true) => ExitCode::SUCCESS,
        Ok(false) => ExitCode::from(1),
        Err(e) => {
            eprintln!("error: {e}");
            ExitCode::from(2)
        }
    }
}
