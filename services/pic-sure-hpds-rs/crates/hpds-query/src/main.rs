use std::path::PathBuf;

use hpds_query::{DEFAULT_DATA_DIRECTORY, router};

#[tokio::main]
async fn main() -> std::io::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(tracing_subscriber::EnvFilter::from_default_env())
        .init();
    let data_dir = PathBuf::from(
        std::env::var("HPDS_DATA_DIRECTORY").unwrap_or_else(|_| DEFAULT_DATA_DIRECTORY.to_string()),
    );
    let port = std::env::var("PORT").unwrap_or_else(|_| "8080".to_string());
    let listener = tokio::net::TcpListener::bind(format!("0.0.0.0:{port}")).await?;
    tracing::info!(%port, data_dir = %data_dir.display(), "hpds-rs listening");
    axum::serve(listener, router(data_dir)).await
}
