//! `columnMeta.csv`, byte for byte as `LoadingStore.dumpStatsAndColumnMeta` and
//! `LowRAMLoadingStore.dumpStatsAndColumnMeta` write it through Commons CSV 1.5.

use std::io::{self, Write};

use hpds_format::ConceptMeta;

use crate::java::java_double_to_string;

/// Writes one record the way Commons CSV 1.5 `CSVPrinter.printRecord` does with
/// `CSVFormat.DEFAULT`: comma separated, `\r\n` terminated, `QuoteMode.MINIMAL`.
///
/// A field is quoted when it is empty and first in the record; when it is first and starts
/// with a character below U+0020, U+0022, U+002C, or above U+007E; when it starts with a
/// character at or below `#`; when it contains a line feed, carriage return, quote or
/// comma; or when it ends with a character at or below U+0020. Quotes inside a quoted
/// field are doubled.
///
/// # Errors
/// Any error from `out`.
pub fn write_commons_csv_record<W: Write + ?Sized, S: AsRef<str>>(
    out: &mut W,
    fields: &[S],
) -> io::Result<()> {
    for (i, field) in fields.iter().enumerate() {
        let field = field.as_ref();
        if i > 0 {
            out.write_all(b",")?;
        }
        if needs_quotes(field, i == 0) {
            out.write_all(b"\"")?;
            out.write_all(field.replace('"', "\"\"").as_bytes())?;
            out.write_all(b"\"")?;
        } else {
            out.write_all(field.as_bytes())?;
        }
    }
    out.write_all(b"\r\n")
}

fn needs_quotes(field: &str, first_in_record: bool) -> bool {
    let (Some(first), Some(last)) = (field.chars().next(), field.chars().next_back()) else {
        return first_in_record;
    };
    let first = u32::from(first);
    if first_in_record && (first < 0x20 || first == 0x22 || first == 0x2C || first > 0x7E) {
        return true;
    }
    if first <= u32::from(b'#') {
        return true;
    }
    if field.contains(['\n', '\r', '"', ',']) {
        return true;
    }
    last <= ' '
}

/// The eleven `columnMeta.csv` fields for one concept: name, width in bytes, column offset,
/// categorical, category values joined with `µ`, min, max, block offset, block length,
/// observation count, patient count. Absent min and max print as `null`, as
/// `String.valueOf` prints a null `Double`.
///
/// Java writes the javabin offset and end position in fields 8 and 9; this writes the
/// block offset and length in `pheno.blocks`. In a partitioned store the observation count
/// covers every partition and the patient count is the sum of the partitions' patient
/// counts ([`ConceptMeta::summed_patient_count`]), as Java's `SummaryColumnMeta` merges
/// the per-partition `columnMeta` a partitioned javabin store holds.
pub fn column_meta_fields(concept: &ConceptMeta) -> [String; 11] {
    let double =
        |value: Option<f64>| value.map_or_else(|| "null".to_string(), java_double_to_string);
    [
        concept.path.clone(),
        concept.width_in_bytes.to_string(),
        concept.column_offset.to_string(),
        concept.is_categorical().to_string(),
        concept
            .category_values
            .as_deref()
            .map(|values| values.join("µ"))
            .unwrap_or_default(),
        double(concept.min),
        double(concept.max),
        concept.block.offset.to_string(),
        concept.block.length.to_string(),
        concept.observation_count.to_string(),
        concept.summed_patient_count().to_string(),
    ]
}

/// Writes `columnMeta.csv` for `concepts`, which must already be in Java `String.compareTo`
/// order of their paths (the order of [`hpds_format::PhenoIndex::concepts`]). No header.
///
/// # Errors
/// Any error from `out`.
pub fn write_column_meta_csv<W: Write>(out: &mut W, concepts: &[ConceptMeta]) -> io::Result<()> {
    for concept in concepts {
        write_commons_csv_record(out, &column_meta_fields(concept))?;
    }
    Ok(())
}
