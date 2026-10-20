//! Genomic filters on cohorts: COUNT, the cross counts, PATIENTS and DATAFRAME over a
//! phenotype store and a genomic store built from VCFs, in both distributed layouts, with
//! every Java behavior of the cohort path reproduced and every Java failure refused.

mod common;

use std::path::Path;

use common::genomic::{GroupVcfs, NodeVcf, RawVariant, load_genomic, rec, write_raw_store};
use common::{PartitionedDir, Row, filter_values, lenient, partitioned_rows, query, required};
use hpds_format::GenoMode;
use hpds_query::{
    Engine, GenomicFilter, GenomicOptions, GenomicStore, Query, QueryError, ResultType,
};
use roaring::RoaringBitmap;

const SEX: &str = "\\demo\\sex\\";
const AGE: &str = "\\demo\\age\\";
const GENE: &str = "Gene_with_variant";
const CONSEQUENCE: &str = "Variant_consequence_calculated";
const AF: &str = "AF";

/// A phenotype store of partitions `pA` (patients 1 to 10) and `pB` (11 to 20), each patient
/// with a sex (odd `M`, even `F`) and an age (twice the id), and a genomic store over the same
/// patients, loaded from VCFs in `mode`: in `localDistributed` one group, in
/// `localPatientDistributed` group `gA` holding patients 1 to 10 and `gB` 11 to 20. Each group
/// has nodes `chr1` and `chr2` holding:
///
/// | spec | gene | consequence | AF | carriers |
/// |---|---|---|---|---|
/// | `chr1,100,A,G,G1,missense_variant` | G1 | missense | 0.1 | 1 `0/1`, 2 `1/1`, 3 `./1`, 4 `./.` |
/// | `chr1,100,A,G,G2,synonymous_variant` | G2 | synonymous | 0.2 | 6 `1/1` |
/// | `chr1,100,A,GT,G1,frameshift_variant` | G1 | frameshift | 0.2 | 5 `0/1` |
/// | `chr1,1500,A,T,G3,missense_variant` | G3 | missense | 0.3 | 7, 11 `0/1` |
/// | `chr1,2500,A,T,G1,missense_variant` | G1 | missense | 0.1 | 8, 12 `0/1` |
/// | `chr2,100,A,C,G4,missense_variant` | G4 | missense | 0.2 | 1 `0/1`, 13 `1/1` |
/// | `chr2,5000,A,C,G1,stop_gained` | G1 | stop_gained | 0.5 | 2, 14 `0/1` |
/// | `chr2,6000,A,C,D_____E,stop_lost` | `D_____E` | stop_lost | 0.5 | 15 `0/1` |
///
/// AF is numeric on both nodes, so each node's largest value (0.3 on chr1, 0.5 on chr2) is the
/// last of its only `CompressedIndex` partition and can never match.
struct Fixture {
    _pheno: PartitionedDir,
    _genomic: tempfile::TempDir,
    engine: Engine,
}

fn phenotype_rows(patients: std::ops::RangeInclusive<u32>) -> Vec<Row<'static>> {
    patients
        .flat_map(|p| {
            let sex = if p % 2 == 1 { "M" } else { "F" };
            [
                (p, SEX, None, Some(sex)),
                (p, AGE, Some(f64::from(p * 2)), None),
            ]
        })
        .collect()
}

fn nodes_of(patients: &[u32]) -> Vec<NodeVcf<'static>> {
    let keep = |gts: &[(u32, &'static str)]| -> Vec<(u32, &'static str)> {
        gts.iter()
            .copied()
            .filter(|(p, _)| patients.contains(p))
            .collect()
    };
    vec![
        NodeVcf {
            chrom: "chr1",
            patients: patients.to_vec(),
            records: vec![
                rec(
                    "chr1",
                    100,
                    "G",
                    "G1",
                    "missense_variant",
                    "0.1",
                    &keep(&[(1, "0|1"), (2, "1|1"), (3, "./1"), (4, "./.")]),
                ),
                rec(
                    "chr1",
                    100,
                    "G",
                    "G2",
                    "synonymous_variant",
                    "0.2",
                    &keep(&[(6, "1|1")]),
                ),
                rec(
                    "chr1",
                    100,
                    "GT",
                    "G1",
                    "frameshift_variant",
                    "0.2",
                    &keep(&[(5, "0|1")]),
                ),
                rec(
                    "chr1",
                    1500,
                    "T",
                    "G3",
                    "missense_variant",
                    "0.3",
                    &keep(&[(7, "0|1"), (11, "0|1")]),
                ),
                rec(
                    "chr1",
                    2500,
                    "T",
                    "G1",
                    "missense_variant",
                    "0.1",
                    &keep(&[(8, "0|1"), (12, "0|1")]),
                ),
            ],
        },
        NodeVcf {
            chrom: "chr2",
            patients: patients.to_vec(),
            records: vec![
                rec(
                    "chr2",
                    100,
                    "C",
                    "G4",
                    "missense_variant",
                    "0.2",
                    &keep(&[(1, "0|1"), (13, "1|1")]),
                ),
                rec(
                    "chr2",
                    5000,
                    "C",
                    "G1",
                    "stop_gained",
                    "0.5",
                    &keep(&[(2, "0|1"), (14, "0|1")]),
                ),
                rec(
                    "chr2",
                    6000,
                    "C",
                    "D_____E",
                    "stop_lost",
                    "0.5",
                    &keep(&[(15, "0|1")]),
                ),
            ],
        },
    ]
}

fn fixture(mode: GenoMode) -> Fixture {
    fixture_with(mode, &GenomicOptions::default())
}

fn fixture_with(mode: GenoMode, options: &GenomicOptions) -> Fixture {
    let pheno = partitioned_rows(&[
        ("pA", phenotype_rows(1..=10)),
        ("pB", phenotype_rows(11..=20)),
    ]);
    let genomic = tempfile::tempdir().unwrap();
    let key = pheno.out.join("encryption_key");
    let groups = match mode {
        GenoMode::LocalPatientDistributed => vec![
            GroupVcfs {
                name: "gA",
                nodes: nodes_of(&(1..=10).collect::<Vec<_>>()),
            },
            GroupVcfs {
                name: "gB",
                nodes: nodes_of(&(11..=20).collect::<Vec<_>>()),
            },
        ],
        _ => vec![GroupVcfs {
            name: "",
            nodes: nodes_of(&(1..=20).collect::<Vec<_>>()),
        }],
    };
    let store_dir = genomic.path().join("store");
    load_genomic(
        &genomic.path().join("work"),
        &key,
        mode,
        &groups,
        &store_dir,
    );
    let store = GenomicStore::open_with_key_file(&store_dir, &key, options).unwrap();
    let engine = pheno.open(&lenient()).unwrap().with_genomic(store);
    Fixture {
        _pheno: pheno,
        _genomic: genomic,
        engine,
    }
}

const LAYOUTS: [GenoMode; 2] = [
    GenoMode::LocalDistributed,
    GenoMode::LocalPatientDistributed,
];

fn categorical(key: &str, values: &[&str]) -> GenomicFilter {
    GenomicFilter {
        key: Some(key.into()),
        values: Some(values.iter().map(|v| Some((*v).to_owned())).collect()),
        ..GenomicFilter::default()
    }
}

fn numeric(key: &str, min: f32, max: f32) -> GenomicFilter {
    GenomicFilter {
        key: Some(key.into()),
        min: Some(min),
        max: Some(max),
        ..GenomicFilter::default()
    }
}

fn spec(key: &str) -> GenomicFilter {
    GenomicFilter {
        key: Some(key.into()),
        ..GenomicFilter::default()
    }
}

fn zygous(key: &str, values: &[&str]) -> GenomicFilter {
    categorical(key, values)
}

fn genomic_query(result_type: ResultType, filters: Vec<GenomicFilter>) -> Query {
    let mut q = query(result_type, None);
    q.genomic_filters = filters;
    q
}

fn patients(engine: &Engine, filters: Vec<GenomicFilter>) -> Vec<u32> {
    engine
        .patients(&genomic_query(ResultType::Patients, filters))
        .unwrap()
        .iter()
        .collect()
}

fn refusal(engine: &Engine, q: &Query) -> String {
    match engine.count(q) {
        Err(error @ QueryError::GenomicFilter(_)) => {
            assert!(error.is_client_error());
            error.to_string()
        }
        other => panic!("expected a genomic filter refusal, got {other:?}"),
    }
}

#[test]
fn info_filters_union_he_and_ho_per_node_and_never_count_no_calls() {
    for mode in LAYOUTS {
        let f = fixture(mode);
        let e = &f.engine;
        assert_eq!(
            patients(e, vec![categorical(GENE, &["G1"])]),
            [1, 2, 5, 8, 12, 14],
            "{mode:?}"
        );
        assert_eq!(
            patients(e, vec![categorical(GENE, &["G1", "G3"])]),
            [1, 2, 5, 7, 8, 11, 12, 14]
        );
        assert_eq!(
            patients(e, vec![categorical(GENE, &["G1", "g1", "nope"])]),
            [1, 2, 5, 8, 12, 14]
        );
        assert_eq!(
            patients(
                e,
                vec![
                    categorical(GENE, &["G1"]),
                    categorical(CONSEQUENCE, &["missense_variant"])
                ]
            ),
            [1, 2, 8, 12],
            "columns AND within a node: chr2 holds G1 and missense on different variants"
        );
        assert!(
            patients(e, vec![categorical(GENE, &[])]).is_empty(),
            "[] matches nothing"
        );
        assert!(patients(e, vec![categorical("Nope", &["x"])]).is_empty());
        assert!(
            patients(
                e,
                vec![categorical(GENE, &["G1"]), numeric("Nope", 0.0, 1.0)]
            )
            .is_empty()
        );
    }
}

#[test]
fn numeric_ranges_intersect_one_set_per_stored_value_and_never_reach_a_partition_maximum() {
    for mode in LAYOUTS {
        let f = fixture(mode);
        let e = &f.engine;
        assert_eq!(
            patients(e, vec![numeric(AF, 0.1, 0.1)]),
            [1, 2, 8, 12],
            "{mode:?}"
        );
        assert_eq!(
            patients(e, vec![numeric(AF, 0.1, 0.2)]),
            [1, 13],
            "two values on chr1 intersect to nothing; chr2 holds only 0.2 in range"
        );
        assert!(
            patients(e, vec![numeric(AF, 0.3, 0.3)]).is_empty(),
            "chr1's maximum"
        );
        assert!(
            patients(e, vec![numeric(AF, 0.5, 0.5)]).is_empty(),
            "chr2's maximum"
        );
        assert!(
            patients(e, vec![numeric(AF, 0.15, 0.16)]).is_empty(),
            "no stored value alone"
        );
        assert_eq!(
            patients(e, vec![categorical(GENE, &["G1"]), numeric(AF, 0.15, 0.16)]),
            [1, 2, 5, 8, 12, 14],
            "a range without stored values adds nothing beside another column"
        );
        assert_eq!(
            patients(e, vec![numeric(AF, f32::NEG_INFINITY, 0.1)]),
            [1, 2, 8, 12]
        );
    }
}

#[test]
fn spec_filters_match_by_raw_prefix_with_javas_zygosities() {
    for mode in LAYOUTS {
        let f = fixture(mode);
        let e = &f.engine;
        let four = "chr1,100,A,G";
        assert_eq!(
            patients(e, vec![spec(four)]),
            [1, 2, 5, 6],
            "{mode:?}: every annotation, and A>GT"
        );
        assert_eq!(
            patients(e, vec![spec("chr1,100,A,G,G1,missense_variant")]),
            [1, 2]
        );
        assert_eq!(patients(e, vec![zygous(four, &["1/1"])]), [2, 6]);
        assert_eq!(patients(e, vec![zygous(four, &["0/1"])]), [1, 5]);
        assert_eq!(patients(e, vec![zygous(four, &[""])]), [1, 2, 5, 6]);
        assert_eq!(patients(e, vec![zygous(four, &[])]), [1, 2, 5, 6]);
        for nothing in ["0/0", "0|1", "1/0", "./1"] {
            assert!(
                patients(e, vec![zygous(four, &[nothing])]).is_empty(),
                "{nothing}"
            );
        }
        assert_eq!(
            patients(e, vec![zygous(four, &["1/1"]), zygous(four, &["0/1"])]),
            [1, 5],
            "a repeated key keeps its last entry"
        );
        assert!(
            patients(e, vec![spec("chr1,0100,A,G")]).is_empty(),
            "bucket 0, no stored prefix"
        );
        assert!(
            patients(e, vec![spec("chr9,100,A,G")]).is_empty(),
            "unknown contig"
        );
        assert!(
            patients(e, vec![spec("chr1,100,,")]).is_empty(),
            "Java reads two fields and matches nothing"
        );
        assert_eq!(
            patients(e, vec![spec("chr1,100,A,G"), categorical(GENE, &["G1"])]),
            [1, 2, 5]
        );
    }
}

#[test]
fn filters_on_two_nodes_and_with_each_other_answer_empty_in_the_distributed_layouts() {
    for mode in LAYOUTS {
        let f = fixture(mode);
        let e = &f.engine;
        assert_eq!(patients(e, vec![spec("chr2,100,A,C")]), [1, 13]);
        assert!(
            patients(e, vec![spec("chr1,100,A,G"), spec("chr2,100,A,C")]).is_empty(),
            "{mode:?}: patient 1 carries both, on two nodes"
        );
        assert!(patients(e, vec![categorical(GENE, &["G4"]), spec("chr1,100,A,G")]).is_empty());
    }
}

#[test]
fn consents_and_phenotype_clauses_narrow_the_genomic_answer() {
    for mode in LAYOUTS {
        let f = fixture(mode);
        let e = &f.engine;
        let mut q = genomic_query(ResultType::Count, vec![categorical(GENE, &["G1"])]);
        assert_eq!(e.count(&q).unwrap(), 6);
        q.user_consents = Query::with_consents(ResultType::Count, ["pA"]).user_consents;
        assert_eq!(e.count(&q).unwrap(), 4, "{mode:?}");
        q.user_consents = Query::with_consents(ResultType::Count, ["pB"]).user_consents;
        assert_eq!(e.count(&q).unwrap(), 2);
        let mut males = genomic_query(ResultType::Patients, vec![categorical(GENE, &["G1"])]);
        males.phenotypic_clause = Some(filter_values(SEX, &["M"]));
        assert_eq!(
            e.patients(&males).unwrap().iter().collect::<Vec<_>>(),
            [1, 5]
        );
        let mut none = genomic_query(ResultType::Count, vec![categorical(GENE, &["G1"])]);
        none.phenotypic_clause = Some(filter_values(SEX, &["X"]));
        assert_eq!(e.count(&none).unwrap(), 0);
        let only_dropped = genomic_query(ResultType::Count, vec![spec(GENE)]);
        assert_eq!(
            e.count(&only_dropped).unwrap(),
            20,
            "a filter without values or bounds is dropped"
        );
    }
}

#[test]
fn cross_counts_patients_and_dataframes_run_on_the_narrowed_cohort() {
    let f = fixture(GenoMode::LocalPatientDistributed);
    let e = &f.engine;
    let g1 = || vec![categorical(GENE, &["G1"])];
    let mut cross = genomic_query(ResultType::CrossCount, g1());
    cross.select = vec![SEX.into(), "chr1,100,A,G".into()];
    assert_eq!(
        e.answer_sync(&cross).unwrap().body().as_ref(),
        br#"{"\\demo\\sex\\":6,"chr1,100,A,G":0}"#
    );
    let mut categorical_cross = genomic_query(ResultType::CategoricalCrossCount, g1());
    categorical_cross.phenotypic_clause = Some(common::subquery(
        hpds_query::Operator::And,
        vec![
            filter_values(SEX, &["M", "F"]),
            required("chr1,100,A,G,G1,missense_variant"),
        ],
    ));
    assert_eq!(
        e.answer_sync(&categorical_cross).unwrap().body().as_ref(),
        br#"{"\\demo\\sex\\":{"F":0,"M":0}}"#,
        "a spec path in a phenotypic filter is an unknown concept: the cohort is empty, and \
         the spec filter has no entry"
    );
    categorical_cross.phenotypic_clause = Some(filter_values(SEX, &["M", "F"]));
    assert_eq!(
        e.answer_sync(&categorical_cross).unwrap().body().as_ref(),
        br#"{"\\demo\\sex\\":{"F":4,"M":2}}"#
    );
    let mut continuous = genomic_query(ResultType::ContinuousCrossCount, g1());
    continuous.phenotypic_clause = Some(required(AGE));
    assert_eq!(
        e.answer_sync(&continuous).unwrap().body().as_ref(),
        br#"{"\\demo\\age\\":{"2.0":1,"4.0":1,"10.0":1,"16.0":1,"24.0":1,"28.0":1}}"#
    );
    let mut observations = genomic_query(ResultType::ObservationCrossCount, g1());
    observations.select = vec![AGE.into()];
    assert_eq!(
        e.answer_sync(&observations).unwrap().body().as_ref(),
        br#"{"\\demo\\age\\":6}"#
    );
    let mut frame = genomic_query(ResultType::Dataframe, g1());
    frame.select = vec![SEX.into(), "chr1,100,A,G,G1,missense_variant".into()];
    let dataframe = e.dataframe(&frame).unwrap();
    assert_eq!(
        dataframe.header,
        ["patient_id", SEX, "chr1,100,A,G,G1,missense_variant"]
    );
    let rows: Vec<Vec<String>> = dataframe.rows.collect();
    assert_eq!(
        rows.iter().map(|r| r[0].as_str()).collect::<Vec<_>>(),
        ["1", "2", "5", "8", "12", "14"]
    );
    assert!(rows.iter().all(|r| r[2].is_empty()), "{rows:?}");
    assert_eq!(e.partitions().cohort_guard_refusals(), 0);
}

#[test]
fn what_java_fails_on_is_refused_even_for_an_empty_cohort() {
    let f = fixture(GenoMode::LocalDistributed);
    let e = &f.engine;
    let cases: Vec<Vec<GenomicFilter>> = vec![
        vec![GenomicFilter {
            values: Some(vec![Some("x".into())]),
            ..GenomicFilter::default()
        }],
        vec![GenomicFilter::default()],
        vec![spec("rs123")],
        vec![spec("chr1,1.5,A,G")],
        vec![GenomicFilter {
            values: Some(vec![Some("0/1".into()), None]),
            ..spec("chr1,100,A,G")
        }],
        vec![GenomicFilter {
            values: Some(vec![None]),
            ..spec(GENE)
        }],
        vec![GenomicFilter {
            min: Some(0.1),
            ..spec(AF)
        }],
        vec![numeric(AF, 0.2, 0.1)],
        vec![numeric(GENE, 0.0, 1.0)],
        vec![categorical(GENE, &["D_____E"])],
        vec![categorical(GENE, &["D_____E"]), spec("chr1,100,A,G")],
    ];
    for filters in cases {
        for clause in [None, Some(filter_values(SEX, &["X"]))] {
            let mut q = genomic_query(ResultType::Count, filters.clone());
            q.phenotypic_clause = clause.clone();
            assert!(!refusal(e, &q).is_empty(), "{filters:?} {clause:?}");
        }
    }
    assert!(
        patients(e, vec![categorical(GENE, &["X_____Y"])]).is_empty(),
        "a key no node holds is never looked up"
    );
    assert_eq!(
        patients(e, vec![categorical(CONSEQUENCE, &["stop_lost"])]),
        [15]
    );
}

#[test]
fn without_a_genomic_store_genomic_filters_are_unsupported_and_spec_paths_are_concepts() {
    let pheno = partitioned_rows(&[("pA", phenotype_rows(1..=10))]);
    let e = pheno.open(&lenient()).unwrap();
    let q = genomic_query(ResultType::Count, vec![categorical(GENE, &["G1"])]);
    assert!(matches!(e.count(&q), Err(QueryError::Unsupported(_))));
    assert!(matches!(e.validate(&q), Err(QueryError::Unsupported(_))));
    let mut frame = query(ResultType::Dataframe, Some(required(SEX)));
    frame.select = vec!["rs12345".into()];
    assert!(e.validate(&frame).is_ok());
    assert_eq!(e.dataframe(&frame).unwrap().rows.count(), 10);
    let spec_filter = query(ResultType::Count, Some(required("chr1,100,A,G")));
    assert!(e.validate(&spec_filter).is_ok());
    assert_eq!(e.count(&spec_filter).unwrap(), 0);
    let mut excerpt = query(ResultType::AggregateVcfExcerpt, None);
    assert!(matches!(
        e.vcf_excerpt(&excerpt),
        Ok(hpds_query::ExcerptAnswer::Text(text)) if text == "AGGREGATE_VCF_EXCERPT query type not allowed"
    ));
    excerpt.genomic_filters = q.genomic_filters.clone();
    assert!(matches!(
        e.variant_count(&excerpt),
        Err(QueryError::Unsupported(_))
    ));
}

/// A store holding a short spec, `chr9,100,A,G`, beside `chr9,100,A,G,S,c`, both of gene `S`:
/// Java builds a `VariantSpec` for every variant of an INFO set before it reads a mask, and
/// throws on the short one, but only on a node whose scope is not empty.
#[test]
fn a_short_spec_in_an_info_set_is_refused_where_java_would_read_it() {
    let pheno = partitioned_rows(&[(
        "p",
        [10, 20, 30]
            .iter()
            .map(|&p| (p, SEX, None, Some(if p == 30 { "M" } else { "F" })))
            .collect(),
    )]);
    let genomic = tempfile::tempdir().unwrap();
    let store_dir = write_raw_store(
        &genomic.path().join("store"),
        &common::protection(),
        &[10, 20, 30],
        "chr9",
        &[
            RawVariant {
                spec: "chr9,100,A,G",
                he: vec![0],
                ho: vec![],
                gene: "S",
            },
            RawVariant {
                spec: "chr9,100,A,G,S,c",
                he: vec![1],
                ho: vec![],
                gene: "S",
            },
            RawVariant {
                spec: "chr9,200,A,T,T,c",
                he: vec![2],
                ho: vec![],
                gene: "T",
            },
        ],
    );
    let key = pheno.out.join("encryption_key");
    let open = || {
        pheno.open(&lenient()).unwrap().with_genomic(
            GenomicStore::open_with_key_file(&store_dir, &key, &GenomicOptions::default()).unwrap(),
        )
    };
    let e = open();
    let s = genomic_query(ResultType::Count, vec![categorical(GENE, &["S"])]);
    assert!(refusal(&e, &s).contains("fewer than six"));
    let mut in_scope = s.clone();
    in_scope.phenotypic_clause = Some(filter_values(SEX, &["M"]));
    assert!(
        refusal(&e, &in_scope).contains("fewer than six"),
        "patient 30 is in scope"
    );
    let mut empty = s.clone();
    empty.phenotypic_clause = Some(filter_values(SEX, &["X"]));
    assert_eq!(
        e.count(&empty).unwrap(),
        0,
        "an empty scope never builds the specs"
    );
    assert_eq!(
        e.count(&genomic_query(
            ResultType::Count,
            vec![categorical(GENE, &["T"])]
        ))
        .unwrap(),
        1
    );
    assert_eq!(
        e.patients(&genomic_query(
            ResultType::Patients,
            vec![spec("chr9,100,A,G")]
        ))
        .unwrap()
        .iter()
        .collect::<Vec<_>>(),
        [10, 20],
        "a spec filter matches the short spec by prefix without parsing it"
    );
}

/// Variants of the scan fixture: on each of four nodes, 3,000 sites a thousand base pairs
/// apart in groups, carried by about one patient in a hundred, every fiftieth by a third of
/// them.
fn scan_nodes(patients: &[u32]) -> Vec<NodeVcf<'static>> {
    ["chr1", "chr2", "chr3", "chr4"]
        .iter()
        .enumerate()
        .map(|(c, &chrom)| {
            let records = (0..3_000u32)
                .map(|v| {
                    let carriers: Vec<(u32, &'static str)> = patients
                        .iter()
                        .copied()
                        .filter(|&p| {
                            if v % 50 == 0 {
                                (p + v + c as u32).is_multiple_of(3)
                            } else {
                                (p * 7 + v * 13 + c as u32).is_multiple_of(97)
                            }
                        })
                        .map(|p| (p, if (p + v) % 4 == 0 { "1|1" } else { "0|1" }))
                        .collect();
                    common::genomic::Rec {
                        contig: chrom,
                        pos: 1_000 + v * 37,
                        reference: "A",
                        alt: "G",
                        info: format!(
                            "Gene_with_variant=G{};Variant_consequence_calculated=c{};AF=0.{}",
                            v % 40,
                            v % 7,
                            v % 9 + 1
                        ),
                        genotypes: carriers,
                    }
                })
                .collect();
            NodeVcf {
                chrom,
                patients: patients.to_vec(),
                records,
            }
        })
        .collect()
}

/// The largest mask chunk of the store in `dir`, stored and decoded: what one scan permit holds.
fn one_block(dir: &Path) -> u64 {
    let index =
        hpds_format::GenoIndex::read_file(dir.join("geno.index"), &common::protection()).unwrap();
    index
        .nodes()
        .iter()
        .flat_map(|n| &n.chunks)
        .map(|c| u64::from(c.mask.stored_len) + u64::from(c.mask.decoded_len))
        .max()
        .unwrap()
}

/// Many concurrent broad scans, each fanning its node tasks out on a genomic pool of two
/// threads, more tasks than threads, under a read budget of exactly one block and a cache too
/// small to plan through: every scan completes, the answers equal an unbudgeted run's, and the
/// budget never holds more than one block.
#[test]
fn nested_scans_finish_under_a_one_block_budget() {
    let patients: Vec<u32> = (1..=48).collect();
    let pheno = partitioned_rows(&[(
        "p",
        patients
            .iter()
            .map(|&p| (p, SEX, None, Some("M")))
            .collect(),
    )]);
    let genomic = tempfile::tempdir().unwrap();
    let key = pheno.out.join("encryption_key");
    let store_dir = genomic.path().join("store");
    load_genomic(
        &genomic.path().join("work"),
        &key,
        GenoMode::LocalDistributed,
        &[GroupVcfs {
            name: "",
            nodes: scan_nodes(&patients),
        }],
        &store_dir,
    );
    let block = one_block(&store_dir);
    let chunks: usize =
        hpds_format::GenoIndex::read_file(store_dir.join("geno.index"), &common::protection())
            .unwrap()
            .nodes()
            .iter()
            .map(|n| n.chunks.len())
            .sum();
    assert!(chunks >= 8, "{chunks} chunks");
    let queries: Vec<Query> = (0..3)
        .map(|i| {
            let genes = [format!("G{}", i * 3 + 1), format!("G{}", i * 3 + 2)];
            let consequences = [format!("c{i}"), format!("c{}", i + 1)];
            genomic_query(
                ResultType::Patients,
                vec![
                    categorical(GENE, &[&genes[0], &genes[1]]),
                    categorical(CONSEQUENCE, &[&consequences[0], &consequences[1]]),
                ],
            )
        })
        .collect();
    let roomy = pheno.open(&lenient()).unwrap().with_genomic(
        GenomicStore::open_with_key_file(&store_dir, &key, &GenomicOptions::default()).unwrap(),
    );
    let expected: Vec<RoaringBitmap> = queries.iter().map(|q| roomy.patients(q).unwrap()).collect();
    assert!(expected.iter().all(|p| !p.is_empty() && p.len() < 48));
    assert_eq!(roomy.genomic().unwrap().cache_stats().scan_reads, 0);
    let tight = pheno.open(&lenient()).unwrap().with_genomic(
        GenomicStore::open_with_key_file(
            &store_dir,
            &key,
            &GenomicOptions {
                cache_bytes: 64 << 10,
                read_budget_bytes: block,
                threads: Some(2),
            },
        )
        .unwrap(),
    );
    std::thread::scope(|scope| {
        for t in 0..8 {
            let (tight, queries, expected) = (&tight, &queries, &expected);
            scope.spawn(move || {
                for round in 0..3 {
                    let which = (t + round) % queries.len();
                    assert_eq!(tight.patients(&queries[which]).unwrap(), expected[which]);
                }
            });
        }
    });
    let store = tight.genomic().unwrap();
    let stats = store.read_stats();
    assert_eq!(stats.held, 0, "every permit is released");
    assert!(stats.peak_held <= block, "{stats:?}, one block is {block}");
    assert!(stats.permits > 0, "{stats:?}");
    assert!(store.cache_stats().scan_reads > 0);
    assert_eq!(tight.partitions().cohort_guard_refusals(), 0);
}
