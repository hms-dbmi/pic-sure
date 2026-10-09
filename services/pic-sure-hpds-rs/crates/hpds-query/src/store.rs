//! The phenotype store: `pheno.index` held in memory, `pheno.blocks` read on demand into a
//! byte-bounded cache of decoded blocks. Stores opened together by a
//! [`crate::PartitionSet`] share one cache and so one byte budget.

use std::path::{Path, PathBuf};
use std::sync::Arc;

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlocksReader, INDEX_FILE_NAME, PhenoIndex, Protection,
};
use moka::sync::Cache;

use crate::error::{QueryError, Result};

/// Name of the key file inside the data directory, as the Java service reads
/// `/opt/local/hpds/encryption_key`.
pub const KEY_FILE_NAME: &str = "encryption_key";

/// Default capacity of the decoded block cache: 4 GiB.
pub const DEFAULT_CACHE_BYTES: u64 = 4 << 30;

/// Decoded blocks keyed by the owning store's slot and the concept's position in that
/// store's index, so a lookup never allocates.
pub(crate) type BlockCache = Cache<(u32, u32), Arc<Block>>;

/// A block cache bounded by the summed [`Block::heap_size`] of its entries.
pub(crate) fn block_cache(cache_bytes: u64) -> BlockCache {
    Cache::builder()
        .max_capacity(cache_bytes)
        .weigher(|_: &(u32, u32), block: &Arc<Block>| {
            u32::try_from(block.heap_size()).unwrap_or(u32::MAX)
        })
        .build()
}

/// How the partition stores are opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct StoreOptions {
    /// Upper bound on the summed [`Block::heap_size`] of cached blocks, in bytes.
    pub cache_bytes: u64,
    /// Open a plain store when the data directory holds no key file. Off by default: a
    /// missing key is then an error. For local benchmarking only.
    pub allow_plain: bool,
}

impl Default for StoreOptions {
    /// A 4 GiB cache, and a key file required.
    fn default() -> Self {
        StoreOptions {
            cache_bytes: DEFAULT_CACHE_BYTES,
            allow_plain: false,
        }
    }
}

/// One open partition store. Every method takes `&self` and is safe to call from many
/// threads. Crate-private: patient data is read through the engine, scoped by consent.
///
/// Block loads are blocking (a positioned read, then decryption and decompression on the
/// calling thread).
pub(crate) struct Store {
    dir: PathBuf,
    index: PhenoIndex,
    reader: BlocksReader,
    codec: BlockCodec,
    cache: BlockCache,
    slot: u32,
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("dir", &self.dir)
            .field("concepts", &self.index.concepts().len())
            .field("patients", &self.index.patients().len())
            .field("encrypted", &self.codec.is_encrypted())
            .finish_non_exhaustive()
    }
}

impl Store {
    /// Opens the store in `dir` with blocks cached in `cache` under `slot`, which must be
    /// unique among the stores sharing that cache.
    pub(crate) fn open_shared(
        dir: &Path,
        protection: Protection,
        cache: BlockCache,
        slot: u32,
    ) -> Result<Self> {
        let index = PhenoIndex::read_file(dir.join(INDEX_FILE_NAME), &protection)
            .map_err(|e| open_error(dir, e))?;
        let reader = BlocksReader::open(dir.join(BLOCKS_FILE_NAME), index.store_id())
            .map_err(|e| open_error(dir, e))?;
        let codec = index
            .block_codec(protection)
            .map_err(|e| open_error(dir, e))?;
        Ok(Store {
            dir: dir.to_path_buf(),
            index,
            reader,
            codec,
            cache,
            slot,
        })
    }

    /// The decoded index: every concept's metadata and every patient id.
    pub(crate) fn index(&self) -> &PhenoIndex {
        &self.index
    }

    /// The decoded block of the concept at `path`, from the cache or loaded from disk, or
    /// `None` when there is no such concept (Java's `getCube` returning `Optional.empty()`).
    ///
    /// Concurrent calls for the same uncached path load it once; the others wait for that
    /// load. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read, authenticated or decoded.
    pub(crate) fn block(&self, path: &str) -> Result<Option<Arc<Block>>> {
        let Some(position) = self.index.position(path) else {
            return Ok(None);
        };
        let concept = &self.index.concepts()[position];
        self.cache
            .try_get_with(self.cache_key(position), || {
                self.reader.read_block(&self.codec, concept).map(Arc::new)
            })
            .map(Some)
            .map_err(|source| QueryError::Block {
                concept_path: path.to_owned(),
                source,
            })
    }

    fn cache_key(&self, position: usize) -> (u32, u32) {
        (self.slot, position as u32)
    }
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}
