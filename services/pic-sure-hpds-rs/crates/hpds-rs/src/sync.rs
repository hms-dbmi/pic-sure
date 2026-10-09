//! `POST /query/sync`.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};

use crate::envelope::{random_uuid, read_query};
use crate::error::ApiError;
use crate::state::AppState;

/// Response header that carries an id for the answered query.
pub const QUERY_METADATA_HEADER: &str = "queryMetadata";

/// `POST /query/sync`: COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and
/// OBSERVATION_CROSS_COUNT.
///
/// A success is a 200 with Java's body: COUNT as a bare integer in `text/plain`, the cross
/// counts as JSON in `application/json`, and a `queryMetadata` header holding a fresh random
/// UUID (Java sends a UUIDv5 of the query text; callers treat it as opaque).
///
/// - INFO_COLUMN_LISTING is `[]` as `application/json`: with no genomic data the list of
///   variant info columns is empty, as in Java.
/// - Any other genomic result type is a 400 text error naming it.
/// - An export type (PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES, DATAFRAME_PFB) is a 400 with
///   Java's message pointing at `POST /query`.
/// - As in Java, `QueryValidator` is not applied: a query whose authorization filter names a
///   concept the store lacks counts 0 instead of being refused. Only genomic input is
///   refused up front by the engine. Structural failures the engine raises while evaluating
///   (a FILTER without criteria, an empty subquery) are 400 text errors, where Java answers
///   500 or an empty 200.
/// - At most `HPDS_ENGINE_CONCURRENCY` engine computations run at once; the rest wait. A query
///   that has not answered within `HPDS_SYNC_TIMEOUT_SECONDS`, waiting included, is a 503; its
///   computation finishes in the background and releases its slot then.
pub async fn query_sync(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let query = read_query(&body)?;
    let result_type = query.expected_result_type;
    if result_type == hpds_query::ResultType::InfoColumnListing {
        return Ok(([(CONTENT_TYPE, "application/json")], "[]").into_response());
    }
    if !result_type.is_sync() {
        return Err(ApiError::bad_request(format!(
            "Result type {result_type} is served asynchronously. Submit it with POST /query, poll \
             /query/{{resourceQueryId}}/status, then collect it from /query/{{resourceQueryId}}/result."
        )));
    }
    let engine = state.engine()?;
    engine.check_limits(&query)?;
    let timeout = state.sync_timeout();
    let computation = async {
        let slot = state.engine_slot().await;
        engine
            .run_blocking(move |e| {
                let _slot = slot;
                e.run_sync(&query)
            })
            .await
    };
    let result = match tokio::time::timeout(timeout, computation).await {
        Ok(result) => result?,
        Err(_) => {
            return Err(ApiError::unavailable(format!(
                "the query did not finish within {} seconds",
                timeout.as_secs()
            )));
        }
    };
    Ok((
        [
            (CONTENT_TYPE.as_str(), result.content_type().to_owned()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        result.body(),
    )
        .into_response())
}
