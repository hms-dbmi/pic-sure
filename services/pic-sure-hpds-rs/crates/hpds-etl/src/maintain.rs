//! Commands that change or regenerate the metadata of an existing store:
//! `hpds-etl remove-concepts` and `hpds-etl column-meta-csv`.

use std::io::Write;
use std::path::Path;

use hpds_format::{INDEX_FILE_NAME, PhenoIndex, Protection};
use serde::Serialize;

use crate::column_meta_csv::write_column_meta_csv;
use crate::error::{EtlError, Result};
use crate::load::COLUMN_META_CSV_NAME;
use crate::store::{read_index, replace_file};

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
/// `pheno.index` is rewritten with the store's id and header and renamed over the old one;
/// `pheno.blocks` is not touched, so the removed concepts' blocks stay in it as unreferenced
/// bytes, as Java leaves their cubes in `allObservationsStore.javabin`. `columnMeta.csv` is
/// then regenerated from the new index, which Java leaves to a separate
/// `CreateColumnmetaCSV` run.
///
/// # Errors
/// [`EtlError::Io`] when the concepts file cannot be read or is not UTF-8, and the errors
/// of reading and writing the index and `columnMeta.csv`.
pub fn remove_concepts(
    dir: &Path,
    concepts_file: &Path,
    protection: &Protection,
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
    let kept: Vec<_> = index
        .concepts()
        .iter()
        .zip(&remove)
        .filter(|(_, removed)| !**removed)
        .map(|(concept, _)| concept.clone())
        .collect();
    report.removed = (index.concepts().len() - kept.len()) as u64;
    report.remaining = kept.len() as u64;
    let rewritten = PhenoIndex::new(index.header().clone(), index.patients().clone(), kept)?;
    replace_file(&dir.join(INDEX_FILE_NAME), |out| {
        out.write_all(&rewritten.encode(protection)?)
            .map_err(EtlError::io("writing", dir.join(INDEX_FILE_NAME)))
    })?;
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
