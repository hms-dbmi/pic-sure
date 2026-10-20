//! `hpds-etl genomic-migrate`: a Java genomic store, exported by `ExportGenomicForMigration`,
//! to a new Rust genomic store.
//!
//! The store keeps Java's data as stored: every node of the layout, each node's patient list
//! (one list per group), every mask with Java's form, the metadata strings in Java's order,
//! every info store with its keys and the `CompressedIndex` Java built, and the bucket
//! carriers from Java's `BucketIndexBySample`. A node's variants are the union of the specs
//! of its mask records, its metadata and its spec index, in the store's spec order; Java's
//! spec index ids become node variant ids through that order. What the store cannot
//! represent is refused with its location; what it represents but Java would treat
//! differently than the masks imply is counted in the report:
//!
//! - specs with fewer than six fields, kept and listed in the node's short specs;
//! - spec index entries naming a spec another entry already names (both ids map to one
//!   variant);
//! - buckets whose `BucketIndexBySample` carriers differ from the union of `he`, `hen` and
//!   `ho` over the bucket's masks (the store keeps Java's carriers, which the variant list
//!   prefilter reads); a node without a bucket index gets carriers derived from its masks,
//!   as Java builds the index when it is missing;
//! - specs present in two chromosome nodes of one group, which the engine resolves to the
//!   first node in index order;
//! - continuous columns whose stored `CompressedIndex` differs from the one the Rust port
//!   builds from the same keys.
//!
//! Patient ids must be Java `int`s from 0 to `i32::MAX`, appear once in a group, be the same
//! list on every node of a group, and appear in one group only: an id in two groups is
//! refused with the count of such ids.

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::time::Instant;

use hpds_format::{
    CompressedRange, CryptoKey, GENO_BLOCKS_FILE_NAME, GenoBlockCodec, GenoBlocksReader, GenoGroup,
    GenoIndex, GenoMode, MAX_GENO_GROUPS, MAX_GENO_NODES_PER_GROUP, PosBitset, Protection, StoreId,
    VariantSpecKey, compare_specs, java_parse_int, java_string_cmp,
};
use rayon::prelude::*;
use serde::Serialize;

use super::export::{
    ExportedBucket, ExportedCarriers, ExportedInfo, Manifest, NodeReader, read_manifest,
};
use super::writer::{
    BucketVariant, BuiltNode, InfoColumnInput, InfoColumnsBuilder, NodeBuilder, VariantMask,
    assemble, store_header,
};
use crate::error::{EtlError, Result};
use crate::load::{REPORT_FILE_NAME, Staging, thread_pool};
use crate::private_fs;

/// Everything `hpds-etl genomic-migrate` needs.
#[derive(Clone, Debug)]
pub struct GenomicMigrateOptions {
    /// Directory holding the export's manifest and node files.
    pub export: PathBuf,
    /// The store directory to create. It must not exist.
    pub out: PathBuf,
    /// The phenotype store's key file: it opens the export and seals the new store.
    pub key_file: PathBuf,
    /// Worker threads, one node each; `None` uses one per core.
    pub threads: Option<usize>,
}

/// One group of the migrated store.
#[derive(Clone, Debug, Serialize)]
pub struct GroupReport {
    /// The group directory name, empty in `local` and `localDistributed`.
    pub name: String,
    /// Patients in the group's list.
    pub patients: u64,
    /// Nodes of the group.
    pub nodes: u64,
}

/// What migrating one node did.
#[derive(Clone, Debug, Default, Serialize)]
pub struct NodeReport {
    /// The group directory name.
    pub group: String,
    /// The chromosome directory name.
    pub dir: String,
    /// Contigs holding variants.
    pub contigs: u64,
    /// Variants of the node: the distinct specs of its masks, metadata and spec index.
    pub variants: u64,
    /// Variants with a mask record.
    pub mask_records: u64,
    /// Variants without a mask record (known from the spec index or the metadata only).
    pub variants_without_record: u64,
    /// Of those, variants known from the metadata only.
    pub metadata_only_variants: u64,
    /// Entries of Java's spec index.
    pub spec_index_entries: u64,
    /// Spec index entries naming a spec an earlier entry already names.
    pub spec_index_duplicates: u64,
    /// Variants whose spec has fewer than six fields after Java's split.
    pub short_specs: u64,
    /// Buckets holding variants.
    pub buckets: u64,
    /// Chunks written.
    pub chunks: u64,
    /// INFO columns.
    pub info_columns: u64,
    /// True when the node had a `BucketIndexBySample`.
    pub bucket_index: bool,
    /// Buckets whose Java carriers differ from the union of `he`, `hen` and `ho` of their
    /// masks.
    pub carrier_disagreements: u64,
    /// Patients of the node's list without a bucket index mask.
    pub patients_missing_from_bucket_index: u64,
    /// Continuous columns whose `CompressedIndex` differs from the Rust port's.
    pub compressed_index_port_differences: Vec<String>,
    /// Bytes of the node's export file.
    pub export_bytes: u64,
}

/// What a genomic migration did, written to the store's `etl-report.json`.
#[derive(Clone, Debug, Serialize)]
pub struct GenomicMigrateReport {
    /// The export directory read.
    pub export: PathBuf,
    /// The layout.
    pub mode: String,
    /// Whether the store is encrypted.
    pub encrypted: bool,
    /// The new store's id.
    pub store_id: String,
    /// The store id, for publishing.
    #[serde(skip)]
    pub id: StoreId,
    /// The groups.
    pub groups: Vec<GroupReport>,
    /// Patients over all groups.
    pub patients: u64,
    /// Patient ids found in more than one group: always 0 in a written store, since any is
    /// refused.
    pub patients_in_two_groups: u64,
    /// Nodes.
    pub node_count: u64,
    /// Variants summed over nodes.
    pub variants: u64,
    /// Variants with a mask record, summed over nodes.
    pub mask_records: u64,
    /// Variants without a mask record, summed over nodes.
    pub variants_without_record: u64,
    /// Spec index duplicates, summed over nodes.
    pub spec_index_duplicates: u64,
    /// Short specs, summed over nodes.
    pub short_specs: u64,
    /// Buckets, summed over nodes.
    pub buckets: u64,
    /// Chunks, summed over nodes.
    pub chunks: u64,
    /// Carrier disagreements, summed over nodes.
    pub carrier_disagreements: u64,
    /// Nodes without a bucket index, whose carriers were derived from the masks.
    pub nodes_without_bucket_index: u64,
    /// Specs held by two or more chromosome nodes of one group, counted once per group.
    pub specs_in_two_nodes: u64,
    /// Continuous columns, as `node:column`, whose `CompressedIndex` differs from the Rust
    /// port's.
    pub compressed_index_port_differences: Vec<String>,
    /// Bytes of the export read.
    pub export_bytes: u64,
    /// Bytes of `geno.blocks`.
    pub blocks_bytes: u64,
    /// Bytes of `geno.index`.
    pub index_bytes: u64,
    /// The nodes in index order.
    pub nodes: Vec<NodeReport>,
    /// Wall time in seconds.
    pub elapsed_seconds: f64,
}

/// Migrates the export at `options.export` into a new genomic store at `options.out`,
/// staged beside it and moved into place whole.
///
/// # Errors
/// [`EtlError::Usage`] when `out` exists, the key cannot be loaded or the export is missing;
/// [`EtlError::Export`] when the export does not decrypt with the key, is malformed, breaks
/// the layout's rules, or holds what the store cannot represent (named with its location),
/// including a patient id in two groups (with the count of such ids); format and I/O errors.
pub fn genomic_migrate(options: &GenomicMigrateOptions) -> Result<GenomicMigrateReport> {
    let started = Instant::now();
    if options.out.exists() {
        return Err(EtlError::Usage(format!(
            "{} exists; genomic-migrate writes a new store directory, so remove it or name another",
            options.out.display()
        )));
    }
    let key = CryptoKey::load(&options.key_file).map_err(|e| {
        EtlError::Usage(format!(
            "cannot load the encryption key {}: {e}",
            options.key_file.display()
        ))
    })?;
    let protection = Protection::Encrypted(key.clone());
    let manifest = read_manifest(&options.export, &key)?;
    let layout = Layout::check(&manifest, &options.export)?;
    let patients = read_patients(&options.export, &key, &manifest, &layout)?;
    let pool = thread_pool(options.threads)?;

    let staging = Staging::create(&options.out)?;
    let scratch = staging.path().join(".nodes");
    private_fs::create_dir(&scratch).map_err(EtlError::io("creating", &scratch))?;
    let store_id = StoreId::random()?;
    let codec = GenoBlockCodec::new(protection.clone(), store_id);
    let context = NodeContext {
        export: &options.export,
        key: &key,
        manifest: &manifest,
        layout: &layout,
        codec: &codec,
        scratch: &scratch,
        groups: &patients,
    };
    let migrated: Vec<(BuiltNode, NodeReport)> = pool.install(|| {
        (0..manifest.nodes.len())
            .into_par_iter()
            .map(|i| migrate_node(&context, i))
            .collect::<Result<Vec<_>>>()
    })?;
    let (built, nodes): (Vec<BuiltNode>, Vec<NodeReport>) = migrated.into_iter().unzip();
    let groups: Vec<GenoGroup> = layout
        .groups
        .iter()
        .zip(&patients)
        .map(|(name, ids)| GenoGroup {
            name: name.clone(),
            patients: ids.clone(),
        })
        .collect();
    let (index, sizes) = assemble(
        staging.path(),
        store_header(&protection, store_id),
        manifest.mode,
        groups,
        built,
        layout.columns.clone(),
        &protection,
    )?;
    std::fs::remove_dir(&scratch).map_err(EtlError::io("removing", &scratch))?;
    let specs_in_two_nodes = specs_in_two_nodes(staging.path(), &index, &protection)?;

    let sum = |f: fn(&NodeReport) -> u64| nodes.iter().map(f).sum::<u64>();
    let mut report = GenomicMigrateReport {
        export: options.export.clone(),
        mode: manifest.mode.java_name().to_string(),
        encrypted: true,
        store_id: store_id.to_string(),
        id: store_id,
        groups: layout
            .groups
            .iter()
            .zip(&patients)
            .map(|(name, ids)| GroupReport {
                name: name.clone(),
                patients: ids.len() as u64,
                nodes: manifest.nodes.iter().filter(|n| &n.group == name).count() as u64,
            })
            .collect(),
        patients: patients.iter().map(|p| p.len() as u64).sum(),
        patients_in_two_groups: 0,
        node_count: nodes.len() as u64,
        variants: sum(|n| n.variants),
        mask_records: sum(|n| n.mask_records),
        variants_without_record: sum(|n| n.variants_without_record),
        spec_index_duplicates: sum(|n| n.spec_index_duplicates),
        short_specs: sum(|n| n.short_specs),
        buckets: sum(|n| n.buckets),
        chunks: sum(|n| n.chunks),
        carrier_disagreements: sum(|n| n.carrier_disagreements),
        nodes_without_bucket_index: nodes.iter().filter(|n| !n.bucket_index).count() as u64,
        specs_in_two_nodes,
        compressed_index_port_differences: nodes
            .iter()
            .flat_map(|n| {
                n.compressed_index_port_differences
                    .iter()
                    .map(move |c| format!("{}/{}:{c}", n.group, n.dir))
            })
            .collect(),
        export_bytes: sum(|n| n.export_bytes),
        blocks_bytes: sizes.blocks_bytes,
        index_bytes: sizes.index_bytes,
        nodes,
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
        "genomic store migrated"
    );
    Ok(report)
}

/// The groups and columns of the export, checked against the layout's rules.
struct Layout {
    /// Group names in index order.
    groups: Vec<String>,
    /// Each node's group index, in manifest order.
    node_groups: Vec<u16>,
    /// The column table: every node's columns, ascending in Java order.
    columns: Vec<String>,
}

impl Layout {
    fn check(manifest: &Manifest, export: &Path) -> Result<Self> {
        let refuse = |detail: String| EtlError::Export {
            path: export.join(super::export::MANIFEST_FILE),
            detail,
        };
        if manifest.nodes.is_empty() {
            return Err(refuse("the export holds no node".into()));
        }
        let mut groups: Vec<String> = Vec::new();
        let mut node_groups = Vec::with_capacity(manifest.nodes.len());
        let mut per_group: Vec<usize> = Vec::new();
        for (i, node) in manifest.nodes.iter().enumerate() {
            let (group_ok, dir_ok) = match manifest.mode {
                GenoMode::Local => (node.group.is_empty(), node.dir.is_empty()),
                GenoMode::LocalDistributed => (node.group.is_empty(), !node.dir.is_empty()),
                GenoMode::LocalPatientDistributed => (!node.group.is_empty(), !node.dir.is_empty()),
                GenoMode::LocalPatientOnlyDistributed => {
                    (!node.group.is_empty(), node.dir.is_empty())
                }
            };
            if !group_ok || !dir_ok {
                return Err(refuse(format!(
                    "node {i} ({:?}/{:?}) does not fit the {} layout",
                    node.group,
                    node.dir,
                    manifest.mode.java_name()
                )));
            }
            if let Some(previous) = i.checked_sub(1).map(|p| &manifest.nodes[p]) {
                let order = java_string_cmp(&previous.group, &node.group)
                    .then_with(|| java_string_cmp(&previous.dir, &node.dir));
                if order != std::cmp::Ordering::Less {
                    return Err(refuse(format!(
                        "node {i} ({:?}/{:?}) is out of order or repeated",
                        node.group, node.dir
                    )));
                }
            }
            if groups.last() != Some(&node.group) {
                groups.push(node.group.clone());
                per_group.push(0);
            }
            *per_group.last_mut().unwrap_or(&mut 0) += 1;
            node_groups.push((groups.len() - 1) as u16);
            if node
                .columns
                .windows(2)
                .any(|w| java_string_cmp(&w[0], &w[1]).is_ge())
            {
                return Err(refuse(format!("node {i} lists its columns out of order")));
            }
        }
        let max_nodes = match manifest.mode {
            GenoMode::Local | GenoMode::LocalPatientOnlyDistributed => 1,
            _ => MAX_GENO_NODES_PER_GROUP,
        };
        let max_groups = match manifest.mode {
            GenoMode::Local | GenoMode::LocalDistributed => 1,
            _ => MAX_GENO_GROUPS,
        };
        if groups.len() > max_groups || per_group.iter().any(|&n| n > max_nodes) {
            return Err(refuse(format!(
                "{} groups with up to {} nodes exceed the {} layout's limits",
                groups.len(),
                per_group.iter().max().unwrap_or(&0),
                manifest.mode.java_name()
            )));
        }
        let mut columns: Vec<String> = manifest
            .nodes
            .iter()
            .flat_map(|n| n.columns.iter().cloned())
            .collect();
        columns.sort_by(|a, b| java_string_cmp(a, b));
        columns.dedup();
        if columns.len() > usize::from(u16::MAX) {
            return Err(refuse(format!("{} INFO columns exceed u16", columns.len())));
        }
        Ok(Layout {
            groups,
            node_groups,
            columns,
        })
    }
}

/// Reads every node's patient list and returns each group's: the ids parsed as Java
/// `int`s, checked to be non-negative and unique in the group, the same on every node of a
/// group, and found in one group only.
fn read_patients(
    export: &Path,
    key: &CryptoKey,
    manifest: &Manifest,
    layout: &Layout,
) -> Result<Vec<Vec<u32>>> {
    let mut groups: Vec<Option<Vec<u32>>> = vec![None; layout.groups.len()];
    for (i, node) in manifest.nodes.iter().enumerate() {
        let path = export.join(&node.file);
        let (_, header) = NodeReader::open(&path, key, manifest.export_id)?;
        let refuse = |detail: String| EtlError::Export {
            path: path.clone(),
            detail,
        };
        if header.group != node.group || header.dir != node.dir {
            return Err(refuse(format!(
                "holds node {:?}/{:?} where the manifest names {:?}/{:?}",
                header.group, header.dir, node.group, node.dir
            )));
        }
        let mut ids = Vec::with_capacity(header.patients.len());
        for (p, raw) in header.patients.iter().enumerate() {
            let id = java_parse_int(raw)
                .and_then(|id| u32::try_from(id).ok())
                .ok_or_else(|| {
                    refuse(format!(
                        "patient {p} is not an integer from 0 to 2147483647"
                    ))
                })?;
            ids.push(id);
        }
        let mut sorted = ids.clone();
        sorted.sort_unstable();
        let repeated = sorted.windows(2).filter(|w| w[0] == w[1]).count();
        if repeated > 0 {
            return Err(refuse(format!(
                "{repeated} patient ids appear twice in the node's patient list"
            )));
        }
        let group = usize::from(layout.node_groups[i]);
        match &groups[group] {
            None => groups[group] = Some(ids),
            Some(first) if *first == ids => {}
            Some(_) => {
                return Err(refuse(format!(
                    "node {:?}/{:?} has another patient list than the first node of its group; every chromosome node of a group must share one",
                    node.group, node.dir
                )));
            }
        }
    }
    let groups: Vec<Vec<u32>> = groups.into_iter().map(Option::unwrap_or_default).collect();
    let mut seen: HashMap<u32, usize> = HashMap::new();
    let mut in_two = HashSet::new();
    for (g, ids) in groups.iter().enumerate() {
        for &id in ids {
            if let Some(&other) = seen.get(&id) {
                if other != g {
                    in_two.insert(id);
                }
            } else {
                seen.insert(id, g);
            }
        }
    }
    if !in_two.is_empty() {
        return Err(EtlError::Export {
            path: export.to_path_buf(),
            detail: format!(
                "{} patient ids appear in more than one group; each patient must belong to one group",
                in_two.len()
            ),
        });
    }
    Ok(groups)
}

/// What every node task shares.
struct NodeContext<'a> {
    export: &'a Path,
    key: &'a CryptoKey,
    manifest: &'a Manifest,
    layout: &'a Layout,
    codec: &'a GenoBlockCodec,
    scratch: &'a Path,
    groups: &'a [Vec<u32>],
}

/// One variant of a bucket while it is merged from the export: its data and the spec index
/// ids naming it.
struct Merged {
    variant: BucketVariant,
    spec_ids: Vec<u32>,
    metadata_seen: bool,
}

/// Migrates node `i` of the manifest into a temporary blocks file.
fn migrate_node(context: &NodeContext<'_>, i: usize) -> Result<(BuiltNode, NodeReport)> {
    let entry = &context.manifest.nodes[i];
    let path = context.export.join(&entry.file);
    let refuse = |detail: String| EtlError::Export {
        path: path.clone(),
        detail,
    };
    let export_bytes = std::fs::metadata(&path)
        .map_err(EtlError::io("reading", &path))?
        .len();
    let (mut reader, header) = NodeReader::open(&path, context.key, context.manifest.export_id)?;
    let group = context.layout.node_groups[i];
    let patient_count = context.groups[usize::from(group)].len() as u32;
    let infos = reader.infos(header.spec_count)?;
    let names: Vec<&str> = infos.iter().map(|info| info.column.as_str()).collect();
    if names != entry.columns.iter().map(String::as_str).collect::<Vec<_>>() {
        return Err(refuse(
            "its info columns differ from the manifest's list".into(),
        ));
    }
    let node_index = i as u16;
    let mut builder = NodeBuilder::new(
        node_index,
        group,
        entry.dir.clone(),
        patient_count,
        context.codec.clone(),
        context.scratch.join(format!("{i:05}.blocks")),
    )?;
    let infos = info_inputs(infos, &context.layout.columns).map_err(&refuse)?;
    let info_columns = infos.len() as u64;
    let mut info_builder =
        InfoColumnsBuilder::new(infos, header.spec_count).map_err(|e| refuse(e.to_string()))?;
    let mut report = NodeReport {
        group: entry.group.clone(),
        dir: entry.dir.clone(),
        spec_index_entries: u64::from(header.spec_count),
        bucket_index: header.bucket_index,
        info_columns,
        export_bytes,
        ..NodeReport::default()
    };
    let mut derived = PosBitset::new(patient_count);
    let mut metadata_entries = 0u64;
    let mut contigs = 0u64;
    let mut last_contig: Option<String> = None;
    while let Some(bucket) = reader.next_bucket(patient_count)? {
        if last_contig.as_deref() != Some(bucket.contig.as_str()) {
            contigs += 1;
            last_contig = Some(bucket.contig.clone());
        }
        let ExportedBucket {
            contig,
            bucket,
            masks,
            metadata,
            specs,
            carriers,
        } = bucket;
        let location = format!("contig {contig} bucket {bucket}");
        let mut by_spec: HashMap<String, usize> = HashMap::new();
        let mut merged: Vec<Merged> = Vec::new();
        let mut slot = |spec: String, merged: &mut Vec<Merged>| -> usize {
            *by_spec.entry(spec.clone()).or_insert_with(|| {
                merged.push(Merged {
                    variant: BucketVariant {
                        spec,
                        record: None,
                        metadata: Vec::new(),
                    },
                    spec_ids: Vec::new(),
                    metadata_seen: false,
                });
                merged.len() - 1
            })
        };
        for record in masks {
            let s = slot(record.spec, &mut merged);
            if merged[s].variant.record.is_some() {
                return Err(refuse(format!("{location}: two mask records for one spec")));
            }
            merged[s].variant.record = Some(record.masks.map(|mask| {
                mask.map(|m| VariantMask {
                    bitmask_form: m.bitmask_form,
                    positions: m.positions,
                })
            }));
        }
        for (spec, strings) in metadata {
            metadata_entries += 1;
            let s = slot(spec, &mut merged);
            if merged[s].metadata_seen {
                return Err(refuse(format!(
                    "{location}: two metadata entries for one spec"
                )));
            }
            merged[s].metadata_seen = true;
            merged[s].variant.metadata = strings.unwrap_or_default();
        }
        for (id, spec) in specs {
            let s = slot(spec, &mut merged);
            merged[s].spec_ids.push(id);
        }
        for m in &merged {
            let key = VariantSpecKey::parse(&m.variant.spec).ok_or_else(|| {
                refuse(format!(
                    "{location}: spec {:?} has no contig and Java int position",
                    m.variant.spec
                ))
            })?;
            if key.contig() != contig || key.bucket() != bucket {
                return Err(refuse(format!(
                    "{location}: spec {:?} belongs to contig {} bucket {}",
                    m.variant.spec,
                    key.contig(),
                    key.bucket()
                )));
            }
        }
        merged.sort_by(|a, b| {
            compare_specs(&a.variant.spec, &b.variant.spec).unwrap_or(std::cmp::Ordering::Equal)
        });
        derived.clear();
        let first_id = builder.next_variant_id();
        for (offset, m) in merged.iter().enumerate() {
            let id = first_id + offset as u32;
            for &spec_id in &m.spec_ids {
                info_builder
                    .assign(spec_id, id)
                    .map_err(|detail| refuse(format!("{location}: spec index {detail}")))?;
            }
            report.spec_index_duplicates += m.spec_ids.len().saturating_sub(1) as u64;
            match &m.variant.record {
                Some(record) => {
                    report.mask_records += 1;
                    for kind in [0, 1, 2] {
                        if let Some(mask) = &record[kind] {
                            for &p in &mask.positions {
                                derived.insert(p);
                            }
                        }
                    }
                    let he = record[0].as_ref().map_or(&[][..], |m| &m.positions);
                    let ho = record[1].as_ref().map_or(&[][..], |m| &m.positions);
                    for &spec_id in &m.spec_ids {
                        info_builder.add_carriers(spec_id, he, ho);
                    }
                }
                None => {
                    report.variants_without_record += 1;
                    if m.spec_ids.is_empty() {
                        report.metadata_only_variants += 1;
                    }
                }
            }
        }
        let derived_positions: Vec<u32> = derived.iter().collect();
        let stored = match carriers {
            ExportedCarriers::Present(positions) => {
                if positions != derived_positions {
                    report.carrier_disagreements += 1;
                }
                positions
            }
            ExportedCarriers::NotIndexed => {
                if !derived_positions.is_empty() {
                    report.carrier_disagreements += 1;
                }
                Vec::new()
            }
            ExportedCarriers::NoIndex => derived_positions,
        };
        report.buckets += 1;
        builder.add_bucket(
            &contig,
            bucket,
            merged.into_iter().map(|m| m.variant).collect(),
            stored,
        )?;
    }
    let trailer = reader.trailer().unwrap_or_default();
    if trailer.buckets != report.buckets
        || trailer.mask_records != report.mask_records
        || trailer.metadata_entries != metadata_entries
    {
        return Err(refuse(format!(
            "the end record counts {} buckets, {} mask records and {} metadata entries, {}, {} and {} were read",
            trailer.buckets,
            trailer.mask_records,
            trailer.metadata_entries,
            report.buckets,
            report.mask_records,
            metadata_entries
        )));
    }
    report.patients_missing_from_bucket_index = trailer.missing_from_bucket_index;
    builder.finish_chunks()?;
    if let Some(id) = info_builder.unassigned() {
        return Err(refuse(format!(
            "spec index entry {id} was placed in no bucket"
        )));
    }
    let variant_count = builder.variant_count();
    report.compressed_index_port_differences = info_builder
        .finish(&mut builder, patient_count)
        .map_err(|e| match e {
            EtlError::Usage(detail) => refuse(detail),
            other => other,
        })?;
    report.variants = u64::from(variant_count);
    report.short_specs = builder.short_spec_count() as u64;
    report.chunks = builder.chunk_count() as u64;
    report.contigs = contigs;
    Ok((builder.finish()?, report))
}

/// The builder's form of a node's info stores: each column's index in the column table,
/// and for a continuous column its `CompressedIndex` with key strings mapped to key indexes.
///
/// # Errors
/// A message naming the column when it is not in the column table or its `CompressedIndex`
/// names a key the column does not hold.
fn info_inputs(
    infos: Vec<ExportedInfo>,
    columns: &[String],
) -> std::result::Result<Vec<InfoColumnInput>, String> {
    let mut inputs = Vec::with_capacity(infos.len());
    for info in infos {
        let column = columns
            .binary_search_by(|name| java_string_cmp(name, &info.column))
            .map_err(|_| format!("column {:?} is not in the column table", info.column))?
            as u16;
        let ranges = match &info.ranges {
            None => None,
            Some(exported) => {
                let mut ranges = Vec::with_capacity(exported.len());
                for range in exported {
                    let mut entries = Vec::with_capacity(range.entries.len());
                    for (value, keys) in &range.entries {
                        let mut indexes = Vec::with_capacity(keys.len());
                        for key in keys {
                            let index = info
                                .keys
                                .binary_search_by(|(k, _)| java_string_cmp(k, key))
                                .map_err(|_| {
                                    format!(
                                        "column {:?}: the CompressedIndex names key {key:?}, which the column does not hold",
                                        info.column
                                    )
                                })?;
                            indexes.push(index as u32);
                        }
                        indexes.sort_unstable();
                        entries.push((*value, indexes));
                    }
                    ranges.push(CompressedRange {
                        lower: range.lower,
                        upper: range.upper,
                        entries,
                    });
                }
                Some(ranges)
            }
        };
        inputs.push(InfoColumnInput {
            column,
            name: info.column,
            description: info.description,
            keys: info.keys,
            ranges,
        });
    }
    Ok(inputs)
}

/// Counts the specs held by two or more chromosome nodes of one group, reading the mask
/// chunks of the contigs that two nodes of a group share (none in the usual layout of one
/// contig per node).
fn specs_in_two_nodes(dir: &Path, index: &GenoIndex, protection: &Protection) -> Result<u64> {
    let mut by_contig: BTreeMap<(u16, &str), Vec<u16>> = BTreeMap::new();
    for (n, node) in index.nodes().iter().enumerate() {
        for contig in &node.contigs {
            by_contig
                .entry((node.group, contig.as_str()))
                .or_default()
                .push(n as u16);
        }
    }
    let shared: Vec<(&str, Vec<u16>)> = by_contig
        .into_iter()
        .filter(|(_, nodes)| nodes.len() > 1)
        .map(|((_, contig), nodes)| (contig, nodes))
        .collect();
    if shared.is_empty() {
        return Ok(0);
    }
    let codec = index.block_codec(protection.clone())?;
    let reader = GenoBlocksReader::open(dir.join(GENO_BLOCKS_FILE_NAME), index)?;
    let mut count = 0u64;
    for (contig, nodes) in shared {
        let mut holders: HashMap<String, u32> = HashMap::new();
        for n in nodes {
            let node = &index.nodes()[usize::from(n)];
            let Some(contig_index) = node.contig_index(contig) else {
                continue;
            };
            for (c, chunk) in node.chunks.iter().enumerate() {
                if chunk.contig != contig_index {
                    continue;
                }
                let decoded = reader.read_mask_chunk(&codec, index, n, c as u32)?;
                for v in 0..decoded.len() {
                    *holders.entry(decoded.spec(v).to_string()).or_default() += 1;
                }
            }
        }
        count += holders.values().filter(|&&n| n > 1).count() as u64;
    }
    Ok(count)
}
