//! Writes a genomic store: one [`NodeBuilder`] per node turns buckets of variants into
//! sealed chunk blocks and info blocks in a temporary file of its own, and [`assemble`]
//! concatenates the nodes' blocks into `geno.blocks` in node order (each node's info blocks,
//! then per chunk its carrier, mask and metadata blocks) and writes `geno.index`.
//!
//! Sealing binds every block to its node index and, for info blocks, to the column's index
//! in the store's column table, so both are fixed before any node is built. Block locations
//! are relative to the node's temporary file until [`assemble`] moves them.
//!
//! [`InfoColumnsBuilder`] turns a node's INFO columns, whose keys name variants by a source
//! id (Java's spec index id), into info blocks once every variant has its node id, with the
//! carriers of each categorical key gathered from the masks as the buckets go by.

use std::cmp::Ordering;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::os::unix::fs::FileExt;
use std::path::{Path, PathBuf};

use hpds_format::{
    CompressedRange, GENO_BLOCKS_FILE_NAME, GENO_INDEX_FILE_NAME, GenoBlockCodec, GenoBlockKind,
    GenoBlocksWriter, GenoChunkEntry, GenoGroup, GenoIndex, GenoIndexHeader, GenoInfoEntry,
    GenoLocation, GenoMode, GenoNode, InfoKeyInput, JavaMaskInput, KEY_SOURCE_PHENOTYPE,
    MASK_CHUNK_TARGET, MaskVariantInput, Protection, RangeBound, StoreId,
    build_java_compressed_index, encode_carrier_chunk, encode_info_block, encode_mask_chunk,
    encode_metadata_chunk, java_float_cmp, java_string_cmp,
};
use roaring::RoaringBitmap;

use crate::error::{EtlError, Result};
use crate::load::DEFAULT_KEY_NAME;
use crate::private_fs;

/// One mask of a variant: Java's form and the patient positions, strictly ascending.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct VariantMask {
    /// True when Java holds the mask as `VariantMaskBitmaskImpl`.
    pub bitmask_form: bool,
    /// Positions in the group's patient list.
    pub positions: Vec<u32>,
}

/// One variant of a bucket, as [`NodeBuilder::add_bucket`] takes it.
#[derive(Clone, Debug)]
pub struct BucketVariant {
    /// The spec string.
    pub spec: String,
    /// Java's mask record, `None` when the bucket holds none for the spec; indexed `he`,
    /// `ho`, `hen`, `hon`.
    pub record: Option<[Option<VariantMask>; 4]>,
    /// The raw INFO strings, in Java's order.
    pub metadata: Vec<String>,
}

/// What a node's blocks hold, with block locations relative to the node's temporary file.
#[derive(Debug)]
pub struct BuiltNode {
    /// The node's index entry, its locations relative to [`BuiltNode::blocks`].
    pub node: GenoNode,
    /// The temporary file holding the node's sealed blocks.
    pub blocks: PathBuf,
}

/// A chunk being filled: whole buckets of one contig.
#[derive(Default)]
struct PendingChunk {
    variants: Vec<BucketVariant>,
    carriers: Vec<Vec<u32>>,
    estimated_bytes: usize,
}

/// Builds one node's blocks into a temporary file.
pub struct NodeBuilder {
    node_index: u16,
    group: u16,
    dir_name: String,
    patient_count: u32,
    codec: GenoBlockCodec,
    path: PathBuf,
    out: BufWriter<File>,
    offset: u64,
    contigs: Vec<String>,
    chunks: Vec<GenoChunkEntry>,
    short_specs: Vec<u32>,
    infos: Vec<GenoInfoEntry>,
    variant_count: u32,
    pending: PendingChunk,
    last_bucket: Option<i32>,
}

impl NodeBuilder {
    /// Starts node `node_index` of group `group`, whose patient list has `patient_count`
    /// entries, writing its blocks into the new file `path`.
    ///
    /// # Errors
    /// I/O errors creating the file.
    pub fn new(
        node_index: u16,
        group: u16,
        dir_name: String,
        patient_count: u32,
        codec: GenoBlockCodec,
        path: PathBuf,
    ) -> Result<Self> {
        let file = private_fs::create_file(&path).map_err(EtlError::io("creating", &path))?;
        Ok(NodeBuilder {
            node_index,
            group,
            dir_name,
            patient_count,
            codec,
            out: BufWriter::with_capacity(1 << 20, file),
            path,
            offset: 0,
            contigs: Vec::new(),
            chunks: Vec::new(),
            short_specs: Vec::new(),
            infos: Vec::new(),
            variant_count: 0,
            pending: PendingChunk::default(),
            last_bucket: None,
        })
    }

    /// The node variant id the next variant added gets.
    pub fn next_variant_id(&self) -> u32 {
        self.variant_count + self.pending.variants.len() as u32
    }

    /// Adds one bucket of `contig`: its variants, already in the store's spec order, and its
    /// carriers (the positions holding `he`, `hen` or `ho` on any of them, as the variant
    /// list prefilter reads them). Buckets come in (contig, bucket) order, contigs ascending
    /// in Java `String.compareTo` order. The variants get consecutive node ids from
    /// [`NodeBuilder::next_variant_id`].
    ///
    /// # Errors
    /// [`EtlError::Usage`] when buckets come out of order or a bucket is empty, and the
    /// errors of sealing a chunk this bucket completes.
    pub fn add_bucket(
        &mut self,
        contig: &str,
        bucket: i32,
        variants: Vec<BucketVariant>,
        carriers: Vec<u32>,
    ) -> Result<()> {
        if variants.is_empty() {
            return Err(EtlError::Usage(format!(
                "node {}: contig {contig} bucket {bucket} holds no variant",
                self.node_index
            )));
        }
        let same_contig = self.contigs.last().is_some_and(|c| c == contig);
        if !same_contig {
            if let Some(last) = self.contigs.last()
                && java_string_cmp(last, contig) != Ordering::Less
            {
                return Err(EtlError::Usage(format!(
                    "node {}: contig {contig} comes after {last}",
                    self.node_index
                )));
            }
            self.flush()?;
            self.contigs.push(contig.to_string());
            self.last_bucket = None;
        } else if self.last_bucket.is_some_and(|last| last >= bucket) {
            return Err(EtlError::Usage(format!(
                "node {}: contig {contig} bucket {bucket} comes out of order",
                self.node_index
            )));
        }
        self.last_bucket = Some(bucket);
        let dense = 1 + self.patient_count.div_ceil(8) as usize;
        for variant in &variants {
            self.pending.estimated_bytes += variant.spec.len() + 10;
            if let Some(record) = &variant.record {
                for mask in record.iter().flatten() {
                    self.pending.estimated_bytes += 4 + dense.min(2 + 2 * mask.positions.len());
                }
            }
        }
        self.pending.variants.extend(variants);
        self.pending.carriers.push(carriers);
        if self.pending.estimated_bytes >= MASK_CHUNK_TARGET {
            self.flush()?;
        }
        Ok(())
    }

    /// Seals the pending chunk, if any: its carrier, mask and metadata blocks.
    fn flush(&mut self) -> Result<()> {
        if self.pending.variants.is_empty() {
            return Ok(());
        }
        let pending = std::mem::take(&mut self.pending);
        let contig_index = (self.contigs.len() - 1) as u16;
        let contig = &self.contigs[usize::from(contig_index)];
        let inputs: Vec<MaskVariantInput<'_>> = pending
            .variants
            .iter()
            .map(|v| MaskVariantInput {
                spec: &v.spec,
                record: v.record.as_ref().map(|record| {
                    record.each_ref().map(|mask| {
                        mask.as_ref().map(|m| JavaMaskInput {
                            positions: &m.positions,
                            bitmask_form: m.bitmask_form,
                        })
                    })
                }),
            })
            .collect();
        let first_variant = self.variant_count;
        let encoded = encode_mask_chunk(
            contig_index,
            contig,
            first_variant,
            self.patient_count,
            &inputs,
        )?;
        if encoded.bucket_count as usize != pending.carriers.len() {
            return Err(EtlError::Usage(format!(
                "node {}: a chunk of {} buckets has {} carrier sets",
                self.node_index,
                encoded.bucket_count,
                pending.carriers.len()
            )));
        }
        let metadata: Vec<&[String]> = pending
            .variants
            .iter()
            .map(|v| v.metadata.as_slice())
            .collect();
        let metadata = encode_metadata_chunk(&metadata)?;
        let carriers = encode_carrier_chunk(&pending.carriers, self.patient_count)?;
        let ordinal = self.chunks.len() as u32;
        let carriers = self.seal(GenoBlockKind::Carriers, ordinal, &carriers)?;
        let mask = self.seal(GenoBlockKind::Mask, ordinal, &encoded.payload)?;
        let metadata = self.seal(GenoBlockKind::Metadata, ordinal, &metadata)?;
        let n = pending.variants.len() as u32;
        self.chunks.push(GenoChunkEntry {
            contig: contig_index,
            first_bucket: encoded.first_bucket,
            last_bucket: encoded.last_bucket,
            first_variant,
            variant_count: n,
            bucket_count: encoded.bucket_count,
            mask,
            metadata,
            carriers,
        });
        self.short_specs.extend_from_slice(&encoded.short_specs);
        self.variant_count = self.variant_count.checked_add(n).ok_or_else(|| {
            EtlError::Usage(format!("node {}: over u32 variants", self.node_index))
        })?;
        Ok(())
    }

    /// Seals `payload` as a block of `kind` and `ordinal` and appends it to the file.
    fn seal(&mut self, kind: GenoBlockKind, ordinal: u32, payload: &[u8]) -> Result<GenoLocation> {
        let stored = self.codec.seal(kind, self.node_index, ordinal, payload)?;
        let stored_len = u32::try_from(stored.len()).map_err(|_| {
            EtlError::Usage(format!("node {}: a block over u32 bytes", self.node_index))
        })?;
        self.out
            .write_all(&stored)
            .map_err(EtlError::io("writing", &self.path))?;
        let location = GenoLocation {
            offset: self.offset,
            stored_len,
            decoded_len: payload.len() as u32,
        };
        self.offset += u64::from(stored_len);
        Ok(location)
    }

    /// Seals the info block `payload` of column `column` (its index in the store's column
    /// table). Called after the last bucket, in ascending column order.
    ///
    /// # Errors
    /// [`EtlError::Usage`] for columns out of order, sealing and I/O errors.
    pub fn add_info(
        &mut self,
        column: u16,
        continuous: bool,
        description: String,
        key_count: u32,
        payload: &[u8],
    ) -> Result<()> {
        self.flush()?;
        if self.infos.last().is_some_and(|e| e.column >= column) {
            return Err(EtlError::Usage(format!(
                "node {}: info column {column} comes out of order",
                self.node_index
            )));
        }
        let info = self.seal(GenoBlockKind::Info, u32::from(column), payload)?;
        self.infos.push(GenoInfoEntry {
            column,
            continuous,
            description,
            key_count,
            info,
        });
        Ok(())
    }

    /// Seals the last chunk. Info blocks may still follow.
    ///
    /// # Errors
    /// The errors of sealing the chunk.
    pub fn finish_chunks(&mut self) -> Result<()> {
        self.flush()
    }

    /// The variants added so far, sealed or pending.
    pub fn variant_count(&self) -> u32 {
        self.next_variant_id()
    }

    /// Chunks sealed so far.
    pub fn chunk_count(&self) -> usize {
        self.chunks.len()
    }

    /// Short spec ids found so far.
    pub fn short_spec_count(&self) -> usize {
        self.short_specs.len()
    }

    /// Seals anything pending, flushes the file and returns the node.
    ///
    /// # Errors
    /// Sealing and I/O errors.
    pub fn finish(mut self) -> Result<BuiltNode> {
        self.flush()?;
        let file = self
            .out
            .into_inner()
            .map_err(|e| EtlError::io("writing", &self.path)(e.into_error()))?;
        drop(file);
        Ok(BuiltNode {
            node: GenoNode {
                group: self.group,
                dir_name: self.dir_name,
                contigs: self.contigs,
                variant_count: self.variant_count,
                chunks: self.chunks,
                short_specs: self.short_specs,
                infos: self.infos,
            },
            blocks: self.path,
        })
    }
}

/// The header of a new store sealed under `protection` with the phenotype store's key.
pub fn store_header(protection: &Protection, store_id: StoreId) -> GenoIndexHeader {
    GenoIndexHeader {
        format_version: hpds_format::GENO_FORMAT_VERSION,
        encrypted: protection.is_encrypted(),
        compressed: true,
        store_id,
        key_source: KEY_SOURCE_PHENOTYPE,
        key_name: if protection.is_encrypted() {
            DEFAULT_KEY_NAME.into()
        } else {
            String::new()
        },
        created_at_millis: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as i64)
            .unwrap_or(0),
        etl_version: crate::banner(),
    }
}

/// Sizes of the files [`assemble`] wrote.
#[derive(Clone, Copy, Debug, Default)]
pub struct AssembledSizes {
    /// Bytes of `geno.blocks`.
    pub blocks_bytes: u64,
    /// Bytes of `geno.index`.
    pub index_bytes: u64,
}

/// Writes `geno.blocks` and `geno.index` into `dir` from `built`, given in node index order:
/// per node its info blocks, then per chunk its carrier, mask and metadata blocks, copied
/// from the node's temporary file and relocated. Removes each temporary file after copying
/// it. The index is built last, so [`GenoIndex::new`] checks every invariant of the whole
/// store before anything refers to it.
///
/// # Errors
/// [`EtlError::Format`] for an index that breaks an invariant; I/O errors.
pub fn assemble(
    dir: &Path,
    header: GenoIndexHeader,
    mode: GenoMode,
    groups: Vec<GenoGroup>,
    built: Vec<BuiltNode>,
    columns: Vec<String>,
    protection: &Protection,
) -> Result<(GenoIndex, AssembledSizes)> {
    let blocks_path = dir.join(GENO_BLOCKS_FILE_NAME);
    let file =
        private_fs::create_file(&blocks_path).map_err(EtlError::io("creating", &blocks_path))?;
    let mut writer =
        GenoBlocksWriter::new(BufWriter::with_capacity(4 << 20, file), header.store_id)?;
    let mut nodes = Vec::with_capacity(built.len());
    for BuiltNode { mut node, blocks } in built {
        let source = File::open(&blocks).map_err(EtlError::io("opening", &blocks))?;
        let mut copy = |location: GenoLocation| -> Result<GenoLocation> {
            let mut stored = vec![0u8; location.stored_len as usize];
            source
                .read_exact_at(&mut stored, location.offset)
                .map_err(EtlError::io("reading", &blocks))?;
            Ok(writer.append_stored(&stored, location.decoded_len)?)
        };
        for info in &mut node.infos {
            info.info = copy(info.info)?;
        }
        for chunk in &mut node.chunks {
            chunk.carriers = copy(chunk.carriers)?;
            chunk.mask = copy(chunk.mask)?;
            chunk.metadata = copy(chunk.metadata)?;
        }
        drop(source);
        std::fs::remove_file(&blocks).map_err(EtlError::io("removing", &blocks))?;
        nodes.push(node);
    }
    let blocks_bytes = writer.len();
    writer
        .finish()?
        .into_inner()
        .map_err(|e| EtlError::io("writing", &blocks_path)(e.into_error()))?
        .sync_all()
        .map_err(EtlError::io("syncing", &blocks_path))?;
    let index = GenoIndex::new(header, mode, groups, nodes, columns)?;
    let encoded = index.encode(protection)?;
    let index_path = dir.join(GENO_INDEX_FILE_NAME);
    private_fs::write_file(&index_path, &encoded).map_err(EtlError::io("writing", &index_path))?;
    Ok((
        index,
        AssembledSizes {
            blocks_bytes,
            index_bytes: encoded.len() as u64,
        },
    ))
}

/// One INFO column of a node, as [`InfoColumnsBuilder::new`] takes it.
#[derive(Clone, Debug)]
pub struct InfoColumnInput {
    /// The column's index in the store's column table.
    pub column: u16,
    /// The column's name, for messages.
    pub name: String,
    /// `FileBackedByteIndexedInfoStore.description`.
    pub description: String,
    /// The keys, strictly ascending in Java order, each with the source ids of its variants.
    pub keys: Vec<(String, Vec<u32>)>,
    /// For a continuous column its `CompressedIndex`, entries naming keys by index, in any
    /// range order; `None` for a categorical column.
    pub ranges: Option<Vec<CompressedRange>>,
}

/// Builds a node's info blocks: maps each source id to its node variant id, gathers per
/// categorical key the patients holding `he` or `ho` on any of its variants, and encodes and
/// seals the blocks once the node's buckets are done. Carriers are kept as roaring sets of
/// positions, so memory follows the carriers, not the keys times the patient count.
pub struct InfoColumnsBuilder {
    columns: Vec<InfoColumnInput>,
    ref_offsets: Vec<u32>,
    refs: Vec<(u16, u32)>,
    carriers: Vec<Vec<Option<RoaringBitmap>>>,
    source_to_variant: Vec<u32>,
}

impl InfoColumnsBuilder {
    /// Starts the info blocks of a node whose source ids run below `source_count`.
    ///
    /// # Errors
    /// [`EtlError::Usage`] naming the column when its keys are not strictly ascending in
    /// Java order, a source id is out of range, or columns come out of order.
    pub fn new(columns: Vec<InfoColumnInput>, source_count: u32) -> Result<Self> {
        let refuse =
            |name: &str, detail: &str| EtlError::Usage(format!("column {name:?}: {detail}"));
        if columns.windows(2).any(|w| w[0].column >= w[1].column) {
            return Err(EtlError::Usage("info columns come out of order".into()));
        }
        let mut offsets = vec![0u32; source_count as usize + 1];
        for c in &columns {
            if c.keys
                .windows(2)
                .any(|w| java_string_cmp(&w[0].0, &w[1].0).is_ge())
            {
                return Err(refuse(
                    &c.name,
                    "keys are not strictly ascending in Java order",
                ));
            }
            for (key, ids) in &c.keys {
                if let Some(id) = ids.iter().find(|&&id| id >= source_count) {
                    return Err(refuse(
                        &c.name,
                        &format!("key {key:?} names variant {id}, outside {source_count}"),
                    ));
                }
                if c.ranges.is_none() {
                    for &id in ids {
                        offsets[id as usize + 1] += 1;
                    }
                }
            }
        }
        for i in 1..offsets.len() {
            offsets[i] += offsets[i - 1];
        }
        let mut fill = offsets.clone();
        let mut refs = vec![(0u16, 0u32); offsets[source_count as usize] as usize];
        for (c, column) in columns.iter().enumerate() {
            if column.ranges.is_some() {
                continue;
            }
            for (k, (_, ids)) in column.keys.iter().enumerate() {
                for &id in ids {
                    let slot = &mut fill[id as usize];
                    refs[*slot as usize] = (c as u16, k as u32);
                    *slot += 1;
                }
            }
        }
        let carriers = columns
            .iter()
            .map(|c| {
                if c.ranges.is_some() {
                    Vec::new()
                } else {
                    vec![None; c.keys.len()]
                }
            })
            .collect();
        Ok(InfoColumnsBuilder {
            columns,
            ref_offsets: offsets,
            refs,
            carriers,
            source_to_variant: vec![u32::MAX; source_count as usize],
        })
    }

    /// Gives source id `source` the node variant id `variant`.
    ///
    /// # Errors
    /// A message when the source id is out of range or already has a variant.
    pub fn assign(&mut self, source: u32, variant: u32) -> std::result::Result<(), String> {
        let target = self
            .source_to_variant
            .get_mut(source as usize)
            .ok_or_else(|| format!("source id {source} out of range"))?;
        if *target != u32::MAX {
            return Err(format!("source id {source} is placed twice"));
        }
        *target = variant;
        Ok(())
    }

    /// Adds the `he` and `ho` positions of the variant named by source id `source` to the
    /// carriers of every categorical key holding it.
    pub fn add_carriers(&mut self, source: u32, he: &[u32], ho: &[u32]) {
        let source = source as usize;
        let Some(range) = self
            .ref_offsets
            .get(source..source + 2)
            .map(|w| w[0] as usize..w[1] as usize)
        else {
            return;
        };
        for &(c, k) in &self.refs[range] {
            let set =
                self.carriers[usize::from(c)][k as usize].get_or_insert_with(RoaringBitmap::new);
            set.extend(he.iter().copied());
            set.extend(ho.iter().copied());
        }
    }

    /// The first source id without a variant, if any.
    pub fn unassigned(&self) -> Option<u32> {
        self.source_to_variant
            .iter()
            .position(|&v| v == u32::MAX)
            .map(|id| id as u32)
    }

    /// Encodes and seals every column's info block into `builder`, for a node of
    /// `patient_count` patients whose variants are all added. Returns the names of the
    /// continuous columns whose `CompressedIndex` differs, bit for bit, from the one the
    /// Rust port builds from the same keys.
    ///
    /// # Errors
    /// [`EtlError::Usage`] naming the column for a source id without a variant, a
    /// continuous column without keys or ranges, or a block the format refuses; sealing and
    /// I/O errors.
    pub fn finish(self, builder: &mut NodeBuilder, patient_count: u32) -> Result<Vec<String>> {
        if let Some(id) = self.unassigned() {
            return Err(EtlError::Usage(format!("source id {id} has no variant")));
        }
        let variant_count = builder.variant_count();
        let mut differences = Vec::new();
        for (column, carriers) in self.columns.into_iter().zip(self.carriers) {
            let refuse =
                |detail: String| EtlError::Usage(format!("column {:?}: {detail}", column.name));
            let variants: Vec<RoaringBitmap> = column
                .keys
                .iter()
                .map(|(_, ids)| {
                    ids.iter()
                        .map(|&id| self.source_to_variant[id as usize])
                        .collect()
                })
                .collect();
            let positions: Vec<Vec<u32>> = carriers
                .into_iter()
                .map(|set| set.map(|s| s.iter().collect()).unwrap_or_default())
                .collect();
            let ranges = match column.ranges {
                None => None,
                Some(mut ranges) => {
                    if column.keys.is_empty() || ranges.is_empty() {
                        return Err(refuse(format!(
                            "a continuous column with {} keys and {} CompressedIndex ranges; Java's query on it throws",
                            column.keys.len(),
                            ranges.len()
                        )));
                    }
                    sort_compressed_ranges(&mut ranges);
                    let keys: Vec<&str> = column.keys.iter().map(|(k, _)| k.as_str()).collect();
                    let ported = build_java_compressed_index(&keys);
                    if ported.as_ref().is_none_or(|p| !same_ranges(p, &ranges)) {
                        differences.push(column.name.clone());
                    }
                    Some(ranges)
                }
            };
            let continuous = ranges.is_some();
            let inputs: Vec<InfoKeyInput<'_>> = column
                .keys
                .iter()
                .enumerate()
                .map(|(k, (key, _))| InfoKeyInput {
                    key,
                    variants: &variants[k],
                    carriers: (!continuous).then(|| positions[k].as_slice()),
                })
                .collect();
            let payload =
                encode_info_block(&inputs, ranges.as_deref(), variant_count, patient_count)
                    .map_err(|e| refuse(e.to_string()))?;
            builder.add_info(
                column.column,
                continuous,
                column.description,
                column.keys.len() as u32,
                &payload,
            )?;
        }
        Ok(differences)
    }
}

/// True when two range lists are equal bit for bit: bounds and entry floats compared by
/// their bits, so `-0.0` and `0.0`, and NaN payloads, count as different.
fn same_ranges(a: &[CompressedRange], b: &[CompressedRange]) -> bool {
    let bits = |bound: RangeBound| match bound {
        RangeBound::Unbounded => (0u8, 0u32),
        RangeBound::Open(v) => (1, v.to_bits()),
        RangeBound::Closed(v) => (2, v.to_bits()),
    };
    a.len() == b.len()
        && a.iter().zip(b).all(|(x, y)| {
            bits(x.lower) == bits(y.lower)
                && bits(x.upper) == bits(y.upper)
                && x.entries.len() == y.entries.len()
                && x.entries
                    .iter()
                    .zip(&y.entries)
                    .all(|(e, f)| e.0.to_bits() == f.0.to_bits() && e.1 == f.1)
        })
}

/// Guava's cut order of a lower bound: `CLOSED v` is the cut below `v`, `OPEN v` the cut
/// above it, unbounded the cut below everything. Returns (rank, value, above).
fn lower_cut(bound: RangeBound) -> (u8, f32, bool) {
    match bound {
        RangeBound::Unbounded => (0, 0.0, false),
        RangeBound::Closed(v) => (1, v, false),
        RangeBound::Open(v) => (1, v, true),
    }
}

/// Guava's cut order of an upper bound: `CLOSED v` is the cut above `v`, `OPEN v` the cut
/// below it, unbounded the cut above everything.
fn upper_cut(bound: RangeBound) -> (u8, f32, bool) {
    match bound {
        RangeBound::Unbounded => (2, 0.0, false),
        RangeBound::Closed(v) => (1, v, true),
        RangeBound::Open(v) => (1, v, false),
    }
}

fn cut_cmp(a: (u8, f32, bool), b: (u8, f32, bool)) -> Ordering {
    a.0.cmp(&b.0)
        .then_with(|| {
            if a.0 == 1 {
                java_float_cmp(a.1, b.1)
            } else {
                Ordering::Equal
            }
        })
        .then(a.2.cmp(&b.2))
}

/// Sorts `ranges` into the stored order of an info block: by lower cut, then upper cut.
pub fn sort_compressed_ranges(ranges: &mut [CompressedRange]) {
    ranges.sort_by(|a, b| {
        cut_cmp(lower_cut(a.lower), lower_cut(b.lower))
            .then_with(|| cut_cmp(upper_cut(a.upper), upper_cut(b.upper)))
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use hpds_format::build_java_compressed_index;

    #[test]
    fn ranges_sort_as_the_format_stores_them() {
        let keys: Vec<String> = (0..2500).map(|i| format!("{}", i as f32 / 7.0)).collect();
        let mut sorted: Vec<&str> = keys.iter().map(String::as_str).collect();
        sorted.sort_by(|a, b| java_string_cmp(a, b));
        let built = build_java_compressed_index(&sorted).unwrap();
        let mut shuffled = built.clone();
        shuffled.reverse();
        shuffled.swap(0, 1);
        sort_compressed_ranges(&mut shuffled);
        assert_eq!(shuffled, built);
    }

    #[test]
    fn cuts_order_below_before_above_at_equal_values() {
        let mut ranges = vec![
            CompressedRange {
                lower: RangeBound::Open(1.0),
                upper: RangeBound::Closed(2.0),
                entries: vec![],
            },
            CompressedRange {
                lower: RangeBound::Closed(1.0),
                upper: RangeBound::Closed(2.0),
                entries: vec![],
            },
            CompressedRange {
                lower: RangeBound::Unbounded,
                upper: RangeBound::Open(2.0),
                entries: vec![],
            },
            CompressedRange {
                lower: RangeBound::Closed(1.0),
                upper: RangeBound::Open(2.0),
                entries: vec![],
            },
        ];
        sort_compressed_ranges(&mut ranges);
        let order: Vec<(RangeBound, RangeBound)> =
            ranges.iter().map(|r| (r.lower, r.upper)).collect();
        assert_eq!(
            order,
            vec![
                (RangeBound::Unbounded, RangeBound::Open(2.0)),
                (RangeBound::Closed(1.0), RangeBound::Open(2.0)),
                (RangeBound::Closed(1.0), RangeBound::Closed(2.0)),
                (RangeBound::Open(1.0), RangeBound::Closed(2.0)),
            ]
        );
    }
}
