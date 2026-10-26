//! The bounded spool a one-pass excerpt is generated into before its head is sent.
//!
//! The body is held in memory up to a byte bound. Past it, the rest goes to a spill file in
//! the results directory, which holds patient data (VCF_EXCERPT genotype cells), so it is
//! treated as an export file is: created with mode 0600 by one exclusive open under the
//! results directory checked at startup, and removed from the directory as soon as it is
//! open, so it is gone after a completed stream, an error, a client that left or a crash
//! alike. Its bytes live until the spool is dropped and its descriptor closed. A spill file
//! a crash left between its creation and its removal is removed with the export files at the
//! next start ([`SPILL_PREFIX`]).

use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, Read, Seek, SeekFrom, Write};
use std::os::unix::fs::OpenOptionsExt;
use std::path::{Path, PathBuf};

use crate::envelope::random_uuid;

/// The name every spill file starts with, so the startup clean-up of the results directory
/// can find one a crash left behind.
pub const SPILL_PREFIX: &str = "excerpt-spool-";

/// Bytes the spill file's writer gathers before writing them to the file.
const SPILL_BUFFER_BYTES: usize = 256 << 10;

/// An excerpt body held while it is generated: in memory up to `memory_bound` bytes, the
/// rest in an unlinked spill file under `dir`.
pub struct Spool {
    memory: Vec<u8>,
    memory_bound: u64,
    dir: PathBuf,
    spill: Option<BufWriter<File>>,
    spilled: u64,
}

impl Spool {
    /// An empty spool that holds up to `memory_bound` bytes in memory and spills the rest
    /// into a file in `dir`.
    pub fn new(memory_bound: u64, dir: &Path) -> Spool {
        Spool {
            memory: Vec::new(),
            memory_bound,
            dir: dir.to_path_buf(),
            spill: None,
            spilled: 0,
        }
    }

    /// The bytes held, in memory and spilled.
    pub fn len(&self) -> u64 {
        self.memory.len() as u64 + self.spilled
    }

    /// True when nothing is held.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// True once part of the body went to a spill file.
    pub fn is_spilled(&self) -> bool {
        self.spill.is_some()
    }

    /// The spill file, created on first use: opened for reading and writing with mode 0600,
    /// refusing a name that exists (a symbolic link included), then removed from the
    /// directory while it stays open.
    fn spill_file(&mut self) -> io::Result<&mut BufWriter<File>> {
        if self.spill.is_none() {
            let path = self.dir.join(format!("{SPILL_PREFIX}{}", random_uuid()));
            let file = OpenOptions::new()
                .read(true)
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&path)?;
            if let Err(error) = std::fs::remove_file(&path) {
                tracing::error!(%error, file = %path.display(), "cannot unlink an excerpt spill file");
                return Err(error);
            }
            self.spill = Some(BufWriter::with_capacity(SPILL_BUFFER_BYTES, file));
        }
        Ok(self.spill.as_mut().expect("created above"))
    }

    /// Writes the body held, in order, to `out` in pieces of at most `piece` bytes, each with
    /// one `write_all`, and drops the spool, closing the spill file.
    ///
    /// # Errors
    /// The first error of `out`, or of reading the spill file back.
    pub fn send_to(mut self, piece: usize, out: &mut dyn Write) -> io::Result<()> {
        let piece = piece.max(1);
        for part in self.memory.chunks(piece) {
            out.write_all(part)?;
        }
        self.memory = Vec::new();
        let Some(spill) = self.spill.take() else {
            return Ok(());
        };
        let mut file = spill.into_inner().map_err(io::IntoInnerError::into_error)?;
        file.seek(SeekFrom::Start(0))?;
        let mut buffer = vec![0u8; piece];
        let mut left = self.spilled;
        while left > 0 {
            let want = buffer
                .len()
                .min(usize::try_from(left).unwrap_or(usize::MAX));
            file.read_exact(&mut buffer[..want])?;
            out.write_all(&buffer[..want])?;
            left -= want as u64;
        }
        Ok(())
    }
}

impl Write for Spool {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        crate::sync::fault::on_spool_write();
        let fits = self.spill.is_none()
            && self.memory.len() as u64 + buf.len() as u64 <= self.memory_bound;
        if fits {
            self.memory.extend_from_slice(buf);
        } else {
            self.spill_file()?.write_all(buf)?;
            self.spilled += buf.len() as u64;
        }
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        match self.spill.as_mut() {
            Some(spill) => spill.flush(),
            None => Ok(()),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};

    use super::*;

    fn entries(dir: &Path) -> Vec<String> {
        std::fs::read_dir(dir)
            .unwrap()
            .map(|e| e.unwrap().file_name().to_string_lossy().into_owned())
            .collect()
    }

    fn sent(spool: Spool, piece: usize) -> (Vec<u8>, Vec<usize>) {
        struct Pieces(Vec<u8>, Vec<usize>);
        impl Write for Pieces {
            fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
                self.0.extend_from_slice(buf);
                self.1.push(buf.len());
                Ok(buf.len())
            }
            fn flush(&mut self) -> io::Result<()> {
                Ok(())
            }
        }
        let mut out = Pieces(Vec::new(), Vec::new());
        spool.send_to(piece, &mut out).unwrap();
        (out.0, out.1)
    }

    #[test]
    fn a_body_within_the_memory_bound_stays_in_memory() {
        let dir = tempfile::tempdir().unwrap();
        let mut spool = Spool::new(10, dir.path());
        spool.write_all(b"0123").unwrap();
        spool.write_all(b"456789").unwrap();
        assert!(!spool.is_spilled());
        assert_eq!(spool.len(), 10);
        assert!(entries(dir.path()).is_empty());
        assert_eq!(sent(spool, 4), (b"0123456789".to_vec(), vec![4, 4, 2]));
    }

    #[test]
    fn a_body_past_the_memory_bound_spills_to_an_owner_only_file_no_name_points_at() {
        let dir = tempfile::tempdir().unwrap();
        let mut spool = Spool::new(5, dir.path());
        spool.write_all(b"0123").unwrap();
        spool.write_all(b"4567").unwrap();
        spool.write_all(b"89").unwrap();
        assert!(spool.is_spilled());
        assert_eq!(spool.len(), 10);
        let file = spool.spill.as_ref().unwrap().get_ref();
        let meta = file.metadata().unwrap();
        assert_eq!(meta.permissions().mode() & 0o777, 0o600);
        assert_eq!(meta.nlink(), 0, "the spill file is unlinked while open");
        assert!(entries(dir.path()).is_empty());
        assert_eq!(sent(spool, 3), (b"0123456789".to_vec(), vec![3, 1, 3, 3]));
        assert!(entries(dir.path()).is_empty());
    }

    #[test]
    fn a_zero_memory_bound_spills_everything() {
        let dir = tempfile::tempdir().unwrap();
        let mut spool = Spool::new(0, dir.path());
        spool.write_all(b"abc").unwrap();
        assert!(spool.is_spilled());
        assert_eq!(sent(spool, 64), (b"abc".to_vec(), vec![3]));
    }

    #[test]
    fn a_spill_into_a_missing_directory_fails() {
        let dir = tempfile::tempdir().unwrap();
        let mut spool = Spool::new(0, dir.path());
        spool.dir = dir.path().join("missing");
        assert!(spool.write_all(b"x").is_err());
    }

    #[test]
    fn a_spool_dropped_unsent_leaves_nothing() {
        let dir = tempfile::tempdir().unwrap();
        let mut spool = Spool::new(1, dir.path());
        spool.write_all(b"abcdef").unwrap();
        drop(spool);
        assert!(entries(dir.path()).is_empty());
    }
}
