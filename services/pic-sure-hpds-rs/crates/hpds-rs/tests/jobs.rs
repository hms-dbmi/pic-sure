mod common;

use std::os::unix::fs::PermissionsExt;
use std::sync::Arc;
use std::time::Duration;

use axum::http::StatusCode;
use common::{
    AGE, POPULATION, SEX, consents, envelope, fixture, fixture_with, post, strict_fixture, wait,
};
use hpds_query::{Query, ResultType};
use serde_json::{Value, json};

const QUERY: &str = "/PIC-SURE/v3/query";

fn finnish() -> Value {
    json!({"phenotypicFilterType": "FILTER", "conceptPath": POPULATION, "values": ["Finnish"]})
}

async fn submit(app: &axum::Router, query: Value) -> Value {
    let reply = post(app, QUERY, envelope(query)).await;
    assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
    assert_eq!(reply.content_type(), Some("application/json"));
    let positions: Vec<usize> = [
        "status",
        "resourceID",
        "resourceStatus",
        "picsureResultId",
        "resourceResultId",
        "resultMetadata",
        "sizeInBytes",
        "startTime",
        "duration",
        "expiration",
    ]
    .iter()
    .map(|key| reply.body.find(&format!("\"{key}\":")).unwrap())
    .collect();
    assert!(positions.windows(2).all(|w| w[0] < w[1]), "{}", reply.body);
    reply.json()
}

fn result_files(dir: &std::path::Path) -> usize {
    std::fs::read_dir(dir).map(|d| d.count()).unwrap_or(0)
}

#[tokio::test]
async fn patients_lifecycle() {
    let f = fixture_with(|c| c.result_memory_bytes = 0);
    let submitted = submit(
        &f.app,
        json!({"expectedResultType": "PATIENTS", "phenotypicClause": finnish(), "id": "1EFBC3DB-978F-452F-8CA3-BD4D4A207A41"}),
    )
    .await;
    assert!(submitted["resourceID"].is_null() && submitted["picsureResultId"].is_null());
    assert_eq!(
        submitted["resultMetadata"],
        json!({"picsureQueryId": "1efbc3db-978f-452f-8ca3-bd4d4a207a41"})
    );
    let id = submitted["resourceResultId"].as_str().unwrap().to_owned();

    let done = wait(&f.app, &id).await;
    assert_eq!(
        (done["status"].as_str(), done["resourceStatus"].as_str()),
        (Some("AVAILABLE"), Some("SUCCESS"))
    );

    for prefix in ["/PIC-SURE/v3", "/PIC-SURE"] {
        let result = post(&f.app, &format!("{prefix}/query/{id}/result"), "{}").await;
        assert_eq!(result.status, StatusCode::OK);
        assert_eq!(result.content_type(), Some("text/plain"));
        assert_eq!(done["sizeInBytes"].as_u64(), Some(result.body.len() as u64));
        let lines: Vec<&str> = result.body.lines().collect();
        assert_eq!(lines[0], "PATIENT_NUM");
        assert_eq!(lines.len(), 103);
        let ids: Vec<u32> = lines[1..].iter().map(|l| l.parse().unwrap()).collect();
        assert!(ids.windows(2).all(|w| w[0] < w[1]));
        assert!(result.body.ends_with('\n'));
    }

    let file = f.config.results_dir.join(format!("result-{id}.csv"));
    assert_eq!(
        std::fs::metadata(&file).unwrap().permissions().mode() & 0o777,
        0o600
    );
    assert_eq!(
        std::fs::metadata(&f.config.results_dir)
            .unwrap()
            .permissions()
            .mode()
            & 0o777,
        0o700
    );
}

#[tokio::test]
async fn dataframe_lifecycle() {
    let f = fixture();
    let unknown = r"\not\a\concept\";
    let submitted = submit(
        &f.app,
        json!({"expectedResultType": "DATAFRAME", "select": [SEX, AGE, unknown], "phenotypicClause": finnish()}),
    )
    .await;
    assert!(
        submitted["resultMetadata"]["picsureQueryId"]
            .as_str()
            .is_some()
    );
    let id = submitted["resourceResultId"].as_str().unwrap().to_owned();
    assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
    let result = post(&f.app, &format!("{QUERY}/{id}/result"), "{}").await;
    let lines: Vec<&str> = result.body.lines().collect();
    assert_eq!(lines[0], format!("patient_id,{SEX},{AGE},{unknown}"));
    assert_eq!(lines.len(), 103);
    assert!(
        lines[1..]
            .iter()
            .all(|l| l.split(',').count() == 4 && l.ends_with(','))
    );
    assert!(
        lines[1..]
            .iter()
            .all(|l| l.contains(",male,") || l.contains(",female,"))
    );
}

#[tokio::test]
async fn dataframe_select_of_patient_id_writes_the_patient_id() {
    let f = fixture();
    let submitted = submit(
        &f.app,
        json!({"expectedResultType": "DATAFRAME", "select": ["patient_id", SEX], "phenotypicClause": finnish()}),
    )
    .await;
    let id = submitted["resourceResultId"].as_str().unwrap().to_owned();
    assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
    let result = post(&f.app, &format!("{QUERY}/{id}/result"), "{}").await;
    let lines: Vec<&str> = result.body.lines().collect();
    assert_eq!(lines[0], format!("patient_id,patient_id,{SEX}"));
    assert_eq!(lines.len(), 103);
    for line in &lines[1..] {
        let fields: Vec<&str> = line.split(',').collect();
        assert_eq!(fields.len(), 3, "{line}");
        assert_eq!(fields[0], fields[1], "{line}");
        assert!(fields[0].parse::<u32>().is_ok(), "{line}");
    }
}

#[tokio::test]
async fn unsupported_exports_end_in_error() {
    let f = fixture();
    for result_type in ["DATAFRAME_TIMESERIES", "DATAFRAME_PFB"] {
        let submitted = submit(&f.app, json!({"expectedResultType": result_type})).await;
        assert_eq!(submitted["status"], "ERROR");
        assert_eq!(submitted["resourceStatus"], "ERROR");
        assert!(
            submitted["resultMetadata"]["error"]
                .as_str()
                .unwrap()
                .contains(result_type)
        );
        let id = submitted["resourceResultId"].as_str().unwrap();
        let result = post(&f.app, &format!("{QUERY}/{id}/result"), "{}").await;
        assert_eq!(result.status, StatusCode::BAD_REQUEST);
        assert_eq!(result.body, "Status : ERROR");
    }
}

#[tokio::test]
async fn evaluation_failures_end_in_error() {
    let f = fixture();
    let empty_filter = json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX});
    let submitted = submit(
        &f.app,
        json!({"expectedResultType": "PATIENTS", "phenotypicClause": empty_filter}),
    )
    .await;
    let id = submitted["resourceResultId"].as_str().unwrap().to_owned();
    let done = wait(&f.app, &id).await;
    assert_eq!(done["status"], "ERROR");
    assert!(
        done["resultMetadata"]["error"]
            .as_str()
            .unwrap()
            .contains("Either values")
    );
    assert!(done["duration"].as_u64().is_some());
    assert_eq!(result_files(&f.config.results_dir), 0);
}

#[tokio::test]
async fn invalid_submissions_are_plain_text_400s() {
    let f = strict_fixture();
    let required_with_values =
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "values": ["x"]});
    let p1 = consents(&["partition1"]);
    for query in [
        json!({"expectedResultType": "PATIENTS"}),
        json!({"expectedResultType": "PATIENTS", "userConsents": p1, "phenotypicClause": required_with_values}),
        json!({"expectedResultType": "COUNT", "userConsents": p1}),
        json!({"expectedResultType": "VCF_EXCERPT", "userConsents": p1}),
        json!({"expectedResultType": "PATIENTS", "userConsents": p1, "bogusField": 1}),
        json!("not json at all"),
    ] {
        let reply = post(&f.app, QUERY, envelope(query.clone())).await;
        assert_eq!(
            reply.status,
            StatusCode::BAD_REQUEST,
            "{query}: {}",
            reply.body
        );
        assert!(!reply.body.is_empty(), "{query}");
    }
    assert!(f.state.results().is_empty());
    let ok = submit(
        &f.app,
        json!({"expectedResultType": "PATIENTS", "userConsents": p1}),
    )
    .await;
    let id = ok["resourceResultId"].as_str().unwrap().to_owned();
    assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
}

#[tokio::test]
async fn unknown_and_malformed_ids() {
    let f = fixture();
    let unknown = "00000000-0000-4000-8000-00000000dead";
    for route in ["status", "result", "signed-url"] {
        let reply = post(&f.app, &format!("{QUERY}/{unknown}/{route}"), "{}").await;
        assert_eq!(reply.status, StatusCode::NOT_FOUND, "{route}");
        assert!(!reply.body.is_empty(), "{route}");
        let reply = post(&f.app, &format!("{QUERY}/not-a-uuid/{route}"), "{}").await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{route}");
    }
}

#[tokio::test]
async fn unsupported_routes_are_plain_text_400s() {
    let f = fixture();
    let submitted = submit(&f.app, json!({"expectedResultType": "PATIENTS"})).await;
    let id = submitted["resourceResultId"].as_str().unwrap().to_owned();
    wait(&f.app, &id).await;
    for path in [
        format!("{QUERY}/{id}/signed-url"),
        format!("{QUERY}/format"),
        "/PIC-SURE/v3/write/phenotypic".to_owned(),
    ] {
        let reply = post(
            &f.app,
            &path,
            envelope(json!({"expectedResultType": "COUNT"})),
        )
        .await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{path}");
        assert!(reply.body.contains("not supported"));
    }
}

#[tokio::test]
async fn results_not_yet_available_answer_their_status() {
    let f = fixture_with(|c| c.small_task_threads = 1);
    let engine = Arc::clone(&f.engine);
    let mut held = Vec::new();
    for _ in 0..f.config.engine_concurrency {
        held.push(f.state.engine_slot().await);
    }
    let status = f
        .state
        .results()
        .submit(engine, Query::new(ResultType::Patients))
        .unwrap();
    let result = post(
        &f.app,
        &format!("{QUERY}/{}/result", status.resource_result_id),
        "{}",
    )
    .await;
    assert_eq!(result.status, StatusCode::BAD_REQUEST);
    assert!(
        result.body == "Status : PENDING" || result.body == "Status : RUNNING",
        "{}",
        result.body
    );
    assert_eq!(result.content_type(), Some("application/json"));
    drop(held);
}

async fn finished(f: &common::Fixture, id: &str) {
    wait(&f.app, id).await;
}

#[tokio::test]
async fn registry_evicts_oldest_finished_and_their_files() {
    let f = fixture_with(|c| {
        c.result_limit = 2;
        c.result_memory_bytes = 0;
    });
    let mut ids = Vec::new();
    for _ in 0..2 {
        let status = f
            .state
            .results()
            .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
            .unwrap();
        finished(&f, &status.resource_result_id).await;
        ids.push(status.resource_result_id);
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(result_files(&f.config.results_dir), 2);
    let third = f
        .state
        .results()
        .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
        .unwrap();
    finished(&f, &third.resource_result_id).await;
    assert_eq!(f.state.results().len(), 2);
    assert!(f.state.results().status(&ids[0]).is_none());
    assert!(f.state.results().status(&ids[1]).is_some());
    assert_eq!(result_files(&f.config.results_dir), 2);
    assert!(
        !f.config
            .results_dir
            .join(format!("result-{}.csv", ids[0]))
            .exists()
    );
}

#[tokio::test]
async fn registry_refuses_when_every_entry_is_unfinished() {
    let f = fixture_with(|c| c.result_limit = 1);
    let mut held = Vec::new();
    for _ in 0..f.config.engine_concurrency {
        held.push(f.state.engine_slot().await);
    }
    f.state
        .results()
        .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
        .unwrap();
    let refused = f
        .state
        .results()
        .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients));
    assert_eq!(refused.unwrap_err().status, StatusCode::SERVICE_UNAVAILABLE);
    drop(held);
}

#[tokio::test]
async fn registry_expires_finished_results_by_age() {
    let f = fixture_with(|c| {
        c.result_max_age = Some(Duration::from_millis(1));
        c.result_memory_bytes = 0;
    });
    let status = f
        .state
        .results()
        .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
        .unwrap();
    finished(&f, &status.resource_result_id).await;
    assert_eq!(result_files(&f.config.results_dir), 1);
    tokio::time::sleep(Duration::from_millis(5)).await;
    f.state.results().sweep();
    assert!(f.state.results().is_empty());
    assert_eq!(result_files(&f.config.results_dir), 0);
}

#[tokio::test]
async fn stale_result_files_are_removed_at_startup() {
    let dir = tempfile::tempdir().unwrap();
    let config = common::config(dir.path());
    std::fs::create_dir_all(&config.results_dir).unwrap();
    std::fs::write(config.results_dir.join("result-old.csv"), "x").unwrap();
    std::fs::write(config.results_dir.join("keep.txt"), "x").unwrap();
    hpds_rs::AppState::new(None, &config).unwrap();
    assert!(!config.results_dir.join("result-old.csv").exists());
    assert!(config.results_dir.join("keep.txt").exists());
}

#[tokio::test]
async fn dataframe_is_disabled_while_id_batch_size_is_zero() {
    let f = fixture_with(|c| c.id_batch_size = 0);
    let submitted = submit(
        &f.app,
        json!({"expectedResultType": "DATAFRAME", "select": [SEX]}),
    )
    .await;
    assert_eq!(submitted["status"], "ERROR");
    assert_eq!(
        submitted["resultMetadata"]["error"],
        "exports are disabled (ID_BATCH_SIZE=0)"
    );
    let patients = submit(&f.app, json!({"expectedResultType": "PATIENTS"})).await;
    let id = patients["resourceResultId"].as_str().unwrap().to_owned();
    assert_eq!(wait(&f.app, &id).await["resourceStatus"], "SUCCESS");
}

#[tokio::test]
async fn export_over_the_pin_cap_ends_in_error() {
    let f = fixture();
    let engine = Arc::new(
        hpds_query::Engine::open(&f.store(), &f.config.partitions)
            .unwrap()
            .with_export_pin_bytes(1),
    );
    let mut query = Query::new(ResultType::Dataframe);
    query.select = vec![SEX.to_owned(), AGE.to_owned()];
    let status = f.state.results().submit(engine, query).unwrap();
    let done = wait(&f.app, &status.resource_result_id).await;
    assert_eq!(done["status"], "ERROR");
    assert_eq!(
        done["resultMetadata"]["error"],
        "export too wide; select fewer concepts"
    );
    assert_eq!(result_files(&f.config.results_dir), 0);
}

#[tokio::test]
async fn registry_evicts_oldest_finished_past_the_byte_cap() {
    let f = fixture_with(|c| {
        c.result_max_bytes = 1;
        c.result_memory_bytes = 0;
    });
    let mut ids = Vec::new();
    for _ in 0..3 {
        let status = f
            .state
            .results()
            .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
            .unwrap();
        finished(&f, &status.resource_result_id).await;
        ids.push(status.resource_result_id);
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(f.state.results().len(), 1);
    assert!(f.state.results().status(&ids[2]).is_some());
    assert_eq!(result_files(&f.config.results_dir), 1);
}

#[tokio::test]
async fn results_without_an_age_limit_survive_a_sweep() {
    let f = fixture();
    let status = f
        .state
        .results()
        .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
        .unwrap();
    finished(&f, &status.resource_result_id).await;
    tokio::time::sleep(Duration::from_millis(5)).await;
    f.state.results().sweep();
    assert_eq!(f.state.results().len(), 1);
}

#[test]
fn existing_results_directories_are_checked_not_changed() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = common::config(dir.path());
    config.data_dir = dir.path().join("data");

    std::fs::create_dir(&config.results_dir).unwrap();
    std::fs::set_permissions(&config.results_dir, std::fs::Permissions::from_mode(0o755)).unwrap();
    hpds_rs::AppState::new(None, &config).unwrap();
    let mode = std::fs::metadata(&config.results_dir)
        .unwrap()
        .permissions()
        .mode();
    assert_eq!(mode & 0o777, 0o755);

    std::fs::set_permissions(&config.results_dir, std::fs::Permissions::from_mode(0o775)).unwrap();
    let error = hpds_rs::AppState::new(None, &config).err().unwrap();
    assert!(error.to_string().contains("writable"), "{error}");
}

#[test]
fn symlinked_results_directories_are_refused() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = common::config(dir.path());
    config.data_dir = dir.path().join("data");
    let real = dir.path().join("real");
    std::fs::create_dir(&real).unwrap();
    std::os::unix::fs::symlink(&real, &config.results_dir).unwrap();
    let error = hpds_rs::AppState::new(None, &config).err().unwrap();
    assert!(error.to_string().contains("symbolic link"), "{error}");
}

#[test]
fn results_directories_in_the_data_directory_are_refused() {
    let dir = tempfile::tempdir().unwrap();
    let mut config = common::config(dir.path());
    config.data_dir = dir.path().join("data");
    for results in [config.data_dir.clone(), config.data_dir.join("results")] {
        config.results_dir = results;
        let error = hpds_rs::AppState::new(None, &config).err().unwrap();
        assert!(error.to_string().contains("data directory"), "{error}");
    }
    config.results_dir = dir.path().join("elsewhere");
    hpds_rs::AppState::new(None, &config).unwrap();
}

async fn exported(f: &common::Fixture, query: Value) -> (Value, common::Reply) {
    let id = submit(&f.app, query).await["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    let done = wait(&f.app, &id).await;
    let result = post(&f.app, &format!("{QUERY}/{id}/result"), "{}").await;
    (done, result)
}

#[tokio::test]
async fn results_held_in_memory_answer_as_files_do() {
    let in_memory = fixture();
    let in_files = fixture_with(|c| c.result_memory_bytes = 0);
    let queries = [
        json!({"expectedResultType": "PATIENTS", "phenotypicClause": finnish()}),
        json!({"expectedResultType": "PATIENTS"}),
        json!({"expectedResultType": "DATAFRAME", "select": [SEX, AGE, r"\not\a\concept\"], "phenotypicClause": finnish()}),
        json!({"expectedResultType": "DATAFRAME", "select": [AGE, SEX, AGE]}),
    ];
    for query in queries {
        let (memory_status, memory) = exported(&in_memory, query.clone()).await;
        let (file_status, file) = exported(&in_files, query).await;
        assert_eq!(memory_status["resourceStatus"], "SUCCESS");
        assert_eq!(memory_status["sizeInBytes"], file_status["sizeInBytes"]);
        assert_eq!(
            memory_status["sizeInBytes"].as_u64(),
            Some(memory.body.len() as u64)
        );
        assert_eq!(memory.status, file.status);
        assert_eq!(memory.headers, file.headers);
        assert_eq!(memory.body, file.body);
    }
    assert_eq!(result_files(&in_memory.config.results_dir), 0);
    assert_eq!(result_files(&in_files.config.results_dir), 4);
    assert!(in_memory.state.results().memory_bytes() > 0);
    assert_eq!(in_files.state.results().memory_bytes(), 0);
}

#[tokio::test]
async fn results_held_in_memory_follow_the_same_retention() {
    let f = fixture_with(|c| c.result_limit = 2);
    let mut sizes = Vec::new();
    for _ in 0..3 {
        let status = f
            .state
            .results()
            .submit(Arc::clone(&f.engine), Query::new(ResultType::Patients))
            .unwrap();
        let done = wait(&f.app, &status.resource_result_id).await;
        sizes.push(done["sizeInBytes"].as_u64().unwrap());
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(f.state.results().len(), 2);
    assert_eq!(f.state.results().memory_bytes(), sizes[1] + sizes[2]);
    assert_eq!(result_files(&f.config.results_dir), 0);

    let aged = fixture_with(|c| c.result_max_age = Some(Duration::from_millis(1)));
    let status = aged
        .state
        .results()
        .submit(Arc::clone(&aged.engine), Query::new(ResultType::Patients))
        .unwrap();
    finished(&aged, &status.resource_result_id).await;
    assert!(aged.state.results().memory_bytes() > 0);
    tokio::time::sleep(Duration::from_millis(5)).await;
    aged.state.results().sweep();
    assert!(aged.state.results().is_empty());
    assert_eq!(aged.state.results().memory_bytes(), 0);

    let capped = fixture_with(|c| c.result_max_bytes = 1);
    let mut last = String::new();
    for _ in 0..3 {
        let status = capped
            .state
            .results()
            .submit(Arc::clone(&capped.engine), Query::new(ResultType::Patients))
            .unwrap();
        finished(&capped, &status.resource_result_id).await;
        last = status.resource_result_id;
        tokio::time::sleep(Duration::from_millis(5)).await;
    }
    assert_eq!(capped.state.results().len(), 1);
    assert!(capped.state.results().status(&last).is_some());
    let held = capped.state.results().status(&last).unwrap().size_in_bytes;
    assert_eq!(capped.state.results().memory_bytes(), held);
}

#[tokio::test]
async fn a_full_memory_budget_sends_results_to_files() {
    let f = fixture_with(|c| c.result_memory_bytes = 1);
    let (done, result) = exported(&f, json!({"expectedResultType": "PATIENTS"})).await;
    assert_eq!(done["resourceStatus"], "SUCCESS");
    assert_eq!(result.status, StatusCode::OK);
    assert_eq!(result_files(&f.config.results_dir), 1);
    assert_eq!(f.state.results().memory_bytes(), 0);
}
