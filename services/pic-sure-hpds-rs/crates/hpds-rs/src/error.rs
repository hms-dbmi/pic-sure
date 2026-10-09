//! The JSON error responses.

use axum::Json;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use hpds_query::QueryError;
use serde::Serialize;

/// Message sent in place of the detail of a server-side failure, which goes to the log.
pub const SERVER_ERROR_MESSAGE: &str = "the request could not be completed; see the server log";

/// An error answered as `{"error": <reason phrase>, "message": <message>}`.
///
/// Messages describe the request (and may repeat its own concept paths) but never carry
/// observation values from the store.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    /// The HTTP status.
    pub status: StatusCode,
    /// What went wrong.
    pub message: String,
}

#[derive(Serialize)]
struct ErrorBody<'a> {
    error: &'a str,
    message: &'a str,
}

impl ApiError {
    /// A 400 with `message`.
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    /// A 404 with `message`.
    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(StatusCode::NOT_FOUND, message)
    }

    /// A 503 with `message`.
    pub fn unavailable(message: impl Into<String>) -> Self {
        Self::new(StatusCode::SERVICE_UNAVAILABLE, message)
    }

    /// A 500 with [`SERVER_ERROR_MESSAGE`].
    pub fn internal() -> Self {
        Self::new(StatusCode::INTERNAL_SERVER_ERROR, SERVER_ERROR_MESSAGE)
    }

    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        ApiError {
            status,
            message: message.into(),
        }
    }
}

impl From<QueryError> for ApiError {
    /// A 400 carrying the error's message when [`QueryError::is_client_error`] holds, and
    /// otherwise a 500 whose detail is logged rather than sent.
    fn from(error: QueryError) -> Self {
        if error.is_client_error() {
            ApiError::bad_request(error.to_string())
        } else {
            tracing::error!(%error, "query failed");
            ApiError::internal()
        }
    }
}

impl IntoResponse for ApiError {
    fn into_response(self) -> Response {
        let body = ErrorBody {
            error: self.status.canonical_reason().unwrap_or("Error"),
            message: &self.message,
        };
        (self.status, Json(body)).into_response()
    }
}
