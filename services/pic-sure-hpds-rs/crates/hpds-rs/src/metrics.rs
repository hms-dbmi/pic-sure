//! `GET /metrics`: counters in the Prometheus text exposition format (version 0.0.4), served
//! only when `HPDS_METRICS=on`.
//!
//! Every label value comes from a fixed set: route templates as the router declares them,
//! result type names, error kinds, histogram bounds and export outcomes. No label or value
//! carries a patient id, concept path, consent name, result id or anything else from a
//! request body or the store.

use std::fmt::Write as _;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use axum::extract::{MatchedPath, Request, State};
use axum::http::StatusCode;
use axum::http::header::CONTENT_TYPE;
use axum::middleware::Next;
use axum::response::{IntoResponse, Response};
use hpds_query::{BlockCacheStats, GenomicStore, PrefixReadStats, ResultType};

use crate::genomic::GenomicStatus;
use crate::state::AppState;

/// Content type of the exposition format.
pub const METRICS_CONTENT_TYPE: &str = "text/plain; version=0.0.4; charset=utf-8";

/// The routes requests are counted under, as their templates: each API route under both
/// mounts, then the routes at the root. A request that matches no route counts as
/// `unmatched`.
pub const ROUTES: [&str; 23] = [
    "/PIC-SURE/v3/query/sync",
    "/PIC-SURE/v3/query",
    "/PIC-SURE/v3/query/format",
    "/PIC-SURE/v3/query/{id}/status",
    "/PIC-SURE/v3/query/{id}/result",
    "/PIC-SURE/v3/query/{id}/signed-url",
    "/PIC-SURE/v3/write/{data_type}",
    "/PIC-SURE/v3/search",
    "/PIC-SURE/v3/search/values/",
    "/PIC-SURE/v3/search/values",
    "/PIC-SURE/query/sync",
    "/PIC-SURE/query",
    "/PIC-SURE/query/format",
    "/PIC-SURE/query/{id}/status",
    "/PIC-SURE/query/{id}/result",
    "/PIC-SURE/query/{id}/signed-url",
    "/PIC-SURE/write/{data_type}",
    "/PIC-SURE/search",
    "/PIC-SURE/search/values/",
    "/PIC-SURE/search/values",
    "/actuator/health",
    "/metrics",
    "unmatched",
];

const UNMATCHED: usize = ROUTES.len() - 1;

/// Upper bounds of the request duration histogram, in seconds.
pub const DURATION_BUCKETS: [f64; 16] = [
    0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0,
    60.0,
];

/// The kinds failed requests are counted by, from their status.
const ERROR_KINDS: [&str; 8] = [
    "bad_request",
    "not_found",
    "request_timeout",
    "payload_too_large",
    "internal",
    "unavailable",
    "other_client",
    "other_server",
];

/// The result type a query route answered, attached to its response for [`record`].
#[derive(Debug, Clone, Copy)]
pub struct ResultTypeLabel(pub ResultType);

/// Counters of the HTTP layer. The store's own counters are read from the engine at scrape
/// time.
#[derive(Debug)]
pub struct Metrics {
    requests: Vec<AtomicU64>,
    durations: Vec<Histogram>,
    errors: [AtomicU64; ERROR_KINDS.len()],
}

/// Request durations: one counter per bound of [`DURATION_BUCKETS`] holding the requests
/// that fell in it and no lower one, and a last counter for those above every bound. Each
/// observation adds to exactly one counter, so a reader that loads each counter once and
/// sums them gets cumulative buckets that never decrease and a `+Inf` bucket equal to the
/// count, however observations interleave with the read.
#[derive(Debug, Default)]
struct Histogram {
    buckets: [AtomicU64; DURATION_BUCKETS.len() + 1],
    nanos: AtomicU64,
}

impl Histogram {
    fn observe(&self, elapsed: Duration) {
        let seconds = elapsed.as_secs_f64();
        let bucket = DURATION_BUCKETS
            .iter()
            .position(|&bound| seconds <= bound)
            .unwrap_or(DURATION_BUCKETS.len());
        self.buckets[bucket].fetch_add(1, Ordering::Relaxed);
        self.nanos.fetch_add(
            u64::try_from(elapsed.as_nanos()).unwrap_or(u64::MAX),
            Ordering::Relaxed,
        );
    }
}

/// Result type slots per route: one per [`ResultType`] and one for a request that names none.
const TYPE_SLOTS: usize = ResultType::ALL.len() + 1;

impl Default for Metrics {
    fn default() -> Self {
        Metrics {
            requests: (0..ROUTES.len() * TYPE_SLOTS)
                .map(|_| AtomicU64::new(0))
                .collect(),
            durations: (0..ROUTES.len()).map(|_| Histogram::default()).collect(),
            errors: Default::default(),
        }
    }
}

impl Metrics {
    /// Counts one request to the route at `route` in [`ROUTES`], answered with `status`
    /// after `elapsed`, for `result_type` when the route names one.
    fn observe(
        &self,
        route: usize,
        result_type: Option<ResultType>,
        status: StatusCode,
        elapsed: Duration,
    ) {
        let slot = result_type.map_or(ResultType::ALL.len(), |t| t as usize);
        self.requests[route * TYPE_SLOTS + slot].fetch_add(1, Ordering::Relaxed);
        self.durations[route].observe(elapsed);
        if let Some(kind) = error_kind(status) {
            self.errors[kind].fetch_add(1, Ordering::Relaxed);
        }
    }
}

/// The index in [`ERROR_KINDS`] of a failed request's status, `None` for a success.
fn error_kind(status: StatusCode) -> Option<usize> {
    let code = status.as_u16();
    if !(400..=599).contains(&code) {
        return None;
    }
    let kind = [400, 404, 408, 413, 500, 503]
        .iter()
        .position(|&known| known == code)
        .unwrap_or(if code < 500 { 6 } else { 7 });
    Some(kind)
}

fn route_index(template: &str) -> usize {
    ROUTES[..UNMATCHED]
        .iter()
        .position(|&route| route == template)
        .unwrap_or(UNMATCHED)
}

/// Middleware run only on requests that match a route: copies the matched route template
/// into the response, where [`record`] reads it.
pub async fn tag_route(request: Request, next: Next) -> Response {
    let matched = request.extensions().get::<MatchedPath>().cloned();
    let mut response = next.run(request).await;
    if let Some(matched) = matched {
        response.extensions_mut().insert(matched);
    }
    response
}

/// Middleware around the whole router: counts every request by route, result type and
/// status, and times it to its response head (a streamed download's body is not timed).
pub async fn record(State(state): State<AppState>, request: Request, next: Next) -> Response {
    let started = Instant::now();
    let response = next.run(request).await;
    if let Some(metrics) = state.metrics() {
        let route = response
            .extensions()
            .get::<MatchedPath>()
            .map_or(UNMATCHED, |matched| route_index(matched.as_str()));
        let result_type = response
            .extensions()
            .get::<ResultTypeLabel>()
            .map(|label| label.0);
        metrics.observe(route, result_type, response.status(), started.elapsed());
    }
    response
}

/// Attaches `result_type` to `response` for [`record`] when metrics are on.
pub fn label_result_type(
    state: &AppState,
    result_type: ResultType,
    mut response: Response,
) -> Response {
    if state.metrics().is_some() {
        response
            .extensions_mut()
            .insert(ResultTypeLabel(result_type));
    }
    response
}

/// `GET /metrics`: every counter as Prometheus text. Reading the block cache walks it, so
/// the work runs on tokio's blocking pool.
pub async fn metrics(State(state): State<AppState>) -> Response {
    let rendered = tokio::task::spawn_blocking(move || render(&state)).await;
    match rendered {
        Ok(text) => ([(CONTENT_TYPE, METRICS_CONTENT_TYPE)], text).into_response(),
        Err(error) => {
            tracing::error!(%error, "metrics task failed");
            crate::error::ApiError::internal().into_response()
        }
    }
}

/// Writes one metric family: its help and type lines, then each sample.
struct Family<'a> {
    out: &'a mut String,
}

impl Family<'_> {
    fn header(&mut self, name: &str, kind: &str, help: &str) {
        let _ = writeln!(self.out, "# HELP {name} {help}");
        let _ = writeln!(self.out, "# TYPE {name} {kind}");
    }

    fn sample(&mut self, name: &str, labels: &[(&str, &str)], value: impl std::fmt::Display) {
        self.out.push_str(name);
        if !labels.is_empty() {
            self.out.push('{');
            for (i, (key, label)) in labels.iter().enumerate() {
                if i > 0 {
                    self.out.push(',');
                }
                let _ = write!(self.out, "{key}=\"{label}\"");
            }
            self.out.push('}');
        }
        let _ = writeln!(self.out, " {value}");
    }

    fn single(&mut self, name: &str, kind: &str, help: &str, value: impl std::fmt::Display) {
        self.header(name, kind, help);
        self.sample(name, &[], value);
    }
}

/// Every metric as Prometheus text.
pub fn render(state: &AppState) -> String {
    let mut out = String::with_capacity(16 << 10);
    let mut family = Family { out: &mut out };
    if let Some(metrics) = state.metrics() {
        render_http(&mut family, metrics);
    }
    render_results(&mut family, state);
    if let Ok(engine) = state.engine() {
        let partitions = engine.partitions();
        render_block_cache(&mut family, &partitions.block_cache_stats());
        render_prefix_reads(&mut family, &partitions.prefix_read_stats());
        family.single(
            "hpds_store_superseded",
            "gauge",
            "1 while another store has been moved into the data directory and the service serves the store it opened from its open files; a restart serves the new one.",
            u64::from(partitions.store_superseded()),
        );
        family.single(
            "hpds_cohort_guard_refusals_total",
            "counter",
            "Queries refused because a cohort held patients outside the selected partitions.",
            partitions.cohort_guard_refusals(),
        );
        if state.genomic_status() != GenomicStatus::Off {
            family.single(
                "hpds_genomic_store_up",
                "gauge",
                "1 while the genomic store is served, 0 while one is configured and not served; absent without genomic data.",
                u64::from(state.genomic_status() == GenomicStatus::Served),
            );
        }
        if let Some(store) = engine.genomic() {
            render_genomic(&mut family, store);
        }
        if let Some(stats) = engine.result_cache_stats() {
            family.single(
                "hpds_result_cache_hits_total",
                "counter",
                "Sync answers served from the result cache.",
                stats.hits,
            );
            family.single(
                "hpds_result_cache_misses_total",
                "counter",
                "Cacheable sync queries the result cache did not hold.",
                stats.misses,
            );
            family.single(
                "hpds_result_cache_evictions_total",
                "counter",
                "Result cache entries dropped to stay within its byte bound.",
                stats.evictions,
            );
            family.single(
                "hpds_result_cache_entries",
                "gauge",
                "Answers held by the result cache.",
                stats.entries,
            );
            family.single(
                "hpds_result_cache_bytes",
                "gauge",
                "Bytes held by the result cache, keys and overhead included.",
                stats.bytes,
            );
            family.single(
                "hpds_result_cache_capacity_bytes",
                "gauge",
                "Byte bound of the result cache.",
                stats.capacity,
            );
        }
    }
    out
}

fn render_http(family: &mut Family<'_>, metrics: &Metrics) {
    family.header(
        "hpds_http_requests_total",
        "counter",
        "Requests answered, by route template and the result type the query named.",
    );
    for (route, name) in ROUTES.iter().enumerate() {
        for slot in 0..TYPE_SLOTS {
            let count = metrics.requests[route * TYPE_SLOTS + slot].load(Ordering::Relaxed);
            if count == 0 {
                continue;
            }
            let result_type = ResultType::ALL.get(slot).map_or("none", |t| t.java_name());
            family.sample(
                "hpds_http_requests_total",
                &[("route", name), ("result_type", result_type)],
                count,
            );
        }
    }
    family.header(
        "hpds_http_request_duration_seconds",
        "histogram",
        "Time from receiving a request to sending its response head, by route template.",
    );
    for (route, name) in ROUTES.iter().enumerate() {
        let histogram = &metrics.durations[route];
        let counts: Vec<u64> = histogram
            .buckets
            .iter()
            .map(|bucket| bucket.load(Ordering::Relaxed))
            .collect();
        let count: u64 = counts.iter().sum();
        if count == 0 {
            continue;
        }
        let mut cumulative = 0;
        for (bound, bucket) in DURATION_BUCKETS.iter().zip(&counts) {
            cumulative += bucket;
            let le = bound.to_string();
            family.sample(
                "hpds_http_request_duration_seconds_bucket",
                &[("route", name), ("le", &le)],
                cumulative,
            );
        }
        family.sample(
            "hpds_http_request_duration_seconds_bucket",
            &[("route", name), ("le", "+Inf")],
            count,
        );
        family.sample(
            "hpds_http_request_duration_seconds_sum",
            &[("route", name)],
            histogram.nanos.load(Ordering::Relaxed) as f64 / 1e9,
        );
        family.sample(
            "hpds_http_request_duration_seconds_count",
            &[("route", name)],
            count,
        );
    }
    family.header(
        "hpds_http_errors_total",
        "counter",
        "Requests answered with a 4xx or 5xx status, by kind.",
    );
    for (kind, name) in ERROR_KINDS.iter().enumerate() {
        family.sample(
            "hpds_http_errors_total",
            &[("kind", name)],
            metrics.errors[kind].load(Ordering::Relaxed),
        );
    }
    family.single(
        "hpds_connections_refused_total",
        "counter",
        "Connections refused because every connection slot was taken; each is answered 503 when a refusal slot is free, otherwise closed at once.",
        crate::connections_refused(),
    );
}

fn render_results(family: &mut Family<'_>, state: &AppState) {
    let results = state.results();
    family.single(
        "hpds_async_results",
        "gauge",
        "Async results held: pending, running and finished.",
        results.len(),
    );
    family.header(
        "hpds_exports_finished_total",
        "counter",
        "Async queries finished, by outcome: held in memory, held in a file, or failed.",
    );
    let finished = results.finished();
    for (outcome, count) in [
        ("memory", finished.memory),
        ("file", finished.file),
        ("error", finished.error),
    ] {
        family.sample(
            "hpds_exports_finished_total",
            &[("outcome", outcome)],
            count,
        );
    }
    family.single(
        "hpds_result_memory_bytes",
        "gauge",
        "Bytes of export results held in memory, downloads in progress included.",
        results.memory_bytes(),
    );
    family.single(
        "hpds_result_memory_budget_bytes",
        "gauge",
        "Budget of the export results held in memory; 0 when every result goes to a file.",
        results.memory_budget(),
    );
}

fn render_prefix_reads(family: &mut Family<'_>, stats: &PrefixReadStats) {
    family.single(
        "hpds_prefix_read_budget_bytes",
        "gauge",
        "Bound on the decoded bytes ANY_RECORD_OF reads hold at once outside the block cache.",
        stats.budget,
    );
    family.single(
        "hpds_prefix_read_held_bytes",
        "gauge",
        "Decoded bytes ANY_RECORD_OF reads hold now under the budget.",
        stats.held,
    );
    family.single(
        "hpds_prefix_read_held_peak_bytes",
        "gauge",
        "The most decoded bytes ANY_RECORD_OF reads have held at once.",
        stats.peak_held,
    );
    family.single(
        "hpds_prefix_read_permits_total",
        "counter",
        "Chunks of ANY_RECORD_OF reads granted bytes of the budget.",
        stats.permits,
    );
    family.single(
        "hpds_prefix_read_waits_total",
        "counter",
        "Chunks that waited for bytes of the budget to be released.",
        stats.waits,
    );
    family.single(
        "hpds_prefix_read_wait_seconds_total",
        "counter",
        "Seconds chunks spent waiting for the budget, summed over threads.",
        stats.wait_seconds,
    );
}

fn render_genomic(family: &mut Family<'_>, store: &GenomicStore) {
    let cache = store.cache_stats();
    let counters: [(&str, &str, &str, u64); 8] = [
        (
            "hpds_genomic_cache_hits_total",
            "counter",
            "Genomic block lookups that found their block cached.",
            cache.hits,
        ),
        (
            "hpds_genomic_cache_misses_total",
            "counter",
            "Genomic block lookups that did not.",
            cache.misses,
        ),
        (
            "hpds_genomic_cache_loads_total",
            "counter",
            "Genomic blocks read and admitted to the cache.",
            cache.loads,
        ),
        (
            "hpds_genomic_cache_evictions_total",
            "counter",
            "Genomic blocks dropped to stay within the cache's byte bound.",
            cache.evictions,
        ),
        (
            "hpds_genomic_cache_scan_reads_total",
            "counter",
            "Genomic blocks read by scans under the read budget and dropped without being cached.",
            cache.scan_reads,
        ),
        (
            "hpds_genomic_cache_entries",
            "gauge",
            "Genomic blocks the cache holds.",
            cache.entries,
        ),
        (
            "hpds_genomic_cache_bytes",
            "gauge",
            "Bytes the genomic cache holds, each decoded block plus a fixed overhead.",
            cache.bytes,
        ),
        (
            "hpds_genomic_cache_capacity_bytes",
            "gauge",
            "Byte bound of the genomic cache (HPDS_GENOMIC_CACHE_BYTES).",
            cache.capacity,
        ),
    ];
    for (name, kind, help, value) in counters {
        family.single(name, kind, help, value);
    }
    let budget = store.read_stats();
    family.single(
        "hpds_genomic_read_budget_bytes",
        "gauge",
        "Bound on the bytes genomic scans hold at once outside the cache (HPDS_GENOMIC_READ_BUDGET_BYTES).",
        budget.budget,
    );
    family.single(
        "hpds_genomic_read_held_bytes",
        "gauge",
        "Stored plus decoded bytes genomic scans hold now under the budget.",
        budget.held,
    );
    family.single(
        "hpds_genomic_read_held_peak_bytes",
        "gauge",
        "The most bytes genomic scans have held at once under the budget.",
        budget.peak_held,
    );
    family.single(
        "hpds_genomic_read_permits_total",
        "counter",
        "Genomic blocks granted bytes of the read budget.",
        budget.permits,
    );
    family.single(
        "hpds_genomic_read_waits_total",
        "counter",
        "Genomic blocks that waited for bytes of the read budget to be released.",
        budget.waits,
    );
    family.single(
        "hpds_genomic_read_wait_seconds_total",
        "counter",
        "Seconds genomic reads spent waiting for the read budget, summed over threads.",
        budget.wait_seconds,
    );
    family.single(
        "hpds_genomic_largest_chunk_set_bytes",
        "gauge",
        "Stored plus decoded bytes of the store's largest chunk set, what each genomic thread may hold reading a block before it is cached.",
        store.largest_chunk_set_bytes(),
    );
    family.single(
        "hpds_genomic_threads",
        "gauge",
        "Threads of the genomic pool.",
        store.threads(),
    );
    let (values, value_bytes) = store.value_list_stats();
    family.single(
        "hpds_genomic_search_values",
        "gauge",
        "INFO values kept in the categorical columns' search lists.",
        values,
    );
    family.single(
        "hpds_genomic_search_value_bytes",
        "gauge",
        "Bytes of the INFO values kept in the search lists.",
        value_bytes,
    );
    family.single(
        "hpds_genomic_store_superseded",
        "gauge",
        "1 while another genomic store has been moved into the genomic directory and the service serves the one it opened; a restart serves the new one.",
        u64::from(store.store_superseded()),
    );
}

fn render_block_cache(family: &mut Family<'_>, stats: &BlockCacheStats) {
    family.single(
        "hpds_block_cache_hits_total",
        "counter",
        "Block cache lookups that found the concept cached.",
        stats.hits,
    );
    family.single(
        "hpds_block_cache_misses_total",
        "counter",
        "Block cache lookups that did not.",
        stats.misses,
    );
    family.single(
        "hpds_block_cache_evictions_total",
        "counter",
        "Block cache entries dropped to stay within its byte bound.",
        stats.evictions,
    );
    family.single(
        "hpds_block_cache_entries",
        "gauge",
        "Concepts held by the block cache.",
        stats.entries,
    );
    family.single(
        "hpds_block_cache_bytes",
        "gauge",
        "Bytes held by the block cache, blocks and partition views.",
        stats.bytes,
    );
    family.single(
        "hpds_block_cache_capacity_bytes",
        "gauge",
        "Byte bound of the block cache.",
        stats.capacity,
    );
    family.single(
        "hpds_block_cache_views",
        "gauge",
        "Cached concepts that also hold a partition view.",
        stats.views,
    );
    family.single(
        "hpds_block_cache_view_bytes",
        "gauge",
        "Bytes of the partition views held, part of hpds_block_cache_bytes.",
        stats.view_bytes,
    );
    family.single(
        "hpds_block_loads_total",
        "counter",
        "Blocks read from the store, authenticated and decoded into the cache.",
        stats.loads,
    );
    family.single(
        "hpds_block_load_failures_total",
        "counter",
        "Block reads that failed; nothing was cached for them.",
        stats.load_failures,
    );
    family.single(
        "hpds_block_load_seconds_total",
        "counter",
        "Seconds spent reading blocks, failed reads included, summed over threads.",
        stats.load_seconds,
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_route_template_but_unmatched_is_found() {
        for (index, route) in ROUTES[..UNMATCHED].iter().enumerate() {
            assert_eq!(route_index(route), index);
        }
        assert_eq!(route_index("/PIC-SURE/v3/query/abc/status"), UNMATCHED);
    }

    #[test]
    fn statuses_map_to_error_kinds() {
        assert_eq!(error_kind(StatusCode::OK), None);
        assert_eq!(error_kind(StatusCode::BAD_REQUEST), Some(0));
        assert_eq!(error_kind(StatusCode::IM_A_TEAPOT), Some(6));
        assert_eq!(error_kind(StatusCode::BAD_GATEWAY), Some(7));
    }

    #[test]
    fn durations_land_in_the_first_bucket_that_holds_them() {
        let histogram = Histogram::default();
        histogram.observe(Duration::from_micros(500));
        histogram.observe(Duration::from_millis(3));
        histogram.observe(Duration::from_secs(120));
        let counts: Vec<u64> = histogram
            .buckets
            .iter()
            .map(|b| b.load(Ordering::Relaxed))
            .collect();
        assert_eq!(counts[0], 1);
        assert_eq!(counts[3], 1);
        assert_eq!(counts[DURATION_BUCKETS.len()], 1, "above every bound");
        assert_eq!(counts.iter().sum::<u64>(), 3);
    }

    /// The bucket values and the `_count` of the first route in `text`.
    fn first_histogram(text: &str) -> (Vec<u64>, u64) {
        let value = |line: &str| line.rsplit_once(' ').unwrap().1.parse::<u64>().unwrap();
        let route = format!("route=\"{}\"", ROUTES[0]);
        let buckets = text
            .lines()
            .filter(|l| {
                l.starts_with("hpds_http_request_duration_seconds_bucket") && l.contains(&route)
            })
            .map(value)
            .collect();
        let count = text
            .lines()
            .find(|l| {
                l.starts_with("hpds_http_request_duration_seconds_count") && l.contains(&route)
            })
            .map_or(0, value);
        (buckets, count)
    }

    #[test]
    fn scrapes_during_observations_stay_cumulative_and_whole() {
        let metrics = Metrics::default();
        let done = std::sync::atomic::AtomicBool::new(false);
        std::thread::scope(|scope| {
            for worker in 0..8u64 {
                let (metrics, done) = (&metrics, &done);
                scope.spawn(move || {
                    let mut i = worker;
                    while !done.load(Ordering::Relaxed) {
                        let elapsed = Duration::from_micros((i * 7919) % 90_000_000);
                        metrics.observe(0, None, StatusCode::OK, elapsed);
                        i += 8;
                    }
                });
            }
            let mut previous = 0;
            for _ in 0..500 {
                let mut text = String::new();
                render_http(&mut Family { out: &mut text }, &metrics);
                let (buckets, count) = first_histogram(&text);
                if buckets.is_empty() {
                    continue;
                }
                assert_eq!(buckets.len(), DURATION_BUCKETS.len() + 1);
                assert!(buckets.windows(2).all(|w| w[0] <= w[1]), "{buckets:?}");
                assert_eq!(*buckets.last().unwrap(), count, "+Inf equals _count");
                assert!(count >= previous, "{count} after {previous}");
                previous = count;
            }
            done.store(true, Ordering::Relaxed);
        });
        assert!(
            metrics.durations[0].buckets[DURATION_BUCKETS.len()].load(Ordering::Relaxed) > 0,
            "observations above 60 s were made"
        );
    }
}
