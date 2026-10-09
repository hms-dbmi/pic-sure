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
//! Two differences from a `DumpSourceCSV` dump remain. Java's header line is the
//! `toString` of a `String[]` (`[Ljava.lang.String;@<hash>`) because `ImmutableList.of`
//! receives the array as a single element; this writes the intended header. And Java prints
//! a `Long.MIN_VALUE` timestamp as `-9223372036854775808`, where the store cannot tell it
//! from no timestamp and this prints an empty field.

use std::io::Write;

use hpds_format::{Block, FormatError, NO_TIMESTAMP};
use roaring::RoaringBitmap;
use serde::Serialize;

use crate::column_meta_csv::write_commons_csv_record;
use crate::error::{EtlError, Result};
use crate::java::java_double_to_string;
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
    /// Concepts in the store.
    pub concepts: u64,
    /// Observation rows written.
    pub observations: u64,
}

/// Writes every observation in `store` to `out` as canonical CSV with a header.
///
/// # Errors
/// [`EtlError::Format`] when a block fails to read or verify, or a categorical observation
/// refers to a dictionary entry the block does not have, and [`EtlError::Io`] when writing
/// to `out` fails.
pub fn dump<W: Write + ?Sized>(store: &OpenStore, out: &mut W) -> Result<DumpCounts> {
    dump_merged(std::slice::from_ref(store), out)
}

/// Writes every observation of every store in `stores`, in that order, to `out` as one
/// canonical CSV with a single header: the per-store dumps of [`dump`] concatenated without
/// their repeated headers. Used for a partitioned store, its partitions in name order.
///
/// # Errors
/// As [`dump`].
pub fn dump_merged<W: Write + ?Sized>(stores: &[OpenStore], out: &mut W) -> Result<DumpCounts> {
    write_commons_csv_record(out, &DUMP_HEADER).map_err(write_error)?;
    let mut counts = DumpCounts::default();
    for store in stores {
        write_observations(store, out, &mut counts)?;
    }
    out.flush().map_err(write_error)?;
    Ok(counts)
}

fn write_error(e: std::io::Error) -> EtlError {
    EtlError::io("writing", "the dump")(e)
}

fn write_observations<W: Write + ?Sized>(
    store: &OpenStore,
    out: &mut W,
    counts: &mut DumpCounts,
) -> Result<()> {
    let mut fields: [String; 5] = Default::default();
    for concept in store.index.concepts() {
        counts.concepts += 1;
        fields[1].clone_from(&concept.path);
        let timestamp = |t: i64| {
            if t == NO_TIMESTAMP {
                String::new()
            } else {
                t.to_string()
            }
        };
        match store.reader.read_block(&store.codec, concept)? {
            Block::Numeric(block) => {
                fields[3].clear();
                for &position in block.by_key() {
                    let position = position as usize;
                    fields[0] = block.patients()[position].to_string();
                    fields[2] = java_double_to_string(block.values()[position]);
                    fields[4] = timestamp(block.timestamp_at_value_pos(position));
                    write_commons_csv_record(out, &fields).map_err(write_error)?;
                    counts.observations += 1;
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
                    write_commons_csv_record(out, &fields).map_err(write_error)?;
                    counts.observations += 1;
                }
            }
        }
    }
    Ok(())
}

/// Writes the store's patient ID set to `out`, one decimal ID per line in ascending order,
/// the layout of the migration export's `patients.txt`.
///
/// # Errors
/// [`EtlError::Io`] when writing to `out` fails.
pub fn dump_patients<W: Write + ?Sized>(store: &OpenStore, out: &mut W) -> Result<u64> {
    dump_patients_merged(std::slice::from_ref(store), out)
}

/// Writes the union of the patient ID sets of `stores` to `out`, one decimal ID per line
/// in ascending order, each ID once: the patients a query over every partition can see.
///
/// # Errors
/// [`EtlError::Io`] when writing to `out` fails.
pub fn dump_patients_merged<W: Write + ?Sized>(stores: &[OpenStore], out: &mut W) -> Result<u64> {
    let write_error = |e| EtlError::io("writing", "the patient list")(e);
    let mut patients = RoaringBitmap::new();
    for store in stores {
        patients |= store.index.patients();
    }
    for patient in &patients {
        writeln!(out, "{patient}").map_err(write_error)?;
    }
    out.flush().map_err(write_error)?;
    Ok(patients.len())
}
