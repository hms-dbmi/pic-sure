//! Consent partitions: `PartitionedPhenotypicObservationStore`.
//!
//! A data directory holds either one store at its root (unpartitioned) or one store per
//! immediate subdirectory, each named after the consent that grants it. A [`PartitionSet`]
//! opens them all with one key and one block cache, merges their dictionaries into one
//! [`SummaryColumnMeta`] per concept, and picks the partitions a query may read
//! ([`PartitionSet::select`]).

use std::borrow::Cow;
use std::fmt::Write as _;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use hpds_format::{
    Block, ConceptMeta, ConceptType, CryptoKey, INDEX_FILE_NAME, Protection, java_double_cmp,
    java_double_to_string, java_string_cmp,
};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde_json::Value;

use crate::error::{QueryError, Result};
use crate::model::Query;
use crate::store::{BlockCache, KEY_FILE_NAME, Store, StoreOptions, block_cache};
use crate::validate::ConceptLookup;

/// Name of the single partition of an unpartitioned data directory. It never matches a
/// consent, as in Java.
pub const UNPARTITIONED_NAME: &str = "__unpartitioned__";

/// How a [`PartitionSet`] is opened.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PartitionOptions {
    /// Cache budget and key rules. `cache_bytes` bounds the one cache all partitions share.
    pub store: StoreOptions,
    /// `hpds.requireAuthorizationFilter`. When set, an unpartitioned data directory is
    /// refused at open, and every query must carry at least one consent value.
    pub require_authorization_filter: bool,
}

impl Default for PartitionOptions {
    /// The default store options, and consents required (the Java default).
    fn default() -> Self {
        PartitionOptions {
            store: StoreOptions::default(),
            require_authorization_filter: true,
        }
    }
}

/// Whether the data directory is split by consent.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layout {
    /// One store per consent subdirectory.
    Partitioned,
    /// One store at the root, read as the single partition [`UNPARTITIONED_NAME`] whatever
    /// the consents.
    Unpartitioned,
}

/// One consent's store.
#[derive(Debug)]
pub struct Partition {
    name: String,
    store: Store,
}

impl Partition {
    /// The partition name: the subdirectory name, or [`UNPARTITIONED_NAME`].
    pub fn name(&self) -> &str {
        &self.name
    }

    /// The partition's store.
    pub fn store(&self) -> &Store {
        &self.store
    }
}

/// `SummaryColumnMeta`: a concept's metadata merged over every partition that holds it, the
/// dictionary `/search` and the validators read. Not consent-scoped.
///
/// Merge rules, as `SummaryColumnMeta.merge` applies them: the largest `width_in_bytes`, the
/// union of category values, the smallest `min` and largest `max` and timestamps (absent
/// values skipped, `Math.min` and `Math.max` semantics for NaN and signed zero), the summed
/// patient count (a patient in two partitions counts twice, as in Java), and `has_timestamp`
/// when any partition has one. The type is the same everywhere: a conflict refuses the open.
#[derive(Debug, Clone, PartialEq)]
pub struct SummaryColumnMeta {
    /// The concept path.
    pub name: String,
    /// The largest `widthInBytes` of any partition.
    pub width_in_bytes: i32,
    /// True for categorical concepts.
    pub categorical: bool,
    /// Every category value of any partition, distinct, in Java string order. Empty for
    /// numeric concepts. Java holds an unordered set here.
    pub category_values: Vec<String>,
    /// The smallest value, `None` when no partition records one.
    pub min: Option<f64>,
    /// The largest value, `None` when no partition records one.
    pub max: Option<f64>,
    /// Patient counts summed over partitions.
    pub patient_count: u64,
    /// True when any partition's observations carry a timestamp.
    pub has_timestamp: bool,
    /// The earliest timestamp in epoch milliseconds.
    pub timestamp_min: Option<i64>,
    /// The latest timestamp in epoch milliseconds.
    pub timestamp_max: Option<i64>,
}

impl SummaryColumnMeta {
    /// The summary of one partition's concept (`new SummaryColumnMeta(columnMeta)`).
    pub fn from_concept(concept: &ConceptMeta) -> Self {
        SummaryColumnMeta {
            name: concept.path.clone(),
            width_in_bytes: concept.width_in_bytes,
            categorical: concept.is_categorical(),
            category_values: concept.category_values.clone().unwrap_or_default(),
            min: concept.min,
            max: concept.max,
            patient_count: u64::from(concept.patient_count),
            has_timestamp: concept.has_timestamp,
            timestamp_min: concept.timestamp_min,
            timestamp_max: concept.timestamp_max,
        }
    }

    /// Folds another partition's metadata for the same concept into this one. Category
    /// values are appended; [`SummaryColumnMeta::finish`] sorts and deduplicates them.
    fn absorb(&mut self, concept: &ConceptMeta) {
        self.width_in_bytes = self.width_in_bytes.max(concept.width_in_bytes);
        self.category_values
            .extend(concept.category_values.iter().flatten().cloned());
        self.min = merge_option(self.min, concept.min, java_min);
        self.max = merge_option(self.max, concept.max, java_max);
        self.patient_count += u64::from(concept.patient_count);
        self.has_timestamp |= concept.has_timestamp;
        self.timestamp_min = merge_option(self.timestamp_min, concept.timestamp_min, i64::min);
        self.timestamp_max = merge_option(self.timestamp_max, concept.timestamp_max, i64::max);
    }

    fn finish(&mut self) {
        self.category_values.sort_by(|a, b| java_string_cmp(a, b));
        self.category_values.dedup();
    }

    /// Numeric or categorical.
    pub fn concept_type(&self) -> ConceptType {
        if self.categorical {
            ConceptType::Categorical
        } else {
            ConceptType::Numeric
        }
    }

    /// Appends the concept as Jackson writes `SummaryColumnMeta` from its getters: `name`,
    /// `widthInBytes`, `categorical`, `categoryValues`, `min`, `max`, `patientCount`,
    /// `timestampMin`, `timestampMax`, with absent values as `null`. `hasTimestamp` has no
    /// bean getter and is left out. Doubles are written as `Double.toString` writes them,
    /// with NaN and the infinities as strings. Category values come out in Java string order,
    /// where Java's set order is unspecified.
    pub fn write_json(&self, out: &mut String) {
        out.push_str(r#"{"name":"#);
        push_json_string(out, &self.name);
        let _ = write!(
            out,
            r#","widthInBytes":{},"categorical":{},"categoryValues":["#,
            self.width_in_bytes, self.categorical
        );
        for (i, value) in self.category_values.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            push_json_string(out, value);
        }
        out.push(']');
        for (name, value) in [("min", self.min), ("max", self.max)] {
            let _ = write!(out, r#","{name}":"#);
            match value {
                Some(v) if v.is_finite() => out.push_str(&java_double_to_string(v)),
                Some(v) => push_json_string(out, &java_double_to_string(v)),
                None => out.push_str("null"),
            }
        }
        let _ = write!(out, r#","patientCount":{}"#, self.patient_count);
        for (name, value) in [
            ("timestampMin", self.timestamp_min),
            ("timestampMax", self.timestamp_max),
        ] {
            let _ = write!(out, r#","{name}":"#);
            match value {
                Some(v) => {
                    let _ = write!(out, "{v}");
                }
                None => out.push_str("null"),
            }
        }
        out.push('}');
    }

    /// [`SummaryColumnMeta::write_json`] into a new string.
    pub fn to_json(&self) -> String {
        let mut out = String::new();
        self.write_json(&mut out);
        out
    }
}

fn push_json_string(out: &mut String, value: &str) {
    out.push_str(&Value::String(value.to_owned()).to_string());
}

fn merge_option<T: Copy>(a: Option<T>, b: Option<T>, pick: fn(T, T) -> T) -> Option<T> {
    match (a, b) {
        (Some(a), Some(b)) => Some(pick(a, b)),
        (a, b) => a.or(b),
    }
}

/// `Math.min` on doubles: NaN wins, and `-0.0` is below `0.0`.
fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if java_double_cmp(a, b).is_le() {
        a
    } else {
        b
    }
}

/// `Math.max` on doubles: NaN wins, and `0.0` is above `-0.0`.
fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() || b.is_nan() {
        f64::NAN
    } else if java_double_cmp(a, b).is_ge() {
        a
    } else {
        b
    }
}

/// Every partition of a data directory, opened with one key and one block cache. Share it
/// behind an `Arc`; every method takes `&self`.
pub struct PartitionSet {
    dir: PathBuf,
    layout: Layout,
    require_consents: bool,
    partitions: Vec<Partition>,
    dictionary: Vec<SummaryColumnMeta>,
    all_patients: RoaringBitmap,
    cache: BlockCache,
}

impl std::fmt::Debug for PartitionSet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PartitionSet")
            .field("dir", &self.dir)
            .field("layout", &self.layout)
            .field("require_consents", &self.require_consents)
            .field(
                "partitions",
                &self.partitions.iter().map(|p| &p.name).collect::<Vec<_>>(),
            )
            .field("concepts", &self.dictionary.len())
            .field("patients", &self.all_patients.len())
            .finish_non_exhaustive()
    }
}

impl PartitionSet {
    /// Opens the data directory `dir`.
    ///
    /// - When `dir` itself holds `pheno.index`, it is one unpartitioned store, read as the
    ///   partition [`UNPARTITIONED_NAME`]. Its subdirectories are ignored.
    /// - Otherwise every immediate subdirectory holding `pheno.index` is a partition named
    ///   after the subdirectory. Other subdirectories (a genomic `all` directory,
    ///   `lost+found`) are skipped; Java would load each as an empty partition, which reads
    ///   the same.
    ///
    /// The key is `<dir>/encryption_key`, shared by every partition. A partition may carry
    /// its own `encryption_key` only if it holds the same key. With no root key the
    /// partitions open as plain stores, and only if `options.store.allow_plain` is set.
    /// Partitions are kept in Java string order of their names.
    ///
    /// # Errors
    /// [`QueryError::Open`] when the directory cannot be listed, holds no store, is
    /// unpartitioned while `options.require_authorization_filter` is set, a partition's key
    /// file differs from the shared key, or a store cannot be opened (see [`Store::open`]).
    /// [`QueryError::TypeConflict`] when two partitions type one concept differently.
    pub fn open(dir: &Path, options: &PartitionOptions) -> Result<Self> {
        let cache = block_cache(options.store.cache_bytes);
        let root_key = read_key_text(&dir.join(KEY_FILE_NAME))?;
        let protection = match &root_key {
            Some(text) => Protection::Encrypted(
                CryptoKey::from_key_file_bytes(text.as_bytes()).map_err(|e| open_error(dir, e))?,
            ),
            None if options.store.allow_plain => Protection::Plain,
            None => {
                return Err(open_error(
                    dir,
                    format!(
                        "no key file {} and plain stores are not allowed",
                        dir.join(KEY_FILE_NAME).display()
                    ),
                ));
            }
        };
        let (layout, partitions) = if dir.join(INDEX_FILE_NAME).exists() {
            if options.require_authorization_filter {
                return Err(open_error(
                    dir,
                    format!(
                        "found an unpartitioned {INDEX_FILE_NAME}, which cannot be scoped by \
                         consent. Load the data partitioned by consent, or set \
                         hpds.requireAuthorizationFilter=false"
                    ),
                ));
            }
            let store = Store::open_shared(dir, protection, cache.clone(), 0)?;
            let partition = Partition {
                name: UNPARTITIONED_NAME.to_owned(),
                store,
            };
            (Layout::Unpartitioned, vec![partition])
        } else {
            let mut found = partition_dirs(dir)?;
            if found.is_empty() {
                return Err(open_error(
                    dir,
                    format!("no {INDEX_FILE_NAME} in it or in any immediate subdirectory"),
                ));
            }
            found.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
            for (_, path) in &found {
                check_partition_key(path, root_key.as_deref())?;
            }
            let partitions = found
                .into_par_iter()
                .enumerate()
                .map(|(slot, (name, path))| {
                    let store =
                        Store::open_shared(&path, protection.clone(), cache.clone(), slot as u32)?;
                    Ok(Partition { name, store })
                })
                .collect::<Result<Vec<_>>>()?;
            (Layout::Partitioned, partitions)
        };
        let dictionary = merge_dictionaries(&partitions)?;
        let all_patients = partitions
            .iter()
            .map(|p| p.store.index().patients())
            .union();
        Ok(PartitionSet {
            dir: dir.to_path_buf(),
            layout,
            require_consents: options.require_authorization_filter,
            partitions,
            dictionary,
            all_patients,
            cache,
        })
    }

    /// The data directory.
    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Whether the directory is split by consent.
    pub fn layout(&self) -> Layout {
        self.layout
    }

    /// True when every query must carry a consent value (`hpds.requireAuthorizationFilter`).
    pub fn requires_consents(&self) -> bool {
        self.require_consents
    }

    /// Every partition, in Java string order of their names.
    pub fn partitions(&self) -> &[Partition] {
        &self.partitions
    }

    /// The partition named `name`.
    pub fn partition(&self, name: &str) -> Option<&Partition> {
        self.partitions
            .binary_search_by(|p| java_string_cmp(&p.name, name))
            .ok()
            .map(|i| &self.partitions[i])
    }

    /// The merged dictionary, one entry per concept of any partition, in Java string order
    /// of the paths.
    pub fn dictionary(&self) -> &[SummaryColumnMeta] {
        &self.dictionary
    }

    /// The merged metadata of the concept at exactly `path`.
    pub fn summary(&self, path: &str) -> Option<&SummaryColumnMeta> {
        self.dictionary
            .binary_search_by(|c| java_string_cmp(&c.name, path))
            .ok()
            .map(|i| &self.dictionary[i])
    }

    /// Every merged concept whose path starts with `prefix` (`String.startsWith`), the
    /// concept at `prefix` included.
    pub fn dictionary_with_prefix(&self, prefix: &str) -> &[SummaryColumnMeta] {
        let start = self
            .dictionary
            .partition_point(|c| java_string_cmp(&c.name, prefix).is_lt());
        let len = self.dictionary[start..]
            .iter()
            .take_while(|c| c.name.starts_with(prefix))
            .count();
        &self.dictionary[start..start + len]
    }

    /// Every patient of every partition.
    pub fn all_patients(&self) -> &RoaringBitmap {
        &self.all_patients
    }

    /// True when some partition holds at least one concept, the readiness rule for health
    /// checks.
    pub fn is_healthy(&self) -> bool {
        !self.dictionary.is_empty()
    }

    /// The partitions `query` may read (`partitionsFor(query.consentValues())`):
    ///
    /// | Consent values | Consents required | Layout | Selected |
    /// |---|---|---|---|
    /// | none | yes | any | error |
    /// | none | no | any | every partition |
    /// | some | any | unpartitioned | the one partition |
    /// | some | any | partitioned | those named by a value; unknown values are skipped |
    ///
    /// # Errors
    /// [`QueryError::UserConsentsRequired`] in the first row.
    pub fn select(&self, query: &Query) -> Result<Selection<'_>> {
        self.select_consents(&query.consent_values())
    }

    /// [`PartitionSet::select`] for an explicit list of consent values.
    ///
    /// # Errors
    /// As for [`PartitionSet::select`].
    pub fn select_consents(&self, consents: &[&str]) -> Result<Selection<'_>> {
        let members: Vec<&Partition> = if consents.is_empty() {
            if self.require_consents {
                return Err(QueryError::UserConsentsRequired);
            }
            self.partitions.iter().collect()
        } else if self.layout == Layout::Unpartitioned {
            self.partitions.iter().collect()
        } else {
            self.partitions
                .iter()
                .filter(|p| consents.contains(&p.name.as_str()))
                .collect()
        };
        Ok(Selection { set: self, members })
    }

    /// Fails exactly when [`PartitionSet::select`] would: consents are required and `query`
    /// carries no consent value.
    ///
    /// # Errors
    /// [`QueryError::UserConsentsRequired`].
    pub fn check_consents(&self, query: &Query) -> Result<()> {
        if self.require_consents && query.consent_values().is_empty() {
            return Err(QueryError::UserConsentsRequired);
        }
        Ok(())
    }

    /// Number of cached blocks across all partitions. Eventually consistent.
    pub fn cached_block_count(&self) -> u64 {
        self.cache.entry_count()
    }

    /// Summed weight in bytes of the cached blocks across all partitions. Eventually
    /// consistent.
    pub fn cached_bytes(&self) -> u64 {
        self.cache.weighted_size()
    }

    /// Drops every cached block of every partition.
    pub fn clear_cache(&self) {
        self.cache.invalidate_all();
        self.cache.run_pending_tasks();
    }
}

impl ConceptLookup for PartitionSet {
    fn concept_type(&self, path: &str) -> Option<ConceptType> {
        self.summary(path).map(SummaryColumnMeta::concept_type)
    }
}

/// The partitions one query reads, in partition name order.
#[derive(Debug, Clone)]
pub struct Selection<'a> {
    set: &'a PartitionSet,
    members: Vec<&'a Partition>,
}

impl<'a> Selection<'a> {
    /// The partition set selected from.
    pub fn set(&self) -> &'a PartitionSet {
        self.set
    }

    /// The selected partitions, in partition name order.
    pub fn partitions(&self) -> &[&'a Partition] {
        &self.members
    }

    /// The selected partition names, in order.
    pub fn names(&self) -> Vec<&'a str> {
        self.members.iter().map(|p| p.name.as_str()).collect()
    }

    /// True when every partition of the set is selected.
    pub fn is_all(&self) -> bool {
        self.members.len() == self.set.partitions.len()
    }

    /// The union of the selected partitions' patients
    /// (`PartitionedPhenotypicObservationStore.getPatientIds`). Borrowed when one partition
    /// or every partition is selected.
    pub fn patients(&self) -> Cow<'a, RoaringBitmap> {
        match self.members.as_slice() {
            [] => Cow::Owned(RoaringBitmap::new()),
            [one] => Cow::Borrowed(one.store.index().patients()),
            _ if self.is_all() => Cow::Borrowed(&self.set.all_patients),
            many => Cow::Owned(many.iter().map(|p| p.store.index().patients()).union()),
        }
    }

    /// The block of `path` in each selected partition that holds the concept, in partition
    /// name order. Loads in parallel. Blocking.
    ///
    /// # Errors
    /// [`QueryError::Block`] when a block cannot be loaded.
    pub fn blocks(&self, path: &str) -> Result<Vec<Arc<Block>>> {
        let loaded = self
            .members
            .par_iter()
            .map(|p| p.store.block(path))
            .collect::<Result<Vec<_>>>()?;
        Ok(loaded.into_iter().flatten().collect())
    }
}

fn open_error(dir: &Path, error: impl std::fmt::Display) -> QueryError {
    QueryError::Open {
        dir: dir.to_path_buf(),
        detail: error.to_string(),
    }
}

/// The key file's text trimmed with Java `String.trim` rules, or `None` when there is no
/// such file.
fn read_key_text(path: &Path) -> Result<Option<String>> {
    match std::fs::read(path) {
        Ok(bytes) => Ok(Some(
            String::from_utf8_lossy(&bytes)
                .trim_matches(|c: char| c <= ' ')
                .to_owned(),
        )),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(open_error(path, e)),
    }
}

fn check_partition_key(partition_dir: &Path, root_key: Option<&str>) -> Result<()> {
    let Some(own) = read_key_text(&partition_dir.join(KEY_FILE_NAME))? else {
        return Ok(());
    };
    if root_key == Some(own.as_str()) {
        return Ok(());
    }
    Err(open_error(
        partition_dir,
        format!(
            "its {KEY_FILE_NAME} differs from the shared key file {}",
            partition_dir
                .parent()
                .unwrap_or(partition_dir)
                .join(KEY_FILE_NAME)
                .display()
        ),
    ))
}

/// Every immediate subdirectory of `dir` holding a store, as (name, path).
fn partition_dirs(dir: &Path) -> Result<Vec<(String, PathBuf)>> {
    let entries = std::fs::read_dir(dir).map_err(|e| open_error(dir, e))?;
    let mut found = Vec::new();
    for entry in entries {
        let path = entry.map_err(|e| open_error(dir, e))?.path();
        if !path.is_dir() || !path.join(INDEX_FILE_NAME).exists() {
            continue;
        }
        let name = path
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or_else(|| open_error(&path, "the partition name is not valid UTF-8"))?
            .to_owned();
        found.push((name, path));
    }
    Ok(found)
}

fn type_name(concept_type: ConceptType) -> &'static str {
    match concept_type {
        ConceptType::Categorical => "categorical",
        ConceptType::Numeric => "numeric",
    }
}

/// The merged dictionary of `partitions` (`createAllPartitionMetaStore`), in Java string
/// order, refusing a concept typed differently in two partitions.
fn merge_dictionaries(partitions: &[Partition]) -> Result<Vec<SummaryColumnMeta>> {
    let mut merged: Vec<(SummaryColumnMeta, &str)> = Vec::new();
    let mut positions: std::collections::HashMap<&str, usize> = std::collections::HashMap::new();
    for partition in partitions {
        for concept in partition.store.index().concepts() {
            match positions.get(concept.path.as_str()) {
                Some(&i) => {
                    let (summary, first_partition) = &mut merged[i];
                    if summary.concept_type() != concept.concept_type {
                        return Err(QueryError::TypeConflict {
                            concept_path: concept.path.clone(),
                            first_partition: (*first_partition).to_owned(),
                            first_type: type_name(summary.concept_type()),
                            second_partition: partition.name.clone(),
                            second_type: type_name(concept.concept_type),
                        });
                    }
                    summary.absorb(concept);
                }
                None => {
                    positions.insert(&concept.path, merged.len());
                    merged.push((
                        SummaryColumnMeta::from_concept(concept),
                        partition.name.as_str(),
                    ));
                }
            }
        }
    }
    let mut dictionary: Vec<SummaryColumnMeta> = merged
        .into_iter()
        .map(|(mut summary, _)| {
            summary.finish();
            summary
        })
        .collect();
    dictionary.sort_by(|a, b| java_string_cmp(&a.name, &b.name));
    Ok(dictionary)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_min_and_max_follow_math_rules() {
        assert!(java_min(f64::NAN, 1.0).is_nan());
        assert!(java_max(1.0, f64::NAN).is_nan());
        assert!(java_min(0.0, -0.0).is_sign_negative());
        assert!(java_max(-0.0, 0.0).is_sign_positive());
        assert_eq!(java_min(2.0, 1.0), 1.0);
        assert_eq!(java_max(2.0, 1.0), 2.0);
        assert_eq!(merge_option(None, Some(3), i64::min), Some(3));
        assert_eq!(merge_option(Some(4), None, i64::min), Some(4));
        assert_eq!(merge_option::<i64>(None, None, i64::min), None);
    }

    #[test]
    fn summary_json_matches_the_java_field_order() {
        let summary = SummaryColumnMeta {
            name: "\\a\\".into(),
            width_in_bytes: 8,
            categorical: false,
            category_values: Vec::new(),
            min: Some(31.0),
            max: Some(f64::INFINITY),
            patient_count: 1126,
            has_timestamp: false,
            timestamp_min: None,
            timestamp_max: Some(5),
        };
        assert_eq!(
            summary.to_json(),
            r#"{"name":"\\a\\","widthInBytes":8,"categorical":false,"categoryValues":[],"min":31.0,"max":"Infinity","patientCount":1126,"timestampMin":null,"timestampMax":5}"#
        );
    }
}
