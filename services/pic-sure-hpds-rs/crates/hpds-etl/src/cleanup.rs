//! Temporary directories (staging and spill) that never outlive the run that made them.
//!
//! A [`TemporaryDir`] is removed when it is dropped, and is listed in a process-wide
//! registry that [`remove_registered`] empties, so the binary's SIGTERM and SIGINT handler
//! can remove every one before exiting. A run killed with SIGKILL still leaves its
//! directories behind; [`TemporaryDir::create`] removes those, recognizing them by name
//! (`.<prefix>-<pid>-<nanos>`), owner, and a pid that is no longer running.

use std::os::unix::fs::MetadataExt;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, MutexGuard};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::error::{EtlError, Result};
use crate::private_fs;

static REGISTERED: Mutex<Vec<PathBuf>> = Mutex::new(Vec::new());

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
pub(crate) struct TemporaryDir {
    path: PathBuf,
    armed: bool,
}

impl TemporaryDir {
    /// Creates `<parent>/.<prefix>-<pid>-<nanos>` and registers it, after removing any
    /// directory of that shape that belongs to this user and whose process is gone.
    /// Creates `parent` when it does not exist.
    ///
    /// # Errors
    /// I/O errors creating `parent` or the directory.
    pub(crate) fn create(parent: &Path, prefix: &str) -> Result<Self> {
        std::fs::create_dir_all(parent).map_err(EtlError::io("creating", parent))?;
        sweep_stale(parent, prefix);
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.subsec_nanos())
            .unwrap_or(0);
        let path = parent.join(format!(".{prefix}-{}-{nanos}", std::process::id()));
        let mut registered = lock();
        private_fs::create_dir(&path).map_err(EtlError::io("creating", &path))?;
        registered.push(path.clone());
        Ok(TemporaryDir { path, armed: true })
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

/// Removes the directories `<parent>/.<prefix>-<pid>-<nanos>` that this user owns, that
/// this process has not registered, and whose pid is not running.
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
        if pid == std::process::id() || !process_is_running(pid) {
            match std::fs::remove_dir_all(&path) {
                Ok(()) => {
                    tracing::warn!(dir = %path.display(), "removed a temporary directory left by an interrupted run")
                }
                Err(e) => {
                    tracing::warn!(dir = %path.display(), error = %e, "cannot remove a temporary directory left by an interrupted run")
                }
            }
        }
    }
}

/// The pid in a temporary directory name's `<pid>-<nanos>` tail, or `None` when the tail
/// has another shape.
fn stale_name_pid(tail: &str) -> Option<u32> {
    let (pid, nanos) = tail.split_once('-')?;
    nanos.parse::<u32>().ok()?;
    pid.parse().ok().filter(|&p| p > 0 && p <= i32::MAX as u32)
}

/// The effective user id of this process. `geteuid` has no preconditions and cannot
/// fail, so the call is sound.
fn current_uid() -> u32 {
    unsafe { libc::geteuid() }
}

/// True when a process with this pid exists, including one owned by another user.
/// Signal 0 makes `kill` check only that the process exists and may be signalled, so the
/// call is sound and sends nothing.
fn process_is_running(pid: u32) -> bool {
    let status = unsafe { libc::kill(pid as libc::pid_t, 0) };
    status == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
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

    #[test]
    fn stale_dirs_of_dead_processes_are_swept() {
        let root = tempfile::tempdir().unwrap();
        let mut child = std::process::Command::new("true").spawn().unwrap();
        let dead = child.id();
        child.wait().unwrap();
        let stale = root.path().join(format!(".x.staging-{dead}-5"));
        let live = root
            .path()
            .join(format!(".x.staging-{}-5", std::process::id()));
        let parent_pid = root.path().join(".x.staging-1-5");
        let other = root.path().join(format!(".y.staging-{dead}-5"));
        let unrelated = root.path().join(format!(".x.staging-{dead}-notanumber"));
        for dir in [&stale, &parent_pid, &other, &unrelated] {
            std::fs::create_dir(dir).unwrap();
        }
        std::fs::create_dir(&live).unwrap();
        lock().push(live.clone());
        let fresh = TemporaryDir::create(root.path(), "x.staging").unwrap();
        assert!(!stale.exists());
        assert!(live.exists(), "registered directories of this process stay");
        assert!(parent_pid.exists(), "pid 1 is running");
        assert!(other.exists() && unrelated.exists());
        assert!(fresh.path().exists());
        lock().retain(|p| p != &live);
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
    }
}
