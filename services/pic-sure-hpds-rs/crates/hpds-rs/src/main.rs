use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use hpds_query::Engine;
use hpds_rs::{AppState, Config, port_from_env, probe_health, router, spawn_result_reaper};
use tracing_subscriber::EnvFilter;

const REAPER_PERIOD: Duration = Duration::from_secs(60);

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

fn main() -> ExitCode {
    if std::env::args().nth(1).as_deref() == Some("healthcheck") {
        return match port_from_env() {
            Ok(port) if probe_health(port) => ExitCode::SUCCESS,
            Ok(_) => ExitCode::FAILURE,
            Err(message) => {
                eprintln!("{message}");
                ExitCode::FAILURE
            }
        };
    }
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    let config = match Config::from_env() {
        Ok(config) => config,
        Err(message) => {
            tracing::error!(%message, "invalid configuration");
            return ExitCode::FAILURE;
        }
    };
    for warning in &config.warnings {
        tracing::warn!("{warning}");
    }
    let runtime = match tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .max_blocking_threads(config.blocking_threads)
        .build()
    {
        Ok(runtime) => runtime,
        Err(error) => {
            tracing::error!(%error, "cannot start the runtime");
            return ExitCode::FAILURE;
        }
    };
    runtime.block_on(serve(config))
}

async fn serve(config: Config) -> ExitCode {
    let engine = match Engine::open(&config.data_dir, &config.store) {
        Ok(engine) => {
            tracing::info!(store = ?engine.store(), "store opened");
            Some(Arc::new(engine))
        }
        Err(error) => {
            tracing::error!(%error, "cannot open the store; serving health DOWN");
            None
        }
    };
    let state = match AppState::new(engine, &config) {
        Ok(state) => state,
        Err(error) => {
            tracing::error!(%error, dir = %config.results_dir.display(), "cannot prepare the results directory");
            return ExitCode::FAILURE;
        }
    };
    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", config.port)).await {
        Ok(listener) => listener,
        Err(error) => {
            tracing::error!(%error, port = config.port, "failed to bind");
            return ExitCode::FAILURE;
        }
    };
    tracing::info!(port = config.port, data_dir = %config.data_dir.display(), "hpds-rs listening");
    spawn_result_reaper(state.clone(), REAPER_PERIOD);
    let served = axum::serve(listener, router(state))
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
