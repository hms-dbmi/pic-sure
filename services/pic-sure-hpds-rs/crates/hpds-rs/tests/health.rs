mod common;

use std::sync::Arc;

use axum::http::StatusCode;
use common::{config, fixture, get};
use hpds_query::Engine;
use hpds_rs::state::NOT_OPENED_REASON;
use hpds_rs::{AppState, probe_health, router};

#[tokio::test]
async fn health_is_down_without_a_store() {
    let dir = tempfile::tempdir().unwrap();
    let app = router(AppState::new(None, &config(dir.path())).unwrap());
    let reply = get(&app, "/actuator/health").await;
    assert_eq!(reply.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(reply.json()["status"], "DOWN");
    assert_eq!(reply.json()["reason"], NOT_OPENED_REASON);
    let query = common::post(
        &app,
        "/PIC-SURE/v3/query/sync",
        common::envelope(serde_json::json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(query.status, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn a_data_directory_with_no_partitions_is_refused_and_serves_down() {
    let dir = tempfile::tempdir().unwrap();
    let data = dir.path().join("data");
    std::fs::create_dir_all(data.join("lost+found")).unwrap();
    std::fs::write(data.join("encryption_key"), common::KEY).unwrap();
    let settings = config(dir.path());
    let error = Engine::open(&data, &settings.store).unwrap_err();
    assert!(error.to_string().contains("pheno.index"), "{error}");
    let app = router(AppState::new(None, &settings).unwrap());
    let reply = get(&app, "/actuator/health").await;
    assert_eq!(reply.status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(
        reply.body,
        format!(r#"{{"status":"DOWN","reason":"{NOT_OPENED_REASON}"}}"#)
    );
}

#[tokio::test]
async fn an_unpartitioned_store_is_refused_while_consents_are_required() {
    let fixture = common::fixture();
    let unpartitioned = fixture.store().join("partition1");
    std::fs::copy(
        fixture.store().join("encryption_key"),
        unpartitioned.join("encryption_key"),
    )
    .unwrap();
    let mut settings = config(fixture.dir.path());
    settings.results_dir = fixture.dir.path().join("unpartitioned-results");
    settings.store.require_consents = true;
    let error = Engine::open(&unpartitioned, &settings.store).unwrap_err();
    assert!(
        error
            .to_string()
            .contains("hpds.requireAuthorizationFilter"),
        "{error}"
    );
    settings.store.require_consents = false;
    let engine = Engine::open(&unpartitioned, &settings.store).unwrap();
    let state = AppState::new(Some(Arc::new(engine)), &settings).unwrap();
    assert!(state.is_healthy());
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
