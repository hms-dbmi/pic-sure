//! Replacing the files of a built store in place so that a crash at any point leaves either
//! the old store or one that `hpds-etl recover` completes, never new blocks beside an old
//! index.
//!
//! A command that rewrites a store (`remove-concepts`) stages each replacement file beside
//! its target, complete and synced, then writes the journal [`PUBLISH_JOURNAL_FILE_NAME`]
//! naming every staged file, its target, its length and its XXH3-128, and the maintenance
//! log record the publish ends with. Only then are the staged files renamed over their
//! targets, `columnMeta.csv` regenerated, the record appended, and the journal removed.
//!
//! Before the journal is synced nothing of the store has changed, and the staged files are
//! orphans a later [`recover`] removes. Once it is, every staged file is complete, so
//! [`recover`] always rolls forward: it renames each staged file still present over its
//! target after checking its length and hash, checks that a target already renamed has
//! them, regenerates `columnMeta.csv`, appends the record unless the log already holds it,
//! and removes the journal. A file that matches neither is refused rather than published.
//! While the journal exists the query service refuses to open the directory, and the
//! other `hpds-etl` commands refuse to read it.
//!
//! The hash guards against a torn or substituted file after a crash, not against someone
//! who can write the store directory; XXH3-128 is used because the blocks file can be
//! many gigabytes. The journal lives beside the store files instead of inside the index,
//! so the store format is unchanged.

use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;

use hpds_format::{BLOCKS_FILE_NAME, INDEX_FILE_NAME, PUBLISH_JOURNAL_FILE_NAME, Protection};
use serde::{Deserialize, Serialize};
use xxhash_rust::xxh3::{Xxh3, xxh3_128};

use crate::error::{EtlError, Result};
use crate::load::COLUMN_META_CSV_NAME;
use crate::maintenance_log;
use crate::private_fs;
use crate::store::{
    GENERATION_FILE_NAME, ServingGuard, StagedFile, read_index, replace_file, sync_parent,
};

/// The points of a publish after which a test can stop it as a crash would, leaving every
/// file as it is ([`StopAfter`]).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PublishStep {
    /// Every replacement file is staged and synced; no journal yet.
    Staged,
    /// The journal is written and synced; no store file replaced yet.
    Journaled,
    /// `pheno.blocks` is replaced.
    BlocksMoved,
    /// `pheno.index` is replaced.
    IndexMoved,
    /// `columnMeta.csv` is regenerated.
    ColumnMetaWritten,
    /// The maintenance log record is appended; the journal remains.
    Recorded,
}

/// Where a test stops a publish ([`PublishStep`]); `None` in every real run.
pub type StopAfter = Option<PublishStep>;

/// Result of a removal record whose publish `recover` finished.
pub const RESULT_COMPLETED_BY_RECOVER: &str = "completed-by-recover";

/// Names of the store files a publish may replace, in the order it renames them.
const REPLACEABLE: [&str; 2] = [BLOCKS_FILE_NAME, INDEX_FILE_NAME];

/// The files in a store directory that are refused when they are symbolic links: renaming
/// over a link replaces the link and leaves the file it points to, with whatever it holds,
/// in place.
const STORE_FILES: [&str; 6] = [
    INDEX_FILE_NAME,
    BLOCKS_FILE_NAME,
    COLUMN_META_CSV_NAME,
    GENERATION_FILE_NAME,
    PUBLISH_JOURNAL_FILE_NAME,
    maintenance_log::MAINTENANCE_LOG_FILE_NAME,
];

/// The journal file's content.
#[derive(Debug, Serialize, Deserialize)]
struct Journal {
    operation: String,
    publish_id: String,
    files: Vec<JournalFile>,
    record: serde_json::Value,
}

/// One staged file of a journal.
#[derive(Debug, Serialize, Deserialize)]
struct JournalFile {
    staged: String,
    target: String,
    bytes: u64,
    xxh3_128: String,
}

/// A staged replacement of one store file, with the length and XXH3-128 it was written
/// with.
pub(crate) struct Replacement {
    staged: StagedFile,
    bytes: u64,
    hash: u128,
}

impl Replacement {
    /// The replacement `staged`, written as `bytes` bytes hashing to `hash`.
    pub(crate) fn new(staged: StagedFile, bytes: u64, hash: u128) -> Self {
        Replacement {
            staged,
            bytes,
            hash,
        }
    }

    /// The replacement of `target` by `bytes`, staged and synced beside it.
    ///
    /// # Errors
    /// The errors of [`StagedFile::write`].
    pub(crate) fn of_bytes(target: &Path, bytes: &[u8]) -> Result<Self> {
        let staged = StagedFile::write(target, |out| {
            out.write_all(bytes)
                .map_err(EtlError::io("writing", target))
        })?;
        Ok(Replacement::new(
            staged,
            bytes.len() as u64,
            xxh3_128(bytes),
        ))
    }
}

/// A writer that counts and hashes what passes through it to `inner`.
pub(crate) struct HashingWriter<W: Write> {
    inner: W,
    hasher: Xxh3,
    bytes: u64,
}

impl<W: Write> HashingWriter<W> {
    /// A writer over `inner`.
    pub(crate) fn new(inner: W) -> Self {
        HashingWriter {
            inner,
            hasher: Xxh3::new(),
            bytes: 0,
        }
    }

    /// The bytes written and their XXH3-128.
    pub(crate) fn digest(&self) -> (u64, u128) {
        (self.bytes, self.hasher.digest128())
    }
}

impl<W: Write> Write for HashingWriter<W> {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let written = self.inner.write(buf)?;
        self.hasher.update(&buf[..written]);
        self.bytes += written as u64;
        Ok(written)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

/// Refuses the store in `dir` when one of its files is a symbolic link or not a regular
/// file.
///
/// # Errors
/// [`EtlError::Usage`] naming the file; I/O errors reading its metadata.
pub fn refuse_linked_store_files(dir: &Path) -> Result<()> {
    for name in STORE_FILES {
        let path = dir.join(name);
        match std::fs::symlink_metadata(&path) {
            Ok(meta) if meta.file_type().is_file() => {}
            Ok(_) => {
                return Err(EtlError::Usage(format!(
                    "{} is a symbolic link or not a regular file; replacing it would leave what it points to in place, so the store is not changed in place",
                    path.display()
                )));
            }
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
            Err(e) => return Err(EtlError::io("reading", &path)(e)),
        }
    }
    Ok(())
}

/// Refuses a store in `dir` whose publish was interrupted, naming `hpds-etl recover`.
///
/// # Errors
/// [`EtlError::Usage`] when `dir` holds a [`PUBLISH_JOURNAL_FILE_NAME`].
pub fn refuse_pending_publish(dir: &Path) -> Result<()> {
    let journal = dir.join(PUBLISH_JOURNAL_FILE_NAME);
    if std::fs::symlink_metadata(&journal).is_ok() {
        return Err(EtlError::Usage(format!(
            "{} holds {PUBLISH_JOURNAL_FILE_NAME}: an hpds-etl command was interrupted while replacing the store files. Run hpds-etl recover --store {} to finish it, then retry",
            dir.display(),
            dir.display()
        )));
    }
    Ok(())
}

/// Publishes `replacements` (staged files, renamed in this order) into the store in `dir`
/// through the journal, for `operation`, ending with `record` appended to the maintenance
/// log with result [`crate::store_maintenance::RESULT_PUBLISHED`]. `column_meta` regenerates
/// `columnMeta.csv` once the files are in place. The caller holds the store lock and
/// `serving`, to which the staged index is added before the journal is written, so a
/// service that opens the new index waits until the publish is done. An interrupt waits
/// until the journal is removed.
///
/// # Errors
/// I/O errors writing the journal, renaming, syncing or appending, the error of
/// `column_meta`, and [`EtlError::Usage`] when `stop_after` stops the publish.
pub(crate) fn publish(
    dir: &Path,
    operation: &str,
    replacements: Vec<Replacement>,
    record: serde_json::Value,
    column_meta: impl FnOnce() -> Result<()>,
    serving: &mut ServingGuard,
    stop_after: StopAfter,
) -> Result<()> {
    let stop = |step: PublishStep| stop_after == Some(step);
    for replacement in &replacements {
        if replacement.staged.target().ends_with(INDEX_FILE_NAME) {
            serving.hold(replacement.staged.path())?;
        }
    }
    let publish_id = hpds_format::StoreId::random()?.to_string();
    let mut files = Vec::with_capacity(replacements.len());
    for replacement in &replacements {
        files.push(JournalFile {
            staged: file_name(replacement.staged.path())?,
            target: file_name(replacement.staged.target())?,
            bytes: replacement.bytes,
            xxh3_128: format!("{:032x}", replacement.hash),
        });
    }
    if stop(PublishStep::Staged) {
        return Err(stopped(replacements, PublishStep::Staged));
    }
    let mut record = record;
    record["publish_id"] = publish_id.clone().into();
    let journal = Journal {
        operation: operation.to_owned(),
        publish_id,
        files,
        record,
    };
    let journal_path = dir.join(PUBLISH_JOURNAL_FILE_NAME);
    let text = serde_json::to_vec_pretty(&journal).map_err(|e| EtlError::Usage(e.to_string()))?;
    replace_file(&journal_path, |out| {
        out.write_all(&text)
            .map_err(EtlError::io("writing", &journal_path))
    })?;
    if stop(PublishStep::Journaled) {
        return Err(stopped(replacements, PublishStep::Journaled));
    }
    let interrupts = crate::cleanup::lock();
    let mut remaining = replacements.into_iter();
    while let Some(replacement) = remaining.next() {
        let target = replacement.staged.target().to_path_buf();
        replacement.staged.commit()?;
        let step = if target.ends_with(BLOCKS_FILE_NAME) {
            PublishStep::BlocksMoved
        } else {
            PublishStep::IndexMoved
        };
        if stop(step) {
            sync_parent(&target)?;
            return Err(stopped(remaining.collect(), step));
        }
    }
    private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
    column_meta()?;
    if stop(PublishStep::ColumnMetaWritten) {
        return Err(stopped(Vec::new(), PublishStep::ColumnMetaWritten));
    }
    maintenance_log::append(
        dir,
        &with_result(journal.record, crate::store_maintenance::RESULT_PUBLISHED),
    )?;
    if stop(PublishStep::Recorded) {
        return Err(stopped(Vec::new(), PublishStep::Recorded));
    }
    std::fs::remove_file(&journal_path).map_err(EtlError::io("removing", &journal_path))?;
    private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
    drop(interrupts);
    Ok(())
}

/// What [`recover`] did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct RecoverReport {
    /// The operation whose interrupted publish was finished, or `None` when there was none.
    pub finished: Option<String>,
    /// Staged files renamed over their targets.
    pub files_moved: u64,
    /// Whether the maintenance log record of the finished publish was appended now (false
    /// when the interrupted run had appended it).
    pub recorded: bool,
    /// Temporary files of interrupted runs removed: staged replacements never journaled.
    pub orphans_removed: u64,
}

/// Finishes the interrupted publish of the store in `dir`, if any, and removes temporary
/// files an interrupted run left, as described in the module documentation. The caller
/// holds the store lock; this takes the [`ServingGuard`]. `protection` opens the store's
/// index to regenerate `columnMeta.csv`.
///
/// # Errors
/// [`EtlError::Usage`] for a store held by a running query service, a store file that is a
/// symbolic link, a journal that cannot be parsed or names unexpected files, and a staged
/// or target file whose length or hash differs from the journal's; the errors of renaming,
/// reading the index, writing `columnMeta.csv` and appending to the log.
pub fn recover(dir: &Path, protection: &Protection) -> Result<RecoverReport> {
    recover_held(dir, protection, &mut ServingGuard::acquire(dir)?)
}

/// [`recover`] for a caller that already holds the store's [`ServingGuard`], to which each
/// staged index is added before it is renamed into place.
///
/// # Errors
/// As for [`recover`].
pub(crate) fn recover_held(
    dir: &Path,
    protection: &Protection,
    serving: &mut ServingGuard,
) -> Result<RecoverReport> {
    refuse_linked_store_files(dir)?;
    let mut report = RecoverReport::default();
    let journal_path = dir.join(PUBLISH_JOURNAL_FILE_NAME);
    let journal = match std::fs::read(&journal_path) {
        Ok(bytes) => Some(parse_journal(&journal_path, &bytes)?),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => None,
        Err(e) => return Err(EtlError::io("reading", &journal_path)(e)),
    };
    if let Some(journal) = journal {
        let interrupts = crate::cleanup::lock();
        for file in &journal.files {
            let staged = dir.join(&file.staged);
            let target = dir.join(&file.target);
            if std::fs::symlink_metadata(&staged).is_ok() {
                check_file(&staged, file)?;
                if file.target == INDEX_FILE_NAME {
                    serving.hold(&staged)?;
                }
                std::fs::rename(&staged, &target)
                    .map_err(EtlError::io("moving into place", &target))?;
                report.files_moved += 1;
            } else {
                check_file(&target, file)?;
            }
        }
        private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
        let index = read_index(dir, protection)?;
        crate::store_maintenance::write_column_meta_csv_file(
            &index,
            &dir.join(COLUMN_META_CSV_NAME),
        )?;
        let logged = maintenance_log::records(dir)?
            .iter()
            .any(|r| r["publish_id"] == journal.publish_id.as_str());
        if !logged {
            maintenance_log::append(
                dir,
                &with_result(journal.record, RESULT_COMPLETED_BY_RECOVER),
            )?;
            report.recorded = true;
        }
        std::fs::remove_file(&journal_path).map_err(EtlError::io("removing", &journal_path))?;
        private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
        drop(interrupts);
        tracing::warn!(
            operation = %journal.operation,
            files_moved = report.files_moved,
            "finished an interrupted publish"
        );
        report.finished = Some(journal.operation);
    }
    report.orphans_removed = remove_orphans(dir)?;
    Ok(report)
}

/// Parses the journal at `path` and checks that it names only staged replacements of
/// [`REPLACEABLE`] files in this directory.
fn parse_journal(path: &Path, bytes: &[u8]) -> Result<Journal> {
    let damaged = |detail: String| {
        EtlError::Usage(format!(
            "{} is damaged ({detail}); the store may hold new blocks beside an old index. Restore the store from a backup or reload it",
            path.display()
        ))
    };
    let journal: Journal = serde_json::from_slice(bytes).map_err(|e| damaged(e.to_string()))?;
    for file in &journal.files {
        if !REPLACEABLE.contains(&file.target.as_str()) {
            return Err(damaged(format!("it names the target {:?}", file.target)));
        }
        if !is_staged_name(&file.staged, &file.target) {
            return Err(damaged(format!(
                "it names the staged file {:?}",
                file.staged
            )));
        }
    }
    Ok(journal)
}

/// True when `name` is a temporary name [`StagedFile`] gives a replacement of `target`.
fn is_staged_name(name: &str, target: &str) -> bool {
    name.strip_prefix(&format!(".{target}.tmp-"))
        .is_some_and(|tail| {
            !tail.is_empty() && tail.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
}

/// Checks that `path` holds what the journal entry `file` recorded.
fn check_file(path: &Path, file: &JournalFile) -> Result<()> {
    let (bytes, hash) = hash_file(path)?;
    if bytes != file.bytes || format!("{hash:032x}") != file.xxh3_128 {
        return Err(EtlError::Usage(format!(
            "{} is not the file the interrupted publish staged ({bytes} bytes, expected {}); the store is left as it is. Restore it from a backup or reload it",
            path.display(),
            file.bytes
        )));
    }
    Ok(())
}

/// The length and XXH3-128 of the file at `path`.
fn hash_file(path: &Path) -> Result<(u64, u128)> {
    let mut file = File::open(path).map_err(EtlError::io("reading", path))?;
    let mut hasher = Xxh3::new();
    let mut buffer = vec![0u8; 1 << 20];
    let mut bytes = 0u64;
    loop {
        let read = file
            .read(&mut buffer)
            .map_err(EtlError::io("reading", path))?;
        if read == 0 {
            return Ok((bytes, hasher.digest128()));
        }
        hasher.update(&buffer[..read]);
        bytes += read as u64;
    }
}

/// Removes the temporary files a killed run left in `dir`: staged replacements of the
/// store files and of the journal. The caller holds the store lock and has no publish
/// pending, so none belongs to a live run. Returns how many it removed.
fn remove_orphans(dir: &Path) -> Result<u64> {
    let mut removed = 0;
    let targets = [
        BLOCKS_FILE_NAME,
        INDEX_FILE_NAME,
        COLUMN_META_CSV_NAME,
        PUBLISH_JOURNAL_FILE_NAME,
    ];
    for entry in std::fs::read_dir(dir).map_err(EtlError::io("reading", dir))? {
        let entry = entry.map_err(EtlError::io("reading", dir))?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if targets.iter().any(|target| is_staged_name(&name, target))
            && entry.file_type().is_ok_and(|t| t.is_file())
        {
            let path = entry.path();
            std::fs::remove_file(&path).map_err(EtlError::io("removing", &path))?;
            removed += 1;
        }
    }
    if removed > 0 {
        private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
    }
    Ok(removed)
}

/// The file name of `path`, as UTF-8.
fn file_name(path: &Path) -> Result<String> {
    path.file_name()
        .and_then(|n| n.to_str())
        .map(str::to_owned)
        .ok_or_else(|| EtlError::Usage(format!("{} has no UTF-8 file name", path.display())))
}

/// `record` with its `result` set and its `time` set to now, in UTC.
pub(crate) fn with_result(mut record: serde_json::Value, result: &str) -> serde_json::Value {
    record["result"] = result.into();
    record["time"] = maintenance_log::utc_now().into();
    record
}

/// Leaves `replacements` staged, as a crash would, and returns the error a stopped publish
/// reports.
fn stopped(replacements: Vec<Replacement>, step: PublishStep) -> EtlError {
    for replacement in replacements {
        replacement.staged.keep();
    }
    EtlError::Usage(format!("publish stopped after {step:?}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn staged_names_are_checked() {
        assert!(is_staged_name(
            ".pheno.index.tmp-12-345-0-abcdef01",
            "pheno.index"
        ));
        for bad in [
            ".pheno.index.tmp-",
            ".pheno.index.tmp-../x",
            ".pheno.index.tmp-a/b",
            "pheno.index",
            ".pheno.blocks.tmp-1",
        ] {
            assert!(!is_staged_name(bad, "pheno.index"), "{bad}");
        }
    }
}
