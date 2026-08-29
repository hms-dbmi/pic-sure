#!/usr/bin/env python3

import csv
import hashlib
import subprocess
from pathlib import Path


class ContractError(RuntimeError):
    pass


MATRIX_HEADER = [
    "deployment_scope",
    "cell",
    "binary_generation",
    "binary_commit",
    "binary_source_tree",
    "binary_sha256",
    "migration_source_commits",
    "migration_sql_sha256",
    "mysql_image",
    "flyway_image",
    "build_image",
    "build_output_timestamp",
    "runtime_image",
    "jdk_runtime",
    "schema_cell",
    "result",
    "preserved_data_sha256",
    "supported_boundary",
    "failure_mode",
]

REQUIRED_CELLS = [
    "lazy-version-recovery",
    "allocator-recovery",
    "overlapping-writers",
    "rollback-pre-version",
    "rollback-pre-allocator",
    "final-http-contract",
    "occurrence-only-rejection",
]

FORWARD_SCHEMA_TABLES = {
    "banner_occurrence",
    "banner_version",
    "banner_priority_allocator",
}

GIT_COMMAND_TIMEOUT_SECONDS = 30


def run_git(args, label):
    command = [str(value) for value in args]
    try:
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            timeout=GIT_COMMAND_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as error:
        raise ContractError(
            f"{label} Git command timed out after {GIT_COMMAND_TIMEOUT_SECONDS} seconds: {' '.join(command)}"
        ) from error


def sha256_file(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_checksum(label, expected, actual):
    if actual != expected:
        raise ContractError(f"{label} binary checksum mismatch: expected {expected}, got {actual}")


def require_preserved_checksum(cell, expected, actual):
    if actual != expected:
        raise ContractError(
            f"{cell} preserved-data checksum mismatch: expected {expected}, got {actual}"
        )


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
        raise ContractError(
            f"{label} contains modified or untracked inputs:\n{result.stdout.rstrip()}"
        )


def require_repository_head(path, label, expected_commit):
    require_clean_repository(path, label)
    result = run_git(
        ["git", "-C", Path(path), "rev-parse", "HEAD"],
        label,
    )
    if result.returncode != 0:
        raise ContractError(f"{label} has no readable HEAD at {path}")
    actual = result.stdout.strip()
    if actual != expected_commit:
        raise ContractError(
            f"{label} SHA mismatch: expected {expected_commit}, got {actual}"
        )


def require_git_commit(path, commit):
    result = run_git(
        ["git", "-C", Path(path), "cat-file", "-e", f"{commit}^{{commit}}"],
        "Operations source override",
    )
    if result.returncode != 0:
        raise ContractError(f"Operations source override does not contain commit {commit}")


def require_forward_schema(observed_tables):
    missing = sorted(FORWARD_SCHEMA_TABLES - set(observed_tables))
    if missing:
        raise ContractError(f"forward banner schema is missing: {', '.join(missing)}")


def load_matrix(path):
    with Path(path).open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        actual_header = tuple(reader.fieldnames or [])
        if actual_header != tuple(MATRIX_HEADER):
            raise ContractError(
                f"matrix header drift: expected {MATRIX_HEADER}, got {reader.fieldnames}"
            )
        rows = list(reader)
    cells = [row["cell"] for row in rows]
    if cells != REQUIRED_CELLS:
        raise ContractError(f"matrix row drift: expected {REQUIRED_CELLS}, got {cells}")
    for row in rows:
        validate_observation(row)
        for key in MATRIX_HEADER:
            if row[key] == "":
                raise ContractError(f"matrix cell {row['cell']} has an empty {key}")
    return rows


def empty_observation(cell):
    row = {key: "" for key in MATRIX_HEADER}
    row["cell"] = cell
    return row


def validate_observation(row):
    cell = row.get("cell", "")
    result = row.get("result", "")
    boundary = row.get("supported_boundary", "")
    if cell == "overlapping-writers":
        if result != "UNSUPPORTED_EXPECTED" or boundary != "unsupported":
            raise ContractError(
                "overlapping-writers must remain UNSUPPORTED_EXPECTED with an unsupported boundary"
            )
    elif result not in {"PASS", "REJECTED_EXPECTED"}:
        raise ContractError(f"matrix cell {cell} has invalid result {result}")
    if cell == "occurrence-only-rejection" and (result != "REJECTED_EXPECTED" or boundary != "unsupported"):
        raise ContractError(
            "occurrence-only-rejection must record REJECTED_EXPECTED with an unsupported boundary"
        )
    if cell not in {"overlapping-writers", "occurrence-only-rejection"} and boundary != "supported":
        raise ContractError(f"matrix cell {cell} must record the supported boundary")


def write_matrix(path, rows):
    with Path(path).open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=MATRIX_HEADER, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def require_observations_match(expected_rows, observed_rows):
    if len(expected_rows) != len(observed_rows):
        raise ContractError(
            f"observed matrix row count mismatch: expected {len(expected_rows)}, got {len(observed_rows)}"
        )
    for expected, observed in zip(expected_rows, observed_rows, strict=True):
        validate_observation(observed)
        if expected != observed:
            differences = [
                f"{key}: expected {expected[key]!r}, got {observed[key]!r}"
                for key in MATRIX_HEADER
                if expected[key] != observed[key]
            ]
            raise ContractError(
                f"matrix drift for {expected['cell']}: " + "; ".join(differences)
            )
