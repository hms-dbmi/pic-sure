//! The opt-in result cache: rendered answers of COUNT, CROSS_COUNT,
//! CATEGORICAL_CROSS_COUNT, CONTINUOUS_CROSS_COUNT and OBSERVATION_CROSS_COUNT, bounded in
//! bytes.
//!
//! An entry is keyed on everything that decides the answer and nothing else:
//!
//! - the store generation (a hash of the store id);
//! - the result type;
//! - the partitions the query's consents select, after admission, as a bit per partition,
//!   so two different consent selections never share an entry;
//! - a canonical encoding of the phenotypic clause (every filter field, `not` included,
//!   floats by their bits), and of `select` for the two types that read it (CROSS_COUNT and
//!   OBSERVATION_CROSS_COUNT);
//! - `authorizationFilters` as their exact canonical JSON: the filters and each filter's
//!   values in request order, repeats kept, with nothing sorted or deduplicated. They are
//!   validated and never applied, so what order or repetition would mean is undefined;
//!   keying them exactly means two queries that differ there never share an entry, which
//!   costs hits and is never wrong, and stays correct whatever meaning they are given.
//!
//! Left out because they never change an answer: `id`, `picSureId`, the consent values
//! themselves (the selection replaces them, so unknown values and their order do not
//! matter), and `select` for COUNT, CATEGORICAL_CROSS_COUNT and CONTINUOUS_CROSS_COUNT.
//! CATEGORICAL_CROSS_COUNT reads the concepts and values of the clause's filters, which
//! the clause encoding covers. `genomicFilters` is always empty here, since admission
//! refuses any.
//!
//! The key is the full encoding, not a hash of it, so two queries share an entry only when
//! they encode identically.
//!
//! The cache lives in the process's memory and dies with it. The service opens its stores
//! once, at startup, so a new load is served only after a restart, which starts with an
//! empty cache. The generation in the key does not expire anything; it only ties each
//! entry to the stores it was computed from.

use std::sync::atomic::{AtomicU64, Ordering};

use bytes::Bytes;
use moka::sync::Cache;

use crate::limits::CheckedQuery;
use crate::model::{AuthorizationFilter, PhenotypicClause, PhenotypicFilter, ResultType};
use crate::results::SyncResult;

/// Bytes charged to each entry on top of its key and body, for the cache's own bookkeeping.
const ENTRY_OVERHEAD: u64 = 128;

/// An entry may take at most this fraction of the cache (1/16), so one wide answer cannot
/// evict most of the others.
const MAX_ENTRY_SHARE: u64 = 16;

/// Bytes of the shortest key [`key_of`] writes: the generation (16), the result type
/// `COUNT` behind its length (8 + 5), the partition count (8), one byte of partition bits
/// (at most eight partitions), the no-clause tag (1) and the empty `authorizationFilters`
/// JSON `[]` behind its length (8 + 2).
const SHORTEST_KEY: u64 = 16 + 8 + 5 + 8 + 1 + 1 + 8 + 2;

/// The smallest bound under which the cache can store any answer: the lightest possible
/// entry (the shortest key, a one-byte COUNT body and [`ENTRY_OVERHEAD`]) is then exactly
/// the largest share one entry may take. A smaller nonzero bound turns the cache on but
/// stores nothing.
pub const MIN_RESULT_CACHE_BYTES: u64 = MAX_ENTRY_SHARE * (SHORTEST_KEY + 1 + ENTRY_OVERHEAD);

/// A sync answer as the HTTP layer sends it: the content type and the body bytes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SyncAnswer {
    content_type: &'static str,
    body: Bytes,
}

impl SyncAnswer {
    /// The answer for `result`: its [`SyncResult::content_type`] and [`SyncResult::body`].
    pub fn of(result: &SyncResult) -> Self {
        SyncAnswer {
            content_type: result.content_type(),
            body: Bytes::from(result.body()),
        }
    }

    /// `text/plain` for COUNT, `application/json` for the cross counts.
    pub fn content_type(&self) -> &'static str {
        self.content_type
    }

    /// The response body. Cloning it shares the bytes.
    pub fn body(&self) -> &Bytes {
        &self.body
    }
}

/// Counters of the result cache. `entries` and `bytes` are read after the cache applies its
/// pending inserts and evictions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ResultCacheStats {
    /// Lookups answered from the cache.
    pub hits: u64,
    /// Lookups of a cacheable query that found no entry.
    pub misses: u64,
    /// Entries held.
    pub entries: u64,
    /// Summed weight of the entries held: key, body and a fixed overhead each.
    pub bytes: u64,
    /// The byte bound.
    pub capacity: u64,
}

/// The cache. Shared by every request through the engine.
pub(crate) struct ResultCache {
    entries: Cache<Box<[u8]>, SyncAnswer>,
    capacity: u64,
    hits: AtomicU64,
    misses: AtomicU64,
}

impl std::fmt::Debug for ResultCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ResultCache")
            .field("capacity", &self.capacity)
            .finish_non_exhaustive()
    }
}

impl ResultCache {
    /// An empty cache bounded at `capacity` bytes, which must be above 0.
    pub(crate) fn new(capacity: u64) -> Self {
        ResultCache {
            entries: Cache::<Box<[u8]>, SyncAnswer>::builder()
                .max_capacity(capacity)
                .weigher(|key, answer| u32::try_from(entry_weight(key, answer)).unwrap_or(u32::MAX))
                .build(),
            capacity,
            hits: AtomicU64::new(0),
            misses: AtomicU64::new(0),
        }
    }

    /// The cached answer for `key`, counting a hit or a miss.
    pub(crate) fn get(&self, key: &[u8]) -> Option<SyncAnswer> {
        let found = self.entries.get(key);
        let counter = if found.is_some() {
            &self.hits
        } else {
            &self.misses
        };
        counter.fetch_add(1, Ordering::Relaxed);
        found
    }

    /// Stores `answer` under `key`, unless it weighs more than 1/16 of the capacity.
    pub(crate) fn insert(&self, key: Box<[u8]>, answer: SyncAnswer) {
        if entry_weight(&key, &answer) > self.capacity / MAX_ENTRY_SHARE {
            return;
        }
        self.entries.insert(key, answer);
    }

    /// The counters, after applying pending inserts and evictions.
    pub(crate) fn stats(&self) -> ResultCacheStats {
        self.entries.run_pending_tasks();
        ResultCacheStats {
            hits: self.hits.load(Ordering::Relaxed),
            misses: self.misses.load(Ordering::Relaxed),
            entries: self.entries.entry_count(),
            bytes: self.entries.weighted_size(),
            capacity: self.capacity,
        }
    }
}

fn entry_weight(key: &[u8], answer: &SyncAnswer) -> u64 {
    key.len() as u64 + answer.body.len() as u64 + ENTRY_OVERHEAD
}

/// True for the result types the cache holds.
pub(crate) fn is_cacheable(result_type: ResultType) -> bool {
    matches!(
        result_type,
        ResultType::Count
            | ResultType::CrossCount
            | ResultType::CategoricalCrossCount
            | ResultType::ContinuousCrossCount
            | ResultType::ObservationCrossCount
    )
}

/// True when `result` may be cached: anything but a cross count holding -1, which marks a
/// block that could not be loaded and is an error reported as a value.
pub(crate) fn is_storable(result: &SyncResult) -> bool {
    match result {
        SyncResult::CrossCount(counts) | SyncResult::ObservationCrossCount(counts) => {
            counts.entries.iter().all(|(_, count)| *count >= 0)
        }
        SyncResult::Count(_)
        | SyncResult::CategoricalCrossCount(_)
        | SyncResult::ContinuousCrossCount(_) => true,
    }
}

/// The cache key of an admitted query under store generation `generation`, or `None` when
/// its result type is not cached or it carries genomic filters (which admission refuses).
pub(crate) fn key_of(checked: &CheckedQuery<'_, '_>, generation: u128) -> Option<Box<[u8]>> {
    let query = checked.query();
    let result_type = query.expected_result_type;
    if !is_cacheable(result_type) || !query.genomic_filters.is_empty() {
        return None;
    }
    let selection = checked.selection();
    let mut key = KeyWriter::default();
    key.bytes(&generation.to_le_bytes());
    key.text(result_type.java_name());
    let mask = selection.mask();
    key.count(mask.len());
    let mut bits = vec![0u8; mask.len().div_ceil(8)];
    for (slot, _) in mask.iter().enumerate().filter(|(_, selected)| **selected) {
        bits[slot / 8] |= 1 << (slot % 8);
    }
    key.bytes(&bits);
    if matches!(
        result_type,
        ResultType::CrossCount | ResultType::ObservationCrossCount
    ) {
        key.count(query.select.len());
        for path in &query.select {
            key.text(path);
        }
    }
    match &query.phenotypic_clause {
        None => key.byte(0),
        Some(clause) => {
            key.byte(1);
            key.clause(clause);
        }
    }
    key.authorization_filters(&query.authorization_filters);
    Some(key.finish())
}

/// A prefix-free byte encoding: every string and list is length-prefixed and every optional
/// value tagged, so two different inputs never encode the same.
#[derive(Default)]
struct KeyWriter {
    out: Vec<u8>,
}

impl KeyWriter {
    fn finish(self) -> Box<[u8]> {
        self.out.into_boxed_slice()
    }

    fn byte(&mut self, value: u8) {
        self.out.push(value);
    }

    fn bytes(&mut self, value: &[u8]) {
        self.out.extend_from_slice(value);
    }

    fn count(&mut self, count: usize) {
        self.bytes(&(count as u64).to_le_bytes());
    }

    fn text(&mut self, value: &str) {
        self.count(value.len());
        self.bytes(value.as_bytes());
    }

    fn float(&mut self, value: Option<f64>) {
        match value {
            None => self.byte(0),
            Some(v) => {
                self.byte(1);
                self.bytes(&v.to_bits().to_le_bytes());
            }
        }
    }

    fn flag(&mut self, value: Option<bool>) {
        self.byte(match value {
            None => 0,
            Some(false) => 1,
            Some(true) => 2,
        });
    }

    /// Writes the clause tree in pre-order, each subquery followed by its child count, with
    /// an explicit stack so a deep tree cannot overflow the thread's stack.
    fn clause(&mut self, root: &PhenotypicClause) {
        let mut stack = vec![root];
        while let Some(clause) = stack.pop() {
            match clause {
                PhenotypicClause::Filter(filter) => self.filter(filter),
                PhenotypicClause::Subquery(subquery) => {
                    self.byte(b'S');
                    self.flag(subquery.not);
                    match subquery.operator {
                        None => self.byte(0),
                        Some(operator) => {
                            self.byte(1);
                            self.text(operator.java_name());
                        }
                    }
                    self.count(subquery.phenotypic_clauses.len());
                    stack.extend(subquery.phenotypic_clauses.iter().rev());
                }
            }
        }
    }

    /// Writes `filters` as their canonical JSON, a length-prefixed
    /// `[{"conceptPath":...,"values":[...]}]` with no whitespace, in request order with
    /// repeats kept. `values` is `null` when the request held `null` or omitted it, which
    /// the query model does not tell apart.
    fn authorization_filters(&mut self, filters: &[AuthorizationFilter]) {
        let canonical: Vec<serde_json::Value> = filters
            .iter()
            .map(|filter| {
                serde_json::json!({
                    "conceptPath": filter.concept_path,
                    "values": filter.values,
                })
            })
            .collect();
        let json = serde_json::Value::Array(canonical).to_string();
        self.text(&json);
    }

    fn filter(&mut self, filter: &PhenotypicFilter) {
        self.byte(b'F');
        self.text(filter.phenotypic_filter_type.java_name());
        self.text(&filter.concept_path);
        match &filter.values {
            None => self.byte(0),
            Some(values) => {
                self.byte(1);
                self.count(values.len());
                for value in values {
                    self.text(value);
                }
            }
        }
        self.float(filter.min);
        self.float(filter.max);
        self.flag(filter.not);
    }
}
