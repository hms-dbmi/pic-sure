"""Fits the genomic generator's profile to the 1000 Genomes VCFs and writes src/genomic/profile_data.rs.

Usage:
  python3 -I fit_profile.py <vcf dir> <out profile_data.rs>

The VCFs are only read. The tables written are the joint of consequence and variant class, the
frequency text shares, the gnomAD frequency histograms, the carrier fraction quantiles for each
frequency bin, the homozygous share among carriers, the position gap histogram, the per-gene
variant counts, the SNV substitution matrix and the indel length histogram. Every table is a
count, so the generator can scale the same shapes to any variant total.
"""

import collections
import glob
import gzip
import json
import math
import os
import sys
from multiprocessing import Pool

QUANTILE_POINTS = 41
RARE_EDGES = [round(-7.0 + 0.25 * i, 2) for i in range(21)]
COMMON_EDGES = [round(-2.0 + 0.1 * i, 2) for i in range(21)]
CARRIER_EDGES = [round(-4.0 + 0.25 * i, 2) for i in range(17)]
GAP_BINS = 28
GENE_BINS = 15
INDEL_MAX = 20
BASES = "ACGT"


def log_bin(value, edges):
    """Returns the index of the histogram bin of log10(value) among edges, clamped to the ends."""
    x = math.log10(value)
    for i in range(len(edges) - 1):
        if x < edges[i + 1]:
            return max(i, 0)
    return len(edges) - 2


def scan(path):
    """Counts everything the fit needs from one VCF."""
    c = collections.defaultdict(collections.Counter)
    carriers_by_bin = collections.defaultdict(collections.Counter)
    samples = 0
    prev_pos = None
    prev_key = None
    gene_runs = collections.Counter()
    last_gene = None
    run = 0
    runs = []
    for line in gzip.open(path, "rt"):
        if line[0] == "#":
            if line.startswith("#CHROM"):
                samples = len(line.split("\t")) - 9
            continue
        p = line.split("\t", 9)
        pos = int(p[1])
        info = dict(x.split("=", 1) for x in p[7].split(";"))
        gene = info.get("Gene_with_variant")
        cons = info["Variant_consequence_calculated"]
        cls = info["Variant_class"]
        text = info["Variant_frequency_as_text"]
        af = float(info["Variant_frequency_in_gnomAD"])
        gt = p[9]
        zero = gt.count("0|0")
        hom = gt.count("1|1")
        carriers = samples - zero
        c["cons_class"][(cons, cls)] += 1
        c["text"][text] += 1
        c["gene_missing"][gene is None] += 1
        if text == "Rare":
            b = "zero" if af <= 0 else log_bin(af, RARE_EDGES)
            c["rare_af"][b] += 1
        elif text == "Common":
            c["common_af"][log_bin(min(max(af, 0.01), 1.0), COMMON_EDGES)] += 1
        key = (text, "zero" if af <= 0 else (log_bin(af, RARE_EDGES) if text == "Rare" else log_bin(min(max(af, 0.01), 1.0), COMMON_EDGES)))
        carriers_by_bin[key][carriers] += 1
        if carriers > 0:
            frac = carriers / samples
            cb = 0
            while cb < len(CARRIER_EDGES) - 2 and math.log10(frac) >= CARRIER_EDGES[cb + 1]:
                cb += 1
            c["carrier_cells"][cb] += carriers
            c["hom_cells"][cb] += hom
        ref, alt = p[3], p[4]
        if cls == "SNV":
            c["snv"][(ref, alt)] += 1
        elif cls == "insertion":
            c["indel"][("insertion", min(len(alt) - 1, INDEL_MAX))] += 1
        else:
            c["indel"][("deletion", min(len(ref) - 1, INDEL_MAX))] += 1
        if prev_pos is not None:
            gap = pos - prev_pos
            c["gap"][0 if gap <= 0 else 1 + min(int(math.log2(gap)), GAP_BINS - 2)] += 1
        c["same_site"][(pos, ref, alt) == prev_key] += 1
        prev_pos = pos
        prev_key = (pos, ref, alt)
        if gene != last_gene:
            if last_gene is not None:
                runs.append((last_gene, run))
            last_gene = gene
            run = 0
        run += 1
    runs.append((last_gene, run))
    return os.path.basename(path), samples, c, carriers_by_bin, runs


def quantiles(counter, samples):
    """Returns QUANTILE_POINTS carrier fractions at evenly spaced quantile levels."""
    total = sum(counter.values())
    items = sorted(counter.items())
    out = []
    for i in range(QUANTILE_POINTS):
        level = i / (QUANTILE_POINTS - 1)
        target = level * (total - 1)
        seen = 0
        for k, n in items:
            seen += n
            if seen > target:
                out.append(k / samples)
                break
    return out


def main():
    vcf_dir, out_path = sys.argv[1], sys.argv[2]
    paths = sorted(glob.glob(os.path.join(vcf_dir, "*.vcf.gz")))
    with Pool(min(8, len(paths))) as pool:
        results = pool.map(scan, paths)
    merged = collections.defaultdict(collections.Counter)
    carriers_by_bin = collections.defaultdict(collections.Counter)
    gene_sizes = collections.Counter()
    per_chrom = {}
    samples = results[0][1]
    for name, _, c, cb, runs in results:
        for k, v in c.items():
            merged[k].update(v)
        for k, v in cb.items():
            carriers_by_bin[k].update(v)
        for gene, n in runs:
            if gene is not None:
                gene_sizes[gene] += n
        chrom = name.split(".")[1]
        per_chrom[chrom] = sum(c["text"].values())

    def fmt(vals):
        return ", ".join(repr(v) if isinstance(v, float) else str(v) for v in vals)

    cons_class = sorted(merged["cons_class"].items())
    severity = {}
    lines = []
    lines.append("//! Tables fitted by `tools/fit_profile.py` to the 1000 Genomes VCFs. Do not edit by hand.\n")
    lines.append(f"/// Samples in the fitted VCFs, the denominator of every carrier fraction.\npub const FITTED_SAMPLES: u32 = {samples};\n")
    lines.append(f"/// Variants in the fitted VCFs.\npub const FITTED_VARIANTS: u64 = {sum(merged['text'].values())};\n")
    lines.append("/// Variants per fitted chromosome file, in the order of [`CHROMOSOMES`](super::CHROMOSOMES).\npub const FITTED_CHROM_VARIANTS: [u64; 23] = [" + fmt([per_chrom[f"chr{n}"] for n in list(range(1, 23)) + ["X"]]) + "];\n")
    lines.append("/// Consequence, class and count, for the joint distribution of the two.\npub const CONSEQUENCE_CLASS: [(&str, &str, u64); %d] = [" % len(cons_class))
    for (cons, cls), n in cons_class:
        lines.append(f'    ("{cons}", "{cls}", {n}),')
    lines.append("];\n")
    text = merged["text"]
    lines.append("/// Counts of the `Rare`, `Common` and `Novel` frequency texts.\npub const TEXT_COUNTS: [u64; 3] = [" + fmt([text["Rare"], text["Common"], text["Novel"]]) + "];\n")
    lines.append("/// Variants whose record has no `Gene_with_variant` field.\npub const GENE_MISSING_COUNT: u64 = %d;\n" % merged["gene_missing"][True])
    rare = merged["rare_af"]
    lines.append("/// Rare variants with a frequency of zero.\npub const RARE_ZERO_COUNT: u64 = %d;\n" % rare["zero"])
    lines.append("/// Lower log10 edges of the rare frequency bins, then the upper edge of the last.\npub const RARE_EDGES: [f64; %d] = [%s];\n" % (len(RARE_EDGES), fmt(RARE_EDGES)))
    lines.append("/// Rare variant counts per bin of [`RARE_EDGES`].\npub const RARE_COUNTS: [u64; %d] = [%s];\n" % (len(RARE_EDGES) - 1, fmt([rare[i] for i in range(len(RARE_EDGES) - 1)])))
    common = merged["common_af"]
    lines.append("/// Log10 edges of the common frequency bins.\npub const COMMON_EDGES: [f64; %d] = [%s];\n" % (len(COMMON_EDGES), fmt(COMMON_EDGES)))
    lines.append("/// Common variant counts per bin of [`COMMON_EDGES`].\npub const COMMON_COUNTS: [u64; %d] = [%s];\n" % (len(COMMON_EDGES) - 1, fmt([common[i] for i in range(len(COMMON_EDGES) - 1)])))
    def table(name, doc, key_fn, n):
        lines.append(f"/// {doc}\npub const {name}: [[f64; {QUANTILE_POINTS}]; {n}] = [")
        for i in range(n):
            j = i
            while not carriers_by_bin[key_fn(j)]:
                j = j + 1 if j + 1 < n else j - 1
            q = quantiles(carriers_by_bin[key_fn(j)], samples)
            lines.append("    [" + fmt([float(f"{x:.6g}") for x in q]) + "],")
        lines.append("];\n")
    table("RARE_CARRIER_QUANTILES", "Carrier fraction quantiles of rare variants, one row per rare bin.", lambda i: ("Rare", i), len(RARE_EDGES) - 1)
    lines.append("/// Carrier fraction quantiles of rare variants with a frequency of zero.\npub const RARE_ZERO_CARRIER_QUANTILES: [f64; %d] = [%s];\n" % (QUANTILE_POINTS, fmt([float(f"{x:.6g}") for x in quantiles(carriers_by_bin[("Rare", "zero")], samples)])))
    table("COMMON_CARRIER_QUANTILES", "Carrier fraction quantiles of common variants, one row per common bin.", lambda i: ("Common", i), len(COMMON_EDGES) - 1)
    lines.append("/// Carrier fraction quantiles of novel variants.\npub const NOVEL_CARRIER_QUANTILES: [f64; %d] = [%s];\n" % (QUANTILE_POINTS, fmt([float(f"{x:.6g}") for x in quantiles(carriers_by_bin[("Novel", "zero")], samples)])))
    lines.append("/// Lower log10 edges of the carrier fraction bins of [`HOM_SHARE`].\npub const CARRIER_EDGES: [f64; %d] = [%s];\n" % (len(CARRIER_EDGES), fmt(CARRIER_EDGES)))
    hom = []
    for i in range(len(CARRIER_EDGES) - 1):
        cells = merged["carrier_cells"][i]
        hom.append(float(f"{merged['hom_cells'][i] / cells:.6g}") if cells else 0.0)
    lines.append("/// Share of carrier calls that are `1|1`, per carrier fraction bin.\npub const HOM_SHARE: [f64; %d] = [%s];\n" % (len(hom), fmt(hom)))
    gap = merged["gap"]
    lines.append("/// Gaps between consecutive positions: bin 0 is a gap of zero, bin i is [2^(i-1), 2^i).\npub const GAP_COUNTS: [u64; %d] = [%s];\n" % (GAP_BINS, fmt([gap[i] for i in range(GAP_BINS)])))
    lines.append("/// Records at the same position, reference and alternate as the record before them, and the rest.\npub const SAME_SITE_COUNTS: [u64; 2] = [%d, %d];\n" % (merged["same_site"][True], merged["same_site"][False]))
    sizes = collections.Counter()
    for gene, n in gene_sizes.items():
        sizes[min(int(math.log2(n)), GENE_BINS - 1)] += 1
    lines.append("/// Genes per bin of variant count: bin i holds genes with [2^i, 2^(i+1)) variants.\npub const GENE_SIZE_COUNTS: [u64; %d] = [%s];\n" % (GENE_BINS, fmt([sizes[i] for i in range(GENE_BINS)])))
    snv = merged["snv"]
    lines.append("/// SNV counts by reference then alternate base, in the order A, C, G, T; the diagonal is zero.\npub const SNV_COUNTS: [[u64; 4]; 4] = [")
    for r in BASES:
        lines.append("    [" + fmt([snv[(r, a)] for a in BASES]) + "],")
    lines.append("];\n")
    indel = merged["indel"]
    lines.append("/// Insertion counts by inserted bases 1 to %d, the last bin open ended.\npub const INSERTION_LENGTH_COUNTS: [u64; %d] = [%s];\n" % (INDEL_MAX, INDEL_MAX, fmt([indel[("insertion", n)] for n in range(1, INDEL_MAX + 1)])))
    lines.append("/// Deletion counts by deleted bases 1 to %d, the last bin open ended.\npub const DELETION_LENGTH_COUNTS: [u64; %d] = [%s];\n" % (INDEL_MAX, INDEL_MAX, fmt([indel[("deletion", n)] for n in range(1, INDEL_MAX + 1)])))
    with open(out_path, "w") as f:
        f.write("\n".join(lines))
    json.dump({"samples": samples}, sys.stderr)


if __name__ == "__main__":
    main()
