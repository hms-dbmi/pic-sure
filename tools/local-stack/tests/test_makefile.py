"""Makefile rules of the dir and open-mode wiring, run on a copy of the stack. No docker: only parse-time errors and a recipe that touches nothing."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from test_guard import copy_stack


class MakefileRulesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.stack = copy_stack(os.path.realpath(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def make(self, *args):
        return subprocess.run(["make", "-C", self.stack, "--no-print-directory", *args], check=False, capture_output=True, text=True)

    def test_real_data_modes_refuse_a_non_loopback_bind_address(self):
        for extra in (["DATASET=dir"], ["DICT_DUMP=/data/dictionary_db.tar"]):
            r = self.make("help", "BIND_ADDRESS=0.0.0.0", *extra)
            self.assertNotEqual(r.returncode, 0, extra)
            self.assertIn("BIND_ADDRESS must be 127.0.0.1", r.stdout + r.stderr)
            self.assertEqual(self.make("help", "BIND_ADDRESS=127.0.0.1", *extra).returncode, 0, extra)

    def test_synthetic_datasets_may_bind_elsewhere(self):
        self.assertEqual(self.make("help", "BIND_ADDRESS=0.0.0.0", "DATASET=combined").returncode, 0)

    def test_a_lane_run_without_the_served_lane_skips_the_open_hpds(self):
        r = self.make("_hpds-open", "DATASET=mini", "STORE=lane3")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("open HPDS not started", r.stdout)

    def test_the_open_hpds_with_its_own_columnmeta_removes_the_marker_of_a_generated_one(self):
        r = self.make("-n", "_hpds-open-start", "DATASET=combined", "OPEN_COLUMNMETA_DIR=" + os.path.join(self.tmp.name, "open"))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn('[ -n "%s" ] && [ -n "%s" ]; then rm -f -- "%s"' % (
            os.path.join(self.tmp.name, "open"), os.path.join(self.stack, "work", "combined", "open-columnmeta"),
            os.path.join(self.stack, "work", "combined", "open-columnmeta.jar")), r.stdout)
        # nothing to remove for DATASET=dir (no generated columnMeta): the guard sees an empty path, not ".jar"
        r = self.make("-n", "_hpds-open-start", "DATASET=dir", "OPEN_COLUMNMETA_DIR=/x")
        self.assertIn('[ -n "/x" ] && [ -n "" ]', r.stdout)

    def test_the_containers_that_depend_on_store_say_which_store_they_serve(self):
        with open(os.path.join(os.path.dirname(HERE), "docker-compose.yml")) as f:
            text = f.read()
        for service in ("hpds-open", "api-query"):
            block = text[text.index("\n  %s:\n" % service):]
            block = block[1:block.index("\n\n", 1)] if "\n\n" in block[1:] else block
            self.assertIn('local-stack.store: "${STORE:-lane3}"', block, service)
        self.assertIn("local-stack.store", subprocess.run([sys.executable, "-c", "import services_ref; print(services_ref.FORMAT)"], cwd=os.path.dirname(HERE),
                                                           capture_output=True, text=True).stdout)

    def test_dataset_dir_without_a_partition_root_is_an_error(self):
        r = self.make("_hpds-open", "DATASET=dir", "HPDS_DIR=" + os.path.join(self.tmp.name, "none"), "KEY_FILE=/none")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("open HPDS not started", r.stdout)


class WiringBase(unittest.TestCase):
    """A copy of the stack with services_ref.py, to run make recipes on."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.stack = copy_stack(os.path.realpath(self.tmp.name))
        shutil.copyfile(os.path.join(os.path.dirname(HERE), "services_ref.py"), os.path.join(self.stack, "services_ref.py"))

    def make(self, *args):
        return subprocess.run(["make", "-C", self.stack, "--no-print-directory", *args], check=False, capture_output=True, text=True)


class ServicesRefWiringTest(WiringBase):
    """The make side of SERVICES_REF, read from a dry run."""

    def dry(self, *args):
        r = self.make("-n", *args)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout

    def test_the_default_ref_is_the_deployed_one(self):
        with open(os.path.join(self.stack, ".env.defaults")) as f:
            self.assertIn("SERVICES_REF=csvloader-partition-names", f.read().splitlines())

    def test_jars_builds_the_served_services_from_the_ref_and_the_loaders_from_the_checkout(self):
        out = self.dry("_jars", "LAYERS=hpds,api,dict", "DATASET=combined")
        self.assertIn('_prepare SVC="hpds query operations dict loader"', out)
        self.assertIn("_checkout-loaders", out)

    def test_dataset_dir_builds_no_loader(self):
        out = self.dry("_jars", "DATASET=dir")
        self.assertIn('_prepare SVC="hpds"', out)
        self.assertNotIn("loader", out)

    def test_rebuild_forces_the_build(self):
        self.assertIn("services_ref.py prepare --force hpds", self.dry("_prepare", "SVC=hpds", "REBUILD=1"))
        self.assertNotIn("--force", self.dry("_prepare", "SVC=hpds"))

    def test_the_images_are_labelled_with_the_commit_and_built_from_the_extracted_dockerfile(self):
        out = self.dry("_image-hpds")
        for label in ("sha", "ref", "via"):
            self.assertIn('--label "local-stack.services-%s=$%s"' % (label, label), out)  # quoted: a ref is not shell syntax
        self.assertIn("$src/services/pic-sure-hpds/docker/pic-sure-hpds/Dockerfile", out)

    def test_the_overrides_reach_the_helper(self):
        # the stack copy is not a git repository, so the ref cannot resolve; the message names the ref the helper was given
        for var, name, svc in (("SERVICES_REF", "base-ref", "hpds"), ("HPDS_REF", "hpds-ref", "hpds"), ("QUERY_REF", "query-ref", "operations"),
                               ("DICT_REF", "upstream/main", "dict"), ("LOADER_REF", "loader-ref", "loader")):
            r = self.make("_prepare", "SVC=" + svc, var + "=" + name)
            self.assertEqual(r.returncode, 2, var)
            self.assertIn("ref '%s' does not resolve" % name, r.stdout + r.stderr, var)

    def test_a_bad_ref_stops_the_build_with_the_reason(self):
        r = self.make("_prepare", "SVC=hpds", "SERVICES_REF=no-such-ref")
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("tried no-such-ref, origin/no-such-ref, upstream/no-such-ref", r.stdout + r.stderr)


class LaneTwoLoaderTest(WiringBase):
    """Lane 2 runs the CSVLoader of the served ref or nothing: no fallback to a jar next to the checkout."""

    def setUp(self):
        super().setUp()
        shutil.copyfile(os.path.join(os.path.dirname(HERE), "lane.py"), os.path.join(self.stack, "lane.py"))
        self.jars = os.path.join(self.tmp.name, "checkout-jars")
        os.makedirs(self.jars)
        for name in ("CSVLoader", "PICSURELoader"):
            with open(os.path.join(self.jars, name + "-jar-with-dependencies.jar"), "w") as f:
                f.write("not a jar")

    def test_the_recipe_stops_when_there_is_no_csvloader_of_the_ref(self):
        r = self.make("_lane-csvloader-split", "DATASET=mini", "LOADER_JARS_DIR=" + self.jars)
        self.assertNotEqual(r.returncode, 0)
        out = r.stdout + r.stderr
        self.assertIn("nothing recorded for loader csv", out)  # the error of `services_ref.py get` is visible, not discarded
        self.assertNotIn("python3 lane.py load", out)

    def test_the_recipe_stops_when_the_recorded_jar_is_gone(self):
        work = os.path.join(self.stack, "work")
        os.makedirs(work)
        with open(os.path.join(work, "services.json"), "w") as f:
            json.dump({"services": {"loader": {"ref": "r", "sha": "0" * 40, "csv": os.path.join(self.tmp.name, "gone.jar")}}}, f)
        r = self.make("_lane-csvloader-split", "DATASET=mini", "LOADER_JARS_DIR=" + self.jars)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("no CSVLoader of LOADER_REF", r.stdout + r.stderr)
        self.assertNotIn("python3 lane.py load", r.stdout + r.stderr)

    def test_lane_py_has_no_fallback_for_the_csvloader(self):
        env = {k: v for k, v in os.environ.items() if k != "CSVLOADER_JAR"}
        code = "import lane; lane.LOADER_JARS = %r; lane.jar('CSVLoader')" % self.jars
        r = subprocess.run([sys.executable, "-c", code], cwd=self.stack, env=env, check=False, capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("CSVLOADER_JAR is not set to an existing file", r.stderr)

    def test_lane_py_takes_the_csvloader_named_by_the_environment_and_still_finds_the_picsureloader(self):
        chosen = os.path.join(self.tmp.name, "chosen.jar")
        with open(chosen, "w") as f:
            f.write("x")
        env = dict(os.environ, CSVLOADER_JAR=chosen)
        code = "import lane; lane.LOADER_JARS = %r; print(lane.jar('CSVLoader')); print(lane.jar('PICSURELoader'))" % self.jars
        r = subprocess.run([sys.executable, "-c", code], cwd=self.stack, env=env, check=False, capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.split(), [chosen, os.path.join(self.jars, "PICSURELoader-jar-with-dependencies.jar")])

    def test_a_csvloader_that_is_not_the_one_in_the_environment_is_refused_even_if_it_is_next_to_the_checkout(self):
        env = dict(os.environ, CSVLOADER_JAR=os.path.join(self.tmp.name, "missing.jar"))
        code = "import lane; lane.LOADER_JARS = %r; lane.jar('CSVLoader')" % self.jars
        r = subprocess.run([sys.executable, "-c", code], cwd=self.stack, env=env, check=False, capture_output=True, text=True)
        self.assertNotEqual(r.returncode, 0)


class FlywayMigrationsTest(WiringBase):
    """The operations-service migrations are those of the served commit: nothing defaults to the checkout's."""

    def test_the_compose_file_has_no_default_that_points_into_the_checkout(self):
        with open(os.path.join(os.path.dirname(HERE), "docker-compose.yml")) as f:
            text = f.read()
        self.assertNotIn("../../services/pic-sure-operations-service", text)
        self.assertIn("${API_FLYWAY_SQL:-/dev/null}", text)

    def test_make_does_not_export_the_checkouts_migrations(self):
        r = subprocess.run(["make", "-C", self.stack, "--no-print-directory", "-pn", "help"], check=False, capture_output=True, text=True)
        self.assertNotRegex(r.stdout, r"(?m)^API_FLYWAY_SQL\b")

    def test_the_api_layer_takes_them_from_the_extracted_commit_and_stops_without_them(self):
        out = self.make("-n", "_up-api", "LAYERS=hpds,api").stdout
        self.assertIn('src="$(python3 services_ref.py get operations src)" || exit 1', out)
        self.assertIn('test -n "$src" && test -d "$sql" ||', out)
        self.assertIn('API_FLYWAY_SQL="$sql" docker compose', out)


if __name__ == "__main__":
    unittest.main()
