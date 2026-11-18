"""serve.py: what DATASET=dir accepts as a partition root and a key file, and how the open-mode root is built. No docker."""
import os
import sys
import tempfile
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import lane
import serve


def touch(path, text="x"):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


class DirTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.join(self.tmp.name, "partitions")
        for p in ("phs000000.c1", "phs999999.c1"):
            touch(os.path.join(self.root, p, "allObservationsStore.javabin"))
            touch(os.path.join(self.root, p, "columnMeta.javabin"), "full " + p)
        self.key = os.path.join(self.tmp.name, "encryption_key")
        touch(self.key, "not-a-real-key")

    def tearDown(self):
        self.tmp.cleanup()

    def test_a_partition_root_and_a_key_are_accepted(self):
        self.assertIsNone(serve.dir_problem(self.root, self.key))

    def test_a_key_file_next_to_the_partitions_is_not_a_partition(self):
        touch(os.path.join(self.root, "encryption_key"))
        self.assertIsNone(serve.dir_problem(self.root, self.key))
        self.assertEqual(serve.partitions_of(self.root), ["phs000000.c1", "phs999999.c1"])

    def test_the_parent_of_the_partitions_is_refused(self):
        problem = serve.dir_problem(self.tmp.name, self.key)
        self.assertIn("partitions", problem)
        self.assertIn("not a partition", problem)

    def test_open_and_static_next_to_the_partitions_are_refused(self):
        for extra in ("open", "static"):
            touch(os.path.join(self.root, extra, "metadata.json"))
            self.assertIn(extra, serve.dir_problem(self.root, self.key))
            os.remove(os.path.join(self.root, extra, "metadata.json"))
            os.rmdir(os.path.join(self.root, extra))

    def test_one_unpartitioned_store_is_refused(self):
        touch(os.path.join(self.tmp.name, "flat", "allObservationsStore.javabin"))
        self.assertIn("unpartitioned", serve.dir_problem(os.path.join(self.tmp.name, "flat"), self.key))

    def test_missing_empty_and_absent_inputs(self):
        self.assertIn("HPDS_DIR is empty", serve.dir_problem("", self.key))
        self.assertIn("KEY_FILE is empty", serve.dir_problem(self.root, ""))
        self.assertIn("not a directory", serve.dir_problem(os.path.join(self.tmp.name, "nope"), self.key))
        self.assertIn("not a file", serve.dir_problem(self.root, os.path.join(self.tmp.name, "nokey")))
        touch(os.path.join(self.tmp.name, "empty_key"), "")
        self.assertIn("is empty", serve.dir_problem(self.root, os.path.join(self.tmp.name, "empty_key")))

    def test_the_key_is_never_printed(self):
        import contextlib
        import io

        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(serve.main(["serve.py", "check-dir", self.root, self.key]), 0)
            self.assertEqual(serve.main(["serve.py", "check-dir", self.tmp.name, self.key]), 1)
        self.assertNotIn("not-a-real-key", out.getvalue() + err.getvalue())


class OpenRootTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = os.path.join(self.tmp.name, "tools", "local-stack")
        self.work = os.path.join(base, "work")
        os.makedirs(self.work)
        self.src = os.path.join(self.tmp.name, "src")
        self.over = os.path.join(self.tmp.name, "open")
        for p in ("A", "B"):
            touch(os.path.join(self.src, p, "allObservationsStore.javabin"), "store " + p)
            touch(os.path.join(self.src, p, "columnMeta.javabin"), "full " + p)
            touch(os.path.join(self.src, p, "columnMeta.csv"), "csv " + p)
        touch(os.path.join(self.over, "A", "columnMeta.javabin"), "open A")
        touch(os.path.join(self.over, "B", "columnMeta.javabin"), "open B")
        self.dst = os.path.join(self.work, "open-root")
        patch = mock.patch.multiple(lane, WORK=self.work, HERE=base)
        patch.start()
        self.addCleanup(patch.stop)

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, *parts):
        with open(os.path.join(self.dst, *parts)) as f:
            return f.read()

    def test_every_partition_gets_its_open_columnmeta(self):
        n, replaced = serve.build_open_root(self.src, self.over, self.dst)
        self.assertEqual((n, replaced), (2, 2))
        self.assertEqual(self.read("A", "columnMeta.javabin"), "open A")
        self.assertEqual(self.read("B", "columnMeta.javabin"), "open B")
        self.assertEqual(self.read("A", "allObservationsStore.javabin"), "store A")
        self.assertFalse(os.path.exists(os.path.join(self.dst, "A", "columnMeta.csv")))

    def test_the_source_is_not_modified(self):
        serve.build_open_root(self.src, self.over, self.dst)
        with open(os.path.join(self.src, "A", "columnMeta.javabin")) as f:
            self.assertEqual(f.read(), "full A")

    def test_an_override_for_an_unknown_partition_fails_closed(self):
        touch(os.path.join(self.over, "C", "columnMeta.javabin"), "open C")
        with self.assertRaises(SystemExit) as cm:
            serve.build_open_root(self.src, self.over, self.dst)
        self.assertIn("C", str(cm.exception))
        self.assertFalse(os.path.exists(self.dst))

    def test_a_partition_without_an_override_fails_closed(self):
        os.remove(os.path.join(self.over, "B", "columnMeta.javabin"))
        with self.assertRaises(SystemExit) as cm:
            serve.build_open_root(self.src, self.over, self.dst)
        self.assertIn("B", str(cm.exception))
        self.assertFalse(os.path.exists(self.dst))

    def test_a_store_that_cannot_be_linked_is_never_copied(self):
        with mock.patch("os.link", side_effect=OSError("cross-device")), self.assertRaises(SystemExit) as cm:
            serve.build_open_root(self.src, self.over, self.dst)
        self.assertIn("never copied", str(cm.exception))
        for root, _, files in os.walk(self.dst):
            self.assertNotIn("allObservationsStore.javabin", files, root)

    def test_an_empty_override_fails(self):
        touch(os.path.join(self.over, "B", "columnMeta.javabin"), "")
        with self.assertRaises(SystemExit):
            serve.build_open_root(self.src, self.over, self.dst)

    def test_nothing_is_written_outside_the_work_directory(self):
        with self.assertRaises(SystemExit):
            serve.build_open_root(self.src, self.over, os.path.join(self.tmp.name, "elsewhere"))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "elsewhere")))

    def test_a_rebuild_replaces_the_previous_root(self):
        serve.build_open_root(self.src, self.over, self.dst)
        touch(os.path.join(self.over, "A", "columnMeta.javabin"), "open A again")
        serve.build_open_root(self.src, self.over, self.dst)
        self.assertEqual(self.read("A", "columnMeta.javabin"), "open A again")


if __name__ == "__main__":
    unittest.main()
