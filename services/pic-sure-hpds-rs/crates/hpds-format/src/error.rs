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

    /// The store was written in format 1 by Rust HPDS v1 to v3, before consent partitions
    /// moved inside each block. Only `hpds-etl convert` reads it.
    #[error(
        "the store is in format 1 (Rust HPDS v1 to v3); convert it with hpds-etl convert --store <dir> --out <dir>"
    )]
    LegacyFormat,

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
    /// block was read under a different store, concept path or format version than it
    /// was written with.
    #[error(
        "authentication failed: wrong key, corrupt data, or a block read under the wrong store or concept path"
    )]
    Authentication,

    /// A key whose length is not an AES key length.
    #[error("invalid AES key length {0}; keys are 16, 24 or 32 bytes")]
    InvalidKeyLength(usize),

    /// The caller expected a plain store but the named file is encrypted.
    #[error("{0} is encrypted and no key was supplied")]
    MissingKey(&'static str),

    /// The caller supplied a key, so it expects an encrypted store, but the named file is
    /// plain. Refused so that a plain file can never stand in for an encrypted one.
    #[error("{0} is not encrypted but a key was supplied; refusing an unencrypted store")]
    UnexpectedKey(&'static str),

    /// `pheno.blocks` belongs to a different store than the index it was opened with.
    #[error("pheno.blocks belongs to a different store than pheno.index")]
    StoreMismatch,

    /// A compressed frame declares a decoded size above the bound its concept metadata
    /// allows. Rejected before anything is allocated.
    #[error("{what} declares {declared} decoded bytes, above the limit of {limit}")]
    PayloadTooLarge {
        /// Which structure was being decoded.
        what: &'static str,
        /// The decoded size the frame header claims.
        declared: u64,
        /// The largest decoded size the caller allows.
        limit: u64,
    },

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
