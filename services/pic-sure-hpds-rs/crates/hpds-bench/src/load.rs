use std::collections::BTreeMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

use hdrhistogram::Histogram;
use serde::Serialize;

use crate::client::{Client, Measured, PollStrategy};
use crate::corpus::{CorpusEntry, Kind};
use crate::{BenchError, Result};

const MAX_LATENCY_MICROS: u64 = 3_600_000_000;

/// Default async status poll interval.
pub const DEFAULT_POLL_INTERVAL: Duration = Duration::from_millis(2);

/// Parameters of one load run.
///
/// The run is closed loop: each worker sends its next request only after the previous one
/// finished, so a slow server also receives fewer requests per second. Latency is measured from
/// the moment a request is sent, and nothing corrects for the requests that an open-loop arrival
/// process would have queued during a stall (coordinated omission). Tail percentiles are
/// therefore a lower bound on what independent clients would see.
#[derive(Debug, Clone)]
pub struct LoadConfig {
    /// Number of concurrent request loops.
    pub concurrency: usize,
    /// Length of the measured window in duration mode.
    pub duration: Duration,
    /// Unmeasured lead-in before the window starts, in duration mode.
    pub warmup: Duration,
    /// Replay sync entries only.
    pub only_sync: bool,
    /// Result types left out of the replay.
    pub exclude: Vec<String>,
    /// When above zero, run this many passes instead of a timed window. Each pass sends every
    /// eligible entry once and is reported on its own; pass 1 is the cold pass.
    pub passes: usize,
    /// Fixed interval between async status polls.
    pub poll_interval: Duration,
    /// The longest an async query may take, used to place timeouts in the second latency view.
    pub timeout: Duration,
}

impl Default for LoadConfig {
    fn default() -> Self {
        Self {
            concurrency: 8,
            duration: Duration::from_secs(60),
            warmup: Duration::ZERO,
            only_sync: false,
            exclude: Vec::new(),
            passes: 0,
            poll_interval: DEFAULT_POLL_INTERVAL,
            timeout: Duration::from_secs(120),
        }
    }
}

/// Latency percentiles in milliseconds.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct Percentiles {
    /// Median.
    pub p50_ms: f64,
    /// 90th percentile.
    pub p90_ms: f64,
    /// 99th percentile.
    pub p99_ms: f64,
    /// Slowest sample.
    pub max_ms: f64,
    /// Mean.
    pub mean_ms: f64,
}

/// Latency and throughput summary for a group of requests.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct Stats {
    /// Completed requests: successes, errors and timeouts.
    pub requests: u64,
    /// Requests that failed or returned a non-success outcome, timeouts excluded.
    pub errors: u64,
    /// Requests that hit the timeout.
    pub timeouts: u64,
    /// Requests per second over the measured span, first start to last finish.
    pub throughput_rps: f64,
    /// Median total latency of successful requests in milliseconds.
    pub p50_ms: f64,
    /// 90th percentile total latency in milliseconds.
    pub p90_ms: f64,
    /// 99th percentile total latency in milliseconds.
    pub p99_ms: f64,
    /// Slowest successful request in milliseconds.
    pub max_ms: f64,
    /// Mean total latency of successful requests in milliseconds.
    pub mean_ms: f64,
    /// Total latency with every timeout recorded at the timeout value.
    pub with_timeouts: Percentiles,
    /// Submit to terminal status for async entries, request to response headers for the rest.
    pub service: Percentiles,
    /// Result fetch and body drain, for requests that fetched a body.
    pub download: Percentiles,
    /// Response body bytes read.
    pub bytes: u64,
}

/// Summary of one pass over the corpus.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct PassReport {
    /// One-based pass number.
    pub pass: usize,
    /// Whether this is the first pass, which runs against cold caches.
    pub cold: bool,
    /// First request start to last request finish, in seconds.
    pub span_s: f64,
    /// All result types together.
    pub overall: Stats,
    /// Per result type.
    pub per_type: BTreeMap<String, Stats>,
}

/// Full result of a load run.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct LoadReport {
    /// Base URL under test.
    pub url: String,
    /// Concurrency used.
    pub concurrency: usize,
    /// `duration` for a timed window, `passes` for corpus passes.
    pub mode: String,
    /// Async status poll interval in milliseconds.
    pub poll_interval_ms: f64,
    /// Measured window in seconds; zero in passes mode.
    pub duration_s: f64,
    /// Warm-up in seconds; zero in passes mode.
    pub warmup_s: f64,
    /// Corpus entries replayed.
    pub corpus_size: usize,
    /// Measured span in seconds: first request start to last finish, summed over passes.
    pub span_s: f64,
    /// All result types together; the whole run in passes mode.
    pub overall: Stats,
    /// Per result type; the whole run in passes mode.
    pub per_type: BTreeMap<String, Stats>,
    /// One report per pass; empty in duration mode.
    pub passes: Vec<PassReport>,
}

fn histogram() -> Histogram<u64> {
    Histogram::new_with_bounds(1, MAX_LATENCY_MICROS, 3).expect("static histogram bounds are valid")
}

fn micros(elapsed: Duration) -> u64 {
    u64::try_from(elapsed.as_micros())
        .unwrap_or(u64::MAX)
        .clamp(1, MAX_LATENCY_MICROS)
}

fn percentiles(h: &Histogram<u64>) -> Percentiles {
    let ms = |micros: f64| micros / 1000.0;
    Percentiles {
        p50_ms: ms(h.value_at_quantile(0.50) as f64),
        p90_ms: ms(h.value_at_quantile(0.90) as f64),
        p99_ms: ms(h.value_at_quantile(0.99) as f64),
        max_ms: ms(h.max() as f64),
        mean_ms: ms(h.mean()),
    }
}

struct Accumulator {
    latency: Histogram<u64>,
    with_timeouts: Histogram<u64>,
    service: Histogram<u64>,
    download: Histogram<u64>,
    errors: u64,
    timeouts: u64,
    bytes: u64,
}

impl Accumulator {
    fn new() -> Self {
        Self {
            latency: histogram(),
            with_timeouts: histogram(),
            service: histogram(),
            download: histogram(),
            errors: 0,
            timeouts: 0,
            bytes: 0,
        }
    }

    fn record(&mut self, total: Duration, measured: &Measured, timeout: Duration) {
        self.bytes += measured.bytes;
        if measured.timed_out {
            self.timeouts += 1;
            self.with_timeouts.saturating_record(micros(timeout));
        } else if measured.success {
            let value = micros(total);
            self.latency.saturating_record(value);
            self.with_timeouts.saturating_record(value);
            self.service.saturating_record(micros(measured.service));
            if let Some(download) = measured.download {
                self.download.saturating_record(micros(download));
            }
        } else {
            self.errors += 1;
        }
    }

    fn merge(&mut self, other: &Accumulator) {
        self.errors += other.errors;
        self.timeouts += other.timeouts;
        self.bytes += other.bytes;
        for (mine, theirs) in [
            (&mut self.latency, &other.latency),
            (&mut self.with_timeouts, &other.with_timeouts),
            (&mut self.service, &other.service),
            (&mut self.download, &other.download),
        ] {
            mine.add(theirs).expect("histograms share bounds");
        }
    }

    fn stats(&self, span: Duration) -> Stats {
        let requests = self.latency.len() + self.errors + self.timeouts;
        let base = percentiles(&self.latency);
        Stats {
            requests,
            errors: self.errors,
            timeouts: self.timeouts,
            throughput_rps: if span.is_zero() {
                0.0
            } else {
                requests as f64 / span.as_secs_f64()
            },
            p50_ms: base.p50_ms,
            p90_ms: base.p90_ms,
            p99_ms: base.p99_ms,
            max_ms: base.max_ms,
            mean_ms: base.mean_ms,
            with_timeouts: percentiles(&self.with_timeouts),
            service: percentiles(&self.service),
            download: percentiles(&self.download),
            bytes: self.bytes,
        }
    }
}

#[derive(Clone, Copy, Default)]
struct Span {
    first_start: Option<Instant>,
    last_finish: Option<Instant>,
}

impl Span {
    fn note(&mut self, start: Instant, finish: Instant) {
        self.first_start = Some(self.first_start.map_or(start, |s| s.min(start)));
        self.last_finish = Some(self.last_finish.map_or(finish, |f| f.max(finish)));
    }

    fn merge(&mut self, other: Span) {
        if let (Some(start), Some(finish)) = (other.first_start, other.last_finish) {
            self.note(start, finish);
        }
    }

    fn length(&self) -> Duration {
        match (self.first_start, self.last_finish) {
            (Some(start), Some(finish)) => finish.saturating_duration_since(start),
            _ => Duration::ZERO,
        }
    }
}

struct PassResult {
    groups: BTreeMap<String, Accumulator>,
    span: Span,
}

#[derive(Clone, Copy)]
enum Window {
    Timed { measure_from: Instant, end: Instant },
    Once,
}

async fn run_pass(
    client: &Client,
    entries: &Arc<Vec<CorpusEntry>>,
    config: &LoadConfig,
    window: Window,
) -> Result<PassResult> {
    let cursor = Arc::new(AtomicUsize::new(0));
    let poll = PollStrategy::Fixed(config.poll_interval);
    let mut workers = Vec::new();
    for worker_index in 0..config.concurrency.max(1) {
        let (client, entries, cursor) = (client.clone(), Arc::clone(entries), Arc::clone(&cursor));
        let timeout = config.timeout;
        workers.push(tokio::spawn(async move {
            let mut groups: BTreeMap<String, Accumulator> = BTreeMap::new();
            let mut span = Span::default();
            loop {
                let ticket = cursor.fetch_add(1, Ordering::Relaxed);
                let (entry, measure) = match window {
                    Window::Timed { measure_from, end } => {
                        if Instant::now() >= end {
                            break;
                        }
                        (&entries[ticket % entries.len()], Some(measure_from))
                    }
                    Window::Once => {
                        let Some(entry) = entries.get(ticket) else {
                            break;
                        };
                        (entry, None)
                    }
                };
                let began = Instant::now();
                let measured = client.run_measured(entry, worker_index as u64, poll).await;
                let finished = Instant::now();
                if measure.is_none_or(|from| began >= from) {
                    span.note(began, finished);
                    groups
                        .entry(entry.result_type.clone())
                        .or_insert_with(Accumulator::new)
                        .record(finished - began, &measured, timeout);
                }
            }
            (groups, span)
        }));
    }
    let mut merged: BTreeMap<String, Accumulator> = BTreeMap::new();
    let mut span = Span::default();
    for worker in workers {
        let (groups, worker_span) = worker
            .await
            .map_err(|e| BenchError::Invalid(format!("load worker failed: {e}")))?;
        span.merge(worker_span);
        for (key, acc) in groups {
            merged
                .entry(key)
                .or_insert_with(Accumulator::new)
                .merge(&acc);
        }
    }
    Ok(PassResult {
        groups: merged,
        span,
    })
}

fn summarize(
    groups: &BTreeMap<String, Accumulator>,
    span: Duration,
) -> (Stats, BTreeMap<String, Stats>) {
    let mut overall = Accumulator::new();
    groups.values().for_each(|acc| overall.merge(acc));
    (
        overall.stats(span),
        groups
            .iter()
            .map(|(k, v)| (k.clone(), v.stats(span)))
            .collect(),
    )
}

fn merge_groups(into: &mut BTreeMap<String, Accumulator>, from: BTreeMap<String, Accumulator>) {
    for (key, acc) in from {
        into.entry(key).or_insert_with(Accumulator::new).merge(&acc);
    }
}

/// Replays the corpus from `concurrency` closed-loop workers.
///
/// With `passes` at zero the entries are cycled round-robin for the configured window, and
/// requests that start before the warm-up ends are not recorded. With `passes` above zero each
/// pass sends every eligible entry exactly once and is reported separately. Error-path entries
/// are never replayed. Async requests carry a `picsureId` derived from the worker index, so ids
/// are unique among in-flight requests while the server's result map stays bounded.
///
/// Latency covers the whole request, including submit, polling and result download. Throughput
/// is requests over the span from the first measured start to the last measured finish. Result
/// bodies are streamed and counted, not kept.
///
/// # Errors
///
/// Fails when no entry is eligible for replay or a worker task panics.
pub async fn run_load(
    client: &Client,
    entries: Vec<CorpusEntry>,
    config: &LoadConfig,
) -> Result<LoadReport> {
    let entries: Vec<CorpusEntry> = entries
        .into_iter()
        .filter(|e| {
            !e.error_path
                && (!config.only_sync || e.kind != Kind::Async)
                && !config.exclude.contains(&e.result_type)
        })
        .collect();
    if entries.is_empty() {
        return Err(BenchError::Invalid("no corpus entries to replay".into()));
    }
    let entries = Arc::new(entries);
    let corpus_size = entries.len();
    let base = |mode: &str| LoadReport {
        url: client.base().to_owned(),
        concurrency: config.concurrency,
        mode: mode.to_owned(),
        poll_interval_ms: config.poll_interval.as_secs_f64() * 1000.0,
        duration_s: 0.0,
        warmup_s: 0.0,
        corpus_size,
        span_s: 0.0,
        overall: Stats::default(),
        per_type: BTreeMap::new(),
        passes: Vec::new(),
    };

    if config.passes == 0 {
        let start = Instant::now();
        let window = Window::Timed {
            measure_from: start + config.warmup,
            end: start + config.warmup + config.duration,
        };
        let result = run_pass(client, &entries, config, window).await?;
        let (overall, per_type) = summarize(&result.groups, result.span.length());
        return Ok(LoadReport {
            duration_s: config.duration.as_secs_f64(),
            warmup_s: config.warmup.as_secs_f64(),
            span_s: result.span.length().as_secs_f64(),
            overall,
            per_type,
            ..base("duration")
        });
    }

    let mut report = base("passes");
    let mut total = BTreeMap::new();
    let mut total_span = Duration::ZERO;
    for pass in 1..=config.passes {
        let result = run_pass(client, &entries, config, Window::Once).await?;
        let span = result.span.length();
        let (overall, per_type) = summarize(&result.groups, span);
        report.passes.push(PassReport {
            pass,
            cold: pass == 1,
            span_s: span.as_secs_f64(),
            overall,
            per_type,
        });
        total_span += span;
        merge_groups(&mut total, result.groups);
    }
    let (overall, per_type) = summarize(&total, total_span);
    report.span_s = total_span.as_secs_f64();
    report.overall = overall;
    report.per_type = per_type;
    Ok(report)
}

fn table(title: &str, per_type: &BTreeMap<String, Stats>, overall: &Stats) -> String {
    let mut out = format!(
        "{title}\n{:<26}{:>9}{:>8}{:>9}{:>10}{:>10}{:>10}{:>10}{:>10}{:>10}{:>10}\n",
        "result type",
        "requests",
        "errors",
        "timeouts",
        "req/s",
        "p50 ms",
        "p90 ms",
        "p99 ms",
        "max ms",
        "svc p50",
        "dl p50"
    );
    let row = |name: &str, s: &Stats| {
        format!(
            "{:<26}{:>9}{:>8}{:>9}{:>10.1}{:>10.2}{:>10.2}{:>10.2}{:>10.2}{:>10.2}{:>10.2}\n",
            name,
            s.requests,
            s.errors,
            s.timeouts,
            s.throughput_rps,
            s.p50_ms,
            s.p90_ms,
            s.p99_ms,
            s.max_ms,
            s.service.p50_ms,
            s.download.p50_ms
        )
    };
    for (name, stats) in per_type {
        out.push_str(&row(name, stats));
    }
    out.push_str(&row("ALL", overall));
    out
}

/// Renders the report as aligned text tables, one per pass in passes mode.
pub fn render_table(report: &LoadReport) -> String {
    let mut out = format!(
        "{} mode={} concurrency={} window={:.0}s warmup={:.0}s span={:.1}s corpus={} poll={:.0}ms (closed loop, no coordinated omission correction)\n",
        report.url,
        report.mode,
        report.concurrency,
        report.duration_s,
        report.warmup_s,
        report.span_s,
        report.corpus_size,
        report.poll_interval_ms
    );
    if report.passes.is_empty() {
        out.push_str(&table("", &report.per_type, &report.overall));
        return out;
    }
    for pass in &report.passes {
        let title = format!(
            "pass {}{} span={:.1}s",
            pass.pass,
            if pass.cold { " (cold)" } else { "" },
            pass.span_s
        );
        out.push_str(&table(&title, &pass.per_type, &pass.overall));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(service_ms: u64, download_ms: Option<u64>, bytes: u64) -> Measured {
        Measured {
            success: true,
            timed_out: false,
            service: Duration::from_millis(service_ms),
            download: download_ms.map(Duration::from_millis),
            bytes,
        }
    }

    #[test]
    fn accumulator_percentiles_errors_and_timeouts() {
        let mut acc = Accumulator::new();
        let timeout = Duration::from_secs(2);
        for ms in 1..=100u64 {
            acc.record(
                Duration::from_millis(ms),
                &ok(ms / 2, Some(ms / 4), 10),
                timeout,
            );
        }
        acc.record(Duration::from_millis(5), &Measured::default(), timeout);
        acc.record(
            Duration::from_secs(2),
            &Measured {
                timed_out: true,
                ..Measured::default()
            },
            timeout,
        );
        let stats = acc.stats(Duration::from_secs(10));
        assert_eq!(stats.requests, 102);
        assert_eq!(stats.errors, 1);
        assert_eq!(stats.timeouts, 1);
        assert!((stats.p50_ms - 50.0).abs() < 1.0);
        assert!((stats.p99_ms - 99.0).abs() < 1.5);
        assert!((stats.max_ms - 100.0).abs() < 0.5);
        assert!((stats.throughput_rps - 10.2).abs() < 0.01);
        assert!((stats.with_timeouts.max_ms - 2000.0).abs() < 2.0);
        assert!(stats.with_timeouts.p99_ms > stats.p99_ms);
        assert!((stats.service.p50_ms - 25.0).abs() < 1.0);
        assert!((stats.download.p50_ms - 12.0).abs() < 1.5);
        assert_eq!(stats.bytes, 1000);
    }

    #[test]
    fn span_runs_from_first_start_to_last_finish() {
        let base = Instant::now();
        let mut span = Span::default();
        assert_eq!(span.length(), Duration::ZERO);
        span.note(
            base + Duration::from_millis(10),
            base + Duration::from_millis(30),
        );
        span.note(
            base + Duration::from_millis(5),
            base + Duration::from_millis(20),
        );
        let mut other = Span::default();
        other.note(
            base + Duration::from_millis(40),
            base + Duration::from_millis(90),
        );
        span.merge(other);
        assert_eq!(span.length(), Duration::from_millis(85));
    }

    #[test]
    fn table_lists_every_type_and_total_and_labels_passes() {
        let mut per_type = BTreeMap::new();
        per_type.insert("COUNT".to_owned(), Stats::default());
        let pass = |n: usize| PassReport {
            pass: n,
            cold: n == 1,
            span_s: 1.0,
            overall: Stats::default(),
            per_type: per_type.clone(),
        };
        let report = LoadReport {
            url: "http://x".into(),
            concurrency: 2,
            mode: "passes".into(),
            poll_interval_ms: 2.0,
            duration_s: 0.0,
            warmup_s: 0.0,
            corpus_size: 3,
            span_s: 2.0,
            overall: Stats::default(),
            per_type: per_type.clone(),
            passes: vec![pass(1), pass(2)],
        };
        let table = render_table(&report);
        assert!(table.contains("COUNT") && table.contains("ALL"));
        assert!(table.contains("pass 1 (cold)") && table.contains("pass 2 span"));
        assert!(table.contains("coordinated omission"));
    }
}
