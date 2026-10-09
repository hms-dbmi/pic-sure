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
//! that stalls for 30 seconds is cut off, and so is a request whose headers take more than
//! 10 seconds to arrive ([`serve`]).

use std::future::Future;
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use axum::extract::{DefaultBodyLimit, Request, State};
use axum::http::StatusCode;
use axum::middleware::{self, Next};
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use hyper_util::server::conn::auto;
use hyper_util::server::graceful::GracefulShutdown;
use hyper_util::service::TowerToHyperService;
use serde::Serialize;
use tokio::net::TcpListener;
use tower_http::timeout::RequestBodyTimeoutLayer;

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

/// How long a request body may go without delivering data before it is cut off.
pub const BODY_READ_TIMEOUT: Duration = Duration::from_secs(30);

/// Timeouts of the HTTP listener.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ServerTimeouts {
    /// How long a connection may take to deliver the headers of a request.
    pub header_read: Duration,
    /// How long open connections may take to finish after shutdown starts.
    pub shutdown_grace: Duration,
}

impl Default for ServerTimeouts {
    fn default() -> Self {
        ServerTimeouts {
            header_read: Duration::from_secs(10),
            shutdown_grace: Duration::from_secs(5),
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

/// [`router`] with `body_timeout` as the longest a request body may stall.
pub fn router_with_body_timeout(state: AppState, body_timeout: Duration) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .nest("/PIC-SURE/v3", api())
        .nest("/PIC-SURE", api())
        .layer(DefaultBodyLimit::max(config::MAX_BODY_BYTES))
        .layer(RequestBodyTimeoutLayer::new(body_timeout))
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
/// closed. HTTP/1 and HTTP/2 are both served.
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
        let connection = builder.serve_connection_with_upgrades(
            TokioIo::new(stream),
            TowerToHyperService::new(app.clone()),
        );
        let watched = graceful.watch(connection.into_owned());
        tokio::spawn(async move {
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
