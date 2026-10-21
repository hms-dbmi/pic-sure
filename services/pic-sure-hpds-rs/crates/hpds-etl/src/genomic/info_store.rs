//! A node's INFO columns as the Java loader builds them: `InfoStore.processRecord` while the
//! VCF is read, then `InfoStore.isNumeric`, `FileBackedByteIndexedInfoStore`'s
//! `normalizeNumericStore` and its `.` rule when the column is converted.
//!
//! Variants are named by their source id, Java's spec index id: the order in which the node's
//! VCF first shows each spec.

use std::collections::{BTreeSet, HashMap};

use hpds_format::{java_string_cmp, parse_java_double};
use serde::{Deserialize, Serialize};

use super::vcf::java_split;

/// INFO columns the Java splitter never writes.
const SKIPPED_COLUMNS: [&str; 2] = ["PU", "IPU"];

/// One column of a node after conversion, as the Java store holds it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ConvertedColumn {
    /// The column key.
    pub name: String,
    /// `isNumeric` of the column's values on this node.
    pub continuous: bool,
    /// The keys, strictly ascending in Java order, each with its source ids, ascending.
    pub keys: Vec<(String, Vec<u32>)>,
}

/// Gathers the values of a node's INFO columns, record by record.
#[derive(Debug)]
pub struct InfoAccumulator {
    columns: HashMap<String, HashMap<String, Vec<u32>>>,
}

impl InfoAccumulator {
    /// Starts the columns `keys`, the INFO keys the group's annotated VCFs declare. `PU` and
    /// `IPU` are left out, as the Java splitter leaves them out.
    pub fn new<'a>(keys: impl IntoIterator<Item = &'a str>) -> Self {
        InfoAccumulator {
            columns: keys
                .into_iter()
                .filter(|k| !SKIPPED_COLUMNS.contains(k))
                .map(|k| (k.to_string(), HashMap::new()))
                .collect(),
        }
    }

    /// `InfoStore.processRecord` for every column: each token of `info` split on `[;&]`
    /// that starts with a column's key and `=` adds `source` to the value after the `=`.
    pub fn process(&mut self, source: u32, info: &str) {
        if self.columns.is_empty() {
            return;
        }
        for token in info.split([';', '&']) {
            let Some((key, value)) = token.split_once('=') else {
                continue;
            };
            if let Some(values) = self.columns.get_mut(key) {
                match values.get_mut(value) {
                    Some(ids) => ids.push(source),
                    None => {
                        values.insert(value.to_string(), vec![source]);
                    }
                }
            }
        }
    }

    /// Converts every column that received a value, as `VCFPerPatientInfoStoreToFBBIISConverter`
    /// does (it skips a column without values), in Java order of the column keys.
    pub fn finish(self) -> Vec<ConvertedColumn> {
        let mut columns: Vec<ConvertedColumn> = self
            .columns
            .into_iter()
            .filter(|(_, values)| !values.is_empty())
            .map(|(name, values)| convert_column(name, values))
            .collect();
        columns.sort_by(|a, b| java_string_cmp(&a.name, &b.name));
        columns
    }
}

/// `InfoStore.isNumeric`: the count of keys that parse as a Java double, plus for each key
/// that does not, its comma-separated parts that do, exceeds 10,000 or half the key count.
/// Java checks after every key and stops early, but the count only grows and nothing else
/// stops it (its non-numeric count never passes 1), so the order of keys does not matter.
pub fn is_numeric<'a>(keys: impl ExactSizeIterator<Item = &'a str>) -> bool {
    let size = keys.len();
    let mut numeric = 0usize;
    for key in keys {
        if parse_java_double(key).is_some() {
            numeric += 1;
        } else {
            numeric += java_split(key, |c| c == ',')
                .into_iter()
                .filter(|part| parse_java_double(part).is_some())
                .count();
        }
    }
    numeric > 10_000 || numeric > size / 2
}

/// `normalizeNumericStore`, with Java's sharing of id sets reproduced. Keys are visited in
/// Java order. A key with two or more non-empty comma parts adds its ids under each part
/// other than `.`; any other key adds them under itself; `.` is dropped. The first key to
/// add under a value lends that value its own id set, and later keys add into that set,
/// which every other value sharing it then also sees.
pub fn normalize_numeric(mut keys: Vec<(String, Vec<u32>)>) -> Vec<(String, Vec<u32>)> {
    keys.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    let mut sets: Vec<BTreeSet<u32>> = keys
        .iter()
        .map(|(_, ids)| ids.iter().copied().collect())
        .collect();
    let mut owner: HashMap<String, usize> = HashMap::new();
    for (i, (key, _)) in keys.iter().enumerate() {
        if key == "." {
            continue;
        }
        let parts = java_split(key, |c| c == ',');
        let multiple = parts.iter().filter(|p| !p.is_empty()).count() > 1;
        let values: Vec<&str> = if multiple {
            parts.into_iter().filter(|p| *p != ".").collect()
        } else {
            vec![key.as_str()]
        };
        for value in values {
            match owner.get(value) {
                None => {
                    owner.insert(value.to_string(), i);
                }
                Some(&j) if j != i => {
                    let added = sets[i].clone();
                    sets[j].extend(added);
                }
                Some(_) => {}
            }
        }
    }
    let mut out: Vec<(String, Vec<u32>)> = owner
        .into_iter()
        .map(|(value, i)| (value, sets[i].iter().copied().collect()))
        .collect();
    out.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    out
}

/// Converts one column's values into its stored keys.
fn convert_column(name: String, values: HashMap<String, Vec<u32>>) -> ConvertedColumn {
    let continuous = is_numeric(values.keys().map(String::as_str));
    let mut keys: Vec<(String, Vec<u32>)> = values
        .into_iter()
        .map(|(key, mut ids)| {
            ids.sort_unstable();
            ids.dedup();
            (key, ids)
        })
        .collect();
    if continuous {
        keys = normalize_numeric(keys);
    } else {
        keys.retain(|(key, _)| key != ".");
        keys.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    }
    ConvertedColumn {
        name,
        continuous,
        keys,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn keys(pairs: &[(&str, &[u32])]) -> Vec<(String, Vec<u32>)> {
        pairs
            .iter()
            .map(|(k, ids)| ((*k).to_string(), ids.to_vec()))
            .collect()
    }

    #[test]
    fn records_split_on_semicolon_and_ampersand() {
        let mut acc = InfoAccumulator::new(["G", "C", "PU", "F"]);
        acc.process(0, "G=A&B;C=x&C=y;FLAG;PU=1");
        acc.process(1, "G=A;F=");
        acc.process(1, "G=A");
        let columns = acc.finish();
        let names: Vec<&str> = columns.iter().map(|c| c.name.as_str()).collect();
        assert_eq!(names, ["C", "F", "G"]);
        assert_eq!(columns[0].keys, keys(&[("x", &[0]), ("y", &[0])]));
        assert_eq!(columns[1].keys, keys(&[("", &[1])]));
        assert!(!columns[1].continuous);
        assert_eq!(columns[2].keys, keys(&[("A", &[0, 1])]));
    }

    #[test]
    fn numeric_counts_parts_of_comma_keys() {
        assert!(is_numeric(["1", "2", "x"].into_iter()));
        assert!(!is_numeric(["1", "x", "y"].into_iter()));
        assert!(is_numeric(["0.1,0.2", "x"].into_iter()));
        assert!(is_numeric([".", "1", "2"].into_iter()));
        assert!(!is_numeric([".", "1"].into_iter()));
        assert!(is_numeric(
            ["NaN", "-Infinity", "1e5", "0x1p3", "1f"].into_iter()
        ));
        assert!(!is_numeric(["1", ""].into_iter()));
    }

    #[test]
    fn normalization_shares_id_sets_as_java_does() {
        let out = normalize_numeric(keys(&[
            ("1,2", &[10]),
            ("2", &[20]),
            ("1", &[30]),
            (".", &[40]),
            ("3,.", &[50]),
        ]));
        assert_eq!(
            out,
            keys(&[("1", &[10, 30]), ("2", &[10, 20]), ("3", &[50])])
        );
    }

    #[test]
    fn normalization_bleeds_ids_between_values_of_one_source_key() {
        let out = normalize_numeric(keys(&[("0.1,0.2", &[1]), ("0.2", &[2])]));
        assert_eq!(out, keys(&[("0.1", &[1, 2]), ("0.2", &[1, 2])]));
    }

    #[test]
    fn normalization_keeps_single_part_keys_whole() {
        let out = normalize_numeric(keys(&[("1,", &[1]), (",2", &[2]), ("0.5", &[3])]));
        assert_eq!(out, keys(&[(",2", &[2]), ("0.5", &[3]), ("1,", &[1])]));
    }

    #[test]
    fn normalization_keeps_empty_parts_of_multi_keys() {
        let out = normalize_numeric(keys(&[("1,,2", &[7])]));
        assert_eq!(out, keys(&[("", &[7]), ("1", &[7]), ("2", &[7])]));
    }

    #[test]
    fn categorical_columns_drop_the_dot_key() {
        let mut acc = InfoAccumulator::new(["T"]);
        acc.process(0, "T=.");
        acc.process(1, "T=a");
        let columns = acc.finish();
        assert!(!columns[0].continuous);
        assert_eq!(columns[0].keys, keys(&[("a", &[1])]));
    }
}
