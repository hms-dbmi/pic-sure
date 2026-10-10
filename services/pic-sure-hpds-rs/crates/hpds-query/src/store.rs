//! The phenotype store: `pheno.index` held in memory, `pheno.blocks` read on demand into a
//! byte-bounded cache of decoded blocks. Stores opened together by a
//! [`crate::PartitionSet`] share one cache and so one byte budget.

use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlockLocation, BlocksReader, ConceptView, INDEX_FILE_NAME,
    IndexBody, PhenoIndex, Protection, StoreId,
};
use moka::sync::Cache;
use roaring::RoaringBitmap;

use crate::error::{QueryError, Result};

/// Name of the key file inside the data directory, as the Java service reads
/// `/opt/local/hpds/encryption_key`.
pub const KEY_FILE_NAME: &str = "encryption_key";

/// Default capacity of the decoded block cache: 4 GiB.
pub const DEFAULT_CACHE_BYTES: u64 = 4 << 30;

/// Decoded blocks keyed by the owning store's slot and the concept's position in the
/// partition set's shared concept table, so a lookup never allocates.
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

/// All a store's index says about one of its concepts that the engine reads: the
/// observation count, where the block is and the bound on its decoded size. The path, which
/// the block's authentication also binds, and the rest of the metadata live once in the
/// partition set's merged dictionary.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ConceptEntry {
    pub(crate) observation_count: u32,
    location: BlockLocation,
    max_payload_len: u64,
}

impl ConceptEntry {
    /// The entry of `concept`.
    pub(crate) fn of(concept: &ConceptView<'_>) -> Self {
        ConceptEntry {
            observation_count: concept.observation_count,
            location: concept.block,
            max_payload_len: concept.max_payload_len(),
        }
    }
}

/// The store id in the plain header of the index in `dir`, read without verifying the
/// index. [`StoreFiles::open`] verifies it later; this lets a caller compare ids before it
/// decodes any index.
///
/// # Errors
/// [`QueryError::Open`] when the file cannot be read or its header does not parse.
pub(crate) fn read_store_id(dir: &Path) -> Result<StoreId> {
    let path = dir.join(INDEX_FILE_NAME);
    let mut head = Vec::with_capacity(HEADER_READ_LEN);
    std::fs::File::open(&path)
        .and_then(|file| file.take(HEADER_READ_LEN as u64).read_to_end(&mut head))
        .map_err(|e| open_error(dir, e))?;
    PhenoIndex::decode_header(&head)
        .map(|header| header.store_id)
        .map_err(|e| open_error(dir, e))
}

/// Bytes read from the start of an index for [`read_store_id`], far more than any header.
const HEADER_READ_LEN: usize = 64 << 10;

/// A store's files, opened and its index verified, before its concept table is read.
/// [`StoreFiles::into_store`] then keeps only the patient set of the index.
pub(crate) struct StoreFiles {
    dir: PathBuf,
    index: IndexBody,
    reader: BlocksReader,
    codec: BlockCodec,
}

impl StoreFiles {
    /// Opens the store in `dir`: verifies `pheno.index` under `protection` and opens
    /// `pheno.blocks` for its store id.
    ///
    /// # Errors
    /// [`QueryError::Open`] when either file is missing, corrupt, from another store, or
    /// protected differently from `protection`.
    pub(crate) fn open(dir: &Path, protection: Protection) -> Result<Self> {
        let index = IndexBody::read_file(dir.join(INDEX_FILE_NAME), &protection)
            .map_err(|e| open_error(dir, e))?;
        let reader = BlocksReader::open(dir.join(BLOCKS_FILE_NAME), index.store_id())
            .map_err(|e| open_error(dir, e))?;
        let codec = index
            .block_codec(protection)
            .map_err(|e| open_error(dir, e))?;
        Ok(StoreFiles {
            dir: dir.to_path_buf(),
            index,
            reader,
            codec,
        })
    }

    /// The store directory.
    pub(crate) fn dir(&self) -> &Path {
        &self.dir
    }

    /// The verified index, whose concept table the caller reads.
    pub(crate) fn index(&self) -> &IndexBody {
        &self.index
    }

    /// The open store, with blocks cached in `cache` under `slot`, which must be unique
    /// among the stores sharing that cache, and `entries` its concepts' entries in index
    /// order. The index body is dropped; only its patient set is kept.
    pub(crate) fn into_store(
        self,
        cache: BlockCache,
        slot: u32,
        entries: Vec<ConceptEntry>,
    ) -> Store {
        Store {
            dir: self.dir,
            entries,
            patients: self.index.into_patients(),
            reader: self.reader,
            codec: self.codec,
            cache,
            slot,
        }
    }
}

/// One open store. Every method takes `&self` and is safe to call from many threads.
/// Crate-private: patient data is read through the engine, scoped by consent.
///
/// Of its index it keeps one [`ConceptEntry`] per concept, in index order; paths and the
/// other metadata live once in the [`crate::PartitionSet`]'s merged dictionary. Block loads
/// are blocking (a positioned read, then decryption and decompression on the calling
/// thread).
pub(crate) struct Store {
    dir: PathBuf,
    entries: Vec<ConceptEntry>,
    patients: RoaringBitmap,
    reader: BlocksReader,
    codec: BlockCodec,
    cache: BlockCache,
    slot: u32,
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("dir", &self.dir)
            .field("patients", &self.patients.len())
            .field("encrypted", &self.codec.is_encrypted())
            .finish_non_exhaustive()
    }
}

impl Store {
    /// Every patient id in the store.
    pub(crate) fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// The entry of the concept at `position` in this store's index.
    pub(crate) fn entry(&self, position: u32) -> ConceptEntry {
        self.entries[position as usize]
    }

    /// The decoded block of the concept at `position` in this store's index, whose path is
    /// `path`, cached under the concept's position `concept` in the shared table, from the
    /// cache or loaded from disk. Concurrent calls for the same uncached block load it
    /// once; the others wait for that load. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read, authenticated or decoded.
    pub(crate) fn block(&self, concept: u32, position: u32, path: &str) -> Result<Arc<Block>> {
        let entry = self.entry(position);
        self.cache
            .try_get_with((self.slot, concept), || {
                self.reader
                    .read_block_at(&self.codec, path, entry.location, entry.max_payload_len)
                    .map(Arc::new)
            })
            .map_err(|source| QueryError::Block {
                concept_path: path.to_owned(),
                source,
            })
    }
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}
