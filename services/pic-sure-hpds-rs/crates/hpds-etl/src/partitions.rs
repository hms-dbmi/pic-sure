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
//! has its own store id, and `<dir>/.generation` lists them once a publish is complete (see
//! [`GENERATION_FILE_NAME`]). A load may also write the combined store in
//! `<dir>/.combined/` ([`crate::combined`]), which is never a partition. [`partition_dirs`]
//! holds the partition-directory rule, [`StoreLayout::detect`] tells a partitioned
//! directory from an ordinary one, and [`PartitionedStaging`] writes a set of partitions,
//! and optionally their combined store, and moves them into place.

use std::collections::BTreeMap;
use std::io::Write;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};

use hpds_format::{BLOCKS_FILE_NAME, CryptoKey, INDEX_FILE_NAME, Protection};

use crate::cleanup::{self, TemporaryDir};
use crate::combined::{COMBINED_DIR_NAME, CombinedReport, build_combined, generation_line};
use crate::error::{EtlError, Result};
use crate::load::{
    COLUMN_META_CSV_NAME, DEFAULT_KEY_FILE_NAME, JAVA_STORE_FILE_NAMES, KeySource, REPORT_FILE_NAME,
};
use crate::private_fs;
use crate::store::replace_file;

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

/// The partitions of the store directory `dir`, in byte order of their names: the
/// partition-directory rule of [`partition_dirs_marked_by`] with `pheno.index` as the
/// marker. The query engine applies the same rule when it opens a partitioned store.
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

/// Checks that `name` can be a partition directory: not empty, not `.` or `..`, not
/// hidden, and without a path separator. Refusing every name that starts with `.` also
/// refuses the combined store's directory name ([`COMBINED_DIR_NAME`]) and the
/// `.generation` marker's.
///
/// # Errors
/// [`EtlError::Usage`] saying why it cannot.
pub fn check_partition_name(name: &str) -> Result<()> {
    let problem = if name.is_empty() {
        "is empty"
    } else if name == "." || name == ".." {
        "is a relative directory name"
    } else if name == COMBINED_DIR_NAME {
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

/// Refuses an output directory that a set of partitions must not be written into: one that
/// is not a directory, holds an unpartitioned Rust or Java store at its root, breaks the
/// partition-directory rule (see [`partition_dirs_marked_by`]), holds partitions this
/// write would not replace (they would be served beside the new ones), or holds a
/// [`COMBINED_DIR_NAME`] that is not a directory, and a partition subdirectory that holds a
/// Java store or is not a directory.
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
    let combined = out.join(COMBINED_DIR_NAME);
    if combined.symlink_metadata().is_ok_and(|m| !m.is_dir()) {
        return Err(EtlError::Usage(format!(
            "{} exists and is not a directory; it is where the combined store goes",
            combined.display()
        )));
    }
    let stale: Vec<String> = partition_dirs(out)?
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

/// Name of the generation marker of a partitioned store: one line per partition, in byte
/// order of the partition names, holding the store id of its `pheno.index` as 32 lowercase
/// hex digits, then, when the store has a combined store, its id and the tag `.combined`
/// on a last line ([`generation_line`]). It is written last, atomically and mode `0600`, so
/// its ids match the stores' indexes only once a publish is complete; the query engine
/// refuses to open a store where they differ.
pub const GENERATION_FILE_NAME: &str = ".generation";

/// The store ids of a partitioned store as its [`GENERATION_FILE_NAME`] marker lists them.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Generation {
    /// The partitions' store ids, lowercase, in file order.
    pub partitions: Vec<String>,
    /// The combined store's id, lowercase, when a line is tagged `.combined`.
    pub combined: Option<String>,
}

impl Generation {
    /// Reads `<dir>/.generation`, or `None` when there is none.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when a line's first token is not 32 hex digits or two lines are
    /// tagged `.combined`; I/O errors reading the file.
    pub fn read(dir: &Path) -> Result<Option<Self>> {
        let path = dir.join(GENERATION_FILE_NAME);
        let text = match std::fs::read_to_string(&path) {
            Ok(text) => text,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
            Err(e) => return Err(EtlError::io("reading", &path)(e)),
        };
        let mut generation = Generation::default();
        for line in text.lines() {
            let mut tokens = line.split_whitespace();
            let Some(id) = tokens.next() else {
                continue;
            };
            if id.len() != 32 || !id.bytes().all(|b| b.is_ascii_hexdigit()) {
                return Err(EtlError::Usage(format!(
                    "{}: {id:?} is not a store id of 32 hex digits",
                    path.display()
                )));
            }
            let id = id.to_ascii_lowercase();
            if tokens.next() == Some(crate::combined::COMBINED_GENERATION_TAG) {
                if generation.combined.replace(id).is_some() {
                    return Err(EtlError::Usage(format!(
                        "{} lists more than one combined store",
                        path.display()
                    )));
                }
            } else {
                generation.partitions.push(id);
            }
        }
        Ok(Some(generation))
    }
}

/// A directory beside the output in which every partition, and optionally their combined
/// store, is built and from which the set is published. Removed on drop, and on SIGTERM or
/// SIGINT, unless published.
pub struct PartitionedStaging {
    dir: TemporaryDir,
    names: Vec<String>,
    key: bool,
    combined: bool,
}

impl PartitionedStaging {
    /// Creates the staging directory for `out` (`.<out name>.staging-<unique>` beside it,
    /// mode `0700`) with one empty subdirectory per name, and an empty
    /// [`COMBINED_DIR_NAME`] when `combined` is set, first removing any staging directory
    /// a killed run left beside `out`.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `out` has no name, a name is not a valid partition name, or
    /// `out`, one of its partition directories or its combined store directory is on
    /// another filesystem than the staging directory (see [`check_same_filesystem`]); I/O
    /// errors.
    pub fn create(out: &Path, names: &[String], combined: bool) -> Result<Self> {
        let name = out
            .file_name()
            .ok_or_else(|| EtlError::Usage(format!("{} has no directory name", out.display())))?
            .to_string_lossy()
            .into_owned();
        for partition in names {
            check_partition_name(partition)?;
        }
        let parent = parent_dir(out);
        let targets: Vec<PathBuf> = std::iter::once(out.to_path_buf())
            .chain(names.iter().map(|n| out.join(n)))
            .chain(std::iter::once(out.join(COMBINED_DIR_NAME)))
            .collect();
        check_same_filesystem(&parent, &targets)?;
        let dir = TemporaryDir::create(&parent, &format!("{name}.staging"))?;
        for partition in names
            .iter()
            .map(String::as_str)
            .chain(combined.then_some(COMBINED_DIR_NAME))
        {
            let path = dir.path().join(partition);
            private_fs::create_dir(&path).map_err(EtlError::io("creating", &path))?;
        }
        Ok(PartitionedStaging {
            dir,
            names: names.to_vec(),
            key: false,
            combined,
        })
    }

    /// The staging directory of partition `name`.
    pub fn partition_dir(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    /// Builds the combined store of the staged partitions into the staged
    /// [`COMBINED_DIR_NAME`] (see [`build_combined`]), when the staging was created with
    /// one. Call it once every partition is complete. When [`build_combined`] writes none
    /// (a single partition, or two partitions typing a concept differently), the staged
    /// directory is removed and the publish then removes any combined store `out` has, as
    /// with `--no-combined`.
    ///
    /// # Errors
    /// The errors of [`build_combined`], and I/O errors removing the staged directory.
    pub fn build_combined(
        &mut self,
        protection: &Protection,
        pool: &rayon::ThreadPool,
    ) -> Result<Option<CombinedReport>> {
        if !self.combined {
            return Ok(None);
        }
        let partitions: Vec<Partition> = self
            .names
            .iter()
            .map(|name| Partition {
                name: name.clone(),
                dir: self.partition_dir(name),
            })
            .collect();
        let dir = self.dir.path().join(COMBINED_DIR_NAME);
        let report = build_combined(&partitions, &dir, protection, pool)?;
        if report.is_none() {
            std::fs::remove_dir_all(&dir).map_err(EtlError::io("removing", &dir))?;
            self.combined = false;
        }
        Ok(report)
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

    /// Moves every staged partition, the staged combined store, and the staged key, into
    /// `out`, and writes the [`GENERATION_FILE_NAME`] marker from `store_ids` (partition
    /// name to store id) and `combined_id`, the combined store's id when one was built.
    ///
    /// When `out` does not exist the marker is staged too, and the staging directory is
    /// synced and renamed to `out`, so the whole set appears at once. When it does, the
    /// publish is not atomic across partitions: each partition directory that `out` lacks
    /// is renamed into it, and each that `out` already has gets its files renamed over the
    /// old ones with `pheno.index` last, as [`crate::load`] replaces a single store, one
    /// partition after another; then an old combined store is moved out into the staging
    /// directory (removed with it) and the new one, if any, renamed into place; then the
    /// key, and the marker last, replacing the old one. Until then a reader can see old and
    /// new stores side by side, which the marker's ids reveal. An interrupt waits until the
    /// moves are done.
    ///
    /// # Errors
    /// [`EtlError::Usage`] when `combined_id` is given without a staged combined store or
    /// the other way round; I/O errors syncing, renaming or writing the marker.
    pub fn publish(
        mut self,
        out: &Path,
        store_ids: &BTreeMap<String, String>,
        combined_id: Option<&str>,
    ) -> Result<()> {
        if self.combined != combined_id.is_some() {
            return Err(EtlError::Usage(
                "a combined store id is published exactly when a combined store was staged".into(),
            ));
        }
        let staged = self.dir.path().to_path_buf();
        for name in self
            .names
            .iter()
            .map(String::as_str)
            .chain(self.combined.then_some(COMBINED_DIR_NAME))
        {
            let dir = staged.join(name);
            private_fs::sync_dir(&dir).map_err(EtlError::io("syncing", &dir))?;
        }
        let generation = generation_text(&self.names, store_ids, combined_id)?;
        let mut registered = cleanup::lock();
        if !out.exists() {
            let marker = staged.join(GENERATION_FILE_NAME);
            private_fs::write_file(&marker, generation.as_bytes())
                .map_err(EtlError::io("writing", &marker))?;
            self.dir.release_lock()?;
            private_fs::sync_dir(&staged).map_err(EtlError::io("syncing", &staged))?;
            std::fs::rename(&staged, out).map_err(EtlError::io("moving the store to", out))?;
            self.dir.disarm(&mut registered);
            drop(registered);
            let parent = parent_dir(out);
            return private_fs::sync_dir(&parent).map_err(EtlError::io("syncing", &parent));
        }
        private_fs::sync_dir(&staged).map_err(EtlError::io("syncing", &staged))?;
        for name in &self.names {
            let target = out.join(name);
            if target.exists() {
                replace_store_files(&staged.join(name), &target)?;
            } else {
                std::fs::rename(staged.join(name), &target)
                    .map_err(EtlError::io("moving the partition to", &target))?;
            }
        }
        replace_combined_dir(
            out,
            &staged,
            self.combined.then(|| staged.join(COMBINED_DIR_NAME)),
        )?;
        if self.key {
            let target = out.join(DEFAULT_KEY_FILE_NAME);
            std::fs::rename(staged.join(DEFAULT_KEY_FILE_NAME), &target)
                .map_err(EtlError::io("moving into place", &target))?;
        }
        private_fs::sync_dir(out).map_err(EtlError::io("syncing", out))?;
        let marker = out.join(GENERATION_FILE_NAME);
        let written = replace_file(&marker, |file| {
            file.write_all(generation.as_bytes())
                .map_err(EtlError::io("writing", &marker))
        });
        drop(registered);
        written
    }
}

/// The content of the [`GENERATION_FILE_NAME`] marker for the partitions `names` and, when
/// given, the combined store `combined_id`, whose line comes last.
///
/// # Errors
/// [`EtlError::Usage`] when `store_ids` lacks one of `names`.
pub(crate) fn generation_text(
    names: &[String],
    store_ids: &BTreeMap<String, String>,
    combined_id: Option<&str>,
) -> Result<String> {
    let mut sorted: Vec<&String> = names.iter().collect();
    sorted.sort();
    let mut text = sorted
        .into_iter()
        .map(|name| {
            store_ids
                .get(name)
                .map(|id| format!("{id}\n"))
                .ok_or_else(|| EtlError::Usage(format!("partition {name} has no store id")))
        })
        .collect::<Result<String>>()?;
    if let Some(id) = combined_id {
        text.push_str(&generation_line(id));
    }
    Ok(text)
}

/// Replaces `<out>/.combined` with the store in `new`, or removes it when `new` is `None`.
/// An existing combined store is renamed into `trash_parent` (a temporary directory on the
/// same filesystem, removed with it) before the new one is renamed into place, then `out`
/// is synced.
///
/// # Errors
/// I/O errors renaming or syncing.
pub(crate) fn replace_combined_dir(
    out: &Path,
    trash_parent: &Path,
    new: Option<PathBuf>,
) -> Result<()> {
    let target = out.join(COMBINED_DIR_NAME);
    if target.symlink_metadata().is_ok() {
        let trash = trash_parent.join(format!("{COMBINED_DIR_NAME}-replaced"));
        std::fs::rename(&target, &trash).map_err(EtlError::io(
            "moving the old combined store out of",
            &target,
        ))?;
    }
    if let Some(new) = new {
        std::fs::rename(&new, &target)
            .map_err(EtlError::io("moving the combined store to", &target))?;
    }
    private_fs::sync_dir(out).map_err(EtlError::io("syncing", out))
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
        assert!(check_partitioned_output(dir.path(), &["p1".into(), "p2".into()]).is_err());

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

    #[test]
    fn the_generation_marker_lists_every_partition_in_name_order() {
        let ids: BTreeMap<String, String> = [("b", "22"), ("a", "11")]
            .into_iter()
            .map(|(n, i)| (n.to_string(), i.to_string()))
            .collect();
        assert_eq!(
            generation_text(&["b".into(), "a".into()], &ids, None).unwrap(),
            "11\n22\n"
        );
        assert_eq!(
            generation_text(&["b".into(), "a".into()], &ids, Some("33")).unwrap(),
            "11\n22\n33 .combined\n"
        );
        assert!(generation_text(&["c".into()], &ids, None).is_err());
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
