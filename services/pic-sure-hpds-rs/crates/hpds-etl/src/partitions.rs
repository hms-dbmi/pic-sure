//! Consent-partitioned stores: one complete store per partition, in a subdirectory named
//! after the partition, all sharing the `encryption_key` at the root.
//!
//! ```text
//! <dir>/
//!   encryption_key
//!   <partition>/pheno.index
//!   <partition>/pheno.blocks
//!   <partition>/columnMeta.csv
//!   <partition>/etl-report.json
//! ```
//!
//! This is the layout the harmonization branch's `CSVLoader` writes with javabin files and
//! `PartitionedPhenotypicObservationStore` reads: each immediate subdirectory is a store,
//! and the service picks the ones whose names match the caller's consents. Every partition
//! has its own store id. [`StoreLayout::detect`] tells a partitioned directory from an
//! ordinary one, and [`PartitionedStaging`] writes a set of partitions and moves them into
//! place together.

use std::path::{Path, PathBuf};

use hpds_format::{BLOCKS_FILE_NAME, CryptoKey, INDEX_FILE_NAME, Protection};

use crate::cleanup::{self, TemporaryDir};
use crate::error::{EtlError, Result};
use crate::load::{
    COLUMN_META_CSV_NAME, DEFAULT_KEY_FILE_NAME, JAVA_STORE_FILE_NAMES, KeySource, REPORT_FILE_NAME,
};
use crate::private_fs;

/// One partition of a partitioned store or export: its name and its directory.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Partition {
    /// The partition name, the subdirectory's name.
    pub name: String,
    /// The subdirectory.
    pub dir: PathBuf,
}

/// How a directory holds its data.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StoreLayout {
    /// The directory itself holds the files.
    Root,
    /// Immediate subdirectories hold the files, one per partition, in byte order of their
    /// names.
    Partitioned(Vec<Partition>),
}

impl StoreLayout {
    /// Detects the layout of `dir` by the file that marks a store (or an export):
    /// [`StoreLayout::Root`] when `<dir>/<marker>` is a file, else
    /// [`StoreLayout::Partitioned`] with every immediate subdirectory holding `marker`.
    /// Subdirectories without `marker` and hidden entries are not partitions.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `dir` holds `marker` neither itself nor in any immediate
    /// subdirectory, or a subdirectory name is not UTF-8; I/O errors listing `dir`.
    pub fn detect(dir: &Path, marker: &str) -> Result<Self> {
        if dir.join(marker).is_file() {
            return Ok(StoreLayout::Root);
        }
        let partitions = partition_dirs(dir, marker)?;
        if partitions.is_empty() {
            return Err(EtlError::Usage(format!(
                "{} holds no {marker}, neither itself nor in a partition subdirectory",
                dir.display()
            )));
        }
        Ok(StoreLayout::Partitioned(partitions))
    }
}

/// The immediate, non-hidden subdirectories of `dir` that hold the file `marker`, in byte
/// order of their names. Empty when `dir` does not exist.
///
/// # Errors
/// [`EtlError::Usage`] for a subdirectory name that is not UTF-8; I/O errors listing `dir`.
pub fn partition_dirs(dir: &Path, marker: &str) -> Result<Vec<Partition>> {
    if !dir.is_dir() {
        return Ok(Vec::new());
    }
    let mut partitions = Vec::new();
    for entry in std::fs::read_dir(dir).map_err(EtlError::io("listing", dir))? {
        let entry = entry.map_err(EtlError::io("listing", dir))?;
        let path = entry.path();
        if !path.is_dir() || !path.join(marker).is_file() {
            continue;
        }
        let name = entry.file_name().into_string().map_err(|name| {
            EtlError::Usage(format!(
                "partition directory {:?} in {} has a name that is not UTF-8",
                name,
                dir.display()
            ))
        })?;
        if name.starts_with('.') {
            continue;
        }
        partitions.push(Partition { name, dir: path });
    }
    partitions.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(partitions)
}

/// Checks that `name` can be a partition directory: not empty, not `.` or `..`, not
/// hidden, and without a path separator.
///
/// # Errors
/// [`EtlError::Usage`] saying why it cannot.
pub fn check_partition_name(name: &str) -> Result<()> {
    let problem = if name.is_empty() {
        "is empty"
    } else if name == "." || name == ".." {
        "is a relative directory name"
    } else if name.starts_with('.') {
        "starts with '.', which hides the partition"
    } else if name.contains(['/', '\\', '\0']) {
        "contains a path separator"
    } else {
        return Ok(());
    };
    Err(EtlError::Usage(format!(
        "partition name {name:?} {problem}"
    )))
}

/// Refuses an output directory that a set of partitions must not be written into: one that
/// is not a directory, holds an unpartitioned Rust or Java store at its root, or holds
/// partitions this write would not replace (they would be served beside the new ones), and
/// a partition subdirectory that holds a Java store or is not a directory.
///
/// # Errors
/// [`EtlError::Usage`] naming what makes `out` unsuitable.
pub fn check_partitioned_output(out: &Path, names: &[String]) -> Result<()> {
    if !out.exists() {
        return Ok(());
    }
    if !out.is_dir() {
        return Err(EtlError::Usage(format!(
            "{} exists and is not a directory",
            out.display()
        )));
    }
    for name in [INDEX_FILE_NAME].into_iter().chain(JAVA_STORE_FILE_NAMES) {
        if out.join(name).exists() {
            return Err(EtlError::Usage(format!(
                "{} holds an unpartitioned store ({name}); write the partitions to another directory",
                out.display()
            )));
        }
    }
    for marker in [INDEX_FILE_NAME].into_iter().chain(JAVA_STORE_FILE_NAMES) {
        let stale: Vec<String> = partition_dirs(out, marker)?
            .into_iter()
            .map(|p| p.name)
            .filter(|name| !names.contains(name))
            .collect();
        if !stale.is_empty() {
            return Err(EtlError::Usage(format!(
                "{} holds partitions this run does not write ({}), which would be served beside the new ones; remove them or write to another directory",
                out.display(),
                stale.join(", ")
            )));
        }
    }
    for name in names {
        let dir = out.join(name);
        if dir.exists() && !dir.is_dir() {
            return Err(EtlError::Usage(format!(
                "{} exists and is not a directory",
                dir.display()
            )));
        }
        for java in JAVA_STORE_FILE_NAMES {
            if dir.join(java).exists() {
                return Err(EtlError::Usage(format!(
                    "{} holds a Java store ({java}); write the Rust store to another directory",
                    dir.display()
                )));
            }
        }
    }
    Ok(())
}

/// Resolves the key every partition of `out` is encrypted with, and the key file to install
/// as `<out>/encryption_key` because it is not there yet.
///
/// [`KeySource::File`] loads the named file, which must equal an existing
/// `<out>/encryption_key` byte for byte, and is installed when there is none.
/// [`KeySource::OutputDirectoryKeyFile`] and [`KeySource::StoreDirectory`] load
/// `<out>/encryption_key`. [`KeySource::Plain`] is refused when `<out>` holds a key.
///
/// # Errors
/// [`EtlError::Usage`] for an unreadable or invalid key, a key file that differs from the
/// installed one, or a plain store over a directory with a key.
pub fn resolve_shared_key(key: &KeySource, out: &Path) -> Result<(Protection, Option<PathBuf>)> {
    let installed = out.join(DEFAULT_KEY_FILE_NAME);
    let source = match key {
        KeySource::Plain => {
            if installed.exists() {
                return Err(EtlError::Usage(format!(
                    "{} holds an {DEFAULT_KEY_FILE_NAME}; plain stores are for benchmarking; use an output directory without a key",
                    out.display()
                )));
            }
            return Ok((Protection::Plain, None));
        }
        KeySource::File(path) => path.clone(),
        KeySource::OutputDirectoryKeyFile | KeySource::StoreDirectory => installed.clone(),
    };
    let loaded = CryptoKey::load(&source).map_err(|e| {
        EtlError::Usage(format!(
            "cannot load the encryption key {}: {e}",
            source.display()
        ))
    })?;
    let install = match key {
        KeySource::File(path) if installed.exists() => {
            let same = std::fs::read(path).map_err(EtlError::io("reading", path))?
                == std::fs::read(&installed).map_err(EtlError::io("reading", &installed))?;
            if !same {
                return Err(EtlError::Usage(format!(
                    "{} differs from the existing {}; every partition shares the root key",
                    path.display(),
                    installed.display()
                )));
            }
            None
        }
        KeySource::File(path) => Some(path.clone()),
        _ => None,
    };
    Ok((Protection::Encrypted(loaded), install))
}

/// A directory beside the output in which every partition is built, moved into place only
/// once all of them are complete. Removed on drop, and on SIGTERM or SIGINT, unless
/// published.
pub struct PartitionedStaging {
    dir: TemporaryDir,
    names: Vec<String>,
    key: bool,
}

impl PartitionedStaging {
    /// Creates the staging directory for `out` (`.<out name>.staging-<pid>-<nanos>` beside
    /// it, mode `0700`) with one empty subdirectory per name, first removing any staging
    /// directory a killed run left beside `out`.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `out` has no name or a name is not a valid partition name;
    /// I/O errors.
    pub fn create(out: &Path, names: &[String]) -> Result<Self> {
        let name = out
            .file_name()
            .ok_or_else(|| EtlError::Usage(format!("{} has no directory name", out.display())))?
            .to_string_lossy()
            .into_owned();
        for partition in names {
            check_partition_name(partition)?;
        }
        let dir = TemporaryDir::create(&parent_dir(out), &format!("{name}.staging"))?;
        for partition in names {
            let path = dir.path().join(partition);
            private_fs::create_dir(&path).map_err(EtlError::io("creating", &path))?;
        }
        Ok(PartitionedStaging {
            dir,
            names: names.to_vec(),
            key: false,
        })
    }

    /// The staging directory of partition `name`.
    pub fn partition_dir(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    /// Stages a copy of the key file `source` as the root `encryption_key`, mode `0600`, to
    /// be published with the partitions.
    ///
    /// # Errors
    /// I/O errors reading or writing the key.
    pub fn stage_key(&mut self, source: &Path) -> Result<()> {
        let bytes = std::fs::read(source).map_err(EtlError::io("reading", source))?;
        let target = self.dir.path().join(DEFAULT_KEY_FILE_NAME);
        private_fs::write_file(&target, &bytes).map_err(EtlError::io("writing", &target))?;
        self.key = true;
        Ok(())
    }

    /// Moves every staged partition, and the staged key, into `out`.
    ///
    /// When `out` does not exist the staging directory is synced and renamed to it, so the
    /// whole set appears at once. When it does, each partition directory that `out` lacks is
    /// renamed into it, and each that `out` already has gets its files renamed over the old
    /// ones with `pheno.index` last, as [`crate::load`] replaces a single store; the key is
    /// renamed last. An interrupt waits until the moves are done.
    ///
    /// # Errors
    /// I/O errors syncing or renaming.
    pub fn publish(mut self, out: &Path) -> Result<()> {
        let staged = self.dir.path().to_path_buf();
        for name in &self.names {
            let dir = staged.join(name);
            private_fs::sync_dir(&dir).map_err(EtlError::io("syncing", &dir))?;
        }
        private_fs::sync_dir(&staged).map_err(EtlError::io("syncing", &staged))?;
        let mut registered = cleanup::lock();
        if !out.exists() {
            std::fs::rename(&staged, out).map_err(EtlError::io("moving the store to", out))?;
            self.dir.disarm(&mut registered);
            drop(registered);
            let parent = parent_dir(out);
            return private_fs::sync_dir(&parent).map_err(EtlError::io("syncing", &parent));
        }
        for name in &self.names {
            let target = out.join(name);
            if target.exists() {
                replace_store_files(&staged.join(name), &target)?;
            } else {
                std::fs::rename(staged.join(name), &target)
                    .map_err(EtlError::io("moving the partition to", &target))?;
            }
        }
        if self.key {
            let target = out.join(DEFAULT_KEY_FILE_NAME);
            std::fs::rename(staged.join(DEFAULT_KEY_FILE_NAME), &target)
                .map_err(EtlError::io("moving into place", &target))?;
        }
        drop(registered);
        private_fs::sync_dir(out).map_err(EtlError::io("syncing", out))
    }
}

/// Renames the store files of the staged store in `staged` over those in the existing
/// directory `out`: `pheno.blocks`, `columnMeta.csv` and `etl-report.json` first and
/// `pheno.index` last, so a reader that finds the new index finds the new blocks, then
/// syncs `out`.
///
/// # Errors
/// I/O errors renaming or syncing.
pub(crate) fn replace_store_files(staged: &Path, out: &Path) -> Result<()> {
    for name in [
        BLOCKS_FILE_NAME,
        COLUMN_META_CSV_NAME,
        REPORT_FILE_NAME,
        INDEX_FILE_NAME,
    ] {
        let target = out.join(name);
        std::fs::rename(staged.join(name), &target)
            .map_err(EtlError::io("moving into place", &target))?;
    }
    private_fs::sync_dir(out).map_err(EtlError::io("syncing", out))
}

fn parent_dir(path: &Path) -> PathBuf {
    match path.parent() {
        Some(p) if !p.as_os_str().is_empty() => p.to_path_buf(),
        _ => PathBuf::from("."),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn partition_names_are_checked() {
        for good in ["partition1", "phs000007.c1", "a b", "x-y"] {
            assert!(check_partition_name(good).is_ok(), "{good}");
        }
        for bad in ["", ".", "..", ".hidden", "a/b", "a\\b"] {
            assert!(check_partition_name(bad).is_err(), "{bad:?}");
        }
    }

    #[test]
    fn layout_is_root_partitioned_or_refused() {
        let dir = tempfile::tempdir().unwrap();
        assert!(StoreLayout::detect(dir.path(), INDEX_FILE_NAME).is_err());
        for name in ["b", "a", ".hidden"] {
            std::fs::create_dir(dir.path().join(name)).unwrap();
            std::fs::write(dir.path().join(name).join(INDEX_FILE_NAME), b"x").unwrap();
        }
        std::fs::create_dir(dir.path().join("empty")).unwrap();
        let StoreLayout::Partitioned(partitions) =
            StoreLayout::detect(dir.path(), INDEX_FILE_NAME).unwrap()
        else {
            panic!("expected partitions");
        };
        let names: Vec<&str> = partitions.iter().map(|p| p.name.as_str()).collect();
        assert_eq!(names, ["a", "b"]);
        std::fs::write(dir.path().join(INDEX_FILE_NAME), b"x").unwrap();
        assert_eq!(
            StoreLayout::detect(dir.path(), INDEX_FILE_NAME).unwrap(),
            StoreLayout::Root
        );
    }

    #[test]
    fn output_with_other_partitions_or_a_root_store_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("out");
        let names = vec!["p1".to_string()];
        assert!(check_partitioned_output(&out, &names).is_ok());
        std::fs::create_dir_all(out.join("p1")).unwrap();
        std::fs::write(out.join("p1").join(INDEX_FILE_NAME), b"x").unwrap();
        assert!(check_partitioned_output(&out, &names).is_ok());
        std::fs::create_dir_all(out.join("old")).unwrap();
        std::fs::write(out.join("old").join(INDEX_FILE_NAME), b"x").unwrap();
        let message = check_partitioned_output(&out, &names)
            .unwrap_err()
            .to_string();
        assert!(message.contains("(old)"), "{message}");
        std::fs::remove_dir_all(out.join("old")).unwrap();
        std::fs::write(out.join("p1").join("columnMeta.javabin"), b"x").unwrap();
        assert!(check_partitioned_output(&out, &names).is_err());
        std::fs::remove_file(out.join("p1").join("columnMeta.javabin")).unwrap();
        std::fs::write(out.join(INDEX_FILE_NAME), b"x").unwrap();
        assert!(check_partitioned_output(&out, &names).is_err());
    }
}
