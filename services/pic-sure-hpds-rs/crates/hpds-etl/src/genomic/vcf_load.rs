//! `hpds-etl genomic-load`: VCF index files and their VCFs to a new Rust genomic store, as
//! the Java pipeline `SplitChromosomeVcfLoader`, `SplitChromosomeVariantMetadataLoader` and
//! `GenomicDatasetFinalizer` builds a Java store, one group per index file.
//!
//! What the store holds, per group and node, from the Java source:
//!
//! - **Groups** are named on the command line (none in `localDistributed`). A group's patient
//!   list is the ascending union of its index lines' patient ids, shared by its nodes.
//! - **Nodes.** Each index line's VCF is one node, named by its first record's CHROM.
//! - **Masks.** Records with one spec (`CHROM,POS,REF,ALT,gene,consequence`) merge. Each
//!   sample's genotype class (`(c0 + c1 + c2) % 7`) sets its patient in `he` (`0/1`), `ho`
//!   (`1/1`), `hen` (`./1`) or `hon` (`./.`); `0/0` and `./0` set nothing. A mask holding
//!   more than 5 patients has Java's bitmask form, 1 to 5 the sparse form, and none is
//!   absent; every spec has a mask record, `{}` when nobody carries it.
//! - **Metadata.** For annotated lines, each record's INFO (trimmed) under its spec, in
//!   record order.
//! - **INFO columns.** The keys the group's annotated VCF headers declare, the description
//!   from the first such VCF in index order; per node the columns that received a value,
//!   converted as Java converts them ([`super::info_store`]).
//! - **Bucket carriers.** Per 1000-bp bucket, the patients holding `he`, `hen` or `ho` on
//!   any of its variants, as the finalizer's `BucketIndexBySample` records them.
//!
//! Inputs that make Java store corrupt data are refused, naming the file and line: an index
//! sample missing from its VCF's header (unless `skip_missing_samples`, which drops it and
//! keeps the patient out of the group's list when no other line holds it), a multi-allelic
//! ALT, a genotype outside the biallelic forms, a sample field that is not three characters
//! on a GT-only record, records out of order, a contig in two VCFs of a group, a patient in
//! two groups, a spec with fewer than six fields, and the other cases [`super::vcf`] names.
//!
//! Each node is read twice, nodes in parallel on `threads` workers: the first pass checks the
//! records, numbers the specs in first-seen order (Java's spec index) and builds the node's
//! INFO columns, which go to a scratch file so that only nodes in flight are held in memory;
//! once every node's columns are known the store's column table is fixed, and the second
//! pass streams the records bucket by bucket into chunks. A node holds its INFO columns and
//! one bucket at a time, so memory does not grow with the number of chunks.

use std::collections::{BTreeSet, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::time::Instant;

use hpds_format::{
    CryptoKey, GenoBlockCodec, GenoGroup, GenoMode, MAX_GENO_GROUPS, MAX_GENO_NODES_PER_GROUP,
    Protection, StoreId, build_java_compressed_index, compare_specs, java_string_cmp, java_trim,
};
use rayon::prelude::*;
use serde::Serialize;

use super::info_store::{ConvertedColumn, InfoAccumulator};
use super::vcf::{
    IndexLine, Record, VcfLines, genotype_class, parse_record, read_header, read_index, refuse,
    spec_notation,
};
use super::writer::{
    BucketVariant, BuiltNode, InfoColumnInput, InfoColumnsBuilder, NodeBuilder, VariantMask,
    assemble, store_header,
};
use crate::error::{EtlError, Result};
use crate::load::{REPORT_FILE_NAME, Staging, thread_pool};
use crate::private_fs;

/// Java's variant bucket width.
const BUCKET_WIDTH: i32 = 1000;

/// A mask with more carriers than this has Java's bitmask form.
const SPARSE_VARIANT_THRESHOLD: usize = 5;

/// Everything `hpds-etl genomic-load` needs.
#[derive(Clone, Debug)]
pub struct GenomicLoadOptions {
    /// The layout: `localDistributed` or `localPatientDistributed`.
    pub mode: GenoMode,
    /// The groups: name (empty in `localDistributed`) and index file. VCF paths in an index
    /// file resolve against the working directory, as Java resolves them.
    pub groups: Vec<(String, PathBuf)>,
    /// The store directory to create. It must not exist.
    pub out: PathBuf,
    /// The phenotype store's key file, which seals the store.
    pub key_file: PathBuf,
    /// Drop index samples missing from their VCF's header instead of refusing them.
    pub skip_missing_samples: bool,
    /// Worker threads, one node each; `None` uses one per core.
    pub threads: Option<usize>,
}

/// One group of the loaded store.
#[derive(Clone, Debug, Serialize)]
pub struct GroupReport {
    /// The group name, empty in `localDistributed`.
    pub name: String,
    /// The index file read.
    pub index: PathBuf,
    /// Patients in the group's list.
    pub patients: u64,
    /// Nodes of the group.
    pub nodes: u64,
    /// Index samples dropped as missing from their VCF, over the group's lines.
    pub dropped_samples: u64,
}

/// What loading one node did.
#[derive(Clone, Debug, Default, Serialize)]
pub struct NodeReport {
    /// The group name.
    pub group: String,
    /// The node directory name, the VCF's first CHROM.
    pub dir: String,
    /// The VCF read.
    pub vcf: PathBuf,
    /// Contigs of the VCF.
    pub contigs: u64,
    /// Data records read.
    pub records: u64,
    /// Variants: distinct specs.
    pub variants: u64,
    /// Buckets holding variants.
    pub buckets: u64,
    /// Chunks written.
    pub chunks: u64,
    /// INFO columns holding a value on the node.
    pub info_columns: u64,
    /// Patients of index samples dropped as missing from the VCF header, ascending.
    pub dropped_patients: Vec<u32>,
}

/// What a genomic load did, written to the store's `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct GenomicLoadReport {
    /// The layout.
    pub mode: String,
    /// Whether the store is encrypted.
    pub encrypted: bool,
    /// The new store's id.
    pub store_id: String,
    /// The groups.
    pub groups: Vec<GroupReport>,
    /// Patients over all groups.
    pub patients: u64,
    /// Nodes.
    pub node_count: u64,
    /// Data records read, summed over nodes.
    pub records: u64,
    /// Variants, summed over nodes.
    pub variants: u64,
    /// Buckets, summed over nodes.
    pub buckets: u64,
    /// Chunks, summed over nodes.
    pub chunks: u64,
    /// Index samples dropped as missing from their VCF.
    pub dropped_samples: u64,
    /// Continuous columns, as `node:column`, whose `CompressedIndex` differs from the Rust
    /// port's; always empty, since the load builds them with the port.
    pub compressed_index_port_differences: Vec<String>,
    /// Bytes of `geno.blocks`.
    pub blocks_bytes: u64,
    /// Bytes of `geno.index`.
    pub index_bytes: u64,
    /// The nodes in index order.
    pub nodes: Vec<NodeReport>,
    /// Seconds of the first pass over every node.
    pub first_pass_seconds: f64,
    /// Seconds of the second pass over every node.
    pub second_pass_seconds: f64,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// A group as planned from its index file and VCF headers.
struct GroupPlan {
    name: String,
    index: PathBuf,
    patients: Vec<u32>,
    declared: Vec<String>,
    descriptions: HashMap<String, String>,
    dropped_samples: u64,
}

/// One index sample read from a VCF: its column among the header's samples and its
/// position in the group's patient list.
#[derive(Clone, Copy, Debug)]
struct SampleRead {
    column: usize,
    position: u32,
}

/// A node as planned from its index line and VCF header.
struct NodePlan {
    group: u16,
    index: PathBuf,
    line: IndexLine,
    dir: String,
    header_samples: usize,
    reads: Vec<SampleRead>,
    shared_columns: bool,
    dropped_patients: Vec<u32>,
}

/// What the first pass learned of a node.
struct FirstPass {
    contigs: Vec<(String, u32)>,
    source_count: u32,
    records: u64,
    columns: Vec<String>,
}

/// Loads the VCFs named by `options.groups` into a new genomic store at `options.out`,
/// staged beside it and moved into place whole.
///
/// # Errors
/// [`EtlError::Usage`] for a mode other than `localDistributed` and
/// `localPatientDistributed`, groups that do not fit it, an existing `out`, or a key that
/// cannot be loaded; [`EtlError::Input`] naming the file and line of an input Java would
/// store corrupt data from or fail on; format and I/O errors.
pub fn genomic_load(options: &GenomicLoadOptions) -> Result<GenomicLoadReport> {
    let started = Instant::now();
    check_groups(options)?;
    if options.out.exists() {
        return Err(EtlError::Usage(format!(
            "{} exists; genomic-load writes a new store directory, so remove it or name another",
            options.out.display()
        )));
    }
    let key = CryptoKey::load(&options.key_file).map_err(|e| {
        EtlError::Usage(format!(
            "cannot load the encryption key {}: {e}",
            options.key_file.display()
        ))
    })?;
    let protection = Protection::Encrypted(key);
    let mut groups = Vec::new();
    let mut nodes = Vec::new();
    for (g, (name, index)) in options.groups.iter().enumerate() {
        let (group, group_nodes) = plan_group(g as u16, name, index, options.skip_missing_samples)?;
        groups.push(group);
        nodes.extend(group_nodes);
    }
    check_patients_once(&groups)?;
    nodes.sort_by(|a, b| a.group.cmp(&b.group).then_with(|| java_string_cmp(&a.dir, &b.dir)));
    let pool = thread_pool(options.threads)?;

    let staging = Staging::create(&options.out)?;
    let scratch = staging.path().join(".nodes");
    private_fs::create_dir(&scratch).map_err(EtlError::io("creating", &scratch))?;
    let first_started = Instant::now();
    let first: Vec<FirstPass> = pool.install(|| {
        nodes
            .par_iter()
            .enumerate()
            .map(|(i, node)| first_pass(node, &groups[usize::from(node.group)], &scratch, i))
            .collect::<Result<Vec<_>>>()
    })?;
    let first_pass_seconds = first_started.elapsed().as_secs_f64();
    check_contigs_once(&nodes, &first)?;
    let mut columns: Vec<String> = first.iter().flat_map(|f| f.columns.clone()).collect();
    columns.sort_by(|a, b| java_string_cmp(a, b));
    columns.dedup();
    if columns.len() > usize::from(u16::MAX) {
        return Err(EtlError::Usage(format!(
            "{} INFO columns exceed u16",
            columns.len()
        )));
    }

    let store_id = StoreId::random()?;
    let codec = GenoBlockCodec::new(protection.clone(), store_id);
    let second_started = Instant::now();
    let built: Vec<(BuiltNode, NodeReport)> = pool.install(|| {
        nodes
            .par_iter()
            .zip(&first)
            .enumerate()
            .map(|(i, (node, pass))| {
                second_pass(&SecondPass {
                    node,
                    group: &groups[usize::from(node.group)],
                    first: pass,
                    node_index: i as u16,
                    codec: &codec,
                    scratch: &scratch,
                    columns: &columns,
                })
            })
            .collect::<Result<Vec<_>>>()
    })?;
    let second_pass_seconds = second_started.elapsed().as_secs_f64();
    let (built, node_reports): (Vec<BuiltNode>, Vec<NodeReport>) = built.into_iter().unzip();
    let geno_groups: Vec<GenoGroup> = groups
        .iter()
        .map(|g| GenoGroup {
            name: g.name.clone(),
            patients: g.patients.clone(),
        })
        .collect();
    let (_, sizes) = assemble(
        staging.path(),
        store_header(&protection, store_id),
        options.mode,
        geno_groups,
        built,
        columns,
        &protection,
    )?;
    std::fs::remove_dir_all(&scratch).map_err(EtlError::io("removing", &scratch))?;

    let sum = |f: fn(&NodeReport) -> u64| node_reports.iter().map(f).sum::<u64>();
    let mut report = GenomicLoadReport {
        mode: options.mode.java_name().to_string(),
        encrypted: true,
        store_id: store_id.to_string(),
        groups: groups
            .iter()
            .enumerate()
            .map(|(g, group)| GroupReport {
                name: group.name.clone(),
                index: group.index.clone(),
                patients: group.patients.len() as u64,
                nodes: nodes.iter().filter(|n| usize::from(n.group) == g).count() as u64,
                dropped_samples: group.dropped_samples,
            })
            .collect(),
        patients: groups.iter().map(|g| g.patients.len() as u64).sum(),
        node_count: node_reports.len() as u64,
        records: sum(|n| n.records),
        variants: sum(|n| n.variants),
        buckets: sum(|n| n.buckets),
        chunks: sum(|n| n.chunks),
        dropped_samples: groups.iter().map(|g| g.dropped_samples).sum(),
        compressed_index_port_differences: Vec::new(),
        blocks_bytes: sizes.blocks_bytes,
        index_bytes: sizes.index_bytes,
        nodes: node_reports,
        first_pass_seconds,
        second_pass_seconds,
        elapsed_seconds: 0.0,
    };
    report.elapsed_seconds = started.elapsed().as_secs_f64();
    let report_path = staging.path().join(REPORT_FILE_NAME);
    let json = serde_json::to_vec_pretty(&report)
        .map_err(|e| EtlError::io("encoding", &report_path)(e.into()))?;
    private_fs::write_file(&report_path, &json).map_err(EtlError::io("writing", &report_path))?;
    staging.publish(&options.out, store_id)?;
    tracing::info!(
        out = %options.out.display(),
        nodes = report.node_count,
        variants = report.variants,
        seconds = report.elapsed_seconds,
        "genomic store loaded"
    );
    Ok(report)
}

/// Checks the groups against the mode: one unnamed group in `localDistributed`, one to ten
/// named groups in `localPatientDistributed`, names unique and usable as directory names.
fn check_groups(options: &GenomicLoadOptions) -> Result<()> {
    let names: Vec<&str> = options.groups.iter().map(|(n, _)| n.as_str()).collect();
    match options.mode {
        GenoMode::LocalDistributed => {
            if names != [""] {
                return Err(EtlError::Usage(
                    "localDistributed takes exactly one index file and no group name".into(),
                ));
            }
        }
        GenoMode::LocalPatientDistributed => {
            if names.is_empty() || names.len() > MAX_GENO_GROUPS {
                return Err(EtlError::Usage(format!(
                    "localPatientDistributed takes 1 to {MAX_GENO_GROUPS} groups, not {}",
                    names.len()
                )));
            }
            for (i, name) in names.iter().enumerate() {
                if name.is_empty() || *name == "." || *name == ".." || name.contains('/') {
                    return Err(EtlError::Usage(format!(
                        "group name {name:?} is not a directory name"
                    )));
                }
                if names[..i].contains(name) {
                    return Err(EtlError::Usage(format!("group {name:?} is named twice")));
                }
            }
            let mut sorted = names.clone();
            sorted.sort_by(|a, b| java_string_cmp(a, b));
            if sorted != names {
                return Err(EtlError::Usage(
                    "name the groups in Java String.compareTo order, the order of the store's group table".into(),
                ));
            }
        }
        other => {
            return Err(EtlError::Usage(format!(
                "genomic-load writes the layouts SplitChromosomeVcfLoader writes, localDistributed and localPatientDistributed, not {}",
                other.java_name()
            )));
        }
    }
    Ok(())
}

/// Reads a group's index file and each VCF's header and first record, and plans its nodes.
fn plan_group(
    group: u16,
    name: &str,
    index: &Path,
    skip_missing_samples: bool,
) -> Result<(GroupPlan, Vec<NodePlan>)> {
    let lines = read_index(index)?;
    if lines.is_empty() {
        return Err(refuse(index, 1, "the index file lists no VCF"));
    }
    if lines.len() > MAX_GENO_NODES_PER_GROUP {
        return Err(refuse(
            index,
            1,
            format!(
                "{} VCFs make more than {MAX_GENO_NODES_PER_GROUP} chromosome nodes, the most Java serves in a group",
                lines.len()
            ),
        ));
    }
    let mut declared: Vec<String> = Vec::new();
    let mut descriptions: HashMap<String, (String, PathBuf)> = HashMap::new();
    let unordered = lines.iter().any(|l| l.contig.is_none());
    let mut headers = Vec::with_capacity(lines.len());
    for line in &lines {
        let mut vcf = VcfLines::open(&line.vcf, line.gzipped)?;
        let header = read_header(&mut vcf, line.annotated)?;
        let (n, text) = vcf
            .next_line()?
            .ok_or_else(|| refuse(&line.vcf, 0, "the VCF holds no record"))?;
        let first = parse_record(text)
            .map_err(|d| refuse(&line.vcf, n, d))?
            .contig
            .to_string();
        for declaration in &header.info {
            match descriptions.get(&declaration.key) {
                None => {
                    declared.push(declaration.key.clone());
                    descriptions.insert(
                        declaration.key.clone(),
                        (declaration.description.clone(), line.vcf.clone()),
                    );
                }
                Some((description, from)) if unordered && *description != declaration.description => {
                    return Err(refuse(
                        index,
                        line.line,
                        format!(
                            "{} and {} describe INFO column {} differently, and with a chromosome of ALL in the index Java's choice between them is unspecified",
                            from.display(),
                            line.vcf.display(),
                            declaration.key
                        ),
                    ));
                }
                Some(_) => {}
            }
        }
        headers.push((first, header.samples));
    }
    for (i, (dir, _)) in headers.iter().enumerate() {
        if let Some(j) = headers[..i].iter().position(|(d, _)| d == dir) {
            return Err(refuse(
                index,
                lines[i].line,
                format!(
                    "{} and {} both start with contig {dir}, the node directory Java writes both into",
                    lines[j].vcf.display(),
                    lines[i].vcf.display()
                ),
            ));
        }
    }

    let mut kept: Vec<Vec<(usize, u32)>> = Vec::with_capacity(lines.len());
    let mut dropped: Vec<Vec<u32>> = Vec::with_capacity(lines.len());
    let mut dropped_samples = 0u64;
    let mut patients = BTreeSet::new();
    for (line, (_, samples)) in lines.iter().zip(&headers) {
        let mut columns: HashMap<&str, usize> = HashMap::with_capacity(samples.len());
        for (y, sample) in samples.iter().enumerate().rev() {
            columns.insert(sample.as_str(), y);
        }
        let mut line_kept = Vec::with_capacity(line.samples.len());
        let mut line_dropped = Vec::new();
        let mut missing: Vec<&str> = Vec::new();
        for (sample, &patient) in line.samples.iter().zip(&line.patients) {
            match columns.get(sample.as_str()) {
                Some(&y) => line_kept.push((y, patient)),
                None => {
                    missing.push(sample);
                    line_dropped.push(patient);
                }
            }
        }
        if !missing.is_empty() && !skip_missing_samples {
            return Err(refuse(
                index,
                line.line,
                format!(
                    "{} index samples are missing from the header of {} (the first is {}); Java gives each the genotypes of the VCF's last sample. Pass --skip-missing-samples to leave them out",
                    missing.len(),
                    line.vcf.display(),
                    missing[0]
                ),
            ));
        }
        dropped_samples += missing.len() as u64;
        patients.extend(line_kept.iter().map(|&(_, p)| p));
        line_dropped.sort_unstable();
        line_dropped.dedup();
        kept.push(line_kept);
        dropped.push(line_dropped);
    }
    let patients: Vec<u32> = patients.into_iter().collect();
    let mut nodes = Vec::with_capacity(lines.len());
    for (((line, (dir, samples)), line_kept), line_dropped) in
        lines.into_iter().zip(headers).zip(kept).zip(dropped)
    {
        let reads: Vec<SampleRead> = line_kept
            .iter()
            .map(|&(column, patient)| SampleRead {
                column,
                position: patients
                    .binary_search(&patient)
                    .map(|p| p as u32)
                    .unwrap_or_default(),
            })
            .collect();
        let mut seen = HashSet::with_capacity(reads.len());
        let shared_columns = !reads.iter().all(|r| seen.insert(r.column));
        nodes.push(NodePlan {
            group,
            index: index.to_path_buf(),
            line,
            dir,
            header_samples: samples.len(),
            reads,
            shared_columns,
            dropped_patients: line_dropped,
        });
    }
    let descriptions = descriptions
        .into_iter()
        .map(|(key, (description, _))| (key, description))
        .collect();
    Ok((
        GroupPlan {
            name: name.to_string(),
            index: index.to_path_buf(),
            patients,
            declared,
            descriptions,
            dropped_samples,
        },
        nodes,
    ))
}

/// Refuses patient ids found in two groups, with their count.
fn check_patients_once(groups: &[GroupPlan]) -> Result<()> {
    let mut seen: HashSet<u32> = HashSet::new();
    let mut repeated = 0u64;
    for group in groups {
        for &id in &group.patients {
            if !seen.insert(id) {
                repeated += 1;
            }
        }
    }
    if repeated > 0 {
        return Err(EtlError::Usage(format!(
            "{repeated} patient ids appear in more than one group; each patient must belong to one group"
        )));
    }
    Ok(())
}

/// Refuses a contig held by two VCFs of one group: Java records each contig's INFO values
/// once per load, so the second VCF's would be lost.
fn check_contigs_once(nodes: &[NodePlan], first: &[FirstPass]) -> Result<()> {
    let mut seen: HashMap<(u16, &str), &Path> = HashMap::new();
    for (node, pass) in nodes.iter().zip(first) {
        for (contig, _) in &pass.contigs {
            if let Some(other) = seen.insert((node.group, contig.as_str()), &node.line.vcf) {
                return Err(refuse(
                    &node.index,
                    node.line.line,
                    format!(
                        "contig {contig} is in {} and {}; Java keeps one VCF's INFO values for a contig",
                        other.display(),
                        node.line.vcf.display()
                    ),
                ));
            }
        }
    }
    Ok(())
}

/// The name of a contig's Java mask file, which two contigs of a node must not share.
fn mask_file_name(contig: &str) -> String {
    if contig.starts_with("chr") {
        format!("{contig}masks.bin")
    } else {
        format!("chr{contig}masks.bin")
    }
}

/// The scratch file holding node `i`'s converted INFO columns.
fn columns_file(scratch: &Path, i: usize) -> PathBuf {
    scratch.join(format!("{i:05}.columns.json"))
}

/// The first pass over a node's VCF: checks every record, numbers specs in first-seen order,
/// and writes the node's converted INFO columns to its scratch file.
fn first_pass(node: &NodePlan, group: &GroupPlan, scratch: &Path, i: usize) -> Result<FirstPass> {
    let path = &node.line.vcf;
    let mut vcf = VcfLines::open(path, node.line.gzipped)?;
    read_header(&mut vcf, false)?;
    let mut info = InfoAccumulator::new(group.declared.iter().map(String::as_str));
    let mut contigs: Vec<(String, u32)> = Vec::new();
    let mut mask_files: HashSet<String> = HashSet::new();
    let mut bucket: Option<i32> = None;
    let mut bucket_specs: HashMap<String, u32> = HashMap::new();
    let mut last_position = 0;
    let mut next_id = 0u32;
    let mut records = 0u64;
    while let Some((n, text)) = vcf.next_line()? {
        if text.is_empty() || text.starts_with('#') {
            return Err(refuse(path, n, "an empty or header line among the records"));
        }
        let record = parse_record(text).map_err(|d| refuse(path, n, d))?;
        if contigs.last().is_none_or(|(c, _)| c != record.contig) {
            if contigs.iter().any(|(c, _)| c == record.contig) {
                return Err(refuse(
                    path,
                    n,
                    format!(
                        "contig {} appears again after other contigs; records must be sorted",
                        record.contig
                    ),
                ));
            }
            if !mask_files.insert(mask_file_name(record.contig)) {
                return Err(refuse(
                    path,
                    n,
                    format!(
                        "contig {} shares Java's mask file {} with another contig of the VCF",
                        record.contig,
                        mask_file_name(record.contig)
                    ),
                ));
            }
            contigs.push((record.contig.to_string(), next_id));
            bucket = None;
        } else if record.position < last_position {
            return Err(refuse(
                path,
                n,
                format!(
                    "position {} comes after {last_position}; records must be sorted",
                    record.position
                ),
            ));
        }
        last_position = record.position;
        let record_bucket = record.position / BUCKET_WIDTH;
        if bucket != Some(record_bucket) {
            bucket = Some(record_bucket);
            bucket_specs.clear();
        }
        let spec = spec_notation(&record).map_err(|d| refuse(path, n, d))?;
        let id = match bucket_specs.get(&spec) {
            Some(&id) => id,
            None => {
                let id = next_id;
                next_id = next_id
                    .checked_add(1)
                    .ok_or_else(|| refuse(path, n, "more than 4294967295 specs in one VCF"))?;
                bucket_specs.insert(spec, id);
                id
            }
        };
        info.process(id, record.info);
        records += 1;
    }
    if records == 0 {
        return Err(refuse(path, 0, "the VCF holds no record"));
    }
    let converted = info.finish();
    for column in &converted {
        if column.continuous {
            let keys: Vec<&str> = column.keys.iter().map(|(k, _)| k.as_str()).collect();
            if build_java_compressed_index(&keys).is_none() {
                return Err(refuse(
                    path,
                    0,
                    format!(
                        "INFO column {} is numeric on node {}, yet no key parses as a float, where Java's CompressedIndex build fails",
                        column.name, node.dir
                    ),
                ));
            }
        }
    }
    let columns = converted.iter().map(|c| c.name.clone()).collect();
    let file = columns_file(scratch, i);
    let json = serde_json::to_vec(&converted).map_err(|e| EtlError::io("encoding", &file)(e.into()))?;
    private_fs::write_file(&file, &json).map_err(EtlError::io("writing", &file))?;
    Ok(FirstPass {
        contigs,
        source_count: next_id,
        records,
        columns,
    })
}

/// What a second pass over one node needs.
struct SecondPass<'a> {
    node: &'a NodePlan,
    group: &'a GroupPlan,
    first: &'a FirstPass,
    node_index: u16,
    codec: &'a GenoBlockCodec,
    scratch: &'a Path,
    columns: &'a [String],
}

/// One spec of the bucket being read: its source id, the positions of each genotype class
/// it records (`he`, `ho`, `hen`, `hon`), and its metadata strings.
struct SpecAccumulator {
    spec: String,
    source: u32,
    classes: [Vec<u32>; 4],
    metadata: Vec<String>,
}

/// The mask slot of a genotype class: `he` for 4, `ho` for 5, `hen` for 2, `hon` for 6.
fn class_slot(class: u8) -> Option<usize> {
    match class {
        4 => Some(0),
        5 => Some(1),
        2 => Some(2),
        6 => Some(3),
        _ => None,
    }
}

/// The second pass over a node's VCF: the masks, metadata and carriers of every bucket,
/// then the INFO blocks, into the node's temporary blocks file.
fn second_pass(context: &SecondPass<'_>) -> Result<(BuiltNode, NodeReport)> {
    let node = context.node;
    let path = &node.line.vcf;
    let file = columns_file(context.scratch, usize::from(context.node_index));
    let json = std::fs::read(&file).map_err(EtlError::io("reading", &file))?;
    let converted: Vec<ConvertedColumn> = serde_json::from_slice(&json)
        .map_err(|e| EtlError::io("decoding", &file)(e.into()))?;
    std::fs::remove_file(&file).map_err(EtlError::io("removing", &file))?;
    let info_columns = converted.len() as u64;
    let mut inputs = Vec::with_capacity(converted.len());
    for column in converted {
        let index = context
            .columns
            .binary_search_by(|c| java_string_cmp(c, &column.name))
            .map_err(|_| EtlError::Usage(format!("column {} is not in the column table", column.name)))?;
        let ranges = if column.continuous {
            let keys: Vec<&str> = column.keys.iter().map(|(k, _)| k.as_str()).collect();
            Some(build_java_compressed_index(&keys).ok_or_else(|| {
                EtlError::Usage(format!("column {} has no CompressedIndex", column.name))
            })?)
        } else {
            None
        };
        inputs.push(InfoColumnInput {
            column: index as u16,
            description: context
                .group
                .descriptions
                .get(&column.name)
                .cloned()
                .unwrap_or_default(),
            name: column.name,
            keys: column.keys,
            ranges,
        });
    }
    let mut info = InfoColumnsBuilder::new(inputs, context.first.source_count)?;
    let patient_count = context.group.patients.len() as u32;
    let mut builder = NodeBuilder::new(
        context.node_index,
        node.group,
        node.dir.clone(),
        patient_count,
        context.codec.clone(),
        context
            .scratch
            .join(format!("{:05}.blocks", context.node_index)),
    )?;
    let mut report = NodeReport {
        group: context.group.name.clone(),
        dir: node.dir.clone(),
        vcf: path.clone(),
        contigs: context.first.contigs.len() as u64,
        records: context.first.records,
        info_columns,
        dropped_patients: node.dropped_patients.clone(),
        ..NodeReport::default()
    };
    let mut contigs = context.first.contigs.clone();
    contigs.sort_by(|a, b| java_string_cmp(&a.0, &b.0));
    for (contig, first_id) in &contigs {
        load_contig(context, contig, *first_id, &mut info, &mut builder, &mut report)?;
    }
    builder.finish_chunks()?;
    if let Some(id) = info.unassigned() {
        return Err(refuse(path, 0, format!("spec {id} of the first pass was not read again")));
    }
    let differences = info.finish(&mut builder, patient_count)?;
    if !differences.is_empty() {
        return Err(EtlError::Usage(format!(
            "node {}: CompressedIndex differs from the port for {differences:?}",
            node.dir
        )));
    }
    report.variants = u64::from(builder.variant_count());
    report.chunks = builder.chunk_count() as u64;
    Ok((builder.finish()?, report))
}

/// Reads the records of one contig of a node's VCF, bucket by bucket, into `builder`.
fn load_contig(
    context: &SecondPass<'_>,
    contig: &str,
    first_id: u32,
    info: &mut InfoColumnsBuilder,
    builder: &mut NodeBuilder,
    report: &mut NodeReport,
) -> Result<()> {
    let node = context.node;
    let path = &node.line.vcf;
    let mut vcf = VcfLines::open(path, node.line.gzipped)?;
    read_header(&mut vcf, false)?;
    let mut next_id = first_id;
    let mut bucket: Option<i32> = None;
    let mut specs: Vec<SpecAccumulator> = Vec::new();
    let mut by_spec: HashMap<String, usize> = HashMap::new();
    let mut started = false;
    while let Some((n, text)) = vcf.next_line()? {
        let record = parse_record(text).map_err(|d| refuse(path, n, d))?;
        if record.contig != contig {
            if started {
                break;
            }
            continue;
        }
        started = true;
        let record_bucket = record.position / BUCKET_WIDTH;
        if bucket != Some(record_bucket) {
            if let Some(done) = bucket {
                add_bucket(contig, done, std::mem::take(&mut specs), info, builder, report)?;
                by_spec.clear();
            }
            bucket = Some(record_bucket);
        }
        let spec = spec_notation(&record).map_err(|d| refuse(path, n, d))?;
        let slot = match by_spec.get(&spec) {
            Some(&slot) => slot,
            None => {
                specs.push(SpecAccumulator {
                    spec: spec.clone(),
                    source: next_id,
                    classes: Default::default(),
                    metadata: Vec::new(),
                });
                next_id += 1;
                by_spec.insert(spec, specs.len() - 1);
                specs.len() - 1
            }
        };
        let accumulator = &mut specs[slot];
        read_genotypes(&record, node, &mut accumulator.classes).map_err(|d| refuse(path, n, d))?;
        if node.line.annotated {
            accumulator.metadata.push(java_trim(record.info).to_string());
        }
    }
    if let Some(done) = bucket {
        add_bucket(contig, done, specs, info, builder, report)?;
    }
    Ok(())
}

/// Adds the genotype classes of a record's index samples to `classes`.
///
/// On a record whose FORMAT is `GT`, Java reads sample `y` at a fixed offset of `4 y`
/// characters, so every sample field must be three characters. Otherwise it reads the first
/// three characters of each field among the first as many columns as the index line has
/// samples, keeping one sample per column.
///
/// # Errors
/// The refusal's text.
fn read_genotypes(
    record: &Record<'_>,
    node: &NodePlan,
    classes: &mut [Vec<u32>; 4],
) -> std::result::Result<(), String> {
    let samples = node.header_samples;
    let mut add = |gt: &[u8], read: &SampleRead| -> std::result::Result<(), String> {
        let class = genotype_class(gt).ok_or_else(|| {
            format!(
                "the genotype in sample column {} is outside the biallelic forms Java decodes (0, 1 or . on each side of / or |)",
                read.column + 1
            )
        })?;
        if let Some(slot) = class_slot(class) {
            classes[slot].push(read.position);
        }
        Ok(())
    };
    if record.format.len() == 2 {
        if record.format != "GT" {
            return Err(format!(
                "FORMAT {:?} has two characters but is not GT; Java reads it as GT",
                record.format
            ));
        }
        let bytes = record.samples.as_bytes();
        let laid_out = bytes.len() + 1 == samples * 4
            && bytes.iter().filter(|&&b| b == b'\t').count() + 1 == samples
            && (1..samples).all(|i| bytes[i * 4 - 1] == b'\t');
        if !laid_out {
            return Err(format!(
                "on a GT-only record Java reads each of the {samples} sample fields at a fixed offset, so each must be three characters"
            ));
        }
        for read in &node.reads {
            add(&bytes[read.column * 4..read.column * 4 + 3], read)?;
        }
        return Ok(());
    }
    if !record.format.starts_with("GT:") {
        return Err(format!(
            "FORMAT {:?} does not start with GT, yet Java reads the first three characters of each sample field as the genotype",
            record.format
        ));
    }
    let fields: Vec<&str> = record.samples.split('\t').collect();
    if fields.len() != samples {
        return Err(format!(
            "the record has {} sample fields where the header names {samples}",
            fields.len()
        ));
    }
    if node.shared_columns {
        return Err(
            "two index samples name one VCF column, and on a record whose FORMAT is not GT only Java keeps one of them".into(),
        );
    }
    let read_columns = node.line.samples.len();
    for read in &node.reads {
        if read.column >= read_columns {
            return Err(format!(
                "sample column {} lies past the first {read_columns} columns, the ones Java reads on a record whose FORMAT is not GT only",
                read.column + 1
            ));
        }
        let gt = fields[read.column]
            .split(':')
            .next()
            .unwrap_or_default()
            .as_bytes();
        if gt.len() != 3 {
            return Err(format!(
                "the genotype in sample column {} is not three characters, as Java reads it",
                read.column + 1
            ));
        }
        add(gt, read)?;
    }
    Ok(())
}

/// Turns one bucket's specs into masks and carriers and adds them to the node, giving each
/// spec its node variant id in the store's spec order.
fn add_bucket(
    contig: &str,
    bucket: i32,
    specs: Vec<SpecAccumulator>,
    info: &mut InfoColumnsBuilder,
    builder: &mut NodeBuilder,
    report: &mut NodeReport,
) -> Result<()> {
    let mut variants: Vec<(u32, BucketVariant)> = specs
        .into_iter()
        .map(|accumulator| {
            let record = accumulator.classes.map(|mut positions| {
                positions.sort_unstable();
                positions.dedup();
                (!positions.is_empty()).then_some(VariantMask {
                    bitmask_form: positions.len() > SPARSE_VARIANT_THRESHOLD,
                    positions,
                })
            });
            (
                accumulator.source,
                BucketVariant {
                    spec: accumulator.spec,
                    record: Some(record),
                    metadata: accumulator.metadata,
                },
            )
        })
        .collect();
    variants.sort_by(|a, b| {
        compare_specs(&a.1.spec, &b.1.spec).unwrap_or(std::cmp::Ordering::Equal)
    });
    let first = builder.next_variant_id();
    let mut carriers: Vec<u32> = Vec::new();
    for (offset, (source, variant)) in variants.iter().enumerate() {
        info.assign(*source, first + offset as u32)
            .map_err(|detail| EtlError::Usage(format!("contig {contig} bucket {bucket}: {detail}")))?;
        let record = variant.record.as_ref().map_or(&[][..], |r| &r[..]);
        let positions = |slot: usize| record.get(slot).and_then(Option::as_ref).map_or(&[][..], |m| &m.positions[..]);
        info.add_carriers(*source, positions(0), positions(1));
        for slot in [0, 1, 2] {
            carriers.extend_from_slice(positions(slot));
        }
    }
    carriers.sort_unstable();
    carriers.dedup();
    report.buckets += 1;
    builder.add_bucket(
        contig,
        bucket,
        variants.into_iter().map(|(_, v)| v).collect(),
        carriers,
    )
}
