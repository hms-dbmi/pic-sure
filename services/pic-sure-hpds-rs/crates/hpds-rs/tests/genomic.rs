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
    genomic_fixture_of(&groups(&genomic_patients()), adjust)
}

/// [`genomic_fixture_with`] over the genomic data `data`.
fn genomic_fixture_of(
    data: &[GroupVcfs<'_>],
    adjust: impl FnOnce(&mut Config, &Path),
) -> GenomicFixture {
    let dir = tempfile::tempdir().unwrap();
    let store = common::build_store(dir.path());
    let genomic = dir.path().join("genomic");
    load_genomic(
        &dir.path().join("vcf"),
        &store.join("encryption_key"),
        GenoMode::LocalDistributed,
        data,
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
    for filters in [
        json!([{"key": "chr1,100,A,G", "values": [null]}]),
        json!([{"key": "AF", "min": 0.1}]),
        json!([{"key": "rs123"}]),
    ] {
        let refused = post(
            &f.app,
            "/PIC-SURE/v3/query",
            envelope(json!({"expectedResultType": "PATIENTS", "genomicFilters": filters})),
        )
        .await;
        assert_eq!(
            refused.status,
            StatusCode::BAD_REQUEST,
            "{filters}: {}",
            refused.body
        );
    }
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

/// One `chr1` node with 20,000 `G1` variants, each carried by patient 197506, so a
/// VCF_EXCERPT of `G1` is far larger than the chunks the stream keeps in flight.
fn many_variants() -> Vec<GroupVcfs<'static>> {
    let records = (0..20_000u32)
        .map(|i| {
            rec(
                "chr1",
                100 + i * 10,
                "G",
                "G1",
                "missense_variant",
                "0.1",
                &[(197_506, "0|1")],
            )
        })
        .collect();
    vec![GroupVcfs {
        name: "",
        nodes: vec![NodeVcf {
            chrom: "chr1",
            patients: genomic_patients(),
            records,
        }],
    }]
}

/// Sends `query` and returns the response with its body unread.
async fn send_unread(app: &Router, query: Value) -> axum::response::Response {
    use tower::ServiceExt;
    let request = axum::http::Request::post(SYNC)
        .header("content-type", "application/json")
        .body(axum::body::Body::from(envelope(query)))
        .unwrap();
    app.clone().oneshot(request).await.unwrap()
}

/// Reads the sample `name`, labels included as `/metrics` writes them.
async fn metric(app: &Router, name: &str) -> u64 {
    let text = get(app, "/metrics").await.body;
    let prefix = format!("{name} ");
    text.lines()
        .find_map(|l| l.strip_prefix(&prefix))
        .unwrap_or_else(|| panic!("{name} is not in /metrics"))
        .parse()
        .unwrap()
}

/// Polls `/metrics` for up to five seconds until `name` reads `want`, and returns what it
/// read last.
async fn await_metric(app: &Router, name: &str, want: u64) -> u64 {
    let mut seen = 0;
    for _ in 0..50 {
        seen = metric(app, name).await;
        if seen == want {
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    seen
}

const STALLED: &str = "hpds_excerpts_ended_early_total{end=\"stalled\"}";
const EXPIRED: &str = "hpds_excerpts_ended_early_total{end=\"expired\"}";
const ABORTED: &str = "hpds_excerpts_ended_early_total{end=\"aborted\"}";
const STORE_CHANGED: &str = "hpds_excerpts_ended_early_total{end=\"store_changed\"}";
const REFUSED: &str = "hpds_excerpts_refused_total";
const IN_PROGRESS: &str = "hpds_excerpts_in_progress";

/// The `Content-Length` of `response`.
fn content_length(response: &axum::response::Response) -> u64 {
    response.headers()["content-length"]
        .to_str()
        .unwrap()
        .parse()
        .unwrap()
}

/// Asserts that the body of `response` fails or ends short of its `Content-Length`, which
/// HTTP reports as a failed transfer.
async fn assert_cut_short(response: axum::response::Response) {
    let length = content_length(&response);
    let body = axum::body::to_bytes(response.into_body(), usize::MAX).await;
    assert!(
        body.as_ref().is_err() || body.as_ref().is_ok_and(|b| (b.len() as u64) < length),
        "the stream ends with an error or short of its Content-Length"
    );
}

#[tokio::test]
async fn a_client_that_stops_reading_releases_its_excerpt_slot_within_the_stall_bound() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_queue = std::time::Duration::ZERO;
        c.excerpt_stall = std::time::Duration::from_secs(1);
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VCF_EXCERPT", "G1")).await;
    assert_eq!(held.status(), StatusCode::OK);
    let length = content_length(&held);
    assert!(length > 400_000, "{length}");
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 1);
    let over = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(
        over.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "{}",
        over.body
    );
    assert!(
        over.body.contains("HPDS_EXCERPT_CONCURRENCY"),
        "{}",
        over.body
    );
    assert_eq!(over.headers["retry-after"], "1");
    assert_eq!(metric(&f.app, REFUSED).await, 1);
    let started = std::time::Instant::now();
    assert_eq!(await_metric(&f.app, STALLED, 1).await, 1);
    assert!(
        started.elapsed() < std::time::Duration::from_secs(3),
        "the slot is freed within the stall bound"
    );
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 0);
    let next = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(next.status, StatusCode::OK, "{}", next.body);
    assert_eq!(next.body.len() as u64, length);
    assert_eq!(metric(&f.app, ABORTED).await, 0);
    assert_cut_short(held).await;
}

#[tokio::test]
async fn excerpts_at_their_cap_leave_engine_slots_for_other_queries() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_queue = std::time::Duration::from_millis(200);
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VCF_EXCERPT", "G1")).await;
    assert_eq!(held.status(), StatusCode::OK);
    let count = post(
        &f.app,
        SYNC,
        envelope(json!({"expectedResultType": "COUNT"})),
    )
    .await;
    assert_eq!(
        (count.status, count.body.as_str()),
        (StatusCode::OK, "4978")
    );
    let genomic_count = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!(
        (genomic_count.status, genomic_count.body.as_str()),
        (StatusCode::OK, "1")
    );
    for result_type in ["AGGREGATE_VCF_EXCERPT", "VARIANT_LIST_FOR_QUERY"] {
        let refused = post(&f.app, SYNC, envelope(genomic_query(result_type, "G1"))).await;
        assert_eq!(
            refused.status,
            StatusCode::SERVICE_UNAVAILABLE,
            "{result_type}: {}",
            refused.body
        );
    }
    drop(held);
    assert_eq!(
        await_metric(&f.app, ABORTED, 1).await,
        1,
        "a client that left ends its stream"
    );
    let after = post(
        &f.app,
        SYNC,
        envelope(genomic_query("AGGREGATE_VCF_EXCERPT", "G1")),
    )
    .await;
    assert_eq!(after.status, StatusCode::OK);
}

#[tokio::test]
async fn an_excerpt_that_outlives_the_stream_bound_ends_early() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_stall = std::time::Duration::from_secs(30);
        c.excerpt_stream = std::time::Duration::from_secs(1);
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VCF_EXCERPT", "G1")).await;
    tokio::time::sleep(std::time::Duration::from_millis(1500)).await;
    assert_eq!(await_metric(&f.app, EXPIRED, 1).await, 1);
    assert_eq!(metric(&f.app, STALLED).await, 0);
    assert_cut_short(held).await;
}

#[tokio::test]
async fn a_variant_list_client_that_stops_reading_releases_its_slot_within_the_stall_bound() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_queue = std::time::Duration::ZERO;
        c.excerpt_stall = std::time::Duration::from_secs(1);
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VARIANT_LIST_FOR_QUERY", "G1")).await;
    assert_eq!(held.status(), StatusCode::OK);
    assert!(content_length(&held) > 400_000);
    let over = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(
        over.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "{}",
        over.body
    );
    assert_eq!(await_metric(&f.app, STALLED, 1).await, 1);
    let next = post(
        &f.app,
        SYNC,
        envelope(genomic_query("VARIANT_LIST_FOR_QUERY", "G1")),
    )
    .await;
    assert_eq!(next.status, StatusCode::OK);
    assert!(
        next.body.starts_with("[chr1,100,A,G,G1,"),
        "{}",
        &next.body[..40]
    );
    assert_cut_short(held).await;
}

#[tokio::test]
async fn an_excerpt_whose_genomic_store_changes_while_it_streams_ends_with_an_error() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VCF_EXCERPT", "G1")).await;
    assert_eq!(held.status(), StatusCode::OK);
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(f.genomic_dir().join("geno.blocks"))
        .unwrap();
    std::io::Write::write_all(&mut file, b"x").unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    assert_cut_short(held).await;
    assert_eq!(await_metric(&f.app, STORE_CHANGED, 1).await, 1);
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 0);
}

#[tokio::test]
async fn closed_gates_answer_before_a_genomic_store_that_is_not_served() {
    let f = genomic_fixture_with(|c, dir| {
        c.genomic.data_dir = dir.join("absent");
        c.genomic.variant.vcf_excerpt_enabled = false;
        c.genomic.variant.aggregate_vcf_excerpt_enabled = false;
    });
    assert_eq!(
        f.state.genomic_status(),
        GenomicStatus::Down(GENOMIC_NOT_OPENED_REASON)
    );
    for (result_type, text) in [
        (
            "VARIANT_LIST_FOR_QUERY",
            "VARIANT_LIST query type not allowed",
        ),
        ("VCF_EXCERPT", "VCF_EXCERPT query type not allowed"),
        (
            "AGGREGATE_VCF_EXCERPT",
            "AGGREGATE_VCF_EXCERPT query type not allowed",
        ),
    ] {
        let reply = post(&f.app, SYNC, envelope(genomic_query(result_type, "G1"))).await;
        assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, text));
    }
    let f = genomic_fixture_with(|c, dir| {
        c.genomic.data_dir = dir.join("absent");
        c.genomic.variant.vcf_excerpt_enabled = false;
        c.genomic.variant.aggregate_vcf_excerpt_enabled = true;
    });
    let closed = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(closed.body, "VCF_EXCERPT query type not allowed");
    let open = post(
        &f.app,
        SYNC,
        envelope(genomic_query("AGGREGATE_VCF_EXCERPT", "G1")),
    )
    .await;
    assert_eq!(
        open.status,
        StatusCode::SERVICE_UNAVAILABLE,
        "{}",
        open.body
    );
}

#[tokio::test]
async fn an_excerpt_over_the_cap_waits_for_a_slot_within_the_queue_bound() {
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_stall = std::time::Duration::from_secs(1);
        c.excerpt_queue = std::time::Duration::from_secs(10);
        c.metrics = true;
    });
    let held = send_unread(&f.app, genomic_query("VCF_EXCERPT", "G1")).await;
    let length = content_length(&held);
    let started = std::time::Instant::now();
    let queued = post(&f.app, SYNC, envelope(genomic_query("VCF_EXCERPT", "G1"))).await;
    assert_eq!(queued.status, StatusCode::OK, "{}", queued.body);
    assert_eq!(queued.body.len() as u64, length);
    assert!(
        started.elapsed() >= std::time::Duration::from_millis(900),
        "it waited for the stalled excerpt to free its slot"
    );
    assert_eq!(metric(&f.app, STALLED).await, 1);
    assert_eq!(metric(&f.app, REFUSED).await, 0);
    assert_cut_short(held).await;
}

#[tokio::test]
async fn a_closed_gate_answers_its_text_after_the_genomic_store_changed() {
    let f = genomic_fixture_with(|c, _| {
        c.genomic.variant.vcf_excerpt_enabled = false;
        c.genomic.variant.aggregate_vcf_excerpt_enabled = false;
    });
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(f.genomic_dir().join("geno.blocks"))
        .unwrap();
    std::io::Write::write_all(&mut file, b"x").unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    assert_eq!(health(&f.app).await.1["reason"], GENOMIC_CHANGED_REASON);
    for (result_type, text) in [
        (
            "VARIANT_LIST_FOR_QUERY",
            "VARIANT_LIST query type not allowed",
        ),
        ("VCF_EXCERPT", "VCF_EXCERPT query type not allowed"),
        (
            "AGGREGATE_VCF_EXCERPT",
            "AGGREGATE_VCF_EXCERPT query type not allowed",
        ),
    ] {
        let reply = post(&f.app, SYNC, envelope(genomic_query(result_type, "G1"))).await;
        assert_eq!((reply.status, reply.body.as_str()), (StatusCode::OK, text));
    }
    let count = post(&f.app, SYNC, envelope(genomic_query("COUNT", "G1"))).await;
    assert_eq!(count.status, StatusCode::SERVICE_UNAVAILABLE);
}

#[tokio::test]
async fn search_after_the_genomic_store_changed_answers_terms_no_info_column_matches() {
    let f = genomic_fixture();
    let mut file = std::fs::OpenOptions::new()
        .append(true)
        .open(f.genomic_dir().join("geno.blocks"))
        .unwrap();
    std::io::Write::write_all(&mut file, b"x").unwrap();
    tokio::time::sleep(std::time::Duration::from_millis(1100)).await;
    let phenotype = post(
        &f.app,
        "/PIC-SURE/v3/search",
        json!({"query": "zzz_matches_nothing"}).to_string(),
    )
    .await;
    assert_eq!(phenotype.status, StatusCode::OK, "{}", phenotype.body);
    assert_eq!(phenotype.json()["results"]["info"], json!({}));
    let genomic = post(
        &f.app,
        "/PIC-SURE/v3/search",
        json!({"query": "gene_with"}).to_string(),
    )
    .await;
    assert_eq!(
        (genomic.status, genomic.body.as_str()),
        (StatusCode::SERVICE_UNAVAILABLE, GENOMIC_CHANGED_REASON)
    );
}

/// The write stall bound runs from the first write that stays pending, which can only come
/// after the excerpt's measure pass has sent the response head. The test therefore waits for
/// the head without consuming it, however long the measure pass takes on a slow or
/// instrumented build, and bounds the close from there.
#[tokio::test]
async fn a_connection_whose_client_reads_nothing_is_closed_after_the_write_stall_bound() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let f = genomic_fixture_of(&many_variants(), |c, _| {
        c.engine_concurrency = 2;
        c.excerpt_concurrency = 1;
        c.excerpt_stall = std::time::Duration::from_secs(1);
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
    let server = tokio::spawn(hpds_rs::serve(
        listener,
        f.app.clone(),
        async {
            let _ = stopped.await;
        },
        hpds_rs::ServerTimeouts {
            write_stall: std::time::Duration::from_secs(1),
            ..hpds_rs::ServerTimeouts::default()
        },
    ));
    let before = hpds_rs::connections_write_stalled();
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    socket.set_recv_buffer_size(4096).unwrap();
    let mut stream = socket.connect(addr).await.unwrap();
    let body = envelope(genomic_query("VCF_EXCERPT", "G1"));
    let request = format!(
        "POST {SYNC} HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut first = [0u8; 1];
    tokio::time::timeout(std::time::Duration::from_secs(120), stream.peek(&mut first))
        .await
        .expect("the response head arrives once the excerpt is measured")
        .unwrap();
    let mut closed = false;
    for _ in 0..80 {
        if hpds_rs::connections_write_stalled() > before {
            closed = true;
            break;
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    assert!(
        closed,
        "the server closed the connection its client did not read"
    );
    let mut received = Vec::new();
    let read = tokio::time::timeout(
        std::time::Duration::from_secs(5),
        stream.read_to_end(&mut received),
    )
    .await
    .expect("the connection is closed, so reading it ends");
    let text = String::from_utf8_lossy(&received);
    let length: usize = text
        .lines()
        .find_map(|l| l.strip_prefix("content-length: "))
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    let head_end = text.find("\r\n\r\n").unwrap() + 4;
    assert!(
        read.is_err() || received.len() - head_end < length,
        "the body ended short of its Content-Length"
    );
    let _ = stop.send(());
    server.await.unwrap().unwrap();
}
