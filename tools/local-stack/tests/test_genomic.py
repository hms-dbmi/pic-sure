"""The genomic fixture (genomic.py), the wiring of the genomic settings, and the genomic checks of qa.py against fake services.
No docker, no loader run: the loader is replaced by a function that lays down what it would write."""
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from typing import ClassVar
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
STACK = os.path.dirname(HERE)
sys.path.insert(0, STACK)
sys.path.insert(0, HERE)

from test_guard import copy_stack
from test_qa import DATA, Fake, serve, variant, write_store

import genomic
import qa

PROFILE = os.path.join(genomic.ROOT, "services", "pic-sure-hpds", "service", "src", "main", "resources", "application-bdc-auth-dev.properties")


def tiny_vcf(path, samples, rows):
    with open(path, "w", encoding="utf-8") as f:
        f.write("##fileformat=VCFv4.2\n")
        f.write("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\t" + "\t".join(samples) + "\n")
        f.writelines("\t".join([chrom, pos, ".", ref, alt, ".", ".", info, "GT"] + gts) + "\n" for chrom, pos, ref, alt, info, gts in rows)


class VcfTest(unittest.TestCase):
    def test_zygosity_follows_the_loaders_character_sum(self):
        self.assertEqual([genomic.zygosity(g) for g in ("0/1", "1/0", "0|1", "1|0")], ["0/1"] * 4)
        self.assertEqual([genomic.zygosity(g) for g in ("1/1", "1|1")], ["1/1"] * 2)
        self.assertEqual([genomic.zygosity(g) for g in ("0/0", "0|0", "./.", "")], [None] * 4)

    def test_the_spec_is_the_loaders_six_part_spec(self):
        rec = {"chrom": "chr1", "pos": "10", "ref": "A", "alt": "G", "info": genomic.info_columns("Gene_with_variant=G1;Variant_consequence_calculated=missense;AC=2")}
        self.assertEqual(genomic.spec_of(rec), "chr1,10,A,G,G1,missense")
        rec["info"] = {}
        self.assertEqual(genomic.spec_of(rec), "chr1,10,A,G,NULL,NULL")

    def test_read_vcf(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "a.vcf")
            tiny_vcf(path, ["S1", "S2"], [("chr1", "10", "A", "G", "Gene_with_variant=G1", ["0/1", "0/0"])])
            samples, records = genomic.read_vcf(path)
        self.assertEqual(samples, ["S1", "S2"])
        self.assertEqual(records[0]["genotypes"], ["0/1", "0/0"])


class AssignTest(unittest.TestCase):
    MEMBERS: ClassVar[dict] = {"phs000000.c1": [1, 2, 3], "phs000000.c2": [3, 4], "phs999999.c1": [5, 6], "tutorial": [7]}

    def test_every_patient_once_across_partitions_and_orphans_last(self):
        plan = genomic.assign([f"S{i}" for i in range(20)], self.MEMBERS, 2, 2)
        ids = [pid for _, pid, _ in plan]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(set(ids) - set(range(1, 8)), {1007, 1008})  # two orphans, above every real id
        self.assertEqual(sorted(set(range(1, 8)) - set(ids)), [])

    def test_studies_are_whole_groups_and_orphans_are_in_the_last_group(self):
        plan = genomic.assign([f"S{i}" for i in range(20)], self.MEMBERS, 2, 2)
        group = {pid: g for _, pid, g in plan}
        self.assertEqual({group[i] for i in (1, 2, 3, 4)}, {0})  # phs000000.c1 and c2 are one study
        self.assertEqual({group[i] for i in (5, 6)}, {1})
        self.assertEqual({group[1007], group[1008]}, {1})

    def test_the_best_samples_are_spread_over_the_partitions(self):
        plan = genomic.assign([f"S{i}" for i in range(4)], self.MEMBERS, 1, 2)
        first = {pid for _, pid, _ in plan}
        self.assertEqual(len(first), 4)
        self.assertEqual(len({p for p, ids in self.MEMBERS.items() if set(ids) & first}), 4)

    def test_deterministic(self):
        a = genomic.assign([f"S{i}" for i in range(9)], self.MEMBERS, 2, 2)
        self.assertEqual(a, genomic.assign([f"S{i}" for i in range(9)], self.MEMBERS, 2, 2))


class FixtureTest(unittest.TestCase):
    """build_fixture on three tiny VCFs, the loader replaced by one that writes the store marker."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = os.path.join(self.tmp.name, "tools", "local-stack")
        self.work = os.path.join(self.root, "work")
        os.makedirs(self.work)
        self.vcf = os.path.join(self.tmp.name, "vcf")
        os.makedirs(self.vcf)
        samples = [f"S{i}" for i in range(8)]
        gts = [["0/1", "1/1", "0/0", "0/1", "0/0", "0/0", "0/1", "0/0"], ["0/0", "0/1", "0/1", "0/0", "1/1", "0/0", "0/0", "0/1"]]
        for name, chrom in genomic.VCFS:
            tiny_vcf(os.path.join(self.vcf, name), samples, [("chr" + chrom, "10", "A", "G", "Gene_with_variant=G1;Variant_severity=LOW", gts[0]),
                                                           ("chr" + chrom, "20", "C", "T", "Gene_with_variant=G2;Variant_severity=HIGH", gts[1])])
        self.split = os.path.join(self.tmp.name, "split")
        os.makedirs(self.split)
        for part, ids in {"phs000000.c1": [1, 2, 3], "phs999999.c1": [4, 5, 6]}.items():
            with open(os.path.join(self.split, part + "-allConcepts.csv"), "w", newline="") as f:
                csv.writer(f, quoting=csv.QUOTE_ALL).writerows([str(i), "\\" + part + "\\x\\", "1", "", ""] for i in ids)
        self.jar = os.path.join(self.tmp.name, "loader.jar")
        open(self.jar, "w").close()

    def build(self, **kw):
        def fake_loader(jar, index, storage, merged):
            for chrom in ("chr20", "chr21", "chr4"):
                os.makedirs(os.path.join(storage, chrom))
                open(os.path.join(storage, chrom, "variantStore.javabin"), "w").close()

        out = os.path.join(self.work, "genomic", "fixture")
        env = {"WORK_DIR": self.work}
        with mock.patch.dict(os.environ, env), mock.patch.object(genomic, "HERE", self.root), mock.patch.object(genomic, "run_loader", fake_loader), \
                mock.patch.object(genomic, "VCF_DIR", self.vcf):
            genomic.build_fixture(out, self.jar, self.split, vcf_dir=self.vcf, **kw)
        return out

    def test_layout_manifest_and_index(self):
        out = self.build(orphans=1)
        self.assertEqual(genomic.problems(os.path.join(out, "all")), [])
        with open(os.path.join(out, "genomic.json")) as f:
            m = json.load(f)
        self.assertEqual(sorted(m["groups"]), ["group1", "group2"])
        self.assertEqual(m["patients"], 7)  # six patients of the phenotype data and one orphan
        self.assertEqual(sorted(m["members"]), ["phs000000.c1", "phs999999.c1"])
        v = next(v for v in m["variants"] if v["chromosome"] == "chr20" and v["pos"] == "10")
        self.assertEqual(v["spec"], "chr20,10,A,G,G1,NULL")
        self.assertTrue(set(v["het"]).isdisjoint(v["hom"]))
        with open(os.path.join(out, "vcfIndex-group1.tsv")) as f:
            lines = f.read().splitlines()
        self.assertEqual(len(lines), 1 + len(genomic.VCFS))
        self.assertEqual(lines[1].split("\t")[1], "20")
        with open(os.path.join(out, "loader.jar")) as f:
            self.assertEqual(f.read().strip(), self.jar)

    def test_a_missing_loader_jar_is_refused(self):
        with self.assertRaises(SystemExit) as cm:
            genomic.build_fixture(os.path.join(self.work, "x"), os.path.join(self.tmp.name, "none.jar"))
        self.assertIn("no SplitChromosomeVcfLoader jar", str(cm.exception))

    def test_nothing_outside_work_is_written(self):
        with mock.patch.dict(os.environ, {"WORK_DIR": self.work}), self.assertRaises(SystemExit) as cm:
            genomic.clear(os.path.join(self.tmp.name, "elsewhere"))
        self.assertIn("not inside", str(cm.exception))
        self.assertFalse(os.path.exists(os.path.join(self.tmp.name, "elsewhere")))

    def test_the_fixture_must_be_able_to_tell_scoping_apart(self):
        self.assertIsNone(genomic.check_discriminates({"members": None, "variants": []}))
        m = {"members": {"a": [1], "b": [2]}, "variants": [{"het": [1], "hom": []}]}
        self.assertIsNotNone(genomic.check_discriminates(m))
        m["variants"].append({"het": [1, 2], "hom": []})
        self.assertIsNone(genomic.check_discriminates(m))


class PreflightTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.all = os.path.join(self.tmp.name, "all")
        os.makedirs(self.all)

    def store(self, group, chrom):
        d = os.path.join(self.all, group, chrom)
        os.makedirs(d)
        open(os.path.join(d, "variantStore.javabin"), "w").close()

    def test_an_empty_directory_is_named(self):
        found = genomic.problems(self.all)
        self.assertEqual(len(found), 1)
        self.assertIn("genomic data directory has no dataset folders", found[0])
        open(os.path.join(self.all, "stray-file"), "w").close()  # a file is not a dataset folder
        self.assertIn("no dataset folders", genomic.problems(self.all)[0])

    def test_a_missing_directory(self):
        self.assertIn("does not exist", genomic.problems(os.path.join(self.tmp.name, "none"))[0])
        self.assertTrue(genomic.problems(""))

    def test_a_group_without_chromosomes_and_a_chromosome_without_a_store(self):
        os.makedirs(os.path.join(self.all, "g1"))
        self.assertTrue(any("study group folder g1 has no chromosome folders" in p for p in genomic.problems(self.all)))
        os.makedirs(os.path.join(self.all, "g2", "chr1"))
        self.assertTrue(any("g2/chr1 has no variantStore.javabin" in p for p in genomic.problems(self.all)))

    def test_a_valid_layout_has_no_problem_and_the_limits_are_those_of_hpds(self):
        self.store("g1", "chr1")
        self.assertEqual(genomic.problems(self.all), [])
        self.assertEqual(genomic.summary(self.all), (1, 1))
        for i in range(genomic.MAX_GROUPS):
            self.store(f"h{i}", "chr1")
        self.assertTrue(any("at most 10" in p for p in genomic.problems(self.all)))

    def test_the_cli_exits_1_with_the_reason(self):
        r = subprocess.run([sys.executable, os.path.join(STACK, "genomic.py"), "check", self.all], capture_output=True, text=True, check=False)
        self.assertEqual(r.returncode, 1)
        self.assertIn("FAIL genomic pre-flight: genomic data directory has no dataset folders", r.stderr)


class WiringTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.stack = copy_stack(os.path.realpath(self.tmp.name))

    def dry(self, *args, ok=True):
        r = subprocess.run(["make", "-C", self.stack, "--no-print-directory", "-n", *args], capture_output=True, text=True, check=False)
        if ok:
            self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        return r.stdout + r.stderr

    def test_the_settings_are_those_of_the_bdc_authorized_profile(self):
        with open(PROFILE) as f:
            props = dict(ln.split("=", 1) for ln in f.read().splitlines() if "=" in ln and not ln.startswith("#"))
        props = {k.strip(): v.strip() for k, v in props.items()}
        self.assertEqual(props["hpds.genomicProcessor.impl"], genomic.IMPL)
        self.assertEqual(props["HPDS_GENOMIC_DATA_DIRECTORY"], genomic.CONTAINER_DIR)
        with open(os.path.join(STACK, "Makefile")) as f:
            text = f.read()
        self.assertIn(f"-Dhpds.genomicProcessor.impl={genomic.IMPL} -DHPDS_GENOMIC_DATA_DIRECTORY={genomic.CONTAINER_DIR}", text)

    def test_compose_hands_the_options_and_the_genomic_root_to_hpds_dir_only(self):
        with open(os.path.join(STACK, "docker-compose.yml")) as f:
            text = f.read()
        block = text[text.index("\n  hpds-dir:\n"):]
        block = block[1:block.index("\n\n", 1)]
        self.assertIn("${HPDS_GENOMIC_OPTS:-}", block)
        self.assertIn("${GENOMIC_ROOT:-./work/none}:/opt/local/hpds/all", block)
        self.assertNotIn(":/opt/local/hpds/all", text.replace(block, ""))  # no other service mounts genomic data

    def test_genomic_is_a_variable_with_three_values(self):
        out = self.dry("help", "GENOMIC=bogus", ok=False)
        self.assertIn("one of fixture, empty, off", out)
        for g in ("fixture", "empty", "off"):
            self.dry("help", "GENOMIC=" + g)

    def test_the_fixture_is_built_before_hpds_dir_starts_and_checked(self):
        out = self.dry("_genomic-prepare", "DATASET=dir", "GENOMIC=fixture", "GENOMIC_SPLIT_DIR=/split")
        self.assertIn("genomic.py build fixture", out)
        self.assertIn('--split-dir "/split"', out)
        self.assertIn("services_ref.py get loader vcf", out)
        self.assertIn("genomic.py check", out)
        self.assertLess(out.index("build fixture"), out.index("genomic.py check"))

    def test_an_empty_directory_is_made_and_the_preflight_can_be_skipped(self):
        out = self.dry("_genomic-prepare", "DATASET=dir", "GENOMIC=empty")
        self.assertIn("genomic.py build empty", out)
        self.assertIn("genomic.py check", out)
        out = self.dry("_genomic-prepare", "DATASET=dir", "GENOMIC=empty", "GENOMIC_PREFLIGHT=off")
        self.assertNotIn("genomic.py check", out)
        self.assertIn("pre-flight skipped", out)

    def test_off_and_the_lanes_have_no_genomic_processor(self):
        for args in (["DATASET=dir", "GENOMIC=off"], ["DATASET=mini", "GENOMIC=fixture"]):
            self.assertNotIn("genomic.py", self.dry("_genomic-prepare", *args), args)
            out = self.dry("qa", *args, "HPDS_DIR=/a", "KEY_FILE=/k")
            self.assertNotIn("--genomic-dir", out, args)
        out = subprocess.run(["make", "-C", self.stack, "--no-print-directory", "-pn", "help", "DATASET=dir", "GENOMIC=off"], capture_output=True, text=True, check=False).stdout
        self.assertRegex(out, r"(?m)^HPDS_GENOMIC_OPTS :=\s*$")

    def test_qa_is_told_where_the_genomic_directory_and_the_expected_answers_are(self):
        out = self.dry("qa", "DATASET=dir", "HPDS_DIR=/a", "KEY_FILE=/k", "GENOMIC=fixture", "GENOMIC_SPLIT_DIR=/split")
        self.assertRegex(out, r'--genomic-dir "[^"]*/work/genomic/fixture/all"')
        self.assertRegex(out, r'--genomic-manifest "[^"]*/work/genomic/fixture/genomic.json"')
        self.assertIn('--pheno-split "/split"', out)


# ----------------------------------------------------------------------------------------------------------------------------
# qa.py: genomic checks

VARIANTS = [
    {"spec": "chr1,10,A,G,GENE1,missense", "chromosome": "chr1", "pos": "10", "ref": "A", "alt": "G", "info": {"Gene_with_variant": "GENE1", "Variant_severity": "LOW"},
     "het": [1, 4, 9], "hom": [5]},
    {"spec": "chr1,20,C,T,GENE2,synonymous", "chromosome": "chr1", "pos": "20", "ref": "C", "alt": "T", "info": {"Gene_with_variant": "GENE2", "Variant_severity": "HIGH"},
     "het": [2], "hom": [3, 6]},
]
MEMBERS = {p: sorted(set().union(*c.values())) for p, c in DATA.items()}
LOG = "16 patient ids loaded from patient partitions"
JAVA_OPTS = f"-Xmx1g -Dhpds.genomicProcessor.impl={genomic.IMPL} -DHPDS_GENOMIC_DATA_DIRECTORY={genomic.CONTAINER_DIR}"


class FakeGenomic(Fake):
    """The fake HPDS with genomic filters: the carriers of the fixture variants, within the patients of the caller's consents."""
    variants: ClassVar[list] = VARIANTS
    scope_leak = False  # answers for every fixture patient whatever the consents
    skew = 0  # added to every genomic count

    def carriers(self, key, values):
        zyg = set(values or ("0/1", "1/1"))
        pick = [v for v in self.variants if v["spec"] == key or ",".join(v["spec"].split(",")[:4]) == key]
        return set().union(set(), *({p for z, ids in (("0/1", v["het"]), ("1/1", v["hom"])) if z in zyg for p in ids} for v in pick))

    def answer(self, body):
        q = body["query"]
        if q["expectedResultType"] == "INFO_COLUMN_LISTING":
            return self.reply(200, json.dumps([{"key": k} for k in ("Gene_with_variant", "Variant_severity")]))
        filters = q.get("genomicFilters")
        if filters is None:
            return super().answer(body)
        consents = [c["value"] for c in q.get("userConsents") or []]
        parts = self.partitions(consents)
        if parts is None:
            return self.reply(*self.none_reply, "text/plain")
        granted = set().union(set(), *(MEMBERS[p] for p in parts))
        clause = q.get("phenotypicClause")
        if clause:
            granted &= set().union(set(), *(self.data[p].get(clause["conceptPath"], set()) for p in parts))
        if self.scope_leak:
            granted = {1, 2, 3, 4, 5, 6, 9}
        hit = None
        info = {f["key"]: f["values"] for f in filters if f["key"] in ("Gene_with_variant", "Variant_severity")}
        for f in filters:
            if f["key"] in info:
                continue
            s = self.carriers(f["key"], f.get("values"))
            hit = s if hit is None else hit & s
        if info:
            match = [v for v in self.variants if all(v["info"].get(k) in vals for k, vals in info.items())]
            s = set().union(set(), *({p for p in v["het"] + v["hom"]} for v in match))
            hit = s if hit is None else hit & s
        return self.reply(200, str(len(granted & (hit or set())) + self.skew), "text/plain")


class GenomicQaTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        t = self.tmp.name
        self.store = os.path.join(t, "store")
        write_store(self.store)
        self.split = os.path.join(t, "split")
        os.makedirs(self.split)
        for part, concepts in DATA.items():
            with open(os.path.join(self.split, part + "-allConcepts.csv"), "w", newline="") as f:
                w = csv.writer(f, quoting=csv.QUOTE_ALL)
                for path, pats in concepts.items():
                    for pat in sorted(pats):
                        w.writerows([[str(pat), path, str(i), "", ""] for i in (1, 2)])
        self.all = os.path.join(t, "all")
        os.makedirs(os.path.join(self.all, "group1", "chr1"))
        open(os.path.join(self.all, "group1", "chr1", "variantStore.javabin"), "w").close()
        self.manifest = os.path.join(t, "genomic.json")
        self.write_manifest()

    def write_manifest(self, **over):
        body = {"patients": 16, "members": MEMBERS, "variants": VARIANTS, "groups": {"group1": 16}, "chromosomes": ["chr1"]}
        body.update(over)
        with open(self.manifest, "w") as f:
            json.dump(body, f)

    def run_qa(self, hpds, logs=lambda c: LOG, env=None, extra=(), genomic_dir=None):
        argv = ["--hpds", hpds, "--store", self.store, "--report-dir", os.path.join(self.tmp.name, "report"), "--sample", "2", "--pheno-split", self.split,
                "--genomic-dir", genomic_dir or self.all, "--genomic-manifest", self.manifest, "--hpds-container", "picsure-local-hpds-dir"]
        env = [f"JAVA_OPTS={JAVA_OPTS}"] if env is None else env
        rep, _ = qa.run(qa.parse(argv + list(extra)), lambda n: None, lambda c: env, logs)
        return rep

    def failing(self, rep):
        return sorted({(r["check"], r["case"]) for r in rep.results if r["status"] == qa.FAIL})

    def statuses(self, rep, check):
        return {r["status"] for r in rep.results if r["check"] == check}

    def test_a_consistent_genomic_hpds_passes_every_genomic_check(self):
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds)
        self.assertEqual(self.failing(rep), [])
        self.assertEqual(rep.exit_code(), 0)
        for check in ("genomic.preflight", "genomic.config", "genomic.patients", "genomic.variant_spec", "genomic.info", "genomic.combined", "genomic.consent"):
            self.assertEqual(self.statuses(rep, check), {qa.PASS}, check)

    def test_a_genomic_query_that_ignores_the_consents_fails_the_scoping_check(self):
        with serve(variant(FakeGenomic, scope_leak=True)) as hpds:
            rep = self.run_qa(hpds)
        failed = {c for c, _ in self.failing(rep)}
        self.assertIn("genomic.consent", failed)
        self.assertIn("genomic.variant_spec", failed)

    def test_a_wrong_variant_count_fails(self):
        with serve(variant(FakeGenomic, skew=1)) as hpds:
            rep = self.run_qa(hpds)
        self.assertTrue({"genomic.variant_spec", "genomic.info", "genomic.combined"} <= {c for c, _ in self.failing(rep)})

    def test_a_different_genomic_patient_count_fails(self):
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds, logs=lambda c: "15 patient ids loaded from patient partitions")
        self.assertEqual(self.failing(rep), [("genomic.patients", "patients in the genomic stores")])

    def test_no_genomic_processor_in_the_log_fails(self):
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds, logs=lambda c: "Started HpdsApplication")
        self.assertEqual({c for c, _ in self.failing(rep)}, {"genomic.patients"})

    def test_a_container_without_the_bdc_settings_fails_the_config_check(self):
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds, env=["JAVA_OPTS=-Xmx1g"])
            self.assertEqual({c for c, _ in self.failing(rep)}, {"genomic.config"})
            rep = self.run_qa(hpds, env=["SPRING_PROFILES_ACTIVE=bdc-auth-dev"])
        self.assertEqual(self.failing(rep), [])

    def test_without_the_phenotype_patients_the_counts_per_consent_are_inconclusive_not_a_pass(self):
        self.write_manifest(members=None)
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds)
        self.assertEqual(self.failing(rep), [])
        self.assertEqual(rep.exit_code(), 2)
        self.assertTrue({"genomic.variant_spec", "genomic.consent", "genomic.combined"} <= {r["check"] for r in rep.results if r["status"] == qa.INCONCLUSIVE})

    def test_a_missing_manifest_is_inconclusive(self):
        os.remove(self.manifest)
        with serve(variant(FakeGenomic)) as hpds:
            rep = self.run_qa(hpds)
        self.assertEqual((rep.exit_code(), self.statuses(rep, "genomic.patients")), (2, {qa.INCONCLUSIVE}))

    def test_an_empty_genomic_directory_with_the_hpds_down_is_a_fail_naming_the_cause(self):
        empty = os.path.join(self.tmp.name, "empty-all")
        os.makedirs(empty)
        npe = f"Caused by: java.lang.NullPointerException: {qa.STARTUP_NPE}\n\tat ...initializePatientIds"
        rep = self.run_qa("http://127.0.0.1:9", logs=lambda c: npe, genomic_dir=empty)
        self.assertEqual(rep.exit_code(), 1)
        details = " ".join(r["detail"] for r in rep.results if r["status"] == qa.FAIL)
        self.assertIn("genomic data directory has no dataset folders", details)
        self.assertEqual({c for c, _ in self.failing(rep)}, {"genomic.preflight", "genomic.startup"})
        self.assertIn("NullPointerException", details)

    def test_the_preflight_alone_makes_a_down_hpds_a_fail_even_when_the_log_cannot_be_read(self):
        empty = os.path.join(self.tmp.name, "empty-all")
        os.makedirs(empty)
        rep = self.run_qa("http://127.0.0.1:9", logs=lambda c: None, genomic_dir=empty)
        self.assertEqual(rep.exit_code(), 1)
        self.assertIn("no dataset folders", " ".join(r["detail"] for r in rep.results if r["status"] == qa.FAIL))

    def test_a_down_hpds_on_a_good_directory_with_no_cause_in_the_log_stays_inconclusive(self):
        rep = self.run_qa("http://127.0.0.1:9", logs=lambda c: "nothing useful")
        self.assertEqual((rep.exit_code(), [r["check"] for r in rep.results if r["status"] != qa.PASS]), (2, ["setup"]))

    def test_without_a_genomic_directory_nothing_genomic_runs_and_the_label_says_so(self):
        argv = ["--hpds", "http://127.0.0.1:9", "--store", self.store]
        args = qa.parse(argv)
        self.assertEqual(qa.scenario_label(args), "phenotype only (no genomic processor)")
        with serve(variant(Fake)) as hpds:
            rep, _ = qa.run(qa.parse(["--hpds", hpds, "--store", self.store, "--pheno-split", self.split, "--report-dir", os.path.join(self.tmp.name, "r")]))
        self.assertFalse([r for r in rep.results if r["check"].startswith("genomic")])

    def test_the_label_names_the_genomic_directory(self):
        args = qa.parse(["--hpds", "http://x", "--store", self.store, "--genomic-dir", self.all])
        self.assertIn("1 study group(s), 1 chromosome folder(s)", qa.scenario_label(args))
        empty = os.path.join(self.tmp.name, "empty-all")
        os.makedirs(empty)
        args = qa.parse(["--hpds", "http://x", "--store", self.store, "--genomic-dir", empty])
        self.assertIn("genomic data directory has no dataset folders", qa.scenario_label(args))

    def test_the_final_line_reports_the_scenario(self):
        out = subprocess.run([sys.executable, os.path.join(STACK, "qa.py"), "--hpds", "http://127.0.0.1:9", "--store", self.store, "--genomic-dir", self.all,
                              "--report-dir", os.path.join(self.tmp.name, "rr")], capture_output=True, text=True, check=False)
        self.assertRegex(out.stdout, r"(?m)^scenario: phenotype \+ genomic \(")
        self.assertRegex(out.stdout, re.compile(r"^QA \w+ \[phenotype \+ genomic\]:", re.MULTILINE))


if __name__ == "__main__":
    unittest.main()
