//! HTTP server in front of the `hpds-query` engine.

use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::path::PathBuf;
use std::time::Duration;

use axum::{Json, Router, extract::State, http::StatusCode, routing::get};
use hpds_query::{HealthState, health_for};
use serde::Serialize;

/// Port used when `SERVER_PORT` is not set.
pub const DEFAULT_PORT: u16 = 8080;

/// Body of the `/actuator/health` response.
#[derive(Debug, Serialize, PartialEq, Eq)]
pub struct Health {
    /// `UP` when the store is loaded, `DOWN` otherwise.
    pub status: &'static str,
}

async fn health(State(data_dir): State<PathBuf>) -> (StatusCode, Json<Health>) {
    match health_for(&data_dir) {
        HealthState::Up => (StatusCode::OK, Json(Health { status: "UP" })),
        HealthState::Down => (
            StatusCode::SERVICE_UNAVAILABLE,
            Json(Health { status: "DOWN" }),
        ),
    }
}

/// Builds the HTTP router serving the given data directory.
pub fn router(data_dir: PathBuf) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .with_state(data_dir)
}

/// Reads the listen port from `SERVER_PORT`, falling back to [`DEFAULT_PORT`].
///
/// Returns an error message when the variable is set but is not a valid port.
pub fn port_from_env() -> Result<u16, String> {
    match std::env::var("SERVER_PORT") {
        Ok(value) => value
            .trim()
            .parse()
            .map_err(|_| format!("SERVER_PORT is not a valid port: {value}")),
        Err(_) => Ok(DEFAULT_PORT),
    }
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
