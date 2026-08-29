#!/usr/bin/env python3

import csv
import hashlib
import subprocess
from pathlib import Path


class ContractError(RuntimeError):
    pass


MATRIX_HEADER = [
    "cell",
    "backend_commit",
    "backend_tree",
    "frontend_commit",
    "frontend_tree",
    "browser_path",
    "feed_path",
    "http_status",
    "result",
    "boundary",
    "observed_sha256",
]

REQUIRED_CELLS = [
    "final-backend-old-frontend",
    "final-backend-final-frontend",
    "old-backend-final-frontend",
    "old-backend-old-frontend-unsafe",
    "supported-rollback-sequence",
]

EXPECTED_RESULTS = ["PASS", "PASS", "REJECTED_EXPECTED", "UNSAFE_EXPECTED", "PASS"]
GIT_TIMEOUT_SECONDS = 30


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def semantic_sha256(value):
    import json

    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def run_git(arguments, label):
    command = [str(value) for value in arguments]
    try:
        return subprocess.run(command, check=False, capture_output=True, text=True, timeout=GIT_TIMEOUT_SECONDS)
    except subprocess.TimeoutExpired as error:
        raise ContractError(
            f"{label} Git command timed out after {GIT_TIMEOUT_SECONDS} seconds: {' '.join(command)}"
        ) from error


def require_clean_repository(path, label):
    repository = Path(path)
    result = run_git(
        ["git", "-C", repository, "status", "--porcelain", "--untracked-files=all"],
        label,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ContractError(f"{label} is not a Git checkout: {repository}: {detail}")
    if result.stdout:
        raise ContractError(f"{label} contains modified or untracked inputs:\n{result.stdout.rstrip()}")


def require_repository_head(path, label, expected_commit):
    require_clean_repository(path, label)
    result = run_git(["git", "-C", Path(path), "rev-parse", "HEAD"], label)
    actual = result.stdout.strip() if result.returncode == 0 else ""
    if actual != expected_commit:
        raise ContractError(f"{label} SHA mismatch: expected {expected_commit}, got {actual or 'unreadable'}")


def require_git_commit(path, label, commit):
    result = run_git(["git", "-C", Path(path), "cat-file", "-e", f"{commit}^{{commit}}"], label)
    if result.returncode != 0:
        raise ContractError(f"{label} does not contain exact commit {commit}")


def require_git_tree(path, label, commit, expected_tree):
    result = run_git(["git", "-C", Path(path), "rev-parse", f"{commit}^{{tree}}"], label)
    actual = result.stdout.strip() if result.returncode == 0 else ""
    if actual != expected_tree:
        raise ContractError(f"{label} tree mismatch: expected {expected_tree}, got {actual or 'unreadable'}")


def load_matrix(path):
    with Path(path).open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        if tuple(reader.fieldnames or []) != tuple(MATRIX_HEADER):
            raise ContractError(f"matrix header drift: expected {MATRIX_HEADER}, got {reader.fieldnames}")
        rows = list(reader)
    if [row["cell"] for row in rows] != REQUIRED_CELLS:
        raise ContractError(f"matrix row drift: expected {REQUIRED_CELLS}, got {[row['cell'] for row in rows]}")
    if [row["result"] for row in rows] != EXPECTED_RESULTS:
        raise ContractError("matrix result boundary drift")
    for row in rows:
        for key in MATRIX_HEADER:
            if not row[key]:
                raise ContractError(f"matrix cell {row['cell']} has an empty {key}")
        if len(row["observed_sha256"]) != 64:
            raise ContractError(f"matrix cell {row['cell']} has an invalid observed checksum")
    return rows


def write_observed_matrix(path, rows):
    with Path(path).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MATRIX_HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
