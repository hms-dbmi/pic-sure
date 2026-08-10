#!/usr/bin/env python3
"""Behavioural pins for scripts/ci/run-maven-reactor.sh.

The helper is exercised against a fake ``mvn`` placed on PATH, so these tests
need neither a real Maven installation nor network access. Standard library
only: CI runs them on a bare runner with no pip install step.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
HELPER = REPO_ROOT / "scripts" / "ci" / "run-maven-reactor.sh"

# Each fake-mvn invocation appends one TAB-separated record:
#   <cwd>\t<MAVEN_OPTS>\t<args>
FAKE_MVN_OK = """#!/bin/sh
printf '%s\\t%s\\t%s\\n' "$PWD" "${MAVEN_OPTS-}" "$*" >> "$MVN_LOG"
"""

FAKE_MVN_FAIL_ON_BOM = """#!/bin/sh
printf '%s\\t%s\\t%s\\n' "$PWD" "${MAVEN_OPTS-}" "$*" >> "$MVN_LOG"
case "$*" in
  *platform/pom.xml*) exit 17 ;;
esac
"""


class HelperTestCase(unittest.TestCase):
    """Shared harness: a temp dir holding the fake mvn, the log, and a CWD."""

    fake_mvn_body = FAKE_MVN_OK

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.addCleanup(self._tmp.cleanup)

        self.bindir = self.tmp / "bin"
        self.bindir.mkdir()
        fake = self.bindir / "mvn"
        fake.write_text(self.fake_mvn_body)
        fake.chmod(0o755)

        self.log = self.tmp / "mvn.log"
        # A CWD that is emphatically not the repository root.
        self.cwd = self.tmp / "elsewhere"
        self.cwd.mkdir()

    def run_helper(self, *args, maven_opts=None, drop_github_token=True):
        env = dict(os.environ)
        env["PATH"] = f"{self.bindir}{os.pathsep}{env['PATH']}"
        env["MVN_LOG"] = str(self.log)
        if maven_opts is None:
            env.pop("MAVEN_OPTS", None)
        else:
            env["MAVEN_OPTS"] = maven_opts
        if drop_github_token:
            env.pop("GITHUB_TOKEN", None)
        return subprocess.run(
            [str(HELPER), *args],
            cwd=str(self.cwd),
            env=env,
            capture_output=True,
            text=True,
        )

    def records(self):
        if not self.log.exists():
            return []
        return [
            line.split("\t")
            for line in self.log.read_text().splitlines()
            if line.strip()
        ]


class TestHelperShape(HelperTestCase):
    def test_helper_exists_and_is_executable(self):
        self.assertTrue(HELPER.is_file(), f"{HELPER} is missing")
        self.assertTrue(os.access(HELPER, os.X_OK), f"{HELPER} is not executable")

    def test_no_args_is_a_usage_error(self):
        result = self.run_helper()
        self.assertEqual(result.returncode, 2, result.stderr)
        self.assertEqual(self.records(), [], "no Maven call should happen")


class TestTwoStageBootstrap(HelperTestCase):
    def test_bom_is_installed_before_the_requested_command(self):
        result = self.run_helper("verify")
        self.assertEqual(result.returncode, 0, result.stderr)

        records = self.records()
        self.assertEqual(len(records), 2, f"expected exactly 2 Maven calls, got {records}")
        self.assertEqual(records[0][2].split(), ["-B", "-f", "platform/pom.xml", "install"])

    def test_requested_arguments_are_forwarded_unchanged(self):
        args = ["-pl", "services/pic-sure-logging", "-am", "verify", "-Dfoo=bar baz"]
        result = self.run_helper(*args)
        self.assertEqual(result.returncode, 0, result.stderr)

        second = self.records()[1][2].split()
        # The helper may prefix batch mode; the caller's arguments must survive
        # verbatim, in order, as the tail of the command line.
        self.assertEqual(second[-6:], ["-pl", "services/pic-sure-logging", "-am", "verify", "-Dfoo=bar", "baz"])
        self.assertIn("-B", second)

    def test_both_calls_run_from_the_repository_root(self):
        result = self.run_helper("verify")
        self.assertEqual(result.returncode, 0, result.stderr)

        expected = str(REPO_ROOT.resolve())
        for index, record in enumerate(self.records()):
            self.assertEqual(
                str(Path(record[0]).resolve()), expected,
                f"Maven call {index} ran in {record[0]}, not the repository root",
            )

    def test_maven_opts_reach_both_calls(self):
        sentinel = "-Dmaven.repo.local=/tmp/pinned-repo"
        result = self.run_helper("verify", maven_opts=sentinel)
        self.assertEqual(result.returncode, 0, result.stderr)

        records = self.records()
        self.assertEqual(len(records), 2)
        for index, record in enumerate(records):
            self.assertEqual(
                record[1], sentinel,
                f"Maven call {index} lost MAVEN_OPTS; both stages must share maven.repo.local",
            )

    def test_succeeds_without_a_github_token(self):
        result = self.run_helper("verify", drop_github_token=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("GITHUB_TOKEN", HELPER.read_text())


class TestFailFast(HelperTestCase):
    fake_mvn_body = FAKE_MVN_FAIL_ON_BOM

    def test_a_failed_bom_install_aborts_before_the_reactor_runs(self):
        result = self.run_helper("verify")
        self.assertNotEqual(result.returncode, 0, "helper masked a failed BOM install")

        records = self.records()
        self.assertEqual(len(records), 1, f"reactor ran despite a failed BOM install: {records}")


if __name__ == "__main__":
    unittest.main()
