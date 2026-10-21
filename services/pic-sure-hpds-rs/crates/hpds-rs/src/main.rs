use std::process::ExitCode;
use std::sync::Arc;
use std::time::Duration;

use hpds_query::Engine;
use hpds_rs::genomic::{GenomicStatus, attach_genomic};
use hpds_rs::{
    AppState, Config, LogFormat, ServerTimeouts, format_layer, install_panic_hook,
    log_block_cache_stats, log_result_cache_stats, port_from_env, probe_health, router, serve,
    spawn_result_cache_reporter, spawn_result_reaper,
};
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{RollingFileAppender, Rotation};
use tracing_subscriber::EnvFilter;
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;

/// The process allocator: jemalloc. glibc's malloc keeps most of what a broad query frees
/// in its per-thread arenas, so resident memory stays gigabytes above live memory after the
/// query ends; jemalloc returns freed pages to the system on its own schedule
/// ([`MALLOC_CONF`]) without the throughput loss of capping glibc's arenas.
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

/// jemalloc's options, read at its first allocation: a background thread purges freed
/// pages, and dirty and muzzy pages are returned to the system after one second instead
/// of ten. The environment variable `_RJEM_MALLOC_CONF` is applied after these and
/// overrides them.
#[unsafe(export_name = "_rjem_malloc_conf")]
pub static MALLOC_CONF: Option<&'static u8> =
    Some(&b"background_thread:true,dirty_decay_ms:1000,muzzy_decay_ms:1000\0"[0]);

const REAPER_PERIOD: Duration = Duration::from_secs(60);
const RESULT_CACHE_REPORT_PERIOD: Duration = Duration::from_secs(60);
const RUNTIME_SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(5);

/// Starts logging to a non-blocking stdout writer and, when `directory` is given, to a daily
/// rotated `hpds-rs.<date>.log` file there, both in `format`. The returned guards flush the
/// writers when dropped, so they must live until the process ends. The second value is a
/// message when the log file could not be opened; logging then goes to stdout only and the
/// caller refuses to start.
fn init_logging(
    format: &LogFormat,
    directory: Option<&std::path::Path>,
) -> (Vec<WorkerGuard>, Option<String>) {
    let filter = EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info"));
    let (stdout, stdout_guard) = tracing_appender::non_blocking(std::io::stdout());
    let mut guards = vec![stdout_guard];
    let mut file_problem = None;
    let file_layer = directory.and_then(|dir| {
        let appender = RollingFileAppender::builder()
            .rotation(Rotation::DAILY)
            .filename_prefix("hpds-rs")
            .filename_suffix("log")
            .build(dir);
        match appender {
            Ok(appender) => {
                let (writer, guard) = tracing_appender::non_blocking(appender);
                guards.push(guard);
                Some(format_layer(format, writer, Some(false)))
            }
            Err(error) => {
                file_problem = Some(format!(
                    "cannot write the log file in {}: {error}",
                    dir.display()
                ));
                None
            }
        }
    });
    tracing_subscriber::registry()
        .with(filter)
        .with(format_layer(format, stdout, None))
        .with(file_layer)
        .init();
    (guards, file_problem)
}

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
    let config = Config::from_env();
    let log_format = config
        .as_ref()
        .map(|config| config.log_format.clone())
        .unwrap_or_default();
    let (_log_guards, log_file_problem) = init_logging(
        &log_format,
        config
            .as_ref()
            .ok()
            .and_then(|config| config.log_directory.as_deref()),
    );
    install_panic_hook();
    if let Some(problem) = log_file_problem {
        tracing::error!("{problem}");
        return ExitCode::FAILURE;
    }
    let config = match config {
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
    let code = runtime.block_on(run(config));
    runtime.shutdown_timeout(RUNTIME_SHUTDOWN_TIMEOUT);
    code
}

async fn run(config: Config) -> ExitCode {
    tracing::info!(
        consent_contract = %config.partitions.contract,
        require_authorization_filter = config.partitions.require_consents,
        "serving the {} consent contract",
        config.partitions.contract
    );
    let mut genomic = GenomicStatus::Off;
    let engine = match Engine::open(&config.data_dir, &config.partitions) {
        Ok(engine) => {
            let engine = engine
                .with_export_pin_bytes(config.export_pin_bytes)
                .with_limits(config.query_limits)
                .with_result_cache_bytes(config.result_cache_bytes);
            let partitions = engine.partitions();
            tracing::info!(
                layout = ?partitions.layout(),
                partitions = ?partitions.partition_names().collect::<Vec<_>>(),
                concepts = partitions.concept_count(),
                require_consents = partitions.requires_consents(),
                overlap_patients = partitions.overlap_patients(),
                result_cache_bytes = config.result_cache_bytes,
                "store opened"
            );
            let (engine, status) = attach_genomic(engine, &config);
            genomic = status;
            Some(Arc::new(engine))
        }
        Err(error) => {
            tracing::error!(%error, "cannot open the data directory; serving health DOWN");
            None
        }
    };
    if let Some(engine) = &engine {
        spawn_result_cache_reporter(Arc::clone(engine), RESULT_CACHE_REPORT_PERIOD);
    }
    let state = match AppState::with_genomic(engine.clone(), &config, genomic) {
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
    let served = serve(
        listener,
        router(state),
        shutdown_signal(),
        ServerTimeouts {
            max_connections: config.max_connections,
            write_stall: config.write_stall,
            ..ServerTimeouts::default()
        },
    )
    .await;
    match served {
        Ok(()) => {
            if let Some(engine) = &engine {
                log_result_cache_stats(engine);
                log_block_cache_stats(engine);
            }
            tracing::info!("hpds-rs stopped");
            ExitCode::SUCCESS
        }
        Err(error) => {
            tracing::error!(%error, "server error");
            ExitCode::FAILURE
        }
    }
}
