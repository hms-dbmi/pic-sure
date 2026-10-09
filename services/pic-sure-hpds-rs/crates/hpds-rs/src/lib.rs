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
//! `GET /actuator/health` is at the root. Every request is logged with its method, path,
//! status and latency, never its body.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::time::{Duration, Instant};

use axum::extract::{DefaultBodyLimit, Request, State};
use axum::http::StatusCode;
use axum::middleware::{self, Next};
use axum::response::Response;
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Serialize;

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

/// Builds the HTTP router over `state`, with a 2 MiB request body limit and the access log.
pub fn router(state: AppState) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .nest("/PIC-SURE/v3", api())
        .nest("/PIC-SURE", api())
        .layer(DefaultBodyLimit::max(config::MAX_BODY_BYTES))
        .layer(middleware::from_fn(access_log))
        .with_state(state)
}

/// Drops expired async results every `period` until the runtime shuts down.
pub fn spawn_result_reaper(state: AppState, period: Duration) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut ticks = tokio::time::interval(period);
        loop {
            ticks.tick().await;
            state.jobs().sweep();
        }
    })
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
