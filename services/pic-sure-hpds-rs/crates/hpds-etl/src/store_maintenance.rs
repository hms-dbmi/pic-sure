//! Commands that change or regenerate the metadata of an existing store:
//! `hpds-etl remove-concepts` and `hpds-etl column-meta-csv`.

use std::io::Write;
use std::path::Path;

use hpds_format::{
    BLOCKS_FILE_NAME, BlocksReader, BlocksWriter, ConceptMeta, INDEX_FILE_NAME, PhenoIndex,
    Protection,
};
use serde::Serialize;

use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};
use crate::load::COLUMN_META_CSV_NAME;
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
