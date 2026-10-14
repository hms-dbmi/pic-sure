//! Typed results, with the JSON and text forms Java's `/query/sync` writes and the rows the
//! PATIENTS and DATAFRAME exports are built from.

use std::sync::Arc;

use hpds_format::{Block, java_double_to_string, push_decimal_u32, push_java_double};
use roaring::RoaringBitmap;
use serde::ser::{Serialize, SerializeMap, Serializer};

/// Header of the PATIENTS export (`PatientV3Processor.getHeaderRow`).
pub const PATIENTS_HEADER: &str = "PATIENT_NUM";

/// First header of the DATAFRAME export (`MultiValueQueryV3Processor.PATIENT_ID_FIELD_NAME`).
pub const PATIENT_ID_FIELD_NAME: &str = "patient_id";

/// One count per concept path, for CROSS_COUNT and OBSERVATION_CROSS_COUNT. A count of
/// `-1` marks a concept whose block could not be loaded, as Java's catch block writes it.
///
/// Serializes as a JSON object in `entries` order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ConceptCounts {
    /// Concept path and count pairs, each path once.
    pub entries: Vec<(String, i64)>,
}

impl ConceptCounts {
    /// The count for `path`.
    pub fn get(&self, path: &str) -> Option<i64> {
        self.entries
            .iter()
            .find(|(p, _)| p == path)
            .map(|(_, c)| *c)
    }
}

impl Serialize for ConceptCounts {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(self.entries.len()))?;
        for (path, count) in &self.entries {
            map.serialize_entry(path, count)?;
        }
        map.end()
    }
}

/// CATEGORICAL_CROSS_COUNT: per concept, the number of cohort patients holding each
/// listed category. Concepts are in Java string order of their paths and categories in
/// Java string order, the order of Java's nested `TreeMap`.
///
/// Serializes as `{path: {category: count}}` in that order.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CategoricalCrossCounts {
    /// Concept path and its category counts.
    pub concepts: Vec<(String, Vec<(String, u64)>)>,
}

impl CategoricalCrossCounts {
    /// The category counts of `path`.
    pub fn get(&self, path: &str) -> Option<&[(String, u64)]> {
        self.concepts
            .iter()
            .find(|(p, _)| p == path)
            .map(|(_, c)| c.as_slice())
    }
}

impl Serialize for CategoricalCrossCounts {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(self.concepts.len()))?;
        for (path, categories) in &self.concepts {
            map.serialize_entry(path, &Pairs(categories))?;
        }
        map.end()
    }
}

/// CONTINUOUS_CROSS_COUNT: per numeric concept, the number of cohort observations holding
/// each distinct value. Concepts are in Java string order and values in Java
/// `Double.compareTo` order, the order of Java's nested `TreeMap`.
///
/// Serializes as `{path: {value: count}}` with each value written by
/// [`java_double_to_string`], which is how Jackson writes a `Double` map key (`31.0`,
/// `1.0E7`, `NaN`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ContinuousCrossCounts {
    /// Concept path and its value counts.
    pub concepts: Vec<(String, Vec<(f64, u64)>)>,
}

impl ContinuousCrossCounts {
    /// The value counts of `path`.
    pub fn get(&self, path: &str) -> Option<&[(f64, u64)]> {
        self.concepts
            .iter()
            .find(|(p, _)| p == path)
            .map(|(_, c)| c.as_slice())
    }
}

impl Serialize for ContinuousCrossCounts {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(self.concepts.len()))?;
        for (path, values) in &self.concepts {
            let keyed: Vec<(String, u64)> = values
                .iter()
                .map(|(v, c)| (java_double_to_string(*v), *c))
                .collect();
            map.serialize_entry(path, &Pairs(&keyed))?;
        }
        map.end()
    }
}

struct Pairs<'a>(&'a [(String, u64)]);

impl Serialize for Pairs<'_> {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut map = serializer.serialize_map(Some(self.0.len()))?;
        for (key, count) in self.0 {
            map.serialize_entry(key, count)?;
        }
        map.end()
    }
}

/// The answer to one of the sync result types.
#[derive(Debug, Clone, PartialEq)]
pub enum SyncResult {
    /// COUNT: the cohort size.
    Count(u64),
    /// CROSS_COUNT.
    CrossCount(ConceptCounts),
    /// CATEGORICAL_CROSS_COUNT.
    CategoricalCrossCount(CategoricalCrossCounts),
    /// CONTINUOUS_CROSS_COUNT.
    ContinuousCrossCount(ContinuousCrossCounts),
    /// OBSERVATION_CROSS_COUNT.
    ObservationCrossCount(ConceptCounts),
}

impl SyncResult {
    /// The content type Java sends: `text/plain` for COUNT, `application/json` for the
    /// cross counts.
    pub fn content_type(&self) -> &'static str {
        match self {
            SyncResult::Count(_) => "text/plain",
            _ => "application/json",
        }
    }

    /// The response body Java writes: the bare decimal count for COUNT
    /// (`String.valueOf(int)`), compact JSON for the cross counts.
    pub fn body(&self) -> String {
        let json = match self {
            SyncResult::Count(count) => return count.to_string(),
            SyncResult::CrossCount(c) | SyncResult::ObservationCrossCount(c) => {
                serde_json::to_string(c)
            }
            SyncResult::CategoricalCrossCount(c) => serde_json::to_string(c),
            SyncResult::ContinuousCrossCount(c) => serde_json::to_string(c),
        };
        json.unwrap_or_else(|e| unreachable!("string-keyed maps always serialize: {e}"))
    }
}

/// A DATAFRAME export: the header row and a streaming iterator over the data rows.
pub struct Dataframe {
    /// `patient_id` followed by every `select` path in request order, unknown and repeated
    /// paths included, as `MultiValueQueryV3Processor.getHeaderRow` builds it.
    pub header: Vec<String>,
    /// One row per cohort patient, ascending by patient id.
    pub rows: DataframeRows,
}

/// Rows of a DATAFRAME export, produced one patient at a time from blocks loaded up front.
///
/// Each row is `[patient id, cell...]` with one cell per `select` path. A cell holds every
/// value the patient has for that concept in a selected partition, joined with a tab, as
/// `CsvWriter.writeMultiValueEntity` joins them, in the block's key order: partition by
/// partition in partition name order, and within one partition in input order (Java's
/// `sortedByKey`), which is a k-way merge of the partitions on patient with ties broken by
/// partition name. Numeric values are written with [`java_double_to_string`], the
/// `toString` Java applies. A patient with no value, or a path no selected partition holds,
/// gives an empty cell. A `select` entry equal to [`PATIENT_ID_FIELD_NAME`] holds the patient
/// id instead, as `MultiValueQueryV3Processor.runQuery` writes it. CSV quoting is left to the
/// writer.
pub struct DataframeRows {
    patients: roaring::bitmap::IntoIter,
    columns: Vec<Cells>,
}

/// What one DATAFRAME column holds, as the engine hands it to [`DataframeRows`].
#[derive(Debug, Clone)]
pub(crate) enum DataframeColumn {
    /// The patient id, for a `select` entry equal to [`PATIENT_ID_FIELD_NAME`]. Java matches
    /// that header name before it looks at the dictionary, so it wins over any concept path
    /// spelled the same.
    PatientId,
    /// The values of the concept the entry names.
    Values(ColumnBlock),
    /// Nothing: a path no selected partition holds.
    Empty,
}

/// One column of [`DataframeRows`] while rows are produced.
enum Cells {
    PatientId,
    Values(Part),
    Empty,
}

/// The block one DATAFRAME column reads, and, when some of its rows lie in unselected
/// partitions and carry tags, the selected partitions as one flag per partition: a row is
/// written only when its tag's flag is set. A cohort patient outside the overlap set has
/// every row in a selected partition, so an untagged block needs no filter.
#[derive(Debug, Clone)]
pub(crate) struct ColumnBlock {
    block: Arc<Block>,
    partitions: Option<Arc<[bool]>>,
}

impl ColumnBlock {
    /// The column reading `block`, keeping only rows whose tag `partitions` selects when
    /// given.
    pub(crate) fn new(block: Arc<Block>, partitions: Option<Arc<[bool]>>) -> Self {
        ColumnBlock { block, partitions }
    }
}

/// One column's place in its block: `cursor` indexes its observations in patient order, and
/// `next` is the patient there (`u32::MAX` past the end), so a patient the block does not
/// hold costs one comparison.
struct Part {
    block: Arc<Block>,
    partitions: Option<Arc<[bool]>>,
    cursor: usize,
    next: u32,
}

impl DataframeRows {
    /// Rows for `cohort`, one column per entry of `columns`.
    pub(crate) fn new(cohort: RoaringBitmap, columns: Vec<DataframeColumn>) -> Self {
        DataframeRows {
            patients: cohort.into_iter(),
            columns: columns
                .into_iter()
                .map(|column| match column {
                    DataframeColumn::PatientId => Cells::PatientId,
                    DataframeColumn::Values(block) => Cells::Values(Part::new(block)),
                    DataframeColumn::Empty => Cells::Empty,
                })
                .collect(),
        }
    }
}

impl Cells {
    /// Appends this column's cell for `patient` to `out` and returns true when the field
    /// needs CSV quotes. A patient id never does.
    fn write(&mut self, patient: u32, out: &mut Vec<u8>) -> bool {
        match self {
            Cells::PatientId => {
                push_decimal_u32(patient, out);
                false
            }
            Cells::Values(part) => part.write_cell(patient, out),
            Cells::Empty => false,
        }
    }
}

impl Part {
    fn new(column: ColumnBlock) -> Self {
        let mut part = Part {
            block: column.block,
            partitions: column.partitions,
            cursor: 0,
            next: 0,
        };
        part.next = part.patient_at(0);
        part
    }

    /// The patient of the observation at `cursor` in patient order, `u32::MAX` past the end.
    fn patient_at(&self, cursor: usize) -> u32 {
        match self.block.as_ref() {
            Block::Categorical(b) => b.patients().get(cursor).copied(),
            Block::Numeric(b) => b.by_key().get(cursor).map(|&i| b.patients()[i as usize]),
        }
        .unwrap_or(u32::MAX)
    }

    /// Moves the cursor to the first observation of a patient at or after `patient`.
    fn seek(&mut self, patient: u32) {
        self.cursor += match self.block.as_ref() {
            Block::Categorical(b) => b.patients()[self.cursor..].partition_point(|&p| p < patient),
            Block::Numeric(b) => {
                let patients = b.patients();
                b.by_key()[self.cursor..].partition_point(|&i| patients[i as usize] < patient)
            }
        };
        self.next = self.patient_at(self.cursor);
    }

    /// Appends the patient's values for this column to `out`, joined with a tab, and
    /// returns true when one of them holds a byte that makes the CSV field need quotes
    /// (`"`, `,`, CR or LF). Only categorical values can; numbers never do.
    fn write_cell(&mut self, patient: u32, out: &mut Vec<u8>) -> bool {
        if self.next > patient {
            return false;
        }
        if self.next < patient {
            self.seek(patient);
            if self.next != patient {
                return false;
            }
        }
        let mut first = true;
        let mut separator = |out: &mut Vec<u8>| {
            if !std::mem::take(&mut first) {
                out.push(b'\t');
            }
        };
        let mut needs_quotes = false;
        let selected = self.partitions.as_deref();
        let mut cursor = self.cursor;
        match self.block.as_ref() {
            Block::Categorical(b) => {
                let patients = b.patients();
                while patients.get(cursor) == Some(&patient) {
                    let key = cursor;
                    cursor += 1;
                    if !writes(selected, || b.tag_at_key_pos(key)) {
                        continue;
                    }
                    let index = b.value_indices()[key] as usize;
                    let value = b.dictionary_value(index).unwrap_or_default().as_bytes();
                    separator(out);
                    needs_quotes |= value.iter().any(|&byte| needs_csv_quotes(byte));
                    out.extend_from_slice(value);
                }
            }
            Block::Numeric(b) => {
                let (by_key, patients) = (b.by_key(), b.patients());
                while cursor < by_key.len() && patients[by_key[cursor] as usize] == patient {
                    let position = by_key[cursor] as usize;
                    cursor += 1;
                    if !writes(selected, || b.tag_at_value_pos(position)) {
                        continue;
                    }
                    separator(out);
                    push_java_double(b.values()[position], out);
                }
            }
        }
        self.cursor = cursor;
        self.next = self.patient_at(self.cursor);
        needs_quotes
    }
}

/// True for the bytes that make FastCSV quote a field: `"`, `,`, CR and LF.
fn needs_csv_quotes(byte: u8) -> bool {
    matches!(byte, b'"' | b',' | b'\r' | b'\n')
}

/// Wraps the field that starts at `start` and runs to the end of `out` in quotes, doubling
/// every `"` inside it, as FastCSV writes a field that needs quoting.
fn quote_field(out: &mut Vec<u8>, start: usize) {
    let field = out.split_off(start);
    out.reserve(field.len() + 2);
    out.push(b'"');
    for byte in field {
        if byte == b'"' {
            out.push(b'"');
        }
        out.push(byte);
    }
    out.push(b'"');
}

/// True when a row whose tag is `tag` is written: always without a partition filter, and
/// with one only when the tag names a selected partition.
fn writes(selected: Option<&[bool]>, tag: impl FnOnce() -> Option<u16>) -> bool {
    selected.is_none_or(|selected| {
        tag().is_some_and(|tag| selected.get(usize::from(tag)).copied().unwrap_or(false))
    })
}

/// A reusable buffer holding one rendered DATAFRAME row: every field's text back to back
/// and the end offset of each, so a row costs no allocation once the buffer has grown.
#[derive(Debug, Default, Clone)]
pub struct RowBuffer {
    text: Vec<u8>,
    ends: Vec<usize>,
}

impl RowBuffer {
    /// An empty buffer.
    pub fn new() -> Self {
        Self::default()
    }

    fn clear(&mut self) {
        self.text.clear();
        self.ends.clear();
    }

    fn end_field(&mut self) {
        self.ends.push(self.text.len());
    }

    /// The fields of the row, in order: the patient id, then one per `select` path.
    pub fn fields(&self) -> impl Iterator<Item = &str> {
        let starts = std::iter::once(0).chain(self.ends.iter().copied());
        starts.zip(self.ends.iter().copied()).map(|(start, end)| {
            std::str::from_utf8(&self.text[start..end])
                .expect("fields hold only text copied from str values and ASCII digits")
        })
    }
}

impl DataframeRows {
    /// Renders the next row into `row`, replacing its previous content, and returns false
    /// when no patients remain. No per-row or per-cell allocation once `row` has grown.
    pub fn next_into(&mut self, row: &mut RowBuffer) -> bool {
        let Some(patient) = self.patients.next() else {
            return false;
        };
        row.clear();
        push_decimal_u32(patient, &mut row.text);
        row.end_field();
        for column in &mut self.columns {
            column.write(patient, &mut row.text);
            row.end_field();
        }
        true
    }

    /// Appends the next row to `out` as one CSV line and returns false when no patients
    /// remain. The line is what `write_csv_row` writes for the fields [`Self::next_into`]
    /// renders: fields joined with `,`, a field quoted only when it holds `"`, `,`, CR or LF
    /// with `"` doubled inside, and `\n` at the end. Each value is written once, straight into
    /// `out`; only a field that needs quotes is copied again.
    pub fn next_csv_into(&mut self, out: &mut Vec<u8>) -> bool {
        let Some(patient) = self.patients.next() else {
            return false;
        };
        push_decimal_u32(patient, out);
        for column in &mut self.columns {
            out.push(b',');
            let start = out.len();
            if column.write(patient, out) {
                quote_field(out, start);
            }
        }
        out.push(b'\n');
        true
    }
}

impl Iterator for DataframeRows {
    type Item = Vec<String>;

    fn next(&mut self) -> Option<Vec<String>> {
        let mut row = RowBuffer::new();
        self.next_into(&mut row)
            .then(|| row.fields().map(str::to_owned).collect())
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        self.patients.size_hint()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bodies_match_java_shapes() {
        assert_eq!(SyncResult::Count(562).body(), "562");
        let continuous = SyncResult::ContinuousCrossCount(ContinuousCrossCounts {
            concepts: vec![(
                "\\age\\".into(),
                vec![(-0.0, 1), (31.0, 2), (1e7, 3), (f64::NAN, 4)],
            )],
        });
        assert_eq!(
            continuous.body(),
            r#"{"\\age\\":{"-0.0":1,"31.0":2,"1.0E7":3,"NaN":4}}"#
        );
        let cross = SyncResult::CrossCount(ConceptCounts {
            entries: vec![("\\b\\".into(), 3), ("\\a\\".into(), -1)],
        });
        assert_eq!(cross.body(), r#"{"\\b\\":3,"\\a\\":-1}"#);
        assert_eq!(SyncResult::Count(0).content_type(), "text/plain");
        assert_eq!(cross.content_type(), "application/json");
    }
}
