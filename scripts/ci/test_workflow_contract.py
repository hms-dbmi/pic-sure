#!/usr/bin/env python3
"""Static pins over the GitHub Actions surface and the Maven POMs.

The contract, after the 2026-08 consolidation: ONE workflow builds the whole
reactor with plain Maven from the repository root -- Temurin Java 25, pinned
actions, no credentials -- and no POM resolves dependencies from the retired
pic-sure-common package repository (the root cause of the fork CI failures).

Plain `mvn` from the root is sufficient: the reactor model pool satisfies the
pic-sure-bom import, measured cold and tokenless. The failure mode being
pinned against is the old one -- per-service workflows running Maven from a
service working directory, whose single-module reactor cannot satisfy the
import and so reaches for a package registry.

Standard library only, so this runs on a bare runner with no pip step. PyYAML,
when present, backs a real syntax check; when absent that one test skips
(actionlint covers syntax out-of-band).
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO_ROOT / ".github" / "workflows"

FULL_REACTOR = "github-actions-test.yml"

CHECKOUT_SHA = "actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd"
SETUP_JAVA_SHA = "actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654"

DICTIONARY_MODULES = (
    "services/picsure-dictionary,"
    "services/picsure-dictionary/aggregate,"
    "services/picsure-dictionary/dictionaryweights"
)

# Mainline refs whose pushes build; PRs from anywhere build via pull_request.
PUSH_BRANCHES = ("pic_sure_api_mono_repo", "main")

RETIRED_PACKAGE_REPO = "https://maven.pkg.github.com/hms-dbmi/pic-sure-common"


def read(name):
    return (WORKFLOWS / name).read_text()


def pom_files():
    """Every tracked POM in the repository, ignoring build output."""
    return sorted(
        path
        for path in REPO_ROOT.rglob("pom.xml")
        if "target" not in path.parts and ".git" not in path.parts
    )


def resolution_source_blocks(pom_text):
    """Return the body of every <repositories>/<pluginRepositories> block.

    These are the blocks Maven downloads *from*. <distributionManagement> --
    where a repository the project publishes *to* is named, and where the
    retired pic-sure-common URL is deliberately retained -- is not matched, so
    a check built on this is context-aware rather than a substring sweep.
    """
    return [
        body
        for _, body in re.findall(
            r"<(repositories|pluginRepositories)>(.*?)</\1>", pom_text, re.DOTALL
        )
    ]


def setup_java_blocks(text):
    """Yield the lines following each pinned setup-java step, up to the next
    step (`- ...`). Positional, so a `cache: maven` on one step cannot vouch
    for another."""
    lines = text.splitlines()
    for i, line in enumerate(lines):
        if SETUP_JAVA_SHA not in line:
            continue
        block = []
        for follower in lines[i + 1:]:
            if re.match(r"\s*-\s", follower):
                break
            block.append(follower)
        yield "\n".join(block)


class TestWorkflowInventory(unittest.TestCase):
    def test_exactly_two_root_workflows_remain(self):
        found = sorted(p.name for p in WORKFLOWS.glob("*.yml"))
        self.assertEqual(
            found, [FULL_REACTOR, "label-checker.yml"],
            "the CI surface is one Maven workflow plus the label checker; "
            f"found {found}. Per-service workflows were deliberately removed: "
            "they duplicated the unfiltered full reactor.",
        )

    def test_no_nested_workflow_directories_remain(self):
        nested = [
            str(path.relative_to(REPO_ROOT))
            for path in REPO_ROOT.glob("*/**/.github/workflows")
            if path.is_dir() and any(path.iterdir())
        ]
        self.assertEqual(nested, [], f"non-empty nested workflow directories: {nested}")

    def test_label_checker_is_preserved(self):
        label_checker = WORKFLOWS / "label-checker.yml"
        self.assertTrue(label_checker.is_file(), "the root label-checker.yml must survive")
        self.assertIn("pull-request-label-checker", label_checker.read_text())

    def test_the_bootstrap_helper_stays_deleted(self):
        helper = REPO_ROOT / "scripts" / "ci" / "run-maven-reactor.sh"
        self.assertFalse(
            helper.exists(),
            "run-maven-reactor.sh was removed: plain `mvn` from the root "
            "resolves the reactor; a returning helper means the premise "
            "changed and this contract needs rethinking, not just a re-add",
        )


class TestFullReactorWorkflow(unittest.TestCase):
    def setUp(self):
        self.text = read(FULL_REACTOR)

    def test_runs_plain_maven_from_the_repository_root(self):
        self.assertIn("mvn -B verify", self.text)
        self.assertNotIn("working-directory", self.text)
        self.assertNotIn("run-maven-reactor", self.text)

    def test_lints_the_dictionary_modules(self):
        self.assertIn(f"mvn -B -pl {DICTIONARY_MODULES} spotless:check", self.text)

    def test_runs_the_ci_regression_tests(self):
        self.assertIn("unittest", self.text)
        self.assertIn("scripts/ci", self.text)

    def test_pull_requests_build_unfiltered(self):
        self.assertRegex(self.text, r"(?m)^\s{2}pull_request:\s*$",
                         "pull_request must be bare: every PR gets the full reactor")
        self.assertNotIn("paths:", self.text)

    def test_pushes_build_only_mainline_branches(self):
        match = re.search(r"(?m)^\s{2}push:\s*\n\s+branches:\s*\[([^\]]*)\]", self.text)
        self.assertIsNotNone(match, "push trigger must carry a branches list -- "
                             "unfiltered push double-builds every same-repo PR")
        branches = [b.strip().strip("'\"") for b in match.group(1).split(",")]
        self.assertEqual(branches, list(PUSH_BRANCHES))

    def test_pr_runs_supersede_but_mainline_runs_complete(self):
        self.assertIn("concurrency", self.text)
        self.assertIn(
            "cancel-in-progress: ${{ github.event_name == 'pull_request' }}",
            self.text,
            "cancel-in-progress must be conditional: unconditional true "
            "cancels mainline runs and leaves commits with no verdict",
        )

    def test_uses_temurin_25_with_maven_cache_everywhere(self):
        versions = re.findall(r"java-version:\s*'?\"?([^'\"\s]+)", self.text)
        self.assertTrue(versions, "no java-version declared")
        self.assertEqual(set(versions), {"25"})
        distributions = re.findall(r"distribution:\s*'?\"?([^'\"\s]+)", self.text)
        self.assertEqual(set(distributions), {"temurin"})
        blocks = list(setup_java_blocks(self.text))
        self.assertTrue(blocks, "no pinned setup-java step found")
        for i, block in enumerate(blocks):
            self.assertIn("cache: maven", block, f"setup-java step {i} lacks the Maven cache")

    def test_actions_are_pinned_to_the_approved_shas(self):
        for line in self.text.splitlines():
            if "actions/checkout@" in line:
                self.assertIn(CHECKOUT_SHA, line, line.strip())
            if "actions/setup-java@" in line:
                self.assertIn(SETUP_JAVA_SHA, line, line.strip())
        self.assertIn(CHECKOUT_SHA, self.text)

    def test_needs_no_credentials(self):
        for needle in ("GITHUB_TOKEN", "github.token"):
            self.assertNotIn(needle, self.text,
                             f"{FULL_REACTOR} references {needle}; the reactor "
                             "build needs no credentials")

    def test_read_only_permissions(self):
        self.assertIn("permissions:", self.text)
        self.assertIn("contents: read", self.text)


class TestRetiredPackageRepository(unittest.TestCase):
    def test_no_pom_resolves_from_the_retired_package_repository(self):
        for pom in pom_files():
            with self.subTest(pom=str(pom.relative_to(REPO_ROOT))):
                for block in resolution_source_blocks(pom.read_text()):
                    self.assertNotIn(
                        RETIRED_PACKAGE_REPO, block,
                        "a <repositories>/<pluginRepositories> block resolves "
                        "from the retired pic-sure-common registry -- the root "
                        "cause of the fork CI failures",
                    )

    def test_the_block_matcher_sees_resolution_sources(self):
        # Self-test: the context matcher must flag the URL inside a
        # repositories block and ignore it inside distributionManagement,
        # else the pin above passes vacuously.
        resolves = f"""<project><repositories>
            <repository><url>{RETIRED_PACKAGE_REPO}</url></repository>
        </repositories></project>"""
        publishes = f"""<project><distributionManagement>
            <repository><url>{RETIRED_PACKAGE_REPO}</url></repository>
        </distributionManagement></project>"""
        self.assertIn(RETIRED_PACKAGE_REPO, "".join(resolution_source_blocks(resolves)))
        self.assertEqual(resolution_source_blocks(publishes), [])


class TestWorkflowSyntax(unittest.TestCase):
    def test_all_retained_workflows_are_valid_yaml(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML unavailable; syntax checked by actionlint out-of-band")
        for path in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=path.name):
                document = yaml.safe_load(path.read_text())
                self.assertIsInstance(document, dict, f"{path.name} is not a YAML mapping")
                self.assertIn("jobs", document, f"{path.name} declares no jobs")


if __name__ == "__main__":
    unittest.main()
