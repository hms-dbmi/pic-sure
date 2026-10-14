//! Commands that change or regenerate the metadata of an existing store:
//! `hpds-etl remove-concepts` and `hpds-etl column-meta-csv`, and the merge rules of a
//! concept's metadata across partitions. Both commands hold the store lock
//! ([`crate::store::lock_store`]) while they write.

use std::collections::BTreeMap;
use std::path::Path;

use hpds_format::{
    BLOCKS_FILE_NAME, BLOCKS_HEADER_LEN, BlockLocation, BlocksReader, BlocksWriter, ConceptMeta,
    INDEX_FILE_NAME, PhenoIndex, Protection, java_string_cmp,
};
use roaring::RoaringBitmap;
use serde::Serialize;

use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};
use crate::java::{java_double_max, java_double_min};
use crate::load::COLUMN_META_CSV_NAME;
use crate::maintenance_log;
use crate::publish::{
    self, HashingWriter, Replacement, StopAfter, recover_held, refuse_linked_store_files,
    refuse_pending_publish,
};
use crate::store::{
    ServingGuard, StagedFile, lock_store, lock_store_shared, read_index, replace_file,
};

/// Largest concepts file `remove-concepts` reads: 64 MiB, far above any list of concept
/// paths, so a device or a wrong file is refused rather than read into memory.
pub const MAX_CONCEPTS_FILE_BYTES: u64 = 64 << 20;

/// How many unmatched lines the error of a strict removal lists before it only counts the
/// rest.
const LISTED_MISSING_LINES: usize = 20;

/// How `remove-concepts` runs.
#[derive(Clone, Debug, Default)]
pub struct RemoveOptions {
    /// Remove the concepts that exist even when some lines of the file name no concept,
    /// listing those lines in the report. Without it such a line fails the command before
    /// anything is written.
    pub allow_missing: bool,
    /// Rewrite only `pheno.index`, leaving the removed concepts' blocks in `pheno.blocks` as
    /// unreferenced, still encrypted bytes. Without it `pheno.blocks` is compacted.
    pub keep_removed_blocks: bool,
    /// Report what would be removed, reading the store under a shared lock, and write
    /// nothing. Lines naming no concept are listed rather than failing the command.
    pub dry_run: bool,
    /// Stops the publish after this step as a crash would, for tests of
    /// [`crate::publish::recover`]. `None` in every real run.
    #[doc(hidden)]
    pub stop_after: StopAfter,
}

/// What `remove-concepts` did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct RemoveReport {
    /// True when nothing was written ([`RemoveOptions::dry_run`]): the counts, paths and
    /// sizes are what a run would produce.
    pub dry_run: bool,
    /// Dry runs only: true when a query service holds the store open, so a run would be
    /// refused until the service is stopped.
    pub store_in_use: bool,
    /// The store's id, which removal keeps.
    pub store_id: String,
    /// Lines read from the concepts file.
    pub lines: u64,
    /// Empty lines, which name nothing and are skipped.
    pub blank_lines: u64,
    /// Concepts removed from the index.
    pub removed: u64,
    /// Paths of the removed concepts, in index order.
    pub removed_paths: Vec<String>,
    /// Observations the removed concepts held.
    pub observations_removed: u64,
    /// Distinct patients with at least one removed observation, read from the removed
    /// blocks; `None` when one of them could not be read.
    pub patients_with_removed_rows: Option<u64>,
    /// Removed concepts whose block could not be read, authenticated or decoded. They are
    /// removed all the same.
    pub unreadable_removed_blocks: u64,
    /// Concepts left in the index.
    pub remaining: u64,
    /// Lines that named no concept in the store, in file order. Only an
    /// [`RemoveOptions::allow_missing`] run reports any; otherwise they fail the command.
    pub not_found: Vec<String>,
    /// Whether `pheno.blocks` was rewritten to hold only the remaining concepts' blocks.
    pub compacted: bool,
    /// Whether the removed concepts' blocks were left in `pheno.blocks`
    /// ([`RemoveOptions::keep_removed_blocks`]).
    pub removed_blocks_kept: bool,
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

/// Reads the concepts file of `remove-concepts`: a regular file (a symbolic link to one is
/// followed) of at most [`MAX_CONCEPTS_FILE_BYTES`], in UTF-8.
///
/// # Errors
/// [`EtlError::Usage`] for anything but a regular file, or one too large; [`EtlError::Io`]
/// when it cannot be read or is not UTF-8.
pub fn read_concepts_file(path: &Path) -> Result<String> {
    let meta = std::fs::metadata(path).map_err(EtlError::io("reading", path))?;
    if !meta.is_file() {
        return Err(EtlError::Usage(format!(
            "{} is not a regular file; give the file listing the concept paths to remove",
            path.display()
        )));
    }
    if meta.len() > MAX_CONCEPTS_FILE_BYTES {
        return Err(EtlError::Usage(format!(
            "{} holds {} bytes, more than the {MAX_CONCEPTS_FILE_BYTES} a list of concept paths can need",
            path.display(),
            meta.len()
        )));
    }
    std::fs::read_to_string(path).map_err(EtlError::io("reading", path))
}

/// The concepts the lines of a concepts file select in `index`, Java's rules: a line names
/// the concept whose path equals it exactly once U+FFFD is removed from it. Returns which
/// index positions are selected, and fills the report's line counts and `not_found`.
fn select_concepts(index: &PhenoIndex, lines: &[&str], report: &mut RemoveReport) -> Vec<bool> {
    let mut remove = vec![false; index.concepts().len()];
    report.lines = lines.len() as u64;
    for line in lines {
        if line.is_empty() {
            report.blank_lines += 1;
            continue;
        }
        let path = line.replace('\u{FFFD}', "");
        match index.position(&path) {
            Some(position) => remove[position] = true,
            None => report.not_found.push((*line).to_string()),
        }
    }
    remove
}

/// Why `line`, which names no concept of `index`, may have been meant to name one.
fn near_miss(index: &PhenoIndex, line: &str) -> Option<&'static str> {
    let path = line.replace('\u{FFFD}', "");
    let trimmed = path.trim();
    if trimmed != path && index.position(trimmed).is_some() {
        return Some("a concept matches it without the surrounding whitespace");
    }
    let core = trimmed.trim_matches('\\');
    let framed = format!("\\{core}\\");
    if framed != trimmed && index.position(&framed).is_some() {
        return Some("a concept matches it with a backslash at each end");
    }
    None
}

/// The error of a strict removal whose concepts file has lines naming no concept: lists
/// them (up to [`LISTED_MISSING_LINES`]) with their line numbers and any near miss.
fn missing_error(
    concepts_file: &Path,
    index: &PhenoIndex,
    lines: &[&str],
    not_found: &[String],
) -> EtlError {
    let mut listed = Vec::new();
    let mut wanted = not_found.iter().peekable();
    for (number, line) in lines.iter().enumerate() {
        if listed.len() == LISTED_MISSING_LINES {
            break;
        }
        if wanted.peek().is_some_and(|w| w.as_str() == *line) {
            wanted.next();
            let hint = near_miss(index, line).map_or_else(String::new, |h| format!(" ({h})"));
            listed.push(format!("line {}: {line:?}{hint}", number + 1));
        }
    }
    let more = not_found.len().saturating_sub(listed.len());
    let more = if more > 0 {
        format!("; and {more} more")
    } else {
        String::new()
    };
    EtlError::Usage(format!(
        "{} of the lines in {} name no concept in the store, so nothing was removed: {}{more}. Lines match a concept path exactly, case, spaces and backslashes included. Fix the file, or pass --allow-missing to remove the concepts that exist (for example on a rerun)",
        not_found.len(),
        concepts_file.display(),
        listed.join("; ")
    ))
}

/// Reads the block of every concept `remove` selects and counts the distinct patients with
/// a row in one into `report`, with the blocks that could not be read. Logs each of those
/// by concept path.
///
/// # Errors
/// The errors of building the block codec.
fn count_removed_patients(
    index: &PhenoIndex,
    reader: &BlocksReader,
    protection: &Protection,
    remove: &[bool],
    report: &mut RemoveReport,
) -> Result<()> {
    let codec = index.block_codec(protection.clone())?;
    let membership = index.membership();
    let mut patients = RoaringBitmap::new();
    for (concept, _) in index.concepts().iter().zip(remove).filter(|(_, r)| **r) {
        match reader.read_block(&codec, concept, &membership) {
            Ok(block) => patients |= block.all_patients(),
            Err(error) => {
                tracing::warn!(concept = %concept.path, %error, "cannot read a removed concept's block; it is removed all the same");
                report.unreadable_removed_blocks += 1;
            }
        }
    }
    report.patients_with_removed_rows =
        (report.unreadable_removed_blocks == 0).then(|| patients.len());
    Ok(())
}

/// Removes the concepts named in `concepts_file` from the store in `dir`, the step
/// `RemoveConceptFromMetadata` performs on a javabin store.
///
/// The file is read as UTF-8 ([`read_concepts_file`]) and split into lines as
/// `BufferedReader.readLine` does. A concept is removed when its path equals a line exactly,
/// after U+FFFD is removed from the line: Java matches the raw metastore keys, which still
/// hold U+FFFD, while this store holds the keys with U+FFFD stripped, as the Java service
/// sees them. Lines are not trimmed and a line is never a prefix match. Empty lines are
/// skipped. Unlike Java, which ignores a line naming no concept, such a line fails the
/// command before anything is written, listing the lines, unless
/// [`RemoveOptions::allow_missing`] is set; a file with no line naming anything always
/// fails. With `allow_missing` and nothing to remove, no file is rewritten.
///
/// The patient ID set, the partition table and the overlap set are left as they are, as
/// Java leaves `allIds`; removing rows never adds a patient to an untagged block, so the
/// overlap set stays correct for the blocks that remain.
///
/// By default `pheno.blocks` is rewritten to hold only the remaining concepts' blocks, each
/// verified on the way, so the removed concepts' observations are no longer in the store's
/// files; Java instead leaves their cubes in `allObservationsStore.javabin`. The store keeps
/// its id, so the blocks are copied without being re-encrypted. With
/// [`RemoveOptions::keep_removed_blocks`] only `pheno.index` is rewritten and the removed
/// blocks stay in `pheno.blocks` as unreferenced bytes. `columnMeta.csv` is then regenerated
/// from the new index, which Java leaves to a separate `CreateColumnmetaCSV` run. The whole
/// command holds the store lock, so it never interleaves with a load publishing into the
/// same directory. It also holds the [`ServingGuard`], so it refuses to run while a query
/// service holds the store open: a running service keeps the index and blocks it read at
/// startup, and would go on serving the removed concepts.
///
/// With [`RemoveOptions::dry_run`] the store is read under the shared store lock, every
/// removed block is read to count its patients, and the report says what a run would remove
/// and how large `pheno.blocks` would become; nothing is written, and lines naming no
/// concept are listed instead of failing. A real run reads the removed blocks too, for the
/// same count, and still removes a concept whose block cannot be read.
///
/// Every run that is not a dry run appends a record to the store's maintenance log
/// ([`crate::maintenance_log`]) once its files are in place: result `published`, or
/// `no-change` for an `allow_missing` run that found nothing to remove.
///
/// # Errors
/// [`EtlError::Usage`] for a concepts file that names nothing or has lines naming no
/// concept (without `allow_missing`), and the errors of [`read_concepts_file`] and of
/// reading, verifying and writing the index, the blocks and `columnMeta.csv`.
pub fn remove_concepts(
    dir: &Path,
    concepts_file: &Path,
    protection: &Protection,
    options: &RemoveOptions,
) -> Result<RemoveReport> {
    let text = read_concepts_file(concepts_file)?;
    let concepts_sha256 = maintenance_log::sha256_hex(text.as_bytes());
    let lines = java_read_lines(&text);
    if lines.iter().all(|line| line.is_empty()) {
        return Err(EtlError::Usage(format!(
            "{} names no concept: it is empty or holds only empty lines",
            concepts_file.display()
        )));
    }
    let _lock = if options.dry_run {
        let lock = lock_store_shared(dir)?;
        refuse_pending_publish(dir)?;
        lock
    } else {
        let lock = lock_store(dir)?;
        refuse_linked_store_files(dir)?;
        lock
    };
    let mut serving = if options.dry_run {
        None
    } else {
        let mut serving = ServingGuard::acquire(dir)?;
        recover_held(dir, protection, &mut serving)?;
        Some(serving)
    };
    let index = read_index(dir, protection)?;
    let mut report = RemoveReport {
        dry_run: options.dry_run,
        store_in_use: options.dry_run && ServingGuard::is_held(dir)?,
        store_id: index.store_id().to_string(),
        removed_blocks_kept: options.keep_removed_blocks,
        ..RemoveReport::default()
    };
    let remove = select_concepts(&index, &lines, &mut report);
    if !report.not_found.is_empty() && !options.allow_missing && !options.dry_run {
        return Err(missing_error(
            concepts_file,
            &index,
            &lines,
            &report.not_found,
        ));
    }
    let mut kept = Vec::with_capacity(index.concepts().len());
    for (concept, removed) in index.concepts().iter().zip(&remove) {
        if *removed {
            report.removed_paths.push(concept.path.clone());
            report.observations_removed += u64::from(concept.observation_count);
        } else {
            kept.push(concept.clone());
        }
    }
    report.removed = report.removed_paths.len() as u64;
    report.remaining = kept.len() as u64;

    let blocks_path = dir.join(BLOCKS_FILE_NAME);
    let index_path = dir.join(INDEX_FILE_NAME);
    let reader = BlocksReader::open(&blocks_path, index.store_id())?;
    report.blocks_bytes_before = reader.len();
    report.blocks_bytes_after = reader.len();
    if report.removed == 0 {
        report.removed_blocks_kept = false;
        if !options.dry_run {
            let record = removal_record(&report, concepts_file, &concepts_sha256, None);
            maintenance_log::append(dir, &publish::with_result(record, RESULT_NO_CHANGE))?;
        }
        return Ok(report);
    }
    count_removed_patients(&index, &reader, protection, &remove, &mut report)?;
    if options.dry_run {
        if !options.keep_removed_blocks {
            report.compacted = true;
            report.blocks_bytes_after =
                BLOCKS_HEADER_LEN + kept.iter().map(|c| c.block.length).sum::<u64>();
        }
        return Ok(report);
    }
    let mut replacements = Vec::with_capacity(2);
    if !options.keep_removed_blocks {
        let codec = index.block_codec(protection.clone())?;
        let membership = index.membership();
        let mut written = (0, 0);
        let staged = StagedFile::write(&blocks_path, |out| {
            let mut writer = BlocksWriter::new(HashingWriter::new(out), index.store_id())?;
            for concept in &mut kept {
                reader.read_block(&codec, concept, &membership)?;
                let stored = reader.read_stored(concept.block)?;
                concept.block = writer.append_stored(&stored)?;
            }
            written = writer.finish()?.digest();
            Ok(())
        })?;
        report.blocks_bytes_after = written.0;
        report.compacted = true;
        replacements.push(Replacement::new(staged, written.0, written.1));
    }
    drop(reader);

    let rewritten = PhenoIndex::with_partitions(
        index.header().clone(),
        index.patients().clone(),
        index.partition_table().clone(),
        kept,
    )?;
    let encoded = rewritten.encode(protection)?;
    replacements.push(Replacement::of_bytes(&index_path, &encoded)?);
    let record = removal_record(
        &report,
        concepts_file,
        &concepts_sha256,
        Some(&maintenance_log::sha256_hex(&encoded)),
    );
    publish::publish(
        dir,
        "remove-concepts",
        replacements,
        record,
        || write_column_meta_csv_file(&rewritten, &dir.join(COLUMN_META_CSV_NAME)),
        serving.get_or_insert_with(ServingGuard::default),
        options.stop_after,
    )?;
    Ok(report)
}

/// Result of a removal record whose files were published by the run itself.
pub const RESULT_PUBLISHED: &str = "published";

/// Result of a removal record of an `--allow-missing` run that found nothing to remove and
/// wrote no store file.
pub const RESULT_NO_CHANGE: &str = "no-change";

/// The maintenance log record of the removal `report` describes, driven by `concepts_file`
/// whose SHA-256 is `concepts_sha256`, leaving the index whose SHA-256 is `index_sha256`.
/// Its `result` and `time` are set when it is appended.
fn removal_record(
    report: &RemoveReport,
    concepts_file: &Path,
    concepts_sha256: &str,
    index_sha256: Option<&str>,
) -> serde_json::Value {
    serde_json::json!({
        "time": null,
        "operation": "remove-concepts",
        "result": null,
        "tool": crate::banner(),
        "store_id": report.store_id,
        "concepts_file": concepts_file.display().to_string(),
        "concepts_file_sha256": concepts_sha256,
        "lines": report.lines,
        "blank_lines": report.blank_lines,
        "lines_naming_no_concept": report.not_found.len(),
        "removed": report.removed,
        "removed_paths": report.removed_paths,
        "observations_removed": report.observations_removed,
        "patients_with_removed_rows": report.patients_with_removed_rows,
        "unreadable_removed_blocks": report.unreadable_removed_blocks,
        "remaining": report.remaining,
        "compacted": report.compacted,
        "removed_blocks_kept": report.removed_blocks_kept,
        "blocks_bytes_before": report.blocks_bytes_before,
        "blocks_bytes_after": report.blocks_bytes_after,
        "index_sha256": index_sha256,
    })
}

/// Regenerates `columnMeta.csv` for the store in `dir` from its index, byte for byte as
/// `hpds-etl load` writes it, at `out` or by default at `<dir>/columnMeta.csv`, holding the
/// store lock while it reads the index and writes the file. Returns the number of rows
/// written.
///
/// # Errors
/// The errors of locking the store, reading the index and writing the file.
pub fn regenerate_column_meta_csv(
    dir: &Path,
    out: Option<&Path>,
    protection: &Protection,
) -> Result<u64> {
    let _lock = lock_store(dir)?;
    refuse_pending_publish(dir)?;
    let index = read_index(dir, protection)?;
    let target = out.map_or_else(|| dir.join(COLUMN_META_CSV_NAME), Path::to_path_buf);
    write_column_meta_csv_file(&index, &target)?;
    Ok(index.concepts().len() as u64)
}

/// Builds the dictionary of a partitioned store: one entry per concept path found in any
/// partition, in Java `String.compareTo` order, from `partitions` given as (name, concepts)
/// in partition name order. This follows the merge rules of the harmonization branch's
/// `SummaryColumnMeta`, which the Java service builds in memory for `/search`:
///
/// - width: the largest; column offset: the first partition's;
/// - category values: the sorted union, in Java `String.compareTo` order;
/// - min and max: the smallest min and the largest max, absent ones skipped;
/// - observation and patient counts: the sums, so a patient in two partitions counts twice,
///   as `SummaryColumnMeta.patientCount` does;
/// - timestamps: present when present in any partition, the smallest min and largest max;
/// - block offset and length: 0, and no holders, for the caller to fill in.
///
/// # Errors
/// [`EtlError::Usage`] when a concept is numeric in one partition and categorical in
/// another, naming the concept and both partitions; one block cannot hold both.
pub fn merge_column_meta(partitions: &[(String, &[ConceptMeta])]) -> Result<Vec<ConceptMeta>> {
    let mut merged: BTreeMap<&str, (ConceptMeta, &str)> = BTreeMap::new();
    for (name, concepts) in partitions {
        for concept in concepts.iter() {
            let Some((into, first)) = merged.get_mut(concept.path.as_str()) else {
                let mut start = concept.clone();
                start.block = BlockLocation::default();
                start.holders = Vec::new();
                start.tagged = false;
                merged.insert(&concept.path, (start, name));
                continue;
            };
            if into.concept_type != concept.concept_type {
                return Err(EtlError::Usage(format!(
                    "concept {:?} is {} in partition {first} and {} in partition {name}; one block cannot hold both, so fix the input",
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

/// Writes `columnMeta.csv` for `index` at `target`, replacing any file there atomically.
///
/// # Errors
/// I/O errors writing, syncing or renaming the file.
pub(crate) fn write_column_meta_csv_file(index: &PhenoIndex, target: &Path) -> Result<()> {
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
