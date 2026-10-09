//! Typed results, with the JSON and text forms Java's `/query/sync` writes and the rows the
//! PATIENTS and DATAFRAME exports are built from.

use std::sync::Arc;

use hpds_format::{Block, java_double_to_string};
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
    /// True when Java answers with `application/json`; false for COUNT, which is
    /// `text/plain`.
    pub fn is_json(&self) -> bool {
        !matches!(self, SyncResult::Count(_))
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
/// value the patient has for that concept, in `by_key` order (Java's `sortedByKey`: input
/// order within a patient), joined with a tab, as `CsvWriter.writeMultiValueEntity` joins
/// them. Numeric values are written with [`java_double_to_string`], the `toString` Java
/// applies. A patient with no value, or a path with no concept, gives an empty cell. CSV
/// quoting is left to the writer.
pub struct DataframeRows {
    patients: roaring::bitmap::IntoIter,
    columns: Vec<Column>,
}

struct Column {
    block: Option<Arc<Block>>,
    cursor: usize,
}

impl DataframeRows {
    /// Rows for `cohort`, one column per entry of `blocks` (`None` for a path with no
    /// concept).
    pub(crate) fn new(cohort: RoaringBitmap, blocks: Vec<Option<Arc<Block>>>) -> Self {
        DataframeRows {
            patients: cohort.into_iter(),
            columns: blocks
                .into_iter()
                .map(|block| Column { block, cursor: 0 })
                .collect(),
        }
    }
}

impl Column {
    fn cell(&mut self, patient: u32) -> String {
        let Some(block) = self.block.as_deref() else {
            return String::new();
        };
        let mut values: Vec<String> = Vec::new();
        match block {
            Block::Categorical(b) => {
                let patients = b.patients();
                self.cursor += patients[self.cursor..].partition_point(|&p| p < patient);
                while patients.get(self.cursor) == Some(&patient) {
                    let index = b.value_indices()[self.cursor] as usize;
                    values.push(b.dictionary_value(index).unwrap_or_default().to_owned());
                    self.cursor += 1;
                }
            }
            Block::Numeric(b) => {
                let (by_key, patients) = (b.by_key(), b.patients());
                let at = |k: usize| patients[by_key[k] as usize];
                self.cursor +=
                    by_key[self.cursor..].partition_point(|&i| patients[i as usize] < patient);
                while self.cursor < by_key.len() && at(self.cursor) == patient {
                    values.push(java_double_to_string(
                        b.values()[by_key[self.cursor] as usize],
                    ));
                    self.cursor += 1;
                }
            }
        }
        values.join("\t")
    }
}

impl Iterator for DataframeRows {
    type Item = Vec<String>;

    fn next(&mut self) -> Option<Vec<String>> {
        let patient = self.patients.next()?;
        let mut row = Vec::with_capacity(self.columns.len() + 1);
        row.push(patient.to_string());
        row.extend(self.columns.iter_mut().map(|c| c.cell(patient)));
        Some(row)
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
        assert!(!SyncResult::Count(0).is_json());
    }
}
