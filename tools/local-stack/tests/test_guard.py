import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import lane  # noqa: E402


def copy_stack(root):
    """A copy of the stack's Makefile and scripts at <root>/tools/local-stack; returns that directory."""
    dst = os.path.join(root, "tools", "local-stack")
    os.makedirs(dst)
    for f in ("Makefile", "lane.py", ".env.defaults"):
        shutil.copyfile(os.path.join(STACK, f), os.path.join(dst, f))
    return dst


class WorkGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = os.path.realpath(self.tmp.name)
        self.stack = copy_stack(self.root)
        self.victim = os.path.join(self.root, "victim")
        os.makedirs(self.victim)
        with open(os.path.join(self.victim, "precious"), "w") as f:
            f.write("keep")

    def tearDown(self):
        self.tmp.cleanup()

    def make(self, target, *args):
        return subprocess.run(["make", "-C", self.stack, "--no-print-directory", target] + list(args),
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, universal_newlines=True)

    def assert_untouched(self):
        self.assertTrue(os.path.exists(os.path.join(self.victim, "precious")))

    def test_function(self):
        work = os.path.join(self.stack, "work")
        self.assertIsNone(lane.work_dir_problem(work, self.stack))  # does not need to exist
        os.makedirs(work)
        self.assertIsNone(lane.work_dir_problem(work, self.stack))
        self.assertIn("does not end in", lane.work_dir_problem(self.victim, self.stack))
        # right ending, wrong checkout
        other = os.path.join(self.root, "other", "tools", "local-stack", "work")
        os.makedirs(other)
        self.assertIn("not the work directory", lane.work_dir_problem(other, self.stack))
        # a dot-dot path that resolves elsewhere
        self.assertIsNotNone(lane.work_dir_problem(os.path.join(work, "..", "..", "victim"), self.stack))
        os.rmdir(work)
        os.symlink(self.victim, work)
        self.assertIn("symlink", lane.work_dir_problem(work, self.stack))

    def test_make_clean_refuses_a_foreign_work(self):
        for target in ("clean", "clean-data"):
            r = self.make(target, "WORK=" + self.victim)
            self.assertNotEqual(r.returncode, 0, r.stdout)
            self.assertIn("refusing to touch", r.stdout)
            self.assert_untouched()

    def test_make_clean_refuses_a_work_that_ends_right_but_is_elsewhere(self):
        elsewhere = os.path.join(self.root, "tools", "local-stack-2", "tools", "local-stack", "work")
        os.makedirs(elsewhere)
        open(os.path.join(elsewhere, "precious"), "w").close()
        r = self.make("clean", "WORK=" + elsewhere)
        self.assertNotEqual(r.returncode, 0, r.stdout)
        self.assertTrue(os.path.exists(os.path.join(elsewhere, "precious")))

    def test_make_clean_refuses_a_symlinked_work(self):
        work = os.path.join(self.stack, "work")
        os.symlink(self.victim, work)
        for target in ("clean", "clean-data"):
            r = self.make(target)
            self.assertNotEqual(r.returncode, 0, r.stdout)
            self.assertIn("symlink", r.stdout)
            self.assert_untouched()
        self.assertTrue(os.path.islink(work))

    def test_make_clean_refuses_a_dotdot_path_into_a_victim(self):
        r = self.make("clean", "WORK=" + os.path.join(self.stack, "work", "..", "..", "..", "victim"))
        self.assertNotEqual(r.returncode, 0, r.stdout)
        self.assert_untouched()

    def test_python_rmtree_checks_the_same_condition(self):
        old = (lane.WORK, lane.HERE)
        lane.HERE = self.stack  # the copy is the stack
        try:
            lane.WORK = self.victim
            with self.assertRaises(SystemExit) as cm:
                lane.rmtree(os.path.join(self.victim, "anything"))
            self.assertIn("refusing to delete", str(cm.exception))
            self.assert_untouched()
            # a work dir that is right, a path that is not inside it
            lane.WORK = os.path.join(self.stack, "work")
            os.makedirs(lane.WORK)
            with self.assertRaises(SystemExit):
                lane.rmtree(self.victim)
            with self.assertRaises(SystemExit):
                lane.rmtree(lane.WORK)
            self.assert_untouched()
            # inside: removed; a symlink inside is unlinked, its target kept
            inner = os.path.join(lane.WORK, "d", "x")
            os.makedirs(inner)
            lane.rmtree(os.path.join(lane.WORK, "d"))
            self.assertFalse(os.path.exists(os.path.join(lane.WORK, "d")))
            os.symlink(self.victim, os.path.join(lane.WORK, "link"))
            lane.rmtree(os.path.join(lane.WORK, "link"))
            self.assertFalse(os.path.lexists(os.path.join(lane.WORK, "link")))
            self.assert_untouched()
            # a parent that is a symlink out of work
            os.symlink(self.victim, os.path.join(lane.WORK, "out"))
            with self.assertRaises(SystemExit):
                lane.rmtree(os.path.join(lane.WORK, "out", "precious"))
            self.assert_untouched()
        finally:
            lane.WORK, lane.HERE = old

    def test_lane_prepare_refuses_a_symlinked_work(self):
        old = (lane.WORK, lane.HERE)
        lane.HERE = self.stack
        try:
            lane.WORK = os.path.join(self.stack, "work")
            os.symlink(self.victim, lane.WORK)
            with self.assertRaises(SystemExit):
                lane.prepare("mini")
            self.assert_untouched()
        finally:
            lane.WORK, lane.HERE = old


if __name__ == "__main__":
    unittest.main()
