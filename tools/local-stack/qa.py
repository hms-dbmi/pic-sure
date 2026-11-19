"""Headless QA battery for the partitioned PIC-SURE stack. Standard library only; it prints and writes counts, never patient-level data.

  qa.py --hpds URL --store DIR [--open URL] [--api URL] [--dict URL] [--dataset NAME] [--manifest FILE ...] [--stigvars FILE] [--stigvar-limit N]
        [--require open,api,dict] [--sample N] [--seed N] [--report-dir DIR]

  --hpds      the HPDS that serves the partitions with the authorization filter on (queries must carry userConsents)
  --store     the partition root on this machine: one directory per partition with columnMeta.csv (the source of truth for concept counts)
  --open      the open-mode HPDS (filter off) over the same partitions, with or without per-partition open columnMeta files
  --api       the query-service in front of --hpds (POST /hpds/auth/v3/query/sync, X-User-Id)
  --dict      the dictionary service (POST /concepts with a consent filter)
  --dataset   a synthetic dataset of the local stack: its ground truth (pheno.py, as compare.py computes it) is a further source of truth
  --manifest  a loader manifest (split_manifest.json, a flow manifest.json, ingest-summary.json): partitions[] with name and patients
              and/or observations (or observationsWritten); repeatable
  --stigvars  a file of concept paths (one per line), matched exactly: no listed path may appear in the open-mode HPDS's search, and no
              listed concept of the partitions may answer a query there. A path below a listed path is not listed. Without the file the
              open stigvar check is INCONCLUSIVE
  --stigvar-limit  how many listed paths that are in the partitions' columnMeta are searched and queried (default 200, an evenly spaced
              sample of the sorted paths; 0 = all); the calls stay bounded for a list of 100k+ entries. Listed paths in no partition are
              one informational row. The coverage (listed / in the partitions / checked) is reported as the `coverage` row
  --columnmeta-root  <partition>/columnMeta.csv from another place (the flow prefix of a release's partitions/, which has none); the
              dictionary path checks are INCONCLUSIVE for a study whose partitions have no columnMeta.csv
  --require   checks a missing target must not skip (open, api, dict, stigvar): the target missing is then a setup problem (exit 2)

Checks (each one is reported per partition or per case, as PASS, FAIL, INCONCLUSIVE or SKIP):
  partition.patients      COUNT with userConsents=[partition] and no clause = the source of truth (truth, manifest; else the \\_consents\\ concept)
  partition.observations  the sum of the observation counts of columnMeta.csv = the manifest / truth observation total
  concept.sample          for sampled concepts of a partition: REQUIRED count = columnMeta patientCount, observation count (OBSERVATION_CROSS_COUNT)
                          = columnMeta observationCount
  union                   a patient of two consents is counted once (exact with --dataset, bounds and identities otherwise)
  consent.missing         a consent with no partition counts 0 (alone and next to a real one)
  consent.none            a query without consents is refused (no count is returned)
  open.*                  the open HPDS with no consents = every partition; with consents = those partitions; unknown consent = 0
  stigvar.open            no listed path (exact match) in the open HPDS's search, no listed concept answers there; one coverage row
  stigvar.control         the authorized HPDS does have the checked listed paths (INCONCLUSIVE when none can be checked, or one is missing)
  api.same                the query-service answers what the HPDS answers, query by query
  dict.consent            the dictionary's concepts for a consent filter belong to that consent's study (or are global/harmonized)
  dict.study_paths        per-study dictionary concept paths are a subset of the study's partition columnMeta paths
  dict.global_paths       global / harmonized dictionary paths (not under a study), compared separately, against the same partitions
  dict.unknown            a consent with no partition and no dataset returns no dictionary concepts

Exit 0 = every check PASS (SKIPs allowed), 1 = any FAIL, 2 = no FAIL but a check was INCONCLUSIVE or the setup was not usable.
Reports: DIR/qa.json and DIR/qa.tsv (default work/report/qa).

Provenance: the first lines name the commit of every service that is running (`services: hpds=<sha> query=<sha> dict=<sha>`, read from the
`local-stack.services-sha` label of the image of each running container; work/services.json, written by the make build, is only a cross-check
and a difference is printed as a warning), the tooling commit at this moment, and `tests:`, which names csvloader-partition-names only when
every running service is at that ref's commit (with `(tooling <sha>-dirty)` when tools/local-stack has uncommitted changes). `loader=<sha>` is
added when the run used a generated open columnMeta, and a loader at another commit then takes the label away. The same goes into qa.json
under summary.provenance.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import compare
import pheno
import services_ref

PASS, FAIL, INCONCLUSIVE, SKIP = "PASS", "FAIL", "INCONCLUSIVE", "SKIP"
UNKNOWN_CONSENT = "unknown.c9"
API_QUERY_PATH = "/hpds/auth/v3/query/sync"
MAX_SHOWN = 3  # failures of one check listed on the console, and paths shown in a detail


# ----------------------------------------------------------------------------------------------------------------------------
# clients


class Client(compare.Hpds):
    """compare.Hpds plus what the battery needs: raw answers, observation counts and search."""

    def post(self, path, body, timeout=60):
        req = urllib.request.Request(self.base_url + path, data=json.dumps(body).encode(), method="POST",
                                     headers=dict(self.headers, **{"Content-Type": "application/json"}))
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read().decode(errors="replace")
        except urllib.error.HTTPError as e:
            return e.code, e.read().decode(errors="replace")
        except Exception as e:  # noqa: BLE001
            return None, str(e)

    def query(self, consents, clause=None, result="COUNT", select=None):
        q = {"userConsents": [{"value": c} for c in consents], "expectedResultType": result}
        if clause is not None:
            q["phenotypicClause"] = clause
        if select is not None:
            q["select"] = list(select)
        return self.post(self.path, {"query": q})

    def patients(self, consents, clause=None):
        """(count, None) or (None, why): None when the answer is not a count."""
        code, text = self.query(consents, clause)
        try:
            return int(text.strip()), None
        except ValueError:
            return None, f"HTTP {code}, not a count (body {len(text.strip())} bytes)"

    def observations(self, consents, concept):
        """The observation count of one concept over the patients of the consents, or (None, why)."""
        code, text = self.query(consents, None, "OBSERVATION_CROSS_COUNT", [concept])
        try:
            v = json.loads(text)[concept]
        except (ValueError, KeyError, TypeError):
            return None, f"HTTP {code}, no count for the concept"
        return (v, None) if isinstance(v, int) and v >= 0 else (None, f"observation count {v}")

    def search(self, term):
        """The concept paths a search for `term` returns, or None when the call failed."""
        _code, text = self.post("/PIC-SURE/v3/search", {"query": term})
        try:
            return set(json.loads(text)["results"]["phenotypes"])
        except (ValueError, KeyError, TypeError):
            return None


def api_client(base, user_id):
    c = Client(base, path=API_QUERY_PATH, headers={"X-User-Id": user_id})
    return c


def dict_concepts(base, consents, page_size, max_pages):
    """(list of (dataset, path), total, truncated) from POST /concepts with a consent filter. Raises OSError when the service fails."""
    out, total, page = [], 0, 0
    while page < max_pages:
        req = urllib.request.Request(f"{base.rstrip('/')}/concepts?page_number={page}&page_size={page_size}",
                                     data=json.dumps({"consents": list(consents), "facets": [], "search": ""}).encode(), method="POST",
                                     headers={"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.loads(r.read().decode())
        except Exception as e:
            raise OSError(f"dictionary /concepts failed: {e}") from e
        total = data.get("totalElements", 0)
        content = data.get("content") or []
        out += [(c.get("dataset"), c.get("conceptPath")) for c in content]
        page += 1
        if len(out) >= total or not content:
            break
    return out, total, len(out) < total


# ----------------------------------------------------------------------------------------------------------------------------
# sources of truth


def read_manifest(path):
    """partition -> {"patients": n, "observations": n} from a loader manifest (only the keys it has)."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    parts = data.get("partitions", [])
    if isinstance(parts, dict):
        parts = [dict(v, name=k) for k, v in parts.items()]
    out = {}
    for p in parts:
        name = p.get("name")
        if not name:
            continue
        e = {}
        if isinstance(p.get("patients"), int):
            e["patients"] = p["patients"]
        for k in ("observations", "observationsWritten"):
            if isinstance(p.get(k), int):
                e["observations"] = p[k]
        out[name] = e
    return out


def read_store(store):
    """partition -> parsed columnMeta.csv (concept -> {observations, patients, ...}); partitions without the file map to None."""
    meta = {}
    if not os.path.isdir(store):
        return meta
    for name in sorted(os.listdir(store)):
        d = os.path.join(store, name)
        if not os.path.isdir(d):
            continue
        f = os.path.join(d, "columnMeta.csv")
        meta[name] = pheno.parse_column_meta(f) if os.path.isfile(f) else None
    return meta


def expectations(partitions, meta, manifests, truth):
    """partition -> {"patients": [(source, n)], "observations": [(source, n)]}."""
    exp = {}
    for p in partitions:
        e = {"patients": [], "observations": []}
        if truth and p in truth:
            e["patients"].append(("truth", len(truth[p].patients)))
            e["observations"].append(("truth", truth[p].observations()))
        for name, m in manifests:
            if p in m:
                for k in ("patients", "observations"):
                    if k in m[p]:
                        e[k].append((name, m[p][k]))
        if not e["patients"] and meta.get(p) and pheno.CONSENTS_PATH in meta[p]:
            e["patients"].append(("columnMeta \\_consents\\", meta[p][pheno.CONSENTS_PATH]["patients"]))
        exp[p] = e
    return exp


def read_stigvars(path):
    """The listed concept paths as a set: every lookup is exact and O(1), however long the list is."""
    with open(path, encoding="utf-8") as f:
        return frozenset(line.rstrip("\r\n") for line in f if line.strip() and not line.startswith("#"))


def listed(path, stigvars):
    """Exact match: only a path equal to a listed path is listed. A path below a listed path is not."""
    return path in stigvars


def spread(items, limit):
    """At most `limit` of the sorted items, evenly spaced: deterministic, and the same list gives the same sample. limit 0 = all."""
    items = sorted(items)
    if limit <= 0 or len(items) <= limit:
        return items
    return [items[i * len(items) // limit] for i in range(limit)]


# ----------------------------------------------------------------------------------------------------------------------------
# the battery


class Report:
    def __init__(self):
        self.results = []

    def add(self, check, case, status, detail="", expected=None, got=None):
        self.results.append({"check": check, "case": case, "status": status, "detail": detail, "expected": expected, "got": got})

    def eq(self, check, case, expected, got, why=None):
        if got is None:
            self.add(check, case, FAIL, why or "no count", expected, got)
        elif got == expected:
            self.add(check, case, PASS, "", expected, got)
        else:
            self.add(check, case, FAIL, f"expected {expected}, got {got}", expected, got)

    def counts(self):
        c = {PASS: 0, FAIL: 0, INCONCLUSIVE: 0, SKIP: 0}
        for r in self.results:
            c[r["status"]] += 1
        return c

    def exit_code(self):
        c = self.counts()
        return 1 if c[FAIL] else 2 if c[INCONCLUSIVE] else 0


def check_partitions(rep, hpds, parts, meta, exp):
    for p in parts:
        got, why = hpds.patients([p])
        sources = exp[p]["patients"]
        if not sources:
            rep.add("partition.patients", p, INCONCLUSIVE, "no source of truth for the patient count (no truth, manifest or \\_consents\\ concept)", None, got)
        elif got is None:
            rep.add("partition.patients", p, FAIL, why, [n for _, n in sources], got)
        elif all(n == got for _, n in sources):
            rep.add("partition.patients", p, PASS, "", got, got)
        else:
            rep.add("partition.patients", p, FAIL, "expected " + ", ".join(f"{s}={n}" for s, n in sources) + f"; HPDS {got}", [n for _, n in sources], got)
        m = meta.get(p)
        sources = exp[p]["observations"]
        if m and sources:
            total = sum(c["observations"] for c in m.values())
            bad = [f"{s}={n}" for s, n in sources if n != total]
            rep.add("partition.observations", p, FAIL if bad else PASS, ("columnMeta.csv total " + str(total) + " differs from " + ", ".join(bad)) if bad else "", total if not bad else [n for _, n in sources], total)
        else:
            rep.add("partition.observations", p, SKIP, "no observation total to compare with" if m else "no columnMeta.csv")


def sample_concepts(parts, meta, n, rng):
    out = {}
    for p in parts:
        paths = sorted(meta.get(p) or {})
        out[p] = rng.sample(paths, min(n, len(paths)))
    return out


def check_concepts(rep, hpds, parts, meta, sample):
    for p in parts:
        if not meta.get(p):
            rep.add("concept.sample", p, INCONCLUSIVE, "no columnMeta.csv to sample concepts from")
            continue
        for path in sample[p]:
            m = meta[p][path]
            got, why = hpds.patients([p], compare.required(path))
            rep.eq("concept.sample", f"{p} patients {path}", m["patients"], got, why)
            got, why = hpds.observations([p], path)
            rep.eq("concept.sample", f"{p} observations {path}", m["observations"], got, why)


def pick_pairs(parts, truth, limit, rng):
    pairs = [(a, b) for i, a in enumerate(parts) for b in parts[i + 1:]]
    if len(pairs) <= limit:
        return pairs
    chosen = []
    if truth:  # prefer pairs that share a patient: that is where double counting would show
        shared = [pr for pr in pairs if truth[pr[0]].patients & truth[pr[1]].patients]
        chosen += rng.sample(shared, min(limit // 2 + 1, len(shared)))
    rest = [pr for pr in pairs if pr not in chosen]
    return chosen + rng.sample(rest, min(limit - len(chosen), len(rest)))


def check_union(rep, hpds, parts, meta, truth, limit, rng):
    if len(parts) < 2:
        rep.add("union", "all", INCONCLUSIVE, "fewer than two partitions")
        return
    shared_seen = 0
    for a, b in pick_pairs(parts, truth, limit, rng):
        case = f"{a} + {b}"
        got, why = hpds.patients([a, b])
        got_rev, _ = hpds.patients([b, a])
        pa, _ = hpds.patients([a])
        pb, _ = hpds.patients([b])
        if None in (got, got_rev, pa, pb):
            rep.add("union", case, FAIL, why or "a count was not answered", None, got)
            continue
        if truth and a in truth and b in truth:
            overlap = len(truth[a].patients & truth[b].patients)
            shared_seen += overlap > 0
            rep.eq("union", f"{case} exact (shared {overlap})", len(truth[a].patients | truth[b].patients), got)
            common = sorted(set(meta.get(a) or {}) & set(meta.get(b) or {}) & set(truth[a].concepts) & set(truth[b].concepts))
            if common:
                c = common[0]
                want = len(truth[a].concepts[c].patients | truth[b].concepts[c].patients)
                g, why = hpds.patients([a, b], compare.required(c))
                rep.eq("union", f"{case} exact on {c}", want, g, why)
        else:
            ok = max(pa, pb) <= got <= pa + pb
            rep.add("union", f"{case} bounds", PASS if ok else FAIL, "" if ok else f"{pa} and {pb} patients, union {got}", [max(pa, pb), pa + pb], got)
        rep.eq("union", f"{case} order", got, got_rev)
    dup, why = hpds.patients([parts[0], parts[0]])
    one, _ = hpds.patients([parts[0]])
    rep.eq("union", f"{parts[0]} twice", one, dup, why)
    allp, why = hpds.patients(parts)
    if truth:
        everyone = set().union(*(truth[p].patients for p in parts if p in truth))
        rep.eq("union", "all partitions exact", len(everyone), allp, why)
    elif allp is not None:
        singles = [hpds.patients([p])[0] for p in parts[:50]]
        ok = None not in singles and max(singles) <= allp
        rep.add("union", "all partitions bounds", PASS if ok else FAIL, "" if ok else f"all {allp}, largest single {max(s for s in singles if s is not None)}", None, allp)
    else:
        rep.add("union", "all partitions", FAIL, why)
    if truth and not shared_seen:
        rep.add("union", "shared patients", INCONCLUSIVE, "no picked pair shares a patient: double counting could not show")


def check_missing(rep, hpds, parts, sample):
    if UNKNOWN_CONSENT in parts:
        rep.add("consent.missing", "setup", INCONCLUSIVE, f"{UNKNOWN_CONSENT} is a partition")
        return
    got, why = hpds.patients([UNKNOWN_CONSENT])
    rep.eq("consent.missing", "no clause", 0, got, why)
    p = parts[0]
    one, _ = hpds.patients([p])
    both, why = hpds.patients([p, UNKNOWN_CONSENT])
    if one is None:
        rep.add("consent.missing", f"{p} + unknown", FAIL, "the real consent was not answered")
    else:
        rep.eq("consent.missing", f"{p} + unknown", one, both, why)
    for path in sample.get(p, [])[:1]:
        got, why = hpds.patients([UNKNOWN_CONSENT], compare.required(path))
        rep.eq("consent.missing", "REQUIRED concept", 0, got, why)
    # no consents at all: refused
    for case, clause in (("no clause", None), ("REQUIRED concept", compare.required(sample[p][0]) if sample.get(p) else None)):
        code, text = hpds.query([], clause)
        refused, why = refusal(code, text)
        rep.add("consent.none", case, PASS if refused else FAIL, why)


def refusal(code, text):
    """(True, note) for a clean refusal of a query without consents: an answer, not a count, not a server error, not a locked resource.
    A connection error, a 5xx or the 403 "Resource is locked" (no key loaded) say nothing about the consent rule."""
    if code is None:
        return False, f"no answer: {text[:80]}"
    if code >= 500:
        return False, f"HTTP {code}: a server error is not a refusal"
    if "locked" in text.lower():
        return False, f"HTTP {code}: the resource is locked (no key), which says nothing about consents"
    try:
        int(text.strip())
        return False, f"HTTP {code}: a query without consents was answered with a count"
    except ValueError:
        return True, f"HTTP {code}, no count"


def check_open(rep, hpds, opn, parts, sample, stigvars):
    skip = [p for p in parts]
    plain = {p: [c for c in sample.get(p, []) if not listed(c, stigvars)] for p in skip}
    allp, why_a = hpds.patients(parts)
    got, why = opn.patients([])
    rep.eq("open.none", "no consents, no clause = all partitions", allp, got, why or why_a)
    for p in parts:
        want, _ = hpds.patients([p])
        got, why = opn.patients([p])
        rep.eq("open.consents", f"{p} no clause", want, got, why)
    for p in parts:
        for c in plain[p][:1]:
            want, _ = hpds.patients([p], compare.required(c))
            got, why = opn.patients([p], compare.required(c))
            rep.eq("open.consents", f"{p} REQUIRED {c}", want, got, why)
    firsts = [(p, plain[p][0]) for p in parts if plain[p]]
    for p, c in firsts[:5]:
        want, _ = hpds.patients(parts, compare.required(c))
        got, why = opn.patients([], compare.required(c))
        rep.eq("open.none", f"no consents REQUIRED {c}", want, got, why)
    got, why = opn.patients([UNKNOWN_CONSENT])
    rep.eq("open.consents", "unknown consent", 0, got, why)


def check_stigvars(rep, hpds, opn, stigvars, parts, meta, limit=200):
    """Listed paths are matched exactly (a path below a listed path is not listed). Of the listed paths that are in the partitions' columnMeta,
    at most `limit` (evenly spaced in sorted order, 0 = all) are checked: each must not be in the open search (the search is the whole
    path, and only that exact path counts as a hit), and the open HPDS must count nothing on it. The authorized HPDS is the control: it must
    have the checked paths, else the check proves nothing for them (INCONCLUSIVE). One summary row reports the coverage, with the listed paths
    that are in no partition (informational)."""
    have_meta = any(meta.get(p) for p in parts)
    in_parts = {c for p in parts for c in (meta.get(p) or {}) if listed(c, stigvars)}
    if have_meta:
        candidates, absent = in_parts, len(stigvars) - len(in_parts)
    else:
        candidates, absent = stigvars, 0
        rep.add("stigvar.open", "concepts of the partitions", INCONCLUSIVE, "no columnMeta.csv to find the listed concepts in: a sample of the list was searched")
    checked = spread(candidates, limit)
    coverage = f"{len(stigvars)} listed, {len(in_parts)} in the partitions, {absent} in no partition (not checked), {len(checked)} checked"
    if len(checked) < len(candidates):
        coverage += f": an evenly spaced sample of the sorted paths, --stigvar-limit {limit} (0 = all)"
    for s in checked:
        hits = opn.search(s)
        if hits is None:
            rep.add("stigvar.open", f"search {s}", INCONCLUSIVE, "the open search did not answer")
        else:
            bad = s in hits
            rep.add("stigvar.open", f"search {s}", FAIL if bad else PASS, "the listed path is in the open search" if bad else "", 0, int(bad))
        control = hpds.search(s)
        if control is None:
            rep.add("stigvar.control", s, INCONCLUSIVE, "the authorized search did not answer")
        elif s in control:
            rep.add("stigvar.control", s, PASS, "the path is in the authorized search")
        else:
            rep.add("stigvar.control", s, INCONCLUSIVE, "the listed path is not in the authorized search: the open check proves nothing for it")
        if have_meta:
            got, _ = opn.patients([], compare.required(s))
            if got is None:
                rep.add("stigvar.open", f"query {s}", INCONCLUSIVE, "the open HPDS did not answer the query")
            else:
                rep.add("stigvar.open", f"query {s}", FAIL if got else PASS, f"the open HPDS counted {got} patients on a listed concept" if got else "", 0, got)
    if not checked:
        rep.add("stigvar.control", "listed paths", INCONCLUSIVE, f"no listed path to check ({coverage}): the open check proves nothing")
    rep.add("stigvar.open", "coverage", SKIP, coverage)


def check_api(rep, hpds, api, parts, meta, sample):
    cases = []
    for p in parts[:5]:
        cases.append((f"{p} no clause", [p], None, None))
        for c in sample.get(p, [])[:1]:
            cases.append((f"{p} REQUIRED {c}", [p], compare.required(c), None))
            cases.append((f"{p} observations {c}", [p], None, c))
    if len(parts) >= 2:
        cases.append(("two partitions", parts[:2], None, None))
    cases.append(("all partitions", parts, None, None))
    cases.append(("unknown consent", [UNKNOWN_CONSENT], None, None))
    cases.append(("no consents", [], None, None))
    for name, consents, clause, obs in cases:
        if obs:
            want, _ = hpds.observations(consents, obs)
            got, why = api.observations(consents, obs)
        else:
            want, _why_w = hpds.patients(consents, clause)
            got, why = api.patients(consents, clause)
        if want is None:  # the HPDS refused: the API must not turn that into a count
            rep.add("api.same", name, PASS if got is None else FAIL, "" if got is None else f"HPDS did not answer a count, the API answered {got}", want, got)
        else:
            rep.eq("api.same", name, want, got, why)


def check_dictionary(rep, base, parts, meta, page_size, max_pages, all_parts=None):
    """parts: the partitions under test; all_parts: every partition (default: parts): studies, and so what counts as another study's
    concept, are classified against all of them, so a sampled run does not take a study it did not sample for a global dataset."""
    studies = {}
    for p in all_parts or parts:
        studies.setdefault(pheno.study_of(p), []).append(p)
    paths_of = {s: set().union(*[set(meta.get(p) or {}) for p in ps]) for s, ps in studies.items()}
    no_meta = {s: [p for p in ps if not meta.get(p)] for s, ps in studies.items()}
    try:
        for s in sorted({pheno.study_of(p) for p in parts}):
            ps = studies[s]
            study_concepts, global_concepts, leaks, total, trunc = [], [], 0, 0, False
            fetched, total, trunc = dict_concepts(base, ps, page_size, max_pages)
            for ds, path in fetched:
                if ds == s:
                    study_concepts.append(path)
                elif ds in studies:
                    leaks += 1
                else:
                    global_concepts.append(path)
            note = f" (sampled {len(fetched)} of {total})" if trunc else ""
            rep.add("dict.consent", s, FAIL if leaks else PASS if study_concepts else INCONCLUSIVE,
                    f"{leaks} concept(s) of another study returned for the consents of {s}" if leaks else "" if study_concepts else f"no dictionary concept for {s}", 0, leaks)
            if no_meta[s]:
                why = f"no columnMeta.csv for {len(no_meta[s])} of {len(ps)} partitions of {s} (a release's partitions/ has none): give --columnmeta-root"
                rep.add("dict.study_paths", s, INCONCLUSIVE, why)
                rep.add("dict.global_paths", s, INCONCLUSIVE, why)
                for p in ps:
                    fetched, _, _ = dict_concepts(base, [p], page_size, 1)
                    other = sum(1 for ds, _ in fetched if ds != s and ds in studies)
                    rep.add("dict.consent", p, FAIL if other else PASS, f"{other} concept(s) of another study" if other else "", 0, other)
                continue
            missing = sorted(set(study_concepts) - paths_of[s])
            rep.add("dict.study_paths", s, FAIL if missing else PASS if study_concepts else INCONCLUSIVE,
                    (f"{len(missing)} of {len(set(study_concepts))} dictionary paths are not in the partitions: " + "; ".join(missing[:MAX_SHOWN])) if missing else ("" if study_concepts else "no dictionary concepts") + note,
                    0, len(missing))
            gmissing = sorted(set(global_concepts) - paths_of[s])
            if global_concepts:
                rep.add("dict.global_paths", s, FAIL if gmissing else PASS,
                        (f"{len(gmissing)} of {len(set(global_concepts))} global paths are not in the partitions of {s}: " + "; ".join(gmissing[:MAX_SHOWN])) if gmissing else note,
                        0, len(gmissing))
            else:
                rep.add("dict.global_paths", s, SKIP, "the dictionary returned no global or harmonized concept for the consents of " + s)
            for p in ps:  # a single consent: only that study's (and global) concepts
                fetched, _, _ = dict_concepts(base, [p], page_size, 1)
                other = sum(1 for ds, _ in fetched if ds != s and ds in studies)
                rep.add("dict.consent", p, FAIL if other else PASS, f"{other} concept(s) of another study" if other else "", 0, other)
        fetched, _, _ = dict_concepts(base, [UNKNOWN_CONSENT], page_size, 1)
        rep.add("dict.unknown", UNKNOWN_CONSENT, FAIL if fetched else PASS, f"{len(fetched)} concept(s) returned for a consent with no partition" if fetched else "", 0, len(fetched))
    except OSError as e:
        rep.add("dict.consent", "service", INCONCLUSIVE, str(e))


# ----------------------------------------------------------------------------------------------------------------------------


def write_reports(rep, summary, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "qa.json"), "w", encoding="utf-8") as f:
        json.dump({"summary": summary, "results": rep.results}, f, indent=1, sort_keys=True)
        f.write("\n")
    with open(os.path.join(out_dir, "qa.tsv"), "w", encoding="utf-8") as f:
        f.write("status\tcheck\tcase\texpected\tgot\tdetail\n")
        for r in rep.results:
            cells = [r["status"], r["check"], r["case"], r["expected"], r["got"], r["detail"]]
            f.write("\t".join(str(x).replace("\t", " ").replace("\n", " ") if x is not None else "" for x in cells) + "\n")


def print_summary(rep, out_dir, verbose):
    by = {}
    for r in rep.results:
        by.setdefault(r["check"], {PASS: 0, FAIL: 0, INCONCLUSIVE: 0, SKIP: 0})[r["status"]] += 1
    for check in sorted(by):
        c = by[check]
        verdict = FAIL if c[FAIL] else INCONCLUSIVE if c[INCONCLUSIVE] else PASS if c[PASS] else SKIP
        print(f"{verdict:12} {check:24} pass {c[PASS]}, fail {c[FAIL]}, inconclusive {c[INCONCLUSIVE]}, skip {c[SKIP]}")
    for status in (FAIL, INCONCLUSIVE):
        shown = [r for r in rep.results if r["status"] == status]
        for r in shown[: (len(shown) if verbose else MAX_SHOWN * 4)]:
            print(f"  {status} {r['check']} {r['case']}: {r['detail']}")
        if len(shown) > MAX_SHOWN * 4 and not verbose:
            print(f"  ... {len(shown) - MAX_SHOWN * 4} more {status} in {out_dir}/qa.tsv")


def run(args, inspect=services_ref.inspect_container, env_inspect=compare.docker_inspect_env):
    t0 = time.time()
    rep = Report()
    rng = random.Random(args.seed)
    hpds = Client(args.hpds)
    health = hpds.health()
    if health != "UP":
        rep.add("setup", "hpds", INCONCLUSIVE, f"the HPDS at {args.hpds} is {health}")
        return rep, {"setup": "hpds not up"}
    meta = read_store(args.store)
    if not meta:
        rep.add("setup", "store", INCONCLUSIVE, f"no partitions under {args.store}")
        return rep, {"setup": "no partitions"}
    if args.columnmeta_root:  # columnMeta.csv from elsewhere (a release's partitions/ has none): same partition names
        other = read_store(args.columnmeta_root)
        meta = {p: other.get(p) for p in meta}
        if not any(meta.values()):
            rep.add("setup", "columnmeta-root", INCONCLUSIVE, f"no columnMeta.csv for any partition under {args.columnmeta_root}")
    all_parts = sorted(meta)
    parts = all_parts[: args.max_partitions] if args.max_partitions else all_parts
    truth = None
    manifests = [(os.path.basename(m), read_manifest(m)) for m in args.manifest]
    if args.dataset:
        import dictgen

        try:
            truth = dictgen.load_truth(args.dataset)
        except SystemExit as e:
            rep.add("setup", "truth", INCONCLUSIVE, str(e))
        split = os.path.join(compare.WORK, args.dataset, "split", "split_manifest.json")
        if os.path.isfile(split):
            manifests.append(("split_manifest.json", read_manifest(split)))
    exp = expectations(parts, meta, manifests, truth)
    sample = sample_concepts(parts, meta, args.sample, rng)
    stigvars = read_stigvars(args.stigvars) if args.stigvars else []

    for name, url in (("open", args.open), ("api", args.api), ("dict", args.dict)):
        if not url and name in args.require:
            rep.add("setup", name, INCONCLUSIVE, f"--{name} is required and not given (the layer is not up)")
    opn = Client(args.open) if args.open else None
    api = api_client(args.api, args.user_id) if args.api else None
    for name, c in (("open", opn), ("api", api)):
        if c is not None and c.health() != "UP":
            rep.add("setup", name, INCONCLUSIVE, f"the {name} service at {c.base_url} is not UP")
            if name == "open":
                opn = None
            else:
                api = None

    for name, why in target_problems(args, inspect, env_inspect):  # asked about another store than the one it serves: no comparison is meaningful
        rep.add("setup", name, INCONCLUSIVE, why)
        if name == "open":
            opn = None
        else:
            api = None

    check_partitions(rep, hpds, parts, meta, exp)
    check_concepts(rep, hpds, parts, meta, sample)
    check_union(rep, hpds, parts, meta, truth, args.union_pairs, rng)
    check_missing(rep, hpds, parts, sample)
    if opn:
        check_open(rep, hpds, opn, parts, sample, stigvars)
        if stigvars:
            check_stigvars(rep, hpds, opn, stigvars, parts, meta, args.stigvar_limit)
        else:
            rep.add("stigvar.open", "list", INCONCLUSIVE, "no --stigvars file: nothing says what the open HPDS must not serve")
    else:
        rep.add("open.none", "open HPDS", SKIP, "no open-mode HPDS (--open)")
        if "stigvar" in args.require:
            rep.add("setup", "stigvar", INCONCLUSIVE, "--require stigvar and there is no open-mode HPDS to check")
    if api:
        check_api(rep, hpds, api, parts, meta, sample)
    else:
        rep.add("api.same", "query-service", SKIP, "no query-service (--api)")
    if args.dict:
        check_dictionary(rep, args.dict, parts, meta, args.dict_page_size, args.dict_max_pages, all_parts)
    else:
        rep.add("dict.consent", "dictionary", SKIP, "no dictionary (--dict)")
    return rep, {"partitions": len(parts), "sources": [n for n, _ in manifests] + (["truth"] if truth else []), "seconds": round(time.time() - t0, 1)}


def parse(argv):
    ap = argparse.ArgumentParser(description="Headless QA battery for the partitioned PIC-SURE stack (counts only).")
    ap.add_argument("--hpds", required=True)
    ap.add_argument("--store", required=True)
    ap.add_argument("--open")
    ap.add_argument("--api")
    ap.add_argument("--dict")
    ap.add_argument("--dataset")
    ap.add_argument("--manifest", action="append", default=[])
    ap.add_argument("--stigvars")
    ap.add_argument("--stigvar-limit", type=int, default=200, help="listed paths searched and queried (evenly spaced in sorted order); 0 = every listed path of the partitions")
    ap.add_argument("--columnmeta-root", help="a directory with <partition>/columnMeta.csv when --store has none (a release's partitions/)")
    ap.add_argument("--hpds-container", help="the container of --hpds, whose image label says what it serves (default: every HPDS container that is running)")
    ap.add_argument("--loader-jar-file", help="a file naming the RemoveConceptFromMetadata jar that made the open columnMeta of this run")
    ap.add_argument("--require", default="")
    ap.add_argument("--user-id", default=compare.DEFAULT_API_USER)
    ap.add_argument("--sample", type=int, default=5, help="concepts sampled per partition")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--max-partitions", type=int, default=0, help="0 = every partition")
    ap.add_argument("--union-pairs", type=int, default=6)
    ap.add_argument("--dict-page-size", type=int, default=500)
    ap.add_argument("--dict-max-pages", type=int, default=200)
    ap.add_argument("--report-dir", default=os.path.join(compare.WORK, "report", "qa"))
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args(argv)
    args.require = {x.strip() for x in args.require.split(",") if x.strip()}
    return args


OPEN_CONTAINER = "picsure-local-hpds-open"
QUERY_CONTAINER = "picsure-local-query"


def own_lane(args):
    """The store (laneN or dir) of the HPDS this run asks: from its container name, else from the flow directory of --store."""
    m = re.match(r"^picsure-local-hpds-(lane[123]|dir)$", getattr(args, "hpds_container", None) or "")
    if m:
        return m.group(1)
    store = os.path.realpath(args.store) if getattr(args, "store", None) else ""
    return compare.LANE_OF.get(os.path.basename(os.path.dirname(store))) if os.path.basename(store) == "store" else None


def served_stores(args, inspect):
    """What the open-mode HPDS and the query-service this run asks are serving, by the `local-stack.store` label of the running container:
    {container: store, or None when the container has no such label}. A container that is not running is not in it."""
    out = {}
    for flag, name in ((args.open, OPEN_CONTAINER), (args.api, QUERY_CONTAINER)):
        info = inspect(name) if flag else None
        if info:
            out[name] = info.get("store") or None
    return out


def target_problems(args, inspect=services_ref.inspect_container, env_inspect=compare.docker_inspect_env):
    """[(open|api, why)]: the services this run asks that are not serving the store it compares with (empty when they are, or when qa does not
    know its own store). The open-mode HPDS and the query-service are asked for the counts of --store: one that serves another lane's store, or does not
    say, would make the comparison meaningless."""
    lane = own_lane(args)
    if not lane or not getattr(args, "hpds_container", None):
        return []
    problems = []
    for name, store in served_stores(args, inspect).items():
        if store != lane:
            problems.append(("open" if name == OPEN_CONTAINER else "api",
                             f"{name} serves {store or 'an unknown store (no local-stack.store label)'}, this run asks {lane}"))
    why = compare.check_api_target(lane, env_inspect) if args.api else None
    if why and not any(k == "api" for k, _ in problems):
        problems.append(("api", why))
    return problems


def lane_two_load_json(args):
    """The load.json of the lane-2 store this run asks about, or None when the dataset cannot be told."""
    store = os.path.realpath(args.store) if getattr(args, "store", None) else ""
    if os.path.basename(store) == "store" and os.path.basename(os.path.dirname(store)) == compare.CSV:
        return os.path.join(os.path.dirname(store), "load.json")
    dataset = getattr(args, "dataset", None)
    if not dataset and os.path.basename(store) == "store":
        dataset = os.path.basename(os.path.dirname(os.path.dirname(store)))
    return os.path.join(compare.WORK, dataset, compare.CSV, "load.json") if dataset else None


def provenance(args, repo=services_ref.REPO, inspect=services_ref.inspect_container):
    """What is running, by commit: the hpds always, the query-service with --api, the dictionary with --dict. Loaders whose output the run used
    are part of it: the open columnMeta that a run generated with RemoveConceptFromMetadata names its jar in --loader-jar-file (a loader whose
    output was used when the open HPDS is up), and the CSVLoader named in the load.json of the lane-2 store, when any container this run asks
    serves that store. Which store each one serves is read from the running container (the HPDS container by its name, the open-mode HPDS and
    the query-service by their `local-stack.store` label), not from this run's own --store; one that does not say is an unknown, and so is a
    lane-2 store whose load.json names no jar."""
    names = ["hpds"] + (["query"] if args.api else []) + (["dict"] if args.dict else [])
    containers = dict(services_ref.CONTAINERS)
    if args.hpds_container:
        containers["hpds"] = [args.hpds_container] + (["picsure-local-hpds-open"] if args.open else [])
    served = served_stores(args, inspect)
    for store in served.values():  # the HPDS behind the query-service is part of what runs
        if store and re.match(r"^(lane[123]|dir)$", store) and args.hpds_container:
            c = "picsure-local-hpds-" + store
            if c not in containers["hpds"]:
                containers["hpds"].append(c)
    loaders = []
    if args.open and args.loader_jar_file and os.path.isfile(args.loader_jar_file):
        with open(args.loader_jar_file, encoding="utf-8") as f:
            loaders.append(f.read().strip())
    stores = set(served.values()) | {own_lane(args)}
    unknown = ["%s (no store label)" % c for c, st in sorted(served.items()) if not st]
    if "lane2" in stores:
        lj = lane_two_load_json(args)
        loaders.append(compare.lane_loader_jar(lj) if lj else "")
    return services_ref.provenance(names, work=compare.WORK, repo=repo, containers=containers, inspect=inspect, loaders=loaders, unknown=unknown)


def print_provenance(prov):
    print(prov["line"])
    print("tooling: " + prov["tooling"])
    print("tests: " + prov["tests"])
    for m in prov["mismatches"]:
        print("warning: " + m)


def main(argv):
    args = parse(argv)
    prov = provenance(args)
    print_provenance(prov)
    rep, info = run(args)
    summary = {"counts": rep.counts(), "exit": rep.exit_code(), "targets": {"hpds": args.hpds, "open": args.open, "api": args.api, "dict": args.dict},
               "provenance": prov, **info}
    write_reports(rep, summary, args.report_dir)
    print_summary(rep, args.report_dir, args.verbose)
    c = rep.counts()
    verdict = {0: "PASS", 1: "FAIL", 2: "INCONCLUSIVE"}[rep.exit_code()]
    print(f"QA {verdict}: {c[PASS]} pass, {c[FAIL]} fail, {c[INCONCLUSIVE]} inconclusive, {c[SKIP]} skipped (report: {args.report_dir}/qa.json, qa.tsv)")
    return rep.exit_code()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
