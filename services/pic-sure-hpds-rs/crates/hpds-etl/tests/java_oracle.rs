//! Compares the Java reproductions against vectors produced by `fixtures/java/Oracle.java`,
//! run under JDK 25 with the ETL's own jar (Commons CSV 1.5) on the classpath.

mod common;

use hpds_etl::column_meta_csv::write_commons_csv_record;
use hpds_etl::java::{
    java_double_to_string, normalize_concept_path, normalized_text_value, parse_java_int, roll_up,
};

fn base64(input: &str) -> Vec<u8> {
    const ALPHABET: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::new();
    let mut acc = 0u32;
    let mut bits = 0;
    for &c in input.as_bytes() {
        if c == b'=' {
            break;
        }
        let v = ALPHABET.iter().position(|&a| a == c).expect("base64 digit") as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
        }
    }
    out
}

fn base64_str(input: &str) -> String {
    String::from_utf8(base64(input)).unwrap()
}

fn read(name: &str) -> String {
    std::fs::read_to_string(common::fixture(name)).unwrap()
}

#[test]
fn double_to_string_matches_jdk() {
    let mut checked = 0;
    for line in read("java/double_to_string.txt").lines() {
        let (bits, expected) = line.split_once(' ').unwrap();
        let value = f64::from_bits(u64::from_str_radix(bits, 16).unwrap());
        assert_eq!(java_double_to_string(value), expected, "bits {bits}");
        checked += 1;
    }
    assert!(checked > 8000);
}

#[test]
fn concept_paths_match_csv_parser_util() {
    for line in read("java/concept_paths.tsv").lines() {
        let fields: Vec<String> = line.split('\t').map(base64_str).collect();
        let [raw, text, without_rollup, with_rollup] = fields.as_slice() else {
            panic!("bad line {line}");
        };
        let normalized = normalize_concept_path(raw);
        assert_eq!(&normalized, without_rollup, "raw {raw:?}");
        let rolled = roll_up(&normalized, &normalized_text_value(text))
            .unwrap_or_else(|| normalized.clone());
        assert_eq!(&rolled, with_rollup, "raw {raw:?} text {text:?}");
    }
}

#[test]
fn csv_records_match_commons_csv_printer() {
    for line in read("java/csv_printer.tsv").lines() {
        let (value, expected) = line.split_once('\t').unwrap();
        let value = base64_str(value);
        let mut out = Vec::new();
        write_commons_csv_record(&mut out, &[value.as_str(), value.as_str(), "x"]).unwrap();
        assert_eq!(
            String::from_utf8(out).unwrap(),
            base64_str(expected),
            "value {value:?}"
        );
    }
}

#[test]
fn decimal_digits_match_character_digit() {
    let mut java_digits = std::collections::HashMap::new();
    for line in read("java/decimal_digits.txt").lines() {
        let (code, digit) = line.split_once(' ').unwrap();
        java_digits.insert(
            u32::from_str_radix(code, 16).unwrap(),
            digit.parse::<i32>().unwrap(),
        );
    }
    for code in 0..0x1_0000u32 {
        let Some(c) = char::from_u32(code) else {
            continue;
        };
        let parsed = parse_java_int(&c.to_string());
        assert_eq!(parsed, java_digits.get(&code).copied(), "U+{code:04X}");
    }
}
