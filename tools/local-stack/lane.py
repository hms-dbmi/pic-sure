#!/usr/bin/env python3
"""Runs the split step and the loader lanes of the local stack. Standard library only.

  lane.py prepare DATASET          make work/DATASET/{input,split} and the key (generates combined/synth, links mini/1000g)
  lane.py split   DATASET          prepare, then run the splitter (combined/synth); writes work/DATASET/split.json
  lane.py load    DATASET FLOW     run one loader flow; writes work/DATASET/FLOW/{store,load.json,load.log}
  lane.py flows   DATASET          print the flows that apply to the dataset, space separated
  lane.py guard-work PATH          exit 1 (and say why) unless PATH is this stack's work directory (.../tools/local-stack/work, not a symlink)
  lane.py request DATASET FLOW...  record which flows this run asked for (work/DATASET/requested-flows.json), for the report

Environment: LANE1=auto|require|off  lane 1 (needs a PICSURELoader that reads the combined file): auto runs it when the jar can and
skips it when the jar has no `partitionBy` option, require turns that skip into a failure, off never runs it. A jar that has the option
but fails the probe run on a tiny combined file is a failure (lane1 FAILED, with the probe's exit code and log tail), in every mode but off.

Flows (named like the future runner's flows; lane numbers are used for the HPDS ports):
  lane1  picsureloader-combined   PICSURELoader reads the combined file and routes it by consent itself
  lane2  csvloader-split          splitter -> sort by concept -> CSVLoader, one JVM per partition
  lane3  picsureloader-split      splitter -> PICSURELoader (unsorted input, one JVM over all partitions)

Both loaders write next to their input, so every flow works on copies or links inside work/ and ends with a clean `store/`:
the key and one directory per loaded partition holding allObservationsStore.javabin, columnMeta.javabin and columnMeta.csv.
That directory is what an HPDS container mounts at /opt/local/hpds.
"""
from __future__ import annotations

import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))  # the pic-sure checkout
WORK = os.environ.get("WORK_DIR") or os.path.join(HERE, "work")
LOADER_JARS = os.environ.get("LOADER_JAR_DIR") or os.path.join(ROOT, "services", "pic-sure-hpds", "docker", "pic-sure-hpds-etl")
SPLITTER_JAR = os.environ.get("SPLITTER_JAR") or os.path.join(WORK, "src", "hpds-etl", "target", "hpds-etl.jar")
LOADER_XMX = os.environ.get("LOADER_XMX", "2g")
SPLITTER_XMX = os.environ.get("SPLITTER_XMX", "2g")
PROBE_XMX = os.environ.get("PROBE_XMX", "512m")
SPLIT_MAX_REJECT = os.environ.get("SPLIT_MAX_REJECT", "0.05")
STORE_FILES = ("allObservationsStore.javabin", "columnMeta.javabin", "columnMeta.csv")

FLOWS = {
    "lane1": "picsureloader-combined",
    "lane2": "csvloader-split",
    "lane3": "picsureloader-split",
}
FLOW_NAMES = list(FLOWS.values())

# kind "combined": one merged file that the splitter splits. kind "split": already split, the splitter is not involved.
PHENO_1000G = os.path.join(ROOT, "services", "pic-sure-hpds", "service", "src", "test", "resources", "phenotypic")
DATASETS = {
    "mini": {"kind": "split", "source": os.path.join(HERE, "fixtures", "mini", "split"), "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
    "edge": {"kind": "split", "source": os.path.join(HERE, "fixtures", "edge", "split"), "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
    "1000g": {"kind": "split", "source": PHENO_1000G, "key": os.path.join(PHENO_1000G, "encryption_key")},
    "combined": {"kind": "combined", "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
    "synth": {"kind": "combined", "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
}


LANE1_MODES = ("auto", "require", "off")
COMBINED_FLOW = "picsureloader-combined"


def lane1_mode():
    mode = (os.environ.get("LANE1") or "auto").strip().lower()
    if mode not in LANE1_MODES:
        raise SystemExit("LANE1=%s: one of %s" % (mode, ", ".join(LANE1_MODES)))
    return mode


def work_dir_problem(work=None, stack=None):
    """Why `work` may not be deleted from, or None. Mirrors the Makefile guard: the resolved path must end in /tools/local-stack/work
    and be the `work` directory of the resolved stack directory; a symlink is refused (its target is not ours to delete)."""
    work = WORK if work is None else work
    stack = HERE if stack is None else stack
    if os.path.islink(work):
        return "%s is a symlink (to %s)" % (work, os.path.realpath(work))
    real, stack_real = os.path.realpath(work), os.path.realpath(stack)
    if not real.endswith("/tools/local-stack/work"):
        return "%s (resolved: %s) does not end in /tools/local-stack/work" % (work, real)
    if real != os.path.join(stack_real, "work"):
        return "%s (resolved: %s) is not the work directory of %s" % (work, real, stack_real)
    return None


def rmtree(path):
    """shutil.rmtree for something under WORK, after the same check as `make clean`: refuses (SystemExit) when WORK is not
    .../tools/local-stack/work or the path is not inside it. A symlink at `path` is unlinked, never followed."""
    problem = work_dir_problem()
    if problem:
        raise SystemExit("refusing to delete %s: WORK_DIR %s" % (path, problem))
    real_work = os.path.realpath(WORK)
    real = os.path.join(os.path.realpath(os.path.dirname(path)), os.path.basename(path))  # the entry itself is not resolved
    if not real.startswith(real_work + os.sep):
        raise SystemExit("refusing to delete %s: not inside %s" % (path, real_work))
    if os.path.islink(path):
        os.unlink(path)
    else:
        shutil.rmtree(path, ignore_errors=True)


def dpath(dataset, *parts):
    return os.path.join(WORK, dataset, *parts)


def say(msg):
    print(msg, flush=True)


def flow_name(flow):
    return FLOWS.get(flow, flow)


def applicable_flows(dataset):
    kind = DATASETS[dataset]["kind"]
    return FLOW_NAMES if kind == "combined" else ["csvloader-split", "picsureloader-split"]


def run(cmd, log_path, cwd=None, env=None):
    """Run a command with stdout+stderr in log_path; return (exit code, seconds)."""
    t0 = time.time()
    with open(log_path, "ab") as log:
        log.write(("$ %s\n" % " ".join(cmd)).encode())
        log.flush()
        rc = subprocess.call(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=cwd, env=env)
    return rc, time.time() - t0


def tree_bytes(path):
    total = 0
    for dp, _, files in os.walk(path):
        for f in files:
            total += os.path.getsize(os.path.join(dp, f))
    return total


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(data, f, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)


# ----------------------------------------------------------------------------------------------------------------------------


def prepare(dataset):
    """Create work/DATASET/input (combined) or work/DATASET/split (split datasets) and work/DATASET/encryption_key."""
    spec = DATASETS[dataset]
    base = dpath(dataset)
    os.makedirs(base, exist_ok=True)
    for flow in FLOW_NAMES:  # stores of an earlier input must not be served or compared against the new one
        rmtree(dpath(dataset, flow))
    if os.path.exists(dpath(dataset, "requested-flows.json")):
        os.remove(dpath(dataset, "requested-flows.json"))
    shutil.copyfile(spec["key"], dpath(dataset, "encryption_key"))
    if spec["kind"] == "split":
        split = dpath(dataset, "split")
        rmtree(split)
        os.makedirs(split)
        src = spec["source"]
        n = 0
        for f in sorted(os.listdir(src)):
            if f.endswith("-allConcepts.csv") or f == "split_manifest.json":
                os.symlink(os.path.realpath(os.path.join(src, f)), os.path.join(split, f))
                n += f.endswith("-allConcepts.csv")
        say("prepare %s: %d split files linked from %s" % (dataset, n, os.path.relpath(src, ROOT)))
        return 0
    inp = dpath(dataset, "input")
    rmtree(inp)
    os.makedirs(inp)
    out = os.path.join(inp, "allConcepts_data_analyzer.csv")
    if dataset == "combined":
        cmd = [sys.executable, os.path.join(HERE, "gen-combined.py"), out]
    else:
        cmd = [sys.executable, os.path.join(HERE, "gen-synth.py"),
               "--patients", os.environ.get("PATIENTS", "200"), "--concepts", os.environ.get("CONCEPTS", "12"),
               "--seed", os.environ.get("SEED", "7"), out]
    return subprocess.call(cmd)


def split(dataset):
    rc = prepare(dataset)
    if rc:
        return rc
    spec = DATASETS[dataset]
    if spec["kind"] == "split":
        write_json(dpath(dataset, "split.json"), {"dataset": dataset, "status": "not-needed", "reason": "dataset is already split"})
        return 0
    if not os.path.exists(SPLITTER_JAR):
        sys.stderr.write("splitter jar not found: %s (run make jars, or set SPLITTER_JAR)\n" % SPLITTER_JAR)
        return 1
    out = dpath(dataset, "split")
    rmtree(out)
    log = dpath(dataset, "split.log")
    if os.path.exists(log):
        os.remove(log)
    cmd = ["java", "-Xmx" + SPLITTER_XMX, "-jar", SPLITTER_JAR, "--job=split-allconcepts-by-consent",
           "--input=" + os.path.join(dpath(dataset, "input"), "allConcepts_data_analyzer.csv"), "--output=" + out,
           "--fence-mapping=none", "--globals=by-patient", "--partition-naming=suffix", "--timestamps=convert-iso",
           "--zero-timestamps=blank", "--bad-rows=quarantine", "--max-reject-fraction=" + SPLIT_MAX_REJECT, "--force"]
    # the job writes its report to ./reports: keep that inside work/
    rc, secs = run(cmd, log, cwd=dpath(dataset))
    info = {"dataset": dataset, "command": " ".join(cmd), "exit_code": rc, "wall_seconds": round(secs, 2)}
    mpath = os.path.join(out, "split_manifest.json")
    if os.path.exists(mpath):
        with open(mpath) as f:
            m = json.load(f)
        info["manifest"] = {"rows": m.get("rows"), "partitions": m.get("partitions"), "repairs": m.get("repairs")}
    info["status"] = "ok" if rc in (0,) and os.path.exists(mpath) else "failed"
    write_json(dpath(dataset, "split.json"), info)
    n = len([f for f in os.listdir(out) if f.endswith("-allConcepts.csv")]) if os.path.isdir(out) else 0
    say("split %s: exit %d in %.1fs, %d partitions (%s)" % (dataset, rc, secs, n, os.path.relpath(log, HERE)))
    return 0 if info["status"] == "ok" else 1


# ----------------------------------------------------------------------------------------------------------------------------


def partition_files(split_dir):
    suffix = "-allConcepts.csv"
    return sorted((f[: -len(suffix)], os.path.join(split_dir, f)) for f in os.listdir(split_dir) if f.endswith(suffix))


def has_store(directory):
    return all(os.path.isfile(os.path.join(directory, f)) for f in STORE_FILES)


def jar(name):
    return os.path.join(LOADER_JARS, name + "-jar-with-dependencies.jar")


PROBE_ROWS = [
    ["1", "\\_consents\\", "", "phs000000.c1", "0"],
    ["2", "\\_consents\\", "", "phs000000.c2", "0"],
    ["1", "\\phs000000\\t\\num\\", "5", "", "0"],
    ["2", "\\phs000000\\t\\cat\\", "", "Yes", "0"],
]


def jar_has_option(path, needle=b"partitionBy"):
    """Static check: the option name is a string constant of some class in the jar. A jar without T25b does not know the option."""
    try:
        with zipfile.ZipFile(path) as z:
            return any(n.endswith(".class") and needle in z.read(n) for n in z.namelist())
    except (OSError, zipfile.BadZipFile):
        return False


def tail(data, lines=12, chars=800):
    text = data.decode("utf-8", errors="replace").strip() if data else ""
    return "\n".join(text.splitlines()[-lines:])[-chars:]


def lane1_probe():
    """(state, reason): state is "ok", "skip" (the jar has no combined-input option, so lane 1 does not apply) or "fail" (the jar has
    the option and the probe did not work: broken jar, crash, timeout, wrong output). Never a silent skip for the last.

    Static check first: the `partitionBy` option name must be in the jar. Then the jar runs on a tiny combined file with
    -Dpicsure.loader.input and -Dpicsure.loader.partitionBy=consent into a temporary base under work/, and must exit 0 and write a
    store per consent."""
    path = jar("PICSURELoader")
    if not os.path.exists(path):
        return "skip", "PICSURELoader jar not found: %s" % path
    if not jar_has_option(path):
        return "skip", "PICSURELoader without combined input"
    os.makedirs(WORK, exist_ok=True)
    base = tempfile.mkdtemp(prefix=".lane1-probe-", dir=WORK)
    try:
        inp = os.path.join(base, "probe-input.csv")
        with open(inp, "w", newline="") as f:
            csv.writer(f, quoting=csv.QUOTE_ALL, lineterminator="\n").writerows(PROBE_ROWS)
        out = os.path.join(base, "out")
        os.makedirs(out)
        shutil.copyfile(DATASETS["combined"]["key"], os.path.join(out, "encryption_key"))
        cmd = ["java", "-Xmx" + PROBE_XMX, "-Dpicsure.loader.failFast=false", "-Dpicsure.loader.input=" + inp,
               "-Dpicsure.loader.partitionBy=consent", "-jar", path, out]
        try:
            r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        except (OSError, subprocess.TimeoutExpired) as e:
            return "fail", "probe did not run: %s" % e
        stores = [d for d in ("phs000000.c1", "phs000000.c2") if has_store(os.path.join(out, d))]
        if r.returncode == 0 and len(stores) == 2:
            return "ok", ""
        why = "PICSURELoader has the combined-input option but the probe failed (rc=%s, %d of 2 stores)" % (r.returncode, len(stores))
        t = tail(r.stdout)
        return "fail", why + (": " + t if t else "")
    finally:
        shutil.rmtree(base, ignore_errors=True)  # the probe's own mkdtemp


def picsureloader_supports_combined_input():
    """(capable, reason): lane1_probe() as a pair. Not capable covers both a jar without the option and a probe that failed."""
    state, reason = lane1_probe()
    return state == "ok", reason


def lane1_state():
    """(state, reason) for lane 1 here: "ok"; "off" (LANE1=off); "skip" (jar without the option); "fail" (probe failed)."""
    if lane1_mode() == "off":
        return "off", "LANE1=off"
    return lane1_probe()


def lane1_unavailable():
    """None when lane 1 would run here, else the reason it cannot or may not (LANE1=off, a jar without combined input, or a failed
    probe)."""
    state, reason = lane1_state()
    return None if state == "ok" else reason


def load_status(rc, loaded, failed):
    """ok only when the loader exited 0, something was loaded and no partition failed; partial when only some loaded."""
    if rc == 0 and loaded and not failed:
        return "ok"
    return "partial" if loaded else "failed"


def check_sortable(path):
    """(non-empty lines, CSV records): the runner's check before sorting. They differ when a quoted field holds a line break, which
    `sort` would tear apart. Blank lines are not records (commons-csv ignores them)."""
    lines = 0
    with open(path, "rb") as f:
        for line in f:
            if line.rstrip(b"\r\n") != b"":
                lines += 1
    records = 0
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for row in csv.reader(f):
            if row:
                records += 1
    return lines, records


def request(dataset, flows):
    """Record the flows this run asked for; compare.py lists the applicable flows that were not asked for as 'not run'."""
    flows = [flow_name(f) for f in flows]
    if not flows:
        sys.stderr.write("request: no flows\n")
        return 2
    bad = [f for f in flows if f not in applicable_flows(dataset)]
    if bad:
        sys.stderr.write("%s: flow(s) %s do not apply to dataset %s (flows: %s)\n" % (
            "request", ", ".join(bad), dataset, " ".join(applicable_flows(dataset))))
        return 2
    os.makedirs(dpath(dataset), exist_ok=True)
    write_json(dpath(dataset, "requested-flows.json"), {"dataset": dataset, "flows": flows})
    return 0


def load(dataset, flow):
    flow = flow_name(flow)
    if flow not in FLOW_NAMES:
        sys.stderr.write("unknown flow %s (one of %s)\n" % (flow, ", ".join(FLOW_NAMES)))
        return 2
    lane = os.path.join(WORK, dataset, flow)
    rmtree(lane)
    os.makedirs(lane)
    result = {"dataset": dataset, "flow": flow, "started": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "loader_xmx": LOADER_XMX}

    def finish(status, rc, secs, loaded, failed, extra=None, msg=None):
        store = os.path.join(lane, "store")
        result.update({"status": status, "exit_code": rc, "wall_seconds": round(secs, 2), "partitions_loaded": sorted(loaded),
                       "partitions_failed": sorted(failed), "store_bytes": tree_bytes(store) if os.path.isdir(store) else 0})
        if extra:
            result.update(extra)
        write_json(os.path.join(lane, "load.json"), result)
        say("%s %s: %s, exit %s, %.1fs, %d loaded, %d failed, store %.1f KiB%s" % (
            dataset, flow, status, rc, secs, len(loaded), len(failed), result["store_bytes"] / 1024.0, (" (" + msg + ")") if msg else ""))
        return 0

    try:
        if flow == "picsureloader-combined":
            if DATASETS[dataset]["kind"] != "combined":
                result["reason"] = "dataset is already split"
                say("%s lane1 skipped: dataset is already split" % dataset)
                return finish("skipped", None, 0, [], [], msg="dataset is already split")
            state, why = lane1_state()
            if state == "fail":  # the jar has the option and the probe failed: a failure whatever LANE1 says, never a skip
                result["reason"] = why
                say("%s lane1 FAILED: %s" % (dataset, why))
                return finish("failed", None, 0, [], [], msg=why)
            if state != "ok":
                result["reason"] = why
                if lane1_mode() == "require":
                    say("%s lane1 FAILED: LANE1=require and %s" % (dataset, why))
                    return finish("failed", None, 0, [], [], msg="LANE1=require: " + why)
                say("%s lane1 skipped: %s" % (dataset, why))
                return finish("skipped", None, 0, [], [], msg=why)
            return load_picsureloader_combined(dataset, lane, result, finish)
        if not os.path.isdir(dpath(dataset, "split")):
            sys.stderr.write("no split directory for %s: run split first\n" % dataset)
            return 1
        if flow == "csvloader-split":
            return load_csvloader(dataset, lane, result, finish)
        return load_picsureloader_split(dataset, lane, result, finish)
    except FileNotFoundError as e:
        sys.stderr.write("%s\n" % e)
        return 1


def new_store(dataset, lane):
    store = os.path.join(lane, "store")
    os.makedirs(store)
    shutil.copyfile(dpath(dataset, "encryption_key"), os.path.join(store, "encryption_key"))
    return store


def load_csvloader(dataset, lane, result, finish):
    """Per partition: sorted copy + key in its own base directory, one CSVLoader JVM, then the three files go to store/."""
    store = new_store(dataset, lane)
    log = os.path.join(lane, "load.log")
    t0 = time.time()
    loaded, failed, rcs = [], [], {}
    for part, path in partition_files(dpath(dataset, "split")):
        base = os.path.join(lane, "base", part)
        os.makedirs(base)
        shutil.copyfile(dpath(dataset, "encryption_key"), os.path.join(base, "encryption_key"))
        # CSVLoader keeps 16 concepts in memory and silently loses data on input that is not grouped by concept
        env = dict(os.environ, LC_ALL="C")
        lines, records = check_sortable(path)
        if lines != records:  # as pipelines/hpds-pheno-load/entrypoint.sh: embedded line breaks cannot be sorted safely
            with open(log, "a") as lg:
                lg.write("%s: %d non-empty lines but %d CSV records (embedded line breaks cannot be sorted safely)\n" % (part, lines, records))
            rc = 1
        else:
            rc, _ = run(["sort", "-t,", "-k2", "-o", os.path.join(base, part + "-allConcepts.csv"), os.path.realpath(path)], log, env=env)
        if rc == 0:
            rc, _ = run(["java", "-Xmx" + LOADER_XMX, "-jar", jar("CSVLoader"), base], log)
        rcs[part] = rc
        out = os.path.join(base, part)
        if rc == 0 and has_store(out):
            os.makedirs(os.path.join(store, part))
            for f in STORE_FILES:
                shutil.move(os.path.join(out, f), os.path.join(store, part, f))
            loaded.append(part)
        else:
            failed.append(part)
        rmtree(base)
    rmtree(os.path.join(lane, "base"))
    secs = time.time() - t0
    rc = 0 if not failed else 1
    return finish(load_status(rc, loaded, failed), rc, secs, loaded, failed,
                  {"loader": "CSVLoader", "partition_exit_codes": rcs, "sorted": "LC_ALL=C sort -t, -k2"})


def summarize_ingest(base):
    path = os.path.join(base, "ingest-summary.json")
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        s = json.load(f)
    parts = {}
    for p in s.get("partitions", []):
        parts[p["name"]] = {k: p.get(k) for k in ("status", "rowsRead", "rowsRejected", "rowsKeptWithWarning", "observationsWritten",
                                                  "concepts", "patients")}
    return {"ingest_status": s.get("status"), "ingest_partitions": parts}


def collect_picsureloader(base, store):
    loaded, failed = [], []
    summary = summarize_ingest(base)
    names = set(summary.get("ingest_partitions", {}))
    for entry in sorted(os.listdir(base)):
        d = os.path.join(base, entry)
        if os.path.isdir(d) and not entry.startswith("."):
            names.add(entry)
    for part in sorted(names):
        d = os.path.join(base, part)
        if os.path.isdir(d) and has_store(d):
            os.makedirs(os.path.join(store, part))
            for f in STORE_FILES:
                shutil.move(os.path.join(d, f), os.path.join(store, part, f))
            loaded.append(part)
        else:
            failed.append(part)
    return loaded, failed, summary


def load_picsureloader_split(dataset, lane, result, finish):
    store = new_store(dataset, lane)
    base = os.path.join(lane, "base")
    os.makedirs(base)
    shutil.copyfile(dpath(dataset, "encryption_key"), os.path.join(base, "encryption_key"))
    for part, path in partition_files(dpath(dataset, "split")):
        os.symlink(os.path.realpath(path), os.path.join(base, part + "-allConcepts.csv"))
    log = os.path.join(lane, "load.log")
    # failFast=false: load every partition that can be loaded, the exit code is non-zero if one is not
    cmd = ["java", "-Xmx" + LOADER_XMX, "-Dpicsure.loader.failFast=false", "-jar", jar("PICSURELoader"), base]
    rc, secs = run(cmd, log)
    loaded, failed, summary = collect_picsureloader(base, store)
    if os.path.exists(os.path.join(base, "ingest-summary.json")):
        shutil.copyfile(os.path.join(base, "ingest-summary.json"), os.path.join(lane, "ingest-summary.json"))
    rmtree(base)
    extra = {"loader": "PICSURELoader", "command": " ".join(cmd)}
    extra.update(summary)
    return finish(load_status(rc, loaded, failed), rc, secs, loaded, failed, extra)


def load_picsureloader_combined(dataset, lane, result, finish):
    store = new_store(dataset, lane)
    base = os.path.join(lane, "base")
    os.makedirs(base)
    shutil.copyfile(dpath(dataset, "encryption_key"), os.path.join(base, "encryption_key"))
    inp = os.path.realpath(os.path.join(dpath(dataset, "input"), "allConcepts_data_analyzer.csv"))
    log = os.path.join(lane, "load.log")
    opts = ["-Dpicsure.loader.failFast=false", "-Dpicsure.loader.input=" + inp, "-Dpicsure.loader.partitionBy=consent"]
    opts += os.environ.get("LANE1_EXTRA_OPTS", "-Dpicsure.loader.maxRejectedRows=10").split()
    cmd = ["java", "-Xmx" + LOADER_XMX] + opts + ["-jar", jar("PICSURELoader"), base]
    rc, secs = run(cmd, log)
    loaded, failed, summary = collect_picsureloader(base, store)
    if os.path.exists(os.path.join(base, "ingest-summary.json")):
        shutil.copyfile(os.path.join(base, "ingest-summary.json"), os.path.join(lane, "ingest-summary.json"))
    rmtree(base)
    extra = {"loader": "PICSURELoader", "command": " ".join(cmd)}
    extra.update(summary)
    return finish(load_status(rc, loaded, failed), rc, secs, loaded, failed, extra)


# ----------------------------------------------------------------------------------------------------------------------------


def main(argv):
    if len(argv) == 3 and argv[1] == "guard-work":  # the Makefile guard: lane.py guard-work <path>
        problem = work_dir_problem(argv[2])
        if problem:
            sys.stderr.write("refusing to touch %s\n" % problem)
            return 1
        return 0
    if len(argv) < 3 or argv[1] not in ("prepare", "split", "load", "flows", "request"):
        sys.stderr.write(__doc__)
        return 2
    cmd, dataset = argv[1], argv[2]
    if dataset not in DATASETS:
        sys.stderr.write("unknown dataset %s (one of %s)\n" % (dataset, ", ".join(sorted(DATASETS))))
        return 2
    if cmd == "prepare":
        return prepare(dataset)
    if cmd == "split":
        return split(dataset)
    if cmd == "flows":
        print(" ".join(applicable_flows(dataset)))
        return 0
    if cmd == "request":
        return request(dataset, argv[3:])
    if len(argv) != 4:
        sys.stderr.write("usage: lane.py load DATASET FLOW\n")
        return 2
    return load(dataset, argv[3])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
