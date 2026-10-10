//! `hpds-etl load --mode partitioned`: a directory of `<partition>-allConcepts.csv` files to
//! one store per partition, as the harmonization branch's `CSVLoader` builds javabin
//! partitions.
//!
//! `CSVLoader.main` walks its base directory recursively, takes every regular file whose
//! path ends in `allConcepts.csv`, names its partition by the file name up to the first
//! `-`, and loads each file sequentially into `<file's directory>/<partition>/` with the
//! one key in `<base>/encryption_key`. This loader takes the same files and the same
//! partition names, builds the partitions in parallel into `<out>/<partition>/`, and differs
//! where Java would crash, overwrite, or write a partition the service cannot see:
//!
//! - A file whose name has no `-` before `allConcepts.csv` (a bare `allConcepts.csv`),
//!   or whose prefix is empty or `.`/`..`, is refused. Java names the partition after the
//!   whole file name, fails to create a directory beside the file of that name, and aborts;
//!   an empty prefix would write the partition into the base directory itself.
//! - Two files with the same prefix are refused. Java loads both into one directory, the
//!   second overwriting the first's metadata over its stale observations.
//! - A matching file in a subdirectory is refused. Java writes its partition beside it,
//!   nested where the service, which lists only immediate subdirectories, never reads it.
//!
//! Every partition is staged beside `out` and nothing is published until all are complete;
//! a failure in any partition publishes none. Unless [`PartitionedLoadOptions::combined`]
//! is off, the combined store of every partition is then built from the staged partitions
//! ([`crate::combined`]) and published with them. Into a new `out` the set appears at once;
//! into an existing one the partitions are replaced one after another and the
//! `.generation` marker is written last (see [`PartitionedStaging::publish`]).

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::Instant;

use rayon::prelude::*;
use serde::Serialize;

use super::{
    CONFIG_FILE_NAME, InputFile, KeySource, LoadOptions, LoadReport, Mode, StoreSettings,
    build_store, resolve_config, thread_pool, warn_without_all_concepts_entry,
};
use crate::combined::CombinedReport;
use crate::error::{EtlError, Result};
use crate::partitions::{
    PartitionRecord, PartitionedStaging, check_partition_name, check_partitioned_output,
    resolve_shared_key,
};

/// The suffix `CSVLoader` selects input files by.
pub const PARTITION_FILE_SUFFIX: &str = "allConcepts.csv";

/// The name every partition file's CSV config is looked up under, as `CSVLoader` does.
pub const PARTITION_CONFIG_KEY: &str = "allConcepts";

/// Everything `hpds-etl load --mode partitioned` needs.
#[derive(Clone, Debug)]
pub struct PartitionedLoadOptions {
    /// Directory holding the `<partition>-allConcepts.csv` files.
    pub input_dir: PathBuf,
    /// The directory to write `<partition>/` stores into.
    pub out: PathBuf,
    /// Where the key comes from. [`KeySource::File`] also installs a copy as
    /// `<out>/encryption_key`; [`KeySource::OutputDirectoryKeyFile`] reads that file.
    pub key: KeySource,
    /// The CSV config file, whose `allConcepts` entry applies to every partition. Either
    /// this or `no_config` is required: `CSVLoader` reads
    /// `/opt/local/hpds_input/config.json` whatever its input directory, so there is no
    /// place beside the inputs to look for it.
    pub config: Option<PathBuf>,
    /// Load without a CSV config.
    pub no_config: bool,
    /// Spill files per partition store; `None` picks one per 32 MiB of that partition's
    /// input, at most 128.
    pub spill_partitions: Option<usize>,
    /// Worker threads shared by every partition build; `None` uses one per core.
    pub threads: Option<usize>,
    /// Directory for the spill files; `None` uses each partition's staging directory.
    pub tmp: Option<PathBuf>,
    /// Also write the combined store of every partition (`--no-combined` turns it off).
    /// Off, an existing combined store in `out` is removed by the publish.
    pub combined: bool,
}

impl PartitionedLoadOptions {
    /// Options with the defaults for everything but the input and output directories.
    pub fn new(input_dir: PathBuf, out: PathBuf) -> Self {
        PartitionedLoadOptions {
            input_dir,
            out,
            key: KeySource::OutputDirectoryKeyFile,
            config: None,
            no_config: false,
            spill_partitions: None,
            threads: None,
            tmp: None,
            combined: true,
        }
    }
}

/// What a partitioned load did: one [`LoadReport`] per partition, also written to that
/// partition's `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct PartitionedLoadReport {
    /// Partition name to its report, in byte order of the names.
    pub partitions: BTreeMap<String, LoadReport>,
    /// What building the combined store did, when one was built.
    pub combined: Option<CombinedReport>,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// One partition's input file.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PartitionInput {
    /// The partition name: the file name up to its first `-`.
    pub name: String,
    /// The input file.
    pub path: PathBuf,
}

/// Lists the partition files in `dir`, sorted by partition name.
///
/// Walks `dir` recursively, without following directory links, for regular files (links
/// to regular files included) whose names end in `allConcepts.csv`, as `CSVLoader.main`
/// does. The partition name is the file name up to its first `-`.
///
/// # Errors
/// [`EtlError::Usage`] naming every offending file when `dir` holds no partition file, a
/// file has no partition prefix or an invalid one, two files share a prefix, a file is in
/// a subdirectory, or a file name is not UTF-8; I/O errors walking `dir`.
pub fn discover_partition_inputs(dir: &Path) -> Result<Vec<PartitionInput>> {
    if !dir.is_dir() {
        return Err(EtlError::Usage(format!(
            "{} is not a directory",
            dir.display()
        )));
    }
    let mut found = Vec::new();
    walk(dir, &mut found)?;
    found.sort();
    let mut problems = Vec::new();
    let mut by_name: BTreeMap<String, Vec<PathBuf>> = BTreeMap::new();
    for path in found {
        let Some(file_name) = path.file_name().and_then(|n| n.to_str()) else {
            problems.push(format!("{} has a name that is not UTF-8", path.display()));
            continue;
        };
        if path.parent() != Some(dir) {
            problems.push(format!(
                "{} is in a subdirectory; CSVLoader would write its partition nested where the service never reads it, so move it into {}",
                path.display(),
                dir.display()
            ));
            continue;
        }
        let Some((name, _)) = file_name.split_once('-') else {
            problems.push(format!(
                "{} has no partition prefix; name it <partition>-{PARTITION_FILE_SUFFIX} (CSVLoader cannot load it either)",
                path.display()
            ));
            continue;
        };
        if let Err(e) = check_partition_name(name) {
            problems.push(format!("{}: {e}", path.display()));
            continue;
        }
        by_name.entry(name.to_string()).or_default().push(path);
    }
    for (name, paths) in &by_name {
        if paths.len() > 1 {
            let list: Vec<String> = paths.iter().map(|p| p.display().to_string()).collect();
            problems.push(format!(
                "partition {name} has more than one file ({}); CSVLoader would overwrite one with the other, so merge them into one {name}-{PARTITION_FILE_SUFFIX}",
                list.join(", ")
            ));
        }
    }
    if !problems.is_empty() {
        return Err(EtlError::Usage(problems.join("; ")));
    }
    if by_name.is_empty() {
        return Err(EtlError::Usage(format!(
            "{} holds no <partition>-{PARTITION_FILE_SUFFIX} file",
            dir.display()
        )));
    }
    Ok(by_name
        .into_iter()
        .map(|(name, mut paths)| PartitionInput {
            name,
            path: paths.remove(0),
        })
        .collect())
}

fn walk(dir: &Path, found: &mut Vec<PathBuf>) -> Result<()> {
    for entry in std::fs::read_dir(dir).map_err(EtlError::io("listing", dir))? {
        let entry = entry.map_err(EtlError::io("listing", dir))?;
        let path = entry.path();
        let file_type = entry.file_type().map_err(EtlError::io("listing", &path))?;
        if file_type.is_dir() {
            walk(&path, found)?;
        } else if path.is_file()
            && entry
                .file_name()
                .to_string_lossy()
                .ends_with(PARTITION_FILE_SUFFIX)
        {
            found.push(path);
        }
    }
    Ok(())
}

/// Loads every partition file in `options.input_dir` into `<options.out>/<partition>/`
/// with `CSVLoader`'s rules ([`Mode::Partitioned`]), all under one key.
///
/// Inputs, output, key and config are checked before anything is written. The partitions
/// build in parallel on one thread pool into a staging directory beside `out`, then their
/// combined store when [`PartitionedLoadOptions::combined`] is set, and the set is published
/// once every store is complete, as [`PartitionedStaging::publish`] describes. Every
/// partition's `.generation` line carries [`crate::partitions::PATIENTS_INDEXED_TAG`]: a
/// loaded store's patient set is the set of patients its rows hold.
///
/// # Errors
/// [`EtlError::Usage`] when neither `config` nor `no_config` is set; everything
/// [`discover_partition_inputs`] and [`check_partitioned_output`] refuse; a key
/// that cannot be read, a missing `<out>/encryption_key`, or an `--key-file` that differs
/// from an existing `<out>/encryption_key`; [`EtlError::InvalidPatient`] for the first row
/// of a partition whose patient number Java would fail on, or that is negative; the errors
/// of [`crate::combined::build_combined`], a concept typed differently in two partitions
/// among them; I/O, CSV, config and format errors.
pub fn load_partitioned(options: &PartitionedLoadOptions) -> Result<PartitionedLoadReport> {
    let started = Instant::now();
    if options.config.is_none() && !options.no_config {
        return Err(EtlError::Usage(format!(
            "partitioned mode needs --config <file> or --no-config. Java's CSVLoader reads /opt/local/hpds_input/config.json whatever its input directory, so pass that file (in the all-in-one, hpds_input/config.json on the host) with --config, or --no-config when the Java load ran without one; {CONFIG_FILE_NAME} beside the inputs is not looked for"
        )));
    }
    let inputs = discover_partition_inputs(&options.input_dir)?;
    let names: Vec<String> = inputs.iter().map(|i| i.name.clone()).collect();
    check_partitioned_output(&options.out, &names)?;
    let (protection, install_key) = resolve_shared_key(&options.key, &options.out)?;
    let configs = resolve_config(&LoadOptions {
        config: options.config.clone(),
        no_config: options.no_config,
        ..LoadOptions::new(
            Mode::Partitioned,
            vec![options.input_dir.clone()],
            options.out.clone(),
        )
    })?;
    warn_without_all_concepts_entry(&configs);
    let settings = StoreSettings::new(
        Mode::Partitioned,
        true,
        &configs.map,
        &protection,
        options.tmp.as_deref(),
        options.spill_partitions,
        None,
    )?;
    tracing::info!(
        partitions = names.len(),
        names = %names.join(","),
        out = %options.out.display(),
        "loading partitions"
    );

    let mut staging = PartitionedStaging::create(&options.out, &names, options.combined)?;
    let pool = thread_pool(options.threads)?;
    let reports: Vec<LoadReport> = pool.install(|| {
        inputs
            .par_iter()
            .map(|input| {
                let file = InputFile {
                    path: input.path.clone(),
                    config_key: PARTITION_CONFIG_KEY.into(),
                };
                let report = build_store(&staging.partition_dir(&input.name), &[file], &settings)?;
                tracing::info!(
                    partition = %input.name,
                    concepts = report.counts.concepts,
                    accepted = report.counts.accepted,
                    patients = report.counts.patients,
                    "partition built"
                );
                Ok(report)
            })
            .collect::<Result<Vec<_>>>()
    })?;
    let combined = staging.build_combined(&protection, &pool)?;
    if let Some(key) = install_key {
        staging.stage_key(&key)?;
    }
    let partitions: BTreeMap<String, LoadReport> = names.into_iter().zip(reports).collect();
    let records = partitions
        .iter()
        .map(|(name, report)| {
            let record = PartitionRecord {
                store_id: report.store_id.clone(),
                patients_indexed: true,
            };
            (name.clone(), record)
        })
        .collect();
    staging.publish(
        &options.out,
        &records,
        combined.as_ref().map(|c| c.store_id.as_str()),
    )?;
    let report = PartitionedLoadReport {
        partitions,
        combined,
        elapsed_seconds: started.elapsed().as_secs_f64(),
    };
    tracing::info!(
        out = %options.out.display(),
        partitions = report.partitions.len(),
        seconds = report.elapsed_seconds,
        "partitions written"
    );
    Ok(report)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn names(dir: &Path) -> Result<Vec<String>> {
        Ok(discover_partition_inputs(dir)?
            .into_iter()
            .map(|i| i.name)
            .collect())
    }

    fn dir_with(files: &[&str]) -> tempfile::TempDir {
        let dir = tempfile::tempdir().unwrap();
        for file in files {
            let path = dir.path().join(file);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, b"").unwrap();
        }
        dir
    }

    #[test]
    fn partition_names_come_from_the_text_before_the_first_dash() {
        let dir = dir_with(&[
            "partition2-allConcepts.csv",
            "phs000007.c1-allConcepts.csv",
            "p3-extra-allConcepts.csv",
            "p4-xallConcepts.csv",
            "notes.csv",
            "allConcepts.csv.bak",
            "p5-allconcepts.csv",
        ]);
        std::fs::create_dir(dir.path().join("p9-allConcepts.csv")).unwrap();
        assert_eq!(
            names(dir.path()).unwrap(),
            ["p3", "p4", "partition2", "phs000007.c1"]
        );
    }

    #[test]
    fn a_bare_or_unprefixed_file_is_refused() {
        for bad in [
            "allConcepts.csv",
            "studyallConcepts.csv",
            "-allConcepts.csv",
            ".-allConcepts.csv",
            "..-allConcepts.csv",
            ".hidden-allConcepts.csv",
        ] {
            let dir = dir_with(&["p1-allConcepts.csv", bad]);
            let message = names(dir.path()).unwrap_err().to_string();
            assert!(message.contains(bad), "{bad}: {message}");
        }
        let message = names(dir_with(&["allConcepts.csv"]).path())
            .unwrap_err()
            .to_string();
        assert!(message.contains("no partition prefix"), "{message}");
    }

    #[test]
    fn duplicate_prefixes_are_refused_naming_both_files() {
        let dir = dir_with(&["p1-a-allConcepts.csv", "p1-b-allConcepts.csv"]);
        let message = names(dir.path()).unwrap_err().to_string();
        assert!(
            message.contains("partition p1 has more than one file"),
            "{message}"
        );
        assert!(message.contains("p1-a-allConcepts.csv"), "{message}");
        assert!(message.contains("p1-b-allConcepts.csv"), "{message}");
    }

    #[test]
    fn nested_files_and_empty_directories_are_refused() {
        let dir = dir_with(&["p1-allConcepts.csv", "sub/p2-allConcepts.csv"]);
        let message = names(dir.path()).unwrap_err().to_string();
        assert!(message.contains("is in a subdirectory"), "{message}");
        let empty = dir_with(&["notes.csv"]);
        let message = names(empty.path()).unwrap_err().to_string();
        assert!(
            message.contains("holds no <partition>-allConcepts.csv"),
            "{message}"
        );
    }
}
