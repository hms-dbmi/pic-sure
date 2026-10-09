//! State shared by every request handler.

use std::sync::Arc;
use std::time::Duration;

use hpds_query::{Engine, QueryValidator};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::config::Config;
use crate::error::ApiError;
use crate::jobs::ResultRegistry;
use crate::search::SearchIndex;

/// The engine, the async result registry, the search index, and the validation settings.
/// Cheap to clone.
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
    async_validator: QueryValidator,
}

impl AppState {
    /// State over `engine`, which is `None` when the store could not be opened (every query
    /// route then answers 503 and health reports DOWN), with the registry and validation
    /// settings of `config`. The lowercase search index is built here, once.
    ///
    /// # Errors
    /// The I/O error of [`ResultRegistry::new`] when the results directory cannot be prepared.
    pub fn new(engine: Option<Arc<Engine>>, config: &Config) -> std::io::Result<Self> {
        let engine_slots = Arc::new(Semaphore::new(config.engine_concurrency));
        let search_index = Arc::new(engine.as_ref().map_or_else(SearchIndex::default, |e| {
            SearchIndex::build(e.store().index().concepts())
        }));
        Ok(AppState {
            inner: Arc::new(Shared {
                engine,
                results: ResultRegistry::new(config, Arc::clone(&engine_slots))?,
                search_index,
                sync_timeout: config.sync_timeout,
                engine_slots,
                async_validator: QueryValidator {
                    require_authorization_filter: config.require_authorization_filter,
                },
            }),
        })
    }

    /// The engine.
    ///
    /// # Errors
    /// A 503 [`ApiError`] when the store could not be opened.
    pub fn engine(&self) -> Result<&Arc<Engine>, ApiError> {
        self.inner
            .engine
            .as_ref()
            .ok_or_else(|| ApiError::unavailable("the phenotype store is not loaded"))
    }

    /// True when the store is open and its index holds at least one concept, the rule of
    /// Java's `HpdsReadinessHealthIndicator` without genomic data.
    pub fn is_healthy(&self) -> bool {
        self.inner
            .engine
            .as_ref()
            .is_some_and(|e| e.store().is_healthy())
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

    /// The lowercase search index over the store's concepts.
    pub fn search_index(&self) -> &Arc<SearchIndex> {
        &self.inner.search_index
    }

    /// How long a sync query may run before the request is answered 503.
    pub fn sync_timeout(&self) -> Duration {
        self.inner.sync_timeout
    }

    /// The validator `POST /query` applies, with `hpds.requireAuthorizationFilter` from the
    /// configuration.
    pub fn async_validator(&self) -> &QueryValidator {
        &self.inner.async_validator
    }
}
