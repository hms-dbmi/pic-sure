//! The plain-text error responses.

use axum::http::HeaderValue;
use axum::http::StatusCode;
use axum::http::header::{CONTENT_TYPE, RETRY_AFTER};
use axum::response::{IntoResponse, Response};
use hpds_query::QueryError;

/// Message sent in place of the detail of a server-side failure, which goes to the log.
pub const SERVER_ERROR_MESSAGE: &str = "the request could not be completed; see the server log";

/// Content type of every error body.
pub const ERROR_CONTENT_TYPE: &str = "text/plain; charset=utf-8";

/// An error answered with its message as the whole `text/plain` body, as Java's 400 bodies
/// are, so the query service shows a readable message.
///
/// Messages describe the request (and may repeat its own concept paths) but never carry
/// observation values from the store.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApiError {
    /// The HTTP status.
    pub status: StatusCode,
    /// What went wrong.
    pub message: String,
    /// Seconds sent in a `Retry-After` header, when the client may retry then.
    pub retry_after: Option<u64>,
}

impl ApiError {
    /// A 400 with `message`.
    pub fn bad_request(message: impl Into<String>) -> Self {
        Self::new(StatusCode::BAD_REQUEST, message)
    }

    /// A 408 with `message`.
    pub fn request_timeout(message: impl Into<String>) -> Self {
        Self::new(StatusCode::REQUEST_TIMEOUT, message)
    }

    /// A 413 with `message`.
    pub fn payload_too_large(message: impl Into<String>) -> Self {
        Self::new(StatusCode::PAYLOAD_TOO_LARGE, message)
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

    /// This error with a `Retry-After` header of `seconds`.
    #[must_use]
    pub fn with_retry_after(self, seconds: u64) -> Self {
        ApiError {
            retry_after: Some(seconds),
            ..self
        }
    }

    fn new(status: StatusCode, message: impl Into<String>) -> Self {
        ApiError {
            status,
            message: message.into(),
            retry_after: None,
        }
    }
}

impl From<QueryError> for ApiError {
    /// A 400 carrying the error's message when [`QueryError::is_client_error`] holds, a 503
    /// saying the service must restart for [`QueryError::StoreChanged`] and
    /// [`QueryError::GenomicStoreChanged`] (the directory is logged, not sent), and otherwise a 500 whose detail is logged rather than sent.
    fn from(error: QueryError) -> Self {
        if let QueryError::StoreChanged { .. } = error {
            return ApiError::unavailable(crate::state::STORE_CHANGED_REASON);
        }
        if let QueryError::GenomicStoreChanged { .. } = error {
            return ApiError::unavailable(crate::genomic::GENOMIC_CHANGED_REASON);
        }
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
        let mut response = (
            self.status,
            [(CONTENT_TYPE, ERROR_CONTENT_TYPE)],
            self.message,
        )
            .into_response();
        if let Some(seconds) = self.retry_after {
            response
                .headers_mut()
                .insert(RETRY_AFTER, HeaderValue::from(seconds));
        }
        response
    }
}
