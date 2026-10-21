//! The main consent contract in the engine: authorization filters applied as `FILTER`
//! clauses to every result type, Java main's validator, the request model, and the search
//! shape, on a small unpartitioned store and a genomic store beside it.

mod common;

use common::genomic::{GroupVcfs, NodeVcf, load_genomic, rec};
use common::{Row, csv, filter_range, filter_values, load_csv, partitioned_rows, required};
use hpds_format::GenoMode;
use hpds_query::{
    AUTHORIZATION_FILTER_REQUIRED_JAVA_MESSAGE, AuthorizationFilter, ConsentContract, Engine,
    GenomicFilter, GenomicOptions, GenomicStore, MainQueryValidator,
    NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE, NULL_AUTHORIZATION_FILTER_MESSAGE,
    NULL_AUTHORIZATION_VALUE_MESSAGE, NULL_AUTHORIZATION_VALUES_MESSAGE, PartitionOptions, Query,
    QueryError, ResultType, VariantResultSettings, parse_strict_for,
};
use serde_json::{Value, json};

const CONSENTS: &str = "\\_consents\\";
const TOPMED: &str = "\\_topmed_consents\\";
const SEX: &str = "\\demo\\sex\\";
const AGE: &str = "\\demo\\age\\";

/// Patients 1 to 20: `\_consents\` phs1.c1 for 1 to 8, phs1.c2 for 9 to 14, phs2.c1 for 15
/// to 18, none for 19 and 20; `\_topmed_consents\` phs1.c2 for 9 to 11; sex F for odd
/// patients, M for even; age twice the patient id.
fn rows() -> Vec<Row<'static>> {
    let mut rows = Vec::new();
    for p in 1..=20u32 {
        let consent = match p {
            1..=8 => Some("phs1.c1"),
            9..=14 => Some("phs1.c2"),
            15..=18 => Some("phs2.c1"),
            _ => None,
        };
        if let Some(consent) = consent {
            rows.push((p, CONSENTS, None, Some(consent)));
        }
        if (9..=11).contains(&p) {
            rows.push((p, TOPMED, None, Some("phs1.c2")));
        }
        rows.push((p, SEX, None, Some(if p % 2 == 1 { "F" } else { "M" })));
        rows.push((p, AGE, Some(f64::from(p * 2)), None));
    }
    rows
}

/// Options of the main contract with `hpds.requireAuthorizationFilter` set to `required`.
fn main_options(required: bool) -> PartitionOptions {
    PartitionOptions {
        require_consents: required,
        contract: ConsentContract::Main,
        ..PartitionOptions::default()
    }
}

/// The store of [`rows`], opened under the main contract.
struct MainStore {
    _store: common::TestStore,
    engine: Engine,
}

fn main_store(required: bool) -> MainStore {
    let store = load_csv(&csv(&rows()));
    let engine = Engine::open(&store.out, &main_options(required)).unwrap();
    MainStore {
        _store: store,
        engine,
    }
}

/// A query read as the main contract's request model reads it.
fn parse(query: Value) -> Query {
    Query::from_value(query, ConsentContract::Main).unwrap()
}

/// A query of `result_type` with `filters` as its `authorizationFilters` and `extra` merged
/// in.
fn with_filters(result_type: &str, filters: Value, extra: Value) -> Query {
    let mut query = json!({"expectedResultType": result_type, "authorizationFilters": filters});
    for (key, value) in extra.as_object().unwrap() {
        query[key] = value.clone();
    }
    parse(query)
}

fn consents(values: &[&str]) -> Value {
    json!([{"conceptPath": CONSENTS, "values": values}])
}

/// The JSON body of a sync answer.
fn answer(engine: &Engine, query: &Query) -> Value {
    let answer = engine
        .answer_sync(query)
        .unwrap_or_else(|e| panic!("{e}: {query:?}"));
    serde_json::from_slice(answer.body()).unwrap()
}

fn count(engine: &Engine, query: &Query) -> u64 {
    engine
        .count(query)
        .unwrap_or_else(|e| panic!("{e}: {query:?}"))
}

fn refusal(engine: &Engine, query: &Query) -> QueryError {
    engine
        .count(query)
        .expect_err("the query should be refused")
}

#[test]
fn absent_null_and_empty_filters_select_every_patient() {
    let s = main_store(false);
    for query in [
        json!({"expectedResultType": "COUNT"}),
        json!({"expectedResultType": "COUNT", "authorizationFilters": null}),
        json!({"expectedResultType": "COUNT", "authorizationFilters": []}),
    ] {
        assert_eq!(count(&s.engine, &parse(query)), 20);
    }
}

#[test]
fn with_the_flag_on_a_query_without_authorization_filters_is_refused() {
    let s = main_store(true);
    for query in [
        json!({"expectedResultType": "COUNT"}),
        json!({"expectedResultType": "CROSS_COUNT", "authorizationFilters": []}),
        json!({"expectedResultType": "PATIENTS", "authorizationFilters": null}),
    ] {
        let query = parse(query);
        assert!(matches!(
            s.engine.count(&query),
            Err(QueryError::AuthorizationFilterRequired)
        ));
        assert!(matches!(
            s.engine.check_consents(&query),
            Err(QueryError::AuthorizationFilterRequired)
        ));
    }
    let message = QueryError::AuthorizationFilterRequired.to_string();
    assert!(message.contains("authorizationFilters"), "{message}");
    assert_eq!(
        count(
            &s.engine,
            &with_filters("COUNT", consents(&["phs1.c1"]), json!({}))
        ),
        8
    );
    let null_filter = with_filters("COUNT", json!([null]), json!({}));
    assert!(s.engine.check_consents(&null_filter).is_ok());
    assert!(s.engine.info_column_listing().unwrap().is_empty());
}

#[test]
fn values_are_a_set() {
    let s = main_store(false);
    let once = count(
        &s.engine,
        &with_filters("COUNT", consents(&["phs1.c1"]), json!({})),
    );
    let twice = count(
        &s.engine,
        &with_filters("COUNT", consents(&["phs1.c1", "phs1.c1"]), json!({})),
    );
    assert_eq!((once, twice), (8, 8));
    let ab = with_filters("COUNT", consents(&["phs1.c1", "phs2.c1"]), json!({}));
    let ba = with_filters("COUNT", consents(&["phs2.c1", "phs1.c1"]), json!({}));
    assert_eq!((count(&s.engine, &ab), count(&s.engine, &ba)), (12, 12));
}

#[test]
fn filters_and_with_each_other_and_with_the_clause() {
    let s = main_store(false);
    let female = json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["F"]});
    let under_20 = json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "max": 20});
    assert_eq!(
        count(
            &s.engine,
            &with_filters(
                "COUNT",
                consents(&["phs1.c1"]),
                json!({"phenotypicClause": female})
            )
        ),
        4
    );
    assert_eq!(
        count(
            &s.engine,
            &with_filters(
                "COUNT",
                consents(&["phs1.c2"]),
                json!({"phenotypicClause": under_20})
            )
        ),
        2
    );
    let repeated = json!([
        {"conceptPath": CONSENTS, "values": ["phs1.c1", "phs1.c2"]},
        {"conceptPath": CONSENTS, "values": ["phs1.c2", "phs2.c1"]},
    ]);
    assert_eq!(
        count(&s.engine, &with_filters("COUNT", repeated, json!({}))),
        6
    );
    let two_paths = json!([
        {"conceptPath": CONSENTS, "values": ["phs1.c2"]},
        {"conceptPath": TOPMED, "values": ["phs1.c2"]},
    ]);
    assert_eq!(
        count(&s.engine, &with_filters("COUNT", two_paths, json!({}))),
        3
    );
}

#[test]
fn unknown_values_unknown_concepts_and_empty_values_select_nobody() {
    let s = main_store(false);
    for filters in [
        consents(&["phs9.c9"]),
        json!([{"conceptPath": "\\no such concept\\", "values": ["x"]}]),
        json!([{"conceptPath": CONSENTS, "values": []}]),
        json!([{"conceptPath": "\\demo\\", "values": ["F"]}]),
    ] {
        assert_eq!(
            count(
                &s.engine,
                &with_filters("COUNT", filters.clone(), json!({}))
            ),
            0,
            "{filters}"
        );
    }
}

#[test]
fn values_on_a_numeric_concept_are_refused() {
    let s = main_store(false);
    let query = with_filters(
        "COUNT",
        json!([{"conceptPath": AGE, "values": ["40"]}]),
        json!({}),
    );
    assert!(matches!(
        refusal(&s.engine, &query),
        QueryError::TypeMismatch {
            expected: "categorical",
            ..
        }
    ));
    let empty = with_filters(
        "COUNT",
        json!([{"conceptPath": AGE, "values": []}]),
        json!({}),
    );
    assert_eq!(count(&s.engine, &empty), 0);
}

#[test]
fn nulls_in_authorization_filters_are_refused_where_java_fails() {
    let s = main_store(false);
    for (filters, message) in [
        (json!([null]), NULL_AUTHORIZATION_FILTER_MESSAGE),
        (
            json!([{"conceptPath": CONSENTS, "values": ["phs1.c1"]}, null]),
            NULL_AUTHORIZATION_FILTER_MESSAGE,
        ),
        (
            json!([{"conceptPath": CONSENTS, "values": null}]),
            NULL_AUTHORIZATION_VALUES_MESSAGE,
        ),
        (
            json!([{"conceptPath": CONSENTS}]),
            NULL_AUTHORIZATION_VALUES_MESSAGE,
        ),
        (
            json!([{"conceptPath": null, "values": ["phs1.c1"]}]),
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!([{"values": ["phs1.c1"]}]),
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE,
        ),
        (
            json!([{"conceptPath": null, "values": null}]),
            NULL_AUTHORIZATION_VALUES_MESSAGE,
        ),
        (
            json!([{"conceptPath": CONSENTS, "values": ["phs1.c1", null]}]),
            NULL_AUTHORIZATION_VALUE_MESSAGE,
        ),
    ] {
        let query = with_filters("COUNT", filters.clone(), json!({}));
        match refusal(&s.engine, &query) {
            QueryError::AuthorizationFilterNull(got) => assert_eq!(got, message, "{filters}"),
            other => panic!("{filters}: {other:?}"),
        }
        assert!(message.starts_with("an authorization filter"));
    }
    for filters in [
        json!([{"conceptPath": AGE, "values": [null]}]),
        json!([{"conceptPath": "\\no such concept\\", "values": [null]}]),
    ] {
        assert_eq!(
            count(
                &s.engine,
                &with_filters("COUNT", filters.clone(), json!({}))
            ),
            0,
            "{filters}"
        );
    }
}

#[test]
fn every_result_type_starts_from_the_authorized_set() {
    let s = main_store(false);
    let c1 = consents(&["phs1.c1"]);
    let cross = with_filters(
        "CROSS_COUNT",
        c1.clone(),
        json!({"select": [SEX, AGE, TOPMED]}),
    );
    assert_eq!(
        answer(&s.engine, &cross),
        json!({SEX: 8, AGE: 8, TOPMED: 0})
    );
    let observations = with_filters(
        "OBSERVATION_CROSS_COUNT",
        c1.clone(),
        json!({"select": [SEX]}),
    );
    assert_eq!(answer(&s.engine, &observations), json!({SEX: 8}));
    let sexes = json!({"phenotypicFilterType": "FILTER", "conceptPath": SEX, "values": ["F", "M"]});
    let categories = with_filters(
        "CATEGORICAL_CROSS_COUNT",
        c1.clone(),
        json!({"phenotypicClause": sexes}),
    );
    assert_eq!(
        answer(&s.engine, &categories),
        json!({SEX: {"F": 4, "M": 4}})
    );
    let ages = json!({"phenotypicFilterType": "FILTER", "conceptPath": AGE, "min": 0});
    let continuous = with_filters(
        "CONTINUOUS_CROSS_COUNT",
        c1.clone(),
        json!({"phenotypicClause": ages}),
    );
    let values = answer(&s.engine, &continuous);
    assert_eq!(values[AGE].as_object().unwrap().len(), 8, "{values}");
    let patients = s
        .engine
        .patients(&with_filters("PATIENTS", c1.clone(), json!({})))
        .unwrap();
    assert_eq!(
        patients.iter().collect::<Vec<_>>(),
        (1..=8).collect::<Vec<_>>()
    );
    let frame = s
        .engine
        .dataframe(&with_filters("DATAFRAME", c1, json!({"select": [SEX]})))
        .unwrap();
    assert_eq!(frame.rows.count(), 8);
}

#[test]
fn authorization_concepts_appear_only_in_the_cross_counts_that_select_them() {
    let s = main_store(false);
    let auth_only = with_filters("CATEGORICAL_CROSS_COUNT", consents(&["phs1.c1"]), json!({}));
    assert_eq!(answer(&s.engine, &auth_only), json!({}));
    let selected = with_filters(
        "CROSS_COUNT",
        consents(&["phs1.c1"]),
        json!({"select": [CONSENTS]}),
    );
    assert_eq!(answer(&s.engine, &selected), json!({CONSENTS: 8}));
}

#[test]
fn an_unpartitioned_store_opens_with_the_flag_on_and_a_partitioned_one_reads_as_the_union() {
    let store = load_csv(&csv(&rows()));
    assert!(Engine::open(&store.out, &main_options(true)).is_ok());
    assert!(Engine::open(&store.out, &PartitionOptions::default()).is_err());
    let all = rows();
    let (first, second): (Vec<Row<'_>>, Vec<Row<'_>>) = all.into_iter().partition(|r| r.0 <= 10);
    let dir = partitioned_rows(&[("pA", first), ("pB", second)]);
    let engine = dir.open(&main_options(true)).unwrap();
    for (values, expected) in [(vec!["phs1.c1"], 8), (vec!["phs1.c2"], 6), (vec![], 0)] {
        let query = with_filters("COUNT", consents(&values), json!({}));
        assert_eq!(count(&engine, &query), expected, "{values:?}");
    }
    assert!(
        engine
            .read_plan(&with_filters("COUNT", consents(&["phs1.c1"]), json!({})))
            .unwrap()
            .full
    );
}

/// [`MainQueryValidator`] with the flag `required` over the store's dictionary.
fn validate(s: &MainStore, required: bool, query: &Query) -> Result<(), QueryError> {
    MainQueryValidator {
        require_authorization_filter: required,
    }
    .validate(query, s.engine.partitions())
}

fn invalid(result: Result<(), QueryError>) -> String {
    match result {
        Err(QueryError::Invalid(message)) => message,
        other => panic!("expected an IllegalArgumentException, got {other:?}"),
    }
}

#[test]
fn the_validator_runs_in_javas_order_with_javas_messages() {
    let s = main_store(true);
    let bad_clause =
        json!({"phenotypicFilterType": "REQUIRED", "conceptPath": SEX, "values": ["F"]});
    let empty = with_filters(
        "PATIENTS",
        json!([]),
        json!({"phenotypicClause": bad_clause}),
    );
    assert_eq!(
        invalid(validate(&s, true, &empty)),
        AUTHORIZATION_FILTER_REQUIRED_JAVA_MESSAGE
    );
    assert!(invalid(validate(&s, false, &empty)).starts_with("Required filter with concept path"));
    let unknown_then_null = with_filters(
        "PATIENTS",
        json!([{"conceptPath": "\\x\\", "values": ["a"]}, null]),
        json!({}),
    );
    assert_eq!(
        invalid(validate(&s, true, &unknown_then_null)),
        "\\x\\ is not a valid concept path"
    );
    let null_first = with_filters(
        "PATIENTS",
        json!([null, {"conceptPath": "\\x\\", "values": ["a"]}]),
        json!({}),
    );
    assert!(matches!(
        validate(&s, true, &null_first),
        Err(QueryError::AuthorizationFilterNull(
            NULL_AUTHORIZATION_FILTER_MESSAGE
        ))
    ));
    for (filters, message) in [
        (
            json!([{"conceptPath": CONSENTS, "values": []}]),
            "AuthorizationFilter values cannot be null or empty",
        ),
        (
            json!([{"conceptPath": null, "values": null}]),
            "AuthorizationFilter values cannot be null or empty",
        ),
        (
            json!([{"conceptPath": AGE, "values": ["1"]}]),
            "\\demo\\age\\ is not a categorical variable. Authorization filters must be categorical",
        ),
        (
            json!([{"conceptPath": "\\demo\\", "values": ["1"]}]),
            "\\demo\\ is not a valid concept path",
        ),
    ] {
        let query = with_filters("PATIENTS", filters.clone(), json!({}));
        assert_eq!(invalid(validate(&s, true, &query)), message, "{filters}");
    }
    let null_path = with_filters(
        "PATIENTS",
        json!([{"conceptPath": null, "values": ["a"]}]),
        json!({}),
    );
    assert!(matches!(
        validate(&s, true, &null_path),
        Err(QueryError::AuthorizationFilterNull(
            NULL_AUTHORIZATION_CONCEPT_PATH_MESSAGE
        ))
    ));
    let valid = with_filters(
        "PATIENTS",
        json!([{"conceptPath": CONSENTS, "values": ["phs1.c1", null]}]),
        json!({"select": ["\\unknown select\\"]}),
    );
    assert!(validate(&s, true, &valid).is_ok());
    assert!(s.engine.validate(&valid).is_ok());
    assert_eq!(
        invalid(
            s.engine
                .validate(&with_filters("PATIENTS", json!([]), json!({})))
        ),
        AUTHORIZATION_FILTER_REQUIRED_JAVA_MESSAGE
    );
}

#[test]
fn the_result_cache_keys_on_the_authorization_filters() {
    let store = load_csv(&csv(&rows()));
    let engine = Engine::open(&store.out, &main_options(false))
        .unwrap()
        .with_result_cache_bytes(1 << 20);
    let c1 = with_filters("COUNT", consents(&["phs1.c1"]), json!({}));
    let c2 = with_filters("COUNT", consents(&["phs1.c2"]), json!({}));
    assert_eq!(engine.answer_sync(&c1).unwrap().body().as_ref(), b"8");
    assert!(engine.cached_sync(&c2).unwrap().is_none());
    assert_eq!(
        engine.cached_sync(&c1).unwrap().unwrap().body().as_ref(),
        b"8"
    );
    assert_eq!(engine.answer_sync(&c2).unwrap().body().as_ref(), b"6");
}

#[test]
fn the_request_model_is_mains() {
    let refused = Query::from_value(
        json!({"expectedResultType": "COUNT", "userConsents": [{"value": "a"}]}),
        ConsentContract::Main,
    )
    .unwrap_err()
    .to_string();
    assert!(
        refused.contains("unknown field `userConsents`"),
        "{refused}"
    );
    assert!(
        Query::from_value(
            json!({"expectedResultType": "COUNT", "userConsents": [{"value": "a"}]}),
            ConsentContract::Harmonization,
        )
        .is_ok()
    );
    for (query, field) in [
        (
            json!({"expectedResultType": "COUNT", "authorizationFilters": [{"conceptPath": "a", "values": [], "bogus": 1}]}),
            "bogus",
        ),
        (
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"conceptPath": "a", "operator": "AND"}}),
            "operator",
        ),
        (
            json!({"expectedResultType": "COUNT", "phenotypicClause": {"conceptPath": "a", "phenotypicFilterType": "REQUIRED", "phenotypicClauses": []}}),
            "phenotypicClauses",
        ),
    ] {
        let message = Query::from_value(query.clone(), ConsentContract::Main)
            .unwrap_err()
            .to_string();
        assert!(
            message.contains(&format!("unknown field `{field}`")),
            "{query}: {message}"
        );
    }
    let mixed = json!({"expectedResultType": "COUNT", "phenotypicClause": {"phenotypicClauses": [{"conceptPath": "a", "operator": "AND"}], "operator": "OR"}});
    let harmonization = Query::from_value(mixed.clone(), ConsentContract::Harmonization)
        .unwrap_err()
        .to_string();
    assert!(
        harmonization.starts_with(
            "a phenotypic clause cannot mix subquery fields (phenotypicClauses, operator) with \
             filter fields (phenotypicFilterType, conceptPath, values, min, max)"
        ) && !harmonization.contains("unknown field"),
        "{harmonization}"
    );
    let main = Query::from_value(mixed, ConsentContract::Main)
        .unwrap_err()
        .to_string();
    assert!(
        main.contains("unknown field `operator` for a filter"),
        "{main}"
    );
    let text = format!(
        r#"{{"expectedResultType":0,"authorizationFilters":[{{"conceptPath":{},"values":[7,1.50,true]}}]}}"#,
        serde_json::to_string(CONSENTS).unwrap()
    );
    let numbers = parse(parse_strict_for(text.as_bytes(), ConsentContract::Main).unwrap());
    assert_eq!(numbers.expected_result_type, ResultType::Count);
    assert_eq!(
        numbers.authorization_filters,
        [AuthorizationFilter {
            concept_path: Some(CONSENTS.into()),
            values: Some(vec![
                Some("7".into()),
                Some("1.50".into()),
                Some("true".into())
            ]),
        }]
    );
}

#[test]
fn a_repeated_authorization_filters_key_keeps_its_last_value() {
    let text = format!(
        r#"{{"expectedResultType":"COUNT","authorizationFilters":[{{"conceptPath":{c},"values":["phs1.c1"]}}],"authorizationFilters":[{{"conceptPath":{c},"values":["phs1.c2"]}}]}}"#,
        c = serde_json::to_string(CONSENTS).unwrap()
    );
    let value = parse_strict_for(text.as_bytes(), ConsentContract::Main).unwrap();
    let s = main_store(false);
    assert_eq!(count(&s.engine, &parse(value)), 6);
    assert!(parse_strict_for(text.as_bytes(), ConsentContract::Harmonization).is_err());
    let emptied = r#"{"expectedResultType":"COUNT","authorizationFilters":[{"conceptPath":"a","values":["b"]}],"authorizationFilters":[]}"#;
    let value = parse_strict_for(emptied.as_bytes(), ConsentContract::Main).unwrap();
    assert!(!parse(value).has_authorization_filters());
    for other in [
        r#"{"expectedResultType":"COUNT","expectedResultType":"COUNT"}"#,
        r#"{"expectedResultType":"COUNT","authorizationFilters":[{"conceptPath":"a","conceptPath":"b","values":[]}]}"#,
    ] {
        assert!(
            parse_strict_for(other.as_bytes(), ConsentContract::Main)
                .unwrap_err()
                .to_string()
                .contains("duplicate key"),
            "{other}"
        );
    }
}

#[test]
fn search_metadata_is_javas_column_meta() {
    let s = main_store(false);
    let rendered: Vec<String> = s
        .engine
        .partitions()
        .column_meta()
        .filter(|meta| meta.name() == SEX || meta.name() == AGE)
        .map(|meta| {
            let mut out = String::new();
            meta.write_json(&mut out);
            out
        })
        .collect();
    assert_eq!(
        rendered,
        [
            format!(
                r#"{{"name":{},"categorical":false,"min":2.0,"max":40.0,"observationCount":20,"patientCount":20}}"#,
                serde_json::to_string(AGE).unwrap()
            ),
            format!(
                r#"{{"name":{},"categorical":true,"categoryValues":["F","M"],"observationCount":20,"patientCount":20}}"#,
                serde_json::to_string(SEX).unwrap()
            ),
        ]
    );
}

/// Patients 1 to 20 genotyped: chr1 100 G>A in gene G1 carried by 1, 9, 10 and 15; chr1 200
/// C>T in gene G2 carried by 2 and 11.
fn genomic_groups() -> Vec<GroupVcfs<'static>> {
    vec![GroupVcfs {
        name: "",
        nodes: vec![NodeVcf {
            chrom: "chr1",
            patients: (1..=20).collect(),
            records: vec![
                rec(
                    "chr1",
                    100,
                    "A",
                    "G1",
                    "missense_variant",
                    "0.1",
                    &[(1, "0|1"), (9, "0|1"), (10, "1|1"), (15, "0|1")],
                ),
                rec(
                    "chr1",
                    200,
                    "T",
                    "G2",
                    "synonymous_variant",
                    "0.2",
                    &[(2, "0|1"), (11, "0|1")],
                ),
            ],
        }],
    }]
}

fn gene(name: &str) -> GenomicFilter {
    GenomicFilter {
        key: Some("Gene_with_variant".into()),
        values: Some(vec![Some(name.into())]),
        ..GenomicFilter::default()
    }
}

#[test]
fn genomic_answers_run_over_the_authorized_set() {
    let store = load_csv(&csv(&rows()));
    let genomic = tempfile::tempdir().unwrap();
    let key = store.out.join("encryption_key");
    let out = genomic.path().join("store");
    load_genomic(
        &genomic.path().join("work"),
        &key,
        GenoMode::LocalDistributed,
        &genomic_groups(),
        &out,
    );
    let genomic_store =
        GenomicStore::open_with_key_file(&out, &key, &GenomicOptions::default()).unwrap();
    let engine = Engine::open(&store.out, &main_options(true))
        .unwrap()
        .with_genomic(genomic_store)
        .with_variant_settings(VariantResultSettings {
            vcf_excerpt_enabled: true,
            ..VariantResultSettings::default()
        });
    let query = |result_type: ResultType, filters: Value, genes: &[&str]| Query {
        genomic_filters: genes.iter().map(|g| gene(g)).collect(),
        ..with_filters(result_type.java_name(), filters, json!({}))
    };
    let topmed = json!([
        {"conceptPath": CONSENTS, "values": ["phs1.c2"]},
        {"conceptPath": TOPMED, "values": ["phs1.c2"]},
    ]);
    for (filters, gene_name, expected) in [
        (
            consents(&["phs1.c1", "phs1.c2", "phs2.c1"]),
            "G1",
            vec![1, 9, 10, 15],
        ),
        (consents(&["phs1.c1"]), "G1", vec![1]),
        (consents(&["phs1.c2"]), "G1", vec![9, 10]),
        (topmed.clone(), "G1", vec![9, 10]),
        (topmed.clone(), "G2", vec![11]),
        (consents(&["phs9.c9"]), "G1", vec![]),
    ] {
        let q = query(ResultType::Patients, filters.clone(), &[gene_name]);
        let patients: Vec<u32> = engine.patients(&q).unwrap().iter().collect();
        assert_eq!(patients, expected, "{filters} {gene_name}");
        let counted = engine
            .count(&query(ResultType::Count, filters.clone(), &[gene_name]))
            .unwrap();
        assert_eq!(counted, expected.len() as u64);
    }
    let variants = |filters: Value| {
        engine
            .variant_count(&query(ResultType::VariantCountForQuery, filters, &["G1"]))
            .unwrap()
    };
    assert_eq!(variants(consents(&["phs1.c1"])), Some(1));
    assert_eq!(variants(consents(&["phs9.c9"])), Some(0));
    let list = |filters: Value, gene_name: &str| {
        engine
            .variant_list(&query(
                ResultType::VariantListForQuery,
                filters,
                &[gene_name],
            ))
            .unwrap()
    };
    assert_eq!(list(consents(&["phs2.c1"]), "G2"), "[]");
    assert_eq!(
        list(topmed.clone(), "G2"),
        "[chr1,200,A,T,G2,synonymous_variant]"
    );
    let excerpt = |result_type: ResultType, filters: Value| match engine
        .vcf_excerpt(&query(result_type, filters, &["G1"]))
        .unwrap()
    {
        hpds_query::ExcerptAnswer::Text(text) => text,
        hpds_query::ExcerptAnswer::Rows(rows) => {
            let mut out = Vec::new();
            rows.write_to(&mut out).unwrap();
            String::from_utf8(out).unwrap()
        }
    };
    assert_eq!(
        excerpt(ResultType::VcfExcerpt, consents(&["phs1.c2"])),
        "CHROM\tPOSITION\tREF\tALT\tVariant_consequence_calculated\tGene_with_variant\tAF\t\
         Patients with this variant in subset\tPatients with this variant NOT in subset\t9\t10\n\
         chr1\t100\tA\tA\tmissense_variant\tG1\t0.1\t2/2\t2/4\t0/1\t1/1\n"
    );
    assert_eq!(
        excerpt(ResultType::AggregateVcfExcerpt, consents(&["phs1.c2"])),
        "CHROM\tPOSITION\tREF\tALT\tVariant_consequence_calculated\tGene_with_variant\tAF\t\
         Patients with this variant in subset\tPatients with this variant NOT in subset\n\
         chr1\t100\tA\tA\tmissense_variant\tG1\t0.1\t2/2\t2/4\n"
    );
    assert_eq!(
        excerpt(ResultType::VcfExcerpt, consents(&["phs9.c9"])),
        hpds_query::NO_VARIANTS_FOUND
    );
    let unfiltered =
        |result_type: ResultType| with_filters(result_type.java_name(), json!([null]), json!({}));
    assert!(matches!(
        engine.variant_list(&unfiltered(ResultType::VariantListForQuery)),
        Err(QueryError::AuthorizationFilterNull(
            NULL_AUTHORIZATION_FILTER_MESSAGE
        ))
    ));
    for result_type in [ResultType::VcfExcerpt, ResultType::AggregateVcfExcerpt] {
        assert!(matches!(
            engine.vcf_excerpt(&unfiltered(result_type)),
            Err(QueryError::AuthorizationFilterNull(
                NULL_AUTHORIZATION_FILTER_MESSAGE
            ))
        ));
    }
    assert_eq!(
        engine
            .variant_count(&unfiltered(ResultType::VariantCountForQuery))
            .unwrap(),
        None
    );
}

#[test]
fn clauses_built_from_filters_are_filters_on_their_paths() {
    let s = main_store(false);
    let by_clause = Query {
        phenotypic_clause: Some(filter_values(CONSENTS, &["phs1.c1"])),
        ..Query::new(ResultType::Count)
    };
    let by_filter = with_filters("COUNT", consents(&["phs1.c1"]), json!({}));
    assert_eq!(count(&s.engine, &by_clause), count(&s.engine, &by_filter));
    let both = Query {
        phenotypic_clause: Some(required(TOPMED)),
        ..with_filters("COUNT", consents(&["phs1.c2"]), json!({}))
    };
    assert_eq!(count(&s.engine, &both), 3);
    let range = Query {
        phenotypic_clause: Some(filter_range(AGE, Some(30.0), None)),
        ..with_filters("COUNT", consents(&["phs2.c1"]), json!({}))
    };
    assert_eq!(count(&s.engine, &range), 4);
}
