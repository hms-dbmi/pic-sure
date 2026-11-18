#!/usr/bin/env python3
"""Which commit of the services the local stack serves, and where its jars come from. Standard library only.

  services_ref.py resolve REF               print the commit REF names (a local branch, origin/REF, upstream/REF or a sha; csvloader-partition-names upstream-first); exit 1 if none does
  services_ref.py prepare [--force] NAME... resolve, extract (git archive) and build what NAME needs; NAME is hpds, query, operations, dict or loader
  services_ref.py get NAME FIELD            print a field of what `prepare` recorded: ref, sha, src, jar (loader: csv, rm)
  services_ref.py line                      print `services: hpds=<sha> query=<sha> dict=<sha>`

Each served service is built from `git archive <sha>` of this repository, extracted to work/src/services-<sha>/ and built there with maven
(the checkout is never written to), so the stack tests the service code of a commit, not the code of the working tree.
The commit comes from SERVICES_REF (default csvloader-partition-names), per service from HPDS_REF, QUERY_REF (query-service and
operations-service) and DICT_REF, and for the CSVLoader of lane 2 from LOADER_REF. An extraction is reused while its jars exist
(the cache key is the commit sha); `prepare --force` rebuilds.

`prepare` records what it resolved in work/services.json (and which candidate matched: the ref itself, origin/<ref> or upstream/<ref>). The
provenance that qa.py and compare.py print and store is read from the RUNNING containers: the `local-stack.services-sha` label of the image each
one was created from. work/services.json is only a cross-check, and a difference between the two is reported.
"""
from __future__ import annotations

import glob
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
WORK = os.environ.get("WORK_DIR") or os.path.join(HERE, "work")
DEFAULT_REF = "csvloader-partition-names"
# the ref the deployed load and the dev stack use: a run is labelled as testing it only when every served service is at its commit
DEPLOYED_REF = "csvloader-partition-names"
MARKER = ".extracted"
RECORD = "services.json"
LABEL_SHA = "local-stack.services-sha"
LABEL_REF = "local-stack.services-ref"
LABEL_VIA = "local-stack.services-via"
# set by compose on the containers whose data depends on STORE (the open-mode HPDS and the query-service): which lane's store they serve
LABEL_STORE = "local-stack.store"
# the containers each served service runs in (an HPDS per lane, the dir one and the open one; the operations-service belongs to the query side)
CONTAINERS = {
    "hpds": ["picsure-local-hpds-lane1", "picsure-local-hpds-lane2", "picsure-local-hpds-lane3", "picsure-local-hpds-dir", "picsure-local-hpds-open"],
    "query": ["picsure-local-query", "picsure-local-operations"],
    "dict": ["picsure-local-dict-api"],
}
CONTAINER_RECORD = {"picsure-local-query": "query", "picsure-local-operations": "operations", "picsure-local-dict-api": "dict"}

# name -> the variable that overrides its ref, its maven module and the jars it needs (a pattern per jar, relative to the extracted tree)
SERVICES = {
    "hpds": {"var": "HPDS_REF", "module": "services/pic-sure-hpds/service",
             "jars": {"jar": "services/pic-sure-hpds/service/target/service-*.jar"}},
    "query": {"var": "QUERY_REF", "module": "services/pic-sure-hpds-query-service",
              "jars": {"jar": "services/pic-sure-hpds-query-service/target/pic-sure-hpds-query-service-*.jar"}},
    "operations": {"var": "QUERY_REF", "module": "services/pic-sure-operations-service",
                   "jars": {"jar": "services/pic-sure-operations-service/target/pic-sure-operations-service-*.jar"}},
    "dict": {"var": "DICT_REF", "module": "services/picsure-dictionary",
             "jars": {"jar": "services/picsure-dictionary/target/dictionary-*.jar"}},
    # the loaders that produce synthetic input (the CSVLoader of lane 2) and the tool that makes the open columnMeta
    "loader": {"var": "LOADER_REF", "module": "services/pic-sure-hpds/etl",
               "jars": {"csv": "services/pic-sure-hpds/docker/pic-sure-hpds-etl/CSVLoader-jar-with-dependencies.jar",
                        "rm": "services/pic-sure-hpds/docker/pic-sure-hpds-etl/RemoveConceptFromMetadata-jar-with-dependencies.jar"}},
}
# what `make qa` and the compare report name: the served services, operations-service being part of the query side
SERVED = ("hpds", "query", "dict")
MVN = ["mvn", "-B", "-ntp", "-q"]


class RefError(Exception):
    pass


def git(repo, *args, **kw):
    return subprocess.run(["git", "-C", repo, *args], check=False, capture_output=True, text=True, **kw)


PREFIXES = ("", "origin/", "upstream/")
# the deployed ref is looked up on the org remote first, in the build and in the provenance label alike: a stale local branch of that name must
# neither be served nor make the label name a commit that is not the one served
DEPLOYED_PREFIXES = ("upstream/", "origin/", "")


def prefixes_for(ref):
    return DEPLOYED_PREFIXES if (ref or "").strip() == DEPLOYED_REF else PREFIXES


def resolve_ref(ref, repo=REPO, prefixes=PREFIXES):
    """(full commit sha, the candidate that matched) of REF: tried as given (a local branch, a remote-tracking ref, a tag, a sha), then as
    origin/REF and upstream/REF; PREFIXES sets the order (prefixes_for(ref) picks it: DEPLOYED_REF is tried upstream-first)."""
    ref = (ref or "").strip()
    if not ref:
        raise RefError("empty ref")
    if ref.startswith("-"):
        raise RefError("ref %r looks like an option" % ref)
    tried = [p + ref for p in prefixes]
    for c in tried:
        r = git(repo, "rev-parse", "--verify", "--quiet", c + "^{commit}")
        sha = r.stdout.strip()
        if r.returncode == 0 and len(sha) == 40:
            return sha, c
    raise RefError("ref %r does not resolve to a commit in %s (tried %s); fetch it or pass a local branch, origin/<ref>, upstream/<ref> or a sha"
                   % (ref, repo, ", ".join(tried)))


def resolve(ref, repo=REPO):
    return resolve_ref(ref, repo, prefixes_for(ref))[0]


def ref_for(name, env=None):
    env = os.environ if env is None else env
    return (env.get(SERVICES[name]["var"]) or "").strip() or (env.get("SERVICES_REF") or "").strip() or DEFAULT_REF


def tree_dir(sha, work=WORK):
    """Where the commit is extracted: work/src/services-<sha>."""
    return os.path.join(work, "src", "services-" + sha)


def extract(repo, sha, work=WORK, stack=HERE):
    """git archive of the whole commit into tree_dir(sha), once: a finished extraction carries a marker and is reused. Returns the directory."""
    dest = tree_dir(sha, work)
    if os.path.isfile(os.path.join(dest, MARKER)):
        return dest
    guard(work, stack)
    tmp = dest + ".partial"
    for d in (tmp, dest):
        shutil.rmtree(d, ignore_errors=True)
    os.makedirs(tmp)
    ga = subprocess.Popen(["git", "-C", repo, "archive", sha], stdout=subprocess.PIPE)
    tar = subprocess.run(["tar", "-x", "-C", tmp], stdin=ga.stdout, check=False, capture_output=True, text=True)
    ga.stdout.close()
    if ga.wait() != 0 or tar.returncode != 0:
        shutil.rmtree(tmp, ignore_errors=True)
        raise RefError("git archive %s in %s failed: %s" % (sha, repo, tar.stderr.strip()))
    with open(os.path.join(tmp, MARKER), "w") as f:
        f.write(sha + "\n")
    os.rename(tmp, dest)
    return dest


def guard(work, stack=HERE):
    """Nothing is removed unless WORK is this stack's work directory (the same check as lane.py guard-work)."""
    import lane

    problem = lane.work_dir_problem(work, stack)
    if problem:
        raise RefError(problem)


def find_jar(src, pattern):
    hits = sorted(h for h in glob.glob(os.path.join(src, pattern)) if not h.endswith(("-sources.jar", "-tests.jar")) and not os.path.basename(h).startswith("original-"))
    return hits[0] if hits else None


def missing(src, names):
    return [n for n in names if any(find_jar(src, p) is None for p in SERVICES[n]["jars"].values())]


def build(src, modules, run=subprocess.run):
    cmd = MVN + ["-pl", ",".join(modules), "-am", "package", "-DskipTests"]
    print("mvn package in %s: %s" % (src, ",".join(modules)), flush=True)
    r = run(cmd, cwd=src)
    if r.returncode != 0:
        raise RefError("maven failed in %s (%s)" % (src, " ".join(cmd)))


def record_path(work=WORK):
    return os.path.join(work, RECORD)


def read_record(work=WORK):
    try:
        with open(record_path(work), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def tooling_commit(repo=REPO):
    """The commit of this tooling (the checkout the stack runs from), with -dirty when tools/local-stack has uncommitted changes."""
    r = git(repo, "rev-parse", "HEAD")
    if r.returncode != 0:
        return "unknown"
    sha = r.stdout.strip()
    st = git(repo, "status", "--porcelain", "--", "tools/local-stack")
    return sha + ("-dirty" if st.returncode == 0 and st.stdout.strip() else "")


def prepare(names, force=False, repo=REPO, work=WORK, stack=HERE, env=None, run=subprocess.run):
    """Resolve, extract and build what NAMES need; record the result. Returns the record's services entries."""
    for n in names:
        if n not in SERVICES:
            raise RefError("unknown service %r (one of %s)" % (n, ", ".join(SERVICES)))
    resolved = {}
    for n in names:
        ref = ref_for(n, env)
        resolved[n] = (ref,) + resolve_ref(ref, repo, prefixes_for(ref))
    srcs = {sha: extract(repo, sha, work, stack) for _, sha, _ in resolved.values()}
    for sha, src in srcs.items():
        mine = [n for n in names if resolved[n][1] == sha]
        todo = mine if force else missing(src, mine)
        if todo:
            build(src, sorted({SERVICES[n]["module"] for n in todo}), run)
        bad = missing(src, mine)
        if bad:
            raise RefError("built %s but the jar of %s is not there (%s)" % (src, ", ".join(bad), ", ".join(p for n in bad for p in SERVICES[n]["jars"].values())))
    rec = read_record(work)
    rec.pop("tooling", None)
    entries = rec.setdefault("services", {})
    for n in names:
        ref, sha, matched = resolved[n]
        src = srcs[sha]
        entry = {"ref": ref, "sha": sha, "matched": matched, "src": src}
        entry.update({k: find_jar(src, p) for k, p in SERVICES[n]["jars"].items()})
        entries[n] = entry
    os.makedirs(work, exist_ok=True)
    with open(record_path(work), "w", encoding="utf-8") as f:
        json.dump(rec, f, indent=1, sort_keys=True)
        f.write("\n")
    return {n: entries[n] for n in names}


def short(sha):
    return sha[:12] if sha else "-"


FORMAT = "{{.State.Running}}|" + "|".join("{{index .Config.Labels \"%s\"}}" % x for x in (LABEL_SHA, LABEL_REF, LABEL_VIA, LABEL_STORE))


def inspect_container(name):
    """The labels of the RUNNING container NAME: {"sha", "ref", "via", "store"} ("" for a label it does not carry), or None when
    there is no such container, it is not running, or docker cannot be asked."""
    try:
        r = subprocess.run(["docker", "inspect", "-f", FORMAT, name], check=False, capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    if r.returncode != 0:
        return None
    running, sha, ref, via, store = (r.stdout.strip().split("|") + ["", "", "", "", ""])[:5]
    return {"sha": sha, "ref": ref, "via": via, "store": store} if running == "true" else None


def loader_info(jar):
    """The commit a loader jar was built from: from its path in work/src/services-<sha>/, else "checkout"."""
    m = re.search(r"/services-([0-9a-f]{40})/", jar or "")
    return {"jar": jar, "sha": m.group(1) if m else "checkout"}


def provenance(names=SERVED, work=WORK, repo=REPO, containers=None, inspect=inspect_container, loaders=(), unknown=()):
    """What the stack serves, read from the running containers (the label of their images), cross-checked against work/services.json:

    {"tooling", "services": {name: {"sha", "shas", "ref", "via", "containers"}}, "loaders", "mismatches", "line", "tests"}

    NAMES are the services that are up; CONTAINERS (or `containers`, name -> candidate container names) says where each one runs, and only the
    candidates that are running count. LOADERS are the jars of loaders whose output the run used (lane-2 stores, a generated open columnMeta);
    `loader=<sha>` is added to the line then. UNKNOWN are further things the caller could not establish (a container without its store label): the
    `tests` line says `unknown: ...` for them. `tests` names DEPLOYED_REF only when everything the run used is at its commit."""
    containers = containers or CONTAINERS
    recorded = read_record(work).get("services") or {}
    services, mismatches = {}, []
    for n in names:
        running = {c: inspect(c) for c in containers.get(n, CONTAINERS[n])}
        running = {c: v for c, v in running.items() if v}
        if not running:
            continue
        shas = sorted({v["sha"] for v in running.values() if v["sha"]})
        unlabelled = sorted(c for c, v in running.items() if not v["sha"])
        refs = sorted({v["ref"] for v in running.values() if v["ref"]})
        vias = sorted({v["via"] for v in running.values() if v["via"]})
        services[n] = {"sha": shas[0] if len(shas) == 1 and not unlabelled else None, "shas": shas, "unlabelled": unlabelled,
                       "ref": "/".join(refs), "via": "/".join(vias), "containers": {c: v["sha"] for c, v in running.items()}}
        for c, v in running.items():
            rec = recorded.get(CONTAINER_RECORD.get(c, n))
            if v["sha"] and rec and rec["sha"] != v["sha"]:
                mismatches.append("%s runs services %s, work/services.json records %s" % (c, short(v["sha"]), short(rec["sha"])))
    infos = [loader_info(j) for j in loaders]
    line = "services: " + " ".join("%s=%s" % (n, ("/".join(short(x) for x in services[n]["shas"]) or "unlabelled") if n in services else "-") for n in SERVED)
    if infos:
        line += " loader=" + "/".join(sorted({short(i["sha"]) if i["sha"] != "checkout" else "checkout" for i in infos}))
    tooling = tooling_commit(repo)
    return {"tooling": tooling, "services": services, "loaders": infos, "mismatches": mismatches, "line": line,
            "tests": tests_label(services, names, repo, tooling, infos, unknown)}


def tests_label(services, names, repo=REPO, tooling=None, loaders=(), extra_unknown=()):
    """`csvloader-partition-names` when every served service in NAMES runs at that ref's commit and so does every loader whose output was used, else
    why it is not. The target is resolved as upstream/<ref> first, as the build resolves it. A dirty tooling checkout qualifies the label (it does not block it)."""
    wanted = [n for n in names if n in SERVED]
    unknown = list(extra_unknown)
    for n in wanted:
        if n not in services:
            unknown.append("%s (no running container)" % n)
        elif services[n]["unlabelled"] or not services[n]["shas"]:
            unknown.append("%s (a container without the label)" % n)
    if unknown:
        return "unknown: %s" % ", ".join(unknown)
    try:
        target, matched = resolve_ref(DEPLOYED_REF, repo, DEPLOYED_PREFIXES)
    except RefError as e:
        return "unknown: %s" % e
    off = ["%s=%s" % (n, "/".join(short(x) for x in services[n]["shas"])) for n in wanted if services[n]["shas"] != [target]]
    off += ["loader=%s" % (short(i["sha"]) if i["sha"] != "checkout" else "checkout") for i in loaders if i["sha"] != target]
    if off:
        return "NOT %s (%s, not %s)" % (DEPLOYED_REF, ", ".join(off), short(target))
    return DEPLOYED_REF + (" (tooling %s)" % short_tooling(tooling) if tooling and tooling.endswith("-dirty") else "")


def short_tooling(tooling):
    return short(tooling.replace("-dirty", "")) + ("-dirty" if tooling.endswith("-dirty") else "")


def main(argv):
    if not argv:
        sys.stderr.write(__doc__)
        return 2
    cmd, rest = argv[0], argv[1:]
    try:
        if cmd == "resolve" and len(rest) == 1:
            print(resolve(rest[0]))
        elif cmd == "prepare":
            force = "--force" in rest
            names = [a for a in rest if a != "--force"]
            for n, e in prepare(names, force=force).items():
                print("%-10s %s (%s) -> %s  %s" % (n, e["ref"], e["matched"], short(e["sha"]), e.get("jar") or e.get("csv")))
        elif cmd == "get" and len(rest) == 2:
            e = (read_record().get("services") or {}).get(rest[0])
            if not e or rest[1] not in e:
                sys.stderr.write("nothing recorded for %s %s (run `services_ref.py prepare %s`)\n" % (rest[0], rest[1], rest[0]))
                return 1
            print(e[rest[1]])
        elif cmd == "line":
            print(provenance()["line"])
        else:
            sys.stderr.write(__doc__)
            return 2
    except RefError as e:
        sys.stderr.write("%s\n" % e)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
