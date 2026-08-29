#!/usr/bin/env python3

import csv
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
PROOF = ROOT / "tests" / "banner-feed-compatibility"
sys.path.insert(0, str(PROOF))

import contract  # noqa: E402
import run as compatibility_run  # noqa: E402


class BannerFeedCompatibilityContractTest(unittest.TestCase):

    @staticmethod
    def git_repository():
        directory = tempfile.TemporaryDirectory()
        repository = Path(directory.name)
        subprocess.run(["git", "init", "--quiet", repository], check=True)
        subprocess.run(["git", "-C", repository, "config", "user.name", "Compatibility Test"], check=True)
        subprocess.run(
            ["git", "-C", repository, "config", "user.email", "compatibility@example.invalid"], check=True
        )
        tracked = repository / "tracked.txt"
        tracked.write_text("fixture\n", encoding="utf-8")
        subprocess.run(["git", "-C", repository, "add", "tracked.txt"], check=True)
        subprocess.run(["git", "-C", repository, "commit", "--quiet", "-m", "fixture"], check=True)
        return directory, repository

    def source(self, name):
        path = PROOF / name
        self.assertTrue(path.is_file(), f"missing Ticket 18 proof file: {path}")
        return path.read_text(encoding="utf-8")

    def test_exact_source_and_input_pins_are_bound(self):
        source = self.source("run.py")
        expected = {
            "9251d64f607acc198d95c7d53294807cc56efa82",
            "9c17b0caecbee1b7f2231ca974b8b8b59ba7f211",
            "e49ae2d07cfb76cdbe9186161c3d726ae76ba416",
            "7b69aa960ff98f97c1a2d026b7137b0e3dcdf603",
            "05b1a77512dc0921570f0d442853fdcee75b8131",
            "5d2ba9f59f161ace5e807c82a0580518a9d44d16",
            "d6195f4acced760904d1e0d025dc86c4983fa64f",
            "c211efbbe69944c791b2d7f897b9d05b1593e71d",
            "419ef5cf7ff8f9981218976e93a14f51ea17b8f2",
            "e4506d9e5bca3a42da2e5436750c8951da2076ee",
            "23a550f373f07475efd8a838161e5e031e8706b14640a8a13d44de9ef0c9938e",
            "47fe7fcc0c0d775ad771ceca0f28327d019d2816639e88699eeae62256a2d2bc",
            "a211596a81df2488caad8a9ffefe881aff9804fda7a6199e3968cbdf1535614d",
            "ce4b2f3b448e254f2017e88a2649136e9d9edbec4ca4a187274d3509458ea23f",
        }
        for value in expected:
            self.assertIn(value, source)
        for ticket17_commit in (
            "97d772913aa147207f9ddcf16f8c2cfdf5ede646",
            "e9e457cd285e185bdfb78d54b166fe5ded161335",
            "4fbeb285cd584ff993ce93d3830e85aad1a14490",
        ):
            self.assertIn(ticket17_commit, compatibility_run.TICKET17_BACKEND_COMMITS)

    def test_matrix_has_the_complete_five_cell_contract(self):
        matrix = PROOF / "matrix.tsv"
        self.assertTrue(matrix.is_file(), f"missing Ticket 18 matrix: {matrix}")
        with matrix.open(encoding="utf-8", newline="") as handle:
            rows = list(csv.DictReader(handle, delimiter="\t"))
        self.assertEqual(
            [
                "final-backend-old-frontend",
                "final-backend-final-frontend",
                "old-backend-final-frontend",
                "old-backend-old-frontend-unsafe",
                "supported-rollback-sequence",
            ],
            [row["cell"] for row in rows],
        )
        self.assertEqual(
            ["PASS", "PASS", "REJECTED_EXPECTED", "UNSAFE_EXPECTED", "PASS"],
            [row["result"] for row in rows],
        )
        self.assertTrue(all(row["observed_sha256"] for row in rows))

    def test_matrix_loader_rejects_a_missing_runtime_field(self):
        with tempfile.TemporaryDirectory() as directory:
            matrix = Path(directory) / "matrix.tsv"
            matrix.write_text((PROOF / "matrix.tsv").read_text(encoding="utf-8"), encoding="utf-8")
            rows = matrix.read_text(encoding="utf-8").splitlines()
            fields = rows[1].split("\t")
            fields[5] = ""
            rows[1] = "\t".join(fields)
            matrix.write_text("\n".join(rows) + "\n", encoding="utf-8")

            with self.assertRaisesRegex(contract.ContractError, "empty browser_path"):
                contract.load_matrix(matrix)

    def test_git_source_validation_rejects_tree_drift_and_dirty_inputs(self):
        directory, repository = self.git_repository()
        try:
            commit = subprocess.run(
                ["git", "-C", repository, "rev-parse", "HEAD"], check=True, capture_output=True, text=True
            ).stdout.strip()
            with self.assertRaisesRegex(contract.ContractError, "tree mismatch"):
                contract.require_git_tree(repository, "fixture", commit, "0" * 40)
            (repository / "tracked.txt").write_text("dirty\n", encoding="utf-8")
            with self.assertRaisesRegex(contract.ContractError, "modified or untracked"):
                contract.require_clean_repository(repository, "fixture")
        finally:
            directory.cleanup()

    def test_observation_is_derived_from_runtime_state_and_browser_evidence(self):
        harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
        harness.current_backend_generation = "old"
        harness.current_frontend_generation = "old"
        harness.operations_source = Path("/runtime/backend")
        harness.frontend_source = Path("/runtime/frontend")
        harness.runtime_git_identity = mock.Mock(
            side_effect=[
                (compatibility_run.OLD_BACKEND_COMMIT, compatibility_run.BACKEND_TREES["old"]),
                (compatibility_run.OLD_FRONTEND_COMMIT, compatibility_run.FRONTEND_TREES["old"]),
            ]
        )
        browser = {
            "pageUrl": "http://frontend/login?redirected=true",
            "observedFeedPath": "/picsure/operations/banners/active",
            "observedFeedStatus": 200,
            "renderedMarkers": [
                compatibility_run.FIXTURES["all"]["title"],
                compatibility_run.FIXTURES["login"]["title"],
                compatibility_run.FIXTURES["not-login"]["title"],
            ],
            "regionPresent": True,
        }

        observed = harness.observation("old-backend-old-frontend-unsafe", browser)

        self.assertEqual("/login", observed["browser_path"])
        self.assertEqual(compatibility_run.OLD_BACKEND_COMMIT, observed["backend_commit"])
        self.assertEqual(compatibility_run.FRONTEND_TREES["old"], observed["frontend_tree"])
        self.assertEqual(200, int(observed["http_status"]))

        browser["renderedMarkers"] = [compatibility_run.FIXTURES["all"]["title"]]
        with self.assertRaisesRegex(contract.ContractError, "cannot classify"):
            harness.runtime_outcome("old-backend-old-frontend-unsafe", browser, browser)

    def test_observation_match_rejects_runtime_provenance_drift(self):
        harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
        expected = {key: "expected" for key in contract.MATRIX_HEADER}
        expected["cell"] = "fixture"
        harness.expected_by_cell = {"fixture": expected}
        observed = dict(expected)
        observed["backend_tree"] = "runtime-tree"

        with self.assertRaisesRegex(contract.ContractError, "backend_tree"):
            harness.require_observation_matches(observed)

    def test_supported_rollback_state_blocks_writes_and_early_backend_transition(self):
        harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
        harness.rollback_state = "FRONTEND_ROLLED_BACK"
        harness.management_writes_frozen = True
        harness.current_backend_generation = "final"
        harness.current_frontend_generation = "old"
        harness.require_backend_rollback_allowed = mock.Mock(
            side_effect=contract.ContractError("targeted banners remain Active or Scheduled")
        )
        harness.stop_backend = mock.Mock()

        with self.assertRaisesRegex(contract.ContractError, "targeted banners remain Active or Scheduled"):
            harness.start_backend("old")
        harness.stop_backend.assert_not_called()
        with self.assertRaisesRegex(contract.ContractError, "management writes are frozen"):
            harness.management_write("POST", "http://operations/banners", {})

    def test_frontend_rollback_is_rejected_before_write_freeze(self):
        harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
        harness.rollback_state = "FINAL_RUNNING"
        harness.management_writes_frozen = False
        harness.stop_frontend = mock.Mock()

        with self.assertRaisesRegex(contract.ContractError, "until management writes are frozen"):
            harness.start_frontend("old")
        harness.stop_frontend.assert_not_called()

    def test_harness_rejects_rollout_contract_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            contract_path = repository / ".github" / "banner-rollout-contract.json"
            contract_path.parent.mkdir()
            contract_path.write_text(
                json.dumps(
                    {
                        "rollbackPhases": list(compatibility_run.REQUIRED_ROLLBACK_PHASES),
                        "rollbackStateContract": {
                            **compatibility_run.REQUIRED_ROLLBACK_STATE_CONTRACT,
                            "frontendFirstRollbackAloneSafe": True,
                        },
                    }
                ),
                encoding="utf-8",
            )
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.repository_root = repository

            with self.assertRaisesRegex(contract.ContractError, "rollback state contract drift"):
                harness.require_current_rollout_contract()

    def test_ticket17_process_group_timeout_kills_descendants(self):
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory) / "ready"
            grandchild_code = (
                "import signal, sys, time\n"
                "def stop(_signum, _frame):\n"
                "    print('ticket17 drained stdout', flush=True)\n"
                "    print('ticket17 drained stderr', file=sys.stderr, flush=True)\n"
                "    raise SystemExit(0)\n"
                "signal.signal(signal.SIGTERM, stop)\n"
                "time.sleep(30)\n"
            )
            child_code = (
                "import pathlib, subprocess, sys\n"
                "print('ticket17 partial stdout', flush=True)\n"
                "print('ticket17 partial stderr', file=sys.stderr, flush=True)\n"
                f"child = subprocess.Popen([sys.executable, '-c', {grandchild_code!r}])\n"
                "pathlib.Path(sys.argv[1]).write_text(str(child.pid), encoding='utf-8')\n"
            )
            started = time.monotonic()
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.active_process_group = None
            leader_status_at_cleanup = []
            terminate = harness.terminate_active_process_group

            def record_leader_status():
                leader_status_at_cleanup.append(harness.active_process_group.poll())
                return terminate()

            harness.terminate_active_process_group = record_leader_status
            result = harness.process_group_command(
                [sys.executable, "-c", child_code, ready], timeout=2, capture=True, merge_stderr=True
            )
            elapsed = time.monotonic() - started
            self.assertTrue(ready.is_file(), "process-group timeout fixture did not become ready")
            child_pid = int(ready.read_text(encoding="utf-8"))
            for _ in range(100):
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.01)
            else:
                self.fail(f"timed-out process group left descendant {child_pid} running")

            self.assertIsInstance(result.stdout, str)
            self.assertIn("ticket17 partial stdout", result.stdout)
            self.assertIn("ticket17 partial stderr", result.stdout)
            self.assertIn("ticket17 drained stdout", result.stdout)
            self.assertIn("ticket17 drained stderr", result.stdout)
            harness.ticket17_dir = PROOF.parent / "operations-binary-compatibility"
            harness.temp_root = Path(directory)
            harness.operations_source = ROOT
            harness.aio_source = ROOT
            harness.bdc_source = ROOT
            harness.process_group_command = mock.Mock(return_value=result)
            with self.assertRaisesRegex(contract.ContractError, "failed with status 124"):
                harness.run_ticket17_composition()
            diagnostics = (Path(directory) / "ticket17.log").read_text(encoding="utf-8")
            ticket17_result = json.loads(
                (Path(directory) / "ticket17-result.json").read_text(encoding="utf-8")
            )
            self.assertIn("ticket17 partial stdout", diagnostics)
            self.assertIn("ticket17 drained stdout", diagnostics)
            self.assertEqual(124, ticket17_result["status"])
            self.assertFalse(ticket17_result["passed"])

        self.assertEqual(124, result.returncode)
        self.assertEqual([0], leader_status_at_cleanup)
        self.assertLess(elapsed, 4.0)

    def test_ticket17_process_group_forwards_outer_signal(self):
        with tempfile.TemporaryDirectory() as directory:
            ready = Path(directory) / "ready"
            fixture = (
                "import pathlib, signal, subprocess, sys, time\n"
                f"sys.path.insert(0, {str(PROOF)!r})\n"
                "import run\n"
                "harness = run.Harness.__new__(run.Harness)\n"
                "harness.active_process_group = None\n"
                "def stop(_signum, _frame):\n"
                "    harness.terminate_active_process_group()\n"
                "    raise KeyboardInterrupt\n"
                "signal.signal(signal.SIGTERM, stop)\n"
                "child_code = (\"import pathlib, subprocess, sys, time\\n\"\n"
                "    \"child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])\\n\"\n"
                "    \"pathlib.Path(sys.argv[1]).write_text(str(child.pid), encoding='utf-8')\\n\"\n"
                "    \"time.sleep(30)\\n\")\n"
                "harness.process_group_command([sys.executable, '-c', child_code, sys.argv[1]], timeout=30)\n"
            )
            wrapper = subprocess.Popen(
                [sys.executable, "-c", fixture, ready],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )
            deadline = time.monotonic() + 2
            while not ready.is_file() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(ready.is_file(), "process-group signal fixture did not start")
            child_pid = int(ready.read_text(encoding="utf-8"))
            wrapper.send_signal(signal.SIGTERM)
            wrapper.communicate(timeout=2)
            for _ in range(100):
                try:
                    os.kill(child_pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.01)
            else:
                self.fail(f"signaled composition left descendant {child_pid} running")

        self.assertNotEqual(0, wrapper.returncode)

    def test_process_group_sigkill_drain_is_bounded(self):
        process = mock.Mock()
        process.pid = 12345
        process.stdout = mock.Mock()
        process.stderr = mock.Mock()
        process.communicate.side_effect = [
            subprocess.TimeoutExpired("fixture", 1, output=b"term output", stderr=b"term error"),
            subprocess.TimeoutExpired("fixture", 1, output=b"kill output", stderr=b"kill error"),
        ]
        process.wait.return_value = -signal.SIGKILL
        harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
        harness.active_process_group = process

        with mock.patch.object(compatibility_run.os, "killpg") as killpg:
            stdout, stderr_text = harness.terminate_active_process_group()

        self.assertEqual("kill output", stdout)
        self.assertIn("kill error", stderr_text)
        self.assertIn("diagnostics may be partial", stderr_text)
        self.assertEqual([mock.call(timeout=1), mock.call(timeout=1)], process.communicate.call_args_list)
        process.wait.assert_called_once_with(timeout=1)
        process.stdout.close.assert_called_once_with()
        process.stderr.close.assert_called_once_with()
        self.assertEqual(
            [mock.call(process.pid, signal.SIGTERM), mock.call(process.pid, signal.SIGKILL)],
            killpg.call_args_list,
        )
        self.assertIsNone(harness.active_process_group)

    def test_ticket17_cleanup_rejects_invalid_run_id(self):
        with tempfile.TemporaryDirectory() as directory:
            ticket17_temp = Path(directory) / "ticket17-temp"
            (ticket17_temp / "operations-binary-bad_suffix").mkdir(parents=True)
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.temp_root = Path(directory)
            harness.ticket17_dir = PROOF.parent / "operations-binary-compatibility"
            harness.command = mock.Mock()

            with self.assertRaisesRegex(contract.ContractError, "invalid Ticket 17 cleanup run ID"):
                harness.cleanup_ticket17_resources()
            harness.command.assert_not_called()

    def test_fetched_multi_commit_source_checks_out_requested_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.command = mock.Mock(return_value=subprocess.CompletedProcess([], 0, "", ""))
            commits = ["old", "final", "ticket17"]
            destination = Path(directory) / "source"

            harness.fetch_repository("https://example.invalid/repository.git", commits, destination, "source", "final")

            self.assertEqual(
                ["git", "-C", destination, "checkout", "--quiet", "--detach", "final"],
                harness.command.call_args_list[-1].args[0],
            )

    def test_pre_cell_failure_writes_structured_diagnostics(self):
        with tempfile.TemporaryDirectory() as directory:
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.temp_root = Path(directory)
            harness.selection = "final-backend-old-frontend"
            harness.current_phase = "initialization"
            harness.observations = []
            harness.require_tools_and_runtime = mock.Mock(
                side_effect=contract.ContractError("synthetic runtime failure")
            )
            harness.cleanup_cell_resources = mock.Mock(return_value=["synthetic cleanup detail"])
            harness.write_provenance = mock.Mock()

            with self.assertRaisesRegex(contract.ContractError, "synthetic runtime failure"):
                harness.run()

            observed = Path(directory) / "observed-matrix.tsv"
            failure = json.loads((Path(directory) / "failed-cell.json").read_text(encoding="utf-8"))
            self.assertTrue(observed.is_file())
            self.assertEqual("runtime-validation", failure["failed_phase"])
            self.assertIsNone(failure["failed_cell"])
            self.assertEqual(["synthetic cleanup detail"], failure["cleanup_errors"])

    def test_repeated_container_log_capture_uses_distinct_files(self):
        with tempfile.TemporaryDirectory() as directory:
            harness = compatibility_run.Harness.__new__(compatibility_run.Harness)
            harness.temp_root = Path(directory)
            harness.log_capture_counts = {}
            harness.command = mock.Mock(
                side_effect=[
                    subprocess.CompletedProcess([], 0, "first phase\n", ""),
                    subprocess.CompletedProcess([], 0, "second phase\n", ""),
                ]
            )

            harness.capture_logs("frontend-old")
            harness.capture_logs("frontend-old")

            logs = Path(directory) / "logs"
            self.assertEqual("first phase\n", (logs / "frontend-old.log").read_text(encoding="utf-8"))
            self.assertEqual("second phase\n", (logs / "frontend-old.2.log").read_text(encoding="utf-8"))

    def test_real_production_frontend_and_generated_inputs_are_required(self):
        source = self.source("run.py")
        self.assertIn('"Dockerfile"', source)
        self.assertIn('docker", "build"', source)
        self.assertIn("ENV_INPUT_SHA256", source)
        self.assertIn("VHOST_INPUT_SHA256", source)
        self.assertIn("httpd-vhosts.conf", source)
        self.assertIn("ProxyPass /picsure/ http://gateway:8080/", source)
        self.assertIn("ProxyPass / http://127.0.0.1:3000/", source)
        self.assertIn('"/login"', source)
        self.assertIn(
            "node:24.19.0-alpine3.23@sha256:244cc2b53f46f9e876304391d17682b0ddae9ac33491f4857e25e35a36ba7995",
            source,
        )
        self.assertIn(
            "httpd:2.4.68-alpine3.23@sha256:4a15e9c73f25334bc03cfb3c692c9adfc103bb46ca89cee1f0b9a5fcbc7b21f6",
            source,
        )
        self.assertIn('"v24.19.0"', source)

    def test_browser_uses_real_responses_without_mocking_or_retries(self):
        browser = self.source("browser.mjs")
        combined = browser + self.source("run.py")
        for forbidden in (
            "page.route(",
            "route.fulfill(",
            "waitForTimeout(",
            "vite preview",
            "mocked feed",
        ):
            self.assertNotIn(forbidden, combined)
        self.assertIn("page.on('request'", browser)
        self.assertIn("page.on('response'", browser)
        self.assertIn("site-banner-region", browser)
        self.assertIn("retriesDisabled", browser)

    def test_commands_diagnostics_and_cleanup_are_bounded(self):
        run_source = self.source("run.py")
        entrypoint = self.source("test.sh")
        cleanup = self.source("cleanup-resources.sh")
        self.assertIn("timeout=DEFAULT_COMMAND_TIMEOUT_SECONDS", run_source)
        self.assertIn("failed-cell.json", run_source)
        self.assertIn("observed-matrix.tsv", run_source)
        self.assertIn("cleanup_cell_resources", run_source)
        self.assertIn("cleanup-resources.sh", entrypoint)
        self.assertIn("bounded_command.py", cleanup)
        self.assertNotRegex(cleanup, r"(?m)^\s*docker\s")
        self.assertIn("org.pic-sure.banner-feed-compatibility", cleanup)
        self.assertIn("image ls --quiet --filter", cleanup)
        self.assertIn("image rm --force", cleanup)

    def test_ticket_17_is_composed_without_copying_its_rows(self):
        source = self.source("run.py")
        entrypoint = self.source("test.sh")
        self.assertIn("operations-binary-compatibility/test.sh", source)
        self.assertIn('"all"', source)
        self.assertIn("ticket17-result.json", source)
        self.assertIn("run.py", entrypoint)
        matrix = self.source("matrix.tsv")
        self.assertNotIn("lazy-version-recovery", matrix)

    def test_rollback_gate_requires_freeze_disable_and_retained_freeze(self):
        source = self.source("run.py")
        for event in (
            "FREEZE_BANNER_MANAGEMENT_WRITES",
            "ROLL_BACK_FRONTEND",
            "DISABLE_ACTIVE_AND_SCHEDULED_TARGETED_BANNERS_BEFORE_LEGACY_ACTIVE_FEED_BACKEND",
            "ROLL_BACK_OPERATIONS_AND_GATEWAY",
            "KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND",
        ):
            self.assertIn(event, source)
        self.assertIn("require_backend_rollback_allowed", source)
        self.assertIn("targeted banners remain Active or Scheduled", source)

        rollout_contract = json.loads(
            (ROOT / ".github" / "banner-rollout-contract.json").read_text(encoding="utf-8")
        )
        self.assertEqual(3, rollout_contract["schemaVersion"])
        self.assertEqual(
            list(compatibility_run.REQUIRED_ROLLBACK_PHASES),
            rollout_contract["rollbackPhases"],
        )
        self.assertEqual(
            "KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND",
            rollout_contract["managementWriteFreezeBoundary"],
        )
        self.assertEqual(
            compatibility_run.REQUIRED_ROLLBACK_STATE_CONTRACT,
            rollout_contract["rollbackStateContract"],
        )

    def test_fixed_fixtures_and_observation_fields_are_present(self):
        source = self.source("run.py")
        for value in (
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            "33333333-3333-4333-8333-333333333333",
            "44444444-4444-4444-8444-444444444444",
            '"/login"',
            '"/not-login"',
            '"/scheduled"',
        ):
            self.assertIn(value, source)
        browser = self.source("browser.mjs")
        for field in (
            "requestedFeedUrls",
            "feedResponses",
            "renderedMarkers",
            "regionPresent",
            "pageUrl",
            "observedFeedPath",
            "observedFeedStatus",
        ):
            self.assertIn(field, browser)

    def test_ci_is_read_only_bounded_and_path_scoped(self):
        workflow = (ROOT / ".github" / "workflows" / "banner-feed-compatibility.yml")
        self.assertTrue(workflow.is_file(), f"missing Ticket 18 workflow: {workflow}")
        source = workflow.read_text(encoding="utf-8")
        self.assertIn("contents: read", source)
        self.assertRegex(source, r"timeout-minutes:\s*[1-9][0-9]*")
        self.assertIn("pull_request:", source)
        self.assertIn("branches: [main]", source)
        self.assertIn("tests/banner-feed-compatibility/**", source)
        self.assertIn("actions/upload-artifact@", source)

    def test_python_proof_does_not_depend_on_optimized_assertions(self):
        pattern = re.compile(r"(?m)^\s*assert(?:\s|\()")
        for name in ("run.py", "contract.py"):
            self.assertIsNone(pattern.search(self.source(name)), name)


if __name__ == "__main__":
    unittest.main()
