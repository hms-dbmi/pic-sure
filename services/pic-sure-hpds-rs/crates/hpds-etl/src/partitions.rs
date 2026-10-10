//! Partition directories: the per-partition exports `migrate` reads (one export directory
//! per partition) and the stores of format 1 that `convert` reads (one store per partition
//! directory, all sharing the `encryption_key` at the root), and the rules for partition
//! names.
//!
//! ```text
//! <dir>/
//!   encryption_key
//!   <partition>/pheno.index        or concepts.bin and patients.txt for an export
//!   <partition>/pheno.blocks
//! ```
//!
//! This is the layout the harmonization branch's `CSVLoader` writes with javabin files:
//! each immediate subdirectory is a partition named after it. [`partition_dirs_marked_by`]
//! holds the partition-directory rule and [`StoreLayout::detect`] tells a partitioned
//! directory from an ordinary one. A store of this format is never laid out this way: its
//! partitions live inside its one index and blocks file.

use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};

use hpds_format::{BLOCKS_FILE_NAME, CryptoKey, INDEX_FILE_NAME, Protection};

use crate::error::{EtlError, Result};
use crate::load::{
    COLUMN_META_CSV_NAME, DEFAULT_KEY_FILE_NAME, JAVA_STORE_FILE_NAMES, KeySource,
    LEGACY_COMBINED_DIR_NAME, REPORT_FILE_NAME,
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
    /// [`StoreLayout::Partitioned`] with the partitions [`partition_dirs_marked_by`] finds.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `dir` holds `marker` neither itself nor in any partition
    /// subdirectory, and every error of [`partition_dirs_marked_by`].
    pub fn detect(dir: &Path, marker: &str) -> Result<Self> {
        if dir.join(marker).is_file() {
            return Ok(StoreLayout::Root);
        }
        let partitions = partition_dirs_marked_by(dir, marker)?;
        if partitions.is_empty() {
            return Err(EtlError::Usage(format!(
                "{} holds no {marker}, neither itself nor in a partition subdirectory",
                dir.display()
            )));
        }
        Ok(StoreLayout::Partitioned(partitions))
    }
}

/// The partitions of the format 1 store directory `dir`, in byte order of their names: the
/// partition-directory rule of [`partition_dirs_marked_by`] with `pheno.index` as the
/// marker.
///
/// # Errors
/// As [`partition_dirs_marked_by`].
pub fn partition_dirs(dir: &Path) -> Result<Vec<Partition>> {
    partition_dirs_marked_by(dir, INDEX_FILE_NAME)
}

/// The partitions of `dir` by the partition-directory rule, in byte order of their names.
/// Empty when `dir` does not exist.
///
/// Only immediate subdirectories are considered, and names starting with `.` are ignored.
/// A subdirectory holding the file `marker` is a partition. One holding both
/// `allObservationsStore.javabin` and `columnMeta.javabin` but no `marker` is a Java
/// partition that was never converted, and one holding only one of the two javabin files
/// is a broken Java partition; both are errors, so a consent group is never silently
/// left out. Any other subdirectory is ignored.
///
/// # Errors
/// [`EtlError::Usage`] naming every subdirectory that breaks the rule, or a partition
/// whose name is not UTF-8; I/O errors listing `dir`.
pub fn partition_dirs_marked_by(dir: &Path, marker: &str) -> Result<Vec<Partition>> {
    if !dir.is_dir() {
        return Ok(Vec::new());
    }
    let mut partitions = Vec::new();
    let mut problems = Vec::new();
    for entry in std::fs::read_dir(dir).map_err(EtlError::io("listing", dir))? {
        let entry = entry.map_err(EtlError::io("listing", dir))?;
        if entry.file_name().as_bytes().first() == Some(&b'.') {
            continue;
        }
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let java_files: Vec<&str> = JAVA_STORE_FILE_NAMES
            .into_iter()
            .filter(|name| path.join(name).exists())
            .collect();
        if !path.join(marker).is_file() {
            match java_files.as_slice() {
                [] => {}
                [only] => problems.push(format!(
                    "{} holds {only} but not the other javabin file, a broken Java partition",
                    path.display()
                )),
                _ => problems.push(format!(
                    "{} holds a Java partition (javabin files) but no {marker}; convert it or move it out of {}",
                    path.display(),
                    dir.display()
                )),
            }
            continue;
        }
        match entry.file_name().into_string() {
            Ok(name) => partitions.push(Partition { name, dir: path }),
            Err(name) => problems.push(format!(
                "partition directory {:?} in {} has a name that is not UTF-8",
                name,
                dir.display()
            )),
        }
    }
    if !problems.is_empty() {
        problems.sort();
        return Err(EtlError::Usage(problems.join("; ")));
    }
    partitions.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(partitions)
}

/// Checks that `name` can be a partition name: not empty, not `.` or `..`, not hidden, and
/// without a path separator, so it can also name the partition's directory of an export,
/// its dump file, and the input file it is loaded from. Refusing every name that starts
/// with `.` also refuses the combined store directory name of format 1 stores and the
/// `.generation` marker's.
///
/// # Errors
/// [`EtlError::Usage`] saying why it cannot.
pub fn check_partition_name(name: &str) -> Result<()> {
    let problem = if name.is_empty() {
        "is empty"
    } else if name == "." || name == ".." {
        "is a relative directory name"
    } else if name == LEGACY_COMBINED_DIR_NAME {
        "is the combined store's directory name"
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

/// Resolves the key the store in `out` is encrypted with, and the key file to install as
/// `<out>/encryption_key` because it is not there yet.
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
                    "{} differs from the existing {}; the store keeps the key it has",
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

/// Refuses to stage a store in `staging_parent` when any existing path in `targets` (the
/// output directory, and the partition directories in it) is on another filesystem: the
/// staged files are moved into place by `rename`, which cannot cross filesystems
/// (`EXDEV`). Checked before anything is built, so a load does not fail at its very end.
///
/// # Errors
/// [`EtlError::Usage`] naming the target and the staging directory's parent; I/O errors
/// reading their metadata.
pub(crate) fn check_same_filesystem(staging_parent: &Path, targets: &[PathBuf]) -> Result<()> {
    let device = |path: &Path| {
        std::fs::metadata(path)
            .map(|m| m.dev())
            .map_err(EtlError::io("reading metadata of", path))
    };
    if !staging_parent.exists() {
        return Ok(());
    }
    let staging_device = device(staging_parent)?;
    for target in targets {
        if target.exists() && device(target)? != staging_device {
            return Err(EtlError::Usage(format!(
                "{} is on another filesystem than {}, where the new store is staged; moving it into place would fail with EXDEV (a rename cannot cross filesystems). Give an output directory that is not a mount point, such as a new directory inside it",
                target.display(),
                staging_parent.display()
            )));
        }
    }
    Ok(())
}

/// Renames the store files of the staged store in `staged` over those in the existing
/// directory `out`: `pheno.blocks`, `columnMeta.csv` and `etl-report.json` first and
/// `pheno.index` last, so a reader that finds the new index finds the new blocks, then
/// syncs `out`. The caller holds the store lock.
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn partition_names_are_checked() {
        for good in ["partition1", "phs000007.c1", "a b", "x-y"] {
            assert!(check_partition_name(good).is_ok(), "{good}");
        }
        for bad in ["", ".", "..", ".hidden", ".combined", "a/b", "a\\b"] {
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
    fn the_partition_directory_rule() {
        let dir = tempfile::tempdir().unwrap();
        let make = |name: &str, files: &[&str]| {
            let path = dir.path().join(name);
            std::fs::create_dir(&path).unwrap();
            for file in files {
                std::fs::write(path.join(file), b"x").unwrap();
            }
        };
        make("p1", &[INDEX_FILE_NAME]);
        make("p2", &[INDEX_FILE_NAME, "columnMeta.javabin"]);
        make(
            ".hidden",
            &["columnMeta.javabin", "allObservationsStore.javabin"],
        );
        make(".old", &["allObservationsStore.javabin"]);
        make("notes", &["readme.txt"]);
        make("empty", &[]);
        std::fs::write(dir.path().join("loose.javabin"), b"x").unwrap();
        let names: Vec<String> = partition_dirs(dir.path())
            .unwrap()
            .into_iter()
            .map(|p| p.name)
            .collect();
        assert_eq!(names, ["p1", "p2"]);

        make(
            "java",
            &["columnMeta.javabin", "allObservationsStore.javabin"],
        );
        make("half", &["allObservationsStore.javabin"]);
        let message = partition_dirs(dir.path()).unwrap_err().to_string();
        assert!(
            message.contains("java holds a Java partition (javabin files) but no pheno.index"),
            "{message}"
        );
        assert!(
            message.contains("half holds allObservationsStore.javabin but not the other"),
            "{message}"
        );
        assert!(StoreLayout::detect(dir.path(), INDEX_FILE_NAME).is_err());

        let export = partition_dirs_marked_by(dir.path(), "concepts.bin")
            .unwrap_err()
            .to_string();
        assert!(export.contains("but no concepts.bin"), "{export}");
    }

    #[test]
    fn staging_on_another_filesystem_is_refused_up_front() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("out");
        check_same_filesystem(dir.path(), std::slice::from_ref(&out)).unwrap();
        std::fs::create_dir(&out).unwrap();
        check_same_filesystem(dir.path(), std::slice::from_ref(&out)).unwrap();
        let message = check_same_filesystem(dir.path(), &[PathBuf::from("/dev")])
            .unwrap_err()
            .to_string();
        assert!(message.contains("EXDEV"), "{message}");
        assert!(
            message.contains("/dev is on another filesystem"),
            "{message}"
        );
    }
}
