//! The `pheno.index` file: header, patient set, and the sorted concept table.

use std::cmp::Ordering;
use std::path::Path;

use roaring::RoaringBitmap;
use xxhash_rust::xxh3::xxh3_64;

use crate::aligned::AlignedBuf;
use crate::block::{
    BlockCodec, MAX_PARTITIONS, PartitionHolder, categorical_payload_bound, numeric_payload_bound,
    zstd_decompress_aligned,
};
use crate::crypto::{open_framed, seal_framed};
use crate::error::{FormatError, Result};
use crate::membership::Membership;
use crate::parity::java_string_cmp;
use crate::protection::{Protection, STORE_ID_LEN, StoreId};
use crate::{FORMAT_VERSION, INDEX_MAGIC, LEGACY_FORMAT_VERSION};

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

const CONCEPT_TAGGED: u8 = 1;
const PARTITION_PATIENTS_INDEXED: u8 = 1;
const LAYOUT_UNPARTITIONED: u8 = 0;
const LAYOUT_PARTITIONED: u8 = 1;
const INDEX_HOLDER_LEN: usize = 10;

/// Largest decompressed index body accepted, checked against the zstd frame header before
/// anything is allocated.
pub const MAX_INDEX_BODY_LEN: u64 = 1 << 32;

const INDEX_FILE: &str = "pheno.index";

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
    /// Concept path, the key of the concept table. Java calls it `ColumnMeta.name`.
    pub path: String,
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
    /// The partitions holding the concept, ascending by partition, with their rows and
    /// patients in its block. Empty in an unpartitioned store.
    pub holders: Vec<PartitionHolder>,
    /// True when the block carries a partition tag on every row, because it holds a row of
    /// a patient in the store's overlap set.
    pub tagged: bool,
}

impl ConceptMeta {
    /// True for categorical concepts.
    pub fn is_categorical(&self) -> bool {
        self.concept_type == ConceptType::Categorical
    }

    /// Upper bound on this concept's decoded block payload, for
    /// [`BlockCodec::open_payload`]. Derived from the observation count, the timestamp
    /// arrays (assumed present), and for categorical concepts the number and UTF-8 length
    /// of the category values, plus a quarter and 4 KiB of slack. A block whose frame
    /// declares more than this does not match its metadata and is refused.
    pub fn max_payload_len(&self) -> u64 {
        let values = self.category_values.as_deref().unwrap_or_default();
        payload_bound(
            self.concept_type,
            self.observation_count,
            values.len() as u64,
            values.iter().map(|v| v.len() as u64).sum(),
            self.holders.len(),
            self.tagged,
        )
    }

    /// The sum of the holders' patient counts: the concept's patient count as Java's
    /// `SummaryColumnMeta.merge` sums it, a patient in two partitions counted twice. For an
    /// unpartitioned store, the patient count.
    pub fn summed_patient_count(&self) -> u64 {
        if self.holders.is_empty() {
            u64::from(self.patient_count)
        } else {
            self.holders.iter().map(|h| u64::from(h.patients)).sum()
        }
    }
}

/// [`ConceptMeta::max_payload_len`] from the counts it reads: the observation count, and
/// for a categorical concept the number of category values and their summed UTF-8 length.
fn payload_bound(
    concept_type: ConceptType,
    observation_count: u32,
    value_count: u64,
    string_bytes: u64,
    holders: usize,
    tagged: bool,
) -> u64 {
    let n = u64::from(observation_count);
    let body = match concept_type {
        ConceptType::Numeric => numeric_payload_bound(n),
        ConceptType::Categorical => categorical_payload_bound(n, value_count, string_bytes),
    };
    let prefix = (16 + 12 * holders as u64).next_multiple_of(8);
    let tags = if tagged {
        (2 * n).next_multiple_of(8)
    } else {
        0
    };
    let exact = body.saturating_add(prefix).saturating_add(tags);
    exact.saturating_add(exact / 4).saturating_add(4096)
}

/// One concept of an [`IndexBody`], parsed in place: the fixed fields of [`ConceptMeta`],
/// with the path and category values borrowed from the index body instead of copied.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ConceptView<'a> {
    /// Concept path. [`ConceptMeta::path`].
    pub path: &'a str,
    /// Numeric or categorical.
    pub concept_type: ConceptType,
    /// The category values, or `None` where [`ConceptMeta::category_values`] is `None`.
    pub category_values: Option<CategoryValues<'a>>,
    /// Smallest value. [`ConceptMeta::min`].
    pub min: Option<f64>,
    /// Largest value. [`ConceptMeta::max`].
    pub max: Option<f64>,
    /// Number of observations.
    pub observation_count: u32,
    /// Number of distinct patients.
    pub patient_count: u32,
    /// True when any observation has a timestamp.
    pub has_timestamp: bool,
    /// Earliest timestamp in epoch milliseconds.
    pub timestamp_min: Option<i64>,
    /// Latest timestamp in epoch milliseconds.
    pub timestamp_max: Option<i64>,
    /// [`ConceptMeta::width_in_bytes`].
    pub width_in_bytes: i32,
    /// [`ConceptMeta::column_offset`].
    pub column_offset: i32,
    /// Location of the concept's block.
    pub block: BlockLocation,
    /// The partitions holding the concept. [`ConceptMeta::holders`].
    pub holders: ConceptHolders<'a>,
    /// [`ConceptMeta::tagged`].
    pub tagged: bool,
}

impl ConceptView<'_> {
    /// True for categorical concepts.
    pub fn is_categorical(&self) -> bool {
        self.concept_type == ConceptType::Categorical
    }

    /// [`ConceptMeta::max_payload_len`] of this concept, without copying its values.
    pub fn max_payload_len(&self) -> u64 {
        let (count, bytes) = self
            .category_values
            .map_or((0, 0), |v| (v.len() as u64, v.string_bytes()));
        payload_bound(
            self.concept_type,
            self.observation_count,
            count,
            bytes,
            self.holders.len(),
            self.tagged,
        )
    }

    /// The concept as an owned [`ConceptMeta`].
    pub fn to_meta(&self) -> ConceptMeta {
        ConceptMeta {
            path: self.path.to_owned(),
            concept_type: self.concept_type,
            category_values: self
                .category_values
                .map(|values| values.iter().map(str::to_owned).collect()),
            min: self.min,
            max: self.max,
            observation_count: self.observation_count,
            patient_count: self.patient_count,
            has_timestamp: self.has_timestamp,
            timestamp_min: self.timestamp_min,
            timestamp_max: self.timestamp_max,
            width_in_bytes: self.width_in_bytes,
            column_offset: self.column_offset,
            block: self.block,
            holders: self.holders.iter().collect(),
            tagged: self.tagged,
        }
    }
}

/// A concept's holders inside an index body, already checked when the concept was parsed.
/// Iterates ascending by partition.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ConceptHolders<'a> {
    bytes: &'a [u8],
}

impl<'a> ConceptHolders<'a> {
    /// The number of holders.
    pub fn len(&self) -> usize {
        self.bytes.len() / INDEX_HOLDER_LEN
    }

    /// True when there are no holders, as in an unpartitioned store.
    pub fn is_empty(&self) -> bool {
        self.bytes.is_empty()
    }

    /// The holders, ascending by partition.
    pub fn iter(&self) -> impl Iterator<Item = PartitionHolder> + 'a {
        self.bytes
            .as_chunks::<INDEX_HOLDER_LEN>()
            .0
            .iter()
            .map(|h| PartitionHolder {
                partition: u16::from_le_bytes([h[0], h[1]]),
                observations: u32::from_le_bytes([h[2], h[3], h[4], h[5]]),
                patients: u32::from_le_bytes([h[6], h[7], h[8], h[9]]),
            })
    }
}

/// A consent partition of a partitioned store, as its index records it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IndexPartition {
    /// The partition name, the consent value that selects it.
    pub name: String,
    /// True when every patient with a row in the partition is in its patient set. Always
    /// true for a store loaded from CSV; a migrated or converted store keeps the patient set
    /// Java exported, which can miss a patient its rows hold.
    pub patients_indexed: bool,
    /// The partition's patient set, as Java's per-partition patient ids.
    pub patients: RoaringBitmap,
}

/// The partition table of a store: its partitions in Java `String.compareTo` order of the
/// names, and the overlap set `O`. Empty for an unpartitioned store.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct PartitionTable {
    /// The partitions, strictly ascending by name in Java string order.
    pub partitions: Vec<IndexPartition>,
    /// The patients whose partition the patient alone does not decide: those in two or more
    /// partitions' sets, and those with a row in a partition whose set lacks them. Their
    /// rows carry partition tags. A patient with rows but in no partition's set (a migrated
    /// partition whose exported patient list misses them) is here and not in the store's
    /// patients, which stay the union of the partition sets, as Java's patient ids do.
    pub overlap: RoaringBitmap,
}

impl PartitionTable {
    /// True for an unpartitioned store's table.
    pub fn is_empty(&self) -> bool {
        self.partitions.is_empty()
    }

    /// The union of the partitions' patient sets.
    pub fn patients(&self) -> RoaringBitmap {
        let mut union = RoaringBitmap::new();
        for partition in &self.partitions {
            union |= &partition.patients;
        }
        union
    }

    /// The routing of patients to partitions this table gives ([`Membership::new`]).
    pub fn membership(&self) -> Membership {
        if self.partitions.is_empty() {
            return Membership::unpartitioned();
        }
        let sets: Vec<&RoaringBitmap> = self.partitions.iter().map(|p| &p.patients).collect();
        Membership::new(&sets, &self.overlap)
    }

    /// The position of the partition named `name`, by binary search in Java string order.
    pub fn position(&self, name: &str) -> Option<usize> {
        self.partitions
            .binary_search_by(|p| java_string_cmp(&p.name, name))
            .ok()
    }

    /// Checks the table against the store's `patients`: at most [`MAX_PARTITIONS`]
    /// partitions, names strictly ascending in Java string order, and, for a partitioned
    /// store, the partition sets unioning to `patients`; an unpartitioned store has an empty
    /// overlap set. The overlap set may hold patients outside `patients`: those with rows in
    /// a partition but in no partition's set.
    fn check(&self, patients: &RoaringBitmap) -> Result<()> {
        let what = "partition table";
        if self.partitions.len() > MAX_PARTITIONS {
            return Err(FormatError::corrupt(
                what,
                format!(
                    "{} partitions exceed {MAX_PARTITIONS}",
                    self.partitions.len()
                ),
            ));
        }
        if let Some(w) = self
            .partitions
            .windows(2)
            .find(|w| java_string_cmp(&w[0].name, &w[1].name) != Ordering::Less)
        {
            return Err(FormatError::corrupt(
                what,
                format!("{:?} is not sorted after {:?}", w[1].name, w[0].name),
            ));
        }
        if self.partitions.is_empty() {
            if !self.overlap.is_empty() {
                return Err(FormatError::corrupt(
                    what,
                    "an unpartitioned store has an overlap set",
                ));
            }
        } else if &self.patients() != patients {
            return Err(FormatError::corrupt(
                what,
                "the partitions' patient sets do not union to the store's patients",
            ));
        }
        Ok(())
    }
}

/// A concept's category values inside an index body, already checked to be valid UTF-8
/// when the concept was parsed. Iterates in stored order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct CategoryValues<'a> {
    bytes: &'a [u8],
    count: usize,
    string_bytes: u64,
}

impl<'a> CategoryValues<'a> {
    /// The number of values.
    pub fn len(&self) -> usize {
        self.count
    }

    /// True when there are no values.
    pub fn is_empty(&self) -> bool {
        self.count == 0
    }

    /// The summed UTF-8 length of the values.
    pub fn string_bytes(&self) -> u64 {
        self.string_bytes
    }

    /// The values in stored order.
    pub fn iter(&self) -> impl Iterator<Item = &'a str> + 'a {
        let mut reader = Reader::new(self.bytes, "category values");
        (0..self.count).map(move |_| {
            reader
                .str()
                .unwrap_or_else(|e| unreachable!("values were validated when parsed: {e}"))
        })
    }
}

/// The plain header at the start of `pheno.index`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IndexHeader {
    /// Format version the file was written with.
    pub format_version: u16,
    /// True when the index body and every block are AES-GCM encrypted. Informational: a
    /// reader decides what it accepts with [`Protection`], and a mismatch is an error.
    pub encrypted: bool,
    /// True when the index body and every block are zstd-compressed.
    pub compressed: bool,
    /// Name of the key used for encryption, as in `Crypto` named keys. Empty when plain.
    pub key_name: String,
    /// Creation time in epoch milliseconds.
    pub created_at_millis: i64,
    /// Version string of the ETL that wrote the store.
    pub etl_version: String,
    /// Random id of the store, bound into every block. Generate one per store with
    /// [`StoreId::random`] and use the same id for the index, the blocks file and the
    /// block codec.
    pub store_id: StoreId,
}

/// The in-memory form of `pheno.index`: header, all patient IDs, the partition table, and
/// the concept table sorted by path in Java `String.compareTo` order.
#[derive(Clone, Debug, PartialEq)]
pub struct PhenoIndex {
    header: IndexHeader,
    patients: RoaringBitmap,
    table: PartitionTable,
    concepts: Vec<ConceptMeta>,
}

impl PhenoIndex {
    /// Builds the index of an unpartitioned store, sorting `concepts` by path. The header's
    /// format version is set to [`FORMAT_VERSION`].
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when two concepts share a path or a concept has
    /// holders.
    pub fn new(
        header: IndexHeader,
        patients: RoaringBitmap,
        concepts: Vec<ConceptMeta>,
    ) -> Result<Self> {
        Self::with_partitions(header, patients, PartitionTable::default(), concepts)
    }

    /// Builds the index of a store with the partition table `table` (empty for an
    /// unpartitioned store), sorting `concepts` by path. The header's format version is set
    /// to [`FORMAT_VERSION`].
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when two concepts share a path, the table does not fit
    /// `patients` (see [`PartitionTable`]), or a concept's holders do not fit the table
    /// (holders strictly ascending by partition, each partition in the table, observation
    /// counts summing to the concept's; none in an unpartitioned store; a tagged concept
    /// only with a non-empty overlap set).
    pub fn with_partitions(
        mut header: IndexHeader,
        patients: RoaringBitmap,
        table: PartitionTable,
        mut concepts: Vec<ConceptMeta>,
    ) -> Result<Self> {
        header.format_version = FORMAT_VERSION;
        concepts.sort_by(|a, b| java_string_cmp(&a.path, &b.path));
        if let Some(w) = concepts.windows(2).find(|w| w[0].path == w[1].path) {
            return Err(FormatError::InvalidInput(format!(
                "duplicate concept path {:?}",
                w[0].path
            )));
        }
        let invalid = |e: FormatError| FormatError::InvalidInput(e.to_string());
        table.check(&patients).map_err(invalid)?;
        let context = ConceptContext {
            version: FORMAT_VERSION,
            partitions: table.partitions.len(),
            overlap_empty: table.overlap.is_empty(),
        };
        for concept in &concepts {
            context
                .check_holders(
                    &concept.path,
                    concept.observation_count,
                    &concept.holders,
                    concept.tagged,
                )
                .map_err(invalid)?;
        }
        Ok(PhenoIndex {
            header,
            patients,
            table,
            concepts,
        })
    }

    /// The file header.
    pub fn header(&self) -> &IndexHeader {
        &self.header
    }

    /// The store id from the header.
    pub fn store_id(&self) -> StoreId {
        self.header.store_id
    }

    /// The codec for this store's blocks under `protection`, which should be the
    /// protection the index was decoded with.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] or [`FormatError::UnexpectedKey`] when `protection`
    /// disagrees with the header.
    pub fn block_codec(&self, protection: Protection) -> Result<BlockCodec> {
        protection.require(self.header.encrypted, INDEX_FILE)?;
        Ok(BlockCodec::new(
            protection,
            self.header.store_id,
            self.header.compressed,
        ))
    }

    /// Every patient ID in the store.
    pub fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// The partition table, empty for an unpartitioned store.
    pub fn partition_table(&self) -> &PartitionTable {
        &self.table
    }

    /// True when the store is split into consent partitions.
    pub fn is_partitioned(&self) -> bool {
        !self.table.is_empty()
    }

    /// The routing of patients to partitions ([`PartitionTable::membership`]).
    pub fn membership(&self) -> Membership {
        self.table.membership()
    }

    /// All concepts in Java string order of their paths.
    pub fn concepts(&self) -> &[ConceptMeta] {
        &self.concepts
    }

    /// Position of `path` in [`PhenoIndex::concepts`], by binary search.
    pub fn position(&self, path: &str) -> Option<usize> {
        self.concepts
            .binary_search_by(|c| java_string_cmp(&c.path, path))
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
            .partition_point(|c| java_string_cmp(&c.path, prefix) == Ordering::Less);
        let len = self.concepts[start..]
            .iter()
            .take_while(|c| c.path.starts_with(prefix))
            .count();
        &self.concepts[start..start + len]
    }

    /// Serializes the index. `protection` must agree with the header's encrypted flag.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] or [`FormatError::UnexpectedKey`] when `protection`
    /// disagrees with the header, [`FormatError::InvalidInput`] when a string or count
    /// exceeds its field, and compression or encryption errors.
    pub fn encode(&self, protection: &Protection) -> Result<Vec<u8>> {
        protection.require(self.header.encrypted, INDEX_FILE)?;
        let body = self.encode_body()?;
        let body = if self.header.compressed {
            zstd::bulk::compress(&body, zstd::DEFAULT_COMPRESSION_LEVEL)
                .map_err(|e| FormatError::Compression(e.to_string()))?
        } else {
            body
        };
        let mut out = encode_preamble(&self.header)?;
        match protection.key() {
            Some(key) => {
                let sealed = seal_framed(key, &body, &out)?;
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
        put_bitmap(&mut out, &self.patients)?;
        out.push(if self.table.is_empty() {
            LAYOUT_UNPARTITIONED
        } else {
            LAYOUT_PARTITIONED
        });
        let k = u16::try_from(self.table.partitions.len())
            .map_err(|_| FormatError::InvalidInput("too many partitions".into()))?;
        out.extend_from_slice(&k.to_le_bytes());
        for partition in &self.table.partitions {
            put_str(&mut out, &partition.name)?;
            out.push(if partition.patients_indexed {
                PARTITION_PATIENTS_INDEXED
            } else {
                0
            });
            put_bitmap(&mut out, &partition.patients)?;
        }
        put_bitmap(&mut out, &self.table.overlap)?;
        put_u32(&mut out, self.concepts.len(), "concept count")?;
        for c in &self.concepts {
            put_str(&mut out, &c.path)?;
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
            out.push(if c.tagged { CONCEPT_TAGGED } else { 0 });
            let h = u16::try_from(c.holders.len())
                .map_err(|_| FormatError::InvalidInput("too many holders".into()))?;
            out.extend_from_slice(&h.to_le_bytes());
            for holder in &c.holders {
                out.extend_from_slice(&holder.partition.to_le_bytes());
                out.extend_from_slice(&holder.observations.to_le_bytes());
                out.extend_from_slice(&holder.patients.to_le_bytes());
            }
        }
        Ok(out)
    }

    /// Reads only the plain header of an index file, for example to learn which key name
    /// to load before calling [`PhenoIndex::decode`].
    ///
    /// # Errors
    /// [`FormatError::BadMagic`], [`FormatError::LegacyFormat`],
    /// [`FormatError::UnsupportedVersion`], [`FormatError::Truncated`] or
    /// [`FormatError::Corrupt`].
    pub fn decode_header(bytes: &[u8]) -> Result<IndexHeader> {
        Ok(decode_preamble(bytes, FORMAT_VERSION)?.0)
    }

    /// Parses and verifies a serialized index under the protection the caller requires.
    /// The header's encrypted flag never decides on its own: a plain index with a valid
    /// checksum is refused when `protection` holds a key.
    ///
    /// # Errors
    /// [`FormatError::LegacyFormat`] for an index of format 1 (Rust HPDS v1 to v3), which
    /// `hpds-etl convert` turns into this format; [`FormatError::MissingKey`] when the index
    /// is encrypted and `protection` is plain; [`FormatError::UnexpectedKey`] when
    /// `protection` holds a key and the index is plain; [`FormatError::Authentication`] or
    /// [`FormatError::Checksum`] when the bytes were altered or the key is wrong;
    /// [`FormatError::PayloadTooLarge`] when the compressed body declares more than
    /// [`MAX_INDEX_BODY_LEN`]; [`FormatError::Truncated`] or [`FormatError::Corrupt`] for
    /// structural damage, including a concept table that is not strictly sorted or a
    /// partition table or holder list that does not fit the store.
    pub fn decode(bytes: &[u8], protection: &Protection) -> Result<Self> {
        let body = IndexBody::decode(bytes, protection)?;
        let concepts = body
            .concepts()
            .map(|concept| concept.map(|c| c.to_meta()))
            .collect::<Result<Vec<_>>>()?;
        Ok(PhenoIndex {
            header: body.header,
            patients: body.patients,
            table: body.table,
            concepts,
        })
    }

    /// Serializes the index and writes it to `path`.
    ///
    /// # Errors
    /// The errors of [`PhenoIndex::encode`] and [`FormatError::Io`].
    pub fn write_file(&self, path: impl AsRef<Path>, protection: &Protection) -> Result<()> {
        std::fs::write(path, self.encode(protection)?)?;
        Ok(())
    }

    /// Reads and verifies the index at `path`.
    ///
    /// # Errors
    /// The errors of [`PhenoIndex::decode`] and [`FormatError::Io`].
    pub fn read_file(path: impl AsRef<Path>, protection: &Protection) -> Result<Self> {
        Self::decode(&std::fs::read(path)?, protection)
    }
}

/// The index of a format 1 store, written by Rust HPDS v1 to v3: one store per directory,
/// no partition table and no holders. Read only, for `hpds-etl convert`.
#[derive(Clone, Debug, PartialEq)]
pub struct LegacyIndex {
    header: IndexHeader,
    patients: RoaringBitmap,
    concepts: Vec<ConceptMeta>,
}

impl LegacyIndex {
    /// Parses and verifies a serialized format 1 index under `protection`, with the
    /// protection rules of [`PhenoIndex::decode`].
    ///
    /// # Errors
    /// [`FormatError::UnsupportedVersion`] for any other format version, and the errors of
    /// [`PhenoIndex::decode`].
    pub fn decode(bytes: &[u8], protection: &Protection) -> Result<Self> {
        let (header, patients, body, table_start, concept_count) =
            open_body(bytes, protection, LEGACY_FORMAT_VERSION)?;
        let concepts = ConceptViews {
            reader: Reader::new(&body.as_bytes()[table_start..], "index body"),
            remaining: concept_count,
            previous: None,
            done: false,
            context: ConceptContext {
                version: LEGACY_FORMAT_VERSION,
                partitions: 0,
                overlap_empty: true,
            },
        }
        .map(|concept| concept.map(|c| c.to_meta()))
        .collect::<Result<Vec<_>>>()?;
        Ok(LegacyIndex {
            header,
            patients,
            concepts,
        })
    }

    /// Reads and verifies the format 1 index at `path`.
    ///
    /// # Errors
    /// The errors of [`LegacyIndex::decode`] and [`FormatError::Io`].
    pub fn read_file(path: impl AsRef<Path>, protection: &Protection) -> Result<Self> {
        Self::decode(&std::fs::read(path)?, protection)
    }

    /// The file header.
    pub fn header(&self) -> &IndexHeader {
        &self.header
    }

    /// The store id from the header.
    pub fn store_id(&self) -> StoreId {
        self.header.store_id
    }

    /// The codec that opens this store's blocks ([`BlockCodec::legacy`]).
    ///
    /// # Errors
    /// As for [`PhenoIndex::block_codec`].
    pub fn block_codec(&self, protection: Protection) -> Result<BlockCodec> {
        protection.require(self.header.encrypted, INDEX_FILE)?;
        Ok(BlockCodec::legacy(
            protection,
            self.header.store_id,
            self.header.compressed,
        ))
    }

    /// Every patient ID in the store.
    pub fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// All concepts in Java string order of their paths, none with holders.
    pub fn concepts(&self) -> &[ConceptMeta] {
        &self.concepts
    }

    /// Position of `path` in [`LegacyIndex::concepts`], by binary search.
    pub fn position(&self, path: &str) -> Option<usize> {
        self.concepts
            .binary_search_by(|c| java_string_cmp(&c.path, path))
            .ok()
    }
}

/// The format version recorded in the header of the index file `bytes`, read without
/// verifying anything else: [`FORMAT_VERSION`] for a store this crate reads,
/// [`LEGACY_FORMAT_VERSION`] for a store `hpds-etl convert` reads.
///
/// # Errors
/// [`FormatError::BadMagic`] or [`FormatError::Truncated`].
pub fn index_format_version(bytes: &[u8]) -> Result<u16> {
    let mut r = Reader::new(bytes, "index header");
    if r.bytes(INDEX_MAGIC.len())? != INDEX_MAGIC {
        return Err(FormatError::BadMagic { file: INDEX_FILE });
    }
    r.u16()
}

/// A verified and decompressed `pheno.index` whose concept table is parsed on demand
/// ([`IndexBody::concepts`]), borrowing every path and category value from the body. A
/// reader that keeps only some of each concept's fields builds its own table from it
/// without allocating a [`ConceptMeta`] per concept.
#[derive(Debug)]
pub struct IndexBody {
    header: IndexHeader,
    patients: RoaringBitmap,
    table: PartitionTable,
    body: AlignedBuf,
    table_start: usize,
    concept_count: usize,
}

/// Opens the stored body of an index of format `version`: verifies the preamble, the
/// protection and the checksum or tag, decompresses, and reads the patient set. Returns
/// the header, the patients, the body and the position after the patient set.
#[allow(clippy::type_complexity)]
fn open_body(
    bytes: &[u8],
    protection: &Protection,
    version: u16,
) -> Result<(IndexHeader, RoaringBitmap, AlignedBuf, usize, usize)> {
    let (header, preamble_len) = decode_preamble(bytes, version)?;
    protection.require(header.encrypted, INDEX_FILE)?;
    let mut r = Reader::new(&bytes[preamble_len..], "index");
    let stored_len = r.u64()?;
    let stored_len = r.len(stored_len)?;
    let stored = r.bytes(stored_len)?;
    let body_start = preamble_len + 8;
    let opened;
    let body: &[u8] = if let Some(key) = protection.key() {
        r.finish()?;
        opened = open_framed(key, stored, &bytes[..preamble_len])?;
        &opened
    } else {
        let checksum = r.u64()?;
        r.finish()?;
        if xxh3_64(&bytes[..body_start + stored_len]) != checksum {
            return Err(FormatError::Checksum("index"));
        }
        stored
    };
    let body = if header.compressed {
        zstd_decompress_aligned(body, MAX_INDEX_BODY_LEN, "index body")?
    } else {
        AlignedBuf::copy_from(body)
    };
    let mut r = Reader::new(body.as_bytes(), "index body");
    let patients = r.bitmap("patient bitmap")?;
    let (pos, concept_count) = if version == LEGACY_FORMAT_VERSION {
        let count = r.u32()? as usize;
        (r.pos, count)
    } else {
        (r.pos, 0)
    };
    Ok((header, patients, body, pos, concept_count))
}

impl IndexBody {
    /// Parses and verifies a serialized index as [`PhenoIndex::decode`] does, but parses
    /// only the patient set, the partition table and the concept count. The concept table
    /// is checked as [`IndexBody::concepts`] walks it.
    ///
    /// # Errors
    /// The errors of [`PhenoIndex::decode`], except those of the concept table.
    pub fn decode(bytes: &[u8], protection: &Protection) -> Result<Self> {
        let (header, patients, body, after_patients, _) =
            open_body(bytes, protection, FORMAT_VERSION)?;
        let mut r = Reader::new(body.as_bytes(), "index body");
        r.pos = after_patients;
        let layout = r.u8()?;
        let k = r.u16()? as usize;
        match (layout, k) {
            (LAYOUT_UNPARTITIONED, 0) => {}
            (LAYOUT_PARTITIONED, k) if k > 0 => {}
            _ => {
                return Err(FormatError::corrupt(
                    "partition table",
                    format!("layout {layout} with {k} partitions"),
                ));
            }
        }
        let mut partitions = Vec::with_capacity(k);
        for _ in 0..k {
            let name = r.string()?;
            let flags = r.u8()?;
            if flags & !PARTITION_PATIENTS_INDEXED != 0 {
                return Err(FormatError::corrupt(
                    "partition table",
                    format!("unknown partition flags {flags:#04x}"),
                ));
            }
            partitions.push(IndexPartition {
                name,
                patients_indexed: flags & PARTITION_PATIENTS_INDEXED != 0,
                patients: r.bitmap("partition patient bitmap")?,
            });
        }
        let overlap = r.bitmap("overlap bitmap")?;
        let table = PartitionTable {
            partitions,
            overlap,
        };
        table.check(&patients)?;
        let concept_count = r.u32()? as usize;
        let table_start = r.pos;
        Ok(IndexBody {
            header,
            patients,
            table,
            body,
            table_start,
            concept_count,
        })
    }

    /// Reads and verifies the index at `path` ([`IndexBody::decode`]).
    ///
    /// # Errors
    /// The errors of [`IndexBody::decode`] and [`FormatError::Io`].
    pub fn read_file(path: impl AsRef<Path>, protection: &Protection) -> Result<Self> {
        Self::decode(&std::fs::read(path)?, protection)
    }

    /// The file header.
    pub fn header(&self) -> &IndexHeader {
        &self.header
    }

    /// The store id from the header.
    pub fn store_id(&self) -> StoreId {
        self.header.store_id
    }

    /// The codec for this store's blocks under `protection`, as
    /// [`PhenoIndex::block_codec`].
    ///
    /// # Errors
    /// As for [`PhenoIndex::block_codec`].
    pub fn block_codec(&self, protection: Protection) -> Result<BlockCodec> {
        protection.require(self.header.encrypted, INDEX_FILE)?;
        Ok(BlockCodec::new(
            protection,
            self.header.store_id,
            self.header.compressed,
        ))
    }

    /// Every patient ID in the store.
    pub fn patients(&self) -> &RoaringBitmap {
        &self.patients
    }

    /// The partition table, empty for an unpartitioned store.
    pub fn partition_table(&self) -> &PartitionTable {
        &self.table
    }

    /// The patient set and the partition table, dropping the body.
    pub fn into_parts(self) -> (RoaringBitmap, PartitionTable) {
        (self.patients, self.table)
    }

    /// The number of concepts the table declares.
    pub fn concept_count(&self) -> usize {
        self.concept_count
    }

    /// The concepts in table order, which is Java string order of their paths. Each item
    /// is checked as it is parsed, as [`PhenoIndex::decode`] checks it: a concept that is
    /// truncated, has unknown flags or type, values without a value list, a path or value
    /// that is not UTF-8, a path not sorted after the previous one, or holders that do not
    /// fit the partition table is an error, and so are bytes left after the last concept.
    /// The iteration ends after the first error.
    pub fn concepts(&self) -> ConceptViews<'_> {
        ConceptViews {
            reader: Reader::new(&self.body.as_bytes()[self.table_start..], "index body"),
            remaining: self.concept_count,
            previous: None,
            done: false,
            context: ConceptContext {
                version: FORMAT_VERSION,
                partitions: self.table.partitions.len(),
                overlap_empty: self.table.overlap.is_empty(),
            },
        }
    }
}

/// What a concept entry is checked against: the format version it was written in, the
/// number of partitions in the store, and whether the overlap set is empty.
#[derive(Clone, Copy, Debug)]
struct ConceptContext {
    version: u16,
    partitions: usize,
    overlap_empty: bool,
}

impl ConceptContext {
    /// Checks a concept's holders: none in an unpartitioned store; otherwise strictly
    /// ascending by partition, each below the partition count, with observation counts
    /// summing to `observation_count`; and a tagged concept only in a store whose overlap
    /// set is not empty.
    fn check_holders(
        &self,
        path: &str,
        observation_count: u32,
        holders: &[PartitionHolder],
        tagged: bool,
    ) -> Result<()> {
        let refuse = |detail: &str| {
            Err(FormatError::corrupt(
                "concept table",
                format!("{path:?} {detail}"),
            ))
        };
        if self.partitions == 0 {
            if !holders.is_empty() || tagged {
                return refuse("has holders in an unpartitioned store");
            }
            return Ok(());
        }
        if holders.windows(2).any(|w| w[0].partition >= w[1].partition) {
            return refuse("has holders not strictly ascending by partition");
        }
        if holders
            .iter()
            .any(|h| usize::from(h.partition) >= self.partitions)
        {
            return refuse("has a holder outside the partition table");
        }
        let summed: u64 = holders.iter().map(|h| u64::from(h.observations)).sum();
        if summed != u64::from(observation_count) {
            return refuse("has holders whose observations do not sum to its count");
        }
        if tagged && self.overlap_empty {
            return refuse("is tagged in a store without overlap patients");
        }
        Ok(())
    }
}

/// The concepts of an [`IndexBody`], from [`IndexBody::concepts`].
pub struct ConceptViews<'a> {
    reader: Reader<'a>,
    remaining: usize,
    previous: Option<&'a str>,
    done: bool,
    context: ConceptContext,
}

impl std::fmt::Debug for ConceptViews<'_> {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ConceptViews")
            .field("remaining", &self.remaining)
            .field("done", &self.done)
            .finish_non_exhaustive()
    }
}

impl<'a> Iterator for ConceptViews<'a> {
    type Item = Result<ConceptView<'a>>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.done {
            return None;
        }
        if self.remaining == 0 {
            self.done = true;
            return self.reader.finish().err().map(Err);
        }
        self.remaining -= 1;
        let parsed = parse_concept(&mut self.reader, self.previous, &self.context);
        match &parsed {
            Ok(concept) => self.previous = Some(concept.path),
            Err(_) => self.done = true,
        }
        Some(parsed)
    }
}

fn put_bitmap(out: &mut Vec<u8>, bitmap: &RoaringBitmap) -> Result<()> {
    out.extend_from_slice(&(bitmap.serialized_size() as u64).to_le_bytes());
    bitmap.serialize_into(out)?;
    Ok(())
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

/// Writes the plain preamble of an index in [`FORMAT_VERSION`].
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
    out.extend_from_slice(header.store_id.as_bytes());
    put_str(&mut out, &header.key_name)?;
    out.extend_from_slice(&header.created_at_millis.to_le_bytes());
    put_str(&mut out, &header.etl_version)?;
    Ok(out)
}

/// Reads the plain preamble of an index of format `version`.
fn decode_preamble(bytes: &[u8], version: u16) -> Result<(IndexHeader, usize)> {
    let mut r = Reader::new(bytes, "index header");
    if r.bytes(INDEX_MAGIC.len())? != INDEX_MAGIC {
        return Err(FormatError::BadMagic { file: INDEX_FILE });
    }
    let format_version = r.u16()?;
    if format_version != version {
        return Err(if format_version == LEGACY_FORMAT_VERSION {
            FormatError::LegacyFormat
        } else {
            FormatError::UnsupportedVersion(format_version)
        });
    }
    let flags = r.u16()?;
    if flags & !KNOWN_FLAGS != 0 {
        return Err(FormatError::corrupt(
            "index header",
            format!("unknown flags {flags:#06x}"),
        ));
    }
    let store_id = StoreId::from_bytes(r.array::<STORE_ID_LEN>()?);
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
        store_id,
    };
    Ok((header, r.pos))
}

/// Parses the concept at `r`'s position, which must sort after `previous`, with the fields
/// and checks of `context`'s format version.
fn parse_concept<'a>(
    r: &mut Reader<'a>,
    previous: Option<&'a str>,
    context: &ConceptContext,
) -> Result<ConceptView<'a>> {
    let path = r.str()?;
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
    let values_start = r.pos;
    let mut string_bytes = 0u64;
    for _ in 0..value_count {
        string_bytes += r.str()?.len() as u64;
    }
    let values = CategoryValues {
        bytes: &r.bytes[values_start..r.pos],
        count: value_count,
        string_bytes,
    };
    let (tagged, holders) = if context.version == LEGACY_FORMAT_VERSION {
        (false, ConceptHolders::default())
    } else {
        let concept_flags = r.u8()?;
        if concept_flags & !CONCEPT_TAGGED != 0 {
            return Err(FormatError::corrupt(
                "concept table",
                format!("{path:?} has unknown concept flags {concept_flags:#04x}"),
            ));
        }
        let count = r.u16()? as usize;
        let holders = ConceptHolders {
            bytes: r.bytes(count * INDEX_HOLDER_LEN)?,
        };
        (concept_flags & CONCEPT_TAGGED != 0, holders)
    };
    let listed: Vec<PartitionHolder> = holders.iter().collect();
    context.check_holders(path, observation_count, &listed, tagged)?;
    let has = |bit: u8| flags & bit != 0;
    if !has(META_CATEGORY_VALUES) && value_count != 0 {
        return Err(FormatError::corrupt(
            "concept table",
            format!("{path:?} has values but no value list"),
        ));
    }
    if let Some(previous) = previous
        && java_string_cmp(previous, path) != Ordering::Less
    {
        return Err(FormatError::corrupt(
            "concept table",
            format!("{path:?} is not sorted after {previous:?}"),
        ));
    }
    Ok(ConceptView {
        path,
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
        holders,
        tagged,
    })
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

    fn bitmap(&mut self, what: &'static str) -> Result<RoaringBitmap> {
        let len = self.u64()?;
        let len = self.len(len)?;
        RoaringBitmap::deserialize_from(self.bytes(len)?)
            .map_err(|e| FormatError::corrupt(what, e.to_string()))
    }

    fn string(&mut self) -> Result<String> {
        self.str().map(str::to_owned)
    }

    fn str(&mut self) -> Result<&'a str> {
        let len = self.u32()? as usize;
        let bytes = self.bytes(len)?;
        std::str::from_utf8(bytes).map_err(|e| FormatError::corrupt(self.what, e.to_string()))
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
