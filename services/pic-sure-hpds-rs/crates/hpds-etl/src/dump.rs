//! `hpds-etl dump`: the store as canonical CSV, in the layout and order `DumpSourceCSV`
//! writes a javabin store, so the two dumps can be compared as text.
//!
//! Records follow `CSVFormat.DEFAULT` (see
//! [`write_commons_csv_record`](crate::column_meta_csv::write_commons_csv_record)). Concepts
//! come in Java `String.compareTo` order of their paths, and within a concept the
//! observations come in `sortedByKey` order: by patient, then in stored order. The fields
//! are the patient number, the concept path, the value printed by `Double.toString` for a
//! numeric concept or the text value for a categorical one (the other column empty), and
//! the timestamp in epoch milliseconds, empty when there is none.
//!
//! A partitioned store is dumped one file per partition, each holding the rows of that
//! partition ([`split_by_partition`]), or those files concatenated under one header, as
//! the partitions of a format 1 store were dumped one store at a time.
//!
//! Two differences from a `DumpSourceCSV` dump remain. Java's header line is the
//! `toString` of a `String[]` (`[Ljava.lang.String;@<hash>`) because `ImmutableList.of`
//! receives the array as a single element; this writes the intended header. And Java prints
//! a `Long.MIN_VALUE` timestamp as `-9223372036854775808`, where the store cannot tell it
//! from no timestamp and this prints an empty field.

use std::fs::File;
use std::io::{BufReader, BufWriter, Write};
use std::path::{Path, PathBuf};

use hpds_format::{Block, ConceptMeta, FormatError, NO_TIMESTAMP};
use roaring::RoaringBitmap;
use serde::Serialize;

use crate::cleanup::TemporaryDir;
use crate::column_meta_csv::write_commons_csv_record;
use crate::error::{EtlError, Result};
use crate::java::java_double_to_string;
use crate::private_fs;
use crate::store::OpenStore;

/// The header record of a dump.
pub const DUMP_HEADER: [&str; 5] = [
    "PATIENT_NUM",
    "CONCEPT_PATH",
    "NUMERIC_VALUE",
    "TEXT_VALUE",
    "TIMESTAMP",
];

/// What a dump wrote.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize)]
pub struct DumpCounts {
    /// Concepts in the store, or held by the partition.
    pub concepts: u64,
    /// Observation rows written.
    pub observations: u64,
}

/// Writes every observation in `store` to `out` as canonical CSV with a header. For a
/// partitioned store that is every partition's rows, each concept's in key order (by
/// patient, then partition name, then each partition's order).
///
/// # Errors
/// [`EtlError::Format`] when a block fails to read or verify, or a categorical observation
/// refers to a dictionary entry the block does not have, and [`EtlError::Io`] when writing
/// to `out` fails.
pub fn dump<W: Write + ?Sized>(store: &OpenStore, out: &mut W) -> Result<DumpCounts> {
    write_commons_csv_record(out, &DUMP_HEADER).map_err(write_error)?;
    let mut counts = DumpCounts::default();
    let mut fields: [String; 5] = Default::default();
    for concept in store.index.concepts() {
        counts.concepts += 1;
        let block = store.read_block(concept)?;
        for_each_row(concept, &block, &mut fields, |_, fields| {
            write_commons_csv_record(out, fields).map_err(write_error)?;
            counts.observations += 1;
            Ok(())
        })?;
    }
    out.flush().map_err(write_error)?;
    Ok(counts)
}

/// One partition's rows of a partitioned store, written by [`split_by_partition`] to a file
/// without a header.
#[derive(Debug)]
pub struct PartitionRows {
    /// The partition name.
    pub name: String,
    /// The headerless file holding its rows.
    pub path: PathBuf,
    /// What it holds.
    pub counts: DumpCounts,
}

/// The rows of a partitioned store split by partition, in a temporary directory removed
/// when this is dropped (and on SIGTERM or SIGINT).
pub struct SplitRows {
    _dir: TemporaryDir,
    /// One entry per partition, in the index's partition order (Java string order of the
    /// names).
    pub partitions: Vec<PartitionRows>,
}

/// Reads every block of the partitioned `store` once and writes each row, as a canonical
/// CSV record, to the file of the partition it belongs to: its tag in a tagged block, the
/// partition its patient routes to otherwise. Each partition's file holds, concept by
/// concept in Java string order of the paths, the rows of that partition in key order, which
/// is the order a store of that partition alone would dump them in. The files go into a new
/// temporary directory, mode `0700`, inside `parent`.
///
/// # Errors
/// [`EtlError::Usage`] for an unpartitioned store; the errors of [`dump`]; I/O errors
/// creating or writing the files.
pub fn split_by_partition(store: &OpenStore, parent: &Path) -> Result<SplitRows> {
    let table = store.index.partition_table();
    if table.is_empty() {
        return Err(EtlError::Usage(
            "the store is unpartitioned; it has no partitions to split its rows into".into(),
        ));
    }
    let dir = TemporaryDir::create(parent, "hpds-etl-dump")?;
    let mut partitions = Vec::with_capacity(table.partitions.len());
    let mut writers = Vec::with_capacity(table.partitions.len());
    for (i, partition) in table.partitions.iter().enumerate() {
        let path = dir.path().join(format!("{i}.rows"));
        let file = private_fs::create_file(&path).map_err(EtlError::io("creating", &path))?;
        writers.push(BufWriter::with_capacity(1 << 20, file));
        partitions.push(PartitionRows {
            name: partition.name.clone(),
            path,
            counts: DumpCounts::default(),
        });
    }
    let mut fields: [String; 5] = Default::default();
    for concept in store.index.concepts() {
        for holder in &concept.holders {
            partitions[usize::from(holder.partition)].counts.concepts += 1;
        }
        let block = store.read_block(concept)?;
        for_each_row(concept, &block, &mut fields, |position, fields| {
            let partition =
                row_partition(&block, position, &store.membership).ok_or_else(|| {
                    EtlError::Format(FormatError::Corrupt {
                        what: "block partitions",
                        detail: format!("{:?}: a row belongs to no partition", concept.path),
                    })
                })?;
            let slot = usize::from(partition);
            let path = &partitions[slot].path;
            write_commons_csv_record(&mut writers[slot], fields)
                .map_err(EtlError::io("writing", path))?;
            partitions[slot].counts.observations += 1;
            Ok(())
        })?;
    }
    for (writer, partition) in writers.iter_mut().zip(&partitions) {
        writer
            .flush()
            .map_err(EtlError::io("writing", &partition.path))?;
    }
    Ok(SplitRows {
        _dir: dir,
        partitions,
    })
}

/// Writes the dump header, then the rows of each of `partitions` in order, to `out`: one
/// partition's dump, or with several the partitions' dumps concatenated under one header.
///
/// # Errors
/// I/O errors reading the row files or writing `out`.
pub fn write_partition_dumps<W: Write + ?Sized>(
    partitions: &[&PartitionRows],
    out: &mut W,
) -> Result<DumpCounts> {
    write_commons_csv_record(out, &DUMP_HEADER).map_err(write_error)?;
    let mut counts = DumpCounts::default();
    for partition in partitions {
        let file = File::open(&partition.path).map_err(EtlError::io("reading", &partition.path))?;
        std::io::copy(&mut BufReader::with_capacity(1 << 20, file), out)
            .map_err(EtlError::io("copying", &partition.path))?;
        counts.concepts += partition.counts.concepts;
        counts.observations += partition.counts.observations;
    }
    out.flush().map_err(write_error)?;
    Ok(counts)
}

fn write_error(e: std::io::Error) -> EtlError {
    EtlError::io("writing", "the dump")(e)
}

/// The partition of the row at `position` (a value position in a numeric block, a key
/// position in a categorical one).
fn row_partition(
    block: &Block,
    position: usize,
    membership: &hpds_format::Membership,
) -> Option<u16> {
    let (patient, tag) = match block {
        Block::Numeric(b) => (b.patients()[position], b.tag_at_value_pos(position)),
        Block::Categorical(b) => (b.patients()[position], b.tag_at_key_pos(position)),
    };
    if block.partitions().is_tagged() {
        tag
    } else {
        membership.partition_of(patient)
    }
}

/// Calls `row` with the position and the five dump fields of every row of `concept`'s
/// `block`, in key order.
fn for_each_row(
    concept: &ConceptMeta,
    block: &Block,
    fields: &mut [String; 5],
    mut row: impl FnMut(usize, &[String; 5]) -> Result<()>,
) -> Result<()> {
    fields[1].clone_from(&concept.path);
    let timestamp = |t: i64| {
        if t == NO_TIMESTAMP {
            String::new()
        } else {
            t.to_string()
        }
    };
    match block {
        Block::Numeric(block) => {
            fields[3].clear();
            for &position in block.by_key() {
                let position = position as usize;
                fields[0] = block.patients()[position].to_string();
                fields[2] = java_double_to_string(block.values()[position]);
                fields[4] = timestamp(block.timestamp_at_value_pos(position));
                row(position, fields)?;
            }
        }
        Block::Categorical(block) => {
            fields[2].clear();
            for position in 0..block.len() {
                fields[0] = block.patients()[position].to_string();
                let value = block.value_indices()[position] as usize;
                let text = block.dictionary_value(value).ok_or_else(|| {
                    EtlError::Format(FormatError::Corrupt {
                        what: "categorical block",
                        detail: format!(
                            "concept {:?}: observation {position} refers to dictionary entry {value}, past the end of the dictionary",
                            concept.path
                        ),
                    })
                })?;
                fields[3] = text.to_string();
                fields[4] = timestamp(block.timestamp_at_key_pos(position));
                row(position, fields)?;
            }
        }
    }
    Ok(())
}

/// Writes `patients` to `out`, one decimal ID per line in ascending order, the layout of
/// the migration export's `patients.txt`.
///
/// # Errors
/// [`EtlError::Io`] when writing to `out` fails.
pub fn dump_patients<W: Write + ?Sized>(patients: &RoaringBitmap, out: &mut W) -> Result<u64> {
    let write_error = |e| EtlError::io("writing", "the patient list")(e);
    for patient in patients {
        writeln!(out, "{patient}").map_err(write_error)?;
    }
    out.flush().map_err(write_error)?;
    Ok(patients.len())
}
