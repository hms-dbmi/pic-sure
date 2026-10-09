//! Per-concept block payloads and the codec that seals them for `pheno.blocks`.

use std::cmp::Ordering;
use std::ops::Range;

use bytemuck::Pod;
use roaring::RoaringBitmap;
use xxhash_rust::xxh3::xxh3_64;

use crate::aligned::AlignedBuf;
use crate::crypto::{CryptoKey, decrypt_java_block_with_aad, encrypt_java_block_with_aad};
use crate::error::{FormatError, Result};
use crate::parity::{canonical_nan, java_double_cmp, java_string_cmp};
use crate::{FORMAT_VERSION, NO_TIMESTAMP};

const KIND_NUMERIC: u8 = 0;
const KIND_CATEGORICAL: u8 = 1;
const NUMERIC_HEADER_LEN: usize = 8;
const CATEGORICAL_HEADER_LEN: usize = 16;
const MAX_DECODED_LEN: u64 = 1 << 40;

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

/// Sorts `observations` and encodes them as a numeric block payload.
///
/// The payload holds `values`, `patients` and `timestamps` sorted by (value in Java
/// `Double.compareTo` order, patient, timestamp), then a `by_key` permutation into those
/// arrays ordered by (patient, timestamp, position). NaN values are canonicalized. The
/// timestamp array is omitted when every timestamp is [`NO_TIMESTAMP`].
///
/// # Errors
/// [`FormatError::InvalidInput`] when there are more than `u32::MAX` observations.
pub fn encode_numeric_payload(observations: &mut [NumericObservation]) -> Result<Vec<u8>> {
    let n = observation_count(observations.len())?;
    for o in observations.iter_mut() {
        o.value = canonical_nan(o.value);
    }
    observations.sort_unstable_by(|a, b| {
        java_double_cmp(a.value, b.value)
            .then(a.patient.cmp(&b.patient))
            .then(a.timestamp.cmp(&b.timestamp))
    });
    let has_timestamp = observations.iter().any(|o| o.timestamp != NO_TIMESTAMP);
    let mut by_key: Vec<u32> = (0..n).collect();
    by_key.sort_unstable_by_key(|&i| {
        let o = &observations[i as usize];
        (o.patient, o.timestamp, i)
    });

    let len = observations.len();
    let mut buf = Vec::with_capacity(NUMERIC_HEADER_LEN + len * 32);
    buf.extend_from_slice(&n.to_le_bytes());
    buf.extend_from_slice(&[u8::from(has_timestamp), KIND_NUMERIC, 0, 0]);
    let values: Vec<f64> = observations.iter().map(|o| o.value).collect();
    put_array(&mut buf, &values);
    drop(values);
    let patients: Vec<u32> = observations.iter().map(|o| o.patient).collect();
    put_array(&mut buf, &patients);
    drop(patients);
    if has_timestamp {
        let timestamps: Vec<i64> = observations.iter().map(|o| o.timestamp).collect();
        put_array(&mut buf, &timestamps);
    }
    put_array(&mut buf, &by_key);
    Ok(buf)
}

/// Encodes categorical observations as a categorical block payload.
///
/// The dictionary holds the distinct values sorted in Java `String.compareTo` order. Each
/// dictionary entry gets one roaring bitmap of the patients that have it. The `by_key`
/// arrays (`patients`, `value_index`, `timestamps`) are sorted by (patient, timestamp,
/// value index). The timestamp array is omitted when every timestamp is [`NO_TIMESTAMP`].
///
/// # Errors
/// [`FormatError::InvalidInput`] when there are more than `u32::MAX` observations.
pub fn encode_categorical_payload(observations: &[CategoricalObservation]) -> Result<Vec<u8>> {
    let n = observation_count(observations.len())?;
    let mut order: Vec<u32> = (0..n).collect();
    order.sort_unstable_by(|&a, &b| {
        java_string_cmp(
            &observations[a as usize].value,
            &observations[b as usize].value,
        )
    });
    let mut dictionary: Vec<&str> = Vec::new();
    let mut value_index = vec![0u32; observations.len()];
    for &i in &order {
        let value = observations[i as usize].value.as_str();
        if dictionary.last().is_none_or(|last| *last != value) {
            dictionary.push(value);
        }
        value_index[i as usize] = (dictionary.len() - 1) as u32;
    }
    drop(order);
    let k = dictionary.len() as u32;

    let mut bitmaps = vec![RoaringBitmap::new(); dictionary.len()];
    for (o, &v) in observations.iter().zip(&value_index) {
        bitmaps[v as usize].insert(o.patient);
    }
    let has_timestamp = observations.iter().any(|o| o.timestamp != NO_TIMESTAMP);
    let mut by_key: Vec<u32> = (0..n).collect();
    by_key.sort_unstable_by_key(|&i| {
        let o = &observations[i as usize];
        (o.patient, o.timestamp, value_index[i as usize])
    });

    let mut buf = Vec::new();
    buf.extend_from_slice(&n.to_le_bytes());
    buf.extend_from_slice(&[u8::from(has_timestamp), KIND_CATEGORICAL, 0, 0]);
    buf.extend_from_slice(&k.to_le_bytes());
    buf.extend_from_slice(&0u32.to_le_bytes());

    let mut string_offsets = Vec::with_capacity(dictionary.len() + 1);
    let mut string_bytes = Vec::new();
    string_offsets.push(0u64);
    for value in &dictionary {
        string_bytes.extend_from_slice(value.as_bytes());
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

    let patients: Vec<u32> = by_key
        .iter()
        .map(|&i| observations[i as usize].patient)
        .collect();
    put_array(&mut buf, &patients);
    let values: Vec<u32> = by_key.iter().map(|&i| value_index[i as usize]).collect();
    put_array(&mut buf, &values);
    if has_timestamp {
        let timestamps: Vec<i64> = by_key
            .iter()
            .map(|&i| observations[i as usize].timestamp)
            .collect();
        put_array(&mut buf, &timestamps);
    }
    Ok(buf)
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

/// A decoded numeric block. The accessors are zero-copy views over one aligned buffer.
#[derive(Clone, Debug)]
pub struct NumericBlock {
    buf: AlignedBuf,
    len: usize,
    has_timestamp: bool,
    values: Range<usize>,
    patients: Range<usize>,
    timestamps: Option<Range<usize>>,
    by_key: Range<usize>,
}

impl NumericBlock {
    fn decode(buf: AlignedBuf) -> Result<Self> {
        let mut c = Cursor {
            bytes: buf.as_bytes(),
            pos: 0,
            what: "numeric block",
        };
        let header = c.take(NUMERIC_HEADER_LEN)?;
        let len = c.u32_at(header.start)? as usize;
        let has_timestamp = check_flag(c.bytes[header.start + 4], c.what)?;
        let values = c.array::<f64>(len)?;
        let patients = c.array::<u32>(len)?;
        let timestamps = if has_timestamp {
            Some(c.array::<i64>(len)?)
        } else {
            None
        };
        let by_key = c.array::<u32>(len)?;
        c.finish()?;
        let block = NumericBlock {
            len,
            has_timestamp,
            values,
            patients,
            timestamps,
            by_key,
            buf,
        };
        if block.by_key().iter().any(|&i| i as usize >= len) {
            return Err(FormatError::corrupt(
                "numeric block",
                "by_key index out of range",
            ));
        }
        Ok(block)
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

    /// Values in Java `Double.compareTo` order.
    pub fn values(&self) -> &[f64] {
        view(&self.buf, &self.values)
    }

    /// Patient of each entry of [`NumericBlock::values`].
    pub fn patients(&self) -> &[u32] {
        view(&self.buf, &self.patients)
    }

    /// Timestamp of each entry of [`NumericBlock::values`], or `None` when the block has no
    /// timestamps.
    pub fn timestamps(&self) -> Option<&[i64]> {
        self.timestamps.as_ref().map(|r| view(&self.buf, r))
    }

    /// Timestamp of entry `index` in value order, [`NO_TIMESTAMP`] when the block has none
    /// or `index` is out of range.
    pub fn timestamp_at(&self, index: usize) -> i64 {
        self.timestamps()
            .and_then(|t| t.get(index).copied())
            .unwrap_or(NO_TIMESTAMP)
    }

    /// Indexes into the value-ordered arrays, ordered by (patient, timestamp).
    pub fn by_key(&self) -> &[u32] {
        view(&self.buf, &self.by_key)
    }

    /// The range of value-ordered entries with `min <= value <= max` in Java
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
}

/// A decoded categorical block. The array accessors are zero-copy views over one aligned
/// buffer.
#[derive(Clone, Debug)]
pub struct CategoricalBlock {
    buf: AlignedBuf,
    len: usize,
    dictionary_len: usize,
    has_timestamp: bool,
    string_offsets: Range<usize>,
    string_bytes: Range<usize>,
    bitmap_offsets: Range<usize>,
    bitmap_bytes: Range<usize>,
    patients: Range<usize>,
    value_indices: Range<usize>,
    timestamps: Option<Range<usize>>,
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
    fn decode(buf: AlignedBuf) -> Result<Self> {
        let mut c = Cursor {
            bytes: buf.as_bytes(),
            pos: 0,
            what: "categorical block",
        };
        let header = c.take(CATEGORICAL_HEADER_LEN)?;
        let len = c.u32_at(header.start)? as usize;
        let has_timestamp = check_flag(c.bytes[header.start + 4], c.what)?;
        let dictionary_len = c.u32_at(header.start + 8)? as usize;
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
        c.finish()?;

        let block = CategoricalBlock {
            len,
            dictionary_len,
            has_timestamp,
            string_offsets,
            string_bytes,
            bitmap_offsets,
            bitmap_bytes,
            patients,
            value_indices,
            timestamps,
            buf,
        };
        let strings = view::<u8>(&block.buf, &block.string_bytes);
        let offsets = view::<u64>(&block.buf, &block.string_offsets);
        check_offsets(offsets, strings.len(), "categorical dictionary")?;
        for w in offsets.windows(2) {
            std::str::from_utf8(&strings[w[0] as usize..w[1] as usize])
                .map_err(|e| FormatError::corrupt("categorical dictionary", e.to_string()))?;
        }
        check_offsets(
            view::<u64>(&block.buf, &block.bitmap_offsets),
            block.bitmap_bytes.len(),
            "categorical bitmaps",
        )?;
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
        Ok(block)
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
        self.dictionary_len
    }

    /// Dictionary entry `index`, or `None` when out of range.
    pub fn dictionary_value(&self, index: usize) -> Option<&str> {
        let offsets = view::<u64>(&self.buf, &self.string_offsets);
        let start = usize::try_from(*offsets.get(index)?).ok()?;
        let end = usize::try_from(*offsets.get(index + 1)?).ok()?;
        let bytes = view::<u8>(&self.buf, &self.string_bytes).get(start..end)?;
        std::str::from_utf8(bytes).ok()
    }

    /// Dictionary entries in Java `String.compareTo` order.
    pub fn dictionary(&self) -> impl Iterator<Item = &str> + '_ {
        (0..self.dictionary_len).map(|i| self.dictionary_value(i).unwrap_or_default())
    }

    /// Dictionary index of `value`, found by binary search in Java string order.
    pub fn find_value(&self, value: &str) -> Option<usize> {
        let (mut lo, mut hi) = (0usize, self.dictionary_len);
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

    /// Serialized roaring bitmap of the patients with dictionary entry `index`.
    pub fn bitmap_bytes(&self, index: usize) -> Option<&[u8]> {
        let offsets = view::<u64>(&self.buf, &self.bitmap_offsets);
        let start = usize::try_from(*offsets.get(index)?).ok()?;
        let end = usize::try_from(*offsets.get(index + 1)?).ok()?;
        view::<u8>(&self.buf, &self.bitmap_bytes).get(start..end)
    }

    /// Patients with dictionary entry `index`.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when `index` is out of range, [`FormatError::Corrupt`]
    /// when the stored bitmap does not deserialize.
    pub fn bitmap(&self, index: usize) -> Result<RoaringBitmap> {
        let bytes = self.bitmap_bytes(index).ok_or_else(|| {
            FormatError::InvalidInput(format!("dictionary index {index} out of range"))
        })?;
        RoaringBitmap::deserialize_from(bytes)
            .map_err(|e| FormatError::corrupt("categorical bitmap", e.to_string()))
    }

    /// Union of every dictionary entry's bitmap: all patients with any value.
    ///
    /// # Errors
    /// As [`CategoricalBlock::bitmap`].
    pub fn all_patients(&self) -> Result<RoaringBitmap> {
        let mut all = RoaringBitmap::new();
        for i in 0..self.dictionary_len {
            all |= self.bitmap(i)?;
        }
        Ok(all)
    }

    /// Patient of each observation, ordered by (patient, timestamp).
    pub fn patients(&self) -> &[u32] {
        view(&self.buf, &self.patients)
    }

    /// Dictionary index of each observation, parallel to [`CategoricalBlock::patients`].
    pub fn value_indices(&self) -> &[u32] {
        view(&self.buf, &self.value_indices)
    }

    /// Timestamp of each observation, parallel to [`CategoricalBlock::patients`], or `None`
    /// when the block has no timestamps.
    pub fn timestamps(&self) -> Option<&[i64]> {
        self.timestamps.as_ref().map(|r| view(&self.buf, r))
    }

    /// Timestamp of observation `index`, [`NO_TIMESTAMP`] when the block has none or
    /// `index` is out of range.
    pub fn timestamp_at(&self, index: usize) -> i64 {
        self.timestamps()
            .and_then(|t| t.get(index).copied())
            .unwrap_or(NO_TIMESTAMP)
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
    /// Validates a decoded payload and wraps it in typed views.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`] when the payload does not
    /// match either layout.
    pub fn decode(payload: AlignedBuf) -> Result<Self> {
        match payload.as_bytes().get(5) {
            Some(&KIND_NUMERIC) => NumericBlock::decode(payload).map(Block::Numeric),
            Some(&KIND_CATEGORICAL) => CategoricalBlock::decode(payload).map(Block::Categorical),
            Some(other) => Err(FormatError::corrupt(
                "block",
                format!("unknown kind {other}"),
            )),
            None => Err(FormatError::Truncated("block header")),
        }
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
}

/// Associated data bound into an encrypted block: the concept path's UTF-8 bytes followed
/// by the format version as a little-endian `u16`.
pub fn block_aad(concept_path: &str) -> Vec<u8> {
    let mut aad = Vec::with_capacity(concept_path.len() + 2);
    aad.extend_from_slice(concept_path.as_bytes());
    aad.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    aad
}

/// Seals block payloads into their stored form and opens them again.
///
/// Stored form, encrypted: `ivLength (i32 BE) || iv(12) || AES-GCM(body) || tag(16)` with
/// [`block_aad`] as associated data. Unencrypted: `body || xxh3_64(body) (u64 LE)`. The
/// body is `zstd(payload)` when compression is on and the raw payload otherwise.
#[derive(Clone, Debug)]
pub struct BlockCodec {
    key: Option<CryptoKey>,
    compressed: bool,
    level: i32,
}

impl BlockCodec {
    /// A codec that encrypts when `key` is set and compresses when `compressed` is true,
    /// at zstd's default level.
    pub fn new(key: Option<CryptoKey>, compressed: bool) -> Self {
        BlockCodec {
            key,
            compressed,
            level: zstd::DEFAULT_COMPRESSION_LEVEL,
        }
    }

    /// Sets the zstd level used by [`BlockCodec::seal`].
    pub fn with_compression_level(mut self, level: i32) -> Self {
        self.level = level;
        self
    }

    /// True when blocks are encrypted.
    pub fn is_encrypted(&self) -> bool {
        self.key.is_some()
    }

    /// True when blocks are zstd-compressed.
    pub fn is_compressed(&self) -> bool {
        self.compressed
    }

    /// Converts a payload into its stored form for `concept_path`.
    ///
    /// # Errors
    /// [`FormatError::Compression`] when zstd fails, and the errors of
    /// [`crate::crypto::encrypt_java_block_with_aad`].
    pub fn seal(&self, concept_path: &str, payload: &[u8]) -> Result<Vec<u8>> {
        let compressed;
        let body = if self.compressed {
            compressed = zstd::bulk::compress(payload, self.level)
                .map_err(|e| FormatError::Compression(e.to_string()))?;
            &compressed[..]
        } else {
            payload
        };
        match &self.key {
            Some(key) => encrypt_java_block_with_aad(key, body, &block_aad(concept_path)),
            None => {
                let mut out = Vec::with_capacity(body.len() + 8);
                out.extend_from_slice(body);
                out.extend_from_slice(&xxh3_64(body).to_le_bytes());
                Ok(out)
            }
        }
    }

    /// Verifies and decodes a stored block into an aligned payload buffer.
    ///
    /// # Errors
    /// [`FormatError::Checksum`] or [`FormatError::Authentication`] when the stored bytes
    /// are corrupt, belong to a different concept, or the key is wrong;
    /// [`FormatError::Compression`] when the verified body does not decompress.
    pub fn open_payload(&self, concept_path: &str, stored: &[u8]) -> Result<AlignedBuf> {
        let decrypted;
        let body = match &self.key {
            Some(key) => {
                decrypted = decrypt_java_block_with_aad(key, stored, &block_aad(concept_path))?;
                &decrypted[..]
            }
            None => {
                let Some((body, checksum)) = stored.split_last_chunk::<8>() else {
                    return Err(FormatError::Truncated("block checksum"));
                };
                if xxh3_64(body) != u64::from_le_bytes(*checksum) {
                    return Err(FormatError::Checksum("block"));
                }
                body
            }
        };
        if self.compressed {
            zstd_decompress_aligned(body)
        } else {
            Ok(AlignedBuf::copy_from(body))
        }
    }

    /// Verifies, decodes and validates a stored block.
    ///
    /// # Errors
    /// The errors of [`BlockCodec::open_payload`] and [`Block::decode`].
    pub fn open(&self, concept_path: &str, stored: &[u8]) -> Result<Block> {
        Block::decode(self.open_payload(concept_path, stored)?)
    }
}

pub(crate) fn zstd_decompress_aligned(src: &[u8]) -> Result<AlignedBuf> {
    let size = zstd::zstd_safe::get_frame_content_size(src)
        .map_err(|_| FormatError::Compression("unreadable frame header".into()))?
        .ok_or_else(|| FormatError::Compression("frame does not record its content size".into()))?;
    if size > MAX_DECODED_LEN {
        return Err(FormatError::Compression(format!(
            "frame claims {size} bytes"
        )));
    }
    let mut out = AlignedBuf::zeroed(size as usize);
    let written = zstd::bulk::decompress_to_buffer(src, out.as_bytes_mut())
        .map_err(|e| FormatError::Compression(e.to_string()))?;
    if written != out.len() {
        return Err(FormatError::Compression(format!(
            "frame decoded to {written} bytes, header says {size}"
        )));
    }
    Ok(out)
}
