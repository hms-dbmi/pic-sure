//! Creating files and directories that only their owner can read.
//!
//! Every directory the ETL creates is mode `0700` and every file `0600`, before the umask
//! narrows them further. The stores, spill files and staging directories hold observation
//! data, so nothing the ETL writes is readable by other users on the host. The all-in-one
//! containers that write and read a store (`hpds-rs-etl` and `hpds-rs`) both run as UID 1000,
//! so the owner-only modes do not stop the service from reading the store the ETL wrote.

use std::fs::{DirBuilder, File, OpenOptions};
use std::io::{self, Write};
use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt};
use std::path::Path;

/// Mode of every directory the ETL creates.
pub const DIR_MODE: u32 = 0o700;

/// Mode of every file the ETL creates.
pub const FILE_MODE: u32 = 0o600;

/// Creates the directory `path`, mode `0700`. Its parent must exist.
///
/// # Errors
/// The I/O error of `mkdir`, including `AlreadyExists`.
pub fn create_dir(path: &Path) -> io::Result<()> {
    DirBuilder::new().mode(DIR_MODE).create(path)
}

/// Creates the new file `path` for writing, mode `0600`.
///
/// # Errors
/// The I/O error of `open`, including `AlreadyExists` when `path` exists.
pub fn create_file(path: &Path) -> io::Result<File> {
    OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(FILE_MODE)
        .open(path)
}

/// Writes `bytes` to the new file `path`, mode `0600`, and syncs it to disk.
///
/// # Errors
/// I/O errors creating, writing or syncing the file.
pub fn write_file(path: &Path, bytes: &[u8]) -> io::Result<()> {
    let mut file = create_file(path)?;
    file.write_all(bytes)?;
    file.sync_all()
}

/// Syncs the directory `path`, so the names created, renamed or removed in it survive a
/// crash.
///
/// # Errors
/// The I/O error of opening or syncing the directory.
pub fn sync_dir(path: &Path) -> io::Result<()> {
    File::open(path)?.sync_all()
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use super::*;

    fn mode(path: &Path) -> u32 {
        std::fs::metadata(path).unwrap().permissions().mode() & 0o777
    }

    #[test]
    fn files_and_directories_are_owner_only() {
        let root = tempfile::tempdir().unwrap();
        let dir = root.path().join("d");
        create_dir(&dir).unwrap();
        assert_eq!(mode(&dir), 0o700);
        let file = dir.join("f");
        write_file(&file, b"x").unwrap();
        assert_eq!(mode(&file), 0o600);
        assert_eq!(
            create_file(&file).unwrap_err().kind(),
            io::ErrorKind::AlreadyExists
        );
        sync_dir(&dir).unwrap();
    }
}
