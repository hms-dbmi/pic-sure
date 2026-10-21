use std::path::PathBuf;
use std::process::ExitCode;

use std::sync::Arc;

use clap::{Parser, Subcommand, ValueEnum};
use hpds_bench::client::Client;
use hpds_bench::column_meta::{merge_column_meta, read_column_meta};
use hpds_bench::compare::{CompareReport, Mismatch, compare_corpus};
use hpds_bench::consents::{
    ConsentPlan, expand, parse_variants, partitions_in_dir, partitions_of_files,
};
use hpds_bench::corpus::{generate, read_corpus, write_corpus};
use hpds_bench::divergence::{ConsentContract, Divergences, JavaBranch, Side};
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

/// Subsets of the corpus that `load` can be restricted to.
#[derive(Clone, Copy, ValueEnum)]
enum Only {
    /// Skip async entries.
    Sync,
}

#[derive(Subcommand)]
enum Command {
    /// Generate a deterministic query corpus from a columnMeta.csv.
    Corpus {
        /// Path to columnMeta.csv. Repeat it for a partitioned store, one file per partition;
        /// the files are merged as the service merges its dictionary.
        #[arg(long, required = true)]
        column_meta: Vec<PathBuf>,
        /// Emit each query with `userConsents`: a comma-separated list of partition names, or
        /// `auto` for the subdirectories of --partition-dir, or else the directory names of the
        /// --column-meta files. Without it queries carry no `userConsents`.
        #[arg(long)]
        consents: Option<String>,
        /// Partitioned store directory that `--consents auto` reads partition names from.
        #[arg(long)]
        partition_dir: Option<PathBuf>,
        /// Consent variants to cycle through, comma separated, from all, one, two, subset,
        /// unknown, empty and none. Leave out empty and none for a service that requires
        /// consents when every query should carry some.
        #[arg(long, default_value = "all,one,two,subset,unknown,empty,none")]
        consent_variants: String,
        /// Consent variants per generated entry; fixed entries get every variant.
        #[arg(long, default_value_t = 3)]
        variants_per_entry: usize,
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
        /// Extra attempts for an entry that differs; entries that recover are listed as flaky,
        /// with the side that answered differently between attempts.
        #[arg(long, default_value_t = 0)]
        retries: usize,
        /// JSON file of accepted differences, such as bench/divergences.json. Matching entries
        /// print as WHITELISTED with the rule's reason and do not fail the run.
        #[arg(long)]
        divergences: Option<PathBuf>,
        /// Which server is the Java service, `a` or `b`. With --java-branch, divergence rules
        /// scoped to a Java branch apply, and rules that need to know which answer is Java's
        /// (the narrowing of authorizationFilters on main) can match.
        #[arg(long, value_enum, requires = "java_branch")]
        java: Option<JavaSide>,
        /// The Java branch the Java service runs: `main` (the original HPDS) or `consent`
        /// (feature-harmonization). Needs --java.
        #[arg(long, value_enum, requires = "java")]
        java_branch: Option<JavaBranchArg>,
        /// The consent contract the Rust service serves, its HPDS_CONSENT_CONTRACT:
        /// `harmonization` (the default) or `main`. Rules scoped to a contract apply only under
        /// it. Needs --java.
        #[arg(long, value_enum, requires = "java")]
        consent_contract: Option<ConsentContractArg>,
        /// Whether the Rust service runs with HPDS_REQUIRE_AUTHORIZATION_FILTER on: `on` (the
        /// default) or `off`. With it off, the main contract never accepts a refusal of a sync
        /// query that carries no authorization filter. Needs --java.
        #[arg(long, value_enum, requires = "java")]
        require_authorization_filter: Option<Switch>,
    },
    /// Replay a corpus against one server and report latency and throughput.
    ///
    /// The load is closed loop: each worker sends its next request only after the previous one
    /// finished, so a slow server is offered fewer requests. No coordinated omission correction
    /// is applied, which makes tail latencies a lower bound. Async queries are polled every 2 ms.
    /// Throughput is requests over the span from the first measured start to the last finish.
    /// Error-path entries are never replayed.
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
        /// Measured window, such as 60s. Ignored with --passes.
        #[arg(long, default_value = "60s")]
        duration: String,
        /// Unmeasured lead-in, such as 10s. Ignored with --passes.
        #[arg(long, default_value = "0s")]
        warmup: String,
        /// Run this many passes instead of a timed window. Each pass sends every entry once and
        /// is reported separately; pass 1 is the cold pass.
        #[arg(long, default_value_t = 0)]
        passes: usize,
        /// Interval between async status polls.
        #[arg(long, default_value = "2ms")]
        poll_interval: String,
        /// Restrict the replay to a subset of the corpus.
        #[arg(long, value_enum)]
        only: Option<Only>,
        /// Longest wait for an async query to finish. Timeouts are counted apart from errors
        /// and appear in a second latency view at this value.
        #[arg(long, default_value = "120s")]
        timeout: String,
        /// Result types to leave out, comma separated. The Java service races inside a single
        /// OBSERVATION_CROSS_COUNT request that selects several concepts, so it can drop keys or
        /// hang whatever the concurrency; Java baselines exclude it.
        #[arg(long, value_delimiter = ',')]
        exclude: Vec<String>,
        /// Extra request header as `Name: value`, sent on every request. Repeat for more, for
        /// example `--header 'Accept-Encoding: gzip, deflate, x-gzip'` to send what the query
        /// service's HTTP client sends. Bodies are not decoded, so bytes are counted as sent.
        #[arg(long = "header")]
        headers: Vec<String>,
    },
}

/// Parses `Name: value` pairs into a header map.
fn parse_headers(raw: &[String]) -> Result<reqwest::header::HeaderMap> {
    let mut map = reqwest::header::HeaderMap::new();
    for pair in raw {
        let (name, value) = pair
            .split_once(':')
            .ok_or_else(|| BenchError::Invalid(format!("header {pair:?} is not Name: value")))?;
        let name = reqwest::header::HeaderName::from_bytes(name.trim().as_bytes())
            .map_err(|e| BenchError::Invalid(format!("header name {name:?}: {e}")))?;
        let value = reqwest::header::HeaderValue::from_str(value.trim())
            .map_err(|e| BenchError::Invalid(format!("header value {value:?}: {e}")))?;
        map.append(name, value);
    }
    Ok(map)
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

fn side(changed: bool) -> &'static str {
    if changed { "changed" } else { "stable" }
}

fn report_compare(found: &CompareReport, total: usize) -> bool {
    for item in &found.whitelisted {
        println!(
            "WHITELISTED {} ({}): {}",
            item.entry, item.rule, item.reason
        );
        for line in &item.details {
            println!("    {line}");
        }
    }
    for item in &found.flaky {
        println!(
            "FLAKY {} (agreed only after a retry; A {}, B {} between attempts)",
            item.entry,
            side(item.a_changed),
            side(item.b_changed)
        );
    }
    let ok = report("compare", &found.mismatches, total);
    println!("compare: {} whitelisted", found.whitelisted.len());
    ok
}

async fn run(cli: Cli) -> Result<bool> {
    match cli.command {
        Command::Corpus {
            column_meta,
            consents,
            partition_dir,
            consent_variants,
            variants_per_entry,
            seed,
            out,
            size,
        } => {
            let sets = column_meta
                .iter()
                .map(|path| read_column_meta(path))
                .collect::<Result<Vec<_>>>()?;
            let columns = if sets.len() == 1 {
                sets.into_iter().next().unwrap_or_default()
            } else {
                merge_column_meta(sets)
            };
            let mut entries = generate(&columns, seed, size)?;
            if let Some(spec) = consents {
                let partitions = match (spec.as_str(), &partition_dir) {
                    ("auto", Some(dir)) => partitions_in_dir(dir)?,
                    ("auto", None) => partitions_of_files(&column_meta)?,
                    (list, _) => list
                        .split(',')
                        .map(str::trim)
                        .filter(|s| !s.is_empty())
                        .map(str::to_owned)
                        .collect(),
                };
                let plan = ConsentPlan {
                    partitions,
                    variants: parse_variants(&consent_variants)?,
                    per_entry: variants_per_entry,
                };
                println!("consent partitions: {}", plan.partitions.join(","));
                entries = expand(entries, &plan, seed)?;
            }
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
            divergences,
            java,
            java_branch,
            consent_contract,
            require_authorization_filter,
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
                let mut rules = match divergences {
                    Some(path) => Divergences::read(&path)?,
                    None => Divergences::default(),
                };
                if let (Some(side), Some(branch)) = (java, java_branch) {
                    rules = rules.for_java(side.into(), branch.into());
                }
                if let Some(contract) = consent_contract {
                    rules = rules.with_contract(contract.into());
                }
                if let Some(required) = require_authorization_filter {
                    rules =
                        rules.with_required_authorization_filter(matches!(required, Switch::On));
                }
                let found = compare_corpus(
                    &client_a,
                    &client_b,
                    entries,
                    concurrency,
                    retries,
                    Arc::new(rules),
                )
                .await;
                ok &= report_compare(&found, total);
            }
            Ok(ok)
        }
        Command::Load {
            url,
            corpus,
            concurrency,
            duration,
            warmup,
            passes,
            poll_interval,
            only,
            timeout,
            exclude,
            headers,
        } => {
            let headers = parse_headers(&headers)?;
            let timeout = parse_duration(&timeout)?;
            let config = LoadConfig {
                concurrency,
                duration: parse_duration(&duration)?,
                warmup: parse_duration(&warmup)?,
                only_sync: matches!(only, Some(Only::Sync)),
                exclude,
                passes,
                poll_interval: parse_duration(&poll_interval)?,
                timeout,
            };
            let client = Client::with_headers(&url, timeout, concurrency, &headers)?;
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

#[cfg(test)]
mod tests {
    use super::parse_headers;

    #[test]
    fn headers_parse_name_and_value() {
        let map = parse_headers(&[
            "Accept-Encoding: gzip, deflate, x-gzip".to_owned(),
            "X-Extra:1".to_owned(),
        ])
        .expect("valid headers");
        assert_eq!(map["accept-encoding"], "gzip, deflate, x-gzip");
        assert_eq!(map["x-extra"], "1");
    }

    #[test]
    fn headers_without_a_colon_are_refused() {
        assert!(parse_headers(&["Accept-Encoding gzip".to_owned()]).is_err());
        assert!(parse_headers(&["Bad Name: x".to_owned()]).is_err());
    }
}

/// `--java`: which compared server is the Java service.
#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum JavaSide {
    /// The server given as `--a`.
    A,
    /// The server given as `--b`.
    B,
}

impl From<JavaSide> for Side {
    fn from(side: JavaSide) -> Side {
        match side {
            JavaSide::A => Side::A,
            JavaSide::B => Side::B,
        }
    }
}

/// `--java-branch`: the branch the Java service runs.
#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum JavaBranchArg {
    /// The original HPDS, `main`.
    Main,
    /// The consent-partitioned HPDS, `feature-harmonization`.
    Consent,
}

/// An `on` or `off` setting.
#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum Switch {
    /// Enabled.
    On,
    /// Disabled.
    Off,
}

/// `--consent-contract`: the consent contract the Rust service serves.
#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum ConsentContractArg {
    /// The consent-partitioned contract of `feature-harmonization`.
    Harmonization,
    /// The contract of the original HPDS, `main`.
    Main,
}

impl From<ConsentContractArg> for ConsentContract {
    fn from(contract: ConsentContractArg) -> ConsentContract {
        match contract {
            ConsentContractArg::Harmonization => ConsentContract::Harmonization,
            ConsentContractArg::Main => ConsentContract::Main,
        }
    }
}

impl From<JavaBranchArg> for JavaBranch {
    fn from(branch: JavaBranchArg) -> JavaBranch {
        match branch {
            JavaBranchArg::Main => JavaBranch::Main,
            JavaBranchArg::Consent => JavaBranch::Consent,
        }
    }
}
