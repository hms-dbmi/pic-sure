#!/usr/bin/env python3
"""The genomic side of the authorized HPDS: a small fixture built from the public 1000 Genomes test VCFs, laid out like the BDC host, and a
check of the mounted directory. Standard library only.

  genomic.py build fixture --out DIR --loader-jar JAR [--split-dir DIR] [--groups N] [--orphans N]
  genomic.py build empty   --out DIR
  genomic.py check DIR                 exit 0 when DIR can be served by the BDC genomic settings, else 1 with the reason
  genomic.py clear-all                 remove everything under work/genomic (HPDS writes into it as root: a container does it when the host cannot)
  genomic.py expect SCENARIO REPORT LOG   exit 0 when the qa report of scenario off|fixture|empty came out as that scenario expects, else 1 with why

The BDC authorized HPDS (profile bdc-auth-dev) runs hpds.genomicProcessor.impl=localPatientDistributed over HPDS_GENOMIC_DATA_DIRECTORY
(/opt/local/hpds/all/). GenomicProcessorConfig lists the first-level directories of it (one per study group, at most 10) and the second-level
directories of each (one per chromosome, at most 50). A directory with no first-level directory makes HPDS die at start with a
NullPointerException in GenomicProcessorPatientMergingParentImpl.initializePatientIds.

Fixture, under DIR:
  all/<group>/<chromosome>/   what SplitChromosomeVcfLoader writes for one study group; this is what is mounted at /opt/local/hpds/all
  vcfIndex-<group>.tsv        the loader's input index (public VCF paths, sample ids, patient ids)
  genomic.json                the expected answers, computed here from the VCFs and not from the loader's output (see below)
  loader.jar                  names the loader jar that built it (the provenance of the run)

Patients: the samples of the VCFs are mapped to patient ids that exist in the phenotype data (--split-dir: a directory of
<partition>-allConcepts.csv, read row by row for the patient of every row HPDS would load, nothing else is kept), spread over the partitions so
that the carriers of a variant lie in several partitions. A few extra patients (--orphans) exist only in the genomic data, in no partition. Study
groups are made of whole studies. Without --split-dir the patients are made up and nothing says which partition they belong to (the expected counts
that need it are then skipped).

genomic.json: groups (patients per group), chromosomes, patients, members (partition -> the fixture patients that are in it, or null), concepts
(partition -> a few concept paths -> the fixture patients that have them, for the phenotype + genomic query), variants (spec, chromosome, ref, alt,
info columns, heterozygous and homozygous carriers). The ids are those of the patients of the run; the file stays in work/ next to the loader's own
index, which holds the same ids. work/genomic is removed by make clean and clean-data.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import lane

ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
VCF_DIR = os.path.join(ROOT, "services", "pic-sure-hpds", "service", "src", "test", "resources")
# file, chromosome column of the index (the directory the loader writes is named after the contig in the VCF, chr<N>)
VCFS = [("open_access-1000Genomes.chr20.annotated.vcf", "20"), ("open_access-1000Genomes.chr21.annotated.vcf", "21"),
        ("open_access-1000Genomes.chr4.annotated.vcf", "4")]
IMPL = "localPatientDistributed"
CONTAINER_DIR = "/opt/local/hpds/all/"
HEAP = os.environ.get("LOADER_XMX", "2g")
MAX_GROUPS, MAX_CHROMOSOMES = 10, 50  # GenomicProcessorConfig
NO_FOLDERS = "genomic data directory has no dataset folders"
HET, HOM = "0/1", "1/1"
FIRST_ID = 100001  # made-up patient ids when there is no phenotype data to map to
CONCEPTS_PER_PARTITION = 20  # concepts kept per partition for the phenotype + genomic query


def say(msg):
    print(msg, flush=True)


# ----------------------------------------------------------------------------------------------------------------------------
# the VCFs: what the loader will see, computed independently of it


def zygosity(gt):
    """The loader's rule (VariableVariantMasks): the characters of the genotype summed, modulo 7. 4 = heterozygous, 5 = homozygous,
    anything else (reference, no call) carries no variant."""
    if len(gt) < 3:
        return None
    k = (ord(gt[0]) + ord(gt[1]) + ord(gt[2])) % 7
    return HET if k == 4 else HOM if k == 5 else None


def info_columns(text):
    """The loader's own reading of an INFO field (NewVCFLoader.VCFWalker.currentSpecNotation): alternating key and value after a split on = and ;."""
    parts = re.split("[=;]", text)
    return {parts[i]: parts[i + 1] for i in range(0, len(parts) - 1, 2)}


def read_vcf(path):
    """(sample names, records): a record is {chrom, pos, ref, alt, info, genotypes (one per sample)}."""
    samples, records = None, []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("##"):
                continue
            p = line.rstrip("\n").split("\t")
            if line.startswith("#CHROM"):
                samples = p[9:]
                continue
            records.append({"chrom": p[0], "pos": p[1], "ref": p[3], "alt": p[4], "info": info_columns(p[7]), "genotypes": p[9:]})
    return samples, records


def spec_of(rec):
    """The variant spec as the loader writes it: chrom,pos,ref,alt,gene,consequence."""
    return ",".join([rec["chrom"], rec["pos"], rec["ref"], rec["alt"], rec["info"].get("Gene_with_variant", "NULL"),
                     rec["info"].get("Variant_consequence_calculated", "NULL")])


def read_all(vcf_dir=VCF_DIR):
    out = []
    for name, chrom in VCFS:
        samples, records = read_vcf(os.path.join(vcf_dir, name))
        out.append({"file": os.path.join(vcf_dir, name), "chromosome": chrom, "samples": samples, "records": records})
    return out


# ----------------------------------------------------------------------------------------------------------------------------
# which sample is which patient


def rank_samples(vcfs):
    """Samples present in every VCF, carriers of the most variants first (ties by name): the fixture's few samples then carry something."""
    common = set(vcfs[0]["samples"])
    for v in vcfs[1:]:
        common &= set(v["samples"])
    score = {s: 0 for s in common}
    for v in vcfs:
        index = {s: i for i, s in enumerate(v["samples"]) if s in common}
        for rec in v["records"]:
            for s, i in index.items():
                if zygosity(rec["genotypes"][i]):
                    score[s] += 1
    return sorted(common, key=lambda s: (-score[s], s))


def split_files(split_dir):
    return {f[: -len("-allConcepts.csv")]: os.path.join(split_dir, f) for f in sorted(os.listdir(split_dir)) if f.endswith("-allConcepts.csv")}


def members_from_split(split_dir):
    """partition -> sorted patient ids: the patient of every row HPDS would load (pheno.observation's rules), one row at a time; nothing but the
    ids of one partition is held, so a real release's partitions can be read."""
    import pheno

    out = {}
    for name, path in split_files(split_dir).items():
        ids = set()
        for row in pheno.read_rows(path):
            ob, _ = pheno.observation(row)
            if ob is not None:
                ids.add(ob.patient)
        if ids:
            out[name] = sorted(ids)
    return out


def concepts_from_split(split_dir, patients, limit=CONCEPTS_PER_PARTITION):
    """partition -> {concept path: sorted ids of the PATIENTS that have it}, the `limit` best covered concepts of each partition (a second pass,
    one row at a time, keeping only rows of the fixture's patients)."""
    import pheno

    out = {}
    for name, path in split_files(split_dir).items():
        seen = {}
        for row in pheno.read_rows(path):
            ob, _ = pheno.observation(row)
            if ob is not None and ob.patient in patients:
                seen.setdefault(ob.concept, set()).add(ob.patient)
        best = sorted(seen.items(), key=lambda kv: (-len(kv[1]), kv[0]))[:limit]
        if best:
            out[name] = {c: sorted(ids) for c, ids in best}
    return out


def study_of(partition):
    return partition.split(".")[0]


def assign(ranked, members, orphans, n_groups):
    """[(sample, patient id, group index)] and the groups' patient lists. The ranked samples are dealt out over the partitions in turn
    (and over the orphans as one more bucket), so the best carriers do not all land in one partition. A patient is in one group: groups are
    made of whole studies (sorted, dealt out in turn); orphans are in the last group."""
    studies = sorted({study_of(p) for p in members})
    group_of_study = {s: i % n_groups for i, s in enumerate(studies)}
    buckets = [(p, list(ids)) for p, ids in sorted(members.items())]
    taken = set()
    top = max([i for ids in members.values() for i in ids] or [FIRST_ID])
    orphan_ids = [top + 1000 + i for i in range(orphans)]
    buckets.append((None, orphan_ids))
    plan, k = [], 0
    while k < len(ranked):
        progressed = False
        for name, ids in buckets:
            while ids and ids[0] in taken:
                ids.pop(0)
            if not ids or k >= len(ranked):
                continue
            pid = ids.pop(0)
            taken.add(pid)
            group = n_groups - 1 if name is None else group_of_study[study_of(name)]
            plan.append((ranked[k], pid, group))
            k += 1
            progressed = True
        if not progressed:
            break
    return plan


def made_up_members(n):
    return {}, [FIRST_ID + i for i in range(n)]


def expected(vcfs, plan):
    """The variants of the VCFs with their carriers among the fixture's patients: [{spec, chromosome, pos, ref, alt, info, het, hom}]. Records with
    the same spec are one variant, as the loader makes them (it ORs their masks)."""
    by_sample = {s: pid for s, pid, _ in plan}
    merged = {}
    for v in vcfs:
        index = {s: i for i, s in enumerate(v["samples"]) if s in by_sample}
        for rec in v["records"]:
            e = merged.setdefault(spec_of(rec), {"spec": spec_of(rec), "chromosome": rec["chrom"], "pos": rec["pos"], "ref": rec["ref"], "alt": rec["alt"],
                                                 "info": {k: rec["info"][k] for k in ("Gene_with_variant", "Variant_severity", "Variant_consequence_calculated")
                                                          if k in rec["info"]}, "het": set(), "hom": set()})
            for s, i in index.items():
                z = zygosity(rec["genotypes"][i])
                if z == HET:
                    e["het"].add(by_sample[s])
                elif z == HOM:
                    e["hom"].add(by_sample[s])
    return [dict(e, het=sorted(e["het"]), hom=sorted(e["hom"])) for e in merged.values()]


# ----------------------------------------------------------------------------------------------------------------------------
# build


def work_root():
    return os.environ.get("WORK_DIR") or os.path.join(HERE, "work")


def clear(path):
    """Remove a directory under work/genomic, and nothing else: the work directory must be this stack's, and the path inside work/genomic. HPDS runs
    as root and writes its variant index caches next to the stores, so when the host cannot remove them a container of the local HPDS image does
    (no registry is needed)."""
    work = work_root()
    problem = lane.work_dir_problem(work, HERE)
    if problem:
        raise SystemExit(f"refusing to remove {path}: WORK_DIR {problem}")
    base = os.path.join(os.path.realpath(work), "genomic")
    real = os.path.join(os.path.realpath(os.path.dirname(path)), os.path.basename(path))  # the entry itself is not resolved
    if not real.startswith(base + os.sep):
        raise SystemExit(f"refusing to remove {path}: not inside {base}")
    if os.path.islink(path):
        os.unlink(path)
        return
    if not os.path.lexists(path):
        return
    try:
        shutil.rmtree(path)
    except OSError:
        parent, name = os.path.split(real)
        image = os.environ.get("HPDS_IMAGE") or "picsure-local-hpds:local"
        subprocess.run(["docker", "run", "--rm", "--pull", "never", "--entrypoint", "rm", "-v", parent + ":/p", image, "-rf", "--", "/p/" + name], check=True)


def clear_all():
    base = os.path.join(work_root(), "genomic")
    if os.path.isdir(base):
        for entry in sorted(os.listdir(base)):
            clear(os.path.join(base, entry))


def write_index(path, vcfs, plan, group):
    rows = [(s, pid) for s, pid, g in plan if g == group]
    with open(path, "w", encoding="utf-8") as f:
        f.write("filename\tchromosome\tannotated\tgzip\tsample_ids\tpatient_ids\tsample_relationship\trelated_sample_ids\n")
        samples, patients = ",".join(s for s, _ in rows), ",".join(str(p) for _, p in rows)
        f.writelines("{}\t{}\t1\t0\t\"{}\"\t\"{}\"\t\t\n".format(v["file"], v["chromosome"], samples, patients) for v in vcfs)


def run_loader(jar, index, storage, merged):
    os.makedirs(storage)
    os.makedirs(merged)
    r = subprocess.run(["java", "-Xmx" + HEAP, "-jar", jar, index, storage, merged], capture_output=True, text=True, check=False)
    log = os.path.join(os.path.dirname(index), "loader-" + os.path.basename(storage) + ".log")
    with open(log, "w", encoding="utf-8") as f:
        f.write(r.stdout + r.stderr)
    if r.returncode != 0:
        raise SystemExit(f"SplitChromosomeVcfLoader failed (exit {r.returncode}) for {index}; log: {log}")


def build_fixture(out, loader_jar, split_dir=None, groups=2, orphans=3, vcf_dir=VCF_DIR):
    if not os.path.isfile(loader_jar):
        raise SystemExit(f"no SplitChromosomeVcfLoader jar at {loader_jar}: run make _prepare SVC=loader")
    vcfs = read_all(vcf_dir)
    ranked = rank_samples(vcfs)
    members = members_from_split(split_dir) if split_dir else {}
    if split_dir and not members:
        raise SystemExit(f"no patients found in {split_dir}: it must hold <partition>-allConcepts.csv files")
    if members:
        plan = assign(ranked, members, orphans, groups)
    else:
        ids = [FIRST_ID + i for i in range(min(len(ranked), 16))]
        plan = [(s, pid, i % groups) for i, (s, pid) in enumerate(zip(ranked, ids))]
    if not plan:
        raise SystemExit("no sample could be mapped to a patient")
    n_groups = max(g for _, _, g in plan) + 1
    if n_groups > MAX_GROUPS:
        raise SystemExit(f"{n_groups} study groups: HPDS takes at most {MAX_GROUPS}")
    clear(out)
    os.makedirs(out)
    all_dir = os.path.join(out, "all")
    os.makedirs(all_dir)
    group_names = [f"group{g + 1}" for g in range(n_groups)]
    for g, name in enumerate(group_names):
        if not any(gg == g for _, _, gg in plan):
            continue
        index = os.path.join(out, f"vcfIndex-{name}.tsv")
        write_index(index, vcfs, plan, g)
        run_loader(loader_jar, index, os.path.join(all_dir, name), os.path.join(out, "merged", name))
    variants = expected(vcfs, plan)
    member_ids, concepts = None, None
    if members:
        fixture_patients = {pid for _, pid, _ in plan}
        member_ids = {p: sorted(set(ids) & fixture_patients) for p, ids in members.items()}
        concepts = concepts_from_split(split_dir, fixture_patients)
    manifest = {
        "groups": {name: sum(1 for _, _, g in plan if g == i) for i, name in enumerate(group_names) if any(g == i for _, _, g in plan)},
        "chromosomes": sorted({v["chromosome"] for v in variants}),
        "patients": len(plan), "members": member_ids, "concepts": concepts, "variants": variants,
        "loader_jar": os.path.abspath(loader_jar),
    }
    with open(os.path.join(out, "genomic.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=1, sort_keys=True)
        f.write("\n")
    with open(os.path.join(out, "loader.jar"), "w", encoding="utf-8") as f:
        f.write(os.path.abspath(loader_jar) + "\n")
    problem = check_discriminates(manifest)
    if problem:
        raise SystemExit("the fixture would not tell the consent scoping apart: " + problem)
    say(f"genomic fixture: {len(plan)} patients in {len(manifest['groups'])} study groups, {len(manifest['chromosomes'])} chromosomes, "
        f"{len(variants)} variants ({os.path.relpath(out, HERE)})")


def carriers_of(v):
    return set(v["het"]) | set(v["hom"])


def scoping_variant(variants, members, consents):
    """(variant, carriers inside, carriers outside) of the fixture variant best suited to show that a caller with `consents` does not get the
    carriers of the other served partitions: the one with the most carriers both inside the consents' patients and in other partitions' patients
    (orphans, in no partition, are not counted: the all-consents answer covers them). None when no variant has carriers on both sides."""
    inside_set = set().union(set(), *(members.get(c, ()) for c in consents))
    served = set().union(set(), *members.values())
    best = None
    for v in variants:
        c = carriers_of(v)
        a, b = len(c & inside_set), len((c & served) - inside_set)
        if a and b and (best is None or (min(a, b), v["spec"]) > best[0]):
            best = ((min(a, b), v["spec"]), v, a, b)
    return best[1:] if best else None


def check_discriminates(manifest):
    """None when some partition has a variant whose carriers lie inside it and in another partition (so a cross-partition leak would change the
    answer), else why not. Without phenotype membership there is nothing to tell apart and nothing is asked."""
    members = manifest.get("members")
    if not members:
        return None
    for p in members:
        if scoping_variant(manifest["variants"], members, [p]):
            return None
    return "no partition has a variant with carriers inside it and in another partition"


def build_empty(out):
    """The directory of the failing case: present, and with no dataset folder in it."""
    clear(out)
    os.makedirs(os.path.join(out, "all"))
    say(f"genomic directory with no dataset folders: {os.path.relpath(out, HERE)}")


# ----------------------------------------------------------------------------------------------------------------------------
# the mounted directory


def subdirs(path):
    return sorted(e for e in os.listdir(path) if os.path.isdir(os.path.join(path, e)))


def problems(path):
    """Why HPDS cannot start on this genomic directory with the BDC settings, as a list of sentences (empty: it can)."""
    if not path:
        return ["the genomic data directory is not set"]
    if not os.path.isdir(path):
        return [f"the genomic data directory {path} does not exist"]
    groups = subdirs(path)
    if not groups:
        return [f"{NO_FOLDERS}: {path} (it must hold one folder per study group, each with one folder per chromosome)"]
    out = []
    if len(groups) > MAX_GROUPS:
        out.append(f"{len(groups)} study group folders under {path}: HPDS takes at most {MAX_GROUPS}")
    for g in groups:
        chromosomes = subdirs(os.path.join(path, g))
        if not chromosomes:
            out.append(f"study group folder {g} has no chromosome folders (HPDS would start with no genomic data for it)")
        if len(chromosomes) > MAX_CHROMOSOMES:
            out.append(f"{len(chromosomes)} chromosome folders in {g}: HPDS takes at most {MAX_CHROMOSOMES}")
        for c in chromosomes:
            if not os.path.isfile(os.path.join(path, g, c, "variantStore.javabin")):
                out.append(f"{g}/{c} has no variantStore.javabin")
    return out


def summary(path):
    """(study groups, chromosome folders) of a directory, for the scenario label."""
    if not path or not os.path.isdir(path):
        return 0, 0
    groups = subdirs(path)
    return len(groups), sum(len(subdirs(os.path.join(path, g))) for g in groups)


def expect(scenario, report, log):
    """(ok, why): did the qa run of `scenario` end the way that scenario must? off and fixture: exit 0 and a PASS line naming the scenario. empty:
    exit 1, a FAIL line naming the scenario, and a FAIL row of the pre-flight or of the startup that names the missing dataset folders; an
    inconclusive run, a crash or a FAIL for another reason is not it."""
    try:
        with open(report, encoding="utf-8") as f:
            data = json.load(f)
        with open(log, encoding="utf-8", errors="replace") as f:
            lines = [ln for ln in f.read().splitlines() if ln.startswith("QA ")]
    except (OSError, ValueError) as e:
        return False, f"no usable qa report ({e})"
    line = lines[-1] if lines else ""
    code = data.get("summary", {}).get("exit")
    label = "phenotype only" if scenario == "off" else "phenotype + genomic"
    if scenario != "empty":
        ok = code == 0 and line.startswith(f"QA PASS [{label}]")
        return ok, "" if ok else f"exit {code}, result line {line!r}; wanted exit 0 and QA PASS [{label}]"
    named = [r for r in data.get("results", []) if r.get("status") == "FAIL" and r.get("check") in ("genomic.preflight", "genomic.startup")
             and "no dataset folders" in (r.get("detail") or "")]
    ok = code == 1 and line.startswith(f"QA FAIL [{label}]") and bool(named)
    return ok, "" if ok else f"exit {code}, result line {line!r}, {len(named)} FAIL row(s) naming the missing dataset folders; wanted exit 1, QA FAIL [{label}] and such a row"


def main(argv):
    if argv[:1] == ["clear-all"]:
        clear_all()
        return 0
    if argv[:1] == ["expect"] and len(argv) == 4:
        ok, why = expect(*argv[1:])
        if not ok:
            sys.stderr.write(f"scenario {argv[1]} did not come out as expected: {why}\n")
        return 0 if ok else 1
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    sub = ap.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build")
    b.add_argument("kind", choices=("fixture", "empty"))
    b.add_argument("--out", required=True)
    b.add_argument("--loader-jar")
    b.add_argument("--split-dir")
    b.add_argument("--groups", type=int, default=2)
    b.add_argument("--orphans", type=int, default=3)
    c = sub.add_parser("check")
    c.add_argument("dir")
    args = ap.parse_args(argv)
    if args.cmd == "check":
        found = problems(args.dir)
        for p in found:
            sys.stderr.write(f"FAIL genomic pre-flight: {p}\n")
        if not found:
            g, c_ = summary(args.dir)
            say(f"genomic pre-flight: {g} study group(s), {c_} chromosome folder(s) in {args.dir}")
        return 1 if found else 0
    if args.kind == "empty":
        build_empty(args.out)
    else:
        if not args.loader_jar:
            raise SystemExit("--loader-jar is required for the fixture")
        build_fixture(args.out, args.loader_jar, args.split_dir, args.groups, args.orphans)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
