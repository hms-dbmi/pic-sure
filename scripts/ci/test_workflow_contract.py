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
SHARED_BUILD_INPUTS = (
    "pom.xml",
    "platform/**",
    "libs/**",
    "scripts/ci/**",
    "code-formatting/**",
)

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
        "verify",
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


def normalise_run_blocks(text):
    """Collapse YAML line continuations so multi-line `run:` steps match."""
    return re.sub(r"\\\s*\n\s*", " ", text)


def trigger_blocks(text):
    """Yield (trigger_name, block_lines) for the pull_request: and push: keys
    nested directly under the top-level `on:` mapping.

    This is a minimal, indentation-aware scan -- not a full YAML parser --
    sufficient for this repo's flat `on: / <trigger>: / paths:` shape. It
    exists so path-filter checks survive either YAML sequence style below.
    """
    lines = text.splitlines()
    on_idx = None
    for i, line in enumerate(lines):
        if line.rstrip() == "on:":
            on_idx = i
            break
    if on_idx is None:
        return
    trigger_indent = None
    i = on_idx + 1
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
            continue
        indent = len(line) - len(line.lstrip())
        if trigger_indent is None:
            trigger_indent = indent
        if indent < trigger_indent:
            break
        if indent == trigger_indent:
            match = re.match(r"(pull_request|push):", line.strip())
            if match:
                block_lines = []
                j = i + 1
                while j < len(lines):
                    candidate = lines[j]
                    if not candidate.strip():
                        j += 1
                        continue
                    candidate_indent = len(candidate) - len(candidate.lstrip())
                    if candidate_indent <= trigger_indent:
                        break
                    block_lines.append(candidate)
                    j += 1
                yield match.group(1), block_lines
                i = j
                continue
        i += 1


def strip_inline_comment(value):
    """Return a YAML scalar with any trailing comment removed.

    A '#' opens a comment only when it follows whitespace, and never inside a
    quoted scalar -- so `'a#b/**'` and `- 'x/**'  # note` both yield the path.
    """
    value = value.strip()
    quoted = re.match(r"(['\"])(.*?)\1", value)
    if quoted:
        return quoted.group(2)
    return re.split(r"\s+#", " " + value)[0].strip()


def extract_paths(block_lines):
    """Return the `paths:` filter entries from a trigger block's lines.

    Handles both the YAML block-list form:
        paths:
          - 'a'
          - 'b'
    and the flow-sequence form:
        paths: ['a', 'b']
    Returns [] if the block has no `paths:` key at all.
    """
    text = "\n".join(block_lines)
    flow = re.search(r"paths:[ \t]*\[([^\]]*)\]", text)
    if flow:
        return [
            strip_inline_comment(item)
            for item in flow.group(1).split(",")
            if item.strip()
        ]
    block = re.search(r"paths:[ \t]*\n((?:[ \t]+-[ \t].*\n?)+)", text + "\n")
    if not block:
        return []
    items = []
    for line in block.group(1).splitlines():
        stripped = line.strip()
        if not stripped.startswith("-"):
            continue
        items.append(strip_inline_comment(stripped[1:]))
    return items


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
        # Positional, not whole-file count parity: a stray `cache: maven`
        # elsewhere in the file must not be able to balance out a setup-java
        # step that has no cache of its own. Each setup-java step's own
        # `with:` block (the mapping at its own indentation, immediately
        # following) must itself contain `cache: maven`.
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                lines = read(name).splitlines()
                setup_java_lines = [
                    i for i, line in enumerate(lines) if SETUP_JAVA_SHA in line
                ]
                self.assertTrue(setup_java_lines, f"{name} sets up no Java")
                for i in setup_java_lines:
                    uses_line = lines[i]
                    uses_indent = len(uses_line) - len(uses_line.lstrip())
                    with_block = []
                    j = i + 1
                    if j < len(lines):
                        nxt = lines[j]
                        nxt_indent = len(nxt) - len(nxt.lstrip())
                        if nxt.strip() == "with:" and nxt_indent == uses_indent:
                            j += 1
                            while j < len(lines):
                                candidate = lines[j]
                                if not candidate.strip():
                                    j += 1
                                    continue
                                candidate_indent = len(candidate) - len(candidate.lstrip())
                                if candidate_indent <= uses_indent:
                                    break
                                with_block.append(candidate)
                                j += 1
                    with self.subTest(workflow=name, step_line=i + 1):
                        self.assertTrue(
                            any(re.search(r"cache:\s*maven", entry) for entry in with_block),
                            f"{name}: setup-java step at line {i + 1} has no "
                            "cache: maven in its own with: block",
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


    def test_no_workflow_forces_snapshot_updates(self):
        # The reactor has no SNAPSHOT dependencies, so -U only defeats Maven's
        # caching of failed lookups.
        for name in ACTIVE_MAVEN_WORKFLOWS:
            with self.subTest(workflow=name):
                text = normalise_run_blocks(read(name))
                self.assertNotIn("--update-snapshots", text)
                self.assertNotRegex(text, r"(?m)^.*run:.*\s-U(\s|$)")


class TestPathFilterParsing(unittest.TestCase):
    """Self-tests for the path-filter extractor the trigger checks depend on."""

    def test_trailing_comments_are_not_part_of_the_path(self):
        block = ["    paths:", "      - 'code-formatting/**'  # shared spotless config"]
        self.assertEqual(extract_paths(block), ["code-formatting/**"])

    def test_a_hash_inside_a_quoted_entry_survives(self):
        block = ["    paths:", "      - 'services/odd#name/**'"]
        self.assertEqual(extract_paths(block), ["services/odd#name/**"])

    def test_flow_sequences_are_extracted(self):
        block = ["    paths: ['pom.xml', 'libs/**']"]
        self.assertEqual(extract_paths(block), ["pom.xml", "libs/**"])


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
        # Extracted as data (via trigger_blocks/extract_paths), not matched by
        # a form-specific text pattern, so this survives either YAML sequence
        # style. Each of the four service workflows is expected to be
        # path-filtered on both triggers; a workflow that yields zero path
        # entries for a trigger is a vacuous check and must fail loudly
        # rather than silently pass.
        for name in ACTIVE_MAVEN_WORKFLOWS:
            if name == FULL_REACTOR_WORKFLOW:
                continue
            text = read(name)
            triggers = dict(trigger_blocks(text))
            for trigger_name in ("pull_request", "push"):
                with self.subTest(workflow=name, trigger=trigger_name):
                    self.assertIn(
                        trigger_name, triggers,
                        f"{name} declares no {trigger_name} trigger",
                    )
                    paths = extract_paths(triggers[trigger_name])
                    self.assertTrue(
                        paths,
                        f"{name}: {trigger_name} trigger has no path filter "
                        "entries to check -- this would be a vacuous pass",
                    )
                    for shared in (*SHARED_BUILD_INPUTS, f".github/workflows/{name}"):
                        with self.subTest(workflow=name, trigger=trigger_name, shared=shared):
                            self.assertIn(
                                shared, paths,
                                f"{name} ({trigger_name}) filters out changes to {shared}",
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


class TestRetiredPackageRepository(unittest.TestCase):
    """No POM may resolve from the retired pic-sure-common package repository.

    Fork runners have no credentials for it, and every artifact it once served
    now comes from the reactor or from Central. Publishing *to* it stays legal:
    <distributionManagement> entries are deliberately untouched.
    """

    def test_no_pom_resolves_from_the_retired_package_repository(self):
        poms = pom_files()
        self.assertTrue(poms, "found no POMs to check -- this would be a vacuous pass")
        for pom in poms:
            with self.subTest(pom=str(pom.relative_to(REPO_ROOT))):
                for block in resolution_source_blocks(pom.read_text()):
                    self.assertNotIn(
                        RETIRED_PACKAGE_REPO, block,
                        f"{pom.relative_to(REPO_ROOT)} still declares the retired "
                        "pic-sure-common repository as a resolution source",
                    )

    def test_the_check_distinguishes_resolution_from_publication(self):
        # Guards the guard: a naive substring search over either POM below
        # would treat them identically. The extractor must see the URL in the
        # first and not in the second.
        resolves = f"""<project>
          <repositories>
            <repository><url>{RETIRED_PACKAGE_REPO}</url></repository>
          </repositories>
        </project>"""
        publishes = f"""<project>
          <distributionManagement>
            <repository><url>{RETIRED_PACKAGE_REPO}</url></repository>
          </distributionManagement>
        </project>"""
        self.assertIn(RETIRED_PACKAGE_REPO, "".join(resolution_source_blocks(resolves)))
        self.assertEqual(resolution_source_blocks(publishes), [])

    def test_publication_targets_are_left_intact(self):
        # The live counterpart of the synthetic case above: this POM keeps the
        # retired URL under <distributionManagement>, and must keep passing.
        text = (REPO_ROOT / "libs" / "pic-sure-commons" / "pom.xml").read_text()
        self.assertIn(RETIRED_PACKAGE_REPO, text)
        self.assertIn("<distributionManagement>", text)


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
