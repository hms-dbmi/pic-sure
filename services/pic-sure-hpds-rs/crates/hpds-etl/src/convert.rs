//! `hpds-etl convert`: a store of format 1, as Rust HPDS v1 to v3 wrote it, to a store of
//! this format.
//!
//! A format 1 store is either one store at the root of its directory (unpartitioned), or
//! one store per consent partition directory, all under the root `encryption_key`, maybe
//! beside a combined store in `.combined` and a `.generation` marker. An unpartitioned
//! store converts to an unpartitioned store; a partitioned one to one store whose blocks
//! hold every partition's rows ([`crate::merge`]). The partitions are the source: the
//! combined store is never read.
//!
//! Every block of every partition is read once to learn which patients have rows in it, as
//! `migrate` learns it from the export, so a partition whose rows hold a patient its
//! patient set lacks loses the `patients-indexed` flag and that patient goes into the
//! overlap set. The `patients-indexed` tags of the old `.generation` marker are not
//! trusted. The source is never changed.

use std::collections::BTreeSet;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::time::Instant;

use hpds_format::{INDEX_FILE_NAME, LEGACY_FORMAT_VERSION, index_format_version};
use serde::Serialize;

use crate::error::{EtlError, Result};
use crate::load::{
    DEFAULT_KEY_FILE_NAME, KeySource, LEGACY_COMBINED_DIR_NAME, REPORT_FILE_NAME, Staging,
    check_output, thread_pool,
};
use crate::merge::{MergeReport, MergeSource, SourceStore, write_store};
use crate::partitions::{partition_dirs, resolve_shared_key};
use crate::private_fs;
use crate::store::resolve_protection;

/// Everything `hpds-etl convert` needs.
#[derive(Clone, Debug)]
pub struct ConvertOptions {
    /// The format 1 store directory to read.
    pub store: PathBuf,
    /// The store directory to create or replace.
    pub out: PathBuf,
    /// Where the key comes from: [`KeySource::StoreDirectory`] reads
    /// `<store>/encryption_key`. The key opens the old store and encrypts the new one, and a
    /// copy is installed as `<out>/encryption_key` when `out` has none.
    pub key: KeySource,
    /// Worker threads for reading and sealing blocks; `None` uses one per core.
    pub threads: Option<usize>,
}

/// What a conversion did, written to the new store's `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct ConvertReport {
    /// The store read.
    pub source: PathBuf,
    /// The source's partitions in byte order of their names; empty when unpartitioned.
    pub source_partitions: Vec<String>,
    /// What writing the new store did, its id and overlap counts among it.
    pub store: MergeReport,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// Converts the format 1 store at `options.store` into a new store at `options.out`.
///
/// The new store is staged beside `out` and published as [`crate::load::load`] publishes
/// one, under the store lock when `out` exists.
///
/// # Errors
/// [`EtlError::Usage`] when `store` holds no format 1 store, holds a store of this format
/// already, has a `.generation` marker that does not list exactly its partitions' store ids
/// (a publish under way), or when `out` is `store`, lies inside it, or is refused by
/// [`check_output`]; key errors; the errors of reading the old store and of
/// [`write_store`], a concept typed differently in two partitions among them.
pub fn convert(options: &ConvertOptions) -> Result<ConvertReport> {
    let started = Instant::now();
    let source = &options.store;
    check_not_inside(&options.out, source)?;
    check_output(&options.out, &options.key)?;
    let protection = match &options.key {
        KeySource::Plain => resolve_protection(&KeySource::Plain, source)?,
        KeySource::File(path) => resolve_protection(&KeySource::File(path.clone()), source)?,
        KeySource::OutputDirectoryKeyFile | KeySource::StoreDirectory => {
            resolve_protection(&KeySource::StoreDirectory, source)?
        }
    };
    let install_key = match &protection {
        hpds_format::Protection::Plain => None,
        hpds_format::Protection::Encrypted(_) => {
            let key_file = match &options.key {
                KeySource::File(path) => path.clone(),
                _ => source.join(DEFAULT_KEY_FILE_NAME),
            };
            resolve_shared_key(&KeySource::File(key_file), &options.out)?.1
        }
    };
    let pool = thread_pool(options.threads)?;
    let (sources, partitioned) = if source.join(INDEX_FILE_NAME).is_file() {
        check_legacy_index(source)?;
        let store = SourceStore::open_legacy(source, protection.clone())?;
        let source = MergeSource {
            name: String::new(),
            store,
            observed: None,
        };
        (vec![source], false)
    } else {
        let partitions = partition_dirs(source)?;
        if partitions.is_empty() {
            return Err(EtlError::Usage(format!(
                "{} holds no store: no {INDEX_FILE_NAME} in it or in any partition directory",
                source.display()
            )));
        }
        let mut sources = Vec::with_capacity(partitions.len());
        let mut ids = BTreeSet::new();
        for partition in &partitions {
            check_legacy_index(&partition.dir)?;
            let store = SourceStore::open_legacy(&partition.dir, protection.clone())?;
            if let SourceStore::Legacy { index, .. } = &store {
                ids.insert(index.store_id().to_string());
            }
            sources.push(MergeSource {
                name: partition.name.clone(),
                store,
                observed: None,
            });
        }
        check_legacy_generation(source, &ids)?;
        (sources, true)
    };
    let source_partitions: Vec<String> = if partitioned {
        let mut names: Vec<String> = sources.iter().map(|s| s.name.clone()).collect();
        names.sort();
        names
    } else {
        Vec::new()
    };
    tracing::info!(
        store = %source.display(),
        partitions = source_partitions.len(),
        out = %options.out.display(),
        "converting"
    );
    let mut staging = Staging::create(&options.out)?;
    let merged = write_store(sources, partitioned, staging.path(), &protection, &pool)?;
    if let Some(key) = install_key {
        staging.stage_key(&key)?;
    }
    let report = ConvertReport {
        source: source.clone(),
        source_partitions,
        store: merged,
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    let report_path = staging.path().join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    private_fs::write_file(&report_path, &json).map_err(EtlError::io("writing", &report_path))?;
    staging.publish(&options.out, report.store.id)?;
    tracing::info!(
        out = %options.out.display(),
        seconds = report.elapsed_seconds,
        "store converted"
    );
    Ok(report)
}

/// Refuses an `out` that is `store` or lies inside it, where publishing would replace or
/// mix with the source.
///
/// # Errors
/// [`EtlError::Usage`] naming both; I/O errors resolving `store`.
fn check_not_inside(out: &Path, store: &Path) -> Result<()> {
    let store_real = store
        .canonicalize()
        .map_err(EtlError::io("resolving", store))?;
    let mut probe = out.to_path_buf();
    while !probe.exists() {
        match probe.parent() {
            Some(parent) if !parent.as_os_str().is_empty() => probe = parent.to_path_buf(),
            _ => return Ok(()),
        }
    }
    let probe_real = probe
        .canonicalize()
        .map_err(EtlError::io("resolving", &probe))?;
    if probe_real.starts_with(&store_real) {
        return Err(EtlError::Usage(format!(
            "{} is or lies inside the store {} it converts; write the new store to another directory",
            out.display(),
            store.display()
        )));
    }
    Ok(())
}

/// Refuses a store directory whose index is not format 1, naming what it is.
///
/// # Errors
/// [`EtlError::Usage`] for an index of this format or another version; I/O and format
/// errors reading its header.
fn check_legacy_index(dir: &Path) -> Result<()> {
    let path = dir.join(INDEX_FILE_NAME);
    let mut header = Vec::with_capacity(16);
    std::fs::File::open(&path)
        .and_then(|file| file.take(16).read_to_end(&mut header))
        .map_err(EtlError::io("reading", &path))?;
    match index_format_version(&header)? {
        LEGACY_FORMAT_VERSION => Ok(()),
        hpds_format::FORMAT_VERSION => Err(EtlError::Usage(format!(
            "{} is already in format {}; there is nothing to convert",
            dir.display(),
            hpds_format::FORMAT_VERSION
        ))),
        other => Err(EtlError::Usage(format!(
            "{} is in format {other}, which this ETL does not read",
            dir.display()
        ))),
    }
}

/// Refuses a partitioned format 1 store whose `.generation` marker does not list exactly
/// the partitions' store ids: a load was being published, or was interrupted. A store
/// without a marker converts as found. The combined store's line, tagged
/// `.combined`, is not a partition.
///
/// # Errors
/// [`EtlError::Usage`] when the ids differ; I/O errors reading the marker.
fn check_legacy_generation(dir: &Path, ids: &BTreeSet<String>) -> Result<()> {
    let path = dir.join(crate::store::GENERATION_FILE_NAME);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(EtlError::io("reading", &path)(e)),
    };
    let listed: BTreeSet<String> = text
        .lines()
        .filter_map(|line| {
            let mut tokens = line.split_whitespace();
            let id = tokens.next()?;
            (tokens.next() != Some(LEGACY_COMBINED_DIR_NAME)).then(|| id.to_ascii_lowercase())
        })
        .collect();
    if &listed != ids {
        return Err(EtlError::Usage(format!(
            "{} does not list the partitions' store ids, so a publish is under way or was interrupted; finish or rerun that load first",
            path.display()
        )));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn an_output_inside_the_store_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let store = dir.path().join("store");
        std::fs::create_dir(&store).unwrap();
        assert!(check_not_inside(&store, &store).is_err());
        assert!(check_not_inside(&store.join("new"), &store).is_err());
        assert!(check_not_inside(&dir.path().join("other"), &store).is_ok());
    }

    #[test]
    fn the_old_generation_marker_must_list_the_partitions() {
        let dir = tempfile::tempdir().unwrap();
        let ids: BTreeSet<String> = ["a".repeat(32), "b".repeat(32)].into_iter().collect();
        check_legacy_generation(dir.path(), &ids).unwrap();
        std::fs::write(
            dir.path().join(".generation"),
            format!(
                "{} patients-indexed\n{}\n{} .combined\n",
                "A".repeat(32),
                "b".repeat(32),
                "c".repeat(32)
            ),
        )
        .unwrap();
        check_legacy_generation(dir.path(), &ids).unwrap();
        std::fs::write(dir.path().join(".generation"), "a".repeat(32)).unwrap();
        assert!(check_legacy_generation(dir.path(), &ids).is_err());
    }
}
