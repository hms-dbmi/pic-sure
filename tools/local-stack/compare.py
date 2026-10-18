#!/usr/bin/env python3
"""Compares what the loader lanes put into HPDS with the ground truth computed from the input. Standard library only.

  compare.py run    DATASET   query every running lane HPDS, check each lane's columnMeta.csv, write work/report/DATASET.json
  compare.py report [DATASET ...]  merge work/report/*.json into compare.json and compare.md; exit 1 on unexplained mismatches

What is compared, for each lane that loaded something:
  - the set of partitions it loaded against the truth (a partition missing, or one that should not exist)
  - its columnMeta.csv per partition against the truth (categorical flag, category values, min, max, observation and patient counts)
    and the lanes' columnMeta.csv against each other (the offset and length columns are ignored: they depend on the file layout)
  - a battery of COUNT queries on the lane's HPDS (POST /PIC-SURE/v3/query/sync): per partition, for all partitions together and
    for an unknown consent. REQUIRED per concept, FILTER per categorical value, FILTER min..max on numeric concepts
    (the bounds are inclusive in HPDS).
A difference is "explained" when it matches a rule of KNOWN_DIFFERENCES below, and a rule explains only the kinds of mismatch it lists
(see RULES): for example the U+FFFD difference can explain a category list, never a REQUIRED count. Everything else is an unexplained
mismatch and the exit code is 1. So is a flow that was asked for and did not load every partition, a dataset that asked no query, and a
query that was not answered. A flow the run did not ask for (make FLOWS=...) is a warning: the result then reads
`PASS (partial: <flows> not run)`; a lane that was skipped (lane 1 without a capable jar) is named on the PASS line too.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lane as lanemod  # noqa: E402
import pheno  # noqa: E402

WORK = lanemod.WORK
REPORT_DIR = os.path.join(WORK, "report")
LANE_OF = {v: k for k, v in lanemod.FLOWS.items()}  # flow name -> lane1/2/3
DEFAULT_PORTS = {"lane1": 8081, "lane2": 8082, "lane3": 8083}
UNKNOWN_CONSENT = "unknown.c9"
MAX_VALUES_PER_CONCEPT = 6
MAX_UNKNOWN_QUERIES = 5
QUERY_PATH = "/PIC-SURE/v3/query/sync"

# What is known to differ, written from evidence (each entry carries the proof it found in this run). `lanes` are the flows whose
# answers differ from the truth; a mismatch is explained when its concept (or the whole partition) is in the entry's evidence.
KNOWN_DIFFERENCES = [
    {
        "id": "fffd-in-text",
        "title": "CSVLoader keeps U+FFFD in text values, PICSURELoader removes it",
        "lanes": ["csvloader-split"],
        "description": (
            "A text value containing an invalid byte is read as U+FFFD. CSVLoader strips it from the concept path and from the value it "
            "compares with the path, but stores the raw text value (CSVLoader.java: `value = record.get(TEXT_VALUE)`), so a categorical "
            "value `bad<FFFD>byte` stays as is. PICSURELoader removes the character (`badbyte`). A query for the cleaned value finds the "
            "patient in the PICSURELoader lane only; the column width and the category list differ too. The truth is the PICSURELoader "
            "result. mini's partition phs000000.c2 is the malformed fixture that shows it."
        ),
    },
    {
        "id": "csvloader-empty-row-first",
        "title": "CSVLoader types a concept by its first row, and a row without a value reads as text",
        "lanes": ["csvloader-split"],
        "description": (
            "CSVLoader creates the concept on the first row it reads, as categorical when that row has no numeric value, and then drops "
            "every row of the other type. After the sort, an empty-valued row of a numeric concept comes before the numeric rows "
            "(`\"\"` sorts before `\"5`), so the concept ends up with 0 observations; a concept whose rows are all empty is stored with "
            "0 observations. PICSURELoader keeps the numeric values and stores no concept for empty rows. Checked by hand on a three-row "
            "file (CSVLoader: 0 observations, PICSURELoader: 2). The datasets of the stack avoid it on numeric concepts; real data does not."
        ),
    },
    {
        "id": "csvloader-whitespace-nval",
        "title": "A whitespace-only nval drops the row in the CSVLoader and falls back to the text value in the PICSURELoader",
        "lanes": ["csvloader-split"],
        "description": (
            "The CSVLoader tests the raw cell (`numericValue.isEmpty()`), so an nval of spaces makes the row numeric; the value trims to "
            "nothing and the row is dropped, whatever the text value says. The PICSURELoader trims first, finds the nval empty and keeps "
            "the text value (a number when it parses). With no text value both drop the row. The truth is the PICSURELoader result. "
            "Only split datasets can carry such a row to the loaders: the splitter quarantines it as BAD_NVAL."
        ),
    },
    {
        "id": "csvloader-nonfinite-numbers",
        "title": "NaN and Infinity are numbers in the CSVLoader and text (or nothing) in the PICSURELoader",
        "lanes": ["csvloader-split"],
        "description": (
            "`Double.parseDouble` accepts NaN and Infinity, so the CSVLoader stores them as numeric observations, in nval or (with an "
            "empty nval) in the text value. The PICSURELoader keeps finite numbers only: NaN in nval falls back to the text value (the "
            "row is rejected without one), NaN in the text value is the categorical value `NaN`. The truth is the PICSURELoader result."
        ),
    },
    {
        "id": "rollup-last-node",
        "title": "A last path node equal to the text value is dropped (both loaders)",
        "lanes": [],
        "description": (
            "CSVLoader.java:121-123 (and PICSURELoader, which keeps the rule) turn `\\a\\b\\Yes\\` with text value `Yes` into `\\a\\b\\`. "
            "dbGaP-style paths that carry the value as the last node therefore load under the parent concept. The truth applies the same "
            "rule, so this is not a mismatch; it is listed so nobody queries the full path."
        ),
    },
    {
        "id": "short-rows",
        "title": "Rows with fewer than four columns are skipped (mini's malformed partition)",
        "lanes": [],
        "description": (
            "CSVLoader skips a record with fewer than four columns after an INFO log line. PICSURELoader skips it too and records it as "
            "MALFORMED_RECORD (failures.jsonl, ingest-summary rowsRejected). Both lanes hold the same data. Rows with more than five "
            "columns, blank lines and an invalid byte in a text value are tolerated by both. The truth skips these rows."
        ),
    },
    {
        "id": "no-value-rows",
        "title": "Rows with neither a numeric nor a text value store no observation",
        "lanes": [],
        "description": "Both loaders store nothing for such a row (the PICSURELoader counts it as MISSING_VALUE in failures.jsonl). The truth does the same.",
    },
    {
        "id": "splitter-quarantine",
        "title": "The splitter sets aside rows that break the CSVLoader contract and rows of patients without a consent",
        "lanes": [],
        "description": (
            "With --bad-rows=quarantine a header row in the middle of the file and an nval such as `2402L` go to rejects/rejected-rows.csv. "
            "Rows of a patient without any consent are dropped (study rows) or have nowhere to go (global rows). The truth for a combined "
            "dataset routes the rows by the same rules, independently, and checks its row counts against split_manifest.json."
        ),
    },
]


# ----------------------------------------------------------------------------------------------------------------------------
# HPDS


class Hpds(object):
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")

    def health(self):
        try:
            with urllib.request.urlopen(self.base_url + "/actuator/health", timeout=10) as r:
                return json.loads(r.read().decode()).get("status", "?")
        except Exception as e:  # noqa: BLE001
            return "unreachable: %s" % e

    def count(self, consents, clause):
        body = {"query": {"userConsents": [{"value": c} for c in consents], "phenotypicClause": clause, "expectedResultType": "COUNT"}}
        req = urllib.request.Request(self.base_url + QUERY_PATH, data=json.dumps(body).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode().strip()
        except urllib.error.HTTPError as e:
            return None, "HTTP %d %s" % (e.code, e.read().decode(errors="replace")[:200])
        except Exception as e:  # noqa: BLE001
            return None, "%s" % e
        try:
            return int(text), None
        except ValueError:
            return None, "not a count: %r" % text[:200]


def required(concept):
    return {"phenotypicFilterType": "REQUIRED", "conceptPath": concept}


def values_filter(concept, values):
    return {"phenotypicFilterType": "FILTER", "conceptPath": concept, "values": sorted(values)}


def range_filter(concept, lo, hi):
    c = {"phenotypicFilterType": "FILTER", "conceptPath": concept}
    if lo is not None:
        c["min"] = lo
    if hi is not None:
        c["max"] = hi
    return c


# ----------------------------------------------------------------------------------------------------------------------------
# the battery


class Q(object):
    """One query: where (consents), what (clause) and the truth."""

    def __init__(self, scope, consents, desc, clause, truth, concept):
        self.scope = scope
        self.consents = consents
        self.desc = desc
        self.clause = clause
        self.truth = truth
        self.concept = concept


def fmt(x):
    return repr(float(x))


def build_battery(truth, lane_meta, partitions):
    """truth: name -> PartitionTruth; lane_meta: lane -> partition -> parsed columnMeta; partitions: every partition name in play."""
    queries = []

    def concept_info(scope_parts):
        """concept -> (numeric?, categorical value set, numeric values) over the scope, from the truth and from every lane's meta."""
        info = OrderedDict()
        for p in scope_parts:
            pt = truth.get(p)
            if pt:
                for path, c in pt.concepts.items():
                    e = info.setdefault(path, {"numeric": set(), "values": set(), "nums": []})
                    e["numeric"].add(c.numeric)
                    if c.numeric:
                        e["nums"] += [v for _, v in c.obs]
                    else:
                        e["values"] |= {v for _, v in c.obs}
            for lane, metas in lane_meta.items():
                m = metas.get(p, {})
                for path, cm in m.items():
                    e = info.setdefault(path, {"numeric": set(), "values": set(), "nums": []})
                    e["numeric"].add(not cm["categorical"])
                    if cm["categorical"]:
                        e["values"] |= set(cm["values"])
        return info

    def add_scope(scope, parts):
        for path, e in concept_info(parts).items():
            queries.append(Q(scope, parts, "REQUIRED %s" % path, required(path), pheno.count_required(truth, parts, path), path))
            if len(e["numeric"]) != 1:
                continue  # numeric in one partition, categorical in another: only REQUIRED is well defined
            if True in e["numeric"]:
                nums = e["nums"]
                if not nums:
                    continue
                lo, hi = min(nums), max(nums)
                mid = (lo + hi) / 2.0
                for a, b in ((lo, hi), (lo, mid), (mid, hi), (hi + 1.0, None)):
                    queries.append(Q(scope, parts, "FILTER %s [%s, %s]" % (path, fmt(a), "open" if b is None else fmt(b)),
                                     range_filter(path, a, b), pheno.count_range(truth, parts, path, a, b), path))
            else:
                vals = sorted(e["values"])
                chosen = vals[:MAX_VALUES_PER_CONCEPT]
                for v in chosen:
                    queries.append(Q(scope, parts, "FILTER %s = %r" % (path, v), values_filter(path, [v]),
                                     pheno.count_values(truth, parts, path, [v]), path))
                if len(vals) > 1:
                    queries.append(Q(scope, parts, "FILTER %s in all %d values" % (path, len(vals)), values_filter(path, vals),
                                     pheno.count_values(truth, parts, path, vals), path))

    for p in sorted(partitions):
        add_scope("partition:" + p, [p])
    if len(partitions) > 1:
        add_scope("all", sorted(partitions))
    # an unknown consent must see nothing
    seen = 0
    for path in sorted({q.concept for q in queries}):
        if seen >= MAX_UNKNOWN_QUERIES:
            break
        queries.append(Q("unknown-consent", [UNKNOWN_CONSENT], "REQUIRED %s" % path, required(path), 0, path))
        seen += 1
    return queries


# ----------------------------------------------------------------------------------------------------------------------------
# columnMeta


def meta_vs_truth(lane, part, meta, pt):
    """Differences between one partition's columnMeta.csv and the truth: list of (concept, what, expected, got)."""
    diffs = []
    for path in sorted(set(meta) | set(pt.concepts)):
        m, c = meta.get(path), pt.concepts.get(path)
        if m is None:
            diffs.append((path, "concept missing from columnMeta", "%d observations" % len(c.obs), None))
            continue
        if c is None:
            diffs.append((path, "concept not in the input", None, "%d observations" % m["observations"]))
            continue
        if m["categorical"] == c.numeric:
            diffs.append((path, "categorical flag", not c.numeric, m["categorical"]))
            continue
        if m["observations"] != len(c.obs):
            diffs.append((path, "observationCount", len(c.obs), m["observations"]))
        if m["patients"] != len(c.patients):
            diffs.append((path, "patientCount", len(c.patients), m["patients"]))
        if c.numeric:
            vals = [v for _, v in c.obs]
            for key, want in (("min", min(vals)), ("max", max(vals))):
                try:
                    got = float(m[key])
                except ValueError:
                    got = None
                if got is None or abs(got - want) > 1e-9 * max(1.0, abs(want)):
                    diffs.append((path, key, fmt(want), m[key]))
        elif set(m["values"]) != set(c.values()):
            diffs.append((path, "category values", sorted(set(c.values())), sorted(set(m["values"]))))
    return diffs


def meta_between_lanes(meta_a, meta_b):
    """Concepts whose columnMeta row differs between two lanes (offset and length columns are not part of the parsed meta)."""
    out = []
    for path in sorted(set(meta_a) | set(meta_b)):
        a, b = meta_a.get(path), meta_b.get(path)
        if a != b:
            fields = sorted(k for k in (set(a or {}) | set(b or {})) if (a or {}).get(k) != (b or {}).get(k)) if a and b else ["presence"]
            out.append((path, fields, a, b))
    return out


# ----------------------------------------------------------------------------------------------------------------------------
# run


# Which mismatch kinds a rule may explain (the `kind` of a mismatch: meta:<what> for columnMeta against the truth, meta-lane-diff,
# query:required / query:values / query:range, query:error for a query that got no count, load:*, health, splitter).
CSV = "csvloader-split"
DATA_KINDS = {"meta:categorical flag", "meta:observationCount", "meta:patientCount", "meta:min", "meta:max", "meta:category values",
              "meta:concept missing from columnMeta", "meta:concept not in the input", "meta-lane-diff",
              "query:required", "query:values", "query:range"}
# partition failures that are known and why: {"flow": ..., "partition": ..., "reason": ...}. None today.
PARTITION_FAILURE_RULES = []


def _fffd_kind_ok(mm, concept_values):
    """U+FFFD touches the stored text, so: the category list, the column width, and filters on a value that differs only by U+FFFD.
    Presence (REQUIRED), observation and patient counts, min/max and HTTP errors are not explained by it."""
    kind = mm.get("kind")
    if kind == "meta:category values":
        return True
    if kind == "meta-lane-diff":
        fields = set(mm.get("fields") or [])
        return bool(fields) and fields <= {"values", "width"}
    if kind == "query:values":
        clean = set(concept_values)
        return any(v.replace("\ufffd", "") in clean for v in mm.get("values") or [])
    return False


def _data_kind_ok(mm, _):
    return mm.get("kind") in DATA_KINDS


# rule id -> (evidence key, kind test); every one of them is CSVLoader-only
RULES = (
    ("fffd-in-text", "fffd", _fffd_kind_ok),
    ("csvloader-empty-row-first", "empty_first", _data_kind_ok),
    ("csvloader-whitespace-nval", "whitespace-nval", _data_kind_ok),
    ("csvloader-nonfinite-numbers", "nonfinite", _data_kind_ok),
)


def explain(mm, evidence):
    """The id of the known difference that explains a mismatch, or None.

    All the rules are about the CSVLoader: the mismatch must be that lane's alone, or a difference between it and another lane. It must
    be about a concept the evidence names in one of its partitions, and of a kind the rule lists. evidence: key -> partition ->
    concepts (a list, or for fffd a dict concept -> cleaned values)."""
    if mm.get("category") == "load":
        for r in PARTITION_FAILURE_RULES:
            if mm.get("kind") == "load:partition-failed" and mm.get("lanes") == [r["flow"]] and mm.get("partitions") == [r["partition"]] and r.get("reason"):
                return "partition-failure:" + r["partition"]
        return None
    lanes = set(mm.get("lanes", []))
    concept = mm.get("concept")
    if not concept or not lanes:
        return None
    if mm.get("kind") == "meta-lane-diff":
        if CSV not in lanes:
            return None
    elif not lanes <= {CSV}:
        return None
    for rule, key, kind_ok in RULES:
        named = evidence.get(key, {})
        for p in mm.get("partitions", []):
            entry = named.get(p, ())
            if concept not in entry:
                continue
            if kind_ok(mm, entry[concept] if isinstance(entry, dict) else ()):
                return rule
    return None


def check_loads(applicable, requested, loads, lane1_unavailable):
    """Judge the load.json of each applicable flow. loads: flow -> parsed load.json or None; requested: the flows this run asked for;
    lane1_unavailable: callable returning why lane 1 cannot run here, or None.

    Returns (mismatches, not_run, skipped, not_applicable). A flow that was asked for must have a load.json with status ok and no failed
    partition: anything else is a `load` mismatch. A flow that was not asked for is `not run` (a warning), except lane 1 when it cannot
    run here. Lane 1 may be `skipped` (its own load.json says why); that is reported, not failed."""
    mms, not_run, skipped, n_a = [], [], [], {}

    def add(flow, kind, detail, partitions=(), answers=None, truth="all partitions loaded"):
        mms.append({"category": "load", "kind": kind, "partitions": list(partitions), "lanes": [flow], "concept": None,
                    "query": "load %s" % flow, "truth": truth, "answers": answers or {flow: kind}, "detail": detail})

    for flow in applicable:
        load = loads.get(flow)
        if flow not in requested:
            if load is not None:
                pass  # present although not asked for in this run's record: judge it like any other
            else:
                why = lane1_unavailable() if flow == lanemod.COMBINED_FLOW else None
                if why:
                    n_a[flow] = why
                else:
                    not_run.append(flow)
                continue
        if load is None:
            add(flow, "load:missing", "no load.json: the flow was asked for and did not run", answers={flow: "no load.json"})
            continue
        status = load.get("status")
        failed = list(load.get("partitions_failed") or [])
        if status == "skipped" and flow == lanemod.COMBINED_FLOW:
            skipped.append({"flow": flow, "lane": LANE_OF[flow], "reason": load.get("reason") or "skipped"})
            continue
        for part in failed:
            add(flow, "load:partition-failed", "partition %s was not loaded" % part, [part], {flow: "failed"}, "loaded")
        if status == "ok" and not failed and not load.get("partitions_loaded"):
            add(flow, "load:status", "status ok but nothing was loaded", answers={flow: "nothing loaded"})
        elif status != "ok" and not (status == "partial" and failed):
            add(flow, "load:status", "status %s%s" % (status, (": " + load["reason"]) if load.get("reason") else ""), answers={flow: status})
    return mms, not_run, skipped, n_a


def rejected_by_loader(load):
    """Rows the PICSURELoader reported as rejected, by reason, over all partitions (the CSVLoader reports nothing)."""
    total = {}
    for s in (load.get("ingest_partitions") or {}).values():
        for reason, n in (s.get("rowsRejected") or {}).items():
            total[reason] = total.get(reason, 0) + n
    return total


def requested_flows(dataset, applicable):
    """The flows this run asked for (work/DATASET/requested-flows.json, written by `make load`); all applicable ones when not recorded."""
    path = os.path.join(WORK, dataset, "requested-flows.json")
    if not os.path.exists(path):
        return list(applicable)
    with open(path) as f:
        return [x for x in json.load(f).get("flows", [])]


def run_dataset(dataset, ports, host):
    spec = lanemod.DATASETS[dataset]
    kind = spec["kind"]
    t0 = time.time()
    # the truth
    routed = None
    if kind == "combined":
        truth, routed = pheno.truth_from_combined(os.path.join(WORK, dataset, "input", "allConcepts_data_analyzer.csv"))
    else:
        truth = pheno.truth_from_split_dir(os.path.join(WORK, dataset, "split"))
    mismatches = []

    def mm(**kw):
        kw["id"] = "m%d" % (len(mismatches) + 1)
        mismatches.append(kw)
        return kw

    # the splitter against the truth's routing
    split_info = None
    if kind == "combined":
        with open(os.path.join(WORK, dataset, "split.json")) as f:
            split_info = json.load(f)
        man = split_info.get("manifest") or {}
        got = {p["name"]: (p["rows"], p["patients"]) for p in man.get("partitions", [])}
        want = {p: (len(rows), len({int(r[0]) for r in rows})) for p, rows in routed.partitions.items()}
        if got != want:
            mm(category="splitter-vs-truth", kind="splitter", partitions=[], lanes=[], concept=None, query="partition rows/patients",
               truth=want, answers={"splitter": got}, detail="split_manifest.json disagrees with the independent routing")
        rej = (man.get("rows") or {}).get("rejected")
        if rej != len(routed.rejected):
            mm(category="splitter-vs-truth", kind="splitter", partitions=[], lanes=[], concept=None, query="rejected rows",
               truth=len(routed.rejected), answers={"splitter": rej}, detail="quarantined row count differs")

    # the lanes
    loads = OrderedDict()
    for flow in lanemod.FLOW_NAMES:
        lj = os.path.join(WORK, dataset, flow, "load.json")
        loads[flow] = None
        if os.path.exists(lj):
            with open(lj) as f:
                loads[flow] = json.load(f)
    applicable = lanemod.applicable_flows(dataset)
    requested = requested_flows(dataset, applicable)
    load_mms, not_run, skipped, not_applicable = check_loads(applicable, requested, loads, lanemod.lane1_unavailable)
    for m in load_mms:
        mm(**m)
    lanes = OrderedDict()
    for flow, load in loads.items():
        if load is not None:
            lanes[flow] = {"flow": flow, "lane": LANE_OF[flow], "load": load, "status": load.get("status")}
    active = OrderedDict((f, e) for f, e in lanes.items() if e["status"] in ("ok", "partial") and e["load"].get("partitions_loaded"))

    # columnMeta
    lane_meta = OrderedDict()
    for flow, e in active.items():
        store = os.path.join(WORK, dataset, flow, "store")
        lane_meta[flow] = {}
        for part in e["load"]["partitions_loaded"]:
            p = os.path.join(store, part, "columnMeta.csv")
            lane_meta[flow][part] = pheno.parse_column_meta(p) if os.path.exists(p) else {}
        loaded = set(e["load"]["partitions_loaded"])
        for part in sorted(set(truth) - loaded - set(e["load"].get("partitions_failed", []))):
            mm(category="partition-missing", kind="partition", partitions=[part], lanes=[flow], concept=None, query="partition %s" % part,
               truth="loaded", answers={flow: "absent"}, detail="the lane has no store for this partition")
        for part in sorted(loaded - set(truth)):
            mm(category="partition-extra", kind="partition", partitions=[part], lanes=[flow], concept=None, query="partition %s" % part,
               truth="not in the input", answers={flow: "present"}, detail="the lane loaded a partition the input does not have")
        for part in sorted(loaded & set(truth)):
            for path, what, want, got in meta_vs_truth(flow, part, lane_meta[flow][part], truth[part]):
                mm(category="columnMeta-vs-truth", kind="meta:" + what, partitions=[part], lanes=[flow], concept=path, query="columnMeta " + what,
                   truth=want, answers={flow: got}, detail="")
    flows = list(lane_meta)
    for i in range(len(flows)):
        for j in range(i + 1, len(flows)):
            a, b = flows[i], flows[j]
            for part in sorted(set(lane_meta[a]) & set(lane_meta[b])):
                for path, fields, ma, mb in meta_between_lanes(lane_meta[a][part], lane_meta[b][part]):
                    mm(category="columnMeta-lane-diff", kind="meta-lane-diff", fields=fields, partitions=[part], lanes=[a, b], concept=path,
                       query="columnMeta %s" % ",".join(fields), truth=None, answers={a: ma and {k: ma[k] for k in fields if k in ma},
                                                                                  b: mb and {k: mb[k] for k in fields if k in mb}},
                       detail="")

    # the queries
    partitions = set(truth)
    for flow in active:
        partitions |= set(active[flow]["load"]["partitions_loaded"])
    battery = build_battery(truth, lane_meta, partitions)
    qstats = OrderedDict()
    answers = {}
    for flow, e in active.items():
        base = "http://%s:%d" % (host, ports[e["lane"]])
        hp = Hpds(base)
        health = hp.health()
        e["url"] = base
        e["health"] = health
        qstats[flow] = {"asked": 0, "answered": 0, "errors": 0, "mismatches": 0}
        if health != "UP":
            mm(category="hpds-health", kind="health", partitions=[], lanes=[flow], concept=None, query="GET /actuator/health", truth="UP",
               answers={flow: health}, detail="HPDS of %s is not healthy at %s" % (flow, base))
            continue
        with ThreadPoolExecutor(max_workers=4) as ex:
            results = list(ex.map(lambda q: hp.count(q.consents, q.clause), battery))
        answers[flow] = results
        for q, (n, err) in zip(battery, results):
            qstats[flow]["asked"] += 1
            if err:
                qstats[flow]["errors"] += 1
            else:
                qstats[flow]["answered"] += 1
    for k, q in enumerate(battery):
        per = {}
        bad = []
        for flow in answers:
            n, err = answers[flow][k]
            per[flow] = err if err else n
            if err or n != q.truth:
                bad.append(flow)
                qstats[flow]["mismatches"] += 1
        if bad:
            errored = any(answers[f][k][1] for f in bad)
            kind = ("query:error" if errored else "query:required" if q.clause["phenotypicFilterType"] == "REQUIRED"
                    else "query:values" if "values" in q.clause else "query:range")
            mm(category="query", kind=kind, values=list(q.clause.get("values") or []),
               partitions=q.consents if q.scope != "unknown-consent" else [], lanes=bad, concept=q.concept,
               scope=q.scope, query=q.desc, truth=q.truth, answers=per, detail="")

    # what the known differences may explain: only concepts the input (and for empty rows the CSVLoader's own columnMeta) shows
    evidence = {"fffd": {}, "empty_first": {}, "whitespace-nval": {}, "nonfinite": {}}
    csv_meta = lane_meta.get(CSV, {})
    for p, pt in truth.items():
        fv = {c: sorted(cc.fffd_values) for c, cc in pt.concepts.items() if cc.fffd}
        if fv:
            evidence["fffd"][p] = fv
        confirmed = [c for c in pt.empty_row_concepts()
                     if (csv_meta.get(p, {}).get(c) or {}).get("categorical") and csv_meta[p][c]["observations"] == 0]
        if confirmed:
            evidence["empty_first"][p] = confirmed
        for kind, key in ((pheno.WS_NVAL, "whitespace-nval"), (pheno.NONFINITE, "nonfinite")):
            if pt.csv_differs[kind]:
                evidence[key][p] = sorted(pt.csv_differs[kind])
    for m in mismatches:
        m["explained_by"] = explain(m, evidence)

    # evidence for the known differences
    ev = {k["id"]: {} for k in KNOWN_DIFFERENCES}
    for p, pt in truth.items():
        if pt.fffd_rows:
            ev["fffd-in-text"][p] = {"rows": pt.fffd_rows, "concepts": sorted(evidence["fffd"].get(p, {}))}
        if pt.empty_row_concepts():
            ev["csvloader-empty-row-first"][p] = {"concepts": pt.empty_row_concepts(), "confirmed_in_csvloader_columnMeta": evidence["empty_first"].get(p, [])}
        if pt.csv_differs[pheno.WS_NVAL]:
            ev["csvloader-whitespace-nval"][p] = {"rows": pt.csv_differs[pheno.WS_NVAL]}
        if pt.csv_differs[pheno.NONFINITE]:
            ev["csvloader-nonfinite-numbers"][p] = {"rows": pt.csv_differs[pheno.NONFINITE]}
        if pt.rolled_up_rows:
            ev["rollup-last-node"][p] = {"rows": pt.rolled_up_rows}
        if pt.short_rows:
            ev["short-rows"][p] = {"rows": pt.short_rows}
        if pt.no_value_rows:
            ev["no-value-rows"][p] = {"rows": pt.no_value_rows}
    for flow, e in active.items():
        for p, s in (e["load"].get("ingest_partitions") or {}).items():
            rr = s.get("rowsRejected") or {}
            if rr.get("MALFORMED_RECORD"):
                ev["short-rows"].setdefault(p, {})["picsureloader_malformed_record"] = rr["MALFORMED_RECORD"]
    if routed is not None:
        ev["splitter-quarantine"] = {
            "rejected": {r: sum(1 for x, _ in routed.rejected if x == r) for r in sorted({x for x, _ in routed.rejected})},
            "dropped_no_consent": routed.dropped,
        }
    known = []
    for k in KNOWN_DIFFERENCES:
        known.append(dict(k, evidence=ev.get(k["id"]) or None, observed=bool(ev.get(k["id"])),
                          explained_mismatches=sum(1 for m in mismatches if m["explained_by"] == k["id"])))

    lane_summary = OrderedDict()
    for flow, e in lanes.items():
        ld = e["load"]
        lane_summary[flow] = {
            "lane": e["lane"], "status": ld.get("status"), "reason": ld.get("reason"), "loader": ld.get("loader"),
            "exit_code": ld.get("exit_code"), "wall_seconds": ld.get("wall_seconds"), "store_bytes": ld.get("store_bytes"),
            "partitions_loaded": ld.get("partitions_loaded", []), "partitions_failed": ld.get("partitions_failed", []),
            "url": e.get("url"), "health": e.get("health"), "queries": qstats.get(flow),
            "rows_rejected_by_loader": rejected_by_loader(ld),
        }
    for flow in applicable:
        if flow not in lane_summary:
            if flow in not_applicable:
                lane_summary[flow] = {"lane": LANE_OF[flow], "status": "n/a", "reason": not_applicable[flow]}
            else:
                lane_summary[flow] = {"lane": LANE_OF[flow], "status": "not run"}

    report = {
        "dataset": dataset, "kind": kind, "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "wall_seconds": round(time.time() - t0, 1),
        "truth": {p: {"rows": pt.rows, "patients": len(pt.patients), "concepts": len(pt.concepts), "observations": pt.observations(),
                      "skipped_short_rows": pt.short_rows, "rows_without_value": pt.no_value_rows, "rolled_up_rows": pt.rolled_up_rows}
                  for p, pt in truth.items()},
        "split": split_info and {k: split_info.get(k) for k in ("status", "exit_code", "wall_seconds")},
        "queries_total": len(battery),
        "battery": {
            "queries": len(battery),
            "truth_nonzero": sum(1 for q in battery if q.truth > 0),
            "required": sum(1 for q in battery if q.clause["phenotypicFilterType"] == "REQUIRED"),
            "filter_values": sum(1 for q in battery if "values" in q.clause),
            "filter_range": sum(1 for q in battery if "min" in q.clause or "max" in q.clause),
            "scopes": sorted({q.scope if not q.scope.startswith("partition:") else "partition" for q in battery}),
        },
        "lanes": lane_summary,
        "requested_flows": requested,
        "not_run": not_run,
        "skipped": skipped,
        "mismatches": mismatches,
        "known_differences": known,
        "summary": {"mismatches": len(mismatches), "explained": sum(1 for m in mismatches if m["explained_by"]),
                    "unexplained": sum(1 for m in mismatches if not m["explained_by"])},
    }
    os.makedirs(REPORT_DIR, exist_ok=True)
    with open(os.path.join(REPORT_DIR, dataset + ".json"), "w") as f:
        json.dump(report, f, indent=2, sort_keys=False)
        f.write("\n")
    s = report["summary"]
    print("compare %s: %d queries x %d lanes, %d mismatches (%d explained, %d unexplained)" % (
        dataset, len(battery), len(answers), s["mismatches"], s["explained"], s["unexplained"]))
    return 0


# ----------------------------------------------------------------------------------------------------------------------------
# report


def human(n):
    n = float(n or 0)
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return "%.0f %s" % (n, unit) if unit == "B" else "%.1f %s" % (n, unit)
        n /= 1024.0


def md_escape(s):
    return str(s).replace("|", "\\|").replace("\n", " ")


def render_md(reports, summary):
    out = ["# Local stack comparison", "", "Generated %s." % time.strftime("%Y-%m-%d %H:%M:%S"), ""]
    out.append("Result: **%s**. %d unexplained mismatches, %d explained by known differences; %d of %d queries answered." % (
        pass_line(summary) if summary["pass"] else "FAIL", summary["unexplained"], summary["explained"], summary["queries_answered"],
        summary["queries_asked"]))
    out.append("")
    if summary.get("problems"):
        for pr in summary["problems"]:
            out.append("- FAIL: %s" % pr)
        out.append("")
    warns = [("not run", r["dataset"], f, "applicable, but this run asked for %s only" % ", ".join(r.get("requested_flows") or []))
             for r in reports for f in r.get("not_run") or []]
    warns += [("skipped", r["dataset"], s_["flow"], s_["reason"]) for r in reports for s_ in r.get("skipped") or []]
    if warns:
        out.append("## Warnings")
        out.append("")
        for what, ds, flow, why in warns:
            out.append("- WARN %s: %s %s (%s)" % (what, ds, flow, why))
        out.append("")
    out.append("## Lanes")
    out.append("")
    for r in reports:
        b = r.get("battery") or {}
        out.append("- %s: %d queries per lane (%d REQUIRED, %d categorical FILTER, %d numeric FILTER; %d with a non-zero truth; scopes: %s)" % (
            r["dataset"], b.get("queries", 0), b.get("required", 0), b.get("filter_values", 0), b.get("filter_range", 0),
            b.get("truth_nonzero", 0), ", ".join(b.get("scopes", []))))
    out.append("")
    out.append("| dataset | lane | flow | status | load s | store | loaded | failed | rows rejected by loader | HPDS | queries | answered | mismatches |")
    out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in reports:
        for flow, l in r["lanes"].items():
            q = l.get("queries") or {}
            rej = l.get("rows_rejected_by_loader") or {}
            out.append("| %s | %s | %s | %s | %s | %s | %d | %d | %s | %s | %s | %s | %s |" % (
                r["dataset"], l.get("lane"), flow, l.get("status") + ((" (" + l["reason"] + ")") if l.get("reason") else ""),
                l.get("wall_seconds", ""), human(l.get("store_bytes")) if l.get("store_bytes") is not None else "",
                len(l.get("partitions_loaded") or []), len(l.get("partitions_failed") or []),
                ", ".join("%s=%d" % kv for kv in sorted(rej.items())) or "-", l.get("health", ""),
                q.get("asked", ""), q.get("answered", ""), q.get("mismatches", "")))
    out.append("")
    out.append("## Mismatches")
    out.append("")
    unexplained = [(r["dataset"], m) for r in reports for m in r["mismatches"] if not m["explained_by"]]
    explained = [(r["dataset"], m) for r in reports for m in r["mismatches"] if m["explained_by"]]
    out.append("### Unexplained (%d)" % len(unexplained))
    out.append("")
    if unexplained:
        out.append("| dataset | category | partition(s) | lanes | what | truth | answers |")
        out.append("|---|---|---|---|---|---|---|")
        for ds, m in unexplained[:200]:
            out.append("| %s | %s | %s | %s | %s | %s | %s |" % (ds, m["category"], md_escape(", ".join(m.get("partitions") or [])),
                                                              ", ".join(m.get("lanes") or []), md_escape(m["query"] + (" " + m["concept"] if m.get("concept") and m["concept"] not in m["query"] else "")),
                                                              md_escape(m.get("truth")), md_escape(json.dumps(m.get("answers"), ensure_ascii=False))))
        if len(unexplained) > 200:
            out.append("")
            out.append("(%d more in compare.json)" % (len(unexplained) - 200))
    else:
        out.append("None.")
    out.append("")
    out.append("### Explained by a known difference (%d)" % len(explained))
    out.append("")
    if explained:
        out.append("| dataset | known difference | category | partition(s) | lanes | what | truth | answers |")
        out.append("|---|---|---|---|---|---|---|---|")
        for ds, m in explained[:100]:
            out.append("| %s | %s | %s | %s | %s | %s | %s | %s |" % (ds, m["explained_by"], m["category"], md_escape(", ".join(m.get("partitions") or [])),
                                                                   ", ".join(m.get("lanes") or []), md_escape(m["query"] + (" " + m["concept"] if m.get("concept") and m["concept"] not in m["query"] else "")),
                                                                   md_escape(m.get("truth")), md_escape(json.dumps(m.get("answers"), ensure_ascii=False))))
        if len(explained) > 100:
            out.append("")
            out.append("(%d more in compare.json)" % (len(explained) - 100))
    else:
        out.append("None.")
    out.append("")
    out.append("## Known differences")
    out.append("")
    seen = OrderedDict()
    for r in reports:
        for k in r["known_differences"]:
            e = seen.setdefault(k["id"], {"k": k, "datasets": OrderedDict(), "explained": 0})
            if k["observed"]:
                e["datasets"][r["dataset"]] = k["evidence"]
            e["explained"] += k["explained_mismatches"]
    for kid, e in seen.items():
        k = e["k"]
        out.append("### %s" % k["title"])
        out.append("")
        out.append("`%s`. %s" % (kid, k["description"]))
        out.append("")
        if e["datasets"]:
            out.append("Evidence in this run (%d mismatches explained):" % e["explained"])
            out.append("")
            for ds, ev in e["datasets"].items():
                out.append("- %s: `%s`" % (ds, json.dumps(ev, ensure_ascii=False, sort_keys=True)))
        else:
            out.append("Not observed in the datasets of this run.")
        out.append("")
    out.append("## Truth")
    out.append("")
    out.append("| dataset | partition | rows | patients | concepts | observations | short rows skipped | rows without value | rolled-up rows |")
    out.append("|---|---|---|---|---|---|---|---|---|")
    for r in reports:
        for p, t in r["truth"].items():
            out.append("| %s | %s | %d | %d | %d | %d | %d | %d | %d |" % (
                r["dataset"], p, t["rows"], t["patients"], t["concepts"], t["observations"], t["skipped_short_rows"],
                t["rows_without_value"], t["rolled_up_rows"]))
    out.append("")
    return "\n".join(out)


def summarize(reports, names):
    unexplained = sum(r["summary"]["unexplained"] for r in reports)
    explained = sum(r["summary"]["explained"] for r in reports)
    answered = sum((l.get("queries") or {}).get("answered", 0) for r in reports for l in r["lanes"].values())
    asked = sum((l.get("queries") or {}).get("asked", 0) for r in reports for l in r["lanes"].values())
    problems = []
    for r in reports:
        per_dataset = sum((l.get("queries") or {}).get("asked", 0) for l in r["lanes"].values())
        if per_dataset == 0:
            problems.append("%s asked 0 queries (nothing was compared)" % r["dataset"])
        for flow, l in r["lanes"].items():
            if l.get("status") == "ok" and (l.get("queries") or {}).get("asked", 0) == 0 and per_dataset:
                problems.append("%s %s loaded but asked 0 queries" % (r["dataset"], flow))
    not_run = sorted({f for r in reports for f in r.get("not_run") or []})
    skipped = []
    for r in reports:
        for s_ in r.get("skipped") or []:
            item = {"lane": s_["lane"], "flow": s_["flow"], "reason": s_["reason"]}
            if item not in skipped:
                skipped.append(item)
    return {"datasets": names, "unexplained": unexplained, "explained": explained, "queries_asked": asked, "queries_answered": answered,
            "problems": problems, "not_run": not_run, "skipped": skipped,
            "pass": unexplained == 0 and answered == asked and not problems}


def pass_line(summary):
    """`PASS` when every applicable flow ran and none was skipped; otherwise what did not: `PASS (partial: <flows> not run; lane1
    skipped: <why>)`."""
    parts = []
    if summary.get("not_run"):
        parts.append("partial: %s not run" % ", ".join(summary["not_run"]))
    for s_ in summary.get("skipped") or []:
        parts.append("%s skipped: %s" % (s_["lane"], s_["reason"]))
    return "PASS (%s)" % "; ".join(parts) if parts else "PASS"


def report(datasets):
    names = datasets or sorted(f[:-5] for f in os.listdir(REPORT_DIR) if f.endswith(".json") and f not in ("compare.json",))
    reports = []
    for n in names:
        path = os.path.join(REPORT_DIR, n + ".json")
        if not os.path.exists(path):
            sys.stderr.write("no report for %s (run compare.py run %s)\n" % (n, n))
            return 2
        with open(path) as f:
            reports.append(json.load(f))
    summary = summarize(reports, names)
    unexplained, explained, answered, asked = (summary[k] for k in ("unexplained", "explained", "queries_answered", "queries_asked"))
    with open(os.path.join(REPORT_DIR, "compare.json"), "w") as f:
        json.dump({"summary": summary, "datasets": reports}, f, indent=2)
        f.write("\n")
    with open(os.path.join(REPORT_DIR, "compare.md"), "w") as f:
        f.write(render_md(reports, summary))
    print("compare report: %s" % os.path.join(REPORT_DIR, "compare.md"))
    print("%-10s %-22s %-8s %8s %9s %-8s %7s %9s %8s" % ("dataset", "flow", "status", "load s", "store", "loaded", "queries", "answered", "mismatch"))
    for r in reports:
        for flow, l in r["lanes"].items():
            q = l.get("queries") or {}
            print("%-10s %-22s %-8s %8s %9s %-8s %7s %9s %8s" % (
                r["dataset"], flow, l.get("status"), l.get("wall_seconds", "-"), human(l.get("store_bytes")) if l.get("store_bytes") is not None else "-",
                "%d/%d" % (len(l.get("partitions_loaded") or []), len(l.get("partitions_loaded") or []) + len(l.get("partitions_failed") or [])),
                q.get("asked", "-"), q.get("answered", "-"), q.get("mismatches", "-")))
    print("mismatches: %d explained by known differences, %d unexplained; queries answered %d of %d" % (explained, unexplained, answered, asked))
    for r in reports:
        for f in r.get("not_run") or []:
            print("WARN not run: %s %s" % (r["dataset"], f))
        for s_ in r.get("skipped") or []:
            print("WARN skipped: %s %s (%s)" % (r["dataset"], s_["flow"], s_["reason"]))
    if not summary["pass"]:
        for pr in summary["problems"]:
            print("FAIL: %s" % pr)
        print("FAIL: unexplained mismatches, unanswered queries or nothing compared (see compare.md)")
        return 1
    print(pass_line(summary))
    return 0


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("dataset", choices=sorted(lanemod.DATASETS))
    r.add_argument("--host", default=os.environ.get("HPDS_HOST", "localhost"))
    for lane, port in DEFAULT_PORTS.items():
        r.add_argument("--%s-port" % lane, type=int, default=int(os.environ.get("HPDS_%s_PORT" % lane.upper(), port)))
    p = sub.add_parser("report")
    p.add_argument("datasets", nargs="*")
    a = ap.parse_args()
    if a.cmd == "run":
        return run_dataset(a.dataset, {"lane1": a.lane1_port, "lane2": a.lane2_port, "lane3": a.lane3_port}, a.host)
    return report(a.datasets)


if __name__ == "__main__":
    sys.exit(main())
