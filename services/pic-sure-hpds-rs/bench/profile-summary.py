#!/usr/bin/env python3
"""Summarize async-profiler collapsed stacks from a Java HPDS load run.

Usage: profile-summary.py FILE.collapsed [FILE.collapsed ...]

Each input line is ``frame;frame;...;leaf weight`` as written by ``jfrconv -o collapsed``.
For every file the script prints the share of weight per cost category, the heaviest leaf
frames, and the heaviest HPDS frames by inclusive weight. Categories are assigned per stack by
the first matching rule in ``CATEGORIES``, so a sort comparator that unboxes an Integer counts
as sort, and a HashMap rebuilt inside ``readObject`` counts as deserialization.
"""

import re
import sys
from collections import Counter

JAVA_FRAME = re.compile(r"_\[[ji0-9]\]$")
HPDS = "edu/harvard/hms/dbmi/avillach/hpds"

CATEGORIES = [
    ("gc", lambda s, j: not j and re.search(r"G1|GC|Gang|ParallelCleaning|Concurrent.*Thread|WorkerThread", s)),
    ("jit", lambda s, j: not j and re.search(r"Compile|C2Compiler|C1_|Compiler::", s)),
    ("jvm/native other", lambda s, j: not j),
    ("decompression", lambda s, j: re.search(r"Inflater|GZIPInputStream", s)),
    ("deserialization", lambda s, j: re.search(r"ObjectInputStream", s)),
    ("crypto", lambda s, j: re.search(r"hpds/crypto/Crypto|javax/crypto|com/sun/crypto|aarch64.*aes|ghash", s, re.I)),
    ("store read (I/O)", lambda s, j: re.search(r"RandomAccessFile|FileInputStream|FileChannel", s)),
    ("per-query sort", lambda s, j: re.search(r"PhenoCube\.sortedBy|TimSort|Arrays\.sort|DualPivotQuicksort", s)),
    ("JSON", lambda s, j: re.search(r"jackson", s, re.I)),
    ("boxed sets/maps", lambda s, j: re.search(
        r"java/util/(HashMap|HashSet|TreeMap|TreeSet|AbstractSet|AbstractCollection|LinkedHashMap)"
        r"|java/lang/(Integer|Double|Long)\.(valueOf|compare|hashCode|equals|intValue|doubleValue)"
        r"|java\.util\.(HashMap|TreeMap|HashSet|TreeSet)|java\.lang\.(Integer|Double|Long)_", s)),
    ("HPDS query logic", lambda s, j: HPDS in s or HPDS.replace("/", ".") in s),
    ("HTTP/Spring", lambda s, j: re.search(r"tomcat|springframework|catalina", s)),
]


def is_java_frame(frame):
    """Return True when the frame is a Java method (jfrconv suffix ``_[j]``, ``_[i]``, ``_[0]``)."""
    return bool(JAVA_FRAME.search(frame))


def categorize(frames):
    """Return the first category whose rule matches the stack.

    ``frames`` lists frame names from root to leaf. Rules see the joined stack and a flag that
    says whether any frame is a Java method.
    """
    joined = ";".join(frames)
    has_java = any(is_java_frame(f) for f in frames)
    for name, rule in CATEGORIES:
        if rule(joined, has_java):
            return name
    return "other java"


def summarize(path):
    """Print category shares, top leaf frames and top inclusive HPDS frames for one file."""
    categories, leaves, hpds_inclusive = Counter(), Counter(), Counter()
    total = 0
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            stack, _, weight = line.rstrip("\n").rpartition(" ")
            if not stack or not weight.isdigit():
                continue
            weight = int(weight)
            frames = stack.split(";")
            total += weight
            categories[categorize(frames)] += weight
            leaves[frames[-1]] += weight
            for frame in set(frames):
                if HPDS in frame or HPDS.replace("/", ".") in frame:
                    hpds_inclusive[frame.replace(HPDS + "/", "").replace(HPDS.replace("/", ".") + ".", "")] += weight
    if total == 0:
        print(f"== {path}: no samples")
        return
    print(f"== {path} (total weight {total})")
    print("categories:")
    for name, weight in categories.most_common():
        print(f"  {100 * weight / total:5.1f}%  {name}")
    print("top leaf frames:")
    for frame, weight in leaves.most_common(15):
        print(f"  {100 * weight / total:5.1f}%  {frame}")
    print("top HPDS frames (inclusive):")
    for frame, weight in hpds_inclusive.most_common(12):
        print(f"  {100 * weight / total:5.1f}%  {frame}")
    print()


if __name__ == "__main__":
    for argument in sys.argv[1:]:
        summarize(argument)
