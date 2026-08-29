#!/usr/bin/env python3

import hashlib
import json
import os
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import contract
import run as compatibility_run


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

    def test_occurrence_only_cell_cannot_claim_support(self):
        row = contract.empty_observation("occurrence-only-rejection")
        row["result"] = "REJECTED_EXPECTED"
        row["supported_boundary"] = "supported"

        with self.assertRaisesRegex(contract.ContractError, "unsupported boundary"):
            contract.validate_observation(row)

    def test_rejects_malformed_migration_manifest_row(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "feature-sql.sha256"
            manifest.write_text("missing-tab-separator\n", encoding="utf-8")

            with self.assertRaisesRegex(contract.ContractError, "malformed migration checksum manifest"):
                compatibility_run.Harness.verify_manifest(None, root, manifest, ["fixture.sql"])

    def test_audit_request_file_is_created_by_the_host(self):
        with tempfile.TemporaryDirectory() as directory:
            request_file = compatibility_run.Harness.prepare_audit_request_file(Path(directory))

            self.assertEqual(os.getuid(), request_file.stat().st_uid)
            self.assertTrue(request_file.stat().st_mode & stat.S_IWUSR)
            request_file.write_text("", encoding="utf-8")

    def test_external_command_timeout_is_a_contract_error(self):
        expired = subprocess.TimeoutExpired(["git", "status"], 1)
        with mock.patch.object(compatibility_run.subprocess, "run", side_effect=expired):
            with self.assertRaisesRegex(contract.ContractError, "timed out after 1 seconds"):
                compatibility_run.Harness.command(["git", "status"], timeout=1)

    def test_source_validation_timeout_is_a_contract_error(self):
        expired = subprocess.TimeoutExpired(["git", "status"], contract.GIT_COMMAND_TIMEOUT_SECONDS)
        with mock.patch.object(contract.subprocess, "run", side_effect=expired):
            with self.assertRaisesRegex(contract.ContractError, "Operations source override Git command timed out"):
                contract.require_clean_repository("/synthetic/repository", "Operations source override")

    def test_ci_entrypoint_preserves_source_and_failure_diagnostics(self):
        test_dir = Path(__file__).parent
        entrypoint = (test_dir / "test.sh").read_text(encoding="utf-8")
        workflow = (test_dir.parents[1] / ".github/workflows/operations-binary-compatibility.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("export PYTHONDONTWRITEBYTECODE=1", entrypoint)
        self.assertIn('"$test_dir/test-cleanup.sh"', entrypoint)
        self.assertIn("TMPDIR: ${{ runner.temp }}", workflow)
        self.assertIn("KEEP_COMPAT_TEMP_ON_FAILURE: true", workflow)
        self.assertIn("actions/upload-artifact@", workflow)
        self.assertNotIn("services/pic-sure-operations-service/**", workflow)

    def test_cell_failure_captures_partial_diagnostics_and_preserves_original_error(self):
        with tempfile.TemporaryDirectory() as directory:
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.selection = "final-http-contract"
            harness.temp_root = Path(directory)
            harness.observations = []
            harness.expected_by_cell = {}
            harness.require_tools = mock.Mock()
            harness.require_runtime_pin = mock.Mock()
            harness.prepare_sources = mock.Mock()
            harness.verify_migration_contracts = mock.Mock()
            harness.build_binaries = mock.Mock()
            harness.create_network = mock.Mock()
            harness.cell_final_http_contract = mock.Mock(
                side_effect=contract.ContractError("synthetic cell failure")
            )

            def capture_then_fail():
                (Path(directory) / "synthetic-app.log").write_text("captured failure log\n", encoding="utf-8")
                raise contract.ContractError("synthetic log cleanup failure")

            harness.stop_all_apps = mock.Mock(side_effect=capture_then_fail)
            harness.stop_mysql = mock.Mock()

            with self.assertRaisesRegex(contract.ContractError, "synthetic cell failure"):
                harness.run()

            harness.stop_all_apps.assert_called_once_with()
            harness.stop_mysql.assert_called_once_with()
            observed = Path(directory) / "observed-matrix.tsv"
            failure = Path(directory) / "failed-cell.json"
            self.assertEqual(
                "captured failure log\n",
                (Path(directory) / "synthetic-app.log").read_text(encoding="utf-8"),
            )
            self.assertTrue(observed.is_file())
            self.assertEqual("deployment_scope", observed.read_text(encoding="utf-8").split("\t", 1)[0])
            detail = json.loads(failure.read_text(encoding="utf-8"))
            self.assertEqual("final-http-contract", detail["failed_cell"])
            self.assertEqual("synthetic cell failure", detail["error"])
            self.assertEqual(["stop_all_apps: synthetic log cleanup failure"], detail["cleanup_errors"])

    def test_portable_command_timeout_returns_124(self):
        import bounded_command

        status = bounded_command.run(
            [sys.executable, "-c", "import time; time.sleep(30)"],
            timeout=0.01,
        )

        self.assertEqual(124, status)

    def test_cleanup_timeout_is_bounded_and_diagnostic(self):
        test_dir = Path(__file__).parent
        with tempfile.TemporaryDirectory() as directory:
            fake_docker = Path(directory) / "docker"
            fake_docker.write_text("#!/usr/bin/env sh\nsleep 30\n", encoding="utf-8")
            fake_docker.chmod(0o755)
            environment = os.environ.copy()
            environment.update(
                {
                    "OPERATIONS_COMPAT_DOCKER_BIN": str(fake_docker),
                    "OPERATIONS_COMPAT_DOCKER_TIMEOUT_SECONDS": "0.01",
                }
            )

            result = subprocess.run(
                [test_dir / "cleanup-resources.sh", "timeoutfixture"],
                check=False,
                capture_output=True,
                text=True,
                timeout=2,
                env=environment,
            )

        self.assertEqual(124, result.returncode)
        self.assertIn("command timed out after 0.01 seconds", result.stderr)

    def test_shell_docker_calls_use_the_bounded_wrapper(self):
        test_dir = Path(__file__).parent
        for script_name in ("cleanup-resources.sh", "test-cleanup.sh"):
            source = (test_dir / script_name).read_text(encoding="utf-8")
            self.assertIn("docker_command", source, script_name)
            self.assertNotRegex(source, r"(?m)^\s*docker\s", script_name)

        entrypoint = (test_dir / "test.sh").read_text(encoding="utf-8")
        self.assertIn("original_status=$?", entrypoint)
        self.assertIn("if [[ $final_status -eq 0 && $cleanup_status -ne 0 ]]", entrypoint)

    def test_committed_matrix_is_complete(self):
        matrix = Path(__file__).with_name("matrix.tsv")

        rows = contract.load_matrix(matrix)

        self.assertEqual(contract.REQUIRED_CELLS, [row["cell"] for row in rows])
        self.assertNotIn("PENDING", matrix.read_text(encoding="utf-8"))

    def test_python_checks_do_not_depend_on_assert_statements(self):
        pattern = r"(?m)^\s*assert(?:\s|\()"
        self.assertRegex("assert value", pattern)
        self.assertRegex("assert(value)", pattern)
        for source_path in (Path(contract.__file__), Path(__file__).with_name("run.py")):
            source = source_path.read_text(encoding="utf-8")
            self.assertNotRegex(source, pattern, source_path.name)


if __name__ == "__main__":
    unittest.main()
