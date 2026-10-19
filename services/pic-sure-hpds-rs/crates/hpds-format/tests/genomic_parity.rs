//! The JDK 25 ports of `hpds_format::parity` against values a Java probe recorded:
//! `tests/fixtures/genomic_parity/GenomicParityProbe.java`.

use std::cmp::Ordering;
use std::collections::BTreeSet;

use hpds_format::{
    JavaHashCapacity, java_compare_ignore_case, java_float_cmp, java_hash_layout, java_parse_int,
    parse_java_float,
};

const COMPARE_IGNORE_CASE: &str = include_str!("fixtures/genomic_parity/compare_ignore_case.txt");
const HASH_ORDER: &str = include_str!("fixtures/genomic_parity/hash_order.txt");
const PARSE_FLOAT: &str = include_str!("fixtures/genomic_parity/parse_float.txt");
const PARSE_INT: &str = include_str!("fixtures/genomic_parity/parse_int.txt");

/// Decodes a probe string: `-` is empty, `u:` starts UTF-16 units in hex joined by dots, and
/// anything else is the string itself.
fn decode(field: &str) -> String {
    if field == "-" {
        return String::new();
    }
    let Some(units) = field.strip_prefix("u:") else {
        return field.to_owned();
    };
    let units: Vec<u16> = units
        .split('.')
        .map(|u| u16::from_str_radix(u, 16).expect("hex unit"))
        .collect();
    String::from_utf16(&units).expect("the probe writes valid UTF-16")
}

#[test]
fn compare_ignore_case_matches_java() {
    let mut cases = 0;
    for line in COMPARE_IGNORE_CASE.lines() {
        let fields: Vec<&str> = line.split(' ').collect();
        let (a, b) = (decode(fields[0]), decode(fields[1]));
        let expected = match fields[2] {
            "-1" => Ordering::Less,
            "0" => Ordering::Equal,
            "1" => Ordering::Greater,
            other => panic!("unexpected sign {other}"),
        };
        assert_eq!(
            java_compare_ignore_case(&a, &b),
            expected,
            "{a:?} against {b:?}"
        );
        cases += 1;
    }
    assert!(cases > 10_000);
}

#[test]
fn compare_ignore_case_named_characters() {
    assert_eq!(java_compare_ignore_case("k", "\u{212a}"), Ordering::Equal);
    assert_eq!(
        java_compare_ignore_case("\u{3c2}", "\u{3a3}"),
        Ordering::Equal
    );
    assert_eq!(
        java_compare_ignore_case("\u{3c3}", "\u{3c2}"),
        Ordering::Equal
    );
    assert_eq!(
        java_compare_ignore_case("\u{10400}", "\u{10428}"),
        Ordering::Equal
    );
    assert_eq!(java_compare_ignore_case("i", "\u{131}"), Ordering::Equal);
    assert_eq!(java_compare_ignore_case("i", "\u{130}"), Ordering::Equal);
    assert_eq!(java_compare_ignore_case("\u{df}", "SS"), Ordering::Greater);
    assert_eq!(java_compare_ignore_case("ABC", "abd"), Ordering::Less);
    assert_eq!(java_compare_ignore_case("ab", "AB\u{0}"), Ordering::Less);
}

#[test]
fn parse_int_matches_java() {
    for line in PARSE_INT.lines() {
        let (input, expected) = line.split_once(' ').expect("two fields");
        let input = decode(input);
        let expected = (expected != "err").then(|| expected.parse::<i32>().expect("an int"));
        assert_eq!(java_parse_int(&input), expected, "{input:?}");
    }
}

#[test]
fn parse_float_matches_java() {
    let mut cases = 0;
    for line in PARSE_FLOAT.lines() {
        let (input, expected) = line.split_once(' ').expect("two fields");
        let input = decode(input);
        let actual = parse_java_float(&input).map(f32::to_bits);
        let expected =
            (expected != "err").then(|| u32::from_str_radix(expected, 16).expect("hex bits"));
        assert_eq!(actual, expected, "{input:?}");
        cases += 1;
    }
    assert!(cases > 5_000);
}

#[test]
fn float_cmp_is_java_float_compare() {
    assert_eq!(java_float_cmp(-0.0, 0.0), Ordering::Less);
    assert_eq!(java_float_cmp(f32::NAN, f32::INFINITY), Ordering::Greater);
    assert_eq!(
        java_float_cmp(f32::NAN, f32::from_bits(0xffc0_0001)),
        Ordering::Equal
    );
    assert_eq!(java_float_cmp(1.0, 2.0), Ordering::Less);
}

#[test]
fn hash_order_matches_java() {
    let mut lines = HASH_ORDER.lines();
    let mut cases = 0;
    let mut tree_cases = 0;
    while let Some(header) = lines.next() {
        let header: Vec<&str> = header.split(' ').collect();
        assert_eq!(header[0], "case");
        let n: usize = header[2].parse().expect("item count");
        let capacity: usize = header[4].parse().expect("capacity");
        let java_trees: BTreeSet<u32> = if header[5] == "-" {
            BTreeSet::new()
        } else {
            header[5]
                .split(',')
                .map(|t| t.parse().expect("bucket"))
                .collect()
        };
        let parse = |line: &str| -> Vec<String> {
            if line.is_empty() {
                Vec::new()
            } else {
                line.split(' ').map(decode).collect()
            }
        };
        let items = parse(lines.next().expect("items"));
        let java_order = parse(lines.next().expect("order"));
        assert_eq!(items.len(), n);
        let mode = match header[1] {
            "default" => JavaHashCapacity::Default,
            "collection" => JavaHashCapacity::FromCollection(n),
            other => panic!("unexpected mode {other}"),
        };
        let layout = java_hash_layout(items.iter().map(String::as_str), mode);
        assert_eq!(layout.capacity, capacity, "capacity of case {cases}");
        let trees: BTreeSet<u32> = layout.tree_buckets.iter().copied().collect();
        assert!(java_trees.is_subset(&trees), "tree buckets of case {cases}");
        tree_cases += usize::from(!java_trees.is_empty());

        let ours: Vec<(u32, &str)> = layout.entries.clone();
        assert_eq!(ours.len(), java_order.len(), "size of case {cases}");
        let mut start = 0;
        while start < ours.len() {
            let bucket = ours[start].0;
            let end = start + ours[start..].iter().take_while(|e| e.0 == bucket).count();
            let rust_run: Vec<&str> = ours[start..end].iter().map(|e| e.1).collect();
            let java_run: Vec<&str> = java_order[start..end].iter().map(String::as_str).collect();
            if trees.contains(&bucket) {
                let mut a = rust_run.clone();
                let mut b = java_run.clone();
                a.sort_unstable();
                b.sort_unstable();
                assert_eq!(a, b, "tree bucket {bucket} of case {cases}");
            } else {
                assert_eq!(rust_run, java_run, "bucket {bucket} of case {cases}");
            }
            start = end;
        }
        cases += 1;
    }
    assert!(cases > 150);
    assert!(tree_cases > 0, "the probe holds cases with tree buckets");
}
