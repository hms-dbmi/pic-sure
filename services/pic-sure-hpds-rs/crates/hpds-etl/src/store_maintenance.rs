//! Commands that change or regenerate the metadata of an existing store:
//! `hpds-etl remove-concepts` and `hpds-etl column-meta-csv`, and the merged
//! `columnMeta.csv` of a partitioned store.

use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};

use hpds_format::{
    BLOCKS_FILE_NAME, BlockLocation, BlocksReader, BlocksWriter, ConceptMeta, INDEX_FILE_NAME,
    PhenoIndex, Protection, java_string_cmp,
};
use serde::Serialize;

use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};
use crate::java::{java_double_max, java_double_min};
use crate::load::COLUMN_META_CSV_NAME;
use crate::partitions::Partition;
use crate::store::{StagedFile, read_index, replace_file, sync_parent};

/// What `remove-concepts` did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct RemoveReport {
    /// Lines read from the concepts file.
    pub lines: u64,
    /// Concepts removed from the index.
    pub removed: u64,
    /// Concepts left in the index.
    pub remaining: u64,
    /// Lines that named no concept in the store, in file order. Java ignores them silently.
    pub not_found: Vec<String>,
    /// Whether `pheno.blocks` was rewritten to hold only the remaining concepts' blocks.
    pub compacted: bool,
    /// Size of `pheno.blocks` in bytes before the command.
    pub blocks_bytes_before: u64,
    /// Size of `pheno.blocks` in bytes after the command.
    pub blocks_bytes_after: u64,
}

/// Splits `text` into lines the way `BufferedReader.readLine` does: a line ends at `\n`,
/// `\r`, or `\r\n`, the terminator is not part of the line, and text after the last
/// terminator is a final line only when it is not empty.
pub fn java_read_lines(text: &str) -> Vec<&str> {
    let mut lines = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        match rest.find(['\n', '\r']) {
            Some(end) => {
                lines.push(&rest[..end]);
                let skip = if rest[end..].starts_with("\r\n") {
                    2
                } else {
                    1
                };
                rest = &rest[end + skip..];
            }
            None => {
                lines.push(rest);
                rest = "";
            }
        }
    }
    lines
}

/// Removes the concepts named in `concepts_file` from the store in `dir`, the step
/// `RemoveConceptFromMetadata` performs on a javabin store.
///
/// The file is read as UTF-8 and split into lines as `BufferedReader.readLine` does. A
/// concept is removed when its path equals a line exactly, after U+FFFD is removed from the
/// line: Java matches the raw metastore keys, which still hold U+FFFD, while this store
/// holds the keys with U+FFFD stripped, as the Java service sees them. Lines are not
/// trimmed and a line is never a prefix match. The patient ID set is left as it is, as Java
/// leaves `allIds`.
///
/// With `compact`, `pheno.blocks` is rewritten to hold only the remaining concepts' blocks,
/// each verified on the way, so the removed concepts' observations are physically gone;
/// Java instead leaves their cubes in `allObservationsStore.javabin`. The store keeps its
/// id, so the blocks are copied without being re-encrypted. The new blocks file and index
/// are written beside the old ones and renamed into place, blocks first and index last; a
/// reader holding the old index meanwhile fails authentication on a moved block rather
/// than reading another concept's data. Without `compact`, only `pheno.index` is rewritten
/// and the removed blocks stay in `pheno.blocks` as unreferenced bytes. `columnMeta.csv` is
/// then regenerated from the new index, which Java leaves to a separate
/// `CreateColumnmetaCSV` run.
///
/// # Errors
/// [`EtlError::Io`] when the concepts file cannot be read or is not UTF-8, and the errors
/// of reading, verifying and writing the index, the blocks and `columnMeta.csv`.
pub fn remove_concepts(
    dir: &Path,
    concepts_file: &Path,
    protection: &Protection,
    compact: bool,
) -> Result<RemoveReport> {
    let text =
        std::fs::read_to_string(concepts_file).map_err(EtlError::io("reading", concepts_file))?;
    let lines = java_read_lines(&text);
    let index = read_index(dir, protection)?;
    let mut remove = vec![false; index.concepts().len()];
    let mut report = RemoveReport {
        lines: lines.len() as u64,
        ..RemoveReport::default()
    };
    for line in lines {
        let path = line.replace('\u{FFFD}', "");
        match index.position(&path) {
            Some(position) => remove[position] = true,
            None => report.not_found.push(line.to_string()),
        }
    }
    let mut kept: Vec<ConceptMeta> = index
        .concepts()
        .iter()
        .zip(&remove)
        .filter(|(_, removed)| !**removed)
        .map(|(concept, _)| concept.clone())
        .collect();
    report.removed = (index.concepts().len() - kept.len()) as u64;
    report.remaining = kept.len() as u64;

    let blocks_path = dir.join(BLOCKS_FILE_NAME);
    let index_path = dir.join(INDEX_FILE_NAME);
    let reader = BlocksReader::open(&blocks_path, index.store_id())?;
    report.blocks_bytes_before = reader.len();
    let staged_blocks = if compact {
        let codec = index.block_codec(protection.clone())?;
        let mut new_len = 0;
        let staged = StagedFile::write(&blocks_path, |out| {
            let mut writer = BlocksWriter::new(out, index.store_id())?;
            for concept in &mut kept {
                reader.read_block(&codec, concept)?;
                let stored = reader.read_stored(concept.block)?;
                concept.block = writer.append_stored(&stored)?;
            }
            new_len = writer.len();
            writer.finish()?;
            Ok(())
        })?;
        report.blocks_bytes_after = new_len;
        report.compacted = true;
        Some(staged)
    } else {
        report.blocks_bytes_after = report.blocks_bytes_before;
        None
    };
    drop(reader);

    let rewritten = PhenoIndex::new(index.header().clone(), index.patients().clone(), kept)?;
    let staged_index = StagedFile::write(&index_path, |out| {
        out.write_all(&rewritten.encode(protection)?)
            .map_err(EtlError::io("writing", &index_path))
    })?;
    if let Some(staged_blocks) = staged_blocks {
        staged_blocks.commit()?;
    }
    staged_index.commit()?;
    sync_parent(&index_path)?;
    write_column_meta_csv_file(&rewritten, &dir.join(COLUMN_META_CSV_NAME))?;
    Ok(report)
}

/// Removes the concepts named in `concepts_file` from every partition in `partitions`, in
/// order, as [`remove_concepts`] does for one store, and returns each partition's report
/// by name.
///
/// The partitions are changed one at a time, so a failure leaves the ones before it
/// changed and the rest untouched. The command is idempotent: run again, it finds the
/// concepts already gone from the changed partitions (they appear in `not_found`) and
/// removes them from the rest.
///
/// # Errors
/// [`EtlError::PartitionFailed`] naming the partition that failed, the partitions
/// already changed, and the error of [`remove_concepts`].
pub fn remove_concepts_partitioned(
    partitions: &[Partition],
    concepts_file: &Path,
    protection: &Protection,
    compact: bool,
) -> Result<BTreeMap<String, RemoveReport>> {
    let mut reports = BTreeMap::new();
    let mut changed = Vec::new();
    for partition in partitions {
        let report = remove_concepts(&partition.dir, concepts_file, protection, compact).map_err(
            |source| EtlError::PartitionFailed {
                partition: partition.name.clone(),
                changed: changed.clone(),
                source: Box::new(source),
            },
        )?;
        tracing::info!(partition = %partition.name, removed = report.removed, "concepts removed");
        changed.push(partition.name.clone());
        reports.insert(partition.name.clone(), report);
    }
    Ok(reports)
}

/// Regenerates `columnMeta.csv` for the store in `dir` from its index, byte for byte as
/// `hpds-etl load` writes it, at `out` or by default at `<dir>/columnMeta.csv`. Returns the
/// number of rows written.
///
/// # Errors
/// The errors of reading the index and writing the file.
pub fn regenerate_column_meta_csv(
    dir: &Path,
    out: Option<&Path>,
    protection: &Protection,
) -> Result<u64> {
    let index = read_index(dir, protection)?;
    let target = out.map_or_else(|| dir.join(COLUMN_META_CSV_NAME), Path::to_path_buf);
    write_column_meta_csv_file(&index, &target)?;
    Ok(index.concepts().len() as u64)
}

/// Builds the dictionary of a partitioned store: one entry per concept path found in any
/// partition, in Java `String.compareTo` order, from `partitions` given as (name, index) in
/// partition name order. Java writes no such file; this follows the merge rules of the
/// harmonization branch's `SummaryColumnMeta`, which the Java service builds in memory for
/// `/search`:
///
/// - width: the largest; column offset: the first partition's;
/// - category values: the sorted union, in Java `String.compareTo` order;
/// - min and max: the smallest min and the largest max, absent ones skipped;
/// - observation and patient counts: the sums, so a patient in two partitions counts twice,
///   as `SummaryColumnMeta.patientCount` does;
/// - timestamps: present when present in any partition, the smallest min and largest max;
/// - block offset and length: 0, since no single block holds the concept.
///
/// # Errors
/// [`EtlError::Usage`] when a concept is numeric in one partition and categorical in
/// another, naming the concept and both partitions; the query engine refuses such a store.
pub fn merge_column_meta(partitions: &[(String, &PhenoIndex)]) -> Result<Vec<ConceptMeta>> {
    let mut merged: BTreeMap<&str, (ConceptMeta, &str)> = BTreeMap::new();
    for (name, index) in partitions {
        for concept in index.concepts() {
            let Some((into, first)) = merged.get_mut(concept.path.as_str()) else {
                let mut start = concept.clone();
                start.block = BlockLocation::default();
                merged.insert(&concept.path, (start, name));
                continue;
            };
            if into.concept_type != concept.concept_type {
                return Err(EtlError::Usage(format!(
                    "concept {:?} is {} in partition {first} and {} in partition {name}",
                    concept.path,
                    type_name(into),
                    type_name(concept)
                )));
            }
            merge_into(into, concept);
        }
    }
    let mut concepts: Vec<ConceptMeta> = merged.into_values().map(|(meta, _)| meta).collect();
    concepts.sort_by(|a, b| java_string_cmp(&a.path, &b.path));
    Ok(concepts)
}

fn type_name(meta: &ConceptMeta) -> &'static str {
    if meta.is_categorical() {
        "categorical"
    } else {
        "numeric"
    }
}

fn merge_into(into: &mut ConceptMeta, other: &ConceptMeta) {
    into.width_in_bytes = into.width_in_bytes.max(other.width_in_bytes);
    if let (Some(values), Some(more)) = (&mut into.category_values, &other.category_values) {
        values.extend(more.iter().cloned());
        values.sort_by(|a, b| java_string_cmp(a, b));
        values.dedup();
    }
    let pick = |a: Option<f64>, b: Option<f64>, f: fn(f64, f64) -> f64| match (a, b) {
        (Some(a), Some(b)) => Some(f(a, b)),
        (a, b) => a.or(b),
    };
    into.min = pick(into.min, other.min, java_double_min);
    into.max = pick(into.max, other.max, java_double_max);
    into.observation_count = into
        .observation_count
        .saturating_add(other.observation_count);
    into.patient_count = into.patient_count.saturating_add(other.patient_count);
    into.has_timestamp |= other.has_timestamp;
    into.timestamp_min = match (into.timestamp_min, other.timestamp_min) {
        (Some(a), Some(b)) => Some(a.min(b)),
        (a, b) => a.or(b),
    };
    into.timestamp_max = match (into.timestamp_max, other.timestamp_max) {
        (Some(a), Some(b)) => Some(a.max(b)),
        (a, b) => a.or(b),
    };
}

/// Writes the merged `columnMeta.csv` of the partitions in `dirs`, given as (name,
/// directory) in partition name order, to `target`, replacing it atomically. Returns the
/// number of rows written. See [`merge_column_meta`].
///
/// # Errors
/// The errors of reading each index, of [`merge_column_meta`], and of writing the file.
pub fn write_merged_column_meta_csv(
    dirs: &[(String, PathBuf)],
    target: &Path,
    protection: &Protection,
) -> Result<u64> {
    let indexes = dirs
        .iter()
        .map(|(name, dir)| Ok((name.clone(), read_index(dir, protection)?)))
        .collect::<Result<Vec<_>>>()?;
    let borrowed: Vec<(String, &PhenoIndex)> = indexes
        .iter()
        .map(|(name, index)| (name.clone(), index))
        .collect();
    let concepts = merge_column_meta(&borrowed)?;
    replace_file(target, |out| {
        write_column_meta_csv(out, &concepts).map_err(EtlError::io("writing", target))
    })?;
    Ok(concepts.len() as u64)
}

fn write_column_meta_csv_file(index: &PhenoIndex, target: &Path) -> Result<()> {
    replace_file(target, |out| {
        write_column_meta_csv(out, index.concepts()).map_err(EtlError::io("writing", target))
    })
}

#[cfg(test)]
mod tests {
    use super::java_read_lines;

    #[test]
    fn lines_split_as_buffered_reader_does() {
        assert_eq!(java_read_lines(""), Vec::<&str>::new());
        assert_eq!(java_read_lines("a"), ["a"]);
        assert_eq!(java_read_lines("a\n"), ["a"]);
        assert_eq!(java_read_lines("a\n\n"), ["a", ""]);
        assert_eq!(java_read_lines("a\r\nb\rc\n"), ["a", "b", "c"]);
        assert_eq!(java_read_lines("\r\r\n"), ["", ""]);
        assert_eq!(java_read_lines(" a \\b\\ "), [" a \\b\\ "]);
    }
}
