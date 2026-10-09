mod common;

use axum::http::StatusCode;
use common::{config, fixture, get};
use hpds_rs::{AppState, probe_health, router};

#[tokio::test]
async fn health_is_down_without_a_store() {
    let dir = tempfile::tempdir().unwrap();
    let app = router(AppState::new(None, &config(dir.path())).unwrap());
    let reply = get(&app, "/actuator/health").await;
    assert_eq!(reply.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(reply.json()["status"], "DOWN");
    let query = common::post(
        &app,
        "/PIC-SURE/v3/query/sync",
        common::envelope(serde_json::json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(query.status, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn health_is_up_with_a_store() {
    let fixture = fixture();
    let reply = get(&fixture.app, "/actuator/health").await;
    assert_eq!(reply.status, StatusCode::OK);
    assert_eq!(reply.body, r#"{"status":"UP"}"#);
}

#[tokio::test]
async fn probe_follows_server_health() {
    let fixture = fixture();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    tokio::spawn(axum::serve(listener, fixture.app.clone()).into_future());
    assert!(
        tokio::task::spawn_blocking(move || probe_health(port))
            .await
            .unwrap()
    );

    let dir = tempfile::tempdir().unwrap();
    let down = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let down_port = down.local_addr().unwrap().port();
    let app = router(AppState::new(None, &config(dir.path())).unwrap());
    tokio::spawn(axum::serve(down, app).into_future());
    assert!(
        !tokio::task::spawn_blocking(move || probe_health(down_port))
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
