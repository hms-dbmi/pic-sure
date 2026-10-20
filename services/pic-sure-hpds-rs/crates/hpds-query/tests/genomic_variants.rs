//! The variant result types over hand-built genomic stores whose masks' presence and Java
//! form are set one by one: VARIANT_COUNT_FOR_QUERY, VARIANT_LIST_FOR_QUERY, VCF_EXCERPT,
//! AGGREGATE_VCF_EXCERPT and INFO_COLUMN_LISTING, with each Java behavior they reproduce: the
//! excerpt counts by mask form, `hen` standing in for `he`, the `TreeMap` row collapse, the
//! merge of masks across groups, the per-node threshold, the gates, the size bound, the id
//! concept and the listing's repetition per node or per group.

mod common;

use common::genomic::{RawNode, RawSpec, write_raw};
use common::{PartitionedDir, Row, filter_values, lenient, partitioned_rows};
use hpds_format::GenoMode;
use hpds_query::{
    Engine, ExcerptAnswer, GenomicFilter, GenomicOptions, GenomicStore, Query, QueryError,
    ResultType, VariantResultSettings,
};

const SEX: &str = "\\demo\\sex\\";
const NAME: &str = "\\demo\\name\\";
const AGE: &str = "\\demo\\age\\";
const GENE: &str = "Gene_with_variant";

/// A variant with the masks given per kind (`he`, `ho`, `hen`, `hon`) as positions and
/// bitmask form, of gene `gene`, with one metadata string naming the gene.
fn v<'a>(spec: &'a str, gene: &'a str, masks: [Option<(Vec<u32>, bool)>; 4]) -> RawSpec<'a> {
    RawSpec {
        spec,
        record: true,
        masks,
        gene,
        metadata: vec![format!("Gene_with_variant={gene}")],
    }
}

fn sparse(p: &[u32]) -> Option<(Vec<u32>, bool)> {
    Some((p.to_vec(), false))
}

fn bitmask(p: &[u32]) -> Option<(Vec<u32>, bool)> {
    Some((p.to_vec(), true))
}

/// Phenotype rows for `patients`: sex F for those in `women`, M otherwise.
fn sexes(patients: &[u32], women: &[u32]) -> Vec<Row<'static>> {
    patients
        .iter()
        .map(|&p| {
            (
                p,
                SEX,
                None,
                Some(if women.contains(&p) { "F" } else { "M" }),
            )
        })
        .collect()
}

/// An engine over `pheno` and the raw store of `groups` and `nodes`, with VCF_EXCERPT on.
fn engine(
    pheno: &PartitionedDir,
    dir: &std::path::Path,
    mode: GenoMode,
    groups: &[(&str, Vec<u32>)],
    nodes: &[RawNode<'_>],
) -> Engine {
    let store = write_raw(dir, &common::protection(), mode, groups, nodes);
    pheno
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
        })
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

fn body(e: &Engine, q: &Query) -> String {
    String::from_utf8(e.answer_sync(q).unwrap().body().to_vec()).unwrap()
}

/// The excerpt's rows after the header, each split on tabs.
fn rows(e: &Engine, q: &Query) -> Vec<Vec<String>> {
    body(e, q)
        .lines()
        .skip(1)
        .map(|l| l.split('\t').map(str::to_owned).collect())
        .collect()
}

/// The single-group fixture, ten patients at positions 0 to 9 (ids 1 to 10), women 1 to 5,
/// one `localDistributed` node:
///
/// - `B`: `he` a bitmask of positions 0 to 7, `ho` sparse at 9;
/// - `H`: `hen` at 0 and 1 and `ho` at 2, beside a `he` at 0 that brings patient 1 into the
///   subset;
/// - `N`: one variant only `hen` holds at 5, one `he` holds at 5;
/// - `E`: `he` present and empty, `hen` at 0, `ho` at 1, beside a `he` at 0.
fn single_group(dir: &std::path::Path) -> (PartitionedDir, Engine) {
    let patients: Vec<u32> = (1..=10).collect();
    let pheno = partitioned_rows(&[("p", sexes(&patients, &[1, 2, 3, 4, 5]))]);
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants: vec![
            v(
                "chr1,100,A,G,b,c",
                "B",
                [bitmask(&[0, 1, 2, 3, 4, 5, 6, 7]), sparse(&[9]), None, None],
            ),
            v(
                "chr1,200,A,T,h,c",
                "H",
                [None, sparse(&[2]), sparse(&[0, 1]), None],
            ),
            v("chr1,300,A,C,h,c", "H", [sparse(&[0]), None, None, None]),
            v("chr1,400,A,G,n,c", "N", [None, None, sparse(&[5]), None]),
            v("chr1,500,A,G,n,c", "N", [sparse(&[5]), None, None, None]),
            v(
                "chr1,600,A,G,e,c",
                "E",
                [sparse(&[]), sparse(&[1]), sparse(&[0]), None],
            ),
            v("chr1,700,A,G,e,c", "E", [sparse(&[0]), None, None, None]),
        ],
    }];
    let e = engine(
        &pheno,
        dir,
        GenoMode::LocalDistributed,
        &[("", patients)],
        &nodes,
    );
    (pheno, e)
}

#[test]
fn bitmask_counts_carry_the_padding_bits_and_column_two_is_over_four() {
    let dir = tempfile::tempdir().unwrap();
    let (_pheno, e) = single_group(dir.path());
    let mut q = gene_query(ResultType::VcfExcerpt, &["B"]);
    let all = rows(&e, &q);
    assert_eq!(all.len(), 1);
    assert_eq!(
        all[0][4..],
        [
            "B", "13/9", "0/4", "0/1", "0/1", "0/1", "0/1", "0/1", "0/1", "0/1", "0/1", "1/1"
        ],
        "nine carriers in the subset plus four padding bits, raw 8 + 4 + 1"
    );
    q.phenotypic_clause = Some(filter_values(SEX, &["F"]));
    let women = rows(&e, &q);
    assert_eq!(
        women[0][4..],
        ["B", "9/5", "4/4", "0/1", "0/1", "0/1", "0/1", "0/1"]
    );
    q.expected_result_type = ResultType::AggregateVcfExcerpt;
    assert_eq!(rows(&e, &q)[0][4..], ["B", "9/5", "4/4"]);
    assert_eq!(
        body(&e, &q).lines().next().unwrap(),
        "CHROM\tPOSITION\tREF\tALT\tGene_with_variant\tPatients with this variant in \
         subset\tPatients with this variant NOT in subset"
    );
}

#[test]
fn hen_stands_in_for_an_absent_he_but_not_for_an_empty_one() {
    let dir = tempfile::tempdir().unwrap();
    let (_pheno, e) = single_group(dir.path());
    let h = rows(&e, &gene_query(ResultType::VcfExcerpt, &["H"]));
    assert_eq!(h.len(), 2);
    assert_eq!(h[0][..2], ["chr1", "200"], "rows in VariantSpec order");
    assert_eq!(
        h[0][5..],
        ["2/2", "-1/4", "0/1", "1/1"],
        "column 1 counts hen and ho in the subset; column 2 counts only he and ho"
    );
    let e_rows = rows(&e, &gene_query(ResultType::VcfExcerpt, &["E"]));
    assert_eq!(
        e_rows[0][5..],
        ["1/2", "0/4", "0/0", "1/1"],
        "an empty he hides hen: patient 1 holds hen and reads 0/0"
    );
}

#[test]
fn variant_lists_count_hen_where_cohorts_do_not() {
    let dir = tempfile::tempdir().unwrap();
    let (_pheno, e) = single_group(dir.path());
    let n = gene_query(ResultType::VariantListForQuery, &["N"]);
    assert_eq!(body(&e, &n), "[chr1,400,A,G,n,c, chr1,500,A,G,n,c]");
    let mut count = n.clone();
    count.expected_result_type = ResultType::Count;
    assert_eq!(body(&e, &count), "1");
    count.expected_result_type = ResultType::VariantCountForQuery;
    assert_eq!(
        body(&e, &count),
        r#"{"count":2,"message":"Query ran successfully"}"#
    );
    let mut only_hen = gene_query(ResultType::VariantListForQuery, &["N"]);
    only_hen.genomic_filters.push(GenomicFilter {
        key: Some("chr1,400,A,G".into()),
        values: None,
        min: None,
        max: None,
    });
    assert_eq!(
        body(&e, &only_hen),
        "[]",
        "the spec filter keeps no he or ho carrier, so the node's patients are empty"
    );
    let mut spec_only = Query::new(ResultType::VariantListForQuery);
    spec_only.genomic_filters = only_hen.genomic_filters[1..].to_vec();
    assert_eq!(
        body(&e, &spec_only),
        "[]",
        "spec filters never seed the list"
    );
    spec_only.expected_result_type = ResultType::VariantCountForQuery;
    assert_eq!(
        body(&e, &spec_only),
        r#"{"count":0,"message":"Query ran successfully"}"#
    );
    let mut none = Query::new(ResultType::VariantCountForQuery);
    assert_eq!(
        body(&e, &none),
        r#"{"count":"0","message":"No variant filters were supplied, so no query was run."}"#
    );
    none.expected_result_type = ResultType::VcfExcerpt;
    assert_eq!(body(&e, &none), "No Variants Found\n");
}

#[test]
fn collapsed_rows_keep_the_first_spec_in_raw_byte_order_with_its_own_masks_and_metadata() {
    let dir = tempfile::tempdir().unwrap();
    let patients: Vec<u32> = (1..=4).collect();
    let pheno = partitioned_rows(&[("p", sexes(&patients, &[]))]);
    let mut leading_zero = v("chr1,0100,A,G,g,c", "G", [sparse(&[0]), None, None, None]);
    leading_zero.metadata = vec!["Gene_with_variant=G;X=zero".into()];
    let mut plain = v("chr1,100,A,G,g,c", "G", [sparse(&[1, 2]), None, None, None]);
    plain.metadata = vec!["Gene_with_variant=G;X=plain;X=other".into()];
    let mut seven = v("chr1,100,A,G,g,c,x", "G", [sparse(&[3]), None, None, None]);
    seven.metadata = vec!["Gene_with_variant=G;X=seven".into()];
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants: vec![leading_zero, plain, seven],
    }];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalDistributed,
        &[("", patients)],
        &nodes,
    );
    let count = gene_query(ResultType::VariantCountForQuery, &["G"]);
    assert_eq!(
        body(&e, &count),
        r#"{"count":3,"message":"Query ran successfully"}"#,
        "the list holds every spec"
    );
    let excerpt = rows(&e, &gene_query(ResultType::VcfExcerpt, &["G"]));
    assert_eq!(excerpt.len(), 1, "one VariantSpec key, one row");
    assert_eq!(
        excerpt[0],
        [
            "chr1", "0100", "A", "G", "G", "1/4", "0/4", "0/1", "0/0", "0/0", "0/0"
        ]
    );
}

#[test]
fn masks_merge_across_groups_as_append_mask_folds_them() {
    let dir = tempfile::tempdir().unwrap();
    let a: Vec<u32> = (1..=4).collect();
    let b: Vec<u32> = (5..=8).collect();
    let pheno = partitioned_rows(&[("p", sexes(&(1..=8).collect::<Vec<_>>(), &[]))]);
    let nodes = [
        RawNode {
            group: 0,
            contig: "chr1",
            continuous: false,
            variants: vec![
                v(
                    "chr1,100,A,G,g,c",
                    "G",
                    [sparse(&[0, 1, 3]), None, None, None],
                ),
                v(
                    "chr1,200,A,T,g,c",
                    "G",
                    [sparse(&[]), None, sparse(&[3]), None],
                ),
                v("chr1,300,A,C,g,c", "S", [sparse(&[0, 1]), None, None, None]),
            ],
        },
        RawNode {
            group: 1,
            contig: "chr1",
            continuous: false,
            variants: vec![
                v(
                    "chr1,100,A,G,g,c",
                    "G",
                    [sparse(&[0, 1, 2]), None, None, None],
                ),
                v("chr1,200,A,T,g,c", "G", [None, sparse(&[3]), None, None]),
                v("chr1,300,A,C,g,c", "S", [sparse(&[0, 1]), None, None, None]),
            ],
        },
    ];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalPatientDistributed,
        &[("A", a), ("B", b)],
        &nodes,
    );
    assert_eq!(
        body(&e, &gene_query(ResultType::VariantCountForQuery, &["G"])),
        r#"{"count":2,"message":"Query ran successfully"}"#,
        "a spec both groups list counts once"
    );
    let g = rows(&e, &gene_query(ResultType::VcfExcerpt, &["G"]));
    let cells = |row: &[String]| row[5..].to_vec();
    assert_eq!(
        cells(&g[0]),
        [
            "10/7", "0/4", "0/1", "0/1", "0/1", "0/1", "0/1", "0/1", "0/0"
        ],
        "two sparse masks of three append to a bitmask of six"
    );
    assert_eq!(
        cells(&g[1]),
        [
            "2/7", "-1/4", "0/0", "0/0", "0/1", "0/0", "0/0", "0/0", "1/1"
        ],
        "an empty sparse he beside an absent one merges to absent, so hen stands in"
    );
    let s = rows(&e, &gene_query(ResultType::AggregateVcfExcerpt, &["S"]));
    assert_eq!(
        s[0][5..],
        ["4/4", "0/4"],
        "two sparse masks of two stay sparse"
    );
}

#[test]
fn one_group_of_a_patient_layout_passes_its_masks_through() {
    let dir = tempfile::tempdir().unwrap();
    let patients: Vec<u32> = (1..=8).collect();
    let pheno = partitioned_rows(&[("p", sexes(&patients, &[]))]);
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants: vec![
            v(
                "chr1,100,A,G,g,c",
                "G",
                [bitmask(&[0, 1]), None, None, None],
            ),
            v(
                "chr1,200,A,T,g,c",
                "G",
                [sparse(&[]), sparse(&[2]), sparse(&[3]), None],
            ),
        ],
    }];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalPatientDistributed,
        &[("only", patients)],
        &nodes,
    );
    let g = rows(&e, &gene_query(ResultType::AggregateVcfExcerpt, &["G"]));
    assert_eq!(
        g[0][5..],
        ["6/3", "0/4"],
        "a bitmask of two stays a bitmask"
    );
    assert_eq!(g[1][5..], ["1/3", "0/4"], "the empty he stays present");
}

#[test]
fn a_node_at_the_threshold_lists_its_whole_seed_unfiltered() {
    let dir = tempfile::tempdir().unwrap();
    let patients = vec![1, 2];
    let pheno = partitioned_rows(&[("p", sexes(&patients, &[2]))]);
    let specs: Vec<String> = (0..1_000)
        .flat_map(|b| (1..=100).map(move |j| format!("chr1,{},A,G,g,c", b * 1_000 + j)))
        .collect();
    let variants: Vec<RawSpec<'_>> = specs
        .iter()
        .enumerate()
        .map(|(i, spec)| {
            let (bucket, first) = (i / 100, i % 100 == 0);
            let mut carriers = Vec::new();
            if first {
                carriers.push(0);
                if bucket < 500 {
                    carriers.push(1);
                }
            }
            RawSpec {
                spec,
                record: i != 150,
                masks: [
                    (!carriers.is_empty()).then_some((carriers, false)),
                    None,
                    None,
                    None,
                ],
                gene: "G",
                metadata: vec![],
            }
        })
        .collect();
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants,
    }];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalDistributed,
        &[("", patients)],
        &nodes,
    );
    let count = |clause| {
        let mut q = gene_query(ResultType::VariantCountForQuery, &["G"]);
        q.phenotypic_clause = clause;
        body(&e, &q)
    };
    assert_eq!(
        count(None),
        r#"{"count":100000,"message":"Query ran successfully"}"#,
        "every bucket holds a carrier, so 100,000 pass the prefilter and the seed comes back"
    );
    assert_eq!(
        count(Some(filter_values(SEX, &["F"]))),
        r#"{"count":500,"message":"Query ran successfully"}"#,
        "half the buckets pass, so each variant is tested against the patients"
    );
    let excerpt = gene_query(ResultType::AggregateVcfExcerpt, &["G"]);
    assert!(
        matches!(e.vcf_excerpt(&excerpt), Err(QueryError::GenomicFilter(m)) if m.contains("no node holds")),
        "the seed holds a variant without a mask record, which Java's getMasks cannot find"
    );
}

#[test]
fn the_gates_follow_javas_two_settings() {
    let dir = tempfile::tempdir().unwrap();
    let (_pheno, e) = single_group(dir.path());
    let set = |vcf, aggregate| VariantResultSettings {
        vcf_excerpt_enabled: vcf,
        aggregate_vcf_excerpt_enabled: aggregate,
        ..VariantResultSettings::default()
    };
    let e = e.with_variant_settings(set(false, true));
    let answer = |t| body(&e, &gene_query(t, &["B"]));
    assert_eq!(
        answer(ResultType::VcfExcerpt),
        "VCF_EXCERPT query type not allowed"
    );
    assert!(answer(ResultType::AggregateVcfExcerpt).starts_with("CHROM"));
    assert!(answer(ResultType::VariantListForQuery).starts_with("[chr1"));
    let e = e.with_variant_settings(set(false, false));
    let answer = |t| body(&e, &gene_query(t, &["B"]));
    assert_eq!(
        answer(ResultType::AggregateVcfExcerpt),
        "AGGREGATE_VCF_EXCERPT query type not allowed"
    );
    assert_eq!(
        answer(ResultType::VariantListForQuery),
        "VARIANT_LIST query type not allowed"
    );
    assert!(
        answer(ResultType::VariantCountForQuery).starts_with(r#"{"count":1,"#),
        "VARIANT_COUNT has no gate"
    );
}

#[test]
fn an_excerpt_is_measured_before_it_is_written_and_refused_over_the_bound() {
    let dir = tempfile::tempdir().unwrap();
    let (_pheno, e) = single_group(dir.path());
    let q = gene_query(ResultType::VcfExcerpt, &["B", "H", "N", "E"]);
    let full = body(&e, &q);
    let Ok(ExcerptAnswer::Rows(excerpt)) = e.vcf_excerpt(&q) else {
        panic!("an excerpt");
    };
    assert_eq!(excerpt.len(), full.len() as u64);
    let mut streamed = Vec::new();
    excerpt.write_to(&mut streamed).unwrap();
    assert_eq!(String::from_utf8(streamed).unwrap(), full);
    let len = full.len() as u64;
    let bounded = |max| VariantResultSettings {
        vcf_excerpt_enabled: true,
        max_excerpt_bytes: max,
        ..VariantResultSettings::default()
    };
    let e = e.with_variant_settings(bounded(len));
    assert_eq!(
        body(&e, &q),
        full,
        "an excerpt exactly at the bound is answered"
    );
    let e = e.with_variant_settings(bounded(len - 1));
    assert!(matches!(
        e.vcf_excerpt(&q),
        Err(QueryError::LimitExceeded(m)) if m.contains("HPDS_MAX_EXCERPT_BYTES")
    ));
    let mut list = q.clone();
    list.expected_result_type = ResultType::VariantListForQuery;
    let e = e.with_variant_settings(bounded(20));
    assert!(matches!(
        e.variant_list(&list),
        Err(QueryError::LimitExceeded(_))
    ));
}

#[test]
fn info_cells_join_a_keys_values_and_write_null_for_an_absent_key() {
    let dir = tempfile::tempdir().unwrap();
    let patients: Vec<u32> = (1..=2).collect();
    let pheno = partitioned_rows(&[("p", sexes(&patients, &[]))]);
    let mut twice = v("chr1,100,A,G,g,c", "G", [sparse(&[0]), None, None, None]);
    twice.metadata = vec![
        "Gene_with_variant=G;FLAG;Gene_with_variant=G2".into(),
        "Gene_with_variant=G3=x".into(),
    ];
    let mut absent = v("chr1,200,A,G,g,c", "G", [sparse(&[1]), None, None, None]);
    absent.metadata = vec!["OTHER=1".into()];
    let mut duplicated = v("chr1,300,A,G,g,c", "D", [sparse(&[1]), None, None, None]);
    duplicated.metadata = vec!["A=1".into(), "A=1".into()];
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants: vec![twice, absent, duplicated],
    }];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalDistributed,
        &[("", patients)],
        &nodes,
    );
    let g = rows(&e, &gene_query(ResultType::AggregateVcfExcerpt, &["G"]));
    assert_eq!(g[0][4], "G,G2");
    assert_eq!(g[1][4], "null");
    assert!(matches!(
        e.vcf_excerpt(&gene_query(ResultType::AggregateVcfExcerpt, &["D"])),
        Err(QueryError::GenomicFilter(m)) if m.contains("twice")
    ));
}

#[test]
fn the_id_concept_heads_the_patient_columns_and_the_column_order_setting_picks_columns() {
    let dir = tempfile::tempdir().unwrap();
    let patients: Vec<u32> = (1..=3).collect();
    let mut rows_in = sexes(&patients, &[]);
    rows_in.push((1, NAME, None, Some("first")));
    rows_in.push((3, NAME, None, Some("third")));
    rows_in.push((1, AGE, Some(40.0), None));
    let pheno = partitioned_rows(&[("p", rows_in)]);
    let nodes = [RawNode {
        group: 0,
        contig: "chr1",
        continuous: false,
        variants: vec![v(
            "chr1,100,A,G,g,c",
            "G",
            [sparse(&[0, 1, 2]), None, None, None],
        )],
    }];
    let e = engine(
        &pheno,
        dir.path(),
        GenoMode::LocalDistributed,
        &[("", patients)],
        &nodes,
    );
    let settings = |id: Option<&str>, order: &str| VariantResultSettings {
        vcf_excerpt_enabled: true,
        id_cube_name: id.map(str::to_owned),
        info_column_order: order.to_owned(),
        ..VariantResultSettings::default()
    };
    let q = gene_query(ResultType::VcfExcerpt, &["G"]);
    let header = |e: &Engine| body(e, &q).lines().next().unwrap().to_owned();
    let tail = "\tPatients with this variant in subset\tPatients with this variant NOT in subset";
    let e = e.with_variant_settings(settings(Some(NAME), ""));
    assert_eq!(
        header(&e),
        format!("CHROM\tPOSITION\tREF\tALT\t{GENE}{tail}\tfirst\t2\tthird")
    );
    let e = e.with_variant_settings(settings(Some("\\demo\\missing\\"), "X,Gene_with_variant"));
    assert_eq!(
        header(&e),
        format!("CHROM\tPOSITION\tREF\tALT\t{GENE}{tail}\t1\t2\t3")
    );
    let e = e.with_variant_settings(settings(None, "X"));
    assert_eq!(
        header(&e),
        format!("CHROM\tPOSITION\tREF\tALT{tail}\t1\t2\t3"),
        "a column order naming no column of the store leaves none"
    );
    let e = e.with_variant_settings(settings(None, ","));
    assert_eq!(
        header(&e),
        format!("CHROM\tPOSITION\tREF\tALT\t{GENE}{tail}\t1\t2\t3"),
        "an order that names nothing falls back to every column"
    );
    let e = e.with_variant_settings(settings(Some(AGE), ""));
    assert!(matches!(
        e.vcf_excerpt(&q),
        Err(QueryError::GenomicFilter(m)) if m.contains("numeric")
    ));
}

#[test]
fn the_info_column_listing_repeats_per_node_or_per_group() {
    let dir = tempfile::tempdir().unwrap();
    let pheno = partitioned_rows(&[("p", sexes(&[1, 2], &[]))]);
    let node = |group, contig, continuous| RawNode {
        group,
        contig,
        continuous,
        variants: vec![v(
            if contig == "chr1" {
                "chr1,100,A,G,g,c"
            } else {
                "chr2,100,A,G,g,c"
            },
            "1.5",
            [sparse(&[0]), None, None, None],
        )],
    };
    let categorical = r#"{"key":"Gene_with_variant","description":"Description=\"The gene.\"","continuous":false,"min":3.4028235E38,"max":1.4E-45}"#;
    let continuous = categorical.replace("false", "true");
    let ld = engine(
        &pheno,
        &dir.path().join("ld"),
        GenoMode::LocalDistributed,
        &[("", vec![1, 2])],
        &[node(0, "chr1", false), node(0, "chr2", false)],
    );
    let listing = Query::new(ResultType::InfoColumnListing);
    assert_eq!(
        body(&ld, &listing),
        format!("[{categorical},{categorical}]")
    );
    assert_eq!(
        ld.answer_sync(&listing).unwrap().content_type(),
        "application/json"
    );
    let lpd = engine(
        &pheno,
        &dir.path().join("lpd"),
        GenoMode::LocalPatientDistributed,
        &[("A", vec![1]), ("B", vec![2])],
        &[
            node(0, "chr1", false),
            node(0, "chr2", false),
            node(1, "chr1", false),
            node(1, "chr2", true),
        ],
    );
    assert_eq!(
        body(&lpd, &listing),
        format!("[{categorical},{categorical},{continuous}]"),
        "once per group, and once more where a group's nodes disagree on the kind"
    );
}

/// Java checks the excerpt and variant list gates before it reads the genomic filters: with
/// the gates off, a filter Java fails on, or genomic filters without a genomic store, answer
/// the gate's text. VARIANT_COUNT has no gate, so it still refuses them.
#[test]
fn a_closed_gate_answers_before_the_genomic_filters_are_read() {
    let dir = tempfile::tempdir().unwrap();
    let (pheno, e) = single_group(dir.path());
    let invalid = |result_type| {
        let mut q = Query::new(result_type);
        q.genomic_filters = vec![
            GenomicFilter {
                key: Some("rs123".into()),
                values: None,
                min: None,
                max: None,
            },
            GenomicFilter {
                key: Some("AF".into()),
                values: None,
                min: Some(2.0),
                max: Some(1.0),
            },
        ];
        q
    };
    let closed = [
        (
            ResultType::VariantListForQuery,
            "VARIANT_LIST query type not allowed",
        ),
        (ResultType::VcfExcerpt, "VCF_EXCERPT query type not allowed"),
        (
            ResultType::AggregateVcfExcerpt,
            "AGGREGATE_VCF_EXCERPT query type not allowed",
        ),
    ];
    let e = e.with_variant_settings(VariantResultSettings::default());
    let no_store = pheno.open(&lenient()).unwrap();
    for engine in [&e, &no_store] {
        for (result_type, text) in closed {
            assert_eq!(body(engine, &invalid(result_type)), text, "{result_type}");
        }
        assert!(matches!(
            engine.variant_count(&invalid(ResultType::VariantCountForQuery)),
            Err(QueryError::GenomicFilter(_) | QueryError::Unsupported(_))
        ));
    }
    let e = e.with_variant_settings(VariantResultSettings {
        aggregate_vcf_excerpt_enabled: true,
        ..VariantResultSettings::default()
    });
    assert_eq!(
        body(&e, &invalid(ResultType::VcfExcerpt)),
        "VCF_EXCERPT query type not allowed"
    );
    assert!(matches!(
        e.vcf_excerpt(&invalid(ResultType::AggregateVcfExcerpt)),
        Err(QueryError::GenomicFilter(_))
    ));
}
