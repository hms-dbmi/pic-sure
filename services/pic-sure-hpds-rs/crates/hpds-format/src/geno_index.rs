//! The `geno.index` file: header, groups, nodes with their chunk tables and info entries,
//! and the column table.
//!
//! The preamble is `pheno.index`'s with the genomic magic and a key source. The body is
//! zstd-compressed when the compressed flag is set, then sealed with the preamble as
//! associated data when encrypted, or followed by the xxh3 checksum of every preceding byte
//! when plain.
//!
//! ```text
//! magic             8 bytes  "HPDSGEN1"
//! format_version    u16      GENO_FORMAT_VERSION
//! flags             u16      bit 0 encrypted, bit 1 compressed, others zero
//! store_id          16 bytes random; in geno.blocks and every block's associated data
//! key_source        u8       0: the phenotype store's key file, the only value accepted
//! key_name          string   the key's name; empty in a plain store
//! created_at_millis i64
//! etl_version       string
//! stored_len        u64
//! stored            stored_len bytes
//! checksum          u64      plain only: xxh3_64 of every preceding byte
//! ```
//!
//! Body:
//!
//! ```text
//! mode              u8   0 local, 1 localDistributed, 2 localPatientDistributed,
//!                        3 localPatientOnlyDistributed
//! group_count       u16  1 for modes 0 and 1, 1 to 10 for modes 2 and 3
//! group_count times, strictly ascending by name in Java String.compareTo order:
//!   name            string   "" for modes 0 and 1
//!   patient_count   u32
//!   patients        u32[patient_count]   HPDS ids in Java's patientIds order; a mask
//!                                        position p means patients[p]
//! node_count        u16
//! node_count times, ordered by group, then by dir_name in Java String.compareTo order:
//!   group           u16      index into the group table
//!   dir_name        string   chromosome directory name; "" for modes 0 and 3
//!   contig_count    u16      0 only with chunk_count 0 (a node with no variants)
//!   contigs         contig_count strings, strictly ascending in Java String.compareTo order
//!   variant_count   u32
//!   chunk_count     u32
//!   chunk_count times, ascending by (contig, first_bucket):
//!     contig        u16      index into this node's contigs
//!     first_bucket  i32
//!     last_bucket   i32      >= first_bucket
//!     first_variant u32      the first chunk's is 0; each next one is the previous
//!                            first_variant + variant_count
//!     variant_count u32      >= 1
//!     bucket_count  u32      >= 1
//!     mask          location
//!     metadata      location
//!     carriers      location
//!   short_spec_count u32
//!   short_specs     u32[short_spec_count]   variant ids whose spec has fewer than six
//!                                           fields after Java's split; strictly ascending
//!   info_count      u16
//!   info_count times, strictly ascending by column:
//!     column        u16      index into the column table
//!     continuous    u8       0 or 1, this node's isContinuous
//!     description   string
//!     key_count     u32
//!     info          location
//! column_count      u16
//! column_count times, strictly ascending in Java String.compareTo order:
//!   key             string   the INFO column name
//! location = offset u64, stored_len u32, decoded_len u32
//! ```
//!
//! [`GenoIndex::decode`] checks every invariant of the format and answers
//! [`FormatError::Corrupt`] naming the item, [`FormatError::Truncated`],
//! [`FormatError::PayloadTooLarge`] or [`FormatError::Authentication`]; it never panics.

use std::cmp::Ordering;
use std::path::Path;

use xxhash_rust::xxh3::xxh3_64;

use crate::aligned::AlignedBuf;
use crate::block::zstd_decompress_aligned;
use crate::crypto::{open_framed, seal_framed};
use crate::error::{FormatError, Result};
use crate::geno_blocks::{GENO_BLOCKS_HEADER_LEN, GenoBlockCodec, max_stored_len};
use crate::geno_chunks::ExpectedMaskChunk;
use crate::geno_info::ExpectedInfoBlock;
use crate::parity::java_string_cmp;
use crate::protection::{Protection, STORE_ID_LEN, StoreId};
use crate::{GENO_FORMAT_VERSION, GENO_INDEX_MAGIC, MAX_GENO_BLOCK_LEN, MAX_GENO_INDEX_BODY_LEN};

const FLAG_ENCRYPTED: u16 = 1;
const FLAG_COMPRESSED: u16 = 1 << 1;
const KNOWN_FLAGS: u16 = FLAG_ENCRYPTED | FLAG_COMPRESSED;
const INDEX_FILE: &str = "geno.index";
const LOCATION_LEN: usize = 16;
const CHUNK_ENTRY_LEN: usize = 22 + 3 * LOCATION_LEN;

/// The key source of a store sealed with the phenotype store's key file, the only one this
/// format version writes or accepts.
pub const KEY_SOURCE_PHENOTYPE: u8 = 0;

/// The most groups a patient-distributed store may hold, as `GenomicProcessorConfig` caps
/// them.
pub const MAX_GENO_GROUPS: usize = 10;

/// The most chromosome nodes a group may hold.
pub const MAX_GENO_NODES_PER_GROUP: usize = 50;

/// The genomic processor layout the store was built in, Java's `hpds.genomicProcessor.impl`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum GenoMode {
    /// `local`: one group, one node.
    Local = 0,
    /// `localDistributed`: one group, one node per chromosome directory.
    LocalDistributed = 1,
    /// `localPatientDistributed`: patient groups, each with chromosome nodes.
    LocalPatientDistributed = 2,
    /// `localPatientOnlyDistributed`: patient groups, each one node.
    LocalPatientOnlyDistributed = 3,
}

impl GenoMode {
    /// The mode stored as `byte`.
    pub fn from_byte(byte: u8) -> Option<Self> {
        match byte {
            0 => Some(GenoMode::Local),
            1 => Some(GenoMode::LocalDistributed),
            2 => Some(GenoMode::LocalPatientDistributed),
            3 => Some(GenoMode::LocalPatientOnlyDistributed),
            _ => None,
        }
    }

    /// The value of `hpds.genomicProcessor.impl` that selects this layout in Java.
    pub fn java_name(self) -> &'static str {
        match self {
            GenoMode::Local => "local",
            GenoMode::LocalDistributed => "localDistributed",
            GenoMode::LocalPatientDistributed => "localPatientDistributed",
            GenoMode::LocalPatientOnlyDistributed => "localPatientOnlyDistributed",
        }
    }

    fn patient_groups(self) -> bool {
        matches!(
            self,
            GenoMode::LocalPatientDistributed | GenoMode::LocalPatientOnlyDistributed
        )
    }

    fn chromosome_nodes(self) -> bool {
        matches!(
            self,
            GenoMode::LocalDistributed | GenoMode::LocalPatientDistributed
        )
    }
}

/// Where a block sits in `geno.blocks`, and its sizes.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub struct GenoLocation {
    /// Byte offset of the sealed block from the start of `geno.blocks`.
    pub offset: u64,
    /// Length of the sealed block.
    pub stored_len: u32,
    /// Length of the decoded payload.
    pub decoded_len: u32,
}

impl GenoLocation {
    /// The offset just past the block, saturating.
    pub fn end(&self) -> u64 {
        self.offset.saturating_add(u64::from(self.stored_len))
    }
}

/// The plain header at the start of `geno.index`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenoIndexHeader {
    /// Format version the file was written with.
    pub format_version: u16,
    /// True when the body and every block are AES-GCM encrypted. Informational: a reader
    /// decides what it accepts with [`Protection`], and a mismatch is an error.
    pub encrypted: bool,
    /// True when the body is zstd-compressed. Blocks always are.
    pub compressed: bool,
    /// Random id of the store, bound into every block.
    pub store_id: StoreId,
    /// Which key file seals the store: [`KEY_SOURCE_PHENOTYPE`].
    pub key_source: u8,
    /// The key's name, as `pheno.index` records it; empty when plain.
    pub key_name: String,
    /// Creation time in epoch milliseconds.
    pub created_at_millis: i64,
    /// Version string of the ETL that wrote the store.
    pub etl_version: String,
}

/// A patient group: Java's patient distribution directory, or the one group of modes 0 and
/// 1.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenoGroup {
    /// The directory name, empty in modes 0 and 1.
    pub name: String,
    /// HPDS patient ids in Java's `patientIds` order: mask position `p` means `patients[p]`.
    pub patients: Vec<u32>,
}

/// One entry of a node's chunk table.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct GenoChunkEntry {
    /// Index into the node's contigs.
    pub contig: u16,
    /// The chunk's first bucket.
    pub first_bucket: i32,
    /// The chunk's last bucket.
    pub last_bucket: i32,
    /// Node variant id of the chunk's first variant.
    pub first_variant: u32,
    /// Variants of the chunk.
    pub variant_count: u32,
    /// Buckets of the chunk.
    pub bucket_count: u32,
    /// The mask chunk block.
    pub mask: GenoLocation,
    /// The metadata chunk block.
    pub metadata: GenoLocation,
    /// The bucket carrier chunk block.
    pub carriers: GenoLocation,
}

/// One INFO column a node holds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenoInfoEntry {
    /// Index into the column table.
    pub column: u16,
    /// This node's `isContinuous` for the column.
    pub continuous: bool,
    /// `FileBackedByteIndexedInfoStore.description`, as stored.
    pub description: String,
    /// Keys of the column on this node.
    pub key_count: u32,
    /// The info block.
    pub info: GenoLocation,
}

/// A node: one Java `GenomicProcessorNodeImpl`, a chromosome directory of a group or the
/// group itself.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenoNode {
    /// Index into the group table.
    pub group: u16,
    /// The chromosome directory name, empty in modes 0 and 3.
    pub dir_name: String,
    /// Contigs of the node's specs, strictly ascending in Java `String.compareTo` order.
    pub contigs: Vec<String>,
    /// Variants of the node; ids run `0..variant_count` in chunk order.
    pub variant_count: u32,
    /// The chunk table, ascending by (contig, first bucket).
    pub chunks: Vec<GenoChunkEntry>,
    /// Variant ids whose spec has fewer than six fields, strictly ascending.
    pub short_specs: Vec<u32>,
    /// The node's INFO columns, strictly ascending by column.
    pub infos: Vec<GenoInfoEntry>,
}

impl GenoNode {
    /// The index of contig `name` in the node's contigs.
    pub fn contig_index(&self, name: &str) -> Option<u16> {
        self.contigs
            .binary_search_by(|c| java_string_cmp(c, name))
            .ok()
            .map(|i| i as u16)
    }

    /// The chunk holding bucket `bucket` of contig `contig`, `None` when no chunk covers it
    /// (the bucket may still be absent from the chunk that covers it).
    pub fn chunk_for_bucket(&self, contig: u16, bucket: i32) -> Option<usize> {
        let i = self
            .chunks
            .partition_point(|c| (c.contig, c.last_bucket) < (contig, bucket));
        let chunk = self.chunks.get(i)?;
        (chunk.contig == contig && chunk.first_bucket <= bucket).then_some(i)
    }

    /// The chunk holding node variant `variant`.
    pub fn chunk_for_variant(&self, variant: u32) -> Option<usize> {
        if variant >= self.variant_count {
            return None;
        }
        self.chunks
            .partition_point(|c| c.first_variant <= variant)
            .checked_sub(1)
    }

    /// The node's entry for column `column`.
    pub fn info(&self, column: u16) -> Option<&GenoInfoEntry> {
        self.infos
            .binary_search_by_key(&column, |e| e.column)
            .ok()
            .map(|i| &self.infos[i])
    }

    /// True when node variant `variant`'s spec has fewer than six fields.
    pub fn is_short_spec(&self, variant: u32) -> bool {
        self.short_specs.binary_search(&variant).is_ok()
    }
}

/// The in-memory form of `geno.index`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GenoIndex {
    header: GenoIndexHeader,
    mode: GenoMode,
    groups: Vec<GenoGroup>,
    nodes: Vec<GenoNode>,
    columns: Vec<String>,
}

impl GenoIndex {
    /// Builds an index and checks every invariant of the format. The header's format version
    /// is set to [`GENO_FORMAT_VERSION`].
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] naming the first broken invariant.
    pub fn new(
        mut header: GenoIndexHeader,
        mode: GenoMode,
        groups: Vec<GenoGroup>,
        nodes: Vec<GenoNode>,
        columns: Vec<String>,
    ) -> Result<Self> {
        header.format_version = GENO_FORMAT_VERSION;
        let index = GenoIndex {
            header,
            mode,
            groups,
            nodes,
            columns,
        };
        index.check_header()?;
        index
            .check()
            .map_err(|e| FormatError::InvalidInput(e.to_string()))?;
        Ok(index)
    }

    /// The file header.
    pub fn header(&self) -> &GenoIndexHeader {
        &self.header
    }

    /// The store id from the header.
    pub fn store_id(&self) -> StoreId {
        self.header.store_id
    }

    /// The layout.
    pub fn mode(&self) -> GenoMode {
        self.mode
    }

    /// The groups, ascending by name.
    pub fn groups(&self) -> &[GenoGroup] {
        &self.groups
    }

    /// The nodes, ordered by group, then directory name.
    pub fn nodes(&self) -> &[GenoNode] {
        &self.nodes
    }

    /// The INFO columns, ascending in Java `String.compareTo` order.
    pub fn columns(&self) -> &[String] {
        &self.columns
    }

    /// The index of column `name` in the column table.
    pub fn column_index(&self, name: &str) -> Option<u16> {
        self.columns
            .binary_search_by(|c| java_string_cmp(c, name))
            .ok()
            .map(|i| i as u16)
    }

    /// The codec for this store's blocks under `protection`, which should be the protection
    /// the index was decoded with.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] or [`FormatError::UnexpectedKey`] when `protection`
    /// disagrees with the header.
    pub fn block_codec(&self, protection: Protection) -> Result<GenoBlockCodec> {
        protection.require(self.header.encrypted, INDEX_FILE)?;
        Ok(GenoBlockCodec::new(protection, self.header.store_id))
    }

    /// Every block location the index records.
    pub fn locations(&self) -> impl Iterator<Item = GenoLocation> + '_ {
        self.nodes.iter().flat_map(|node| {
            node.chunks
                .iter()
                .flat_map(|c| [c.mask, c.metadata, c.carriers])
                .chain(node.infos.iter().map(|e| e.info))
        })
    }

    fn node(&self, node: u16) -> Result<&GenoNode> {
        self.nodes
            .get(usize::from(node))
            .ok_or_else(|| FormatError::InvalidInput(format!("no node {node}")))
    }

    /// The chunk table entry `chunk` of node `node`.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when either is out of range.
    pub fn chunk(&self, node: u16, chunk: u32) -> Result<&GenoChunkEntry> {
        self.node(node)?
            .chunks
            .get(chunk as usize)
            .ok_or_else(|| FormatError::InvalidInput(format!("no chunk {chunk} in node {node}")))
    }

    /// The patient count of node `node`'s group, the range of its masks.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when the node is out of range.
    pub fn node_patient_count(&self, node: u16) -> Result<u32> {
        let group = self.node(node)?.group;
        Ok(self.groups[usize::from(group)].patients.len() as u32)
    }

    /// What the index says mask chunk `chunk` of node `node` holds.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when either is out of range.
    pub fn expected_mask_chunk(&self, node: u16, chunk: u32) -> Result<ExpectedMaskChunk<'_>> {
        let entry = self.chunk(node, chunk)?;
        let n = self.node(node)?;
        Ok(ExpectedMaskChunk {
            first_variant: entry.first_variant,
            variant_count: entry.variant_count,
            bucket_count: entry.bucket_count,
            contig: entry.contig,
            contig_name: &n.contigs[usize::from(entry.contig)],
            first_bucket: entry.first_bucket,
            last_bucket: entry.last_bucket,
            patient_count: self.node_patient_count(node)?,
            short_specs: &n.short_specs,
        })
    }

    /// The location of node `node`'s info block for column `column` and what it holds.
    ///
    /// # Errors
    /// [`FormatError::InvalidInput`] when the node is out of range or holds no block for
    /// the column.
    pub fn expected_info_block(
        &self,
        node: u16,
        column: u16,
    ) -> Result<(GenoLocation, ExpectedInfoBlock)> {
        let n = self.node(node)?;
        let entry = n.info(column).ok_or_else(|| {
            FormatError::InvalidInput(format!("node {node} holds no column {column}"))
        })?;
        Ok((
            entry.info,
            ExpectedInfoBlock {
                continuous: entry.continuous,
                key_count: entry.key_count,
                variant_count: n.variant_count,
                patient_count: self.node_patient_count(node)?,
            },
        ))
    }

    fn check_header(&self) -> Result<()> {
        if self.header.key_source != KEY_SOURCE_PHENOTYPE {
            return Err(FormatError::InvalidInput(format!(
                "key source {} is not supported",
                self.header.key_source
            )));
        }
        if !self.header.encrypted && !self.header.key_name.is_empty() {
            return Err(FormatError::InvalidInput(
                "a plain store names no key".into(),
            ));
        }
        Ok(())
    }

    /// Checks every invariant of the body, answering [`FormatError::Corrupt`].
    fn check(&self) -> Result<()> {
        let mode = self.mode;
        let groups_what = "genomic index groups";
        let group_limit = if mode.patient_groups() {
            1..=MAX_GENO_GROUPS
        } else {
            1..=1
        };
        if !group_limit.contains(&self.groups.len()) {
            return Err(FormatError::corrupt(
                groups_what,
                format!("{} groups in mode {}", self.groups.len(), mode.java_name()),
            ));
        }
        for (i, group) in self.groups.iter().enumerate() {
            if group.name.is_empty() == mode.patient_groups() {
                return Err(FormatError::corrupt(
                    groups_what,
                    format!(
                        "group {i} is named {:?} in mode {}",
                        group.name,
                        mode.java_name()
                    ),
                ));
            }
            if i > 0 && java_string_cmp(&self.groups[i - 1].name, &group.name) != Ordering::Less {
                return Err(FormatError::corrupt(
                    groups_what,
                    format!(
                        "group {:?} is not ordered after its predecessor",
                        group.name
                    ),
                ));
            }
        }
        let mut all: Vec<u32> = self
            .groups
            .iter()
            .flat_map(|g| g.patients.iter().copied())
            .collect();
        if let Some(&id) = all.iter().find(|&&id| id > i32::MAX as u32) {
            return Err(FormatError::corrupt(
                groups_what,
                format!("patient id {id} exceeds i32::MAX"),
            ));
        }
        all.sort_unstable();
        if let Some(w) = all.windows(2).find(|w| w[0] == w[1]) {
            return Err(FormatError::corrupt(
                groups_what,
                format!("patient {} appears twice", w[0]),
            ));
        }

        let nodes_what = "genomic index nodes";
        let mut per_group = vec![0usize; self.groups.len()];
        for (i, node) in self.nodes.iter().enumerate() {
            let group = usize::from(node.group);
            let Some(count) = per_group.get_mut(group) else {
                return Err(FormatError::corrupt(
                    nodes_what,
                    format!("node {i} names group {group}"),
                ));
            };
            *count += 1;
            if node.dir_name.is_empty() == mode.chromosome_nodes() {
                return Err(FormatError::corrupt(
                    nodes_what,
                    format!(
                        "node {i} has directory {:?} in mode {}",
                        node.dir_name,
                        mode.java_name()
                    ),
                ));
            }
            if i > 0 {
                let previous = &self.nodes[i - 1];
                let order = previous
                    .group
                    .cmp(&node.group)
                    .then_with(|| java_string_cmp(&previous.dir_name, &node.dir_name));
                if order != Ordering::Less {
                    return Err(FormatError::corrupt(
                        nodes_what,
                        format!(
                            "node {i} ({:?}) is not ordered after its predecessor",
                            node.dir_name
                        ),
                    ));
                }
            }
            self.check_node(i, node)?;
        }
        let node_limit = if mode.chromosome_nodes() {
            0..=MAX_GENO_NODES_PER_GROUP
        } else {
            1..=1
        };
        if let Some((group, &count)) = per_group
            .iter()
            .enumerate()
            .find(|(_, c)| !node_limit.contains(c))
        {
            return Err(FormatError::corrupt(
                nodes_what,
                format!(
                    "group {group} holds {count} nodes in mode {}",
                    mode.java_name()
                ),
            ));
        }

        for (i, column) in self.columns.iter().enumerate() {
            if i > 0 && java_string_cmp(&self.columns[i - 1], column) != Ordering::Less {
                return Err(FormatError::corrupt(
                    "genomic index columns",
                    format!("column {column:?} is not ordered after its predecessor"),
                ));
            }
        }
        Ok(())
    }

    fn check_node(&self, i: usize, node: &GenoNode) -> Result<()> {
        let chunks_what = "genomic index chunks";
        for (c, contig) in node.contigs.iter().enumerate() {
            if c > 0 && java_string_cmp(&node.contigs[c - 1], contig) != Ordering::Less {
                return Err(FormatError::corrupt(
                    "genomic index contigs",
                    format!("node {i}: contig {contig:?} is not ordered after its predecessor"),
                ));
            }
        }
        if node.contigs.is_empty() && !node.chunks.is_empty() {
            return Err(FormatError::corrupt(
                chunks_what,
                format!("node {i} has chunks but no contigs"),
            ));
        }
        let mut next_variant = 0u32;
        for (c, chunk) in node.chunks.iter().enumerate() {
            let bad = |detail: &str| {
                FormatError::corrupt(chunks_what, format!("node {i} chunk {c}: {detail}"))
            };
            if usize::from(chunk.contig) >= node.contigs.len() {
                return Err(bad("contig out of range"));
            }
            if chunk.first_bucket > chunk.last_bucket {
                return Err(bad("first bucket after last bucket"));
            }
            if chunk.variant_count == 0
                || chunk.bucket_count == 0
                || chunk.bucket_count > chunk.variant_count
            {
                return Err(bad("variant or bucket count out of range"));
            }
            let span = i64::from(chunk.last_bucket) - i64::from(chunk.first_bucket) + 1;
            if i64::from(chunk.bucket_count) > span
                || (chunk.bucket_count == 1) != (chunk.first_bucket == chunk.last_bucket)
            {
                return Err(bad("bucket count does not fit the bucket range"));
            }
            if chunk.first_variant != next_variant {
                return Err(bad("first variant is not contiguous"));
            }
            next_variant = next_variant
                .checked_add(chunk.variant_count)
                .ok_or_else(|| bad("variant ids exceed u32"))?;
            if c > 0 {
                let previous = &node.chunks[c - 1];
                if (previous.contig, previous.last_bucket) >= (chunk.contig, chunk.first_bucket) {
                    return Err(bad(
                        "not ordered after the previous chunk, or overlapping it",
                    ));
                }
            }
            for location in [chunk.mask, chunk.metadata, chunk.carriers] {
                self.check_location(location)?;
            }
        }
        if next_variant != node.variant_count {
            return Err(FormatError::corrupt(
                chunks_what,
                format!(
                    "node {i}: chunks hold {next_variant} variants, the node {}",
                    node.variant_count
                ),
            ));
        }
        if node.short_specs.windows(2).any(|w| w[0] >= w[1])
            || node
                .short_specs
                .last()
                .is_some_and(|&id| id >= node.variant_count)
        {
            return Err(FormatError::corrupt(
                "genomic index short specs",
                format!("node {i}: not strictly ascending below its variant count"),
            ));
        }
        for (e, entry) in node.infos.iter().enumerate() {
            let bad = |detail: &str| {
                FormatError::corrupt(
                    "genomic index info entries",
                    format!("node {i} entry {e}: {detail}"),
                )
            };
            if usize::from(entry.column) >= self.columns.len() {
                return Err(bad("column out of range"));
            }
            if e > 0 && node.infos[e - 1].column >= entry.column {
                return Err(bad("not ordered after the previous entry"));
            }
            if entry.continuous && entry.key_count == 0 {
                return Err(bad("a continuous column without keys"));
            }
            self.check_location(entry.info)?;
        }
        Ok(())
    }

    fn check_location(&self, location: GenoLocation) -> Result<()> {
        let what = "genomic index locations";
        if location.offset < GENO_BLOCKS_HEADER_LEN
            || location
                .offset
                .checked_add(u64::from(location.stored_len))
                .is_none()
        {
            return Err(FormatError::corrupt(
                what,
                format!("{location:?} is not inside geno.blocks"),
            ));
        }
        if location.decoded_len > MAX_GENO_BLOCK_LEN {
            return Err(FormatError::corrupt(
                what,
                format!("{location:?} decodes to more than {MAX_GENO_BLOCK_LEN} bytes"),
            ));
        }
        if u64::from(location.stored_len)
            > max_stored_len(location.decoded_len, self.header.encrypted)
        {
            return Err(FormatError::corrupt(
                what,
                format!("{location:?} stores more than the zstd bound of its payload"),
            ));
        }
        Ok(())
    }

    /// Serializes the index. `protection` must agree with the header's encrypted flag.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] or [`FormatError::UnexpectedKey`] when `protection`
    /// disagrees with the header, [`FormatError::InvalidInput`] when a count exceeds its
    /// field, and compression or encryption errors.
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
        out.push(self.mode as u8);
        put_u16(&mut out, self.groups.len(), "group count")?;
        for group in &self.groups {
            put_str(&mut out, &group.name)?;
            put_u32(&mut out, group.patients.len(), "patient count")?;
            for &id in &group.patients {
                out.extend_from_slice(&id.to_le_bytes());
            }
        }
        put_u16(&mut out, self.nodes.len(), "node count")?;
        for node in &self.nodes {
            out.extend_from_slice(&node.group.to_le_bytes());
            put_str(&mut out, &node.dir_name)?;
            put_u16(&mut out, node.contigs.len(), "contig count")?;
            for contig in &node.contigs {
                put_str(&mut out, contig)?;
            }
            out.extend_from_slice(&node.variant_count.to_le_bytes());
            put_u32(&mut out, node.chunks.len(), "chunk count")?;
            for c in &node.chunks {
                out.extend_from_slice(&c.contig.to_le_bytes());
                out.extend_from_slice(&c.first_bucket.to_le_bytes());
                out.extend_from_slice(&c.last_bucket.to_le_bytes());
                out.extend_from_slice(&c.first_variant.to_le_bytes());
                out.extend_from_slice(&c.variant_count.to_le_bytes());
                out.extend_from_slice(&c.bucket_count.to_le_bytes());
                for location in [c.mask, c.metadata, c.carriers] {
                    put_location(&mut out, location);
                }
            }
            put_u32(&mut out, node.short_specs.len(), "short spec count")?;
            for &id in &node.short_specs {
                out.extend_from_slice(&id.to_le_bytes());
            }
            put_u16(&mut out, node.infos.len(), "info count")?;
            for e in &node.infos {
                out.extend_from_slice(&e.column.to_le_bytes());
                out.push(u8::from(e.continuous));
                put_str(&mut out, &e.description)?;
                out.extend_from_slice(&e.key_count.to_le_bytes());
                put_location(&mut out, e.info);
            }
        }
        put_u16(&mut out, self.columns.len(), "column count")?;
        for column in &self.columns {
            put_str(&mut out, column)?;
        }
        Ok(out)
    }

    /// Reads only the plain header of an index file, for example to learn which key to load
    /// before calling [`GenoIndex::decode`].
    ///
    /// # Errors
    /// [`FormatError::BadMagic`], [`FormatError::UnsupportedVersion`],
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode_header(bytes: &[u8]) -> Result<GenoIndexHeader> {
        Ok(decode_preamble(bytes)?.0)
    }

    /// Parses and verifies a serialized index under the protection the caller requires, and
    /// checks every invariant of the format.
    ///
    /// # Errors
    /// [`FormatError::MissingKey`] when the index is encrypted and `protection` is plain;
    /// [`FormatError::UnexpectedKey`] when `protection` holds a key and the index is plain;
    /// [`FormatError::Authentication`] or [`FormatError::Checksum`] when the bytes were
    /// altered or the key is wrong; [`FormatError::PayloadTooLarge`] when the compressed body
    /// declares more than [`MAX_GENO_INDEX_BODY_LEN`]; [`FormatError::Truncated`] or
    /// [`FormatError::Corrupt`] for structural damage or a broken invariant.
    pub fn decode(bytes: &[u8], protection: &Protection) -> Result<Self> {
        let (header, preamble_len) = decode_preamble(bytes)?;
        protection.require(header.encrypted, INDEX_FILE)?;
        let mut r = Reader::new(&bytes[preamble_len..], "genomic index");
        let stored_len = r.u64()?;
        let stored_len = usize::try_from(stored_len)
            .ok()
            .filter(|&l| l <= r.remaining())
            .ok_or(FormatError::Truncated("genomic index"))?;
        let stored = r.bytes(stored_len)?;
        let body_end = preamble_len + 8 + stored_len;
        let opened;
        let body: &[u8] = if let Some(key) = protection.key() {
            r.finish()?;
            opened = open_framed(key, stored, &bytes[..preamble_len])?;
            &opened
        } else {
            let checksum = r.u64()?;
            r.finish()?;
            if xxh3_64(&bytes[..body_end]) != checksum {
                return Err(FormatError::Checksum("genomic index"));
            }
            stored
        };
        let body = if header.compressed {
            zstd_decompress_aligned(body, MAX_GENO_INDEX_BODY_LEN, "genomic index body")?
        } else if body.len() as u64 > MAX_GENO_INDEX_BODY_LEN {
            return Err(FormatError::PayloadTooLarge {
                what: "genomic index body",
                declared: body.len() as u64,
                limit: MAX_GENO_INDEX_BODY_LEN,
            });
        } else {
            AlignedBuf::copy_from(body)
        };
        let index = decode_body(header, body.as_bytes())?;
        index.check()?;
        Ok(index)
    }

    /// Serializes the index and writes it to `path`.
    ///
    /// # Errors
    /// The errors of [`GenoIndex::encode`] and [`FormatError::Io`].
    pub fn write_file(&self, path: impl AsRef<Path>, protection: &Protection) -> Result<()> {
        std::fs::write(path, self.encode(protection)?)?;
        Ok(())
    }

    /// Reads and verifies the index at `path`.
    ///
    /// # Errors
    /// The errors of [`GenoIndex::decode`] and [`FormatError::Io`].
    pub fn read_file(path: impl AsRef<Path>, protection: &Protection) -> Result<Self> {
        Self::decode(&std::fs::read(path)?, protection)
    }
}

fn put_u16(out: &mut Vec<u8>, value: usize, what: &str) -> Result<()> {
    let value = u16::try_from(value)
        .map_err(|_| FormatError::InvalidInput(format!("{what} {value} exceeds u16")))?;
    out.extend_from_slice(&value.to_le_bytes());
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

fn put_location(out: &mut Vec<u8>, location: GenoLocation) {
    out.extend_from_slice(&location.offset.to_le_bytes());
    out.extend_from_slice(&location.stored_len.to_le_bytes());
    out.extend_from_slice(&location.decoded_len.to_le_bytes());
}

fn encode_preamble(header: &GenoIndexHeader) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    out.extend_from_slice(GENO_INDEX_MAGIC);
    out.extend_from_slice(&GENO_FORMAT_VERSION.to_le_bytes());
    let mut flags = 0u16;
    if header.encrypted {
        flags |= FLAG_ENCRYPTED;
    }
    if header.compressed {
        flags |= FLAG_COMPRESSED;
    }
    out.extend_from_slice(&flags.to_le_bytes());
    out.extend_from_slice(header.store_id.as_bytes());
    out.push(header.key_source);
    put_str(&mut out, &header.key_name)?;
    out.extend_from_slice(&header.created_at_millis.to_le_bytes());
    put_str(&mut out, &header.etl_version)?;
    Ok(out)
}

fn decode_preamble(bytes: &[u8]) -> Result<(GenoIndexHeader, usize)> {
    let mut r = Reader::new(bytes, "genomic index header");
    if r.bytes(GENO_INDEX_MAGIC.len())? != GENO_INDEX_MAGIC {
        return Err(FormatError::BadMagic { file: INDEX_FILE });
    }
    let format_version = r.u16()?;
    if format_version != GENO_FORMAT_VERSION {
        return Err(FormatError::UnsupportedVersion(format_version));
    }
    let flags = r.u16()?;
    if flags & !KNOWN_FLAGS != 0 {
        return Err(FormatError::corrupt(
            "genomic index header",
            format!("unknown flags {flags:#06x}"),
        ));
    }
    let mut id = [0u8; STORE_ID_LEN];
    id.copy_from_slice(r.bytes(STORE_ID_LEN)?);
    let key_source = r.u8()?;
    if key_source != KEY_SOURCE_PHENOTYPE {
        return Err(FormatError::corrupt(
            "genomic index header",
            format!("key source {key_source} is not supported"),
        ));
    }
    let key_name = r.string()?;
    let created_at_millis = r.i64()?;
    let etl_version = r.string()?;
    let encrypted = flags & FLAG_ENCRYPTED != 0;
    if !encrypted && !key_name.is_empty() {
        return Err(FormatError::corrupt(
            "genomic index header",
            "a plain store names a key",
        ));
    }
    let header = GenoIndexHeader {
        format_version,
        encrypted,
        compressed: flags & FLAG_COMPRESSED != 0,
        store_id: StoreId::from_bytes(id),
        key_source,
        key_name,
        created_at_millis,
        etl_version,
    };
    Ok((header, r.pos))
}

fn decode_body(header: GenoIndexHeader, body: &[u8]) -> Result<GenoIndex> {
    let mut r = Reader::new(body, "genomic index body");
    let mode_byte = r.u8()?;
    let mode = GenoMode::from_byte(mode_byte).ok_or_else(|| {
        FormatError::corrupt("genomic index body", format!("unknown mode {mode_byte}"))
    })?;
    let group_count = usize::from(r.u16()?);
    let group_count = r.count(group_count, 8)?;
    let mut groups = Vec::with_capacity(group_count);
    for _ in 0..group_count {
        let name = r.string()?;
        let patient_count = r.u32()? as usize;
        let patient_count = r.count(patient_count, 4)?;
        let mut patients = Vec::with_capacity(patient_count);
        for _ in 0..patient_count {
            patients.push(r.u32()?);
        }
        groups.push(GenoGroup { name, patients });
    }
    let node_count = usize::from(r.u16()?);
    let node_count = r.count(node_count, 22)?;
    let mut nodes = Vec::with_capacity(node_count);
    for _ in 0..node_count {
        let group = r.u16()?;
        let dir_name = r.string()?;
        let contig_count = usize::from(r.u16()?);
        let contig_count = r.count(contig_count, 4)?;
        let mut contigs = Vec::with_capacity(contig_count);
        for _ in 0..contig_count {
            contigs.push(r.string()?);
        }
        let variant_count = r.u32()?;
        let chunk_count = r.u32()? as usize;
        let chunk_count = r.count(chunk_count, CHUNK_ENTRY_LEN)?;
        let mut chunks = Vec::with_capacity(chunk_count);
        for _ in 0..chunk_count {
            chunks.push(GenoChunkEntry {
                contig: r.u16()?,
                first_bucket: r.i32()?,
                last_bucket: r.i32()?,
                first_variant: r.u32()?,
                variant_count: r.u32()?,
                bucket_count: r.u32()?,
                mask: r.location()?,
                metadata: r.location()?,
                carriers: r.location()?,
            });
        }
        let short_count = r.u32()? as usize;
        let short_count = r.count(short_count, 4)?;
        let mut short_specs = Vec::with_capacity(short_count);
        for _ in 0..short_count {
            short_specs.push(r.u32()?);
        }
        let info_count = usize::from(r.u16()?);
        let info_count = r.count(info_count, 7 + 4 + LOCATION_LEN)?;
        let mut infos = Vec::with_capacity(info_count);
        for _ in 0..info_count {
            let column = r.u16()?;
            let continuous = match r.u8()? {
                0 => false,
                1 => true,
                other => {
                    return Err(FormatError::corrupt(
                        "genomic index info entries",
                        format!("continuous byte {other}"),
                    ));
                }
            };
            infos.push(GenoInfoEntry {
                column,
                continuous,
                description: r.string()?,
                key_count: r.u32()?,
                info: r.location()?,
            });
        }
        nodes.push(GenoNode {
            group,
            dir_name,
            contigs,
            variant_count,
            chunks,
            short_specs,
            infos,
        });
    }
    let column_count = usize::from(r.u16()?);
    let column_count = r.count(column_count, 4)?;
    let mut columns = Vec::with_capacity(column_count);
    for _ in 0..column_count {
        columns.push(r.string()?);
    }
    r.finish()?;
    Ok(GenoIndex {
        header,
        mode,
        groups,
        nodes,
        columns,
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

    /// Checks that `count` items of at least `width` bytes each fit in the bytes left.
    fn count(&self, count: usize, width: usize) -> Result<usize> {
        count
            .checked_mul(width)
            .filter(|&need| need <= self.remaining())
            .map(|_| count)
            .ok_or(FormatError::Truncated(self.what))
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
        let mut out = [0u8; N];
        out.copy_from_slice(self.bytes(N)?);
        Ok(out)
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

    fn location(&mut self) -> Result<GenoLocation> {
        Ok(GenoLocation {
            offset: self.u64()?,
            stored_len: self.u32()?,
            decoded_len: self.u32()?,
        })
    }

    fn string(&mut self) -> Result<String> {
        let len = self.u32()? as usize;
        let bytes = self.bytes(len)?;
        std::str::from_utf8(bytes)
            .map(str::to_owned)
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
