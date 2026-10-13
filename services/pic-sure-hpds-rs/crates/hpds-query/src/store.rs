//! The phenotype store: `pheno.index` held in memory, `pheno.blocks` read on demand into a
//! byte-bounded cache of decoded blocks and their partition views, which a
//! [`crate::PartitionSet`] holds.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError, RwLock, Weak};

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlockLocation, BlocksReader, ConceptView, ExpectedBlock,
    INDEX_FILE_NAME, IndexBody, Membership, PartitionHolder, PartitionTable, Protection,
};
use moka::notification::RemovalCause;
use moka::sync::Cache;
use roaring::RoaringBitmap;

use crate::counters::BlockCacheCounters;
use crate::error::{QueryError, Result};
use crate::view::View;

/// Name of the key file inside the data directory, as the Java service reads
/// `/opt/local/hpds/encryption_key`.
pub const KEY_FILE_NAME: &str = "encryption_key";

/// Default capacity of the decoded block cache: 4 GiB.
pub const DEFAULT_CACHE_BYTES: u64 = 4 << 30;

/// Decoded concepts, one entry per concept keyed by its position in the index, so a lookup
/// never allocates.
pub(crate) type BlockCache = Cache<u32, Arc<CachedConcept>>;

/// Bytes an entry is weighed at beyond its block and view.
const ENTRY_OVERHEAD: usize = 64;

/// A block cache bounded by the summed weight of its entries ([`CachedConcept::weight`]). A
/// `moka` weigher returns a `u32`, so an entry heavier than `u32::MAX` bytes (4 GiB) is
/// weighed at `u32::MAX`, under its real size; at the default 4 GiB bound such an entry
/// already fills the cache on its own. Every entry dropped to keep the bound is counted in
/// `counters.evictions`; an entry replaced by its reweighed self is not. Every entry the
/// cache drops for any other reason than being replaced is marked dropped
/// ([`CachedConcept::is_dropped`]) and forgotten by `recent`.
pub(crate) fn block_cache(
    cache_bytes: u64,
    counters: Arc<BlockCacheCounters>,
    recent: Arc<RecentEntries>,
) -> BlockCache {
    Cache::builder()
        .max_capacity(cache_bytes)
        .weigher(|_: &u32, concept: &Arc<CachedConcept>| {
            u32::try_from(concept.weight()).unwrap_or(u32::MAX)
        })
        .eviction_listener(move |index: Arc<u32>, concept, cause| {
            if cause == RemovalCause::Size {
                counters.evictions.fetch_add(1, Ordering::Relaxed);
            }
            if cause != RemovalCause::Replaced {
                concept.mark_dropped();
                recent.forget(*index as usize, &concept);
            }
        })
        .build()
}

/// A multi-concept read sends a concept's lookup through the block cache on one call in
/// this many, and takes it from [`RecentEntries`] on the others, so the cache keeps
/// seeing how often and how recently each concept is read, at this fraction of the reads.
/// A lookup through the cache costs such a read about 2 to 3 µs in a Linux container, so
/// one in 16 still slowed a thousand-concept read by about 5%; one in 64 does not
/// measurably.
pub(crate) const RECORD_EVERY: usize = 64;

/// The block cache entry last found for each concept, held weakly, so that a read of
/// thousands of cached concepts (a study-level `ANY_RECORD_OF`) can skip most of its
/// `moka` lookups, which in a Linux container cost several times a lookup here.
///
/// The table serves only entries the cache has not dropped. The cache's eviction listener
/// marks every entry it drops ([`CachedConcept::mark_dropped`]) before it clears the
/// entry's slot, the table never remembers a marked entry, and it never serves one.
/// Whichever comes first, the drop or a read remembering the entry it loaded, the entry is
/// not served after the drop: a read that loads a block can see the cache drop it during
/// the same load (the cache housekeeps on the inserting thread), and the mark is on the
/// entry itself, so it holds however the remember and the listener interleave. The one gap
/// is the instant between the cache removing an entry and its listener marking it, in
/// which a lookup through the cache could have returned the entry too. A slot holds its
/// entry weakly, so it never keeps a block or view alive.
///
/// Memory outside the byte bound: a slot whose entry was dropped without the listener
/// clearing it keeps that entry's small allocation (the struct, about 100 bytes, not its
/// block or view) until the concept is remembered again. That is at most one per concept,
/// a few MB on a dictionary of tens of thousands of concepts.
#[derive(Debug)]
pub(crate) struct RecentEntries {
    slots: RwLock<Box<[Weak<CachedConcept>]>>,
}

impl RecentEntries {
    /// An empty table for `concepts` concepts.
    pub(crate) fn new(concepts: usize) -> Self {
        RecentEntries {
            slots: RwLock::new((0..concepts).map(|_| Weak::new()).collect()),
        }
    }

    /// For each of `indexes`, in order, the entry remembered for that concept while the
    /// cache or a query still holds it, or `None`; also `None` for every index `skip`
    /// names. Takes the table's lock once, and releases it before returning, so the
    /// caller may go on to the cache, whose eviction listener locks the table.
    pub(crate) fn get_all(
        &self,
        indexes: impl Iterator<Item = usize>,
        skip: impl Fn(usize) -> bool,
    ) -> Vec<Option<Arc<CachedConcept>>> {
        let slots = self.slots.read().unwrap_or_else(PoisonError::into_inner);
        indexes
            .map(|index| {
                if skip(index) {
                    None
                } else {
                    slots
                        .get(index)
                        .and_then(Weak::upgrade)
                        .filter(|entry| !entry.is_dropped())
                }
            })
            .collect()
    }

    /// Remembers each `(index, entry)` as the entry of the concept at `index`, except an
    /// entry the cache has already dropped. Takes the table's lock for writing only when
    /// some entry is new to it, and checks the drop mark under that lock, which the
    /// eviction listener also takes after marking.
    pub(crate) fn remember_all<'a>(
        &self,
        entries: impl Iterator<Item = (usize, &'a Arc<CachedConcept>)> + Clone,
    ) {
        let differs = |slots: &[Weak<CachedConcept>], index: usize, entry: &Arc<CachedConcept>| {
            !entry.is_dropped()
                && slots
                    .get(index)
                    .is_some_and(|held| !std::ptr::eq(held.as_ptr(), Arc::as_ptr(entry)))
        };
        {
            let slots = self.slots.read().unwrap_or_else(PoisonError::into_inner);
            if !entries
                .clone()
                .any(|(index, entry)| differs(&slots, index, entry))
            {
                return;
            }
        }
        let mut slots = self.slots.write().unwrap_or_else(PoisonError::into_inner);
        for (index, entry) in entries {
            if differs(&slots, index, entry) {
                slots[index] = Arc::downgrade(entry);
            }
        }
    }

    /// Forgets the entry of the concept at `index` when it is `entry`.
    pub(crate) fn forget(&self, index: usize, entry: &Arc<CachedConcept>) {
        let mut slots = self.slots.write().unwrap_or_else(PoisonError::into_inner);
        if let Some(held) = slots.get_mut(index)
            && std::ptr::eq(held.as_ptr(), Arc::as_ptr(entry))
        {
            *held = Weak::new();
        }
    }

    /// Forgets every entry. A read running across the clear may remember the entries it
    /// loads afterwards; those are new entries the cache holds, while every entry from
    /// before the clear is marked dropped by the cache's listener and never served again.
    pub(crate) fn clear(&self) {
        let mut slots = self.slots.write().unwrap_or_else(PoisonError::into_inner);
        slots.iter_mut().for_each(|held| *held = Weak::new());
    }
}

/// One concept's decoded block, every partition's rows, and the view of those rows by
/// partition that narrow selections read, built on first use. The cache weighs an entry
/// when it is inserted, so the reader that builds the view inserts the entry again to have
/// it reweighed.
#[derive(Debug)]
pub(crate) struct CachedConcept {
    block: Arc<Block>,
    view: OnceLock<Arc<View>>,
    building: Mutex<()>,
    dropped: AtomicBool,
}

impl CachedConcept {
    /// An entry holding `block` and no view yet.
    pub(crate) fn new(block: Block) -> Self {
        CachedConcept {
            block: Arc::new(block),
            view: OnceLock::new(),
            building: Mutex::new(()),
            dropped: AtomicBool::new(false),
        }
    }

    /// Marks the entry as dropped by the block cache, for good: [`RecentEntries`] never
    /// serves or remembers it again, and a reader that builds its view does not put it back
    /// into the cache.
    pub(crate) fn mark_dropped(&self) {
        self.dropped.store(true, Ordering::Release);
    }

    /// Whether the block cache has dropped the entry ([`CachedConcept::mark_dropped`]).
    pub(crate) fn is_dropped(&self) -> bool {
        self.dropped.load(Ordering::Acquire)
    }

    /// The decoded block.
    pub(crate) fn block(&self) -> &Arc<Block> {
        &self.block
    }

    /// The view, when it has been built.
    pub(crate) fn view(&self) -> Option<&Arc<View>> {
        self.view.get()
    }

    /// The view, built by `build` when no reader has built it yet, and whether this call
    /// built it. One reader builds; readers arriving during the build wait for it and get
    /// its view.
    ///
    /// # Errors
    /// The error of `build`; a later call builds again.
    pub(crate) fn view_or_build(
        &self,
        build: impl FnOnce(&Block) -> Result<View>,
    ) -> Result<(Arc<View>, bool)> {
        if let Some(view) = self.view.get() {
            return Ok((Arc::clone(view), false));
        }
        let _building = self
            .building
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(view) = self.view.get() {
            return Ok((Arc::clone(view), false));
        }
        let built = Arc::new(build(&self.block)?);
        let view = self.view.get_or_init(|| built);
        Ok((Arc::clone(view), true))
    }

    /// The weight the cache bounds: the block's [`Block::heap_size`], the view's
    /// [`View::heap_size`] once built, and a small overhead.
    pub(crate) fn weight(&self) -> usize {
        let view = self.view.get().map_or(0, |view| view.heap_size());
        ENTRY_OVERHEAD
            .saturating_add(self.block.heap_size())
            .saturating_add(view)
    }
}

/// How the store is opened.
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

/// All the index says about one concept that reading its block needs: the observation
/// count, where the block is, the bound on its decoded size, and whether it is tagged. The
/// path, which the block's authentication also binds, and the rest of the metadata live in
/// the partition set's dictionary, and the holders in its holder table.
#[derive(Debug, Clone, Copy)]
pub(crate) struct ConceptEntry {
    pub(crate) observation_count: u32,
    location: BlockLocation,
    max_payload_len: u64,
    tagged: bool,
}

impl ConceptEntry {
    /// The entry of `concept`.
    pub(crate) fn of(concept: &ConceptView<'_>) -> Self {
        ConceptEntry {
            observation_count: concept.observation_count,
            location: concept.block,
            max_payload_len: concept.max_payload_len(),
            tagged: concept.tagged,
        }
    }
}

/// A store's files, opened and its index verified, before its concept table is read.
/// [`StoreFiles::into_store`] then keeps only the patient set and the partition table of the
/// index.
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

    /// The verified index, whose concept table the caller reads.
    pub(crate) fn index(&self) -> &IndexBody {
        &self.index
    }

    /// The open store, with `entries` its concepts' entries in index order, and its
    /// partition table. The index body is dropped.
    pub(crate) fn into_store(self, entries: Vec<ConceptEntry>) -> (Store, PartitionTable) {
        let (patients, table) = self.index.into_parts();
        let membership = table.membership();
        let store = Store {
            dir: self.dir,
            entries,
            patients,
            reader: self.reader,
            codec: self.codec,
            membership,
        };
        (store, table)
    }
}

/// The open store. Every method takes `&self` and is safe to call from many threads.
/// Crate-private: patient data is read through the engine, scoped by consent.
///
/// Of its index it keeps one [`ConceptEntry`] per concept, in index order, and the routing
/// of patients to partitions; paths and the other metadata live once in the
/// [`crate::PartitionSet`]'s dictionary. Block loads are blocking (a positioned read, then
/// decryption and decompression on the calling thread).
pub(crate) struct Store {
    dir: PathBuf,
    entries: Vec<ConceptEntry>,
    patients: RoaringBitmap,
    reader: BlocksReader,
    codec: BlockCodec,
    membership: Membership,
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

    /// The routing of patients to partitions.
    pub(crate) fn membership(&self) -> &Membership {
        &self.membership
    }

    /// Reads and decodes the block of the concept at `position` in this store's index,
    /// whose path is `path` and whose holders the index records as `holders` (empty in an
    /// unpartitioned store), checked against them and the store's partitions. Uncached: the
    /// caller caches it. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when the block cannot be read, authenticated or decoded, or
    /// disagrees with the index.
    pub(crate) fn read_block(
        &self,
        position: u32,
        path: &str,
        holders: &[PartitionHolder],
    ) -> Result<Block> {
        let entry = self.entry(position);
        let expected = ExpectedBlock {
            observation_count: entry.observation_count,
            holders,
            tagged: entry.tagged,
            membership: &self.membership,
        };
        self.reader
            .read_block_at(
                &self.codec,
                path,
                entry.location,
                entry.max_payload_len,
                &expected,
            )
            .map_err(|source| QueryError::Block {
                concept_path: path.to_owned(),
                source: Arc::new(source),
            })
    }
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use hpds_format::{AlignedBuf, encode_numeric_payload};

    use super::*;

    #[test]
    fn concurrent_first_readers_build_the_view_once() {
        let payload = encode_numeric_payload(&[]).unwrap();
        let entry = Arc::new(CachedConcept::new(
            Block::decode(AlignedBuf::copy_from(&payload)).unwrap(),
        ));
        let builds = AtomicUsize::new(0);
        let built: Vec<bool> = std::thread::scope(|scope| {
            let readers: Vec<_> = (0..8)
                .map(|_| {
                    scope.spawn(|| {
                        let (_, built) = entry
                            .view_or_build(|block| {
                                builds.fetch_add(1, Ordering::SeqCst);
                                std::thread::sleep(Duration::from_millis(50));
                                View::build(block, &[], &Membership::unpartitioned(), "\\a\\")
                            })
                            .unwrap();
                        built
                    })
                })
                .collect();
            readers.into_iter().map(|r| r.join().unwrap()).collect()
        });
        assert_eq!(builds.load(Ordering::SeqCst), 1);
        assert_eq!(built.iter().filter(|&&b| b).count(), 1);
    }

    fn get(recent: &RecentEntries, index: usize) -> Option<Arc<CachedConcept>> {
        recent.get_all(std::iter::once(index), |_| false).remove(0)
    }

    fn remember(recent: &RecentEntries, index: usize, entry: &Arc<CachedConcept>) {
        recent.remember_all(std::iter::once((index, entry)));
    }

    fn empty_entry() -> Arc<CachedConcept> {
        let payload = encode_numeric_payload(&[]).unwrap();
        Arc::new(CachedConcept::new(
            Block::decode(AlignedBuf::copy_from(&payload)).unwrap(),
        ))
    }

    #[test]
    fn recent_entries_never_keep_an_entry_alive() {
        let recent = RecentEntries::new(2);
        let entry = empty_entry();
        remember(&recent, 1, &entry);
        assert!(Arc::ptr_eq(&get(&recent, 1).unwrap(), &entry));
        assert!(get(&recent, 0).is_none());
        assert!(
            get(&recent, 2).is_none(),
            "an index past the table is never remembered"
        );
        drop(entry);
        assert!(get(&recent, 1).is_none());
    }

    #[test]
    fn recent_entries_forget_only_the_entry_named() {
        let recent = RecentEntries::new(1);
        let (kept, other) = (empty_entry(), empty_entry());
        remember(&recent, 0, &kept);
        recent.forget(0, &other);
        assert!(Arc::ptr_eq(&get(&recent, 0).unwrap(), &kept));
        recent.forget(0, &kept);
        assert!(get(&recent, 0).is_none());
        remember(&recent, 0, &kept);
        recent.clear();
        assert!(get(&recent, 0).is_none());
    }

    #[test]
    fn the_cache_forgets_entries_it_drops_even_while_a_query_holds_them() {
        let entries: Vec<Arc<CachedConcept>> = (0..6).map(|_| empty_entry()).collect();
        let weight = entries[0].weight() as u64;
        let recent = Arc::new(RecentEntries::new(entries.len()));
        let counters = Arc::new(BlockCacheCounters::default());
        let cache = block_cache(weight * 2, Arc::clone(&counters), Arc::clone(&recent));
        for (index, entry) in entries.iter().enumerate() {
            cache.insert(index as u32, Arc::clone(entry));
            remember(&recent, index, entry);
            cache.run_pending_tasks();
        }
        let mut held = 0;
        for index in 0..entries.len() {
            if cache.contains_key(&(index as u32)) {
                held += 1;
                assert!(get(&recent, index).is_some(), "concept {index} is cached");
            } else {
                assert!(get(&recent, index).is_none(), "concept {index} was dropped");
            }
        }
        assert!(held <= 2, "{held} entries held");
        assert_eq!(
            counters.evictions.load(Ordering::Relaxed),
            (entries.len() - held) as u64
        );
        let index = (0..entries.len())
            .find(|&i| cache.contains_key(&(i as u32)))
            .unwrap();
        cache.insert(index as u32, Arc::clone(&entries[index]));
        cache.run_pending_tasks();
        assert!(
            get(&recent, index).is_some(),
            "an entry replaced by itself, as a reweigh does, is still remembered"
        );
    }

    #[test]
    fn an_entry_the_cache_drops_while_a_read_loads_is_never_served() {
        let concepts = 200;
        let weight = empty_entry().weight() as u64;
        let recent = Arc::new(RecentEntries::new(concepts));
        let counters = Arc::new(BlockCacheCounters::default());
        let cache = block_cache(weight * 3, counters, Arc::clone(&recent));
        let held: Vec<Arc<CachedConcept>> = (0..concepts as u32)
            .map(|index| cache.get_with(index, empty_entry))
            .collect();
        recent.remember_all(held.iter().enumerate());
        cache.run_pending_tasks();
        let uncached: Vec<usize> = (0..concepts)
            .filter(|&index| {
                get(&recent, index).is_some_and(|served| {
                    !cache
                        .get(&(index as u32))
                        .is_some_and(|cached| Arc::ptr_eq(&cached, &served))
                })
            })
            .collect();
        assert!(
            uncached.is_empty(),
            "{} of {concepts} concepts served while the cache does not hold them",
            uncached.len()
        );
        drop(held);
    }
}
