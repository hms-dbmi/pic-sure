//! Builds stores in-process with the Rust ETL and opens them with the engine.

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use hpds_etl::load::{KeySource, LoadOptions, Mode, load};
use hpds_format::{CryptoKey, Protection};
use hpds_query::{
    Engine, PartitionOptions, PhenotypicClause, PhenotypicFilter, PhenotypicFilterType, Query,
    ResultType, SummaryColumnMeta,
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

/// Every consent list the routing must handle on `partitions`: each subset (the empty one
/// included), each full set with an unknown consent added, a list of only unknown
/// consents, and no consents at all.
pub fn consent_lists(partitions: &[&str]) -> Vec<Vec<String>> {
    let mut lists = Vec::new();
    for mask in 0..(1u32 << partitions.len()) {
        lists.push(
            partitions
                .iter()
                .enumerate()
                .filter(|(i, _)| mask & (1 << i) != 0)
                .map(|(_, p)| (*p).to_owned())
                .collect::<Vec<_>>(),
        );
    }
    let mut with_unknown: Vec<String> = partitions.iter().map(|p| (*p).to_owned()).collect();
    with_unknown.insert(1, "phs999999.c9".to_owned());
    lists.push(with_unknown);
    lists.push(vec![
        "phs999999.c9".to_owned(),
        "not-a-partition".to_owned(),
    ]);
    lists.retain(|l| !l.is_empty());
    lists.push(Vec::new());
    lists
}

/// The clauses exercised on a store: per concept a REQUIRED filter, a value filter on its
/// first category or a range over the middle of its values, an `ANY_RECORD_OF` on the
/// concept's first path segment, and their AND and OR.
pub fn clauses(dictionary: &[SummaryColumnMeta]) -> Vec<Option<PhenotypicClause>> {
    let mut out = vec![None];
    for concept in dictionary {
        out.push(Some(required(&concept.name)));
        let filter = if concept.categorical {
            let values: Vec<&str> = concept.category_values.iter().map(String::as_str).collect();
            filter_values(&concept.name, &values[..values.len().min(1)])
        } else {
            match (concept.min, concept.max) {
                (Some(min), Some(max)) => {
                    let mid = min + (max - min) / 2.0;
                    filter_range(&concept.name, Some(mid), None)
                }
                _ => filter_range(&concept.name, None, Some(0.0)),
            }
        };
        out.push(Some(filter.clone()));
        out.push(Some(subquery(
            Operator::And,
            vec![filter.clone(), required(&concept.name)],
        )));
        out.push(Some(subquery(
            Operator::Or,
            vec![filter, required(&dictionary[0].name)],
        )));
    }
    let mut prefixes: Vec<String> = dictionary
        .iter()
        .filter_map(|c| c.name.get(..c.name[1..].find('\\')? + 2).map(str::to_owned))
        .collect();
    prefixes.dedup();
    out.extend(prefixes.iter().map(|p| Some(any_record_of(p))));
    out.push(Some(any_record_of("\\")));
    out
}

/// Every answer `engine` gives for `consents`: each sync result type's body, the PATIENTS
/// ids and every DATAFRAME row, for a sample of the clauses of [`clauses`], or the error.
pub fn answers(
    engine: &Engine,
    consents: &[String],
    clauses: &[Option<PhenotypicClause>],
) -> Vec<String> {
    let dictionary = engine.partitions().dictionary();
    let select: Vec<String> = dictionary.iter().map(|c| c.name.clone()).collect();
    let mut out = Vec::new();
    for (i, clause) in clauses.iter().enumerate() {
        for result_type in [
            ResultType::Count,
            ResultType::CrossCount,
            ResultType::CategoricalCrossCount,
            ResultType::ContinuousCrossCount,
            ResultType::ObservationCrossCount,
            ResultType::Patients,
            ResultType::Dataframe,
        ] {
            if result_type == ResultType::Dataframe && i % 5 != 0 {
                continue;
            }
            let query = Query {
                phenotypic_clause: clause.clone(),
                select: select.clone(),
                ..Query::with_consents(result_type, consents.iter().map(String::as_str))
            };
            let answer = match result_type {
                ResultType::Patients => engine
                    .patients(&query)
                    .map(|p| format!("{:?}", p.iter().collect::<Vec<_>>())),
                ResultType::Dataframe => engine.dataframe(&query).map(|frame| {
                    let rows: Vec<String> = frame.rows.map(|row| row.join(",")).collect();
                    format!("{}\n{}", frame.header.join(","), rows.join("\n"))
                }),
                _ => engine.run_sync(&query).map(|r| r.body()),
            };
            out.push(format!(
                "{i} {result_type:?}: {}",
                answer.unwrap_or_else(|e| format!("error {e}"))
            ));
        }
    }
    out
}
