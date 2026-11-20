#!/usr/bin/env python3
"""Writes a sized synthetic merged allConcepts file (same features as gen-combined.py).

usage: gen-synth.py --patients N --concepts M --seed S OUTPUT_FILE
  --concepts is per study (4 studies), default 200 patients, 12 concepts, seed 7
"""
import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import datagen  # noqa: E402


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--patients", type=int, default=200)
    ap.add_argument("--concepts", type=int, default=12)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("output")
    a = ap.parse_args()
    rows = datagen.generate(a.patients, a.concepts, a.seed)
    datagen.write(a.output, rows)
    print("gen-synth: %d rows (%d patients, %d concepts per study, seed %d) -> %s" % (len(rows), a.patients, a.concepts, a.seed, a.output))
    return 0


if __name__ == "__main__":
    sys.exit(main())
