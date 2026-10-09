//! On-disk format for the Rust HPDS phenotype store.
//!
//! A store is a directory holding [`INDEX_FILE_NAME`] and [`BLOCKS_FILE_NAME`]. All
//! integers are little-endian unless marked otherwise. Strings are UTF-8, written as a
//! `u32` byte length followed by the bytes.
//!
//! # `pheno.index`
//!
//! ```text
//! magic             8 bytes  "HPDSPHN1"
//! format_version    u16      FORMAT_VERSION
//! flags             u16      bit 0 encrypted, bit 1 compressed, others zero
//! key_name          string
//! created_at_millis i64
//! etl_version       string
//! stored_len        u64
//! stored            stored_len bytes
//! checksum          u64      plain only: xxh3_64 of every preceding byte of the file
//! ```
//!
//! Everything before `stored_len` is the preamble. `stored` is the body, zstd-compressed
//! when the compressed flag is set. When the encrypted flag is set, `stored` is that in
//! the `Crypto.java` framing (see [`crypto`]) with the preamble as associated data, and
//! there is no checksum.
//!
//! The body:
//!
//! ```text
//! patients_len      u64
//! patients          roaring bitmap, portable serialization
//! concept_count     u32
//! concept_count times, sorted strictly by path in Java String.compareTo order:
//!   path            string
//!   type            u8   0 numeric, 1 categorical
//!   meta_flags      u8   bit 0 has_timestamp, 1 min present, 2 max present,
//!                        3 timestamp_min present, 4 timestamp_max present,
//!                        5 category_values present
//!   block_offset    u64
//!   block_length    u64
//!   observation_cnt u32
//!   patient_count   u32
//!   width_in_bytes  i32
//!   column_offset   i32
//!   min             f64  0.0 when absent
//!   max             f64  0.0 when absent
//!   timestamp_min   i64  0 when absent
//!   timestamp_max   i64  0 when absent
//!   value_count     u32  0 when category_values is absent
//!   values          value_count strings
//! ```
//!
//! # `pheno.blocks`
//!
//! The magic `HPDSBLK1`, then stored blocks back to back at the offsets the index
//! records. A stored block is, when encrypted,
//! `ivLength (i32 big-endian, 12) || iv(12) || AES-GCM(body) || tag(16)` with associated
//! data `path bytes || FORMAT_VERSION (u16)`; when plain, `body || xxh3_64(body)`. The body
//! is `zstd(payload)` when compressed and the payload otherwise.
//!
//! Every array in a payload starts on an 8-byte boundary and is followed by zero padding
//! to the next one, so [`Block`] exposes them as typed slices without copying.
//!
//! Numeric payload, `n` observations:
//!
//! ```text
//! 0   n               u32
//! 4   has_timestamp   u8   0 or 1
//! 5   kind            u8   0
//! 6   reserved        2 bytes, zero
//! 8   values          f64[n]  sorted by (value in Double.compareTo order, patient, timestamp)
//!     patients        u32[n]  parallel to values
//!     timestamps      i64[n]  parallel to values, only when has_timestamp
//!     by_key          u32[n]  indexes into values, ordered by (patient, timestamp, index)
//! ```
//!
//! Categorical payload, `n` observations, `k` distinct values:
//!
//! ```text
//! 0   n               u32
//! 4   has_timestamp   u8   0 or 1
//! 5   kind            u8   1
//! 6   reserved        2 bytes, zero
//! 8   k               u32
//! 12  reserved        u32, zero
//! 16  string_offsets  u64[k+1]  byte offsets into string_bytes, first 0, last its length
//!     string_bytes    u8[]      dictionary in Java String.compareTo order, concatenated
//!     bitmap_offsets  u64[k+1]  byte offsets into bitmap_bytes
//!     bitmap_bytes    u8[]      k portable roaring bitmaps, patients per dictionary entry
//!     patients        u32[n]    sorted by (patient, timestamp, value_index)
//!     value_index     u32[n]    dictionary index, parallel to patients
//!     timestamps      i64[n]    parallel to patients, only when has_timestamp
//! ```
//!
//! A timestamp of [`NO_TIMESTAMP`] means the observation has none. Java's `null` and
//! `Long.MIN_VALUE` both map to it.

#[cfg(target_endian = "big")]
compile_error!(
    "hpds-format views little-endian arrays in place and supports only little-endian targets"
);

mod aligned;
pub mod block;
pub mod blocks_file;
pub mod crypto;
mod error;
pub mod index;
pub mod parity;

pub use aligned::AlignedBuf;
pub use block::{
    Block, BlockCodec, CategoricalBlock, CategoricalObservation, NumericBlock, NumericObservation,
    block_aad, encode_categorical_payload, encode_numeric_payload,
};
pub use blocks_file::{BlocksReader, BlocksWriter};
pub use crypto::{
    CryptoKey, decrypt_java_block, decrypt_java_block_with_aad, encrypt_java_block,
    encrypt_java_block_with_aad,
};
pub use error::{FormatError, Result};
pub use index::{BlockLocation, ConceptMeta, ConceptType, IndexHeader, PhenoIndex};
pub use parity::{canonical_nan, java_double_cmp, java_string_cmp, java_trim, parse_java_double};

/// Name of the index file that marks a populated phenotype data directory.
pub const INDEX_FILE_NAME: &str = "pheno.index";

/// Name of the file holding the per-concept blocks.
pub const BLOCKS_FILE_NAME: &str = "pheno.blocks";

/// Format version written into the index header and bound into block associated data.
pub const FORMAT_VERSION: u16 = 1;

/// Magic bytes at the start of `pheno.index`.
pub const INDEX_MAGIC: &[u8; 8] = b"HPDSPHN1";

/// Magic bytes at the start of `pheno.blocks`.
pub const BLOCKS_MAGIC: &[u8; 8] = b"HPDSBLK1";

/// Timestamp stored for an observation that has none.
pub const NO_TIMESTAMP: i64 = i64::MIN;
