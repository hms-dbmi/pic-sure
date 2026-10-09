#!/usr/bin/env python3
"""Compares a Java HPDS phenotype store with a Rust one and classifies every difference.

The inputs are files the parity harness (etl-parity.sh) produced: both canonical dumps,
both columnMeta.csv files, both patient ID lists and both load logs. Each difference is
WHITELISTED when a rule in the divergence file matches it, or UNEXPECTED otherwise. The
report goes to standard output and, as JSON, to --report. The exit status is 0 when every
difference is whitelisted, 1 when any is unexpected and 2 on a usage or input error.

To classify the differences that come from the Java loaders' data loss, the script models
how the named Java loader ingests the input files: the order it sees rows in, its loading
cache (16 concepts for CSVLoaderNewSearch, 1 for LowRAMCSVProcessor) and LowRAM's chunking,
including the last chunk it drops when a file has more lines than records. The model only
decides which concepts and patients the loss can touch; it never decides a row's value.

Whitelisted counts are in the unit of the difference: rows for dump kinds, fields for
columnMeta kinds, patients for patient kinds, and one per failed load.
"""

import argparse
import collections
import functools
import os
import csv
import json
import math
import re
import struct
import sys

csv.field_size_limit(sys.maxsize)

LONG_MIN = "-9223372036854775808"
DOUBLE_MAX = 1.7976931348623157e308
DOUBLE_MIN_VALUE = 5e-324
COLUMN_NAMES = [
    "name",
    "width",
    "column-offset",
    "categorical",
    "categories",
    "min",
    "max",
    "block-offset",
    "block-length",
    "observation-count",
    "patient-count",
]
SAMPLE_LIMIT = 5


def utf16_key(text):
    """Sort key giving Java String.compareTo order (UTF-16 code units)."""
    return text.encode("utf-16-be", "surrogatepass")


def java_trim(text):
    """String.trim: strips every character at or below U+0020 from both ends."""
    start, end = 0, len(text)
    while start < end and ord(text[start]) <= 0x20:
        start += 1
    while end > start and ord(text[end - 1]) <= 0x20:
        end -= 1
    return text[start:end]


def java_split_backslash(text):
    """String.split("\\\\"): splits on backslash and drops trailing empty strings."""
    parts = text.split("\\")
    while parts and parts[-1] == "":
        parts.pop()
    if not parts and text == "":
        return [""]
    return parts


@functools.lru_cache(maxsize=1 << 20)
def java_base_path(raw_path):
    """The concept path before rollup and the dataset root: mu to backslash, nodes trimmed, U+FFFD removed."""
    path = raw_path.replace("\u00b5", "\\")
    path = "\\".join(java_trim(p) for p in java_split_backslash(path)) + "\\"
    return path.replace("\ufffd", "")


def java_concept_path(raw_path, raw_text, rollup, config):
    """CSVParserUtil.parseConceptPath for one record."""
    path = java_base_path(raw_path)
    if rollup:
        text = java_trim(raw_text).replace("\ufffd", "")
        if path.endswith("\\" + text + "\\"):
            path = re.sub(r"\\[^\\]*\\$", lambda _m: "\\", path)
    if config and config.get("dataset_name_as_root_node"):
        if not path.startswith("\\"):
            path = "\\" + path
        path = "\\" + str(config.get("dataset_name")) + path
    return path


def physical_line_count(path):
    """Files.lines(path).count(): lines ended by \\n, \\r or \\r\\n, a final unterminated one included."""
    count = 0
    pending_cr = False
    last = b""
    with open(path, "rb") as f:
        while True:
            block = f.read(1 << 24)
            if not block:
                break
            count += block.count(b"\n") + block.count(b"\r") - block.count(b"\r\n")
            if pending_cr and block.startswith(b"\n"):
                count -= 1
            pending_cr = block.endswith(b"\r")
            last = block[-1:]
    if last and last not in (b"\n", b"\r"):
        count += 1
    return count


def read_records(path):
    """CSV records of a file after the header record, as Commons CSV DEFAULT reads them."""
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f, strict=False)
        next(reader, None)
        for record in reader:
            yield record


class LruCache:
    """Guava LoadingCache with maximumSize n and a single segment, as both loaders build it."""

    def __init__(self, size):
        self.size = size
        self.entries = collections.OrderedDict()

    def get_if_present(self, key):
        if key in self.entries:
            self.entries.move_to_end(key)
            return True
        return False

    def put(self, key):
        self.entries[key] = True
        self.entries.move_to_end(key)
        while len(self.entries) > self.size:
            self.entries.popitem(last=False)


class IngestModel:
    """Which concepts the Java loader re-created after eviction, and which records it never read.

    generations[concept] counts how many times the loader created a fresh cube for the
    concept; more than one means the loader overwrote the earlier cube's metadata and kept
    only the last one's rows. lost_concepts and lost_patients name the concepts and patients
    of records LowRAM dropped with a file's last chunk.
    """

    def __init__(self, loader, files, rollup, config, max_chunk_gib):
        self.generations = collections.Counter()
        self.lost_concepts = set()
        self.lost_patients = set()
        self.lost_records = 0
        self.chunks = 0
        if loader == "newsearch":
            self._new_search(files[0], config)
        else:
            for path in files:
                self._low_ram(path, rollup, config, max_chunk_gib)

    def _config_for(self, config, file_name):
        if not config:
            return None
        stem = file_name[:-4] if file_name.endswith((".csv", ".sql")) else file_name
        return config.get(stem)

    def _new_search(self, path, config):
        cfg = self._config_for(config, "allConcepts")
        cache = LruCache(16)
        current = None
        for record in read_records(path):
            if len(record) < 4:
                continue
            concept = java_concept_path(record[1], record[3], False, cfg)
            if concept != current:
                if not cache.get_if_present(concept):
                    self.generations[concept] += 1
                    cache.put(concept)
                current = concept
        self.chunks += 1

    def _low_ram(self, path, rollup, config, max_chunk_gib):
        cfg = self._config_for(config, os.path.basename(path))
        size = os.path.getsize(path)
        chunks = max(1, math.ceil(size / (max_chunk_gib * 1024 * 1024 * 1024)))
        total = physical_line_count(path)
        per_chunk = total // chunks
        cache = LruCache(1)
        chunk = {}
        pending_patients = set()
        pending_records = 0
        chunk_lines = 0
        line_count = 0
        for record in read_records(path):
            chunk_lines += 1
            line_count += 1
            pending_records += 1
            raw = record[1] if len(record) > 1 else ""
            runs = chunk.setdefault(raw, [])
            concept = java_concept_path(record[1], record[3], rollup, cfg) if len(record) >= 4 else None
            if runs and runs[-1][0] == concept:
                runs[-1][1] += 1
            else:
                runs.append([concept, 1])
            try:
                pending_patients.add(int(record[0]))
            except ValueError:
                pass
            if chunk_lines > per_chunk or line_count + 1 == total:
                self._ingest_chunk(chunk, cache)
                chunk = {}
                pending_patients = set()
                pending_records = 0
                chunk_lines = 0
        self.lost_records += pending_records
        self.lost_patients |= pending_patients
        for runs in chunk.values():
            self.lost_concepts.update(concept for concept, _count in runs if concept is not None)

    def _ingest_chunk(self, chunk, cache):
        """Replays one chunk in LowRAM's order: a stable sort on the raw concept path field."""
        self.chunks += 1
        for raw in sorted(chunk, key=utf16_key):
            for concept, _count in chunk[raw]:
                if concept is None:
                    continue
                if not cache.get_if_present(concept):
                    self.generations[concept] += 1
                    cache.put(concept)


def canonical_number(text):
    """A comparison key for a printed double: its bits, with every NaN as one value."""
    value = float(text)
    if math.isnan(value):
        return "NaN"
    return struct.pack(">d", value).hex()


class Dump:
    """Streams a canonical dump (DumpSourceCSV or hpds-etl dump) one concept at a time."""

    def __init__(self, path, side):
        self.path = path
        self.side = side
        self.rows = 0
        self.order_errors = []
        self._file = open(path, newline="", encoding="utf-8", errors="surrogateescape")
        self._reader = csv.reader(self._file, strict=False)
        self._file.readline()
        self._pending = None
        self._last_concept = None

    def concepts(self):
        """Yields (concept, rows), rows as [patient, numeric, text, timestamp] string lists."""
        rows = []
        concept = None
        for record in self._reader:
            if len(record) != 5:
                raise ValueError(f"{self.path}: record {self.rows + 2} has {len(record)} fields")
            self.rows += 1
            if record[1] != concept:
                if concept is not None:
                    yield concept, rows
                if self._last_concept is not None and utf16_key(record[1]) <= utf16_key(self._last_concept):
                    self.order_errors.append(record[1])
                concept = record[1]
                self._last_concept = concept
                rows = []
            rows.append(record)
        if concept is not None:
            yield concept, rows
        self._file.close()


def read_column_meta(path):
    meta = {}
    order = []
    with open(path, newline="", encoding="utf-8", errors="surrogateescape") as f:
        for record in csv.reader(f, strict=False):
            if not record:
                continue
            meta[record[0]] = record
            order.append(record[0])
    return meta, order


def read_patients(path):
    with open(path) as f:
        return {int(line) for line in f if line.strip()}


def parse_double_or_null(text):
    if text == "null":
        return None
    return float(text)


class Comparison:
    """Collects differences and classifies them against the divergence rules."""

    def __init__(self, args, rules, model_factory):
        self.args = args
        self.rules = rules
        self._model_factory = model_factory
        self._model = None
        self.whitelisted = collections.Counter()
        self.whitelisted_concepts = collections.defaultdict(set)
        self.unexpected = []
        self.concept_context = {}
        self.samples = collections.defaultdict(list)

    @property
    def model(self):
        """The ingest model, built on first use, or None when the case has no model."""
        if self._model is None and self._model_factory is not None:
            self._model = self._model_factory()
        return self._model

    def context(self, concept):
        return self.concept_context.setdefault(concept, {})

    def record(self, kind, concept=None, **detail):
        item = {"kind": kind}
        if concept is not None:
            item["concept"] = concept
        item.update(detail)
        rule = self.match(item)
        if rule is not None:
            self.whitelisted[rule["name"]] += detail.get("count", 1)
            if concept is not None:
                self.whitelisted_concepts[rule["name"]].add(concept)
            if len(self.samples[rule["name"]]) < SAMPLE_LIMIT and not self.args.redact:
                self.samples[rule["name"]].append(item)
        else:
            if self.args.redact:
                item = {k: v for k, v in item.items() if k in ("kind", "count")}
            self.unexpected.append(item)

    def match(self, item):
        for rule in self.rules:
            if item["kind"] not in rule["kinds"]:
                continue
            if "loaders" in rule and self.args.loader not in rule["loaders"]:
                continue
            if self.predicate(rule, item):
                return rule
        return None

    def predicate(self, rule, item):
        when = rule["when"]
        concept = item.get("concept")
        ctx = self.concept_context.get(concept, {}) if concept is not None else {}
        if when == "always":
            return True
        if when == "java-rows-subset-and-concept-recreated":
            return ctx.get("java_subset_of_rust", False) and self.model is not None and self.model.generations.get(concept, 0) > 1
        if when == "java-rows-subset-and-concept-in-lost-chunk":
            return ctx.get("java_subset_of_rust", False) and self.model is not None and concept in self.model.lost_concepts
        if when == "patient-in-lost-chunk":
            return self.model is not None and item.get("patient") in self.model.lost_patients
        if when == "java-max-is-double-min-value-and-real-max-not-positive":
            java, rust = item.get("java"), item.get("rust")
            return java == DOUBLE_MIN_VALUE and rust is not None and rust <= 0
        if when == "empty-numeric-concept":
            return item.get("observations") == 0 and item.get("rust") is None and item.get("java") in (DOUBLE_MAX, DOUBLE_MIN_VALUE)
        if when == "log-matches":
            pattern = re.compile(rule["params"]["pattern"])
            return bool(pattern.search(item.get("log", "")))
        raise ValueError(f"rule {rule['name']}: unknown condition {when}")

    def compare_dumps(self, java_path, rust_path):
        java = Dump(java_path, "java")
        rust = Dump(rust_path, "rust")
        java_iter = java.concepts()
        rust_iter = rust.concepts()
        j = next(java_iter, None)
        r = next(rust_iter, None)
        concepts = 0
        while j is not None or r is not None:
            if r is None or (j is not None and utf16_key(j[0]) < utf16_key(r[0])):
                self.context(j[0])["java_rows"] = len(j[1])
                self.record("dump-concept-only-in-java", j[0], count=len(j[1]))
                j = next(java_iter, None)
            elif j is None or utf16_key(r[0]) < utf16_key(j[0]):
                ctx = self.context(r[0])
                ctx["rust_rows"] = len(r[1])
                ctx["java_subset_of_rust"] = True
                self.record("dump-concept-only-in-rust", r[0], count=len(r[1]))
                r = next(rust_iter, None)
            else:
                concepts += 1
                self.compare_concept(j[0], j[1], r[1])
                j = next(java_iter, None)
                r = next(rust_iter, None)
        for side in (java, rust):
            for concept in side.order_errors:
                self.record("dump-concept-order", concept, side=side.side)
        return java.rows, rust.rows, concepts

    def row_key(self, row, numeric):
        value = canonical_number(row[2]) if numeric else row[3]
        timestamp = "" if row[4] == LONG_MIN else row[4]
        return (int(row[0]), value, timestamp)

    def compare_concept(self, concept, java_rows, rust_rows):
        ctx = self.context(concept)
        ctx["java_rows"] = len(java_rows)
        ctx["rust_rows"] = len(rust_rows)
        if java_rows == rust_rows:
            ctx["java_subset_of_rust"] = True
            return
        java_numeric = any(row[2] != "" for row in java_rows)
        rust_numeric = any(row[2] != "" for row in rust_rows)
        if java_numeric != rust_numeric:
            ctx["java_subset_of_rust"] = False
            self.record("dump-concept-type", concept, java="numeric" if java_numeric else "categorical", rust="numeric" if rust_numeric else "categorical")
            return
        java_keys = [self.row_key(row, java_numeric) for row in java_rows]
        rust_keys = [self.row_key(row, rust_numeric) for row in rust_rows]
        long_min = sum(1 for row in java_rows if row[4] == LONG_MIN)
        if long_min:
            self.record("dump-long-min-timestamp", concept, count=long_min)
        if java_keys == rust_keys:
            ctx["java_subset_of_rust"] = True
            formatted = sum(1 for a, b in zip(java_rows, rust_rows) if a[2] != b[2])
            if formatted:
                self.record("dump-number-format", concept, count=formatted, java=java_rows[0][2], rust=rust_rows[0][2])
            return
        java_count = collections.Counter(java_keys)
        rust_count = collections.Counter(rust_keys)
        only_java = java_count - rust_count
        only_rust = rust_count - java_count
        ctx["java_subset_of_rust"] = not only_java
        if not only_java and not only_rust:
            first = next(i for i, (a, b) in enumerate(zip(java_keys, rust_keys)) if a != b)
            self.record("dump-row-order", concept, count=1, position=first, java=list(java_keys[first]), rust=list(rust_keys[first]))
            return
        if only_java:
            self.record("dump-rows-only-in-java", concept, count=sum(only_java.values()), sample=[list(k) for k in list(only_java)[:SAMPLE_LIMIT]])
        if only_rust:
            self.record("dump-rows-only-in-rust", concept, count=sum(only_rust.values()), sample=[list(k) for k in list(only_rust)[:SAMPLE_LIMIT]])

    def compare_column_meta(self, java_path, rust_path):
        java, java_order = read_column_meta(java_path)
        rust, rust_order = read_column_meta(rust_path)
        for concept in sorted(set(java) | set(rust), key=utf16_key):
            if concept not in rust:
                self.record("columnmeta-concept-only-in-java", concept)
                continue
            if concept not in java:
                self.record("columnmeta-concept-only-in-rust", concept)
                continue
            j, r = java[concept], rust[concept]
            if len(j) != 11 or len(r) != 11:
                self.record("columnmeta-field-count", concept, java=len(j), rust=len(r))
                continue
            observations = int(r[9]) if r[9].isdigit() else None
            for index, name in enumerate(COLUMN_NAMES):
                if index in self.args.ignored_columns:
                    continue
                if index in (5, 6):
                    jv, rv = parse_double_or_null(j[index]), parse_double_or_null(r[index])
                    same = (jv is None and rv is None) or (
                        jv is not None and rv is not None and (jv == rv or (math.isnan(jv) and math.isnan(rv)))
                    )
                    if not same:
                        self.record(f"columnmeta-{name}", concept, java=jv, rust=rv, observations=observations)
                    elif j[index] != r[index]:
                        self.record("columnmeta-number-format", concept, java=j[index], rust=r[index])
                elif j[index] != r[index]:
                    self.record(f"columnmeta-{name}", concept, java=j[index], rust=r[index])
        if [c for c in java_order if c in rust] != [c for c in rust_order if c in java]:
            self.record("columnmeta-row-order")
        return len(java), len(rust)

    def compare_patients(self, java_path, rust_path):
        java = read_patients(java_path)
        rust = read_patients(rust_path)
        for patient in sorted(java - rust):
            self.record("patient-only-in-java", patient=patient)
        for patient in sorted(rust - java):
            self.record("patient-only-in-rust", patient=patient)
        return len(java), len(rust)


def load_rules(path):
    with open(path) as f:
        data = json.load(f)
    ignored = [c["index"] for c in data.get("ignoredColumnMetaColumns", [])]
    return data["rules"], ignored


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--case", required=True)
    parser.add_argument(
        "--loader",
        required=True,
        help="the Java loader the case reproduces; migration-<loader> compares a migrated store with the Java store that loader built",
         choices=["newsearch", "lowram", "partitioned", "migration-newsearch", "migration-lowram", "migration-partitioned"],
    )
    parser.add_argument("--divergences", required=True)
    parser.add_argument("--java-dump")
    parser.add_argument("--rust-dump")
    parser.add_argument("--java-column-meta")
    parser.add_argument("--rust-column-meta")
    parser.add_argument("--java-patients")
    parser.add_argument("--rust-patients")
    parser.add_argument("--java-log", help="Java loader log, when the Java load failed")
    parser.add_argument("--rust-log", help="hpds-etl log, when the Rust load failed")
    parser.add_argument("--expect-rust-failure", action="store_true")
    parser.add_argument("--input", action="append", default=[], help="input CSV in Java ingest order")
    parser.add_argument("--config")
    parser.add_argument("--rollup", action="store_true")
    parser.add_argument("--max-chunk-gib", type=float, default=5.0)
    parser.add_argument("--redact", action="store_true", help="report counts only, no paths, values or patient IDs")
    parser.add_argument("--report", required=True)
    args = parser.parse_args()
    rules, args.ignored_columns = load_rules(args.divergences)

    model_factory = None
    if args.input and args.loader in ("newsearch", "lowram"):
        config = None
        if args.config:
            with open(args.config) as f:
                config = {k[:-4] if k.endswith(".csv") else k: v for k, v in json.load(f).items()}

        def model_factory():
            return IngestModel(args.loader, args.input, args.rollup, config, args.max_chunk_gib)

    comparison = Comparison(args, rules, model_factory)
    counts = {}
    if args.java_log:
        with open(args.java_log, errors="replace") as f:
            log = f.read()
        comparison.record("java-load-failed", log=log[-4000:])
    if args.rust_log:
        with open(args.rust_log, errors="replace") as f:
            log = f.read()
        comparison.record("rust-load-failed" if args.expect_rust_failure else "rust-load-failed-unexpectedly", log=log[-4000:])
    elif args.expect_rust_failure:
        comparison.record("rust-load-accepted-invalid-input")
    if args.java_dump and args.rust_dump:
        counts["java_rows"], counts["rust_rows"], counts["shared_concepts"] = comparison.compare_dumps(args.java_dump, args.rust_dump)
    if args.java_column_meta and args.rust_column_meta:
        counts["java_concepts"], counts["rust_concepts"] = comparison.compare_column_meta(args.java_column_meta, args.rust_column_meta)
    if args.java_patients and args.rust_patients:
        counts["java_patients"], counts["rust_patients"] = comparison.compare_patients(args.java_patients, args.rust_patients)
    model = comparison._model
    if model is not None:
        counts["model_recreated_concepts"] = sum(1 for g in model.generations.values() if g > 1)
        counts["model_lost_records"] = model.lost_records
        counts["model_chunks"] = model.chunks

    by_name = {rule["name"]: rule for rule in rules}
    unexpected_kinds = collections.Counter(item["kind"] for item in comparison.unexpected)
    report = {
        "case": args.case,
        "loader": args.loader,
        "counts": counts,
        "whitelisted": dict(comparison.whitelisted),
        "whitelistedConcepts": {name: len(c) for name, c in comparison.whitelisted_concepts.items()},
        "unexpected": len(comparison.unexpected),
        "unexpectedByKind": dict(unexpected_kinds),
        "unexpectedItems": comparison.unexpected[:200],
        "whitelistedSamples": dict(comparison.samples),
    }
    with open(args.report, "w") as f:
        json.dump(report, f, indent=2, ensure_ascii=False, default=str)

    print(f"[{args.case}] {json.dumps(counts)}")
    for name, count in sorted(comparison.whitelisted.items()):
        rule = by_name[name]
        concepts = len(comparison.whitelisted_concepts.get(name, ()))
        print(f"[{args.case}] WHITELISTED {name} x{count} ({concepts} concepts): {rule['reason']} (spec: {rule['spec']})")
    for item in comparison.unexpected[:50]:
        print(f"[{args.case}] UNEXPECTED {json.dumps(item, ensure_ascii=False, default=str)[:600]}")
    if len(comparison.unexpected) > 50:
        print(f"[{args.case}] ... {len(comparison.unexpected) - 50} more unexpected differences in {args.report}")
    if not comparison.unexpected:
        print(f"[{args.case}] PASS")
    return 1 if comparison.unexpected else 0


if __name__ == "__main__":
    sys.exit(main())
