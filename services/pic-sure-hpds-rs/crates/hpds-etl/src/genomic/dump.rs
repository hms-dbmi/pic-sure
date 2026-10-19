//! `hpds-etl genomic-dump`: a genomic store in the canonical text form that the Java
//! `DumpGenomicEq` writes for a Java store, so the two compare line for line.
//!
//! One line per item, fields separated by a tab, every line ending in a newline. Strings
//! are escaped: a backslash as `\\`, a tab as `\t`, a newline as `\n`, a carriage return as
//! `\r`. Ids are decimal; an id list is comma-separated and ascending.
//!
//! ```text
//! M  mode
//! per node, ordered by group, then directory name:
//!   N  group  dir  patient_count  variant_count
//!   P  the patient ids in Java's order
//!   per bucket holding a variant, by contig in Java order, then bucket:
//!     B  contig  bucket
//!     V  spec  record  he  ho  hen  hon  string_count  strings...   per variant, by spec
//!     C  the carrier ids: the bucket index's patients for the bucket, empty when it has none
//!   per info column, in Java order:
//!     I  column  continuous  key_count  description
//!     K  key  spec_count  specs...    per key, ascending; the key's distinct specs, ascending
//!     R  lower  upper  entries        per CompressedIndex range, lines in byte order
//! ```
//!
//! `record` is 1 when Java holds a mask record for the spec. A mask is `-` when absent, else
//! `s:` (sparse form) or `b:` (bitmask form) followed by its patients' ids. A range bound is
//! `u` when unbounded, else `o:` (open) or `c:` (closed) and the eight lowercase hex digits
//! of the float's bits. Entries are `bits=k,k;bits=k,...`: each float's bits and the indexes
//! of its keys in the column's ascending key list. Strings sort in Java `String.compareTo`
//! order.
//!
//! In digest mode each bucket's `B`, `V` and `C` lines become one line
//! `D  B  contig  bucket  line_count  sha256`, and each column's `K` lines one line
//! `D  K  column  line_count  sha256`, the SHA-256 of the replaced lines' bytes in hex.

use std::fmt::Write as _;
use std::io::Write;
use std::path::Path;

use hpds_format::{
    GENO_BLOCKS_FILE_NAME, GENO_INDEX_FILE_NAME, GenoBlocksReader, GenoIndex, MaskChunk, MaskKind,
    Protection, RangeBound, java_string_cmp,
};
use sha2::{Digest, Sha256};

use crate::error::{EtlError, Result};

/// What a dump wrote.
#[derive(Clone, Copy, Debug, Default)]
pub struct DumpCounts {
    /// Nodes.
    pub nodes: u64,
    /// Variants over all nodes.
    pub variants: u64,
    /// Lines written.
    pub lines: u64,
}

/// Writes the store in `dir`, opened under `protection`, to `out` in the canonical form, or
/// in digest mode when `digest` is set.
///
/// # Errors
/// Format errors reading the store, and I/O errors writing `out`.
pub fn dump_genomic(
    dir: &Path,
    protection: &Protection,
    digest: bool,
    out: &mut dyn Write,
) -> Result<DumpCounts> {
    let index = GenoIndex::read_file(dir.join(GENO_INDEX_FILE_NAME), protection)?;
    let codec = index.block_codec(protection.clone())?;
    let reader = GenoBlocksReader::open(dir.join(GENO_BLOCKS_FILE_NAME), &index)?;
    let mut sink = Sink {
        out,
        lines: 0,
        digest,
    };
    sink.line(&format!("M\t{}", index.mode().java_name()))?;
    let mut counts = DumpCounts::default();
    for (n, node) in index.nodes().iter().enumerate() {
        let n = n as u16;
        let group = &index.groups()[usize::from(node.group)];
        sink.line(&format!(
            "N\t{}\t{}\t{}\t{}",
            escape(&group.name),
            escape(&node.dir_name),
            group.patients.len(),
            node.variant_count
        ))?;
        sink.line(&format!("P\t{}", join_ids(group.patients.iter().copied())))?;
        let mut specs: Vec<String> = Vec::with_capacity(node.variant_count as usize);
        for (c, chunk) in node.chunks.iter().enumerate() {
            let masks = reader.read_mask_chunk(&codec, &index, n, c as u32)?;
            let metadata = reader.read_metadata_chunk(&codec, &index, n, c as u32)?;
            let carriers = reader.read_carrier_chunk(&codec, &index, n, c as u32)?;
            let contig = &node.contigs[usize::from(chunk.contig)];
            for j in 0..masks.bucket_count() {
                let Some((bucket, variants)) = masks.bucket(j) else {
                    continue;
                };
                let mut block = vec![format!("B\t{}\t{bucket}", escape(contig))];
                let mut order: Vec<usize> = variants.collect();
                order.sort_by(|&a, &b| java_string_cmp(masks.spec(a), masks.spec(b)));
                for i in order {
                    let mut line = format!("V\t{}", escape(masks.spec(i)));
                    variant_fields(&mut line, &masks, i, &group.patients);
                    let strings: Vec<&str> = metadata.strings(i).collect();
                    let _ = write!(line, "\t{}", strings.len());
                    for s in strings {
                        line.push('\t');
                        line.push_str(&escape(s));
                    }
                    block.push(line);
                }
                let ids = carriers
                    .bucket(j)
                    .map(|set| sorted_ids(set.iter(), &group.patients))
                    .unwrap_or_default();
                block.push(format!("C\t{}", join_ids(ids.into_iter())));
                sink.block(&format!("D\tB\t{}\t{bucket}", escape(contig)), &block)?;
            }
            for i in 0..masks.len() {
                specs.push(masks.spec(i).to_string());
            }
        }
        for entry in &node.infos {
            let block = reader.read_info_block(&codec, &index, n, entry.column)?;
            let column = &index.columns()[usize::from(entry.column)];
            sink.line(&format!(
                "I\t{}\t{}\t{}\t{}",
                escape(column),
                u8::from(entry.continuous),
                entry.key_count,
                escape(&entry.description)
            ))?;
            let mut keys = Vec::with_capacity(block.len());
            for k in 0..block.len() {
                let mut key_specs: Vec<&str> = block
                    .variants(k)?
                    .iter()
                    .map(|v| specs.get(v as usize).map_or("", String::as_str))
                    .collect();
                key_specs.sort_by(|a, b| java_string_cmp(a, b));
                key_specs.dedup();
                let mut line = format!("K\t{}\t{}", escape(block.key(k)), key_specs.len());
                for spec in key_specs {
                    line.push('\t');
                    line.push_str(&escape(spec));
                }
                keys.push(line);
            }
            sink.block(&format!("D\tK\t{}", escape(column)), &keys)?;
            if let Some(ranges) = block.compressed_index() {
                let mut lines = Vec::with_capacity(ranges.len());
                for r in 0..ranges.len() {
                    let Some((lower, upper)) = ranges.bounds(r) else {
                        continue;
                    };
                    let entries: Vec<String> = ranges
                        .entries(r)
                        .map(|(value, keys)| {
                            let keys: Vec<String> = keys.iter().map(u32::to_string).collect();
                            format!("{:08x}={}", value.to_bits(), keys.join(","))
                        })
                        .collect();
                    lines.push(format!(
                        "R\t{}\t{}\t{}",
                        bound(lower),
                        bound(upper),
                        entries.join(";")
                    ));
                }
                lines.sort();
                for line in lines {
                    sink.line(&line)?;
                }
            }
        }
        counts.nodes += 1;
        counts.variants += u64::from(node.variant_count);
    }
    counts.lines = sink.lines;
    sink.out.flush().map_err(write_error)?;
    Ok(counts)
}

/// Appends the record flag and the four masks of chunk variant `i`.
fn variant_fields(line: &mut String, masks: &MaskChunk, i: usize, patients: &[u32]) {
    let _ = write!(line, "\t{}", u8::from(masks.has_record(i)));
    for kind in MaskKind::ALL {
        line.push('\t');
        match masks.mask(i, kind) {
            None => line.push('-'),
            Some(mask) => {
                line.push_str(if mask.bitmask_form { "b:" } else { "s:" });
                line.push_str(&join_ids(
                    sorted_ids(mask.positions.iter(), patients).into_iter(),
                ));
            }
        }
    }
}

fn sorted_ids(positions: impl Iterator<Item = u32>, patients: &[u32]) -> Vec<u32> {
    let mut ids: Vec<u32> = positions.map(|p| patients[p as usize]).collect();
    ids.sort_unstable();
    ids
}

fn join_ids(ids: impl Iterator<Item = u32>) -> String {
    let mut out = String::new();
    for (i, id) in ids.enumerate() {
        if i > 0 {
            out.push(',');
        }
        let _ = write!(out, "{id}");
    }
    out
}

fn bound(bound: RangeBound) -> String {
    match bound {
        RangeBound::Unbounded => "u".into(),
        RangeBound::Open(v) => format!("o:{:08x}", v.to_bits()),
        RangeBound::Closed(v) => format!("c:{:08x}", v.to_bits()),
    }
}

/// Escapes a string for one field: backslash, tab, newline and carriage return.
pub fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            c => out.push(c),
        }
    }
    out
}

fn write_error(source: std::io::Error) -> EtlError {
    EtlError::Io {
        action: "writing",
        path: "the dump".into(),
        source,
    }
}

/// Writes lines, or in digest mode replaces a block of lines with one digest line.
struct Sink<'a> {
    out: &'a mut dyn Write,
    lines: u64,
    digest: bool,
}

impl Sink<'_> {
    fn line(&mut self, line: &str) -> Result<()> {
        self.out.write_all(line.as_bytes()).map_err(write_error)?;
        self.out.write_all(b"\n").map_err(write_error)?;
        self.lines += 1;
        Ok(())
    }

    /// Writes `lines`, or in digest mode the line `prefix`, the count of `lines` and the
    /// SHA-256 of their bytes.
    fn block(&mut self, prefix: &str, lines: &[String]) -> Result<()> {
        if !self.digest {
            for line in lines {
                self.line(line)?;
            }
            return Ok(());
        }
        let mut hasher = Sha256::new();
        for line in lines {
            hasher.update(line.as_bytes());
            hasher.update(b"\n");
        }
        let hex: String = hasher
            .finalize()
            .iter()
            .fold(String::with_capacity(64), |mut s, b| {
                let _ = write!(s, "{b:02x}");
                s
            });
        self.line(&format!("{prefix}\t{}\t{hex}", lines.len()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escape_marks_separators() {
        assert_eq!(escape("a\tb\nc\rd\\e"), "a\\tb\\nc\\rd\\\\e");
        assert_eq!(escape("plain, text"), "plain, text");
    }

    #[test]
    fn ids_join_with_commas() {
        assert_eq!(join_ids([3u32, 10, 200].into_iter()), "3,10,200");
        assert_eq!(join_ids(std::iter::empty()), "");
    }
}
