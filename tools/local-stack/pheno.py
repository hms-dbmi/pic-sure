"""Ground truth for the local stack: what a correct load of an allConcepts file must contain.

Everything here is an independent re-implementation, in Python, of three things written in Java elsewhere, so a bug in the Java
cannot hide itself:

1. the row rules of the splitter (`split-allconcepts-by-consent`, SplitRowCheck + SplitMergedAllConceptsJob): which rows are
   quarantined and which partitions a row is written to (`--globals=by-patient`, `--bad-rows=quarantine`);
2. the row semantics shared by the CSVLoader and the PICSURELoader: concept-path normalisation, the roll-up of a last path node
   that equals the text value, numeric versus categorical, rows without a value;
3. what HPDS answers: the patients that have a concept, a categorical value, or a numeric value in [min, max] (both inclusive).

Only the standard library is used.
"""
from __future__ import annotations

import csv
import re
from collections import OrderedDict, defaultdict

CONSENTS_PATH = "\\_consents\\"
DEPTH = 1
FFFD = "\ufffd"

# Java's String.trim(): every character up to U+0020
_JAVA_WS = "".join(chr(i) for i in range(0x21))

_JAVA_DOUBLE = re.compile(r"^[+-]?(NaN|Infinity|((\d+\.?\d*|\.\d+)([eE][+-]?\d+)?)[fFdD]?)$")
_INT = re.compile(r"^[+-]?\d{1,10}$")
_TS_INT = re.compile(r"^-?[0-9]*$")
_TS_ISO = re.compile(r"^\d{4}-\d\d-\d\d([T ]\d\d:\d\d:\d\d(\.\d{1,9})?(Z|[+-]\d\d:\d\d)?)?$")
HEADER_FIELDS = 5


def java_trim(s):
    return s.strip(_JAVA_WS)


def java_double(s):
    """Double.parseDouble(s.trim()) as a float, or None when Java would throw. NaN and the infinities are returned as such."""
    t = java_trim(s)
    if not _JAVA_DOUBLE.match(t):
        return None
    if t.lstrip("+-") == "NaN":
        return float("nan")
    if t.lstrip("+-") == "Infinity":
        return float("-inf") if t.startswith("-") else float("inf")
    return float(t.rstrip("fFdD"))


def is_finite(x):
    return x is not None and x == x and x not in (float("inf"), float("-inf"))


def read_rows(path):
    """Rows of an allConcepts file as lists of strings. Invalid UTF-8 becomes U+FFFD, as Java's FileReader does. Blank lines are
    skipped (commons-csv and the splitter do the same)."""
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for row in csv.reader(f):
            if not row:
                continue
            yield row


# ----------------------------------------------------------------------------------------------------------------------------
# 1. the splitter


def split_row_problem(row, quarantine=True):
    """The reason SplitRowCheck rejects a row (BAD_FIELD_COUNT ...), or None when the row may be written. Assumes the options the
    stack uses: --timestamps=convert-iso, --bad-rows=quarantine."""
    if quarantine and len(row) == HEADER_FIELDS and _int_or_none(row[0]) is None and not any("\\" in f or "µ" in f for f in row):
        return "EMBEDDED_HEADER"
    if len(row) != HEADER_FIELDS:
        return "BAD_FIELD_COUNT"
    if _int_or_none(row[0]) is None:
        return "BAD_PATIENT_NUM"
    if row[1] == "":
        return "EMPTY_CONCEPT_PATH"
    if row[2] != "" and java_double(row[2]) is None:
        return "BAD_NVAL"
    ts = row[4]
    if _TS_INT.match(ts):
        if ts != "" and not (-(2 ** 63) <= int(ts) < 2 ** 63):
            return "BAD_TIMESTAMP"
    elif not _TS_ISO.match(ts):
        return "BAD_TIMESTAMP"
    return None


def _int_or_none(s):
    if s is None or s == "" or len(s) > 11 or not _INT.match(s):
        return None
    v = int(s)
    return v if -(2 ** 31) <= v < 2 ** 31 else None


def segment(path, depth=DEPTH):
    """The path segment at `depth` (the leading empty segment is depth 0); '' when the path is shorter."""
    parts = path.replace("µ", "\\").split("\\")
    return parts[depth] if depth < len(parts) else ""


def study_of(consent):
    return consent.split(".", 1)[0]


def route_combined(rows):
    """Route the rows of a merged allConcepts file like `--globals=by-patient` does.

    Returns an object with: partitions (OrderedDict name -> list of rows), rejected (list of (reason, row)), dropped (dict with
    study_no_consent and global_no_consent counts), consents (patient -> sorted list), header_dropped (bool).
    """
    rows = list(rows)
    header_dropped = False
    if rows and _int_or_none(rows[0][0]) is None:
        header_dropped = True
        rows = rows[1:]
    valid, rejected = [], []
    for row in rows:
        reason = split_row_problem(row)
        if reason:
            rejected.append((reason, row))
        else:
            valid.append(row)
    consents = defaultdict(set)
    for row in valid:
        if row[1].replace("µ", "\\") == CONSENTS_PATH and java_trim(row[3]):
            consents[int(row[0])].add(java_trim(row[3]))
    studies = {study_of(c) for cs in consents.values() for c in cs}
    parts = OrderedDict()
    dropped = {"study_no_consent": 0, "global_no_consent": 0}
    for row in valid:
        patient = int(row[0])
        mine = sorted(consents.get(patient, ()))
        node = segment(row[1])
        if node in studies:
            targets = [c for c in mine if study_of(c) == node]
            if not targets:
                dropped["study_no_consent"] += 1
        else:
            targets = mine
            if not targets:
                dropped["global_no_consent"] += 1
        for t in targets:
            parts.setdefault(t, []).append(row)
    return Routed(parts, rejected, dropped, {p: sorted(c) for p, c in consents.items()}, header_dropped)


class Routed(object):
    def __init__(self, partitions, rejected, dropped, consents, header_dropped):
        self.partitions = partitions
        self.rejected = rejected
        self.dropped = dropped
        self.consents = consents
        self.header_dropped = header_dropped


# ----------------------------------------------------------------------------------------------------------------------------
# 2. the loaders' row semantics


def concept_path(raw):
    """CSVParserUtil.parseConceptPath: mu becomes a backslash, every segment is trimmed, U+FFFD is removed, the path ends in one."""
    p = raw.replace("µ", "\\")
    parts = p.split("\\")
    while parts and parts[-1] == "":  # Java's String.split drops trailing empty strings
        parts.pop()
    p = "\\".join(java_trim(s) for s in parts) + "\\"
    return p.replace(FFFD, "")


class Observation(object):
    __slots__ = ("patient", "concept", "numeric", "value", "rolled_up", "fffd")

    def __init__(self, patient, concept, numeric, value, rolled_up, fffd):
        self.patient = patient
        self.concept = concept
        self.numeric = numeric
        self.value = value
        self.rolled_up = rolled_up
        self.fffd = fffd


def observation(row):
    """(Observation or None, note). note is None, 'short' (fewer than four columns: skipped), 'no_value' or 'bad_patient'.

    The truth follows the PICSURELoader (CsvObservationProducer.parseRecord):
    - nval is trimmed; a finite number in it is the numeric value (it wins over a text value). Anything else in it (empty,
      whitespace, NaN, Infinity, text) is not a number, and the row falls back to the text value
    - the text value is trimmed and has U+FFFD removed; text that parses as a finite number is a number (the legacy rule), other
      text is a categorical value; no value at all stores nothing
    The CSVLoader differs in two places, see csv_difference(): the rows with a whitespace-only nval and the non-finite numbers.
    The text value of a categorical observation has U+FFFD removed, as the PICSURELoader does. The CSVLoader keeps it in the stored
    value; `fffd` flags that case (known difference `fffd-in-text`).
    """
    if len(row) < 4:
        return None, "short"
    path = concept_path(row[1])
    tval_raw = row[3]
    fffd = FFFD in tval_raw
    tval = java_trim(tval_raw.replace(FFFD, ""))
    rolled = False
    if tval and path.endswith("\\" + tval + "\\"):
        path = path[: path[:-1].rfind("\\") + 1]
        rolled = True
    numeric = None
    nval = java_trim(row[2])
    if nval:
        d = java_double(nval)
        if is_finite(d):
            numeric = d
    if numeric is None and tval:
        d = java_double(tval)
        if is_finite(d):
            numeric = d
    patient = _int_or_none(row[0])
    if patient is None:
        return None, "bad_patient"
    if numeric is not None:
        return Observation(patient, path, True, numeric, rolled, fffd), None
    if not tval:
        return None, "no_value"
    return Observation(patient, path, False, tval, rolled, fffd), None


WS_NVAL = "whitespace-nval"
NONFINITE = "nonfinite"


def csv_difference(row):
    """(kind, concept path) when the CSVLoader treats the row differently from the PICSURELoader (and so from the truth), else None.

    CSVLoader.processRecord, read with the PICSURELoader's code next to it:
    - whitespace-nval: an nval that is only whitespace is not empty for the CSVLoader (`numericValue.isEmpty()` is on the raw cell), so
      the row is numeric, its value trims to nothing and the row is dropped, even when a text value is there. The PICSURELoader trims
      first, finds nval empty and keeps the text value (a number if it parses). With no text value both drop the row.
    - nonfinite: `Double.parseDouble` takes NaN and Infinity, so the CSVLoader stores them as numbers, whether they stand in nval or
      (with an empty nval) in the text value. The PICSURELoader accepts finite numbers only: NaN or Infinity in nval falls back to the
      text value (the row is rejected without one), in the text value it is a categorical value `NaN`.
    """
    if len(row) < 4:
        return None
    path = concept_path(row[1])
    tval = java_trim(row[3].replace(FFFD, ""))
    if tval and path.endswith("\\" + tval + "\\"):
        path = path[: path[:-1].rfind("\\") + 1]
    nval_raw, nval = row[2], java_trim(row[2])
    if nval_raw != "" and nval == "":
        return (WS_NVAL, path) if tval else None
    if nval:
        d = java_double(nval)
        if d is not None and not is_finite(d):
            return NONFINITE, path
        return None
    if tval:
        d = java_double(tval)
        if d is not None and not is_finite(d):
            return NONFINITE, path
    return None


class Concept(object):
    """One concept of one partition: type (decided by the first observation) and observations."""

    def __init__(self, numeric):
        self.numeric = numeric
        self.obs = []  # (patient, value)
        self.fffd = False
        self.fffd_values = set()  # the cleaned text of the values that carried U+FFFD
        self.mixed_dropped = 0

    @property
    def patients(self):
        return {p for p, _ in self.obs}

    def values(self):
        return sorted({v for _, v in self.obs})


class PartitionTruth(object):
    def __init__(self, name):
        self.name = name
        self.concepts = OrderedDict()
        self.patients = set()
        self.rows = 0
        self.short_rows = 0
        self.no_value_rows = 0
        self.rolled_up_rows = 0
        self.fffd_rows = 0
        self.mixed_type_dropped = 0
        self.empty_rows = {}  # concept -> rows without a value
        self.csv_differs = {WS_NVAL: {}, NONFINITE: {}}  # kind -> concept -> rows the CSVLoader treats differently

    def empty_row_concepts(self):
        """Concepts with a row without a value that are numeric, or have no value at all: where the CSVLoader can go wrong.

        The CSVLoader decides the type of a concept on the first row it reads, empty or not. A row without a value reads as text, so if
        it sorts before the numeric rows the concept becomes categorical and every numeric value is dropped (0 observations); a concept
        with only such rows is kept with 0 observations. The PICSURELoader has neither problem.
        """
        return sorted(c for c in self.empty_rows if c not in self.concepts or self.concepts[c].numeric)

    def observations(self):
        return sum(len(c.obs) for c in self.concepts.values())


def build_partition(name, rows):
    pt = PartitionTruth(name)
    for row in rows:
        pt.rows += 1
        diff = csv_difference(row)
        if diff:
            pt.csv_differs[diff[0]][diff[1]] = pt.csv_differs[diff[0]].get(diff[1], 0) + 1
        ob, note = observation(row)
        if note == "short":
            pt.short_rows += 1
            continue
        if note == "no_value":
            pt.no_value_rows += 1
            path = concept_path(row[1])
            pt.empty_rows[path] = pt.empty_rows.get(path, 0) + 1
            continue
        if ob is None:
            continue
        c = pt.concepts.get(ob.concept)
        if c is None:
            c = pt.concepts[ob.concept] = Concept(ob.numeric)
        if c.numeric != ob.numeric:
            c.mixed_dropped += 1
            pt.mixed_type_dropped += 1
            continue
        c.obs.append((ob.patient, ob.value))
        pt.patients.add(ob.patient)
        if ob.rolled_up:
            pt.rolled_up_rows += 1
        if ob.fffd and not ob.numeric:
            pt.fffd_rows += 1
            c.fffd = True
            c.fffd_values.add(ob.value)
    return pt


def build_truth(partition_rows):
    """partition_rows: mapping partition name -> iterable of rows. Returns OrderedDict name -> PartitionTruth."""
    return OrderedDict((name, build_partition(name, rows)) for name, rows in partition_rows.items())


def truth_from_split_dir(split_dir, manifest_partitions=None):
    """Truth from a directory of <partition>-allConcepts.csv files (a split dataset, no splitter involved)."""
    import os

    names = sorted(f[: -len("-allConcepts.csv")] for f in os.listdir(split_dir) if f.endswith("-allConcepts.csv"))
    return build_truth(OrderedDict((n, read_rows(os.path.join(split_dir, n + "-allConcepts.csv"))) for n in names))


def truth_from_combined(path):
    routed = route_combined(read_rows(path))
    truth = build_truth(routed.partitions)
    return truth, routed


# ----------------------------------------------------------------------------------------------------------------------------
# 3. what HPDS answers


def scope_concepts(truth, partitions):
    out = OrderedDict()
    for p in partitions:
        pt = truth.get(p)
        if pt is None:
            continue
        for path, c in pt.concepts.items():
            out.setdefault(path, []).append(c)
    return out


def count_required(truth, partitions, concept):
    pats = set()
    for p in partitions:
        pt = truth.get(p)
        if pt and concept in pt.concepts:
            pats |= pt.concepts[concept].patients
    return len(pats)


def count_values(truth, partitions, concept, values):
    want = set(values)
    pats = set()
    for p in partitions:
        pt = truth.get(p)
        if pt and concept in pt.concepts and not pt.concepts[concept].numeric:
            pats |= {pat for pat, v in pt.concepts[concept].obs if v in want}
    return len(pats)


def count_range(truth, partitions, concept, lo, hi):
    """Patients with at least one value in [lo, hi]; a None bound is open."""
    pats = set()
    for p in partitions:
        pt = truth.get(p)
        if pt and concept in pt.concepts and pt.concepts[concept].numeric:
            pats |= {pat for pat, v in pt.concepts[concept].obs if (lo is None or v >= lo) and (hi is None or v <= hi)}
    return len(pats)


def parse_column_meta(path):
    """columnMeta.csv -> {concept: {categorical, values, min, max, observations, patients, width}} (offset/length columns dropped).

    Columns: path, widthInBytes, columnOffset, categorical, categoryValues (joined with U+00B5), min, max, allObservationsOffset,
    allObservationsLength, observationCount, patientCount.
    """
    out = OrderedDict()
    with open(path, "r", encoding="utf-8", errors="replace", newline="") as f:
        for r in csv.reader(f):
            if len(r) < 11:
                continue
            out[r[0]] = {
                "width": r[1],
                "categorical": r[3] == "true",
                "values": [v for v in r[4].split("µ") if v != ""] if r[3] == "true" else [],
                "min": r[5],
                "max": r[6],
                "observations": int(r[9]),
                "patients": int(r[10]),
            }
    return out
