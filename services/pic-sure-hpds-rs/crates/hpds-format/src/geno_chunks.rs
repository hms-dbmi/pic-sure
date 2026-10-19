//! The three chunk payloads of the genomic store: mask chunks (kind 1), metadata chunks
//! (kind 2) and bucket carrier chunks (kind 3). The three blocks of one chunk share its
//! buckets and variants, so one entry of the index's chunk table addresses them all.
//!
//! Every array starts on an 8-byte boundary and is followed by zero padding to the next.
//!
//! # Kind 1, mask chunk
//!
//! ```text
//! 0   kind           u8    1
//! 1   reserved       3 bytes, zero
//! 4   n              u32   variants, = the index's variant_count
//! 8   first_variant  u32   = the index's first_variant
//! 12  bucket_count   u32   = the index's bucket_count
//! 16  contig         u16   = the index entry's contig
//! 18  reserved       u16   zero
//! 20  patient_count  u32   = the group's patient_count
//! 24  buckets        bucket_count x { bucket i32, first u32 }: strictly ascending buckets
//!                    from first_bucket to last_bucket (both present), first strictly
//!                    ascending from 0, all below n
//!     spec_offsets   u32[n+1]   into spec_bytes, first 0, last its length, non-decreasing
//!     spec_bytes     u8[]       the specs, UTF-8, in variant order
//!     flags          u16[n]     bits 0 to 3: he, ho, hen, hon present
//!                               bits 4 to 7: that mask's Java form is bitmask (only for
//!                               present masks)
//!                               bit 8: Java holds a mask record for the spec; when clear,
//!                               bits 0 to 7 are zero
//!                               other bits zero
//!     mask_offsets   u32[m+1]   m = present masks; masks in variant order, each variant's in
//!                               the order he, ho, hen, hon
//!     mask_bytes     u8[]       m position sets ([`crate::pos_set`])
//! ```
//!
//! Every spec's position field parses as a Java `int` whose bucket is the one holding it, its
//! contig field is the chunk's contig, and within a bucket specs are strictly ascending in
//! the store's spec order ([`crate::variant_spec::compare_specs`]). Variant ids are
//! node-local; the chunk holds `first_variant .. first_variant + n`.
//!
//! # Kind 2, metadata chunk
//!
//! ```text
//! 0   kind           u8    2
//! 1   reserved       3 bytes
//! 4   n              u32   = the mask chunk's n
//! 8   string_count   u32
//! 12  reserved       u32
//! 16  per_variant    u32[n+1]   prefix sums: variant i owns strings
//!                               per_variant[i] .. per_variant[i+1]
//!     string_offsets u32[string_count+1]
//!     string_bytes   u8[]       the raw INFO strings, in Java's String[] order
//! ```
//!
//! # Kind 3, bucket carriers
//!
//! ```text
//! 0   kind           u8    3
//! 1   reserved       3 bytes
//! 4   bucket_count   u32   = the mask chunk's bucket_count
//! 8   offsets        u32[bucket_count+1]
//!     bytes          bucket_count position sets, in the mask chunk's bucket order: the
//!                    patients holding he, hen or ho on any variant of the bucket
//! ```

use std::cmp::Ordering;
use std::ops::Range;

use crate::aligned::AlignedBuf;
use crate::error::{FormatError, Result};
use crate::geno_blocks::GenoBlockKind;
use crate::geno_layout::{
    PayloadReader, PayloadWriter, bytes_at, check_offsets, check_text, data_len, text_at, to_u32,
    view,
};
use crate::parity::java_parse_int;
use crate::pos_set::{PosSet, encode_pos_set};
use crate::variant_spec::{BUCKET_WIDTH, VariantSpecKey, java_split_spec};

const MASK_HEADER_LEN: usize = 24;
const FLAG_RECORD: u16 = 1 << 8;
const KNOWN_FLAGS: u16 = 0x1ff;

/// The four masks Java keeps per variant, in their stored order.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum MaskKind {
    /// `heterozygousMask`, genotype `0/1`.
    Heterozygous = 0,
    /// `homozygousMask`, genotype `1/1`.
    Homozygous = 1,
    /// `heterozygousNoCallMask`, genotype `./1`.
    HeterozygousNoCall = 2,
    /// `homozygousNoCallMask`, genotype `./.`.
    HomozygousNoCall = 3,
}

impl MaskKind {
    /// The four kinds in stored order.
    pub const ALL: [MaskKind; 4] = [
        MaskKind::Heterozygous,
        MaskKind::Homozygous,
        MaskKind::HeterozygousNoCall,
        MaskKind::HomozygousNoCall,
    ];
}

/// One mask handed to [`encode_mask_chunk`]: its patients as group positions, and whether
/// Java holds it in the bitmask form (otherwise the sparse form).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct JavaMaskInput<'a> {
    /// Positions in the group's patient list, strictly ascending.
    pub positions: &'a [u32],
    /// True when Java holds the mask as `VariantMaskBitmaskImpl`.
    pub bitmask_form: bool,
}

/// One variant handed to [`encode_mask_chunk`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct MaskVariantInput<'a> {
    /// The spec string as Java stores it.
    pub spec: &'a str,
    /// Java's mask record for the spec, `None` when its bucket holds none (the spec is known
    /// from an info store or the spec index only). Indexed by [`MaskKind`].
    pub record: Option<[Option<JavaMaskInput<'a>>; 4]>,
}

/// What [`encode_mask_chunk`] derived from its input, for the chunk's index entry.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct EncodedMaskChunk {
    /// The payload.
    pub payload: Vec<u8>,
    /// The first bucket of the chunk.
    pub first_bucket: i32,
    /// The last bucket of the chunk.
    pub last_bucket: i32,
    /// Buckets the chunk holds.
    pub bucket_count: u32,
    /// Node variant ids of the chunk's specs with fewer than six fields.
    pub short_specs: Vec<u32>,
}

/// What the index says a mask chunk holds, checked when it is decoded.
#[derive(Clone, Copy, Debug)]
pub struct ExpectedMaskChunk<'a> {
    /// The chunk's first node variant id.
    pub first_variant: u32,
    /// Variants of the chunk.
    pub variant_count: u32,
    /// Buckets of the chunk.
    pub bucket_count: u32,
    /// The chunk's contig, as an index into the node's contigs.
    pub contig: u16,
    /// The contig's name, which every spec's field 0 must equal.
    pub contig_name: &'a str,
    /// The chunk's first bucket.
    pub first_bucket: i32,
    /// The chunk's last bucket.
    pub last_bucket: i32,
    /// The group's patient count.
    pub patient_count: u32,
    /// The node's whole short spec list, strictly ascending.
    pub short_specs: &'a [u32],
}

/// Encodes a mask chunk from `variants`, given in store order, for the contig `contig`
/// (index `contig_index` in the node's contigs) of a group of `patient_count` patients. The
/// chunk's variants get the node ids `first_variant ..`.
///
/// # Errors
/// [`FormatError::InvalidInput`] when the variants break an invariant of the payload: no
/// variants, a spec without a Java `int` position or of another contig, specs out of order,
/// a mask whose positions are not strictly ascending below `patient_count`, or a count that
/// exceeds its field.
pub fn encode_mask_chunk(
    contig_index: u16,
    contig: &str,
    first_variant: u32,
    patient_count: u32,
    variants: &[MaskVariantInput<'_>],
) -> Result<EncodedMaskChunk> {
    let invalid = |detail: String| FormatError::InvalidInput(format!("mask chunk: {detail}"));
    let n = to_u32(variants.len(), "variant count")?;
    if n == 0 {
        return Err(invalid("a chunk holds at least one variant".into()));
    }
    let mut buckets: Vec<(i32, u32)> = Vec::new();
    let mut short_specs = Vec::new();
    let mut spec_offsets = vec![0u32];
    let mut spec_bytes = Vec::new();
    let mut flags = Vec::with_capacity(variants.len());
    let mut mask_offsets = vec![0u32];
    let mut mask_bytes = Vec::new();
    for (i, variant) in variants.iter().enumerate() {
        let key = VariantSpecKey::parse(variant.spec)
            .ok_or_else(|| invalid(format!("{:?} has no Java int position", variant.spec)))?;
        if buckets.last().is_none_or(|&(b, _)| b != key.bucket()) {
            buckets.push((key.bucket(), i as u32));
        }
        if key.is_short() {
            short_specs.push(
                first_variant
                    .checked_add(i as u32)
                    .ok_or_else(|| invalid("variant ids exceed u32".into()))?,
            );
        }
        spec_bytes.extend_from_slice(variant.spec.as_bytes());
        spec_offsets.push(to_u32(spec_bytes.len(), "spec bytes")?);
        let mut flag = 0u16;
        if let Some(record) = &variant.record {
            flag |= FLAG_RECORD;
            for (k, mask) in record.iter().enumerate() {
                if let Some(mask) = mask {
                    flag |= 1 << k;
                    if mask.bitmask_form {
                        flag |= 1 << (k + 4);
                    }
                    encode_pos_set(mask.positions, patient_count, &mut mask_bytes)?;
                    mask_offsets.push(to_u32(mask_bytes.len(), "mask bytes")?);
                }
            }
        }
        flags.push(flag);
    }
    let first_bucket = buckets.first().map_or(0, |b| b.0);
    let last_bucket = buckets.last().map_or(0, |b| b.0);
    let bucket_count = to_u32(buckets.len(), "bucket count")?;

    let mut w = PayloadWriter::default();
    w.u8(GenoBlockKind::Mask as u8);
    w.zeros(3);
    w.u32(n);
    w.u32(first_variant);
    w.u32(bucket_count);
    w.u16(contig_index);
    w.zeros(2);
    w.u32(patient_count);
    let pairs: Vec<u32> = buckets
        .iter()
        .flat_map(|&(bucket, first)| [bucket as u32, first])
        .collect();
    w.array(&pairs);
    w.array(&spec_offsets);
    w.bytes(&spec_bytes);
    w.array(&flags);
    w.array(&mask_offsets);
    w.bytes(&mask_bytes);

    let expected = ExpectedMaskChunk {
        first_variant,
        variant_count: n,
        bucket_count,
        contig: contig_index,
        contig_name: contig,
        first_bucket,
        last_bucket,
        patient_count,
        short_specs: &short_specs,
    };
    MaskChunk::decode(AlignedBuf::copy_from(&w.buf), &expected)
        .map_err(|e| invalid(e.to_string()))?;
    Ok(EncodedMaskChunk {
        payload: w.buf,
        first_bucket,
        last_bucket,
        bucket_count,
        short_specs,
    })
}

/// One mask of a decoded chunk: the position set and Java's form of it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ChunkMask<'a> {
    /// The patients, as group positions.
    pub positions: PosSet<'a>,
    /// True when Java holds the mask as `VariantMaskBitmaskImpl`.
    pub bitmask_form: bool,
}

/// A decoded and fully checked mask chunk. Arrays are views over the payload; masks are
/// read on access.
#[derive(Clone, Debug)]
pub struct MaskChunk {
    buf: AlignedBuf,
    n: u32,
    first_variant: u32,
    patient_count: u32,
    buckets: Range<usize>,
    spec_offsets: Range<usize>,
    spec_bytes: Range<usize>,
    flags: Range<usize>,
    mask_offsets: Range<usize>,
    mask_bytes: Range<usize>,
    mask_start: Vec<u32>,
}

impl MaskChunk {
    /// Decodes `buf` and checks it fully against `expected`: the header, every offset
    /// table, the UTF-8 of every spec, each spec's contig and bucket, the spec order within
    /// each bucket, the short specs against the node's list, the flags, and every mask.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode(buf: AlignedBuf, expected: &ExpectedMaskChunk<'_>) -> Result<Self> {
        let what = "mask chunk";
        let mut r = PayloadReader::new(buf.as_bytes(), what);
        let kind = r.u8()?;
        if kind != GenoBlockKind::Mask as u8 {
            return Err(FormatError::corrupt(what, format!("kind byte {kind}")));
        }
        r.reserved(3)?;
        let n = r.u32()?;
        let first_variant = r.u32()?;
        let bucket_count = r.u32()?;
        let contig = r.u16()?;
        r.reserved(2)?;
        let patient_count = r.u32()?;
        let header = [
            ("variant count", n, expected.variant_count),
            ("first variant", first_variant, expected.first_variant),
            ("bucket count", bucket_count, expected.bucket_count),
            ("contig", u32::from(contig), u32::from(expected.contig)),
            ("patient count", patient_count, expected.patient_count),
        ];
        for (name, found, wanted) in header {
            if found != wanted {
                return Err(FormatError::corrupt(
                    what,
                    format!("{name} {found}, the index says {wanted}"),
                ));
            }
        }
        debug_assert_eq!(r.pos, MASK_HEADER_LEN);
        let n_len = n as usize;
        let buckets = r.array::<u32>(
            (bucket_count as usize)
                .checked_mul(2)
                .ok_or(FormatError::Truncated(what))?,
        )?;
        let spec_offsets = r.offsets::<u32>(n_len)?;
        let spec_bytes = r.array::<u8>(data_len(&buf, &spec_offsets))?;
        let flags = r.array::<u16>(n_len)?;
        let present: usize = view::<u16>(&buf, &flags)
            .iter()
            .map(|f| (f & 0xf).count_ones() as usize)
            .sum();
        let mask_offsets = r.offsets::<u32>(present)?;
        let mask_bytes = r.array::<u8>(data_len(&buf, &mask_offsets))?;
        r.finish()?;

        let mut mask_start = Vec::with_capacity(n_len + 1);
        let mut running = 0u32;
        mask_start.push(0);
        for &flag in view::<u16>(&buf, &flags) {
            running += (flag & 0xf).count_ones();
            mask_start.push(running);
        }
        let chunk = MaskChunk {
            buf,
            n,
            first_variant,
            patient_count,
            buckets,
            spec_offsets,
            spec_bytes,
            flags,
            mask_offsets,
            mask_bytes,
            mask_start,
        };
        chunk.check(expected)?;
        Ok(chunk)
    }

    fn check(&self, expected: &ExpectedMaskChunk<'_>) -> Result<()> {
        let what = "mask chunk";
        let corrupt = |detail: String| FormatError::corrupt(what, detail);
        let pairs = view::<u32>(&self.buf, &self.buckets);
        let buckets: Vec<(i32, u32)> = pairs
            .as_chunks::<2>()
            .0
            .iter()
            .map(|p| (p[0] as i32, p[1]))
            .collect();
        if buckets.first().map(|b| b.0) != Some(expected.first_bucket)
            || buckets.last().map(|b| b.0) != Some(expected.last_bucket)
        {
            return Err(corrupt(format!(
                "buckets do not run from {} to {}",
                expected.first_bucket, expected.last_bucket
            )));
        }
        if buckets.first().map(|b| b.1) != Some(0)
            || buckets
                .windows(2)
                .any(|w| w[0].0 >= w[1].0 || w[0].1 >= w[1].1)
            || buckets.iter().any(|b| b.1 >= self.n)
        {
            return Err(corrupt("bucket table out of order or range".into()));
        }

        let spec_offsets = view::<u32>(&self.buf, &self.spec_offsets);
        let spec_bytes = view::<u8>(&self.buf, &self.spec_bytes);
        check_offsets(spec_offsets, spec_bytes.len(), "mask chunk specs")?;
        check_text(spec_bytes, spec_offsets, "mask chunk specs")?;

        let lo = expected
            .short_specs
            .partition_point(|&id| id < self.first_variant);
        let hi = expected.short_specs.partition_point(|&id| {
            u64::from(id) < u64::from(self.first_variant) + u64::from(self.n)
        });
        let mut listed = expected.short_specs[lo..hi].iter().copied().peekable();

        let mut bucket_index = 0;
        let mut previous: Option<(VariantSpecKey<'_>, &str)> = None;
        for i in 0..self.n as usize {
            if buckets
                .get(bucket_index + 1)
                .is_some_and(|b| b.1 as usize == i)
            {
                bucket_index += 1;
                previous = None;
            }
            let bucket = buckets.get(bucket_index).map_or(i32::MIN, |b| b.0);
            let spec = text_at(spec_bytes, spec_offsets, i);
            let key = VariantSpecKey::parse(spec)
                .ok_or_else(|| corrupt(format!("spec {spec:?} has no Java int position")))?;
            if key.contig() != expected.contig_name {
                return Err(corrupt(format!(
                    "spec {spec:?} is not on contig {:?}",
                    expected.contig_name
                )));
            }
            if key.bucket() != bucket {
                return Err(corrupt(format!("spec {spec:?} is not in bucket {bucket}")));
            }
            if let Some((previous_key, previous_spec)) = previous
                && previous_key
                    .cmp(&key)
                    .then_with(|| previous_spec.as_bytes().cmp(spec.as_bytes()))
                    != Ordering::Less
            {
                return Err(corrupt(format!(
                    "spec {spec:?} is not ordered after {previous_spec:?}"
                )));
            }
            previous = Some((key, spec));
            let id = self
                .first_variant
                .checked_add(i as u32)
                .ok_or_else(|| corrupt("variant ids exceed u32".into()))?;
            let is_listed = listed.next_if_eq(&id).is_some();
            if key.is_short() != is_listed {
                return Err(corrupt(format!(
                    "spec {spec:?} (variant {id}) is {}short but {}in the node's short spec list",
                    if key.is_short() { "" } else { "not " },
                    if is_listed { "" } else { "not " }
                )));
            }
        }
        if let Some(id) = listed.next() {
            return Err(corrupt(format!(
                "short spec list names variant {id}, which is not short"
            )));
        }

        for (i, &flag) in view::<u16>(&self.buf, &self.flags).iter().enumerate() {
            let present = flag & 0xf;
            let forms = (flag >> 4) & 0xf;
            if flag & !KNOWN_FLAGS != 0
                || forms & !present != 0
                || (flag & FLAG_RECORD == 0 && flag & 0xff != 0)
            {
                return Err(corrupt(format!("variant {i} has flags {flag:#06x}")));
            }
        }
        let mask_offsets = view::<u32>(&self.buf, &self.mask_offsets);
        let mask_bytes = view::<u8>(&self.buf, &self.mask_bytes);
        check_offsets(mask_offsets, mask_bytes.len(), "mask chunk masks")?;
        for m in 0..mask_offsets.len().saturating_sub(1) {
            PosSet::parse(bytes_at(mask_bytes, mask_offsets, m), self.patient_count)?;
        }
        Ok(())
    }

    /// Number of variants.
    pub fn len(&self) -> usize {
        self.n as usize
    }

    /// True when the chunk holds no variant, which a decoded chunk never does.
    pub fn is_empty(&self) -> bool {
        self.n == 0
    }

    /// The node variant id of the chunk's first variant; variant `i` of the chunk is node
    /// variant `first_variant() + i`.
    pub fn first_variant(&self) -> u32 {
        self.first_variant
    }

    /// The group's patient count, the range of every mask.
    pub fn patient_count(&self) -> u32 {
        self.patient_count
    }

    /// The spec of chunk variant `i`, empty when out of range.
    pub fn spec(&self, i: usize) -> &str {
        text_at(
            view::<u8>(&self.buf, &self.spec_bytes),
            view::<u32>(&self.buf, &self.spec_offsets),
            i,
        )
    }

    /// Number of buckets.
    pub fn bucket_count(&self) -> usize {
        self.buckets.len() / 8
    }

    /// Bucket `j`: its number and its chunk variants.
    pub fn bucket(&self, j: usize) -> Option<(i32, Range<usize>)> {
        let pairs = view::<u32>(&self.buf, &self.buckets);
        let bucket = *pairs.get(2 * j)? as i32;
        let start = *pairs.get(2 * j + 1)? as usize;
        let end = pairs
            .get(2 * j + 3)
            .map_or(self.n as usize, |&f| f as usize);
        Some((bucket, start..end))
    }

    /// The chunk variants of bucket number `bucket`, `None` when the chunk does not hold it.
    pub fn find_bucket(&self, bucket: i32) -> Option<Range<usize>> {
        let pairs = view::<u32>(&self.buf, &self.buckets);
        let count = pairs.len() / 2;
        let mut lo = 0;
        let mut hi = count;
        while lo < hi {
            let mid = (lo + hi) / 2;
            match (pairs[2 * mid] as i32).cmp(&bucket) {
                Ordering::Less => lo = mid + 1,
                Ordering::Greater => hi = mid,
                Ordering::Equal => return self.bucket(mid).map(|b| b.1),
            }
        }
        None
    }

    /// The chunk variant whose spec is exactly `spec`, found by scanning the spec's bucket
    /// for equal bytes.
    pub fn find_spec(&self, spec: &str) -> Option<usize> {
        let (_, split) = java_split_spec(spec);
        let position = java_parse_int(split[1])?;
        self.find_bucket(position / BUCKET_WIDTH)?
            .find(|&i| self.spec(i) == spec)
    }

    fn flag(&self, i: usize) -> u16 {
        view::<u16>(&self.buf, &self.flags)
            .get(i)
            .copied()
            .unwrap_or(0)
    }

    /// The flags of every variant, as stored: bits 0 to 3 the presence of `he`, `ho`, `hen`
    /// and `hon`, bits 4 to 7 their bitmask form, bit 8 the presence of a mask record.
    pub fn flags(&self) -> &[u16] {
        view::<u16>(&self.buf, &self.flags)
    }

    /// True when Java holds a mask record for chunk variant `i`.
    pub fn has_record(&self, i: usize) -> bool {
        self.flag(i) & FLAG_RECORD != 0
    }

    /// Mask `kind` of chunk variant `i`, `None` when Java holds no such mask.
    pub fn mask(&self, i: usize, kind: MaskKind) -> Option<ChunkMask<'_>> {
        let flag = self.flag(i);
        let bit = kind as u16;
        if flag & (1 << bit) == 0 {
            return None;
        }
        let before = (flag & ((1 << bit) - 1)).count_ones() as usize;
        let m = *self.mask_start.get(i)? as usize + before;
        let bytes = bytes_at(
            view::<u8>(&self.buf, &self.mask_bytes),
            view::<u32>(&self.buf, &self.mask_offsets),
            m,
        );
        Some(ChunkMask {
            positions: PosSet::from_validated(bytes, self.patient_count),
            bitmask_form: flag & (1 << (bit + 4)) != 0,
        })
    }

    /// The decoded payload's bytes.
    pub fn payload(&self) -> &[u8] {
        self.buf.as_bytes()
    }

    /// Heap bytes the chunk holds: the payload and the per-variant mask index.
    pub fn heap_size(&self) -> usize {
        self.buf.heap_size() + self.mask_start.capacity() * size_of::<u32>()
    }
}

/// Encodes a metadata chunk: for each variant of the mask chunk, in order, its raw INFO
/// strings in Java's array order.
///
/// # Errors
/// [`FormatError::InvalidInput`] when a count or length exceeds its `u32` field.
pub fn encode_metadata_chunk<V, S>(variants: &[V]) -> Result<Vec<u8>>
where
    V: AsRef<[S]>,
    S: AsRef<str>,
{
    let mut per_variant = vec![0u32];
    let mut string_offsets = vec![0u32];
    let mut string_bytes = Vec::new();
    for variant in variants {
        for s in variant.as_ref() {
            string_bytes.extend_from_slice(s.as_ref().as_bytes());
            string_offsets.push(to_u32(string_bytes.len(), "metadata bytes")?);
        }
        per_variant.push(to_u32(string_offsets.len() - 1, "metadata string count")?);
    }
    let mut w = PayloadWriter::default();
    w.u8(GenoBlockKind::Metadata as u8);
    w.zeros(3);
    w.u32(to_u32(variants.len(), "variant count")?);
    w.u32(to_u32(string_offsets.len() - 1, "metadata string count")?);
    w.zeros(4);
    w.array(&per_variant);
    w.array(&string_offsets);
    w.bytes(&string_bytes);
    Ok(w.buf)
}

/// A decoded and checked metadata chunk.
#[derive(Clone, Debug)]
pub struct MetadataChunk {
    buf: AlignedBuf,
    n: u32,
    per_variant: Range<usize>,
    string_offsets: Range<usize>,
    string_bytes: Range<usize>,
}

impl MetadataChunk {
    /// Decodes `buf`, a metadata chunk of `variant_count` variants, and checks its offset
    /// tables and the UTF-8 of every string.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode(buf: AlignedBuf, variant_count: u32) -> Result<Self> {
        let what = "metadata chunk";
        let mut r = PayloadReader::new(buf.as_bytes(), what);
        let kind = r.u8()?;
        if kind != GenoBlockKind::Metadata as u8 {
            return Err(FormatError::corrupt(what, format!("kind byte {kind}")));
        }
        r.reserved(3)?;
        let n = r.u32()?;
        if n != variant_count {
            return Err(FormatError::corrupt(
                what,
                format!("variant count {n}, the index says {variant_count}"),
            ));
        }
        let string_count = r.u32()?;
        r.reserved(4)?;
        let per_variant = r.offsets::<u32>(n as usize)?;
        let string_offsets = r.offsets::<u32>(string_count as usize)?;
        let string_bytes = r.array::<u8>(data_len(&buf, &string_offsets))?;
        r.finish()?;
        check_offsets(
            view::<u32>(&buf, &per_variant),
            string_count as usize,
            "metadata variant table",
        )?;
        let offsets = view::<u32>(&buf, &string_offsets);
        let bytes = view::<u8>(&buf, &string_bytes);
        check_offsets(offsets, bytes.len(), "metadata strings")?;
        check_text(bytes, offsets, "metadata strings")?;
        Ok(MetadataChunk {
            buf,
            n,
            per_variant,
            string_offsets,
            string_bytes,
        })
    }

    /// Number of variants.
    pub fn len(&self) -> usize {
        self.n as usize
    }

    /// True when the chunk holds no variant.
    pub fn is_empty(&self) -> bool {
        self.n == 0
    }

    /// The INFO strings of chunk variant `i`, in Java's array order; empty when it has none
    /// or `i` is out of range.
    pub fn strings(&self, i: usize) -> impl ExactSizeIterator<Item = &str> + '_ {
        let per_variant = view::<u32>(&self.buf, &self.per_variant);
        let range = match (per_variant.get(i), per_variant.get(i + 1)) {
            (Some(&start), Some(&end)) => start as usize..end as usize,
            _ => 0..0,
        };
        let offsets = view::<u32>(&self.buf, &self.string_offsets);
        let bytes = view::<u8>(&self.buf, &self.string_bytes);
        range.map(move |s| text_at(bytes, offsets, s))
    }

    /// Heap bytes the chunk holds.
    pub fn heap_size(&self) -> usize {
        self.buf.heap_size()
    }
}

/// Encodes a bucket carrier chunk: per bucket of the mask chunk, in order, the positions of
/// the patients holding `he`, `hen` or `ho` on any of its variants, in a group of
/// `patient_count` patients.
///
/// # Errors
/// [`FormatError::InvalidInput`] when a position list is not strictly ascending below
/// `patient_count`, or a length exceeds its field.
pub fn encode_carrier_chunk<B: AsRef<[u32]>>(buckets: &[B], patient_count: u32) -> Result<Vec<u8>> {
    let mut offsets = vec![0u32];
    let mut bytes = Vec::new();
    for bucket in buckets {
        encode_pos_set(bucket.as_ref(), patient_count, &mut bytes)?;
        offsets.push(to_u32(bytes.len(), "carrier bytes")?);
    }
    let mut w = PayloadWriter::default();
    w.u8(GenoBlockKind::Carriers as u8);
    w.zeros(3);
    w.u32(to_u32(buckets.len(), "bucket count")?);
    w.array(&offsets);
    w.bytes(&bytes);
    Ok(w.buf)
}

/// A decoded and checked bucket carrier chunk.
#[derive(Clone, Debug)]
pub struct CarrierChunk {
    buf: AlignedBuf,
    patient_count: u32,
    offsets: Range<usize>,
    bytes: Range<usize>,
}

impl CarrierChunk {
    /// Decodes `buf`, the carriers of a chunk of `bucket_count` buckets in a group of
    /// `patient_count` patients (both from the index), and checks every set.
    ///
    /// # Errors
    /// [`FormatError::Truncated`] or [`FormatError::Corrupt`].
    pub fn decode(buf: AlignedBuf, bucket_count: u32, patient_count: u32) -> Result<Self> {
        let what = "carrier chunk";
        let mut r = PayloadReader::new(buf.as_bytes(), what);
        let kind = r.u8()?;
        if kind != GenoBlockKind::Carriers as u8 {
            return Err(FormatError::corrupt(what, format!("kind byte {kind}")));
        }
        r.reserved(3)?;
        let count = r.u32()?;
        if count != bucket_count {
            return Err(FormatError::corrupt(
                what,
                format!("bucket count {count}, the index says {bucket_count}"),
            ));
        }
        let offsets = r.offsets::<u32>(count as usize)?;
        let bytes = r.array::<u8>(data_len(&buf, &offsets))?;
        r.finish()?;
        let table = view::<u32>(&buf, &offsets);
        let data = view::<u8>(&buf, &bytes);
        check_offsets(table, data.len(), what)?;
        for j in 0..count as usize {
            PosSet::parse(bytes_at(data, table, j), patient_count)?;
        }
        Ok(CarrierChunk {
            buf,
            patient_count,
            offsets,
            bytes,
        })
    }

    /// Number of buckets.
    pub fn len(&self) -> usize {
        view::<u32>(&self.buf, &self.offsets)
            .len()
            .saturating_sub(1)
    }

    /// True when the chunk holds no bucket.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// The carriers of bucket `j`, in the mask chunk's bucket order.
    pub fn bucket(&self, j: usize) -> Option<PosSet<'_>> {
        if j >= self.len() {
            return None;
        }
        let bytes = bytes_at(
            view::<u8>(&self.buf, &self.bytes),
            view::<u32>(&self.buf, &self.offsets),
            j,
        );
        Some(PosSet::from_validated(bytes, self.patient_count))
    }

    /// Heap bytes the chunk holds.
    pub fn heap_size(&self) -> usize {
        self.buf.heap_size()
    }
}
