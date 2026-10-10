//! Opening an existing store, its lock, its `.generation` marker, and replacing its files in
//! place, shared by the commands that work on a store after it is built.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use hpds_format::{
    BLOCKS_FILE_NAME, Block, BlockCodec, BlocksReader, ConceptMeta, CryptoKey, INDEX_FILE_NAME,
    Membership, PhenoIndex, Protection, StoreId,
};

use crate::error::{EtlError, Result};
use crate::load::{DEFAULT_KEY_FILE_NAME, KeySource};
use crate::private_fs;

/// Resolves the protection of the store in `dir`: the key in the named file, the key in
/// `<dir>/encryption_key` for [`KeySource::OutputDirectoryKeyFile`] and
/// [`KeySource::StoreDirectory`], or plain.
///
/// # Errors
/// [`EtlError::Usage`] when the key file cannot be read or holds an invalid key.
pub fn resolve_protection(key: &KeySource, dir: &Path) -> Result<Protection> {
    let path = match key {
        KeySource::Plain => return Ok(Protection::Plain),
        KeySource::File(path) => path.clone(),
        KeySource::OutputDirectoryKeyFile | KeySource::StoreDirectory => {
            dir.join(DEFAULT_KEY_FILE_NAME)
        }
    };
    CryptoKey::load(&path)
        .map(Protection::Encrypted)
        .map_err(|e| {
            EtlError::Usage(format!(
                "cannot load the encryption key {}: {e}",
                path.display()
            ))
        })
}

/// A store opened for reading: its index, its blocks file, the codec for its blocks and
/// its routing of patients to partitions.
pub struct OpenStore {
    /// The decoded `pheno.index`.
    pub index: PhenoIndex,
    /// The opened `pheno.blocks`.
    pub reader: BlocksReader,
    /// The codec the blocks were sealed with.
    pub codec: BlockCodec,
    /// The routing of patients to partitions the index gives.
    pub membership: Membership,
}

impl OpenStore {
    /// Opens the store in `dir` under `protection`.
    ///
    /// # Errors
    /// [`EtlError::Format`] when the index or blocks file is missing, corrupt, from another
    /// store, or protected differently from `protection`.
    pub fn open(dir: &Path, protection: Protection) -> Result<Self> {
        let index = read_index(dir, &protection)?;
        let reader = BlocksReader::open(dir.join(BLOCKS_FILE_NAME), index.store_id())?;
        let codec = index.block_codec(protection)?;
        let membership = index.membership();
        Ok(OpenStore {
            index,
            reader,
            codec,
            membership,
        })
    }

    /// Reads the block of `concept`, one of the index's concepts, verified and checked
    /// against the index and the store's partitions.
    ///
    /// # Errors
    /// [`EtlError::Format`] when the block cannot be read, authenticated or decoded, or
    /// disagrees with the index.
    pub fn read_block(&self, concept: &ConceptMeta) -> Result<Block> {
        Ok(self
            .reader
            .read_block(&self.codec, concept, &self.membership)?)
    }
}

/// Name of the generation marker of a store: one line, the store id of its `pheno.index`
/// as 32 lowercase hex digits. It is written last when a store is published, atomically
/// and mode `0600`, so it names the store's id only once the publish is complete; the query
/// service refuses a directory whose marker names another id.
pub const GENERATION_FILE_NAME: &str = ".generation";

/// The content of the [`GENERATION_FILE_NAME`] marker of the store `store_id`.
pub fn generation_text(store_id: &str) -> String {
    format!("{store_id}\n")
}

/// The store id the [`GENERATION_FILE_NAME`] marker in `dir` names, lowercase, or `None`
/// when there is no marker.
///
/// # Errors
/// [`EtlError::Usage`] when the marker does not hold exactly one store id of 32 hex digits;
/// I/O errors reading it.
pub fn read_generation(dir: &Path) -> Result<Option<String>> {
    let path = dir.join(GENERATION_FILE_NAME);
    let text = match std::fs::read_to_string(&path) {
        Ok(text) => text,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(EtlError::io("reading", &path)(e)),
    };
    let tokens: Vec<&str> = text.split_whitespace().collect();
    match tokens.as_slice() {
        [id] if id.len() == 32 && id.bytes().all(|b| b.is_ascii_hexdigit()) => {
            Ok(Some(id.to_ascii_lowercase()))
        }
        _ => Err(EtlError::Usage(format!(
            "{} does not hold one store id of 32 hex digits; it was written by an earlier ETL or damaged",
            path.display()
        ))),
    }
}

/// Writes the [`GENERATION_FILE_NAME`] marker of the store `store_id` into `dir`, replacing
/// any marker there atomically.
///
/// # Errors
/// I/O errors writing, syncing or renaming the marker.
pub(crate) fn write_generation(dir: &Path, store_id: StoreId) -> Result<()> {
    let marker = dir.join(GENERATION_FILE_NAME);
    replace_file(&marker, |file| {
        file.write_all(generation_text(&store_id.to_string()).as_bytes())
            .map_err(EtlError::io("writing", &marker))
    })
}

/// An exclusive `flock` on a store's directory itself, held by every command that writes
/// the store: the publish of `load`, `migrate` and `convert` into an existing directory,
/// `remove-concepts`, and `column-meta-csv` writing into the store. Two such commands, in
/// this process or in another container sharing the directory, never interleave their
/// writes. The lock is on the directory's inode, so no lock file is left in it, and it is
/// released when dropped. `flock` serializes processes on one kernel only, so a host
/// process and a container on another kernel (Docker Desktop's virtual machine) do not see
/// each other's lock.
pub struct StoreLock {
    _dir: File,
}

/// Takes the [`StoreLock`] of the store directory `dir`, waiting while another command
/// holds it.
///
/// # Errors
/// I/O errors opening or locking the directory.
pub fn lock_store(dir: &Path) -> Result<StoreLock> {
    let file = File::open(dir).map_err(EtlError::io("opening", dir))?;
    file.lock().map_err(EtlError::io("locking", dir))?;
    Ok(StoreLock { _dir: file })
}

/// Reads and verifies `<dir>/pheno.index`.
///
/// # Errors
/// [`EtlError::Format`] when it is missing, corrupt, or protected differently from
/// `protection`.
pub fn read_index(dir: &Path, protection: &Protection) -> Result<PhenoIndex> {
    Ok(PhenoIndex::read_file(
        dir.join(INDEX_FILE_NAME),
        protection,
    )?)
}

/// Writes `target` by calling `write` on a buffered temporary file beside it, mode `0600`,
/// syncing it, renaming it over `target` and syncing the directory, so a reader sees either
/// the old file or the complete new one. The temporary file is removed when `write` fails.
///
/// # Errors
/// I/O errors creating, writing, syncing or renaming the file, and any error from `write`.
pub fn replace_file<F>(target: &Path, write: F) -> Result<()>
where
    F: FnOnce(&mut BufWriter<File>) -> Result<()>,
{
    let staged = StagedFile::write(target, write)?;
    staged.commit()?;
    sync_parent(target)
}

/// A complete, synced temporary file beside its target, removed on drop unless committed.
pub(crate) struct StagedFile {
    tmp: PathBuf,
    target: PathBuf,
    committed: bool,
}

impl StagedFile {
    /// Writes the temporary file for `target` by calling `write` on it, then flushes and
    /// syncs it.
    ///
    /// # Errors
    /// I/O errors creating, writing or syncing the file, and any error from `write`.
    pub(crate) fn write<F>(target: &Path, write: F) -> Result<Self>
    where
        F: FnOnce(&mut BufWriter<File>) -> Result<()>,
    {
        let tmp = temporary_sibling(target)?;
        let staged = StagedFile {
            tmp,
            target: target.to_path_buf(),
            committed: false,
        };
        let file =
            private_fs::create_file(&staged.tmp).map_err(EtlError::io("creating", &staged.tmp))?;
        let mut out = BufWriter::with_capacity(1 << 20, file);
        write(&mut out)?;
        out.flush().map_err(EtlError::io("writing", &staged.tmp))?;
        out.get_ref()
            .sync_all()
            .map_err(EtlError::io("writing", &staged.tmp))?;
        Ok(staged)
    }

    /// Renames the temporary file over its target. The caller syncs the directory.
    ///
    /// # Errors
    /// The I/O error of the rename.
    pub(crate) fn commit(mut self) -> Result<()> {
        std::fs::rename(&self.tmp, &self.target)
            .map_err(EtlError::io("moving into place", &self.target))?;
        self.committed = true;
        Ok(())
    }
}

impl Drop for StagedFile {
    fn drop(&mut self) {
        if !self.committed {
            let _ = std::fs::remove_file(&self.tmp);
        }
    }
}

/// Syncs the directory holding `target`.
///
/// # Errors
/// The I/O error of the sync.
pub(crate) fn sync_parent(target: &Path) -> Result<()> {
    let dir = match target.parent() {
        Some(p) if !p.as_os_str().is_empty() => p,
        _ => Path::new("."),
    };
    private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))
}

fn temporary_sibling(target: &Path) -> Result<PathBuf> {
    let name = target
        .file_name()
        .ok_or_else(|| EtlError::Usage(format!("{} has no file name", target.display())))?
        .to_string_lossy();
    Ok(target.with_file_name(format!(
        ".{name}.tmp-{}",
        crate::cleanup::unique_name_tail()?
    )))
}
