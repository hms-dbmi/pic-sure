import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import datagen  # noqa: E402
import pheno  # noqa: E402


def read_bytes(path):
    with open(path, "rb") as f:
        return f.read()


def run_cli(script, *args):
    return subprocess.run([sys.executable, os.path.join(STACK, script)] + list(args), check=True, capture_output=True, text=True)


class DatagenTest(unittest.TestCase):
    def test_same_seed_same_rows(self):
        a = datagen.generate(30, 6, 5)
        b = datagen.generate(30, 6, 5)
        self.assertEqual(a, b)
        self.assertEqual(datagen.render(a), datagen.render(b))

    def test_other_seed_other_rows(self):
        self.assertNotEqual(datagen.generate(30, 6, 5), datagen.generate(30, 6, 6))

    def test_too_few_patients(self):
        with self.assertRaises(ValueError):
            datagen.generate(3, 6, 5)

    def test_features(self):
        rows = datagen.generate(80, 8, 20261018)
        self.assertGreater(len(rows), 200)
        consents = {r[3] for r in rows if r[1] == datagen.CONSENTS_PATH}
        self.assertEqual(consents, {"phs000000.c1", "phs000000.c2", "phs999999.c1", "phs123456.c1", "tutorial-biolincc_test"})
        by_patient = {}
        for r in rows:
            if r[1] == datagen.CONSENTS_PATH:
                by_patient.setdefault(r[0], set()).add(r[3])
        self.assertTrue(any(len(c) > 1 for c in by_patient.values()), "a patient in two consents")
        self.assertTrue(any(r[1].startswith("\\DCC Harmonized data set\\") for r in rows), "global concepts")
        self.assertTrue(any(r[1].startswith("\\unconsented_study\\") for r in rows), "a node without consents")
        stamps = {r[4] for r in rows}
        self.assertIn("0", stamps)
        self.assertIn("", stamps)
        self.assertTrue(any("T" in s or "-" in s for s in stamps), "ISO timestamps")
        self.assertTrue(any(s.isdigit() and len(s) == 13 for s in stamps), "epoch milliseconds")
        self.assertEqual(sum(1 for r in rows if r[2] == "2402L"), 1)
        self.assertEqual(sum(1 for r in rows if r[0] == "subject_id"), 1)
        self.assertTrue(any(r[2] != "" for r in rows) and any(r[3] != "" and r[1] != datagen.CONSENTS_PATH for r in rows))
        self.assertTrue(any(r[2] == "" and r[3].isdigit() for r in rows), "a number written in tval")
        # not sorted by concept: the loaders must cope
        paths = [r[1] for r in rows]
        self.assertNotEqual(paths, sorted(paths))

    def test_only_placeholder_accessions(self):
        text = datagen.render(datagen.generate(80, 8, 1))
        import re
        self.assertEqual(set(re.findall(r"phs\d{6}", text)), {"phs000000", "phs999999", "phs123456"})

    def test_the_two_bad_rows_are_the_only_rejects(self):
        routed = pheno.route_combined(datagen.generate(80, 8, 1))
        self.assertEqual(sorted(r for r, _ in routed.rejected), ["BAD_NVAL", "EMBEDDED_HEADER"])

    def test_cli_is_deterministic(self):
        with tempfile.TemporaryDirectory() as d:
            a, b = os.path.join(d, "a.csv"), os.path.join(d, "b.csv")
            run_cli("gen-combined.py", a)
            run_cli("gen-combined.py", b)
            self.assertEqual(read_bytes(a), read_bytes(b))
            self.assertGreater(read_bytes(a).count(b"\n"), 200)

    def test_synth_cli(self):
        with tempfile.TemporaryDirectory() as d:
            a, b, c = (os.path.join(d, n) for n in ("a.csv", "b.csv", "c.csv"))
            run_cli("gen-synth.py", "--patients", "60", "--concepts", "5", "--seed", "3", a)
            run_cli("gen-synth.py", "--patients", "60", "--concepts", "5", "--seed", "3", b)
            run_cli("gen-synth.py", "--patients", "60", "--concepts", "5", "--seed", "4", c)
            read = read_bytes
            self.assertEqual(read(a), read(b))
            self.assertNotEqual(read(a), read(c))
            more = os.path.join(d, "more.csv")
            run_cli("gen-synth.py", "--patients", "120", "--concepts", "5", "--seed", "3", more)
            self.assertGreater(len(read(more)), len(read(a)))


if __name__ == "__main__":
    unittest.main()
