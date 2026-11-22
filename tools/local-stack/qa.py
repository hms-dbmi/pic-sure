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
              and/or observations (or observationsWritten), and kind (study | global) when it has one; repeatable. A release manifest's
              source.globals (split | global | both) is named in the detail of the checks it affects. A partition of kind global (without a
              manifest that says: one named `global`) is not a study partition: it is left out of the per-consent and per-study checks
  --stigvars  a file of concept paths (one per line), matched exactly: no listed path may appear in the open-mode HPDS's search, and no
              listed concept of the partitions may answer a query there. A path below a listed path is not listed. Without the file the
              open stigvar check is INCONCLUSIVE
  --stigvar-limit  how many listed paths that are in the partitions' columnMeta are searched and queried (default 200; 0 = all): at least one
              per study, the rest evenly spaced in the sorted paths, so max(limit, number of studies) in all, and the calls stay bounded for
              a list of 100k+ entries. Listed paths in no examined partition are one informational row. The coverage (listed / in the
              partitions / checked) is reported as the `coverage` row and printed in the summary
  --columnmeta-root  <partition>/columnMeta.csv from another place (the flow prefix of a release's partitions/, which has none); the
              dictionary path checks are INCONCLUSIVE for a study whose partitions have no columnMeta.csv
  --require   checks a missing target must not skip (open, api, dict, stigvar): the target missing is then a setup problem (exit 2)

Checks (each one is reported per partition or per case, as PASS, FAIL, INCONCLUSIVE or SKIP):
  partition.patients      COUNT with userConsents=[partition] and no clause = the source of truth (truth, manifest; else the \\_consents\\ concept)
  partition.observations  the sum of the observation counts of columnMeta.csv = the manifest / truth observation total
  concept.sample          for sampled concepts of a partition: REQUIRED count = columnMeta patientCount, observation count (OBSERVATION_CROSS_COUNT)
                          = columnMeta observationCount
  union                   a patient of two consents is counted once (exact with --dataset, bounds and identities otherwise)
  global.served           the open HPDS serves the sampled concepts of the global partition (a count above 0 with no consents); the authorized HPDS
                          does not serve a concept that only the global partition holds to a caller with the consents of every study partition
                          (SKIP when every concept of the global partition is also in a study partition, as in globals=both, when no concept is
                          sampled (--sample 0), and for the open side without --open)
  consent.missing         a consent with no partition counts 0 (alone and next to a real one)
  consent.none            a query without consents is refused (no count is returned)
  open.*                  the open HPDS with no consents = every partition; with consents = those partitions; unknown consent = 0. With a global
                          partition the open HPDS also serves it, so no consents is checked as bounds (the study partitions' patients, and the global
                          partition's, up to their sum) and the patients only the global partition holds are reported
  stigvar.open            no listed path (exact match) in the open HPDS's search, no listed concept answers there; one coverage row
  stigvar.control         the authorized HPDS does have the checked listed paths (INCONCLUSIVE when none can be checked, or one is missing)
  api.same                the query-service answers what the HPDS answers, query by query
  dict.consent            the dictionary's concepts for a consent filter belong to that consent's study (or are global/harmonized)
  dict.study_paths        per-study dictionary concept paths are a subset of the study's partition columnMeta paths
  dict.global_paths       global / harmonized dictionary paths (not under a study), compared separately, against the same partitions
  dict.unknown            a consent with no partition and no dataset returns no dictionary concepts

  genomic.*               with --genomic-dir (the HPDS runs the BDC genomic settings over a genomic directory laid out all/<group>/<chromosome>/):
                          preflight (the directory has study group and chromosome folders; the cause is named when it has none), startup (an HPDS
                          that is not up on such a directory is a FAIL with the cause from its log), config (the container runs
                          hpds.genomicProcessor.impl=localPatientDistributed over /opt/local/hpds/all/), patients (the genomic patients HPDS loaded =
                          the fixture's), variant_spec / info / combined (counts of variant-spec, variant-info and phenotype + genomic queries =
                          what the VCFs and the partitions' patients imply), consent (a genomic query is answered for the patients of the caller's
                          consents only; an unknown consent and no consents get nothing)
  --genomic-dir DIR / --genomic-manifest FILE   the genomic directory and the fixture's genomic.json. The counts per consent need the fixture's patients per
                          partition (built with GENOMIC_SPLIT_DIR); without them those checks are SKIPped with the reason

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
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import compare
import genomic
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

    def query(self, consents, clause=None, result="COUNT", select=None, genomic_filters=None):
        q = {"userConsents": [{"value": c} for c in consents], "expectedResultType": result}
        if clause is not None:
            q["phenotypicClause"] = clause
        if select is not None:
            q["select"] = list(select)
        if genomic_filters is not None:
            q["genomicFilters"] = list(genomic_filters)
        return self.post(self.path, {"query": q})

    def patients(self, consents, clause=None, genomic_filters=None):
        """(count, None) or (None, why): None when the answer is not a count."""
        code, text = self.query(consents, clause, genomic_filters=genomic_filters)
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
    """partition -> {"patients": n, "observations": n, "kind": "study"|"global"} from a loader manifest (only the keys it has; no kind = study)."""
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
        if p.get("kind") in ("study", "global"):
            e["kind"] = p["kind"]
        out[name] = e
    return out


def manifest_globals(path):
    """The global mode of a release manifest (source.globals, or a top-level globals): split, global or both; None when it names none."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    source = data.get("source")
    mode = (source.get("globals") if isinstance(source, dict) else None) or data.get("globals")
    return mode if mode in ("split", "global", "both") else None


def is_global(name, manifests):
    """A partition of kind global in a manifest; when no manifest gives a kind for it, one named exactly `global`."""
    kinds = [m[name]["kind"] for _, m in manifests if "kind" in m.get(name, {})]
    return "global" in kinds if kinds else name == "global"


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


def stratified(paths_studies, limit):
    """The paths to check, sorted: the smallest path of every study that has one (so no study goes unsampled), then evenly spaced paths of
    the rest up to `limit`. The total is max(limit, number of studies); limit 0 = all. paths_studies: {path: set of studies holding it}."""
    if limit <= 0 or len(paths_studies) <= limit:
        return sorted(paths_studies)
    picked, covered = set(), set()
    for study in sorted({st for sts in paths_studies.values() for st in sts}):
        if study in covered:
            continue
        path = min(p for p, sts in paths_studies.items() if study in sts)
        picked.add(path)
        covered |= paths_studies[path]
    rest = [p for p in paths_studies if p not in picked]
    return sorted(picked | (set(spread(rest, limit - len(picked))) if limit > len(picked) else set()))


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


def with_mode(detail, mode):
    """The detail plus `globals=<mode>` when the release manifest names the mode."""
    return "; ".join(d for d in (detail, f"globals={mode}" if mode else "") if d)


def check_partitions(rep, hpds, parts, meta, exp, mode=None, glob=()):
    """parts: the study partitions (counted through their consent); glob: the global partitions, whose observation total is compared only."""
    for p in list(parts) + list(glob):
        if p not in glob:
            got, why = hpds.patients([p])
            sources = exp[p]["patients"]
            if not sources:
                rep.add("partition.patients", p, INCONCLUSIVE, with_mode("no source of truth for the patient count (no truth, manifest or \\_consents\\ concept)", mode), None, got)
            elif got is None:
                rep.add("partition.patients", p, FAIL, with_mode(why, mode), [n for _, n in sources], got)
            elif all(n == got for _, n in sources):
                rep.add("partition.patients", p, PASS, with_mode("", mode), got, got)
            else:
                rep.add("partition.patients", p, FAIL, with_mode("expected " + ", ".join(f"{s}={n}" for s, n in sources) + f"; HPDS {got}", mode), [n for _, n in sources], got)
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


def check_global(rep, hpds, opn, glob, parts, meta, sample, mode=None):
    """The global partition is not reached through a consent. The open HPDS (no consents) must serve its sampled concepts; the authorized HPDS must not
    serve a concept that only the global partition holds to a caller with the consents of every study partition."""
    elsewhere = {c for p in parts for c in meta.get(p) or {}}
    no_meta = [p for p in parts if not meta.get(p)]
    for g in glob:
        if not meta.get(g):
            rep.add("global.served", g, INCONCLUSIVE, with_mode("no columnMeta.csv to sample concepts from", mode))
            continue
        if not sample[g]:
            rep.add("global.served", g, SKIP, with_mode("no concept is sampled (--sample 0)", mode))
            continue
        for path in sample[g] if opn else ():
            got, why = opn.patients([], compare.required(path))
            ok = got is not None and got > 0
            rep.add("global.served", f"{g} open {path}", PASS if ok else FAIL, with_mode("" if ok else why or f"the open HPDS counts {got} patients for a concept of the global partition", mode), ">0", got)
        if not opn:
            rep.add("global.served", f"{g} open", SKIP, with_mode("no open-mode HPDS (--open): only the authorized side is checked", mode))
        if no_meta:
            rep.add("global.served", f"{g} authorized", INCONCLUSIVE, with_mode(f"no columnMeta.csv for {len(no_meta)} study partitions: which concepts only the global partition holds is not known", mode))
            continue
        only = spread([c for c in meta[g] if c not in elsewhere], len(sample[g]))
        if not only:
            rep.add("global.served", f"{g} authorized", SKIP, with_mode("every concept of the global partition is also in a study partition: the authorized HPDS may serve it", mode))
        for path in only:
            got, why = hpds.patients(parts, compare.required(path))
            ok = got == 0
            rep.add("global.served", f"{g} authorized {path}", PASS if ok else FAIL, with_mode("" if ok else why or f"the authorized HPDS counts {got} patients for a concept only the global partition holds", mode), 0, got)


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


def check_open(rep, hpds, opn, parts, sample, stigvars, glob_patients=None, glob_paths=frozenset(), all_parts=None):
    """glob_patients: global partition -> its patient count (None when unknown). The open HPDS serves the global partitions too, so with no consents it
    counts the study partitions' patients and those only a global partition holds: a bound, not an equality."""
    skip = [p for p in parts]
    plain = {p: [c for c in sample.get(p, []) if not listed(c, stigvars)] for p in skip}
    got, why = opn.patients([])
    if glob_patients:
        allp, why_a = hpds.patients(all_parts or parts)
        known = [n for n in glob_patients.values() if n is not None]
        low = max([allp] + known) if allp is not None else None
        high = allp + sum(known) if allp is not None and len(known) == len(glob_patients) else None
        if got is None or allp is None:
            rep.add("open.none", "no consents, no clause = all partitions", FAIL, why or why_a or "no count", None, got)
        else:
            ok = low <= got and (high is None or got <= high)
            note = (f"the open HPDS counts {got - allp} patients the study partitions do not (study partitions {allp}, "
                    + ", ".join(f"{g} {n if n is not None else 'unknown'}" for g, n in sorted(glob_patients.items())) + ")")
            rep.add("open.none", "no consents, no clause = all partitions", PASS if ok else FAIL,
                    note if ok else f"expected between {low} and {high if high is not None else 'any'} patients; {note}", f"{low}..{high if high is not None else ''}", got)
    else:
        allp, why_a = hpds.patients(parts)
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
        if c in glob_paths and want is not None and got is not None:  # a concept the global partition holds too: the open HPDS adds its patients
            rep.add("open.none", f"no consents REQUIRED {c}", PASS if got >= want else FAIL, "" if got >= want else f"expected at least {want} (the global partition adds patients), got {got}", f">={want}", got)
        else:
            rep.eq("open.none", f"no consents REQUIRED {c}", want, got, why)
    got, why = opn.patients([UNKNOWN_CONSENT])
    rep.eq("open.consents", "unknown consent", 0, got, why)


def check_stigvars(rep, hpds, opn, stigvars, parts, meta, limit=200):
    """Listed paths are matched exactly (a path below a listed path is not listed). Of the listed paths that are in the partitions' columnMeta,
    at most max(`limit`, the number of studies) (0 = all) are checked, at least one per study and the rest evenly spaced in sorted order: each must not be in the open search (the search is the whole
    path, and only that exact path counts as a hit), and the open HPDS must count nothing on it. The authorized HPDS is the control: it must
    have the checked paths, else the check proves nothing for them (INCONCLUSIVE). One summary row reports the coverage, with the listed paths
    that are in no partition (informational)."""
    have_meta = any(meta.get(p) for p in parts)
    studies = {}  # listed path of the partitions -> the studies of the partitions that hold it
    for p in parts:
        for c in meta.get(p) or {}:
            if listed(c, stigvars):
                studies.setdefault(c, set()).add(pheno.study_of(p))
    if have_meta:
        candidates = len(studies)
        checked = stratified(studies, limit)
        coverage = (f"{len(stigvars)} listed, {len(studies)} in the partitions, {len(stigvars) - len(studies)} in no examined partition (not checked), "
                    f"{len(checked)} checked")
    else:
        candidates = len(stigvars)
        checked = spread(stigvars, limit)
        rep.add("stigvar.open", "concepts of the partitions", INCONCLUSIVE, "no columnMeta.csv to find the listed concepts in: a sample of the list was searched")
        coverage = f"{len(stigvars)} listed, which of them are in the partitions is unknown (no columnMeta.csv), {len(checked)} of the list searched"
    if len(checked) < candidates:
        coverage += f": at least one path per study, the rest evenly spaced in the sorted paths, up to --stigvar-limit {limit} (0 = all)"
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
# genomic data of the authorized HPDS (--genomic-dir): the BDC settings (hpds.genomicProcessor.impl=localPatientDistributed over
# /opt/local/hpds/all/, laid out all/<group>/<chromosome>/), a fixture built by genomic.py and the answers it implies


STARTUP_NPE = 'Cannot invoke "java.util.Collection.size()" because "c" is null'


def container_logs(container):
    """The whole log of a container, or None when docker or the container is not there (the seam of the startup diagnosis and of the patient count:
    the line HPDS logs at start must not scroll out of a tail)."""
    try:
        r = subprocess.run(["docker", "logs", container], check=False, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        return None
    return r.stdout + r.stderr if r.returncode == 0 else None


def container_state(container):
    """{"running": bool, "exit": int} of a container (a stopped one too), or None when docker or the container is not there."""
    try:
        r = subprocess.run(["docker", "inspect", "-f", "{{.State.Running}} {{.State.ExitCode}}", container], check=False, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    parts = r.stdout.split()
    if r.returncode != 0 or len(parts) != 2:
        return None
    return {"running": parts[0] == "true", "exit": int(parts[1])}


def scenario_label(args):
    """Which scenario this run is, for the result line: phenotype only, or phenotype + genomic with what the genomic directory holds."""
    if not getattr(args, "genomic_dir", None):
        return "phenotype only (no genomic processor)"
    if not os.path.isdir(args.genomic_dir):
        state = "genomic data directory does not exist"
    else:
        groups, chromosomes = genomic.summary(args.genomic_dir)
        state = f"{groups} study group(s), {chromosomes} chromosome folder(s)" if groups else genomic.NO_FOLDERS
    return f"phenotype + genomic ({genomic.IMPL} over {genomic.CONTAINER_DIR}; {state})"


def startup_failure(args, logs):
    """Why a genomic HPDS that is not UP is not up, from its log, or None when the log does not say (or cannot be read)."""
    text = logs(args.hpds_container) if getattr(args, "hpds_container", None) else None
    if not text:
        return None
    if STARTUP_NPE in text:
        return ("the log shows a NullPointerException in GenomicProcessorPatientMergingParentImpl.initializePatientIds: no genomic patient "
                "ids, because the genomic data directory has no dataset folders (or a study group folder has no chromosome folders)")
    errors = [ln.strip() for ln in text.splitlines() if "Caused by" in ln or "APPLICATION FAILED" in ln]
    return ("the log ends with: " + errors[-1][:200]) if errors else None


def genomic_preflight(rep, args):
    """FAIL rows for what the mounted genomic directory lacks; the same check make runs before it starts HPDS."""
    found = genomic.problems(args.genomic_dir)
    for why in found:
        rep.add("genomic.preflight", args.genomic_dir, FAIL, why)
    if not found:
        groups, chromosomes = genomic.summary(args.genomic_dir)
        rep.add("genomic.preflight", args.genomic_dir, PASS, f"{groups} study group(s), {chromosomes} chromosome folder(s)")
    return found


def option(text, name):
    m = re.search(rf"-D{re.escape(name)}=(\S+)", text or "")
    return m.group(1) if m else None


def check_genomic_config(rep, args, env_inspect):
    """The container runs the BDC genomic settings (set explicitly, or by profile bdc-auth-dev): otherwise a run named for them says nothing."""
    if not getattr(args, "hpds_container", None):
        rep.add("genomic.config", "container", SKIP, "no --hpds-container to read the settings from")
        return
    try:
        env = env_inspect(args.hpds_container) or []
    except Exception as e:  # noqa: BLE001
        rep.add("genomic.config", args.hpds_container, INCONCLUSIVE, f"cannot inspect the container: {e}")
        return
    kv = dict(e.split("=", 1) for e in env if "=" in e)
    if "bdc-auth-dev" in (kv.get("SPRING_PROFILES_ACTIVE") or "").split(","):
        rep.add("genomic.config", "profile", PASS, "profile bdc-auth-dev")
        return
    opts = kv.get("JAVA_OPTS", "")
    impl = option(opts, "hpds.genomicProcessor.impl")
    where = option(opts, "HPDS_GENOMIC_DATA_DIRECTORY") or kv.get("HPDS_GENOMIC_DATA_DIRECTORY")
    ok = impl == genomic.IMPL and (where or "").rstrip("/") == genomic.CONTAINER_DIR.rstrip("/")
    rep.add("genomic.config", args.hpds_container, PASS if ok else FAIL,
            "" if ok else f"hpds.genomicProcessor.impl={impl}, HPDS_GENOMIC_DATA_DIRECTORY={where}; the BDC authorized HPDS runs "
                          f"{genomic.IMPL} over {genomic.CONTAINER_DIR}",
            f"{genomic.IMPL} {genomic.CONTAINER_DIR}", f"{impl} {where}")


def check_genomic_patients(rep, args, manifest, logs):
    """The patients HPDS loaded from the genomic stores (its own log line, read from the whole log) equal the fixture's."""
    text = logs(args.hpds_container) if getattr(args, "hpds_container", None) else None
    if text is None:
        rep.add("genomic.patients", "log", INCONCLUSIVE, "the HPDS log cannot be read, so the genomic patient count is not known")
        return
    seen = re.findall(r"(\d+) patient ids loaded from patient partitions", text)
    if not seen:
        rep.add("genomic.patients", "log", FAIL, "the log has no 'patient ids loaded from patient partitions' line: no genomic processor started")
        return
    rep.eq("genomic.patients", "patients in the genomic stores", manifest["patients"], int(seen[-1]))


def granted(members, consents):
    out = set()
    for c in consents:
        out |= set(members.get(c, ()))
    return out


def four_part(spec):
    return ",".join(spec.split(",")[:4])


NO_MEMBERS = ("the fixture was built without the phenotype patients (no GENOMIC_SPLIT_DIR), so which patients each consent grants is not known; "
              "the counts per consent are not checked")


def check_genomic_queries(rep, hpds, parts, manifest, args):
    """Variant-spec, variant-info and combined queries against what the VCFs say, with consent scoping: a genomic filter alone is answered
    for the patients of the caller's consents only (genomic data is not partitioned by consent; the phenotype partitions scope it). The counts that
    need to know which patients each consent grants are skipped (with the reason) when the fixture was built without that."""
    variants = manifest["variants"]
    members = {p: ids for p, ids in (manifest.get("members") or {}).items() if p in parts}
    exact = bool(members)
    chosen = spread([v["spec"] for v in variants], args.genomic_variants)
    carr = {v["spec"]: genomic.carriers_of(v) for v in variants}
    everyone = granted(members, list(members))
    grant = {p: granted(members, [p]) for p in members}

    def spec_filter(spec, zygosities=None):
        f = {"key": spec}
        if zygosities:
            f["values"] = list(zygosities)
        return [f]

    # variant spec, one record at a time, for all consents and for each partition
    for spec in chosen:
        if not exact:
            got, why = hpds.patients(parts, genomic_filters=spec_filter(spec))
            bound = got is not None and got <= len(carr[spec])
            rep.add("genomic.variant_spec", f"{spec} all consents (bound)", PASS if bound else FAIL,
                    why or ("" if bound else f"{got} patients for {len(carr[spec])} carriers"), f"<= {len(carr[spec])}", got)
            continue
        got, why = hpds.patients(sorted(members), genomic_filters=spec_filter(spec))
        rep.eq("genomic.variant_spec", f"{spec} all consents", len(carr[spec] & everyone), got, why)
        for p in sorted(members):
            got, why = hpds.patients([p], genomic_filters=spec_filter(spec))
            rep.eq("genomic.variant_spec", f"{spec} {p}", len(carr[spec] & grant[p]), got, why)
    if not exact:
        rep.add("genomic.variant_spec", "counts per consent", SKIP, NO_MEMBERS)
    # one zygosity, and the four-part spec (every record with that position and alleles)
    with_hom = next((v for v in variants if v["hom"]), None)
    if with_hom and exact:
        got, why = hpds.patients(sorted(members), genomic_filters=spec_filter(with_hom["spec"], ["1/1"]))
        rep.eq("genomic.variant_spec", f"{with_hom['spec']} homozygous only", len(set(with_hom["hom"]) & everyone), got, why)
    if exact:
        for key in sorted({four_part(v["spec"]) for v in variants})[:4]:
            union = set().union(*(carr[v["spec"]] for v in variants if four_part(v["spec"]) == key))
            got, why = hpds.patients(sorted(members), genomic_filters=spec_filter(key))
            rep.eq("genomic.variant_spec", f"{key} (every record)", len(union & everyone), got, why)
    # variant info: the columns are listed, a category filter finds the carriers of the variants that have the value
    code, text = hpds.query(parts, None, "INFO_COLUMN_LISTING")
    try:
        listed = {c["key"] for c in json.loads(text)}
    except (ValueError, KeyError, TypeError):
        listed = None
    wanted = {k for v in variants for k in v["info"]}
    if listed is None:
        rep.add("genomic.info", "INFO_COLUMN_LISTING", FAIL, f"HTTP {code}: no list of info columns")
    else:
        missing = sorted(wanted - listed)
        rep.add("genomic.info", "INFO_COLUMN_LISTING", FAIL if missing else PASS, f"missing columns: {', '.join(missing)}" if missing else "", sorted(wanted), sorted(listed & wanted))
    genes = sorted({v["info"]["Gene_with_variant"] for v in variants if "Gene_with_variant" in v["info"]})
    for gene in genes:
        match = [v for v in variants if v["info"].get("Gene_with_variant") == gene]
        got, why = hpds.patients(sorted(members) or parts, genomic_filters=[{"key": "Gene_with_variant", "values": [gene]}])
        if exact:
            rep.eq("genomic.info", f"Gene_with_variant={gene}", len(set().union(*(carr[v["spec"]] for v in match)) & everyone), got, why)
        else:
            bound = len(set().union(*(carr[v["spec"]] for v in match)))
            ok = got is not None and got <= bound
            rep.add("genomic.info", f"Gene_with_variant={gene} (bound)", PASS if ok else FAIL, why or ("" if ok else f"{got} patients for {bound} carriers"), f"<= {bound}", got)
    pair = next(((g, s) for g in genes for s in sorted({v["info"].get("Variant_severity") for v in variants if v["info"].get("Gene_with_variant") == g} - {None})), None)
    if pair and exact:
        match = [v for v in variants if v["info"].get("Gene_with_variant") == pair[0] and v["info"].get("Variant_severity") == pair[1]]
        got, why = hpds.patients(sorted(members), genomic_filters=[{"key": "Gene_with_variant", "values": [pair[0]]}, {"key": "Variant_severity", "values": [pair[1]]}])
        rep.eq("genomic.info", f"Gene_with_variant={pair[0]} and Variant_severity={pair[1]}", len(set().union(*(carr[v["spec"]] for v in match)) & everyone), got, why)
    # phenotype and genomic filters together: the concepts of the manifest (the fixture's patients that have them), each partition's best one
    concepts = manifest.get("concepts") or {}
    if not (exact and concepts):
        rep.add("genomic.combined", "phenotype + genomic", SKIP, NO_MEMBERS if not exact else "the manifest has no phenotype concepts of the fixture's patients")
    else:
        done = 0
        for p in sorted(members):
            if p not in concepts or done >= args.genomic_combined:
                continue
            best = None
            for path, ids in concepts[p].items():
                have = set(ids) & grant[p]
                for spec, c in carr.items():
                    n = len(c & have)
                    if n and (best is None or n > best[0]):
                        best = (n, path, spec)
            if best is None:
                continue
            n, path, spec = best
            got, why = hpds.patients([p], compare.required(path), spec_filter(spec))
            rep.eq("genomic.combined", f"{p} REQUIRED + {spec}", n, got, why)
            done += 1
        if not done:
            rep.add("genomic.combined", "phenotype + genomic", INCONCLUSIVE, "no concept of any partition is shared with a carrier of any fixture variant")
    # consent scoping: a caller with the consents of one partition gets the carriers inside it, not those of the other partitions
    if not exact:
        rep.add("genomic.consent", "scoping", SKIP, NO_MEMBERS)
    else:
        shown = 0
        for p in sorted(members):
            sv = genomic.scoping_variant(variants, members, [p])
            if not sv:
                continue
            v, inside, outside = sv
            got, why = hpds.patients([p], genomic_filters=spec_filter(v["spec"]))
            rep.eq("genomic.consent", f"{p}: {inside} carrier(s) of {v['spec']} inside, {outside} in other partitions not counted", inside, got, why)
            shown += 1
        names = sorted(members)
        if len(names) >= 3:
            sv = genomic.scoping_variant(variants, members, names[:2])
            if sv:
                v, inside, outside = sv
                got, why = hpds.patients(names[:2], genomic_filters=spec_filter(v["spec"]))
                rep.eq("genomic.consent", f"{' + '.join(names[:2])}: {inside} inside, {outside} in the other partitions not counted", inside, got, why)
                shown += 1
        if not shown:
            rep.add("genomic.consent", "scoping", INCONCLUSIVE, "no fixture variant has carriers inside a partition and in another one: a cross-partition leak could not show")
    spec = chosen[0]
    got, why = hpds.patients([UNKNOWN_CONSENT], genomic_filters=spec_filter(spec))
    rep.eq("genomic.consent", f"{UNKNOWN_CONSENT} (no partition)", 0, got, why)
    code, text = hpds.query([], None, genomic_filters=spec_filter(spec))
    refused, note = refusal(code, text)
    rep.add("genomic.consent", "no consents", PASS if refused else FAIL, note)


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
    for r in rep.results:
        if r["check"] == "stigvar.open" and r["case"] == "coverage":
            print(f"  stigvar coverage: {r['detail']}")
    for status in (FAIL, INCONCLUSIVE):
        shown = [r for r in rep.results if r["status"] == status]
        for r in shown[: (len(shown) if verbose else MAX_SHOWN * 4)]:
            print(f"  {status} {r['check']} {r['case']}: {r['detail']}")
        if len(shown) > MAX_SHOWN * 4 and not verbose:
            print(f"  ... {len(shown) - MAX_SHOWN * 4} more {status} in {out_dir}/qa.tsv")


def read_genomic_manifest(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError, TypeError):
        return None


def run(args, inspect=services_ref.inspect_container, env_inspect=compare.docker_inspect_env, logs=container_logs, state=container_state):
    t0 = time.time()
    rep = Report()
    rng = random.Random(args.seed)
    hpds = Client(args.hpds)
    with_genomic = bool(args.genomic_dir)
    preflight = genomic_preflight(rep, args) if with_genomic else []
    health = hpds.health()
    if health != "UP":
        cause = startup_failure(args, logs) if with_genomic else None
        st = state(args.hpds_container) if with_genomic and args.hpds_container else None
        exited = st is not None and not st["running"]
        if with_genomic and (preflight or cause or exited):  # an HPDS with genomic settings that cannot start is a failure, and the cause is named
            rep.add("genomic.startup", "hpds", FAIL, f"the HPDS at {args.hpds} is {health}" + (f"; the container exited with code {st['exit']}" if exited else "")
                    + (f"; {cause}" if cause else "") + (f"; {preflight[0]}" if preflight else ""))
        else:
            rep.add("setup", "hpds", INCONCLUSIVE, f"the HPDS at {args.hpds} is {health}")
        return rep, {"setup": "hpds not up"}
    manifest = read_genomic_manifest(args.genomic_manifest) if with_genomic and args.genomic_manifest else None
    if with_genomic:
        check_genomic_config(rep, args, env_inspect)
        if not manifest or not manifest.get("variants"):
            rep.add("genomic.patients", "manifest", INCONCLUSIVE, "no usable --genomic-manifest: the expected genomic answers are not known")
        else:
            check_genomic_patients(rep, args, manifest, logs)
    meta = read_store(args.store)
    if not meta:
        rep.add("setup", "store", INCONCLUSIVE, f"no partitions under {args.store}")
        return rep, {"setup": "no partitions"}
    if args.columnmeta_root:  # columnMeta.csv from elsewhere (a release's partitions/ has none): same partition names
        other = read_store(args.columnmeta_root)
        meta = {p: other.get(p) for p in meta}
        if not any(meta.values()):
            rep.add("setup", "columnmeta-root", INCONCLUSIVE, f"no columnMeta.csv for any partition under {args.columnmeta_root}")
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
    glob = [p for p in sorted(meta) if is_global(p, manifests)]
    all_parts = [p for p in sorted(meta) if p not in glob]
    if not all_parts:
        rep.add("setup", "store", INCONCLUSIVE, f"no study partitions under {args.store} (only the global partition)")
        return rep, {"setup": "no study partitions"}
    parts = all_parts[: args.max_partitions] if args.max_partitions else all_parts
    mode = next((g for g in (manifest_globals(m) for m in args.manifest) if g), None)
    exp = expectations(parts + glob, meta, manifests, truth)
    sample = sample_concepts(parts + glob, meta, args.sample, rng)
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

    check_partitions(rep, hpds, parts, meta, exp, mode, glob)
    check_concepts(rep, hpds, parts, meta, sample)
    check_union(rep, hpds, parts, meta, truth, args.union_pairs, rng)
    check_missing(rep, hpds, parts, sample)
    if glob:
        check_global(rep, hpds, opn, glob, all_parts, meta, sample, mode)
    if with_genomic and manifest and manifest.get("variants"):
        check_genomic_queries(rep, hpds, parts, manifest, args)
    if opn:
        check_open(rep, hpds, opn, parts, sample, stigvars, {g: next((n for _, n in exp[g]["patients"]), None) for g in glob},
                   {c for g in glob for c in meta.get(g) or {}}, all_parts)
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
    return rep, {"partitions": len(parts), "global_partitions": glob, "globals": mode, "sources": [n for n, _ in manifests] + (["truth"] if truth else []), "seconds": round(time.time() - t0, 1)}


def non_negative(text):
    n = int(text)
    if n < 0:
        raise argparse.ArgumentTypeError("must be 0 (all) or more")
    return n


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
    ap.add_argument("--stigvar-limit", type=non_negative, default=200, help="listed paths of the partitions searched and queried: at least one per study, the rest evenly spaced "
                    "in sorted order, so max(limit, number of studies) in all; 0 = every listed path of the partitions")
    ap.add_argument("--columnmeta-root", help="a directory with <partition>/columnMeta.csv when --store has none (a release's partitions/)")
    ap.add_argument("--genomic-dir", help="the genomic data directory mounted into the HPDS (all/<group>/<chromosome>/): the HPDS runs the BDC genomic settings; "
                    "checked before anything is asked, and the genomic checks run")
    ap.add_argument("--genomic-manifest", help="genomic.json of the fixture (genomic.py): the answers the genomic checks expect")
    ap.add_argument("--genomic-variants", type=int, default=40, help="fixture variants queried one by one (spread over the list)")
    ap.add_argument("--genomic-combined", type=int, default=3, help="partitions that get a phenotype + genomic query")
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
    manifest = read_genomic_manifest(args.genomic_manifest) if getattr(args, "genomic_manifest", None) and args.genomic_dir else None
    if manifest and manifest.get("loader_jar"):  # the fixture's genomic store was written by this loader
        loaders.append(manifest["loader_jar"])
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
    print("scenario: " + scenario_label(args))
    rep, info = run(args)
    summary = {"counts": rep.counts(), "exit": rep.exit_code(), "targets": {"hpds": args.hpds, "open": args.open, "api": args.api, "dict": args.dict},
               "provenance": prov, "scenario": scenario_label(args), **info}
    write_reports(rep, summary, args.report_dir)
    print_summary(rep, args.report_dir, args.verbose)
    c = rep.counts()
    verdict = {0: "PASS", 1: "FAIL", 2: "INCONCLUSIVE"}[rep.exit_code()]
    short = "phenotype + genomic" if args.genomic_dir else "phenotype only"
    print(f"QA {verdict} [{short}]: {c[PASS]} pass, {c[FAIL]} fail, {c[INCONCLUSIVE]} inconclusive, {c[SKIP]} skipped (report: {args.report_dir}/qa.json, qa.tsv)")
    return rep.exit_code()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
