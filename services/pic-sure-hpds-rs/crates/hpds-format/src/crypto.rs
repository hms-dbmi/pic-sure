//! AES-GCM encryption compatible with HPDS `Crypto.java`.
//!
//! The key is the UTF-8 bytes of the trimmed key file, never hex-decoded, so a
//! 32-character key file is an AES-256 key, 24 characters AES-192, and 16 AES-128.
//! Ciphertext is framed exactly as `Crypto.encryptData` frames it:
//!
//! ```text
//! ivLength   i32 big-endian, always 12
//! iv         12 random bytes from the operating system
//! ciphertext plaintext length, followed by a 16-byte GCM tag
//! ```
//!
//! Java javabins carry no associated data: read them with [`decrypt_java_block`]. Blocks
//! and indexes written by this crate go through [`seal_framed`] and [`open_framed`], which
//! bind associated data so a block cannot be replayed under another concept or store.

use std::fmt;
use std::path::Path;

use aes_gcm::aead::consts::U12;
use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::aes::Aes192;
use aes_gcm::{Aes128Gcm, Aes256Gcm, AesGcm, Nonce};

use crate::error::{FormatError, Result};
use crate::parity::java_trim;

/// Length of the GCM nonce written by Java and by this crate.
pub const IV_LENGTH: usize = 12;

/// Length of the GCM authentication tag.
pub const TAG_LENGTH: usize = 16;

/// Bytes a sealed message adds to its plaintext: the length prefix, the IV and the tag.
pub const FRAME_OVERHEAD: usize = 4 + IV_LENGTH + TAG_LENGTH;

/// Default key file location, matching `Crypto.DEFAULT_ENCRYPTION_KEY_PATH`.
pub const DEFAULT_KEY_PATH: &str = "/opt/local/hpds/encryption_key";

/// Default key name, matching `Crypto.DEFAULT_KEY_NAME`.
pub const DEFAULT_KEY_NAME: &str = "DEFAULT";

type Aes192Gcm = AesGcm<Aes192, U12>;

#[derive(Clone)]
enum Cipher {
    Aes128(Box<Aes128Gcm>),
    Aes192(Box<Aes192Gcm>),
    Aes256(Box<Aes256Gcm>),
}

/// An AES-GCM key loaded with the rules of `Crypto.loadKey`.
#[derive(Clone)]
pub struct CryptoKey {
    cipher: Cipher,
    length: usize,
}

impl fmt::Debug for CryptoKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("CryptoKey")
            .field("bits", &(self.length * 8))
            .finish_non_exhaustive()
    }
}

impl CryptoKey {
    /// Builds a key from raw bytes.
    ///
    /// # Errors
    /// [`FormatError::InvalidKeyLength`] unless `key` is 16, 24 or 32 bytes long.
    pub fn from_bytes(key: &[u8]) -> Result<Self> {
        let invalid = |_| FormatError::InvalidKeyLength(key.len());
        let cipher = match key.len() {
            16 => Cipher::Aes128(Box::new(Aes128Gcm::new_from_slice(key).map_err(invalid)?)),
            24 => Cipher::Aes192(Box::new(Aes192Gcm::new_from_slice(key).map_err(invalid)?)),
            32 => Cipher::Aes256(Box::new(Aes256Gcm::new_from_slice(key).map_err(invalid)?)),
            other => return Err(FormatError::InvalidKeyLength(other)),
        };
        Ok(CryptoKey {
            cipher,
            length: key.len(),
        })
    }

    /// Builds a key from the bytes of a key file: decoded as UTF-8 with replacement of
    /// malformed sequences, trimmed with Java `String.trim` rules, and used as UTF-8 bytes.
    ///
    /// # Errors
    /// [`FormatError::InvalidKeyLength`] when the trimmed key is not 16, 24 or 32 bytes.
    pub fn from_key_file_bytes(contents: &[u8]) -> Result<Self> {
        let text = String::from_utf8_lossy(contents);
        Self::from_bytes(java_trim(&text).as_bytes())
    }

    /// Reads and parses a key file, as `Crypto.loadKey` does.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the file cannot be read, and the errors of
    /// [`CryptoKey::from_key_file_bytes`].
    pub fn load(path: impl AsRef<Path>) -> Result<Self> {
        Self::from_key_file_bytes(&std::fs::read(path)?)
    }

    /// Key length in bits: 128, 192 or 256.
    pub fn bits(&self) -> usize {
        self.length * 8
    }

    fn encrypt_raw(
        &self,
        nonce: &[u8; IV_LENGTH],
        plaintext: &[u8],
        aad: &[u8],
    ) -> Result<Vec<u8>> {
        let nonce = Nonce::<U12>::from(*nonce);
        let payload = Payload {
            msg: plaintext,
            aad,
        };
        let sealed = match &self.cipher {
            Cipher::Aes128(c) => c.encrypt(&nonce, payload),
            Cipher::Aes192(c) => c.encrypt(&nonce, payload),
            Cipher::Aes256(c) => c.encrypt(&nonce, payload),
        };
        sealed.map_err(|_| FormatError::InvalidInput("plaintext too long for AES-GCM".into()))
    }

    fn decrypt_raw(
        &self,
        nonce: &[u8; IV_LENGTH],
        ciphertext: &[u8],
        aad: &[u8],
    ) -> Result<Vec<u8>> {
        let nonce = Nonce::<U12>::from(*nonce);
        let payload = Payload {
            msg: ciphertext,
            aad,
        };
        let opened = match &self.cipher {
            Cipher::Aes128(c) => c.decrypt(&nonce, payload),
            Cipher::Aes192(c) => c.decrypt(&nonce, payload),
            Cipher::Aes256(c) => c.decrypt(&nonce, payload),
        };
        opened.map_err(|_| FormatError::Authentication)
    }
}

fn random_nonce() -> Result<[u8; IV_LENGTH]> {
    let mut nonce = [0u8; IV_LENGTH];
    getrandom::fill(&mut nonce).map_err(|e| FormatError::Random(e.to_string()))?;
    Ok(nonce)
}

/// Encrypts `plaintext` with no associated data, framed as `Crypto.encryptData` frames
/// it. Java's `Crypto.decryptData` reads the result.
///
/// # Errors
/// [`FormatError::Random`] when the OS RNG fails, [`FormatError::InvalidInput`] when the
/// plaintext exceeds the AES-GCM limit.
pub fn encrypt_java_block(key: &CryptoKey, plaintext: &[u8]) -> Result<Vec<u8>> {
    seal_framed(key, plaintext, &[])
}

/// Encrypts `plaintext` with a fresh random nonce, binding `aad` as associated data, in
/// the `Crypto.encryptData` framing. Only [`open_framed`] with the same `aad` opens the
/// result; Java cannot, because it never passes associated data.
///
/// # Errors
/// As [`encrypt_java_block`].
pub fn seal_framed(key: &CryptoKey, plaintext: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    let nonce = random_nonce()?;
    let sealed = key.encrypt_raw(&nonce, plaintext, aad)?;
    let mut out = Vec::with_capacity(4 + IV_LENGTH + sealed.len());
    out.extend_from_slice(&(IV_LENGTH as i32).to_be_bytes());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&sealed);
    Ok(out)
}

/// Decrypts a block written by Java `Crypto.encryptData`, which uses no associated data.
///
/// # Errors
/// [`FormatError::Truncated`] or [`FormatError::Corrupt`] when the framing is invalid,
/// [`FormatError::Authentication`] when the key is wrong or the bytes were altered.
pub fn decrypt_java_block(key: &CryptoKey, bytes: &[u8]) -> Result<Vec<u8>> {
    open_framed(key, bytes, &[])
}

/// Decrypts a block in the `Crypto.encryptData` framing that was sealed with `aad`.
///
/// # Errors
/// As [`decrypt_java_block`]; a different `aad` than the one used to encrypt also yields
/// [`FormatError::Authentication`].
pub fn open_framed(key: &CryptoKey, bytes: &[u8], aad: &[u8]) -> Result<Vec<u8>> {
    let (nonce, ciphertext) = split_frame(bytes)?;
    key.decrypt_raw(&nonce, ciphertext, aad)
}

fn split_frame(bytes: &[u8]) -> Result<([u8; IV_LENGTH], &[u8])> {
    let Some((length, rest)) = bytes.split_first_chunk::<4>() else {
        return Err(FormatError::Truncated("encrypted block header"));
    };
    let iv_length = i32::from_be_bytes(*length);
    if iv_length != IV_LENGTH as i32 {
        return Err(FormatError::corrupt(
            "encrypted block",
            format!("IV length {iv_length}, expected {IV_LENGTH}"),
        ));
    }
    let Some((nonce, ciphertext)) = rest.split_first_chunk::<IV_LENGTH>() else {
        return Err(FormatError::Truncated("encrypted block IV"));
    };
    if ciphertext.len() < TAG_LENGTH {
        return Err(FormatError::Truncated("encrypted block tag"));
    }
    Ok((*nonce, ciphertext))
}
