#!/usr/bin/env python3
"""Writes the fixed `combined` dataset: a small merged allConcepts file (see datagen.py for what it contains).

usage: gen-combined.py OUTPUT_FILE        (same bytes on every run)
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import datagen  # noqa: E402

PATIENTS = 80
CONCEPTS = 8
SEED = 20261018


def main(argv):
    if len(argv) != 2:
        sys.stderr.write(__doc__)
        return 2
    rows = datagen.generate(PATIENTS, CONCEPTS, SEED)
    datagen.write(argv[1], rows)
    print("gen-combined: %d rows -> %s" % (len(rows), argv[1]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
