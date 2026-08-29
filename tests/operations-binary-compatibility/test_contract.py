#!/usr/bin/env python3

import hashlib
import json
import os
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

import contract
import run as compatibility_run


class ContractTest(unittest.TestCase):

    @staticmethod
    def wait_for_json(path, timeout=2):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if path.is_file():
                return json.loads(path.read_text(encoding="utf-8"))
            time.sleep(0.01)
        raise AssertionError(f"fixture did not become ready within {timeout} seconds: {path}")

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

    def test_portable_command_timeout_kills_descendants_without_pipe_delay(self):
        test_dir = Path(__file__).parent
        with tempfile.TemporaryDirectory() as directory:
            ready_file = Path(directory) / "ready.json"
            parent_code = (
                "import json, os, pathlib, subprocess, sys, time\n"
                "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(4)'])\n"
                "ready = {'parent_pid': os.getpid(), 'child_pid': child.pid}\n"
                "pathlib.Path(sys.argv[1]).write_text(json.dumps(ready), encoding='utf-8')\n"
                "time.sleep(30)\n"
            )
            wrapper = subprocess.Popen(
                [
                    sys.executable,
                    test_dir / "bounded_command.py",
                    "2",
                    sys.executable,
                    "-c",
                    parent_code,
                    ready_file,
                ],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            ready = self.wait_for_json(ready_file)
            started = time.monotonic()
            _stdout, stderr = wrapper.communicate(timeout=3)
            elapsed = time.monotonic() - started
            child_pid = ready["child_pid"]
            for _ in range(100):
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.01)
            else:
                self.fail(f"timed-out command left descendant {child_pid} running")

        self.assertEqual(124, wrapper.returncode)
        self.assertLess(elapsed, 2.25)
        self.assertIn("command timed out after 2.0 seconds", stderr)

    def test_portable_command_forwards_signals_to_descendants(self):
        test_dir = Path(__file__).parent
        fixture = (
            "import json, os, pathlib, subprocess, sys, time\n"
            "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])\n"
            "ready = {'parent_pid': os.getpid(), 'child_pid': child.pid}\n"
            "pathlib.Path(sys.argv[1]).write_text(json.dumps(ready), encoding='utf-8')\n"
            "time.sleep(30)\n"
        )
        for received_signal in (signal.SIGINT, signal.SIGTERM):
            with self.subTest(signal=received_signal), tempfile.TemporaryDirectory() as directory:
                ready_file = Path(directory) / "ready.json"
                wrapper = subprocess.Popen(
                    [
                        sys.executable,
                        test_dir / "bounded_command.py",
                        "30",
                        sys.executable,
                        "-c",
                        fixture,
                        ready_file,
                    ],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    start_new_session=True,
                )
                ready = self.wait_for_json(ready_file)
                completed = True
                try:
                    wrapper.send_signal(received_signal)
                    wrapper.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    completed = False
                finally:
                    try:
                        os.killpg(ready["parent_pid"], signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    if wrapper.poll() is None:
                        wrapper.kill()
                    wrapper.communicate()

                self.assertTrue(completed, f"wrapper did not close pipes after {received_signal}")
                self.assertEqual(-received_signal, wrapper.returncode)
                for process_name, process_id in ready.items():
                    with self.subTest(process=process_name):
                        with self.assertRaises(ProcessLookupError):
                            os.kill(process_id, 0)

    def test_portable_command_immediate_signal_does_not_leave_a_started_command(self):
        test_dir = Path(__file__).parent
        fixture = (
            "import pathlib, sys, time\n"
            "pathlib.Path(sys.argv[1]).write_text('{}', encoding='utf-8')\n"
            "while not pathlib.Path(sys.argv[3]).exists(): time.sleep(0.005)\n"
            "pathlib.Path(sys.argv[2]).write_text('survived', encoding='utf-8')\n"
        )
        for received_signal in (signal.SIGINT, signal.SIGTERM):
            started_attempts = 0
            for start_mode in ("immediate", "ready"):
                with self.subTest(signal=received_signal, start_mode=start_mode), tempfile.TemporaryDirectory() as directory:
                    started_file = Path(directory) / "started"
                    survived_file = Path(directory) / "survived"
                    gate_file = Path(directory) / "gate"
                    wrapper = subprocess.Popen(
                        [
                            sys.executable,
                            test_dir / "bounded_command.py",
                            "30",
                            sys.executable,
                            "-c",
                            fixture,
                            started_file,
                            survived_file,
                            gate_file,
                        ],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=True,
                        start_new_session=True,
                    )
                    if start_mode == "ready":
                        self.wait_for_json(started_file)
                    wrapper.send_signal(received_signal)
                    wrapper.wait(timeout=1)
                    gate_file.write_text("continue\n", encoding="utf-8")
                    _stdout, stderr = wrapper.communicate(timeout=1)
                    command_started = started_file.exists()
                    started_attempts += int(command_started)
                    diagnostic = (
                        f"signal={received_signal}, start_mode={start_mode}, status={wrapper.returncode}, "
                        f"started={command_started}, stderr={stderr.strip()!r}"
                    )

                    self.assertNotEqual(0, wrapper.returncode, diagnostic)
                    if command_started:
                        self.assertEqual(-received_signal, wrapper.returncode, diagnostic)
                    self.assertFalse(survived_file.exists(), diagnostic)
            self.assertGreater(started_attempts, 0, f"no command started during {received_signal} stress")

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

    def test_cleanup_rejects_docker_daemon_error_during_network_query(self):
        test_dir = Path(__file__).parent
        with tempfile.TemporaryDirectory() as directory:
            fake_docker = Path(directory) / "docker"
            fake_docker.write_text(
                "#!/usr/bin/env sh\n"
                "case \"$1 $2\" in\n"
                "  'container ls') exit 0 ;;\n"
                "  'network rm') echo 'daemon API unavailable' >&2; exit 1 ;;\n"
                "  'network ls') echo 'daemon API unavailable' >&2; exit 1 ;;\n"
                "  *) echo \"unexpected Docker call: $*\" >&2; exit 9 ;;\n"
                "esac\n",
                encoding="utf-8",
            )
            fake_docker.chmod(0o755)
            environment = os.environ.copy()
            environment["OPERATIONS_COMPAT_DOCKER_BIN"] = str(fake_docker)

            result = subprocess.run(
                [test_dir / "cleanup-resources.sh", "daemonerror"],
                check=False,
                capture_output=True,
                text=True,
                timeout=2,
                env=environment,
            )

        self.assertNotEqual(0, result.returncode)
        self.assertIn("daemon API unavailable", result.stderr)

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
