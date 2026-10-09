//! HTTP server in front of the `hpds-query` engine, serving the Java HPDS v3 contract.
//!
//! Every route is mounted under both `/PIC-SURE/v3` and `/PIC-SURE`, since the query service
//! calls search on the unversioned path:
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
//! `GET /actuator/health` is at the root. Every request is logged at info under the
//! `hpds_rs::access` target with its method, path, status and latency, never its body.
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
pub mod envelope;
pub mod error;
pub mod jobs;
pub mod search;
pub mod state;
pub mod sync;

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
}

/// Default for [`ServerTimeouts::max_connections`].
pub const DEFAULT_MAX_CONNECTIONS: usize = 512;

impl Default for ServerTimeouts {
    fn default() -> Self {
        ServerTimeouts {
            header_read: Duration::from_secs(10),
            shutdown_grace: Duration::from_secs(5),
            max_connections: DEFAULT_MAX_CONNECTIONS,
        }
    }
}

/// Body of the `/actuator/health` response.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// `UP` when the store is open and holds at least one concept, `DOWN` otherwise.
    pub status: &'static str,
}

async fn health(State(state): State<AppState>) -> (StatusCode, Json<Health>) {
    if state.is_healthy() {
        (StatusCode::OK, Json(Health { status: "UP" }))
    } else {
        (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Health { status: "DOWN" }),
        )
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
/// [`BODY_READ_TIMEOUT`] and the access log.
pub fn router(state: AppState) -> Router {
    router_with_body_timeout(state, BODY_READ_TIMEOUT)
}

/// [`router`] with `body_timeout` as the longest a request body may take to arrive in full.
/// A body over 2 MiB is answered 413 and a body that misses the deadline 408.
pub fn router_with_body_timeout(state: AppState, body_timeout: Duration) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .nest("/PIC-SURE/v3", api())
        .nest("/PIC-SURE", api())
        .layer(DefaultBodyLimit::max(config::MAX_BODY_BYTES))
        .layer(middleware::from_fn_with_state(
            body_timeout,
            bound_body_read,
        ))
        .layer(middleware::from_fn(access_log))
        .with_state(state)
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

/// Serves `app` on `listener` until `shutdown` completes, then stops accepting, lets open
/// connections finish for at most `timeouts.shutdown_grace`, and returns.
///
/// A connection that has not delivered a request's headers within `timeouts.header_read` is
/// closed. Every accepted connection has Nagle's algorithm turned off ([`disable_nagle`]).
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
            TokioIo::new(stream),
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
