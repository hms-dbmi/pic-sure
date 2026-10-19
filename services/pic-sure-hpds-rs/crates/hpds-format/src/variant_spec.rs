//! The order of variant spec strings in the genomic store.
//!
//! A spec string is `contig,position,ref,alt,gene,consequence`. Java's
//! `new VariantSpec(String)` splits it with `String.split(",")` (trailing empty strings
//! dropped), reads field 1 with `Integer.parseInt`, and `VariantSpec.compareTo` orders specs
//! by contig, position as a number, ref, alt, gene and consequence, strings by
//! `String.compareTo`. Fields past the sixth are ignored. A spec with fewer than six fields
//! after the split has no `VariantSpec` (Java throws), so it is a short spec: its key
//! compares the fields it has, a missing field before any present one.
//!
//! Equal keys do not make equal specs (`chr1,0100,A,T,g,c` and `chr1,100,A,T,g,c`, or a
//! seven-field spec beside its six-field prefix), so the store's order breaks ties on the
//! raw bytes, which makes it strict: [`compare_specs`].

use std::cmp::Ordering;

use crate::parity::{java_parse_int, java_string_cmp};

/// Fields Java's `VariantSpec(String)` reads from a spec string.
pub const VARIANT_SPEC_FIELDS: usize = 6;

/// Width in base pairs of a bucket: a spec's bucket is its position divided by this, as a
/// Java `int` division (truncating toward zero).
pub const BUCKET_WIDTH: i32 = 1000;

/// The first [`VARIANT_SPEC_FIELDS`] fields of `s` as Java's `s.split(",")` returns them,
/// and how many fields that array holds: the fields up to the last non-empty one, or 1 for
/// a string without a comma (Java returns the string itself, even when it is empty). Fields
/// past the count are empty strings.
pub fn java_split_spec(s: &str) -> (usize, [&str; VARIANT_SPEC_FIELDS]) {
    let mut fields = [""; VARIANT_SPEC_FIELDS];
    if !s.contains(',') {
        fields[0] = s;
        return (1, fields);
    }
    let mut count = 0;
    for (i, field) in s.split(',').enumerate() {
        if let Some(slot) = fields.get_mut(i) {
            *slot = field;
        }
        if !field.is_empty() {
            count = i + 1;
        }
    }
    (count, fields)
}

/// True when Java cannot build a `VariantSpec` from `s` because its split holds fewer than
/// six fields.
pub fn is_short_spec(s: &str) -> bool {
    java_split_spec(s).0 < VARIANT_SPEC_FIELDS
}

/// The sort key of a spec string: Java's `VariantSpec` fields, or for a short spec the
/// fields it has.
#[derive(Clone, Copy, Debug)]
pub struct VariantSpecKey<'a> {
    contig: &'a str,
    position: i32,
    rest: [Option<&'a str>; VARIANT_SPEC_FIELDS - 2],
    fields: usize,
}

impl<'a> VariantSpecKey<'a> {
    /// The key of `spec`, or `None` when its split holds fewer than two fields or field 1 is
    /// not a Java `int` (`Integer.parseInt` would throw), so no bucket can hold it.
    pub fn parse(spec: &'a str) -> Option<Self> {
        let (fields, split) = java_split_spec(spec);
        if fields < 2 {
            return None;
        }
        let position = java_parse_int(split[1])?;
        let mut rest = [None; VARIANT_SPEC_FIELDS - 2];
        for (i, slot) in rest.iter_mut().enumerate() {
            if i + 2 < fields {
                *slot = Some(split[i + 2]);
            }
        }
        Some(VariantSpecKey {
            contig: split[0],
            position,
            rest,
            fields,
        })
    }

    /// Field 0, the contig.
    pub fn contig(&self) -> &'a str {
        self.contig
    }

    /// Field 1 as Java's `Integer.parseInt` reads it.
    pub fn position(&self) -> i32 {
        self.position
    }

    /// The spec's bucket: the position divided by [`BUCKET_WIDTH`], truncating toward zero.
    pub fn bucket(&self) -> i32 {
        self.position / BUCKET_WIDTH
    }

    /// True when the spec has fewer than six fields, so Java has no `VariantSpec` for it.
    pub fn is_short(&self) -> bool {
        self.fields < VARIANT_SPEC_FIELDS
    }
}

/// Equal when the keys compare equal: a seven-field spec equals its six-field prefix, as
/// Java's `VariantSpec.compareTo` finds them.
impl PartialEq for VariantSpecKey<'_> {
    fn eq(&self, other: &Self) -> bool {
        self.cmp(other) == Ordering::Equal
    }
}

impl Eq for VariantSpecKey<'_> {}

impl Ord for VariantSpecKey<'_> {
    fn cmp(&self, other: &Self) -> Ordering {
        java_string_cmp(self.contig, other.contig)
            .then(self.position.cmp(&other.position))
            .then_with(|| {
                self.rest
                    .iter()
                    .zip(&other.rest)
                    .map(|(a, b)| match (a, b) {
                        (Some(a), Some(b)) => java_string_cmp(a, b),
                        (a, b) => a.is_some().cmp(&b.is_some()),
                    })
                    .find(|o| o.is_ne())
                    .unwrap_or(Ordering::Equal)
            })
    }
}

impl PartialOrd for VariantSpecKey<'_> {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// The store's order of two spec strings: by [`VariantSpecKey`], then by raw bytes. `None`
/// when either has no key ([`VariantSpecKey::parse`]).
pub fn compare_specs(a: &str, b: &str) -> Option<Ordering> {
    let (ka, kb) = (VariantSpecKey::parse(a)?, VariantSpecKey::parse(b)?);
    Some(ka.cmp(&kb).then_with(|| a.as_bytes().cmp(b.as_bytes())))
}
