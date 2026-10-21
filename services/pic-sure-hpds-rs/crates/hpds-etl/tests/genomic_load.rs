//! `hpds-etl genomic-load` on small VCFs and index files written here: what the store holds,
//! read back through `genomic-dump`, and every input refused, each named by file and line.

use std::io::Write;
use std::path::{Path, PathBuf};

use flate2::Compression;
use flate2::write::GzEncoder;
use hpds_etl::EtlError;
use hpds_etl::genomic::dump::dump_genomic;
use hpds_etl::genomic::vcf_load::{GenomicLoadOptions, GenomicLoadReport, genomic_load};
use hpds_format::{CryptoKey, GenoMode, Protection};

const KEY: &str = "0123456789ABCDEF0123456789ABCDEF";

const HEADER: &str = "##fileformat=VCFv4.3\n\
##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"The gene.\">\n\
##INFO=<ID=Variant_consequence_calculated,Number=.,Type=String,Description=\"The consequence.\">\n\
##INFO=<ID=AF,Number=1,Type=Float,Description=\"Frequency, as a number > 0.\">\n\
##INFO=<ID=PU,Number=1,Type=String,Description=\"Skipped by Java.\">\n\
##INFO=<ID=FLAG,Number=0,Type=Flag,Description=\"A flag.\">\n";

/// A VCF with the header above, the given samples and data lines.
fn vcf(samples: &[&str], records: &[&str]) -> String {
    let mut text = String::from(HEADER);
    text.push_str("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT");
    for s in samples {
        text.push('\t');
        text.push_str(s);
    }
    text.push('\n');
    for r in records {
        text.push_str(r);
        text.push('\n');
    }
    text
}

/// A data line of `contig` at `pos` with `info` and GT-only genotypes.
fn gt(contig: &str, pos: u32, alt: &str, info: &str, genotypes: &[&str]) -> String {
    format!(
        "{contig}\t{pos}\t.\tA\t{alt}\t.\tPASS\t{info}\tGT\t{}",
        genotypes.join("\t")
    )
}

/// A dataset under construction: VCFs and index files in a temporary directory.
struct Dataset {
    dir: tempfile::TempDir,
}

/// One index line: VCF file name, its text, gzip it, and the sample and patient lists.
struct Line<'a> {
    file: &'a str,
    text: String,
    gzipped: bool,
    samples: &'a [&'a str],
    patients: &'a [u32],
}

impl Dataset {
    fn new() -> Self {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("key"), KEY).unwrap();
        Dataset { dir }
    }

    fn path(&self, name: &str) -> PathBuf {
        self.dir.path().join(name)
    }

    /// Writes the VCFs and an index file `name` listing them.
    fn index(&self, name: &str, lines: &[Line<'_>]) -> PathBuf {
        self.index_with_chromosomes(name, lines, &[])
    }

    /// Writes the VCFs and an index file `name` listing them, line `i` with chromosome
    /// `chromosomes[i]` (`1` when not given).
    fn index_with_chromosomes(
        &self,
        name: &str,
        lines: &[Line<'_>],
        chromosomes: &[&str],
    ) -> PathBuf {
        let mut index =
            String::from("vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\n");
        for (i, line) in lines.iter().enumerate() {
            let path = self.path(line.file);
            if line.gzipped {
                let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
                encoder.write_all(line.text.as_bytes()).unwrap();
                std::fs::write(&path, encoder.finish().unwrap()).unwrap();
            } else {
                std::fs::write(&path, &line.text).unwrap();
            }
            let patients: Vec<String> = line.patients.iter().map(u32::to_string).collect();
            index.push_str(&format!(
                "{}\t{}\t1\t{}\t\"{}\"\t\"{}\"\n",
                path.display(),
                chromosomes.get(i).unwrap_or(&"1"),
                u8::from(line.gzipped),
                line.samples.join(","),
                patients.join(",")
            ));
        }
        let path = self.path(name);
        std::fs::write(&path, index).unwrap();
        path
    }

    fn load(
        &self,
        mode: GenoMode,
        groups: Vec<(&str, PathBuf)>,
        skip: bool,
    ) -> Result<GenomicLoadReport, EtlError> {
        let out = self.path("store");
        let _ = std::fs::remove_dir_all(&out);
        genomic_load(&GenomicLoadOptions {
            mode,
            groups: groups
                .into_iter()
                .map(|(n, p)| (n.to_string(), p))
                .collect(),
            out,
            key_file: self.path("key"),
            skip_missing_samples: skip,
            threads: Some(2),
        })
    }

    fn dump(&self) -> String {
        let protection =
            Protection::Encrypted(CryptoKey::from_key_file_bytes(KEY.as_bytes()).unwrap());
        let mut out = Vec::new();
        dump_genomic(&self.path("store"), &protection, false, &mut out).unwrap();
        String::from_utf8(out).unwrap()
    }
}

const SEVEN: [&str; 7] = ["S1", "S2", "S3", "S4", "S5", "S6", "S7"];
const SEVEN_IDS: [u32; 7] = [7, 6, 5, 4, 3, 2, 1];

/// Loads one ld group of one VCF and returns the result.
fn load_one(
    records: &[&str],
    samples: &[&str],
    patients: &[u32],
    skip: bool,
) -> (Dataset, Result<GenomicLoadReport, EtlError>) {
    let data = Dataset::new();
    let index = data.index(
        "index.tsv",
        &[Line {
            file: "chr1.vcf",
            text: vcf(&SEVEN, records),
            gzipped: false,
            samples,
            patients,
        }],
    );
    let result = data.load(GenoMode::LocalDistributed, vec![("", index)], skip);
    (data, result)
}

/// Asserts `result` is an input refusal at `line` of a file whose name ends with `file`,
/// with `text` in its message.
fn assert_refused(result: Result<GenomicLoadReport, EtlError>, file: &str, line: u64, text: &str) {
    match result {
        Err(EtlError::Input {
            path,
            line: got,
            detail,
        }) => {
            assert!(path.to_string_lossy().ends_with(file), "{path:?}: {detail}");
            assert_eq!(got, line, "{detail}");
            assert!(detail.contains(text), "{detail}");
        }
        other => panic!("expected a refusal of {file} line {line}, got {other:?}"),
    }
}

const RECORD_LINE: u64 = 8;

#[test]
fn masks_metadata_info_and_carriers_follow_java() {
    let records = [
        gt(
            "chr1",
            100,
            "G",
            "Gene_with_variant=G1;Variant_consequence_calculated=c;AF=0.5;PU=x",
            &["0|1", "1|1", "./1", "./.", "0|0", "./0", "1|0"],
        ),
        gt(
            "chr1",
            100,
            "G",
            "Gene_with_variant=G1;Variant_consequence_calculated=c",
            &["0|0", "0|0", "0|0", "0|0", "0|1", "0|0", "0|0"],
        ),
        gt(
            "chr1",
            200,
            "T",
            "Gene_with_variant=G2;Variant_consequence_calculated=c;AF=0.1,0.2;FLAG ",
            &["0|1", "0|1", "0|1", "0|1", "0|1", "0|1", "0|0"],
        ),
        gt(
            "chr1",
            1500,
            "T",
            ".",
            &["0|0", "0|0", "0|0", "0|0", "0|0", "./0", "0|0"],
        ),
    ];
    let records: Vec<&str> = records.iter().map(String::as_str).collect();
    let (data, result) = load_one(&records, &SEVEN, &SEVEN_IDS, false);
    let report = result.unwrap();
    assert_eq!(report.variants, 3);
    assert_eq!(report.records, 4);
    let dump = data.dump();
    let expected = [
        "M\tlocalDistributed",
        "N\t\tchr1\t7\t3",
        "P\t1,2,3,4,5,6,7",
        "B\tchr1\t0",
        "V\tchr1,100,A,G,G1,c\t1\ts:1,3,7\ts:6\ts:5\ts:4\t2\tGene_with_variant=G1;Variant_consequence_calculated=c;AF=0.5;PU=x\tGene_with_variant=G1;Variant_consequence_calculated=c",
        "V\tchr1,200,A,T,G2,c\t1\tb:2,3,4,5,6,7\t-\t-\t-\t1\tGene_with_variant=G2;Variant_consequence_calculated=c;AF=0.1,0.2;FLAG",
        "C\t1,2,3,4,5,6,7",
        "B\tchr1\t1",
        "V\tchr1,1500,A,T,NULL,NULL\t1\t-\t-\t-\t-\t1\t.",
        "C\t",
        "I\tAF\t1\t3\tDescription=\"Frequency, as a number  0.\"",
        "K\t0.1\t1\tchr1,200,A,T,G2,c",
        "K\t0.2\t1\tchr1,200,A,T,G2,c",
        "K\t0.5\t1\tchr1,100,A,G,G1,c",
    ];
    let lines: Vec<&str> = dump.lines().collect();
    for line in expected {
        assert!(lines.contains(&line), "missing {line:?} in\n{dump}");
    }
    let genes = "I\tGene_with_variant\t0\t2\tDescription=\"The gene.\"";
    assert!(lines.contains(&genes), "{dump}");
    assert!(!dump.contains("I\tPU\t"), "{dump}");
    assert!(!dump.contains("I\tFLAG\t"), "{dump}");
}

#[test]
fn groups_gzip_and_skipped_samples() {
    let data = Dataset::new();
    let a = data.index(
        "a.tsv",
        &[
            Line {
                file: "a1.vcf.gz",
                text: vcf(&["X1", "X2"], &[&gt("chr1", 5, "G", ".", &["0|1", "1|1"])]),
                gzipped: true,
                samples: &["X1", "X2", "GONE"],
                patients: &[10, 11, 12],
            },
            Line {
                file: "a2.vcf",
                text: vcf(
                    &["X1", "X2", "X3"],
                    &[&gt("chr2", 5, "G", ".", &["0|1", "1|1", "0|1"])],
                ),
                gzipped: false,
                samples: &["X1", "X2", "X3"],
                patients: &[10, 11, 13],
            },
        ],
    );
    let b = data.index(
        "b.tsv",
        &[Line {
            file: "b1.vcf",
            text: vcf(&["Y1"], &[&gt("chr1", 5, "G", ".", &["1|1"])]),
            gzipped: false,
            samples: &["Y1"],
            patients: &[20],
        }],
    );
    let groups = || vec![("B", b.clone()), ("A", a.clone())];
    assert_refused(
        data.load(GenoMode::LocalPatientDistributed, groups(), false),
        "a.tsv",
        2,
        "1 index samples are missing from the header",
    );
    let report = data
        .load(GenoMode::LocalPatientDistributed, groups(), true)
        .unwrap();
    assert_eq!(report.dropped_samples, 1);
    assert_eq!(report.nodes[0].dropped_patients, vec![12]);
    let dump = data.dump();
    assert!(dump.contains("N\tA\tchr1\t3\t1\nP\t10,11,13\n"), "{dump}");
    assert!(
        dump.contains("V\tchr1,5,A,G,NULL,NULL\t1\ts:10\ts:11\t-\t-\t1\t.\n"),
        "{dump}"
    );
    assert!(dump.contains("N\tB\tchr1\t1\t1\nP\t20\n"), "{dump}");
}

#[test]
fn patients_in_two_groups_are_refused() {
    let data = Dataset::new();
    let line = |file: &'static str| Line {
        file,
        text: vcf(&["X1"], &[&gt("chr1", 5, "G", ".", &["0|1"])]),
        gzipped: false,
        samples: &["X1"],
        patients: &[10],
    };
    let a = data.index("a.tsv", &[line("a.vcf")]);
    let b = data.index("b.tsv", &[line("b.vcf")]);
    match data.load(
        GenoMode::LocalPatientDistributed,
        vec![("A", a), ("B", b)],
        false,
    ) {
        Err(EtlError::Usage(detail)) => assert!(
            detail.contains("1 patient ids appear in more than one group"),
            "{detail}"
        ),
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_contig_in_two_vcfs_of_a_group_is_refused() {
    let data = Dataset::new();
    let line = |file: &'static str, records: Vec<String>| Line {
        file,
        text: vcf(
            &["X1"],
            &records.iter().map(String::as_str).collect::<Vec<_>>(),
        ),
        gzipped: false,
        samples: &["X1"],
        patients: &[10],
    };
    let index = data.index(
        "index.tsv",
        &[
            line(
                "a.vcf",
                vec![
                    gt("chr1", 5, "G", ".", &["0|1"]),
                    gt("chr2", 5, "G", ".", &["0|1"]),
                ],
            ),
            line("b.vcf", vec![gt("chr2", 9, "G", ".", &["0|1"])]),
        ],
    );
    assert_refused(
        data.load(GenoMode::LocalDistributed, vec![("", index)], false),
        "index.tsv",
        3,
        "contig chr2 is in",
    );
}

#[test]
fn records_java_would_store_corrupt_are_refused() {
    let ok = gt("chr1", 100, "G", ".", &["0|1"; 7]);
    let cases: Vec<(Vec<String>, u64, &str)> = vec![
        (
            vec![gt("chr1", 100, "G,T", ".", &["0|1"; 7])],
            RECORD_LINE,
            "multi-allelic",
        ),
        (
            vec![
                ok.clone(),
                gt(
                    "chr1",
                    101,
                    "G",
                    ".",
                    &["0|2", "0|0", "0|0", "0|0", "0|0", "0|0", "0|0"],
                ),
            ],
            RECORD_LINE + 1,
            "outside the biallelic forms",
        ),
        (
            vec![gt(
                "chr1",
                100,
                "G",
                ".",
                &["1/2", "0|0", "0|0", "0|0", "0|0", "0|0", "0|0"],
            )],
            RECORD_LINE,
            "outside the biallelic forms",
        ),
        (
            vec![gt(
                "chr1",
                100,
                "G",
                ".",
                &["0", "0|0", "0|0", "0|0", "0|0", "0|0", "0|0"],
            )],
            RECORD_LINE,
            "each must be three characters",
        ),
        (
            vec![ok.clone(), gt("chr1", 99, "G", ".", &["0|1"; 7])],
            RECORD_LINE + 1,
            "records must be sorted",
        ),
        (
            vec![
                ok.clone(),
                gt("chr2", 5, "G", ".", &["0|1"; 7]),
                gt("chr1", 900, "G", ".", &["0|1"; 7]),
            ],
            RECORD_LINE + 2,
            "appears again",
        ),
        (
            vec![gt(
                "chr1",
                100,
                "G",
                "Variant_consequence_calculated=;Gene_with_variant=g",
                &["0|1"; 7],
            )],
            RECORD_LINE,
            "fewer than six fields",
        ),
        (
            vec![gt("chr1", 100, "G", "X=1;Gene_with_variant", &["0|1"; 7])],
            RECORD_LINE,
            "reads past it",
        ),
        (
            vec![gt("chr1", 0, "G", ".", &["0|1"; 7]).replacen("\t0\t", "\t00\t", 1)],
            RECORD_LINE,
            "leading zeros",
        ),
        (vec![ok.replace("\tGT\t", "\tGQ\t")], RECORD_LINE, "not GT"),
        (
            vec![ok.replace("\tGT\t", "\tDP:GT\t")],
            RECORD_LINE,
            "does not start with GT",
        ),
        (
            vec![ok.clone(), "#comment".into()],
            RECORD_LINE + 1,
            "header line among the records",
        ),
        (
            vec![ok.replace("\t.\tA\t", "\t.\tA,C\t")],
            RECORD_LINE,
            "REF holds a comma",
        ),
        (
            vec![gt("chr1", 100, "G", ".", &["0|1"; 6])],
            RECORD_LINE,
            "fixed offset",
        ),
    ];
    for (records, line, text) in cases {
        let records: Vec<&str> = records.iter().map(String::as_str).collect();
        let (_, result) = load_one(&records, &SEVEN, &SEVEN_IDS, false);
        assert_refused(result, "chr1.vcf", line, text);
    }
}

#[test]
fn non_gt_only_records_read_the_genotype_subfield() {
    let record =
        "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT:DP\t0/1:3\t1/1:4\t0/0:1\t./1:2\t0|0:9\t0|0:9\t0|0:9";
    let (data, result) = load_one(&[record], &SEVEN, &SEVEN_IDS, false);
    result.unwrap();
    assert!(
        data.dump()
            .contains("V\tchr1,100,A,G,NULL,NULL\t1\ts:7\ts:6\ts:4\t-\t1\t.\n")
    );

    let record = "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT:DP\t0/1:3\t1:4\t0/0\t0/0\t0/0\t0/0\t0/0";
    let (_, result) = load_one(&[record], &SEVEN, &SEVEN_IDS, false);
    assert_refused(result, "chr1.vcf", RECORD_LINE, "not three characters");

    let record = "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT:DP\t0/1\t0/1\t0/0\t0/0\t0/0\t0/0\t1/1";
    let (_, result) = load_one(&[record], &["S1", "S7"], &[1, 2], false);
    assert_refused(
        result,
        "chr1.vcf",
        RECORD_LINE,
        "lies past the first 2 columns",
    );
}

#[test]
fn index_and_header_refusals() {
    let data = Dataset::new();
    let index = data.index(
        "index.tsv",
        &[Line {
            file: "a.vcf",
            text: vcf(&SEVEN, &[]),
            gzipped: false,
            samples: &SEVEN,
            patients: &SEVEN_IDS,
        }],
    );
    assert_refused(
        data.load(GenoMode::LocalDistributed, vec![("", index)], false),
        "a.vcf",
        0,
        "holds no record",
    );
    let path = data.path("bad.tsv");
    std::fs::write(&path, "h\nx.vcf\t1\t1\t0\t\"A,B\"\t\"1\"\n").unwrap();
    assert_refused(
        data.load(GenoMode::LocalDistributed, vec![("", path.clone())], false),
        "bad.tsv",
        2,
        "2 sample ids and 1 patient ids",
    );
    std::fs::write(&path, "h\nx.vcf\t1\t1\t0\t\"A\"\t\"-1\"\n").unwrap();
    assert_refused(
        data.load(GenoMode::LocalDistributed, vec![("", path)], false),
        "bad.tsv",
        2,
        "not an integer from 0",
    );
    match data.load(GenoMode::Local, vec![("", data.path("x"))], false) {
        Err(EtlError::Usage(detail)) => {
            assert!(detail.contains("localPatientDistributed"), "{detail}")
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn refused_loads_leave_no_store() {
    let records = [gt("chr1", 100, "G,T", ".", &["0|1"; 7])];
    let records: Vec<&str> = records.iter().map(String::as_str).collect();
    let (data, result) = load_one(&records, &SEVEN, &SEVEN_IDS, false);
    assert!(result.is_err());
    assert!(!data.path("store").exists());
    let _: &Path = data.dir.path();
}

#[test]
fn carriage_returns_and_unusable_contigs() {
    let ok = gt("chr1", 100, "G", ".", &["0|1"; 7]);
    let crlf = format!("{ok}\r");
    let (_, result) = load_one(&[&crlf], &SEVEN, &SEVEN_IDS, false);
    result.unwrap();
    let inner = ok.replace("\tPASS\t", "\tPA\rSS\t");
    let (_, result) = load_one(&[&ok, &inner], &SEVEN, &SEVEN_IDS, false);
    assert_refused(result, "chr1.vcf", RECORD_LINE + 1, "carriage return");
    for contig in [".", "..", "a/b"] {
        let record = gt(contig, 100, "G", ".", &["0|1"; 7]);
        let (_, result) = load_one(&[&record], &SEVEN, &SEVEN_IDS, false);
        assert_refused(
            result,
            "chr1.vcf",
            RECORD_LINE,
            "cannot name the node directory",
        );
    }
}

/// Two VCFs declaring INFO column AF with different descriptions, under the given index
/// chromosomes; returns the load result and the dump's AF line when it loaded.
fn described_twice(chromosomes: &[&str]) -> (Result<GenomicLoadReport, EtlError>, Option<String>) {
    let data = Dataset::new();
    let with_description = |description: &str, record: String| {
        vcf(&["X1"], &[&record]).replace("Frequency, as a number > 0.", description)
    };
    let index = data.index_with_chromosomes(
        "index.tsv",
        &[
            Line {
                file: "b.vcf",
                text: with_description("second", gt("chr2", 5, "G", "AF=1", &["0|1"])),
                gzipped: false,
                samples: &["X1"],
                patients: &[10],
            },
            Line {
                file: "a.vcf",
                text: with_description("first", gt("chr1", 5, "G", "AF=2", &["0|1"])),
                gzipped: false,
                samples: &["X1"],
                patients: &[10],
            },
        ],
        chromosomes,
    );
    let result = data.load(GenoMode::LocalDistributed, vec![("", index)], false);
    let line = result.is_ok().then(|| {
        data.dump()
            .lines()
            .find(|l| l.starts_with("I\tAF\t"))
            .unwrap()
            .to_string()
    });
    (result, line)
}

#[test]
fn all_chromosome_lines_keep_file_order_and_a_mix_with_named_lines_is_refused() {
    let (result, line) = described_twice(&["ALL", "ALL"]);
    result.unwrap();
    assert!(line.unwrap().ends_with("Description=\"second\""));
    let (result, line) = described_twice(&["2", "1"]);
    result.unwrap();
    assert!(line.unwrap().ends_with("Description=\"first\""));
    let (result, _) = described_twice(&["ALL", "1"]);
    assert_refused(
        result,
        "index.tsv",
        2,
        "describe INFO column AF differently",
    );
}

#[test]
fn a_missing_sample_after_the_last_header_sample_leaves_its_genotypes_in_place() {
    let record =
        "chr1\t100\t.\tA\tG\t.\tPASS\t.\tGT:DP\t0/0:1\t0/0:1\t0/0:1\t0/0:1\t0/0:1\t0/0:1\t1/1:9";
    let samples = ["S1", "S2", "S3", "S4", "S5", "S6", "S7", "GONE"];
    let patients = [7, 6, 5, 4, 3, 2, 1, 99];
    let (data, result) = load_one(&[record], &samples, &patients, true);
    assert_eq!(result.unwrap().dropped_samples, 1);
    let dump = data.dump();
    assert!(
        dump.contains("V\tchr1,100,A,G,NULL,NULL\t1\t-\ts:1\t-\t-\t1\t.\n"),
        "{dump}"
    );
}
