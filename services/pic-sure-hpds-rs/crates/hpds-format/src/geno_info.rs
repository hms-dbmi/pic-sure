//! Info blocks (kind 4), one per node and INFO column, and the port of Java's
//! `CompressedIndex` for numeric columns.
//!
//! ```text
//! 0   kind           u8    4
//! 1   continuous     u8    = the index entry's continuous
//! 2   reserved       u16
//! 4   k              u32   keys, = the index entry's key_count
//! 8   range_count    u32   0 when categorical
//! 12  entry_count    u32   0 when categorical
//! 16  key_offsets    u32[k+1]
//!     key_bytes      u8[]  keys strictly ascending in Java String.compareTo order
//!     variant_offsets u32[k+1]
//!     variant_bytes  k portable roaring bitmaps of node variant ids, the key's variants
//!   categorical only:
//!     carrier_offsets u32[k+1]
//!     carrier_bytes  k position sets: the patients holding he or ho on any of the key's
//!                    variants
//!   continuous only, the CompressedIndex:
//!     ranges         range_count x { lower_kind u8, upper_kind u8, reserved u16,
//!                    lower u32, upper u32 }   kinds 0 unbounded, 1 open, 2 closed; the
//!                    endpoints are f32 bit patterns (zero when unbounded); strictly
//!                    ascending by (lower cut, upper cut)
//!     entry_offsets  u32[range_count+1]   into the entry arrays
//!     entry_floats   u32[entry_count]     f32 bits, each range's entries strictly
//!                                         ascending in Float.compare order
//!     entry_key_offsets u32[entry_count+1] into entry_keys
//!     entry_keys     u32[]                key indexes, strictly ascending within an entry
//! ```
//!
//! Every array starts on an 8-byte boundary and is followed by zero padding to the next.
//!
//! # The `CompressedIndex` port
//!
//! Java keeps, per numeric column and node, a `HashMap<Range<Float>, TreeMap<Float,
//! TreeSet<String>>>`. [`CompressedIndexView::keys_in_range`] ports `getValuesInRange` with
//! Guava's cut semantics: a lower bound `CLOSED v` is the cut below `v` and `OPEN v` the cut
//! above it; an upper bound `CLOSED v` is the cut above `v` and `OPEN v` the cut below it;
//! unbounded ends are the cuts below and above everything. Cuts order by value in
//! `Float.compare` order and, at equal values, the cut below before the cut above. Every
//! stored range enclosed by the target `[min, max]` contributes every key of every entry; a
//! range connected to it but not enclosed contributes the keys of the entries whose float
//! the target contains; others contribute nothing.

use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};
use std::ops::Range;

use roaring::RoaringBitmap;

use crate::aligned::AlignedBuf;
use crate::error::{FormatError, Result};
use crate::geno_blocks::GenoBlockKind;
use crate::geno_layout::{
    PayloadReader, PayloadWriter, bytes_at, check_offsets, check_text, data_len, text_at, to_u32,
    view,
};
use crate::parity::{java_float_cmp, java_string_cmp, parse_java_float};
use crate::pos_set::{PosSet, encode_pos_set};

const WHAT: &str = "info block";
const BOUND_UNBOUNDED: u8 = 0;
const BOUND_OPEN: u8 = 1;
const BOUND_CLOSED: u8 = 2;
const RANGE_WORDS: usize = 3;
const JAVA_PARTITION_SIZE: usize = 1000;

/// One end of a stored range.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum RangeBound {
    /// No bound on this side.
    Unbounded,
    /// The endpoint is excluded.
    Open(f32),
    /// The endpoint is included.
    Closed(f32),
}

impl RangeBound {
    fn encode(self) -> (u8, u32) {
        match self {
            RangeBound::Unbounded => (BOUND_UNBOUNDED, 0),
            RangeBound::Open(v) => (BOUND_OPEN, v.to_bits()),
            RangeBound::Closed(v) => (BOUND_CLOSED, v.to_bits()),
        }
    }

    fn decode(kind: u8, bits: u32) -> Option<Self> {
        match kind {
            BOUND_UNBOUNDED if bits == 0 => Some(RangeBound::Unbounded),
            BOUND_OPEN => Some(RangeBound::Open(f32::from_bits(bits))),
            BOUND_CLOSED => Some(RangeBound::Closed(f32::from_bits(bits))),
            _ => None,
        }
    }

    fn lower_cut(self) -> Cut {
        match self {
            RangeBound::Unbounded => Cut::BelowAll,
            RangeBound::Closed(v) => Cut::Below(v),
            RangeBound::Open(v) => Cut::Above(v),
        }
    }

    fn upper_cut(self) -> Cut {
        match self {
            RangeBound::Unbounded => Cut::AboveAll,
            RangeBound::Closed(v) => Cut::Above(v),
            RangeBound::Open(v) => Cut::Below(v),
        }
    }
}

/// A Guava `Cut` over floats.
#[derive(Clone, Copy, Debug)]
enum Cut {
    BelowAll,
    Below(f32),
    Above(f32),
    AboveAll,
}

impl Cut {
    fn cmp(self, other: Cut) -> Ordering {
        let rank = |c: Cut| match c {
            Cut::BelowAll => 0,
            Cut::Below(_) | Cut::Above(_) => 1,
            Cut::AboveAll => 2,
        };
        match (self, other) {
            (Cut::Below(a) | Cut::Above(a), Cut::Below(b) | Cut::Above(b)) => java_float_cmp(a, b)
                .then_with(|| matches!(self, Cut::Above(_)).cmp(&matches!(other, Cut::Above(_)))),
            _ => rank(self).cmp(&rank(other)),
        }
    }
}

fn range_cmp(a: (RangeBound, RangeBound), b: (RangeBound, RangeBound)) -> Ordering {
    a.0.lower_cut()
        .cmp(b.0.lower_cut())
        .then_with(|| a.1.upper_cut().cmp(b.1.upper_cut()))
}

/// One range of a `CompressedIndex` with its map, as an encoder takes it: entries ascending
/// by float in `Float.compare` order, each with the indexes of its keys, ascending.
#[derive(Clone, Debug, PartialEq)]
pub struct CompressedRange {
    /// The lower bound.
    pub lower: RangeBound,
    /// The upper bound.
    pub upper: RangeBound,
    /// The range's map: floats and the indexes, into the block's keys, of the keys that
    /// parse to them.
    pub entries: Vec<(f32, Vec<u32>)>,
}

/// `String.split(",")`: the fields, with trailing empty strings removed, or the string
/// itself when it holds no comma.
fn java_split_comma(s: &str) -> Vec<&str> {
    if !s.contains(',') {
        return vec![s];
    }
    let mut fields: Vec<&str> = s.split(',').collect();
    while fields.last().is_some_and(|f| f.is_empty()) {
        fields.pop();
    }
    fields
}

/// Java's sort key of a float: `Float.compare` order as an integer order.
fn float_order_key(value: f32) -> i32 {
    let value = if value.is_nan() { f32::NAN } else { value };
    let bits = value.to_bits() as i32;
    bits ^ ((((bits >> 31) as u32) >> 1) as i32)
}

/// Builds a numeric column's `CompressedIndex` from its keys, given strictly ascending in
/// Java `String.compareTo` order, as Java's `buildContinuousValuesMap` and `buildIndex` do:
/// each key parses with `Float.parseFloat(key.trim())` or, when that fails, each part of its
/// `split(",")` that parses (only for two or more parts); the distinct floats, in
/// `Float.compare` order, form partitions of 1,000; the ranges are
/// `openClosed(p0.first, p0.last)`, `openClosed(p.first, next.first)` for every partition
/// but the last, and `openClosed(last.first, last.last)`, each holding
/// `subMap(p.first, p.last)` of its partition and deduplicated as the `HashMap` does. The
/// ranges come back in the stored order.
///
/// Returns `None` when no float parses, where Java's `buildIndex` throws.
pub fn build_java_compressed_index(keys: &[&str]) -> Option<Vec<CompressedRange>> {
    let mut map: BTreeMap<i32, (f32, BTreeSet<u32>)> = BTreeMap::new();
    let mut add = |value: f32, key: u32| {
        map.entry(float_order_key(value))
            .or_insert_with(|| (value, BTreeSet::new()))
            .1
            .insert(key);
    };
    for (index, key) in keys.iter().enumerate() {
        if let Some(value) = parse_java_float(key) {
            add(value, index as u32);
        } else {
            let parts = java_split_comma(key);
            if parts.len() > 1 {
                for part in parts {
                    if let Some(value) = parse_java_float(part) {
                        add(value, index as u32);
                    }
                }
            }
        }
    }
    let values: Vec<(i32, f32)> = map.iter().map(|(&k, (v, _))| (k, *v)).collect();
    let partitions: Vec<&[(i32, f32)]> = values.chunks(JAVA_PARTITION_SIZE).collect();
    let (first, last) = (partitions.first()?, partitions.last()?);
    let sub_map = |p: &[(i32, f32)]| -> Vec<(f32, Vec<u32>)> {
        let (lo, hi) = (p[0].0, p[p.len() - 1].0);
        map.range(lo..hi)
            .map(|(_, (v, set))| (*v, set.iter().copied().collect()))
            .collect()
    };
    let mut ranges: Vec<CompressedRange> = Vec::new();
    let mut put = |lower: f32, upper: f32, entries: Vec<(f32, Vec<u32>)>| {
        let range = CompressedRange {
            lower: RangeBound::Open(lower),
            upper: RangeBound::Closed(upper),
            entries,
        };
        let same = |r: &CompressedRange| {
            r.lower.encode() == range.lower.encode() && r.upper.encode() == range.upper.encode()
        };
        match ranges.iter_mut().find(|r| same(r)) {
            Some(existing) => *existing = range,
            None => ranges.push(range),
        }
    };
    put(first[0].1, first[first.len() - 1].1, sub_map(first));
    for pair in partitions.windows(2) {
        put(pair[0][0].1, pair[1][0].1, sub_map(pair[0]));
    }
    put(last[0].1, last[last.len() - 1].1, sub_map(last));
    ranges.sort_by(|a, b| range_cmp((a.lower, a.upper), (b.lower, b.upper)));
    Some(ranges)
}

/// One key of an info block handed to [`encode_info_block`].
#[derive(Clone, Copy, Debug)]
pub struct InfoKeyInput<'a> {
    /// The key, as the info store holds it.
    pub key: &'a str,
    /// The node variant ids the key holds.
    pub variants: &'a RoaringBitmap,
    /// For a categorical column, the group positions of the patients holding `he` or `ho` on
    /// any of the key's variants, strictly ascending; `None` for a continuous column.
    pub carriers: Option<&'a [u32]>,
}

/// What the index says an info block holds, checked when it is decoded.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ExpectedInfoBlock {
    /// The column is continuous on this node.
    pub continuous: bool,
    /// Keys of the block.
    pub key_count: u32,
    /// The node's variant count, above every variant id.
    pub variant_count: u32,
    /// The group's patient count, the range of every carrier set.
    pub patient_count: u32,
}

/// Encodes an info block of `keys`, given strictly ascending in Java `String.compareTo`
/// order. A continuous column passes its `CompressedIndex` ranges in `compressed_index` and
/// no carriers; a categorical column passes `None` and carriers for every key.
///
/// # Errors
/// [`FormatError::InvalidInput`] when the input breaks an invariant of the block (see
/// [`InfoBlock::decode`]) or a length exceeds its field.
pub fn encode_info_block(
    keys: &[InfoKeyInput<'_>],
    compressed_index: Option<&[CompressedRange]>,
    variant_count: u32,
    patient_count: u32,
) -> Result<Vec<u8>> {
    let continuous = compressed_index.is_some();
    let k = to_u32(keys.len(), "key count")?;
    let mut key_offsets = vec![0u32];
    let mut key_bytes = Vec::new();
    let mut variant_offsets = vec![0u32];
    let mut variant_bytes = Vec::new();
    let mut carrier_offsets = vec![0u32];
    let mut carrier_bytes = Vec::new();
    for key in keys {
        key_bytes.extend_from_slice(key.key.as_bytes());
        key_offsets.push(to_u32(key_bytes.len(), "key bytes")?);
        key.variants.serialize_into(&mut variant_bytes)?;
        variant_offsets.push(to_u32(variant_bytes.len(), "variant bytes")?);
        match (continuous, key.carriers) {
            (false, Some(carriers)) => {
                encode_pos_set(carriers, patient_count, &mut carrier_bytes)?;
                carrier_offsets.push(to_u32(carrier_bytes.len(), "carrier bytes")?);
            }
            (true, None) => {}
            _ => {
                return Err(FormatError::InvalidInput(format!(
                    "info key {:?}: carriers are given for every categorical key and no continuous one",
                    key.key
                )));
            }
        }
    }
    let ranges = compressed_index.unwrap_or_default();
    let mut range_words = Vec::with_capacity(ranges.len() * RANGE_WORDS);
    let mut entry_offsets = vec![0u32];
    let mut entry_floats = Vec::new();
    let mut entry_key_offsets = vec![0u32];
    let mut entry_keys = Vec::new();
    for range in ranges {
        let (lower_kind, lower) = range.lower.encode();
        let (upper_kind, upper) = range.upper.encode();
        range_words.extend([
            u32::from(lower_kind) | (u32::from(upper_kind) << 8),
            lower,
            upper,
        ]);
        for (value, key_indexes) in &range.entries {
            entry_floats.push(value.to_bits());
            entry_keys.extend_from_slice(key_indexes);
            entry_key_offsets.push(to_u32(entry_keys.len(), "entry keys")?);
        }
        entry_offsets.push(to_u32(entry_floats.len(), "entry count")?);
    }

    let mut w = PayloadWriter::default();
    w.u8(GenoBlockKind::Info as u8);
    w.u8(u8::from(continuous));
    w.zeros(2);
    w.u32(k);
    w.u32(to_u32(ranges.len(), "range count")?);
    w.u32(to_u32(entry_floats.len(), "entry count")?);
    w.array(&key_offsets);
    w.bytes(&key_bytes);
    w.array(&variant_offsets);
    w.bytes(&variant_bytes);
    if continuous {
        w.array(&range_words);
        w.array(&entry_offsets);
        w.array(&entry_floats);
        w.array(&entry_key_offsets);
        w.array(&entry_keys);
    } else {
        w.array(&carrier_offsets);
        w.bytes(&carrier_bytes);
    }
    let expected = ExpectedInfoBlock {
        continuous,
        key_count: k,
        variant_count,
        patient_count,
    };
    InfoBlock::decode(AlignedBuf::copy_from(&w.buf), &expected)
        .map_err(|e| FormatError::InvalidInput(e.to_string()))?;
    Ok(w.buf)
}

/// A decoded and fully checked info block.
#[derive(Clone, Debug)]
pub struct InfoBlock {
    buf: AlignedBuf,
    continuous: bool,
    patient_count: u32,
    key_offsets: Range<usize>,
    key_bytes: Range<usize>,
    variant_offsets: Range<usize>,
    variant_bytes: Range<usize>,
    carrier_offsets: Range<usize>,
    carrier_bytes: Range<usize>,
    ranges: Range<usize>,
    entry_offsets: Range<usize>,
    entry_floats: Range<usize>,
    entry_key_offsets: Range<usize>,
    entry_keys: Range<usize>,
}

impl InfoBlock {
    /// Decodes `buf` and checks it fully against `expected`: keys strictly ascending, every
    /// variant bitmap read with the validating roaring deserializer and below the node's
    /// variant count, every carrier set, and for a continuous block at least one range,
    /// every range a valid Guava range, ranges strictly ascending, each range's floats
    /// strictly ascending, and key indexes in range and strictly ascending within an entry.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode(buf: AlignedBuf, expected: &ExpectedInfoBlock) -> Result<Self> {
        let mut r = PayloadReader::new(buf.as_bytes(), WHAT);
        let kind = r.u8()?;
        if kind != GenoBlockKind::Info as u8 {
            return Err(FormatError::corrupt(WHAT, format!("kind byte {kind}")));
        }
        let continuous = match r.u8()? {
            0 => false,
            1 => true,
            other => {
                return Err(FormatError::corrupt(
                    WHAT,
                    format!("continuous byte {other}"),
                ));
            }
        };
        r.reserved(2)?;
        let k = r.u32()?;
        let range_count = r.u32()?;
        let entry_count = r.u32()?;
        if continuous != expected.continuous || k != expected.key_count {
            return Err(FormatError::corrupt(
                WHAT,
                format!(
                    "continuous {continuous} with {k} keys, the index says {} with {}",
                    expected.continuous, expected.key_count
                ),
            ));
        }
        if continuous && (range_count == 0 || k == 0) {
            return Err(FormatError::corrupt(
                WHAT,
                "a continuous block holds at least one key and one range",
            ));
        }
        if !continuous && (range_count != 0 || entry_count != 0) {
            return Err(FormatError::corrupt(
                WHAT,
                "a categorical block holds ranges",
            ));
        }
        let key_offsets = r.offsets::<u32>(k as usize)?;
        let key_bytes = r.array::<u8>(data_len(&buf, &key_offsets))?;
        let variant_offsets = r.offsets::<u32>(k as usize)?;
        let variant_bytes = r.array::<u8>(data_len(&buf, &variant_offsets))?;
        let empty = r.pos..r.pos;
        let (carrier_offsets, carrier_bytes, ranges, entry_offsets, entry_floats) = if continuous {
            let ranges = r.array::<u32>(
                (range_count as usize)
                    .checked_mul(RANGE_WORDS)
                    .ok_or(FormatError::Truncated(WHAT))?,
            )?;
            let entry_offsets = r.offsets::<u32>(range_count as usize)?;
            let entry_floats = r.array::<u32>(entry_count as usize)?;
            (
                empty.clone(),
                empty.clone(),
                ranges,
                entry_offsets,
                entry_floats,
            )
        } else {
            let carrier_offsets = r.offsets::<u32>(k as usize)?;
            let carrier_bytes = r.array::<u8>(data_len(&buf, &carrier_offsets))?;
            (
                carrier_offsets,
                carrier_bytes,
                empty.clone(),
                empty.clone(),
                empty.clone(),
            )
        };
        let (entry_key_offsets, entry_keys) = if continuous {
            let offsets = r.offsets::<u32>(entry_count as usize)?;
            let keys = r.array::<u32>(data_len(&buf, &offsets))?;
            (offsets, keys)
        } else {
            (empty.clone(), empty)
        };
        r.finish()?;
        let block = InfoBlock {
            buf,
            continuous,
            patient_count: expected.patient_count,
            key_offsets,
            key_bytes,
            variant_offsets,
            variant_bytes,
            carrier_offsets,
            carrier_bytes,
            ranges,
            entry_offsets,
            entry_floats,
            entry_key_offsets,
            entry_keys,
        };
        block.check(expected, entry_count)?;
        Ok(block)
    }

    fn check(&self, expected: &ExpectedInfoBlock, entry_count: u32) -> Result<()> {
        let corrupt = |detail: String| FormatError::corrupt(WHAT, detail);
        let k = expected.key_count as usize;
        let key_offsets = view::<u32>(&self.buf, &self.key_offsets);
        let key_bytes = view::<u8>(&self.buf, &self.key_bytes);
        check_offsets(key_offsets, key_bytes.len(), "info block keys")?;
        check_text(key_bytes, key_offsets, "info block keys")?;
        for i in 1..k {
            let (a, b) = (
                text_at(key_bytes, key_offsets, i - 1),
                text_at(key_bytes, key_offsets, i),
            );
            if java_string_cmp(a, b) != Ordering::Less {
                return Err(corrupt(format!("key {b:?} is not ordered after {a:?}")));
            }
        }
        let variant_offsets = view::<u32>(&self.buf, &self.variant_offsets);
        let variant_bytes = view::<u8>(&self.buf, &self.variant_bytes);
        check_offsets(variant_offsets, variant_bytes.len(), "info block variants")?;
        for i in 0..k {
            let bitmap = read_bitmap(bytes_at(variant_bytes, variant_offsets, i))?;
            if bitmap
                .max()
                .is_some_and(|max| max >= expected.variant_count)
            {
                return Err(corrupt(format!(
                    "key {i} names a variant at or above the node's {}",
                    expected.variant_count
                )));
            }
        }
        if !self.continuous {
            let offsets = view::<u32>(&self.buf, &self.carrier_offsets);
            let bytes = view::<u8>(&self.buf, &self.carrier_bytes);
            check_offsets(offsets, bytes.len(), "info block carriers")?;
            for i in 0..k {
                PosSet::parse(bytes_at(bytes, offsets, i), expected.patient_count)?;
            }
            return Ok(());
        }

        let words = view::<u32>(&self.buf, &self.ranges);
        let mut previous: Option<(RangeBound, RangeBound)> = None;
        for (index, range) in words.as_chunks::<RANGE_WORDS>().0.iter().enumerate() {
            let [kinds, lower, upper] = [range[0], range[1], range[2]];
            if kinds >> 16 != 0 {
                return Err(corrupt(format!("range {index} has reserved bits set")));
            }
            let bounds = RangeBound::decode(kinds as u8, lower)
                .zip(RangeBound::decode((kinds >> 8) as u8, upper))
                .ok_or_else(|| corrupt(format!("range {index} has invalid bounds")))?;
            if bounds.0.lower_cut().cmp(bounds.1.upper_cut()) == Ordering::Greater {
                return Err(corrupt(format!(
                    "range {index} has its lower bound above its upper bound"
                )));
            }
            if previous.is_some_and(|p| range_cmp(p, bounds) != Ordering::Less) {
                return Err(corrupt(format!("range {index} is out of order")));
            }
            previous = Some(bounds);
        }
        let entry_offsets = view::<u32>(&self.buf, &self.entry_offsets);
        check_offsets(entry_offsets, entry_count as usize, "info block entries")?;
        let floats = view::<u32>(&self.buf, &self.entry_floats);
        for w in entry_offsets.windows(2) {
            let range = floats.get(w[0] as usize..w[1] as usize).unwrap_or_default();
            if range.windows(2).any(|p| {
                java_float_cmp(f32::from_bits(p[0]), f32::from_bits(p[1])) != Ordering::Less
            }) {
                return Err(corrupt(
                    "a range's floats are not strictly ascending".into(),
                ));
            }
        }
        let key_offsets = view::<u32>(&self.buf, &self.entry_key_offsets);
        let keys = view::<u32>(&self.buf, &self.entry_keys);
        check_offsets(key_offsets, keys.len(), "info block entry keys")?;
        for w in key_offsets.windows(2) {
            let entry = keys.get(w[0] as usize..w[1] as usize).unwrap_or_default();
            if entry.iter().any(|&key| key as usize >= k) || entry.windows(2).any(|p| p[0] >= p[1])
            {
                return Err(corrupt(
                    "an entry's key indexes are out of range or not strictly ascending".into(),
                ));
            }
        }
        Ok(())
    }

    /// True when the column is continuous on this node: the block holds a
    /// `CompressedIndex` and no carriers.
    pub fn is_continuous(&self) -> bool {
        self.continuous
    }

    /// Number of keys.
    pub fn len(&self) -> usize {
        view::<u32>(&self.buf, &self.key_offsets)
            .len()
            .saturating_sub(1)
    }

    /// True when the block holds no key.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Key `i`, empty when out of range.
    pub fn key(&self, i: usize) -> &str {
        text_at(
            view::<u8>(&self.buf, &self.key_bytes),
            view::<u32>(&self.buf, &self.key_offsets),
            i,
        )
    }

    /// The keys, ascending in Java `String.compareTo` order.
    pub fn keys(&self) -> impl ExactSizeIterator<Item = &str> + '_ {
        (0..self.len()).map(|i| self.key(i))
    }

    /// The index of the key equal to `key`.
    pub fn find_key(&self, key: &str) -> Option<usize> {
        let (mut lo, mut hi) = (0, self.len());
        while lo < hi {
            let mid = (lo + hi) / 2;
            match java_string_cmp(self.key(mid), key) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// The serialized roaring bitmap of key `i`'s variants, as checked at decode.
    pub fn serialized_variants(&self, i: usize) -> &[u8] {
        bytes_at(
            view::<u8>(&self.buf, &self.variant_bytes),
            view::<u32>(&self.buf, &self.variant_offsets),
            i,
        )
    }

    /// The node variant ids key `i` holds, read with the validating deserializer.
    ///
    /// # Errors
    /// [`FormatError::Corrupt`] when the bitmap does not deserialize, which a decoded block
    /// rules out.
    pub fn variants(&self, i: usize) -> Result<RoaringBitmap> {
        read_bitmap(self.serialized_variants(i))
    }

    /// The carriers of categorical key `i`; `None` for a continuous block or out of range.
    pub fn carriers(&self, i: usize) -> Option<PosSet<'_>> {
        if self.continuous || i >= self.len() {
            return None;
        }
        let bytes = bytes_at(
            view::<u8>(&self.buf, &self.carrier_bytes),
            view::<u32>(&self.buf, &self.carrier_offsets),
            i,
        );
        Some(PosSet::from_validated(bytes, self.patient_count))
    }

    /// The `CompressedIndex` of a continuous block.
    pub fn compressed_index(&self) -> Option<CompressedIndexView<'_>> {
        self.continuous
            .then_some(CompressedIndexView { block: self })
    }

    /// The decoded payload's bytes.
    pub fn payload(&self) -> &[u8] {
        self.buf.as_bytes()
    }

    /// Heap bytes the block holds.
    pub fn heap_size(&self) -> usize {
        self.buf.heap_size()
    }
}

/// Deserializes one portable roaring bitmap that must fill `bytes` exactly.
fn read_bitmap(bytes: &[u8]) -> Result<RoaringBitmap> {
    let mut reader = bytes;
    let bitmap = RoaringBitmap::deserialize_from(&mut reader)
        .map_err(|e| FormatError::corrupt("info block variant bitmap", e.to_string()))?;
    if !reader.is_empty() {
        return Err(FormatError::corrupt(
            "info block variant bitmap",
            format!("{} trailing bytes", reader.len()),
        ));
    }
    Ok(bitmap)
}

/// The `CompressedIndex` of a continuous info block.
#[derive(Clone, Copy, Debug)]
pub struct CompressedIndexView<'a> {
    block: &'a InfoBlock,
}

impl<'a> CompressedIndexView<'a> {
    /// Number of ranges.
    pub fn len(&self) -> usize {
        view::<u32>(&self.block.buf, &self.block.ranges).len() / RANGE_WORDS
    }

    /// True when the index holds no range, which a decoded block never does.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The bounds of range `r`.
    pub fn bounds(&self, r: usize) -> Option<(RangeBound, RangeBound)> {
        let words = view::<u32>(&self.block.buf, &self.block.ranges);
        let range = words.get(r * RANGE_WORDS..(r + 1) * RANGE_WORDS)?;
        Some((
            RangeBound::decode(range[0] as u8, range[1])?,
            RangeBound::decode((range[0] >> 8) as u8, range[2])?,
        ))
    }

    /// The map of range `r`: each float with its key indexes, ascending.
    pub fn entries(&self, r: usize) -> impl Iterator<Item = (f32, &'a [u32])> + 'a {
        let block = self.block;
        let offsets = view::<u32>(&block.buf, &block.entry_offsets);
        let span = match (offsets.get(r), offsets.get(r + 1)) {
            (Some(&a), Some(&b)) => a as usize..b as usize,
            _ => 0..0,
        };
        let floats = view::<u32>(&block.buf, &block.entry_floats);
        let key_offsets = view::<u32>(&block.buf, &block.entry_key_offsets);
        let keys = view::<u32>(&block.buf, &block.entry_keys);
        span.map(move |e| {
            let value = f32::from_bits(floats.get(e).copied().unwrap_or(0));
            let entry = match (key_offsets.get(e), key_offsets.get(e + 1)) {
                (Some(&a), Some(&b)) => keys.get(a as usize..b as usize).unwrap_or_default(),
                _ => &[][..],
            };
            (value, entry)
        })
    }

    /// The distinct key indexes Java's `getValuesInRange(Range.closed(min, max))` returns,
    /// ascending.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when `min` is above `max` in `Float.compare` order,
    /// where Guava's `Range.closed` throws.
    pub fn keys_in_range(&self, min: f32, max: f32) -> Result<Vec<u32>> {
        if java_float_cmp(min, max) == Ordering::Greater {
            return Err(FormatError::InvalidInput(format!(
                "the range [{min}, {max}] has its minimum above its maximum"
            )));
        }
        let target = (Cut::Below(min), Cut::Above(max));
        let mut keys = BTreeSet::new();
        for r in 0..self.len() {
            let Some((lower, upper)) = self.bounds(r) else {
                continue;
            };
            let (lower, upper) = (lower.lower_cut(), upper.upper_cut());
            let connected = lower.cmp(target.1) != Ordering::Greater
                && target.0.cmp(upper) != Ordering::Greater;
            if !connected {
                continue;
            }
            let enclosed = target.0.cmp(lower) != Ordering::Greater
                && upper.cmp(target.1) != Ordering::Greater;
            for (value, entry) in self.entries(r) {
                if enclosed
                    || (java_float_cmp(min, value) != Ordering::Greater
                        && java_float_cmp(value, max) != Ordering::Greater)
                {
                    keys.extend(entry.iter().copied());
                }
            }
        }
        Ok(keys.into_iter().collect())
    }
}
