//! The `geno.blocks` file and the codec that seals its blocks.
//!
//! The file is the magic `HPDSGBK1` and the 16-byte store id ([`GENO_BLOCKS_HEADER_LEN`]
//! bytes), then sealed blocks back to back at the offsets the index records. A sealed block
//! is the v4 codec's stored form: when encrypted
//! `ivLength (i32 BE, 12) || iv(12) || AES-GCM(body) || tag(16)`, when plain
//! `body || xxh3_64(aad || body)`, with `body = zstd(payload)` at level 3. The zstd frame
//! always declares its content size.
//!
//! The associated data ([`geno_block_aad`]) is fixed length, 33 bytes:
//!
//! ```text
//! "HPDSGEN1"   8 bytes
//! store_id     16 bytes
//! kind         u8    1 mask, 2 metadata, 3 carriers, 4 info
//! node         u16   index into the node table
//! ordinal      u32   chunk index within the node (kinds 1 to 3), column index (kind 4)
//! version      u16   GENO_FORMAT_VERSION
//! ```
//!
//! A block copied to another kind, node, chunk or column, or from another store or format
//! version, fails authentication even under the same key. Every payload also starts with its
//! kind byte, checked after decoding.

use std::fs::File;
use std::io::Write;
use std::os::unix::fs::FileExt;
use std::path::Path;

use xxhash_rust::xxh3::Xxh3Default;

use crate::aligned::AlignedBuf;
use crate::crypto::{FRAME_OVERHEAD, open_framed, seal_framed};
use crate::error::{FormatError, Result};
use crate::geno_chunks::{CarrierChunk, MaskChunk, MetadataChunk};
use crate::geno_index::{GenoIndex, GenoLocation};
use crate::geno_info::InfoBlock;
use crate::protection::{Protection, STORE_ID_LEN, StoreId};
use crate::{GENO_BLOCKS_MAGIC, GENO_FORMAT_VERSION, GENO_INDEX_MAGIC, MAX_GENO_BLOCK_LEN};

/// Bytes before the first block of `geno.blocks`: the magic and the store id.
pub const GENO_BLOCKS_HEADER_LEN: u64 = (GENO_BLOCKS_MAGIC.len() + STORE_ID_LEN) as u64;

/// Length of [`geno_block_aad`].
pub const GENO_BLOCK_AAD_LEN: usize = 33;

/// Bytes a plain block adds to its body: the xxh3 checksum.
pub const PLAIN_BLOCK_OVERHEAD: usize = 8;

const BLOCKS_FILE: &str = "geno.blocks";

/// The four kinds of genomic block.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GenoBlockKind {
    /// A mask chunk: specs, flags and masks of a run of buckets.
    Mask = 1,
    /// A metadata chunk: the raw INFO strings of a mask chunk's variants.
    Metadata = 2,
    /// A bucket carrier chunk: per bucket of a mask chunk, the patients carrying any variant.
    Carriers = 3,
    /// An info block: the keys, variants and carriers or ranges of one INFO column.
    Info = 4,
}

/// The associated data bound into the genomic block of `kind` at `node` and `ordinal` (the
/// chunk index for kinds 1 to 3, the column index for kind 4) in the store `store_id`.
pub fn geno_block_aad(
    store_id: &StoreId,
    kind: GenoBlockKind,
    node: u16,
    ordinal: u32,
) -> [u8; GENO_BLOCK_AAD_LEN] {
    let mut aad = [0u8; GENO_BLOCK_AAD_LEN];
    aad[..8].copy_from_slice(GENO_INDEX_MAGIC);
    aad[8..24].copy_from_slice(store_id.as_bytes());
    aad[24] = kind as u8;
    aad[25..27].copy_from_slice(&node.to_le_bytes());
    aad[27..31].copy_from_slice(&ordinal.to_le_bytes());
    aad[31..33].copy_from_slice(&GENO_FORMAT_VERSION.to_le_bytes());
    aad
}

fn plain_checksum(aad: &[u8], body: &[u8]) -> u64 {
    let mut hasher = Xxh3Default::new();
    hasher.update(aad);
    hasher.update(body);
    hasher.digest()
}

/// The largest stored length a block of `decoded_len` payload bytes can have:
/// `ZSTD_COMPRESSBOUND(decoded_len)` plus the frame of an encrypted block, or the checksum
/// of a plain one.
pub fn max_stored_len(decoded_len: u32, encrypted: bool) -> u64 {
    let frame = if encrypted {
        FRAME_OVERHEAD
    } else {
        PLAIN_BLOCK_OVERHEAD
    };
    (zstd::zstd_safe::compress_bound(decoded_len as usize) + frame) as u64
}

/// Seals genomic block payloads and opens them again, for one store.
#[derive(Clone, Debug)]
pub struct GenoBlockCodec {
    protection: Protection,
    store_id: StoreId,
}

impl GenoBlockCodec {
    /// A codec for the store `store_id` under `protection`.
    pub fn new(protection: Protection, store_id: StoreId) -> Self {
        GenoBlockCodec {
            protection,
            store_id,
        }
    }

    /// The store whose blocks this codec seals and opens.
    pub fn store_id(&self) -> StoreId {
        self.store_id
    }

    /// True when blocks are encrypted.
    pub fn is_encrypted(&self) -> bool {
        self.protection.is_encrypted()
    }

    /// Compresses `payload` with zstd at level 3, declaring its size in the frame, and seals
    /// it for `kind`, `node` and `ordinal`.
    ///
    /// # Errors
    /// [`FormatError::PayloadTooLarge`] above [`MAX_GENO_BLOCK_LEN`],
    /// [`FormatError::Compression`] when zstd fails, and the errors of
    /// [`crate::crypto::seal_framed`].
    pub fn seal(
        &self,
        kind: GenoBlockKind,
        node: u16,
        ordinal: u32,
        payload: &[u8],
    ) -> Result<Vec<u8>> {
        if payload.len() as u64 > u64::from(MAX_GENO_BLOCK_LEN) {
            return Err(FormatError::PayloadTooLarge {
                what: "genomic block",
                declared: payload.len() as u64,
                limit: u64::from(MAX_GENO_BLOCK_LEN),
            });
        }
        let body = zstd::bulk::compress(payload, zstd::DEFAULT_COMPRESSION_LEVEL)
            .map_err(|e| FormatError::Compression(e.to_string()))?;
        let aad = geno_block_aad(&self.store_id, kind, node, ordinal);
        match &self.protection {
            Protection::Encrypted(key) => seal_framed(key, &body, &aad),
            Protection::Plain => {
                let mut out = Vec::with_capacity(body.len() + PLAIN_BLOCK_OVERHEAD);
                out.extend_from_slice(&body);
                out.extend_from_slice(&plain_checksum(&aad, &body).to_le_bytes());
                Ok(out)
            }
        }
    }

    /// Verifies a stored block sealed for `kind`, `node` and `ordinal` and decompresses it
    /// into an aligned buffer of exactly `decoded_len` bytes, the index's length. Checks the
    /// stored length against the zstd bound before anything else, and the frame's declared
    /// content size before allocating.
    ///
    /// # Errors
    /// [`FormatError::PayloadTooLarge`] when `decoded_len` exceeds [`MAX_GENO_BLOCK_LEN`];
    /// [`FormatError::Corrupt`] when the stored length exceeds the bound, the frame does not
    /// declare its size or declares another, the body holds more than one frame, or the
    /// frame decodes to another length; [`FormatError::Authentication`] or
    /// [`FormatError::Checksum`] when the bytes were altered, belong to another kind, node,
    /// ordinal or store, are plain under an encrypted codec, or the key is wrong;
    /// [`FormatError::Truncated`] for a stored block too short to hold its frame.
    pub fn open(
        &self,
        kind: GenoBlockKind,
        node: u16,
        ordinal: u32,
        stored: &[u8],
        decoded_len: u32,
    ) -> Result<AlignedBuf> {
        let what = "genomic block";
        if decoded_len > MAX_GENO_BLOCK_LEN {
            return Err(FormatError::PayloadTooLarge {
                what,
                declared: u64::from(decoded_len),
                limit: u64::from(MAX_GENO_BLOCK_LEN),
            });
        }
        let bound = max_stored_len(decoded_len, self.is_encrypted());
        if stored.len() as u64 > bound {
            return Err(FormatError::corrupt(
                what,
                format!(
                    "{} stored bytes, above the bound of {bound} for {decoded_len} decoded",
                    stored.len()
                ),
            ));
        }
        let aad = geno_block_aad(&self.store_id, kind, node, ordinal);
        let decrypted;
        let body = match &self.protection {
            Protection::Encrypted(key) => {
                decrypted = open_framed(key, stored, &aad)?;
                &decrypted[..]
            }
            Protection::Plain => {
                let Some((body, checksum)) = stored.split_last_chunk::<PLAIN_BLOCK_OVERHEAD>()
                else {
                    return Err(FormatError::Truncated("genomic block checksum"));
                };
                if plain_checksum(&aad, body) != u64::from_le_bytes(*checksum) {
                    return Err(FormatError::Checksum("genomic block"));
                }
                body
            }
        };
        let frame_len = zstd::zstd_safe::find_frame_compressed_size(body)
            .map_err(|_| FormatError::corrupt(what, "unreadable zstd frame"))?;
        if frame_len != body.len() {
            return Err(FormatError::corrupt(
                what,
                format!(
                    "{} bytes after the zstd frame",
                    body.len().saturating_sub(frame_len)
                ),
            ));
        }
        let declared = zstd::zstd_safe::get_frame_content_size(body)
            .map_err(|_| FormatError::corrupt(what, "unreadable zstd frame header"))?
            .ok_or_else(|| {
                FormatError::corrupt(what, "the zstd frame does not declare its size")
            })?;
        if declared != u64::from(decoded_len) {
            return Err(FormatError::corrupt(
                what,
                format!("the zstd frame declares {declared} bytes, the index {decoded_len}"),
            ));
        }
        let mut out = AlignedBuf::zeroed(decoded_len as usize);
        let written = zstd::bulk::decompress_to_buffer(body, out.as_bytes_mut())
            .map_err(|e| FormatError::corrupt(what, format!("zstd: {e}")))?;
        if written != out.len() {
            return Err(FormatError::corrupt(
                what,
                format!("the frame decoded to {written} bytes, the index says {decoded_len}"),
            ));
        }
        if out.as_bytes().first() != Some(&(kind as u8)) {
            return Err(FormatError::corrupt(
                what,
                format!("the payload is not of kind {}", kind as u8),
            ));
        }
        Ok(out)
    }
}

/// Appends sealed blocks to a `geno.blocks` stream and reports where each one landed.
#[derive(Debug)]
pub struct GenoBlocksWriter<W: Write> {
    inner: W,
    offset: u64,
}

impl<W: Write> GenoBlocksWriter<W> {
    /// Writes the file magic and `store_id` to `inner` and returns a writer positioned after
    /// them.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the write fails.
    pub fn new(mut inner: W, store_id: StoreId) -> Result<Self> {
        inner.write_all(GENO_BLOCKS_MAGIC)?;
        inner.write_all(store_id.as_bytes())?;
        Ok(GenoBlocksWriter {
            inner,
            offset: GENO_BLOCKS_HEADER_LEN,
        })
    }

    /// Appends an already sealed block whose payload is `decoded_len` bytes.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when the stored block exceeds `u32::MAX` bytes or the
    /// decoded length exceeds [`MAX_GENO_BLOCK_LEN`], and [`FormatError::Io`].
    pub fn append_stored(&mut self, stored: &[u8], decoded_len: u32) -> Result<GenoLocation> {
        let stored_len = u32::try_from(stored.len()).map_err(|_| {
            FormatError::InvalidInput(format!("a stored block of {} bytes", stored.len()))
        })?;
        if decoded_len > MAX_GENO_BLOCK_LEN {
            return Err(FormatError::InvalidInput(format!(
                "a block of {decoded_len} decoded bytes"
            )));
        }
        self.inner.write_all(stored)?;
        let location = GenoLocation {
            offset: self.offset,
            stored_len,
            decoded_len,
        };
        self.offset += u64::from(stored_len);
        Ok(location)
    }

    /// Seals `payload` with `codec` for `kind`, `node` and `ordinal` and appends it.
    ///
    /// # Errors
    /// The errors of [`GenoBlockCodec::seal`] and [`GenoBlocksWriter::append_stored`].
    pub fn append(
        &mut self,
        codec: &GenoBlockCodec,
        kind: GenoBlockKind,
        node: u16,
        ordinal: u32,
        payload: &[u8],
    ) -> Result<GenoLocation> {
        let stored = codec.seal(kind, node, ordinal, payload)?;
        self.append_stored(&stored, payload.len() as u32)
    }

    /// Bytes written so far, including the header.
    pub fn len(&self) -> u64 {
        self.offset
    }

    /// True when no block has been appended yet.
    pub fn is_empty(&self) -> bool {
        self.offset == GENO_BLOCKS_HEADER_LEN
    }

    /// Flushes and returns the underlying writer.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the flush fails.
    pub fn finish(mut self) -> Result<W> {
        self.inner.flush()?;
        Ok(self.inner)
    }
}

/// Reads sealed blocks from `geno.blocks` with positioned reads that can run concurrently
/// from many threads. Every read is a blocking `pread`.
#[derive(Debug)]
pub struct GenoBlocksReader {
    file: File,
    len: u64,
    store_id: StoreId,
}

impl GenoBlocksReader {
    /// Opens `path`, checks its magic, that it belongs to `index`'s store, and that every
    /// location `index` records lies inside the file.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the file cannot be opened or read,
    /// [`FormatError::BadMagic`] when it is not a genomic blocks file,
    /// [`FormatError::StoreMismatch`] when it belongs to another store, and
    /// [`FormatError::Corrupt`] when a location lies outside it.
    pub fn open(path: impl AsRef<Path>, index: &GenoIndex) -> Result<Self> {
        let file = File::open(path)?;
        let len = file.metadata()?.len();
        if len < GENO_BLOCKS_HEADER_LEN {
            return Err(FormatError::BadMagic { file: BLOCKS_FILE });
        }
        let mut header = [0u8; GENO_BLOCKS_HEADER_LEN as usize];
        file.read_exact_at(&mut header, 0)?;
        let (magic, id) = header.split_at(GENO_BLOCKS_MAGIC.len());
        if magic != GENO_BLOCKS_MAGIC {
            return Err(FormatError::BadMagic { file: BLOCKS_FILE });
        }
        if id != index.store_id().as_bytes() {
            return Err(FormatError::StoreMismatch);
        }
        if let Some(location) = index.locations().find(|l| l.end() > len) {
            return Err(FormatError::corrupt(
                "genomic block location",
                format!("{location:?} outside a {len}-byte file"),
            ));
        }
        Ok(GenoBlocksReader {
            file,
            len,
            store_id: index.store_id(),
        })
    }

    /// The store the file belongs to.
    pub fn store_id(&self) -> StoreId {
        self.store_id
    }

    /// File length in bytes.
    pub fn len(&self) -> u64 {
        self.len
    }

    /// True when the file holds only its header.
    pub fn is_empty(&self) -> bool {
        self.len <= GENO_BLOCKS_HEADER_LEN
    }

    /// The state of the open file now (`fstat`), which a rewrite in place changes.
    ///
    /// # Errors
    /// The I/O error of `fstat`.
    pub fn file_metadata(&self) -> std::io::Result<std::fs::Metadata> {
        self.file.metadata()
    }

    /// Reads the stored bytes at `location` without verifying them. Blocking.
    ///
    /// # Errors
    /// [`FormatError::Corrupt`] when the location lies outside the file,
    /// [`FormatError::Io`] when the read fails.
    pub fn read_stored(&self, location: GenoLocation) -> Result<Vec<u8>> {
        if location.offset < GENO_BLOCKS_HEADER_LEN || location.end() > self.len {
            return Err(FormatError::corrupt(
                "genomic block location",
                format!("{location:?} outside a {}-byte file", self.len),
            ));
        }
        let mut stored = vec![0u8; location.stored_len as usize];
        self.file.read_exact_at(&mut stored, location.offset)?;
        Ok(stored)
    }

    /// Reads and opens the block of `kind` at `location` for `node` and `ordinal`, without
    /// decoding its payload.
    ///
    /// # Errors
    /// [`FormatError::StoreMismatch`] when `codec` is for another store, and the errors of
    /// [`GenoBlocksReader::read_stored`] and [`GenoBlockCodec::open`].
    pub fn read_payload(
        &self,
        codec: &GenoBlockCodec,
        kind: GenoBlockKind,
        node: u16,
        ordinal: u32,
        location: GenoLocation,
    ) -> Result<AlignedBuf> {
        if codec.store_id() != self.store_id {
            return Err(FormatError::StoreMismatch);
        }
        codec.open(
            kind,
            node,
            ordinal,
            &self.read_stored(location)?,
            location.decoded_len,
        )
    }

    /// Reads, opens and decodes the mask chunk `chunk` of node `node`, checked against
    /// `index`.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when `node` or `chunk` is not in `index`, and the errors
    /// of [`GenoBlocksReader::read_payload`] and [`MaskChunk::decode`].
    pub fn read_mask_chunk(
        &self,
        codec: &GenoBlockCodec,
        index: &GenoIndex,
        node: u16,
        chunk: u32,
    ) -> Result<MaskChunk> {
        let expected = index.expected_mask_chunk(node, chunk)?;
        let location = index.chunk(node, chunk)?.mask;
        MaskChunk::decode(
            self.read_payload(codec, GenoBlockKind::Mask, node, chunk, location)?,
            &expected,
        )
    }

    /// Reads, opens and decodes the metadata chunk `chunk` of node `node`, checked against
    /// `index`.
    ///
    /// # Errors
    /// As [`GenoBlocksReader::read_mask_chunk`], with [`MetadataChunk::decode`].
    pub fn read_metadata_chunk(
        &self,
        codec: &GenoBlockCodec,
        index: &GenoIndex,
        node: u16,
        chunk: u32,
    ) -> Result<MetadataChunk> {
        let entry = index.chunk(node, chunk)?;
        MetadataChunk::decode(
            self.read_payload(codec, GenoBlockKind::Metadata, node, chunk, entry.metadata)?,
            entry.variant_count,
        )
    }

    /// Reads, opens and decodes the carrier chunk `chunk` of node `node`, checked against
    /// `index`.
    ///
    /// # Errors
    /// As [`GenoBlocksReader::read_mask_chunk`], with [`CarrierChunk::decode`].
    pub fn read_carrier_chunk(
        &self,
        codec: &GenoBlockCodec,
        index: &GenoIndex,
        node: u16,
        chunk: u32,
    ) -> Result<CarrierChunk> {
        let entry = index.chunk(node, chunk)?;
        let patient_count = index.node_patient_count(node)?;
        CarrierChunk::decode(
            self.read_payload(codec, GenoBlockKind::Carriers, node, chunk, entry.carriers)?,
            entry.bucket_count,
            patient_count,
        )
    }

    /// Reads, opens and decodes node `node`'s info block for column `column` (an index into
    /// the column table), checked against `index`.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when the node holds no block for the column, and the
    /// errors of [`GenoBlocksReader::read_payload`] and [`InfoBlock::decode`].
    pub fn read_info_block(
        &self,
        codec: &GenoBlockCodec,
        index: &GenoIndex,
        node: u16,
        column: u16,
    ) -> Result<InfoBlock> {
        let (location, expected) = index.expected_info_block(node, column)?;
        InfoBlock::decode(
            self.read_payload(
                codec,
                GenoBlockKind::Info,
                node,
                u32::from(column),
                location,
            )?,
            &expected,
        )
    }
}
