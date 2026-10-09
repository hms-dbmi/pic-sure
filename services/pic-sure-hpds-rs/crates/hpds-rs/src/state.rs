//! State shared by every request handler.

use std::sync::Arc;
use std::time::Duration;

use hpds_query::Engine;
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::config::Config;
use crate::error::ApiError;
use crate::jobs::ResultRegistry;
use crate::search::SearchIndex;

/// Health reason when the data directory could not be opened.
pub const NOT_OPENED_REASON: &str = "the phenotype store could not be opened; see the server log";

/// Health reason when the partitions were opened but hold no concept.
pub const NO_CONCEPTS_REASON: &str = "no partition holds a concept";

/// The engine, the async result registry and the search index. Cheap to clone.
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
}

impl AppState {
    /// State over `engine`, which is `None` when the data directory could not be opened
    /// (every query route then answers 503 and health reports DOWN), with the registry
    /// settings of `config`. The lowercase search index over the merged dictionary is built
    /// here, once.
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
                engine_slots,
            }),
        })
    }

    /// The engine.
    ///
    /// # Errors
    /// A 503 [`ApiError`] when the data directory could not be opened.
    pub fn engine(&self) -> Result<&Arc<Engine>, ApiError> {
        self.inner
            .engine
            .as_ref()
            .ok_or_else(|| ApiError::unavailable("the phenotype store is not loaded"))
    }

    /// `None` when the partitions are open and their merged dictionary holds at least one
    /// concept, the rule of Java's `HpdsReadinessHealthIndicator` without genomic data.
    /// Otherwise why the service is not ready: [`NOT_OPENED_REASON`] or
    /// [`NO_CONCEPTS_REASON`].
    pub fn down_reason(&self) -> Option<&'static str> {
        match &self.inner.engine {
            None => Some(NOT_OPENED_REASON),
            Some(engine) if !engine.partitions().is_healthy() => Some(NO_CONCEPTS_REASON),
            Some(_) => None,
        }
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

    /// How long a sync query may run before the request is answered 503.
    pub fn sync_timeout(&self) -> Duration {
        self.inner.sync_timeout
    }
}
