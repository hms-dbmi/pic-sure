//! `POST /query/sync`.

use axum::body::Bytes;
use axum::extract::State;
use axum::http::StatusCode;
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};
use hpds_query::{SyncResult, check_supported};

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
/// - A genomic result type is a 400 JSON error naming it.
/// - An export type (PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES, DATAFRAME_PFB) is a 400 with
///   Java's exact `text/plain` message pointing at `POST /query`.
/// - As in Java, `QueryValidator` is not applied: a query whose authorization filter names a
///   concept the store lacks counts 0 instead of being refused. Only genomic input is
///   refused up front ([`check_supported`]). Structural failures the engine raises while
///   evaluating (a FILTER without criteria, an empty subquery) are 400 JSON errors, where
///   Java answers 500 or an empty 200.
/// - At most `HPDS_ENGINE_CONCURRENCY` engine computations run at once; the rest wait.
pub async fn query_sync(State(state): State<AppState>, body: Bytes) -> Result<Response, ApiError> {
    let query = read_query(&body)?;
    let result_type = query.expected_result_type;
    if result_type.is_genomic() {
        check_supported(&query)?;
    }
    if !result_type.is_sync() {
        let message = format!(
            "Result type {result_type} is served asynchronously. Submit it with POST /query, poll \
             /query/{{resourceQueryId}}/status, then collect it from /query/{{resourceQueryId}}/result."
        );
        return Ok((
            StatusCode::BAD_REQUEST,
            [(CONTENT_TYPE, "text/plain")],
            message,
        )
            .into_response());
    }
    let engine = state.engine()?;
    check_supported(&query)?;
    let _slot = state.engine_slot().await;
    let result = engine.run_blocking(move |e| e.run_sync(&query)).await?;
    Ok((
        [
            (CONTENT_TYPE.as_str(), content_type(&result).to_owned()),
            (QUERY_METADATA_HEADER, random_uuid()),
        ],
        result.body(),
    )
        .into_response())
}

/// The content type Java sends with `result`: `text/plain` for COUNT, `application/json` for
/// the cross counts.
fn content_type(result: &SyncResult) -> &'static str {
    if result.is_json() {
        "application/json"
    } else {
        "text/plain"
    }
}
