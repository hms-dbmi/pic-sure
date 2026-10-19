//! `Float.parseFloat` and `Float.compare` as JDK 25 implements them.

use std::cmp::Ordering;

use super::{JavaLiteral, decimal_literal, hex_literal, java_literal};

/// Parses `input` as Java `Float.parseFloat` does in JDK 25: the grammar of
/// [`super::parse_java_double`] (surrounding whitespace, a sign, `NaN`, `Infinity`, decimal
/// and hexadecimal literals, an `f`, `F`, `d` or `D` suffix), rounded once, straight to the
/// nearest `f32` with ties to even. Java 19 and later round directly; parsing to `f64` and
/// narrowing would round twice and differ near the midpoint of two floats.
///
/// Returns `None` where Java throws `NumberFormatException`.
pub fn parse_java_float(input: &str) -> Option<f32> {
    let (negative, literal) = java_literal(input)?;
    let magnitude = match literal {
        JavaLiteral::NaN => return Some(f32::NAN),
        JavaLiteral::Infinity => f32::INFINITY,
        JavaLiteral::Hex(rest) => match hex_literal(rest)? {
            None => 0.0,
            Some((significand, exponent, sticky)) => {
                round_binary_f32(significand, exponent, sticky)
            }
        },
        JavaLiteral::Decimal(rest) => decimal_literal(rest)?.parse::<f32>().ok()?,
    };
    Some(if negative { -magnitude } else { magnitude })
}

/// Compares two floats the way Java `Float.compare` does: `-0.0` sorts before `0.0`, and
/// every NaN equals every other NaN and sorts above positive infinity.
pub fn java_float_cmp(a: f32, b: f32) -> Ordering {
    let canonical = |v: f32| if v.is_nan() { f32::NAN } else { v };
    canonical(a).total_cmp(&canonical(b))
}

/// Rounds `significand * 2^exponent`, plus a nonzero tail below the significand's last bit
/// when `sticky` is set, to the nearest float with ties to even.
fn round_binary_f32(significand: u64, exponent: i64, sticky: bool) -> f32 {
    let bits = 64 - i64::from(significand.leading_zeros());
    let top = exponent + bits - 1;
    if top > 127 {
        return f32::INFINITY;
    }
    let normal = top >= -126;
    let shift = if normal { bits - 24 } else { -149 - exponent };
    let rounded = if shift <= 0 {
        significand << (-shift)
    } else if shift > 64 {
        0
    } else {
        let kept = if shift == 64 { 0 } else { significand >> shift };
        let dropped = if shift == 64 {
            significand
        } else {
            significand & ((1u64 << shift) - 1)
        };
        let half = 1u64 << (shift - 1);
        let round_up = dropped > half || (dropped == half && (sticky || kept & 1 == 1));
        kept + u64::from(round_up)
    };
    if !normal {
        return f32::from_bits(rounded as u32);
    }
    let (mantissa, scale) = if rounded == 1u64 << 24 {
        (rounded >> 1, exponent + shift + 1)
    } else {
        (rounded, exponent + shift)
    };
    let biased = scale + 23 + 127;
    if biased >= 255 {
        return f32::INFINITY;
    }
    f32::from_bits(((biased as u32) << 23) | (mantissa as u32 & ((1u32 << 23) - 1)))
}
