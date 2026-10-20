//! `POST /query/sync`.

use std::io::{self, BufWriter, Write};
use std::sync::Arc;
use std::time::{Duration, Instant};

use axum::body::{Body, Bytes};
use axum::extract::State;
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE};
use axum::response::{IntoResponse, Response};
use hpds_query::{Engine, ExcerptAnswer, Query, QueryError, ResultType, SyncAnswer};
use tokio::sync::{mpsc, oneshot};

use crate::envelope::{random_uuid, read_query};
use crate::error::ApiError;
use crate::genomic::GenomicStatus;
use crate::metrics::label_result_type;
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
///   setting is off answers Java's `... query type not allowed` text with 200. An excerpt is
///   measured first and refused with 400 above `HPDS_MAX_EXCERPT_BYTES`; otherwise its
///   headers go out with its exact `Content-Length` and the rows stream as they are written,
///   so the body is never held whole. A failure after the headers left closes the connection
///   before the announced length, which a client sees as a truncated body.
/// - With genomic data configured but not served (health DOWN with a genomic reason), every
///   request with `genomicFilters` or a genomic result type is a 503; without genomic data
///   configured, `genomicFilters` are a 400.
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
/// - At most `HPDS_ENGINE_CONCURRENCY` engine computations run at once; the rest wait. A query
///   that has not answered within `HPDS_SYNC_TIMEOUT_SECONDS`, waiting included, is a 503; its
///   computation finishes in the background and releases its slot then.
pub async fn query_sync(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let query = read_query(&body)?;
    let result_type = query.expected_result_type;
    let response = answer_query(&state, query).await.into_response();
    Ok(label_result_type(&state, result_type, response))
}

async fn answer_query(state: &AppState, query: Query) -> Result<Response, ApiError> {
    let result_type = query.expected_result_type;
    state.check_genomic(!query.genomic_filters.is_empty() || result_type.is_genomic())?;
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

/// VARIANT_LIST_FOR_QUERY, computed under an engine slot within the sync timeout and sent as
/// the engine built it, without a copy, since it may be as long as `HPDS_MAX_EXCERPT_BYTES`.
async fn answer_variant_list(
    state: &AppState,
    engine: Arc<Engine>,
    query: Query,
) -> Result<Response, ApiError> {
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
        Ok(body) => body?,
        Err(_) => return Err(timed_out(timeout)),
    };
    Ok((
        [
            (CONTENT_TYPE.as_str(), "text/plain".to_owned()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        body,
    )
        .into_response())
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
/// chunk waits at most `stall` for room, and no chunk is sent after `deadline`. It also
/// refuses to send more than the measured length, so a body never runs past its
/// `Content-Length`. The first failure is kept in `end`.
struct ChannelWriter {
    tx: mpsc::Sender<io::Result<Bytes>>,
    runtime: tokio::runtime::Handle,
    stall: Duration,
    deadline: Instant,
    remaining: u64,
    end: Option<ExcerptEnd>,
}

impl Write for ChannelWriter {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if buf.len() as u64 > self.remaining {
            self.end = Some(ExcerptEnd::Aborted);
            return Err(io::Error::other("the excerpt is longer than it measured"));
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

/// Writes `excerpt` through a [`ChannelWriter`] in chunks of [`EXCERPT_CHUNK_BYTES`]. Returns
/// how the stream ended early, if it did; then it has already put an error on the channel
/// when there was room, and otherwise the response ends short of its `Content-Length`,
/// which a client sees as a failed transfer either way.
fn write_excerpt(
    excerpt: &hpds_query::VcfExcerpt<'_>,
    writer: ChannelWriter,
) -> Option<ExcerptEnd> {
    let tx = writer.tx.clone();
    let mut out = BufWriter::with_capacity(EXCERPT_CHUNK_BYTES, writer);
    let written = excerpt
        .write_to(&mut out)
        .and_then(|()| out.flush().map_err(|e| QueryError::Output(Arc::new(e))));
    let (writer, _unsent) = out.into_parts();
    let end = match (written, writer.end) {
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
    let _ = tx.try_send(Err(io::Error::other("the excerpt was not sent in full")));
    Some(end)
}

/// VCF_EXCERPT and AGGREGATE_VCF_EXCERPT, computed on a blocking thread under one of the
/// `HPDS_EXCERPT_CONCURRENCY` excerpt slots and an engine slot, both kept until the last row
/// is sent or the stream ends. Excerpts therefore never take every engine slot, and a COUNT
/// still finds one while excerpts stream.
///
/// The slots, the measure pass and the head are bounded by `HPDS_SYNC_TIMEOUT_SECONDS`, past
/// which the request is a 503. A measure pass still running then finishes, finds the request
/// gone, writes nothing and releases its slots. The rows then stream through a bounded
/// channel: each chunk waits at most `HPDS_EXCERPT_STALL_SECONDS` for the client to make
/// room, and the whole body ends by `HPDS_EXCERPT_STREAM_SECONDS`; either bound, a client
/// that left, a read failure or a body that would differ from its measured length ends the
/// stream early and releases the slots. Those ends are counted for `/metrics`.
async fn stream_excerpt(
    state: &AppState,
    engine: Arc<Engine>,
    query: Query,
) -> Result<Response, ApiError> {
    let (head_tx, head_rx) = oneshot::channel::<Result<ExcerptHead, QueryError>>();
    let (body_tx, body_rx) = mpsc::channel::<io::Result<Bytes>>(EXCERPT_CHUNKS_IN_FLIGHT);
    let timeout = state.sync_timeout();
    let (stall, stream) = state.excerpt_bounds();
    let runtime = tokio::runtime::Handle::current();
    let task_state = state.clone();
    let computation = async {
        let excerpt_slot = state.excerpt_slot().await;
        let engine_slot = state.engine_slot().await;
        tokio::task::spawn_blocking(move || {
            let _slots = (excerpt_slot, engine_slot);
            match engine.vcf_excerpt(&query) {
                Err(error) => {
                    let _ = head_tx.send(Err(error));
                }
                Ok(ExcerptAnswer::Text(text)) => {
                    let _ = head_tx.send(Ok(ExcerptHead::Text(text)));
                }
                Ok(ExcerptAnswer::Rows(excerpt)) => {
                    let length = excerpt.len();
                    if head_tx.send(Ok(ExcerptHead::Rows(length))).is_err() {
                        return;
                    }
                    let writer = ChannelWriter {
                        tx: body_tx,
                        runtime,
                        stall,
                        deadline: Instant::now() + stream,
                        remaining: length,
                        end: None,
                    };
                    if let Some(end) = write_excerpt(&excerpt, writer) {
                        tracing::warn!(?end, "an excerpt stream ended before its last row");
                        task_state.excerpt_counters().record(end);
                    }
                }
            }
        });
        head_rx.await
    };
    let head = match tokio::time::timeout(timeout, computation).await {
        Ok(Ok(head)) => head?,
        Ok(Err(_)) => return Err(ApiError::internal()),
        Err(_) => return Err(timed_out(timeout)),
    };
    let length = match head {
        ExcerptHead::Text(text) => {
            return Ok((
                [
                    (CONTENT_TYPE.as_str(), "text/plain".to_owned()),
                    (QUERY_METADATA_HEADER, random_uuid()),
                ],
                text,
            )
                .into_response());
        }
        ExcerptHead::Rows(length) => length,
    };
    let rows = futures_util::stream::unfold(body_rx, |mut rx| async move {
        rx.recv().await.map(|chunk| (chunk, rx))
    });
    Ok((
        [
            (CONTENT_TYPE.as_str(), "text/plain".to_owned()),
            (CONTENT_LENGTH.as_str(), length.to_string()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        Body::from_stream(rows),
    )
        .into_response())
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
