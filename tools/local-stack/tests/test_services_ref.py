"""services_ref: which commit of the services the stack serves. A temporary git repository, no docker, no maven (the build is a fake)."""
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import compare
import qa
import services_ref as sr

GIT = ["git", "-c", "user.name=test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false"]


def git(repo, *args):
    r = subprocess.run([*GIT, "-C", repo, *args], check=True, capture_output=True, text=True)
    return r.stdout.strip()


def commit(repo, path, text, message):
    full = os.path.join(repo, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        f.write(text)
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", message)
    return git(repo, "rev-parse", "HEAD")


class FakeMaven:
    """Stands in for `mvn -pl a,b -am package`: writes the jars the listed modules would produce, and remembers each call."""

    def __init__(self):
        self.calls = []

    def __call__(self, cmd, cwd=None):
        modules = cmd[cmd.index("-pl") + 1].split(",")
        self.calls.append((cwd, modules))
        for spec in sr.SERVICES.values():
            if spec["module"] in modules:
                for pattern in spec["jars"].values():
                    path = os.path.join(cwd, pattern.replace("*", "3.0.0"))
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(path, "w") as f:
                        f.write("jar")
        return types.SimpleNamespace(returncode=0)


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        quiet = contextlib.redirect_stdout(io.StringIO())  # the build step announces itself
        quiet.__enter__()
        self.addCleanup(quiet.__exit__, None, None, None)
        root = os.path.realpath(self.tmp.name)
        self.repo = os.path.join(root, "repo")
        os.makedirs(self.repo)
        git(self.repo, "init", "-q", "-b", "main")
        self.first = commit(self.repo, "services/a/f.txt", "one", "one")
        git(self.repo, "branch", "deployed")
        self.second = commit(self.repo, "services/a/f.txt", "two", "two")
        self.stack = os.path.join(root, "checkout", "tools", "local-stack")
        self.work = os.path.join(self.stack, "work")
        os.makedirs(self.stack)

    def prepare(self, names, env, run=None, force=False):
        return sr.prepare(names, force=force, repo=self.repo, work=self.work, stack=self.stack, env=env, run=run or FakeMaven())


class ResolveTest(Base):
    def test_a_local_branch(self):
        self.assertEqual(sr.resolve("deployed", self.repo), self.first)
        self.assertEqual(sr.resolve("main", self.repo), self.second)

    def test_a_full_and_an_abbreviated_sha(self):
        self.assertEqual(sr.resolve(self.first, self.repo), self.first)
        self.assertEqual(sr.resolve(self.first[:10], self.repo), self.first)

    def test_origin_and_upstream_prefixes_are_tried(self):
        git(self.repo, "update-ref", "refs/remotes/origin/only-origin", self.first)
        git(self.repo, "update-ref", "refs/remotes/upstream/only-upstream", self.second)
        self.assertEqual(sr.resolve("only-origin", self.repo), self.first)
        self.assertEqual(sr.resolve("only-upstream", self.repo), self.second)
        self.assertEqual(sr.resolve("upstream/only-upstream", self.repo), self.second)

    def test_a_local_branch_wins_over_the_remote_ones(self):
        git(self.repo, "update-ref", "refs/remotes/origin/deployed", self.second)
        git(self.repo, "update-ref", "refs/remotes/upstream/deployed", self.second)
        self.assertEqual(sr.resolve("deployed", self.repo), self.first)

    def test_an_unknown_ref_fails_clearly(self):
        with self.assertRaises(sr.RefError) as cm:
            sr.resolve("no-such-ref", self.repo)
        msg = str(cm.exception)
        self.assertIn("'no-such-ref' does not resolve to a commit", msg)
        self.assertIn("origin/no-such-ref", msg)
        self.assertIn("upstream/no-such-ref", msg)

    def test_an_empty_ref_or_an_option_is_refused(self):
        for bad in ("", "  ", "--all"):
            with self.assertRaises(sr.RefError, msg=bad):
                sr.resolve(bad, self.repo)

    def test_the_cli_exits_1_with_the_reason(self):
        r = subprocess.run([sys.executable, os.path.join(os.path.dirname(HERE), "services_ref.py"), "resolve", "no-such-ref-anywhere"],
                           check=False, capture_output=True, text=True)
        self.assertEqual(r.returncode, 1)
        self.assertIn("does not resolve", r.stderr)


class DeployedRefResolveTest(Base):
    """The build and the provenance label read the deployed ref the same way: upstream first."""

    def test_the_deployed_ref_is_resolved_upstream_first_by_the_build_and_the_label(self):
        git(self.repo, "branch", "-f", sr.DEPLOYED_REF, self.first)  # a local branch that is behind
        git(self.repo, "update-ref", "refs/remotes/upstream/" + sr.DEPLOYED_REF, self.second)
        git(self.repo, "update-ref", "refs/remotes/origin/" + sr.DEPLOYED_REF, self.first)
        self.assertEqual(sr.resolve(sr.DEPLOYED_REF, self.repo), self.second)
        got = self.prepare(["hpds"], {"SERVICES_REF": sr.DEPLOYED_REF})
        self.assertEqual(got["hpds"]["sha"], self.second)
        self.assertEqual(got["hpds"]["matched"], "upstream/" + sr.DEPLOYED_REF)
        # the label target is the same commit: a run built that way is not labelled NOT
        up = {"sha": self.second, "ref": sr.DEPLOYED_REF, "via": "upstream/" + sr.DEPLOYED_REF}
        p = sr.provenance(["hpds"], work=self.work, repo=self.repo, inspect=lambda n: up if n == "picsure-local-hpds-lane3" else None)
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)

    def test_every_other_ref_keeps_the_local_branch_first(self):
        git(self.repo, "branch", "-f", "topic", self.first)
        git(self.repo, "update-ref", "refs/remotes/upstream/topic", self.second)
        self.assertEqual(sr.resolve("topic", self.repo), self.first)
        self.assertEqual(self.prepare(["hpds"], {"SERVICES_REF": "topic"})["hpds"]["sha"], self.first)


class OverridesTest(unittest.TestCase):
    def test_the_default_is_the_deployed_ref(self):
        for name in sr.SERVICES:
            self.assertEqual(sr.ref_for(name, {}), "csvloader-partition-names")

    def test_services_ref_applies_to_every_service(self):
        for name in sr.SERVICES:
            self.assertEqual(sr.ref_for(name, {"SERVICES_REF": "feature"}), "feature")

    def test_a_per_service_override_wins_and_an_empty_one_does_not(self):
        env = {"SERVICES_REF": "base", "HPDS_REF": "h", "QUERY_REF": "q", "DICT_REF": "upstream/main", "LOADER_REF": ""}
        self.assertEqual(sr.ref_for("hpds", env), "h")
        self.assertEqual(sr.ref_for("query", env), "q")
        self.assertEqual(sr.ref_for("operations", env), "q")  # the operations-service is built with the query-service
        self.assertEqual(sr.ref_for("dict", env), "upstream/main")
        self.assertEqual(sr.ref_for("loader", env), "base")


class ExtractTest(Base):
    def test_the_archive_path_is_work_src_services_sha(self):
        self.assertEqual(sr.tree_dir(self.first, self.work), os.path.join(self.work, "src", "services-" + self.first))

    def test_the_commit_is_extracted_and_the_checkout_is_not_written(self):
        before = git(self.repo, "status", "--porcelain")
        dest = sr.extract(self.repo, self.first, self.work, self.stack)
        self.assertEqual(dest, sr.tree_dir(self.first, self.work))
        with open(os.path.join(dest, "services", "a", "f.txt")) as f:
            self.assertEqual(f.read(), "one")  # the commit, not the working tree (which is at "two")
        self.assertTrue(os.path.isfile(os.path.join(dest, sr.MARKER)))
        self.assertEqual(git(self.repo, "status", "--porcelain"), before)
        self.assertEqual(sorted(os.listdir(self.repo)), [".git", "services"])

    def test_an_extraction_is_reused(self):
        dest = sr.extract(self.repo, self.first, self.work, self.stack)
        with open(os.path.join(dest, "services", "a", "f.txt"), "w") as f:
            f.write("edited")
        sr.extract(self.repo, self.first, self.work, self.stack)
        with open(os.path.join(dest, "services", "a", "f.txt")) as f:
            self.assertEqual(f.read(), "edited")

    def test_an_unfinished_extraction_is_redone(self):
        dest = sr.tree_dir(self.first, self.work)
        os.makedirs(dest + ".partial")
        os.makedirs(dest)  # no marker: it was interrupted
        with open(os.path.join(dest, "junk"), "w") as f:
            f.write("x")
        sr.extract(self.repo, self.first, self.work, self.stack)
        self.assertFalse(os.path.exists(os.path.join(dest, "junk")))
        self.assertFalse(os.path.exists(dest + ".partial"))
        self.assertTrue(os.path.isfile(os.path.join(dest, "services", "a", "f.txt")))

    def test_a_work_directory_that_is_not_the_stacks_is_refused(self):
        with self.assertRaises(sr.RefError):
            sr.extract(self.repo, self.first, os.path.join(self.tmp.name, "elsewhere"), self.stack)


class PrepareTest(Base):
    def test_every_service_is_built_from_the_ref_and_recorded(self):
        mvn = FakeMaven()
        got = self.prepare(["hpds", "query", "operations", "dict"], {"SERVICES_REF": "deployed"}, mvn)
        src = sr.tree_dir(self.first, self.work)
        self.assertEqual(len(mvn.calls), 1)  # one maven call for one commit
        self.assertEqual(mvn.calls[0][0], src)
        self.assertEqual(sorted(mvn.calls[0][1]), sorted(sr.SERVICES[n]["module"] for n in ("hpds", "query", "operations", "dict")))
        for name in got:
            self.assertEqual(got[name]["sha"], self.first)
            self.assertTrue(got[name]["jar"].startswith(src), name)
            self.assertTrue(os.path.isfile(got[name]["jar"]))
        rec = sr.read_record(self.work)
        self.assertEqual(rec["services"]["dict"]["ref"], "deployed")
        self.assertEqual(rec["services"]["hpds"]["sha"], self.first)

    def test_the_cache_key_is_the_sha_so_a_second_run_does_not_build(self):
        mvn = FakeMaven()
        self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, mvn)
        self.prepare(["hpds"], {"SERVICES_REF": self.first}, mvn)  # the same commit under another name
        self.assertEqual(len(mvn.calls), 1)
        self.prepare(["hpds"], {"SERVICES_REF": "main"}, mvn)  # another commit: another tree, another build
        self.assertEqual(len(mvn.calls), 2)
        self.assertNotEqual(mvn.calls[0][0], mvn.calls[1][0])

    def test_force_builds_again(self):
        mvn = FakeMaven()
        self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, mvn)
        self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, mvn, force=True)
        self.assertEqual(len(mvn.calls), 2)

    def test_a_service_added_later_is_built_without_rebuilding_the_others(self):
        mvn = FakeMaven()
        self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, mvn)
        self.prepare(["hpds", "dict"], {"SERVICES_REF": "deployed"}, mvn)
        self.assertEqual(mvn.calls[1][1], [sr.SERVICES["dict"]["module"]])

    def test_per_service_overrides_build_each_commit_in_its_own_tree(self):
        mvn = FakeMaven()
        got = self.prepare(["hpds", "query", "dict"], {"SERVICES_REF": "deployed", "DICT_REF": "main"}, mvn)
        self.assertEqual((got["hpds"]["sha"], got["query"]["sha"], got["dict"]["sha"]), (self.first, self.first, self.second))
        self.assertEqual(len(mvn.calls), 2)
        by_tree = {cwd: modules for cwd, modules in mvn.calls}
        self.assertEqual(by_tree[sr.tree_dir(self.second, self.work)], [sr.SERVICES["dict"]["module"]])
        self.assertEqual(sorted(by_tree[sr.tree_dir(self.first, self.work)]), sorted([sr.SERVICES["hpds"]["module"], sr.SERVICES["query"]["module"]]))

    def test_the_dictionary_can_come_from_a_remote_ref(self):
        git(self.repo, "update-ref", "refs/remotes/upstream/main", self.second)
        git(self.repo, "branch", "-m", "main", "elsewhere")
        got = self.prepare(["hpds", "dict"], {"SERVICES_REF": "deployed", "DICT_REF": "upstream/main"})
        self.assertEqual(got["hpds"]["sha"], self.first)
        self.assertEqual(got["dict"]["sha"], self.second)

    def test_the_loader_follows_services_ref_unless_loader_ref_says_otherwise(self):
        got = self.prepare(["loader"], {"SERVICES_REF": "deployed"})
        self.assertEqual(got["loader"]["sha"], self.first)
        self.assertTrue(got["loader"]["csv"].endswith("CSVLoader-jar-with-dependencies.jar"))
        self.assertTrue(got["loader"]["rm"].endswith("RemoveConceptFromMetadata-jar-with-dependencies.jar"))
        got = self.prepare(["loader"], {"SERVICES_REF": "deployed", "LOADER_REF": "main"})
        self.assertEqual(got["loader"]["sha"], self.second)

    def test_a_bad_ref_builds_nothing_and_says_so(self):
        mvn = FakeMaven()
        with self.assertRaises(sr.RefError) as cm:
            self.prepare(["hpds"], {"SERVICES_REF": "no-such-ref"}, mvn)
        self.assertIn("no-such-ref", str(cm.exception))
        self.assertEqual(mvn.calls, [])
        self.assertFalse(os.path.exists(os.path.join(self.work, "src")))

    def test_a_build_that_leaves_no_jar_is_an_error(self):
        def lazy(cmd, cwd=None):
            return types.SimpleNamespace(returncode=0)

        with self.assertRaises(sr.RefError) as cm:
            self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, lazy)
        self.assertIn("jar of hpds is not there", str(cm.exception))

    def test_a_failed_build_is_an_error(self):
        def broken(cmd, cwd=None):
            return types.SimpleNamespace(returncode=1)

        with self.assertRaises(sr.RefError):
            self.prepare(["hpds"], {"SERVICES_REF": "deployed"}, broken)


def running(stores=None, **by_container):
    """A fake `docker inspect`: container name -> sha (a running container labelled with it) or None (not running). STORES: container name ->
    the local-stack.store label (default lane3: the stack's default store)."""
    def inspect(name):
        sha = by_container.get(name.replace("-", "_"))
        return {"sha": sha, "ref": "r", "via": "origin/r", "store": (stores or {}).get(name, "lane3")} if sha else None
    return inspect


class ProvenanceTest(Base):
    def setUp(self):
        super().setUp()
        git(self.repo, "branch", "-f", sr.DEPLOYED_REF, self.first)

    def prov(self, names=("hpds", "query", "dict"), **kw):
        return sr.provenance(list(names), work=self.work, repo=self.repo, **kw)

    def all_at(self, sha):
        return running(picsure_local_hpds_lane3=sha, picsure_local_query=sha, picsure_local_operations=sha, picsure_local_dict_api=sha)

    def test_the_line_comes_from_the_running_containers(self):
        p = self.prov(inspect=self.all_at(self.first))
        self.assertEqual(p["line"], "services: hpds=%s query=%s dict=%s" % ((self.first[:12],) * 3))
        self.assertEqual(p["services"]["hpds"]["containers"], {"picsure-local-hpds-lane3": self.first})

    def test_what_is_running_wins_over_the_record(self):
        self.prepare(["hpds", "query", "operations", "dict"], {"SERVICES_REF": "main"})  # the record says the second commit
        p = self.prov(inspect=self.all_at(self.first))
        self.assertEqual(p["line"], "services: hpds=%s query=%s dict=%s" % ((self.first[:12],) * 3))
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)
        self.assertEqual(len(p["mismatches"]), 4)  # lane3, query, operations, dict-api each disagree with the record
        self.assertIn("picsure-local-dict-api runs services %s, work/services.json records %s" % (self.first[:12], self.second[:12]), p["mismatches"])

    def test_a_record_that_agrees_is_not_a_mismatch(self):
        self.prepare(["hpds", "query", "operations", "dict"], {"SERVICES_REF": sr.DEPLOYED_REF})
        self.assertEqual(self.prov(inspect=self.all_at(self.first))["mismatches"], [])

    def test_replacing_or_stopping_a_container_changes_the_provenance(self):
        before = self.prov(inspect=self.all_at(self.first))
        self.assertEqual(before["tests"], sr.DEPLOYED_REF)
        swapped = self.prov(inspect=running(picsure_local_hpds_lane3=self.first, picsure_local_query=self.first, picsure_local_operations=self.first,
                                            picsure_local_dict_api=self.second))
        self.assertEqual(swapped["line"], "services: hpds=%s query=%s dict=%s" % (self.first[:12], self.first[:12], self.second[:12]))
        self.assertTrue(swapped["tests"].startswith("NOT " + sr.DEPLOYED_REF), swapped["tests"])
        stopped = self.prov(inspect=running(picsure_local_hpds_lane3=self.first, picsure_local_query=self.first, picsure_local_operations=self.first))
        self.assertEqual(stopped["line"], "services: hpds=%s query=%s dict=-" % (self.first[:12], self.first[:12]))
        self.assertTrue(stopped["tests"].startswith("unknown: dict (no running container)"), stopped["tests"])

    def test_the_hpds_containers_of_one_stack_must_agree(self):
        p = self.prov(("hpds",), inspect=running(picsure_local_hpds_lane1=self.first, picsure_local_hpds_lane2=self.first, picsure_local_hpds_open=self.second))
        self.assertEqual(p["line"], "services: hpds=%s query=- dict=-" % "/".join(sorted([self.first[:12], self.second[:12]])))
        self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
        self.assertIsNone(p["services"]["hpds"]["sha"])

    def test_a_container_without_the_label_is_unknown_not_a_claim(self):
        def unlabelled(name):
            return {"sha": "", "ref": "", "via": ""} if name == "picsure-local-hpds-lane3" else None

        p = self.prov(("hpds",), inspect=unlabelled)
        self.assertTrue(p["tests"].startswith("unknown: hpds (a container without the label)"), p["tests"])

    def test_nothing_running_is_unknown_not_a_claim(self):
        p = self.prov(("hpds",), inspect=running())
        self.assertEqual(p["line"], "services: hpds=- query=- dict=-")
        self.assertTrue(p["tests"].startswith("unknown"), p["tests"])

    def test_services_that_are_not_up_show_a_dash(self):
        p = self.prov(("hpds",), inspect=self.all_at(self.first))
        self.assertEqual(p["line"], "services: hpds=%s query=- dict=-" % self.first[:12])
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)

    def test_the_candidate_containers_can_be_narrowed(self):
        inspect = running(picsure_local_hpds_lane1=self.second, picsure_local_hpds_lane3=self.first)
        p = self.prov(("hpds",), inspect=inspect, containers={"hpds": ["picsure-local-hpds-lane3"]})
        self.assertEqual(p["line"], "services: hpds=%s query=- dict=-" % self.first[:12])

    def test_the_label_names_the_deployed_ref_only_when_the_shas_match(self):
        self.assertEqual(self.prov(inspect=self.all_at(self.first))["tests"], sr.DEPLOYED_REF)
        p = self.prov(inspect=running(picsure_local_hpds_lane3=self.first, picsure_local_query=self.first, picsure_local_operations=self.first,
                                      picsure_local_dict_api=self.second))
        self.assertTrue(p["tests"].startswith("NOT " + sr.DEPLOYED_REF), p["tests"])
        self.assertIn("dict=" + self.second[:12], p["tests"])
        self.assertIn("not " + self.first[:12], p["tests"])

    def test_without_the_deployed_ref_the_label_is_unknown(self):
        git(self.repo, "branch", "-D", sr.DEPLOYED_REF)
        self.assertTrue(self.prov(("hpds",), inspect=self.all_at(self.first))["tests"].startswith("unknown"))

    def test_the_label_target_is_upstream_first(self):
        # the local branch is at the first commit, upstream's copy of it is at the second: upstream is what is deployed
        git(self.repo, "update-ref", "refs/remotes/upstream/" + sr.DEPLOYED_REF, self.second)
        git(self.repo, "update-ref", "refs/remotes/origin/" + sr.DEPLOYED_REF, self.first)
        self.assertEqual(sr.resolve_ref(sr.DEPLOYED_REF, self.repo, ("upstream/", "origin/", "")), (self.second, "upstream/" + sr.DEPLOYED_REF))
        self.assertTrue(self.prov(("hpds",), inspect=self.all_at(self.first))["tests"].startswith("NOT "))
        self.assertEqual(self.prov(("hpds",), inspect=self.all_at(self.second))["tests"], sr.DEPLOYED_REF)

    def test_the_candidate_that_matched_is_recorded_for_every_ref(self):
        git(self.repo, "update-ref", "refs/remotes/upstream/only-upstream", self.second)
        got = self.prepare(["hpds", "query", "dict"], {"SERVICES_REF": "deployed", "DICT_REF": "only-upstream", "QUERY_REF": self.first})
        self.assertEqual(got["hpds"]["matched"], "deployed")
        self.assertEqual(got["dict"]["matched"], "upstream/only-upstream")
        self.assertEqual(got["query"]["matched"], self.first)
        self.assertEqual(sr.read_record(self.work)["services"]["dict"]["matched"], "upstream/only-upstream")

    def write_dirt(self):
        os.makedirs(os.path.join(self.repo, "tools", "local-stack"), exist_ok=True)
        with open(os.path.join(self.repo, "tools", "local-stack", "new.txt"), "w") as f:
            f.write("x")

    def test_the_tooling_commit_is_taken_when_the_report_is_made(self):
        inspect = self.all_at(self.first)
        self.assertEqual(self.prov(("hpds",), inspect=inspect)["tooling"], self.second)
        self.write_dirt()
        self.assertEqual(self.prov(("hpds",), inspect=inspect)["tooling"], self.second + "-dirty")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "tooling")
        self.assertEqual(self.prov(("hpds",), inspect=inspect)["tooling"], git(self.repo, "rev-parse", "HEAD"))

    def test_dirty_tooling_qualifies_the_label_and_does_not_block_it(self):
        inspect = self.all_at(self.first)
        self.assertEqual(self.prov(("hpds",), inspect=inspect)["tests"], sr.DEPLOYED_REF)
        self.write_dirt()
        self.assertEqual(self.prov(("hpds",), inspect=inspect)["tests"], "%s (tooling %s-dirty)" % (sr.DEPLOYED_REF, self.second[:12]))
        off = self.prov(("hpds",), inspect=self.all_at(self.second))["tests"]
        self.assertTrue(off.startswith("NOT "), off)

    def jar_of(self, sha, name="CSVLoader-jar-with-dependencies.jar"):
        return os.path.join(self.work, "src", "services-" + sha, "services", "pic-sure-hpds", "docker", "pic-sure-hpds-etl", name)

    def test_a_loader_whose_output_was_used_is_part_of_the_provenance(self):
        inspect = self.all_at(self.first)
        none = self.prov(inspect=inspect)
        self.assertNotIn("loader", none["line"])
        used = self.prov(inspect=inspect, loaders=[self.jar_of(self.first)])
        self.assertEqual(used["line"], "services: hpds=%s query=%s dict=%s loader=%s" % ((self.first[:12],) * 4))
        self.assertEqual(used["tests"], sr.DEPLOYED_REF)

    def test_a_loader_at_another_commit_takes_the_label_away_for_that_run(self):
        inspect = self.all_at(self.first)
        p = self.prov(inspect=inspect, loaders=[self.jar_of(self.second)])
        self.assertTrue(p["tests"].startswith("NOT " + sr.DEPLOYED_REF), p["tests"])
        self.assertIn("loader=" + self.second[:12], p["tests"])
        self.assertTrue(self.prov(inspect=inspect, loaders=["/some/checkout/CSVLoader-jar-with-dependencies.jar"])["tests"].startswith("NOT "))
        self.assertEqual(self.prov(inspect=inspect)["tests"], sr.DEPLOYED_REF)  # services alone still carry it

    def test_qa_reads_the_open_columnmeta_loader_only_when_the_open_hpds_used_it(self):
        marker = os.path.join(self.tmp.name, "open-columnmeta.jar")
        with open(marker, "w") as f:
            f.write(self.jar_of(self.second, "RemoveConceptFromMetadata-jar-with-dependencies.jar") + "\n")
        inspect = self.all_at(self.first)
        base = dict(api=None, dict=None, hpds_container="picsure-local-hpds-lane3", loader_jar_file=marker)
        with_open = qa.provenance(types.SimpleNamespace(open="http://localhost:1", **base), repo=self.repo, inspect=inspect)
        self.assertIn("loader=" + self.second[:12], with_open["line"])
        self.assertTrue(with_open["tests"].startswith("NOT "), with_open["tests"])
        without_open = qa.provenance(types.SimpleNamespace(open=None, **base), repo=self.repo, inspect=inspect)
        self.assertNotIn("loader", without_open["line"])

    def test_the_compare_report_reads_the_lane_two_loader(self):
        os.makedirs(os.path.join(self.work, "combined", "csvloader-split"))
        jar = self.jar_of(self.first)
        with open(os.path.join(self.work, "combined", "csvloader-split", "load.json"), "w") as f:
            json.dump({"csvloader_jar": jar}, f)
        old = compare.WORK
        compare.WORK = self.work
        try:
            self.assertEqual(compare.used_loaders([{"dataset": "combined", "lanes": {"csvloader-split": {}}}]), [jar])
            self.assertEqual(compare.used_loaders([{"dataset": "combined", "lanes": {"picsureloader-split": {}}}]), [])
        finally:
            compare.WORK = old

    def write_load(self, dataset, text):
        old, compare.WORK = compare.WORK, self.work  # qa and compare find a dataset's load.json under compare.WORK
        self.addCleanup(setattr, compare, "WORK", old)
        os.makedirs(os.path.join(self.work, dataset, "csvloader-split", "store"), exist_ok=True)
        with open(os.path.join(self.work, dataset, "csvloader-split", "load.json"), "w") as f:
            f.write(text)

    def used(self, reports):
        old = compare.WORK
        compare.WORK = self.work
        try:
            return compare.used_loaders(reports)
        finally:
            compare.WORK = old

    LANE2 = [{"dataset": "combined", "lanes": {"csvloader-split": {"status": "ok"}}}]

    def test_a_lane_two_that_ran_without_a_recorded_jar_is_an_unknown_loader(self):
        inspect = self.all_at(self.first)
        # no load.json, an unreadable one, and one that names no jar
        for text in (None, "{not json", "[]", "{}", '{"csvloader_jar": null}', '{"csvloader_jar": ""}'):
            if text is not None:
                self.write_load("combined", text)
            loaders = self.used(self.LANE2)
            self.assertEqual(loaders, [""], text)
            p = self.prov(inspect=inspect, loaders=loaders)
            self.assertIn("loader=checkout", p["line"], text)
            self.assertTrue(p["tests"].startswith("NOT " + sr.DEPLOYED_REF), p["tests"])
            self.assertIn("loader=checkout", p["tests"])
        # a lane that did not run used no loader
        for status in ("not run", "n/a"):
            self.assertEqual(self.used([{"dataset": "combined", "lanes": {"csvloader-split": {"status": status}}}]), [], status)

    def qa_args(self, store, open_=None, marker=None):
        return types.SimpleNamespace(api=None, dict=None, open=open_, hpds_container="picsure-local-hpds-lane2", loader_jar_file=marker, store=store)

    def test_qa_of_the_lane_two_store_counts_the_loader_that_wrote_it(self):
        inspect_lane2 = running(picsure_local_hpds_lane2=self.first)
        store = os.path.join(self.work, "combined", "csvloader-split", "store")
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.second)}))
        for open_ in (None, "http://localhost:1"):  # the open root is built from the served store, so it does not matter
            p = qa.provenance(self.qa_args(store, open_), repo=self.repo, inspect=inspect_lane2)
            self.assertIn("loader=" + self.second[:12], p["line"])
            self.assertTrue(p["tests"].startswith("NOT " + sr.DEPLOYED_REF), p["tests"])
            self.assertIn("loader=" + self.second[:12], p["tests"])
        # the jar is at the commit that is served: the label stays
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.first)}))
        p = qa.provenance(self.qa_args(store), repo=self.repo, inspect=inspect_lane2)
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)
        self.assertIn("loader=" + self.first[:12], p["line"])
        # a store of another lane names no loader
        other = os.path.join(self.work, "combined", "picsureloader-split", "store")
        args3 = self.qa_args(other)
        args3.hpds_container = "picsure-local-hpds-lane3"
        inspect_lane3 = running(picsure_local_hpds_lane3=self.first)
        self.assertEqual(qa.provenance(args3, repo=self.repo, inspect=inspect_lane3)["tests"], sr.DEPLOYED_REF)
        self.assertNotIn("loader", qa.provenance(args3, repo=self.repo, inspect=inspect_lane3)["line"])
        # a lane-2 store whose load.json is gone or names no jar is unknown
        for text in ("{}", "{broken"):
            self.write_load("combined", text)
            p = qa.provenance(self.qa_args(store), repo=self.repo, inspect=inspect_lane2)
            self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
            self.assertIn("loader=checkout", p["tests"])
        os.remove(os.path.join(self.work, "combined", "csvloader-split", "load.json"))
        self.assertTrue(qa.provenance(self.qa_args(store), repo=self.repo, inspect=inspect_lane2)["tests"].startswith("NOT "))

    def fakes(self, open_store, api_store, hpds="picsure_local_hpds_lane3"):
        """Containers of one sha, the open-mode HPDS and the query-service labelled with the store they serve."""
        sha = self.first
        by = {hpds: sha, "picsure_local_hpds_open": sha, "picsure_local_query": sha, "picsure_local_operations": sha, "picsure_local_hpds_lane2": sha}
        return running(stores={"picsure-local-hpds-open": open_store, "picsure-local-query": api_store}, **by)

    def qa_args3(self, **kw):
        base = dict(api=None, dict=None, open=None, hpds_container="picsure-local-hpds-lane3", loader_jar_file=None,
                    store=os.path.join(self.work, "combined", "picsureloader-split", "store"), dataset="combined")
        return types.SimpleNamespace(**dict(base, **kw))

    def test_an_open_hpds_that_serves_the_lane_two_store_counts_its_loader_whatever_store_qa_is_given(self):
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.second)}))
        p = qa.provenance(self.qa_args3(open="http://localhost:1"), repo=self.repo, inspect=self.fakes("lane2", "lane3"))
        self.assertIn("loader=" + self.second[:12], p["tests"])
        self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
        # qa itself is told to compare lane 3: that is a setup problem as well, never a clean run
        problems = qa.target_problems(self.qa_args3(open="http://localhost:1"), self.fakes("lane2", "lane3"), lambda c: [])
        self.assertEqual([k for k, _ in problems], ["open"])
        self.assertIn("serves lane2, this run asks lane3", problems[0][1])

    def test_a_query_service_in_front_of_lane_two_counts_its_loader_and_its_hpds(self):
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.second)}))
        p = qa.provenance(self.qa_args3(api="http://localhost:1"), repo=self.repo, inspect=self.fakes("lane3", "lane2"))
        self.assertIn("loader=" + self.second[:12], p["tests"])
        self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
        self.assertIn("picsure-local-hpds-lane2", p["services"]["hpds"]["containers"])  # the HPDS the API asks is part of what runs
        # even when the loader of that store is the served commit, the HPDS behind the API is checked (a lane-2 HPDS at another sha)
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.first)}))
        inspect = running(stores={"picsure-local-query": "lane2"}, picsure_local_hpds_lane3=self.first, picsure_local_query=self.first,
                          picsure_local_operations=self.first, picsure_local_hpds_lane2=self.second)
        p = qa.provenance(self.qa_args3(api="http://localhost:1"), repo=self.repo, inspect=inspect)
        self.assertIn("hpds=%s/%s" % tuple(sorted((self.first[:12], self.second[:12]))), p["line"])
        self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
        # the query-service is asked for lane 3 but reads lane 2: a setup problem, from the label and from its HPDS_AUTH_URL
        lane2_env = lambda c: ["HPDS_AUTH_URL=http://hpds-lane2:8080/PIC-SURE"]  # noqa: E731
        problems = qa.target_problems(self.qa_args3(api="http://localhost:1"), self.fakes("lane3", "lane2"), lane2_env)
        self.assertEqual([k for k, _ in problems], ["api"])
        # the label alone says lane3 but the container reads lane 2 (HPDS_AUTH_URL): reported too
        problems = qa.target_problems(self.qa_args3(api="http://localhost:1"), self.fakes("lane3", "lane3"), lane2_env)
        self.assertEqual([k for k, _ in problems], ["api"])
        self.assertIn("hpds-lane2", problems[0][1])

    def test_a_container_without_a_store_label_is_unknown(self):
        p = qa.provenance(self.qa_args3(open="http://localhost:1"), repo=self.repo, inspect=self.fakes("", "lane3"))
        self.assertTrue(p["tests"].startswith("unknown: picsure-local-hpds-open (no store label)"), p["tests"])
        self.assertEqual(qa.target_problems(self.qa_args3(open="http://localhost:1"), self.fakes("", "lane3"), lambda c: [])[0][0], "open")

    def test_matching_stores_change_nothing(self):
        lane3 = self.fakes("lane3", "lane3")
        env3 = lambda c: ["HPDS_AUTH_URL=http://hpds-lane3:8080/PIC-SURE"]  # noqa: E731
        args = self.qa_args3(open="http://localhost:1", api="http://localhost:2")
        self.assertEqual(qa.target_problems(args, lane3, env3), [])
        p = qa.provenance(args, repo=self.repo, inspect=lane3)
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)
        self.assertNotIn("loader", p["line"])
        # lane 2 asked and served everywhere: the loader of that store, once
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.first)}))
        both2 = self.fakes("lane2", "lane2", hpds="picsure_local_hpds_lane2")
        a2 = self.qa_args3(open="http://localhost:1", api="http://localhost:2", hpds_container="picsure-local-hpds-lane2",
                           store=os.path.join(self.work, "combined", "csvloader-split", "store"))
        self.assertEqual(qa.target_problems(a2, both2, lambda c: ["HPDS_AUTH_URL=http://hpds-lane2:8080/PIC-SURE"]), [])
        p = qa.provenance(a2, repo=self.repo, inspect=both2)
        self.assertEqual(p["tests"], sr.DEPLOYED_REF)
        self.assertEqual(p["line"], "services: hpds=%s query=%s dict=- loader=%s" % ((self.first[:12],) * 3))
        # qa without a running docker-compose stack (no container name) asks nothing of the targets
        self.assertEqual(qa.target_problems(self.qa_args3(open="http://localhost:1", hpds_container=None), lane3, env3), [])

    def test_the_lane_two_loader_and_the_open_columnmeta_loader_are_both_counted(self):
        store = os.path.join(self.work, "combined", "csvloader-split", "store")
        self.write_load("combined", json.dumps({"csvloader_jar": self.jar_of(self.first)}))
        marker = os.path.join(self.tmp.name, "open-columnmeta.jar")
        with open(marker, "w") as f:
            f.write(self.jar_of(self.second, "RemoveConceptFromMetadata-jar-with-dependencies.jar") + "\n")
        p = qa.provenance(self.qa_args(store, "http://localhost:1", marker), repo=self.repo, inspect=running(picsure_local_hpds_lane2=self.first))
        self.assertTrue(p["tests"].startswith("NOT "), p["tests"])
        self.assertEqual(len(p["loaders"]), 2)

    def test_qa_prints_and_records_the_provenance(self):
        args = types.SimpleNamespace(api="http://localhost:1", dict="http://localhost:2", open=None, hpds_container=None, loader_jar_file=None)
        inspect = running(picsure_local_hpds_lane3=self.first, picsure_local_query=self.first, picsure_local_operations=self.first,
                          picsure_local_dict_api=self.second)
        prov = qa.provenance(args, repo=self.repo, inspect=inspect)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            qa.print_provenance(prov)
        lines = out.getvalue().splitlines()
        self.assertEqual(lines[0], "services: hpds=%s query=%s dict=%s" % (self.first[:12], self.first[:12], self.second[:12]))
        self.assertEqual(lines[1], "tooling: " + self.second)
        self.assertTrue(lines[2].startswith("tests: NOT " + sr.DEPLOYED_REF), lines[2])
        json.dumps(prov)  # goes into qa.json

    def test_the_compare_report_names_the_services(self):
        p = self.prov(("hpds",), inspect=self.all_at(self.first))
        summary = {"pass": True, "unexplained": 0, "explained": 0, "queries_answered": 1, "queries_asked": 1}
        md = compare.render_md([], summary, p)
        self.assertIn("Services: `hpds=%s query=- dict=-`" % self.first[:12], md)
        self.assertIn("Tests: " + sr.DEPLOYED_REF, md)


if __name__ == "__main__":
    unittest.main()
