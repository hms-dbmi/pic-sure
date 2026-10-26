//! `POST /query/sync`.

use std::io::{self, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE};
use axum::response::{IntoResponse, Response};
use hpds_query::{
    ConsentContract, Engine, ExcerptAnswer, GenomicStore, OnePassAnswer, Query, QueryError,
    ResultType, SyncAnswer,
};
use tokio::sync::{OwnedSemaphorePermit, mpsc, oneshot};

use crate::envelope::{random_uuid, read_query_for};
use crate::error::ApiError;
use crate::genomic::GenomicStatus;
use crate::metrics::label_result_type;
use crate::spool::Spool;
use crate::state::{AppState, ExcerptEnd};

/// Response header that carries an id for the answered query.
pub const QUERY_METADATA_HEADER: &str = "queryMetadata";

/// Bytes an excerpt's writer gathers before it hands a chunk to the response.
const EXCERPT_CHUNK_BYTES: usize = 64 << 10;

/// Chunks of an excerpt in flight between its writer and the response, at most.
const EXCERPT_CHUNKS_IN_FLIGHT: usize = 4;

/// `POST /query/sync`: COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and
/// OBSERVATION_CROSS_COUNT.
///
/// A success is a 200 with Java's body: COUNT as a bare integer in `text/plain`, the cross
/// counts as JSON in `application/json`, and a `queryMetadata` header holding a fresh random
/// UUID (Java sends a UUIDv5 of the query text; callers treat it as opaque).
///
/// - INFO_COLUMN_LISTING is the genomic store's INFO columns as `application/json`
///   ([`hpds_query::Engine::info_column_listing`]), or `[]` when no genomic data is
///   configured, as in Java. Java answers it without evaluating the query, so nothing in the
///   query is checked: no limits, consents, genomic filters, or the clause constructs every
///   other type refuses ([`hpds_query::Query::java_failure`]). Only what Jackson refuses
///   while reading the query is refused.
/// - VARIANT_COUNT_FOR_QUERY is `{"count":<n>,"message":"Query ran successfully"}` as
///   `application/json`, and with no `genomicFilters` `{"count":"0","message":"No variant
///   filters were supplied, so no query was run."}`, as in Java, after the limits and consent
///   checks. Java runs no consent check for this case; this service keeps its own.
/// - VARIANT_LIST_FOR_QUERY is Java's `[spec, spec]` text, VCF_EXCERPT and
///   AGGREGATE_VCF_EXCERPT Java's tab-separated excerpt, both `text/plain`; a type whose
///   setting is off answers Java's `... query type not allowed` text with 200, after the
///   limits and consents and before anything genomic is looked at, as Java checks the gate
///   first. An excerpt is generated once into a bounded spool and refused with 400 above
///   `HPDS_MAX_EXCERPT_BYTES`; otherwise its headers go out with its exact `Content-Length`
///   and the rows stream from the spool, which holds at most
///   `HPDS_EXCERPT_SPOOL_MEMORY_BYTES` in memory and spills the rest to a file. A failure
///   after the headers left closes the connection before the announced length, which a
///   client sees as a truncated body. Excerpts and variant lists run under the
///   `HPDS_EXCERPT_CONCURRENCY` cap and the stall and stream bounds (see `stream_excerpt`).
/// - With genomic data configured but not served (health DOWN with a genomic reason), every
///   request with `genomicFilters` or a genomic result type is a 503, except a gated type
///   whose setting is off; without genomic data configured, `genomicFilters` are a 400.
/// - An export type (PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES, DATAFRAME_PFB) is a 400 with
///   Java's message pointing at `POST /query`.
/// - A `null` element of `select` is ignored by COUNT, CATEGORICAL_CROSS_COUNT and
///   CONTINUOUS_CROSS_COUNT, which never read `select`, and refused with a 400
///   ([`hpds_query::NULL_SELECT_MESSAGE`]) by CROSS_COUNT and OBSERVATION_CROSS_COUNT, where
///   Java fails with an empty 200.
/// - Before any work is queued, the query's shape is checked against the limits, then its
///   consents, then the concepts it touches in the partitions its consents select
///   ([`hpds_query::Engine::check_limits`]). With `hpds.requireAuthorizationFilter` on, a
///   query with no consent value (no `userConsents`, an empty list, or only null values) is a
///   400 "User consents are required for this query". Java answers that case 200 with an
///   empty body and no `queryMetadata` header.
/// - The partitions read are those the query's `userConsents` select; with the flag off and
///   no consent value, every partition. `authorizationFilters` are never applied, as on the
///   Java branch.
/// - As in Java, `QueryValidator` is not applied: a filter on a concept no partition holds
///   counts 0 instead of being refused. Only genomic input is refused up front by the
///   engine. Structural failures the engine raises while evaluating (a FILTER without
///   criteria, an empty subquery) are 400 text errors, where Java answers 500 or an empty
///   200.
/// - With `HPDS_RESULT_CACHE_BYTES` above 0, a COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT,
///   CONTINUOUS_CROSS_COUNT or OBSERVATION_CROSS_COUNT that passes every check above is
///   looked up in the result cache ([`hpds_query::Engine::cached_sync`]) and a hit is
///   answered at once, with the same body and headers as a computed answer. A miss is
///   computed and stored.
/// - Under the main consent contract the request model is Java main's (`userConsents` is an
///   unknown field, refused with 400 naming it, and a repeated `authorizationFilters` keeps
///   its last value), and every cohort is scoped by the query's authorization filters: each
///   is a `FILTER` clause ANDed with the phenotypic clause, over every partition, for every
///   result type, genomic ones included. No validator runs, as in Java: an unknown
///   authorization concept counts 0. With `hpds.requireAuthorizationFilter` on, a query of
///   any type answered here but INFO_COLUMN_LISTING whose `authorizationFilters` is missing,
///   `null` or empty is a 400 ([`hpds_query::AUTHORIZATION_FILTER_REQUIRED_MESSAGE`]) before
///   anything else is checked, where Java answers it over every patient. A `null` filter, a
///   `null` path or `values`, and a `null` value looked up in a categorical concept are a 400
///   starting "an authorization filter", where Java fails with an empty 200.
/// - At most `HPDS_ENGINE_CONCURRENCY` engine computations run at once; the rest wait. A query
///   that has not answered within `HPDS_SYNC_TIMEOUT_SECONDS`, waiting included, is a 503; its
///   computation finishes in the background and releases its slot then.
pub async fn query_sync(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let query = read_query_for(&body, state.contract())?;
    let result_type = query.expected_result_type;
    let response = answer_query(&state, query).await.into_response();
    Ok(label_result_type(&state, result_type, response))
}

async fn answer_query(state: &AppState, query: Query) -> Result<Response, ApiError> {
    let result_type = query.expected_result_type;
    if state.contract() == ConsentContract::Main
        && result_type.is_sync()
        && result_type != ResultType::InfoColumnListing
    {
        state.engine()?.check_consents(&query)?;
    }
    let gate_closed = state
        .engine()
        .is_ok_and(|engine| gate_closed(engine, result_type));
    if !gate_closed {
        state.check_genomic(!query.genomic_filters.is_empty() || result_type.is_genomic())?;
    }
    if result_type == ResultType::InfoColumnListing {
        if state.genomic_status() == GenomicStatus::Off {
            return Ok(([(CONTENT_TYPE, "application/json")], "[]").into_response());
        }
        let answer = state.engine()?.answer_sync(&query)?;
        return Ok(respond(&answer));
    }
    if !result_type.is_sync() {
        return Err(ApiError::bad_request(format!(
            "Result type {result_type} is served asynchronously. Submit it with POST /query, poll \
             /query/{{resourceQueryId}}/status, then collect it from /query/{{resourceQueryId}}/result."
        )));
    }
    if query.select_nulls_fail(false) {
        return Err(ApiError::bad_request(hpds_query::NULL_SELECT_MESSAGE));
    }
    let engine = state.engine()?;
    engine.check_limits(&query)?;
    if gate_closed {
        return gate_text(engine, &query);
    }
    if matches!(
        result_type,
        ResultType::VcfExcerpt | ResultType::AggregateVcfExcerpt
    ) {
        return stream_excerpt(state, Arc::clone(engine), query).await;
    }
    if result_type == ResultType::VariantListForQuery {
        return answer_variant_list(state, Arc::clone(engine), query).await;
    }
    if let Some(answer) = engine.cached_sync(&query)? {
        return Ok(respond(&answer));
    }
    let timeout = state.sync_timeout();
    let computation = async {
        let slot = state.engine_slot().await;
        engine
            .run_blocking(move |e| {
                let _slot = slot;
                e.answer_sync(&query)
            })
            .await
    };
    let answer = match tokio::time::timeout(timeout, computation).await {
        Ok(answer) => answer?,
        Err(_) => return Err(timed_out(timeout)),
    };
    Ok(respond(&answer))
}

/// True when `result_type` is VARIANT_LIST_FOR_QUERY, VCF_EXCERPT or AGGREGATE_VCF_EXCERPT
/// and its setting is off. Java checks that gate before it reads the genomic filters, so
/// neither a genomic store that is not served nor a genomic filter may answer first.
fn gate_closed(engine: &Engine, result_type: ResultType) -> bool {
    let settings = engine.variant_settings();
    match result_type {
        ResultType::VariantListForQuery => !settings.variant_list_enabled(),
        ResultType::VcfExcerpt => !settings.excerpt_enabled(true),
        ResultType::AggregateVcfExcerpt => !settings.excerpt_enabled(false),
        _ => false,
    }
}

/// The text of a gated type whose setting is off, from the engine, which checks the stores,
/// the limits and the consents and then answers the gate without reading genomic data, so
/// it runs on the request's own task.
fn gate_text(engine: &Engine, query: &Query) -> Result<Response, ApiError> {
    let text = if query.expected_result_type == ResultType::VariantListForQuery {
        engine.variant_list(query)?
    } else {
        match engine.vcf_excerpt(query)? {
            ExcerptAnswer::Text(text) => text,
            ExcerptAnswer::Rows(_) => {
                tracing::error!("an excerpt whose setting is off was computed");
                return Err(ApiError::internal());
            }
        }
    };
    Ok(text_response(text))
}

/// A 200 `text/plain` answer of `body` with a fresh `queryMetadata` UUID.
fn text_response(body: String) -> Response {
    (
        [
            (CONTENT_TYPE.as_str(), "text/plain".to_owned()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        body,
    )
        .into_response()
}

/// VARIANT_LIST_FOR_QUERY, computed under an excerpt slot and an engine slot within the sync
/// timeout, as the engine built it without a copy, since it may be as long as
/// `HPDS_MAX_EXCERPT_BYTES`. The engine slot is released once the list is built; the excerpt
/// slot is kept while the body is sent, under the same stall and stream bounds as an
/// excerpt ([`send_body`]), so a client that stops reading cannot hold the list in memory
/// without bound.
async fn answer_variant_list(
    state: &AppState,
    engine: Arc<Engine>,
    query: Query,
) -> Result<Response, ApiError> {
    let excerpt_slot = take_excerpt_slot(state).await?;
    let timeout = state.sync_timeout();
    let computation = async {
        let slot = state.engine_slot().await;
        engine
            .run_blocking(move |e| {
                let _slot = slot;
                e.variant_list(&query)
            })
            .await
    };
    let body = match tokio::time::timeout(timeout, computation).await {
        Ok(body) => Bytes::from(body?),
        Err(_) => return Err(timed_out(timeout)),
    };
    let length = body.len();
    let (tx, rx) = mpsc::channel::<io::Result<Bytes>>(EXCERPT_CHUNKS_IN_FLIGHT);
    let ended = EndedEarly::default();
    let task_ended = ended.clone();
    let task_state = state.clone();
    tokio::spawn(async move {
        let _slot = excerpt_slot;
        if let Some(end) = send_body(body, &tx, task_state.excerpt_bounds()).await {
            task_ended.set();
            tracing::warn!(?end, "a variant list stream ended before its last byte");
            task_state.excerpt_counters().record(end);
            let _ = tx.try_send(Err(io::Error::other(
                "the variant list was not sent in full",
            )));
        }
    });
    Ok(streamed(length as u64, rx, ended))
}

/// Sends `body` on `tx` in chunks of [`EXCERPT_CHUNK_BYTES`], each waiting at most the stall
/// bound of `bounds` for room, the whole by its stream bound. Returns how it ended early, if
/// it did.
async fn send_body(
    body: Bytes,
    tx: &mpsc::Sender<io::Result<Bytes>>,
    (stall, stream): (Duration, Duration),
) -> Option<ExcerptEnd> {
    let deadline = tokio::time::Instant::now() + stream;
    let mut rest = body;
    while !rest.is_empty() {
        let chunk = rest.split_to(rest.len().min(EXCERPT_CHUNK_BYTES));
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return Some(ExcerptEnd::Expired);
        }
        let wait = stall.min(deadline - now);
        match tx.send_timeout(Ok(chunk), wait).await {
            Ok(()) => {}
            Err(mpsc::error::SendTimeoutError::Timeout(_)) if wait < stall => {
                return Some(ExcerptEnd::Expired);
            }
            Err(mpsc::error::SendTimeoutError::Timeout(_)) => return Some(ExcerptEnd::Stalled),
            Err(mpsc::error::SendTimeoutError::Closed(_)) => return Some(ExcerptEnd::Aborted),
        }
    }
    None
}

/// One of the `HPDS_EXCERPT_CONCURRENCY` excerpt slots, waiting at most
/// `HPDS_EXCERPT_QUEUE_SECONDS` for one, or a 503 with `Retry-After` when none came free,
/// counted for `/metrics`. The query service answers its caller 502 for any 5xx, so a
/// caller behind it sees a 502 without the header.
async fn take_excerpt_slot(state: &AppState) -> Result<OwnedSemaphorePermit, ApiError> {
    if let Some(slot) = state.excerpt_slot().await {
        return Ok(slot);
    }
    state.excerpt_counters().record_refused();
    let (_, total) = state.excerpt_slots_in_use();
    Err(ApiError::unavailable(format!(
        "all {total} excerpt slots (HPDS_EXCERPT_CONCURRENCY) are in use; retry later"
    ))
    .with_retry_after(state.excerpt_queue().as_secs().max(1)))
}

/// Set by the task writing a streamed body when the stream ends before its last byte, so
/// the response body fails at its next poll instead of first sending the chunks still in
/// flight.
#[derive(Clone, Default)]
struct EndedEarly(Arc<AtomicBool>);

impl EndedEarly {
    fn set(&self) {
        self.0.store(true, Ordering::Release);
    }

    fn is_set(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

/// A 200 `text/plain` response of `length` bytes whose body is the chunks `rx` receives. Once
/// `ended` is set the body yields an error before receiving another chunk, which makes hyper
/// close the connection.
fn streamed(length: u64, rx: mpsc::Receiver<io::Result<Bytes>>, ended: EndedEarly) -> Response {
    let chunks = futures_util::stream::unfold(Some((rx, ended)), |state| async move {
        let (mut rx, ended) = state?;
        if ended.is_set() {
            return Some((Err(io::Error::other("the body was not sent in full")), None));
        }
        rx.recv().await.map(|chunk| (chunk, Some((rx, ended))))
    });
    (
        [
            (CONTENT_TYPE.as_str(), "text/plain".to_owned()),
            (CONTENT_LENGTH.as_str(), length.to_string()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        Body::from_stream(chunks),
    )
        .into_response()
}

fn timed_out(timeout: Duration) -> ApiError {
    ApiError::unavailable(format!(
        "the query did not finish within {} seconds",
        timeout.as_secs()
    ))
}

/// What an excerpt's computation tells the response before any row is written.
enum ExcerptHead {
    /// A text answered whole: a disabled type, or no variant found.
    Text(String),
    /// The excerpt's exact length; its rows follow on the body channel.
    Rows(u64),
}

/// A writer that hands each write to the response as one chunk, at most
/// [`EXCERPT_CHUNKS_IN_FLIGHT`] chunks ahead of the client. It never waits unbounded: each
/// chunk waits at most `stall` for room, and no chunk is sent after `deadline`. It refuses
/// to send more than the measured length, so a body never runs past its `Content-Length`,
/// and checks before every chunk, the last included, that the genomic store is still the
/// one the excerpt was generated from ([`hpds_query::GenomicStore::check_unchanged`]), so a
/// store changed since the generation ends the stream with an error instead of completing
/// the body, as a store changed between v6's measure pass and write pass did. The first
/// failure is kept in `end`.
struct ChannelWriter<'s> {
    tx: mpsc::Sender<io::Result<Bytes>>,
    runtime: tokio::runtime::Handle,
    stall: Duration,
    deadline: Instant,
    remaining: u64,
    store: Option<&'s GenomicStore>,
    end: Option<ExcerptEnd>,
}

impl Write for ChannelWriter<'_> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.len() as u64 > self.remaining {
            self.end = Some(ExcerptEnd::Aborted);
            return Err(io::Error::other("the excerpt is longer than it measured"));
        }
        if self
            .store
            .is_some_and(|store| store.check_unchanged().is_err())
        {
            self.end = Some(ExcerptEnd::StoreChanged);
            return Err(io::Error::other(
                "the genomic store changed while the excerpt was written",
            ));
        }
        let now = Instant::now();
        if now >= self.deadline {
            self.end = Some(ExcerptEnd::Expired);
            return Err(io::Error::new(
                io::ErrorKind::TimedOut,
                "the excerpt ran too long",
            ));
        }
        let left = self.deadline - now;
        let wait = self.stall.min(left);
        let chunk = Ok(Bytes::copy_from_slice(buf));
        match self.runtime.block_on(self.tx.send_timeout(chunk, wait)) {
            Ok(()) => {
                self.remaining -= buf.len() as u64;
                Ok(buf.len())
            }
            Err(mpsc::error::SendTimeoutError::Timeout(_)) => {
                self.end = Some(if wait < self.stall {
                    ExcerptEnd::Expired
                } else {
                    ExcerptEnd::Stalled
                });
                Err(io::Error::new(
                    io::ErrorKind::TimedOut,
                    "the client stopped reading",
                ))
            }
            Err(mpsc::error::SendTimeoutError::Closed(_)) => {
                self.end = Some(ExcerptEnd::Aborted);
                Err(io::Error::new(
                    io::ErrorKind::BrokenPipe,
                    "the client went away",
                ))
            }
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

/// Sends the body `spool` holds through a [`ChannelWriter`] in chunks of
/// [`EXCERPT_CHUNK_BYTES`], then drops the spool, closing its spill file. Returns how the
/// stream ended early, if it did; then it has already put an error on the channel when there
/// was room, and otherwise the response ends short of its `Content-Length`, which a client
/// sees as a failed transfer either way.
fn send_spool(
    spool: Spool,
    mut writer: ChannelWriter<'_>,
    ended: &EndedEarly,
) -> Option<ExcerptEnd> {
    let tx = writer.tx.clone();
    let sent = spool.send_to(EXCERPT_CHUNK_BYTES, &mut writer);
    let end = match (sent, writer.end) {
        (Ok(()), _) if writer.remaining == 0 => return None,
        (Ok(()), _) => {
            tracing::error!(
                missing = writer.remaining,
                "the excerpt was shorter than it measured"
            );
            ExcerptEnd::Aborted
        }
        (Err(_), Some(end)) => end,
        (Err(error), None) => {
            tracing::error!(%error, "the excerpt failed after its headers were sent");
            ExcerptEnd::Aborted
        }
    };
    ended.set();
    let _ = tx.try_send(Err(io::Error::other("the excerpt was not sent in full")));
    Some(end)
}

/// VCF_EXCERPT and AGGREGATE_VCF_EXCERPT, computed on a blocking thread under one of the
/// `HPDS_EXCERPT_CONCURRENCY` excerpt slots and an engine slot, both kept until the last row
/// is sent or the stream ends. An excerpt that finds no excerpt slot free within
/// `HPDS_EXCERPT_QUEUE_SECONDS` is a 503 with `Retry-After`. Excerpts therefore never take
/// every engine slot, and a COUNT still finds one while excerpts stream.
///
/// The excerpt is generated once, into a [`Spool`] that holds up to
/// `min(HPDS_EXCERPT_SPOOL_MEMORY_BYTES, HPDS_MAX_EXCERPT_BYTES)` bytes in memory and spills
/// the rest to an owner-only, unlinked file in the results directory, never more than
/// `HPDS_MAX_EXCERPT_BYTES` in all. Generating it decides every refusal the old measure pass
/// decided, the byte bound included, so each is answered before the head with the same status
/// and body, and the head carries the exact `Content-Length` of the body spooled.
///
/// The excerpt slot wait is bounded by `HPDS_EXCERPT_QUEUE_SECONDS`. The wait for the engine
/// slot, the generation into the spool and the head are bounded by
/// `HPDS_SYNC_TIMEOUT_SECONDS`, past which the request is a 503. A generation still running
/// then stops before its next row, finding the request gone, sends nothing and releases its
/// slots and its spool. The rows then stream from the spool through a bounded channel:
/// each chunk waits at most `HPDS_EXCERPT_STALL_SECONDS` for the client to make room, and the
/// whole body ends by `HPDS_EXCERPT_STREAM_SECONDS`; either bound, a client that left, a read
/// failure, a genomic store changed on disk since the generation (checked before every chunk,
/// as the write pass did), or a body that would differ from its spooled length ends the stream
/// early and releases the slots. Those ends are counted for `/metrics`, and the response body
/// then fails at its next poll, so hyper closes the connection rather than send the chunks
/// still in flight. A client that reads nothing at all leaves hyper's write pending instead;
/// the server closes that connection after `HPDS_WRITE_STALL_SECONDS`
/// ([`crate::write_stall::WriteStall`]). The excerpt slot is released only after the spool,
/// and with it any spill file, is dropped.
///
/// The generation reads the open genomic store: the engine, and with it the store's shared
/// lock on `geno.index` and its open `geno.blocks`, lives until the stream ends.
async fn stream_excerpt(
    state: &AppState,
    engine: Arc<Engine>,
    query: Query,
) -> Result<Response, ApiError> {
    let excerpt_slot = take_excerpt_slot(state).await?;
    let ended = EndedEarly::default();
    let task_ended = ended.clone();
    let (head_tx, head_rx) = oneshot::channel::<Result<ExcerptHead, QueryError>>();
    let (body_tx, body_rx) = mpsc::channel::<io::Result<Bytes>>(EXCERPT_CHUNKS_IN_FLIGHT);
    let timeout = state.sync_timeout();
    let (stall, stream) = state.excerpt_bounds();
    let (spool_memory, spool_dir) = state.excerpt_spool();
    let spool_memory = spool_memory.min(engine.variant_settings().max_excerpt_bytes);
    let spool_dir = spool_dir.to_path_buf();
    let runtime = tokio::runtime::Handle::current();
    let task_state = state.clone();
    let computation = async {
        let engine_slot = state.engine_slot().await;
        tokio::task::spawn_blocking(move || {
            let _slots = (excerpt_slot, engine_slot);
            fault::before_generation();
            let excerpt = match engine.vcf_excerpt_once(&query) {
                Err(error) => {
                    let _ = head_tx.send(Err(error));
                    return;
                }
                Ok(OnePassAnswer::Text(text)) => {
                    let _ = head_tx.send(Ok(ExcerptHead::Text(text)));
                    return;
                }
                Ok(OnePassAnswer::Rows(excerpt)) => excerpt,
            };
            let mut spool = Spool::new(spool_memory, &spool_dir);
            let abandoned = || head_tx.is_closed();
            let spooled = excerpt
                .write_measuring(&mut spool, &abandoned)
                .and_then(|length| {
                    spool.flush().map_err(|e| QueryError::Output(Arc::new(e)))?;
                    Ok(length)
                })
                .and_then(fault::after_generation);
            let length = match spooled {
                Ok(length) => length,
                Err(error) => {
                    let _ = head_tx.send(Err(error));
                    return;
                }
            };
            if head_tx.send(Ok(ExcerptHead::Rows(length))).is_err() {
                return;
            }
            let writer = ChannelWriter {
                tx: body_tx,
                runtime,
                stall,
                deadline: Instant::now() + stream,
                remaining: length,
                store: engine.genomic(),
                end: None,
            };
            if let Some(end) = send_spool(spool, writer, &task_ended) {
                tracing::warn!(?end, "an excerpt stream ended before its last row");
                task_state.excerpt_counters().record(end);
            }
        });
        head_rx.await
    };
    let head = match tokio::time::timeout(timeout, computation).await {
        Ok(Ok(head)) => head?,
        Ok(Err(_)) => return Err(ApiError::internal()),
        Err(_) => return Err(timed_out(timeout)),
    };
    match head {
        ExcerptHead::Text(text) => Ok(text_response(text)),
        ExcerptHead::Rows(length) => Ok(streamed(length, body_rx, ended)),
    }
}

/// A 200 carrying `answer` with its content type and a fresh `queryMetadata` UUID, built
/// the same way whether the answer was computed or came from the result cache.
fn respond(answer: &SyncAnswer) -> Response {
    (
        [
            (CONTENT_TYPE.as_str(), answer.content_type().to_owned()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        answer.body().clone(),
    )
        .into_response()
}

/// Test-only faults on the one-pass excerpt path: fixed delays in the generation before the
/// head and in the first spool write, and a refusal raised once the body is spooled, so the
/// timeout band and every refusal answered before the head are tested without timing a real
/// excerpt. They hold for every excerpt of the process while set; tests that set them run
/// one at a time under [`fault::serial`].
#[cfg(test)]
pub(crate) mod fault {
    use std::sync::{Mutex, MutexGuard};
    use std::time::Duration;

    use hpds_query::QueryError;

    /// The faults set for the excerpts of the process.
    #[derive(Default)]
    pub(crate) struct Faults {
        /// Slept before the excerpt is built, as a slow generation would take.
        pub(crate) generation: Duration,
        /// Slept on the first write into each spool, as a slow spool would take.
        pub(crate) spool_write: Duration,
        /// Built and returned in place of the spooled length, as a refusal decided at the end
        /// of the generation would be.
        pub(crate) refusal: Option<fn() -> QueryError>,
    }

    static FAULTS: Mutex<Option<Faults>> = Mutex::new(None);
    static SPOOL_WRITES_SEEN: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    static SERIAL: Mutex<()> = Mutex::new(());
    thread_local! {
        static SPOOL_WRITES: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    }

    fn faults() -> MutexGuard<'static, Option<Faults>> {
        FAULTS.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Holds the faults of the process for one test: sets `faults` until the guard drops.
    pub(crate) struct Set {
        _serial: MutexGuard<'static, ()>,
    }

    impl Drop for Set {
        fn drop(&mut self) {
            *faults() = None;
        }
    }

    /// Sets `faults` for every excerpt until the returned guard drops, waiting for any other
    /// test holding them.
    pub(crate) fn serial(set: Faults) -> Set {
        let guard = SERIAL.lock().unwrap_or_else(|p| p.into_inner());
        *faults() = Some(set);
        Set { _serial: guard }
    }

    /// Sleeps the generation delay set, if any.
    pub(crate) fn before_generation() {
        SPOOL_WRITES.with(|w| w.set(0));
        let delay = faults().as_ref().map(|f| f.generation);
        if let Some(delay) = delay {
            std::thread::sleep(delay);
        }
    }

    /// Writes into any spool of the process so far.
    pub(crate) fn spool_writes() -> u64 {
        SPOOL_WRITES_SEEN.load(std::sync::atomic::Ordering::SeqCst)
    }

    /// Counts the write, and sleeps the spool delay set on the first write into a spool on
    /// this thread.
    pub(crate) fn on_spool_write() {
        SPOOL_WRITES_SEEN.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        let first = SPOOL_WRITES.with(|w| {
            let n = w.get();
            w.set(n + 1);
            n == 0
        });
        let delay = faults().as_ref().map(|f| f.spool_write);
        if let (true, Some(delay)) = (first, delay) {
            std::thread::sleep(delay);
        }
    }

    /// The refusal set, in place of `length`.
    pub(crate) fn after_generation(length: u64) -> hpds_query::Result<u64> {
        match faults().as_ref().and_then(|f| f.refusal) {
            Some(refusal) => Err(refusal()),
            None => Ok(length),
        }
    }
}

/// The no-op faults of a build that is not a test.
#[cfg(not(test))]
pub(crate) mod fault {
    pub(crate) fn before_generation() {}

    pub(crate) fn on_spool_write() {}

    pub(crate) fn after_generation(length: u64) -> hpds_query::Result<u64> {
        Ok(length)
    }
}

#[cfg(test)]
mod tests {
    use std::path::Path;
    use std::time::Duration;

    use axum::Router;
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use hpds_etl::genomic::vcf_load::{GenomicLoadOptions, genomic_load};
    use hpds_etl::load::{KeySource, PartitionedLoadOptions, load_partitioned};
    use hpds_format::{FormatError, GenoMode};
    use http_body_util::BodyExt;
    use serde_json::json;
    use tower::ServiceExt;

    use super::fault::{self, Faults};
    use super::*;
    use crate::config::Config;
    use crate::genomic::attach_genomic;
    use crate::spool::SPILL_PREFIX;

    const SYNC: &str = "/PIC-SURE/v3/query/sync";
    const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";
    const VCF: &str = "##fileformat=VCFv4.3\n\
        ##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"The gene.\">\n\
        ##INFO=<ID=AF,Number=1,Type=Float,Description=\"Frequency.\">\n\
        #CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tS197506\tS197507\tS200000\n\
        chr1\t100\t.\tA\tG\t.\tPASS\tGene_with_variant=G1;AF=0.1\tGT\t0|1\t1|1\t0|0\n\
        chr1\t1500\t.\tA\tT\t.\tPASS\tGene_with_variant=G1;AF=0.2\tGT\t0|0\t0|0\t0|1\n";

    struct Fixture {
        dir: tempfile::TempDir,
        state: AppState,
        app: Router,
    }

    impl Fixture {
        fn spill_files(&self) -> Vec<String> {
            std::fs::read_dir(self.dir.path().join("results"))
                .unwrap()
                .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
                .filter(|name| name.starts_with(SPILL_PREFIX))
                .collect()
        }

        async fn slots_released(&self) {
            for _ in 0..500 {
                if self.state.excerpt_slots_in_use().0 == 0 {
                    return;
                }
                tokio::time::sleep(Duration::from_millis(10)).await;
            }
            panic!("the excerpt slot was not released");
        }
    }

    /// The Java phenotype fixture's four partitions with a one-node `localDistributed`
    /// genomic store of two `G1` variants beside them, served with both excerpt gates on and
    /// the settings of `adjust`.
    fn fixture(adjust: impl FnOnce(&mut Config)) -> Fixture {
        fixture_of(0, adjust)
    }

    /// [`fixture`] with `more` further `G1` variants, each carried by patient 197506.
    fn fixture_of(more: u32, adjust: impl FnOnce(&mut Config)) -> Fixture {
        let dir = tempfile::tempdir().unwrap();
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let phenotypic = root.join("pic-sure-hpds/service/src/test/resources/phenotypic");
        let input = dir.path().join("input");
        std::fs::create_dir_all(&input).unwrap();
        for n in 1..=4 {
            let file = format!("partition{n}-allConcepts.csv");
            std::fs::copy(phenotypic.join(&file), input.join(&file)).unwrap();
        }
        let key_file = dir.path().join("key");
        std::fs::write(&key_file, KEY).unwrap();
        let store = dir.path().join("store");
        let mut options = PartitionedLoadOptions::new(input, store.clone());
        options.key = KeySource::File(key_file);
        options.no_config = true;
        options.threads = Some(2);
        load_partitioned(&options).unwrap();
        let work = dir.path().join("vcf");
        std::fs::create_dir_all(&work).unwrap();
        let vcf = work.join("n0.vcf");
        let mut text = String::from(VCF);
        for i in 0..more {
            text.push_str(&format!(
                "chr1\t{}\t.\tA\tC\t.\tPASS\tGene_with_variant=G1;AF=0.3\tGT\t0|1\t0|0\t0|0\n",
                2000 + i * 10
            ));
        }
        std::fs::write(&vcf, text).unwrap();
        let index = work.join("index.tsv");
        std::fs::write(
            &index,
            format!(
                "vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\n\
                 {}\tchr1\t1\t0\t\"S197506,S197507,S200000\"\t\"197506,197507,200000\"\n",
                vcf.display()
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
        let mut config = Config::from_lookup(|name| {
            (name == "HPDS_CONSENT_CONTRACT").then(|| "harmonization".into())
        })
        .unwrap();
        config.results_dir = dir.path().join("results");
        config.partitions.require_consents = false;
        config.data_dir = store.clone();
        config.genomic.data_dir = genomic;
        config.genomic.processor = Some(GenoMode::LocalDistributed);
        config.genomic.variant.vcf_excerpt_enabled = true;
        config.engine_concurrency = 4;
        config.excerpt_concurrency = 2;
        adjust(&mut config);
        let engine = Engine::open(&store, &config.partitions)
            .unwrap()
            .with_limits(config.query_limits);
        let (engine, status) = attach_genomic(engine, &config);
        let state = AppState::with_genomic(Some(Arc::new(engine)), &config, status).unwrap();
        let app = crate::router(state.clone());
        Fixture { dir, state, app }
    }

    fn excerpt_request(result_type: &str) -> String {
        json!({"query": {
            "expectedResultType": result_type,
            "genomicFilters": [{"key": "Gene_with_variant", "values": ["G1"]}],
        }, "resourceUUID": null})
        .to_string()
    }

    struct Reply {
        status: StatusCode,
        headers: axum::http::HeaderMap,
        body: String,
    }

    async fn post(app: &Router, body: String) -> Reply {
        let request = Request::post(SYNC)
            .header("content-type", "application/json")
            .body(Body::from(body))
            .unwrap();
        let response = app.clone().oneshot(request).await.unwrap();
        let status = response.status();
        let headers = response.headers().clone();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        Reply {
            status,
            headers,
            body: String::from_utf8(bytes.to_vec()).unwrap(),
        }
    }

    /// The status and body v6 answered for `error` raised by its measure pass: the same
    /// mapping, applied before the head.
    async fn v6_answer(error: QueryError) -> (StatusCode, String) {
        let response = ApiError::from(error).into_response();
        let status = response.status();
        let bytes = response.into_body().collect().await.unwrap().to_bytes();
        (status, String::from_utf8(bytes.to_vec()).unwrap())
    }

    /// The excerpt as v6's two passes write it: measured, then written.
    fn two_pass(state: &AppState, result_type: &str) -> String {
        let query: Query = serde_json::from_value(json!({
            "expectedResultType": result_type,
            "genomicFilters": [{"key": "Gene_with_variant", "values": ["G1"]}],
        }))
        .unwrap();
        let engine = state.engine().unwrap();
        let Ok(ExcerptAnswer::Rows(excerpt)) = engine.vcf_excerpt(&query) else {
            panic!("an excerpt");
        };
        let mut body = Vec::new();
        excerpt.write_to(&mut body).unwrap();
        String::from_utf8(body).unwrap()
    }

    #[tokio::test]
    async fn a_spooled_excerpt_is_the_two_pass_body_in_memory_and_spilled() {
        let _faults = fault::serial(Faults::default());
        for memory in [crate::config::DEFAULT_EXCERPT_SPOOL_MEMORY_BYTES, 0, 40] {
            let f = fixture(|c| c.excerpt_spool_memory_bytes = memory);
            for result_type in ["VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"] {
                let reply = post(&f.app, excerpt_request(result_type)).await;
                assert_eq!(reply.status, StatusCode::OK, "{}", reply.body);
                let whole = two_pass(&f.state, result_type);
                assert_eq!(reply.body, whole, "{result_type} at {memory}");
                assert_eq!(
                    reply.headers["content-length"].to_str().unwrap(),
                    whole.len().to_string()
                );
                assert_eq!(reply.body.lines().count(), 3, "{}", reply.body);
            }
            f.slots_released().await;
            assert!(f.spill_files().is_empty());
        }
    }

    /// The answer to an excerpt under a 2-second sync timeout with `generation` slept before
    /// it is built and `spool_write` slept on its first spool write.
    async fn band_case(generation: u64, spool_write: u64) -> (StatusCode, String) {
        let f = fixture(|c| {
            c.sync_timeout = Duration::from_secs(2);
            c.excerpt_spool_memory_bytes = 0;
        });
        let _faults = fault::serial(Faults {
            generation: Duration::from_millis(generation),
            spool_write: Duration::from_millis(spool_write),
            refusal: None,
        });
        let reply = post(&f.app, excerpt_request("AGGREGATE_VCF_EXCERPT")).await;
        f.slots_released().await;
        assert!(f.spill_files().is_empty());
        (reply.status, reply.body)
    }

    const TIMED_OUT: &str = "the query did not finish within 2 seconds";

    #[tokio::test]
    async fn generation_and_spooling_within_the_sync_timeout_answer_200() {
        let (status, body) = band_case(200, 200).await;
        assert_eq!(status, StatusCode::OK, "{body}");
        assert!(body.starts_with("CHROM\t"), "{body}");
    }

    #[tokio::test]
    async fn generation_within_and_spooling_past_the_sync_timeout_answer_503() {
        assert_eq!(
            band_case(600, 1800).await,
            (StatusCode::SERVICE_UNAVAILABLE, TIMED_OUT.to_owned())
        );
    }

    #[tokio::test]
    async fn generation_past_the_sync_timeout_answers_503() {
        assert_eq!(
            band_case(2500, 0).await,
            (StatusCode::SERVICE_UNAVAILABLE, TIMED_OUT.to_owned())
        );
    }

    #[tokio::test]
    async fn an_excerpt_over_the_byte_bound_is_refused_before_the_head_as_v6_refused_it() {
        let _faults = fault::serial(Faults::default());
        for memory in [0, 1 << 20] {
            let f = fixture(|c| {
                c.genomic.variant.max_excerpt_bytes = 64;
                c.excerpt_spool_memory_bytes = memory;
            });
            for result_type in ["VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"] {
                let query: Query = serde_json::from_value(json!({
                    "expectedResultType": result_type,
                    "genomicFilters": [{"key": "Gene_with_variant", "values": ["G1"]}],
                }))
                .unwrap();
                let measured = f.state.engine().unwrap().vcf_excerpt(&query).unwrap_err();
                assert!(matches!(measured, QueryError::LimitExceeded(_)));
                let expected = v6_answer(measured).await;
                let reply = post(&f.app, excerpt_request(result_type)).await;
                assert_eq!((reply.status, reply.body), expected, "{result_type}");
                assert_eq!(expected.0, StatusCode::BAD_REQUEST);
                assert!(!reply.headers.contains_key(QUERY_METADATA_HEADER));
            }
            f.slots_released().await;
            assert!(f.spill_files().is_empty());
        }
    }

    #[tokio::test]
    async fn an_excerpt_finding_no_slot_is_refused_with_retry_after() {
        let f = fixture(|c| {
            c.excerpt_concurrency = 1;
            c.excerpt_queue = Duration::ZERO;
            c.excerpt_spool_memory_bytes = 0;
        });
        let _faults = fault::serial(Faults {
            generation: Duration::from_millis(1500),
            ..Faults::default()
        });
        let held = tokio::spawn({
            let app = f.app.clone();
            async move { post(&app, excerpt_request("AGGREGATE_VCF_EXCERPT")).await }
        });
        for _ in 0..500 {
            if f.state.excerpt_slots_in_use().0 == 1 {
                break;
            }
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
        let refused = post(&f.app, excerpt_request("VCF_EXCERPT")).await;
        assert_eq!(refused.status, StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(refused.headers["retry-after"].to_str().unwrap(), "1");
        assert_eq!(
            refused.body,
            "all 1 excerpt slots (HPDS_EXCERPT_CONCURRENCY) are in use; retry later"
        );
        assert_eq!(held.await.unwrap().status, StatusCode::OK);
        assert_eq!(f.state.excerpt_counters().counts().refused, 1);
    }

    fn unreadable_block() -> QueryError {
        QueryError::GenomicBlock {
            node: "/chr1".into(),
            what: "metadata chunk 0".into(),
            source: Arc::new(FormatError::Authentication),
        }
    }

    fn short_spec() -> QueryError {
        QueryError::GenomicFilter(
            "a listed variant has a spec with fewer than six fields, so Java cannot sort the \
             excerpt's rows"
                .into(),
        )
    }

    fn duplicated_string() -> QueryError {
        QueryError::GenomicFilter(
            "a listed variant holds a metadata string twice on one node, which Java cannot read"
                .into(),
        )
    }

    fn no_masks() -> QueryError {
        QueryError::GenomicFilter(
            "no node holds the masks of a listed variant, which Java cannot read".into(),
        )
    }

    #[tokio::test]
    async fn refusals_decided_while_generating_are_answered_before_the_head_with_v6s_answer() {
        let cases: [(fn() -> QueryError, StatusCode); 4] = [
            (unreadable_block, StatusCode::INTERNAL_SERVER_ERROR),
            (short_spec, StatusCode::BAD_REQUEST),
            (duplicated_string, StatusCode::BAD_REQUEST),
            (no_masks, StatusCode::BAD_REQUEST),
        ];
        let f = fixture(|c| c.excerpt_spool_memory_bytes = 0);
        for (refusal, status) in cases {
            let expected = v6_answer(refusal()).await;
            assert_eq!(expected.0, status);
            let _faults = fault::serial(Faults {
                refusal: Some(refusal),
                ..Faults::default()
            });
            for result_type in ["VCF_EXCERPT", "AGGREGATE_VCF_EXCERPT"] {
                let reply = post(&f.app, excerpt_request(result_type)).await;
                assert_eq!((reply.status, reply.body), expected, "{result_type}");
                assert!(!reply.headers.contains_key(QUERY_METADATA_HEADER));
            }
            f.slots_released().await;
            assert!(f.spill_files().is_empty());
        }
        let _faults = fault::serial(Faults::default());
        let reply = post(&f.app, excerpt_request("VCF_EXCERPT")).await;
        assert_eq!(reply.status, StatusCode::OK, "the faults are cleared");
    }

    #[tokio::test]
    async fn a_client_that_leaves_mid_stream_releases_the_slot_and_leaves_no_spill_file() {
        let _faults = fault::serial(Faults::default());
        let f = fixture_of(20_000, |c| {
            c.excerpt_spool_memory_bytes = 0;
            c.excerpt_concurrency = 1;
        });
        let request = Request::post(SYNC)
            .header("content-type", "application/json")
            .body(Body::from(excerpt_request("VCF_EXCERPT")))
            .unwrap();
        let mut response = f.app.clone().oneshot(request).await.unwrap();
        assert_eq!(response.status(), StatusCode::OK);
        let length: u64 = response.headers()["content-length"]
            .to_str()
            .unwrap()
            .parse()
            .unwrap();
        let chunks = (EXCERPT_CHUNKS_IN_FLIGHT + 2) as u64 * EXCERPT_CHUNK_BYTES as u64;
        assert!(length > 2 * chunks, "{length}");
        let first = response.body_mut().frame().await.unwrap().unwrap();
        assert!(first.is_data());
        assert_eq!(f.state.excerpt_slots_in_use().0, 1, "still streaming");
        assert!(f.spill_files().is_empty());
        drop(response);
        f.slots_released().await;
        assert_eq!(f.state.excerpt_counters().counts().aborted, 1);
        assert!(f.spill_files().is_empty());
    }

    #[tokio::test]
    async fn a_generation_whose_request_timed_out_stops_before_its_next_row() {
        let f = fixture_of(20_000, |c| {
            c.sync_timeout = Duration::from_secs(1);
            c.excerpt_spool_memory_bytes = 0;
        });
        let _faults = fault::serial(Faults {
            spool_write: Duration::from_millis(1500),
            ..Faults::default()
        });
        let before = fault::spool_writes();
        let reply = post(&f.app, excerpt_request("AGGREGATE_VCF_EXCERPT")).await;
        assert_eq!(
            (reply.status, reply.body.as_str()),
            (
                StatusCode::SERVICE_UNAVAILABLE,
                "the query did not finish within 1 seconds"
            )
        );
        f.slots_released().await;
        let written = fault::spool_writes() - before;
        assert!(
            written <= 2,
            "the header and at most one row are spooled, not 20,002 rows: {written}"
        );
        assert!(f.spill_files().is_empty());
    }
}
