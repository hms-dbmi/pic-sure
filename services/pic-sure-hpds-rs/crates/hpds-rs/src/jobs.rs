//! The async query lifecycle: `POST /query`, `/query/{id}/status`, `/query/{id}/result` and
//! `/query/{id}/signed-url`, with a result registry bounded by count, bytes and optionally age,
//! and the PATIENTS and DATAFRAME CSV exports.
//!
//! Java keeps every result in an unbounded map and names it with a UUIDv5 of the query text.
//! Here a result is named with a random UUID (callers treat the id as opaque), finished results
//! are dropped after a maximum age, when the registry is full, or when the export files pass
//! the byte cap (oldest finished first, never a pending or running one), and each export is a
//! file in the results directory, removed with its registry entry. Files are removed after the
//! registry lock is released.

use std::collections::HashMap;
use std::fs::{DirBuilder, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::os::unix::fs::{DirBuilderExt, MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use axum::body::{Body, Bytes};
use axum::extract::{Path as PathParam, State};
use axum::http::StatusCode;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE};
use axum::response::{IntoResponse, Response};
use hpds_query::{
    Engine, PATIENTS_HEADER, Query, QueryError, ResultType, RowBuffer, check_supported,
};
use serde::Serialize;
use tokio::io::AsyncReadExt;
use tokio::sync::Semaphore;

use crate::config::Config;
use crate::envelope::{canonical_uuid, random_uuid, read_query};
use crate::error::{ApiError, SERVER_ERROR_MESSAGE};
use crate::state::AppState;

/// Content type of a finished export, as Java's `CsvWriter.getResponseType` gives it.
pub const RESULT_CONTENT_TYPE: &str = "text/plain";

/// Message of an export that is refused because Java's `ID_BATCH_SIZE` is 0.
pub const EXPORTS_DISABLED_MESSAGE: &str = "exports are disabled (ID_BATCH_SIZE=0)";

const READ_CHUNK_BYTES: usize = 64 * 1024;

/// Where an async query is in its lifecycle. The names are Java's `AsyncResult.Status`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResultState {
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

impl ResultState {
    /// The `AsyncResult.Status` name: `PENDING`, `RUNNING`, `SUCCESS` or `ERROR`.
    pub fn name(&self) -> &'static str {
        match self {
            ResultState::Pending => "PENDING",
            ResultState::Running => "RUNNING",
            ResultState::Success { .. } => "SUCCESS",
            ResultState::Error { .. } => "ERROR",
        }
    }

    /// The `PicSureStatus` Java maps the state to: PENDING is QUEUED, RUNNING is PENDING,
    /// SUCCESS is AVAILABLE and ERROR is ERROR.
    pub fn picsure_status(&self) -> &'static str {
        match self {
            ResultState::Pending => "QUEUED",
            ResultState::Running => "PENDING",
            ResultState::Success { .. } => "AVAILABLE",
            ResultState::Error { .. } => "ERROR",
        }
    }

    fn is_finished(&self) -> bool {
        matches!(
            self,
            ResultState::Success { .. } | ResultState::Error { .. }
        )
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
struct StoredResult {
    picsure_query_id: String,
    queued_ms: u64,
    completed_ms: u64,
    state: ResultState,
    reserved: u64,
}

impl StoredResult {
    fn status(&self, id: &str) -> QueryStatus {
        let size = match &self.state {
            ResultState::Success { size, .. } => *size,
            _ => 0,
        };
        let error = match &self.state {
            ResultState::Error { message } => Some(message.clone()),
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

type Entries = HashMap<String, StoredResult>;

/// The registry of async query results and the two pools that run exports.
///
/// An export with more `select` paths than `SMALL_JOB_LIMIT` runs on the large pool, the
/// others on the small pool, each pool running at most its configured number of exports at
/// once. The computation runs on tokio's blocking pool.
pub struct ResultRegistry {
    entries: Arc<Mutex<Entries>>,
    small: Arc<Semaphore>,
    large: Arc<Semaphore>,
    exports: Arc<Semaphore>,
    engine_slots: Arc<Semaphore>,
    small_job_limit: usize,
    results_dir: PathBuf,
    limit: usize,
    max_age: Option<Duration>,
    max_bytes: u64,
    max_file_bytes: u64,
    id_batch_size: u64,
}

impl ResultRegistry {
    /// An empty registry with the pool sizes, bounds and results directory of `config`. Every
    /// export also holds one of `engine_slots` while it computes.
    ///
    /// A results directory that does not exist is created with mode 0700. An existing one is
    /// used only when it is a real directory (not a symbolic link) owned by the current user
    /// with no group or other write bit, and its permissions are left as they are. The
    /// directory must not be the data directory or lie inside it. Export files a previous
    /// process left in it are removed.
    ///
    /// # Errors
    /// The I/O error when the directory cannot be created, fails these checks, or cannot be
    /// cleaned.
    pub fn new(config: &Config, engine_slots: Arc<Semaphore>) -> io::Result<Self> {
        prepare_results_dir(&config.results_dir, &config.data_dir)?;
        Ok(ResultRegistry {
            entries: Arc::new(Mutex::new(HashMap::new())),
            small: Arc::new(Semaphore::new(config.small_task_threads)),
            large: Arc::new(Semaphore::new(config.large_task_threads)),
            exports: Arc::new(Semaphore::new(config.export_concurrency)),
            engine_slots,
            small_job_limit: config.small_job_limit,
            results_dir: config.results_dir.clone(),
            limit: config.result_limit,
            max_age: config.result_max_age,
            max_bytes: config.result_max_bytes,
            max_file_bytes: config.result_max_file_bytes,
            id_batch_size: config.id_batch_size,
        })
    }

    /// Registers `query` and starts its export. DATAFRAME_TIMESERIES and DATAFRAME_PFB are
    /// registered already failed, since this engine does not produce them, and so is
    /// DATAFRAME while `ID_BATCH_SIZE` is 0. Returns the status right after registration.
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
            ResultType::Dataframe if self.id_batch_size == 0 => {
                Some(EXPORTS_DISABLED_MESSAGE.to_owned())
            }
            _ => None,
        };
        let entry = StoredResult {
            picsure_query_id: query.id.clone().unwrap_or_else(random_uuid),
            queued_ms: now,
            completed_ms: if refusal.is_some() { now } else { 0 },
            reserved: 0,
            state: match &refusal {
                Some(message) => ResultState::Error {
                    message: message.clone(),
                },
                None => ResultState::Pending,
            },
        };
        let status = entry.status(&id);
        let mut removed = Vec::new();
        let admitted = {
            let mut entries = lock(&self.entries);
            let admitted = self.make_room(&mut entries, now, &mut removed);
            if admitted.is_ok() {
                entries.insert(id.clone(), entry);
            }
            admitted
        };
        remove_files(&removed);
        admitted?;
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
        let entries = Arc::clone(&self.entries);
        let max_bytes = self.max_bytes;
        let max_file_bytes = self.max_file_bytes;
        let quota = Quota {
            entries: Arc::clone(&self.entries),
            id: id.clone(),
            max_bytes,
        };
        let file = self.results_dir.join(format!("result-{id}.csv"));
        tokio::spawn(async move {
            let (Ok(_pool_slot), Ok(_export_slot), Ok(_engine_slot)) = (
                pool.acquire_owned().await,
                exports.acquire_owned().await,
                engine_slots.acquire_owned().await,
            ) else {
                return;
            };
            set_state(&entries, &id, ResultState::Running);
            let target = file.clone();
            let outcome = engine
                .run_blocking(move |e| Ok(write_export(e, &query, &target, max_file_bytes, &quota)))
                .await
                .unwrap_or_else(|e| Err(ExportError::Query(e)));
            let state = match outcome {
                Ok(size) => ResultState::Success { file, size },
                Err(error) => {
                    remove_file(&file);
                    ResultState::Error {
                        message: error.public_message(&id),
                    }
                }
            };
            finish(&entries, &id, state, max_bytes);
        });
    }

    /// The status of result `id`, or `None` when the registry does not hold it.
    pub fn status(&self, id: &str) -> Option<QueryStatus> {
        lock(&self.entries).get(id).map(|entry| entry.status(id))
    }

    /// The finished export of result `id`, opened, and its length.
    ///
    /// # Errors
    /// [`ResultError::Unknown`] when the registry does not hold `id`, and
    /// [`ResultError::NotReady`] with the state name when the query has not succeeded.
    pub fn open_result(&self, id: &str) -> Result<(File, u64), ResultError> {
        let (file, size) = {
            let entries = lock(&self.entries);
            let entry = entries.get(id).ok_or(ResultError::Unknown)?;
            match &entry.state {
                ResultState::Success { file, size } => (file.clone(), *size),
                other => return Err(ResultError::NotReady(other.name())),
            }
        };
        File::open(file).map(|f| (f, size)).map_err(ResultError::Io)
    }

    /// Number of results held.
    pub fn len(&self) -> usize {
        lock(&self.entries).len()
    }

    /// True when no result is held.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Drops every finished result older than the maximum age, with its file. Does nothing
    /// when results have no age limit.
    pub fn sweep(&self) {
        let removed = expire(&mut lock(&self.entries), now_ms(), self.max_age);
        remove_files(&removed);
    }

    fn make_room(
        &self,
        entries: &mut Entries,
        now: u64,
        removed: &mut Vec<PathBuf>,
    ) -> Result<(), ApiError> {
        removed.extend(expire(entries, now, self.max_age));
        removed.extend(enforce_byte_cap(entries, self.max_bytes, None));
        while entries.len() >= self.limit {
            match oldest_finished(entries, None) {
                Some(id) => removed.extend(evict(entries, &id)),
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
    TooLarge { limit: u64 },
    StorageFull,
    Io(io::Error),
}

impl From<QueryError> for ExportError {
    fn from(error: QueryError) -> Self {
        ExportError::Query(error)
    }
}

impl From<io::Error> for ExportError {
    fn from(error: io::Error) -> Self {
        let inner = error.get_ref();
        if let Some(FileTooLarge { limit }) = inner.and_then(|e| e.downcast_ref::<FileTooLarge>()) {
            ExportError::TooLarge { limit: *limit }
        } else if inner.is_some_and(|e| e.is::<StorageFull>()) {
            ExportError::StorageFull
        } else {
            ExportError::Io(error)
        }
    }
}

#[derive(Debug)]
struct FileTooLarge {
    limit: u64,
}

impl std::fmt::Display for FileTooLarge {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "export file exceeds {} bytes", self.limit)
    }
}

impl std::error::Error for FileTooLarge {}

#[derive(Debug)]
struct StorageFull;

impl std::fmt::Display for StorageFull {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "no room left in the result store")
    }
}

impl std::error::Error for StorageFull {}

/// An export's claim on the result store's byte cap. Each write reserves its bytes in the
/// registry before they reach the file, so exports still being written count against
/// `HPDS_RESULT_MAX_BYTES` alongside the finished ones. A reservation that does not fit
/// evicts the oldest finished results first. It then fails unless this is the only export
/// holding reserved bytes, which keeps a lone result larger than the cap, as a finished one
/// is kept.
struct Quota {
    entries: Arc<Mutex<Entries>>,
    id: String,
    max_bytes: u64,
}

impl Quota {
    fn reserve(&self, len: u64) -> io::Result<()> {
        let mut removed = Vec::new();
        let granted = {
            let mut entries = lock(&self.entries);
            loop {
                let fits = held_bytes(&entries).saturating_add(len) <= self.max_bytes;
                if !fits {
                    if let Some(id) = oldest_finished(&entries, None) {
                        removed.extend(evict(&mut entries, &id));
                        continue;
                    }
                    let lone = !entries.iter().any(|(id, entry)| {
                        id != &self.id && !entry.state.is_finished() && entry.reserved > 0
                    });
                    if !lone {
                        break false;
                    }
                }
                if let Some(entry) = entries.get_mut(&self.id) {
                    entry.reserved = entry.reserved.saturating_add(len);
                }
                break true;
            }
        };
        remove_files(&removed);
        if granted {
            Ok(())
        } else {
            Err(io::Error::other(StorageFull))
        }
    }
}

/// A file that refuses any write that would take it past `limit` bytes, counting every byte
/// that reaches the file, header included.
struct LimitedFile<'a> {
    file: File,
    written: u64,
    limit: u64,
    quota: &'a Quota,
}

impl Write for LimitedFile<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let next = self.written.saturating_add(buf.len() as u64);
        if next > self.limit {
            return Err(io::Error::other(FileTooLarge { limit: self.limit }));
        }
        self.quota.reserve(buf.len() as u64)?;
        self.file.write_all(buf)?;
        self.written = next;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
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
            ExportError::StorageFull => "the result store has no room for this export while \
                 other exports are running (HPDS_RESULT_MAX_BYTES); try again later"
                .to_owned(),
            ExportError::TooLarge { limit } => format!(
                "the export is larger than the maximum result file size of {limit} bytes \
                 (HPDS_RESULT_MAX_FILE_BYTES); narrow the cohort or select fewer concepts"
            ),
            ExportError::Io(error) => {
                tracing::error!(%error, result = id, "export could not be written");
                SERVER_ERROR_MESSAGE.to_owned()
            }
        }
    }
}

fn lock(entries: &Mutex<Entries>) -> MutexGuard<'_, Entries> {
    entries
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn set_state(entries: &Mutex<Entries>, id: &str, state: ResultState) {
    if let Some(entry) = lock(entries).get_mut(id) {
        entry.state = state;
    }
}

fn finish(entries: &Mutex<Entries>, id: &str, state: ResultState, max_bytes: u64) {
    let removed = {
        let mut entries = lock(entries);
        match entries.get_mut(id) {
            Some(entry) => {
                entry.state = state;
                entry.reserved = 0;
                entry.completed_ms = now_ms().max(1);
                enforce_byte_cap(&mut entries, max_bytes, Some(id))
            }
            None => match state {
                ResultState::Success { file, .. } => vec![file],
                _ => Vec::new(),
            },
        }
    };
    remove_files(&removed);
}

fn oldest_finished(entries: &Entries, keep: Option<&str>) -> Option<String> {
    entries
        .iter()
        .filter(|(id, entry)| entry.state.is_finished() && Some(id.as_str()) != keep)
        .min_by_key(|(_, entry)| entry.completed_ms)
        .map(|(id, _)| id.clone())
}

fn held_bytes(entries: &Entries) -> u64 {
    entries
        .values()
        .map(|entry| match &entry.state {
            ResultState::Success { size, .. } => *size,
            ResultState::Pending | ResultState::Running => entry.reserved,
            ResultState::Error { .. } => 0,
        })
        .fold(0, u64::saturating_add)
}

fn enforce_byte_cap(entries: &mut Entries, max_bytes: u64, keep: Option<&str>) -> Vec<PathBuf> {
    let mut removed = Vec::new();
    while held_bytes(entries) > max_bytes {
        match oldest_finished(entries, keep) {
            Some(id) => removed.extend(evict(entries, &id)),
            None => break,
        }
    }
    removed
}

fn expire(entries: &mut Entries, now: u64, max_age: Option<Duration>) -> Vec<PathBuf> {
    let Some(max_age) = max_age else {
        return Vec::new();
    };
    let max_age_ms = u64::try_from(max_age.as_millis()).unwrap_or(u64::MAX);
    let expired: Vec<String> = entries
        .iter()
        .filter(|(_, entry)| {
            entry.state.is_finished() && now.saturating_sub(entry.completed_ms) >= max_age_ms
        })
        .map(|(id, _)| id.clone())
        .collect();
    expired.iter().filter_map(|id| evict(entries, id)).collect()
}

fn evict(entries: &mut Entries, id: &str) -> Option<PathBuf> {
    match entries.remove(id) {
        Some(StoredResult {
            state: ResultState::Success { file, .. },
            ..
        }) => Some(file),
        _ => None,
    }
}

fn remove_files(files: &[PathBuf]) {
    for file in files {
        remove_file(file);
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

fn prepare_results_dir(dir: &Path, data_dir: &Path) -> io::Result<()> {
    let refuse = |message: String| io::Error::new(io::ErrorKind::PermissionDenied, message);
    match std::fs::symlink_metadata(dir) {
        Ok(meta) => {
            if !meta.file_type().is_dir() {
                return Err(refuse(format!(
                    "{} is not a directory (a symbolic link is refused)",
                    dir.display()
                )));
            }
            let owner = meta.uid();
            let current = current_uid();
            if owner != current {
                return Err(refuse(format!(
                    "{} is owned by uid {owner}, not the service user {current}",
                    dir.display()
                )));
            }
            if meta.mode() & 0o022 != 0 {
                return Err(refuse(format!(
                    "{} is writable by its group or others",
                    dir.display()
                )));
            }
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            if let Some(parent) = dir.parent() {
                std::fs::create_dir_all(parent)?;
            }
            DirBuilder::new().mode(0o700).create(dir)?;
        }
        Err(error) => return Err(error),
    }
    if resolve(dir)?.starts_with(resolve(data_dir)?) {
        return Err(refuse(format!(
            "{} must not be the data directory or inside it",
            dir.display()
        )));
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let name = entry.file_name();
        let name = name.to_string_lossy();
        if name.starts_with("result-") && name.ends_with(".csv") && entry.file_type()?.is_file() {
            std::fs::remove_file(entry.path())?;
        }
    }
    Ok(())
}

fn current_uid() -> u32 {
    unsafe { libc::geteuid() }
}

fn resolve(path: &Path) -> io::Result<PathBuf> {
    let absolute = std::path::absolute(path)?;
    let mut missing = Vec::new();
    let mut existing = absolute.as_path();
    loop {
        match existing.canonicalize() {
            Ok(mut resolved) => {
                resolved.extend(missing.iter().rev());
                return Ok(resolved);
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => {
                let (Some(parent), Some(name)) = (existing.parent(), existing.file_name()) else {
                    return Ok(absolute);
                };
                missing.push(name);
                existing = parent;
            }
            Err(error) => return Err(error),
        }
    }
}

/// Computes the export of `query` and writes it to a new file at `path` with mode 0600.
/// Runs on a blocking thread: DATAFRAME rows are produced and rendered there, one row at a
/// time into a reused buffer, never on the async runtime. Returns the file's length. The file
/// is created only after the cohort is computed, so a query that fails to evaluate leaves
/// nothing behind.
fn write_export(
    engine: &Engine,
    query: &Query,
    path: &Path,
    max_file_bytes: u64,
    quota: &Quota,
) -> Result<u64, ExportError> {
    match query.expected_result_type {
        ResultType::Patients => {
            let cohort = engine.patients(query)?;
            let mut out = create_result_file(path, max_file_bytes, quota)?;
            write_csv_row(&mut out, [PATIENTS_HEADER])?;
            for patient in &cohort {
                writeln!(out, "{patient}")?;
            }
            close_result_file(out)
        }
        ResultType::Dataframe => {
            let frame = engine.dataframe(query)?;
            let mut out = create_result_file(path, max_file_bytes, quota)?;
            write_csv_row(&mut out, &frame.header)?;
            let mut rows = frame.rows;
            let mut row = RowBuffer::new();
            while rows.next_into(&mut row) {
                write_csv_row(&mut out, row.fields())?;
            }
            close_result_file(out)
        }
        other => Err(ExportError::Query(QueryError::NotSync(other))),
    }
}

fn create_result_file<'a>(
    path: &Path,
    limit: u64,
    quota: &'a Quota,
) -> io::Result<BufWriter<LimitedFile<'a>>> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(path)?;
    Ok(BufWriter::with_capacity(
        1 << 20,
        LimitedFile {
            file,
            written: 0,
            limit,
            quota,
        },
    ))
}

fn close_result_file(out: BufWriter<LimitedFile<'_>>) -> Result<u64, ExportError> {
    let limited = out.into_inner().map_err(|e| e.into_error())?;
    Ok(limited.file.metadata()?.len())
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

fn not_ready_response(name: &str) -> Response {
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
/// and end in ERROR, as does DATAFRAME while `ID_BATCH_SIZE` is 0. Any other result type, a
/// validation failure, or a malformed request is a 400 text error, where Java answers 200 with
/// an ERROR status or 500. Java accepts COUNT, CATEGORICAL_CROSS_COUNT and
/// CONTINUOUS_CROSS_COUNT here and COUNT then ends in ERROR; this service refuses all sync
/// types up front.
///
/// Checks run in this order: the query's shape against the limits, then its consents
/// ([`Engine::check_limits`], which fails with "User consents are required for this query"
/// when `hpds.requireAuthorizationFilter` is on and no consent value is given), then the
/// concepts it touches in the selected partitions, then `QueryValidator` against the merged
/// dictionary ([`Engine::validate`], whose consent rule follows the same flag). The query,
/// `userConsents` included, travels with the job, so the export reads exactly the partitions
/// those consents select.
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
    engine.check_limits(&query)?;
    engine.validate(&query)?;
    let status = state.results().submit(engine, query)?;
    Ok(axum::Json(status).into_response())
}

/// `POST /query/{id}/status`: the [`QueryStatus`] of a held result, or 404 JSON for an id
/// the registry does not hold (Java fails with a `NullPointerException`).
pub async fn status(
    State(state): State<AppState>,
    PathParam(id): PathParam<String>,
) -> Result<Response, ApiError> {
    let id = parse_id(&id)?;
    let status = state.results().status(&id).ok_or_else(unknown_id)?;
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
    let (file, size) = match state.results().open_result(&id) {
        Ok(opened) => opened,
        Err(ResultError::Unknown) => return Err(unknown_id()),
        Err(ResultError::NotReady(name)) => return Ok(not_ready_response(name)),
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
    state.results().status(&id).ok_or_else(unknown_id)?;
    Err(ApiError::bad_request(
        "signed URLs are not supported: this HPDS does not upload results to S3",
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(state: ResultState, completed_ms: u64, reserved: u64) -> StoredResult {
        StoredResult {
            picsure_query_id: String::new(),
            queued_ms: 0,
            completed_ms,
            state,
            reserved,
        }
    }

    fn finished(size: u64, completed_ms: u64) -> StoredResult {
        entry(
            ResultState::Success {
                file: PathBuf::from("/nonexistent/result.csv"),
                size,
            },
            completed_ms,
            0,
        )
    }

    fn quota(entries: Entries, max_bytes: u64) -> Quota {
        Quota {
            entries: Arc::new(Mutex::new(entries)),
            id: "mine".to_owned(),
            max_bytes,
        }
    }

    fn reserved_of(quota: &Quota, id: &str) -> u64 {
        lock(&quota.entries).get(id).map_or(0, |e| e.reserved)
    }

    #[test]
    fn a_reservation_evicts_the_oldest_finished_result_to_fit() {
        let mut entries = Entries::new();
        entries.insert("mine".into(), entry(ResultState::Running, 0, 0));
        entries.insert("old".into(), finished(60, 1));
        entries.insert("new".into(), finished(30, 2));
        let quota = quota(entries, 100);
        quota.reserve(50).unwrap();
        let entries = lock(&quota.entries);
        assert!(!entries.contains_key("old") && entries.contains_key("new"));
        assert_eq!(held_bytes(&entries), 80);
    }

    #[test]
    fn running_exports_count_against_the_byte_cap() {
        let mut entries = Entries::new();
        entries.insert("mine".into(), entry(ResultState::Running, 0, 0));
        entries.insert("other".into(), entry(ResultState::Running, 0, 90));
        entries.insert("done".into(), finished(5, 1));
        let quota = quota(entries, 100);
        let error = quota.reserve(20).unwrap_err();
        assert!(error.get_ref().is_some_and(|e| e.is::<StorageFull>()));
        let entries = lock(&quota.entries);
        assert!(!entries.contains_key("done"));
        assert!(entries.contains_key("other"));
        assert_eq!(held_bytes(&entries), 90);
    }

    #[test]
    fn running_exports_are_never_evicted_by_the_byte_cap() {
        let mut entries = Entries::new();
        entries.insert("running".into(), entry(ResultState::Running, 0, 500));
        entries.insert("done".into(), finished(10, 1));
        assert_eq!(enforce_byte_cap(&mut entries, 100, None).len(), 1);
        assert!(entries.contains_key("running"));
    }

    #[test]
    fn a_lone_export_may_pass_the_cap_but_not_beside_another() {
        let mut entries = Entries::new();
        entries.insert("mine".into(), entry(ResultState::Running, 0, 0));
        let lone = quota(entries, 10);
        lone.reserve(50).unwrap();
        assert_eq!(reserved_of(&lone, "mine"), 50);

        lock(&lone.entries).insert("rival".into(), entry(ResultState::Running, 0, 1));
        assert!(lone.reserve(1).is_err());
        assert_eq!(reserved_of(&lone, "mine"), 50);
    }

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
            (ResultState::Pending, "PENDING", "QUEUED"),
            (ResultState::Running, "RUNNING", "PENDING"),
            (
                ResultState::Success {
                    file: PathBuf::new(),
                    size: 0,
                },
                "SUCCESS",
                "AVAILABLE",
            ),
            (
                ResultState::Error {
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
