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

/// Body Java answers for VARIANT_COUNT_FOR_QUERY when `genomicFilters` is empty, keys in the
/// sorted order of the `TreeMap` Java serializes.
const NO_VARIANT_FILTERS_BODY: &str =
    r#"{"count":"0","message":"No variant filters were supplied, so no query was run."}"#;

/// `POST /query/sync`: COUNT, CROSS_COUNT, CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and
/// OBSERVATION_CROSS_COUNT.
///
/// A success is a 200 with Java's body: COUNT as a bare integer in `text/plain`, the cross
/// counts as JSON in `application/json`, and a `queryMetadata` header holding a fresh random
/// UUID (Java sends a UUIDv5 of the query text; callers treat it as opaque).
///
/// - INFO_COLUMN_LISTING is `[]` as `application/json`: with no genomic data the list of
///   variant info columns is empty, as in Java.
/// - VARIANT_COUNT_FOR_QUERY with no `genomicFilters` is a 200 `application/json`
///   `{"count":"0","message":"No variant filters were supplied, so no query was run."}`, as in
///   Java, after the limits and consent checks. Java runs no consent check for this case; this
///   service keeps its own. With `genomicFilters` it is a 400 like the other genomic types.
/// - Any other genomic result type is a 400 text error naming it.
/// - An export type (PATIENTS, DATAFRAME, DATAFRAME_TIMESERIES, DATAFRAME_PFB) is a 400 with
///   Java's message pointing at `POST /query`.
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
    if result_type == hpds_query::ResultType::VariantCountForQuery
        && query.genomic_filters.is_empty()
    {
        return Ok((
            [
                (CONTENT_TYPE.as_str(), "application/json".to_owned()),
                (QUERY_METADATA_HEADER, random_uuid()),
            ],
            NO_VARIANT_FILTERS_BODY,
        )
            .into_response());
    }
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
