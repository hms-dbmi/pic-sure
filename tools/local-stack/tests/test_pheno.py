import os
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import pheno  # noqa: E402

C = pheno.CONSENTS_PATH


def row(patient, path, nval="", tval="", ts=""):
    return [str(patient), path, nval, tval, ts]


class JavaRulesTest(unittest.TestCase):
    def test_double(self):
        for ok in ("1", "1.5", " 2 ", "-3e2", ".5", "5.", "7d", "+4"):
            self.assertIsNotNone(pheno.java_double(ok), ok)
        for bad in ("", "abc", "1,5", "2402L", "1_000", "0x10"):
            self.assertIsNone(pheno.java_double(bad), bad)
        self.assertFalse(pheno.is_finite(pheno.java_double("NaN")))
        self.assertFalse(pheno.is_finite(pheno.java_double("Infinity")))

    def test_concept_path(self):
        self.assertEqual(pheno.concept_path("\\a\\ b \\c"), "\\a\\b\\c\\")
        self.assertEqual(pheno.concept_path("\\a\\b\\"), "\\a\\b\\")
        self.assertEqual(pheno.concept_path("µaµb"), "\\a\\b\\")
        self.assertEqual(pheno.concept_path("\\a\\b�\\"), "\\a\\b\\")


class ObservationTest(unittest.TestCase):
    def test_numeric_and_categorical(self):
        ob, note = pheno.observation(row(1, "\\a\\x\\", "5.5", "", "0"))
        self.assertIsNone(note)
        self.assertTrue(ob.numeric)
        self.assertEqual(ob.value, 5.5)
        ob, _ = pheno.observation(row(1, "\\a\\y\\", "", "Yes"))
        self.assertFalse(ob.numeric)
        self.assertEqual(ob.value, "Yes")

    def test_number_in_text_value_is_numeric(self):
        ob, _ = pheno.observation(row(1, "\\a\\x\\", "", "17"))
        self.assertTrue(ob.numeric)
        self.assertEqual(ob.value, 17.0)

    def test_numeric_wins_over_text(self):
        ob, _ = pheno.observation(row(1, "\\a\\x\\", "3", "words"))
        self.assertTrue(ob.numeric)

    def test_rollup(self):
        ob, _ = pheno.observation(row(1, "\\a\\b\\Yes\\", "", "Yes"))
        self.assertEqual(ob.concept, "\\a\\b\\")
        self.assertTrue(ob.rolled_up)
        ob, _ = pheno.observation(row(1, "\\a\\b\\Yes\\", "", "No"))
        self.assertEqual(ob.concept, "\\a\\b\\Yes\\")
        self.assertFalse(ob.rolled_up)

    def test_no_value_and_short_rows(self):
        self.assertEqual(pheno.observation(row(1, "\\a\\"))[1], "no_value")
        self.assertEqual(pheno.observation(["1", "\\a\\", "3"])[1], "short")

    def test_replacement_character_is_removed_from_text(self):
        ob, _ = pheno.observation(row(1, "\\a\\", "", "bad�byte"))
        self.assertEqual(ob.value, "badbyte")
        self.assertTrue(ob.fffd)

    def test_extra_columns_are_fine(self):
        ob, _ = pheno.observation(row(1, "\\a\\", "2") + ["extra"])
        self.assertTrue(ob.numeric)


class SplitterRulesTest(unittest.TestCase):
    def test_problems(self):
        ok = row(1, "\\a\\", "5", "", "1700000000000")
        self.assertIsNone(pheno.split_row_problem(ok))
        self.assertEqual(pheno.split_row_problem(["1", "\\a\\", "5"]), "BAD_FIELD_COUNT")
        self.assertEqual(pheno.split_row_problem(row("x", "\\a\\")), "BAD_PATIENT_NUM")
        self.assertEqual(pheno.split_row_problem(row(1, "")), "EMPTY_CONCEPT_PATH")
        self.assertEqual(pheno.split_row_problem(row(1, "\\a\\", "2402L")), "BAD_NVAL")
        self.assertEqual(pheno.split_row_problem(row(1, "\\a\\", "5", "", "yesterday")), "BAD_TIMESTAMP")
        self.assertEqual(pheno.split_row_problem(["subject_id", "concept_path", "nval", "tval", "timestamp"]), "EMBEDDED_HEADER")
        self.assertIsNone(pheno.split_row_problem(row(1, "\\a\\", "5", "", "2023-06-06T15:20:00Z")))
        self.assertIsNone(pheno.split_row_problem(row(1, "\\a\\", "5", "", "2023-06-06")))

    def test_routing(self):
        rows = [
            row(1, C, "", "phs000000.c1"), row(1, C, "", "phs000000.c2"),   # two consents of one study
            row(2, C, "", "phs999999.c1"),
            row(3, C, "", "tutorial-biolincc_test"),
            row(1, "\\phs000000\\t\\v\\", "5"),          # study row, both partitions of patient 1
            row(2, "\\phs000000\\t\\v\\", "6"),          # patient 2 has no consent for phs000000: dropped
            row(1, "\\DCC\\age\\", "40"),                # global: every partition of patient 1
            row(2, "\\DCC\\age\\", "50"),
            row(4, "\\DCC\\age\\", "60"),                # patient without consents: dropped
            row(3, "\\tutorial-biolincc_test\\v\\", "", "x"),
            ["subject_id", "concept_path", "nval", "tval", "timestamp"],
            row(1, "\\phs000000\\t\\v\\", "2402L"),
        ]
        r = pheno.route_combined(rows)
        self.assertEqual(sorted(r.partitions), ["phs000000.c1", "phs000000.c2", "phs999999.c1", "tutorial-biolincc_test"])
        # patient 1: two consent rows, the study row and the global row go to both of their partitions
        self.assertEqual(len(r.partitions["phs000000.c1"]), 4)
        self.assertEqual(len(r.partitions["phs000000.c2"]), 4)
        self.assertEqual(len(r.partitions["phs999999.c1"]), 2)  # consent row + age; the phs000000 row is dropped
        self.assertEqual(len(r.partitions["tutorial-biolincc_test"]), 2)
        self.assertEqual(r.dropped, {"study_no_consent": 1, "global_no_consent": 1})
        self.assertEqual(sorted(x for x, _ in r.rejected), ["BAD_NVAL", "EMBEDDED_HEADER"])
        self.assertEqual(r.consents[1], ["phs000000.c1", "phs000000.c2"])

    def test_leading_header_is_dropped_not_rejected(self):
        r = pheno.route_combined([["patient_num", "concept_path", "nval", "tval", "ts"], row(1, C, "", "phs000000.c1")])
        self.assertTrue(r.header_dropped)
        self.assertEqual(r.rejected, [])

    def test_a_node_that_is_not_a_study_is_global(self):
        rows = [row(1, C, "", "phs000000.c1"), row(1, "\\other_node\\v\\", "1")]
        r = pheno.route_combined(rows)
        self.assertEqual(len(r.partitions["phs000000.c1"]), 2)


class TruthTest(unittest.TestCase):
    def setUp(self):
        self.truth = pheno.build_truth({
            "P1": [row(1, "\\n\\", "5"), row(2, "\\n\\", "9"), row(2, "\\n\\", "11"), row(1, "\\c\\", "", "a"), row(3, "\\c\\", "", "b"),
                   row(4, "\\c\\")],
            "P2": [row(2, "\\n\\", "20"), row(5, "\\c\\", "", "a")],
        })

    def test_counts(self):
        t = self.truth
        self.assertEqual(pheno.count_required(t, ["P1"], "\\n\\"), 2)
        self.assertEqual(pheno.count_required(t, ["P1", "P2"], "\\n\\"), 2)  # patient 2 is in both: counted once
        self.assertEqual(pheno.count_required(t, ["P1"], "\\nothing\\"), 0)
        self.assertEqual(pheno.count_values(t, ["P1"], "\\c\\", ["a"]), 1)
        self.assertEqual(pheno.count_values(t, ["P1", "P2"], "\\c\\", ["a"]), 2)
        self.assertEqual(pheno.count_values(t, ["P1"], "\\c\\", ["a", "b"]), 2)

    def test_range_is_inclusive_and_any_value_counts(self):
        t = self.truth
        self.assertEqual(pheno.count_range(t, ["P1"], "\\n\\", 5, 5), 1)
        self.assertEqual(pheno.count_range(t, ["P1"], "\\n\\", 10, 12), 1)      # patient 2 has 11
        self.assertEqual(pheno.count_range(t, ["P1"], "\\n\\", 6, 8), 0)
        self.assertEqual(pheno.count_range(t, ["P1", "P2"], "\\n\\", 12, None), 1)

    def test_row_without_value_stores_nothing(self):
        self.assertEqual(self.truth["P1"].no_value_rows, 1)
        self.assertNotIn(4, self.truth["P1"].patients)

    def test_concept_type_is_set_by_the_first_observation(self):
        t = pheno.build_truth({"P": [row(1, "\\x\\", "5"), row(2, "\\x\\", "", "text")]})
        self.assertEqual(len(t["P"].concepts["\\x\\"].obs), 1)
        self.assertEqual(t["P"].mixed_type_dropped, 1)


class MiniFixtureTest(unittest.TestCase):
    """The truth engine against expected/*.tsv, which gen-fixtures.sh computed without a loader."""

    def test_observation_counts_match_the_expected_files(self):
        base = os.path.join(STACK, "fixtures", "mini")
        truth = pheno.truth_from_split_dir(os.path.join(base, "split"))
        self.assertEqual(len(truth), 5)
        for part, pt in truth.items():
            expected = {}
            with open(os.path.join(base, "expected", part + ".tsv"), encoding="utf-8") as f:
                for line in f:
                    concept, n = line.rstrip("\n").split("\t")
                    expected[concept] = int(n)
            self.assertEqual({c: len(cc.obs) for c, cc in pt.concepts.items()}, expected, part)

    def test_the_malformed_partition(self):
        truth = pheno.truth_from_split_dir(os.path.join(STACK, "fixtures", "mini", "split"))
        pt = truth["phs000000.c2"]
        self.assertEqual(pt.short_rows, 1)
        self.assertEqual(pt.fffd_rows, 1)


if __name__ == "__main__":
    unittest.main()
