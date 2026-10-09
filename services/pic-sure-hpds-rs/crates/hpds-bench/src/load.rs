use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use hdrhistogram::Histogram;
use serde::Serialize;

use crate::client::Client;
use crate::corpus::{CorpusEntry, Kind};
use crate::{BenchError, Result};

const MAX_LATENCY_MICROS: u64 = 3_600_000_000;

/// Parameters of one load run.
#[derive(Debug, Clone)]
pub struct LoadConfig {
    /// Number of concurrent request loops.
    pub concurrency: usize,
    /// Length of the measured window.
    pub duration: Duration,
    /// Unmeasured lead-in before the window starts.
    pub warmup: Duration,
    /// Replay sync entries only.
    pub only_sync: bool,
    /// Result types left out of the replay.
    pub exclude: Vec<String>,
}

/// Latency and throughput summary for a group of requests.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct Stats {
    /// Completed requests, successful or not.
    pub requests: u64,
    /// Requests that failed or returned a non-success outcome.
    pub errors: u64,
    /// Requests per second over the measured window.
    pub throughput_rps: f64,
    /// Median latency of successful requests in milliseconds.
    pub p50_ms: f64,
    /// 90th percentile latency in milliseconds.
    pub p90_ms: f64,
    /// 99th percentile latency in milliseconds.
    pub p99_ms: f64,
    /// Slowest successful request in milliseconds.
    pub max_ms: f64,
    /// Mean latency of successful requests in milliseconds.
    pub mean_ms: f64,
}

/// Full result of a load run.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LoadReport {
    /// Base URL under test.
    pub url: String,
    /// Concurrency used.
    pub concurrency: usize,
    /// Measured window in seconds.
    pub duration_s: f64,
    /// Warm-up in seconds.
    pub warmup_s: f64,
    /// Corpus entries replayed.
    pub corpus_size: usize,
    /// All result types together.
    pub overall: Stats,
    /// Per result type.
    pub per_type: BTreeMap<String, Stats>,
}

struct Accumulator {
    latency: Histogram<u64>,
    errors: u64,
}

impl Accumulator {
    fn new() -> Self {
        let latency = Histogram::new_with_bounds(1, MAX_LATENCY_MICROS, 3)
            .expect("static histogram bounds are valid");
        Self { latency, errors: 0 }
    }

    fn record(&mut self, elapsed: Duration, success: bool) {
        if success {
            let micros = u64::try_from(elapsed.as_micros())
                .unwrap_or(u64::MAX)
                .clamp(1, MAX_LATENCY_MICROS);
            self.latency.saturating_record(micros);
        } else {
            self.errors += 1;
        }
    }

    fn merge(&mut self, other: &Accumulator) {
        self.errors += other.errors;
        self.latency
            .add(&other.latency)
            .expect("histograms share bounds");
    }

    fn stats(&self, window: Duration) -> Stats {
        let ms = |micros: f64| micros / 1000.0;
        let requests = self.latency.len() + self.errors;
        Stats {
            requests,
            errors: self.errors,
            throughput_rps: requests as f64 / window.as_secs_f64().max(f64::MIN_POSITIVE),
            p50_ms: ms(self.latency.value_at_quantile(0.50) as f64),
            p90_ms: ms(self.latency.value_at_quantile(0.90) as f64),
            p99_ms: ms(self.latency.value_at_quantile(0.99) as f64),
            max_ms: ms(self.latency.max() as f64),
            mean_ms: ms(self.latency.mean()),
        }
    }
}

/// Replays the entries round-robin from `concurrency` loops for the configured time.
///
/// Requests that start before the warm-up ends are not recorded. Latency covers the whole
/// request, including submit, polling and result download for async entries.
///
/// # Errors
///
/// Fails when no entry is eligible for replay.
pub async fn run_load(
    client: &Client,
    entries: Vec<CorpusEntry>,
    config: &LoadConfig,
) -> Result<LoadReport> {
    let entries: Vec<CorpusEntry> = entries
        .into_iter()
        .filter(|e| {
            (!config.only_sync || e.kind == Kind::Sync) && !config.exclude.contains(&e.result_type)
        })
        .collect();
    if entries.is_empty() {
        return Err(BenchError::Invalid("no corpus entries to replay".into()));
    }
    let entries = Arc::new(entries);
    let cursor = Arc::new(AtomicUsize::new(0));
    let start = Instant::now();
    let measure_from = start + config.warmup;
    let end = measure_from + config.duration;

    let mut workers = Vec::new();
    for _ in 0..config.concurrency.max(1) {
        let (client, entries, cursor) = (client.clone(), Arc::clone(&entries), Arc::clone(&cursor));
        workers.push(tokio::spawn(async move {
            let mut groups: BTreeMap<String, Accumulator> = BTreeMap::new();
            while Instant::now() < end {
                let ticket = cursor.fetch_add(1, Ordering::Relaxed);
                let entry = &entries[ticket % entries.len()];
                let began = Instant::now();
                let outcome = client.run(entry, ticket as u64).await;
                if began >= measure_from {
                    groups
                        .entry(entry.result_type.clone())
                        .or_insert_with(Accumulator::new)
                        .record(began.elapsed(), outcome.is_success());
                }
            }
            groups
        }));
    }

    let mut merged: BTreeMap<String, Accumulator> = BTreeMap::new();
    for worker in workers {
        let groups = worker
            .await
            .map_err(|e| BenchError::Invalid(format!("load worker failed: {e}")))?;
        for (key, acc) in groups {
            merged
                .entry(key)
                .or_insert_with(Accumulator::new)
                .merge(&acc);
        }
    }
    let mut overall = Accumulator::new();
    merged.values().for_each(|acc| overall.merge(acc));
    Ok(LoadReport {
        url: client.base().to_owned(),
        concurrency: config.concurrency,
        duration_s: config.duration.as_secs_f64(),
        warmup_s: config.warmup.as_secs_f64(),
        corpus_size: entries.len(),
        overall: overall.stats(config.duration),
        per_type: merged
            .iter()
            .map(|(k, v)| (k.clone(), v.stats(config.duration)))
            .collect(),
    })
}

/// Renders the report as an aligned text table.
pub fn render_table(report: &LoadReport) -> String {
    let mut out = format!(
        "{} concurrency={} window={:.0}s warmup={:.0}s corpus={}\n",
        report.url, report.concurrency, report.duration_s, report.warmup_s, report.corpus_size
    );
    out.push_str(&format!(
        "{:<26}{:>9}{:>8}{:>10}{:>10}{:>10}{:>10}{:>10}\n",
        "result type", "requests", "errors", "req/s", "p50 ms", "p90 ms", "p99 ms", "max ms"
    ));
    let row = |name: &str, s: &Stats| {
        format!(
            "{:<26}{:>9}{:>8}{:>10.1}{:>10.2}{:>10.2}{:>10.2}{:>10.2}\n",
            name, s.requests, s.errors, s.throughput_rps, s.p50_ms, s.p90_ms, s.p99_ms, s.max_ms
        )
    };
    for (name, stats) in &report.per_type {
        out.push_str(&row(name, stats));
    }
    out.push_str(&row("ALL", &report.overall));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accumulator_percentiles_and_errors() {
        let mut acc = Accumulator::new();
        for ms in 1..=100u64 {
            acc.record(Duration::from_millis(ms), true);
        }
        acc.record(Duration::from_millis(5), false);
        let stats = acc.stats(Duration::from_secs(10));
        assert_eq!(stats.requests, 101);
        assert_eq!(stats.errors, 1);
        assert!((stats.p50_ms - 50.0).abs() < 1.0);
        assert!((stats.p99_ms - 99.0).abs() < 1.5);
        assert!((stats.max_ms - 100.0).abs() < 0.5);
        assert!((stats.throughput_rps - 10.1).abs() < 0.01);
    }

    #[test]
    fn table_lists_every_type_and_total() {
        let mut per_type = BTreeMap::new();
        per_type.insert("COUNT".to_owned(), Stats::default());
        let report = LoadReport {
            url: "http://x".into(),
            concurrency: 2,
            duration_s: 1.0,
            warmup_s: 0.0,
            corpus_size: 3,
            overall: Stats::default(),
            per_type,
        };
        let table = render_table(&report);
        assert!(table.contains("COUNT"));
        assert!(table.contains("ALL"));
    }
}
