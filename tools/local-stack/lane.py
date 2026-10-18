#!/usr/bin/env python3
"""Runs the split step and the loader lanes of the local stack. Standard library only.

  lane.py prepare DATASET          make work/DATASET/{input,split} and the key (generates combined/synth, links mini/1000g)
  lane.py split   DATASET          prepare, then run the splitter (combined/synth); writes work/DATASET/split.json
  lane.py load    DATASET FLOW     run one loader flow; writes work/DATASET/FLOW/{store,load.json,load.log}
  lane.py flows   DATASET          print the flows that apply to the dataset, one per line

Flows (named like the future runner's flows; lane numbers are used for the HPDS ports):
  lane1  picsureloader-combined   PICSURELoader reads the combined file and routes it by consent itself
  lane2  csvloader-split          splitter -> sort by concept -> CSVLoader, one JVM per partition
  lane3  picsureloader-split      splitter -> PICSURELoader (unsorted input, one JVM over all partitions)

Both loaders write next to their input, so every flow works on copies or links inside work/ and ends with a clean `store/`:
the key and one directory per loaded partition holding allObservationsStore.javabin, columnMeta.javabin and columnMeta.csv.
That directory is what an HPDS container mounts at /opt/local/hpds.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))  # the pic-sure checkout
WORK = os.environ.get("WORK_DIR") or os.path.join(HERE, "work")
LOADER_JARS = os.environ.get("LOADER_JAR_DIR") or os.path.join(ROOT, "services", "pic-sure-hpds", "docker", "pic-sure-hpds-etl")
SPLITTER_JAR = os.environ.get("SPLITTER_JAR") or os.path.join(WORK, "src", "hpds-etl", "target", "hpds-etl.jar")
LOADER_XMX = os.environ.get("LOADER_XMX", "2g")
SPLITTER_XMX = os.environ.get("SPLITTER_XMX", "2g")
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
    "1000g": {"kind": "split", "source": PHENO_1000G, "key": os.path.join(PHENO_1000G, "encryption_key")},
    "combined": {"kind": "combined", "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
    "synth": {"kind": "combined", "key": os.path.join(HERE, "fixtures", "mini", "test-only-encryption_key")},
}


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
        shutil.rmtree(dpath(dataset, flow), ignore_errors=True)
    shutil.copyfile(spec["key"], dpath(dataset, "encryption_key"))
    if spec["kind"] == "split":
        split = dpath(dataset, "split")
        shutil.rmtree(split, ignore_errors=True)
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
    shutil.rmtree(inp, ignore_errors=True)
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
    shutil.rmtree(out, ignore_errors=True)
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


def picsureloader_supports_combined_input():
    """True when the PICSURELoader jar knows the combined-file options (picsure.loader.input / partitionBy)."""
    path = jar("PICSURELoader")
    if not os.path.exists(path):
        return False
    try:
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.endswith("phenotype/picsure/LoaderConfig.class"):
                    return b"partitionBy" in z.read(name)
    except (OSError, zipfile.BadZipFile):
        return False
    return False


def load(dataset, flow):
    flow = flow_name(flow)
    if flow not in FLOW_NAMES:
        sys.stderr.write("unknown flow %s (one of %s)\n" % (flow, ", ".join(FLOW_NAMES)))
        return 2
    lane = os.path.join(WORK, dataset, flow)
    shutil.rmtree(lane, ignore_errors=True)
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
            if not picsureloader_supports_combined_input():
                say("lane1 skipped: PICSURELoader without combined input")
                result["reason"] = "PICSURELoader without combined input"
                return finish("skipped", None, 0, [], [], msg="PICSURELoader without combined input")
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
        shutil.rmtree(base, ignore_errors=True)
    shutil.rmtree(os.path.join(lane, "base"), ignore_errors=True)
    secs = time.time() - t0
    rc = 0 if not failed else 1
    return finish("ok" if not failed else "partial", rc, secs, loaded, failed,
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
    shutil.rmtree(base, ignore_errors=True)
    extra = {"loader": "PICSURELoader", "command": " ".join(cmd)}
    extra.update(summary)
    return finish("ok" if rc == 0 and not failed else "partial" if loaded else "failed", rc, secs, loaded, failed, extra)


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
    shutil.rmtree(base, ignore_errors=True)
    extra = {"loader": "PICSURELoader", "command": " ".join(cmd)}
    extra.update(summary)
    return finish("ok" if rc == 0 and loaded else "partial" if loaded else "failed", rc, secs, loaded, failed, extra)


# ----------------------------------------------------------------------------------------------------------------------------


def main(argv):
    if len(argv) < 3 or argv[1] not in ("prepare", "split", "load", "flows"):
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
    if len(argv) != 4:
        sys.stderr.write("usage: lane.py load DATASET FLOW\n")
        return 2
    return load(dataset, argv[3])


if __name__ == "__main__":
    sys.exit(main(sys.argv))
