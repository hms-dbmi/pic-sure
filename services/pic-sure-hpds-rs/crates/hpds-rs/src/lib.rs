//! HTTP server in front of the `hpds-query` engine, serving the Java HPDS v3 contract of the
//! consent-partitioned (harmonization) service.
//!
//! Every route is mounted under `/PIC-SURE/v3`, where Java serves it, and under the
//! unversioned `/PIC-SURE`, which Java no longer serves but the harmonization query service
//! still calls for search:
//!
//! | Route | Handler |
//! |---|---|
//! | `POST /query/sync` | [`sync::query_sync`] |
//! | `POST /query` | [`jobs::submit`] |
//! | `POST /query/{id}/status` | [`jobs::status`] |
//! | `POST /query/{id}/result` | [`jobs::result`] |
//! | `POST /query/{id}/signed-url` | [`jobs::signed_url`] |
//! | `POST /query/format` | 400, not supported |
//! | `POST /write/{dataType}` | 400, not supported |
//! | `POST /search` | [`search::search`] |
//! | `GET /search/values/` (and without the slash) | [`search::search_values`] |
//!
//! `GET /actuator/health` is at the root: 200 `{"status":"UP"}` when the merged dictionary of
//! the partitions holds a concept (or, with a genomic store, the store holds an INFO column),
//! otherwise 503 `{"status":"DOWN","reason":...}` ([`AppState::down_reason`]), a genomic
//! store configured but not served or changed on disk included. With
//! `HPDS_METRICS=on`, `GET /metrics` is at the root too ([`metrics::metrics`]); otherwise
//! it is a 404 like any unknown path. Every
//! request is logged at info under the `hpds_rs::access` target with its method, path, status
//! and latency, never its body.
//!
//! Client errors and server errors are `text/plain` messages ([`ApiError`]). A request body
//! that is not fully received within 30 seconds of the request starting is answered 408, however
//! steadily it trickles, and so is a request whose headers take more than 10 seconds to arrive
//! ([`serve`]). Open connections are capped ([`ServerTimeouts::max_connections`]); a connection
//! past the cap is answered 503 and closed.

use std::future::Future;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, Instant};

use axum::body::Body;
use axum::extract::{DefaultBodyLimit, Request, State};
use axum::http::StatusCode;
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post};
use axum::{Json, Router};
use http_body_util::{BodyExt, LengthLimitError, Limited};
use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use hyper_util::server::conn::auto;
use hyper_util::server::graceful::GracefulShutdown;
use hyper_util::service::TowerToHyperService;
use serde::Serialize;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::net::TcpStream as AsyncTcpStream;
use tokio::sync::{Semaphore, TryAcquireError};

pub mod config;
pub mod encoding;
pub mod envelope;
pub mod error;
pub mod genomic;
pub mod jobs;
pub mod metrics;
pub mod search;
pub mod state;
pub mod sync;
pub mod write_stall;

pub use write_stall::connections_write_stalled;

pub use config::{Config, DEFAULT_PORT, port_from_env};
pub use error::ApiError;
pub use state::AppState;

/// How long a request body may take to arrive in full, measured from the start of the request.
/// A client that trickles bytes still hits it.
pub const BODY_READ_TIMEOUT: Duration = Duration::from_secs(30);

/// Timeouts of the HTTP listener.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServerTimeouts {
    /// How long a connection may take to deliver the headers of a request.
    pub header_read: Duration,
    /// How long open connections may take to finish after shutdown starts.
    pub shutdown_grace: Duration,
    /// Most connections served at once. A connection accepted while the cap is reached is
    /// answered `503 Service Unavailable` (`text/plain`) and closed, so clients and the
    /// container health probe fail fast instead of waiting in the kernel backlog. A connection
    /// that sits idle between requests is closed by `header_read`, since hyper starts the header
    /// timer when it waits for the next request on a kept-alive connection.
    pub max_connections: usize,
    /// How long a write to a connection may stay pending, the client making no room in its
    /// socket buffer, before the connection is closed ([`write_stall::WriteStall`]).
    pub write_stall: Duration,
}

/// Default for [`ServerTimeouts::max_connections`].
pub const DEFAULT_MAX_CONNECTIONS: usize = 512;

impl Default for ServerTimeouts {
    fn default() -> Self {
        ServerTimeouts {
            header_read: Duration::from_secs(10),
            shutdown_grace: Duration::from_secs(5),
            max_connections: DEFAULT_MAX_CONNECTIONS,
            write_stall: Duration::from_secs(config::DEFAULT_WRITE_STALL_SECONDS),
        }
    }
}

/// Body of the `/actuator/health` response.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// `UP` when the partitions are open and their merged dictionary holds at least one
    /// concept, `DOWN` otherwise.
    pub status: &'static str,
    /// Why the service is DOWN ([`AppState::down_reason`]); left out when it is UP. Never
    /// carries a path or the open error, which go to the log.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub reason: Option<&'static str>,
    /// True while another store, of another id, has been moved into the data directory and
    /// the service goes on serving the store it opened from its open files
    /// ([`AppState::store_superseded`]). The status stays UP; a restart serves the new
    /// store. Left out when false.
    #[serde(rename = "storeSuperseded", skip_serializing_if = "std::ops::Not::not")]
    pub store_superseded: bool,
    /// True while another genomic store has been moved into the genomic directory and the
    /// service goes on serving the one it opened ([`AppState::genomic_store_superseded`]).
    /// Left out when false.
    #[serde(
        rename = "genomicStoreSuperseded",
        skip_serializing_if = "std::ops::Not::not"
    )]
    pub genomic_store_superseded: bool,
}

async fn health(State(state): State<AppState>) -> (StatusCode, Json<Health>) {
    match state.down_reason() {
        None => (
            StatusCode::OK,
            Json(Health {
                status: "UP",
                reason: None,
                store_superseded: state.store_superseded(),
                genomic_store_superseded: state.genomic_store_superseded(),
            }),
        ),
        Some(reason) => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Health {
                status: "DOWN",
                reason: Some(reason),
                store_superseded: state.store_superseded(),
                genomic_store_superseded: state.genomic_store_superseded(),
            }),
        ),
    }
}

async fn query_format() -> ApiError {
    ApiError::bad_request("query formatting is not supported by this HPDS")
}

async fn write_result() -> ApiError {
    ApiError::bad_request("writing results to file sharing is not supported by this HPDS")
}

async fn access_log(request: Request, next: Next) -> Response {
    let method = request.method().clone();
    let path = request.uri().path().to_owned();
    let started = Instant::now();
    let response = next.run(request).await;
    tracing::info!(
        target: "hpds_rs::access",
        %method,
        %path,
        status = response.status().as_u16(),
        latency_ms = started.elapsed().as_secs_f64() * 1000.0,
        "request"
    );
    response
}

async fn bound_body_read(
    State(deadline): State<Duration>,
    request: Request,
    next: Next,
) -> Response {
    let (parts, body) = request.into_parts();
    let limited = Limited::new(body, config::MAX_BODY_BYTES);
    let collected = match tokio::time::timeout(deadline, limited.collect()).await {
        Ok(Ok(collected)) => collected,
        Ok(Err(error)) if error.downcast_ref::<LengthLimitError>().is_some() => {
            return ApiError::payload_too_large("request body is too large").into_response();
        }
        Ok(Err(_)) => {
            return ApiError::bad_request("request body could not be read").into_response();
        }
        Err(_) => {
            return ApiError::request_timeout("request body was not received in time")
                .into_response();
        }
    };
    next.run(Request::from_parts(parts, Body::from(collected.to_bytes())))
        .await
}

fn api() -> Router<AppState> {
    Router::new()
        .route("/query/sync", post(sync::query_sync))
        .route("/query", post(jobs::submit))
        .route("/query/format", post(query_format))
        .route("/query/{id}/status", post(jobs::status))
        .route("/query/{id}/result", post(jobs::result))
        .route("/query/{id}/signed-url", post(jobs::signed_url))
        .route("/write/{data_type}", post(write_result))
        .route("/search", post(search::search))
        .route("/search/values/", get(search::search_values))
        .route("/search/values", get(search::search_values))
}

/// Builds the HTTP router over `state`, with a 2 MiB request body limit, a
/// [`BODY_READ_TIMEOUT`], the access log and, when `state` keeps metrics, `GET /metrics` and
/// the request counters.
pub fn router(state: AppState) -> Router {
    router_with_body_timeout(state, BODY_READ_TIMEOUT)
}

/// [`router`] with `body_timeout` as the longest a request body may take to arrive in full.
/// A body over 2 MiB is answered 413 and a body that misses the deadline 408.
pub fn router_with_body_timeout(state: AppState, body_timeout: Duration) -> Router {
    let mut app = Router::new()
        .route("/actuator/health", get(health))
        .nest("/PIC-SURE/v3", api())
        .nest("/PIC-SURE", api());
    let counted = state.metrics().is_some();
    if counted {
        app = app
            .route("/metrics", get(metrics::metrics))
            .route_layer(middleware::from_fn(metrics::tag_route));
    }
    app = app
        .layer(DefaultBodyLimit::max(config::MAX_BODY_BYTES))
        .layer(middleware::from_fn_with_state(
            body_timeout,
            bound_body_read,
        ))
        .layer(middleware::from_fn(access_log));
    if counted {
        app = app.layer(middleware::from_fn_with_state(
            state.clone(),
            metrics::record,
        ));
    }
    app.with_state(state)
}

static CONNECTIONS_REFUSED: AtomicU64 = AtomicU64::new(0);

/// Connections this process refused because every connection slot was taken ([`serve`]).
/// Each is answered 503 when one of the refusal slots is free, and otherwise closed at once,
/// but counted either way.
pub fn connections_refused() -> u64 {
    CONNECTIONS_REFUSED.load(Ordering::Relaxed)
}

/// Drops expired async results every `period` until the runtime shuts down.
pub fn spawn_result_reaper(state: AppState, period: Duration) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut ticks = tokio::time::interval(period);
        loop {
            ticks.tick().await;
            let state = state.clone();
            if let Err(error) = tokio::task::spawn_blocking(move || state.results().sweep()).await {
                tracing::error!(%error, "result sweep failed");
            }
        }
    })
}

/// Log target of the result cache's counters.
pub const RESULT_CACHE_LOG_TARGET: &str = "hpds_rs::result_cache";

/// Logs the result cache's counters at info under [`RESULT_CACHE_LOG_TARGET`]: hits, misses,
/// the hit rate in percent, entries, bytes held and the byte bound. Logs nothing when the
/// cache is off.
pub fn log_result_cache_stats(engine: &hpds_query::Engine) {
    let Some(stats) = engine.result_cache_stats() else {
        return;
    };
    let lookups = stats.hits + stats.misses;
    let hit_rate = if lookups == 0 {
        0.0
    } else {
        stats.hits as f64 * 100.0 / lookups as f64
    };
    tracing::info!(
        target: RESULT_CACHE_LOG_TARGET,
        hits = stats.hits,
        misses = stats.misses,
        hit_rate = format!("{hit_rate:.1}"),
        entries = stats.entries,
        bytes = stats.bytes,
        capacity = stats.capacity,
        "result cache"
    );
}

/// Logs at info what the block cache holds: decoded blocks, the partition views built for
/// narrow selections, and the bytes each takes. Walks the cache, so it is meant for
/// shutdown rather than a hot path.
pub fn log_block_cache_stats(engine: &hpds_query::Engine) {
    let partitions = engine.partitions();
    let (views, view_bytes) = partitions.cached_views();
    tracing::info!(
        blocks = partitions.cached_block_count(),
        bytes = partitions.cached_bytes(),
        views,
        view_bytes,
        "block cache"
    );
}

/// Logs the result cache's counters ([`log_result_cache_stats`]) every `period` in which a
/// lookup happened, until the runtime shuts down. Returns `None` and starts nothing when
/// the cache is off.
pub fn spawn_result_cache_reporter(
    engine: Arc<hpds_query::Engine>,
    period: Duration,
) -> Option<tokio::task::JoinHandle<()>> {
    engine.result_cache_stats()?;
    Some(tokio::spawn(async move {
        let mut ticks = tokio::time::interval(period);
        ticks.tick().await;
        let mut logged_lookups = 0;
        loop {
            ticks.tick().await;
            let lookups = engine
                .result_cache_stats()
                .map_or(0, |stats| stats.hits + stats.misses);
            if lookups != logged_lookups {
                logged_lookups = lookups;
                log_result_cache_stats(&engine);
            }
        }
    }))
}

/// Serves `app` on `listener` until `shutdown` completes, then stops accepting, lets open
/// connections finish for at most `timeouts.shutdown_grace`, and returns.
///
/// A connection that has not delivered a request's headers within `timeouts.header_read` is
/// closed, and so is one whose client makes no room in its socket buffer while a write to it
/// stays pending for `timeouts.write_stall` ([`write_stall::WriteStall`]). Every accepted
/// connection has Nagle's algorithm turned off ([`disable_nagle`]).
/// At most `timeouts.max_connections` connections are served at once; a connection
/// accepted past that cap is answered 503 and closed (see [`OVER_CAPACITY_RESPONSE`]). HTTP/1 and
/// HTTP/2 are both served.
///
/// # Errors
/// Never fails once the listener is bound; accept errors are logged and retried.
pub async fn serve(
    listener: TcpListener,
    app: Router,
    shutdown: impl Future<Output = ()>,
    timeouts: ServerTimeouts,
) -> io::Result<()> {
    let mut builder = auto::Builder::new(TokioExecutor::new());
    builder
        .http1()
        .timer(TokioTimer::new())
        .header_read_timeout(timeouts.header_read);
    builder.http2().timer(TokioTimer::new());
    let graceful = GracefulShutdown::new();
    let slots = Arc::new(Semaphore::new(timeouts.max_connections.max(1)));
    let refusals = Arc::new(Semaphore::new(timeouts.max_connections.max(1)));
    tokio::pin!(shutdown);
    loop {
        let stream = tokio::select! {
            accepted = listener.accept() => match accepted {
                Ok((stream, _peer)) => stream,
                Err(error) => {
                    tracing::warn!(%error, "accept failed");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    continue;
                }
            },
            () = &mut shutdown => break,
        };
        disable_nagle(&stream);
        let permit = match Arc::clone(&slots).try_acquire_owned() {
            Ok(permit) => permit,
            Err(TryAcquireError::NoPermits) => {
                refuse_over_capacity(stream, &refusals);
                continue;
            }
            Err(TryAcquireError::Closed) => break,
        };
        let connection = builder.serve_connection_with_upgrades(
            write_stall::WriteStall::new(TokioIo::new(stream), timeouts.write_stall),
            TowerToHyperService::new(app.clone()),
        );
        let watched = graceful.watch(connection.into_owned());
        tokio::spawn(async move {
            let _slot = permit;
            if let Err(error) = watched.await {
                tracing::debug!(%error, "connection ended with an error");
            }
        });
    }
    drop(listener);
    tokio::select! {
        () = graceful.shutdown() => {}
        () = tokio::time::sleep(timeouts.shutdown_grace) => {
            tracing::warn!("open connections did not finish within the shutdown grace period");
        }
    }
    Ok(())
}

/// Turns off Nagle's algorithm (sets `TCP_NODELAY`) on an accepted connection.
///
/// A response leaves in several small writes (headers, then body frames). With Nagle on, each
/// write after the first waits until the client acknowledges the previous one, and a client
/// that delays its ACKs holds every streamed response for about 40 ms. A failure is logged and
/// the connection is served anyway.
fn disable_nagle(stream: &AsyncTcpStream) {
    if let Err(error) = stream.set_nodelay(true) {
        tracing::debug!(%error, "could not set TCP_NODELAY on an accepted connection");
    }
}

/// Raw HTTP/1.1 response written to a connection accepted while every connection slot is taken.
pub const OVER_CAPACITY_RESPONSE: &[u8] = b"HTTP/1.1 503 Service Unavailable\r\n\
content-type: text/plain; charset=utf-8\r\n\
content-length: 35\r\n\
retry-after: 1\r\n\
connection: close\r\n\
\r\n\
server is at its connection limit\r\n";

/// How long a refused connection may take to receive [`OVER_CAPACITY_RESPONSE`] and close.
const REFUSAL_DEADLINE: Duration = Duration::from_secs(1);

/// Answers `stream` with [`OVER_CAPACITY_RESPONSE`] on a task of its own and closes it.
///
/// At most as many refusals as there are connection slots run at once, each bounded by
/// [`REFUSAL_DEADLINE`]; past that the stream is dropped, which closes it at once. The request is
/// never read or parsed. After the response the write side is shut down and pending input is
/// drained briefly, so the client reads the 503 instead of a connection reset.
fn refuse_over_capacity(stream: AsyncTcpStream, refusals: &Arc<Semaphore>) {
    CONNECTIONS_REFUSED.fetch_add(1, Ordering::Relaxed);
    let Ok(permit) = Arc::clone(refusals).try_acquire_owned() else {
        return;
    };
    tracing::warn!("connection limit reached; answering 503");
    tokio::spawn(async move {
        let _permit = permit;
        let _ = tokio::time::timeout(REFUSAL_DEADLINE, write_refusal(stream)).await;
    });
}

async fn write_refusal(mut stream: AsyncTcpStream) -> io::Result<()> {
    stream.write_all(OVER_CAPACITY_RESPONSE).await?;
    stream.shutdown().await?;
    let mut sink = [0u8; 1024];
    while stream.read(&mut sink).await? > 0 {}
    Ok(())
}

/// Probes `/actuator/health` on the loopback interface at `port`.
///
/// Sends a minimal HTTP/1.0 request over a plain TCP stream with a 2 second timeout. Returns
/// true only when the response status is 200 and the body reports `UP`.
pub fn probe_health(port: u16) -> bool {
    let timeout = Duration::from_secs(2);
    let addr = SocketAddr::from(([127, 0, 0, 1], port));
    let Ok(mut stream) = TcpStream::connect_timeout(&addr, timeout) else {
        return false;
    };
    if stream.set_read_timeout(Some(timeout)).is_err()
        || stream.set_write_timeout(Some(timeout)).is_err()
    {
        return false;
    }
    let request = "GET /actuator/health HTTP/1.0\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n";
    if stream.write_all(request.as_bytes()).is_err() {
        return false;
    }
    let mut response = String::new();
    if stream.read_to_string(&mut response).is_err() {
        return false;
    }
    let ok = response
        .lines()
        .next()
        .is_some_and(|line| line.split_whitespace().nth(1) == Some("200"));
    ok && response.contains("\"UP\"")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn accepted_connections_have_nagle_turned_off() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let client = AsyncTcpStream::connect(address).await.unwrap();
        let (stream, _) = listener.accept().await.unwrap();
        assert!(!stream.nodelay().unwrap());
        disable_nagle(&stream);
        assert!(stream.nodelay().unwrap());
        drop(client);
    }
}
