"""Builds the synthetic data dictionary of a dataset: SQL for the `dict` schema of the picsure-dictionary service. Standard library only.

  dictgen.py build DATASET [--out DIR]   write DIR/schema.sql and DIR/data.sql (default DIR: work/DATASET/dict) and DIR/dict.json
  dictgen.py schema [--out FILE]         only the schema (services/picsure-dictionary/db/schema.sql and flyway/V*.sql, in version order)

The content follows the same ground truth as compare.py (pheno.py, computed from the input, independent of the loaders), so the
dictionary matches the partitions of the dataset by construction:

  - one dataset per study (ref `phs000000`; the first node of the concept paths), one consent row per partition (`c1` of
    `phs000000.c1`; a partition without a dot, such as `tutorial-biolincc_test`, has the empty consent code the service matches on the
    dataset ref itself)
  - one concept node per concept path of the study, interior nodes for the path prefixes, a `values` meta entry (a JSON list of the
    categories, or [min, max] for a numeric concept) so the service lists it, and min / max meta
  - the global `\\DCC Harmonized data set\\...` concepts under the dataset `DCC Harmonized data set`, linked by dataset_harmonization to
    every study (the service returns them to a user who holds a consent of a harmonized study)
  - the other global nodes of the input (`\\_consents\\`, `\\unconsented_study\\`) are not dictionary entries

A real dictionary comes from the dictionary ETL and is restored from a dump instead (DICT_DUMP, see the README).
"""
from __future__ import annotations

import json
import os
import re
import sys
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pheno

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SERVICE_DIR = os.path.join(ROOT, "services", "picsure-dictionary")
HARMONIZED = "DCC Harmonized data set"


def q(value):
    """A SQL string literal (standard_conforming_strings is on: a backslash is a plain character)."""
    return "'" + str(value).replace("'", "''") + "'"


def consent_code(partition):
    return partition.split(".", 1)[1] if "." in partition else ""


def schema_sql(service_dir=SERVICE_DIR):
    """The schema the service expects: db/schema.sql, then the flyway migrations in version order."""
    parts = [os.path.join(service_dir, "db", "schema.sql")]
    flyway = os.path.join(service_dir, "db", "flyway")

    def version(name):
        m = re.match(r"V(\d+)__", name)
        return int(m.group(1)) if m else None

    migrations = sorted((version(f), f) for f in os.listdir(flyway) if f.endswith(".sql") and version(f) is not None)
    parts += [os.path.join(flyway, f) for _, f in migrations]
    out = []
    for p in parts:
        with open(p, "r", encoding="utf-8") as f:
            out.append(f"-- {os.path.relpath(p, service_dir)}\n{f.read()}\n")
    return "\n".join(out)


def classify(path, studies):
    """('study', <study>) for a path under a study node, ('harmonized', HARMONIZED) for the harmonized data set, else (None, None)."""
    node = pheno.segment(path)
    if node in studies:
        return "study", node
    if node == HARMONIZED:
        return "harmonized", HARMONIZED
    return None, None


def collect(truth):
    """truth: partition -> PartitionTruth. Returns (studies, concepts, counts).

    studies: study -> [partitions]; concepts: dataset ref -> {path: {"numeric", "values", "min", "max"}};
    counts: partition -> {"participants", "variables"}.
    """
    studies = OrderedDict()
    for p in sorted(truth):
        studies.setdefault(pheno.study_of(p), []).append(p)
    concepts = OrderedDict()
    counts = OrderedDict()
    for p in sorted(truth):
        n = 0
        for path, c in truth[p].concepts.items():
            kind, ref = classify(path, studies)
            if kind is None:
                continue
            e = concepts.setdefault(ref, OrderedDict()).setdefault(path, {"numeric": c.numeric, "values": set(), "nums": []})
            if c.numeric:
                e["nums"] += [v for _, v in c.obs]
            else:
                e["values"] |= {v for _, v in c.obs}
            n += kind == "study"
        counts[p] = {"participants": len(truth[p].patients), "variables": n}
    return studies, concepts, counts


def ancestors(path):
    """The interior paths above a concept path: \\a\\b\\c\\ -> [\\a\\, \\a\\b\\]."""
    parts = [x for x in path.split("\\") if x != ""]
    return ["\\" + "\\".join(parts[:i]) + "\\" for i in range(1, len(parts))]


def values_blob(e):
    if e["numeric"]:
        nums = e["nums"] or [0.0]
        return json.dumps([min(nums), max(nums)]), min(nums), max(nums)
    return json.dumps(sorted(e["values"])), None, None


def data_sql(truth):
    """(sql, summary) for the data of the dict schema. Needs the schema of schema_sql() first."""
    studies, concepts, counts = collect(truth)
    lines = ["-- synthetic dictionary data: see dictgen.py", "BEGIN;"]
    for ref in list(studies) + ([HARMONIZED] if HARMONIZED in concepts else []):
        name = (f"Synthetic study {ref}") if ref in studies else ref
        lines.append("INSERT INTO dict.dataset (ref, full_name, abbreviation, description) VALUES ({}, {}, {}, {});".format(q(ref), q(name), q(ref), q("Synthetic data for the local stack.")))
    for study, parts in studies.items():
        for p in parts:
            lines.append(
                "INSERT INTO dict.consent (dataset_id, consent_code, description, participant_count, variable_count, sample_count, authz) "
                f"SELECT dataset_id, {q(consent_code(p))}, {q(f'Synthetic consent group {p}')}, {counts[p]['participants']}, "
                f"{counts[p]['variables']}, 0, {q(chr(92) + '_consents' + chr(92) + p)} FROM dict.dataset WHERE ref = {q(study)};")
    n_concepts = 0
    for ref, paths in concepts.items():
        leaves = set(paths)
        interior = sorted({a for path in paths for a in ancestors(path)} - leaves, key=lambda x: (x.count("\\"), x))
        ds = f"(SELECT dataset_id FROM dict.dataset WHERE ref = {q(ref)})"
        nodes = [(a, "Interior", None) for a in interior]
        nodes += [(path, "Continuous" if paths[path]["numeric"] else "Categorical", paths[path]) for path in sorted(paths, key=lambda x: (x.count("\\"), x))]
        nodes.sort(key=lambda n: (n[0].count("\\"), n[0]))
        for path, ctype, e in nodes:
            parts = [x for x in path.split("\\") if x != ""]
            name = parts[-1]
            parent = ancestors(path)[-1] if len(parts) > 1 else None
            parent_sql = f"(SELECT concept_node_id FROM dict.concept_node WHERE concept_path = {q(parent)})" if parent else "NULL"
            lines.append(f"INSERT INTO dict.concept_node (dataset_id, name, display, concept_type, concept_path, parent_id) VALUES ({ds}, {q(name)}, {q(name)}, {q(ctype)}, {q(path)}, {parent_sql});")
            n_concepts += 1
            if e is not None:
                blob, lo, hi = values_blob(e)
                metas = [("values", blob)]
                if lo is not None:
                    metas += [("min", repr(float(lo))), ("max", repr(float(hi)))]
                for k, v in metas:
                    lines.append(f"INSERT INTO dict.concept_node_meta (concept_node_id, key, value) SELECT concept_node_id, {q(k)}, {q(v)} FROM dict.concept_node WHERE concept_path = {q(path)};")
    if HARMONIZED in concepts:
        for study in studies:
            lines.append("INSERT INTO dict.dataset_harmonization (harmonized_dataset_id, source_dataset_id) "
                         f"SELECT h.dataset_id, s.dataset_id FROM dict.dataset h, dict.dataset s WHERE h.ref = {q(HARMONIZED)} AND s.ref = {q(study)};")
    lines.append("UPDATE dict.concept_node SET searchable_fields = to_tsvector('english', replace(coalesce(display, '') || ' ' || concept_path, '_', ' '));")
    lines.append("COMMIT;")
    summary = {
        "studies": {s: parts for s, parts in studies.items()},
        "datasets": list(studies) + ([HARMONIZED] if HARMONIZED in concepts else []),
        "concepts_per_dataset": {ref: len(paths) for ref, paths in concepts.items()},
        "concept_nodes": n_concepts,
        "consents": [f"{p}" for parts in studies.values() for p in parts],
    }
    return "\n".join(lines) + "\n", summary


def load_truth(dataset, work=None):
    """The truth of a dataset prepared by lane.py (work/DATASET/input or work/DATASET/split)."""
    work = work or os.environ.get("WORK_DIR") or os.path.join(HERE, "work")
    inp = os.path.join(work, dataset, "input", "allConcepts_data_analyzer.csv")
    if os.path.exists(inp):
        return pheno.truth_from_combined(inp)[0]
    split = os.path.join(work, dataset, "split")
    if os.path.isdir(split):
        return pheno.truth_from_split_dir(split)
    raise SystemExit(f"no input for dataset {dataset} under {work}: run make split DATASET={dataset}")


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def main(argv):
    if len(argv) < 2 or argv[1] not in ("build", "schema"):
        sys.stderr.write(__doc__)
        return 2
    out = None
    args = list(argv[2:])
    if "--out" in args:
        i = args.index("--out")
        out = args[i + 1]
        del args[i:i + 2]
    if argv[1] == "schema":
        write(out or os.path.join(HERE, "work", "dict", "schema.sql"), schema_sql())
        return 0
    if len(args) != 1:
        sys.stderr.write("usage: dictgen.py build DATASET [--out DIR]\n")
        return 2
    dataset = args[0]
    work = os.environ.get("WORK_DIR") or os.path.join(HERE, "work")
    out = out or os.path.join(work, dataset, "dict")
    sql, summary = data_sql(load_truth(dataset, work))
    write(os.path.join(out, "schema.sql"), schema_sql())
    write(os.path.join(out, "data.sql"), sql)
    write(os.path.join(out, "dict.json"), json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(f"dict {dataset}: {len(summary['datasets'])} datasets, {summary['concept_nodes']} concept nodes ({os.path.relpath(out, HERE)})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
