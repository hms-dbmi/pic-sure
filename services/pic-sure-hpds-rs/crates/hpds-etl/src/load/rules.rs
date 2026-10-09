//! The per-row rules of the two Java phenotype loaders: how a row's value is classified,
//! how its timestamp and patient number are read, and how the CSV config prefixes paths.

use std::collections::HashMap;
use std::path::Path;

use hpds_format::{java_trim, parse_java_double};
use serde::Deserialize;

use crate::error::{EtlError, Result};
use crate::java::{is_log4j_blank, parse_java_int, parse_java_long};

/// Which Java loader `hpds-etl load` reproduces.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize)]
#[serde(rename_all = "kebab-case")]
pub enum Mode {
    /// `CSVLoaderNewSearch` with `LoadingStore`: one `allConcepts.csv`, rows in file order,
    /// a row is text when `NUMERIC_VALUE` is empty, never rolls up, and looks up its CSV
    /// config under the name `allConcepts`.
    NewSearch,
    /// `SequentialLoader` on CSV input, which hands every all-CSV input set to
    /// `LowRAMMultiCSVLoader`, `LowRAMCSVProcessor` and `LowRAMLoadingStore`: one or more
    /// files, rows ordered by chunk and then by raw concept path within a chunk, a row is
    /// text when `NUMERIC_VALUE` is blank, rolls up by default, and looks up its CSV config
    /// by file name.
    Sequential,
}

/// One entry of the CSV config file (`config.json`), as `CSVConfig` maps it.
#[derive(Clone, Debug, Default, PartialEq, Eq, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct CsvConfig {
    /// Name used as the root node when `dataset_name_as_root_node` is set. Java prints a
    /// missing name as `null`.
    #[serde(default)]
    pub dataset_name: Option<String>,
    /// Prefix every concept path with `\<dataset_name>`.
    #[serde(default, deserialize_with = "null_as_false")]
    pub dataset_name_as_root_node: bool,
}

fn null_as_false<'de, D: serde::Deserializer<'de>>(
    deserializer: D,
) -> std::result::Result<bool, D::Error> {
    Ok(Option::<bool>::deserialize(deserializer)?.unwrap_or(false))
}

/// The CSV config file: input file name, with or without `.csv`, to [`CsvConfig`].
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ConfigMap {
    entries: HashMap<String, CsvConfig>,
}

impl ConfigMap {
    /// Reads a config file, a JSON object from file name to [`CsvConfig`]. A `.csv` suffix
    /// on a key is removed, as `ConfigLoader` does.
    ///
    /// # Errors
    /// [`EtlError::Config`] when the file cannot be read or is not such an object.
    pub fn load(path: &Path) -> Result<Self> {
        let config_error = |detail: String| EtlError::Config {
            path: path.to_path_buf(),
            detail,
        };
        let text = std::fs::read_to_string(path).map_err(|e| config_error(e.to_string()))?;
        let raw: HashMap<String, CsvConfig> =
            serde_json::from_str(&text).map_err(|e| config_error(e.to_string()))?;
        Ok(Self::from_entries(raw))
    }

    /// Builds a map from entries, removing a `.csv` suffix from each key.
    pub fn from_entries(entries: impl IntoIterator<Item = (String, CsvConfig)>) -> Self {
        ConfigMap {
            entries: entries
                .into_iter()
                .map(|(key, config)| match key.strip_suffix(".csv") {
                    Some(stem) => (stem.to_string(), config),
                    None => (key, config),
                })
                .collect(),
        }
    }

    /// The config for `name`, after removing a `.csv` or `.sql` suffix, as
    /// `ConfigLoader.getConfigFor` looks it up.
    pub fn get(&self, name: &str) -> Option<&CsvConfig> {
        let key = name
            .strip_suffix(".csv")
            .or_else(|| name.strip_suffix(".sql"))
            .unwrap_or(name);
        self.entries.get(key)
    }
}

/// Prefixes `path` with `\<dataset_name>` when `config` asks for it, adding a leading `\` to
/// `path` first when it lacks one.
pub fn apply_dataset_root(path: String, config: Option<&CsvConfig>) -> String {
    match config {
        Some(config) if config.dataset_name_as_root_node => {
            let name = config.dataset_name.as_deref().unwrap_or("null");
            if path.starts_with('\\') {
                format!("\\{name}{path}")
            } else {
                format!("\\{name}\\{path}")
            }
        }
        _ => path,
    }
}

/// What became of a row's value.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ValueStatus {
    /// A value the loader stores.
    Present,
    /// The value is empty after trimming; the row is dropped.
    Blank,
    /// A numeric value `Double.parseDouble` rejects; the row is dropped.
    Unparseable,
}

/// A row's value as one loader reads it.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ClassifiedValue<'a> {
    /// True for a text (categorical) row, false for a numeric one. The first row of a
    /// concept fixes the concept's type from this, whatever its status.
    pub text: bool,
    /// Whether the value is stored or why it is dropped.
    pub status: ValueStatus,
    /// The parsed number, for a present numeric value.
    pub number: f64,
    /// The trimmed text, for a present text value.
    pub value: &'a str,
}

/// Classifies a row's value from its `NUMERIC_VALUE` and `TEXT_VALUE` fields.
///
/// New-search: the row is text when `NUMERIC_VALUE` is empty; the value is the text or
/// the numeric field, blank when empty after `String.trim`.
///
/// Sequential: the row is text when `NUMERIC_VALUE` is blank by `Character.isWhitespace`.
/// A numeric value is trimmed and parsed, and is unparseable even when trimming empties
/// it. A text value is trimmed and is blank when that empties it.
pub fn classify_value<'a>(mode: Mode, numeric: &'a str, text: &'a str) -> ClassifiedValue<'a> {
    let is_text = match mode {
        Mode::NewSearch => numeric.is_empty(),
        Mode::Sequential => is_log4j_blank(numeric),
    };
    let raw = if is_text { text } else { numeric };
    let trimmed = java_trim(raw);
    let mut out = ClassifiedValue {
        text: is_text,
        status: ValueStatus::Present,
        number: 0.0,
        value: "",
    };
    if is_text {
        if trimmed.is_empty() {
            out.status = ValueStatus::Blank;
        } else {
            out.value = trimmed;
        }
    } else if mode == Mode::NewSearch && trimmed.is_empty() {
        out.status = ValueStatus::Blank;
    } else {
        match parse_java_double(trimmed) {
            Some(number) => out.number = number,
            None => out.status = ValueStatus::Unparseable,
        }
    }
    out
}

/// A row's timestamp field as `Long.parseLong` reads it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Timestamp {
    /// The row has no fifth field, or it is empty.
    Absent,
    /// Epoch milliseconds.
    Millis(i64),
    /// The field does not parse. `LowRAMCSVProcessor` treats it as no timestamp;
    /// `CSVLoaderNewSearch` aborts the load. The Rust loader treats it as no timestamp in
    /// both modes and counts it.
    Unparseable,
}

/// Reads a row's timestamp field, epoch milliseconds parsed with `Long.parseLong`.
pub fn parse_timestamp(field: Option<&str>) -> Timestamp {
    match field {
        None | Some("") => Timestamp::Absent,
        Some(field) => parse_java_long(field).map_or(Timestamp::Unparseable, Timestamp::Millis),
    }
}

/// Why a patient number cannot be stored.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PatientProblem {
    /// `Integer.parseInt` rejects it; Java aborts the load.
    NotAnInteger,
    /// It is negative; the store keeps patients as unsigned 32-bit integers.
    Negative,
}

/// Reads a patient number with `Integer.parseInt`, untrimmed, refusing negative values.
pub fn parse_patient(field: &str) -> std::result::Result<u32, PatientProblem> {
    let value = parse_java_int(field).ok_or(PatientProblem::NotAnInteger)?;
    u32::try_from(value).map_err(|_| PatientProblem::Negative)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn classify(mode: Mode, numeric: &str, text: &str) -> (bool, ValueStatus) {
        let c = classify_value(mode, numeric, text);
        (c.text, c.status)
    }

    #[test]
    fn empty_numeric_field_is_text_in_both_modes() {
        for mode in [Mode::NewSearch, Mode::Sequential] {
            assert_eq!(classify(mode, "", "x"), (true, ValueStatus::Present));
            assert_eq!(classify(mode, "", "  "), (true, ValueStatus::Blank));
            assert_eq!(classify(mode, "", ""), (true, ValueStatus::Blank));
        }
    }

    #[test]
    fn whitespace_numeric_field_differs_between_modes() {
        assert_eq!(
            classify(Mode::NewSearch, "   ", "text_wins"),
            (false, ValueStatus::Blank)
        );
        assert_eq!(
            classify(Mode::Sequential, "   ", "text_wins"),
            (true, ValueStatus::Present)
        );
        assert_eq!(
            classify(Mode::NewSearch, "\u{2003}", "t"),
            (false, ValueStatus::Unparseable)
        );
        assert_eq!(
            classify(Mode::Sequential, "\u{2003}", "t"),
            (true, ValueStatus::Present)
        );
        assert_eq!(
            classify(Mode::NewSearch, "\u{1}", "t"),
            (false, ValueStatus::Blank)
        );
        assert_eq!(
            classify(Mode::Sequential, "\u{1}", "t"),
            (false, ValueStatus::Unparseable)
        );
        assert_eq!(
            classify(Mode::NewSearch, "\u{A0}", "t"),
            (false, ValueStatus::Unparseable)
        );
        assert_eq!(
            classify(Mode::Sequential, "\u{A0}", "t"),
            (false, ValueStatus::Unparseable)
        );
    }

    #[test]
    fn numbers_parse_with_java_grammar_after_trimming() {
        for mode in [Mode::NewSearch, Mode::Sequential] {
            let c = classify_value(mode, "  42  ", "");
            assert_eq!(
                (c.text, c.status, c.number),
                (false, ValueStatus::Present, 42.0)
            );
            assert_eq!(classify_value(mode, "0x1.8p1", "").number, 3.0);
            assert_eq!(classify_value(mode, "1.5d", "").number, 1.5);
            assert!(classify_value(mode, "NaN", "").number.is_nan());
            for bad in ["abc", "1,5", "--1", "1_000", "nan", "0x10", "infinity"] {
                assert_eq!(
                    classify(mode, bad, ""),
                    (false, ValueStatus::Unparseable),
                    "{bad}"
                );
            }
        }
    }

    #[test]
    fn text_values_are_trimmed_with_string_trim() {
        for mode in [Mode::NewSearch, Mode::Sequential] {
            assert_eq!(
                classify_value(mode, "", "\t inner space \t").value,
                "inner space"
            );
            assert_eq!(
                classify_value(mode, "", "\u{A0}x\u{A0}").value,
                "\u{A0}x\u{A0}"
            );
        }
    }

    #[test]
    fn timestamps_parse_as_java_longs() {
        assert_eq!(parse_timestamp(None), Timestamp::Absent);
        assert_eq!(parse_timestamp(Some("")), Timestamp::Absent);
        assert_eq!(
            parse_timestamp(Some("1700000000000")),
            Timestamp::Millis(1_700_000_000_000)
        );
        assert_eq!(parse_timestamp(Some("-5")), Timestamp::Millis(-5));
        for bad in ["abc", "1.5", " 1000", "12345678901234567890"] {
            assert_eq!(parse_timestamp(Some(bad)), Timestamp::Unparseable, "{bad}");
        }
    }

    #[test]
    fn patients_parse_as_java_ints_and_refuse_negatives() {
        assert_eq!(parse_patient("00900"), Ok(900));
        assert_eq!(parse_patient("+901"), Ok(901));
        assert_eq!(parse_patient("2147483647"), Ok(2_147_483_647));
        assert_eq!(parse_patient("-0"), Ok(0));
        assert_eq!(parse_patient("-5"), Err(PatientProblem::Negative));
        assert_eq!(parse_patient(" 5"), Err(PatientProblem::NotAnInteger));
        assert_eq!(
            parse_patient("2147483648"),
            Err(PatientProblem::NotAnInteger)
        );
    }

    #[test]
    fn config_keys_lose_their_csv_suffix() {
        let config = CsvConfig {
            dataset_name: Some("ds".into()),
            dataset_name_as_root_node: true,
        };
        let map = ConfigMap::from_entries([
            ("a.csv".to_string(), config.clone()),
            ("b.csv".to_string(), config.clone()),
            ("c".to_string(), config.clone()),
        ]);
        assert_eq!(map.get("a.csv"), Some(&config));
        assert_eq!(map.get("b"), Some(&config));
        assert_eq!(map.get("c.sql"), Some(&config));
        assert_eq!(map.get("d.csv"), None);
    }

    #[test]
    fn config_parses_jackson_shaped_json() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("config.json");
        std::fs::write(
            &path,
            r#"{"x.csv": {"dataset_name": "phs1", "dataset_name_as_root_node": true}, "y": {"dataset_name_as_root_node": null}}"#,
        )
        .unwrap();
        let map = ConfigMap::load(&path).unwrap();
        assert!(map.get("x").unwrap().dataset_name_as_root_node);
        assert!(!map.get("y").unwrap().dataset_name_as_root_node);
        std::fs::write(&path, r#"{"x": {"unknown": 1}}"#).unwrap();
        assert!(matches!(
            ConfigMap::load(&path),
            Err(EtlError::Config { .. })
        ));
    }

    #[test]
    fn dataset_root_prefix() {
        let config = CsvConfig {
            dataset_name: Some("phs1".into()),
            dataset_name_as_root_node: true,
        };
        assert_eq!(
            apply_dataset_root("\\a\\".into(), Some(&config)),
            "\\phs1\\a\\"
        );
        assert_eq!(
            apply_dataset_root("a\\".into(), Some(&config)),
            "\\phs1\\a\\"
        );
        let unnamed = CsvConfig {
            dataset_name: None,
            dataset_name_as_root_node: true,
        };
        assert_eq!(
            apply_dataset_root("\\a\\".into(), Some(&unnamed)),
            "\\null\\a\\"
        );
        assert_eq!(apply_dataset_root("\\a\\".into(), None), "\\a\\");
        assert_eq!(
            apply_dataset_root("\\a\\".into(), Some(&CsvConfig::default())),
            "\\a\\"
        );
    }
}
