use std::path::Path;

use crate::{BenchError, Result};

/// Separator Java uses to join category values inside one `columnMeta.csv` cell.
pub const CATEGORY_SEPARATOR: char = '\u{00B5}';

/// One concept row of a `columnMeta.csv` file.
#[derive(Debug, Clone, PartialEq)]
pub struct ColumnMeta {
    /// Concept path.
    pub name: String,
    /// Whether the concept holds categorical values.
    pub categorical: bool,
    /// Category values, empty for numeric concepts.
    pub categories: Vec<String>,
    /// Smallest numeric value, if the concept is numeric and finite.
    pub min: Option<f64>,
    /// Largest numeric value, if the concept is numeric and finite.
    pub max: Option<f64>,
    /// Number of observations.
    pub observation_count: u64,
    /// Number of distinct patients with an observation.
    pub patient_count: u64,
}

impl ColumnMeta {
    /// Whether the concept path looks like a genomic variant spec or a non-phenotype path.
    pub fn is_genomic(&self) -> bool {
        is_genomic_path(&self.name)
    }
}

/// Whether a concept path is a variant spec (`chr21,5032061,A,G`) or lacks the leading backslash
/// every phenotype path has.
pub fn is_genomic_path(path: &str) -> bool {
    !path.starts_with('\\')
}

/// Reads a headerless 11-column `columnMeta.csv`.
///
/// Columns: name, width, offset, categorical, category values joined by `µ`, min, max,
/// observations offset, observations length, observation count, patient count.
pub fn read_column_meta(path: &Path) -> Result<Vec<ColumnMeta>> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_path(path)
        .map_err(|e| BenchError::ColumnMeta {
            path: path.display().to_string(),
            line: 0,
            message: e.to_string(),
        })?;
    let mut out = Vec::new();
    for (index, record) in reader.records().enumerate() {
        let line = index + 1;
        let fail = |message: String| BenchError::ColumnMeta {
            path: path.display().to_string(),
            line,
            message,
        };
        let record = record.map_err(|e| fail(e.to_string()))?;
        if record.len() < 11 {
            return Err(fail(format!("expected 11 columns, found {}", record.len())));
        }
        let field = |i: usize| record.get(i).unwrap_or_default();
        let categorical = field(3).eq_ignore_ascii_case("true");
        let categories = if categorical && !field(4).is_empty() {
            field(4)
                .split(CATEGORY_SEPARATOR)
                .map(str::to_owned)
                .collect()
        } else {
            Vec::new()
        };
        let finite = |text: &str| text.parse::<f64>().ok().filter(|v| v.is_finite());
        let count = |i: usize| {
            field(i)
                .parse::<u64>()
                .map_err(|e| fail(format!("column {i}: {e}")))
        };
        out.push(ColumnMeta {
            name: field(0).to_owned(),
            categorical,
            categories,
            min: if categorical { None } else { finite(field(5)) },
            max: if categorical { None } else { finite(field(6)) },
            observation_count: count(9)?,
            patient_count: count(10)?,
        });
    }
    Ok(out)
}

/// Merges per-partition column metadata into one row per concept, as the Java service's
/// `SummaryColumnMeta` does: the first partition's type, category values unioned (sorted),
/// the smallest min and largest max, and observation and patient counts summed. Rows come
/// out in path order.
pub fn merge_column_meta(sets: Vec<Vec<ColumnMeta>>) -> Vec<ColumnMeta> {
    let mut merged: std::collections::BTreeMap<String, ColumnMeta> =
        std::collections::BTreeMap::new();
    for column in sets.into_iter().flatten() {
        match merged.get_mut(&column.name) {
            None => {
                merged.insert(column.name.clone(), column);
            }
            Some(into) => {
                for category in column.categories {
                    if !into.categories.contains(&category) {
                        into.categories.push(category);
                    }
                }
                into.min = match (into.min, column.min) {
                    (Some(a), Some(b)) => Some(a.min(b)),
                    (a, b) => a.or(b),
                };
                into.max = match (into.max, column.max) {
                    (Some(a), Some(b)) => Some(a.max(b)),
                    (a, b) => a.or(b),
                };
                into.observation_count += column.observation_count;
                into.patient_count += column.patient_count;
            }
        }
    }
    merged
        .into_values()
        .map(|mut column| {
            column.categories.sort();
            column
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_categorical_numeric_and_quoted_rows() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("columnMeta.csv");
        std::fs::write(
            &file,
            "\\a\\SEX\\,6,0,true,female\u{00B5}male,null,null,0,10,5,5\n\
             \\a\\AGE\\,8,0,false,,31.0,62.0,10,20,7,6\n\
             \\a\\COLL\\,9,0,true,\"x, y\u{00B5}z\",null,null,20,30,2,2\n\
             \\a\\EMPTY\\,8,0,false,,Infinity,-Infinity,30,30,0,0\n",
        )
        .unwrap();
        let columns = read_column_meta(&file).unwrap();
        assert_eq!(columns.len(), 4);
        assert_eq!(columns[0].categories, vec!["female", "male"]);
        assert_eq!(columns[1].min, Some(31.0));
        assert_eq!(columns[1].max, Some(62.0));
        assert_eq!(columns[1].patient_count, 6);
        assert_eq!(columns[2].categories, vec!["x, y", "z"]);
        assert_eq!(columns[3].min, None);
    }

    #[test]
    fn partitions_merge_like_summary_column_meta() {
        let column =
            |name: &str, categories: &[&str], range: Option<(f64, f64)>, patients| ColumnMeta {
                name: name.into(),
                categorical: range.is_none(),
                categories: categories.iter().map(|s| (*s).to_owned()).collect(),
                min: range.map(|r| r.0),
                max: range.map(|r| r.1),
                observation_count: patients,
                patient_count: patients,
            };
        let merged = merge_column_meta(vec![
            vec![
                column("\\b\\", &["y", "x"], None, 2),
                column("\\a\\", &[], Some((1.0, 5.0)), 3),
            ],
            vec![
                column("\\b\\", &["z", "x"], None, 4),
                column("\\a\\", &[], Some((-2.0, 4.0)), 1),
                column("\\c\\", &["q"], None, 1),
            ],
        ]);
        let names: Vec<&str> = merged.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["\\a\\", "\\b\\", "\\c\\"]);
        assert_eq!((merged[0].min, merged[0].max), (Some(-2.0), Some(5.0)));
        assert_eq!(merged[0].patient_count, 4);
        assert_eq!(merged[1].categories, ["x", "y", "z"]);
        assert_eq!(merged[1].patient_count, 6);
    }

    #[test]
    fn short_rows_are_errors() {
        let dir = tempfile::tempdir().unwrap();
        let file = dir.path().join("columnMeta.csv");
        std::fs::write(&file, "\\a\\,1,2\n").unwrap();
        assert!(read_column_meta(&file).is_err());
    }

    #[test]
    fn genomic_paths_are_detected() {
        assert!(is_genomic_path("chr21,5032061,A,G"));
        assert!(!is_genomic_path("\\open\\data\\SEX\\"));
    }
}
