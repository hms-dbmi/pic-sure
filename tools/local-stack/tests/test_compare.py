import contextlib
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest
import subprocess
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


def mmatch(kind, lanes=("csvloader-split",), concept="\\c\\", partitions=("P",), **kw):
    category = {"meta-lane-diff": "columnMeta-lane-diff"}.get(kind, "query" if kind.startswith("query") else "columnMeta-vs-truth")
    return dict({"category": category, "kind": kind, "partitions": list(partitions), "concept": concept, "lanes": list(lanes)}, **kw)


class ExplainTest(unittest.TestCase):
    ev = {"fffd": {"P": {"\\c\\": ["badbyte"]}}}

    def test_fffd_explains_only_text_differences(self):
        self.assertEqual(compare.explain(mmatch("meta:category values"), self.ev), "fffd-in-text")
        self.assertEqual(compare.explain(mmatch("query:values", values=["badbyte"]), self.ev), "fffd-in-text")
        self.assertEqual(compare.explain(mmatch("query:values", values=["x", "bad\ufffdbyte"]), self.ev), "fffd-in-text")
        self.assertEqual(compare.explain(mmatch("meta-lane-diff", lanes=["csvloader-split", "picsureloader-split"], fields=["values", "width"]),
                                         self.ev), "fffd-in-text")

    def test_fffd_never_explains_counts_presence_ranges_or_errors(self):
        for kind in ("query:required", "query:range", "query:error", "meta:observationCount", "meta:patientCount", "meta:min", "meta:max",
                     "meta:categorical flag", "meta:concept missing from columnMeta"):
            self.assertIsNone(compare.explain(mmatch(kind, values=["badbyte"]), self.ev), kind)
        # a values filter on another value, or a lane diff that also touches a count
        self.assertIsNone(compare.explain(mmatch("query:values", values=["Yes"]), self.ev))
        self.assertIsNone(compare.explain(mmatch("meta-lane-diff", lanes=["csvloader-split", "picsureloader-split"], fields=["values", "observations"]),
                                          self.ev))

    def test_only_the_csvloader_lane_and_the_concept(self):
        base = mmatch("meta:category values")
        self.assertIsNone(compare.explain(dict(base, concept="\\other\\"), self.ev))
        self.assertIsNone(compare.explain(dict(base, lanes=["picsureloader-split"]), self.ev))
        self.assertIsNone(compare.explain(dict(base, lanes=["csvloader-split", "picsureloader-split"]), self.ev))
        self.assertIsNone(compare.explain(dict(base, partitions=["Q"]), self.ev))
        self.assertIsNone(compare.explain(dict(base, lanes=[]), self.ev))
        diff = mmatch("meta-lane-diff", lanes=["picsureloader-combined", "picsureloader-split"], fields=["values"])
        self.assertIsNone(compare.explain(diff, self.ev))


class ExplainEmptyRowTest(unittest.TestCase):
    def test_empty_row_concepts_are_found_and_explain_csvloader_only(self):
        pt = pheno.build_partition("P", [row(1, "\\n\\", "5"), row(2, "\\n\\"), row(3, "\\c\\", "", "a"), row(4, "\\c\\"), row(5, "\\e\\")])
        self.assertEqual(pt.empty_row_concepts(), ["\\e\\", "\\n\\"])  # numeric with an empty row, and a concept with only empty rows
        # the run confirms them in the CSVLoader's own columnMeta (categorical, 0 observations) before they count as evidence
        ev = {"empty_first": {"P": ["\\n\\"]}}
        for kind in ("query:required", "query:range", "meta:observationCount", "meta:categorical flag"):
            self.assertEqual(compare.explain(mmatch(kind, concept="\\n\\"), ev), "csvloader-empty-row-first", kind)
        self.assertIsNone(compare.explain(mmatch("query:required", concept="\\c\\"), ev))
        self.assertIsNone(compare.explain(mmatch("query:required", concept="\\n\\", lanes=["picsureloader-split"]), ev))
        self.assertIsNone(compare.explain(mmatch("query:error", concept="\\n\\"), ev))
        self.assertIsNone(compare.explain(mmatch("query:required", concept="\\n\\"), {"empty_first": {}}))  # not confirmed: no explanation


class CsvDifferenceTest(unittest.TestCase):
    def test_truth_follows_the_picsureloader(self):
        # whitespace nval: the text value is used (a number when it parses)
        self.assertEqual(pheno.observation(row(1, "\\a\\", "  ", "hello"))[0].value, "hello")
        ob = pheno.observation(row(1, "\\a\\", "  ", "9"))[0]
        self.assertTrue(ob.numeric)
        self.assertEqual(ob.value, 9.0)
        # NaN / Infinity in nval fall back to the text value, none: no observation
        self.assertEqual(pheno.observation(row(1, "\\a\\", "NaN", "11"))[0].value, 11.0)
        self.assertEqual(pheno.observation(row(1, "\\a\\", "Infinity", "xyz"))[0].value, "xyz")
        self.assertEqual(pheno.observation(row(1, "\\a\\", "NaN", "")), (None, "no_value"))
        # NaN in the text value is a categorical value
        ob = pheno.observation(row(1, "\\a\\", "", "NaN"))[0]
        self.assertFalse(ob.numeric)
        self.assertEqual(ob.value, "NaN")

    def test_rows_the_csvloader_reads_differently_are_flagged(self):
        self.assertEqual(pheno.csv_difference(row(1, "\\a\\", "  ", "hello")), (pheno.WS_NVAL, "\\a\\"))
        self.assertIsNone(pheno.csv_difference(row(1, "\\a\\", "  ", "")))  # both drop it
        self.assertIsNone(pheno.csv_difference(row(1, "\\a\\", "", "")))
        self.assertEqual(pheno.csv_difference(row(1, "\\a\\", "NaN", "")), (pheno.NONFINITE, "\\a\\"))
        self.assertEqual(pheno.csv_difference(row(1, "\\a\\", "-Infinity", "x")), (pheno.NONFINITE, "\\a\\"))
        self.assertEqual(pheno.csv_difference(row(1, "\\a\\", "", "NaN")), (pheno.NONFINITE, "\\a\\"))
        self.assertIsNone(pheno.csv_difference(row(1, "\\a\\", "5", "NaN")))  # a finite nval wins in both
        self.assertIsNone(pheno.csv_difference(row(1, "\\a\\", "", "abc")))
        pt = pheno.build_partition("P", [row(1, "\\a\\", "  ", "hello"), row(2, "\\a\\", "", "NaN"), row(3, "\\b\\", "NaN", "")])
        self.assertEqual(pt.csv_differs[pheno.WS_NVAL], {"\\a\\": 1})
        self.assertEqual(pt.csv_differs[pheno.NONFINITE], {"\\a\\": 1, "\\b\\": 1})

    def test_edge_fixture(self):
        truth = pheno.truth_from_split_dir(os.path.join(STACK, "fixtures", "edge", "split"))
        pt = truth["phs000000.c1"]
        self.assertEqual(sorted(pt.csv_differs[pheno.WS_NVAL]), ["\\phs000000\\edge\\ws_num\\", "\\phs000000\\edge\\ws_text\\"])
        self.assertEqual(sorted(pt.csv_differs[pheno.NONFINITE]),
                         ["\\phs000000\\edge\\nan_nval\\", "\\phs000000\\edge\\nan_tval\\", "\\phs000000\\edge\\nan_tval_text\\",
                          "\\phs000000\\edge\\nan_with_text\\"])
        self.assertEqual(pt.concepts["\\phs000000\\edge\\ws_text\\"].values(), ["hello", "keep", "world"])
        self.assertEqual(sorted(v for _, v in pt.concepts["\\phs000000\\edge\\ws_num\\"].obs), [7.0, 8.0, 9.0])
        self.assertEqual(sorted(v for _, v in pt.concepts["\\phs000000\\edge\\nan_nval\\"].obs), [3.0, 11.0])

    def test_rules_explain_csvloader_data_differences_only(self):
        ev = {"whitespace-nval": {"P": ["\\c\\"]}, "nonfinite": {"P": ["\\n\\"]}}
        self.assertEqual(compare.explain(mmatch("query:required"), ev), "csvloader-whitespace-nval")
        self.assertEqual(compare.explain(mmatch("meta:observationCount", concept="\\n\\"), ev), "csvloader-nonfinite-numbers")
        self.assertIsNone(compare.explain(mmatch("query:required", lanes=["picsureloader-split"]), ev))
        self.assertIsNone(compare.explain(mmatch("query:error"), ev))
        self.assertIsNone(compare.explain(mmatch("query:required", concept="\\other\\"), ev))


    RULE_EVIDENCE = (("csvloader-empty-row-first", "empty_first"), ("csvloader-whitespace-nval", "whitespace-nval"),
                     ("csvloader-nonfinite-numbers", "nonfinite"))
    OTHER = "picsureloader-split"

    def explained_by(self, key, mm):
        return compare.explain(mm, {key: {"P": ["\\c\\"]}})

    def test_no_csvloader_rule_explains_a_concept_the_csvloader_lacks(self):
        # the CSVLoader creates the cube before it looks at the value: it cannot drop a concept, so a concept missing from its
        # columnMeta is a real defect whatever the evidence says
        for rule, key in self.RULE_EVIDENCE:
            self.assertIsNone(self.explained_by(key, mmatch("meta:concept missing from columnMeta")), rule)
            # a presence difference where the CSVLoader lacks the concept (it is the "absent" side)
            lacks = mmatch("meta-lane-diff", lanes=["csvloader-split", self.OTHER], fields=["presence"],
                           answers={"csvloader-split": "absent", self.OTHER: "present"})
            self.assertIsNone(self.explained_by(key, lacks), rule)
            # and the same diff with the lanes the other way round in the mismatch
            lacks = mmatch("meta-lane-diff", lanes=[self.OTHER, "csvloader-split"], fields=["presence"],
                           answers={self.OTHER: "present", "csvloader-split": "absent"})
            self.assertIsNone(self.explained_by(key, lacks), rule)

    def test_presence_difference_is_explained_only_when_the_csvloader_has_the_concept(self):
        has = mmatch("meta-lane-diff", lanes=["csvloader-split", self.OTHER], fields=["presence"],
                     answers={"csvloader-split": "present", self.OTHER: "absent"})
        self.assertEqual(self.explained_by("empty_first", has), "csvloader-empty-row-first")
        self.assertEqual(self.explained_by("nonfinite", has), "csvloader-nonfinite-numbers")
        # whitespace-nval never makes the CSVLoader hold a concept the PICSURELoader lacks
        self.assertIsNone(self.explained_by("whitespace-nval", has))
        # a presence diff without the answers is not explained either
        self.assertIsNone(self.explained_by("empty_first", dict(has, answers=None)))
        self.assertIsNone(self.explained_by("empty_first", {k: v for k, v in has.items() if k != "answers"}))

    def test_a_concept_not_in_the_input_is_explained_by_empty_first_and_nonfinite_only(self):
        mm = mmatch("meta:concept not in the input")
        self.assertEqual(self.explained_by("empty_first", mm), "csvloader-empty-row-first")
        self.assertEqual(self.explained_by("nonfinite", mm), "csvloader-nonfinite-numbers")
        self.assertIsNone(self.explained_by("whitespace-nval", mm))

    def test_each_rule_has_its_own_kinds(self):
        self.assertNotIn("meta:concept missing from columnMeta", compare.EMPTY_FIRST_KINDS | compare.WHITESPACE_NVAL_KINDS | compare.NONFINITE_KINDS)
        self.assertNotIn(compare.CONCEPT_NOT_IN_INPUT, compare.WHITESPACE_NVAL_KINDS)
        self.assertIn(compare.CONCEPT_NOT_IN_INPUT, compare.EMPTY_FIRST_KINDS)
        self.assertIn(compare.CONCEPT_NOT_IN_INPUT, compare.NONFINITE_KINDS)

    def test_nonfinite_needs_the_csvloaders_own_columnmeta(self):
        numeric = meta(categorical=False, min="NaN", max="NaN")
        self.assertTrue(compare.csv_stored_nonfinite(numeric))
        self.assertTrue(compare.csv_stored_nonfinite(meta(categorical=False, min="1.0", max="Infinity")))
        self.assertTrue(compare.csv_stored_nonfinite(meta(categorical=False)))  # numeric: the NaN went in as a number
        self.assertTrue(compare.csv_stored_nonfinite(meta(categorical=True, min="-Infinity", max="5")))
        # categorical, with the null min and max the CSVLoader writes for text: no NaN was stored
        self.assertFalse(compare.csv_stored_nonfinite(meta(categorical=True, min="null", max="null")))
        self.assertFalse(compare.csv_stored_nonfinite(meta(categorical=True, min="1.0", max="2.0")))
        self.assertFalse(compare.csv_stored_nonfinite(None))
        self.assertFalse(compare.csv_stored_nonfinite({}))


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
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.old = (lane.LOADER_JARS, lane.WORK, os.environ.get("LANE1"), subprocess.run)
        lane.LOADER_JARS = self.tmp.name
        lane.WORK = os.path.join(self.tmp.name, "work")

    def tearDown(self):
        lane.LOADER_JARS, lane.WORK, l1, run = self.old
        subprocess.run = run
        if l1 is None:
            os.environ.pop("LANE1", None)
        else:
            os.environ["LANE1"] = l1
        self.tmp.cleanup()

    def fake_java(self, rc, writes_stores):
        calls = []

        def run(cmd, **kw):
            calls.append(cmd)
            out = cmd[-1]
            if writes_stores:
                for part in ("phs000000.c1", "phs000000.c2"):
                    os.makedirs(os.path.join(out, part))
                    for f in lane.STORE_FILES:
                        open(os.path.join(out, part, f), "w").close()
            return subprocess.CompletedProcess(cmd, rc, b"")
        subprocess.run = run
        return calls

    def make_jar(self, with_option):
        path = os.path.join(self.tmp.name, "PICSURELoader-jar-with-dependencies.jar")
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("a/Other.class", b"\xca\xfe\xba\xbe nothing here")
            if with_option:
                z.writestr("a/LoaderConfig.class", b"\xca\xfe\xba\xbe picsure.loader.partitionBy")
        return path

    def test_probe(self):
        ok, why = lane.picsureloader_supports_combined_input()
        self.assertFalse(ok)
        self.assertIn("not found", why)
        self.assertEqual(lane.lane1_probe()[0], "skip")
        # static check: a jar without the option is skipped and the probe never runs
        self.make_jar(False)
        calls = self.fake_java(1, False)
        self.assertEqual(lane.lane1_probe(), ("skip", "PICSURELoader without combined input"))
        self.assertEqual(lane.picsureloader_supports_combined_input(), (False, "PICSURELoader without combined input"))
        self.assertEqual(calls, [])
        # not a zip at all: no option
        open(os.path.join(self.tmp.name, "PICSURELoader-jar-with-dependencies.jar"), "w").close()
        self.assertEqual(lane.lane1_probe()[0], "skip")
        # the option is there: the probe runs, with a real heap, and failing it is a failure, not a skip
        self.make_jar(True)
        calls = self.fake_java(1, False)
        state, why = lane.lane1_probe()
        self.assertEqual(state, "fail")
        self.assertIn("rc=1", why)
        self.assertTrue(any(a.startswith("-Dpicsure.loader.input=") for a in calls[0]))
        self.assertIn("-Dpicsure.loader.partitionBy=consent", calls[0])
        self.assertIn("-Xmx512m", calls[0])
        self.fake_java(0, False)  # exit 0 but no store per consent
        self.assertEqual(lane.lane1_probe()[0], "fail")
        self.assertFalse(lane.picsureloader_supports_combined_input()[0])
        self.fake_java(0, True)
        self.assertEqual(lane.lane1_probe(), ("ok", ""))
        self.assertEqual(lane.picsureloader_supports_combined_input(), (True, ""))
        self.assertEqual([d for d in os.listdir(lane.WORK) if d.startswith(".lane1-probe")], [])  # the temp base is removed

    def test_probe_failure_carries_the_log_tail(self):
        self.make_jar(True)

        def run(cmd, **kw):
            return subprocess.CompletedProcess(cmd, 3, b"line1\n" + b"java.lang.OutOfMemoryError: Java heap space\n")
        subprocess.run = run
        state, why = lane.lane1_probe()
        self.assertEqual(state, "fail")
        self.assertIn("rc=3", why)
        self.assertIn("OutOfMemoryError", why)

        def boom(cmd, **kw):
            raise subprocess.TimeoutExpired(cmd, 120)
        subprocess.run = boom
        state, why = lane.lane1_probe()
        self.assertEqual(state, "fail")
        self.assertIn("probe did not run", why)

    def test_load_lane1_fails_on_a_failed_probe_in_every_mode_but_off(self):
        self.make_jar(True)
        self.fake_java(1, False)
        old_rm = lane.rmtree
        lane.rmtree = lambda p: __import__("shutil").rmtree(p, ignore_errors=True)  # WORK is a temp dir here
        try:
            for mode, status, text in (("auto", "failed", "probe failed"), ("require", "failed", "probe failed"),
                                       ("off", "skipped", "LANE1=off")):
                os.environ["LANE1"] = mode
                os.makedirs(os.path.join(lane.WORK, "combined"), exist_ok=True)
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(lane.load("combined", "picsureloader-combined"), 0)
                with open(os.path.join(lane.WORK, "combined", "picsureloader-combined", "load.json")) as f:
                    load = json.load(f)
                self.assertEqual(load["status"], status, mode)
                self.assertIn(text, load["reason"])
        finally:
            lane.rmtree = old_rm

    def test_lane1_modes(self):
        os.environ["LANE1"] = "off"
        self.assertEqual(lane.lane1_unavailable(), "LANE1=off")
        os.environ["LANE1"] = "bogus"
        with self.assertRaises(SystemExit):
            lane.lane1_mode()
        os.environ["LANE1"] = "auto"
        self.assertIn("not found", lane.lane1_unavailable())  # no jar
        self.make_jar(True)
        self.fake_java(0, True)
        self.assertIsNone(lane.lane1_unavailable())
        self.fake_java(1, False)  # the option is there, the probe fails: unavailable with the reason, and state "fail"
        self.assertIn("probe failed", lane.lane1_unavailable())
        self.assertEqual(lane.lane1_state()[0], "fail")
        os.environ["LANE1"] = "off"
        self.assertEqual(lane.lane1_state(), ("off", "LANE1=off"))


class LoadStatusTest(unittest.TestCase):
    def test_ok_needs_exit_zero_something_loaded_and_no_failed_partition(self):
        self.assertEqual(lane.load_status(0, ["a"], []), "ok")
        self.assertEqual(lane.load_status(0, ["a"], ["b"]), "partial")
        self.assertEqual(lane.load_status(1, ["a"], []), "partial")
        self.assertEqual(lane.load_status(0, [], []), "failed")
        self.assertEqual(lane.load_status(1, [], ["a"]), "failed")

    def test_check_sortable_finds_embedded_line_breaks(self):
        with tempfile.TemporaryDirectory() as d:
            good = os.path.join(d, "good.csv")
            with open(good, "w") as f:
                f.write('"1","\\a\\","5","","0"\n\n"2","\\a\\","6","","0"\n')
            self.assertEqual(lane.check_sortable(good), (2, 2))
            bad = os.path.join(d, "bad.csv")
            with open(bad, "w") as f:
                f.write('"1","\\a\\","","two\nlines","0"\n"2","\\a\\","6","","0"\n')
            self.assertEqual(lane.check_sortable(bad), (3, 2))

    def test_request_records_and_validates_flows(self):
        with tempfile.TemporaryDirectory() as d:
            old = lane.WORK
            lane.WORK = d
            try:
                quiet = contextlib.redirect_stderr(io.StringIO())
                with quiet:
                    self.assertEqual(lane.request("combined", ["picsureloader-split"]), 0)
                    with open(os.path.join(d, "combined", "requested-flows.json")) as f:
                        self.assertEqual(json.load(f)["flows"], ["picsureloader-split"])
                    self.assertEqual(lane.request("mini", ["picsureloader-combined"]), 2)  # a split dataset has no combined flow
                    self.assertEqual(lane.request("mini", []), 2)
            finally:
                lane.WORK = old


class CheckLoadsTest(unittest.TestCase):
    ALL = ["picsureloader-combined", "csvloader-split", "picsureloader-split"]

    def ok(self, **kw):
        return dict({"status": "ok", "partitions_loaded": ["P"], "partitions_failed": []}, **kw)

    def check(self, loads, requested=None, lane1=None, applicable=None):
        return compare.check_loads(applicable or self.ALL, requested if requested is not None else (applicable or self.ALL), loads,
                                   lambda: lane1)

    def test_clean_run(self):
        loads = {f: self.ok() for f in self.ALL}
        mms, not_run, skipped, n_a = self.check(loads)
        self.assertEqual((mms, not_run, skipped, n_a), ([], [], [], {}))

    def test_a_lane_that_never_ran_fails(self):
        loads = {"picsureloader-combined": self.ok(), "csvloader-split": None, "picsureloader-split": self.ok()}
        mms, not_run, _, _ = self.check(loads)
        self.assertEqual([(m["category"], m["kind"], m["lanes"]) for m in mms], [("load", "load:missing", ["csvloader-split"])])
        self.assertEqual(not_run, [])

    def test_a_status_other_than_ok_fails(self):
        for status in ("failed", "skipped", "partial"):
            loads = {f: self.ok() for f in self.ALL}
            loads["csvloader-split"] = self.ok(status=status)
            mms, _, skipped, _ = self.check(loads)
            self.assertEqual([m["kind"] for m in mms], ["load:status"], status)
            self.assertEqual(skipped, [])  # only lane 1 may be skipped

    def test_a_failed_partition_fails_whatever_the_status(self):
        loads = {f: self.ok() for f in self.ALL}
        loads["picsureloader-split"] = self.ok(partitions_failed=["Q"])  # status ok, but a partition failed
        mms, _, _, _ = self.check(loads)
        self.assertEqual([(m["kind"], m["partitions"]) for m in mms], [("load:partition-failed", ["Q"])])
        loads["picsureloader-split"] = self.ok(status="partial", partitions_failed=["Q", "R"])
        mms, _, _, _ = self.check(loads)
        self.assertEqual([(m["kind"], m["partitions"]) for m in mms], [("load:partition-failed", ["Q"]), ("load:partition-failed", ["R"])])
        self.assertTrue(all(compare.explain(m, {}) is None for m in mms))  # no known-difference rule names these partitions

    def test_ok_with_nothing_loaded_fails(self):
        loads = {f: self.ok() for f in self.ALL}
        loads["csvloader-split"] = self.ok(partitions_loaded=[])
        self.assertEqual([m["kind"] for m in self.check(loads)[0]], ["load:status"])

    def test_lane1_skipped_is_reported_not_failed(self):
        loads = {f: self.ok() for f in self.ALL}
        loads["picsureloader-combined"] = {"status": "skipped", "reason": "PICSURELoader without combined input"}
        mms, not_run, skipped, _ = self.check(loads)
        self.assertEqual((mms, not_run), ([], []))
        self.assertEqual(skipped, [{"flow": "picsureloader-combined", "lane": "lane1", "reason": "PICSURELoader without combined input"}])
        loads["picsureloader-combined"] = {"status": "failed", "reason": "LANE1=require: x", "partitions_failed": []}
        self.assertEqual([m["kind"] for m in self.check(loads)[0]], ["load:status"])

    def test_flows_that_were_not_asked_for_are_not_run_not_failures(self):
        loads = {"picsureloader-combined": None, "csvloader-split": None, "picsureloader-split": self.ok()}
        mms, not_run, _, n_a = self.check(loads, requested=["picsureloader-split"])
        self.assertEqual(mms, [])
        self.assertEqual(not_run, ["picsureloader-combined", "csvloader-split"])
        # lane 1 that cannot run on this jar is not applicable, so it is not 'not run'
        mms, not_run, _, n_a = self.check(loads, requested=["picsureloader-split"], lane1="PICSURELoader without combined input")
        self.assertEqual((mms, not_run), ([], ["csvloader-split"]))
        self.assertEqual(n_a, {"picsureloader-combined": "PICSURELoader without combined input"})


class ReportTest(unittest.TestCase):
    LANE_OK = {"lane": "lane2", "status": "ok", "wall_seconds": 1.0, "store_bytes": 2048, "partitions_loaded": ["P"],
               "partitions_failed": [], "health": "UP"}

    def write(self, d, name, unexplained, answered=4, asked=4, not_run=(), skipped=(), lanes=None):
        lane_ = dict(self.LANE_OK, queries={"asked": asked, "answered": answered, "errors": asked - answered, "mismatches": unexplained})
        mism = [{"id": "m%d" % i, "category": "query", "partitions": ["P"], "lanes": ["csvloader-split"], "concept": "\\c\\",
                 "query": "REQUIRED", "truth": 1, "answers": {"csvloader-split": 2}, "explained_by": None} for i in range(unexplained)]
        rep = {"dataset": name, "lanes": lanes or {"csvloader-split": lane_}, "mismatches": mism, "known_differences": [],
               "requested_flows": ["csvloader-split"], "not_run": list(not_run), "skipped": list(skipped),
               "truth": {"P": {"rows": 1, "patients": 1, "concepts": 1, "observations": 1, "skipped_short_rows": 0,
                               "rows_without_value": 0, "rolled_up_rows": 0}},
               "summary": {"mismatches": unexplained, "explained": 0, "unexplained": unexplained}}
        with open(os.path.join(d, name + ".json"), "w") as f:
            json.dump(rep, f)

    def run_report(self, d, names):
        compare.REPORT_DIR = d
        out = io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            rc = compare.report(names)
        return rc, out.getvalue()

    def test_report_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, "a", 0)
            rc, out = self.run_report(d, ["a"])
            self.assertEqual(rc, 0)
            self.assertEqual(out.strip().splitlines()[-1], "PASS")  # nothing partial, nothing skipped: a plain PASS
            self.write(d, "b", 2)
            self.assertEqual(self.run_report(d, ["a", "b"])[0], 1)
            self.write(d, "c", 0, answered=3)  # an unanswered query fails the run too
            self.assertEqual(self.run_report(d, ["c"])[0], 1)
            self.assertEqual(self.run_report(d, ["missing"])[0], 2)
            with open(os.path.join(d, "compare.md")) as f:
                self.assertIn("FAIL", f.read())

    def test_a_dataset_that_asked_no_queries_fails(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, "a", 0, answered=0, asked=0)
            rc, out = self.run_report(d, ["a"])
            self.assertEqual(rc, 1)
            self.assertIn("asked 0 queries", out)
            self.assertNotIn("PASS", out)
            # one dataset with queries does not make up for another without
            self.write(d, "b", 0)
            self.assertEqual(self.run_report(d, ["a", "b"])[0], 1)
            # a lane that loaded but asked nothing, next to a lane that did ask
            two = {"csvloader-split": dict(self.LANE_OK, queries={"asked": 4, "answered": 4, "errors": 0, "mismatches": 0}),
                   "picsureloader-split": dict(self.LANE_OK, lane="lane3", queries={"asked": 0, "answered": 0, "errors": 0, "mismatches": 0})}
            self.write(d, "c", 0, lanes=two)
            rc, out = self.run_report(d, ["c"])
            self.assertEqual(rc, 1)
            self.assertIn("picsureloader-split loaded but asked 0 queries", out)

    def test_a_flows_override_passes_as_partial(self):
        with tempfile.TemporaryDirectory() as d:
            self.write(d, "combined", 0, not_run=["csvloader-split"])
            rc, out = self.run_report(d, ["combined"])
            self.assertEqual(rc, 0)
            self.assertEqual(out.strip().splitlines()[-1], "PASS (partial: csvloader-split not run)")
            self.assertIn("WARN not run: combined csvloader-split", out)
            with open(os.path.join(d, "compare.md")) as f:
                md = f.read()
            self.assertIn("**PASS (partial: csvloader-split not run)**", md)
            self.assertIn("WARN not run: combined csvloader-split", md)

    def test_a_skipped_lane_is_on_the_pass_line(self):
        with tempfile.TemporaryDirectory() as d:
            skipped = [{"flow": "picsureloader-combined", "lane": "lane1", "reason": "PICSURELoader without combined input"}]
            self.write(d, "combined", 0, skipped=skipped)
            self.write(d, "synth", 0, skipped=skipped)
            rc, out = self.run_report(d, ["combined", "synth"])
            self.assertEqual(rc, 0)
            self.assertEqual(out.strip().splitlines()[-1], "PASS (lane1 skipped: PICSURELoader without combined input)")
            self.write(d, "combined", 0, skipped=skipped, not_run=["csvloader-split"])
            self.assertEqual(self.run_report(d, ["combined"])[1].strip().splitlines()[-1],
                             "PASS (partial: csvloader-split not run; lane1 skipped: PICSURELoader without combined input)")


if __name__ == "__main__":
    unittest.main()
