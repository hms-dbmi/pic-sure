use std::path::PathBuf;

use thiserror::Error;

/// Every failure the ETL reports.
#[derive(Debug, Error)]
pub enum EtlError {
    /// A file could not be read, written, created or renamed.
    #[error("{action} {path}: {source}")]
    Io {
        /// What was being done.
        action: &'static str,
        /// The file or directory involved.
        path: PathBuf,
        /// The underlying error.
        source: std::io::Error,
    },

    /// The CSV reader failed on an input file.
    #[error("reading {path}: {source}")]
    Csv {
        /// The input file.
        path: PathBuf,
        /// The underlying error.
        source: csv::Error,
    },

    /// The store could not be encoded or written.
    #[error("writing the store: {0}")]
    Format(#[from] hpds_format::FormatError),

    /// A row's patient number is not a Java `int`, or is negative. Java aborts the load on
    /// an unparseable patient number; negative numbers are refused because the store keeps
    /// patients as unsigned 32-bit integers. The message names the record, never the value.
    #[error(
        "{file} record {record} (line {line}): PATIENT_NUM is not an integer between 0 and 2147483647"
    )]
    InvalidPatient {
        /// The input file.
        file: PathBuf,
        /// The 1-based data record number in that file, header excluded.
        record: u64,
        /// The 1-based line of the file the record starts on, header included.
        line: u64,
    },

    /// The CSV config file is unreadable or malformed.
    #[error("config {path}: {detail}")]
    Config {
        /// The config file.
        path: PathBuf,
        /// What is wrong with it.
        detail: String,
    },

    /// A migration export is malformed, or holds something the store cannot represent
    /// exactly.
    #[error("export {path}: {detail}")]
    Export {
        /// The export file.
        path: PathBuf,
        /// What is wrong with it.
        detail: String,
    },

    /// An input file of a genomic load (an index file or a VCF) holds something Java would
    /// store corrupt data from or fail on.
    #[error("{path} line {line}: {detail}")]
    Input {
        /// The input file.
        path: PathBuf,
        /// The 1-based line, 0 when the problem is not on one line.
        line: u64,
        /// What is wrong with it.
        detail: String,
    },

    /// The command line asked for something the loader cannot do.
    #[error("{0}")]
    Usage(String),

    /// The worker thread pool could not be started.
    #[error("thread pool: {0}")]
    ThreadPool(String),
}

impl EtlError {
    pub(crate) fn io(
        action: &'static str,
        path: impl Into<PathBuf>,
    ) -> impl FnOnce(std::io::Error) -> Self {
        let path = path.into();
        move |source| EtlError::Io {
            action,
            path,
            source,
        }
    }
}

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, EtlError>;
