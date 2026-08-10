#!/usr/bin/env python3
"""Static pins over the GitHub Actions surface and the root POM.

These encode the fork-safe CI contract: every active Maven workflow builds the
checked-out reactor through scripts/ci/run-maven-reactor.sh, on Java 25, from
the repository root, without private-package credentials.

Standard library only, so this runs on a bare runner. PyYAML, when present, is
used for a real syntax check; when absent that one test skips.
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO_ROOT / ".github" / "workflows"
HELPER_REF = "scripts/ci/run-maven-reactor.sh"

CHECKOUT_SHA = "actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd"
SETUP_JAVA_SHA = "actions/setup-java@be666c2fcd27ec809703dec50e508c2fdc7f6654"

ACTIVE_MAVEN_WORKFLOWS = (
    "github-actions-test.yml",
    "pic-sure-auth-microapp-test.yml",
    "pic-sure-logging-test.yml",
    "picsure-dictionary-test.yml",
    "visualization-service-test.yml",
)

FULL_REACTOR_WORKFLOW = "github-actions-test.yml"

# Shared build inputs: a change to any of these must not be able to slip past a
# path-filtered service workflow.
SHARED_BUILD_INPUTS = ("pom.xml", "platform/**", "libs/**", "scripts/ci/**")

# workflow filename -> the reactor selection its Maven step must use
REQUIRED_SELECTIONS = {
    "pic-sure-auth-microapp-test.yml": [
        "-pl services/pic-sure-auth-microapp/pic-sure-auth-services -am verify",
    ],
    "pic-sure-logging-test.yml": [
        "-pl services/pic-sure-logging -am verify",
    ],
    "picsure-dictionary-test.yml": [
        "-pl services/picsure-dictionary,services/picsure-dictionary/aggregate,"
        "services/picsure-dictionary/dictionaryweights -am verify",
        "-pl services/picsure-dictionary,services/picsure-dictionary/aggregate,"
        "services/picsure-dictionary/dictionaryweights spotless:check",
    ],
    "visualization-service-test.yml": [
        "-pl services/pic-sure-visualization-service -am verify",
    ],
    FULL_REACTOR_WORKFLOW: [
        "--update-snapshots verify",
    ],
}

STALE_WORKFLOWS = (
    ".github/workflows/github-actions-deploy-snapshots.yml",
    ".github/workflows/pic-sure-services-test.yml",
    "libs/pic-sure-commons/.github/workflows/github-actions-deploy-snapshots.yml",
    "libs/pic-sure-commons/.github/workflows/github-actions-test.yml",
    "libs/pic-sure-commons/.github/workflows/label-checker.yml",
    "libs/pic-sure-logging-client/.github/workflows/ci.yml",
    "libs/pic-sure-logging-client/.github/workflows/label-checker.yml",
    "libs/pic-sure-logging-client/.github/workflows/publish.yml",
    "services/pic-sure-auth-microapp/.github/workflows/github-actions-test.yml",
    "services/pic-sure-auth-microapp/.github/workflows/label-checker.yml",
    "services/pic-sure-hpds/.github/workflows/github-actions-deploy-snapshots.yml",
    "services/pic-sure-hpds/.github/workflows/github-actions-test.yml",
    "services/pic-sure-hpds/.github/workflows/label-checker.yml",
    "services/pic-sure-logging/.github/workflows/ci.yml",
    "services/picsure-dictionary/.github/workflows/linting.yml",
    "services/picsure-dictionary/.github/workflows/maven.yml",
)

RETIRED_PACKAGE_REPO = "https://maven.pkg.github.com/hms-dbmi/pic-sure-common"


def read(name):
    return (WORKFLOWS / name).read_text()


def normalise_run_blocks(text):
    """Collapse YAML line continuations so multi-line `run:` steps match."""
    return re.sub(r"\\\s*\n\s*", " ", text)


class TestActiveWorkflows(unittest.TestCase):
    def test_all_active_workflows_exist(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                self.assertTrue((WORKFLOWS / name).is_file(), f"{name} is missing")

    def test_every_maven_step_goes_through_the_helper(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = normalise_run_blocks(read(name))
                self.assertIn(HELPER_REF, text, f"{name} does not invoke {HELPER_REF}")
                bare = [
                    line.strip()
                    for line in text.splitlines()
                    if re.search(r"(^|[:|>\s])mvn\s", line) and HELPER_REF not in line
                ]
                self.assertEqual(bare, [], f"{name} calls Maven directly: {bare}")

    def test_every_setup_java_uses_temurin_25(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = read(name)
                versions = re.findall(r"java-version:\s*'?\"?([^'\"\s]+)", text)
                self.assertTrue(versions, f"{name} declares no java-version")
                self.assertEqual(
                    set(versions), {"25"}, f"{name} pins a non-25 JDK: {versions}"
                )
                distributions = re.findall(r"distribution:\s*'?\"?([^'\"\s]+)", text)
                self.assertEqual(
                    set(distributions), {"temurin"},
                    f"{name} uses a non-Temurin distribution: {distributions}",
                )

    def test_maven_cache_is_enabled_wherever_java_is_set_up(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = read(name)
                setups = text.count(SETUP_JAVA_SHA)
                caches = len(re.findall(r"cache:\s*maven", text))
                self.assertEqual(
                    setups, caches,
                    f"{name} has {setups} setup-java step(s) but {caches} maven cache(s)",
                )

    def test_no_service_level_working_directory(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                self.assertNotIn(
                    "working-directory", read(name),
                    f"{name} still sets a working-directory; Maven must run from the root",
                )

    def test_no_workflow_injects_a_github_token(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = read(name)
                for needle in ("GITHUB_TOKEN", "github.token"):
                    self.assertNotIn(
                        needle, text,
                        f"{name} references {needle}; the reactor build needs no credentials",
                    )

    def test_actions_are_pinned_to_the_approved_shas(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = read(name)
                self.assertIn(CHECKOUT_SHA, text, f"{name} does not use the pinned checkout")
                for line in text.splitlines():
                    stripped = line.strip()
                    if "actions/checkout@" in stripped:
                        self.assertIn(CHECKOUT_SHA, stripped, f"{name}: {stripped}")
                    if "actions/setup-java@" in stripped:
                        self.assertIn(SETUP_JAVA_SHA, stripped, f"{name}: {stripped}")

    def test_reactor_selections_match_the_approved_commands(self):
        for name, selections in REQUIRED_SELECTIONS.items():
            text = " ".join(normalise_run_blocks(read(name)).split())
            for selection in selections:
                with self.subTest(workflow=name, selection=selection):
                    self.assertIn(
                        f"{HELPER_REF} {selection}", text,
                        f"{name} is missing the approved selection: {selection}",
                    )


class TestTriggerCoverage(unittest.TestCase):
    def test_full_reactor_runs_on_pull_requests_and_pushes(self):
        text = read(FULL_REACTOR_WORKFLOW)
        self.assertRegex(text, r"(?m)^\s{2}pull_request:", "full reactor must run on PRs")
        self.assertRegex(text, r"(?m)^\s{2}push:", "full reactor must run on pushes")

    def test_full_reactor_is_not_path_filtered(self):
        self.assertNotIn(
            "paths:", read(FULL_REACTOR_WORKFLOW),
            "the full reactor is the catch-all; it must not be path-filtered",
        )

    def test_shared_build_inputs_cannot_evade_service_workflows(self):
        for name in ACTIVE_MAVEN_WORKFLOWS:
            if name == FULL_REACTOR_WORKFLOW:
                continue
            text = read(name)
            if "paths:" not in text:
                continue
            for filter_block in re.findall(r"paths:\s*\n((?:\s+-\s.*\n)+)", text):
                for shared in (*SHARED_BUILD_INPUTS, f".github/workflows/{name}"):
                    with self.subTest(workflow=name, shared=shared):
                        self.assertIn(
                            shared, filter_block,
                            f"{name} filters out changes to {shared}",
                        )

    def test_ci_regression_tests_run_in_the_full_reactor_workflow(self):
        text = normalise_run_blocks(read(FULL_REACTOR_WORKFLOW))
        self.assertIn("unittest", text, "the full reactor workflow must run the CI pin tests")
        self.assertIn("scripts/ci", text)


class TestRemovedAndPreserved(unittest.TestCase):
    def test_stale_workflows_are_absent(self):
        for relative in STALE_WORKFLOWS:
            with self.subTest(path=relative):
                self.assertFalse(
                    (REPO_ROOT / relative).exists(), f"{relative} should have been deleted"
                )

    def test_label_checker_is_preserved(self):
        label_checker = WORKFLOWS / "label-checker.yml"
        self.assertTrue(label_checker.is_file(), "the root label-checker.yml must survive")
        self.assertIn("pull-request-label-checker", label_checker.read_text())

    def test_no_nested_workflow_directories_remain(self):
        nested = [
            str(path.relative_to(REPO_ROOT))
            for path in REPO_ROOT.glob("*/**/.github/workflows")
            if path.is_dir() and any(path.iterdir())
        ]
        self.assertEqual(nested, [], f"non-empty nested workflow directories remain: {nested}")


class TestRootPom(unittest.TestCase):
    def test_root_pom_does_not_declare_the_retired_package_repository(self):
        text = (REPO_ROOT / "pom.xml").read_text()
        self.assertNotIn(
            RETIRED_PACKAGE_REPO, text,
            "the root POM still points at the retired pic-sure-common package repository",
        )

    def test_root_pom_keeps_its_distribution_management(self):
        text = (REPO_ROOT / "pom.xml").read_text()
        self.assertIn("<distributionManagement>", text)
        self.assertIn("https://maven.pkg.github.com/hms-dbmi/pic-sure", text)


class TestWorkflowSyntax(unittest.TestCase):
    def test_all_retained_workflows_are_valid_yaml(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML unavailable; syntax checked by actionlint in verification")
        for path in sorted(WORKFLOWS.glob("*.yml")):
            with self.subTest(workflow=path.name):
                document = yaml.safe_load(path.read_text())
                self.assertIsInstance(document, dict, f"{path.name} is not a YAML mapping")
                self.assertIn("jobs", document, f"{path.name} declares no jobs")


if __name__ == "__main__":
    unittest.main()
