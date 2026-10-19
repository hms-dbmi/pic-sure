//! Sets of positions in a group's patient list, the form of every genomic mask and carrier
//! set.
//!
//! A position `p` means the group's patient `patients[p]`; `P` is the group's patient count.
//! One encoded set:
//!
//! ```text
//! tag   u8   0 sparse, 1 dense
//! sparse: count varint, then count varints: the first position, then each gap to the next (>= 1)
//! dense:  ceil(P / 8) bytes, position p is bit (p % 8) of byte (p / 8); bits at P and above zero
//! ```
//!
//! Varints are LEB128: 7 bits per byte, low bits first, at most 5 bytes for a `u32`. The
//! writer emits whichever form is shorter, sparse on a tie. A reader accepts either and
//! refuses a varint longer than 5 bytes or overflowing a `u32`, positions not strictly
//! ascending or not below `P`, a dense set of the wrong length or with a padding bit set, and
//! trailing bytes. A set may be empty.
//!
//! [`PosSet::parse`] validates an encoded set once; its operations then read it without
//! checks and without allocating, into a [`PosBitset`] of `P` bits.

use crate::error::{FormatError, Result};

const TAG_SPARSE: u8 = 0;
const TAG_DENSE: u8 = 1;
const WHAT: &str = "patient position set";

fn varint_len(value: u32) -> usize {
    match value {
        0..0x80 => 1,
        0x80..0x4000 => 2,
        0x4000..0x20_0000 => 3,
        0x20_0000..0x1000_0000 => 4,
        _ => 5,
    }
}

fn put_varint(out: &mut Vec<u8>, mut value: u32) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

/// Reads one varint at `*pos`, advancing it.
///
/// # Errors
/// [`FormatError::Truncated`] when the bytes end inside the varint, and
/// [`FormatError::Corrupt`] when it runs past 5 bytes or its value overflows a `u32`.
fn get_varint(bytes: &[u8], pos: &mut usize) -> Result<u32> {
    let mut value: u32 = 0;
    for shift in [0u32, 7, 14, 21, 28] {
        let byte = *bytes.get(*pos).ok_or(FormatError::Truncated(WHAT))?;
        *pos += 1;
        if shift == 28 && byte > 0x0f {
            return Err(FormatError::corrupt(
                WHAT,
                "a varint is longer than 5 bytes or overflows u32",
            ));
        }
        value |= u32::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(FormatError::corrupt(
        WHAT,
        "a varint is longer than 5 bytes or overflows u32",
    ))
}

/// Bytes the dense form of a set over `patient_count` patients takes after its tag.
fn dense_len(patient_count: u32) -> usize {
    (patient_count as usize).div_ceil(8)
}

/// Appends the shorter encoding of `positions` (sparse on a tie) to `out`.
///
/// # Errors
/// [`FormatError::InvalidInput`] when `positions` is not strictly ascending or holds a
/// position not below `patient_count`.
pub fn encode_pos_set(positions: &[u32], patient_count: u32, out: &mut Vec<u8>) -> Result<()> {
    let count = u32::try_from(positions.len())
        .map_err(|_| FormatError::InvalidInput("too many positions".into()))?;
    let mut sparse_len = 1 + varint_len(count);
    let mut previous: Option<u32> = None;
    for &p in positions {
        if p >= patient_count || previous.is_some_and(|q| p <= q) {
            return Err(FormatError::InvalidInput(format!(
                "patient positions must be strictly ascending and below {patient_count}, found {p} after {previous:?}"
            )));
        }
        sparse_len += varint_len(p - previous.unwrap_or(0));
        previous = Some(p);
    }
    let dense = 1 + dense_len(patient_count);
    if sparse_len <= dense {
        out.push(TAG_SPARSE);
        put_varint(out, count);
        let mut previous = 0;
        for (i, &p) in positions.iter().enumerate() {
            put_varint(out, if i == 0 { p } else { p - previous });
            previous = p;
        }
    } else {
        out.push(TAG_DENSE);
        let start = out.len();
        out.resize(start + dense_len(patient_count), 0);
        for &p in positions {
            out[start + (p / 8) as usize] |= 1 << (p % 8);
        }
    }
    Ok(())
}

/// A validated encoded position set borrowed from a payload.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct PosSet<'a> {
    body: &'a [u8],
    dense: bool,
    len: u32,
    patient_count: u32,
}

impl<'a> PosSet<'a> {
    /// Validates `bytes` as exactly one encoded set over `patient_count` patients.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] when the bytes end inside the set, and
    /// [`FormatError::Corrupt`] for an unknown tag, a bad varint, positions out of order or
    /// range, a dense set of the wrong length or with a padding bit set, or trailing bytes.
    pub fn parse(bytes: &'a [u8], patient_count: u32) -> Result<Self> {
        let (&tag, body) = bytes.split_first().ok_or(FormatError::Truncated(WHAT))?;
        match tag {
            TAG_SPARSE => {
                let mut pos = 0;
                let count = get_varint(body, &mut pos)?;
                if count as usize > body.len() - pos {
                    return Err(FormatError::Truncated(WHAT));
                }
                let mut previous: Option<u32> = None;
                for _ in 0..count {
                    let value = get_varint(body, &mut pos)?;
                    let position = match previous {
                        None => value,
                        Some(_) if value == 0 => {
                            return Err(FormatError::corrupt(WHAT, "a gap of zero"));
                        }
                        Some(q) => q.checked_add(value).ok_or_else(|| {
                            FormatError::corrupt(WHAT, "a position overflows u32")
                        })?,
                    };
                    if position >= patient_count {
                        return Err(FormatError::corrupt(
                            WHAT,
                            format!("position {position} is not below {patient_count}"),
                        ));
                    }
                    previous = Some(position);
                }
                if pos != body.len() {
                    return Err(FormatError::corrupt(
                        WHAT,
                        format!("{} trailing bytes", body.len() - pos),
                    ));
                }
                Ok(PosSet {
                    body,
                    dense: false,
                    len: count,
                    patient_count,
                })
            }
            TAG_DENSE => {
                let expected = dense_len(patient_count);
                if body.len() != expected {
                    return Err(if body.len() < expected {
                        FormatError::Truncated(WHAT)
                    } else {
                        FormatError::corrupt(
                            WHAT,
                            format!("{} bytes in a dense set of {expected}", body.len()),
                        )
                    });
                }
                if let Some(&last) = body.last()
                    && !patient_count.is_multiple_of(8)
                    && last >> (patient_count % 8) != 0
                {
                    return Err(FormatError::corrupt(WHAT, "a padding bit is set"));
                }
                let len = body.iter().map(|b| b.count_ones()).sum();
                Ok(PosSet {
                    body,
                    dense: true,
                    len,
                    patient_count,
                })
            }
            other => Err(FormatError::corrupt(WHAT, format!("unknown tag {other}"))),
        }
    }

    /// A set that [`PosSet::parse`] accepted before, read again without its checks.
    pub(crate) fn from_validated(bytes: &'a [u8], patient_count: u32) -> Self {
        let (dense, body) = match bytes.split_first() {
            Some((&TAG_DENSE, body)) => (true, body),
            Some((_, body)) => (false, body),
            None => (false, &[][..]),
        };
        let len = if dense {
            body.iter().map(|b| b.count_ones()).sum()
        } else {
            get_varint(body, &mut 0).unwrap_or(0)
        };
        PosSet {
            body,
            dense,
            len,
            patient_count,
        }
    }

    /// Number of positions in the set.
    pub fn len(&self) -> usize {
        self.len as usize
    }

    /// True when the set holds no position.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// True when the set is in the dense form.
    pub fn is_dense(&self) -> bool {
        self.dense
    }

    /// The patient count the set ranges over.
    pub fn patient_count(&self) -> u32 {
        self.patient_count
    }

    /// The positions, ascending.
    pub fn iter(&self) -> PosSetIter<'a> {
        PosSetIter {
            body: self.body,
            dense: self.dense,
            pos: 0,
            remaining: self.len,
            current: 0,
            word: 0,
            started: false,
        }
    }

    /// Replaces the contents of `bitset` with this set.
    ///
    /// # Panics
    /// When `bitset` does not range over the set's patient count.
    pub fn decode_into(&self, bitset: &mut PosBitset) {
        bitset.check_len(self.patient_count);
        bitset.clear();
        self.union_into(bitset);
    }

    /// Adds this set's positions to `bitset`.
    ///
    /// # Panics
    /// When `bitset` does not range over the set's patient count.
    pub fn union_into(&self, bitset: &mut PosBitset) {
        bitset.check_len(self.patient_count);
        if self.dense {
            for (word, chunk) in bitset.words.iter_mut().zip(self.body.chunks(8)) {
                let mut bytes = [0u8; 8];
                bytes[..chunk.len()].copy_from_slice(chunk);
                *word |= u64::from_le_bytes(bytes);
            }
        } else {
            for p in self.iter() {
                bitset.insert(p);
            }
        }
    }

    /// True when this set and `bitset` share a position.
    ///
    /// # Panics
    /// When `bitset` does not range over the set's patient count.
    pub fn intersects(&self, bitset: &PosBitset) -> bool {
        bitset.check_len(self.patient_count);
        if self.dense {
            bitset
                .words
                .iter()
                .zip(self.body.chunks(8))
                .any(|(word, chunk)| {
                    let mut bytes = [0u8; 8];
                    bytes[..chunk.len()].copy_from_slice(chunk);
                    word & u64::from_le_bytes(bytes) != 0
                })
        } else {
            self.iter().any(|p| bitset.contains(p))
        }
    }
}

/// Iterator over the positions of a [`PosSet`], ascending.
#[derive(Clone, Debug)]
pub struct PosSetIter<'a> {
    body: &'a [u8],
    dense: bool,
    pos: usize,
    remaining: u32,
    current: u32,
    word: u64,
    started: bool,
}

impl Iterator for PosSetIter<'_> {
    type Item = u32;

    fn next(&mut self) -> Option<u32> {
        if self.remaining == 0 {
            return None;
        }
        if self.dense {
            while self.word == 0 {
                let chunk = self.body.get(self.pos..)?;
                let chunk = &chunk[..chunk.len().min(8)];
                if chunk.is_empty() {
                    return None;
                }
                let mut bytes = [0u8; 8];
                bytes[..chunk.len()].copy_from_slice(chunk);
                self.word = u64::from_le_bytes(bytes);
                self.current = (self.pos * 8) as u32;
                self.pos += 8;
            }
            let bit = self.word.trailing_zeros();
            self.word &= self.word - 1;
            self.remaining -= 1;
            Some(self.current + bit)
        } else {
            if !self.started {
                get_varint(self.body, &mut self.pos).ok()?;
                self.started = true;
                let first = get_varint(self.body, &mut self.pos).ok()?;
                self.current = first;
            } else {
                let gap = get_varint(self.body, &mut self.pos).ok()?;
                self.current = self.current.wrapping_add(gap);
            }
            self.remaining -= 1;
            Some(self.current)
        }
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        (self.remaining as usize, Some(self.remaining as usize))
    }
}

/// A set of positions below a fixed patient count, as 64-bit words: the per-node working set
/// of genomic evaluation.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PosBitset {
    words: Vec<u64>,
    len: u32,
}

impl PosBitset {
    /// An empty set over `patient_count` positions.
    pub fn new(patient_count: u32) -> Self {
        PosBitset {
            words: vec![0; (patient_count as usize).div_ceil(64)],
            len: patient_count,
        }
    }

    /// The set of every position below `patient_count`.
    pub fn full(patient_count: u32) -> Self {
        let mut set = Self::new(patient_count);
        for p in 0..patient_count {
            set.insert(p);
        }
        set
    }

    fn check_len(&self, patient_count: u32) {
        assert_eq!(
            self.len, patient_count,
            "a position set over {patient_count} patients used with a bitset over {}",
            self.len
        );
    }

    /// The patient count the set ranges over.
    pub fn patient_count(&self) -> u32 {
        self.len
    }

    /// Removes every position.
    pub fn clear(&mut self) {
        self.words.fill(0);
    }

    /// Adds `position`; positions at or above the patient count are ignored.
    pub fn insert(&mut self, position: u32) {
        if position < self.len {
            self.words[(position / 64) as usize] |= 1 << (position % 64);
        }
    }

    /// True when the set holds `position`.
    pub fn contains(&self, position: u32) -> bool {
        position < self.len && self.words[(position / 64) as usize] & (1 << (position % 64)) != 0
    }

    /// Number of positions in the set.
    pub fn count(&self) -> usize {
        self.words.iter().map(|w| w.count_ones() as usize).sum()
    }

    /// True when the set holds no position.
    pub fn is_empty(&self) -> bool {
        self.words.iter().all(|&w| w == 0)
    }

    /// Adds every position of `other`.
    ///
    /// # Panics
    /// When the two sets range over different patient counts.
    pub fn union_with(&mut self, other: &PosBitset) {
        other.check_len(self.len);
        for (a, b) in self.words.iter_mut().zip(&other.words) {
            *a |= b;
        }
    }

    /// Keeps only the positions also in `other`.
    ///
    /// # Panics
    /// When the two sets range over different patient counts.
    pub fn intersect_with(&mut self, other: &PosBitset) {
        other.check_len(self.len);
        for (a, b) in self.words.iter_mut().zip(&other.words) {
            *a &= b;
        }
    }

    /// The positions, ascending.
    pub fn iter(&self) -> impl Iterator<Item = u32> + '_ {
        self.words.iter().enumerate().flat_map(|(i, &word)| {
            let base = (i * 64) as u32;
            let mut word = word;
            std::iter::from_fn(move || {
                if word == 0 {
                    return None;
                }
                let bit = word.trailing_zeros();
                word &= word - 1;
                Some(base + bit)
            })
        })
    }

    /// The words, 64 positions each, position `p` at bit `p % 64` of word `p / 64`.
    pub fn words(&self) -> &[u64] {
        &self.words
    }

    /// Heap bytes the set holds.
    pub fn heap_size(&self) -> usize {
        self.words.capacity() * size_of::<u64>()
    }
}
