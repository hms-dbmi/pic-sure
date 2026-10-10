//! Builds stores in-process with the Rust ETL and opens them with the engine.

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_format::{CryptoKey, Protection};
use hpds_query::{
    Engine, PartitionOptions, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, Query,
    ResultType,
};
use hpds_query::{Operator, PhenotypicSubquery};

pub const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";

/// A store built in a temporary directory, removed on drop.
pub struct TestStore {
    pub dir: tempfile::TempDir,
    pub out: PathBuf,
    pub engine: Engine,
}

/// One input row: patient, concept path, numeric value, text value.
pub type Row<'a> = (u32, &'a str, Option<f64>, Option<&'a str>);

fn csv_field(field: &str) -> String {
    format!("\"{}\"", field.replace('"', "\"\""))
}

/// The CSV text for `rows`, with a header line.
pub fn csv(rows: &[Row<'_>]) -> String {
    let mut out = String::from("PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n");
    for (patient, path, number, text) in rows {
        let number = number.map(|n| format!("{n:?}")).unwrap_or_default();
        out.push_str(&format!(
            "{},{},{},{},\n",
            patient,
            csv_field(path),
            csv_field(&number),
            csv_field(text.unwrap_or_default())
        ));
    }
    out
}

/// Loads `csv_text` with the new-search loader into an encrypted store and opens it.
pub fn load_csv(csv_text: &str) -> TestStore {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("allConcepts.csv");
    std::fs::write(&input, csv_text).unwrap();
    load_file(dir, &input)
}

/// Options for an unpartitioned test store: consents not required.
pub fn lenient() -> PartitionOptions {
    PartitionOptions {
        require_consents: false,
        ..PartitionOptions::default()
    }
}

/// Options requiring consents, as `hpds.requireAuthorizationFilter=true` does.
pub fn strict() -> PartitionOptions {
    PartitionOptions::default()
}

/// Loads `input` with the new-search loader into the encrypted store directory `out`,
/// keyed with [`KEY`] from `key_file`.
pub fn load_into(input: &Path, key_file: &Path, out: &Path) {
    let mut options = LoadOptions::new(Mode::NewSearch, vec![input.to_path_buf()], out.into());
    options.key = KeySource::File(key_file.to_path_buf());
    options.partitions = Some(2);
    options.threads = Some(2);
    load(&options).unwrap();
}

/// Loads `input` with the new-search loader into an unpartitioned store under `dir` and
/// opens it with consents not required.
pub fn load_file(dir: tempfile::TempDir, input: &Path) -> TestStore {
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, KEY).unwrap();
    let out = dir.path().join("store");
    load_into(input, &key_file, &out);
    std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
    let engine = Engine::open(&out, &lenient()).unwrap();
    TestStore { dir, out, engine }
}

/// A partitioned data directory: one store per `(name, csv text)` under `<out>/<name>`, and
/// the shared key at `<out>/encryption_key`. Not opened.
pub struct PartitionedDir {
    pub dir: tempfile::TempDir,
    pub out: PathBuf,
}

impl PartitionedDir {
    /// Opens the directory with `options`.
    pub fn open(&self, options: &PartitionOptions) -> hpds_query::Result<Engine> {
        Engine::open(&self.out, options)
    }
}

/// Builds a partitioned data directory from `(name, csv text)` pairs, each text with a
/// header line.
pub fn partitioned(partitions: &[(&str, String)]) -> PartitionedDir {
    let dir = tempfile::tempdir().unwrap();
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, KEY).unwrap();
    let out = dir.path().join("data");
    std::fs::create_dir_all(&out).unwrap();
    for (name, text) in partitions {
        let input = dir.path().join(format!("{name}-allConcepts.csv"));
        std::fs::write(&input, text).unwrap();
        load_into(&input, &key_file, &out.join(name));
    }
    std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
    PartitionedDir { dir, out }
}

/// [`partitioned`] from rows.
pub fn partitioned_rows(partitions: &[(&str, Vec<Row<'_>>)]) -> PartitionedDir {
    let texts: Vec<(&str, String)> = partitions
        .iter()
        .map(|(name, rows)| (*name, csv(rows)))
        .collect();
    partitioned(&texts)
}

/// Loads `rows` into a fresh store.
pub fn store(rows: &[Row<'_>]) -> TestStore {
    load_csv(&csv(rows))
}

pub fn filter_values(path: &str, values: &[&str]) -> PhenotypicClause {
    PhenotypicClause::Filter(PhenotypicFilter {
        values: Some(values.iter().map(|v| (*v).to_owned()).collect()),
        ..PhenotypicFilter::new(PhenotypicFilterType::Filter, path)
    })
}

pub fn filter_range(path: &str, min: Option<f64>, max: Option<f64>) -> PhenotypicClause {
    PhenotypicClause::Filter(PhenotypicFilter {
        min,
        max,
        ..PhenotypicFilter::new(PhenotypicFilterType::Filter, path)
    })
}

pub fn required(path: &str) -> PhenotypicClause {
    PhenotypicClause::Filter(PhenotypicFilter::new(PhenotypicFilterType::Required, path))
}

pub fn any_record_of(path: &str) -> PhenotypicClause {
    PhenotypicClause::Filter(PhenotypicFilter::new(
        PhenotypicFilterType::AnyRecordOf,
        path,
    ))
}

pub fn subquery(operator: Operator, clauses: Vec<PhenotypicClause>) -> PhenotypicClause {
    PhenotypicClause::Subquery(PhenotypicSubquery {
        not: None,
        phenotypic_clauses: clauses,
        operator: Some(operator),
    })
}

pub fn query(result_type: ResultType, clause: Option<PhenotypicClause>) -> Query {
    Query {
        phenotypic_clause: clause,
        ..Query::new(result_type)
    }
}

pub fn ids(bitmap: &roaring::RoaringBitmap) -> Vec<u32> {
    bitmap.iter().collect()
}

/// The protection of every test store: encrypted with [`KEY`].
pub fn protection() -> Protection {
    Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap())
}

/// Builds the combined store of the partitioned directory `out` in place, as `hpds-etl
/// combine` does, and returns its store id.
pub fn add_combined(out: &Path) -> String {
    let pool = rayon::ThreadPoolBuilder::new()
        .num_threads(2)
        .build()
        .unwrap();
    hpds_etl::combined::combine_in_place(out, &protection(), &pool)
        .unwrap()
        .store_id
}

/// The workspace root.
pub fn workspace() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

/// The header line the Java fixture files lack.
pub const FIXTURE_HEADER: &str = "PATIENT_NUM,CONCEPT_PATH,NUMERIC_VALUE,TEXT_VALUE,DATETIME\n";

/// The Java integration fixture (`partition{1..4}-allConcepts.csv`) as (name, csv text)
/// pairs, each text with [`FIXTURE_HEADER`] prepended.
pub fn fixture_texts() -> Vec<(String, String)> {
    let resources = workspace().join("../pic-sure-hpds/service/src/test/resources/phenotypic");
    (1..=4)
        .map(|n| {
            let name = format!("partition{n}");
            let path = resources.join(format!("{name}-allConcepts.csv"));
            let body = std::fs::read_to_string(&path).unwrap();
            (name, format!("{FIXTURE_HEADER}{body}"))
        })
        .collect()
}

/// [`partitioned`] over `(name, text)` pairs held as owned strings.
pub fn partitioned_texts(texts: &[(String, String)]) -> PartitionedDir {
    let refs: Vec<(&str, String)> = texts
        .iter()
        .map(|(name, text)| (name.as_str(), text.clone()))
        .collect();
    partitioned(&refs)
}

/// The four fixture partitions, each loaded with the new-search loader after a header line
/// is prepended. No combined store.
pub fn harm_fixture() -> PartitionedDir {
    partitioned_texts(&fixture_texts())
}
