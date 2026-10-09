use axum::{body::Body, http::Request, http::StatusCode};
use hpds_rs::{probe_health, router};
use tower::ServiceExt;

#[tokio::test]
async fn health_is_503_without_index_and_200_with_it() {
    let dir = tempfile::tempdir().unwrap();
    let app = router(dir.path().to_path_buf());
    let request = || {
        Request::get("/actuator/health")
            .body(Body::empty())
            .unwrap()
    };
    let down = app.clone().oneshot(request()).await.unwrap();
    assert_eq!(down.status(), StatusCode::SERVICE_UNAVAILABLE);

    std::fs::write(dir.path().join("pheno.index"), b"").unwrap();
    let up = app.oneshot(request()).await.unwrap();
    assert_eq!(up.status(), StatusCode::OK);
}

#[tokio::test]
async fn probe_follows_server_health() {
    let dir = tempfile::tempdir().unwrap();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(axum::serve(listener, router(dir.path().to_path_buf())).into_future());

    assert!(
        !tokio::task::spawn_blocking(move || probe_health(port))
            .await
            .unwrap()
    );
    std::fs::write(dir.path().join("pheno.index"), b"").unwrap();
    assert!(
        tokio::task::spawn_blocking(move || probe_health(port))
            .await
            .unwrap()
    );
}

#[test]
fn probe_is_false_when_nothing_listens() {
    let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    drop(listener);
    assert!(!probe_health(port));
}
