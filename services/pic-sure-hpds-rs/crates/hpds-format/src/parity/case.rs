//! `String.CASE_INSENSITIVE_ORDER` and `Integer.parseInt` as JDK 25 implements them, over
//! the `Character` tables of [`super::java_tables`].

use std::cmp::Ordering;

use super::java_tables::{DECIMAL_DIGITS, TO_LOWER, TO_UPPER};

fn lookup(table: &[(u32, u32)], cp: u32) -> u32 {
    table
        .binary_search_by_key(&cp, |&(from, _)| from)
        .map_or(cp, |i| table[i].1)
}

/// `Character.toUpperCase(int)` of JDK 25: the simple (one to one) uppercase mapping.
pub fn java_to_upper_case(cp: u32) -> u32 {
    match cp {
        0x61..=0x7a => cp - 0x20,
        0..=0x7f => cp,
        _ => lookup(TO_UPPER, cp),
    }
}

/// `Character.toLowerCase(int)` of JDK 25: the simple (one to one) lowercase mapping.
pub fn java_to_lower_case(cp: u32) -> u32 {
    match cp {
        0x41..=0x5a => cp + 0x20,
        0..=0x7f => cp,
        _ => lookup(TO_LOWER, cp),
    }
}

fn is_high_surrogate(unit: u16) -> bool {
    (0xd800..0xdc00).contains(&unit)
}

fn is_low_surrogate(unit: u16) -> bool {
    (0xdc00..0xe000).contains(&unit)
}

fn to_code_point(high: u16, low: u16) -> u32 {
    0x10000 + ((u32::from(high) - 0xd800) << 10) + (u32::from(low) - 0xdc00)
}

/// `StringUTF16.compareCodePointCI`: zero when the two code points are equal after
/// uppercasing, or after lowercasing the uppercased values; otherwise their difference.
fn compare_code_point_ci(a: u32, b: u32) -> i64 {
    let (a, b) = (java_to_upper_case(a), java_to_upper_case(b));
    if a != b {
        let (a, b) = (java_to_lower_case(a), java_to_lower_case(b));
        if a != b {
            return i64::from(a) - i64::from(b);
        }
    }
    0
}

/// `StringUTF16.codePointIncluding`: the code point that the unit at `index` belongs to,
/// and whether it consumed the unit after it.
fn code_point_including(units: &[u16], index: usize) -> (u32, bool) {
    let unit = units[index];
    if is_low_surrogate(unit) {
        if index > 0 && is_high_surrogate(units[index - 1]) {
            return (to_code_point(units[index - 1], unit), false);
        }
    } else if is_high_surrogate(unit)
        && let Some(&next) = units.get(index + 1)
        && is_low_surrogate(next)
    {
        return (to_code_point(unit, next), true);
    }
    (u32::from(unit), false)
}

/// `StringUTF16.compareToCI`, for two strings of which at least one holds a unit above
/// 0xFF.
fn compare_utf16_ci(a: &[u16], b: &[u16]) -> Ordering {
    let (mut i, mut j) = (0, 0);
    while i < a.len() && j < b.len() {
        let (ua, ub) = (u32::from(a[i]), u32::from(b[j]));
        if ua != ub && compare_code_point_ci(ua, ub) != 0 {
            let (ca, skip_a) = code_point_including(a, i);
            let (cb, skip_b) = code_point_including(b, j);
            i += usize::from(skip_a);
            j += usize::from(skip_b);
            let diff = compare_code_point_ci(ca, cb);
            if diff != 0 {
                return diff.cmp(&0);
            }
        }
        i += 1;
        j += 1;
    }
    a.len().cmp(&b.len())
}

/// `StringLatin1.compareToCI` and `StringLatin1.compareToCI_UTF16`: a unit-by-unit
/// comparison of uppercased, then lowercased, `char` values.
fn compare_units_ci(a: &[u16], b: &[u16]) -> Ordering {
    for (&x, &y) in a.iter().zip(b) {
        if x != y {
            let upper = |unit: u16| java_to_upper_case(u32::from(unit)) & 0xffff;
            let (x, y) = (upper(x), upper(y));
            if x != y {
                let lower = |unit: u32| java_to_lower_case(unit) & 0xffff;
                let (x, y) = (lower(x), lower(y));
                if x != y {
                    return x.cmp(&y);
                }
            }
        }
    }
    a.len().cmp(&b.len())
}

/// Compares two strings as Java `String.compareToIgnoreCase` (`String.CASE_INSENSITIVE_ORDER`)
/// does in JDK 25.
///
/// Java stores a string whose UTF-16 units are all at most 0xFF as Latin-1 and compares two
/// such strings, or one with a UTF-16 string, unit by unit; two UTF-16 strings are compared
/// unit by unit until the units differ under case folding, where each side then forms its
/// code point (a high surrogate with the following low surrogate, a low surrogate with the
/// preceding high surrogate) and the code points decide. Both paths fold with
/// `Character.toLowerCase(Character.toUpperCase(c))` using JDK 25's simple case mappings,
/// which Rust's `char::to_uppercase` (full mappings, another Unicode version) does not
/// match. When one string runs out, the lengths in UTF-16 units decide.
pub fn java_compare_ignore_case(a: &str, b: &str) -> Ordering {
    let a: Vec<u16> = a.encode_utf16().collect();
    let b: Vec<u16> = b.encode_utf16().collect();
    let latin1 = |units: &[u16]| units.iter().all(|&u| u <= 0xff);
    if latin1(&a) || latin1(&b) {
        compare_units_ci(&a, &b)
    } else {
        compare_utf16_ci(&a, &b)
    }
}

fn java_decimal_digit(unit: u16) -> Option<u32> {
    match unit {
        0x30..=0x39 => Some(u32::from(unit - 0x30)),
        0..=0x7f => None,
        _ => DECIMAL_DIGITS
            .binary_search_by_key(&unit, |&(u, _)| u)
            .ok()
            .map(|i| u32::from(DECIMAL_DIGITS[i].1)),
    }
}

/// Parses `s` as Java `Integer.parseInt(s)` does in JDK 25: an optional `+` or `-`, then one
/// or more decimal digits as `Character.digit(char, 10)` reads them (which includes
/// non-ASCII decimal digits such as U+0661), with no surrounding whitespace, in the `int`
/// range.
///
/// Returns `None` where Java throws `NumberFormatException`.
pub fn java_parse_int(s: &str) -> Option<i32> {
    let units: Vec<u16> = s.encode_utf16().collect();
    let (&first, rest) = units.split_first()?;
    let negative = first == u16::from(b'-');
    let digits = if negative || first == u16::from(b'+') {
        rest
    } else {
        &units[..]
    };
    if digits.is_empty() {
        return None;
    }
    let mut value: i64 = 0;
    for &unit in digits {
        value = value * 10 + i64::from(java_decimal_digit(unit)?);
        if value > i64::from(i32::MAX) + 1 {
            return None;
        }
    }
    let value = if negative { -value } else { value };
    i32::try_from(value).ok()
}
