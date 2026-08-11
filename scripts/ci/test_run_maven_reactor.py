#!/usr/bin/env python3
"""Behavioural pins for scripts/ci/run-maven-reactor.sh.

Deliberately narrow. Every workflow in .github/workflows runs this helper for
real on every pull request, so a regression in anything CI exercises surfaces
there, with better evidence than a fake-mvn harness can give. What CI cannot
see is what these three tests cover:

* Argument boundaries. No workflow passes an argument containing a space, so
  an unquoted ``$@`` would forward the same tokens CI expects and go unnoticed.
* The ``cd`` to the repository root. CI already starts there, making the ``cd``
  a no-op; delete it and CI stays green while anyone invoking the helper from a
  subdirectory (as the README suggests) breaks.
* Fail-fast, which doubles as the pin on stage 1 existing at all: the failing
  fake-mvn only trips on ``platform/pom.xml``, so dropping the BOM install
  makes this test fail.

The helper is exercised against a fake ``mvn`` on PATH -- no real Maven, no
network. Standard library only: CI runs this on a bare runner with no pip step.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
HELPER = REPO_ROOT / "scripts" / "ci" / "run-maven-reactor.sh"

# Each fake-mvn invocation appends one TAB-separated record:
#   <cwd>\t<arg>|<arg>|...|
#
# The argument field is pipe-terminated per argument rather than "$*"-joined:
# "$*" flattens argv into one space-separated string, which cannot tell
# `mvn "a b"` from `mvn a b` -- and so would pass just as happily if the
# helper forwarded an unquoted $@ and let the shell re-split the caller's
# arguments. The delimiter keeps the boundaries observable.
FAKE_MVN_OK = """#!/bin/sh
printf '%s\\t' "$PWD" >> "$MVN_LOG"
printf '%s|' "$@" >> "$MVN_LOG"
printf '\\n' >> "$MVN_LOG"
"""

# Same, but fails the stage-1 BOM install. The `platform/pom.xml` match is
# load-bearing: it is what makes test_a_failed_bom_install_aborts_before_the
# _reactor_runs fail if stage 1 is ever removed.
FAKE_MVN_FAIL_ON_BOM = FAKE_MVN_OK + """
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

    def run_helper(self, *args):
        env = dict(os.environ)
        env["PATH"] = f"{self.bindir}{os.pathsep}{env['PATH']}"
        env["MVN_LOG"] = str(self.log)
        # The helper must never need a token; run it as a fork runner would.
        env.pop("GITHUB_TOKEN", None)
        return subprocess.run(
            [str(HELPER), *args],
            cwd=str(self.cwd),
            env=env,
            capture_output=True,
            text=True,
        )

    def records(self):
        """Each fake-mvn call as (cwd, argv), argv unflattened.

        Arguments are recovered from the '|'-terminated field, so a single
        argument containing a space stays a single element. (No test argument
        contains a '|' or a newline; those would need a richer encoding.)
        """
        if not self.log.exists():
            return []
        parsed = []
        for line in self.log.read_text().splitlines():
            if not line.strip():
                continue
            cwd, packed = line.split("\t")
            argv = packed.split("|")
            self.assertEqual(
                argv[-1], "", f"malformed fake-mvn record, not '|'-terminated: {line!r}"
            )
            parsed.append((cwd, argv[:-1]))
        return parsed


class TestTwoStageBootstrap(HelperTestCase):
    def test_requested_arguments_are_forwarded_unchanged(self):
        # The last argument embeds a space on purpose: it must arrive as one
        # argument, which is what fails if the helper forwards $@ unquoted.
        args = ["-pl", "services/pic-sure-logging", "-am", "verify", "-Dfoo=bar baz"]
        result = self.run_helper(*args)
        self.assertEqual(result.returncode, 0, result.stderr)

        second = self.records()[1][1]
        # The helper may prefix batch mode; the caller's arguments must survive
        # verbatim, in order, and with their boundaries intact, as the tail of
        # the command line.
        self.assertEqual(second[-len(args):], args)
        self.assertIn("-B", second)

    def test_both_calls_run_from_the_repository_root(self):
        result = self.run_helper("verify")
        self.assertEqual(result.returncode, 0, result.stderr)

        records = self.records()
        self.assertEqual(len(records), 2, f"expected exactly 2 Maven calls, got {records}")

        expected = str(REPO_ROOT.resolve())
        for index, (cwd, _argv) in enumerate(records):
            self.assertEqual(
                str(Path(cwd).resolve()), expected,
                f"Maven call {index} ran in {cwd}, not the repository root",
            )


class TestFailFast(HelperTestCase):
    fake_mvn_body = FAKE_MVN_FAIL_ON_BOM

    def test_a_failed_bom_install_aborts_before_the_reactor_runs(self):
        result = self.run_helper("verify")
        self.assertNotEqual(result.returncode, 0, "helper masked a failed BOM install")

        records = self.records()
        self.assertEqual(len(records), 1, f"reactor ran despite a failed BOM install: {records}")


if __name__ == "__main__":
    unittest.main()
