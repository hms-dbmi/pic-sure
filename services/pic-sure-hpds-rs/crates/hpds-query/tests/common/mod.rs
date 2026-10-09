//! Builds stores in-process with the Rust ETL and opens them with the engine.

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_query::{
    Engine, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, Query, ResultType,
    StoreOptions,
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

/// Loads `input` with the new-search loader into a store under `dir` and opens it.
pub fn load_file(dir: tempfile::TempDir, input: &Path) -> TestStore {
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, KEY).unwrap();
    let out = dir.path().join("store");
    let mut options = LoadOptions::new(Mode::NewSearch, vec![input.to_path_buf()], out.clone());
    options.key = KeySource::File(key_file.clone());
    options.partitions = Some(2);
    options.threads = Some(2);
    load(&options).unwrap();
    std::fs::copy(&key_file, out.join("encryption_key")).unwrap();
    let engine = Engine::open(&out, &StoreOptions::default()).unwrap();
    TestStore { dir, out, engine }
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
