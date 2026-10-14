//! State shared by every request handler.

use std::sync::Arc;
use std::time::Duration;

use hpds_query::Engine;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::config::Config;
use crate::error::ApiError;
use crate::jobs::ResultRegistry;
use crate::metrics::Metrics;
use crate::search::SearchIndex;

/// Health reason when the data directory could not be opened.
pub const NOT_OPENED_REASON: &str = "the phenotype store could not be opened; see the server log";

/// Health reason when the store files changed on disk after the service opened them.
pub const STORE_CHANGED_REASON: &str =
    "the phenotype store changed on disk after it was opened; restart the service";

/// Health reason when the partitions were opened but hold no concept.
pub const NO_CONCEPTS_REASON: &str = "no partition holds a concept";

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

    /// `None` when the partitions are open and their merged dictionary holds at least one
    /// concept, the rule of Java's `HpdsReadinessHealthIndicator` without genomic data.
    /// Otherwise why the service is not ready: [`NOT_OPENED_REASON`],
    /// [`STORE_CHANGED_REASON`] or [`NO_CONCEPTS_REASON`].
    pub fn down_reason(&self) -> Option<&'static str> {
        match &self.inner.engine {
            None => Some(NOT_OPENED_REASON),
            Some(engine) if engine.partitions().check_unchanged().is_err() => {
                Some(STORE_CHANGED_REASON)
            }
            Some(engine) if !engine.partitions().is_healthy() => Some(NO_CONCEPTS_REASON),
            Some(_) => None,
        }
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
