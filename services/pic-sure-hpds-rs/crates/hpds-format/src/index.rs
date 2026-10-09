//! The `pheno.index` file: header, patient set, and the sorted concept table.

use std::cmp::Ordering;
use std::path::Path;

use roaring::RoaringBitmap;
use xxhash_rust::xxh3::xxh3_64;

use crate::block::{BlockCodec, zstd_decompress_aligned};
use crate::crypto::{CryptoKey, decrypt_java_block_with_aad, encrypt_java_block_with_aad};
use crate::error::{FormatError, Result};
use crate::parity::java_string_cmp;
use crate::{FORMAT_VERSION, INDEX_MAGIC};

const FLAG_ENCRYPTED: u16 = 1;
const FLAG_COMPRESSED: u16 = 1 << 1;
const KNOWN_FLAGS: u16 = FLAG_ENCRYPTED | FLAG_COMPRESSED;

const META_HAS_TIMESTAMP: u8 = 1;
const META_MIN: u8 = 1 << 1;
const META_MAX: u8 = 1 << 2;
const META_TIMESTAMP_MIN: u8 = 1 << 3;
const META_TIMESTAMP_MAX: u8 = 1 << 4;
const META_CATEGORY_VALUES: u8 = 1 << 5;
const KNOWN_META: u8 = (1 << 6) - 1;

/// Whether a concept holds numbers or category strings.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum ConceptType {
    /// Values are doubles.
    Numeric,
    /// Values are strings.
    Categorical,
}

/// Where a concept's block sits in `pheno.blocks`.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct BlockLocation {
    /// Byte offset of the stored block from the start of `pheno.blocks`.
    pub offset: u64,
    /// Length of the stored block in bytes.
    pub length: u64,
}

/// Per-concept metadata: every `ColumnMeta` field the service and `columnMeta.csv` use.
#[derive(Clone, Debug, PartialEq)]
pub struct ConceptMeta {
    /// Concept path, the key of the concept table. `ColumnMeta.name`.
    pub name: String,
    /// Numeric or categorical. `ColumnMeta.categorical`.
    pub concept_type: ConceptType,
    /// Distinct values in Java string order; `None` where Java holds `null`, which is
    /// every numeric concept. `ColumnMeta.categoryValues`.
    pub category_values: Option<Vec<String>>,
    /// Smallest value; `None` where Java holds `null`. `ColumnMeta.min`.
    pub min: Option<f64>,
    /// Largest value; `None` where Java holds `null`. `ColumnMeta.max`.
    pub max: Option<f64>,
    /// Number of observations. `ColumnMeta.observationCount`.
    pub observation_count: u32,
    /// Number of distinct patients. `ColumnMeta.patientCount`.
    pub patient_count: u32,
    /// True when any observation has a timestamp. `ColumnMeta.hasTimestamp`.
    pub has_timestamp: bool,
    /// Earliest timestamp in epoch milliseconds. `ColumnMeta.timestampMin`.
    pub timestamp_min: Option<i64>,
    /// Latest timestamp in epoch milliseconds. `ColumnMeta.timestampMax`.
    pub timestamp_max: Option<i64>,
    /// Kept only for `columnMeta.csv` parity. `ColumnMeta.widthInBytes`.
    pub width_in_bytes: i32,
    /// Kept only for `columnMeta.csv` parity. `ColumnMeta.columnOffset`.
    pub column_offset: i32,
    /// Location of the concept's block. Stands in for `allObservationsOffset` and
    /// `allObservationsLength`.
    pub block: BlockLocation,
}

impl ConceptMeta {
    /// True for categorical concepts.
    pub fn is_categorical(&self) -> bool {
        self.concept_type == ConceptType::Categorical
    }
}

/// The plain header at the start of `pheno.index`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IndexHeader {
    /// Format version the file was written with.
    pub format_version: u16,
    /// True when the index body and every block are AES-GCM encrypted.
    pub encrypted: bool,
    /// True when the index body and every block are zstd-compressed.
    pub compressed: bool,
    /// Name of the key used for encryption, as in `Crypto` named keys. Empty when plain.
    pub key_name: String,
    /// Creation time in epoch milliseconds.
    pub created_at_millis: i64,
    /// Version string of the ETL that wrote the store.
    pub etl_version: String,
}

impl IndexHeader {
    /// The block codec this header describes.
    ///
    /// # Errors
    /// [`FormatError::KeyMismatch`] when the store is encrypted and `key` is `None`.
    pub fn block_codec(&self, key: Option<CryptoKey>) -> Result<BlockCodec> {
        if self.encrypted && key.is_none() {
            return Err(FormatError::KeyMismatch(
                "the store is encrypted and no key was supplied",
            ));
        }
        Ok(BlockCodec::new(
            if self.encrypted { key } else { None },
            self.compressed,
        ))
    }
}

/// The in-memory form of `pheno.index`: header, all patient IDs, and the concept table
/// sorted by path in Java `String.compareTo` order.
#[derive(Clone, Debug, PartialEq)]
pub struct PhenoIndex {
    header: IndexHeader,
    patients: RoaringBitmap,
    concepts: Vec<ConceptMeta>,
}

impl PhenoIndex {
    /// Builds an index, sorting `concepts` by path. The header's format version is set to
    /// [`FORMAT_VERSION`].
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when two concepts share a path.
    pub fn new(
        mut header: IndexHeader,
        patients: RoaringBitmap,
        mut concepts: Vec<ConceptMeta>,
    ) -> Result<Self> {
        header.format_version = FORMAT_VERSION;
        concepts.sort_by(|a, b| java_string_cmp(&a.name, &b.name));
        if let Some(w) = concepts.windows(2).find(|w| w[0].name == w[1].name) {
            return Err(FormatError::InvalidInput(format!(
                "duplicate concept path {:?}",
                w[0].name
            )));
        }
        Ok(PhenoIndex {
            header,
            patients,
            concepts,
        })
    }

    /// The file header.
    pub fn header(&self) -> &IndexHeader {
        &self.header
    }

    /// Every patient ID in the store.
    pub fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// All concepts in Java string order of their paths.
    pub fn concepts(&self) -> &[ConceptMeta] {
        &self.concepts
    }

    /// Position of `path` in [`PhenoIndex::concepts`], by binary search.
    pub fn position(&self, path: &str) -> Option<usize> {
        self.concepts
            .binary_search_by(|c| java_string_cmp(&c.name, path))
            .ok()
    }

    /// Metadata for the concept at exactly `path`.
    pub fn get(&self, path: &str) -> Option<&ConceptMeta> {
        self.position(path).map(|i| &self.concepts[i])
    }

    /// Every concept whose path starts with `prefix`, the rule of
    /// `PhenotypeMetaStore.loadChildConceptPaths` (`String.startsWith`). The concept at
    /// `prefix` itself is included, and an empty prefix matches every concept. The result
    /// is contiguous in the sorted table.
    pub fn with_prefix(&self, prefix: &str) -> &[ConceptMeta] {
        let start = self
            .concepts
            .partition_point(|c| java_string_cmp(&c.name, prefix) == Ordering::Less);
        let len = self.concepts[start..]
            .iter()
            .take_while(|c| c.name.starts_with(prefix))
            .count();
        &self.concepts[start..start + len]
    }

    /// Serializes the index. `key` must be set exactly when the header says encrypted.
    ///
    /// # Errors
    /// [`FormatError::KeyMismatch`] when `key` disagrees with the header,
    /// [`FormatError::InvalidInput`] when a string or count exceeds its field, and
    /// compression or encryption errors.
    pub fn encode(&self, key: Option<&CryptoKey>) -> Result<Vec<u8>> {
        match (self.header.encrypted, key) {
            (true, None) => {
                return Err(FormatError::KeyMismatch(
                    "the index is encrypted and no key was supplied",
                ));
            }
            (false, Some(_)) => {
                return Err(FormatError::KeyMismatch(
                    "the index is plain but a key was supplied",
                ));
            }
            _ => {}
        }
        let body = self.encode_body()?;
        let body = if self.header.compressed {
            zstd::bulk::compress(&body, zstd::DEFAULT_COMPRESSION_LEVEL)
                .map_err(|e| FormatError::Compression(e.to_string()))?
        } else {
            body
        };
        let mut out = encode_preamble(&self.header)?;
        match key {
            Some(key) => {
                let sealed = encrypt_java_block_with_aad(key, &body, &out)?;
                out.extend_from_slice(&(sealed.len() as u64).to_le_bytes());
                out.extend_from_slice(&sealed);
            }
            None => {
                out.extend_from_slice(&(body.len() as u64).to_le_bytes());
                out.extend_from_slice(&body);
                let checksum = xxh3_64(&out);
                out.extend_from_slice(&checksum.to_le_bytes());
            }
        }
        Ok(out)
    }

    fn encode_body(&self) -> Result<Vec<u8>> {
        let mut out = Vec::new();
        out.extend_from_slice(&(self.patients.serialized_size() as u64).to_le_bytes());
        self.patients.serialize_into(&mut out)?;
        put_u32(&mut out, self.concepts.len(), "concept count")?;
        for c in &self.concepts {
            put_str(&mut out, &c.name)?;
            out.push(match c.concept_type {
                ConceptType::Numeric => 0,
                ConceptType::Categorical => 1,
            });
            let mut flags = 0u8;
            for (set, bit) in [
                (c.has_timestamp, META_HAS_TIMESTAMP),
                (c.min.is_some(), META_MIN),
                (c.max.is_some(), META_MAX),
                (c.timestamp_min.is_some(), META_TIMESTAMP_MIN),
                (c.timestamp_max.is_some(), META_TIMESTAMP_MAX),
                (c.category_values.is_some(), META_CATEGORY_VALUES),
            ] {
                if set {
                    flags |= bit;
                }
            }
            out.push(flags);
            out.extend_from_slice(&c.block.offset.to_le_bytes());
            out.extend_from_slice(&c.block.length.to_le_bytes());
            out.extend_from_slice(&c.observation_count.to_le_bytes());
            out.extend_from_slice(&c.patient_count.to_le_bytes());
            out.extend_from_slice(&c.width_in_bytes.to_le_bytes());
            out.extend_from_slice(&c.column_offset.to_le_bytes());
            out.extend_from_slice(&c.min.unwrap_or(0.0).to_bits().to_le_bytes());
            out.extend_from_slice(&c.max.unwrap_or(0.0).to_bits().to_le_bytes());
            out.extend_from_slice(&c.timestamp_min.unwrap_or(0).to_le_bytes());
            out.extend_from_slice(&c.timestamp_max.unwrap_or(0).to_le_bytes());
            let values = c.category_values.as_deref().unwrap_or_default();
            put_u32(&mut out, values.len(), "category value count")?;
            for v in values {
                put_str(&mut out, v)?;
            }
        }
        Ok(out)
    }

    /// Reads only the plain header of an index file, for example to learn which key name
    /// to load before calling [`PhenoIndex::decode`].
    ///
    /// # Errors
    /// [`FormatError::BadMagic`], [`FormatError::UnsupportedVersion`],
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode_header(bytes: &[u8]) -> Result<IndexHeader> {
        Ok(decode_preamble(bytes)?.0)
    }

    /// Parses and verifies a serialized index.
    ///
    /// # Errors
    /// [`FormatError::KeyMismatch`] when the index is encrypted and `key` is `None`;
    /// [`FormatError::Authentication`] or [`FormatError::Checksum`] when the bytes were
    /// altered or the key is wrong; [`FormatError::Truncated`] or [`FormatError::Corrupt`]
    /// for structural damage, including a concept table that is not strictly sorted.
    pub fn decode(bytes: &[u8], key: Option<&CryptoKey>) -> Result<Self> {
        let (header, preamble_len) = decode_preamble(bytes)?;
        let mut r = Reader::new(&bytes[preamble_len..], "index");
        let stored_len = r.u64()?;
        let stored_len = r.len(stored_len)?;
        let stored = r.bytes(stored_len)?;
        let body_start = preamble_len + 8;
        let opened;
        let body: &[u8] = if header.encrypted {
            r.finish()?;
            let key = key.ok_or(FormatError::KeyMismatch(
                "the index is encrypted and no key was supplied",
            ))?;
            opened = decrypt_java_block_with_aad(key, stored, &bytes[..preamble_len])?;
            &opened
        } else {
            let checksum = r.u64()?;
            r.finish()?;
            if xxh3_64(&bytes[..body_start + stored_len]) != checksum {
                return Err(FormatError::Checksum("index"));
            }
            stored
        };
        let decompressed;
        let body: &[u8] = if header.compressed {
            decompressed = zstd_decompress_aligned(body)?;
            decompressed.as_bytes()
        } else {
            body
        };
        let (patients, concepts) = decode_body(body)?;
        Ok(PhenoIndex {
            header,
            patients,
            concepts,
        })
    }

    /// Serializes the index and writes it to `path`.
    ///
    /// # Errors
    /// The errors of [`PhenoIndex::encode`] and [`FormatError::Io`].
    pub fn write_file(&self, path: impl AsRef<Path>, key: Option<&CryptoKey>) -> Result<()> {
        std::fs::write(path, self.encode(key)?)?;
        Ok(())
    }

    /// Reads and verifies the index at `path`.
    ///
    /// # Errors
    /// The errors of [`PhenoIndex::decode`] and [`FormatError::Io`].
    pub fn read_file(path: impl AsRef<Path>, key: Option<&CryptoKey>) -> Result<Self> {
        Self::decode(&std::fs::read(path)?, key)
    }
}

fn put_u32(out: &mut Vec<u8>, value: usize, what: &str) -> Result<()> {
    let value = u32::try_from(value)
        .map_err(|_| FormatError::InvalidInput(format!("{what} {value} exceeds u32")))?;
    out.extend_from_slice(&value.to_le_bytes());
    Ok(())
}

fn put_str(out: &mut Vec<u8>, s: &str) -> Result<()> {
    put_u32(out, s.len(), "string length")?;
    out.extend_from_slice(s.as_bytes());
    Ok(())
}

fn encode_preamble(header: &IndexHeader) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(INDEX_MAGIC);
    out.extend_from_slice(&FORMAT_VERSION.to_le_bytes());
    let mut flags = 0u16;
    if header.encrypted {
        flags |= FLAG_ENCRYPTED;
    }
    if header.compressed {
        flags |= FLAG_COMPRESSED;
    }
    out.extend_from_slice(&flags.to_le_bytes());
    put_str(&mut out, &header.key_name)?;
    out.extend_from_slice(&header.created_at_millis.to_le_bytes());
    put_str(&mut out, &header.etl_version)?;
    Ok(out)
}

fn decode_preamble(bytes: &[u8]) -> Result<(IndexHeader, usize)> {
    let mut r = Reader::new(bytes, "index header");
    if r.bytes(INDEX_MAGIC.len())? != INDEX_MAGIC {
        return Err(FormatError::BadMagic {
            file: "pheno.index",
        });
    }
    let format_version = r.u16()?;
    if format_version != FORMAT_VERSION {
        return Err(FormatError::UnsupportedVersion(format_version));
    }
    let flags = r.u16()?;
    if flags & !KNOWN_FLAGS != 0 {
        return Err(FormatError::corrupt(
            "index header",
            format!("unknown flags {flags:#06x}"),
        ));
    }
    let key_name = r.string()?;
    let created_at_millis = r.i64()?;
    let etl_version = r.string()?;
    let header = IndexHeader {
        format_version,
        encrypted: flags & FLAG_ENCRYPTED != 0,
        compressed: flags & FLAG_COMPRESSED != 0,
        key_name,
        created_at_millis,
        etl_version,
    };
    Ok((header, r.pos))
}

fn decode_body(body: &[u8]) -> Result<(RoaringBitmap, Vec<ConceptMeta>)> {
    let mut r = Reader::new(body, "index body");
    let bitmap_len = r.u64()?;
    let bitmap_len = r.len(bitmap_len)?;
    let patients = RoaringBitmap::deserialize_from(r.bytes(bitmap_len)?)
        .map_err(|e| FormatError::corrupt("patient bitmap", e.to_string()))?;
    let count = r.u32()? as usize;
    let mut concepts: Vec<ConceptMeta> = Vec::with_capacity(count.min(body.len() / 64));
    for _ in 0..count {
        let name = r.string()?;
        let concept_type = match r.u8()? {
            0 => ConceptType::Numeric,
            1 => ConceptType::Categorical,
            other => {
                return Err(FormatError::corrupt(
                    "concept table",
                    format!("unknown concept type {other}"),
                ));
            }
        };
        let flags = r.u8()?;
        if flags & !KNOWN_META != 0 {
            return Err(FormatError::corrupt(
                "concept table",
                format!("unknown metadata flags {flags:#04x}"),
            ));
        }
        let block = BlockLocation {
            offset: r.u64()?,
            length: r.u64()?,
        };
        let observation_count = r.u32()?;
        let patient_count = r.u32()?;
        let width_in_bytes = r.i32()?;
        let column_offset = r.i32()?;
        let min = f64::from_bits(r.u64()?);
        let max = f64::from_bits(r.u64()?);
        let timestamp_min = r.i64()?;
        let timestamp_max = r.i64()?;
        let value_count = r.u32()? as usize;
        let mut values = Vec::with_capacity(value_count.min(r.remaining() / 4));
        for _ in 0..value_count {
            values.push(r.string()?);
        }
        let has = |bit: u8| flags & bit != 0;
        if !has(META_CATEGORY_VALUES) && !values.is_empty() {
            return Err(FormatError::corrupt(
                "concept table",
                format!("{name:?} has values but no value list"),
            ));
        }
        let meta = ConceptMeta {
            concept_type,
            category_values: has(META_CATEGORY_VALUES).then_some(values),
            min: has(META_MIN).then_some(min),
            max: has(META_MAX).then_some(max),
            observation_count,
            patient_count,
            has_timestamp: has(META_HAS_TIMESTAMP),
            timestamp_min: has(META_TIMESTAMP_MIN).then_some(timestamp_min),
            timestamp_max: has(META_TIMESTAMP_MAX).then_some(timestamp_max),
            width_in_bytes,
            column_offset,
            block,
            name,
        };
        if let Some(prev) = concepts.last()
            && java_string_cmp(&prev.name, &meta.name) != Ordering::Less
        {
            return Err(FormatError::corrupt(
                "concept table",
                format!("{:?} is not sorted after {:?}", meta.name, prev.name),
            ));
        }
        concepts.push(meta);
    }
    r.finish()?;
    Ok((patients, concepts))
}

struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
    what: &'static str,
}

impl<'a> Reader<'a> {
    fn new(bytes: &'a [u8], what: &'static str) -> Self {
        Reader {
            bytes,
            pos: 0,
            what,
        }
    }

    fn remaining(&self) -> usize {
        self.bytes.len() - self.pos
    }

    fn bytes(&mut self, len: usize) -> Result<&'a [u8]> {
        if len > self.remaining() {
            return Err(FormatError::Truncated(self.what));
        }
        let out = &self.bytes[self.pos..self.pos + len];
        self.pos += len;
        Ok(out)
    }

    fn array<const N: usize>(&mut self) -> Result<[u8; N]> {
        let bytes = self.bytes(N)?;
        bytes
            .try_into()
            .map_err(|_| FormatError::Truncated(self.what))
    }

    fn u8(&mut self) -> Result<u8> {
        Ok(self.array::<1>()?[0])
    }

    fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_le_bytes(self.array()?))
    }

    fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_le_bytes(self.array()?))
    }

    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.array()?))
    }

    fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_le_bytes(self.array()?))
    }

    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_le_bytes(self.array()?))
    }

    fn len(&self, value: u64) -> Result<usize> {
        usize::try_from(value)
            .ok()
            .filter(|&len| len <= self.remaining())
            .ok_or(FormatError::Truncated(self.what))
    }

    fn string(&mut self) -> Result<String> {
        let len = self.u32()? as usize;
        let bytes = self.bytes(len)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|e| FormatError::corrupt(self.what, e.to_string()))
    }

    fn finish(&self) -> Result<()> {
        if self.pos == self.bytes.len() {
            Ok(())
        } else {
            Err(FormatError::corrupt(
                self.what,
                format!("{} trailing bytes", self.remaining()),
            ))
        }
    }
}
