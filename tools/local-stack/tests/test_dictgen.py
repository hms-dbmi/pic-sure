"""dictgen.py: the synthetic dictionary written for a dataset. No docker; the SQL itself is run by the stack's dict layer."""
import os
import re
import sys
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import datagen
import dictgen
import pheno

ALLOWED_ACCESSIONS = {"phs000000", "phs999999", "phs123456"}


def row(patient, path, nval="", tval=""):
    return [str(patient), path, nval, tval, ""]


def small_truth():
    return pheno.build_truth({
        "phs000000.c1": [row(1, "\\phs000000\\pht1\\phv1\\Var 1\\", "2.5"), row(1, "\\phs000000\\pht1\\phv2\\Var 2\\", "", "Yes"),
                         row(2, "\\DCC Harmonized data set\\age\\", "40"), row(2, "\\_consents\\", "", "phs000000.c1")],
        "phs000000.c2": [row(3, "\\phs000000\\pht1\\phv1\\Var 1\\", "9"), row(3, "\\phs000000\\pht1\\phv2\\Var 2\\", "", "No")],
        "phs999999.c1": [row(4, "\\phs999999\\pht1\\phv1\\Var 1\\", "1"), row(4, "\\unconsented_study\\x\\", "5")],
        "tutorial-biolincc_test": [row(5, "\\tutorial-biolincc_test\\pht1\\phv1\\Var 1\\", "1")],
    })


class DictgenTest(unittest.TestCase):
    def setUp(self):
        self.sql, self.summary = dictgen.data_sql(small_truth())

    def test_one_dataset_per_study_and_a_harmonized_dataset(self):
        self.assertEqual(self.summary["datasets"], ["phs000000", "phs999999", "tutorial-biolincc_test", "DCC Harmonized data set"])
        self.assertEqual(self.summary["studies"]["phs000000"], ["phs000000.c1", "phs000000.c2"])

    def test_one_consent_row_per_partition_and_the_bare_consent_has_an_empty_code(self):
        consents = re.findall(r"INSERT INTO dict.consent .*? SELECT dataset_id, '([^']*)', .*? WHERE ref = '([^']*)';", self.sql)
        self.assertEqual(consents, [("c1", "phs000000"), ("c2", "phs000000"), ("c1", "phs999999"), ("", "tutorial-biolincc_test")])

    def test_study_concepts_have_interior_nodes_and_values_meta(self):
        leaf = "\\phs000000\\pht1\\phv1\\Var 1\\"
        self.assertIn(f"'Continuous', '{leaf}'", self.sql)
        for interior in ("\\phs000000\\", "\\phs000000\\pht1\\", "\\phs000000\\pht1\\phv1\\"):
            self.assertIn(f"'Interior', '{interior}'", self.sql)
        self.assertIn("'[2.5, 9.0]'", self.sql)  # numeric values meta is [min, max] over the study's partitions
        self.assertIn("'[\"No\", \"Yes\"]'", self.sql)  # categories of the study's partitions together

    def test_only_global_nodes_of_the_harmonized_dataset_are_dictionary_entries(self):
        self.assertIn("\\DCC Harmonized data set\\age\\", self.sql)
        self.assertNotIn("unconsented_study", self.sql)
        self.assertNotIn("_consents", self.sql.replace("'\\_consents\\", ""))

    def test_the_harmonized_dataset_is_linked_to_every_study(self):
        self.assertEqual(self.sql.count("INSERT INTO dict.dataset_harmonization"), 3)

    def test_sql_quoting(self):
        self.assertEqual(dictgen.q("it's"), "'it''s'")
        self.assertEqual(dictgen.q("\\a\\"), "'\\a\\'")

    def test_the_generator_is_deterministic(self):
        self.assertEqual(self.sql, dictgen.data_sql(small_truth())[0])

    def test_consent_codes(self):
        self.assertEqual(dictgen.consent_code("phs000000.c1"), "c1")
        self.assertEqual(dictgen.consent_code("tutorial-biolincc_test"), "")

    def test_the_schema_is_the_service_schema_then_its_migrations_in_order(self):
        sql = dictgen.schema_sql()
        order = [m.group(1) for m in re.finditer(r"^-- (db/\S+)$", sql, re.MULTILINE)]
        self.assertEqual(order[0], "db/schema.sql")
        versions = [int(re.search(r"V(\d+)__", o).group(1)) for o in order[1:]]
        self.assertEqual(versions, sorted(versions))
        self.assertGreaterEqual(len(versions), 10)

    def test_only_placeholder_accessions_in_a_generated_dictionary(self):
        rows = datagen.generate(30, 6, 5)
        sql, _ = dictgen.data_sql(pheno.build_truth(pheno.route_combined(rows).partitions))
        self.assertLessEqual(set(re.findall(r"phs\d{6}", sql)), ALLOWED_ACCESSIONS)


if __name__ == "__main__":
    unittest.main()
