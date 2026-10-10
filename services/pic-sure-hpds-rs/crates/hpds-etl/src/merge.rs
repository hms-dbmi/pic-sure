//! Writing one store from one source store per consent partition: the last step of a
//! partitioned `load` and `migrate`, and all of `convert`.
//!
//! Each concept becomes one block holding the rows of every partition that holds it, in
//! the order Java's stable `sortedByKey` gives the concatenation of the partitions' rows in
//! partition name order (by patient, then partition name in Java `String.compareTo`
//! order, then each partition's own order within the patient). That is the order the v2
//! and v3 engines merged partitions in, so exports and per-value cross counts read from the
//! block are byte for byte the partitions' answers.
//!
//! The index records each partition's patient set as its source store recorded it, and the
//! overlap set `O`: every patient in two or more partition sets, and every patient with a
//! row in a partition whose set lacks it. A partition with no such patient carries the
//! `patients-indexed` flag. A block holding a row of a patient in `O` is tagged with every
//! row's partition; any other block is routed by patient ([`hpds_format::Membership`]).
//! When `O` holds more than [`OVERLAP_WARNING_SHARE`] of the rows a warning is logged,
//! since narrow selections read those rows by tag.
//!
//! A concept typed numeric in one partition and categorical in another fails the merge,
//! naming the concept and both partitions.

use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::{Instant, SystemTime, UNIX_EPOCH};

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlockPartitioning, BlocksReader, BlocksWriter,
    CategoricalRow, ConceptMeta, ConceptType, INDEX_FILE_NAME, IndexHeader, IndexPartition,
    LegacyIndex, MAX_PARTITIONS, NumericObservation, PartitionHolder, PartitionTable, PhenoIndex,
    Protection, StoreId, encode_interned_categorical_block, encode_numeric_block, java_string_cmp,
};
use rayon::prelude::*;
use roaring::RoaringBitmap;
use serde::Serialize;

use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};
use crate::load::{COLUMN_META_CSV_NAME, DEFAULT_KEY_NAME};
use crate::partitions::check_partition_name;
use crate::private_fs;
use crate::store::OpenStore;
use crate::store_maintenance::merge_column_meta;

/// The share of rows above which a merge warns that the overlap set is large.
pub const OVERLAP_WARNING_SHARE: f64 = 0.05;

/// Concepts merged in parallel before their blocks are appended, which bounds the decoded
/// rows held at once.
const MERGE_CHUNK: usize = 64;

/// One store a merge reads: an unpartitioned store of this format, as `load` and `migrate`
/// stage each partition, or a store of format 1, as Rust HPDS v1 to v3 wrote one per
/// partition directory.
pub enum SourceStore {
    /// An unpartitioned store of this format.
    Current(OpenStore),
    /// A format 1 store.
    Legacy {
        /// Its index.
        index: LegacyIndex,
        /// Its blocks file.
        reader: BlocksReader,
        /// The codec that opens its blocks.
        codec: BlockCodec,
    },
}

impl SourceStore {
    /// Opens the unpartitioned store of this format in `dir`.
    ///
    /// # Errors
    /// The errors of [`OpenStore::open`], and [`EtlError::Usage`] for a partitioned store.
    pub fn open(dir: &Path, protection: Protection) -> Result<Self> {
        let store = OpenStore::open(dir, protection)?;
        if store.index.is_partitioned() {
            return Err(EtlError::Usage(format!(
                "{} is partitioned; a merge reads one unpartitioned store per partition",
                dir.display()
            )));
        }
        Ok(SourceStore::Current(store))
    }

    /// Opens the format 1 store in `dir`.
    ///
    /// # Errors
    /// [`EtlError::Format`] when its index or blocks file is missing, corrupt, of another
    /// format, from another store, or protected differently from `protection`.
    pub fn open_legacy(dir: &Path, protection: Protection) -> Result<Self> {
        let index = LegacyIndex::read_file(dir.join(INDEX_FILE_NAME), &protection)?;
        let reader = BlocksReader::open(dir.join(BLOCKS_FILE_NAME), index.store_id())?;
        let codec = index.block_codec(protection)?;
        Ok(SourceStore::Legacy {
            index,
            reader,
            codec,
        })
    }

    /// The store's concepts, in Java string order of their paths.
    pub fn concepts(&self) -> &[ConceptMeta] {
        match self {
            SourceStore::Current(store) => store.index.concepts(),
            SourceStore::Legacy { index, .. } => index.concepts(),
        }
    }

    /// The store's patient set.
    pub fn patients(&self) -> &RoaringBitmap {
        match self {
            SourceStore::Current(store) => store.index.patients(),
            SourceStore::Legacy { index, .. } => index.patients(),
        }
    }

    /// Reads and verifies the block of `concept`, one of [`SourceStore::concepts`].
    ///
    /// # Errors
    /// [`EtlError::Format`] when it cannot be read, authenticated, decoded or checked.
    pub fn read_block(&self, concept: &ConceptMeta) -> Result<Block> {
        match self {
            SourceStore::Current(store) => store.read_block(concept),
            SourceStore::Legacy { reader, codec, .. } => {
                Ok(reader.read_legacy_block(codec, concept)?)
            }
        }
    }

    /// Every patient with a row in the store, read from every block.
    ///
    /// # Errors
    /// The errors of [`SourceStore::read_block`].
    pub fn observed_patients(&self) -> Result<RoaringBitmap> {
        self.concepts()
            .par_iter()
            .map(|concept| Ok(self.read_block(concept)?.all_patients().clone()))
            .try_reduce(RoaringBitmap::new, |mut a, b| {
                a |= b;
                Ok(a)
            })
    }
}

/// One partition of a merge: its name, its source store, and, when the caller already knows
/// it, the set of patients with a row in it. Without that set the merge reads it from every
/// block before merging.
pub struct MergeSource {
    /// The partition name, the consent value that selects it.
    pub name: String,
    /// The partition's rows and patient set.
    pub store: SourceStore,
    /// Every patient with a row in the partition, when known.
    pub observed: Option<RoaringBitmap>,
}

/// One partition of a merged store, as its report describes it.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct PartitionSummary {
    /// The partition name.
    pub name: String,
    /// Patients in the partition's patient set.
    pub patients: u64,
    /// Patients with a row in the partition that its patient set lacks.
    pub observed_patients_missing_from_set: u64,
    /// True when that count is 0, the `patients-indexed` flag of the index.
    pub patients_indexed: bool,
}

/// What a merge wrote, part of the store's `etl-report.json`.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct MergeReport {
    /// The store id, as 32 lowercase hex digits.
    pub store_id: String,
    /// The store id, for publishing; [`MergeReport::store_id`] is its text.
    #[serde(skip)]
    pub id: StoreId,
    /// True for a partitioned store.
    pub partitioned: bool,
    /// The partitions in Java string order of their names; empty when unpartitioned.
    pub partitions: Vec<PartitionSummary>,
    /// Concepts in the store.
    pub concepts: u64,
    /// Rows over every concept.
    pub observations: u64,
    /// Patients in the store's patient set.
    pub patients: u64,
    /// Patients in the overlap set `O`.
    pub overlap_patients: u64,
    /// Rows of patients in `O`, which narrow selections read by tag.
    pub overlap_observations: u64,
    /// `overlap_observations` as a share of `observations`, 0 for a store without rows.
    pub overlap_share: f64,
    /// Concepts whose block is tagged.
    pub tagged_concepts: u64,
    /// Size of `pheno.blocks` in bytes.
    pub blocks_bytes: u64,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// One merged concept: its sealed block and what the index records of it.
struct MergedConcept {
    stored: Vec<u8>,
    observations: u32,
    patients: u32,
    holders: Vec<PartitionHolder>,
    tagged: bool,
    overlap_rows: u64,
}

/// Writes `pheno.blocks`, `pheno.index` and `columnMeta.csv` into the existing, empty
/// directory `dir`, each mode `0600` and synced, from `sources` under `protection` and a
/// new store id. With `partitioned` the store has one partition per source; without it
/// there must be exactly one source and the store is unpartitioned. Blocks are read,
/// merged and sealed on `pool`.
///
/// # Errors
/// [`EtlError::Usage`] for no source, several sources of an unpartitioned store, more
/// than [`MAX_PARTITIONS`] partitions, a partition name that cannot be one, two sources of
/// one name, or a concept typed differently in two partitions; the errors of reading the
/// sources' blocks; encoding and I/O errors.
pub fn write_store(
    mut sources: Vec<MergeSource>,
    partitioned: bool,
    dir: &Path,
    protection: &Protection,
    pool: &rayon::ThreadPool,
) -> Result<MergeReport> {
    let started = Instant::now();
    check_sources(&mut sources, partitioned)?;
    let observed = pool.install(|| {
        sources
            .iter_mut()
            .map(|source| match source.observed.take() {
                Some(observed) => Ok(observed),
                None => source.store.observed_patients(),
            })
            .collect::<Result<Vec<_>>>()
    })?;
    let (table, summaries) = if partitioned {
        partition_table(&sources, &observed)
    } else {
        (PartitionTable::default(), Vec::new())
    };
    let patients = if partitioned {
        table.patients()
    } else {
        sources[0].store.patients().clone()
    };
    let named: Vec<(String, &[ConceptMeta])> = sources
        .iter()
        .map(|s| (s.name.clone(), s.store.concepts()))
        .collect();
    let mut metas = merge_column_meta(&named)?;
    drop(named);
    let holders: Vec<Vec<(usize, usize)>> = metas
        .iter()
        .map(|meta| {
            sources
                .iter()
                .enumerate()
                .filter_map(|(slot, source)| {
                    source
                        .store
                        .concepts()
                        .binary_search_by(|c| java_string_cmp(&c.path, &meta.path))
                        .ok()
                        .map(|position| (slot, position))
                })
                .collect()
        })
        .collect();

    let store_id = StoreId::random()?;
    let codec = BlockCodec::new(protection.clone(), store_id, true);
    let blocks_path = dir.join(BLOCKS_FILE_NAME);
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer = BlocksWriter::new(BufWriter::with_capacity(4 << 20, file), store_id)?;
    let context = MergeContext {
        sources: &sources,
        overlap: &table.overlap,
        partitioned,
        partition_count: table.partitions.len(),
        codec: &codec,
    };
    let mut observations = 0u64;
    let mut overlap_observations = 0u64;
    let mut tagged_concepts = 0u64;
    for (meta_chunk, holder_chunk) in metas
        .chunks_mut(MERGE_CHUNK)
        .zip(holders.chunks(MERGE_CHUNK))
    {
        let merged = pool.install(|| {
            meta_chunk
                .par_iter()
                .zip(holder_chunk)
                .map(|(meta, holders)| context.merge_concept(meta, holders))
                .collect::<Result<Vec<_>>>()
        })?;
        for (meta, concept) in meta_chunk.iter_mut().zip(merged) {
            meta.observation_count = concept.observations;
            meta.patient_count = concept.patients;
            meta.holders = concept.holders;
            meta.tagged = concept.tagged;
            meta.block = writer.append_stored(&concept.stored)?;
            observations += u64::from(concept.observations);
            overlap_observations += concept.overlap_rows;
            tagged_concepts += u64::from(concept.tagged);
        }
    }
    let blocks_bytes = writer.len();
    writer
        .finish()?
        .into_inner()
        .map_err(|e| EtlError::io("writing", &blocks_path)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("writing", &blocks_path))?;

    let report = MergeReport {
        store_id: store_id.to_string(),
        id: store_id,
        partitioned,
        partitions: summaries,
        concepts: metas.len() as u64,
        observations,
        patients: patients.len(),
        overlap_patients: table.overlap.len(),
        overlap_observations,
        overlap_share: if observations == 0 {
            0.0
        } else {
            overlap_observations as f64 / observations as f64
        },
        tagged_concepts,
        blocks_bytes,
        elapsed_seconds: 0.0,
    };
    let index =
        PhenoIndex::with_partitions(store_header(protection, store_id), patients, table, metas)?;
    write_index_and_column_meta(dir, &index, protection)?;
    let report = MergeReport {
        elapsed_seconds: started.elapsed().as_secs_f64(),
        ..report
    };
    log_report(&report);
    Ok(report)
}

/// Sorts `sources` in Java string order of their names and checks them for
/// [`write_store`].
fn check_sources(sources: &mut [MergeSource], partitioned: bool) -> Result<()> {
    if sources.is_empty() {
        return Err(EtlError::Usage("a store needs at least one source".into()));
    }
    if !partitioned && sources.len() != 1 {
        return Err(EtlError::Usage(format!(
            "an unpartitioned store has one source, not {}",
            sources.len()
        )));
    }
    if sources.len() > MAX_PARTITIONS {
        return Err(EtlError::Usage(format!(
            "{} partitions exceed the limit of {MAX_PARTITIONS}",
            sources.len()
        )));
    }
    sources.sort_by(|a, b| java_string_cmp(&a.name, &b.name));
    if partitioned {
        for source in sources.iter() {
            check_partition_name(&source.name)?;
        }
        if let Some(w) = sources.windows(2).find(|w| w[0].name == w[1].name) {
            return Err(EtlError::Usage(format!(
                "partition {} has two sources",
                w[0].name
            )));
        }
    }
    Ok(())
}

/// The partition table of `sources`, whose observed patients are `observed`, and each
/// partition's summary. A partition's set is its source's patient set; the overlap set is
/// every patient in two or more sets and every observed patient its partition's set lacks.
fn partition_table(
    sources: &[MergeSource],
    observed: &[RoaringBitmap],
) -> (PartitionTable, Vec<PartitionSummary>) {
    let mut seen = RoaringBitmap::new();
    let mut overlap = RoaringBitmap::new();
    let mut partitions = Vec::with_capacity(sources.len());
    let mut summaries = Vec::with_capacity(sources.len());
    for (source, observed) in sources.iter().zip(observed) {
        let set = source.store.patients();
        overlap |= &seen & set;
        seen |= set;
        let missing = observed - set;
        overlap |= &missing;
        summaries.push(PartitionSummary {
            name: source.name.clone(),
            patients: set.len(),
            observed_patients_missing_from_set: missing.len(),
            patients_indexed: missing.is_empty(),
        });
        partitions.push(IndexPartition {
            name: source.name.clone(),
            patients_indexed: missing.is_empty(),
            patients: set.clone(),
        });
    }
    let table = PartitionTable {
        partitions,
        overlap,
    };
    (table, summaries)
}

/// What [`MergeContext::merge_concept`] reads besides the concept.
struct MergeContext<'a> {
    sources: &'a [MergeSource],
    overlap: &'a RoaringBitmap,
    partitioned: bool,
    partition_count: usize,
    codec: &'a BlockCodec,
}

impl MergeContext<'_> {
    /// Reads the concept's block from every source in `holders` (slot and index position,
    /// in slot order), merges their rows, and seals the result for `meta.path`.
    ///
    /// # Errors
    /// Block read and encoding errors; [`EtlError::Usage`] when a block's kind disagrees
    /// with the merged type; [`EtlError::Format`] when the payload exceeds the bound the
    /// merged metadata gives it.
    fn merge_concept(
        &self,
        meta: &ConceptMeta,
        holders: &[(usize, usize)],
    ) -> Result<MergedConcept> {
        let blocks = holders
            .iter()
            .map(|&(slot, position)| {
                let store = &self.sources[slot].store;
                store.read_block(&store.concepts()[position])
            })
            .collect::<Result<Vec<Block>>>()?;
        let partition_holders: Vec<PartitionHolder> = if self.partitioned {
            holders
                .iter()
                .zip(&blocks)
                .map(|(&(slot, _), block)| PartitionHolder {
                    partition: slot as u16,
                    observations: block.len() as u32,
                    patients: block.all_patients().len() as u32,
                })
                .collect()
        } else {
            Vec::new()
        };
        let tagged = self.partitioned
            && blocks
                .iter()
                .any(|b| !b.all_patients().is_disjoint(self.overlap));
        let slots: Vec<u16> = holders.iter().map(|&(slot, _)| slot as u16).collect();
        let merged = match meta.concept_type {
            ConceptType::Numeric => merge_numeric(&meta.path, &blocks, &slots)?,
            ConceptType::Categorical => merge_categorical(&meta.path, &blocks, &slots)?,
        };
        let overlap_rows = if tagged {
            merged
                .patients_in_order
                .iter()
                .filter(|&&p| self.overlap.contains(p))
                .count() as u64
        } else {
            0
        };
        let partitioning = BlockPartitioning {
            holders: &partition_holders,
            row_partitions: tagged.then_some(&merged.partitions[..]),
            partition_count: self.partition_count,
        };
        let payload = match meta.concept_type {
            ConceptType::Numeric => encode_numeric_block(&merged.numeric, &partitioning)?,
            ConceptType::Categorical => encode_interned_categorical_block(
                &merged.values,
                &merged.categorical,
                &partitioning,
            )?,
        };
        let observations = row_count(&meta.path, merged.patients_in_order.len())?;
        let counted = ConceptMeta {
            observation_count: observations,
            holders: partition_holders.clone(),
            tagged,
            ..meta.clone()
        };
        let bound = counted.max_payload_len();
        if payload.len() as u64 > bound {
            return Err(EtlError::Format(hpds_format::FormatError::InvalidInput(
                format!(
                    "the block of {:?} decodes to {} bytes, above the {bound} its merged metadata allows",
                    meta.path,
                    payload.len()
                ),
            )));
        }
        Ok(MergedConcept {
            stored: self.codec.seal(&meta.path, &payload)?,
            observations,
            patients: distinct_patients(merged.patients_in_order.iter().copied()),
            holders: partition_holders,
            tagged,
            overlap_rows,
        })
    }
}

/// One concept's rows in merged order, with each row's partition slot.
#[derive(Default)]
struct MergedRows<'a> {
    numeric: Vec<NumericObservation>,
    categorical: Vec<CategoricalRow>,
    values: Vec<&'a str>,
    partitions: Vec<u16>,
    patients_in_order: Vec<u32>,
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

/// The numeric rows of `blocks`, held by the partitions `slots`, in merged order. Each block
/// contributes its rows in `by_key` order; the stable sort on patient then keeps partition
/// order, and each partition's order, within a patient.
fn merge_numeric<'a>(path: &str, blocks: &[Block], slots: &[u16]) -> Result<MergedRows<'a>> {
    let mut rows = Vec::with_capacity(blocks.iter().map(Block::len).sum());
    for (block, &slot) in blocks.iter().zip(slots) {
        let Block::Numeric(block) = block else {
            return Err(kind_mismatch(path, "numeric"));
        };
        let (values, patients) = (block.values(), block.patients());
        rows.extend(block.by_key().iter().map(|&i| {
            let i = i as usize;
            (
                NumericObservation {
                    patient: patients[i],
                    value: values[i],
                    timestamp: block.timestamp_at_value_pos(i),
                },
                slot,
            )
        }));
    }
    rows.sort_by_key(|row| row.0.patient);
    Ok(MergedRows {
        patients_in_order: rows.iter().map(|r| r.0.patient).collect(),
        partitions: rows.iter().map(|r| r.1).collect(),
        numeric: rows.into_iter().map(|r| r.0).collect(),
        ..MergedRows::default()
    })
}

/// The categorical rows of `blocks`, held by the partitions `slots`, in merged order. Values
/// are interned across the partitions.
fn merge_categorical<'a>(path: &str, blocks: &'a [Block], slots: &[u16]) -> Result<MergedRows<'a>> {
    let mut ids: std::collections::HashMap<&str, u32> = std::collections::HashMap::new();
    let mut values: Vec<&str> = Vec::new();
    let mut rows = Vec::with_capacity(blocks.iter().map(Block::len).sum());
    for (block, &slot) in blocks.iter().zip(slots) {
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
                .map(|(cursor, &patient)| {
                    (
                        CategoricalRow {
                            patient,
                            value_id: local[indices[cursor] as usize],
                            timestamp: block.timestamp_at_key_pos(cursor),
                        },
                        slot,
                    )
                }),
        );
    }
    rows.sort_by_key(|row| row.0.patient);
    Ok(MergedRows {
        patients_in_order: rows.iter().map(|r| r.0.patient).collect(),
        partitions: rows.iter().map(|r| r.1).collect(),
        categorical: rows.into_iter().map(|r| r.0).collect(),
        values,
        ..MergedRows::default()
    })
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

/// The index header of a new store with id `store_id`, sealed with `protection`.
pub(crate) fn store_header(protection: &Protection, store_id: StoreId) -> IndexHeader {
    IndexHeader {
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
    }
}

/// Writes `pheno.index` and `columnMeta.csv` for `index` into `dir`, each mode `0600` and
/// synced to disk.
///
/// # Errors
/// Encoding and I/O errors.
pub(crate) fn write_index_and_column_meta(
    dir: &Path,
    index: &PhenoIndex,
    protection: &Protection,
) -> Result<()> {
    let index_path = dir.join(INDEX_FILE_NAME);
    private_fs::write_file(&index_path, &index.encode(protection)?)
        .map_err(EtlError::io("writing", &index_path))?;
    let csv_path = dir.join(COLUMN_META_CSV_NAME);
    let file = private_fs::create_file(&csv_path).map_err(EtlError::io("creating", &csv_path))?;
    let mut out = BufWriter::new(file);
    write_column_meta_csv(&mut out, index.concepts())
        .and_then(|()| out.flush())
        .and_then(|()| out.get_ref().sync_all())
        .map_err(EtlError::io("writing", &csv_path))
}

/// Logs what a merge wrote, and warns when the overlap set holds more than
/// [`OVERLAP_WARNING_SHARE`] of the rows.
fn log_report(report: &MergeReport) {
    tracing::info!(
        partitions = report.partitions.len(),
        concepts = report.concepts,
        observations = report.observations,
        patients = report.patients,
        overlap_patients = report.overlap_patients,
        overlap_observations = report.overlap_observations,
        tagged_concepts = report.tagged_concepts,
        blocks_bytes = report.blocks_bytes,
        seconds = report.elapsed_seconds,
        "store merged"
    );
    let not_indexed: Vec<&str> = report
        .partitions
        .iter()
        .filter(|p| !p.patients_indexed)
        .map(|p| p.name.as_str())
        .collect();
    if !not_indexed.is_empty() {
        tracing::warn!(
            partitions = %not_indexed.join(","),
            "partitions hold rows of patients missing from their patient sets; those patients are in the overlap set"
        );
    }
    if report.overlap_share > OVERLAP_WARNING_SHARE {
        tracing::warn!(
            overlap_patients = report.overlap_patients,
            share = report.overlap_share,
            "more than 5% of the rows belong to patients in two consent partitions or missing from their partition's patient set; narrow consent selections read those rows by tag"
        );
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn distinct_patients_counts_runs() {
        assert_eq!(distinct_patients([].into_iter()), 0);
        assert_eq!(distinct_patients([1, 1, 2, 5, 5, 5].into_iter()), 3);
    }
}
