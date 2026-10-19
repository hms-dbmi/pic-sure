//! The store's order of variant spec strings: Java's split, the `VariantSpec` key, and the
//! raw-byte tie break.

use std::cmp::Ordering;

use hpds_format::{VariantSpecKey, compare_specs, is_short_spec, java_split_spec};
use proptest::prelude::*;

#[test]
fn split_drops_trailing_empty_fields_as_java_does() {
    assert_eq!(java_split_spec("").0, 1);
    assert_eq!(java_split_spec("chr1").0, 1);
    assert_eq!(java_split_spec(",,,").0, 0);
    assert_eq!(java_split_spec(",a").0, 2);
    assert_eq!(java_split_spec("a,b,,,").0, 2);
    assert_eq!(java_split_spec("a,,b").0, 3);
    let (count, fields) = java_split_spec("chr1,100,A,T,GENE,missense,extra");
    assert_eq!(count, 7);
    assert_eq!(fields, ["chr1", "100", "A", "T", "GENE", "missense"]);
    assert!(is_short_spec("chr1,100,A,T,GENE,"));
    assert!(is_short_spec("chr1,100,A,T"));
    assert!(!is_short_spec("chr1,100,A,T,GENE,missense"));
    assert!(!is_short_spec("chr1,100,A,T,,missense"));
}

#[test]
fn key_parses_the_position_as_a_java_int() {
    let key = VariantSpecKey::parse("chr1,0100,A,T,g,c").unwrap();
    assert_eq!(key.position(), 100);
    assert_eq!(key.bucket(), 0);
    assert_eq!(
        VariantSpecKey::parse("chr1,-1500,A,T,g,c")
            .unwrap()
            .bucket(),
        -1
    );
    assert_eq!(
        VariantSpecKey::parse("chr1,-999,A,T,g,c").unwrap().bucket(),
        0
    );
    assert_eq!(
        VariantSpecKey::parse("chr1,+2000,A,T,g,c")
            .unwrap()
            .bucket(),
        2
    );
    assert_eq!(
        VariantSpecKey::parse("chr1,\u{661}\u{662},A,T,g,c")
            .unwrap()
            .position(),
        12
    );
    assert!(VariantSpecKey::parse("chr1,2147483648,A,T,g,c").is_none());
    assert!(VariantSpecKey::parse("chr1, 1,A,T,g,c").is_none());
    assert!(VariantSpecKey::parse("chr1").is_none());
    assert!(VariantSpecKey::parse("chr1,").is_none());
    assert!(compare_specs("chr1,x,A,T,g,c", "chr1,1,A,T,g,c").is_none());
}

#[test]
fn specs_order_as_variant_spec_compare_to_then_raw_bytes() {
    let ordered = [
        "chr1,9,A,T,g,c",
        "chr1,10,A,T,g,c",
        "chr1,0100,A,T,g,c",
        "chr1,100,A,T,g,c",
        "chr1,100,A,T,g",
        "chr1,100,A,T,g,c",
        "chr1,100,A,T,g,c,extra",
        "chr1,100,A,T,h,a",
        "chr1,100,C,A,a,a",
        "chr10,1,A,T,g,c",
        "chr2,1,A,T,g,c",
    ];
    let mut sorted: Vec<&str> = ordered.to_vec();
    sorted.dedup();
    sorted.sort_by(|a, b| compare_specs(a, b).unwrap());
    let expected = vec![
        "chr1,9,A,T,g,c",
        "chr1,10,A,T,g,c",
        "chr1,100,A,T,g",
        "chr1,0100,A,T,g,c",
        "chr1,100,A,T,g,c",
        "chr1,100,A,T,g,c",
        "chr1,100,A,T,g,c,extra",
        "chr1,100,A,T,h,a",
        "chr1,100,C,A,a,a",
        "chr10,1,A,T,g,c",
        "chr2,1,A,T,g,c",
    ];
    assert_eq!(sorted, expected);
    let a = VariantSpecKey::parse("chr1,0100,A,T,g,c").unwrap();
    let b = VariantSpecKey::parse("chr1,100,A,T,g,c,extra").unwrap();
    assert_eq!(a.cmp(&b), Ordering::Equal);
    assert_eq!(
        compare_specs("chr1,0100,A,T,g,c", "chr1,100,A,T,g,c"),
        Some(Ordering::Less)
    );
    assert_eq!(
        compare_specs("chr1,1,A,T,\u{ffff},c", "chr1,1,A,T,\u{10000},c"),
        Some(Ordering::Greater)
    );
}

fn spec_strategy() -> impl Strategy<Value = String> {
    let field = prop::sample::select(vec![
        "",
        "A",
        "AT",
        "T",
        "g",
        "G",
        "0",
        "\u{e000}",
        "\u{10000}",
    ]);
    (
        prop::sample::select(vec!["chr1", "chr10", "chr2", "X"]),
        prop::sample::select(vec!["0", "1", "01", "999", "1000", "-5", "+7"]),
        prop::collection::vec(field, 0..6),
    )
        .prop_map(|(contig, position, rest)| {
            let mut s = format!("{contig},{position}");
            for f in rest {
                s.push(',');
                s.push_str(f);
            }
            s
        })
}

proptest! {
    #[test]
    fn the_order_is_strict_and_total(
        specs in prop::collection::vec(spec_strategy(), 1..40),
    ) {
        for a in &specs {
            for b in &specs {
                let ab = compare_specs(a, b).unwrap();
                prop_assert_eq!(ab, compare_specs(b, a).unwrap().reverse());
                prop_assert_eq!(ab == Ordering::Equal, a == b);
            }
        }
        let mut sorted = specs.clone();
        sorted.sort_by(|a, b| compare_specs(a, b).unwrap());
        for w in sorted.windows(2) {
            prop_assert!(compare_specs(&w[0], &w[1]).unwrap() != Ordering::Greater);
        }
        for i in 0..sorted.len() {
            for j in i + 1..sorted.len() {
                prop_assert!(compare_specs(&sorted[i], &sorted[j]).unwrap() != Ordering::Greater);
            }
        }
    }
}
