//! Reads the export the Java `ExportGenomicForMigration` writes: a manifest
//! ([`MANIFEST_FILE`]) and one file per node, each in the encrypted container of the
//! phenotype export (see [`crate::migrate::EXPORT_CONCEPTS_FILE`]), all under one export
//! id, sealed with the phenotype store's key.
//!
//! The layout of both files is documented on the Java class. In short, a node file holds
//! the node's group and directory names, its patient ids as Java stores them, the length of
//! its spec index, whether it has a bucket index, its info stores (keys with their spec
//! index ids, and for continuous columns the `CompressedIndex` ranges and maps), then one
//! record per bucket in (contig, bucket) order: the mask records, the metadata, the spec
//! index entries placed in the bucket, and the bucket's carriers from Java's
//! `BucketIndexBySample`. Every integer is big-endian; a string is an `i32` UTF-8 length and
//! the bytes.

use std::fs::File;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};

use hpds_format::{CryptoKey, GenoMode, RangeBound};

use crate::error::{EtlError, Result};
use crate::migrate::{EXPORT_ID_LEN, ExportDecryptor, ExportKind, export_io_error};

/// Name of the export's manifest, which the exporter renames into place last.
pub const MANIFEST_FILE: &str = "genomic.bin";

/// Largest count of items read before allocating for them, so a damaged count cannot ask
/// for an unbounded allocation up front.
const PREALLOCATE_LIMIT: usize = 1 << 16;

/// The export's manifest: the layout and every node in export order.
#[derive(Clone, Debug)]
pub struct Manifest {
    /// The layout the Java store was exported in.
    pub mode: GenoMode,
    /// The nodes, ordered by group, then directory name.
    pub nodes: Vec<ManifestNode>,
    /// The export id every file of the export carries.
    pub export_id: [u8; EXPORT_ID_LEN],
}

/// One node of the manifest.
#[derive(Clone, Debug)]
pub struct ManifestNode {
    /// The group directory name; empty in `local` and `localDistributed`.
    pub group: String,
    /// The chromosome directory name; empty in `local` and `localPatientOnlyDistributed`.
    pub dir: String,
    /// The node file's name in the export directory.
    pub file: String,
    /// The node's INFO columns, ascending.
    pub columns: Vec<String>,
}

/// The start of a node file.
#[derive(Clone, Debug)]
pub struct NodeHeader {
    /// The group directory name.
    pub group: String,
    /// The chromosome directory name.
    pub dir: String,
    /// `VariantStore.patientIds`, as Java stores them.
    pub patients: Vec<String>,
    /// Length of the node's spec index.
    pub spec_count: u32,
    /// True when the node has a `BucketIndexBySample`.
    pub bucket_index: bool,
}

/// One info store of a node.
#[derive(Clone, Debug)]
pub struct ExportedInfo {
    /// The column, as queries name it.
    pub column: String,
    /// `FileBackedByteIndexedInfoStore.description`.
    pub description: String,
    /// `isContinuous`.
    pub continuous: bool,
    /// The keys, ascending in Java order, each with the spec index ids Java stores for it.
    pub keys: Vec<(String, Vec<u32>)>,
    /// For a continuous column the `CompressedIndex` ranges in the exported order (Java's
    /// `HashMap` order); `None` for a categorical column.
    pub ranges: Option<Vec<ExportedRange>>,
}

/// One range of a `CompressedIndex` with its map.
#[derive(Clone, Debug, PartialEq)]
pub struct ExportedRange {
    /// The lower bound.
    pub lower: RangeBound,
    /// The upper bound.
    pub upper: RangeBound,
    /// The map in `TreeMap` order: each float with the keys that parse to it.
    pub entries: Vec<(f32, Vec<String>)>,
}

/// One mask as exported: Java's form and the patient positions.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExportedMask {
    /// True when Java holds the mask as `VariantMaskBitmaskImpl`.
    pub bitmask_form: bool,
    /// Positions in the node's patient list, strictly ascending.
    pub positions: Vec<u32>,
}

/// One mask record: the spec and its `he`, `ho`, `hen` and `hon` masks.
#[derive(Clone, Debug)]
pub struct ExportedMaskRecord {
    /// The spec string.
    pub spec: String,
    /// The masks in the order `he`, `ho`, `hen`, `hon`.
    pub masks: [Option<ExportedMask>; 4],
}

/// The carriers Java's bucket index holds for one bucket.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ExportedCarriers {
    /// The bucket index has no bit for the bucket, so Java's prefilter drops its variants.
    NotIndexed,
    /// The positions of the patients whose bucket mask has the bucket's bit.
    Present(Vec<u32>),
    /// The node has no bucket index.
    NoIndex,
}

/// One bucket of a node.
#[derive(Clone, Debug)]
pub struct ExportedBucket {
    /// The contig.
    pub contig: String,
    /// The bucket, position divided by 1000.
    pub bucket: i32,
    /// The mask records, ascending by spec.
    pub masks: Vec<ExportedMaskRecord>,
    /// Metadata per spec: the INFO strings, `None` for a null array.
    pub metadata: Vec<(String, Option<Vec<String>>)>,
    /// The spec index entries placed in the bucket: id and spec.
    pub specs: Vec<(u32, String)>,
    /// The bucket's carriers.
    pub carriers: ExportedCarriers,
}

/// The counts at the end of a node file.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct NodeTrailer {
    /// Bucket records.
    pub buckets: u64,
    /// Mask records.
    pub mask_records: u64,
    /// Metadata entries.
    pub metadata_entries: u64,
    /// Patients whose bucket index mask is missing.
    pub missing_from_bucket_index: u64,
}

/// Reads the manifest in `export` with `key`.
///
/// # Errors
/// [`EtlError::Usage`] when it is missing; [`EtlError::Export`] when it does not decrypt
/// with the key, is malformed, or names an unknown mode.
pub fn read_manifest(export: &Path, key: &CryptoKey) -> Result<Manifest> {
    let path = export.join(MANIFEST_FILE);
    if !path.is_file() {
        return Err(EtlError::Usage(format!(
            "{} does not exist; it is written last, so the export is missing or incomplete",
            path.display()
        )));
    }
    let mut input = ExportInput::open(&path, key, ExportKind::GenomicManifest)?;
    let export_id = input.export_id();
    let mode_name = input.string("the mode")?;
    let mode = [
        GenoMode::Local,
        GenoMode::LocalDistributed,
        GenoMode::LocalPatientDistributed,
        GenoMode::LocalPatientOnlyDistributed,
    ]
    .into_iter()
    .find(|m| m.java_name() == mode_name)
    .ok_or_else(|| input.malformed(&format!("unknown mode {mode_name:?}")))?;
    let count = input.count("node count")?;
    let mut nodes = Vec::with_capacity(count.min(PREALLOCATE_LIMIT));
    for _ in 0..count {
        let group = input.string("a group name")?;
        let dir = input.string("a directory name")?;
        let file = input.string("a node file name")?;
        if file.is_empty() || file.contains('/') || file.starts_with('.') {
            return Err(input.malformed(&format!("node file name {file:?}")));
        }
        let columns = input.count("column count")?;
        let mut names = Vec::with_capacity(columns.min(PREALLOCATE_LIMIT));
        for _ in 0..columns {
            names.push(input.string("a column name")?);
        }
        nodes.push(ManifestNode {
            group,
            dir,
            file,
            columns: names,
        });
    }
    input.finish()?;
    Ok(Manifest {
        mode,
        nodes,
        export_id,
    })
}

/// Streams one node file.
pub struct NodeReader {
    input: ExportInput,
    trailer: Option<NodeTrailer>,
}

impl NodeReader {
    /// Opens the node file at `path`, which must belong to the export `export_id`, and reads
    /// its header.
    ///
    /// # Errors
    /// [`EtlError::Export`] when it does not decrypt with the key, belongs to another export,
    /// or its header is malformed; I/O errors.
    pub fn open(
        path: &Path,
        key: &CryptoKey,
        export_id: [u8; EXPORT_ID_LEN],
    ) -> Result<(Self, NodeHeader)> {
        let mut input = ExportInput::open(path, key, ExportKind::GenomicNode)?;
        if input.export_id() != export_id {
            return Err(input.malformed("belongs to another export than the manifest"));
        }
        let group = input.string("the group name")?;
        let dir = input.string("the directory name")?;
        let patients = input.count("patient count")?;
        let mut ids = Vec::with_capacity(patients.min(PREALLOCATE_LIMIT));
        for _ in 0..patients {
            ids.push(input.string("a patient id")?);
        }
        let spec_count = input.count("spec index length")?;
        let spec_count = u32::try_from(spec_count)
            .map_err(|_| input.malformed("the spec index is longer than u32"))?;
        let bucket_index = input.bool()?;
        let header = NodeHeader {
            group,
            dir,
            patients: ids,
            spec_count,
            bucket_index,
        };
        Ok((
            NodeReader {
                input,
                trailer: None,
            },
            header,
        ))
    }

    /// The file being read.
    pub fn path(&self) -> &Path {
        &self.input.path
    }

    /// Reads the node's info stores, which follow the header. Every spec index id must be
    /// below `spec_count`.
    ///
    /// # Errors
    /// [`EtlError::Export`] when they are malformed or an id is outside the spec index.
    pub fn infos(&mut self, spec_count: u32) -> Result<Vec<ExportedInfo>> {
        let input = &mut self.input;
        let count = input.count("info count")?;
        let mut infos = Vec::with_capacity(count.min(PREALLOCATE_LIMIT));
        for _ in 0..count {
            let column = input.string("an info column")?;
            let description = input.string("a description")?;
            let continuous = input.bool()?;
            let key_count = input.count("key count")?;
            let mut keys = Vec::with_capacity(key_count.min(PREALLOCATE_LIMIT));
            for _ in 0..key_count {
                let key = input.string("an info key")?;
                let n = input.count("variant id count")?;
                let mut ids = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
                for _ in 0..n {
                    let id = input.i32()?;
                    let id = u32::try_from(id)
                        .ok()
                        .filter(|&id| id < spec_count)
                        .ok_or_else(|| {
                            input.malformed(&format!(
                                "column {column:?} key {key:?}: variant id {id} outside the spec index of {spec_count}"
                            ))
                        })?;
                    ids.push(id);
                }
                keys.push((key, ids));
            }
            let range_count = input.i32()?;
            let ranges = match (continuous, range_count) {
                (false, -1) => None,
                (true, n) if n >= 0 => {
                    let mut ranges = Vec::with_capacity((n as usize).min(PREALLOCATE_LIMIT));
                    for _ in 0..n {
                        let lower = input.bound()?;
                        let upper = input.bound()?;
                        let entries = input.count("entry count")?;
                        let mut map = Vec::with_capacity(entries.min(PREALLOCATE_LIMIT));
                        for _ in 0..entries {
                            let value = f32::from_bits(input.i32()? as u32);
                            let n = input.count("entry key count")?;
                            let mut keys = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
                            for _ in 0..n {
                                keys.push(input.string("a CompressedIndex key")?);
                            }
                            map.push((value, keys));
                        }
                        ranges.push(ExportedRange {
                            lower,
                            upper,
                            entries: map,
                        });
                    }
                    Some(ranges)
                }
                (_, n) => {
                    return Err(input.malformed(&format!(
                        "column {column:?}: range count {n} for a {} column",
                        if continuous {
                            "continuous"
                        } else {
                            "categorical"
                        }
                    )));
                }
            };
            infos.push(ExportedInfo {
                column,
                description,
                continuous,
                keys,
                ranges,
            });
        }
        Ok(infos)
    }

    /// Reads the next bucket, or `None` at the end record, whose counts it keeps for
    /// [`NodeReader::trailer`]. Every position is checked to be below `patient_count`.
    ///
    /// # Errors
    /// [`EtlError::Export`] when the record is malformed.
    pub fn next_bucket(&mut self, patient_count: u32) -> Result<Option<ExportedBucket>> {
        let input = &mut self.input;
        match input.u8()? {
            0 => {
                let trailer = NodeTrailer {
                    buckets: input.i64()? as u64,
                    mask_records: input.i64()? as u64,
                    metadata_entries: input.i64()? as u64,
                    missing_from_bucket_index: input.i64()? as u64,
                };
                input.finish()?;
                self.trailer = Some(trailer);
                return Ok(None);
            }
            1 => {}
            other => return Err(input.malformed(&format!("unknown record tag {other}"))),
        }
        let contig = input.string("a contig")?;
        let bucket = input.i32()?;
        let n = input.count("mask record count")?;
        let mut masks = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
        for _ in 0..n {
            let spec = input.string("a spec")?;
            let mut record: [Option<ExportedMask>; 4] = [None, None, None, None];
            for slot in &mut record {
                *slot = match input.u8()? {
                    0 => None,
                    form @ (1 | 2) => Some(ExportedMask {
                        bitmask_form: form == 2,
                        positions: input.position_set(patient_count)?,
                    }),
                    other => return Err(input.malformed(&format!("mask form {other}"))),
                };
            }
            masks.push(ExportedMaskRecord {
                spec,
                masks: record,
            });
        }
        let n = input.count("metadata count")?;
        let mut metadata = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
        for _ in 0..n {
            let spec = input.string("a metadata spec")?;
            let count = input.i32()?;
            let strings = if count == -1 {
                None
            } else {
                let count = usize::try_from(count)
                    .map_err(|_| input.malformed(&format!("metadata string count {count}")))?;
                let mut strings = Vec::with_capacity(count.min(PREALLOCATE_LIMIT));
                for _ in 0..count {
                    strings.push(input.string("a metadata string")?);
                }
                Some(strings)
            };
            metadata.push((spec, strings));
        }
        let n = input.count("spec index entry count")?;
        let mut specs = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
        for _ in 0..n {
            let id = input.i32()?;
            let id =
                u32::try_from(id).map_err(|_| input.malformed(&format!("spec index id {id}")))?;
            specs.push((id, input.string("a spec index entry")?));
        }
        let carriers = match input.u8()? {
            0 => ExportedCarriers::NotIndexed,
            1 => ExportedCarriers::Present(input.position_set(patient_count)?),
            2 => ExportedCarriers::NoIndex,
            other => return Err(input.malformed(&format!("carrier tag {other}"))),
        };
        Ok(Some(ExportedBucket {
            contig,
            bucket,
            masks,
            metadata,
            specs,
            carriers,
        }))
    }

    /// The end record's counts, once [`NodeReader::next_bucket`] has returned `None`.
    pub fn trailer(&self) -> Option<NodeTrailer> {
        self.trailer
    }
}

/// Typed reads over a decrypted export file.
struct ExportInput {
    path: PathBuf,
    input: BufReader<ExportDecryptor<File>>,
}

impl ExportInput {
    fn open(path: &Path, key: &CryptoKey, kind: ExportKind) -> Result<Self> {
        let file = File::open(path).map_err(EtlError::io("opening", path))?;
        let decrypted =
            ExportDecryptor::open(file, key.clone(), kind).map_err(|e| export_io_error(path, e))?;
        Ok(ExportInput {
            path: path.to_path_buf(),
            input: BufReader::with_capacity(4 << 20, decrypted),
        })
    }

    fn export_id(&self) -> [u8; EXPORT_ID_LEN] {
        self.input.get_ref().export_id
    }

    fn malformed(&self, detail: &str) -> EtlError {
        EtlError::Export {
            path: self.path.clone(),
            detail: detail.to_string(),
        }
    }

    fn bytes(&mut self, buf: &mut [u8]) -> Result<()> {
        self.input
            .read_exact(buf)
            .map_err(|e| export_io_error(&self.path, e))
    }

    fn u8(&mut self) -> Result<u8> {
        let mut b = [0u8; 1];
        self.bytes(&mut b)?;
        Ok(b[0])
    }

    fn bool(&mut self) -> Result<bool> {
        match self.u8()? {
            0 => Ok(false),
            1 => Ok(true),
            other => Err(self.malformed(&format!("flag byte {other} is not 0 or 1"))),
        }
    }

    fn i32(&mut self) -> Result<i32> {
        let mut b = [0u8; 4];
        self.bytes(&mut b)?;
        Ok(i32::from_be_bytes(b))
    }

    fn i64(&mut self) -> Result<i64> {
        let mut b = [0u8; 8];
        self.bytes(&mut b)?;
        Ok(i64::from_be_bytes(b))
    }

    fn count(&mut self, what: &str) -> Result<usize> {
        let n = self.i32()?;
        usize::try_from(n).map_err(|_| self.malformed(&format!("{what} {n} is negative")))
    }

    fn string(&mut self, what: &str) -> Result<String> {
        let len = self.i32()?;
        let len = usize::try_from(len)
            .map_err(|_| self.malformed(&format!("{what} has length {len}")))?;
        let mut buf = vec![0u8; len.min(PREALLOCATE_LIMIT)];
        if len > buf.len() {
            let mut all = Vec::new();
            (&mut self.input)
                .take(len as u64)
                .read_to_end(&mut all)
                .map_err(|e| export_io_error(&self.path, e))?;
            if all.len() != len {
                return Err(self.malformed("ends early; the export is incomplete"));
            }
            buf = all;
        } else {
            self.bytes(&mut buf)?;
        }
        String::from_utf8(buf).map_err(|_| self.malformed(&format!("{what} is not UTF-8")))
    }

    fn bound(&mut self) -> Result<RangeBound> {
        let kind = self.u8()?;
        let bits = self.i32()? as u32;
        match kind {
            0 if bits == 0 => Ok(RangeBound::Unbounded),
            1 => Ok(RangeBound::Open(f32::from_bits(bits))),
            2 => Ok(RangeBound::Closed(f32::from_bits(bits))),
            _ => Err(self.malformed(&format!("range bound kind {kind} with bits {bits:#x}"))),
        }
    }

    /// Reads a position set: a list of strictly ascending positions or a bitmap of
    /// `ceil(patient_count / 8)` bytes, every position below `patient_count`.
    fn position_set(&mut self, patient_count: u32) -> Result<Vec<u32>> {
        match self.u8()? {
            0 => {
                let n = self.count("position count")?;
                let mut positions = Vec::with_capacity(n.min(PREALLOCATE_LIMIT));
                for _ in 0..n {
                    let p = self.i32()?;
                    let p = u32::try_from(p)
                        .ok()
                        .filter(|&p| p < patient_count)
                        .ok_or_else(|| {
                            self.malformed(&format!(
                                "position {p} outside a node of {patient_count} patients"
                            ))
                        })?;
                    if positions.last().is_some_and(|&last| last >= p) {
                        return Err(self.malformed("positions are not strictly ascending"));
                    }
                    positions.push(p);
                }
                Ok(positions)
            }
            1 => {
                let len = self.count("bitmap length")?;
                let expected = patient_count.div_ceil(8) as usize;
                if len != expected {
                    return Err(self.malformed(&format!(
                        "a bitmap of {len} bytes for {patient_count} patients"
                    )));
                }
                let mut bytes = vec![0u8; len];
                self.bytes(&mut bytes)?;
                let mut positions = Vec::new();
                for (i, &byte) in bytes.iter().enumerate() {
                    let mut bits = byte;
                    while bits != 0 {
                        let p = (i as u32) * 8 + bits.trailing_zeros();
                        if p >= patient_count {
                            return Err(self.malformed(&format!(
                                "bitmap bit {p} outside a node of {patient_count} patients"
                            )));
                        }
                        positions.push(p);
                        bits &= bits - 1;
                    }
                }
                Ok(positions)
            }
            other => Err(self.malformed(&format!("position set tag {other}"))),
        }
    }

    /// Checks that nothing follows the content.
    fn finish(&mut self) -> Result<()> {
        let mut rest = [0u8; 1];
        let n = self
            .input
            .read(&mut rest)
            .map_err(|e| export_io_error(&self.path, e))?;
        if n != 0 {
            return Err(self.malformed("holds bytes after its end"));
        }
        Ok(())
    }
}
