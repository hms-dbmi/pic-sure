//! The phenotype store: `pheno.index` held in memory, `pheno.blocks` read on demand into a
//! byte-bounded cache of decoded blocks and their partition views, which a
//! [`crate::PartitionSet`] holds.

use std::fs::{File, TryLockError};
use std::io::Read;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, PoisonError, RwLock, Weak};
use std::time::Instant;

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlockLocation, BlocksReader, ConceptView, ExpectedBlock,
    INDEX_FILE_NAME, IndexBody, Membership, PUBLISH_JOURNAL_FILE_NAME, PartitionHolder,
    PartitionTable, Protection, StoreId,
};
use moka::notification::RemovalCause;
use moka::sync::Cache;
use roaring::RoaringBitmap;

use crate::budget::ByteBudget;
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

/// The bit of [`CachedConcept`]'s hold state set once the block cache has dropped it.
const HOLDS_DROPPED: u64 = 1 << 63;

/// The low bits of [`CachedConcept`]'s hold state, counting the reads that hold it.
const HOLDS_COUNT: u64 = (1 << 20) - 1;

/// Where the bytes [`CachedConcept`] has charged to the prefix read budget start in its hold
/// state, above the count.
const HOLDS_CHARGE_SHIFT: u32 = 20;

/// The most bytes the hold state records as charged (8 TiB less one byte), far above any
/// entry's weight; a heavier entry is charged at this.
const HOLDS_CHARGE_MAX: u64 = (1 << 43) - 1;

/// Bytes an entry is weighed at beyond its block and view.
const ENTRY_OVERHEAD: usize = 64;

/// A block cache bounded by the summed weight of its entries ([`CachedConcept::weight`]). A
/// `moka` weigher returns a `u32`, so an entry heavier than `u32::MAX` bytes (4 GiB) is
/// weighed at `u32::MAX`, under its real size; at the default 4 GiB bound such an entry
/// already fills the cache on its own. Every entry dropped to keep the bound is counted in
/// `counters.evictions`; an entry replaced by its reweighed self is not. Every entry the
/// cache drops for any other reason than being replaced is marked dropped
/// ([`CachedConcept::mark_dropped`], which charges it to `budget` while reads hold it) and
/// forgotten by `recent`.
pub(crate) fn block_cache(
    cache_bytes: u64,
    counters: Arc<BlockCacheCounters>,
    recent: Arc<RecentEntries>,
    budget: Arc<ByteBudget>,
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
                concept.mark_dropped(&budget);
                recent.forget(*index as usize, &concept);
            }
        })
        .build()
}

/// Collections [`collect_unlinked`] runs. Memory unlinked in epoch `e` is freed once the
/// global epoch reaches `e + 2`, and each collection advances it at most once, so the last two
/// may free what the first two let expire.
const RECLAIM_COLLECTIONS: usize = 4;

/// Pushes the calling thread's bag of deferred frees to `crossbeam-epoch`'s global queue and
/// collects [`RECLAIM_COLLECTIONS`] times, freeing every expired bag in the queue up to the
/// collector's eight per collection ([`crate::PartitionSet::reclaim_unlinked`]).
pub(crate) fn collect_unlinked() {
    for _ in 0..RECLAIM_COLLECTIONS {
        crossbeam_epoch::pin().flush();
    }
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
/// entry itself, so it holds however the remember and the listener interleave. A reader
/// that builds a view reinserts its entry only while the cache holds that very entry, so
/// no entry is ever replaced by another without the listener seeing it dropped. A slot
/// holds its entry weakly, so it never keeps a block or view alive.
///
/// The gaps, each one where a lookup through the cache could misjudge the same way or the
/// cache itself has not yet let the entry go:
///
/// - The instant between the cache removing an entry from its map and calling the
///   listener, before the mark is set.
/// - Inside [`crate::PartitionSet::clear_cache`]: `invalidate_all` hides every entry from
///   lookups at once, but the cache drops them, and the listener marks them, only as
///   `run_pending_tasks` drains them. A read that found an entry through the cache just
///   before the clear can remember it after the table is cleared, and the table serves it
///   until the drain marks it. `clear_cache` relies on its `run_pending_tasks` call
///   draining every entry; one that a time-limited drain leaves for later stays unmarked
///   until the cache's next housekeeping.
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
    /// loads afterwards; those are new entries the cache holds. Every entry from before the
    /// clear is marked dropped by the cache's listener as the cache drains it, and never
    /// served after that (see the gaps on [`RecentEntries`]).
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
    holds: AtomicU64,
    #[cfg(test)]
    ticket: OnceLock<test_support::LiveTicket>,
    #[cfg(test)]
    dropped_at: OnceLock<u64>,
}

impl CachedConcept {
    /// An entry holding `block` and no view yet.
    pub(crate) fn new(block: Block) -> Self {
        CachedConcept {
            block: Arc::new(block),
            view: OnceLock::new(),
            building: Mutex::new(()),
            dropped: AtomicBool::new(false),
            holds: AtomicU64::new(0),
            #[cfg(test)]
            ticket: OnceLock::new(),
            #[cfg(test)]
            dropped_at: OnceLock::new(),
        }
    }

    /// Counts the entry in `live` until it is dropped.
    #[cfg(test)]
    pub(crate) fn track(&self, live: &Arc<test_support::LiveEntries>) {
        let _ = self.ticket.set(test_support::LiveTicket::new(live));
    }

    /// Marks the entry as dropped by the block cache, for good: [`RecentEntries`] never
    /// serves or remembers it again. A reader that builds its view puts it back into the
    /// cache only while the cache still holds it, so a dropped entry never returns. While
    /// reads hold the entry ([`CachedConcept::hold`]), its weight is charged to `budget`
    /// from now until the last of them lets it go.
    pub(crate) fn mark_dropped(&self, budget: &ByteBudget) {
        #[cfg(test)]
        let _ = self.dropped_at.set(test_support::tick_drop_clock());
        self.dropped.store(true, Ordering::Release);
        self.update_holds(budget, |holds| holds | HOLDS_DROPPED);
    }

    /// Records that a read holds the entry, which it found in the block cache and so took no
    /// permit for. Should the cache drop it before [`CachedConcept::release_hold`], the read
    /// would hold its block and view outside both the cache and `budget`, so the entry's
    /// weight ([`CachedConcept::weight`]) is then charged to `budget` until every holding read
    /// has released it; when the cache has dropped it already, the charge starts now.
    pub(crate) fn hold(&self, budget: &ByteBudget) {
        self.update_holds(budget, |holds| holds + 1);
    }

    /// Ends a hold taken by [`CachedConcept::hold`], giving back the charge when it was the
    /// last one.
    pub(crate) fn release_hold(&self, budget: &ByteBudget) {
        self.update_holds(budget, |holds| holds - 1);
    }

    /// Brings the charge up to the entry's weight after a reader has built its view, when the
    /// entry is charged: a view built on a dropped entry that reads hold is charged with it.
    pub(crate) fn recharge(&self, budget: &ByteBudget) {
        self.update_holds(budget, |holds| holds);
    }

    /// Applies `change` to the hold state (the count of holding reads and the dropped bit) in
    /// one atomic step, with the bytes charged to `budget` recorded beside them: the entry's
    /// weight, or what was charged already when that is more, exactly while the entry is
    /// dropped and held, and nothing otherwise. Bytes are charged to `budget` before the
    /// state records them and released after it no longer does, so what the budget counts
    /// never falls below what the charged entries hold.
    fn update_holds(&self, budget: &ByteBudget, change: impl Fn(u64) -> u64) {
        let mut ahead = 0u64;
        let mut current = self.holds.load(Ordering::SeqCst);
        loop {
            let charged = current >> HOLDS_CHARGE_SHIFT & HOLDS_CHARGE_MAX;
            let changed = change(current & (HOLDS_DROPPED | HOLDS_COUNT));
            let wanted = if changed & HOLDS_DROPPED != 0 && changed & HOLDS_COUNT != 0 {
                charged.max((self.weight() as u64).min(HOLDS_CHARGE_MAX))
            } else {
                0
            };
            let more = wanted.saturating_sub(charged);
            if more > ahead {
                budget.charge_held(more - ahead);
                ahead = more;
            }
            let next = changed | wanted << HOLDS_CHARGE_SHIFT;
            if next == current {
                budget.release_held(ahead);
                return;
            }
            match self.holds.compare_exchange_weak(
                current,
                next,
                Ordering::SeqCst,
                Ordering::SeqCst,
            ) {
                Ok(_) => {
                    budget.release_held(ahead - more);
                    budget.release_held(charged.saturating_sub(wanted));
                    return;
                }
                Err(now) => current = now,
            }
        }
    }

    /// The drop clock's reading when the entry was first marked dropped
    /// ([`test_support::drop_clock`]), or `None` while it is not.
    #[cfg(test)]
    pub(crate) fn dropped_at(&self) -> Option<u64> {
        self.dropped_at.get().copied()
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

    /// The bound on the concept's decoded payload in bytes, which the index records and a
    /// block read enforces: the size a read of it may decode at most.
    pub(crate) fn decoded_bound(&self) -> u64 {
        self.max_payload_len
    }

    /// Where the concept's block is stored.
    #[cfg(test)]
    pub(crate) fn location(&self) -> BlockLocation {
        self.location
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
    /// Opens the store in `dir` whose `pheno.index` holds `index_bytes` (read under the
    /// [`ServingLock`]): verifies the index under `protection` and opens `pheno.blocks` for
    /// its store id.
    ///
    /// # Errors
    /// [`QueryError::Open`] when either file is missing, corrupt, from another store, or
    /// protected differently from `protection`.
    pub(crate) fn open(dir: &Path, index_bytes: &[u8], protection: Protection) -> Result<Self> {
        let index = IndexBody::decode(index_bytes, &protection).map_err(|e| open_error(dir, e))?;
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

    /// The state of the open `pheno.blocks` descriptor now.
    ///
    /// # Errors
    /// The I/O error of `fstat`.
    pub(crate) fn blocks_metadata(&self) -> std::io::Result<std::fs::Metadata> {
        self.reader.file_metadata()
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

/// How often, at most, [`ServingLock::check`] looks at the data directory: once a second.
const STORE_CHECK_INTERVAL_MS: u64 = 1000;

/// How much of a replacement index [`ServingLock::check`] reads to learn its store id from
/// the plain header.
const HEADER_READ_LEN: u64 = 64 << 10;

/// What identifies the content of a file: its device and inode, length, and modification
/// time. A rename gives another inode; a rewrite in place (`cp` over the file, `rsync
/// --inplace`) changes the length or the modification time. A `touch` changes the time
/// too, and counts as a change.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct FileStamp {
    dev: u64,
    ino: u64,
    len: u64,
    mtime_ns: i128,
}

impl FileStamp {
    fn of(meta: &std::fs::Metadata) -> Self {
        FileStamp {
            dev: meta.dev(),
            ino: meta.ino(),
            len: meta.len(),
            mtime_ns: i128::from(meta.mtime()) * 1_000_000_000 + i128::from(meta.mtime_nsec()),
        }
    }

    fn same_inode(&self, other: &FileStamp) -> bool {
        (self.dev, self.ino) == (other.dev, other.ino)
    }
}

/// The files of one kind of store that a [`ServingLock`] watches: the index it locks and
/// reads, the blocks file the service keeps open, how to read a replacement index's store
/// id, and the error that refuses queries once the store changed.
#[derive(Debug)]
pub(crate) struct ServedFiles {
    /// The index file name, `pheno.index` or `geno.index`.
    pub(crate) index: &'static str,
    /// The blocks file name, `pheno.blocks` or `geno.blocks`.
    pub(crate) blocks: &'static str,
    /// Which store it is, for the log: `store` or `genomic store`.
    pub(crate) what: &'static str,
    /// The store id in the plain header of an index, or `None` when this format does not
    /// read the header.
    pub(crate) header_store_id: fn(&[u8]) -> Option<StoreId>,
    /// The error every query gets once the store in `dir` changed.
    pub(crate) changed: fn(PathBuf) -> QueryError,
}

/// The phenotype store's files.
pub(crate) const PHENOTYPE_FILES: ServedFiles = ServedFiles {
    index: INDEX_FILE_NAME,
    blocks: BLOCKS_FILE_NAME,
    what: "store",
    header_store_id: |bytes| {
        hpds_format::PhenoIndex::decode_header(bytes)
            .ok()
            .map(|header| header.store_id)
    },
    changed: |dir| QueryError::StoreChanged { dir },
};

/// The genomic store's files.
pub(crate) const GENOMIC_FILES: ServedFiles = ServedFiles {
    index: hpds_format::GENO_INDEX_FILE_NAME,
    blocks: hpds_format::GENO_BLOCKS_FILE_NAME,
    what: "genomic store",
    header_store_id: |bytes| {
        hpds_format::GenoIndex::decode_header(bytes)
            .ok()
            .map(|header| header.store_id)
    },
    changed: |dir| QueryError::GenomicStoreChanged { dir },
};

/// The shared `flock` the service holds on the index it serves (`pheno.index`, or
/// `geno.index` for the genomic store), for as long as it serves it, and the check that what
/// it serves is still what it opened.
///
/// Every `hpds-etl` command that replaces store files takes an exclusive `flock` on the
/// current `pheno.index` without waiting, and on the new one before it renames it into
/// place, holding both until its publish is done. So a command refuses to change a store a
/// service on the same kernel holds, and a service that starts during a publish waits for
/// it to finish rather than reading half of it. The genomic commands write a new directory
/// only, which they rename into place whole; the lock on `geno.index` keeps any later
/// command that replaces files in place from reaching a store a service holds.
///
/// After startup the service reads the store only through what it opened: the index it
/// holds in memory and its open blocks file descriptor, read by offset. It never opens a
/// store path for its data again; the check opens the index only to stamp it and read a
/// replacement's header, never following a link or waiting on a FIFO ([`open_index`]), and
/// the startup open refuses an index that is not a regular file the same way.
/// [`ServingLock::check`] therefore asks two questions. Has a file the
/// service holds open changed in place? Then its reads may no longer be the store it
/// opened, and every query is refused. Has the directory moved on? A publish journal, or an
/// index with the same store id but another file (an in-place `remove-concepts` or
/// `recover` the lock did not reach, which withdraws data), also refuses every query. An
/// index of another store id (a whole store renamed in by `aio/load.sh` or
/// `bin/migrate-javabins`, or a genomic directory swapped for a new one) does not: the
/// service goes on serving the old store from its open files, as it did before this check
/// existed, until it is stopped, and reports itself superseded
/// ([`ServingLock::is_superseded`]). An index whose header this format does not read
/// (another format version) refuses at once.
#[derive(Debug)]
pub(crate) struct ServingLock {
    file: File,
    dir: PathBuf,
    files: &'static ServedFiles,
    index: FileStamp,
    blocks: Option<FileStamp>,
    store_id: Option<StoreId>,
    opened: Instant,
    checked_ms: AtomicU64,
    changed: AtomicBool,
    superseded: AtomicBool,
}

impl ServingLock {
    /// Opens the index of `files` in `dir`, takes the shared lock on it (waiting while an
    /// `hpds-etl` command holds it, and opening again if the file was replaced meanwhile), and
    /// returns the lock with the file's bytes. A filesystem without `flock` is logged and
    /// served unlocked; the check still applies.
    ///
    /// # Errors
    /// [`QueryError::Open`] when the index cannot be opened or read.
    pub(crate) fn open(dir: &Path, files: &'static ServedFiles) -> Result<(Self, Vec<u8>)> {
        let path = dir.join(files.index);
        loop {
            let mut file = open_index(&path).map_err(|e| open_error(dir, e))?;
            match file.try_lock_shared() {
                Ok(()) => {}
                Err(TryLockError::WouldBlock) => {
                    tracing::warn!(
                        dir = %dir.display(),
                        "an hpds-etl command is changing the {}; waiting for it to finish",
                        files.what
                    );
                    file.lock_shared().map_err(|e| open_error(dir, e))?;
                }
                Err(TryLockError::Error(error)) => {
                    tracing::warn!(
                        %error,
                        dir = %dir.display(),
                        "cannot lock {}; hpds-etl will not see that this service holds the {}",
                        files.index,
                        files.what
                    );
                }
            }
            let held = FileStamp::of(&file.metadata().map_err(|e| open_error(dir, e))?);
            match std::fs::metadata(&path) {
                Ok(now) if FileStamp::of(&now).same_inode(&held) => {}
                Ok(_) => continue,
                Err(e) => return Err(open_error(dir, e)),
            }
            let mut bytes = Vec::new();
            file.read_to_end(&mut bytes)
                .map_err(|e| open_error(dir, e))?;
            let lock = ServingLock {
                file,
                dir: dir.to_path_buf(),
                files,
                index: held,
                blocks: None,
                store_id: None,
                opened: Instant::now(),
                checked_ms: AtomicU64::new(0),
                changed: AtomicBool::new(false),
                superseded: AtomicBool::new(false),
            };
            return Ok((lock, bytes));
        }
    }

    /// Records the store id of the index read under the lock and the state of the open
    /// blocks file descriptor, `blocks`, which [`ServingLock::check`] compares against.
    pub(crate) fn bind(&mut self, store_id: StoreId, blocks: &std::fs::Metadata) {
        self.store_id = Some(store_id);
        self.blocks = Some(FileStamp::of(blocks));
    }

    /// Fails once what the service serves may no longer be the store it opened (see the type
    /// documentation): a file it holds open changed in place, a publish journal appeared, or
    /// the index became another file of the same store id. `blocks` gives the state of the
    /// open blocks file descriptor now. Looks at most once per [`STORE_CHECK_INTERVAL_MS`]
    /// and answers from the last look in between; once it has failed it always fails.
    ///
    /// # Errors
    /// The files' `changed` error: [`QueryError::StoreChanged`] for the phenotype store,
    /// [`QueryError::GenomicStoreChanged`] for the genomic store.
    pub(crate) fn check(
        &self,
        blocks: impl FnOnce() -> std::io::Result<std::fs::Metadata>,
    ) -> Result<()> {
        if !self.changed.load(Ordering::Relaxed) {
            let now = u64::try_from(self.opened.elapsed().as_millis()).unwrap_or(u64::MAX);
            let last = self.checked_ms.load(Ordering::Relaxed);
            if now.saturating_sub(last) < STORE_CHECK_INTERVAL_MS && last != 0 {
                return Ok(());
            }
            self.checked_ms.store(now.max(1), Ordering::Relaxed);
            match self.look(blocks) {
                None => return Ok(()),
                Some(reason) => {
                    if !self.changed.swap(true, Ordering::Relaxed) {
                        tracing::error!(
                            dir = %self.dir.display(),
                            reason,
                            "the {} changed on disk after it was opened; refusing every query that reads it until the service restarts",
                            self.files.what
                        );
                    }
                }
            }
        }
        Err((self.files.changed)(self.dir.clone()))
    }

    /// Why the service must stop answering, or `None` while it may go on.
    fn look(&self, blocks: impl FnOnce() -> std::io::Result<std::fs::Metadata>) -> Option<String> {
        let (index_name, blocks_name) = (self.files.index, self.files.blocks);
        if std::fs::symlink_metadata(self.dir.join(PUBLISH_JOURNAL_FILE_NAME)).is_ok() {
            return Some("a publish journal appeared".to_owned());
        }
        if self
            .file
            .metadata()
            .map_or(true, |meta| FileStamp::of(&meta) != self.index)
        {
            return Some(format!("the open {index_name} was changed in place"));
        }
        if let Some(opened) = self.blocks
            && blocks().map_or(true, |meta| FileStamp::of(&meta) != opened)
        {
            return Some(format!("the open {blocks_name} was changed in place"));
        }
        if self.superseded.load(Ordering::Relaxed) {
            return None;
        }
        let mut file = match open_index(&self.dir.join(index_name)) {
            Ok(file) => file,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Some(format!("{index_name} is gone"));
            }
            Err(_) => {
                return Some(format!(
                    "{index_name} is a symbolic link, not a regular file, or unreadable"
                ));
            }
        };
        let Ok(now) = file.metadata() else {
            return Some(format!("{index_name} cannot be examined"));
        };
        if FileStamp::of(&now) == self.index {
            return None;
        }
        match (self.read_store_id(&mut file), self.store_id) {
            (None, _) => Some(format!(
                "{index_name} was replaced by a file whose header this format does not read"
            )),
            (Some(id), Some(ours)) if id != ours => {
                self.superseded.store(true, Ordering::Relaxed);
                tracing::warn!(
                    dir = %self.dir.display(),
                    new_store = %id,
                    "another {} was moved into the directory; serving the one opened at startup from its open files until the service stops",
                    self.files.what
                );
                None
            }
            _ => Some(format!("{index_name} was replaced within the same store")),
        }
    }

    /// The store id in the plain header of the open index `file`, or `None` when its header
    /// cannot be read in this format (another format version, a truncated or foreign file).
    fn read_store_id(&self, file: &mut File) -> Option<StoreId> {
        let mut bytes = Vec::new();
        file.take(HEADER_READ_LEN).read_to_end(&mut bytes).ok()?;
        (self.files.header_store_id)(&bytes)
    }

    /// True once [`ServingLock::check`] has seen a store of another id moved into the
    /// directory while this one goes on serving its open files.
    pub(crate) fn is_superseded(&self) -> bool {
        self.superseded.load(Ordering::Relaxed)
    }
}

/// Opens the index at `path` for reading without following a symbolic link and without
/// waiting on a FIFO or device (`O_NOFOLLOW | O_NONBLOCK`), and refuses anything but a
/// regular file, judged by `fstat` on the same handle so nothing can be swapped in between.
///
/// # Errors
/// The I/O error of the open (`ELOOP` for a symbolic link) or `fstat`, and
/// [`std::io::ErrorKind::InvalidInput`] for a file that is not regular.
fn open_index(path: &Path) -> std::io::Result<File> {
    let file = std::fs::OpenOptions::new()
        .read(true)
        .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
        .open(path)?;
    if !file.metadata()?.file_type().is_file() {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidInput,
            format!("{} is not a regular file", path.display()),
        ));
    }
    Ok(file)
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}

/// Counting of decoded entries alive, for tests that bound what a read holds at once.
#[cfg(test)]
pub(crate) mod test_support {
    use std::sync::Arc;
    use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};

    static DROP_CLOCK: AtomicU64 = AtomicU64::new(0);

    /// The number of entries marked dropped so far, process-wide: an entry marked after a
    /// reading of it carries a later value ([`super::CachedConcept::dropped_at`]).
    pub(crate) fn drop_clock() -> u64 {
        DROP_CLOCK.load(Ordering::SeqCst)
    }

    /// Advances the drop clock for one entry being marked dropped and returns its new value.
    pub(super) fn tick_drop_clock() -> u64 {
        DROP_CLOCK.fetch_add(1, Ordering::SeqCst) + 1
    }

    /// The number of tracked entries alive now and the most alive at once.
    #[derive(Debug, Default)]
    pub(crate) struct LiveEntries {
        live: AtomicUsize,
        peak: AtomicUsize,
    }

    impl LiveEntries {
        /// The most tracked entries alive at once since the last [`LiveEntries::reset_peak`].
        pub(crate) fn peak(&self) -> usize {
            self.peak.load(Ordering::SeqCst)
        }

        /// The tracked entries alive now.
        pub(crate) fn live(&self) -> usize {
            self.live.load(Ordering::SeqCst)
        }

        /// Starts the peak again from the entries alive now.
        pub(crate) fn reset_peak(&self) {
            self.peak.store(self.live(), Ordering::SeqCst);
        }

        /// The tracked entries alive once memory already unlinked from shared structures
        /// is reclaimed, waiting up to `bound` for that. A load through the block cache
        /// shares its entry with concurrent loaders of the same concept through a waiter
        /// that `moka` unlinks with `crossbeam-epoch`, which frees it, and the entry clone it
        /// holds, only after the global epoch has advanced past every pinned thread. Pinning
        /// and flushing here drives that, so an entry no read holds any longer is counted
        /// as dropped.
        pub(crate) fn live_after_reclaim(&self, bound: std::time::Duration) -> usize {
            let deadline = std::time::Instant::now() + bound;
            while self.live() > 0 && std::time::Instant::now() < deadline {
                crossbeam_epoch::pin().flush();
                std::thread::yield_now();
            }
            self.live()
        }
    }

    /// One tracked entry, counted in its [`LiveEntries`] until dropped.
    #[derive(Debug)]
    pub(crate) struct LiveTicket(Arc<LiveEntries>);

    impl LiveTicket {
        pub(crate) fn new(live: &Arc<LiveEntries>) -> Self {
            let now = live.live.fetch_add(1, Ordering::SeqCst) + 1;
            live.peak.fetch_max(now, Ordering::SeqCst);
            LiveTicket(Arc::clone(live))
        }
    }

    impl Drop for LiveTicket {
        fn drop(&mut self) {
            self.0.live.fetch_sub(1, Ordering::SeqCst);
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;

    use hpds_format::{AlignedBuf, encode_numeric_payload};

    use super::*;

    #[test]
    fn a_dropped_entry_is_charged_exactly_while_reads_hold_it() {
        let payload = encode_numeric_payload(&[]).unwrap();
        let block = Block::decode(AlignedBuf::copy_from(&payload)).unwrap();
        let budget = ByteBudget::new(u64::MAX);
        let held = || budget.stats().held;
        let early = CachedConcept::new(Block::decode(AlignedBuf::copy_from(&payload)).unwrap());
        let bytes = early.weight() as u64;
        assert!(bytes > 0);
        early.hold(&budget);
        early.hold(&budget);
        assert_eq!(held(), 0, "a cached entry is the cache's to count");
        early.mark_dropped(&budget);
        assert_eq!(held(), bytes, "charged once, however many reads hold it");
        early.release_hold(&budget);
        assert_eq!(held(), bytes);
        early.release_hold(&budget);
        assert_eq!(held(), 0, "given back with the last hold");
        let late = CachedConcept::new(block);
        late.mark_dropped(&budget);
        assert_eq!(
            held(),
            0,
            "a dropped entry no read holds costs the budget nothing"
        );
        late.hold(&budget);
        assert_eq!(
            held(),
            bytes,
            "a hold on an entry already dropped charges it"
        );
        late.mark_dropped(&budget);
        assert_eq!(held(), bytes);
        late.release_hold(&budget);
        assert_eq!(held(), 0);
    }

    #[test]
    fn holds_and_drops_racing_leave_nothing_charged() {
        let payload = encode_numeric_payload(&[]).unwrap();
        let budget = ByteBudget::new(u64::MAX);
        for _ in 0..200 {
            let entry = CachedConcept::new(Block::decode(AlignedBuf::copy_from(&payload)).unwrap());
            std::thread::scope(|scope| {
                for _ in 0..3 {
                    scope.spawn(|| {
                        entry.hold(&budget);
                        std::hint::spin_loop();
                        entry.release_hold(&budget);
                    });
                }
                scope.spawn(|| entry.mark_dropped(&budget));
            });
            assert_eq!(budget.stats().held, 0);
        }
    }

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
        let cache = block_cache(
            weight * 2,
            Arc::clone(&counters),
            Arc::clone(&recent),
            Arc::new(ByteBudget::new(u64::MAX)),
        );
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
        let cache = block_cache(
            weight * 3,
            counters,
            Arc::clone(&recent),
            Arc::new(ByteBudget::new(u64::MAX)),
        );
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
