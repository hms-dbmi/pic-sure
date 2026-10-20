//! Builds genomic stores in-process: from VCFs and index files with `hpds-etl genomic-load`,
//! or block by block with the genomic writer for shapes the loader refuses.

use std::path::{Path, PathBuf};

use hpds_etl::genomic::vcf_load::{GenomicLoadOptions, genomic_load};
use hpds_etl::genomic::writer::{
    BucketVariant, InfoColumnInput, InfoColumnsBuilder, NodeBuilder, VariantMask, assemble,
    store_header,
};
use hpds_format::{GenoGroup, GenoMode, Protection, StoreId};

/// One VCF record: contig, position, ref, alt, INFO text, and the genotype of each listed
/// patient; every other sample of the VCF is `0|0`.
pub struct Rec<'a> {
    pub contig: &'a str,
    pub pos: u32,
    pub reference: &'a str,
    pub alt: &'a str,
    pub info: String,
    pub genotypes: Vec<(u32, &'a str)>,
}

/// A record with `Gene_with_variant`, `Variant_consequence_calculated` and `AF` set.
pub fn rec<'a>(
    contig: &'a str,
    pos: u32,
    alt: &'a str,
    gene: &str,
    consequence: &str,
    af: &str,
    genotypes: &[(u32, &'a str)],
) -> Rec<'a> {
    Rec {
        contig,
        pos,
        reference: "A",
        alt,
        info: format!(
            "Gene_with_variant={gene};Variant_consequence_calculated={consequence};AF={af}"
        ),
        genotypes: genotypes.to_vec(),
    }
}

/// One node: the VCF of `chrom` with a sample per patient of `patients`.
pub struct NodeVcf<'a> {
    pub chrom: &'a str,
    pub patients: Vec<u32>,
    pub records: Vec<Rec<'a>>,
}

/// One group of the store and its nodes; the name is empty in `localDistributed`.
pub struct GroupVcfs<'a> {
    pub name: &'a str,
    pub nodes: Vec<NodeVcf<'a>>,
}

const VCF_HEADER: &str = "##fileformat=VCFv4.3\n\
##INFO=<ID=Gene_with_variant,Number=.,Type=String,Description=\"The gene.\">\n\
##INFO=<ID=Variant_consequence_calculated,Number=.,Type=String,Description=\"The consequence.\">\n\
##INFO=<ID=AF,Number=1,Type=Float,Description=\"Frequency.\">\n";

fn vcf_text(node: &NodeVcf<'_>) -> String {
    let mut text = String::from(VCF_HEADER);
    text.push_str("#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT");
    for p in &node.patients {
        text.push_str(&format!("\tS{p}"));
    }
    text.push('\n');
    for r in &node.records {
        text.push_str(&format!(
            "{}\t{}\t.\t{}\t{}\t.\tPASS\t{}\tGT",
            r.contig, r.pos, r.reference, r.alt, r.info
        ));
        for p in &node.patients {
            let gt = r
                .genotypes
                .iter()
                .find(|(q, _)| q == p)
                .map_or("0|0", |(_, gt)| *gt);
            text.push('\t');
            text.push_str(gt);
        }
        text.push('\n');
    }
    text
}

/// Loads `groups` with `genomic-load` in `mode` into the new store `out`, sealed with the key
/// in `key_file`; the VCFs and index files go into `work`.
pub fn load_genomic(
    work: &Path,
    key_file: &Path,
    mode: GenoMode,
    groups: &[GroupVcfs<'_>],
    out: &Path,
) {
    std::fs::create_dir_all(work).unwrap();
    let mut indexed = Vec::new();
    for (g, group) in groups.iter().enumerate() {
        let mut index =
            String::from("vcf_path\tchromosome\tisAnnotated\tisGzipped\tsample_ids\tpatient_ids\n");
        for (n, node) in group.nodes.iter().enumerate() {
            let path = work.join(format!("g{g}-n{n}.vcf"));
            std::fs::write(&path, vcf_text(node)).unwrap();
            let samples: Vec<String> = node.patients.iter().map(|p| format!("S{p}")).collect();
            let patients: Vec<String> = node.patients.iter().map(u32::to_string).collect();
            index.push_str(&format!(
                "{}\t{}\t1\t0\t\"{}\"\t\"{}\"\n",
                path.display(),
                node.chrom,
                samples.join(","),
                patients.join(",")
            ));
        }
        let index_path = work.join(format!("g{g}-index.tsv"));
        std::fs::write(&index_path, index).unwrap();
        indexed.push((group.name.to_owned(), index_path));
    }
    genomic_load(&GenomicLoadOptions {
        mode,
        groups: indexed,
        out: out.to_path_buf(),
        key_file: key_file.to_path_buf(),
        skip_missing_samples: false,
        threads: Some(2),
    })
    .unwrap();
}

/// One variant of a hand-built node: its spec, its `he` and `ho` positions (bitmask form when
/// more than 5), and the categorical `Gene_with_variant` key it holds.
pub struct RawVariant<'a> {
    pub spec: &'a str,
    pub he: Vec<u32>,
    pub ho: Vec<u32>,
    pub gene: &'a str,
}

fn mask(positions: &[u32]) -> Option<VariantMask> {
    (!positions.is_empty()).then(|| VariantMask {
        bitmask_form: positions.len() > 5,
        positions: positions.to_vec(),
    })
}

/// Writes a one-node `localDistributed` store of `patients` (in that order) into `out` with
/// the genomic writer, sealed under `protection`: `variants` of contig `contig`, in the
/// store's spec order and bucket by bucket, with one categorical column,
/// `Gene_with_variant`. Specs Java cannot parse (short specs) are kept, as `genomic-migrate`
/// keeps them.
pub fn write_raw_store(
    out: &Path,
    protection: &Protection,
    patients: &[u32],
    contig: &str,
    variants: &[RawVariant<'_>],
) -> PathBuf {
    std::fs::create_dir_all(out).unwrap();
    let store_id = StoreId::random().unwrap();
    let codec = hpds_format::GenoBlockCodec::new(protection.clone(), store_id);
    let patient_count = patients.len() as u32;
    let mut builder = NodeBuilder::new(
        0,
        0,
        contig.to_owned(),
        patient_count,
        codec,
        out.join("node.tmp"),
    )
    .unwrap();
    let mut genes: Vec<(String, Vec<u32>)> = Vec::new();
    for (source, v) in variants.iter().enumerate() {
        match genes.iter_mut().find(|(g, _)| g == v.gene) {
            Some((_, ids)) => ids.push(source as u32),
            None => genes.push((v.gene.to_owned(), vec![source as u32])),
        }
    }
    genes.sort_by(|a, b| hpds_format::java_string_cmp(&a.0, &b.0));
    let mut info = InfoColumnsBuilder::new(
        vec![InfoColumnInput {
            column: 0,
            name: "Gene_with_variant".into(),
            description: "Description=\"The gene.\"".into(),
            keys: genes,
            ranges: None,
        }],
        variants.len() as u32,
    )
    .unwrap();
    let bucket_of =
        |spec: &str| -> i32 { spec.split(',').nth(1).unwrap().parse::<i32>().unwrap() / 1000 };
    let mut i = 0;
    while i < variants.len() {
        let bucket = bucket_of(variants[i].spec);
        let mut in_bucket = Vec::new();
        let mut carriers = std::collections::BTreeSet::new();
        while i < variants.len() && bucket_of(variants[i].spec) == bucket {
            let v = &variants[i];
            info.assign(i as u32, builder.next_variant_id() + in_bucket.len() as u32)
                .unwrap();
            info.add_carriers(i as u32, &v.he, &v.ho);
            carriers.extend(v.he.iter().chain(&v.ho).copied());
            in_bucket.push(BucketVariant {
                spec: v.spec.to_owned(),
                record: Some([mask(&v.he), mask(&v.ho), None, None]),
                metadata: vec![format!("Gene_with_variant={}", v.gene)],
            });
            i += 1;
        }
        builder
            .add_bucket(contig, bucket, in_bucket, carriers.into_iter().collect())
            .unwrap();
    }
    builder.finish_chunks().unwrap();
    info.finish(&mut builder, patient_count).unwrap();
    let built = builder.finish().unwrap();
    assemble(
        out,
        store_header(protection, store_id),
        GenoMode::LocalDistributed,
        vec![GenoGroup {
            name: String::new(),
            patients: patients.to_vec(),
        }],
        vec![built],
        vec!["Gene_with_variant".into()],
        protection,
    )
    .unwrap();
    out.to_path_buf()
}
