//! Opening an existing store and replacing its files in place, shared by the commands that
//! work on a store after it is built.

use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use hpds_format::{
    BLOCKS_FILE_NAME, BlockCodec, BlocksReader, CryptoKey, INDEX_FILE_NAME, PhenoIndex, Protection,
};

use crate::error::{EtlError, Result};
use crate::load::{DEFAULT_KEY_FILE_NAME, KeySource};
use crate::private_fs;

/// Resolves the protection of the store in `dir`: the key in the named file, the key in
/// `<dir>/encryption_key`, or plain.
///
/// # Errors
/// [`EtlError::Usage`] when the key file cannot be read or holds an invalid key.
pub fn resolve_protection(key: &KeySource, dir: &Path) -> Result<Protection> {
    let path = match key {
        KeySource::Plain => return Ok(Protection::Plain),
        KeySource::File(path) => path.clone(),
        KeySource::OutputDirectoryKeyFile => dir.join(DEFAULT_KEY_FILE_NAME),
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

/// A store opened for reading: its index, its blocks file and the codec for its blocks.
pub struct OpenStore {
    /// The decoded `pheno.index`.
    pub index: PhenoIndex,
    /// The opened `pheno.blocks`.
    pub reader: BlocksReader,
    /// The codec the blocks were sealed with.
    pub codec: BlockCodec,
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
        Ok(OpenStore {
            index,
            reader,
            codec,
        })
    }
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
    let tmp = temporary_sibling(target)?;
    let _ = std::fs::remove_file(&tmp);
    let result = (|| {
        let file = private_fs::create_file(&tmp).map_err(EtlError::io("creating", &tmp))?;
        let mut out = BufWriter::with_capacity(1 << 20, file);
        write(&mut out)?;
        out.flush().map_err(EtlError::io("writing", &tmp))?;
        out.get_ref()
            .sync_all()
            .map_err(EtlError::io("writing", &tmp))?;
        std::fs::rename(&tmp, target).map_err(EtlError::io("moving into place", target))?;
        let dir = match target.parent() {
            Some(p) if !p.as_os_str().is_empty() => p,
            _ => Path::new("."),
        };
        private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(&tmp);
    }
    result
}

fn temporary_sibling(target: &Path) -> Result<PathBuf> {
    let name = target
        .file_name()
        .ok_or_else(|| EtlError::Usage(format!("{} has no file name", target.display())))?
        .to_string_lossy();
    Ok(target.with_file_name(format!(".{name}.tmp-{}", std::process::id())))
}
