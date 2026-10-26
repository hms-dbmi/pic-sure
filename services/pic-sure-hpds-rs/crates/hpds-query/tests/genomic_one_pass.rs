//! The one-pass excerpt against the two-pass one: on every excerpt the hand-built stores below
//! answer and, when `HPDS_GENOMIC_BENCH` names the bench data directory, on every excerpt
//! request of the Java genomic integration tests on `gfx` in both layouts,
//! [`VcfExcerpt::write_measuring`] writes the bytes [`VcfExcerpt::write_to`] writes after the
//! measure pass, or refuses with the same error, at byte bounds below, at and above the
//! excerpt's length.

mod common;

use std::path::PathBuf;

use common::genomic::{RawNode, RawSpec, write_raw};
use common::{Row, lenient, partitioned_rows};
use hpds_format::GenoMode;
use hpds_query::{
    Engine, ExcerptAnswer, GenomicFilter, GenomicOptions, GenomicStore, Query, ResultType,
    VariantResultSettings, VcfExcerpt, parse_request_body,
};
use serde_json::Value;

const SEX: &str = "\\demo\\sex\\";
const GENE: &str = "Gene_with_variant";

/// A store's groups: name and patients.
type Groups = Vec<(&'static str, Vec<u32>)>;

/// The answer of one pass or two: the body, Java's text, or the refusal's text.
#[derive(Debug, PartialEq, Eq)]
enum Outcome {
    Body(Vec<u8>),
    Text(String),
    Refused(String),
}

fn two_pass(e: &Engine, q: &Query) -> Outcome {
    match e.vcf_excerpt(q) {
        Err(error) => Outcome::Refused(error.to_string()),
        Ok(ExcerptAnswer::Text(text)) => Outcome::Text(text),
        Ok(ExcerptAnswer::Rows(excerpt)) => {
            let mut body = Vec::new();
            excerpt.write_to(&mut body).unwrap();
            assert_eq!(body.len() as u64, excerpt.len());
            Outcome::Body(body)
        }
    }
}

fn one_pass(e: &Engine, q: &Query) -> Outcome {
    match e.vcf_excerpt_once(q) {
        Err(error) => Outcome::Refused(error.to_string()),
        Ok(ExcerptAnswer::Text(text)) => Outcome::Text(text),
        Ok(ExcerptAnswer::Rows(mut excerpt)) => {
            let mut body = Vec::new();
            match excerpt.write_measuring(&mut body) {
                Err(error) => {
                    assert!(body.len() as u64 <= e.variant_settings().max_excerpt_bytes);
                    Outcome::Refused(error.to_string())
                }
                Ok(length) => {
                    assert_eq!(length, body.len() as u64);
                    assert_eq!(length, VcfExcerpt::len(&excerpt));
                    Outcome::Body(body)
                }
            }
        }
    }
}

/// Compares the two on `q` with the engine's settings, then with the byte bound one below,
/// at and one above the excerpt's length, and returns how many comparisons were made.
fn compare(e: Engine, q: &Query, what: &str) -> (Engine, usize) {
    let first = two_pass(&e, q);
    assert_eq!(one_pass(&e, q), first, "{what}");
    let Outcome::Body(body) = first else {
        return (e, 1);
    };
    let settings = e.variant_settings().clone();
    let mut e = e;
    let length = body.len() as u64;
    for bound in [length - 1, length, length + 1] {
        e = e.with_variant_settings(VariantResultSettings {
            max_excerpt_bytes: bound,
            ..settings.clone()
        });
        let expected = two_pass(&e, q);
        assert_eq!(
            expected == Outcome::Body(body.clone()),
            bound >= length,
            "{what}"
        );
        assert_eq!(one_pass(&e, q), expected, "{what} at {bound}");
    }
    (e.with_variant_settings(settings), 4)
}

fn masks(he: &[u32], ho: &[u32], hen: &[u32], bitmask: bool) -> [Option<(Vec<u32>, bool)>; 4] {
    let kind = |p: &[u32]| (!p.is_empty()).then(|| (p.to_vec(), bitmask));
    [kind(he), kind(ho), kind(hen), None]
}

fn spec<'a>(spec: &'a str, gene: &'a str, m: [Option<(Vec<u32>, bool)>; 4]) -> RawSpec<'a> {
    RawSpec {
        spec,
        record: true,
        masks: m,
        gene,
        metadata: vec![format!("{GENE}={gene};AF=0.{}", spec.len())],
    }
}

fn gene_query(result_type: ResultType, genes: &[&str]) -> Query {
    let mut q = Query::new(result_type);
    q.genomic_filters = vec![GenomicFilter {
        key: Some(GENE.into()),
        values: Some(genes.iter().map(|g| Some((*g).to_owned())).collect()),
        min: None,
        max: None,
    }];
    q
}

/// Variants of genes `A` (sparse and bitmask masks, `hen` standing in for `he`), `T`, `D`
/// (a metadata string held twice) and `N` (no mask record), on node `contig` of `group`.
fn variants(group: u16, contig: &str, offset: u32) -> RawNode<'_> {
    let p = |x: u32| x + offset;
    let mut twice = spec("chrX,300,A,G,d,c", "D", masks(&[p(1)], &[], &[], false));
    twice.metadata = vec!["A=1".into(), "A=1".into()];
    RawNode {
        group,
        contig,
        continuous: false,
        variants: vec![
            spec(
                "chrX,100,A,G,a,c",
                "A",
                masks(&[p(0), p(1)], &[p(2)], &[], false),
            ),
            spec(
                "chrX,150,A,T,a,c",
                "A",
                masks(&[p(0), p(1), p(2), p(3), p(4), p(5)], &[p(6)], &[], true),
            ),
            spec("chrX,160,A,C,a,c", "A", masks(&[], &[p(3)], &[p(4)], false)),
            spec("chrX,200,A,G,t,c", "T", masks(&[p(5)], &[], &[], false)),
            twice,
            RawSpec {
                record: false,
                ..spec("chrX,400,A,G,n,c", "N", masks(&[], &[], &[], false))
            },
        ],
    }
}

/// An engine over phenotype rows for `patients` and the raw store of `groups` and `nodes`,
/// with the phenotype directory it reads.
fn engine(
    dir: &std::path::Path,
    patients: &[u32],
    mode: GenoMode,
    groups: &[(&str, Vec<u32>)],
    nodes: &[RawNode<'_>],
) -> (common::PartitionedDir, Engine) {
    let rows: Vec<Row<'static>> = patients
        .iter()
        .map(|&p| (p, SEX, None, Some(if p % 2 == 0 { "F" } else { "M" })))
        .collect();
    let pheno = partitioned_rows(&[("p", rows)]);
    let store = write_raw(dir, &common::protection(), mode, groups, nodes);
    let e = pheno
        .open(&lenient())
        .unwrap()
        .with_genomic(
            GenomicStore::open_with_key_file(
                &store,
                &pheno.out.join("encryption_key"),
                &GenomicOptions::default(),
            )
            .unwrap(),
        )
        .with_variant_settings(VariantResultSettings {
            vcf_excerpt_enabled: true,
            ..VariantResultSettings::default()
        });
    (pheno, e)
}

#[test]
fn one_pass_writes_the_two_pass_excerpt_or_its_refusal_on_hand_built_stores() {
    let mut compared = 0;
    let cases: [(GenoMode, Groups); 3] = [
        (GenoMode::LocalDistributed, vec![("", (1..=10).collect())]),
        (GenoMode::Local, vec![("", (1..=10).collect())]),
        (
            GenoMode::LocalPatientDistributed,
            vec![("a", (1..=10).collect()), ("b", (11..=20).collect())],
        ),
    ];
    for (mode, groups) in cases {
        let dir = tempfile::tempdir().unwrap();
        let patients: Vec<u32> = groups.iter().flat_map(|(_, p)| p.clone()).collect();
        let nodes: Vec<RawNode<'_>> = (0..groups.len() as u16)
            .map(|g| variants(g, "chrX", 0))
            .collect();
        let (_pheno, mut e) = engine(dir.path(), &patients, mode, &groups, &nodes);
        for result_type in [ResultType::VcfExcerpt, ResultType::AggregateVcfExcerpt] {
            for genes in [
                &["A"][..],
                &["T"],
                &["A", "T"],
                &["D"],
                &["N"],
                &["A", "D"],
                &["A", "N"],
                &["Z"],
            ] {
                let what = format!("{mode:?} {result_type} {genes:?}");
                let (back, n) = compare(e, &gene_query(result_type, genes), &what);
                e = back;
                compared += n;
            }
        }
    }
    assert!(compared >= 60, "{compared}");
}

fn bench_dir() -> Option<PathBuf> {
    std::env::var_os("HPDS_GENOMIC_BENCH").map(PathBuf::from)
}

#[test]
fn one_pass_writes_the_two_pass_excerpt_of_every_gfx_integration_request() {
    let Some(bench) = bench_dir() else {
        eprintln!("HPDS_GENOMIC_BENCH is not set; skipping the gfx excerpts");
        return;
    };
    let pheno = common::harm_fixture();
    for layout in ["ld", "lpd"] {
        let store = GenomicStore::open_with_key_file(
            &bench.join(format!("gfx/{layout}/rust")),
            &bench.join("gfx/pheno/encryption_key"),
            &GenomicOptions::default(),
        )
        .unwrap();
        let mut e = pheno
            .open(&lenient())
            .unwrap()
            .with_genomic(store)
            .with_variant_settings(VariantResultSettings {
                vcf_excerpt_enabled: true,
                ..VariantResultSettings::default()
            });
        let log =
            std::fs::read_to_string(bench.join(format!("evidence/gfx-{layout}-integration.jsonl")))
                .unwrap();
        let mut excerpts = 0;
        for (i, line) in log.lines().enumerate() {
            let entry: Value = serde_json::from_str(line).unwrap();
            if entry["path"] != "query/sync" {
                continue;
            }
            let body = parse_request_body(entry["request"].to_string().as_bytes()).unwrap();
            let query: Query = match &body["query"] {
                Value::String(text) => Query::from_json(text.as_bytes()).unwrap(),
                object => serde_json::from_value(object.clone()).unwrap(),
            };
            if !matches!(
                query.expected_result_type,
                ResultType::VcfExcerpt | ResultType::AggregateVcfExcerpt
            ) {
                continue;
            }
            let (back, _) = compare(e, &query, &format!("{layout} entry {i}"));
            e = back;
            excerpts += 1;
        }
        eprintln!("gfx {layout}: {excerpts} excerpts written alike in one pass and two");
        assert!(excerpts > 0);
    }
}
