//! The genomic routes over HTTP: the four-partition fixture with a genomic store loaded from
//! VCFs beside it, opened as `main` opens it, and the health states of a genomic store that
//! cannot be served.

mod common;

#[allow(dead_code)]
#[path = "../../hpds-query/tests/common/genomic.rs"]
mod genomic_data;

use std::path::{Path, PathBuf};
use std::sync::Arc;

use axum::Router;
use axum::http::StatusCode;
use common::{config, envelope, get, open_engine, post};
use genomic_data::{GroupVcfs, NodeVcf, load_genomic, rec};
use hpds_format::GenoMode;
use hpds_query::{ExcerptAnswer, Query};
use hpds_rs::config::Config;
use hpds_rs::genomic::{
    GENOMIC_CHANGED_REASON, GENOMIC_LAYOUT_REASON, GENOMIC_NOT_OPENED_REASON, GenomicStatus,
    attach_genomic,
};
use hpds_rs::{AppState, router};
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const GENE: &str = "Gene_with_variant";

/// The genomic patients: ten of `partition1` and ten of `partition4`.
fn genomic_patients() -> Vec<u32> {
    (197_506..197_516).chain(200_000..200_010).collect()
}

/// One group of nodes `chr1` and `chr2`:
///
/// | spec | gene | AF | carriers |
/// |---|---|---|---|
/// | `chr1,100,A,G,G1,missense_variant` | G1 | 0.1 | 197506 `0/1`, 197507 `1/1` |
/// | `chr1,1500,A,T,G2,synonymous_variant` | G2 | 0.2 | 200000 `0/1` |
/// | `chr2,100,A,C,G1,stop_gained` | G1 | 0.3 | 197508, 200001 `0/1` |
fn groups(patients: &[u32]) -> Vec<GroupVcfs<'static>> {
    vec![GroupVcfs {
        name: "",
        nodes: vec![
            NodeVcf {
                chrom: "chr1",
                patients: patients.to_vec(),
                records: vec![
                    rec(
                        "chr1",
                        100,
                        "G",
                        "G1",
                        "missense_variant",
                        "0.1",
                        &[(197_506, "0|1"), (197_507, "1|1")],
                    ),
                    rec(
                        "chr1",
                        1500,
                        "T",
                        "G2",
                        "synonymous_variant",
                        "0.2",
                        &[(200_000, "0|1")],
                    ),
                ],
            },
            NodeVcf {
                chrom: "chr2",
                patients: patients.to_vec(),
                records: vec![rec(
                    "chr2",
                    100,
                    "C",
                    "G1",
                    "stop_gained",
                    "0.3",
                    &[(197_508, "0|1"), (200_001, "0|1")],
                )],
            },
        ],
    }]
}

struct GenomicFixture {
    dir: tempfile::TempDir,
    state: AppState,
    app: Router,
}

impl GenomicFixture {
    fn genomic_dir(&self) -> PathBuf {
        self.dir.path().join("genomic")
    }
}

/// Builds the phenotype fixture and the genomic store in `localDistributed`, and serves them
/// with the settings of `adjust` applied over genomic on, both excerpt gates on.
fn genomic_fixture_with(adjust: impl FnOnce(&mut Config, &Path)) -> GenomicFixture {
    let dir = tempfile::tempdir().unwrap();
    let store = common::build_store(dir.path());
    let genomic = dir.path().join("genomic");
    load_genomic(
        &dir.path().join("vcf"),
        &store.join("encryption_key"),
        GenoMode::LocalDistributed,
        &groups(&genomic_patients()),
        &genomic,
    );
    let mut config = config(dir.path());
    config.data_dir = store.clone();
    config.genomic.data_dir = genomic;
    config.genomic.processor = Some(GenoMode::LocalDistributed);
    config.genomic.variant.vcf_excerpt_enabled = true;
    adjust(&mut config, dir.path());
    let engine = Arc::try_unwrap(open_engine(&store, &config)).unwrap();
    let (engine, status) = attach_genomic(engine, &config);
    let state = AppState::with_genomic(Some(Arc::new(engine)), &config, status).unwrap();
    let app = router(state.clone());
    GenomicFixture { dir, state, app }
}

fn genomic_fixture() -> GenomicFixture {
    genomic_fixture_with(|_, _| {})
}

fn genomic_query(result_type: &str, gene: &str) -> Value {
    json!({
        "expectedResultType": result_type,
        "genomicFilters": [{"key": GENE, "values": [gene]}],
    })
}

#[tokio::test]
async fn sync_genomic_types_answer_from_the_genomic_store() {
    let f = genomic_fixture();
    assert_eq!(f.state.genomic_status(), GenomicStatus::Served);
    let count = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!((count.status, count.body.as_str()), (StatusCode::OK, "4"));
    let variants = post(
        &f.app,
        SYNC,
        envelope(genomic_query("VARIANT_COUNT_FOR_QUERY", "G1")),
    )
    .await;
    assert_eq!(variants.status, StatusCode::OK);
    assert_eq!(
        variants.body,
        r#"{"count":2,"message":"Query ran successfully"}"#
    );
    assert!(variants.headers.contains_key("queryMetadata"));
    let none = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "VARIANT_COUNT_FOR_QUERY"})),
    )
    .await;
    assert_eq!(
        none.body,
        r#"{"count":"0","message":"No variant filters were supplied, so no query was run."}"#
    );
    let list = post(
        &f.app,
        SYNC,
        envelope(genomic_query("VARIANT_LIST_FOR_QUERY", "G1")),
    )
    .await;
    assert_eq!(list.content_type(), Some("text/plain"));
    assert_eq!(
        list.body,
        "[chr1,100,A,G,G1,missense_variant, chr2,100,A,C,G1,stop_gained]"
    );
    let listing = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "INFO_COLUMN_LISTING"})),
    )
    .await;
    assert_eq!(listing.content_type(), Some("application/json"));
    let entries = listing.json();
    assert_eq!(entries.as_array().unwrap().len(), 6, "{entries}");
    assert!(
        entries
            .as_array()
            .unwrap()
            .iter()
            .any(|e| e["key"] == GENE && e["continuous"] == false && e["min"] == 3.4028235e38)
    );
}

#[tokio::test]
async fn excerpts_stream_with_their_exact_length() {
    let f = genomic_fixture();
    for result_type in ["VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"] {
        let reply = post(&f.app, SYNC, envelope(genomic_query(result_type, "G1"))).await;
        assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
        assert_eq!(reply.content_type(), Some("text/plain"));
        assert!(reply.headers.contains_key("queryMetadata"));
        let engine = f.state.engine().unwrap();
        let query: Query = serde_json::from_value(genomic_query(result_type, "G1")).unwrap();
        let Ok(ExcerptAnswer::Rows(excerpt)) = engine.vcf_excerpt(&query) else {
            panic!("an excerpt");
        };
        let mut whole = Vec::new();
        excerpt.write_to(&mut whole).unwrap();
        assert_eq!(reply.body.as_bytes(), whole.as_slice(), "{result_type}");
        assert_eq!(
            reply.headers["content-length"].to_str().unwrap(),
            whole.len().to_string()
        );
        assert!(reply.body.starts_with("CHROM\tPOSITION\tREF\tALT\t"));
        assert_eq!(reply.body.lines().count(), 3, "{}", reply.body);
    }
    let none = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G9"))).await;
    assert_eq!(
        (none.status, none.body.as_str()),
        (StatusCode::OK, "No Variants Found\n")
    );
}

#[tokio::test]
async fn excerpts_over_the_size_bound_are_refused_and_gates_answer_javas_text() {
    let f = genomic_fixture_with(|c, _| {
        c.genomic.variant.max_excerpt_bytes = 64;
        c.genomic.variant.vcf_excerpt_enabled = false;
    });
    let refused = post(
        &f.app,
        SYNC,
        envelope(genomic_query("VARIANT_LIST_FOR_QUERY", "G1")),
    )
    .await;
    assert_eq!(
        refused.body, "VARIANT_LIST query type not allowed",
        "both gates off"
    );
    let off = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(
        (off.status, off.body.as_str()),
        (StatusCode::OK, "VCF_EXCERPT query type not allowed")
    );
    let f = genomic_fixture_with(|c, _| c.genomic.variant.max_excerpt_bytes = 64);
    let big = post(
        &f.app,
        SYNC,
        envelope(genomic_query("AGGREGATE_VCF_EXCERPT", "G1")),
    )
    .await;
    assert_eq!(big.status, StatusCode::BAD_REQUEST);
    assert!(big.body.contains("HPDS_MAX_EXCERPT_BYTES"), "{}", big.body);
}

#[tokio::test]
async fn genomic_types_are_refused_on_the_async_route_and_exports_use_genomic_filters() {
    let f = genomic_fixture();
    for result_type in [
        "INFO_COLUMN_LISTING",
        "VARIANT_COUNT_FOR_QUERY",
        "VARIANT_LIST_FOR_QUERY",
        "VCF_EXCERPT",
        "AGGREGATE_VCF_EXCERPT",
    ] {
        let reply = post(
            &f.app,
            "/PIC-SURE/v3/query",
            envelope(genomic_query(result_type, "G1")),
        )
        .await;
        assert_eq!(reply.status, StatusCode::BAD_REQUEST, "{result_type}");
        assert_eq!(
            reply.body,
            format!(
                "result type {result_type} is not supported by POST /query; send it to POST /query/sync"
            )
        );
    }
    let submitted = post(
        &f.app,
        "/PIC-SURE/v3/query",
        envelope(genomic_query("PATIENTS", "G1")),
    )
    .await;
    assert_eq!(submitted.status, StatusCode::OK, "{}", submitted.body);
    let id = submitted.json()["resourceResultId"]
        .as_str()
        .unwrap()
        .to_owned();
    assert_eq!(common::wait(&f.app, &id).await["status"], "AVAILABLE");
    let result = post(&f.app, &format!("/PIC-SURE/v3/query/{id}/result"), "{}").await;
    let mut rows: Vec<&str> = result.body.lines().skip(1).collect();
    rows.sort_unstable();
    assert_eq!(rows, ["197506", "197507", "197508", "200001"]);
}

#[tokio::test]
async fn search_lists_matching_info_columns_and_pages_their_values() {
    let f = genomic_fixture();
    let search = |term: Value| {
        let app = f.app.clone();
        async move {
            post(
                &app,
                "/PIC-SURE/v3/search",
                json!({"query": term}).to_string(),
            )
            .await
        }
    };
    let gene = search(json!("gene")).await;
    assert_eq!(gene.status, StatusCode::OK);
    let info = &gene.json()["results"]["info"];
    assert_eq!(info.as_object().unwrap().len(), 1, "{info}");
    assert_eq!(info[GENE]["description"], "Description=\"The gene.\"");
    assert_eq!(info[GENE]["continuous"], false);
    let mut values: Vec<&str> = info[GENE]["values"]
        .as_array()
        .unwrap()
        .iter()
        .map(|v| v.as_str().unwrap())
        .collect();
    values.sort_unstable();
    assert_eq!(values, ["G1", "G2"]);
    assert!(
        gene.body
            .contains(r#""info":{"Gene_with_variant":{"description":"#),
        "{}",
        gene.body
    );
    let every = search(json!("")).await.json();
    let keys: Vec<&String> = every["results"]["info"]
        .as_object()
        .unwrap()
        .keys()
        .collect();
    assert_eq!(keys.len(), 3);
    assert_eq!(every["results"]["info"]["AF"]["values"], json!([]));
    assert_eq!(every["results"]["info"]["AF"]["continuous"], true);
    let null = search(Value::Null).await.json();
    assert_eq!(null["results"]["info"], json!({}));

    let values = |query: &str| {
        let app = f.app.clone();
        let path = format!("/PIC-SURE/v3/search/values/?{query}");
        async move { get(&app, &path).await }
    };
    let all = values("genomicConceptPath=Gene_with_variant&query=g&page=1&size=1").await;
    assert_eq!(all.status, StatusCode::OK, "{}", all.body);
    let page = all.json();
    assert_eq!(
        (page["page"].clone(), page["total"].clone()),
        (json!(1), json!(2))
    );
    assert_eq!(page["results"].as_array().unwrap().len(), 1);
    let second = values("genomicConceptPath=Gene_with_variant&query=g&page=2&size=1")
        .await
        .json();
    assert_ne!(second["results"], page["results"]);
    let past = values("genomicConceptPath=Gene_with_variant&query=&page=9&size=5").await;
    assert_eq!(past.body, r#"{"results":[],"page":9,"total":2}"#);
    let one = values("genomicConceptPath=Gene_with_variant&query=1&page=1&size=10").await;
    assert_eq!(one.body, r#"{"results":["G1"],"page":1,"total":1}"#);
    let unknown = values("genomicConceptPath=Nothing&query=&page=1&size=10").await;
    assert_eq!(unknown.status, StatusCode::BAD_REQUEST);
    assert!(
        unknown.body.contains("not held by every genomic node"),
        "{}",
        unknown.body
    );
    let overflow =
        values("genomicConceptPath=Gene_with_variant&query=&page=3&size=1073741824").await;
    assert_eq!(
        overflow.status,
        StatusCode::BAD_REQUEST,
        "{}",
        overflow.body
    );
    let zero = values("genomicConceptPath=Gene_with_variant&query=&page=0&size=1").await;
    assert_eq!(zero.status, StatusCode::BAD_REQUEST);
}

async fn health(app: &Router) -> (StatusCode, Value) {
    let reply = get(app, "/actuator/health").await;
    (reply.status, reply.json())
}

/// Asserts the fixture serves health DOWN with `reason`, refuses genomic requests with a 503
/// and still answers a phenotype COUNT.
async fn assert_genomic_down(f: &GenomicFixture, reason: &str) {
    let (status, body) = health(&f.app).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(body, json!({"status": "DOWN", "reason": reason}));
    for query in [
        genomic_query("COUNT", "G1"),
        json!({"expectedResultType": "INFO_COLUMN_LISTING"}),
        genomic_query("AGGREGATE_VCF_EXCERPT", "G1"),
    ] {
        let reply = post(&f.app, SYNC, envelope(query)).await;
        assert_eq!(
            reply.status,
            StatusCode::SERVICE_UNAVAILABLE,
            "{}",
            reply.body
        );
    }
    let values = get(
        &f.app,
        "/PIC-SURE/v3/search/values/?genomicConceptPath=Gene_with_variant&query=&page=1&size=1",
    )
    .await;
    assert_eq!(values.status, StatusCode::SERVICE_UNAVAILABLE);
    let phenotype = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(
        (phenotype.status, phenotype.body.as_str()),
        (StatusCode::OK, "4978")
    );
}

#[tokio::test]
async fn health_is_up_with_a_served_genomic_store() {
    let f = genomic_fixture();
    assert_eq!(
        health(&f.app).await,
        (StatusCode::OK, json!({"status": "UP"}))
    );
}

#[tokio::test]
async fn a_missing_genomic_store_serves_down_when_one_is_configured() {
    let f = genomic_fixture_with(|c, dir| c.genomic.data_dir = dir.join("absent"));
    assert_eq!(
        f.state.genomic_status(),
        GenomicStatus::Down(GENOMIC_NOT_OPENED_REASON)
    );
    assert_genomic_down(&f, GENOMIC_NOT_OPENED_REASON).await;
}

#[tokio::test]
async fn a_java_genomic_directory_serves_down() {
    let f = genomic_fixture_with(|c, dir| {
        let java = dir.join("java").join("chr1");
        std::fs::create_dir_all(&java).unwrap();
        std::fs::write(java.join("variantStore.javabin"), b"java").unwrap();
        c.genomic.data_dir = dir.join("java");
    });
    assert_genomic_down(&f, GENOMIC_NOT_OPENED_REASON).await;
}

#[tokio::test]
async fn a_genomic_store_of_another_layout_serves_down() {
    let f = genomic_fixture_with(|c, _| {
        c.genomic.processor = Some(GenoMode::LocalPatientDistributed);
    });
    assert_eq!(
        f.state.genomic_status(),
        GenomicStatus::Down(GENOMIC_LAYOUT_REASON)
    );
    assert_genomic_down(&f, GENOMIC_LAYOUT_REASON).await;
}

#[tokio::test]
async fn a_genomic_store_sealed_with_another_key_serves_down() {
    let f = genomic_fixture_with(|c, dir| {
        let other = dir.join("other-key");
        std::fs::write(&other, "FEDCBA9876543210FEDCBA9876543210").unwrap();
        let resealed = dir.join("resealed");
        load_genomic(
            &dir.join("vcf2"),
            &other,
            GenoMode::LocalDistributed,
            &groups(&genomic_patients()),
            &resealed,
        );
        c.genomic.data_dir = resealed;
    });
    assert_genomic_down(&f, GENOMIC_NOT_OPENED_REASON).await;
}

#[tokio::test]
async fn a_genomic_store_changed_on_disk_serves_down_and_refuses_genomic_queries() {
    let f = genomic_fixture();
    let blocks = f.genomic_dir().join("geno.blocks");
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(blocks)
        .unwrap();
    std::io::Write::write_all(&mut file, b"x").unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    let (status, body) = health(&f.app).await;
    assert_eq!(status, StatusCode::SERVICE_UNAVAILABLE);
    assert_eq!(body["reason"], GENOMIC_CHANGED_REASON);
    let reply = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!(
        (reply.status, reply.body.as_str()),
        (StatusCode::SERVICE_UNAVAILABLE, GENOMIC_CHANGED_REASON)
    );
    let phenotype = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(phenotype.status, StatusCode::OK);
}

#[tokio::test]
async fn a_genomic_store_of_another_id_moved_in_is_reported_superseded() {
    let f = genomic_fixture_with(|_, dir| {
        let key = dir.join("store").join("encryption_key");
        load_genomic(
            &dir.join("vcf2"),
            &key,
            GenoMode::LocalDistributed,
            &groups(&genomic_patients()),
            &dir.join("second"),
        );
    });
    let moved = f.genomic_dir().join("geno.index.new");
    std::fs::copy(f.dir.path().join("second").join("geno.index"), &moved).unwrap();
    std::fs::rename(&moved, f.genomic_dir().join("geno.index")).unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    assert_eq!(
        health(&f.app).await,
        (
            StatusCode::OK,
            json!({"status": "UP", "genomicStoreSuperseded": true})
        )
    );
    let reply = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!(reply.body, "4");
}

#[tokio::test]
async fn without_the_processor_setting_no_genomic_data_is_served() {
    let f = genomic_fixture_with(|c, _| c.genomic.processor = None);
    assert_eq!(f.state.genomic_status(), GenomicStatus::Off);
    assert_eq!(health(&f.app).await.0, StatusCode::OK);
    let count = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!(count.status, StatusCode::BAD_REQUEST);
    let listing = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "INFO_COLUMN_LISTING"})),
    )
    .await;
    assert_eq!(listing.body, "[]");
    let values = get(
        &f.app,
        "/PIC-SURE/v3/search/values/?genomicConceptPath=Gene_with_variant&query=&page=2&size=1",
    )
    .await;
    assert_eq!(values.body, r#"{"results":[],"page":2,"total":0}"#);
}

#[tokio::test]
async fn metrics_report_the_genomic_cache_budget_and_lists() {
    let f = genomic_fixture_with(|c, _| c.metrics = true);
    post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    get(
        &f.app,
        "/PIC-SURE/v3/search/values/?genomicConceptPath=Gene_with_variant&query=&page=1&size=1",
    )
    .await;
    let text = get(&f.app, "/metrics").await.body;
    for line in [
        "hpds_genomic_store_up 1",
        "hpds_genomic_cache_capacity_bytes 2147483648",
        "hpds_genomic_read_budget_bytes 1073741824",
        "hpds_genomic_search_values 2",
        "hpds_genomic_store_superseded 0",
    ] {
        assert!(text.lines().any(|l| l == line), "{line} missing:\n{text}");
    }
    for name in [
        "hpds_genomic_cache_hits_total",
        "hpds_genomic_cache_loads_total",
        "hpds_genomic_read_held_peak_bytes",
        "hpds_genomic_largest_chunk_set_bytes",
        "hpds_genomic_search_value_bytes",
    ] {
        assert!(
            text.lines().any(|l| l.starts_with(&format!("{name} "))),
            "{name} missing"
        );
    }
    let down = genomic_fixture_with(|c, dir| {
        c.metrics = true;
        c.genomic.data_dir = dir.join("absent");
    });
    let text = get(&down.app, "/metrics").await.body;
    assert!(text.lines().any(|l| l == "hpds_genomic_store_up 0"));
    assert!(!text.contains("hpds_genomic_cache_hits_total"));
}
