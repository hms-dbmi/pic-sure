"""Deterministic synthetic data in the shape of the merged allConcepts file (allConcepts_data_analyzer.csv).

Five quoted columns and no header: patient_num, concept_path, nval, tval, timestamp. Everything is made up. Study accessions are
the placeholders phs000000, phs999999 and phs123456 (plus the bare consent tutorial-biolincc_test); patient numbers start at 1000.

The same function backs gen-combined.py (the fixed `combined` dataset) and gen-synth.py (a sized `synth` dataset), so both carry
the features the splitter and the loaders have to cope with:

- `\\_consents\\` rows (tval = one consent string per row), a patient in two consents of one study, a patient in two studies, patients
  without any consent (their rows are dropped by the splitter)
- global concepts (`\\DCC Harmonized data set\\...`), copied into every partition of a patient by `--globals=by-patient`
- a node that is not a study in `_consents` (`\\unconsented_study\\...`), handled as a global
- numeric concepts (nval), categorical concepts (tval), numbers written in tval, text with a comma and quotes, repeated observations
- timestamps as ISO-8601, epoch milliseconds, 0 and empty
- one header row in the middle of the file (EMBEDDED_HEADER) and one `2402L` nval (BAD_NVAL): the splitter quarantines both
- rows in random order (the loaders must not depend on the input being sorted by concept)
- a few rows whose value is empty (no observation), only on categorical concepts (see README: CSVLoader types a concept by its first
  sorted row)
- concepts whose value is also the last path node (`\\study\\table\\var\\Name\\Yes\\` with tval `Yes`), which both loaders roll up
"""
from __future__ import annotations

import csv
import io
import random

CONSENTS_PATH = "\\_consents\\"

# (study node, consent strings). The last study is a bare consent, as in the real data (tutorial-biolincc_*).
STUDIES = [
    ("phs000000", ["phs000000.c1", "phs000000.c2"]),
    ("phs999999", ["phs999999.c1"]),
    ("phs123456", ["phs123456.c1"]),
    ("tutorial-biolincc_test", ["tutorial-biolincc_test"]),
]

CATEGORIES = [
    ["Yes", "No"],
    ["Never", "Former", "Current"],
    ["Low", "Medium", "High"],
    ['Said "maybe", twice', "Yes", "No"],
    ["Left", "Right"],
]

ISO_TIMESTAMPS = [
    "2023-06-06T15:20:00Z",
    "2023-06-06T17:20:00+02:00",
    "2023-06-06T15:20:00",
    "2023-11-25 14:00:00",
    "2023-06-06",
]


def _concept_defs(study, n_concepts):
    """Concepts of one study: a stable list of (path, kind, detail)."""
    defs = []
    for i in range(n_concepts):
        table = "pht%06d" % (1 + i // 5)
        var = "phv%08d" % (i + 1)
        kind = ("numeric", "categorical", "numeric", "numeric_tval", "categorical")[i % 5]
        name = "Var %02d" % (i + 1)
        if kind == "categorical":
            values = CATEGORIES[i % len(CATEGORIES)]
            if i % 7 == 1:
                # the value is also the last path node (dbGaP style): both loaders drop that node from the concept path
                path = "\\%s\\%s\\%s\\%s\\" % (study, table, var, name)
                defs.append((path, "rollup", values))
                continue
            defs.append(("\\%s\\%s\\%s\\%s\\" % (study, table, var, name), kind, values))
        else:
            defs.append(("\\%s\\%s\\%s\\%s\\" % (study, table, var, name), kind, None))
    return defs


def _global_defs():
    return [
        ("\\DCC Harmonized data set\\age\\", "numeric", None),
        ("\\DCC Harmonized data set\\sex\\", "categorical", ["Female", "Male"]),
        ("\\DCC Harmonized data set\\race, ethnicity\\", "categorical", ["Group A", "Group B", "Group C"]),
        ("\\unconsented_study\\visit\\score\\", "numeric", None),
    ]


def _value(rng, kind, detail):
    """(nval, tval) for one observation of a concept."""
    if kind == "numeric":
        if rng.random() < 0.5:
            return ("%d" % rng.randint(1, 90), "")
        return ("%.2f" % rng.uniform(0, 100), "")
    if kind == "numeric_tval":
        return ("", "%d" % rng.randint(0, 400))
    return ("", rng.choice(detail))


def _timestamp(rng):
    r = rng.random()
    if r < 0.35:
        return "0"
    if r < 0.6:
        return ""
    if r < 0.85:
        return str(rng.randint(1_500_000_000_000, 1_700_000_000_000))
    return rng.choice(ISO_TIMESTAMPS)


def generate(patients=40, concepts=8, seed=20261018):
    """Return the file as a list of rows (each a list of five strings)."""
    if patients < 8:
        raise ValueError("patients must be at least 8 (the fixed cases need them)")
    rng = random.Random(seed)
    studies = [(s, cs, _concept_defs(s, concepts)) for s, cs in STUDIES]
    globals_ = _global_defs()
    all_consents = [c for _, cs, _ in studies for c in cs]
    rows = []

    def add(patient, path, nval, tval, ts):
        rows.append([str(patient), path, nval, tval, ts])

    for n in range(patients):
        pid = 1000 + n
        # fixed cases first, so every size has them
        if n == 0:
            mine = ["phs000000.c1", "phs000000.c2"]  # two consents of one study
        elif n == 1:
            mine = ["phs000000.c1", "phs999999.c1"]  # two studies
        elif n == 2:
            mine = []  # no consent at all
        elif n == 3:
            mine = ["tutorial-biolincc_test"]
        else:
            r = rng.random()
            if r < 0.06:
                mine = []
            elif r < 0.22:
                mine = rng.sample(all_consents, 2)
            else:
                mine = [rng.choice(all_consents)]
        for c in mine:
            add(pid, CONSENTS_PATH, "", c, "1700000000000")
        for study, consents, defs in studies:
            if not any(c in mine for c in consents):
                continue
            for path, kind, detail in defs:
                if rng.random() < 0.2:
                    continue
                reps = 2 if rng.random() < 0.1 else 1
                for _ in range(reps):
                    nval, tval = _value(rng, kind, detail)
                    row_path = path + tval + "\\" if kind == "rollup" else path
                    add(pid, row_path, nval, tval, _timestamp(rng))
                if kind == "categorical" and rng.random() < 0.03:
                    add(pid, path, "", "", "")  # empty value: no observation
        # patients without a consent get globals and a study row too; the splitter drops them
        for path, kind, detail in globals_:
            if path.startswith("\\unconsented_study") and rng.random() < 0.5:
                continue
            nval, tval = _value(rng, kind, detail)
            add(pid, path, nval, tval, _timestamp(rng))
        if not mine:
            add(pid, studies[0][2][0][0], "5", "", "0")

    rng.shuffle(rows)
    # the two rows the splitter quarantines (--bad-rows=quarantine)
    rows.insert(len(rows) // 3, ["subject_id", "concept_path", "nval_num", "tval_char", "timestamp"])
    rows.insert(len(rows) // 2, ["1001", studies[1][2][0][0], "2402L", "", "0"])
    return rows


def render(rows):
    """The rows as the text of the file: every field quoted, LF line ends."""
    buf = io.StringIO()
    w = csv.writer(buf, quoting=csv.QUOTE_ALL, lineterminator="\n")
    w.writerows(rows)
    return buf.getvalue()


def write(path, rows):
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(render(rows))
