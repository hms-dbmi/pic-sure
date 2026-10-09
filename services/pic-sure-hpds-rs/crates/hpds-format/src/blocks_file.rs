//! The `pheno.blocks` file: an 8-byte magic followed by stored blocks back to back.

use std::fs::File;
use std::io::Write;
use std::os::unix::fs::FileExt;
use std::path::Path;

use crate::BLOCKS_MAGIC;
use crate::block::{Block, BlockCodec};
use crate::error::{FormatError, Result};
use crate::index::BlockLocation;

/// Appends stored blocks to a `pheno.blocks` stream and reports where each one landed.
#[derive(Debug)]
pub struct BlocksWriter<W: Write> {
    inner: W,
    offset: u64,
}

impl<W: Write> BlocksWriter<W> {
    /// Writes the file magic to `inner` and returns a writer positioned after it.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the write fails.
    pub fn new(mut inner: W) -> Result<Self> {
        inner.write_all(BLOCKS_MAGIC)?;
        Ok(BlocksWriter {
            inner,
            offset: BLOCKS_MAGIC.len() as u64,
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

    /// Bytes written so far, including the magic.
    pub fn len(&self) -> u64 {
        self.offset
    }

    /// True when no block has been appended yet.
    pub fn is_empty(&self) -> bool {
        self.offset == BLOCKS_MAGIC.len() as u64
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
#[derive(Debug)]
pub struct BlocksReader {
    file: File,
    len: u64,
}

impl BlocksReader {
    /// Opens `path` and checks its magic.
    ///
    /// # Errors
    /// [`FormatError::Io`] when the file cannot be opened or read,
    /// [`FormatError::BadMagic`] when it is not a blocks file.
    pub fn open(path: impl AsRef<Path>) -> Result<Self> {
        let file = File::open(path)?;
        let len = file.metadata()?.len();
        let mut magic = [0u8; 8];
        if len < magic.len() as u64 {
            return Err(FormatError::BadMagic {
                file: "pheno.blocks",
            });
        }
        file.read_exact_at(&mut magic, 0)?;
        if &magic != BLOCKS_MAGIC {
            return Err(FormatError::BadMagic {
                file: "pheno.blocks",
            });
        }
        Ok(BlocksReader { file, len })
    }

    /// File length in bytes.
    pub fn len(&self) -> u64 {
        self.len
    }

    /// True when the file holds only its magic.
    pub fn is_empty(&self) -> bool {
        self.len <= BLOCKS_MAGIC.len() as u64
    }

    /// Reads the stored bytes at `location` without verifying them.
    ///
    /// # Errors
    /// [`FormatError::Corrupt`] when the location lies outside the file,
    /// [`FormatError::Io`] when the read fails.
    pub fn read_stored(&self, location: BlockLocation) -> Result<Vec<u8>> {
        let end = location.offset.checked_add(location.length);
        if location.offset < BLOCKS_MAGIC.len() as u64 || end.is_none_or(|end| end > self.len) {
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

    /// Reads, verifies and decodes the block for `concept_path` at `location`.
    ///
    /// # Errors
    /// The errors of [`BlocksReader::read_stored`] and [`BlockCodec::open`].
    pub fn read_block(
        &self,
        codec: &BlockCodec,
        concept_path: &str,
        location: BlockLocation,
    ) -> Result<Block> {
        codec.open(concept_path, &self.read_stored(location)?)
    }
}
