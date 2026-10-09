use std::path::PathBuf;
use std::process::ExitCode;

use hpds_query::DEFAULT_DATA_DIRECTORY;
use hpds_rs::{port_from_env, probe_health, router};
use tracing_subscriber::EnvFilter;

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut signal) => {
                signal.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    tokio::select! {
        _ = ctrl_c => {}
        _ = terminate => {}
    }
}

#[tokio::main]
async fn main() -> ExitCode {
    let port = match port_from_env() {
        Ok(port) => port,
        Err(message) => {
            eprintln!("{message}");
            return ExitCode::FAILURE;
        }
    };
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        let healthy = tokio::task::spawn_blocking(move || probe_health(port))
            .await
            .unwrap_or(false);
        return if healthy {
            ExitCode::SUCCESS
        } else {
            ExitCode::FAILURE
        };
    }

    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let data_dir = PathBuf::from(
        std::env::var("HPDS_DATA_DIRECTORY").unwrap_or_else(|_| DEFAULT_DATA_DIRECTORY.to_string()),
    );
    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
        Ok(listener) => listener,
        Err(error) => {
            tracing::error!(%error, port, "failed to bind");
            return ExitCode::FAILURE;
        }
    };
    tracing::info!(port, data_dir = %data_dir.display(), "hpds-rs listening");
    let served = axum::serve(listener, router(data_dir))
        .with_graceful_shutdown(shutdown_signal())
        .await;
    match served {
        Ok(()) => {
            tracing::info!("hpds-rs stopped");
            ExitCode::SUCCESS
        }
        Err(error) => {
            tracing::error!(%error, "server error");
            ExitCode::FAILURE
        }
    }
}
