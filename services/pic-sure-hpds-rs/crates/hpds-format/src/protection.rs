//! What a caller expects of a store's protection, and the random id that ties a store's
//! files and blocks together.

use std::fmt;

use crate::crypto::CryptoKey;
use crate::error::{FormatError, Result};

/// Length of a [`StoreId`] in bytes.
pub const STORE_ID_LEN: usize = 16;

/// A random 128-bit id written into `pheno.index` and `pheno.blocks` when a store is
/// created. Every encrypted block binds it as associated data, so a block copied from
/// another store fails authentication even under the same key and concept path.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct StoreId([u8; STORE_ID_LEN]);

impl StoreId {
    /// A fresh id from the operating system random number generator.
    ///
    /// # Errors
    /// [`FormatError::Random`] when the generator fails.
    pub fn random() -> Result<Self> {
        let mut bytes = [0u8; STORE_ID_LEN];
        getrandom::fill(&mut bytes).map_err(|e| FormatError::Random(e.to_string()))?;
        Ok(StoreId(bytes))
    }

    /// The id with exactly these bytes, for tests and for reading files.
    pub fn from_bytes(bytes: [u8; STORE_ID_LEN]) -> Self {
        StoreId(bytes)
    }

    /// The raw bytes.
    pub fn as_bytes(&self) -> &[u8; STORE_ID_LEN] {
        &self.0
    }
}

impl fmt::Debug for StoreId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "StoreId({self})")
    }
}

impl fmt::Display for StoreId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for b in self.0 {
            write!(f, "{b:02x}")?;
        }
        Ok(())
    }
}

/// The protection a caller requires of a store, decided by the caller and never by the
/// store's header flags.
///
/// A reader holding [`Protection::Encrypted`] refuses a plain index or block with
/// [`FormatError::UnexpectedKey`] or [`FormatError::Authentication`], so a forged plain
/// store with a valid checksum cannot stand in for an encrypted one. A reader holding
/// [`Protection::Plain`] refuses an encrypted index with [`FormatError::MissingKey`].
/// Plain stores exist only for local benchmarking.
#[derive(Clone, Debug)]
pub enum Protection {
    /// AES-GCM with this key, the production default.
    Encrypted(CryptoKey),
    /// No encryption: blocks and index carry an xxh3 checksum against accidental damage
    /// only.
    Plain,
}

impl Protection {
    /// The key, when encrypted.
    pub fn key(&self) -> Option<&CryptoKey> {
        match self {
            Protection::Encrypted(key) => Some(key),
            Protection::Plain => None,
        }
    }

    /// True for [`Protection::Encrypted`].
    pub fn is_encrypted(&self) -> bool {
        matches!(self, Protection::Encrypted(_))
    }

    /// Checks that a file whose header says `encrypted` meets this expectation.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] when the file is encrypted and this is plain,
    /// [`FormatError::UnexpectedKey`] when this holds a key and the file is plain.
    pub fn require(&self, encrypted: bool, file: &'static str) -> Result<()> {
        match (self, encrypted) {
            (Protection::Plain, true) => Err(FormatError::MissingKey(file)),
            (Protection::Encrypted(_), false) => Err(FormatError::UnexpectedKey(file)),
            _ => Ok(()),
        }
    }
}
