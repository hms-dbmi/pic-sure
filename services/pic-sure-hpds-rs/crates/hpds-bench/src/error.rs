/// Failures raised by the bench harness.
#[derive(Debug, thiserror::Error)]
pub enum BenchError {
    /// A file could not be read or written.
    #[error("{path}: {source}")]
    Io {
        /// Path involved in the failed operation.
        path: String,
        /// Underlying I/O error.
        source: std::io::Error,
    },
    /// A JSON document could not be parsed or produced.
    #[error("{context}: {source}")]
    Json {
        /// What was being parsed.
        context: String,
        /// Underlying JSON error.
        source: serde_json::Error,
    },
    /// A `columnMeta.csv` row could not be parsed.
    #[error("columnMeta {path} line {line}: {message}")]
    ColumnMeta {
        /// Path of the file.
        path: String,
        /// One-based line number.
        line: usize,
        /// What was wrong with the row.
        message: String,
    },
    /// An HTTP request failed at the transport level.
    #[error("http: {0}")]
    Http(#[from] reqwest::Error),
    /// A command line value or corpus content was invalid.
    #[error("{0}")]
    Invalid(String),
}

/// Result alias used across the crate.
pub type Result<T> = std::result::Result<T, BenchError>;

impl BenchError {
    /// Wraps an I/O error with the path involved.
    pub fn io(path: &std::path::Path, source: std::io::Error) -> Self {
        Self::Io {
            path: path.display().to_string(),
            source,
        }
    }

    /// Wraps a JSON error with what was being parsed.
    pub fn json(context: impl Into<String>, source: serde_json::Error) -> Self {
        Self::Json {
            context: context.into(),
            source,
        }
    }
}
