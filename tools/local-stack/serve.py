"""Helpers for serving an existing partition set (DATASET=dir) and for the open-mode HPDS. Standard library only.

  serve.py check-dir HPDS_DIR KEY_FILE         is HPDS_DIR a partition root and KEY_FILE a key file? exit 0, else 1 with the reason
  serve.py open-root SRC OVERRIDES DST         DST = SRC with the columnMeta.javabin of every partition replaced by OVERRIDES/<partition>/
                                               columnMeta.javabin. Every partition needs an override (a partition without one would be served
                                               with its full columnMeta: refused). The stores are hard-linked, never copied; DST must be under work/

A partition root is a directory with one subdirectory per partition, each holding allObservationsStore.javabin and
columnMeta.javabin. HPDS treats every subdirectory of HPDS_DATA_DIRECTORY as a partition, so a directory that also holds something
else (a dataset's `open/` or `static/`, the parent of `partitions/`) would be served as extra, empty partitions: refused here.
The key is never read or printed; only its existence and size are checked.
"""
from __future__ import annotations

import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lane

STORE_FILE = "allObservationsStore.javabin"
META_FILE = "columnMeta.javabin"


def partitions_of(root):
    """The partition subdirectories of a root, sorted; plain files (the key, manifests) are not partitions."""
    return sorted(e for e in os.listdir(root) if os.path.isdir(os.path.join(root, e)))


def dir_problem(hpds_dir, key_file):
    """Why HPDS_DIR / KEY_FILE cannot be served, or None."""
    if not hpds_dir:
        return "HPDS_DIR is empty: DATASET=dir needs HPDS_DIR=<partition root> and KEY_FILE=<key file>"
    if not key_file:
        return "KEY_FILE is empty: DATASET=dir needs HPDS_DIR=<partition root> and KEY_FILE=<key file>"
    if not os.path.isdir(hpds_dir):
        return f"HPDS_DIR {hpds_dir} is not a directory"
    if not os.path.isfile(key_file):
        return f"KEY_FILE {key_file} is not a file"
    if os.path.getsize(key_file) == 0:
        return f"KEY_FILE {key_file} is empty"
    if not os.access(key_file, os.R_OK):
        return f"KEY_FILE {key_file} is not readable"
    if os.path.exists(os.path.join(hpds_dir, STORE_FILE)):
        return f"HPDS_DIR {hpds_dir} holds {STORE_FILE} itself: it is one unpartitioned store, not a partition root (the stack serves partitions)"
    parts = partitions_of(hpds_dir)
    if not parts:
        return f"HPDS_DIR {hpds_dir} has no partition subdirectories"
    for p in parts:
        missing = [f for f in (STORE_FILE, META_FILE) if not os.path.isfile(os.path.join(hpds_dir, p, f))]
        if missing:
            return ("HPDS_DIR {}: subdirectory {} is not a partition (no {}). Point HPDS_DIR at the directory that holds only the partitions "
                    "(a dataset's partitions/), never at its parent, open/ or static/".format(hpds_dir, p, " or ".join(missing)))
    return None


def build_open_root(src, overrides, dst):
    """Make dst a copy of src (links) with the open columnMeta.javabin of each partition. Fails closed: a partition without an override,
    an override for a partition that src does not have, an empty override, and a store that cannot be hard-linked (it is never copied)
    are errors."""
    if not os.path.isdir(src):
        raise SystemExit(f"open-root: {src} is not a directory")
    parts = partitions_of(src)
    if not parts:
        raise SystemExit(f"open-root: {src} has no partitions")
    extra = sorted(set(partitions_of(overrides)) - set(parts)) if os.path.isdir(overrides) else []
    if not os.path.isdir(overrides):
        raise SystemExit(f"open-root: OPEN_COLUMNMETA_DIR {overrides} is not a directory")
    if extra:
        raise SystemExit("open-root: {} has columnMeta for partitions the store does not have: {}".format(overrides, ", ".join(extra)))
    absent = [p for p in parts if not os.path.isfile(os.path.join(overrides, p, META_FILE))]
    if absent:
        raise SystemExit("open-root: no open columnMeta.javabin for {} of {} partitions ({}): a partition without one would be served with its full columnMeta".format(
            len(absent), len(parts), ", ".join(absent[:5])))
    for p in parts:
        if os.path.getsize(os.path.join(overrides, p, META_FILE)) == 0:
            raise SystemExit(f"open-root: the override of {p} is empty")
    problem = lane.work_dir_problem()
    if problem:
        raise SystemExit(f"open-root: refusing to write: WORK_DIR {problem}")
    real_work = os.path.realpath(lane.WORK)
    if not os.path.realpath(os.path.dirname(dst)).startswith(real_work):
        raise SystemExit(f"open-root: {dst} is not under {real_work}")
    lane.rmtree(dst)
    os.makedirs(dst)
    for p in parts:
        os.makedirs(os.path.join(dst, p))
        for f in sorted(os.listdir(os.path.join(src, p))):
            if f in (META_FILE, "columnMeta.csv"):
                continue
            try:
                os.link(os.path.realpath(os.path.join(src, p, f)), os.path.join(dst, p, f))
            except OSError as e:
                raise SystemExit(f"open-root: cannot hard-link {p}/{f} into {dst} ({e}); a store is never copied (keep work/ on the file system of the partitions)") from e
        override = os.path.join(overrides, p, META_FILE)
        try:
            os.link(os.path.realpath(override), os.path.join(dst, p, META_FILE))
        except OSError:
            shutil.copyfile(override, os.path.join(dst, p, META_FILE))  # a columnMeta is small; the stores are not
    return len(parts), len(parts)


def main(argv):
    if len(argv) == 4 and argv[1] == "check-dir":
        problem = dir_problem(argv[2], argv[3])
        if problem:
            sys.stderr.write(f"{problem}\n")
            return 1
        print(f"dir: {len(partitions_of(argv[2]))} partitions in {argv[2]}, key file present")
        return 0
    if len(argv) == 5 and argv[1] == "open-root":
        n, replaced = build_open_root(argv[2], argv[3], argv[4])
        print(f"open root: {n} partitions, {replaced} with an open columnMeta ({os.path.relpath(argv[4], HERE)})")
        return 0
    sys.stderr.write(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
