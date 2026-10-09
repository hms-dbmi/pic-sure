//! State shared by every request handler.

use std::sync::Arc;

use hpds_query::{Engine, QueryValidator};
use tokio::sync::{OwnedSemaphorePermit, Semaphore};

use crate::config::Config;
use crate::error::ApiError;
use crate::jobs::Jobs;

/// The engine, the async result registry, and the validation settings. Cheap to clone.
#[derive(Clone)]
pub struct AppState {
    inner: Arc<Shared>,
}

struct Shared {
    engine: Option<Arc<Engine>>,
    engine_slots: Arc<Semaphore>,
    jobs: Jobs,
    async_validator: QueryValidator,
}

impl AppState {
    /// State over `engine`, which is `None` when the store could not be opened (every query
    /// route then answers 503 and health reports DOWN), with the registry and validation
    /// settings of `config`.
    ///
    /// # Errors
    /// The I/O error of [`Jobs::new`] when the results directory cannot be prepared.
    pub fn new(engine: Option<Arc<Engine>>, config: &Config) -> std::io::Result<Self> {
        let engine_slots = Arc::new(Semaphore::new(config.engine_concurrency));
        Ok(AppState {
            inner: Arc::new(Shared {
                engine,
                jobs: Jobs::new(config, Arc::clone(&engine_slots))?,
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
    pub fn jobs(&self) -> &Jobs {
        &self.inner.jobs
    }

    /// The validator `POST /query` applies, with `hpds.requireAuthorizationFilter` from the
    /// configuration.
    pub fn async_validator(&self) -> &QueryValidator {
        &self.inner.async_validator
    }
}
