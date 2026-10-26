//! Spilled excerpts torn down mid-stream: a client that stops reading, a body that outlives the
//! stream bound and a client that leaves each release the excerpt slot and close the spill
//! file, so the process's open descriptors (`/dev/fd`) return to their count before the excerpt.
//! The tests run one at a time, since the descriptor count is the whole process's.

mod common;

use std::path::Path;
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::Router;
use axum::http::StatusCode;
use common::{build_store, config, envelope, get, open_engine, post};
use hpds_etl::genomic::vcf_load::{GenomicLoadOptions, genomic_load};
use hpds_format::GenoMode;
use hpds_rs::config::Config;
use hpds_rs::genomic::attach_genomic;
use hpds_rs::{AppState, router};
use http_body_util::BodyExt;
use serde_json::{Value, json};

const SYNC: &str = "/PIC-SURE/v3/query/sync";
const STALLED: &str = "hpds_excerpts_ended_early_total{end=\"stalled\"}";
const EXPIRED: &str = "hpds_excerpts_ended_early_total{end=\"expired\"}";
const ABORTED: &str = "hpds_excerpts_ended_early_total{end=\"aborted\"}";
const IN_PROGRESS: &str = "hpds_excerpts_in_progress";

static ONE_AT_A_TIME: tokio::sync::Mutex<()> = tokio::sync::Mutex::const_new(());

struct Fixture {
    _dir: tempfile::TempDir,
    results: std::path::PathBuf,
    app: Router,
}

/// The four-partition fixture with a one-node `localDistributed` genomic store of 20,000 `G1`
/// variants, each carried by patient 197506, served with every excerpt spilled and the
/// settings of `adjust`.
fn fixture(adjust: impl FnOnce(&mut Config)) -> Fixture {
    let dir = tempfile::tempdir().unwrap();
    let store = build_store(dir.path());
    let patients: Vec<u32> = (197_506..197_516).chain(200_000..200_010).collect();
    let work = dir.path().join("vcf");
    std::fs::create_dir_all(&work).unwrap();
    let mut text = String::from(
        "##fileformat=VCFv4.3\n\
         ##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"The gene.\">\n\
         #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT",
    );
    for p in &patients {
        text.push_str(&format!("\tS{p}"));
    }
    text.push('\n');
    for i in 0..20_000u32 {
        text.push_str(&format!(
            "chr1\t{}\t.\tA\tG\t.\tPASS\tGene_with_variant=G1\tGT\t0|1",
            100 + i * 10
        ));
        for _ in 1..patients.len() {
            text.push_str("\t0|0");
        }
        text.push('\n');
    }
    let vcf = work.join("n0.vcf");
    std::fs::write(&vcf, text).unwrap();
    let ids = |prefix: &str| {
        patients
            .iter()
            .map(|p| format!("{prefix}{p}"))
            .collect::<Vec<_>>()
            .join(",")
    };
    let index = work.join("index.tsv");
    std::fs::write(
        &index,
        format!(
            "vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\n\
             {}\tchr1\t1\t0\t\"{}\"\t\"{}\"\n",
            vcf.display(),
            ids("S"),
            ids("")
        ),
    )
    .unwrap();
    let genomic = dir.path().join("genomic");
    genomic_load(&GenomicLoadOptions {
        mode: GenoMode::LocalDistributed,
        groups: vec![(String::new(), index)],
        out: genomic.clone(),
        key_file: store.join("encryption_key"),
        skip_missing_samples: false,
        threads: Some(2),
    })
    .unwrap();
    let mut config = config(dir.path());
    config.data_dir = store.clone();
    config.genomic.data_dir = genomic;
    config.genomic.processor = Some(GenoMode::LocalDistributed);
    config.genomic.variant.vcf_excerpt_enabled = true;
    config.engine_concurrency = 2;
    config.excerpt_concurrency = 1;
    config.excerpt_queue = Duration::ZERO;
    config.excerpt_spool_memory_bytes = 0;
    config.metrics = true;
    adjust(&mut config);
    let engine = Arc::try_unwrap(open_engine(&store, &config)).unwrap();
    let (engine, status) = attach_genomic(engine, &config);
    let state = AppState::with_genomic(Some(Arc::new(engine)), &config, status).unwrap();
    Fixture {
        results: config.results_dir.clone(),
        _dir: dir,
        app: router(state),
    }
}

fn query() -> Value {
    json!({
        "expectedResultType": "VCF_EXCERPT",
        "genomicFilters": [{"key": "Gene_with_variant", "values": ["G1"]}],
    })
}

/// The descriptors the process holds open.
fn open_descriptors() -> usize {
    std::fs::read_dir("/dev/fd").unwrap().count()
}

fn spill_files(dir: &Path) -> usize {
    std::fs::read_dir(dir)
        .unwrap()
        .filter(|e| {
            e.as_ref()
                .unwrap()
                .file_name()
                .to_string_lossy()
                .starts_with("excerpt-spool-")
        })
        .count()
}

async fn send_unread(app: &Router) -> axum::response::Response {
    use tower::ServiceExt;
    let request = axum::http::Request::post(SYNC)
        .header("content-type", "application/json")
        .body(axum::body::Body::from(envelope(query())))
        .unwrap();
    app.clone().oneshot(request).await.unwrap()
}

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
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    seen
}

/// Polls for up to five seconds until the process holds `want` descriptors, and returns the
/// count it read last.
async fn await_descriptors(want: usize) -> usize {
    let mut seen = 0;
    for _ in 0..50 {
        seen = open_descriptors();
        if seen == want {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    seen
}

/// Answers one excerpt in full, so every store file the excerpt path opens is open, and
/// returns its length and the descriptors held afterwards.
async fn warm(f: &Fixture) -> (usize, usize) {
    let whole = post(&f.app, SYNC, envelope(query())).await;
    assert_eq!(whole.status, StatusCode::OK, "{}", whole.body);
    assert!(whole.body.len() > 400_000, "{}", whole.body.len());
    assert_eq!(await_metric(&f.app, IN_PROGRESS, 0).await, 0);
    (whole.body.len(), open_descriptors())
}

#[tokio::test]
async fn a_spilled_excerpt_whose_client_stops_reading_releases_its_slot_and_spill_file() {
    let _one = ONE_AT_A_TIME.lock().await;
    let f = fixture(|c| c.excerpt_stall = Duration::from_secs(1));
    let (length, before) = warm(&f).await;
    let held = send_unread(&f.app).await;
    assert_eq!(held.status(), StatusCode::OK);
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 1);
    let started = Instant::now();
    assert_eq!(await_metric(&f.app, STALLED, 1).await, 1);
    assert!(started.elapsed() < Duration::from_secs(3));
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 0);
    assert_eq!(await_descriptors(before).await, before);
    assert_eq!(spill_files(&f.results), 0);
    let next = post(&f.app, SYNC, envelope(query())).await;
    assert_eq!(next.body.len(), length);
    drop(held);
}

#[tokio::test]
async fn a_spilled_excerpt_that_outlives_the_stream_bound_releases_its_slot_and_spill_file() {
    let _one = ONE_AT_A_TIME.lock().await;
    let f = fixture(|c| {
        c.excerpt_stall = Duration::from_secs(30);
        c.excerpt_stream = Duration::from_secs(1);
    });
    let (_, before) = warm(&f).await;
    let held = send_unread(&f.app).await;
    assert_eq!(held.status(), StatusCode::OK);
    tokio::time::sleep(Duration::from_millis(1500)).await;
    assert_eq!(await_metric(&f.app, EXPIRED, 1).await, 1);
    assert_eq!(metric(&f.app, STALLED).await, 0);
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 0);
    assert_eq!(await_descriptors(before).await, before);
    assert_eq!(spill_files(&f.results), 0);
    drop(held);
}

#[tokio::test]
async fn a_spilled_excerpt_whose_client_leaves_mid_stream_releases_its_slot_and_spill_file() {
    let _one = ONE_AT_A_TIME.lock().await;
    let f = fixture(|_| {});
    let (_, before) = warm(&f).await;
    let mut held = send_unread(&f.app).await;
    assert_eq!(held.status(), StatusCode::OK);
    let first = held.body_mut().frame().await.unwrap().unwrap();
    assert!(first.is_data());
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 1);
    assert!(
        open_descriptors() > before,
        "the spill file is open while it streams"
    );
    drop(held);
    assert_eq!(await_metric(&f.app, ABORTED, 1).await, 1);
    assert_eq!(metric(&f.app, IN_PROGRESS).await, 0);
    assert_eq!(await_descriptors(before).await, before);
    assert_eq!(spill_files(&f.results), 0);
}
