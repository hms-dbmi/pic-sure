//! Temporary directories (staging and spill) that never outlive the run that made them.
//!
//! A [`TemporaryDir`] is removed when it is dropped, and is listed in a process-wide
//! registry that [`remove_registered`] empties, so the binary's SIGTERM and SIGINT handler
//! can remove every one before exiting. A run killed with SIGKILL still leaves its
//! directories behind; [`TemporaryDir::create`] removes those, recognizing them by name
//! (`.<prefix>-<pid>-<nanos>-<sequence>-<random>`), owner, and a lock nobody holds.
//!
//! While a directory is live its creator holds an exclusive `flock` on the file
//! [`LOCK_FILE_NAME`] inside it. A sweep removes a directory only after taking that lock
//! itself, so a run in another container, whose pid means nothing in this one, never loses
//! a directory it is still writing. A directory without the lock file is left alone.

use std::fs::{File, TryLockError};
use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{EtlError, Result};
use crate::private_fs;

static REGISTERED: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

static SEQUENCE: AtomicU64 = AtomicU64::new(0);

/// Name of the lock file inside a live temporary directory. Its creator holds an exclusive
/// `flock` on it until the directory is removed or published.
pub(crate) const LOCK_FILE_NAME: &str = ".lock";

/// Name the lock file is created and locked under before it is renamed to
/// [`LOCK_FILE_NAME`], so a sweep never finds an unlocked lock file in a new directory.
const NEW_LOCK_FILE_NAME: &str = ".lock-new";

/// A name tail no other temporary name shares: this process's pid, the current
/// sub-second nanoseconds, a process-wide sequence number (two names made in the same
/// nanosecond differ), and eight random hex digits (two containers whose processes share a
/// pid differ). Shaped `<pid>-<nanos>-<sequence>-<random>`.
///
/// # Errors
/// [`EtlError::Format`] when the system random number generator fails.
pub(crate) fn unique_name_tail() -> Result<String> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    let sequence = SEQUENCE.fetch_add(1, Ordering::Relaxed);
    let random = hpds_format::StoreId::random()?.to_string();
    Ok(format!(
        "{}-{nanos}-{sequence}-{}",
        std::process::id(),
        &random[..8]
    ))
}

/// Locks the registry. The binary's signal handler waits on this lock, so holding it keeps
/// an interrupt from removing directories while a store is being moved into place.
pub(crate) fn lock() -> MutexGuard<'static, Vec<PathBuf>> {
    REGISTERED
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Removes every registered temporary directory. Called by the binary on SIGTERM and SIGINT
/// before it exits; waits for a store being moved into place to finish first.
pub fn remove_registered() {
    remove_all(&mut lock());
}

/// Removes every directory in `dirs` and empties it, retrying a directory a worker thread
/// was still writing into.
fn remove_all(dirs: &mut Vec<PathBuf>) {
    for dir in dirs.drain(..) {
        for _ in 0..3 {
            if std::fs::remove_dir_all(&dir).is_ok() || !dir.exists() {
                break;
            }
        }
    }
}

/// A directory, mode `0700`, removed on drop unless [`TemporaryDir::disarm`] is called.
/// Holds the `flock` on its [`LOCK_FILE_NAME`] until dropped or
/// [`TemporaryDir::release_lock`] is called.
pub(crate) struct TemporaryDir {
    path: PathBuf,
    armed: bool,
    lock: Option<File>,
}

impl TemporaryDir {
    /// Creates `<parent>/.<prefix>-<tail>`, `<tail>` from [`unique_name_tail`], locks its
    /// [`LOCK_FILE_NAME`] and registers it, after removing any directory of that shape that
    /// belongs to this user and whose lock nobody holds. Creates `parent` when it does not
    /// exist.
    ///
    /// # Errors
    /// I/O errors creating `parent`, the directory or its lock file, or locking it; a
    /// failing random number generator.
    pub(crate) fn create(parent: &Path, prefix: &str) -> Result<Self> {
        std::fs::create_dir_all(parent).map_err(EtlError::io("creating", parent))?;
        sweep_stale(parent, prefix);
        let path = parent.join(format!(".{prefix}-{}", unique_name_tail()?));
        let mut registered = lock();
        private_fs::create_dir(&path).map_err(EtlError::io("creating", &path))?;
        registered.push(path.clone());
        let mut dir = TemporaryDir {
            path,
            armed: true,
            lock: None,
        };
        dir.lock = Some(hold_lock(&dir.path)?);
        Ok(dir)
    }

    /// Removes the lock file and releases its lock, for a directory about to be renamed
    /// into place, so the published directory carries no lock file. A sweep leaves a
    /// directory without a lock file alone, so the directory stays safe until it is moved.
    ///
    /// # Errors
    /// I/O errors removing the lock file.
    pub(crate) fn release_lock(&mut self) -> Result<()> {
        if self.lock.is_some() {
            let file = self.path.join(LOCK_FILE_NAME);
            std::fs::remove_file(&file).map_err(EtlError::io("removing", &file))?;
            self.lock = None;
        }
        Ok(())
    }

    /// The directory.
    pub(crate) fn path(&self) -> &Path {
        &self.path
    }

    /// Stops removing the directory on drop and on interrupt, for a directory that has
    /// been renamed into place. `registered` is the held registry lock.
    pub(crate) fn disarm(&mut self, registered: &mut Vec<PathBuf>) {
        registered.retain(|p| p != &self.path);
        self.armed = false;
    }
}

impl Drop for TemporaryDir {
    fn drop(&mut self) {
        if self.armed {
            let mut registered = lock();
            let _ = std::fs::remove_dir_all(&self.path);
            registered.retain(|p| p != &self.path);
        }
    }
}

/// Creates the lock file of the new directory `dir` under a temporary name, takes an
/// exclusive lock on it, and renames it to [`LOCK_FILE_NAME`]. Returns the open file, whose
/// lock lasts until it is closed.
///
/// # Errors
/// I/O errors creating, locking or renaming the file.
fn hold_lock(dir: &Path) -> Result<File> {
    let new = dir.join(NEW_LOCK_FILE_NAME);
    let file = private_fs::create_file(&new).map_err(EtlError::io("creating", &new))?;
    file.try_lock().map_err(|e| {
        let error = match e {
            TryLockError::Error(error) => error,
            TryLockError::WouldBlock => std::io::Error::from(std::io::ErrorKind::WouldBlock),
        };
        EtlError::io("locking", &new)(error)
    })?;
    let target = dir.join(LOCK_FILE_NAME);
    std::fs::rename(&new, &target).map_err(EtlError::io("renaming", &target))?;
    Ok(file)
}

/// Takes the lock of the temporary directory `dir`, or returns `None` when its lock file is
/// missing or another open file holds the lock.
fn take_abandoned_lock(dir: &Path) -> Option<File> {
    let file = File::open(dir.join(LOCK_FILE_NAME)).ok()?;
    file.try_lock().ok()?;
    Some(file)
}

/// Removes the directories `<parent>/.<prefix>-<pid>-<nanos>[-<sequence>-<random>]` that
/// this user owns, that this process has not registered, and whose [`LOCK_FILE_NAME`] lock
/// this call can take. The lock is held while the directory is removed. The pid in the name
/// is not consulted: in another container the same pid can belong to a live run.
fn sweep_stale(parent: &Path, prefix: &str) {
    let Ok(entries) = std::fs::read_dir(parent) else {
        return;
    };
    let lead = format!(".{prefix}-");
    let own_uid = current_uid();
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(pid) = name
            .to_str()
            .and_then(|n| n.strip_prefix(&lead))
            .and_then(stale_name_pid)
        else {
            continue;
        };
        let path = entry.path();
        let Ok(meta) = std::fs::symlink_metadata(&path) else {
            continue;
        };
        if !meta.is_dir() || meta.uid() != own_uid || lock().contains(&path) {
            continue;
        }
        let Some(_held) = take_abandoned_lock(&path) else {
            continue;
        };
        match std::fs::remove_dir_all(&path) {
            Ok(()) => {
                tracing::warn!(dir = %path.display(), pid, "removed a temporary directory left by an interrupted run")
            }
            Err(e) => {
                tracing::warn!(dir = %path.display(), error = %e, "cannot remove a temporary directory left by an interrupted run")
            }
        }
    }
}

/// The pid in a temporary directory name's tail, `<pid>-<nanos>-<sequence>-<random>` or
/// the older `<pid>-<nanos>`, or `None` when the tail has another shape.
fn stale_name_pid(tail: &str) -> Option<u32> {
    let (pid, rest) = tail.split_once('-')?;
    let well_formed = match rest.split('-').collect::<Vec<_>>()[..] {
        [nanos] => nanos.parse::<u32>().is_ok(),
        [nanos, sequence, random] => {
            nanos.parse::<u32>().is_ok()
                && sequence.parse::<u64>().is_ok()
                && random.len() == 8
                && random.bytes().all(|b| b.is_ascii_hexdigit())
        }
        _ => false,
    };
    if !well_formed {
        return None;
    }
    pid.parse().ok().filter(|&p| p > 0 && p <= i32::MAX as u32)
}

/// The effective user id of this process. `geteuid` has no preconditions and cannot
/// fail, so the call is sound.
fn current_uid() -> u32 {
    unsafe { libc::geteuid() }
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::PermissionsExt;

    use super::*;

    #[test]
    fn temporary_dirs_are_private_and_removed_on_drop() {
        let root = tempfile::tempdir().unwrap();
        let dir = TemporaryDir::create(root.path(), "x.staging").unwrap();
        let path = dir.path().to_path_buf();
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o700);
        assert!(lock().contains(&path));
        drop(dir);
        assert!(!path.exists());
        assert!(!lock().contains(&path));
    }

    fn abandoned_dir(path: &Path) {
        std::fs::create_dir(path).unwrap();
        std::fs::write(path.join(LOCK_FILE_NAME), b"").unwrap();
        std::fs::write(path.join("spill-0"), b"rows").unwrap();
    }

    #[test]
    fn only_dirs_whose_lock_is_free_are_swept() {
        let root = tempfile::tempdir().unwrap();
        let abandoned = root.path().join(".x.staging-77777-5-0-0a1b2c3d");
        let held_elsewhere = root.path().join(".x.staging-77778-5-0-0a1b2c3d");
        let running_pid = root.path().join(".x.staging-1-5");
        let without_lock = root.path().join(".x.staging-77779-5");
        let other = root.path().join(".y.staging-77777-5");
        let unrelated = root.path().join(".x.staging-77777-notanumber");
        for dir in [
            &abandoned,
            &held_elsewhere,
            &running_pid,
            &other,
            &unrelated,
        ] {
            abandoned_dir(dir);
        }
        std::fs::create_dir(&without_lock).unwrap();
        let holder = File::open(held_elsewhere.join(LOCK_FILE_NAME)).unwrap();
        holder.try_lock().unwrap();

        let fresh = TemporaryDir::create(root.path(), "x.staging").unwrap();

        assert!(!abandoned.exists());
        assert!(
            !running_pid.exists(),
            "a running pid does not protect a directory whose lock is free"
        );
        assert!(
            held_elsewhere.exists(),
            "a lock held by another run protects its directory"
        );
        assert!(
            without_lock.exists(),
            "a directory without a lock file is left alone"
        );
        assert!(other.exists() && unrelated.exists());
        assert!(fresh.path().join(LOCK_FILE_NAME).exists());

        drop(holder);
        let again = TemporaryDir::create(root.path(), "x.staging").unwrap();
        assert!(!held_elsewhere.exists(), "swept once its lock is released");
        assert!(fresh.path().exists() && again.path().exists());
    }

    #[test]
    fn a_live_dir_holds_its_lock_against_another_sweep() {
        let root = tempfile::tempdir().unwrap();
        let live = TemporaryDir::create(root.path(), "x.staging").unwrap();
        let path = live.path().to_path_buf();
        assert!(take_abandoned_lock(&path).is_none());
        assert!(!path.join(NEW_LOCK_FILE_NAME).exists());
        lock().retain(|p| p != &path);
        sweep_stale(root.path(), "x.staging");
        assert!(path.exists(), "the creator's lock keeps the directory");
        lock().push(path.clone());
        drop(live);
        assert!(!path.exists());
    }

    #[test]
    fn releasing_the_lock_removes_the_lock_file() {
        let root = tempfile::tempdir().unwrap();
        let mut dir = TemporaryDir::create(root.path(), "x.staging").unwrap();
        dir.release_lock().unwrap();
        assert!(!dir.path().join(LOCK_FILE_NAME).exists());
        dir.release_lock().unwrap();
        let path = dir.path().to_path_buf();
        lock().retain(|p| p != &path);
        sweep_stale(root.path(), "x.staging");
        assert!(
            path.exists(),
            "an unlocked directory without a lock file is not swept"
        );
        lock().push(path.clone());
        drop(dir);
        assert!(!path.exists());
    }

    #[test]
    fn remove_all_removes_and_forgets_every_directory() {
        let root = tempfile::tempdir().unwrap();
        let path = root.path().join("d");
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("f"), b"x").unwrap();
        let mut dirs = vec![path.clone(), root.path().join("missing")];
        remove_all(&mut dirs);
        assert!(!path.exists());
        assert!(dirs.is_empty());
    }

    #[test]
    fn stale_names_parse_only_pid_and_nanos() {
        assert_eq!(stale_name_pid("123-456"), Some(123));
        assert_eq!(stale_name_pid("0-456"), None);
        assert_eq!(stale_name_pid("123"), None);
        assert_eq!(stale_name_pid("abc-1"), None);
        assert_eq!(stale_name_pid("123-x"), None);
        assert_eq!(stale_name_pid("123-456-7-0a1b2c3d"), Some(123));
        assert_eq!(stale_name_pid("123-456-7-0a1b2c3"), None);
        assert_eq!(stale_name_pid("123-456-x-0a1b2c3d"), None);
        assert_eq!(stale_name_pid("123-456-7"), None);
    }

    #[test]
    fn names_made_back_to_back_differ_and_parse_as_temporary_names() {
        let tails: Vec<String> = (0..64).map(|_| unique_name_tail().unwrap()).collect();
        let mut unique = tails.clone();
        unique.sort();
        unique.dedup();
        assert_eq!(unique.len(), tails.len());
        for tail in &tails {
            assert_eq!(stale_name_pid(tail), Some(std::process::id()), "{tail}");
        }
        let root = tempfile::tempdir().unwrap();
        let a = TemporaryDir::create(root.path(), "x.staging").unwrap();
        let b = TemporaryDir::create(root.path(), "x.staging").unwrap();
        assert_ne!(a.path(), b.path());
        assert!(a.path().exists() && b.path().exists());
    }
}
