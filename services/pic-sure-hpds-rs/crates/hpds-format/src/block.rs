//! Per-concept block payloads and the codec that seals them for `pheno.blocks`.

use std::cmp::Ordering;
use std::collections::HashMap;
use std::ops::Range;

use bytemuck::Pod;
use roaring::RoaringBitmap;
use xxhash_rust::xxh3::Xxh3Default;

use crate::aligned::AlignedBuf;
use crate::crypto::{open_framed, seal_framed};
use crate::error::{FormatError, Result};
use crate::membership::Membership;
use crate::parity::{canonical_nan, java_double_cmp, java_string_cmp};
use crate::protection::{Protection, StoreId};
use crate::{FORMAT_VERSION, LEGACY_FORMAT_VERSION, NO_TIMESTAMP};

const KIND_NUMERIC: u8 = 0;
const KIND_CATEGORICAL: u8 = 1;
const NUMERIC_HEADER_LEN: usize = 8;
const CATEGORICAL_HEADER_LEN: usize = 16;
const PREFIX_HEADER_LEN: usize = 16;
const HOLDER_LEN: usize = 12;
const FLAG_TAGGED: u8 = 1;

/// One numeric observation handed to [`encode_numeric_payload`].
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct NumericObservation {
    /// Patient number.
    pub patient: u32,
    /// Observed value. NaN is stored as the canonical positive NaN.
    pub value: f64,
    /// Epoch milliseconds, or [`NO_TIMESTAMP`].
    pub timestamp: i64,
}

/// One categorical observation handed to [`encode_categorical_payload`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CategoricalObservation {
    /// Patient number.
    pub patient: u32,
    /// Observed category value.
    pub value: String,
    /// Epoch milliseconds, or [`NO_TIMESTAMP`].
    pub timestamp: i64,
}

/// One categorical observation handed to [`encode_interned_categorical_payload`], naming
/// its value by position in a separate list of distinct values.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub struct CategoricalRow {
    /// Patient number.
    pub patient: u32,
    /// Index of the observed value in the `values` list passed alongside the rows.
    pub value_id: u32,
    /// Epoch milliseconds, or [`NO_TIMESTAMP`].
    pub timestamp: i64,
}

fn observation_count(n: usize) -> Result<u32> {
    u32::try_from(n).map_err(|_| {
        FormatError::InvalidInput(format!("{n} observations exceed the u32 limit of a block"))
    })
}

fn pad8(buf: &mut Vec<u8>) {
    buf.resize(buf.len().next_multiple_of(8), 0);
}

fn put_array<T: Pod>(buf: &mut Vec<u8>, items: &[T]) {
    buf.extend_from_slice(bytemuck::cast_slice(items));
    pad8(buf);
}

fn padded(len: u64) -> u64 {
    len.saturating_add(7) & !7
}

/// Maps a double to an `i64` whose natural order is Java `Double.compareTo` order once NaN
/// is canonical: the order of `f64::total_cmp`.
fn java_order_key(value: f64) -> i64 {
    let bits = value.to_bits() as i64;
    bits ^ ((((bits >> 63) as u64) >> 1) as i64)
}

/// Input positions sorted by patient, keeping input order among one patient's rows: the
/// stable sort on patient alone that Java `sortedByKey` performs.
fn positions_by_patient(n: u32, patient_at: impl Fn(usize) -> u32) -> Vec<u32> {
    let mut keys: Vec<u64> = (0..n)
        .map(|pos| (u64::from(patient_at(pos as usize)) << 32) | u64::from(pos))
        .collect();
    keys.sort_unstable();
    keys.into_iter().map(|k| k as u32).collect()
}

/// A consent partition that holds a concept, with its share of the concept's block: the
/// partition's index in the store's partition table, its rows, and the distinct patients of
/// those rows.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct PartitionHolder {
    /// The partition's index in the store's partition table.
    pub partition: u16,
    /// Rows of the block that belong to the partition.
    pub observations: u32,
    /// Distinct patients of those rows.
    pub patients: u32,
}

/// How the rows handed to a block encoder split over the store's consent partitions.
///
/// An unpartitioned store's blocks use [`BlockPartitioning::default`]: no holders and no
/// tags. A partitioned store's blocks list every partition holding the concept in
/// `holders`, ascending by partition, with observation counts summing to the rows. When
/// some row belongs to a patient whose partition the patient alone does not decide (the
/// overlap set), `row_partitions` gives every row's partition, parallel to the input rows,
/// and the block is tagged.
#[derive(Clone, Copy, Debug, Default)]
pub struct BlockPartitioning<'a> {
    /// The partitions holding the concept, ascending by partition.
    pub holders: &'a [PartitionHolder],
    /// The partition of each input row, or `None` for an untagged block.
    pub row_partitions: Option<&'a [u16]>,
    /// The number of partitions in the store, which decides the tag width: one byte for at
    /// most [`MAX_NARROW_TAG_PARTITIONS`] partitions, two bytes above.
    pub partition_count: usize,
}

/// The most partitions a store may hold for its block tags to take one byte each.
pub const MAX_NARROW_TAG_PARTITIONS: usize = 255;

/// The most partitions a store may hold.
pub const MAX_PARTITIONS: usize = u16::MAX as usize;

/// Encodes numeric observations, given in input (file) order, as the payload of an
/// unpartitioned store's block: [`encode_numeric_block`] with no holders and no tags.
///
/// # Errors
/// As [`encode_numeric_block`].
pub fn encode_numeric_payload(observations: &[NumericObservation]) -> Result<Vec<u8>> {
    encode_numeric_block(observations, &BlockPartitioning::default())
}

/// Encodes numeric observations, given in input (file) order, as a numeric block payload
/// whose rows split over partitions as `partitioning` says.
///
/// The body holds `values`, `patients` and `timestamps` sorted by (value in Java
/// `Double.compareTo` order, patient, timestamp, input position), then a `by_key`
/// permutation into those arrays that orders the observations by patient and, within a
/// patient, by input position, as Java's stable `sortedByKey` does. NaN values are
/// canonicalized. The timestamp array is omitted when every timestamp is
/// [`NO_TIMESTAMP`]. Tags, when given, are stored in value order. `observations` is not
/// modified.
///
/// # Errors
/// [`FormatError::InvalidInput`] when there are more than `u32::MAX` observations, or the
/// partitioning does not fit the rows (see [`check_partitioning`]).
pub fn encode_numeric_block(
    observations: &[NumericObservation],
    partitioning: &BlockPartitioning<'_>,
) -> Result<Vec<u8>> {
    let n = observation_count(observations.len())?;
    check_partitioning(n, partitioning)?;
    let mut by_value: Vec<(i64, u32, i64, u32)> = observations
        .iter()
        .enumerate()
        .map(|(pos, o)| {
            (
                java_order_key(canonical_nan(o.value)),
                o.patient,
                o.timestamp,
                pos as u32,
            )
        })
        .collect();
    by_value.sort_unstable();
    let mut rank = vec![0u32; observations.len()];
    for (r, entry) in by_value.iter().enumerate() {
        rank[entry.3 as usize] = r as u32;
    }
    let mut by_key = positions_by_patient(n, |pos| observations[pos].patient);
    for slot in &mut by_key {
        *slot = rank[*slot as usize];
    }
    drop(rank);
    let has_timestamp = observations.iter().any(|o| o.timestamp != NO_TIMESTAMP);

    let len = observations.len();
    let mut buf = Vec::with_capacity(
        (numeric_payload_bound(n.into()) + partition_bound(n.into(), partitioning)) as usize,
    );
    put_prefix(&mut buf, n, has_timestamp, KIND_NUMERIC, partitioning)?;
    buf.extend_from_slice(&n.to_le_bytes());
    buf.extend_from_slice(&[u8::from(has_timestamp), KIND_NUMERIC, 0, 0]);
    let mut values = Vec::with_capacity(len);
    values.extend(
        by_value
            .iter()
            .map(|e| canonical_nan(observations[e.3 as usize].value)),
    );
    put_array(&mut buf, &values);
    drop(values);
    let patients: Vec<u32> = by_value.iter().map(|e| e.1).collect();
    put_array(&mut buf, &patients);
    drop(patients);
    if has_timestamp {
        let timestamps: Vec<i64> = by_value.iter().map(|e| e.2).collect();
        put_array(&mut buf, &timestamps);
    }
    put_array(&mut buf, &by_key);
    drop(by_key);
    if let Some(rows) = partitioning.row_partitions {
        put_tags(
            &mut buf,
            by_value.iter().map(|e| rows[e.3 as usize]),
            tag_width(partitioning.partition_count),
        );
    }
    Ok(buf)
}

/// Encodes categorical observations, given in input (file) order, as the payload of an
/// unpartitioned store's block. Interns the values and calls
/// [`encode_interned_categorical_payload`]; prefer that function when the caller already
/// holds distinct values, since this one needs a `String` per observation.
///
/// # Errors
/// As [`encode_interned_categorical_payload`].
pub fn encode_categorical_payload(observations: &[CategoricalObservation]) -> Result<Vec<u8>> {
    let mut ids: HashMap<&str, u32> = HashMap::new();
    let mut values: Vec<&str> = Vec::new();
    let mut rows = Vec::with_capacity(observations.len());
    for o in observations {
        let value_id = *ids.entry(o.value.as_str()).or_insert_with(|| {
            values.push(o.value.as_str());
            (values.len() - 1) as u32
        });
        rows.push(CategoricalRow {
            patient: o.patient,
            value_id,
            timestamp: o.timestamp,
        });
    }
    encode_interned_categorical_payload(&values, &rows)
}

/// Encodes categorical observations as the payload of an unpartitioned store's block:
/// [`encode_interned_categorical_block`] with no holders and no tags.
///
/// # Errors
/// As [`encode_interned_categorical_block`].
pub fn encode_interned_categorical_payload<S: AsRef<str>>(
    values: &[S],
    rows: &[CategoricalRow],
) -> Result<Vec<u8>> {
    encode_interned_categorical_block(values, rows, &BlockPartitioning::default())
}

/// Encodes categorical observations as a categorical block payload, with each value named
/// by its index into `values`, and the rows split over partitions as `partitioning` says.
/// `rows` are in input (file) order.
///
/// The dictionary holds the values that at least one row uses, sorted in Java
/// `String.compareTo` order; unused entries of `values` are dropped. Each dictionary entry
/// gets one roaring bitmap of the patients that have it. The `by_key` arrays (`patients`,
/// `value_index`, `timestamps`) order the rows by patient and, within a patient, by input
/// position, as Java's stable `sortedByKey` does. The timestamp array is omitted when every
/// timestamp is [`NO_TIMESTAMP`]. Tags, when given, are stored in that key order.
///
/// # Errors
/// [`FormatError::InvalidInput`] when there are more than `u32::MAX` rows or values, a
/// row's `value_id` is out of range, two used entries of `values` are equal, or the
/// partitioning does not fit the rows (see [`check_partitioning`]).
pub fn encode_interned_categorical_block<S: AsRef<str>>(
    values: &[S],
    rows: &[CategoricalRow],
    partitioning: &BlockPartitioning<'_>,
) -> Result<Vec<u8>> {
    let n = observation_count(rows.len())?;
    check_partitioning(n, partitioning)?;
    if u32::try_from(values.len()).is_err() {
        return Err(FormatError::InvalidInput(format!(
            "{} distinct values exceed the u32 limit of a block",
            values.len()
        )));
    }
    let mut used = vec![false; values.len()];
    for row in rows {
        let slot = used.get_mut(row.value_id as usize).ok_or_else(|| {
            FormatError::InvalidInput(format!(
                "value id {} is out of range for {} values",
                row.value_id,
                values.len()
            ))
        })?;
        *slot = true;
    }
    let mut dictionary: Vec<u32> = (0..values.len() as u32)
        .filter(|&id| used[id as usize])
        .collect();
    drop(used);
    dictionary.sort_unstable_by(|&a, &b| {
        java_string_cmp(values[a as usize].as_ref(), values[b as usize].as_ref())
    });
    if let Some(w) = dictionary
        .windows(2)
        .find(|w| values[w[0] as usize].as_ref() == values[w[1] as usize].as_ref())
    {
        return Err(FormatError::InvalidInput(format!(
            "value {:?} appears twice in the value list",
            values[w[0] as usize].as_ref()
        )));
    }
    let mut dictionary_index = vec![0u32; values.len()];
    for (index, &id) in dictionary.iter().enumerate() {
        dictionary_index[id as usize] = index as u32;
    }
    let k = dictionary.len() as u32;

    let by_key = positions_by_patient(n, |pos| rows[pos].patient);
    let mut bitmaps = vec![RoaringBitmap::new(); dictionary.len()];
    for &pos in &by_key {
        let row = &rows[pos as usize];
        bitmaps[dictionary_index[row.value_id as usize] as usize].insert(row.patient);
    }
    let has_timestamp = rows.iter().any(|r| r.timestamp != NO_TIMESTAMP);

    let mut buf = Vec::new();
    put_prefix(&mut buf, n, has_timestamp, KIND_CATEGORICAL, partitioning)?;
    buf.extend_from_slice(&n.to_le_bytes());
    buf.extend_from_slice(&[u8::from(has_timestamp), KIND_CATEGORICAL, 0, 0]);
    buf.extend_from_slice(&k.to_le_bytes());
    buf.extend_from_slice(&0u32.to_le_bytes());

    let mut string_offsets = Vec::with_capacity(dictionary.len() + 1);
    let mut string_bytes = Vec::new();
    string_offsets.push(0u64);
    for &id in &dictionary {
        string_bytes.extend_from_slice(values[id as usize].as_ref().as_bytes());
        string_offsets.push(string_bytes.len() as u64);
    }
    put_array(&mut buf, &string_offsets);
    put_array(&mut buf, &string_bytes);

    let mut bitmap_offsets = Vec::with_capacity(bitmaps.len() + 1);
    let mut bitmap_bytes = Vec::new();
    bitmap_offsets.push(0u64);
    for bitmap in &bitmaps {
        bitmap.serialize_into(&mut bitmap_bytes)?;
        bitmap_offsets.push(bitmap_bytes.len() as u64);
    }
    put_array(&mut buf, &bitmap_offsets);
    put_array(&mut buf, &bitmap_bytes);

    let patients: Vec<u32> = by_key.iter().map(|&i| rows[i as usize].patient).collect();
    put_array(&mut buf, &patients);
    let indices: Vec<u32> = by_key
        .iter()
        .map(|&i| dictionary_index[rows[i as usize].value_id as usize])
        .collect();
    put_array(&mut buf, &indices);
    if has_timestamp {
        let timestamps: Vec<i64> = by_key.iter().map(|&i| rows[i as usize].timestamp).collect();
        put_array(&mut buf, &timestamps);
    }
    if let Some(row_partitions) = partitioning.row_partitions {
        put_tags(
            &mut buf,
            by_key.iter().map(|&i| row_partitions[i as usize]),
            tag_width(partitioning.partition_count),
        );
    }
    Ok(buf)
}

/// The tag width in bytes for a store of `partition_count` partitions.
fn tag_width(partition_count: usize) -> u8 {
    if partition_count <= MAX_NARROW_TAG_PARTITIONS {
        1
    } else {
        2
    }
}

/// Bytes of the block prefix for `holders` holders: the 16-byte header and the holder
/// table, padded to 8.
fn prefix_len(holders: usize) -> usize {
    (PREFIX_HEADER_LEN + HOLDER_LEN * holders).next_multiple_of(8)
}

/// Bytes the prefix and tags add to a block of `n` rows partitioned as `partitioning`.
fn partition_bound(n: u64, partitioning: &BlockPartitioning<'_>) -> u64 {
    let tags = if partitioning.row_partitions.is_some() {
        padded(n.saturating_mul(u64::from(tag_width(partitioning.partition_count))))
    } else {
        0
    };
    prefix_len(partitioning.holders.len()) as u64 + tags
}

/// Checks that `partitioning` fits a block of `n` rows: at most [`MAX_PARTITIONS`]
/// partitions; holders strictly ascending by partition, each below the partition count,
/// with observation counts summing to `n` (no holders for an unpartitioned block, which
/// then takes no tags); and, when tagged, one tag per row naming a holder, each holder
/// getting exactly its observation count.
///
/// # Errors
/// [`FormatError::InvalidInput`] naming the first mismatch.
pub fn check_partitioning(n: u32, partitioning: &BlockPartitioning<'_>) -> Result<()> {
    let invalid = |detail: String| Err(FormatError::InvalidInput(detail));
    let holders = partitioning.holders;
    if partitioning.partition_count > MAX_PARTITIONS {
        return invalid(format!(
            "{} partitions exceed the limit of {MAX_PARTITIONS}",
            partitioning.partition_count
        ));
    }
    if holders.is_empty() {
        if partitioning.row_partitions.is_some() {
            return invalid("an unpartitioned block takes no tags".into());
        }
        return Ok(());
    }
    if let Some(w) = holders
        .windows(2)
        .find(|w| w[0].partition >= w[1].partition)
    {
        return invalid(format!(
            "holder partitions {} and {} are not strictly ascending",
            w[0].partition, w[1].partition
        ));
    }
    if let Some(h) = holders
        .iter()
        .find(|h| usize::from(h.partition) >= partitioning.partition_count)
    {
        return invalid(format!(
            "holder partition {} is not below the partition count {}",
            h.partition, partitioning.partition_count
        ));
    }
    let summed: u64 = holders.iter().map(|h| u64::from(h.observations)).sum();
    if summed != u64::from(n) {
        return invalid(format!(
            "holders count {summed} observations, the block has {n}"
        ));
    }
    if let Some(tags) = partitioning.row_partitions {
        if tags.len() != n as usize {
            return invalid(format!("{} tags for {n} rows", tags.len()));
        }
        let mut counts = vec![0u32; holders.len()];
        for &tag in tags {
            let Ok(slot) = holders.binary_search_by_key(&tag, |h| h.partition) else {
                return invalid(format!(
                    "a tag names partition {tag}, which holds no rows here"
                ));
            };
            counts[slot] += 1;
        }
        if let Some((h, c)) = holders
            .iter()
            .zip(&counts)
            .find(|(h, c)| h.observations != **c)
        {
            return invalid(format!(
                "partition {} is tagged on {c} rows, its holder says {}",
                h.partition, h.observations
            ));
        }
    }
    Ok(())
}

/// Writes the block prefix: `n`, the timestamp flag, the kind, the tag flag and width, the
/// holder count and a reserved word, then the holder table, padded to 8.
fn put_prefix(
    buf: &mut Vec<u8>,
    n: u32,
    has_timestamp: bool,
    kind: u8,
    partitioning: &BlockPartitioning<'_>,
) -> Result<()> {
    let holders = u32::try_from(partitioning.holders.len())
        .map_err(|_| FormatError::InvalidInput("more holders than a block records".into()))?;
    let tagged = partitioning.row_partitions.is_some();
    buf.extend_from_slice(&n.to_le_bytes());
    buf.extend_from_slice(&[
        u8::from(has_timestamp),
        kind,
        if tagged { FLAG_TAGGED } else { 0 },
        if tagged {
            tag_width(partitioning.partition_count)
        } else {
            0
        },
    ]);
    buf.extend_from_slice(&holders.to_le_bytes());
    buf.extend_from_slice(&0u32.to_le_bytes());
    for holder in partitioning.holders {
        buf.extend_from_slice(&holder.partition.to_le_bytes());
        buf.extend_from_slice(&0u16.to_le_bytes());
        buf.extend_from_slice(&holder.observations.to_le_bytes());
        buf.extend_from_slice(&holder.patients.to_le_bytes());
    }
    pad8(buf);
    Ok(())
}

/// Writes one tag per row, `width` bytes each, little-endian, padded to 8.
fn put_tags(buf: &mut Vec<u8>, tags: impl Iterator<Item = u16>, width: u8) {
    for tag in tags {
        if width == 1 {
            buf.push(tag as u8);
        } else {
            buf.extend_from_slice(&tag.to_le_bytes());
        }
    }
    pad8(buf);
}

/// Largest numeric payload `n` observations can encode to.
pub(crate) fn numeric_payload_bound(n: u64) -> u64 {
    let n = n.min(u64::from(u32::MAX));
    NUMERIC_HEADER_LEN as u64 + 8 * n + padded(4 * n) + 8 * n + padded(4 * n)
}

/// Largest categorical payload `n` observations over `k` distinct values whose UTF-8 bytes
/// total `string_bytes` can encode to. A portable roaring bitmap costs at most 8 bytes
/// plus 10 per member (no run containers are written), and a bitmap has at most one
/// member per observation, so 16 bytes per value and per observation bound them.
pub(crate) fn categorical_payload_bound(n: u64, k: u64, string_bytes: u64) -> u64 {
    let n = n.min(u64::from(u32::MAX));
    let k = k.min(u64::from(u32::MAX));
    let offsets = 8 * (k + 1);
    CATEGORICAL_HEADER_LEN as u64
        + offsets
        + padded(string_bytes)
        + offsets
        + padded(16 * k + 16 * n)
        + padded(4 * n)
        + padded(4 * n)
        + 8 * n
}

struct Cursor<'a> {
    bytes: &'a [u8],
    pos: usize,
    what: &'static str,
}

impl<'a> Cursor<'a> {
    fn take(&mut self, len: usize) -> Result<Range<usize>> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|&end| end <= self.bytes.len())
            .ok_or(FormatError::Truncated(self.what))?;
        let range = self.pos..end;
        self.pos = end;
        Ok(range)
    }

    fn u32_at(&self, at: usize) -> Result<u32> {
        self.bytes
            .get(at..at + 4)
            .and_then(|b| b.try_into().ok())
            .map(u32::from_le_bytes)
            .ok_or(FormatError::Truncated(self.what))
    }

    fn array<T: Pod>(&mut self, count: usize) -> Result<Range<usize>> {
        let len = count
            .checked_mul(size_of::<T>())
            .ok_or(FormatError::Truncated(self.what))?;
        let range = self.take(len)?;
        let padding = range.end.next_multiple_of(8) - range.end;
        self.take(padding)?;
        Ok(range)
    }

    fn finish(&self) -> Result<()> {
        if self.pos == self.bytes.len() {
            Ok(())
        } else {
            Err(FormatError::corrupt(
                self.what,
                format!("{} trailing bytes", self.bytes.len() - self.pos),
            ))
        }
    }
}

fn view<'a, T: Pod>(buf: &'a AlignedBuf, range: &Range<usize>) -> &'a [T] {
    buf.as_bytes()
        .get(range.clone())
        .and_then(|bytes| bytemuck::try_cast_slice(bytes).ok())
        .unwrap_or(&[])
}

fn check_flag(byte: u8, what: &'static str) -> Result<bool> {
    match byte {
        0 => Ok(false),
        1 => Ok(true),
        other => Err(FormatError::corrupt(
            what,
            format!("has_timestamp byte {other}"),
        )),
    }
}

/// The distinct patients of a patient-ordered sequence, or an error when the sequence
/// decreases somewhere.
fn distinct_sorted_patients(
    patients: impl Iterator<Item = u32>,
    what: &'static str,
) -> Result<RoaringBitmap> {
    let mut previous = None;
    let distinct = patients.filter(move |&p| {
        let fresh = previous != Some(p);
        previous = Some(p);
        fresh
    });
    RoaringBitmap::from_sorted_iter(distinct)
        .map_err(|_| FormatError::corrupt(what, "observations are not ordered by patient"))
}

fn bitmap_heap_size(bitmap: &RoaringBitmap) -> usize {
    size_of::<RoaringBitmap>() + bitmap.serialized_size()
}

/// The tag array of a block, empty when it is untagged.
fn tag_bytes<'a>(buf: &'a AlignedBuf, partitions: &BlockPartitions) -> &'a [u8] {
    partitions
        .tags
        .clone()
        .and_then(|range| buf.as_bytes().get(range))
        .unwrap_or_default()
}

/// The tag of row `index` in `buf` under `partitions`, `None` when untagged or out of
/// range.
fn tag_at(buf: &AlignedBuf, partitions: &BlockPartitions, index: usize) -> Option<u16> {
    let bytes = buf.as_bytes().get(partitions.tags.clone()?)?;
    match partitions.tag_width {
        1 => bytes.get(index).map(|&t| u16::from(t)),
        _ => bytes
            .get(index * 2..index * 2 + 2)
            .map(|t| u16::from_le_bytes([t[0], t[1]])),
    }
}

/// The partition layout a decoded block records: its holders, and for a tagged block the
/// partition of every row.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BlockPartitions {
    holders: Vec<PartitionHolder>,
    tag_width: u8,
    tags: Option<Range<usize>>,
}

impl BlockPartitions {
    /// The partitions holding the concept, ascending by partition. Empty for a block of an
    /// unpartitioned store.
    pub fn holders(&self) -> &[PartitionHolder] {
        &self.holders
    }

    /// True when every row carries its partition ([`Block::tag`]).
    pub fn is_tagged(&self) -> bool {
        self.tags.is_some()
    }

    fn heap_size(&self) -> usize {
        self.holders.capacity() * size_of::<PartitionHolder>()
    }
}

/// The block prefix as parsed, before the body.
struct Prefix {
    n: usize,
    has_timestamp: bool,
    kind: u8,
    tag_width: u8,
    tagged: bool,
    holders: Vec<PartitionHolder>,
    body_start: usize,
}

impl Prefix {
    /// The prefix of a format 1 payload, which has none: the body starts at 0.
    fn legacy(bytes: &[u8]) -> Result<Self> {
        let header = bytes
            .get(..8)
            .ok_or(FormatError::Truncated("block header"))?;
        Ok(Prefix {
            n: u32::from_le_bytes(header[..4].try_into().unwrap_or_default()) as usize,
            has_timestamp: check_flag(header[4], "block")?,
            kind: header[5],
            tag_width: 0,
            tagged: false,
            holders: Vec::new(),
            body_start: 0,
        })
    }

    /// Parses and checks the prefix at the start of `bytes`: known flags, a tag width that
    /// fits the flag, zero reserved bytes, holders strictly ascending by partition whose
    /// observation counts sum to the rows, and no tags without holders.
    fn parse(bytes: &[u8]) -> Result<Self> {
        let what = "block prefix";
        let header = bytes
            .get(..PREFIX_HEADER_LEN)
            .ok_or(FormatError::Truncated(what))?;
        let word =
            |at: usize| u32::from_le_bytes(header[at..at + 4].try_into().unwrap_or_default());
        let n = word(0) as usize;
        let has_timestamp = check_flag(header[4], what)?;
        let kind = header[5];
        let flags = header[6];
        let tag_width = header[7];
        let holder_count = word(8) as usize;
        if word(12) != 0 {
            return Err(FormatError::corrupt(what, "reserved bytes are not zero"));
        }
        if flags & !FLAG_TAGGED != 0 {
            return Err(FormatError::corrupt(
                what,
                format!("unknown flags {flags:#04x}"),
            ));
        }
        let tagged = flags & FLAG_TAGGED != 0;
        match (tagged, tag_width) {
            (false, 0) | (true, 1) | (true, 2) => {}
            _ => {
                return Err(FormatError::corrupt(
                    what,
                    format!("tag width {tag_width} with tagging {tagged}"),
                ));
            }
        }
        let table_end = holder_count
            .checked_mul(HOLDER_LEN)
            .and_then(|len| len.checked_add(PREFIX_HEADER_LEN))
            .filter(|&end| end <= bytes.len())
            .ok_or(FormatError::Truncated(what))?;
        let body_start = table_end.next_multiple_of(8);
        if body_start > bytes.len() || bytes[table_end..body_start].iter().any(|&b| b != 0) {
            return Err(FormatError::corrupt(
                what,
                "holder table padding is not zero",
            ));
        }
        let holders: Vec<PartitionHolder> = bytes[PREFIX_HEADER_LEN..table_end]
            .as_chunks::<HOLDER_LEN>()
            .0
            .iter()
            .map(|h| {
                let reserved = u16::from_le_bytes([h[2], h[3]]);
                (reserved == 0)
                    .then(|| PartitionHolder {
                        partition: u16::from_le_bytes([h[0], h[1]]),
                        observations: u32::from_le_bytes(h[4..8].try_into().unwrap_or_default()),
                        patients: u32::from_le_bytes(h[8..12].try_into().unwrap_or_default()),
                    })
                    .ok_or_else(|| FormatError::corrupt(what, "holder reserved bytes are not zero"))
            })
            .collect::<Result<_>>()?;
        if holders.is_empty() && tagged {
            return Err(FormatError::corrupt(
                what,
                "an unpartitioned block is tagged",
            ));
        }
        if holders.windows(2).any(|w| w[0].partition >= w[1].partition) {
            return Err(FormatError::corrupt(
                what,
                "holder partitions are not strictly ascending",
            ));
        }
        let summed: u64 = holders.iter().map(|h| u64::from(h.observations)).sum();
        if !holders.is_empty() && summed != n as u64 {
            return Err(FormatError::corrupt(
                what,
                format!("holders count {summed} rows, the block has {n}"),
            ));
        }
        Ok(Prefix {
            n,
            has_timestamp,
            kind,
            tag_width,
            tagged,
            holders,
            body_start,
        })
    }

    /// Checks that the body header at `at` repeats the prefix's row count, timestamp flag
    /// and kind.
    fn check_body_header(&self, bytes: &[u8], at: usize, what: &'static str) -> Result<()> {
        let header = bytes.get(at..at + 8).ok_or(FormatError::Truncated(what))?;
        let n = u32::from_le_bytes(header[..4].try_into().unwrap_or_default()) as usize;
        if n != self.n
            || check_flag(header[4], what)? != self.has_timestamp
            || header[5] != self.kind
        {
            return Err(FormatError::corrupt(
                what,
                "the body header disagrees with the block prefix",
            ));
        }
        Ok(())
    }

    /// Reads the tag array after the body when the block is tagged, and checks that every
    /// tag names a holder and each holder gets exactly its observation count. Returns the
    /// partitions record of the block.
    fn finish(self, c: &mut Cursor<'_>) -> Result<BlockPartitions> {
        let tags = if self.tagged {
            let range = match self.tag_width {
                1 => c.array::<u8>(self.n)?,
                _ => c.array::<u16>(self.n)?,
            };
            let bytes = &c.bytes[range.clone()];
            let mut counts = vec![0u32; self.holders.len()];
            let mut count = |tag: u16| -> Result<()> {
                let slot = self
                    .holders
                    .binary_search_by_key(&tag, |h| h.partition)
                    .map_err(|_| {
                        FormatError::corrupt(
                            "block tags",
                            format!("a tag names partition {tag}, no holder"),
                        )
                    })?;
                counts[slot] += 1;
                Ok(())
            };
            if self.tag_width == 1 {
                bytes.iter().try_for_each(|&t| count(u16::from(t)))?;
            } else {
                bytes
                    .as_chunks::<2>()
                    .0
                    .iter()
                    .try_for_each(|t| count(u16::from_le_bytes([t[0], t[1]])))?;
            }
            if self
                .holders
                .iter()
                .zip(&counts)
                .any(|(h, &c)| h.observations != c)
            {
                return Err(FormatError::corrupt(
                    "block tags",
                    "the tags disagree with the holders' observation counts",
                ));
            }
            Some(range)
        } else {
            None
        };
        c.finish()?;
        Ok(BlockPartitions {
            holders: self.holders,
            tag_width: self.tag_width,
            tags,
        })
    }
}

/// A decoded numeric block. The array accessors are zero-copy views over one aligned
/// buffer; the patient set is computed once at decode.
#[derive(Clone, Debug)]
pub struct NumericBlock {
    buf: AlignedBuf,
    len: usize,
    has_timestamp: bool,
    values: Range<usize>,
    patients: Range<usize>,
    timestamps: Option<Range<usize>>,
    by_key: Range<usize>,
    all_patients: RoaringBitmap,
    partitions: BlockPartitions,
}

impl NumericBlock {
    fn decode(buf: AlignedBuf, prefix: Prefix) -> Result<Self> {
        let what = "numeric block";
        let mut c = Cursor {
            bytes: buf.as_bytes(),
            pos: prefix.body_start,
            what,
        };
        prefix.check_body_header(c.bytes, c.pos, what)?;
        c.take(NUMERIC_HEADER_LEN)?;
        let len = prefix.n;
        let has_timestamp = prefix.has_timestamp;
        let values = c.array::<f64>(len)?;
        let patients = c.array::<u32>(len)?;
        let timestamps = if has_timestamp {
            Some(c.array::<i64>(len)?)
        } else {
            None
        };
        let by_key = c.array::<u32>(len)?;
        let partitions = prefix.finish(&mut c)?;
        let mut block = NumericBlock {
            len,
            has_timestamp,
            values,
            patients,
            timestamps,
            by_key,
            buf,
            all_patients: RoaringBitmap::new(),
            partitions,
        };
        let mut seen = vec![0u64; len.div_ceil(64)];
        for &i in block.by_key() {
            let i = i as usize;
            if i >= len || seen[i / 64] & (1 << (i % 64)) != 0 {
                return Err(FormatError::corrupt(
                    "numeric block",
                    "by_key is not a permutation of the observations",
                ));
            }
            seen[i / 64] |= 1 << (i % 64);
        }
        drop(seen);
        let patients = block.patients();
        block.all_patients = distinct_sorted_patients(
            block.by_key().iter().map(|&i| patients[i as usize]),
            "numeric block",
        )?;
        Ok(block)
    }

    /// The partition layout the block records.
    pub fn partitions(&self) -> &BlockPartitions {
        &self.partitions
    }

    /// The partition tag of value position `index`, or `None` when the block is untagged or
    /// `index` is out of range.
    pub fn tag_at_value_pos(&self, index: usize) -> Option<u16> {
        tag_at(&self.buf, &self.partitions, index)
    }

    /// Number of observations.
    pub fn len(&self) -> usize {
        self.len
    }

    /// True when the block holds no observations.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// True when at least one observation carries a timestamp.
    pub fn has_timestamp(&self) -> bool {
        self.has_timestamp
    }

    /// Values in Java `Double.compareTo` order. Positions in this array are value
    /// positions.
    pub fn values(&self) -> &[f64] {
        view(&self.buf, &self.values)
    }

    /// Patient of each value position.
    pub fn patients(&self) -> &[u32] {
        view(&self.buf, &self.patients)
    }

    /// Timestamp of each value position, or `None` when the block has no timestamps.
    pub fn timestamps(&self) -> Option<&[i64]> {
        self.timestamps.as_ref().map(|r| view(&self.buf, r))
    }

    /// Timestamp at value position `index`, [`NO_TIMESTAMP`] when the block has none or
    /// `index` is out of range.
    pub fn timestamp_at_value_pos(&self, index: usize) -> i64 {
        self.timestamps()
            .and_then(|t| t.get(index).copied())
            .unwrap_or(NO_TIMESTAMP)
    }

    /// Value positions ordered by patient and, within a patient, by input order: the order
    /// of Java `sortedByKey`. Walk this for per-patient exports.
    pub fn by_key(&self) -> &[u32] {
        view(&self.buf, &self.by_key)
    }

    /// Every patient with at least one observation, computed once at decode.
    pub fn all_patients(&self) -> &RoaringBitmap {
        &self.all_patients
    }

    /// The range of value positions with `min <= value <= max` in Java
    /// `Double.compareTo` order. A `None` bound is open.
    pub fn value_range(&self, min: Option<f64>, max: Option<f64>) -> Range<usize> {
        let values = self.values();
        let start = min.map_or(0, |m| {
            values.partition_point(|&v| java_double_cmp(v, m) == Ordering::Less)
        });
        let end = max.map_or(values.len(), |m| {
            values.partition_point(|&v| java_double_cmp(v, m) != Ordering::Greater)
        });
        start..end.max(start)
    }

    /// Approximate heap bytes held: the payload buffer, the patient bitmap and the holder
    /// table.
    pub fn heap_size(&self) -> usize {
        self.buf.heap_size() + bitmap_heap_size(&self.all_patients) + self.partitions.heap_size()
    }
}

/// A decoded categorical block. The observation arrays are zero-copy views over one
/// aligned buffer; the dictionary, the per-value bitmaps and their union are decoded once.
#[derive(Clone, Debug)]
pub struct CategoricalBlock {
    buf: AlignedBuf,
    len: usize,
    has_timestamp: bool,
    dictionary_text: String,
    dictionary: Vec<Range<usize>>,
    bitmap_offsets: Range<usize>,
    bitmap_bytes: Range<usize>,
    bitmaps: Vec<RoaringBitmap>,
    all_patients: RoaringBitmap,
    patients: Range<usize>,
    value_indices: Range<usize>,
    timestamps: Option<Range<usize>>,
    partitions: BlockPartitions,
}

fn check_offsets(offsets: &[u64], data_len: usize, what: &'static str) -> Result<()> {
    if offsets.first() != Some(&0) || offsets.last() != Some(&(data_len as u64)) {
        return Err(FormatError::corrupt(
            what,
            "offset table does not span its data",
        ));
    }
    if offsets.windows(2).any(|w| w[0] > w[1]) {
        return Err(FormatError::corrupt(what, "offsets decrease"));
    }
    Ok(())
}

impl CategoricalBlock {
    fn decode(buf: AlignedBuf, prefix: Prefix) -> Result<Self> {
        let what = "categorical block";
        let mut c = Cursor {
            bytes: buf.as_bytes(),
            pos: prefix.body_start,
            what,
        };
        prefix.check_body_header(c.bytes, c.pos, what)?;
        let header = c.take(CATEGORICAL_HEADER_LEN)?;
        let len = prefix.n;
        let has_timestamp = prefix.has_timestamp;
        let dictionary_len = c.u32_at(header.start + 8)? as usize;
        if c.u32_at(header.start + 12)? != 0 {
            return Err(FormatError::corrupt(what, "reserved bytes are not zero"));
        }
        let entries = dictionary_len
            .checked_add(1)
            .ok_or(FormatError::Truncated(c.what))?;

        let string_offsets = c.array::<u64>(entries)?;
        let string_len = view::<u64>(&buf, &string_offsets)
            .last()
            .copied()
            .unwrap_or(0);
        let string_bytes = c.array::<u8>(
            usize::try_from(string_len).map_err(|_| FormatError::Truncated(c.what))?,
        )?;
        let bitmap_offsets = c.array::<u64>(entries)?;
        let bitmap_len = view::<u64>(&buf, &bitmap_offsets)
            .last()
            .copied()
            .unwrap_or(0);
        let bitmap_bytes = c.array::<u8>(
            usize::try_from(bitmap_len).map_err(|_| FormatError::Truncated(c.what))?,
        )?;
        let patients = c.array::<u32>(len)?;
        let value_indices = c.array::<u32>(len)?;
        let timestamps = if has_timestamp {
            Some(c.array::<i64>(len)?)
        } else {
            None
        };
        let partitions = prefix.finish(&mut c)?;

        let strings = view::<u8>(&buf, &string_bytes);
        let offsets = view::<u64>(&buf, &string_offsets);
        check_offsets(offsets, strings.len(), "categorical dictionary")?;
        let dictionary: Vec<Range<usize>> = offsets
            .windows(2)
            .map(|w| w[0] as usize..w[1] as usize)
            .collect();
        for range in &dictionary {
            std::str::from_utf8(&strings[range.clone()])
                .map_err(|e| FormatError::corrupt("categorical dictionary", e.to_string()))?;
        }
        let dictionary_text = String::from_utf8(strings.to_vec())
            .map_err(|e| FormatError::corrupt("categorical dictionary", e.to_string()))?;

        let serialized = view::<u8>(&buf, &bitmap_bytes);
        let offsets = view::<u64>(&buf, &bitmap_offsets);
        check_offsets(offsets, serialized.len(), "categorical bitmaps")?;
        let bitmaps = offsets
            .windows(2)
            .map(|w| {
                RoaringBitmap::deserialize_from(&serialized[w[0] as usize..w[1] as usize])
                    .map_err(|e| FormatError::corrupt("categorical bitmap", e.to_string()))
            })
            .collect::<Result<Vec<_>>>()?;

        let block = CategoricalBlock {
            len,
            has_timestamp,
            dictionary_text,
            dictionary,
            bitmap_offsets,
            bitmap_bytes,
            bitmaps,
            all_patients: RoaringBitmap::new(),
            patients,
            value_indices,
            timestamps,
            buf,
            partitions,
        };
        if block
            .value_indices()
            .iter()
            .any(|&v| v as usize >= dictionary_len)
        {
            return Err(FormatError::corrupt(
                "categorical block",
                "value index out of range",
            ));
        }
        let all_patients =
            distinct_sorted_patients(block.patients().iter().copied(), "categorical block")?;
        Ok(CategoricalBlock {
            all_patients,
            ..block
        })
    }

    /// Number of observations.
    pub fn len(&self) -> usize {
        self.len
    }

    /// True when the block holds no observations.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// True when at least one observation carries a timestamp.
    pub fn has_timestamp(&self) -> bool {
        self.has_timestamp
    }

    /// Number of distinct values.
    pub fn dictionary_len(&self) -> usize {
        self.dictionary.len()
    }

    /// Dictionary entry `index`, or `None` when out of range. Validated once at decode.
    pub fn dictionary_value(&self, index: usize) -> Option<&str> {
        self.dictionary_text
            .get(self.dictionary.get(index)?.clone())
    }

    /// Dictionary entries in Java `String.compareTo` order.
    pub fn dictionary(&self) -> impl Iterator<Item = &str> + '_ {
        self.dictionary
            .iter()
            .map(|r| self.dictionary_text.get(r.clone()).unwrap_or_default())
    }

    /// Dictionary index of `value`, found by binary search in Java string order.
    pub fn find_value(&self, value: &str) -> Option<usize> {
        let (mut lo, mut hi) = (0usize, self.dictionary.len());
        while lo < hi {
            let mid = lo + (hi - lo) / 2;
            match java_string_cmp(self.dictionary_value(mid)?, value) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return Some(mid),
            }
        }
        None
    }

    /// The stored portable serialization of dictionary entry `index`'s bitmap, or `None`
    /// when out of range.
    pub fn serialized_bitmap(&self, index: usize) -> Option<&[u8]> {
        let offsets = view::<u64>(&self.buf, &self.bitmap_offsets);
        let start = usize::try_from(*offsets.get(index)?).ok()?;
        let end = usize::try_from(*offsets.get(index + 1)?).ok()?;
        view::<u8>(&self.buf, &self.bitmap_bytes).get(start..end)
    }

    /// Patients with dictionary entry `index`, deserialized once at decode.
    ///
    /// # Panics
    /// When `index >= dictionary_len()`.
    pub fn bitmap(&self, index: usize) -> &RoaringBitmap {
        &self.bitmaps[index]
    }

    /// Every dictionary entry's patients, in dictionary order.
    pub fn bitmaps(&self) -> &[RoaringBitmap] {
        &self.bitmaps
    }

    /// Every patient with at least one observation, computed once at decode.
    pub fn all_patients(&self) -> &RoaringBitmap {
        &self.all_patients
    }

    /// Patient of each observation, ordered by patient and, within a patient, by input
    /// order: the order of Java `sortedByKey`. Positions in this array are key positions.
    pub fn patients(&self) -> &[u32] {
        view(&self.buf, &self.patients)
    }

    /// Dictionary index of each key position, parallel to [`CategoricalBlock::patients`].
    pub fn value_indices(&self) -> &[u32] {
        view(&self.buf, &self.value_indices)
    }

    /// Timestamp of each key position, parallel to [`CategoricalBlock::patients`], or
    /// `None` when the block has no timestamps.
    pub fn timestamps(&self) -> Option<&[i64]> {
        self.timestamps.as_ref().map(|r| view(&self.buf, r))
    }

    /// Timestamp at key position `index`, [`NO_TIMESTAMP`] when the block has none or
    /// `index` is out of range.
    pub fn timestamp_at_key_pos(&self, index: usize) -> i64 {
        self.timestamps()
            .and_then(|t| t.get(index).copied())
            .unwrap_or(NO_TIMESTAMP)
    }

    /// The partition layout the block records.
    pub fn partitions(&self) -> &BlockPartitions {
        &self.partitions
    }

    /// The partition tag of key position `index`, or `None` when the block is untagged or
    /// `index` is out of range.
    pub fn tag_at_key_pos(&self, index: usize) -> Option<u16> {
        tag_at(&self.buf, &self.partitions, index)
    }

    /// Approximate heap bytes held: the payload buffer, the dictionary copy, the
    /// deserialized bitmaps and the holder table.
    pub fn heap_size(&self) -> usize {
        self.partitions.heap_size()
            + self.buf.heap_size()
            + self.dictionary_text.capacity()
            + self.dictionary.capacity() * size_of::<Range<usize>>()
            + self.bitmaps.iter().map(bitmap_heap_size).sum::<usize>()
            + bitmap_heap_size(&self.all_patients)
    }
}

/// A decoded block of either kind.
#[derive(Clone, Debug)]
pub enum Block {
    /// A numeric concept's block.
    Numeric(NumericBlock),
    /// A categorical concept's block.
    Categorical(CategoricalBlock),
}

impl Block {
    /// Validates a decoded payload, wraps it in typed views, and computes the patient set
    /// (and, for categorical blocks, the dictionary and bitmaps). Checks the prefix and the
    /// tags on their own; [`Block::check_membership`] checks them against the store's
    /// partitions.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`] when the payload does not
    /// match either layout.
    pub fn decode(payload: AlignedBuf) -> Result<Self> {
        let prefix = Prefix::parse(payload.as_bytes())?;
        Self::decode_body(payload, prefix)
    }

    /// [`Block::decode`] for a format 1 payload, which has no prefix, holders or tags.
    ///
    /// # Errors
    /// As [`Block::decode`].
    pub fn decode_legacy(payload: AlignedBuf) -> Result<Self> {
        let prefix = Prefix::legacy(payload.as_bytes())?;
        Self::decode_body(payload, prefix)
    }

    fn decode_body(payload: AlignedBuf, prefix: Prefix) -> Result<Self> {
        match prefix.kind {
            KIND_NUMERIC => NumericBlock::decode(payload, prefix).map(Block::Numeric),
            KIND_CATEGORICAL => CategoricalBlock::decode(payload, prefix).map(Block::Categorical),
            other => Err(FormatError::corrupt(
                "block",
                format!("unknown kind {other}"),
            )),
        }
    }

    /// The partition layout the block records.
    pub fn partitions(&self) -> &BlockPartitions {
        match self {
            Block::Numeric(b) => b.partitions(),
            Block::Categorical(b) => b.partitions(),
        }
    }

    /// Patient and tag of every row, in key order: by patient, then the order within the
    /// patient ([`NumericBlock::by_key`] order for a numeric block, key position order for a
    /// categorical one). The tag is `None` in an untagged block.
    fn tags_by_key(&self) -> impl Iterator<Item = (u32, Option<u16>)> + '_ {
        let (patients, order, tags): (&[u32], &[u32], &[u8]) = match self {
            Block::Numeric(b) => (b.patients(), b.by_key(), tag_bytes(&b.buf, &b.partitions)),
            Block::Categorical(b) => (b.patients(), &[][..], tag_bytes(&b.buf, &b.partitions)),
        };
        let width = usize::from(self.partitions().tag_width);
        let numeric = matches!(self, Block::Numeric(_));
        (0..patients.len()).map(move |k| {
            let position = if numeric { order[k] as usize } else { k };
            let tag = match width {
                1 => tags.get(position).map(|&t| u16::from(t)),
                2 => tags
                    .get(position * 2..position * 2 + 2)
                    .map(|t| u16::from_le_bytes([t[0], t[1]])),
                _ => None,
            };
            (patients[position], tag)
        })
    }

    /// Patient and partition of every row, in key order: by patient, then the order within
    /// the patient. For a numeric block that is [`NumericBlock::by_key`] order, for a
    /// categorical one key position order. The partition is the row's tag in a tagged block,
    /// and otherwise the partition `membership` routes the patient to, `None` when it routes
    /// nowhere.
    pub fn rows_by_key<'a>(
        &'a self,
        membership: &'a Membership,
    ) -> impl Iterator<Item = (u32, Option<u16>)> + 'a {
        let tagged = self.partitions().is_tagged();
        self.tags_by_key().map(move |(patient, tag)| {
            if tagged {
                (patient, tag)
            } else {
                (patient, membership.partition_of(patient))
            }
        })
    }

    /// Checks the block's partitions against the store's: its holders must be `expected`
    /// (the index's holders of the concept) and its tagging must be `tagged`; every row of
    /// an untagged block must belong to a patient `membership` routes to one partition; a
    /// tagged row of a patient routed to a partition must carry that partition; a tagged row
    /// of an overlap patient must carry a partition whose tagged set holds the patient, when
    /// `membership` carries tagged sets; and the rows and distinct patients each partition
    /// gets must equal its holder's counts. A block of an unpartitioned store holds no
    /// holders and passes.
    ///
    /// # Errors
    /// [`FormatError::Corrupt`] naming the first disagreement.
    pub fn check_membership(
        &self,
        expected: &[PartitionHolder],
        tagged: bool,
        membership: &Membership,
    ) -> Result<()> {
        let what = "block partitions";
        let partitions = self.partitions();
        if partitions.holders() != expected {
            return Err(FormatError::corrupt(
                what,
                "the block's holders disagree with the index",
            ));
        }
        if partitions.is_tagged() != tagged {
            return Err(FormatError::corrupt(
                what,
                "the block's tagging disagrees with the index",
            ));
        }
        if expected.is_empty() {
            return Ok(());
        }
        let mut slot_of = vec![u32::MAX; membership.partition_count()];
        for (slot, holder) in expected.iter().enumerate() {
            match slot_of.get_mut(usize::from(holder.partition)) {
                Some(entry) => *entry = slot as u32,
                None => {
                    return Err(FormatError::corrupt(
                        what,
                        format!("holder partition {} is not in the store", holder.partition),
                    ));
                }
            }
        }
        let mut rows = vec![0u32; expected.len()];
        let mut patients = vec![0u32; expected.len()];
        let mut last = vec![None::<u32>; expected.len()];
        let mut routed: Option<(u32, Option<u16>)> = None;
        for (patient, tag) in self.tags_by_key() {
            let route = match routed {
                Some((p, route)) if p == patient => route,
                _ => {
                    let route = membership.partition_of(patient);
                    routed = Some((patient, route));
                    route
                }
            };
            let partition = match (partitions.is_tagged(), tag, route) {
                (false, _, Some(partition)) => partition,
                (false, _, None) => {
                    return Err(FormatError::corrupt(
                        what,
                        "an untagged block holds a row of a patient no single partition holds",
                    ));
                }
                (true, Some(tag), Some(route)) if tag != route => {
                    return Err(FormatError::corrupt(
                        what,
                        "a row is tagged with another partition than its patient's",
                    ));
                }
                (true, Some(tag), None) if membership.tagged_with(tag, patient) == Some(false) => {
                    return Err(FormatError::corrupt(
                        what,
                        "a row is tagged with a partition whose tagged set lacks its patient",
                    ));
                }
                (true, Some(tag), _) => tag,
                (true, None, _) => {
                    return Err(FormatError::corrupt(
                        what,
                        "a tagged block lacks a row's tag",
                    ));
                }
            };
            let slot = slot_of
                .get(usize::from(partition))
                .copied()
                .filter(|&slot| slot != u32::MAX)
                .ok_or_else(|| {
                    FormatError::corrupt(
                        what,
                        format!("a row belongs to partition {partition}, which is no holder"),
                    )
                })? as usize;
            rows[slot] += 1;
            if last[slot] != Some(patient) {
                patients[slot] += 1;
                last[slot] = Some(patient);
            }
        }
        for (slot, holder) in expected.iter().enumerate() {
            if holder.observations != rows[slot] || holder.patients != patients[slot] {
                return Err(FormatError::corrupt(
                    what,
                    format!(
                        "partition {} holds {} rows of {} patients, its holder says {} of {}",
                        holder.partition,
                        rows[slot],
                        patients[slot],
                        holder.observations,
                        holder.patients
                    ),
                ));
            }
        }
        Ok(())
    }

    /// Number of observations.
    pub fn len(&self) -> usize {
        match self {
            Block::Numeric(b) => b.len(),
            Block::Categorical(b) => b.len(),
        }
    }

    /// True when the block holds no observations.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Every patient with at least one observation.
    pub fn all_patients(&self) -> &RoaringBitmap {
        match self {
            Block::Numeric(b) => b.all_patients(),
            Block::Categorical(b) => b.all_patients(),
        }
    }

    /// Approximate heap bytes the decoded block holds, for a byte-weighted cache.
    pub fn heap_size(&self) -> usize {
        size_of::<Block>()
            + match self {
                Block::Numeric(b) => b.heap_size(),
                Block::Categorical(b) => b.heap_size(),
            }
    }
}

/// Associated data bound into a block: the store id, the concept path's UTF-8 bytes, and
/// the format version ([`FORMAT_VERSION`]) as a little-endian `u16`. Encrypted blocks
/// authenticate it; plain blocks fold it into their checksum.
pub fn block_aad(store_id: &StoreId, concept_path: &str) -> Vec<u8> {
    versioned_block_aad(store_id, concept_path, FORMAT_VERSION)
}

fn versioned_block_aad(store_id: &StoreId, concept_path: &str, version: u16) -> Vec<u8> {
    let mut aad = Vec::with_capacity(store_id.as_bytes().len() + concept_path.len() + 2);
    aad.extend_from_slice(store_id.as_bytes());
    aad.extend_from_slice(concept_path.as_bytes());
    aad.extend_from_slice(&version.to_le_bytes());
    aad
}

fn plain_checksum(aad: &[u8], body: &[u8]) -> u64 {
    let mut hasher = Xxh3Default::new();
    hasher.update(aad);
    hasher.update(body);
    hasher.digest()
}

/// Seals block payloads into their stored form and opens them again, for one store.
///
/// Stored form under [`Protection::Encrypted`]:
/// `ivLength (i32 BE) || iv(12) || AES-GCM(body) || tag(16)` with [`block_aad`] as
/// associated data. Under [`Protection::Plain`]: `body || xxh3_64(block_aad || body)`
/// (`u64` LE), which catches accidental damage and misplaced blocks but is not
/// authentication. The body is `zstd(payload)` when compression is on and the raw payload
/// otherwise. The codec's protection comes from the caller, never from a file header, so
/// an encrypted codec never accepts a plain block.
#[derive(Clone, Debug)]
pub struct BlockCodec {
    protection: Protection,
    store_id: StoreId,
    compressed: bool,
    level: i32,
    format_version: u16,
}

impl BlockCodec {
    /// A codec for the store `store_id`, sealing with `protection` and compressing when
    /// `compressed` is true, at zstd's default level.
    pub fn new(protection: Protection, store_id: StoreId, compressed: bool) -> Self {
        BlockCodec {
            protection,
            store_id,
            compressed,
            level: zstd::DEFAULT_COMPRESSION_LEVEL,
            format_version: FORMAT_VERSION,
        }
    }

    /// A codec that only opens blocks of a format 1 store (Rust HPDS v1 to v3), whose
    /// associated data binds format version 1 and whose payloads carry no partition prefix.
    /// It refuses to seal: format 1 is read only, for `hpds-etl convert`.
    pub fn legacy(protection: Protection, store_id: StoreId, compressed: bool) -> Self {
        BlockCodec {
            format_version: LEGACY_FORMAT_VERSION,
            ..BlockCodec::new(protection, store_id, compressed)
        }
    }

    /// The format version whose blocks this codec seals and opens.
    pub fn format_version(&self) -> u16 {
        self.format_version
    }

    /// Sets the zstd level used by [`BlockCodec::seal`].
    pub fn with_compression_level(mut self, level: i32) -> Self {
        self.level = level;
        self
    }

    /// True when blocks are encrypted.
    pub fn is_encrypted(&self) -> bool {
        self.protection.is_encrypted()
    }

    /// True when blocks are zstd-compressed.
    pub fn is_compressed(&self) -> bool {
        self.compressed
    }

    /// The store whose blocks this codec seals and opens.
    pub fn store_id(&self) -> StoreId {
        self.store_id
    }

    /// Converts a payload into its stored form for `concept_path`.
    ///
    /// # Errors
    /// [`FormatError::Compression`] when zstd fails, and the errors of
    /// [`crate::crypto::seal_framed`].
    pub fn seal(&self, concept_path: &str, payload: &[u8]) -> Result<Vec<u8>> {
        if self.format_version != FORMAT_VERSION {
            return Err(FormatError::InvalidInput(format!(
                "format {} is read only; blocks are written in format {FORMAT_VERSION}",
                self.format_version
            )));
        }
        let compressed;
        let body = if self.compressed {
            compressed = zstd::bulk::compress(payload, self.level)
                .map_err(|e| FormatError::Compression(e.to_string()))?;
            &compressed[..]
        } else {
            payload
        };
        let aad = versioned_block_aad(&self.store_id, concept_path, self.format_version);
        match &self.protection {
            Protection::Encrypted(key) => seal_framed(key, body, &aad),
            Protection::Plain => {
                let mut out = Vec::with_capacity(body.len() + 8);
                out.extend_from_slice(body);
                out.extend_from_slice(&plain_checksum(&aad, body).to_le_bytes());
                Ok(out)
            }
        }
    }

    /// Verifies and decodes a stored block into an aligned payload buffer of at most
    /// `max_payload_len` bytes; pass [`crate::ConceptMeta::max_payload_len`].
    ///
    /// # Errors
    /// [`FormatError::Checksum`] or [`FormatError::Authentication`] when the stored bytes
    /// are corrupt, belong to a different concept or store, are plain under an encrypted
    /// codec, or the key is wrong; [`FormatError::PayloadTooLarge`] when the payload would
    /// exceed `max_payload_len`, checked before allocating; [`FormatError::Compression`]
    /// when the verified body does not decompress.
    pub fn open_payload(
        &self,
        concept_path: &str,
        stored: &[u8],
        max_payload_len: u64,
    ) -> Result<AlignedBuf> {
        let aad = versioned_block_aad(&self.store_id, concept_path, self.format_version);
        let decrypted;
        let body = match &self.protection {
            Protection::Encrypted(key) => {
                decrypted = open_framed(key, stored, &aad)?;
                &decrypted[..]
            }
            Protection::Plain => {
                let Some((body, checksum)) = stored.split_last_chunk::<8>() else {
                    return Err(FormatError::Truncated("block checksum"));
                };
                if plain_checksum(&aad, body) != u64::from_le_bytes(*checksum) {
                    return Err(FormatError::Checksum("block"));
                }
                body
            }
        };
        if self.compressed {
            zstd_decompress_aligned(body, max_payload_len, "block")
        } else if body.len() as u64 > max_payload_len {
            Err(FormatError::PayloadTooLarge {
                what: "block",
                declared: body.len() as u64,
                limit: max_payload_len,
            })
        } else {
            Ok(AlignedBuf::copy_from(body))
        }
    }

    /// Verifies, decodes and validates a stored block, with [`Block::decode_legacy`] for a
    /// [`BlockCodec::legacy`] codec. The block is checked on its own, not against the
    /// store's partitions ([`Block::check_membership`]).
    ///
    /// # Errors
    /// The errors of [`BlockCodec::open_payload`] and [`Block::decode`].
    pub fn open(&self, concept_path: &str, stored: &[u8], max_payload_len: u64) -> Result<Block> {
        let payload = self.open_payload(concept_path, stored, max_payload_len)?;
        if self.format_version == LEGACY_FORMAT_VERSION {
            Block::decode_legacy(payload)
        } else {
            Block::decode(payload)
        }
    }
}

/// Decompresses one zstd frame into an aligned buffer, refusing before allocation a frame
/// whose header declares more than `limit` bytes or does not declare its size.
pub(crate) fn zstd_decompress_aligned(
    src: &[u8],
    limit: u64,
    what: &'static str,
) -> Result<AlignedBuf> {
    let size = zstd::zstd_safe::get_frame_content_size(src)
        .map_err(|_| FormatError::Compression("unreadable frame header".into()))?
        .ok_or_else(|| FormatError::Compression("frame does not record its content size".into()))?;
    if size > limit {
        return Err(FormatError::PayloadTooLarge {
            what,
            declared: size,
            limit,
        });
    }
    let size = usize::try_from(size).map_err(|_| FormatError::PayloadTooLarge {
        what,
        declared: size,
        limit,
    })?;
    let mut out = AlignedBuf::zeroed(size);
    let written = zstd::bulk::decompress_to_buffer(src, out.as_bytes_mut())
        .map_err(|e| FormatError::Compression(e.to_string()))?;
    if written != out.len() {
        return Err(FormatError::Compression(format!(
            "frame decoded to {written} bytes, header says {size}"
        )));
    }
    Ok(out)
}
