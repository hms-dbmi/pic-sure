//! Loads CSV through the public API and reads the store back through `hpds-format`.

#![allow(dead_code)]

use std::path::{Path, PathBuf};

use hpds_etl::EtlError;
use hpds_etl::java::java_double_to_string;
use hpds_etl::load::{KeySource, LoadOptions, LoadReport, Mode, load};
use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlocksReader, ConceptMeta, CryptoKey, INDEX_FILE_NAME,
    NO_TIMESTAMP, PhenoIndex, Protection,
};

pub const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";

/// Both modes, for tests that check a rule in each.
pub const MODES: [Mode; 2] = [Mode::NewSearch, Mode::Sequential];

/// A loaded store and the directory that holds it.
pub struct Store {
    pub dir: tempfile::TempDir,
    pub out: PathBuf,
    pub index: PhenoIndex,
    pub reader: BlocksReader,
    pub codec: BlockCodec,
    pub column_meta: String,
    pub report: LoadReport,
}

/// One observation in a concept's `by_key` order: patient, value as Java prints it, and
/// timestamp (`None` for no timestamp).
pub type Row = (u32, String, Option<i64>);

impl Store {
    pub fn meta(&self, path: &str) -> &ConceptMeta {
        self.index
            .get(path)
            .unwrap_or_else(|| panic!("no concept {path:?}; have {:?}", self.paths()))
    }

    pub fn paths(&self) -> Vec<&str> {
        self.index
            .concepts()
            .iter()
            .map(|c| c.path.as_str())
            .collect()
    }

    pub fn rows(&self, path: &str) -> Vec<Row> {
        let ts = |t: i64| (t != NO_TIMESTAMP).then_some(t);
        match self
            .reader
            .read_block(&self.codec, self.meta(path))
            .unwrap()
        {
            Block::Numeric(b) => b
                .by_key()
                .iter()
                .map(|&i| {
                    let i = i as usize;
                    (
                        b.patients()[i],
                        java_double_to_string(b.values()[i]),
                        ts(b.timestamp_at_value_pos(i)),
                    )
                })
                .collect(),
            Block::Categorical(b) => (0..b.len())
                .map(|i| {
                    let value = b
                        .dictionary_value(b.value_indices()[i] as usize)
                        .unwrap()
                        .to_string();
                    (b.patients()[i], value, ts(b.timestamp_at_key_pos(i)))
                })
                .collect(),
        }
    }

    pub fn values(&self, path: &str) -> Vec<String> {
        self.rows(path).into_iter().map(|r| r.1).collect()
    }

    pub fn patients(&self) -> Vec<u32> {
        self.index.patients().iter().collect()
    }

    /// The `columnMeta.csv` line for `path`, without its terminator.
    pub fn csv_line(&self, path: &str) -> String {
        let position = self.index.position(path).unwrap();
        self.column_meta
            .split("\r\n")
            .nth(position)
            .unwrap()
            .to_string()
    }
}

/// Writes `files` (name, content) into a fresh input directory, loads them with `mode`
/// (new-search reads the first file only), and opens the store with the test key.
pub fn load_files(
    mode: Mode,
    files: &[(&str, &[u8])],
    configure: impl FnOnce(&mut LoadOptions),
) -> Result<Store, EtlError> {
    let dir = tempfile::tempdir().unwrap();
    let input = dir.path().join("input");
    std::fs::create_dir(&input).unwrap();
    let mut paths = Vec::new();
    for (name, content) in files {
        let path = input.join(name);
        std::fs::write(&path, content).unwrap();
        paths.push(path);
    }
    let key_file = dir.path().join("key");
    std::fs::write(&key_file, format!("{KEY}\n")).unwrap();
    let out = dir.path().join("store");
    let mut options = LoadOptions::new(mode, paths, out.clone());
    options.key = KeySource::File(key_file);
    options.partitions = Some(3);
    options.threads = Some(2);
    configure(&mut options);
    let report = load(&options)?;
    Ok(open(dir, out, report))
}

/// Loads one CSV string with `mode` and default options.
pub fn load_str(mode: Mode, csv: &str) -> Store {
    load_files(mode, &[("allConcepts.csv", csv.as_bytes())], |_| {}).unwrap()
}

/// Opens a store written with the test key.
pub fn open(dir: tempfile::TempDir, out: PathBuf, report: LoadReport) -> Store {
    let protection = if report.encrypted {
        Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap())
    } else {
        Protection::Plain
    };
    let index = PhenoIndex::read_file(out.join(INDEX_FILE_NAME), &protection).unwrap();
    let reader = BlocksReader::open(out.join(BLOCKS_FILE_NAME), index.store_id()).unwrap();
    let codec = index.block_codec(protection).unwrap();
    let column_meta = std::fs::read_to_string(out.join("columnMeta.csv")).unwrap();
    Store {
        dir,
        out,
        index,
        reader,
        codec,
        column_meta,
        report,
    }
}

pub fn fixture(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(name)
}

pub fn row(patient: u32, value: &str, timestamp: Option<i64>) -> Row {
    (patient, value.to_string(), timestamp)
}

pub const HEADER: &str = "PATIENT_NUM,CONCEPT_PATH,NVAL_NUM,TVAL_CHAR,TIMESTAMP\n";
