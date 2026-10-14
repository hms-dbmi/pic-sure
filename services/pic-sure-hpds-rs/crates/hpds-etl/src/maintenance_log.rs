//! The maintenance log of a store: `maintenance-log.jsonl` in the store directory, one JSON
//! object per line, appended by every command that changes a built store in place
//! (`remove-concepts`, and `recover` when it finishes an interrupted one).
//!
//! A record names what was done and to which store, never a patient id or an observation
//! value: the UTC time, the operation and its result, the tool version, the store id, a
//! SHA-256 of the input that drove it, the concept paths it removed, counts, and a SHA-256
//! of the `pheno.index` it left, so `sha256sum pheno.index` tells whether the store is
//! still the one the last record describes. The file is created mode `0600`, never through
//! a symbolic link, and each record is synced before the command reports success. Loads
//! publishing into the directory leave it in place, so it outlives the store files it
//! describes.

use std::fs::OpenOptions;
use std::io::Write;
use std::os::unix::fs::{FileExt, OpenOptionsExt, PermissionsExt};
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

use sha2::{Digest, Sha256};

use crate::error::{EtlError, Result};
use crate::private_fs;

/// Name of the maintenance log in a store directory.
pub const MAINTENANCE_LOG_FILE_NAME: &str = "maintenance-log.jsonl";

/// Appends `record` as one line to the maintenance log of the store in `dir`, creating the
/// log mode `0600` when it is missing, and syncs it (and the directory, for a new log). A
/// torn last line left by a crash is ended first, so the new record stays on a line of
/// its own. An existing log with another mode (one readable by group or others) is set to
/// `0600` first.
///
/// # Errors
/// [`EtlError::Usage`] when the log is a symbolic link or not a regular file; I/O errors
/// opening, writing or syncing it.
pub fn append(dir: &Path, record: &serde_json::Value) -> Result<()> {
    let path = dir.join(MAINTENANCE_LOG_FILE_NAME);
    let existed = check_regular(&path)?;
    let mut file = OpenOptions::new()
        .read(true)
        .append(true)
        .create(true)
        .mode(private_fs::FILE_MODE)
        .custom_flags(libc::O_NOFOLLOW)
        .open(&path)
        .map_err(EtlError::io("opening", &path))?;
    let meta = file.metadata().map_err(EtlError::io("reading", &path))?;
    if meta.permissions().mode() & 0o777 != private_fs::FILE_MODE {
        file.set_permissions(std::fs::Permissions::from_mode(private_fs::FILE_MODE))
            .map_err(EtlError::io("narrowing the mode of", &path))?;
    }
    let len = meta.len();
    let mut last = *b"\n";
    if len > 0 {
        file.read_exact_at(&mut last, len - 1)
            .map_err(EtlError::io("reading", &path))?;
    }
    let mut line = Vec::new();
    if last[0] != b'\n' {
        line.push(b'\n');
    }
    line.extend(serde_json::to_vec(record).map_err(|e| EtlError::Usage(e.to_string()))?);
    line.push(b'\n');
    file.write_all(&line)
        .and_then(|()| file.sync_all())
        .map_err(EtlError::io("writing", &path))?;
    if !existed {
        private_fs::sync_dir(dir).map_err(EtlError::io("syncing", dir))?;
    }
    Ok(())
}

/// The records of the maintenance log of the store in `dir`, oldest first; empty when there
/// is no log. A line that is not a JSON object (a torn last line) is skipped.
///
/// # Errors
/// [`EtlError::Usage`] when the log is a symbolic link or not a regular file; I/O errors
/// reading it.
pub fn records(dir: &Path) -> Result<Vec<serde_json::Value>> {
    let path = dir.join(MAINTENANCE_LOG_FILE_NAME);
    if !check_regular(&path)? {
        return Ok(Vec::new());
    }
    let text = std::fs::read_to_string(&path).map_err(EtlError::io("reading", &path))?;
    Ok(text
        .lines()
        .filter_map(|line| serde_json::from_str::<serde_json::Value>(line).ok())
        .filter(serde_json::Value::is_object)
        .collect())
}

/// True when `path` exists as a regular file, false when it does not exist.
///
/// # Errors
/// [`EtlError::Usage`] when it is a symbolic link or anything but a regular file.
fn check_regular(path: &Path) -> Result<bool> {
    match std::fs::symlink_metadata(path) {
        Ok(meta) if meta.file_type().is_file() => Ok(true),
        Ok(_) => Err(EtlError::Usage(format!(
            "{} is not a regular file (a symbolic link is refused); move it aside",
            path.display()
        ))),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(e) => Err(EtlError::io("reading", path)(e)),
    }
}

/// The SHA-256 of `bytes`, as 64 lowercase hex digits.
pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes)
        .iter()
        .map(|b| format!("{b:02x}"))
        .collect()
}

/// The current time in UTC as RFC 3339 to the second, `2026-10-14T08:49:03Z`.
pub fn utc_now() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_secs());
    utc_timestamp(seconds)
}

/// `seconds` after the Unix epoch in UTC as RFC 3339 to the second, by the proleptic
/// Gregorian calendar (Howard Hinnant's `civil_from_days`).
fn utc_timestamp(seconds: u64) -> String {
    let days = (seconds / 86_400) as i64;
    let second_of_day = seconds % 86_400;
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let shifted_month = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * shifted_month + 2) / 5 + 1;
    let month = if shifted_month < 10 {
        shifted_month + 3
    } else {
        shifted_month - 9
    };
    let year = year_of_era + era * 400 + i64::from(month <= 2);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        second_of_day / 3600,
        second_of_day / 60 % 60,
        second_of_day % 60
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn timestamps_are_utc_rfc_3339() {
        assert_eq!(utc_timestamp(0), "1970-01-01T00:00:00Z");
        assert_eq!(utc_timestamp(951_782_400), "2000-02-29T00:00:00Z");
        assert_eq!(utc_timestamp(1_791_967_743), "2026-10-14T08:49:03Z");
        assert_eq!(utc_timestamp(4_102_444_799), "2099-12-31T23:59:59Z");
    }

    #[test]
    fn the_log_is_appended_private_and_never_through_a_link() {
        let dir = tempfile::tempdir().unwrap();
        assert!(records(dir.path()).unwrap().is_empty());
        append(dir.path(), &serde_json::json!({"n": 1})).unwrap();
        append(dir.path(), &serde_json::json!({"n": 2})).unwrap();
        let path = dir.path().join(MAINTENANCE_LOG_FILE_NAME);
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        std::fs::OpenOptions::new()
            .append(true)
            .open(&path)
            .unwrap()
            .write_all(b"{\"torn")
            .unwrap();
        append(dir.path(), &serde_json::json!({"n": 3})).unwrap();
        let read = records(dir.path()).unwrap();
        assert_eq!(
            read,
            [
                serde_json::json!({"n": 1}),
                serde_json::json!({"n": 2}),
                serde_json::json!({"n": 3})
            ]
        );

        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        append(dir.path(), &serde_json::json!({"n": 4})).unwrap();
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600, "a wider mode is narrowed");

        let other = tempfile::tempdir().unwrap();
        std::os::unix::fs::symlink(&path, other.path().join(MAINTENANCE_LOG_FILE_NAME)).unwrap();
        assert!(append(other.path(), &serde_json::json!({})).is_err());
        assert!(records(other.path()).is_err());
    }

    #[test]
    fn sha256_is_lowercase_hex() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
