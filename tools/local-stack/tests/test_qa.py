"""qa.py against fake services: the battery passes on a consistent stack and fails, check by check, on a stack that is wrong in one place.
No docker: the HPDS, the open-mode HPDS, the query-service and the dictionary are small HTTP servers over a few made-up partitions."""
import contextlib
import csv
import http.server
import io
import json
import os
import sys
import tempfile
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)

import qa

STUDY_A, STUDY_B = "phs000000", "phs999999"
GLOBAL = "\\DCC Harmonized data set\\age\\"
STIG = "\\phs000000\\pht000001\\phv00000001\\Var 01\\"
V1, V2 = STIG, "\\phs000000\\pht000001\\phv00000002\\Var 02\\"
W1 = "\\phs999999\\pht000001\\phv00000001\\Var 01\\"

# partition -> {concept: set of patients}; phs000000.c1 and phs000000.c2 share patient 3, phs999999.c1 shares patient 3 as well
DATA = {
    "phs000000.c1": {V1: {1, 2, 3}, V2: {1, 3}, GLOBAL: {1, 2, 3}},
    "phs000000.c2": {V1: {3, 4}, GLOBAL: {3, 4}},
    "phs999999.c1": {W1: {3, 5, 6}, GLOBAL: {3, 5, 6}},
}
OBS = 2  # observations per patient and concept


class Fake(http.server.BaseHTTPRequestHandler):
    """One configurable HPDS. cls attributes are set by the subclass factory."""
    require_filter = True
    union = "set"  # "sum" double counts patients of two partitions
    removed = ()  # concept paths the search does not list, and queries do not count
    hidden = ()  # concept paths the search does not list but queries still count
    none_reply = (200, "")  # what a query without consents gets when the filter is on
    data = DATA

    def reply(self, status, text, ctype="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(text.encode())

    def do_GET(self):
        self.reply(200, '{"status":"UP"}')

    def partitions(self, consents):
        if not consents:
            return None if self.require_filter else list(self.data)
        return [c for c in consents if c in self.data]

    def do_POST(self):
        self.answer(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))

    def answer(self, body):
        if self.path.endswith("/search"):
            term = str(body["query"])
            paths = {c for p in self.data.values() for c in p if term.lower() in c.lower() and c not in self.removed and c not in self.hidden}
            return self.reply(200, json.dumps({"results": {"phenotypes": {c: {} for c in paths}, "info": {}}, "searchQuery": term}))
        q = body["query"]
        consents = [c["value"] for c in q.get("userConsents") or []]
        parts = self.partitions(consents)
        if parts is None:
            return self.reply(*self.none_reply, "text/plain")
        clause = q.get("phenotypicClause")
        concept = clause["conceptPath"] if clause else None
        if q["expectedResultType"] == "OBSERVATION_CROSS_COUNT":
            c = q["select"][0]
            n = sum(len(self.data[p].get(c, ())) * OBS for p in parts)
            return self.reply(200, json.dumps({c: n}))
        sets = []
        for p in parts:
            if concept is None:
                sets.append(set().union(*self.data[p].values()))
            elif concept not in self.removed:
                sets.append(self.data[p].get(concept, set()))
        if self.union == "sum":
            return self.reply(200, str(sum(len(s) for s in sets)), "text/plain")
        return self.reply(200, str(len(set().union(*sets)) if sets else 0), "text/plain")

    def log_message(self, *a):
        pass


class FakeDict(http.server.BaseHTTPRequestHandler):
    """POST /concepts: the concepts of the datasets of the consents' studies, plus the global dataset. extra: (dataset, path) pairs added."""
    extra = ()  # (dataset, path) rows added for a request whose consents include that dataset's study (a global dataset: always)
    leaks = ()  # (dataset, path) rows added to every answer

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'{"status":"UP"}')

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        studies = {c.split(".")[0] for c in body["consents"] if c in DATA}
        out = []
        for study in sorted(studies):
            seen = {c for p, cs in DATA.items() if p.split(".")[0] == study for c in cs if not c.startswith("\\DCC")}
            out += [{"dataset": study, "conceptPath": c} for c in sorted(seen)]
        if studies:
            out.append({"dataset": "DCC Harmonized data set", "conceptPath": GLOBAL})
        if studies:
            out += [{"dataset": d, "conceptPath": c} for d, c in self.extra if d in studies or d not in (STUDY_A, STUDY_B)]
            out += [{"dataset": d, "conceptPath": c} for d, c in self.leaks]
        self.send_response(200)
        self.end_headers()
        self.wfile.write(json.dumps({"content": out, "totalElements": len(out)}).encode())

    def log_message(self, *a):
        pass


@contextlib.contextmanager
def serve(handler):
    srv = http.server.HTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_port}"
    finally:
        srv.shutdown()
        srv.server_close()


def variant(base, **attrs):
    return type("Variant", (base,), attrs)


def write_store(root, data=DATA, skew=None):
    """columnMeta.csv per partition from DATA; skew = {(partition, concept): observation count to write instead}."""
    for p, concepts in data.items():
        os.makedirs(os.path.join(root, p))
        with open(os.path.join(root, p, "columnMeta.csv"), "w", newline="") as f:
            w = csv.writer(f)
            for path, pats in sorted(concepts.items()):
                obs = (skew or {}).get((p, path), len(pats) * OBS)
                w.writerow([path, 8, 0, "false", "", 1.0, 9.0, 0, 10, obs, len(pats)])


class QaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = os.path.join(self.tmp.name, "store")
        write_store(self.store)
        self.manifest = os.path.join(self.tmp.name, "manifest.json")
        self.write_manifest({p: {"patients": len(set().union(*c.values())), "observations": sum(len(s) for s in c.values()) * OBS} for p, c in DATA.items()})
        self.stig = os.path.join(self.tmp.name, "stig.txt")
        with open(self.stig, "w") as f:
            f.write(STIG + "\n")

    def tearDown(self):
        self.tmp.cleanup()

    def write_manifest(self, parts):
        with open(self.manifest, "w") as f:
            json.dump({"partitions": [dict(name=p, **v) for p, v in parts.items()]}, f)

    def run_qa(self, hpds, opn=None, api=None, dic=None, extra=()):
        argv = ["--hpds", hpds, "--store", self.store, "--manifest", self.manifest, "--report-dir", os.path.join(self.tmp.name, "report"), "--sample", "3"]
        for flag, url in (("--open", opn), ("--api", api), ("--dict", dic)):
            if url:
                argv += [flag, url]
        rep, _ = qa.run(qa.parse(argv + list(extra)))
        return rep

    def failing(self, rep):
        return {r["check"] for r in rep.results if r["status"] == qa.FAIL}

    def test_a_consistent_stack_passes(self):
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False, removed=(STIG,))) as opn, serve(variant(Fake)) as api, serve(FakeDict) as dic:
            rep = self.run_qa(hpds, opn, api, dic, ["--stigvars", self.stig])
        self.assertEqual(self.failing(rep), set())
        self.assertEqual(rep.exit_code(), 0)
        checks = {r["check"] for r in rep.results if r["status"] == qa.PASS}
        self.assertTrue({"partition.patients", "concept.sample", "union", "consent.missing", "consent.none", "open.none", "open.consents", "stigvar.open",
                         "api.same", "dict.consent", "dict.study_paths", "dict.global_paths", "dict.unknown"} <= checks)

    def run_qa_asking(self, inspect, env_inspect, opn=None, api=None, extra=()):
        with serve(variant(Fake)) as hpds:
            argv = ["--hpds", hpds, "--store", self.store, "--manifest", self.manifest, "--report-dir", os.path.join(self.tmp.name, "report"), "--sample", "3",
                    "--hpds-container", "picsure-local-hpds-lane3"]
            for flag, url in (("--open", opn), ("--api", api)):
                if url:
                    argv += [flag, url]
            rep, _ = qa.run(qa.parse(argv + list(extra)), inspect, env_inspect)
        return rep

    def test_services_that_serve_another_store_are_a_setup_problem_and_are_not_compared(self):
        def inspect(store):
            return lambda name: {"sha": "a" * 40, "ref": "r", "via": "v", "store": store}
        env = {"lane2": ["HPDS_AUTH_URL=http://hpds-lane2:8080/PIC-SURE"], "lane3": ["HPDS_AUTH_URL=http://hpds-lane3:8080/PIC-SURE"]}
        with serve(variant(Fake, require_filter=False, removed=(STIG,))) as opn, serve(variant(Fake)) as api:
            rep = self.run_qa_asking(inspect("lane3"), lambda c: env["lane3"], opn, api, ["--stigvars", self.stig])
            self.assertEqual(rep.exit_code(), 0)  # the stores match
            rep = self.run_qa_asking(inspect("lane2"), lambda c: env["lane2"], opn, api, ["--stigvars", self.stig])
        self.assertEqual(rep.exit_code(), 2)
        setup = [r for r in rep.results if r["check"] == "setup"]
        self.assertEqual({r["case"] for r in setup}, {"open", "api"})
        self.assertTrue(all("lane2" in r["detail"] for r in setup))
        self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.SKIP}, {"open.none", "api.same", "dict.consent"})  # neither was compared

    def test_missing_targets_are_skipped_unless_required(self):
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds)
            self.assertEqual(rep.exit_code(), 0)
            self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.SKIP}, {"open.none", "api.same", "dict.consent"})
            rep = self.run_qa(hpds, extra=["--require", "open,dict"])
        self.assertEqual(rep.exit_code(), 2)
        self.assertEqual(sum(1 for r in rep.results if r["check"] == "setup" and r["status"] == qa.INCONCLUSIVE), 2)

    def test_a_patient_counted_twice_fails_the_union_check(self):
        with serve(variant(Fake, union="sum")) as hpds:
            rep = self.run_qa(hpds)
        self.assertIn("union", self.failing(rep))
        self.assertEqual(rep.exit_code(), 1)

    def test_a_wrong_manifest_count_fails_the_partition_check(self):
        self.write_manifest({"phs000000.c1": {"patients": 4, "observations": 10}})
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds)
        bad = [r for r in rep.results if r["status"] == qa.FAIL]
        self.assertEqual({r["check"] for r in bad}, {"partition.patients", "partition.observations"})
        self.assertTrue(all(r["case"] == "phs000000.c1" for r in bad))

    def test_a_concept_count_that_differs_from_columnmeta_fails(self):
        os.rename(self.store, self.store + ".old")
        write_store(self.store, skew={("phs000000.c1", V1): 99})
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds, extra=["--sample", "20"])
        self.assertIn("concept.sample", self.failing(rep))

    def test_no_source_of_truth_is_inconclusive_not_a_pass(self):
        self.write_manifest({})
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds)
        self.assertEqual(rep.exit_code(), 2)
        self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE}, {"partition.patients"})

    def test_no_consents_is_refused_only_by_a_clean_answer(self):
        for reply in ((500, "boom"), (403, "Resource is locked"), (502, "")):
            with serve(variant(Fake, none_reply=reply)) as hpds:
                rep = self.run_qa(hpds)
            self.assertIn("consent.none", self.failing(rep), reply)
        with serve(variant(Fake, none_reply=(403, "forbidden"))) as hpds:
            self.assertNotIn("consent.none", self.failing(self.run_qa(hpds)))

    def test_refusal(self):
        self.assertEqual(qa.refusal(None, "refused")[0], False)
        self.assertEqual(qa.refusal(503, "")[0], False)
        self.assertEqual(qa.refusal(403, "Resource is locked")[0], False)
        self.assertEqual(qa.refusal(200, "12")[0], False)
        self.assertEqual(qa.refusal(200, "")[0], True)
        self.assertEqual(qa.refusal(400, "missing consents")[0], True)

    def test_a_filter_that_answers_without_consents_fails(self):
        with serve(variant(Fake, require_filter=False)) as hpds:
            rep = self.run_qa(hpds)
        self.assertIn("consent.none", self.failing(rep))

    def test_an_unknown_consent_that_counts_fails(self):
        class Wrong(Fake):
            def partitions(self, consents):
                return list(self.data) if "unknown.c9" in consents else super().partitions(consents)

        with serve(Wrong) as hpds:
            rep = self.run_qa(hpds)
        self.assertIn("consent.missing", self.failing(rep))

    def test_open_without_consents_must_see_every_partition(self):
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=True)) as opn:
            rep = self.run_qa(hpds, opn)
        self.assertIn("open.none", self.failing(rep))

    def test_a_listed_path_in_the_open_search_fails(self):
        with open(self.stig, "w") as f:
            f.write(f"{V1}\n{V2}\n")
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False, removed=(V1,))) as opn:
            rep = self.run_qa(hpds, opn, extra=["--stigvars", self.stig])
        bad = [r for r in rep.results if r["check"] == "stigvar.open" and r["status"] == qa.FAIL]
        self.assertTrue(bad)
        self.assertIn(f"search {V2}", [r["case"] for r in bad])
        self.assertNotIn(f"search {V1}", [r["case"] for r in bad])

    def test_a_listed_concept_the_open_hpds_still_counts_fails_even_when_the_search_hides_it(self):
        with open(self.stig, "w") as f:
            f.write(f"{V1}\n{V2}\n")
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False, removed=(V2,), hidden=(V1,))) as opn:
            rep = self.run_qa(hpds, opn, extra=["--stigvars", self.stig])
        bad = [r for r in rep.results if r["check"] == "stigvar.open" and r["status"] == qa.FAIL]
        self.assertEqual([r["case"] for r in bad], [f"query {V1}"])

    def test_a_listed_path_does_not_hide_the_paths_below_it(self):
        parent, child = "\\phs000000\\pht000002\\", "\\phs000000\\pht000002\\phv00000001\\Var 01\\"
        data = {"phs000000.c1": {parent: {1, 2}, child: {1, 2, 3}, GLOBAL: {1, 2, 3}}}
        store = os.path.join(self.tmp.name, "store2")
        write_store(store, data)
        with open(self.stig, "w") as f:
            f.write(parent + "\n")
        argv = ["--store", store, "--report-dir", os.path.join(self.tmp.name, "report"), "--sample", "3", "--stigvars", self.stig]
        # the parent is removed, the child is not and is searched and counted: exact matching means that is not a failure
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False, removed=(parent,))) as opn:
            rep, _ = qa.run(qa.parse(["--hpds", hpds, "--open", opn] + argv))
        self.assertFalse([r for r in rep.results if r["check"].startswith("stigvar") and r["status"] == qa.FAIL])
        cases = [r["case"] for r in rep.results if r["check"] == "stigvar.open"]
        self.assertIn(f"search {parent}", cases)
        self.assertFalse([c for c in cases if child in c])
        # the parent itself still served is a failure
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False)) as opn:
            rep, _ = qa.run(qa.parse(["--hpds", hpds, "--open", opn] + argv))
        self.assertIn(f"search {parent}", [r["case"] for r in rep.results if r["check"] == "stigvar.open" and r["status"] == qa.FAIL])
        # the open-mode comparison still samples a concept below a listed path
        rep = qa.Report()
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False)) as opn:
            qa.check_open(rep, qa.Client(hpds), qa.Client(opn), ["phs000000.c1"], {"phs000000.c1": [parent, child]}, frozenset([parent]))
        cases = [r["case"] for r in rep.results]
        self.assertIn(f"phs000000.c1 REQUIRED {child}", cases)
        self.assertNotIn(f"phs000000.c1 REQUIRED {parent}", cases)

    def big_list(self, n, present):
        return [f"\\phs000000\\pht{i:06d}\\phv{i:08d}\\Var\\" for i in range(n)] + list(present)

    def test_a_large_list_is_checked_with_bounded_calls(self):
        present = [f"\\phs000000\\pht900000\\phv{i:08d}\\Var\\" for i in range(300)]
        data = {"phs000000.c1": {c: {1, 2} for c in present + [GLOBAL]}}
        entries = self.big_list(120000, present)
        with open(self.stig, "w") as f:
            f.write("\n".join(entries) + "\n")
        calls = {"open": 0, "hpds": 0}

        def counting(name):
            return variant(Fake, data=data, require_filter=name == "hpds", removed=set(present) if name == "open" else (),
                           do_POST=lambda self: (calls.__setitem__(name, calls[name] + 1), Fake.do_POST(self))[1])

        stigvars = qa.read_stigvars(self.stig)
        self.assertEqual(len(stigvars), 120300)
        meta = {"phs000000.c1": {c: {} for c in data["phs000000.c1"]}}
        rep = qa.Report()
        with serve(counting("hpds")) as hpds, serve(counting("open")) as opn:
            qa.check_stigvars(rep, qa.Client(hpds), qa.Client(opn), stigvars, ["phs000000.c1"], meta, 25)
        self.assertEqual(calls["hpds"], 25)
        self.assertEqual(calls["open"], 50)  # a search and a query per checked path
        self.assertFalse([r for r in rep.results if r["status"] == qa.FAIL])
        self.assertEqual([r["status"] for r in rep.results if r["check"] == "stigvar.control"], [qa.PASS] * 25)
        cover = [r for r in rep.results if r["case"] == "coverage"]
        self.assertEqual(len(cover), 1)
        self.assertIn("120300 listed, 300 in the partitions, 120000 in no examined partition (not checked), 25 checked", cover[0]["detail"])
        self.assertLess(len(rep.results), 100)
        # the same list gives the same sample
        rep2 = qa.Report()
        with serve(counting("hpds")) as hpds, serve(counting("open")) as opn:
            qa.check_stigvars(rep2, qa.Client(hpds), qa.Client(opn), stigvars, ["phs000000.c1"], meta, 25)
        self.assertEqual([r["case"] for r in rep.results], [r["case"] for r in rep2.results])

    def test_every_study_with_a_listed_path_is_checked_whatever_the_limit(self):
        studies = [f"phs{n:06d}" for n in range(100, 140)]
        big = {s: [f"\\{s}\\pht000001\\phv{i:08d}\\Var\\" for i in range(40)] for s in studies[:-1]}
        lone = f"\\{studies[-1]}\\pht000001\\phv00000001\\Var\\"  # the one listed path of the last study
        data = {f"{s}.c1": {c: {1} for c in cs + [GLOBAL]} for s, cs in big.items()}
        data[f"{studies[-1]}.c1"] = {lone: {1}, GLOBAL: {1}}
        meta = {p: {c: {} for c in d} for p, d in data.items()}
        listed = frozenset(c for cs in big.values() for c in cs) | {lone}
        rep = qa.Report()
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False, removed=listed)) as opn:
            qa.check_stigvars(rep, qa.Client(hpds), qa.Client(opn), listed, sorted(data), meta, 10)
        searched = {r["case"][len("search "):] for r in rep.results if r["case"].startswith("search ")}
        self.assertLess(10, len(studies))  # the limit is smaller than the number of studies, so the total is the number of studies
        self.assertIn(lone, searched)
        self.assertEqual({p.split("\\")[1] for p in searched}, set(studies))
        self.assertEqual(len(searched), len(studies))
        again = qa.Report()
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False, removed=listed)) as opn:
            qa.check_stigvars(again, qa.Client(hpds), qa.Client(opn), listed, sorted(data), meta, 10)
        self.assertEqual([r["case"] for r in rep.results], [r["case"] for r in again.results])
        # a limit above the number of studies fills evenly up to the limit
        self.assertEqual(len(qa.stratified({p: {p.split("\\")[1]} for p in listed}, 100)), 100)

    def test_the_coverage_is_printed_in_the_summary(self):
        rep = qa.Report()
        rep.add("stigvar.open", "coverage", qa.SKIP, "5 listed, 3 in the partitions, 2 in no examined partition (not checked), 3 checked")
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            qa.print_summary(rep, self.tmp.name, False)
        self.assertIn("stigvar coverage: 5 listed, 3 in the partitions", out.getvalue())

    def test_without_columnmeta_the_coverage_says_membership_is_unknown(self):
        rep = qa.Report()
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False)) as opn:
            qa.check_stigvars(rep, qa.Client(hpds), qa.Client(opn), frozenset([V1]), ["phs000000.c1"], {"phs000000.c1": None}, 5)
        cover = [r for r in rep.results if r["case"] == "coverage"][0]["detail"]
        self.assertIn("unknown", cover)
        self.assertNotIn("in no examined partition", cover)

    def test_a_negative_stigvar_limit_is_a_usage_error(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as cm:
            qa.parse(["--hpds", "h", "--store", "s", "--stigvar-limit", "-1"])
        self.assertEqual(cm.exception.code, 2)

    def test_stigvar_limit_zero_checks_every_listed_path_of_the_partitions(self):
        present = [f"\\phs000000\\pht900000\\phv{i:08d}\\Var\\" for i in range(30)]
        data = {"phs000000.c1": {c: {1, 2} for c in present + [GLOBAL]}}
        meta = {"phs000000.c1": {c: {} for c in data["phs000000.c1"]}}
        rep = qa.Report()
        with serve(variant(Fake, data=data)) as hpds, serve(variant(Fake, data=data, require_filter=False, removed=set(present))) as opn:
            qa.check_stigvars(rep, qa.Client(hpds), qa.Client(opn), frozenset(present) | {"\\nowhere\\"}, ["phs000000.c1"], meta, 0)
        self.assertEqual(len([r for r in rep.results if r["case"].startswith("search ")]), 30)
        self.assertEqual(qa.parse(["--hpds", "h", "--store", "s"]).stigvar_limit, 200)

    def test_a_list_that_matches_nothing_in_the_data_is_inconclusive(self):
        with open(self.stig, "w") as f:
            f.write("\\phs000000\\nothing\\here\\\n")
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False)) as opn:
            rep = self.run_qa(hpds, opn, extra=["--stigvars", self.stig])
        self.assertEqual(self.failing(rep), set())
        self.assertEqual(rep.exit_code(), 2)
        self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE}, {"stigvar.control"})

    def test_an_open_hpds_without_a_stigvar_list_is_inconclusive(self):
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False)) as opn:
            rep = self.run_qa(hpds, opn)
        self.assertEqual(rep.exit_code(), 2)
        self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE}, {"stigvar.open"})

    def test_requiring_stigvar_without_an_open_hpds_is_a_setup_problem(self):
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds, extra=["--require", "stigvar"])
        self.assertEqual(rep.exit_code(), 2)

    def test_partitions_without_columnmeta_csv_make_the_dictionary_path_checks_inconclusive(self):
        release = os.path.join(self.tmp.name, "release")
        for p in DATA:
            os.makedirs(os.path.join(release, p))
        self.store = release
        with serve(variant(Fake)) as hpds, serve(FakeDict) as dic:
            rep = self.run_qa(hpds, dic=dic)
            self.assertEqual(self.failing(rep), set())
            self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE and r["check"].startswith("dict.")},
                             {"dict.study_paths", "dict.global_paths"})
            # the same partitions with the csv files from another directory
            root = os.path.join(self.tmp.name, "flow")
            write_store(root)
            rep = self.run_qa(hpds, dic=dic, extra=["--columnmeta-root", root])
        self.assertEqual({r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE} & {"dict.study_paths", "dict.global_paths"}, set())
        self.assertTrue(any(r["check"] == "dict.study_paths" and r["status"] == qa.PASS for r in rep.results))

    def test_a_sampled_run_still_knows_the_other_studies(self):
        with serve(variant(Fake)) as hpds, serve(variant(FakeDict, leaks=((STUDY_B, W1),))) as dic:
            rep = self.run_qa(hpds, dic=dic, extra=["--max-partitions", "1"])
        self.assertIn("dict.consent", self.failing(rep))

    def test_the_query_service_must_answer_what_the_hpds_answers(self):
        class Drift(Fake):
            def answer(self, body):
                q = body["query"]
                if q["expectedResultType"] == "COUNT" and not q.get("phenotypicClause") and len(q["userConsents"]) == 1:
                    return self.reply(200, "7", "text/plain")
                return super().answer(body)

        with serve(variant(Fake)) as hpds, serve(Drift) as api:
            rep = self.run_qa(hpds, api=api)
        self.assertIn("api.same", self.failing(rep))

    def test_dictionary_concepts_of_another_study_fail(self):
        with serve(variant(Fake)) as hpds, serve(variant(FakeDict, leaks=((STUDY_B, W1),))) as dic:
            rep = self.run_qa(hpds, dic=dic)
        self.assertIn("dict.consent", self.failing(rep))

    def test_dictionary_paths_missing_from_the_partitions_fail(self):
        with serve(variant(Fake)) as hpds, serve(variant(FakeDict, extra=((STUDY_A, "\\phs000000\\pht000009\\nope\\"),))) as dic:
            rep = self.run_qa(hpds, dic=dic)
        self.assertEqual(self.failing(rep), {"dict.study_paths"})

    def test_global_paths_are_compared_apart_from_study_paths(self):
        with serve(variant(Fake)) as hpds, serve(variant(FakeDict, extra=(("DCC Harmonized data set", "\\DCC Harmonized data set\\ghost\\"),))) as dic:
            rep = self.run_qa(hpds, dic=dic)
        self.assertEqual(self.failing(rep), {"dict.global_paths"})

    def test_an_unreachable_hpds_is_a_setup_problem(self):
        rep = self.run_qa("http://127.0.0.1:9")
        self.assertEqual(rep.exit_code(), 2)
        self.assertEqual([r["check"] for r in rep.results], ["setup"])

    def test_reports_hold_counts_and_paths_only(self):
        with serve(variant(Fake)) as hpds:
            rep = self.run_qa(hpds)
        out = os.path.join(self.tmp.name, "report")
        qa.write_reports(rep, {"exit": rep.exit_code()}, out)
        with open(os.path.join(out, "qa.json")) as f:
            data = json.load(f)
        self.assertEqual(data["summary"]["exit"], 0)
        with open(os.path.join(out, "qa.tsv")) as f:
            lines = f.read().splitlines()
        self.assertEqual(lines[0], "status\tcheck\tcase\texpected\tgot\tdetail")
        self.assertEqual(len(lines) - 1, len(rep.results))

    def test_listed_is_an_exact_match(self):
        listed = frozenset(["\\A\\B\\"])
        self.assertTrue(qa.listed("\\A\\B\\", listed))
        self.assertFalse(qa.listed("\\A\\B\\C\\", listed))
        self.assertFalse(qa.listed("\\A\\Bc\\", listed))
        self.assertFalse(qa.listed("\\A\\", listed))

    def test_manifest_shapes(self):
        for body in ({"partitions": [{"name": "P", "patients": 3, "rows": 9}]}, {"partitions": [{"name": "P", "observationsWritten": 7}]},
                     {"partitions": {"P": {"patients": 3}}}):
            with open(self.manifest, "w") as f:
                json.dump(body, f)
            m = qa.read_manifest(self.manifest)
            self.assertEqual(list(m), ["P"])
        self.assertEqual(qa.read_manifest(self.manifest)["P"], {"patients": 3})

    CONSENTS = "\\_consents\\"
    SOURCE_IDS = "\\_source_subject_id\\"

    def global_release(self, mode="global", kind=True, serve_global_to_auth=False):
        """A release with a global partition holding the global rows; in mode global the study partitions hold data rows only. Returns the fake HPDS classes."""
        everyone = {1, 2, 3, 4, 5, 6}
        data = dict(DATA, **{"global": {self.CONSENTS: everyone, self.SOURCE_IDS: everyone}})
        if mode != "global":  # split / both: the study partitions hold the consents rows as well
            for p in DATA:
                data[p] = dict(DATA[p], **{g: set().union(*DATA[p].values()) for g in (self.CONSENTS, self.SOURCE_IDS)})
        self.store = os.path.join(self.tmp.name, "store-global")
        write_store(self.store, data)
        parts = []
        for p, c in data.items():
            e = {"name": p, "patients": len(set().union(*c.values())), "observations": sum(len(s) for s in c.values()) * OBS}
            if kind:
                e["kind"] = "global" if p == "global" else "study"
            parts.append(e)
        with open(self.manifest, "w") as f:
            json.dump({"schema": 2, "source": {"globals": mode}, "partitions": parts}, f)
        auth = variant(Fake, data=data, partitions=(lambda self, consents: [c for c in consents if c in self.data] + ["global"] if consents else None) if serve_global_to_auth else Fake.partitions)
        return auth, variant(Fake, data=data, require_filter=False)

    def test_manifest_kind_and_globals_are_read(self):
        self.global_release("global")
        m = qa.read_manifest(self.manifest)
        self.assertEqual(m["global"]["kind"], "global")
        self.assertEqual(m["phs000000.c1"]["kind"], "study")
        self.assertEqual(qa.manifest_globals(self.manifest), "global")
        with open(self.manifest, "w") as f:
            json.dump({"globals": "both", "partitions": [{"name": "P"}]}, f)
        self.assertEqual(qa.manifest_globals(self.manifest), "both")
        self.assertNotIn("kind", qa.read_manifest(self.manifest)["P"])
        with open(self.manifest, "w") as f:
            json.dump({"source": {"globals": "other"}, "partitions": []}, f)
        self.assertIsNone(qa.manifest_globals(self.manifest))

    def test_a_global_partition_is_not_a_study_partition(self):
        auth, opn_cls = self.global_release("global")
        with serve(auth) as hpds, serve(opn_cls) as opn, serve(FakeDict) as dic:
            rep = self.run_qa(hpds, opn, None, dic)
        self.assertEqual(self.failing(rep), set())
        for check in ("partition.patients", "concept.sample", "dict.consent", "dict.study_paths", "dict.global_paths"):
            self.assertNotIn("global", {r["case"] for r in rep.results if r["check"] == check}, check)
        served = [r for r in rep.results if r["check"] == "global.served"]
        self.assertTrue(served and all(r["status"] == qa.PASS and "globals=global" in r["detail"] for r in served), served)
        self.assertEqual({"open", "authorized"}, {r["case"].split()[1] for r in served})
        self.assertTrue(any(r["check"] == "partition.observations" and r["case"] == "global" and r["status"] == qa.PASS for r in rep.results))
        self.assertTrue(all("globals=global" in r["detail"] for r in rep.results if r["check"] == "partition.patients"))

    def test_a_global_partition_without_a_kind_is_found_by_its_name(self):
        auth, opn_cls = self.global_release("global", kind=False)
        with serve(auth) as hpds, serve(opn_cls) as opn:
            rep = self.run_qa(hpds, opn)
        self.assertNotIn("global", {r["case"] for r in rep.results if r["check"] == "partition.patients"})
        self.assertIn("global.served", {r["check"] for r in rep.results})

    def test_an_authorized_hpds_that_serves_the_global_partition_fails(self):
        auth, opn_cls = self.global_release("global", serve_global_to_auth=True)
        with serve(auth) as hpds, serve(opn_cls) as opn:
            rep = self.run_qa(hpds, opn)
        self.assertIn("global.served", self.failing(rep))

    def test_a_missing_open_hpds_skips_the_open_side_only(self):
        auth, _ = self.global_release("global")
        with serve(auth) as hpds:
            rep = self.run_qa(hpds)
        rows = {r["case"].split()[1] if " " in r["case"] else r["case"]: r["status"] for r in rep.results if r["check"] == "global.served"}
        self.assertEqual(rows, {"open": qa.SKIP, "authorized": qa.PASS})

    def test_in_mode_both_the_authorized_side_is_inconclusive_and_the_study_counts_still_compare(self):
        auth, opn_cls = self.global_release("both")
        with serve(auth) as hpds, serve(opn_cls) as opn:
            rep = self.run_qa(hpds, opn)
        served = {r["case"]: r["status"] for r in rep.results if r["check"] == "global.served"}
        self.assertEqual(served["global authorized"], qa.INCONCLUSIVE)
        self.assertEqual(self.failing(rep), set())
        self.assertTrue(any(r["check"] == "partition.patients" and r["case"] == "phs000000.c1" and r["status"] == qa.PASS and "globals=both" in r["detail"] for r in rep.results))

    def test_a_study_count_that_differs_from_the_manifest_in_mode_global_is_still_a_failure(self):
        auth, opn_cls = self.global_release("global")
        with open(self.manifest) as f:
            data = json.load(f)
        for p in data["partitions"]:
            if p["name"] == "phs000000.c1":
                p["patients"] += 1
        with open(self.manifest, "w") as f:
            json.dump(data, f)
        with serve(auth) as hpds, serve(opn_cls) as opn:
            rep = self.run_qa(hpds, opn)
        bad = [r for r in rep.results if r["status"] == qa.FAIL]
        self.assertEqual([(r["check"], r["case"]) for r in bad], [("partition.patients", "phs000000.c1")])
        self.assertIn("globals=global", bad[0]["detail"])

    def test_a_manifest_without_kind_or_globals_changes_nothing(self):
        with serve(variant(Fake)) as hpds, serve(variant(Fake, require_filter=False)) as opn:
            rep = self.run_qa(hpds, opn)
        self.assertEqual(self.failing(rep), set())
        self.assertNotIn("global.served", {r["check"] for r in rep.results})
        self.assertFalse(any("globals=" in r["detail"] for r in rep.results))

    def test_exit_codes(self):
        rep = qa.Report()
        self.assertEqual(rep.exit_code(), 0)
        rep.add("x", "y", qa.SKIP)
        self.assertEqual(rep.exit_code(), 0)
        rep.add("x", "y", qa.INCONCLUSIVE)
        self.assertEqual(rep.exit_code(), 2)
        rep.add("x", "y", qa.FAIL)
        self.assertEqual(rep.exit_code(), 1)


if __name__ == "__main__":
    unittest.main()
