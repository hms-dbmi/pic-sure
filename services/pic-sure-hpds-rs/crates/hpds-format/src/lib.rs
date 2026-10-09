//! On-disk format for the Rust HPDS phenotype store.
//!
//! A store is a directory holding [`INDEX_FILE_NAME`] and [`BLOCKS_FILE_NAME`]. All
//! integers are little-endian unless marked otherwise. Strings are UTF-8, written as a
//! `u32` byte length followed by the bytes.
//!
//! # Protection
//!
//! Every reader and writer takes a [`Protection`] from the caller: `Encrypted(key)` or
//! `Plain`. The header's encrypted flag is informational and never decides what a reader
//! accepts. A reader holding a key refuses a plain index with
//! [`FormatError::UnexpectedKey`] and a plain block with [`FormatError::Authentication`],
//! so a forged plain store with valid checksums cannot replace an encrypted one. A reader
//! without a key refuses an encrypted index with [`FormatError::MissingKey`]. Plain stores
//! exist only for local benchmarking.
//!
//! Each store has a random 128-bit [`StoreId`], written into both files. Encrypted blocks
//! bind it as associated data together with the concept path and the format version, so
//! a block copied from another concept or another store fails authentication even under
//! the same key.
//!
//! # `pheno.index`
//!
//! ```text
//! magic             8 bytes  "HPDSPHN1"
//! format_version    u16      FORMAT_VERSION
//! flags             u16      bit 0 encrypted, bit 1 compressed, others zero
//! store_id          16 bytes random, shared with pheno.blocks and every block's AAD
//! key_name          string
//! created_at_millis i64
//! etl_version       string
//! stored_len        u64
//! stored            stored_len bytes
//! checksum          u64      plain only: xxh3_64 of every preceding byte of the file
//! ```
//!
//! Everything before `stored_len` is the preamble. `stored` is the body, zstd-compressed
//! when the compressed flag is set (at most [`MAX_INDEX_BODY_LEN`] bytes decompressed).
//! When encrypted, `stored` is that in the `Crypto.java` framing (see [`crypto`]) with the
//! preamble as associated data, and there is no checksum.
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
//! The magic `HPDSBLK1` and the 16-byte store id ([`BLOCKS_HEADER_LEN`] bytes), then
//! stored blocks back to back at the offsets the index records. A stored block is, when
//! encrypted, `ivLength (i32 big-endian, 12) || iv(12) || AES-GCM(body) || tag(16)` with
//! associated data `store_id || path bytes || FORMAT_VERSION (u16)` (see [`block_aad`]);
//! when plain, `body || xxh3_64(associated data || body)`. The body is `zstd(payload)`
//! when compressed and the payload otherwise. A reader refuses a frame that declares more
//! than [`ConceptMeta::max_payload_len`] decoded bytes before allocating.
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
//! 8   values          f64[n]  sorted by (value in Double.compareTo order, patient,
//!                             timestamp, input position)
//!     patients        u32[n]  parallel to values
//!     timestamps      i64[n]  parallel to values, only when has_timestamp
//!     by_key          u32[n]  indexes into values, ordered by patient, then input position
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
//!     patients        u32[n]    ordered by patient, then input position
//!     value_index     u32[n]    dictionary index, parallel to patients
//!     timestamps      i64[n]    parallel to patients, only when has_timestamp
//! ```
//!
//! "Input position" is the observation's position in the encoder's input, which the ETL
//! passes in file order. Within one patient the `by_key` order is therefore file order,
//! the result of Java's stable `sortedByKey` sort on patient alone, and DATAFRAME exports
//! see a patient's values in the same order Java does. Input already in Java
//! `sortedByKey` order (a migrated javabin) keeps that order exactly.
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
pub mod protection;

pub use aligned::AlignedBuf;
pub use block::{
    Block, BlockCodec, CategoricalBlock, CategoricalObservation, CategoricalRow, NumericBlock,
    NumericObservation, block_aad, encode_categorical_payload, encode_interned_categorical_payload,
    encode_numeric_payload,
};
pub use blocks_file::{BLOCKS_HEADER_LEN, BlocksReader, BlocksWriter};
pub use crypto::{CryptoKey, decrypt_java_block, encrypt_java_block, open_framed, seal_framed};
pub use error::{FormatError, Result};
pub use index::{
    BlockLocation, ConceptMeta, ConceptType, IndexHeader, MAX_INDEX_BODY_LEN, PhenoIndex,
};
pub use parity::{canonical_nan, java_double_cmp, java_string_cmp, java_trim, parse_java_double};
pub use protection::{Protection, STORE_ID_LEN, StoreId};

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
