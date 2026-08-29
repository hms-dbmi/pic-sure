#!/usr/bin/env python3

import csv
import hashlib
import json
import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PROOF = ROOT / "tests" / "banner-feed-compatibility"


class BannerFeedCompatibilityContractTest(unittest.TestCase):

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
        }
        for value in expected:
            self.assertIn(value, source)

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

        contract = json.loads((ROOT / ".github" / "banner-rollout-contract.json").read_text(encoding="utf-8"))
        self.assertEqual(2, contract["schemaVersion"])
        self.assertEqual(
            [
                "FREEZE_BANNER_MANAGEMENT_WRITES",
                "ROLL_BACK_FRONTEND",
                "DISABLE_ACTIVE_AND_SCHEDULED_TARGETED_BANNERS_BEFORE_LEGACY_ACTIVE_FEED_BACKEND",
                "ROLL_BACK_OPERATIONS_AND_GATEWAY",
                "KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND",
                "RECREATE_PSAMA",
            ],
            contract["rollbackPhases"],
        )
        self.assertEqual(
            "KEEP_BANNER_MANAGEMENT_WRITES_FROZEN_BELOW_TARGETING_CAPABLE_BACKEND",
            contract["managementWriteFreezeBoundary"],
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
        for field in ("requestedFeedUrls", "feedResponses", "renderedMarkers", "regionPresent"):
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
