//! The `pheno.blocks` file: an 8-byte magic and the 16-byte store id, followed by stored
//! blocks back to back.

use std::fs::File;
use std::io::Write;
use std::os::unix::fs::FileExt;
use std::path::Path;

use crate::BLOCKS_MAGIC;
use crate::block::{Block, BlockCodec};
use crate::error::{FormatError, Result};
use crate::index::{BlockLocation, ConceptMeta};
use crate::protection::{STORE_ID_LEN, StoreId};

/// Bytes before the first block: the magic and the store id.
pub const BLOCKS_HEADER_LEN: u64 = (BLOCKS_MAGIC.len() + STORE_ID_LEN) as u64;

/// Appends stored blocks to a `pheno.blocks` stream and reports where each one landed.
#[derive(Debug)]
pub struct BlocksWriter<W: Write> {
    inner: W,
    offset: u64,
}

impl<W: Write> BlocksWriter<W> {
    /// Writes the file magic and `store_id` to `inner` and returns a writer positioned
    /// after them. Use the store id of the index and codec the blocks belong to.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the write fails.
    pub fn new(mut inner: W, store_id: StoreId) -> Result<Self> {
        inner.write_all(BLOCKS_MAGIC)?;
        inner.write_all(store_id.as_bytes())?;
        Ok(BlocksWriter {
            inner,
            offset: BLOCKS_HEADER_LEN,
        })
    }

    /// Appends an already sealed block.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the write fails.
    pub fn append_stored(&mut self, stored: &[u8]) -> Result<BlockLocation> {
        self.inner.write_all(stored)?;
        let location = BlockLocation {
            offset: self.offset,
            length: stored.len() as u64,
        };
        self.offset += location.length;
        Ok(location)
    }

    /// Seals `payload` for `concept_path` with `codec` and appends it.
    ///
    /// # Errors
    /// The errors of [`BlockCodec::seal`] and [`FormatError::Io`].
    pub fn append(
        &mut self,
        codec: &BlockCodec,
        concept_path: &str,
        payload: &[u8],
    ) -> Result<BlockLocation> {
        let stored = codec.seal(concept_path, payload)?;
        self.append_stored(&stored)
    }

    /// Bytes written so far, including the header.
    pub fn len(&self) -> u64 {
        self.offset
    }

    /// True when no block has been appended yet.
    pub fn is_empty(&self) -> bool {
        self.offset == BLOCKS_HEADER_LEN
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

/// Reads stored blocks from `pheno.blocks` by location, with positioned reads that can run
/// concurrently from many threads.
///
/// Every read is a blocking `pread`. From async code, call [`BlocksReader::read_block`] and
/// [`BlocksReader::read_stored`] inside `spawn_blocking` or on a dedicated thread pool.
#[derive(Debug)]
pub struct BlocksReader {
    file: File,
    len: u64,
    store_id: StoreId,
}

impl BlocksReader {
    /// Opens `path`, checks its magic, and checks that it belongs to the store
    /// `store_id` (the index's [`crate::PhenoIndex::store_id`]).
    ///
    /// # Errors
    /// [`FormatError::Io`] when the file cannot be opened or read,
    /// [`FormatError::BadMagic`] when it is not a blocks file,
    /// [`FormatError::StoreMismatch`] when it belongs to another store.
    pub fn open(path: impl AsRef<Path>, store_id: StoreId) -> Result<Self> {
        let file = File::open(path)?;
        let len = file.metadata()?.len();
        let mut header = [0u8; BLOCKS_HEADER_LEN as usize];
        if len < BLOCKS_HEADER_LEN {
            return Err(FormatError::BadMagic {
                file: "pheno.blocks",
            });
        }
        file.read_exact_at(&mut header, 0)?;
        let (magic, id) = header.split_at(BLOCKS_MAGIC.len());
        if magic != BLOCKS_MAGIC {
            return Err(FormatError::BadMagic {
                file: "pheno.blocks",
            });
        }
        if id != store_id.as_bytes() {
            return Err(FormatError::StoreMismatch);
        }
        Ok(BlocksReader {
            file,
            len,
            store_id,
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
        self.len <= BLOCKS_HEADER_LEN
    }

    /// Reads the stored bytes at `location` without verifying them. Blocking.
    ///
    /// # Errors
    /// [`FormatError::Corrupt`] when the location lies outside the file,
    /// [`FormatError::Io`] when the read fails.
    pub fn read_stored(&self, location: BlockLocation) -> Result<Vec<u8>> {
        let end = location.offset.checked_add(location.length);
        if location.offset < BLOCKS_HEADER_LEN || end.is_none_or(|end| end > self.len) {
            return Err(FormatError::corrupt(
                "block location",
                format!("{location:?} outside a {}-byte file", self.len),
            ));
        }
        let length = usize::try_from(location.length)
            .map_err(|_| FormatError::corrupt("block location", "length exceeds address space"))?;
        let mut stored = vec![0u8; length];
        self.file.read_exact_at(&mut stored, location.offset)?;
        Ok(stored)
    }

    /// Reads, verifies and decodes the block of `concept`, bounding its decoded size by
    /// [`ConceptMeta::max_payload_len`]. Blocking: this does a positioned read and then
    /// decrypts and decompresses on the calling thread.
    ///
    /// # Errors
    /// [`FormatError::StoreMismatch`] when `codec` is for another store, and the errors of
    /// [`BlocksReader::read_stored`] and [`BlockCodec::open`].
    pub fn read_block(&self, codec: &BlockCodec, concept: &ConceptMeta) -> Result<Block> {
        self.read_block_at(
            codec,
            &concept.path,
            concept.block,
            concept.max_payload_len(),
        )
    }

    /// [`BlocksReader::read_block`] from the three things it reads of the concept: its
    /// path, its block location and its decoded size bound
    /// ([`crate::ConceptView::max_payload_len`]).
    ///
    /// # Errors
    /// As for [`BlocksReader::read_block`].
    pub fn read_block_at(
        &self,
        codec: &BlockCodec,
        concept_path: &str,
        location: BlockLocation,
        max_payload_len: u64,
    ) -> Result<Block> {
        if codec.store_id() != self.store_id {
            return Err(FormatError::StoreMismatch);
        }
        codec.open(concept_path, &self.read_stored(location)?, max_payload_len)
    }
}
