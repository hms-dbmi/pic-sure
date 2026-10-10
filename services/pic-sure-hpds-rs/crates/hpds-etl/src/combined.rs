//! The combined store of a consent-partitioned store: one more store, in
//! `<dir>/.combined/`, holding every partition's rows, so a query that selects every
//! partition reads one block per concept instead of one per partition.
//!
//! ```text
//! <dir>/
//!   encryption_key
//!   .generation               partition ids, then "<combined id> .combined"
//!   <partition>/pheno.index
//!   <partition>/pheno.blocks
//!   .combined/pheno.index
//!   .combined/pheno.blocks
//!   .combined/etl-report.json
//! ```
//!
//! The directory name starts with `.`, so the partition-directory rule
//! ([`crate::partitions::partition_dirs`]) never lists it, and no partition can be named
//! after it ([`crate::partitions::check_partition_name`] refuses every name starting with
//! `.`). The query service applies the same rule, so the combined store is never a consent
//! group. Java never reads a Rust store, so its discovery rule (every subdirectory is a
//! partition) does not apply here.
//!
//! Each concept's rows are those of every partition holding it, in the order the query
//! engine merges partitions when a query selects all of them: by patient, then partition
//! name in Java `String.compareTo` order, then each partition's own order within the
//! patient. That is Java's stable `sortedByKey` over the concatenation of the partitions'
//! rows in partition name order, so the combined block's `by_key` order is exactly the
//! engine's k-way merge, and exports and cross counts from it are byte for byte the
//! all-partition answers. The concept's metadata is merged as `column-meta-csv --merged`
//! merges it ([`crate::store_maintenance::merge_column_meta`]), except that the observation
//! and patient counts are those of the combined rows.
//!
//! The combined store has its own store id, under the partitions' shared key. The
//! `.generation` marker lists it on its own line, tagged with [`COMBINED_GENERATION_TAG`],
//! after the partitions' ids (see [`generation_line`]).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlocksWriter, CategoricalRow, ConceptMeta, ConceptType,
    IndexHeader, NumericObservation, PhenoIndex, Protection, StoreId,
    encode_interned_categorical_payload, encode_numeric_payload, java_string_cmp,
};
use rayon::prelude::*;
use roaring::{MultiOps, RoaringBitmap};
use serde::Serialize;

use crate::cleanup::TemporaryDir;
use crate::error::{EtlError, Result};
use crate::load::{DEFAULT_KEY_NAME, REPORT_FILE_NAME};
use crate::partitions::{
    GENERATION_FILE_NAME, Generation, Partition, PartitionRecord, generation_text, partition_dirs,
    replace_combined_dir,
};
use crate::private_fs;
use crate::store::{OpenStore, read_index, replace_file};
use crate::store_maintenance::merge_column_meta;

/// Name of the combined store's directory inside a partitioned store.
pub const COMBINED_DIR_NAME: &str = ".combined";

/// The second token of the combined store's line in `.generation`, after its store id.
pub const COMBINED_GENERATION_TAG: &str = ".combined";

/// Concepts merged in parallel before their blocks are appended, which bounds the decoded
/// rows held at once.
const MERGE_CHUNK: usize = 64;

/// What building a combined store did, also written to its `etl-report.json`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct CombinedReport {
    /// The combined store's id, as 32 lowercase hex digits.
    pub store_id: String,
    /// The partitions merged, in merge order (Java `String.compareTo` order of the names).
    pub partitions: Vec<String>,
    /// Concepts in the combined store: every concept of any partition.
    pub concepts: u64,
    /// Observations summed over every concept.
    pub observations: u64,
    /// Patients in the combined store's patient set: the union of the partitions' sets.
    pub patients: u64,
    /// True when no patient id is in two partitions' patient sets.
    pub partitions_disjoint: bool,
    /// Size of `pheno.blocks` in bytes.
    pub blocks_bytes: u64,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// The `.generation` line that lists the combined store `store_id`: the id, a space and
/// [`COMBINED_GENERATION_TAG`].
///
/// A query service before the combined store reads only the first token of each line and
/// requires every listed id to belong to a partition it opened. It never opens `.combined`
/// (a name starting with `.`), so it refuses such a store as a partially published load
/// rather than serving it. See the crate README.
pub fn generation_line(store_id: &str) -> String {
    format!("{store_id} {COMBINED_GENERATION_TAG}\n")
}

/// Builds the combined store of `partitions` into the existing, empty directory `dir`:
/// `pheno.blocks`, `pheno.index` and `etl-report.json`, each mode `0600` and synced. Every
/// partition is opened with `protection`, and the combined store is sealed with it under a
/// new store id. Blocks are read and merged on `pool`.
///
/// Returns `None`, writing nothing, in two cases. With exactly one partition, the combined
/// store would repeat it block for block, and the query engine ignores one there, so none is
/// built. When a concept is numeric in one partition and categorical in another, a warning
/// is logged: no block can hold both, and the query engine refuses to open such a store
/// anyway. The partitions themselves load as `CSVLoader` loads them.
///
/// # Errors
/// [`EtlError::Usage`] when `partitions` is empty; the errors of opening the partitions and
/// reading their blocks; [`EtlError::Format`] when a merged block does not fit the bound
/// its merged metadata declares; I/O and encoding errors.
pub fn build_combined(
    partitions: &[Partition],
    dir: &Path,
    protection: &Protection,
    pool: &rayon::ThreadPool,
) -> Result<Option<CombinedReport>> {
    let started = Instant::now();
    if partitions.is_empty() {
        return Err(EtlError::Usage(
            "a combined store needs at least one partition".into(),
        ));
    }
    if partitions.len() == 1 {
        tracing::info!(
            "no combined store is written for a single partition: it would repeat that partition, and the query service reads the partition directly"
        );
        return Ok(None);
    }
    let mut ordered: Vec<&Partition> = partitions.iter().collect();
    ordered.sort_by(|a, b| java_string_cmp(&a.name, &b.name));
    let stores = ordered
        .iter()
        .map(|p| OpenStore::open(&p.dir, protection.clone()))
        .collect::<Result<Vec<_>>>()?;
    let named: Vec<(String, &PhenoIndex)> = ordered
        .iter()
        .zip(&stores)
        .map(|(p, s)| (p.name.clone(), &s.index))
        .collect();
    let mut metas = match merge_column_meta(&named) {
        Ok(metas) => metas,
        Err(conflict) => {
            tracing::warn!(
                %conflict,
                "no combined store is written: one block cannot hold a concept of two types, and the query service refuses this store until the input is fixed"
            );
            return Ok(None);
        }
    };
    let holders: Vec<Vec<(usize, usize)>> = metas
        .iter()
        .map(|meta| {
            stores
                .iter()
                .enumerate()
                .filter_map(|(slot, store)| store.index.position(&meta.path).map(|i| (slot, i)))
                .collect()
        })
        .collect();

    let patient_sets: Vec<&RoaringBitmap> = stores.iter().map(|s| s.index.patients()).collect();
    let patients = patient_sets.iter().copied().union();
    let summed: u64 = patient_sets.iter().map(|p| p.len()).sum();

    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(protection.clone(), store_id, true);
    let blocks_path = dir.join(BLOCKS_FILE_NAME);
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer = BlocksWriter::new(BufWriter::with_capacity(4 << 20, file), store_id)?;
    let mut observations = 0u64;
    for (meta_chunk, holder_chunk) in metas
        .chunks_mut(MERGE_CHUNK)
        .zip(holders.chunks(MERGE_CHUNK))
    {
        let merged = pool.install(|| {
            meta_chunk
                .par_iter()
                .zip(holder_chunk)
                .map(|(meta, holders)| merge_concept(meta, holders, &stores, &codec))
                .collect::<Result<Vec<_>>>()
        })?;
        for (meta, concept) in meta_chunk.iter_mut().zip(merged) {
            meta.observation_count = concept.observations;
            meta.patient_count = concept.patients;
            meta.block = writer.append_stored(&concept.stored)?;
            observations += u64::from(concept.observations);
        }
    }
    let blocks_bytes = writer.len();
    writer
        .finish()?
        .into_inner()
        .map_err(|e| EtlError::io("writing", &blocks_path)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("writing", &blocks_path))?;

    let header = IndexHeader {
        format_version: hpds_format::FORMAT_VERSION,
        encrypted: protection.is_encrypted(),
        compressed: true,
        key_name: if protection.is_encrypted() {
            DEFAULT_KEY_NAME.into()
        } else {
            String::new()
        },
        created_at_millis: SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0),
        etl_version: crate::banner(),
        store_id,
    };
    let concepts = metas.len() as u64;
    let patient_count = patients.len();
    let index = PhenoIndex::new(header, patients, metas)?;
    let index_path = dir.join(hpds_format::INDEX_FILE_NAME);
    private_fs::write_file(&index_path, &index.encode(protection)?)
        .map_err(EtlError::io("writing", &index_path))?;

    let report = CombinedReport {
        store_id: store_id.to_string(),
        partitions: ordered.iter().map(|p| p.name.clone()).collect(),
        concepts,
        observations,
        patients: patient_count,
        partitions_disjoint: summed == patient_count,
        blocks_bytes,
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    let report_path = dir.join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    private_fs::write_file(&report_path, &json).map_err(EtlError::io("writing", &report_path))?;
    private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
    tracing::info!(
        concepts = report.concepts,
        observations = report.observations,
        patients = report.patients,
        disjoint = report.partitions_disjoint,
        blocks_bytes = report.blocks_bytes,
        seconds = report.elapsed_seconds,
        "combined store built"
    );
    Ok(Some(report))
}

/// One concept of the combined store: its sealed block and the counts of its rows.
struct MergedConcept {
    stored: Vec<u8>,
    observations: u32,
    patients: u32,
}

/// Reads the concept's block from every partition in `holders` (slot and index position,
/// in partition name order), merges their rows into the engine's all-partition order and
/// seals the result for `meta.path`.
///
/// # Errors
/// Block read and encoding errors; [`EtlError::Usage`] when a block's kind disagrees with
/// the merged type; [`EtlError::Format`] when the payload exceeds the bound the merged
/// metadata gives it.
fn merge_concept(
    meta: &ConceptMeta,
    holders: &[(usize, usize)],
    stores: &[OpenStore],
    codec: &BlockCodec,
) -> Result<MergedConcept> {
    let blocks = holders
        .iter()
        .map(|&(slot, position)| {
            let store = &stores[slot];
            let concept = &store.index.concepts()[position];
            Ok(store.reader.read_block(&store.codec, concept)?)
        })
        .collect::<Result<Vec<Block>>>()?;
    let (payload, observations, patients) = match meta.concept_type {
        ConceptType::Numeric => merge_numeric(&meta.path, &blocks)?,
        ConceptType::Categorical => merge_categorical(&meta.path, &blocks)?,
    };
    let counted = ConceptMeta {
        observation_count: observations,
        ..meta.clone()
    };
    let bound = counted.max_payload_len();
    if payload.len() as u64 > bound {
        return Err(EtlError::Format(hpds_format::FormatError::InvalidInput(
            format!(
                "the combined block of {:?} decodes to {} bytes, above the {bound} its merged metadata allows",
                meta.path,
                payload.len()
            ),
        )));
    }
    Ok(MergedConcept {
        stored: codec.seal(&meta.path, &payload)?,
        observations,
        patients,
    })
}

fn kind_mismatch(path: &str, expected: &str) -> EtlError {
    EtlError::Usage(format!(
        "a partition's block of {path:?} is not {expected}, as its index says"
    ))
}

fn row_count(path: &str, n: usize) -> Result<u32> {
    u32::try_from(n).map_err(|_| {
        EtlError::Usage(format!(
            "{path:?} has {n} rows over all partitions, more than a block holds"
        ))
    })
}

/// The numeric rows of `blocks` in the engine's all-partition order, encoded, with the row
/// and distinct patient counts. Each block contributes its rows in `by_key` order; the
/// stable sort on patient then keeps partition order, and each partition's order, within a
/// patient.
fn merge_numeric(path: &str, blocks: &[Block]) -> Result<(Vec<u8>, u32, u32)> {
    let mut rows = Vec::with_capacity(blocks.iter().map(Block::len).sum());
    for block in blocks {
        let Block::Numeric(block) = block else {
            return Err(kind_mismatch(path, "numeric"));
        };
        let (values, patients) = (block.values(), block.patients());
        rows.extend(block.by_key().iter().map(|&i| {
            let i = i as usize;
            NumericObservation {
                patient: patients[i],
                value: values[i],
                timestamp: block.timestamp_at_value_pos(i),
            }
        }));
    }
    rows.sort_by_key(|row| row.patient);
    let observations = row_count(path, rows.len())?;
    let patients = distinct_patients(rows.iter().map(|r| r.patient));
    Ok((encode_numeric_payload(&rows)?, observations, patients))
}

/// The categorical rows of `blocks` in the engine's all-partition order, encoded, with the
/// row and distinct patient counts. Values are interned across the partitions.
fn merge_categorical(path: &str, blocks: &[Block]) -> Result<(Vec<u8>, u32, u32)> {
    let mut ids: HashMap<&str, u32> = HashMap::new();
    let mut values: Vec<&str> = Vec::new();
    let mut rows = Vec::with_capacity(blocks.iter().map(Block::len).sum());
    for block in blocks {
        let Block::Categorical(block) = block else {
            return Err(kind_mismatch(path, "categorical"));
        };
        let local: Vec<u32> = block
            .dictionary()
            .map(|value| {
                *ids.entry(value).or_insert_with(|| {
                    values.push(value);
                    (values.len() - 1) as u32
                })
            })
            .collect();
        let indices = block.value_indices();
        rows.extend(
            block
                .patients()
                .iter()
                .enumerate()
                .map(|(cursor, &patient)| CategoricalRow {
                    patient,
                    value_id: local[indices[cursor] as usize],
                    timestamp: block.timestamp_at_key_pos(cursor),
                }),
        );
    }
    rows.sort_by_key(|row| row.patient);
    let observations = row_count(path, rows.len())?;
    let patients = distinct_patients(rows.iter().map(|r| r.patient));
    Ok((
        encode_interned_categorical_payload(&values, &rows)?,
        observations,
        patients,
    ))
}

/// The number of distinct values in a sequence ordered by patient.
fn distinct_patients(patients: impl Iterator<Item = u32>) -> u32 {
    let mut previous = None;
    let mut count = 0u32;
    for patient in patients {
        if previous != Some(patient) {
            count += 1;
            previous = Some(patient);
        }
    }
    count
}

/// The combined store directory of the partitioned store `store`, when it holds one
/// (`<store>/.combined/pheno.index` exists).
pub fn existing_combined_dir(store: &Path) -> Option<PathBuf> {
    let dir = store.join(COMBINED_DIR_NAME);
    dir.join(hpds_format::INDEX_FILE_NAME)
        .is_file()
        .then_some(dir)
}

/// Builds the combined store of the partitioned store `store` in place (`hpds-etl
/// combine`), replacing any combined store it has, and rewrites its `.generation` marker to
/// list the partitions' ids and the new combined store's.
///
/// The partitions are not changed. When `store` already has a `.generation` marker, its
/// partition ids must be exactly the partitions' current ids, so a store whose publish is
/// under way or was interrupted is refused rather than certified. The new store is built in
/// a temporary directory inside `store` (named with a leading `.`, so neither the ETL nor
/// the query service takes it for a partition), renamed into place, and the marker written
/// last. Just before that, with the interrupt handler held off, the partitions' ids are
/// read again, and the publish is abandoned when they changed during the build, so a load
/// that published meanwhile keeps its newer marker.
///
/// # Errors
/// [`EtlError::Usage`] when `store` holds no partitions or only one (a combined store would
/// repeat it, and the query service ignores one there), its marker disagrees with them,
/// two partitions type a concept differently, or the partitions changed during the build;
/// the errors of [`build_combined`]; I/O errors.
pub fn combine_in_place(
    store: &Path,
    protection: &Protection,
    pool: &rayon::ThreadPool,
) -> Result<CombinedReport> {
    let (partitions, records) = checked_partitions(store, protection)?;
    if partitions.len() == 1 {
        return Err(EtlError::Usage(format!(
            "{} holds one partition; a combined store would only repeat it, and the query service ignores one there",
            store.display()
        )));
    }
    let staging = TemporaryDir::create(store, "combined.staging")?;
    let built = staging.path().join(COMBINED_DIR_NAME);
    private_fs::create_dir(&built).map_err(EtlError::io("creating", &built))?;
    let report = build_combined(&partitions, &built, protection, pool)?.ok_or_else(|| {
        EtlError::Usage(format!(
            "{} has a concept typed differently in two partitions; no combined store can hold it",
            store.display()
        ))
    })?;
    let names: Vec<String> = partitions.into_iter().map(|p| p.name).collect();
    let generation = generation_text(&names, &records, Some(&report.store_id))?;
    let registered = crate::cleanup::lock();
    let published = refuse_if_changed(store, protection, &records).and_then(|()| {
        replace_combined_dir(store, staging.path(), Some(built))?;
        let marker = store.join(GENERATION_FILE_NAME);
        replace_file(&marker, |file| {
            file.write_all(generation.as_bytes())
                .map_err(EtlError::io("writing", &marker))
        })
    });
    drop(registered);
    published?;
    Ok(report)
}

/// Refuses to publish a combined store built from the partitions whose store ids were
/// `built_from`, when `store`'s partitions or its marker no longer agree with them: a load
/// published new partitions during the build, and its marker must not be overwritten.
///
/// # Errors
/// [`EtlError::Usage`] when the partitions' ids changed, or the errors of reading them
/// again (see [`checked_partitions`]).
fn refuse_if_changed(
    store: &Path,
    protection: &Protection,
    built_from: &BTreeMap<String, PartitionRecord>,
) -> Result<()> {
    let (_, now) = checked_partitions(store, protection)?;
    if &now == built_from {
        return Ok(());
    }
    Err(EtlError::Usage(format!(
        "the partitions of {} changed while the combined store was built, so a newer load was published; nothing was replaced. Rerun hpds-etl combine",
        store.display()
    )))
}

/// Removes the combined store of the partitioned store `store` (`hpds-etl combine
/// --remove`) and drops its line from the `.generation` marker, so a query service without
/// combined store support opens `store` again. The marker is rewritten first, then the
/// directory moved into a temporary directory and removed. Returns whether there was a
/// combined store or a marker line to remove.
///
/// # Errors
/// [`EtlError::Usage`] when `store` holds no partitions or its marker disagrees with them;
/// I/O errors.
pub fn remove_combined(store: &Path, protection: &Protection) -> Result<bool> {
    let (partitions, records) = checked_partitions(store, protection)?;
    let listed = Generation::read(store)?.is_some_and(|g| g.combined.is_some());
    let present = store.join(COMBINED_DIR_NAME).symlink_metadata().is_ok();
    if listed {
        let names: Vec<String> = partitions.into_iter().map(|p| p.name).collect();
        let generation = generation_text(&names, &records, None)?;
        let marker = store.join(GENERATION_FILE_NAME);
        replace_file(&marker, |file| {
            file.write_all(generation.as_bytes())
                .map_err(EtlError::io("writing", &marker))
        })?;
    }
    if present {
        let trash = TemporaryDir::create(store, "combined.removed")?;
        let _registered = crate::cleanup::lock();
        replace_combined_dir(store, trash.path(), None)?;
    }
    Ok(listed || present)
}

/// The partitions of `store` and their records by name, after checking that a
/// `.generation` marker, when there is one, lists exactly their store ids. A partition's
/// record carries [`crate::partitions::PATIENTS_INDEXED_TAG`] over from the marker line of
/// its store id; this command never reads blocks to establish it, so a store without a
/// marker gets no such record.
///
/// # Errors
/// [`EtlError::Usage`] when `store` holds no partitions or the marker disagrees; the errors
/// of reading the partitions' indexes.
fn checked_partitions(
    store: &Path,
    protection: &Protection,
) -> Result<(Vec<Partition>, BTreeMap<String, PartitionRecord>)> {
    let partitions = partition_dirs(store)?;
    if partitions.is_empty() {
        return Err(EtlError::Usage(format!(
            "{} holds no partitions; a combined store belongs to a partitioned store",
            store.display()
        )));
    }
    let store_ids = partitions
        .iter()
        .map(|p| {
            Ok((
                p.name.clone(),
                read_index(&p.dir, protection)?.store_id().to_string(),
            ))
        })
        .collect::<Result<BTreeMap<String, String>>>()?;
    let generation = Generation::read(store)?;
    if let Some(generation) = &generation {
        let listed: BTreeSet<&str> = generation.partitions.iter().map(String::as_str).collect();
        let found: BTreeSet<&str> = store_ids.values().map(String::as_str).collect();
        if listed != found {
            return Err(EtlError::Usage(format!(
                "{} does not list the partitions' store ids, so a publish is under way or was interrupted; finish or rerun the load first",
                store.join(GENERATION_FILE_NAME).display()
            )));
        }
    }
    let records = store_ids
        .into_iter()
        .map(|(name, store_id)| {
            let patients_indexed = generation
                .as_ref()
                .is_some_and(|g| g.patients_indexed.contains(&store_id));
            let record = PartitionRecord {
                store_id,
                patients_indexed,
            };
            (name, record)
        })
        .collect();
    Ok((partitions, records))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distinct_patients_counts_runs() {
        assert_eq!(distinct_patients([].into_iter()), 0);
        assert_eq!(distinct_patients([1, 1, 2, 5, 5, 5].into_iter()), 3);
    }

    fn harmonization_inputs() -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../pic-sure-hpds/service/src/test/resources/phenotypic")
    }

    fn load_plain(out: &Path) {
        let mut options =
            crate::load::PartitionedLoadOptions::new(harmonization_inputs(), out.to_path_buf());
        options.no_config = true;
        options.key = crate::load::KeySource::Plain;
        options.threads = Some(2);
        options.combined = false;
        crate::load::load_partitioned(&options).unwrap();
    }

    #[test]
    fn a_publish_is_refused_when_the_partitions_changed_during_the_build() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("out");
        load_plain(&out);
        let (_, built_from) = checked_partitions(&out, &Protection::Plain).unwrap();
        refuse_if_changed(&out, &Protection::Plain, &built_from).unwrap();
        load_plain(&out);
        let error = refuse_if_changed(&out, &Protection::Plain, &built_from)
            .unwrap_err()
            .to_string();
        assert!(
            error.contains("changed while the combined store was built"),
            "{error}"
        );
    }

    #[test]
    fn a_single_partition_gets_no_combined_store() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("out");
        load_plain(&out);
        let partitions = partition_dirs(&out).unwrap();
        let target = dir.path().join("combined");
        std::fs::create_dir(&target).unwrap();
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(1)
            .build()
            .unwrap();
        let built = build_combined(&partitions[..1], &target, &Protection::Plain, &pool).unwrap();
        assert!(built.is_none());
        assert_eq!(std::fs::read_dir(&target).unwrap().count(), 0);
    }

    #[test]
    fn the_generation_line_is_tagged() {
        assert_eq!(
            generation_line("00112233445566778899aabbccddeeff"),
            "00112233445566778899aabbccddeeff .combined\n"
        );
    }
}
