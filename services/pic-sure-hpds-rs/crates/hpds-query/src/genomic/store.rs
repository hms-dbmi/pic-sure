//! The genomic store as the engine serves it: `geno.index` in memory, `geno.blocks` read on
//! demand through one byte-bounded cache of decoded blocks or, for scans larger than a share
//! of that cache, under the genomic read budget, and the rayon pool genomic work runs on.

use std::cell::Cell;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use hpds_format::{
    CryptoKey, FormatError, GENO_BLOCKS_FILE_NAME, GENO_INDEX_FILE_NAME, GenoBlockCodec,
    GenoBlockKind, GenoBlocksReader, GenoIndex, GenoMode, GenoNode, InfoBlock, MaskChunk,
    PUBLISH_JOURNAL_FILE_NAME, Protection,
};
use moka::notification::RemovalCause;
use moka::sync::Cache;
use roaring::RoaringBitmap;

use crate::budget::{ByteBudget, GenomicReadStats, PermitThreads};
use crate::error::{QueryError, Result};

/// Default capacity of the genomic block cache (`HPDS_GENOMIC_CACHE_BYTES`): 2 GiB.
pub const DEFAULT_GENOMIC_CACHE_BYTES: u64 = 2 << 30;

/// Default process-wide bound on the bytes genomic scans hold outside the cache
/// (`HPDS_GENOMIC_READ_BUDGET_BYTES`): 1 GiB.
pub const DEFAULT_GENOMIC_READ_BUDGET_BYTES: u64 = 1 << 30;

/// A query reads through the cache, admitting the blocks it misses, when the decoded bytes it
/// plans to read are at most the cache's capacity divided by this; above that it scans.
pub(crate) const PLAN_CACHE_SHARE_DIVISOR: u64 = 4;

/// Name of the file `hpds-etl` writes last into a store directory, holding its store id.
const GENERATION_FILE_NAME: &str = ".generation";

/// File names of a Java genomic node directory, any one of which marks a directory holding a
/// Java genomic store that was never migrated.
const JAVA_GENOMIC_FILE_NAMES: [&str; 3] = [
    "variantStore.javabin",
    "variantSpecIndex.javabin",
    "VariantMetadata.javabin",
];

/// How the genomic store is opened and how much memory its reads may hold.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GenomicOptions {
    /// Upper bound on the summed heap size of the decoded blocks the genomic cache holds
    /// ([`DEFAULT_GENOMIC_CACHE_BYTES`] by default). Every kind of block shares it.
    pub cache_bytes: u64,
    /// Upper bound on the bytes every genomic scan in the process holds at once outside the
    /// cache, each block counted at its stored plus its decoded length
    /// ([`DEFAULT_GENOMIC_READ_BUDGET_BYTES`] by default).
    pub read_budget_bytes: u64,
    /// Threads of the genomic rayon pool; `None` sizes it like the main pool.
    pub threads: Option<usize>,
}

impl Default for GenomicOptions {
    /// The default cache and budget, and a pool the size of the main one.
    fn default() -> Self {
        GenomicOptions {
            cache_bytes: DEFAULT_GENOMIC_CACHE_BYTES,
            read_budget_bytes: DEFAULT_GENOMIC_READ_BUDGET_BYTES,
            threads: None,
        }
    }
}

/// What the genomic cache holds and has done since the store was opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct GenomicCacheStats {
    /// The cache's capacity in bytes.
    pub capacity: u64,
    /// Blocks the cache holds.
    pub entries: u64,
    /// Their summed weight, the heap size of each decoded block.
    pub bytes: u64,
    /// Lookups that found their block cached.
    pub hits: u64,
    /// Lookups that did not.
    pub misses: u64,
    /// Blocks read and admitted to the cache.
    pub loads: u64,
    /// Blocks the cache dropped to stay within its capacity.
    pub evictions: u64,
    /// Blocks read by a scan under the read budget and dropped without being admitted.
    pub scan_reads: u64,
}

thread_local! {
    static GENOMIC_WORKER: Cell<bool> = const { Cell::new(false) };
}

/// True on a worker of a genomic store's rayon pool.
pub(crate) fn on_genomic_worker() -> bool {
    GENOMIC_WORKER.with(Cell::get)
}

/// A rayon pool of `threads` threads, at least 1, whose workers [`on_genomic_worker`] knows.
///
/// # Errors
/// The pool builder's error when the threads cannot be started.
pub(crate) fn genomic_pool(
    threads: usize,
) -> std::result::Result<rayon::ThreadPool, rayon::ThreadPoolBuildError> {
    rayon::ThreadPoolBuilder::new()
        .num_threads(threads.max(1))
        .thread_name(|i| format!("hpds-genomic-{i}"))
        .start_handler(|_| GENOMIC_WORKER.with(|w| w.set(true)))
        .build()
}

/// One group's patient tables: the list in Java's order, which a mask position indexes, and
/// the pairs `(id, position)` sorted by id.
#[derive(Debug)]
pub(crate) struct GroupPatients {
    pub(crate) ids_by_pos: Vec<u32>,
    pos_by_id: Vec<(u32, u32)>,
    ids: RoaringBitmap,
}

impl GroupPatients {
    fn new(ids_by_pos: &[u32]) -> Self {
        let mut pos_by_id: Vec<(u32, u32)> = ids_by_pos
            .iter()
            .enumerate()
            .map(|(pos, &id)| (id, pos as u32))
            .collect();
        pos_by_id.sort_unstable();
        GroupPatients {
            ids_by_pos: ids_by_pos.to_vec(),
            ids: pos_by_id.iter().map(|&(id, _)| id).collect(),
            pos_by_id,
        }
    }

    /// The number of patients, the range of the group's mask positions.
    pub(crate) fn count(&self) -> u32 {
        self.ids_by_pos.len() as u32
    }

    /// The position of patient `id`, when the group holds it.
    pub(crate) fn position(&self, id: u32) -> Option<u32> {
        self.pos_by_id
            .binary_search_by_key(&id, |&(i, _)| i)
            .ok()
            .map(|i| self.pos_by_id[i].1)
    }

    /// The group's patients as a set of ids.
    pub(crate) fn ids(&self) -> &RoaringBitmap {
        &self.ids
    }
}

/// A decoded genomic block in the cache.
#[derive(Debug, Clone)]
pub(crate) enum CachedBlock {
    /// A mask chunk.
    Mask(Arc<MaskChunk>),
    /// An info block.
    Info(Arc<InfoBlock>),
}

impl CachedBlock {
    /// The weight the cache bounds: the decoded block's heap size.
    fn heap_size(&self) -> usize {
        match self {
            CachedBlock::Mask(b) => b.heap_size(),
            CachedBlock::Info(b) => b.heap_size(),
        }
    }
}

/// Whether a query's chunk reads go through the cache or scan under the read budget.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ReadPath {
    /// Read through the cache, admitting misses.
    Cached,
    /// Use cached blocks found, read each missing one under a permit of the read budget and
    /// drop it without admitting it.
    Scan,
}

#[derive(Debug, Default)]
struct Counters {
    hits: AtomicU64,
    misses: AtomicU64,
    loads: AtomicU64,
    evictions: Arc<AtomicU64>,
    scan_reads: AtomicU64,
}

/// The genomic store of `HPDS_GENOMIC_DATA_DIRECTORY`, opened for queries: the decoded
/// `geno.index`, the open `geno.blocks`, each group's patient tables, the genomic block cache,
/// the genomic read budget and the genomic rayon pool. Attach it to an engine with
/// [`crate::Engine::with_genomic`]; its patient data is read only by the engine, within a
/// query's consents.
pub struct GenomicStore {
    dir: PathBuf,
    index: GenoIndex,
    reader: GenoBlocksReader,
    codec: GenoBlockCodec,
    groups: Vec<GroupPatients>,
    cache: Cache<u64, CachedBlock>,
    cache_capacity: u64,
    counters: Counters,
    budget: ByteBudget,
    pool: rayon::ThreadPool,
}

impl std::fmt::Debug for GenomicStore {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("GenomicStore")
            .field("dir", &self.dir)
            .field("mode", &self.index.mode())
            .field("groups", &self.groups.len())
            .field("nodes", &self.index.nodes().len())
            .finish_non_exhaustive()
    }
}

impl GenomicStore {
    /// Opens the genomic store in `dir` under the phenotype store's key file `key_file`, as
    /// [`GenomicStore::open`] does; a missing key file opens a plain store.
    ///
    /// # Errors
    /// [`QueryError::Open`] when the key file cannot be read or is not a key, and the errors
    /// of [`GenomicStore::open`].
    pub fn open_with_key_file(
        dir: &Path,
        key_file: &Path,
        options: &GenomicOptions,
    ) -> Result<Self> {
        let protection = match std::fs::read(key_file) {
            Ok(bytes) => {
                let text = String::from_utf8_lossy(&bytes);
                Protection::Encrypted(
                    CryptoKey::from_key_file_bytes(
                        text.trim_matches(|c: char| c <= ' ').as_bytes(),
                    )
                    .map_err(|e| open_error(key_file, e))?,
                )
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Protection::Plain,
            Err(e) => return Err(open_error(key_file, e)),
        };
        Self::open(dir, protection, options)
    }

    /// Opens the genomic store in `dir`, sealed under `protection`, the phenotype store's key.
    ///
    /// `dir` must hold `geno.index` and `geno.blocks` of one store. A directory holding a Java
    /// genomic store and no `geno.index` is refused naming `hpds-etl genomic-migrate`, one
    /// holding the journal of an interrupted publish is refused, and when `.generation` exists
    /// it must hold exactly the index's store id. The service never writes the directory.
    ///
    /// # Errors
    /// [`QueryError::Open`] for every refusal above and for an index or blocks file that is
    /// missing, corrupt, from another store, or protected differently from `protection`.
    pub fn open(dir: &Path, protection: Protection, options: &GenomicOptions) -> Result<Self> {
        let index_path = dir.join(GENO_INDEX_FILE_NAME);
        if !index_path.exists() {
            return Err(refuse_without_index(dir));
        }
        if std::fs::symlink_metadata(dir.join(PUBLISH_JOURNAL_FILE_NAME)).is_ok() {
            return Err(open_error(
                dir,
                format!(
                    "it holds {PUBLISH_JOURNAL_FILE_NAME}: an hpds-etl command was interrupted \
                     while replacing the genomic store files. Run hpds-etl recover --store <this \
                     directory>, then restart the service"
                ),
            ));
        }
        let index =
            GenoIndex::read_file(&index_path, &protection).map_err(|e| open_error(dir, e))?;
        check_generation(dir, &index.store_id().to_string())?;
        let reader = GenoBlocksReader::open(dir.join(GENO_BLOCKS_FILE_NAME), &index)
            .map_err(|e| open_error(dir, e))?;
        let codec = index
            .block_codec(protection)
            .map_err(|e| open_error(dir, e))?;
        let groups = index
            .groups()
            .iter()
            .map(|g| GroupPatients::new(&g.patients))
            .collect();
        let counters = Counters::default();
        let evictions = Arc::clone(&counters.evictions);
        let cache = Cache::builder()
            .max_capacity(options.cache_bytes)
            .weigher(|_: &u64, block: &CachedBlock| {
                u32::try_from(block.heap_size()).unwrap_or(u32::MAX)
            })
            .eviction_listener(move |_, _, cause| {
                if cause == RemovalCause::Size {
                    evictions.fetch_add(1, Ordering::Relaxed);
                }
            })
            .build();
        let threads = options.threads.unwrap_or_else(rayon::current_num_threads);
        let pool = genomic_pool(threads)
            .map_err(|e| open_error(dir, format!("cannot start the genomic thread pool: {e}")))?;
        Ok(GenomicStore {
            dir: dir.to_path_buf(),
            index,
            reader,
            codec,
            groups,
            cache,
            cache_capacity: options.cache_bytes,
            counters,
            budget: ByteBudget::on_threads(
                options.read_budget_bytes,
                PermitThreads::GenomicWorkers,
            ),
            pool,
        })
    }

    /// The genomic data directory.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// The layout the store records, the `hpds.genomicProcessor.impl` it was built for.
    pub fn mode(&self) -> GenoMode {
        self.index.mode()
    }

    /// The decoded `geno.index`.
    pub(crate) fn index(&self) -> &GenoIndex {
        &self.index
    }

    /// Every group's patient tables, in index order.
    pub(crate) fn groups(&self) -> &[GroupPatients] {
        &self.groups
    }

    /// The pool genomic work runs on.
    pub(crate) fn pool(&self) -> &rayon::ThreadPool {
        &self.pool
    }

    /// The cache's capacity in bytes.
    pub(crate) fn cache_capacity(&self) -> u64 {
        self.cache_capacity
    }

    /// The genomic read budget's capacity, what it holds and what it has done.
    pub fn read_stats(&self) -> GenomicReadStats {
        self.budget.stats()
    }

    /// The genomic cache's counters.
    pub fn cache_stats(&self) -> GenomicCacheStats {
        self.cache.run_pending_tasks();
        GenomicCacheStats {
            capacity: self.cache_capacity,
            entries: self.cache.entry_count(),
            bytes: self.cache.weighted_size(),
            hits: self.counters.hits.load(Ordering::Relaxed),
            misses: self.counters.misses.load(Ordering::Relaxed),
            loads: self.counters.loads.load(Ordering::Relaxed),
            evictions: self.counters.evictions.load(Ordering::Relaxed),
            scan_reads: self.counters.scan_reads.load(Ordering::Relaxed),
        }
    }

    /// Drops every cached block.
    pub fn clear_cache(&self) {
        self.cache.invalidate_all();
        self.cache.run_pending_tasks();
    }

    /// The name of node `node` for messages: `group/dir`, either part empty in the layouts
    /// that have none.
    pub(crate) fn node_name(&self, node: u16) -> String {
        let n = &self.index.nodes()[usize::from(node)];
        format!(
            "{}/{}",
            self.index.groups()[usize::from(n.group)].name,
            n.dir_name
        )
    }

    /// Node `node` of the index.
    pub(crate) fn node(&self, node: u16) -> &GenoNode {
        &self.index.nodes()[usize::from(node)]
    }

    /// Node `node`'s info block for column `column`, always through the cache.
    ///
    /// # Errors
    /// [`QueryError::GenomicBlock`] when it cannot be read, authenticated or decoded.
    pub(crate) fn info_block(&self, node: u16, column: u16) -> Result<Arc<InfoBlock>> {
        let key = cache_key(node, GenoBlockKind::Info, u32::from(column));
        match self.cached(key, || {
            self.reader
                .read_info_block(&self.codec, &self.index, node, column)
                .map(|b| CachedBlock::Info(Arc::new(b)))
        }) {
            Ok(CachedBlock::Info(block)) => Ok(block),
            Ok(_) => unreachable!("an info key holds an info block"),
            Err(e) => Err(self.block_error(node, "info block", u32::from(column), e)),
        }
    }

    /// Runs `fold` on node `node`'s mask chunk `chunk`, read as `path` says. On the scan path
    /// a block the cache does not hold is read, decoded and folded under one permit of the
    /// read budget, which is released before this returns; `fold` must not call into rayon.
    ///
    /// # Errors
    /// [`QueryError::GenomicBlock`] when the chunk cannot be read, authenticated or decoded.
    pub(crate) fn with_mask_chunk<R>(
        &self,
        node: u16,
        chunk: u32,
        path: ReadPath,
        fold: impl FnOnce(&MaskChunk) -> R,
    ) -> Result<R> {
        let key = cache_key(node, GenoBlockKind::Mask, chunk);
        let read = || {
            self.reader
                .read_mask_chunk(&self.codec, &self.index, node, chunk)
        };
        let wrap = |e| self.block_error(node, "mask chunk", chunk, Arc::new(e));
        if path == ReadPath::Scan {
            if let Some(CachedBlock::Mask(cached)) = self.cache.get(&key) {
                self.counters.hits.fetch_add(1, Ordering::Relaxed);
                return Ok(fold(&cached));
            }
            self.counters.misses.fetch_add(1, Ordering::Relaxed);
            let location = self.index.chunk(node, chunk).map_err(wrap)?.mask;
            let _permit = self.budget.acquire(
                u64::from(location.stored_len).saturating_add(u64::from(location.decoded_len)),
            );
            let decoded = read().map_err(wrap)?;
            self.counters.scan_reads.fetch_add(1, Ordering::Relaxed);
            return Ok(fold(&decoded));
        }
        match self.cached(key, || read().map(|c| CachedBlock::Mask(Arc::new(c)))) {
            Ok(CachedBlock::Mask(chunk)) => Ok(fold(&chunk)),
            Ok(_) => unreachable!("a mask key holds a mask chunk"),
            Err(e) => Err(self.block_error(node, "mask chunk", chunk, e)),
        }
    }

    /// The decoded length of node `node`'s mask chunk `chunk`, from the index.
    pub(crate) fn mask_decoded_len(&self, node: u16, chunk: u32) -> u64 {
        self.index
            .chunk(node, chunk)
            .map_or(0, |c| u64::from(c.mask.decoded_len))
    }

    /// The cached block under `key`, or the one `load` reads, admitted.
    fn cached(
        &self,
        key: u64,
        load: impl FnOnce() -> std::result::Result<CachedBlock, FormatError>,
    ) -> std::result::Result<CachedBlock, Arc<FormatError>> {
        if let Some(block) = self.cache.get(&key) {
            self.counters.hits.fetch_add(1, Ordering::Relaxed);
            return Ok(block);
        }
        self.counters.misses.fetch_add(1, Ordering::Relaxed);
        let mut loaded = false;
        let block = self.cache.try_get_with(key, || {
            loaded = true;
            load()
        })?;
        if loaded {
            self.counters.loads.fetch_add(1, Ordering::Relaxed);
        }
        Ok(block)
    }

    fn block_error(
        &self,
        node: u16,
        kind: &str,
        ordinal: u32,
        error: Arc<FormatError>,
    ) -> QueryError {
        QueryError::GenomicBlock {
            node: self.node_name(node),
            what: format!("{kind} {ordinal}"),
            source: error,
        }
    }
}

/// The cache key of the block of `kind` and `ordinal` of node `node`.
fn cache_key(node: u16, kind: GenoBlockKind, ordinal: u32) -> u64 {
    (u64::from(node) << 40) | (u64::from(kind as u8) << 32) | u64::from(ordinal)
}

fn open_error(path: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: path.to_path_buf(),
        detail: error.to_string(),
    }
}

/// The refusal for a genomic directory `dir` without `geno.index`: one holding a Java genomic
/// store, in its own files or in node or group directories up to two levels down, names
/// `hpds-etl genomic-migrate`; anything else says no store is there.
fn refuse_without_index(dir: &Path) -> QueryError {
    let holds_java = |d: &Path| JAVA_GENOMIC_FILE_NAMES.iter().any(|n| d.join(n).exists());
    let subdirs = |d: &Path| -> Vec<PathBuf> {
        std::fs::read_dir(d)
            .map(|entries| {
                entries
                    .filter_map(std::result::Result::ok)
                    .map(|e| e.path())
                    .filter(|p| p.is_dir())
                    .collect()
            })
            .unwrap_or_default()
    };
    let java = holds_java(dir)
        || subdirs(dir)
            .iter()
            .any(|d| holds_java(d) || subdirs(d).iter().any(|dd| holds_java(dd)));
    if java {
        open_error(
            dir,
            format!(
                "it holds a Java genomic store and no {GENO_INDEX_FILE_NAME}. Build the Rust \
                 genomic store with hpds-etl genomic-migrate, and point \
                 HPDS_GENOMIC_DATA_DIRECTORY at it"
            ),
        )
    } else {
        open_error(
            dir,
            format!("no {GENO_INDEX_FILE_NAME} in the genomic data directory"),
        )
    }
}

/// Refuses `dir` when its `.generation` exists and does not name `store_id` alone.
fn check_generation(dir: &Path, store_id: &str) -> Result<()> {
    let path = dir.join(GENERATION_FILE_NAME);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(open_error(&path, e)),
    };
    let tokens: Vec<String> = text
        .split_whitespace()
        .map(str::to_ascii_lowercase)
        .collect();
    if tokens.len() == 1 && tokens[0] == store_id {
        return Ok(());
    }
    Err(open_error(
        dir,
        format!(
            "a partially published genomic store: {GENERATION_FILE_NAME} does not name the store \
             id {store_id} of {GENO_INDEX_FILE_NAME} alone"
        ),
    ))
}
