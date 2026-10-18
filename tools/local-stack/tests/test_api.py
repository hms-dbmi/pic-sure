"""compare.py --via=api: the client, the diff against the HPDS's own answers, and the report. No docker: a fake query-service."""
import contextlib
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import compare  # noqa: E402
import pheno  # noqa: E402


def row(patient, path, nval="", tval=""):
    return [str(patient), path, nval, tval, ""]


class FakeApi(http.server.BaseHTTPRequestHandler):
    """Answers like the query-service: the count of the consents' first value ("P1" 3, "P2" 5), 403 without X-User-Id, 502 for "bad",
    an empty 200 for no consents, 7 for "drift" (an answer the HPDS would not give)."""
    seen = []

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeApi.seen.append((self.path, dict(self.headers), body))
        consents = [c["value"] for c in body["query"].get("userConsents") or []]
        if not self.headers.get("X-User-Id"):
            status, text = 403, ""
        elif consents == ["bad"]:
            status, text = 502, "bad gateway"
        else:
            status, text = 200, {("P1",): "3", ("P2",): "5", ("drift",): "7"}.get(tuple(consents), "")
        self.send_response(status)
        self.end_headers()
        self.wfile.write(text.encode())

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def log_message(self, *a):
        pass


@contextlib.contextmanager
def fake_api():
    srv = http.server.HTTPServer(("127.0.0.1", 0), FakeApi)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield "http://127.0.0.1:%d" % srv.server_port
    finally:
        srv.shutdown()
        srv.server_close()


def q(consents, desc="REQUIRED \\a\\", scope=None):
    return compare.Q(scope or "partition:" + consents[0], consents, desc, compare.required("\\a\\"), 0, "\\a\\")


class ApiClientTest(unittest.TestCase):
    def test_path_identity_and_body(self):
        with fake_api() as base:
            api = compare.Api(base, "someone")
            self.assertEqual(api.health(), "UP")
            self.assertEqual(api.count(["P1"], compare.required("\\a\\")), (3, None))
            path, headers, body = FakeApi.seen[-1]
            self.assertEqual(path, "/hpds/auth/v3/query/sync")
            self.assertEqual(headers["X-User-Id"], "someone")
            self.assertEqual(body, {"query": {"userConsents": [{"value": "P1"}], "expectedResultType": "COUNT",
                                              "phenotypicClause": {"phenotypicFilterType": "REQUIRED", "conceptPath": "\\a\\"}}})
            # the same body goes to HPDS directly, on its own path and without the identity header
            direct = compare.Hpds(base)
            direct.count(["P1"], compare.required("\\a\\"))
            self.assertEqual(FakeApi.seen[-1][0], "/PIC-SURE/v3/query/sync")
            self.assertNotIn("X-User-Id", FakeApi.seen[-1][1])

    def test_errors_and_the_empty_answer(self):
        with fake_api() as base:
            n, err = compare.Api(base, "").count(["P1"], compare.required("\\a\\"))  # no identity: the service answers 403
            self.assertIsNone(n)
            self.assertIn("HTTP 403", err)
            n, err = compare.Api(base).count(["bad"], compare.required("\\a\\"))
            self.assertIn("HTTP 502", err)
            n, err = compare.Api(base).count(["unlisted"], compare.required("\\a\\"))  # an empty 200 is not a count
            self.assertIsNone(n)
            self.assertIn("not a count", err)


class ApiDiffTest(unittest.TestCase):
    battery = [q(["P1"]), q(["P2"], "REQUIRED \\b\\"), q(["unknown.c9"], "REQUIRED \\a\\", "unknown-consent")]

    def test_identical_answers_are_no_difference(self):
        res = [(3, None), (5, None), (0, None)]
        mms, stats = compare.api_diffs(self.battery, res, list(res), "picsureloader-split", "lane3")
        self.assertEqual(mms, [])
        self.assertEqual(stats, {"asked": 3, "answered": 3, "errors": 0, "differences": 0})

    def test_a_different_count_is_an_unexplained_mismatch(self):
        direct = [(3, None), (5, None), (0, None)]
        via = [(3, None), (6, None), (0, None)]
        mms, stats = compare.api_diffs(self.battery, direct, via, "picsureloader-split", "lane3")
        self.assertEqual(stats["differences"], 1)
        (m,) = mms
        self.assertEqual((m["category"], m["kind"], m["lanes"], m["partitions"]), ("api-vs-hpds", "api:diff", ["picsureloader-split"], ["P2"]))
        self.assertEqual((m["truth"], m["answers"]), (5, {"hpds": 5, "api": 6}))
        self.assertIsNone(compare.explain(m, {}))

    def test_an_error_where_the_hpds_answered_and_the_reverse(self):
        direct = [(3, None), (None, "HTTP 500 x"), (0, None)]
        via = [(None, "HTTP 502 bad gateway"), (4, None), (None, "not a count: ''")]
        mms, stats = compare.api_diffs(self.battery, direct, via, "csvloader-split", "lane2")
        self.assertEqual([m["kind"] for m in mms], ["api:error"] * 3)
        self.assertEqual(stats, {"asked": 3, "answered": 1, "errors": 2, "differences": 3})
        self.assertEqual(mms[1]["answers"], {"hpds": "HTTP 500 x", "api": 4})
        self.assertEqual(mms[2]["partitions"], [])  # the unknown-consent scope names no partition

    def test_both_failing_is_the_hpds_reports_finding_not_a_new_one(self):
        mms, stats = compare.api_diffs([q(["P1"])], [(None, "HTTP 500 x")], [(None, "HTTP 502 y")], "f", "lane3")
        self.assertEqual(mms, [])
        self.assertEqual(stats["differences"], 0)

    def test_no_rule_explains_it_even_for_the_csvloader_lane_and_a_known_concept(self):
        m = compare.api_diffs([q(["P1"])], [(3, None)], [(4, None)], "csvloader-split", "lane2")[0][0]
        evidence = {"fffd": {"P1": {"\\a\\": ["x"]}}, "empty_first": {"P1": ["\\a\\"]}, "nonfinite": {"P1": ["\\a\\"]},
                    "whitespace-nval": {"P1": ["\\a\\"]}}
        self.assertIsNone(compare.explain(m, evidence))


class RunApiTest(unittest.TestCase):
    def setUp(self):
        self.mms = []

    def mm(self, **kw):
        kw["id"] = "m%d" % (len(self.mms) + 1)
        self.mms.append(kw)
        return kw

    def run_api(self, store, base, answers, battery=None):
        return compare.run_api(self.mm, battery or [q(["P1"]), q(["drift"], "REQUIRED \\c\\")], answers, {}, store, base, "u")

    def test_a_store_served_without_a_difference(self):
        with fake_api() as base:
            sec = self.run_api("lane3", base, {"picsureloader-split": [(3, None), (7, None)]})
        self.assertEqual(self.mms, [])
        self.assertEqual((sec["store"], sec["flow"], sec["health"], sec["queries"]["differences"]), ("lane3", "picsureloader-split", "UP", 0))

    def test_a_difference_is_recorded(self):
        with fake_api() as base:
            sec = self.run_api("lane2", base, {"csvloader-split": [(3, None), (8, None)]})
        self.assertEqual(sec["queries"]["differences"], 1)
        self.assertEqual([m["category"] for m in self.mms], ["api-vs-hpds"])

    def test_nothing_to_compare_is_a_mismatch(self):
        with fake_api() as base:
            sec = self.run_api("lane1", base, {"picsureloader-split": []})  # lane 1 was not queried directly
            self.assertIsNone(sec["queries"])
            self.assertEqual(self.mms[-1]["kind"], "api:setup")
            self.run_api("lane9", base, {})
            self.assertEqual(self.mms[-1]["kind"], "api:setup")
        self.assertEqual(len(self.mms), 2)

    def test_an_api_that_is_down_is_a_mismatch(self):
        sec = self.run_api("lane3", "http://127.0.0.1:1", {"picsureloader-split": [(3, None), (7, None)]})
        self.assertTrue(sec["health"].startswith("unreachable"))
        self.assertEqual([m["kind"] for m in self.mms], ["api:health"])
        self.assertIsNone(sec["queries"])


class ApiReportTest(unittest.TestCase):
    def write(self, d, differences=0, asked=4, health="UP"):
        lane = {"lane": "lane3", "status": "ok", "wall_seconds": 1.0, "store_bytes": 2048, "partitions_loaded": ["P"],
                "partitions_failed": [], "health": "UP", "queries": {"asked": 4, "answered": 4, "errors": 0, "mismatches": 0}}
        mism = [{"id": "m1", "category": "api-vs-hpds", "kind": "api:diff", "partitions": ["P"], "lanes": ["picsureloader-split"],
                 "concept": "\\c\\", "query": "REQUIRED", "truth": 1, "answers": {"hpds": 1, "api": 2}, "explained_by": None}
                for _ in range(differences)]
        rep = {"dataset": "combined", "lanes": {"picsureloader-split": lane}, "mismatches": mism, "known_differences": [],
               "api": {"store": "lane3", "flow": "picsureloader-split", "url": "http://localhost:8091", "path": compare.API_QUERY_PATH,
                       "health": health, "queries": {"asked": asked, "answered": asked, "errors": 0, "differences": differences} if asked else None},
               "requested_flows": ["picsureloader-split"], "not_run": [], "skipped": [],
               "truth": {"P": {"rows": 1, "patients": 1, "concepts": 1, "observations": 1, "skipped_short_rows": 0,
                               "rows_without_value": 0, "rolled_up_rows": 0}},
               "summary": {"mismatches": differences, "explained": 0, "unexplained": differences}}
        with open(os.path.join(d, "combined.json"), "w") as f:
            json.dump(rep, f)

    def report(self, d):
        compare.REPORT_DIR = d
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            rc = compare.report(["combined"])
        with open(os.path.join(d, "compare.md")) as f:
            md = f.read()
        with open(os.path.join(d, "compare.json")) as f:
            js = json.load(f)
        return rc, out.getvalue(), md, js

    def test_no_difference_passes_and_is_reported(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d)
            rc, out, md, js = self.report(d)
        self.assertEqual(rc, 0)
        self.assertEqual(out.strip().splitlines()[-1], "PASS")
        self.assertIn("api: combined via http://localhost:8091 (store lane3): 4 queries, 4 answered, 0 differences from the HPDS", out)
        self.assertIn("## Through the API", md)
        self.assertEqual(js["summary"]["api"][0]["differences"], 0)

    def test_a_difference_fails(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, differences=2)
            rc, out, md, js = self.report(d)
        self.assertEqual(rc, 1)
        self.assertIn("FAIL", out)
        self.assertIn("api-vs-hpds", md)

    def test_an_api_that_answered_nothing_fails(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, asked=0, health="unreachable: refused")
            rc, out, md, js = self.report(d)
        self.assertEqual(rc, 1)
        self.assertIn("no query went through the API", out)

    def test_a_run_without_the_api_has_no_api_section(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d)
            p = os.path.join(d, "combined.json")
            with open(p) as f:
                rep = json.load(f)
            rep["api"] = None
            with open(p, "w") as f:
                json.dump(rep, f)
            rc, out, md, js = self.report(d)
        self.assertEqual(rc, 0)
        self.assertNotIn("Through the API", md)
        self.assertEqual(js["summary"]["api"], [])


if __name__ == "__main__":
    unittest.main()
