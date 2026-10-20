//! The genomic part of search: the `info` block of `POST /search` and the values of
//! `GET /search/values/` with `genomicConceptPath`, as Java's `PicSureV3Service.search` and
//! `QueryExecutor.searchInfoConceptValues` answer them over the configured processors.
//!
//! A node lists a column's values as `getInfoStoreValues` does: its info store's keys sorted
//! by `String.compareToIgnoreCase` and collected into a `HashSet`. The parents union their
//! nodes' sets into another `HashSet`, so the order a caller sees is Java's `HashSet` order of
//! the distinct keys, reproduced with [`java_hash_order`]; within one hash bucket Java's own
//! order depends on its parallel collection and is not specified. A column a node does not
//! hold makes Java dereference a null info store; Rust refuses that with 400.
//!
//! The value list of a column categorical on every node is built once from its info blocks
//! and kept for the life of the process, as Java's `infoStoreValuesCache` keeps it. A column
//! continuous on some node is listed per request and never kept: its keys are every distinct
//! number, too many to pin for a call the UI never makes.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, PoisonError};

use hpds_format::{JavaHashCapacity, java_compare_ignore_case, java_hash_order, java_string_cmp};

use super::filters::echo;
use super::listing::info_column_listing;
use super::store::GenomicStore;
use crate::error::{QueryError, Result};

/// Bytes a kept value list is counted at per value beyond the value's own bytes: the
/// `String` header.
const VALUE_OVERHEAD: u64 = 24;

/// One column of the `/search` info block, `ImmutableMap.of("description", ..., "values",
/// ..., "continuous", ...)` in Java.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InfoSearchEntry {
    /// The column key, the map key of the block.
    pub key: String,
    /// The ETL's description, as stored.
    pub description: String,
    /// Every value of the column in Java's order; empty when `continuous`.
    pub values: Arc<[String]>,
    /// Whether the entry's node holds the column as numeric.
    pub continuous: bool,
}

/// The value lists of categorical columns, built on first use and kept.
#[derive(Debug, Default)]
pub(crate) struct ValueLists {
    lists: Mutex<HashMap<u16, Arc<[String]>>>,
}

impl ValueLists {
    /// The values kept, and the bytes they take: each value's length plus a fixed overhead.
    pub(crate) fn stats(&self) -> (u64, u64) {
        let lists = self.lists.lock().unwrap_or_else(PoisonError::into_inner);
        let values = lists.values().map(|l| l.len() as u64).sum();
        let bytes = lists
            .values()
            .flat_map(|l| l.iter())
            .map(|v| v.len() as u64 + VALUE_OVERHEAD)
            .sum();
        (values, bytes)
    }
}

/// The refusal of a column some node does not hold, where Java dereferences a null info
/// store.
fn missing_column(column: &str) -> QueryError {
    QueryError::GenomicFilter(format!(
        "the INFO column \"{}\" is not held by every genomic node, so its values cannot be listed",
        echo(column)
    ))
}

/// Every value of `column` in Java's `getInfoStoreValues` order (see the module docs).
///
/// # Errors
/// [`QueryError::GenomicFilter`] when some node does not hold the column (an unknown column
/// included), and [`QueryError::GenomicBlock`] when an info block cannot be read.
pub(crate) fn column_values(store: &GenomicStore, column: &str) -> Result<Arc<[String]>> {
    let index = store.index();
    let Some(c) = index.column_index(column) else {
        return Err(missing_column(column));
    };
    let mut categorical = true;
    for node in index.nodes() {
        match node.info(c) {
            None => return Err(missing_column(column)),
            Some(entry) => categorical &= !entry.continuous,
        }
    }
    if categorical
        && let Some(list) = store
            .value_lists()
            .lists
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .get(&c)
    {
        return Ok(Arc::clone(list));
    }
    let mut keys: Vec<String> = Vec::new();
    for n in 0..index.nodes().len() {
        let block = store.info_block(n as u16, c)?;
        keys.extend(block.keys().map(str::to_owned));
    }
    keys.sort_by(|a, b| match java_compare_ignore_case(a, b) {
        Ordering::Equal => java_string_cmp(a, b),
        other => other,
    });
    keys.dedup();
    let list: Arc<[String]> =
        java_hash_order(keys.iter().map(String::as_str), JavaHashCapacity::Default)
            .into_iter()
            .map(|(_, v)| v.to_owned())
            .collect();
    if categorical {
        store
            .value_lists()
            .lists
            .lock()
            .unwrap_or_else(PoisonError::into_inner)
            .entry(c)
            .or_insert_with(|| Arc::clone(&list));
    }
    Ok(list)
}

/// `searchInfoConceptValues(column, query)`: the values of `column` (see [`column_values`])
/// whose upper case, as `toUpperCase(Locale.ENGLISH)` maps it, contains the upper-cased
/// `query`, in Java's order.
///
/// # Errors
/// As for [`column_values`].
pub(crate) fn search_values(
    store: &GenomicStore,
    column: &str,
    query: &str,
) -> Result<Vec<String>> {
    let values = column_values(store, column)?;
    if query.is_empty() {
        return Ok(values.to_vec());
    }
    let needle = query.to_uppercase();
    Ok(values
        .iter()
        .filter(|v| v.to_uppercase().contains(&needle))
        .cloned()
        .collect())
}

/// The `/search` info block for `term`: every INFO_COLUMN_LISTING entry whose lowercased
/// description or key contains the lowercased term, one per key in Java `String` order, the
/// last matching entry of a key winning as `TreeMap.put` lets it. A matching categorical
/// entry lists the column's values, which fails as Java does when some node lacks the
/// column; a continuous one lists none.
///
/// # Errors
/// As for [`column_values`], for a matching categorical entry.
pub(crate) fn search_info(store: &GenomicStore, term: &str) -> Result<Vec<InfoSearchEntry>> {
    let needle = term.to_lowercase();
    let mut found: Vec<InfoSearchEntry> = Vec::new();
    for meta in info_column_listing(store) {
        if !meta.description.to_lowercase().contains(&needle)
            && !meta.key.to_lowercase().contains(&needle)
        {
            continue;
        }
        let values: Arc<[String]> = if meta.continuous {
            Arc::from(Vec::new())
        } else {
            column_values(store, &meta.key)?
        };
        let entry = InfoSearchEntry {
            key: meta.key,
            description: meta.description,
            values,
            continuous: meta.continuous,
        };
        match found.iter_mut().find(|e| e.key == entry.key) {
            Some(slot) => *slot = entry,
            None => found.push(entry),
        }
    }
    found.sort_by(|a, b| java_string_cmp(&a.key, &b.key));
    Ok(found)
}
