#!/usr/bin/env python3

import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

import contract


class ContractTest(unittest.TestCase):

    def test_rejects_wrong_binary_checksum(self):
        with self.assertRaisesRegex(contract.ContractError, "binary checksum mismatch"):
            contract.require_checksum("pre-version", "0" * 64, "1" * 64)

    def test_rejects_dirty_source_override(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "--quiet", repository], check=True)
            subprocess.run(["git", "-C", repository, "config", "user.name", "Compatibility Test"], check=True)
            subprocess.run(["git", "-C", repository, "config", "user.email", "compatibility@example.invalid"], check=True)
            tracked = repository / "tracked.txt"
            tracked.write_text("clean\n", encoding="utf-8")
            subprocess.run(["git", "-C", repository, "add", "tracked.txt"], check=True)
            subprocess.run(["git", "-C", repository, "commit", "--quiet", "-m", "fixture"], check=True)
            tracked.write_text("dirty\n", encoding="utf-8")

            with self.assertRaisesRegex(contract.ContractError, "contains modified or untracked inputs"):
                contract.require_clean_repository(repository, "Operations source override")

    def test_rejects_missing_forward_version_and_allocator_schema(self):
        occurrence_only = {"banner_occurrence"}

        with self.assertRaisesRegex(contract.ContractError, "banner_priority_allocator, banner_version"):
            contract.require_forward_schema(occurrence_only)

    def test_rejects_corrupted_preserved_data_checksum(self):
        observed = hashlib.sha256(b"observed rows").hexdigest()

        with self.assertRaisesRegex(contract.ContractError, "preserved-data checksum mismatch"):
            contract.require_preserved_checksum("rollback-pre-version", "f" * 64, observed)

    def test_rejects_matrix_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            matrix = Path(directory) / "matrix.tsv"
            matrix.write_text("cell\tresult\nwrong\tPASS\n", encoding="utf-8")

            with self.assertRaisesRegex(contract.ContractError, "matrix header drift"):
                contract.load_matrix(matrix)

    def test_concurrent_writer_cell_cannot_claim_support(self):
        row = contract.empty_observation("overlapping-writers")
        row["result"] = "PASS"
        row["supported_boundary"] = "supported"

        with self.assertRaisesRegex(contract.ContractError, "must remain UNSUPPORTED"):
            contract.validate_observation(row)

    def test_committed_matrix_is_complete(self):
        matrix = Path(__file__).with_name("matrix.tsv")

        rows = contract.load_matrix(matrix)

        self.assertEqual(contract.REQUIRED_CELLS, [row["cell"] for row in rows])
        self.assertNotIn("PENDING", matrix.read_text(encoding="utf-8"))

    def test_python_checks_do_not_depend_on_assert_statements(self):
        for source_path in (Path(contract.__file__), Path(__file__).with_name("run.py")):
            source = source_path.read_text(encoding="utf-8")
            self.assertNotRegex(source, r"(?m)^\s*assert\s", source_path.name)


if __name__ == "__main__":
    unittest.main()
