import contextlib
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import compare  # noqa: E402
import lane  # noqa: E402
import pheno  # noqa: E402


def row(patient, path, nval="", tval="", ts=""):
    return [str(patient), path, nval, tval, ts]


def meta(**kw):
    base = {"width": "8", "categorical": False, "values": [], "min": "1.0", "max": "9.0", "observations": 3, "patients": 3}
    base.update(kw)
    return base


class BatteryTest(unittest.TestCase):
    def setUp(self):
        self.truth = pheno.build_truth({
            "P1": [row(1, "\\n\\", "1"), row(2, "\\n\\", "9"), row(3, "\\n\\", "5"), row(1, "\\c\\", "", "a"), row(2, "\\c\\", "", "b")],
            "P2": [row(2, "\\n\\", "4"), row(7, "\\c\\", "", "a")],
        })

    def test_battery_and_truth(self):
        qs = compare.build_battery(self.truth, {}, ["P1", "P2"])
        by = {(q.scope, q.desc): q for q in qs}
        self.assertEqual(by[("partition:P1", "REQUIRED \\n\\")].truth, 3)
        self.assertEqual(by[("all", "REQUIRED \\n\\")].truth, 3)
        self.assertEqual(by[("all", "REQUIRED \\c\\")].truth, 3)
        self.assertEqual(by[("partition:P1", "FILTER \\c\\ = 'a'")].truth, 1)
        self.assertEqual(by[("all", "FILTER \\c\\ = 'a'")].truth, 2)
        self.assertEqual(by[("partition:P1", "FILTER \\n\\ [1.0, 9.0]")].truth, 3)
        self.assertEqual(by[("partition:P1", "FILTER \\n\\ [10.0, open]")].truth, 0)
        unknown = [q for q in qs if q.scope == "unknown-consent"]
        self.assertTrue(unknown)
        self.assertTrue(all(q.truth == 0 and q.consents == [compare.UNKNOWN_CONSENT] for q in unknown))

    def test_values_that_only_a_store_has_are_queried(self):
        lane_meta = {"csvloader-split": {"P1": {"\\c\\": meta(categorical=True, values=["a", "b", "extra"])}}}
        qs = compare.build_battery(self.truth, lane_meta, ["P1"])
        extra = [q for q in qs if q.desc == "FILTER \\c\\ = 'extra'"]
        self.assertEqual(len(extra), 1)
        self.assertEqual(extra[0].truth, 0)

    def test_concepts_that_only_a_store_has_are_queried(self):
        lane_meta = {"csvloader-split": {"P1": {"\\ghost\\": meta()}}}
        qs = compare.build_battery(self.truth, lane_meta, ["P1"])
        self.assertTrue(any(q.concept == "\\ghost\\" and q.truth == 0 for q in qs))


class MetaTest(unittest.TestCase):
    def setUp(self):
        self.pt = pheno.build_partition("P", [row(1, "\\n\\", "1"), row(2, "\\n\\", "9"), row(1, "\\c\\", "", "a"), row(2, "\\c\\", "", "b")])

    def good(self):
        return {"\\n\\": meta(min="1.0", max="9.0", observations=2, patients=2),
                "\\c\\": meta(categorical=True, values=["a", "b"], min="null", max="null", observations=2, patients=2)}

    def test_clean(self):
        self.assertEqual(compare.meta_vs_truth("l", "P", self.good(), self.pt), [])

    def test_differences_are_found(self):
        m = self.good()
        m["\\n\\"]["max"] = "10.0"
        m["\\c\\"]["values"] = ["a"]
        m["\\c\\"]["patients"] = 1
        del m["\\n\\"]
        m["\\x\\"] = meta()
        what = sorted((d[0], d[1]) for d in compare.meta_vs_truth("l", "P", m, self.pt))
        self.assertEqual(what, [("\\c\\", "category values"), ("\\c\\", "patientCount"), ("\\n\\", "concept missing from columnMeta"),
                                ("\\x\\", "concept not in the input")])

    def test_numeric_tolerance_and_text_form(self):
        m = self.good()
        m["\\n\\"]["min"] = "1"  # 1 and 1.0 are the same number
        self.assertEqual(compare.meta_vs_truth("l", "P", m, self.pt), [])

    def test_between_lanes(self):
        a, b = self.good(), self.good()
        self.assertEqual(compare.meta_between_lanes(a, b), [])
        b["\\c\\"]["width"] = "3"
        d = compare.meta_between_lanes(a, b)
        self.assertEqual([(x[0], x[1]) for x in d], [("\\c\\", ["width"])])


class ExplainTest(unittest.TestCase):
    ev = {"fffd": {"P": ["\\c\\"]}}

    def test_explained_only_for_the_csvloader_lane_and_the_concept(self):
        base = {"category": "query", "partitions": ["P"], "concept": "\\c\\", "lanes": ["csvloader-split"]}
        self.assertEqual(compare.explain(base, self.ev), "fffd-in-text")
        self.assertIsNone(compare.explain(dict(base, concept="\\other\\"), self.ev))
        self.assertIsNone(compare.explain(dict(base, lanes=["picsureloader-split"]), self.ev))
        self.assertIsNone(compare.explain(dict(base, lanes=["csvloader-split", "picsureloader-split"]), self.ev))
        self.assertIsNone(compare.explain(dict(base, partitions=["Q"]), self.ev))
        diff = {"category": "columnMeta-lane-diff", "partitions": ["P"], "concept": "\\c\\", "lanes": ["csvloader-split", "picsureloader-split"]}
        self.assertEqual(compare.explain(diff, self.ev), "fffd-in-text")


class ExplainEmptyRowTest(unittest.TestCase):
    def test_empty_row_concepts_are_found_and_explain_csvloader_only(self):
        pt = pheno.build_partition("P", [row(1, "\\n\\", "5"), row(2, "\\n\\"), row(3, "\\c\\", "", "a"), row(4, "\\c\\"), row(5, "\\e\\")])
        self.assertEqual(pt.empty_row_concepts(), ["\\e\\", "\\n\\"])  # numeric with an empty row, and a concept with only empty rows
        ev = {"empty_first": {"P": pt.empty_row_concepts()}}
        mm = {"category": "query", "partitions": ["P"], "concept": "\\n\\", "lanes": ["csvloader-split"]}
        self.assertEqual(compare.explain(mm, ev), "csvloader-empty-row-first")
        self.assertIsNone(compare.explain(dict(mm, concept="\\c\\"), ev))
        self.assertIsNone(compare.explain(dict(mm, lanes=["picsureloader-split"]), ev))


class Handler(http.server.BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Handler.seen.append((self.path, body))
        consents = [c["value"] for c in body["query"]["userConsents"]]
        status, text = (200, "3") if consents == ["P1"] else (500, "boom") if consents == ["bad"] else (200, "")
        self.send_response(status)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(text.encode())

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def log_message(self, *a):
        pass


class HpdsClientTest(unittest.TestCase):
    def test_request_shape_and_errors(self):
        srv = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            h = compare.Hpds("http://127.0.0.1:%d" % srv.server_port)
            self.assertEqual(h.health(), "UP")
            self.assertEqual(h.count(["P1"], compare.required("\\a\\")), (3, None))
            path, body = Handler.seen[-1]
            self.assertEqual(path, "/PIC-SURE/v3/query/sync")
            self.assertEqual(body, {"query": {"userConsents": [{"value": "P1"}], "expectedResultType": "COUNT",
                                              "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"}}})
            n, err = h.count(["bad"], compare.required("\\a\\"))
            self.assertIsNone(n)
            self.assertIn("HTTP 500", err)
            n, err = h.count(["other"], compare.required("\\a\\"))
            self.assertIsNone(n)
            self.assertIn("not a count", err)
            h.count(["P1"], compare.range_filter("\\n\\", 1.5, None))
            self.assertEqual(Handler.seen[-1][1]["query"]["phenotypicClause"],
                             {"phenotypicFilterType": "FILTER", "conceptPath": "\\n\\", "min": 1.5})
            h.count(["P1"], compare.values_filter("\\c\\", ["b", "a"]))
            self.assertEqual(Handler.seen[-1][1]["query"]["phenotypicClause"]["values"], ["a", "b"])
        finally:
            srv.shutdown()
            srv.server_close()
        self.assertTrue(compare.Hpds("http://127.0.0.1:1").health().startswith("unreachable"))


class LoaderSupportTest(unittest.TestCase):
    def test_combined_input_detection(self):
        with tempfile.TemporaryDirectory() as d:
            old = lane.LOADER_JARS
            lane.LOADER_JARS = d
            try:
                self.assertFalse(lane.picsureloader_supports_combined_input())  # no jar
                path = os.path.join(d, "PICSURELoader-jar-with-dependencies.jar")
                cls = "edu/harvard/hms/dbmi/avillach/hpds/etl/phenotype/picsure/LoaderConfig.class"
                with zipfile.ZipFile(path, "w") as z:
                    z.writestr(cls, b"\xca\xfe\xba\xbe picsure.loader.maxRejectedRows")
                self.assertFalse(lane.picsureloader_supports_combined_input())
                with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
                    z.writestr(cls, b"\xca\xfe\xba\xbe picsure.loader.partitionBy picsure.loader.input")
                self.assertTrue(lane.picsureloader_supports_combined_input())
            finally:
                lane.LOADER_JARS = old


class ReportTest(unittest.TestCase):
    def test_report_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            compare.REPORT_DIR = d
            lane_ok = {"lane": "lane2", "status": "ok", "wall_seconds": 1.0, "store_bytes": 2048, "partitions_loaded": ["P"],
                       "partitions_failed": [], "health": "UP", "queries": {"asked": 4, "answered": 4, "errors": 0, "mismatches": 0}}

            def write(name, unexplained, answered=4):
                lane_ = dict(lane_ok, queries={"asked": 4, "answered": answered, "errors": 4 - answered, "mismatches": unexplained})
                mism = [{"id": "m%d" % i, "category": "query", "partitions": ["P"], "lanes": ["csvloader-split"], "concept": "\\c\\",
                         "query": "REQUIRED", "truth": 1, "answers": {"csvloader-split": 2}, "explained_by": None} for i in range(unexplained)]
                rep = {"dataset": name, "lanes": {"csvloader-split": lane_}, "mismatches": mism, "known_differences": [],
                       "truth": {"P": {"rows": 1, "patients": 1, "concepts": 1, "observations": 1, "skipped_short_rows": 0,
                                       "rows_without_value": 0, "rolled_up_rows": 0}},
                       "summary": {"mismatches": unexplained, "explained": 0, "unexplained": unexplained}}
                with open(os.path.join(d, name + ".json"), "w") as f:
                    json.dump(rep, f)

            quiet = contextlib.redirect_stdout(io.StringIO())
            with quiet, contextlib.redirect_stderr(io.StringIO()):
                write("a", 0)
                self.assertEqual(compare.report(["a"]), 0)
                write("b", 2)
                self.assertEqual(compare.report(["a", "b"]), 1)
                write("c", 0, answered=3)  # an unanswered query fails the run too
                self.assertEqual(compare.report(["c"]), 1)
                self.assertEqual(compare.report(["missing"]), 2)
            with open(os.path.join(d, "compare.md")) as f:
                self.assertIn("FAIL", f.read())


if __name__ == "__main__":
    unittest.main()
