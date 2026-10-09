use thiserror::Error;

/// Every failure the format crate reports. Readers return one of these on corrupt,
/// truncated, or unauthenticated input instead of panicking.
#[derive(Debug, Error)]
pub enum FormatError {
    /// An underlying read or write failed.
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// A file did not start with the expected magic bytes.
    #[error("{file} does not start with the expected magic bytes")]
    BadMagic {
        /// Which file was being read.
        file: &'static str,
    },

    /// The file was written by a format version this crate does not read.
    #[error("unsupported format version {0}")]
    UnsupportedVersion(u16),

    /// The input ended before a complete structure could be read.
    #[error("truncated {0}")]
    Truncated(&'static str),

    /// The input was complete but structurally invalid.
    #[error("corrupt {what}: {detail}")]
    Corrupt {
        /// Which structure was invalid.
        what: &'static str,
        /// What exactly was wrong.
        detail: String,
    },

    /// An unencrypted block or index failed its xxh3 checksum.
    #[error("checksum mismatch in {0}")]
    Checksum(&'static str),

    /// AES-GCM refused the ciphertext: the key is wrong, the bytes are corrupt, or the
    /// block was read under a different concept path or format version than it was
    /// written with.
    #[error(
        "authentication failed: wrong key, corrupt data, or a block read under the wrong concept path"
    )]
    Authentication,

    /// A key whose length is not an AES key length.
    #[error("invalid AES key length {0}; keys are 16, 24 or 32 bytes")]
    InvalidKeyLength(usize),

    /// The store is encrypted and no key was supplied, or a key was supplied for an
    /// operation on an unencrypted store that requires none.
    #[error("{0}")]
    KeyMismatch(&'static str),

    /// The operating system random number generator failed.
    #[error("random number generator failed: {0}")]
    Random(String),

    /// zstd compression or decompression failed.
    #[error("zstd: {0}")]
    Compression(String),

    /// The caller passed data the format cannot represent.
    #[error("invalid input: {0}")]
    InvalidInput(String),
}

impl FormatError {
    pub(crate) fn corrupt(what: &'static str, detail: impl Into<String>) -> Self {
        FormatError::Corrupt {
            what,
            detail: detail.into(),
        }
    }
}

/// Result alias used throughout the crate.
pub type Result<T> = std::result::Result<T, FormatError>;
