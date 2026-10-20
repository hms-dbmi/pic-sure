//! State shared by every request handler.

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use hpds_query::Engine;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::config::Config;
use crate::error::ApiError;
use crate::genomic::{GENOMIC_CHANGED_REASON, GenomicStatus};
use crate::jobs::ResultRegistry;
use crate::metrics::Metrics;
use crate::search::SearchIndex;

/// Health reason when the data directory could not be opened.
pub const NOT_OPENED_REASON: &str = "the phenotype store could not be opened; see the server log";

/// Health reason when the store files changed on disk after the service opened them.
pub const STORE_CHANGED_REASON: &str =
    "the phenotype store changed on disk after it was opened; restart the service";

/// Health reason when the partitions were opened but hold no concept and no genomic store is
/// served.
pub const NO_CONCEPTS_REASON: &str = "no partition holds a concept";

/// Health reason when the partitions hold no concept and the genomic store holds no INFO
/// column, Java's "no phenotype or genomic data loaded".
pub const NO_DATA_REASON: &str =
    "no partition holds a concept and the genomic store holds no INFO column";

/// The engine, the async result registry, the search index and, when they are on, the
/// metrics. Cheap to clone.
#[derive(Clone)]
pub struct AppState {
    inner: Arc<Shared>,
}

struct Shared {
    engine: Option<Arc<Engine>>,
    engine_slots: Arc<Semaphore>,
    results: ResultRegistry,
    search_index: Arc<SearchIndex>,
    sync_timeout: Duration,
    metrics: Option<Metrics>,
    search_gzip: bool,
    genomic: GenomicStatus,
    excerpt_slots: Arc<Semaphore>,
    excerpt_concurrency: usize,
    excerpt_stall: Duration,
    excerpt_stream: Duration,
    excerpts: ExcerptCounters,
}

/// How streamed excerpts ended, other than in full, and how many were refused at the cap.
#[derive(Debug, Default)]
pub struct ExcerptCounters {
    stalled: AtomicU64,
    expired: AtomicU64,
    aborted: AtomicU64,
    store_changed: AtomicU64,
    refused: AtomicU64,
}

/// A snapshot of [`ExcerptCounters`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExcerptCounts {
    /// Streams ended because the client took no chunk within `HPDS_EXCERPT_STALL_SECONDS`.
    pub stalled: u64,
    /// Streams ended because the body was not sent within `HPDS_EXCERPT_STREAM_SECONDS`.
    pub expired: u64,
    /// Streams ended because the client left or the write failed.
    pub aborted: u64,
    /// Streams ended because the genomic store changed on disk while they were written.
    pub store_changed: u64,
    /// Excerpts refused with a 503 because every `HPDS_EXCERPT_CONCURRENCY` slot was in use.
    pub refused: u64,
}

/// Why a streamed excerpt ended before its last byte.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ExcerptEnd {
    /// The client took no chunk within `HPDS_EXCERPT_STALL_SECONDS`.
    Stalled,
    /// The body was not sent in full within `HPDS_EXCERPT_STREAM_SECONDS`.
    Expired,
    /// The client went away, or the excerpt failed while it was written.
    Aborted,
    /// The genomic store changed on disk between the measure pass and the last chunk, so the
    /// rows sent may not be the rows measured.
    StoreChanged,
}

impl ExcerptCounters {
    /// Counts one excerpt that ended as `end`.
    pub fn record(&self, end: ExcerptEnd) {
        let counter = match end {
            ExcerptEnd::Stalled => &self.stalled,
            ExcerptEnd::Expired => &self.expired,
            ExcerptEnd::Aborted => &self.aborted,
            ExcerptEnd::StoreChanged => &self.store_changed,
        };
        counter.fetch_add(1, Ordering::Relaxed);
    }

    /// Counts one excerpt refused because no excerpt slot was free.
    pub fn record_refused(&self) {
        self.refused.fetch_add(1, Ordering::Relaxed);
    }

    /// The counts so far.
    pub fn counts(&self) -> ExcerptCounts {
        ExcerptCounts {
            stalled: self.stalled.load(Ordering::Relaxed),
            expired: self.expired.load(Ordering::Relaxed),
            aborted: self.aborted.load(Ordering::Relaxed),
            store_changed: self.store_changed.load(Ordering::Relaxed),
            refused: self.refused.load(Ordering::Relaxed),
        }
    }
}

impl AppState {
    /// State over `engine`, which is `None` when the data directory could not be opened
    /// (every query route then answers 503 and health reports DOWN), with the registry
    /// settings of `config`. The lowercase search index over the merged dictionary is built
    /// here, once. Metrics are kept when `config.metrics` is set.
    ///
    /// # Errors
    /// The I/O error of [`ResultRegistry::new`] when the results directory cannot be prepared.
    pub fn new(engine: Option<Arc<Engine>>, config: &Config) -> std::io::Result<Self> {
        let genomic = match &engine {
            Some(e) if e.genomic().is_some() => GenomicStatus::Served,
            _ => GenomicStatus::Off,
        };
        Self::with_genomic(engine, config, genomic)
    }

    /// [`AppState::new`] with the genomic status `genomic` that opening the genomic store
    /// gave ([`crate::genomic::attach_genomic`]).
    ///
    /// # Errors
    /// As for [`AppState::new`].
    pub fn with_genomic(
        engine: Option<Arc<Engine>>,
        config: &Config,
        genomic: GenomicStatus,
    ) -> std::io::Result<Self> {
        let engine_slots = Arc::new(Semaphore::new(config.engine_concurrency));
        let search_index = Arc::new(engine.as_ref().map_or_else(SearchIndex::default, |e| {
            SearchIndex::build(e.partitions().dictionary())
        }));
        Ok(AppState {
            inner: Arc::new(Shared {
                engine,
                results: ResultRegistry::new(config, Arc::clone(&engine_slots))?,
                search_index,
                sync_timeout: config.sync_timeout,
                metrics: config.metrics.then(Metrics::default),
                search_gzip: config.search_gzip,
                engine_slots,
                genomic,
                excerpt_slots: Arc::new(Semaphore::new(config.excerpt_concurrency)),
                excerpt_concurrency: config.excerpt_concurrency,
                excerpt_stall: config.excerpt_stall,
                excerpt_stream: config.excerpt_stream,
                excerpts: ExcerptCounters::default(),
            }),
        })
    }

    /// The engine, while the data directory still holds the store it opened.
    ///
    /// # Errors
    /// A 503 [`ApiError`] when the data directory could not be opened, or when its store
    /// files changed on disk since ([`hpds_query::PartitionSet::check_unchanged`]).
    pub fn engine(&self) -> Result<&Arc<Engine>, ApiError> {
        let engine = self
            .inner
            .engine
            .as_ref()
            .ok_or_else(|| ApiError::unavailable("the phenotype store is not loaded"))?;
        engine.partitions().check_unchanged()?;
        Ok(engine)
    }

    /// Fails while the engine's data directory no longer holds the store it opened. Passes
    /// when no store was opened.
    ///
    /// # Errors
    /// A 503 [`ApiError`] naming [`STORE_CHANGED_REASON`].
    pub fn check_store(&self) -> Result<(), ApiError> {
        match &self.inner.engine {
            Some(engine) => Ok(engine.partitions().check_unchanged()?),
            None => Ok(()),
        }
    }

    /// `None` when the service is ready, the rule of Java's `HpdsReadinessHealthIndicator`:
    /// the partitions are open and their merged dictionary holds at least one concept, or,
    /// with a genomic store served, either that or the genomic store holds an INFO column.
    /// Otherwise why the service is not ready, in this order: [`NOT_OPENED_REASON`],
    /// [`STORE_CHANGED_REASON`], the reason of a genomic store that could not be served
    /// ([`GenomicStatus::Down`]), [`GENOMIC_CHANGED_REASON`], then [`NO_CONCEPTS_REASON`]
    /// or, with a genomic store, [`NO_DATA_REASON`]. Unlike Java, which fails to start when
    /// its genomic processor cannot be built, the service starts and reports DOWN.
    pub fn down_reason(&self) -> Option<&'static str> {
        let engine = match &self.inner.engine {
            None => return Some(NOT_OPENED_REASON),
            Some(engine) => engine,
        };
        if engine.partitions().check_unchanged().is_err() {
            return Some(STORE_CHANGED_REASON);
        }
        if let GenomicStatus::Down(reason) = self.inner.genomic {
            return Some(reason);
        }
        let genomic = engine.genomic();
        if genomic.is_some_and(|store| store.check_unchanged().is_err()) {
            return Some(GENOMIC_CHANGED_REASON);
        }
        if engine.partitions().is_healthy() {
            return None;
        }
        match genomic {
            None => Some(NO_CONCEPTS_REASON),
            Some(store) if store.info_column_count() == 0 => Some(NO_DATA_REASON),
            Some(_) => None,
        }
    }

    /// What the service does with genomic data.
    pub fn genomic_status(&self) -> GenomicStatus {
        self.inner.genomic
    }

    /// Fails with a 503 naming the reason when `needs_genomic` holds and the genomic store
    /// is configured but could not be served, so a request that needs genomic data is not
    /// answered as if no genomic data were configured.
    ///
    /// # Errors
    /// A 503 [`ApiError`] carrying the [`GenomicStatus::Down`] reason.
    pub fn check_genomic(&self, needs_genomic: bool) -> Result<(), ApiError> {
        match self.inner.genomic {
            GenomicStatus::Down(reason) if needs_genomic => Err(ApiError::unavailable(reason)),
            _ => Ok(()),
        }
    }

    /// True while another genomic store of another id has been moved into the genomic
    /// directory and the engine goes on serving the one it opened. Read after
    /// [`AppState::down_reason`], which runs the check that notices it.
    pub fn genomic_store_superseded(&self) -> bool {
        self.inner
            .engine
            .as_ref()
            .and_then(|engine| engine.genomic())
            .is_some_and(hpds_query::GenomicStore::store_superseded)
    }

    /// True while another store of another id has been moved into the data directory and
    /// the engine goes on serving the store it opened
    /// ([`hpds_query::PartitionSet::store_superseded`]). Read after
    /// [`AppState::down_reason`], which runs the check that notices it.
    pub fn store_superseded(&self) -> bool {
        self.inner
            .engine
            .as_ref()
            .is_some_and(|engine| engine.partitions().store_superseded())
    }

    /// True when [`AppState::down_reason`] is `None`.
    pub fn is_healthy(&self) -> bool {
        self.down_reason().is_none()
    }

    /// Waits for one of the `HPDS_ENGINE_CONCURRENCY` engine slots; the slot is released when
    /// the returned permit drops.
    pub async fn engine_slot(&self) -> OwnedSemaphorePermit {
        Arc::clone(&self.inner.engine_slots)
            .acquire_owned()
            .await
            .unwrap_or_else(|_| unreachable!("the engine semaphore is never closed"))
    }

    /// One of the `HPDS_EXCERPT_CONCURRENCY` excerpt slots, which an excerpt holds beside its
    /// engine slot until its last row is sent, or `None` at once when every slot is in use.
    /// The slot is released when the returned permit drops.
    pub fn try_excerpt_slot(&self) -> Option<OwnedSemaphorePermit> {
        Arc::clone(&self.inner.excerpt_slots)
            .try_acquire_owned()
            .ok()
    }

    /// The excerpt slots in use, and `HPDS_EXCERPT_CONCURRENCY`.
    pub fn excerpt_slots_in_use(&self) -> (usize, usize) {
        let total = self.inner.excerpt_concurrency;
        (
            total.saturating_sub(self.inner.excerpt_slots.available_permits()),
            total,
        )
    }

    /// How long a streamed excerpt waits for its client to take a chunk, and how long its
    /// whole body may take.
    pub fn excerpt_bounds(&self) -> (Duration, Duration) {
        (self.inner.excerpt_stall, self.inner.excerpt_stream)
    }

    /// How streamed excerpts ended early.
    pub fn excerpt_counters(&self) -> &ExcerptCounters {
        &self.inner.excerpts
    }

    /// The async result registry.
    pub fn results(&self) -> &ResultRegistry {
        &self.inner.results
    }

    /// The lowercase search index over the merged dictionary.
    pub fn search_index(&self) -> &Arc<SearchIndex> {
        &self.inner.search_index
    }

    /// The HTTP counters, or `None` when metrics are off.
    pub fn metrics(&self) -> Option<&Metrics> {
        self.inner.metrics.as_ref()
    }

    /// True when `/search` compresses answers for requests that accept gzip
    /// (`HPDS_SEARCH_GZIP`).
    pub fn search_gzip(&self) -> bool {
        self.inner.search_gzip
    }

    /// How long a sync query may run before the request is answered 503.
    pub fn sync_timeout(&self) -> Duration {
        self.inner.sync_timeout
    }
}
