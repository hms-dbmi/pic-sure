//! The async query lifecycle: `POST /query`, `/query/{id}/status`, `/query/{id}/result` and
//! `/query/{id}/signed-url`, with a result registry bounded by count and age and the PATIENTS
//! and DATAFRAME CSV exports.
//!
//! Java keeps every result in an unbounded map and names it with a UUIDv5 of the query text.
//! Here a result is named with a random UUID (callers treat the id as opaque), finished results
//! are dropped after a maximum age or when the registry is full (oldest finished first, never
//! a pending or running one), and each export is a file in the results directory, removed
//! with its registry entry.

use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::body::{Body, Bytes};
use axum::extract::{Path as PathParam, State};
use axum::http::StatusCode;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE};
use axum::response::{IntoResponse, Response};
use hpds_query::{Engine, PATIENTS_HEADER, Query, QueryError, ResultType, check_supported};
use serde::Serialize;
use tokio::io::AsyncReadExt;
use tokio::sync::Semaphore;

use crate::config::Config;
use crate::envelope::{canonical_uuid, random_uuid, read_query};
use crate::error::{ApiError, SERVER_ERROR_MESSAGE};
use crate::state::AppState;

/// Content type of a finished export, as Java's `CsvWriter.getResponseType` gives it.
pub const RESULT_CONTENT_TYPE: &str = "text/plain";

const READ_CHUNK_BYTES: usize = 64 * 1024;

/// Where an async query is in its lifecycle. The names are Java's `AsyncResult.Status`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum JobState {
    /// Waiting for a slot on its pool.
    Pending,
    /// Being computed.
    Running,
    /// Finished; the export is `file`, `size` bytes long.
    Success {
        /// The export file.
        file: PathBuf,
        /// Its length in bytes.
        size: u64,
    },
    /// Failed with `message`, which names the problem without echoing store data.
    Error {
        /// What went wrong.
        message: String,
    },
}

impl JobState {
    /// The `AsyncResult.Status` name: `PENDING`, `RUNNING`, `SUCCESS` or `ERROR`.
    pub fn name(&self) -> &'static str {
        match self {
            JobState::Pending => "PENDING",
            JobState::Running => "RUNNING",
            JobState::Success { .. } => "SUCCESS",
            JobState::Error { .. } => "ERROR",
        }
    }

    /// The `PicSureStatus` Java maps the state to: PENDING is QUEUED, RUNNING is PENDING,
    /// SUCCESS is AVAILABLE and ERROR is ERROR.
    pub fn picsure_status(&self) -> &'static str {
        match self {
            JobState::Pending => "QUEUED",
            JobState::Running => "PENDING",
            JobState::Success { .. } => "AVAILABLE",
            JobState::Error { .. } => "ERROR",
        }
    }

    fn is_finished(&self) -> bool {
        matches!(self, JobState::Success { .. } | JobState::Error { .. })
    }
}

/// `QueryStatus` as Java's Jackson writes it: every field in declaration order, nulls
/// included.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct QueryStatus {
    /// The `PicSureStatus`.
    pub status: &'static str,
    /// Always null; HPDS never sets it.
    #[serde(rename = "resourceID")]
    pub resource_id: Option<String>,
    /// The `AsyncResult.Status` name.
    pub resource_status: &'static str,
    /// Always null; the query service sets it.
    pub picsure_result_id: Option<String>,
    /// The result id the status, result and signed-url routes take.
    pub resource_result_id: String,
    /// `picsureQueryId`, plus `error` for a failed query.
    pub result_metadata: ResultMetadata,
    /// The export's length in bytes once it succeeded, 0 before.
    pub size_in_bytes: u64,
    /// When the query was queued, in epoch milliseconds.
    pub start_time: u64,
    /// Queued to finished in milliseconds, 0 until finished.
    pub duration: u64,
    /// Always 0, as in Java.
    pub expiration: u64,
}

/// The `resultMetadata` map of a [`QueryStatus`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResultMetadata {
    /// The query's own `id`, or a random UUID when it had none (Java's `Query.generateId`).
    pub picsure_query_id: String,
    /// Why the query failed. Java has no such key; it is present only on ERROR.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

#[derive(Debug, Clone)]
struct Job {
    picsure_query_id: String,
    queued_ms: u64,
    completed_ms: u64,
    state: JobState,
}

impl Job {
    fn status(&self, id: &str) -> QueryStatus {
        let size = match &self.state {
            JobState::Success { size, .. } => *size,
            _ => 0,
        };
        let error = match &self.state {
            JobState::Error { message } => Some(message.clone()),
            _ => None,
        };
        QueryStatus {
            status: self.state.picsure_status(),
            resource_id: None,
            resource_status: self.state.name(),
            picsure_result_id: None,
            resource_result_id: id.to_owned(),
            result_metadata: ResultMetadata {
                picsure_query_id: self.picsure_query_id.clone(),
                error,
            },
            size_in_bytes: size,
            start_time: self.queued_ms,
            duration: if self.completed_ms == 0 {
                0
            } else {
                self.completed_ms.saturating_sub(self.queued_ms)
            },
            expiration: 0,
        }
    }
}

type Registry = HashMap<String, Job>;

/// The registry of async queries and the two pools that run exports.
///
/// An export with more `select` paths than `SMALL_JOB_LIMIT` runs on the large pool, the
/// others on the small pool, each pool running at most its configured number of exports at
/// once. The computation runs on tokio's blocking pool.
pub struct Jobs {
    registry: Arc<Mutex<Registry>>,
    small: Arc<Semaphore>,
    large: Arc<Semaphore>,
    exports: Arc<Semaphore>,
    engine_slots: Arc<Semaphore>,
    small_job_limit: usize,
    results_dir: PathBuf,
    limit: usize,
    max_age: Duration,
}

impl Jobs {
    /// An empty registry with the pool sizes, bounds and results directory of `config`. Every
    /// export also holds one of `engine_slots` while it computes. Creates the results directory with mode 0700 and removes export files a previous
    /// process left in it.
    ///
    /// # Errors
    /// The I/O error when the directory cannot be created, restricted or cleaned.
    pub fn new(config: &Config, engine_slots: Arc<Semaphore>) -> io::Result<Self> {
        prepare_results_dir(&config.results_dir)?;
        Ok(Jobs {
            registry: Arc::new(Mutex::new(HashMap::new())),
            small: Arc::new(Semaphore::new(config.small_task_threads)),
            large: Arc::new(Semaphore::new(config.large_task_threads)),
            exports: Arc::new(Semaphore::new(config.export_concurrency)),
            engine_slots,
            small_job_limit: config.small_job_limit,
            results_dir: config.results_dir.clone(),
            limit: config.result_limit,
            max_age: config.result_max_age,
        })
    }

    /// Registers `query` and starts its export. DATAFRAME_TIMESERIES and DATAFRAME_PFB are
    /// registered already failed, since this engine does not produce them. Returns the
    /// status right after registration.
    ///
    /// # Errors
    /// A 503 [`ApiError`] when the registry is full of pending and running queries.
    pub fn submit(&self, engine: Arc<Engine>, query: Query) -> Result<QueryStatus, ApiError> {
        let id = random_uuid();
        let now = now_ms();
        let refusal = match query.expected_result_type {
            ResultType::DataframeTimeseries | ResultType::DataframePfb => Some(format!(
                "{} exports are not supported by this phenotype-only HPDS",
                query.expected_result_type
            )),
            _ => None,
        };
        let job = Job {
            picsure_query_id: query.id.clone().unwrap_or_else(random_uuid),
            queued_ms: now,
            completed_ms: if refusal.is_some() { now } else { 0 },
            state: match &refusal {
                Some(message) => JobState::Error {
                    message: message.clone(),
                },
                None => JobState::Pending,
            },
        };
        let status = job.status(&id);
        {
            let mut registry = lock(&self.registry);
            self.make_room(&mut registry, now)?;
            registry.insert(id.clone(), job);
        }
        if refusal.is_none() {
            self.spawn_export(id, engine, query);
        }
        Ok(status)
    }

    fn spawn_export(&self, id: String, engine: Arc<Engine>, query: Query) {
        let pool = if query.select.len() > self.small_job_limit {
            Arc::clone(&self.large)
        } else {
            Arc::clone(&self.small)
        };
        let exports = Arc::clone(&self.exports);
        let engine_slots = Arc::clone(&self.engine_slots);
        let registry = Arc::clone(&self.registry);
        let file = self.results_dir.join(format!("result-{id}.csv"));
        tokio::spawn(async move {
            let (Ok(_pool_slot), Ok(_export_slot), Ok(_engine_slot)) = (
                pool.acquire_owned().await,
                exports.acquire_owned().await,
                engine_slots.acquire_owned().await,
            ) else {
                return;
            };
            set_state(&registry, &id, JobState::Running);
            let target = file.clone();
            let outcome = engine
                .run_blocking(move |e| Ok(write_export(e, &query, &target)))
                .await
                .unwrap_or_else(|e| Err(ExportError::Query(e)));
            let state = match outcome {
                Ok(size) => JobState::Success { file, size },
                Err(error) => {
                    remove_file(&file);
                    JobState::Error {
                        message: error.public_message(&id),
                    }
                }
            };
            finish(&registry, &id, state);
        });
    }

    /// The status of result `id`, or `None` when the registry does not hold it.
    pub fn status(&self, id: &str) -> Option<QueryStatus> {
        lock(&self.registry).get(id).map(|job| job.status(id))
    }

    /// The finished export of result `id`, opened, and its length.
    ///
    /// # Errors
    /// [`ResultError::Unknown`] when the registry does not hold `id`, and
    /// [`ResultError::NotReady`] with the state name when the query has not succeeded.
    pub fn open_result(&self, id: &str) -> Result<(File, u64), ResultError> {
        let registry = lock(&self.registry);
        let job = registry.get(id).ok_or(ResultError::Unknown)?;
        match &job.state {
            JobState::Success { file, size } => File::open(file)
                .map(|f| (f, *size))
                .map_err(ResultError::Io),
            other => Err(ResultError::NotReady(other.name())),
        }
    }

    /// Number of results held.
    pub fn len(&self) -> usize {
        lock(&self.registry).len()
    }

    /// True when no result is held.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Drops every finished result older than the maximum age, with its file.
    pub fn sweep(&self) {
        expire(&mut lock(&self.registry), now_ms(), self.max_age);
    }

    fn make_room(&self, registry: &mut Registry, now: u64) -> Result<(), ApiError> {
        expire(registry, now, self.max_age);
        while registry.len() >= self.limit {
            let oldest = registry
                .iter()
                .filter(|(_, job)| job.state.is_finished())
                .min_by_key(|(_, job)| job.completed_ms)
                .map(|(id, _)| id.clone());
            match oldest {
                Some(id) => evict(registry, &id),
                None => {
                    return Err(ApiError::unavailable(
                        "too many queries are pending or running; try again later",
                    ));
                }
            }
        }
        Ok(())
    }
}

/// Why a result cannot be streamed.
#[derive(Debug)]
pub enum ResultError {
    /// The registry does not hold the id.
    Unknown,
    /// The query has not succeeded; carries its state name.
    NotReady(&'static str),
    /// The export file could not be opened.
    Io(io::Error),
}

#[derive(Debug)]
enum ExportError {
    Query(QueryError),
    Io(io::Error),
}

impl From<QueryError> for ExportError {
    fn from(error: QueryError) -> Self {
        ExportError::Query(error)
    }
}

impl From<io::Error> for ExportError {
    fn from(error: io::Error) -> Self {
        ExportError::Io(error)
    }
}

impl ExportError {
    fn public_message(&self, id: &str) -> String {
        match self {
            ExportError::Query(error) if error.is_client_error() => error.to_string(),
            ExportError::Query(error) => {
                tracing::error!(%error, result = id, "export failed");
                SERVER_ERROR_MESSAGE.to_owned()
            }
            ExportError::Io(error) => {
                tracing::error!(%error, result = id, "export could not be written");
                SERVER_ERROR_MESSAGE.to_owned()
            }
        }
    }
}

fn lock(registry: &Mutex<Registry>) -> MutexGuard<'_, Registry> {
    registry
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn set_state(registry: &Mutex<Registry>, id: &str, state: JobState) {
    if let Some(job) = lock(registry).get_mut(id) {
        job.state = state;
    }
}

fn finish(registry: &Mutex<Registry>, id: &str, state: JobState) {
    let mut registry = lock(registry);
    match registry.get_mut(id) {
        Some(job) => {
            job.state = state;
            job.completed_ms = now_ms().max(1);
        }
        None => {
            if let JobState::Success { file, .. } = state {
                remove_file(&file);
            }
        }
    }
}

fn expire(registry: &mut Registry, now: u64, max_age: Duration) {
    let max_age_ms = u64::try_from(max_age.as_millis()).unwrap_or(u64::MAX);
    let expired: Vec<String> = registry
        .iter()
        .filter(|(_, job)| {
            job.state.is_finished() && now.saturating_sub(job.completed_ms) >= max_age_ms
        })
        .map(|(id, _)| id.clone())
        .collect();
    for id in expired {
        evict(registry, &id);
    }
}

fn evict(registry: &mut Registry, id: &str) {
    if let Some(Job {
        state: JobState::Success { file, .. },
        ..
    }) = registry.remove(id)
    {
        remove_file(&file);
    }
}

fn remove_file(file: &Path) {
    match std::fs::remove_file(file) {
        Ok(()) => {}
        Err(e) if e.kind() == io::ErrorKind::NotFound => {}
        Err(error) => tracing::warn!(%error, file = %file.display(), "cannot remove result file"),
    }
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
        .unwrap_or(0)
}

fn prepare_results_dir(dir: &Path) -> io::Result<()> {
    std::fs::create_dir_all(dir)?;
    std::fs::set_permissions(dir, std::fs::Permissions::from_mode(0o700))?;
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with("result-") && name.ends_with(".csv") {
            std::fs::remove_file(entry.path())?;
        }
    }
    Ok(())
}

/// Computes the export of `query` and writes it to a new file at `path` with mode 0600.
/// Runs on a blocking thread: DATAFRAME rows are produced and rendered there, one row at a
/// time into a reused buffer, never on the async runtime. Returns the file's length. The file is created only after the cohort is computed, so a
/// query that fails to evaluate leaves nothing behind.
fn write_export(engine: &Engine, query: &Query, path: &Path) -> Result<u64, ExportError> {
    match query.expected_result_type {
        ResultType::Patients => {
            let cohort = engine.patients(query)?;
            let mut out = create_result_file(path)?;
            write_csv_row(&mut out, [PATIENTS_HEADER])?;
            for patient in &cohort {
                writeln!(out, "{patient}")?;
            }
            close_result_file(out)
        }
        ResultType::Dataframe => {
            let frame = engine.dataframe(query)?;
            let mut out = create_result_file(path)?;
            let mut line = Vec::with_capacity(4096);
            write_csv_row(&mut out, &frame.header)?;
            for row in frame.rows {
                line.clear();
                write_csv_row(&mut line, &row)?;
                out.write_all(&line)?;
            }
            close_result_file(out)
        }
        other => Err(ExportError::Query(QueryError::NotSync(other))),
    }
}

fn create_result_file(path: &Path) -> io::Result<BufWriter<File>> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)?;
    Ok(BufWriter::with_capacity(1 << 20, file))
}

fn close_result_file(out: BufWriter<File>) -> Result<u64, ExportError> {
    let file = out.into_inner().map_err(|e| e.into_error())?;
    Ok(file.metadata()?.len())
}

/// Writes one CSV row as FastCSV 1.0's `CsvWriter` does with its defaults, which Java's
/// `CsvWriter` uses: fields separated by `,`, a field quoted only when it holds `"`, `,`, CR
/// or LF, with `"` doubled inside quotes, and the row ended by `\n`.
///
/// # Errors
/// The I/O error of the writer.
pub fn write_csv_row<W: Write, S: AsRef<str>>(
    out: &mut W,
    fields: impl IntoIterator<Item = S>,
) -> io::Result<()> {
    for (i, field) in fields.into_iter().enumerate() {
        if i > 0 {
            out.write_all(b",")?;
        }
        let field = field.as_ref();
        if field.contains(['"', ',', '\r', '\n']) {
            out.write_all(b"\"")?;
            out.write_all(field.replace('"', "\"\"").as_bytes())?;
            out.write_all(b"\"")?;
        } else {
            out.write_all(field.as_bytes())?;
        }
    }
    out.write_all(b"\n")
}

fn parse_id(id: &str) -> Result<String, ApiError> {
    canonical_uuid(id).ok_or_else(|| ApiError::bad_request("the query id must be a UUID"))
}

fn unknown_id() -> ApiError {
    ApiError::not_found("no query with this id is held")
}

fn status_text(name: &str) -> Response {
    (
        StatusCode::BAD_REQUEST,
        [(CONTENT_TYPE, "application/json")],
        format!("Status : {name}"),
    )
        .into_response()
}

/// `POST /query`: validates an export query and starts it, answering its [`QueryStatus`].
///
/// PATIENTS and DATAFRAME are exported; DATAFRAME_TIMESERIES and DATAFRAME_PFB are accepted
/// and end in ERROR. Any other result type, a validation failure (with
/// `hpds.requireAuthorizationFilter` from the configuration), or a malformed request is a
/// 400 JSON error, where Java answers 200 with an ERROR status or 500.
pub async fn submit(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let query = read_query(&body)?;
    match query.expected_result_type {
        ResultType::Patients
        | ResultType::Dataframe
        | ResultType::DataframeTimeseries
        | ResultType::DataframePfb => {}
        other if other.is_genomic() => {
            check_supported(&query)?;
        }
        other => {
            return Err(ApiError::bad_request(format!(
                "Result type {other} is computed synchronously. Submit it with POST /query/sync."
            )));
        }
    }
    let engine = Arc::clone(state.engine()?);
    engine.validate(&query, state.async_validator())?;
    let status = state.jobs().submit(engine, query)?;
    Ok(axum::Json(status).into_response())
}

/// `POST /query/{id}/status`: the [`QueryStatus`] of a held result, or 404 JSON for an id
/// the registry does not hold (Java fails with a `NullPointerException`).
pub async fn status(
    State(state): State<AppState>,
    PathParam(id): PathParam<String>,
) -> Result<Response, ApiError> {
    let id = parse_id(&id)?;
    let status = state.jobs().status(&id).ok_or_else(unknown_id)?;
    Ok(axum::Json(status).into_response())
}

/// `POST /query/{id}/result`: streams a finished export as `text/plain`. An unknown id is a
/// 404 JSON error; a query that has not succeeded is a 400 with Java's body
/// `Status : <state>` and Java's `application/json` content type.
pub async fn result(
    State(state): State<AppState>,
    PathParam(id): PathParam<String>,
) -> Result<Response, ApiError> {
    let id = parse_id(&id)?;
    let (file, size) = match state.jobs().open_result(&id) {
        Ok(opened) => opened,
        Err(ResultError::Unknown) => return Err(unknown_id()),
        Err(ResultError::NotReady(name)) => return Ok(status_text(name)),
        Err(ResultError::Io(error)) => {
            tracing::error!(%error, result = %id, "cannot open result file");
            return Err(ApiError::internal());
        }
    };
    let file = tokio::fs::File::from_std(file);
    let chunks = futures_util::stream::unfold(Some(file), |file| async move {
        let mut file = file?;
        let mut buffer = vec![0u8; READ_CHUNK_BYTES];
        match file.read(&mut buffer).await {
            Ok(0) => None,
            Ok(read) => {
                buffer.truncate(read);
                Some((Ok(Bytes::from(buffer)), Some(file)))
            }
            Err(error) => Some((Err::<Bytes, io::Error>(error), None)),
        }
    });
    Ok((
        [
            (CONTENT_TYPE, RESULT_CONTENT_TYPE.to_owned()),
            (CONTENT_LENGTH, size.to_string()),
        ],
        Body::from_stream(chunks),
    )
        .into_response())
}

/// `POST /query/{id}/signed-url`: S3 upload is out of scope, so a held id is a 400 JSON error
/// and an unknown one a 404.
pub async fn signed_url(
    State(state): State<AppState>,
    PathParam(id): PathParam<String>,
) -> Result<Response, ApiError> {
    let id = parse_id(&id)?;
    state.jobs().status(&id).ok_or_else(unknown_id)?;
    Err(ApiError::bad_request(
        "signed URLs are not supported: this HPDS does not upload results to S3",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn csv_rows_quote_like_fastcsv() {
        let mut out = Vec::new();
        write_csv_row(
            &mut out,
            ["a", "b,c", "say \"hi\"", "tab\there", "", "x\ny"],
        )
        .unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            "a,\"b,c\",\"say \"\"hi\"\"\",tab\there,,\"x\ny\"\n"
        );
    }

    #[test]
    fn states_map_to_java_names() {
        let states = [
            (JobState::Pending, "PENDING", "QUEUED"),
            (JobState::Running, "RUNNING", "PENDING"),
            (
                JobState::Success {
                    file: PathBuf::new(),
                    size: 0,
                },
                "SUCCESS",
                "AVAILABLE",
            ),
            (
                JobState::Error {
                    message: String::new(),
                },
                "ERROR",
                "ERROR",
            ),
        ];
        for (state, name, picsure) in states {
            assert_eq!((state.name(), state.picsure_status()), (name, picsure));
        }
    }
}
