//! Files that `hpds-etl dump` writes outside a store, holding observation data or patient
//! IDs in the clear.
//!
//! An output file is created mode `0600` with `O_CREAT | O_EXCL`, which never follows a
//! symbolic link, and an existing one is refused unless the caller forces a replacement,
//! which then goes through a temporary file renamed over it. An output directory is
//! created mode `0700`; an existing one must be a real directory that neither group nor
//! others can access. No output may lie inside the store it is dumped from, or inside any of
//! its partition directories, wherever those resolve to.

use std::io::{BufWriter, ErrorKind, Write};
use std::os::unix::fs::PermissionsExt;
use std::path::{Component, Path, PathBuf};

use crate::error::{EtlError, Result};
use crate::private_fs;
use crate::store::replace_file;

/// Refuses `out` when it is, or resolves to a path inside, one of the directories in
/// `stores` (a store directory and its partition directories). Symbolic links are resolved
/// on both sides; the missing tail of a path that does not exist yet is taken as written.
///
/// # Errors
/// [`EtlError::Usage`] naming the directory `out` lies in, or when the missing tail of
/// `out` holds `..`; I/O errors resolving the paths.
pub fn check_outside_stores(out: &Path, stores: &[&Path]) -> Result<()> {
    let resolved = resolve(out)?;
    for store in stores {
        let store_real = store
            .canonicalize()
            .map_err(EtlError::io("resolving", *store))?;
        if resolved.starts_with(&store_real) {
            return Err(EtlError::Usage(format!(
                "{} lies inside {}; write the dump outside the store and its partition directories",
                out.display(),
                store.display()
            )));
        }
    }
    Ok(())
}

/// The real path of `path`: its nearest existing ancestor with links resolved, followed
/// by the components that do not exist yet.
fn resolve(path: &Path) -> Result<PathBuf> {
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map_err(EtlError::io("resolving", path))?
            .join(path)
    };
    let mut existing = absolute.as_path();
    let mut missing = Vec::new();
    while std::fs::symlink_metadata(existing).is_err() {
        let Some(name) = existing.file_name() else {
            break;
        };
        missing.push(name.to_os_string());
        existing = existing.parent().unwrap_or(Path::new("/"));
    }
    if absolute
        .strip_prefix(existing)
        .is_ok_and(|tail| tail.components().any(|c| c == Component::ParentDir))
    {
        return Err(EtlError::Usage(format!(
            "{} has '..' in a part that does not exist yet",
            path.display()
        )));
    }
    let mut real = existing
        .canonicalize()
        .map_err(EtlError::io("resolving", existing))?;
    real.extend(missing.into_iter().rev());
    Ok(real)
}

/// Makes `dir` ready to receive output files: creates it mode `0700` when it does not
/// exist (its parent must), and otherwise requires a directory, not a symbolic link, that
/// grants group and others nothing.
///
/// # Errors
/// [`EtlError::Usage`] for a symbolic link, a file, or a directory group or others can
/// access; I/O errors creating or inspecting it.
pub fn prepare_output_dir(dir: &Path) -> Result<()> {
    let meta = match std::fs::symlink_metadata(dir) {
        Ok(meta) => meta,
        Err(e) if e.kind() == ErrorKind::NotFound => {
            return private_fs::create_dir(dir).map_err(EtlError::io("creating", dir));
        }
        Err(e) => return Err(EtlError::io("inspecting", dir)(e)),
    };
    if meta.file_type().is_symlink() {
        return Err(EtlError::Usage(format!(
            "{} is a symbolic link; give the real output directory",
            dir.display()
        )));
    }
    if !meta.is_dir() {
        return Err(EtlError::Usage(format!(
            "{} exists and is not a directory",
            dir.display()
        )));
    }
    let mode = meta.permissions().mode() & 0o777;
    if mode & 0o077 != 0 {
        return Err(EtlError::Usage(format!(
            "{} has mode {mode:04o}, so group or others can reach the dump; give a directory of mode 0700 or one that does not exist yet",
            dir.display()
        )));
    }
    Ok(())
}

/// Writes the output file `path` by calling `write` on a buffered writer to it.
///
/// A new file is created mode `0600` without following a symbolic link at `path`, and is
/// removed again when `write` fails. An existing `path` (a symbolic link included) is
/// refused unless `force`; then the output goes to a temporary file beside it that is
/// renamed over it, so `path` holds the old content or the complete new one, and a link at
/// `path` is replaced, never written through.
///
/// # Errors
/// [`EtlError::Usage`] when `path` exists and `force` is off; I/O errors; any error from
/// `write`.
pub fn write_output_file(
    path: &Path,
    force: bool,
    write: impl FnOnce(&mut dyn Write) -> Result<()>,
) -> Result<()> {
    let file = match private_fs::create_file(path) {
        Ok(file) => file,
        Err(e) if e.kind() == ErrorKind::AlreadyExists && force => {
            return replace_file(path, |out| write(out));
        }
        Err(e) if e.kind() == ErrorKind::AlreadyExists => {
            return Err(EtlError::Usage(format!(
                "{} exists; pass --force to replace it",
                path.display()
            )));
        }
        Err(e) => return Err(EtlError::io("creating", path)(e)),
    };
    let mut out = BufWriter::with_capacity(1 << 20, file);
    let written = write(&mut out).and_then(|()| {
        out.flush()
            .and_then(|()| out.get_ref().sync_all())
            .map_err(EtlError::io("writing", path))
    });
    if written.is_err() {
        drop(out);
        let _ = std::fs::remove_file(path);
    }
    written
}

#[cfg(test)]
mod tests {
    use std::os::unix::fs::symlink;

    use super::*;

    fn mode(path: &Path) -> u32 {
        std::fs::metadata(path).unwrap().permissions().mode() & 0o777
    }

    fn write_text(path: &Path, force: bool, text: &str) -> Result<()> {
        write_output_file(path, force, |out| {
            out.write_all(text.as_bytes())
                .map_err(EtlError::io("writing", path))
        })
    }

    #[test]
    fn new_files_are_owner_only_and_existing_ones_need_force() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("dump.csv");
        write_text(&path, false, "one").unwrap();
        assert_eq!(mode(&path), 0o600);
        let message = write_text(&path, false, "two").unwrap_err().to_string();
        assert!(message.contains("pass --force"), "{message}");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "one");
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        write_text(&path, true, "two").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "two");
        assert_eq!(
            mode(&path),
            0o600,
            "the replacement is a new owner-only file"
        );
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn a_symbolic_link_is_never_written_through() {
        let dir = tempfile::tempdir().unwrap();
        let victim = dir.path().join("victim");
        std::fs::write(&victim, "keep").unwrap();
        let link = dir.path().join("dump.csv");
        symlink(&victim, &link).unwrap();
        assert!(write_text(&link, false, "x").is_err());
        write_text(&link, true, "x").unwrap();
        assert_eq!(std::fs::read_to_string(&victim).unwrap(), "keep");
        assert!(
            !std::fs::symlink_metadata(&link)
                .unwrap()
                .file_type()
                .is_symlink()
        );
        assert_eq!(std::fs::read_to_string(&link).unwrap(), "x");
        let dangling = dir.path().join("dangling");
        symlink(dir.path().join("nowhere"), &dangling).unwrap();
        assert!(write_text(&dangling, false, "x").is_err());
        assert!(!dir.path().join("nowhere").exists());
    }

    #[test]
    fn a_failed_write_leaves_no_file() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("dump.csv");
        let failed = write_output_file(&path, false, |_| Err(EtlError::Usage("boom".into())));
        assert!(failed.is_err());
        assert!(!path.exists());
        std::fs::write(&path, "old").unwrap();
        let failed = write_output_file(&path, true, |_| Err(EtlError::Usage("boom".into())));
        assert!(failed.is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "old");
        assert_eq!(std::fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[test]
    fn output_directories_are_created_private_and_checked() {
        let dir = tempfile::tempdir().unwrap();
        let out = dir.path().join("dumps");
        prepare_output_dir(&out).unwrap();
        assert_eq!(mode(&out), 0o700);
        prepare_output_dir(&out).unwrap();
        for open in [0o750, 0o705, 0o777] {
            std::fs::set_permissions(&out, std::fs::Permissions::from_mode(open)).unwrap();
            let message = prepare_output_dir(&out).unwrap_err().to_string();
            assert!(message.contains("group or others"), "{open:o}: {message}");
        }
        std::fs::set_permissions(&out, std::fs::Permissions::from_mode(0o700)).unwrap();
        let link = dir.path().join("link");
        symlink(&out, &link).unwrap();
        assert!(prepare_output_dir(&link).is_err());
        let file = dir.path().join("file");
        std::fs::write(&file, "").unwrap();
        assert!(prepare_output_dir(&file).is_err());
    }

    #[test]
    fn outputs_inside_a_store_or_a_linked_partition_are_refused() {
        let dir = tempfile::tempdir().unwrap();
        let store = dir.path().join("store");
        let elsewhere = dir.path().join("elsewhere");
        std::fs::create_dir_all(&store).unwrap();
        std::fs::create_dir_all(&elsewhere).unwrap();
        let partition = store.join("p1");
        symlink(&elsewhere, &partition).unwrap();
        let stores = [store.as_path(), partition.as_path()];
        for inside in [
            store.clone(),
            store.join("dump.csv"),
            store.join("new/dump.csv"),
            elsewhere.join("dump.csv"),
            dir.path().join("store/./x.csv"),
        ] {
            assert!(
                check_outside_stores(&inside, &stores).is_err(),
                "{}",
                inside.display()
            );
        }
        assert!(check_outside_stores(&dir.path().join("x/../store/y"), &stores).is_err());
        check_outside_stores(&dir.path().join("dump.csv"), &stores).unwrap();
        check_outside_stores(&dir.path().join("store-dump.csv"), &stores).unwrap();
        let alias = dir.path().join("alias");
        symlink(&store, &alias).unwrap();
        assert!(check_outside_stores(&alias.join("dump.csv"), &stores).is_err());
    }
}
