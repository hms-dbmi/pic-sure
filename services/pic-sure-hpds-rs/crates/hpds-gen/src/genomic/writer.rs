//! Writes a random dataset: one BGZF VCF per group and chromosome, one index file and one
//! phenotype CSV per group, and a `groups.tsv` summary.

use std::fs::{self, File};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, PoisonError};
use std::time::Instant;

use super::bgzf::BgzfWriter;
use super::cohort::{Cohort, Group, write_phenotypes};
use super::sites::{SiteStream, chromosome_target};
use super::vcf::{Gt, RowBuilder, group_view, write_header};
use super::{CHROMOSOMES, Shape};
use crate::rng::{Source, mix};

const WRITE_BUFFER_BYTES: usize = 4 * 1024 * 1024;
const DEFLATE_LEVEL: u32 = 3;

/// What a write produced.
#[derive(Clone, Debug, Default)]
pub struct Output {
    /// Files written, indexes and phenotype CSVs included.
    pub files: usize,
    /// VCF records written, over all files.
    pub records: u64,
    /// Bytes of VCF text before compression.
    pub text_bytes: u64,
    /// Bytes of VCF files on disk.
    pub vcf_bytes: u64,
    /// Seconds spent.
    pub seconds: f64,
}

/// Counts of one VCF.
#[derive(Clone, Copy, Debug, Default)]
pub struct FileStats {
    /// Records written.
    pub records: u64,
    /// Uncompressed bytes of the whole file.
    pub text_bytes: u64,
    /// Compressed bytes of the whole file.
    pub vcf_bytes: u64,
}

struct CountingWriter<W: Write> {
    inner: W,
    bytes: u64,
}

impl<W: Write> Write for CountingWriter<W> {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.bytes += buf.len() as u64;
        self.inner.write(buf)
    }

    fn flush(&mut self) -> io::Result<()> {
        self.inner.flush()
    }
}

/// The per-site random source for one group: the same site draws the same carriers however
/// the files are scheduled.
pub fn group_site_rng(seed: u64, chromosome: usize, genotype_key: u64, group: usize) -> Source {
    Source::new(mix(seed
        ^ mix(0x6E07_0000_0000_0000
            ^ ((chromosome as u64) << 40)
            ^ genotype_key)
        ^ mix(0xC0DE_0000_0000_0000 ^ group as u64)))
}

/// Writes the VCF of `group` (number `group_number`) for `chromosome` to `out`.
///
/// Only the sites the group lists are written, each with at least one carrier, except the sites
/// nobody carries (a zero carrier fraction).
///
/// # Errors
/// Returns the writer's error.
pub fn write_vcf_stream(
    seed: u64,
    variants: u64,
    group: &Group,
    group_number: usize,
    chromosome: usize,
    out: &mut impl Write,
) -> io::Result<u64> {
    let name = CHROMOSOMES[chromosome];
    write_header(out, name, &group.header_samples, &[])?;
    let samples = group.header_samples.len();
    let mut builder = RowBuilder::new(samples);
    let mut calls: Vec<(u32, Gt)> = Vec::new();
    let mut info = Vec::with_capacity(256);
    let mut cached_key = u64::MAX;
    let mut present = false;
    let mut records = 0;
    let target = chromosome_target(chromosome, variants);
    for site in SiteStream::new(seed, chromosome, target) {
        if site.genotype_key != cached_key {
            let mut rng = group_site_rng(seed, chromosome, site.genotype_key, group_number);
            present = group_view(&mut rng, samples, site.carrier_fraction, &mut calls);
            cached_key = site.genotype_key;
        }
        if !present {
            continue;
        }
        info.clear();
        site.write_info(&mut info);
        let row = builder.build(
            name,
            site.position,
            &site.reference,
            &site.alternate,
            &info,
            &calls,
        );
        out.write_all(row)?;
        records += 1;
    }
    Ok(records)
}

fn write_vcf_file(
    seed: u64,
    shape: &Shape,
    group: &Group,
    group_number: usize,
    chromosome: usize,
    path: &Path,
) -> io::Result<FileStats> {
    let file = BufWriter::with_capacity(WRITE_BUFFER_BYTES, File::create(path)?);
    let mut counted = CountingWriter {
        inner: BgzfWriter::new(
            CountingWriter {
                inner: file,
                bytes: 0,
            },
            DEFLATE_LEVEL,
        ),
        bytes: 0,
    };
    let records = write_vcf_stream(
        seed,
        shape.variants,
        group,
        group_number,
        chromosome,
        &mut counted,
    )?;
    let text_bytes = counted.bytes;
    let compressed = counted.inner.finish()?;
    Ok(FileStats {
        records,
        text_bytes,
        vcf_bytes: compressed.bytes,
    })
}

/// The index chromosome label of a chromosome name: `chr1` is `1`.
pub fn index_label(name: &str) -> &str {
    name.strip_prefix("chr").unwrap_or(name)
}

/// The file name of a group's VCF for a chromosome.
pub fn vcf_file_name(group: &str, chromosome: &str, gzipped: bool) -> String {
    format!(
        "{group}.{chromosome}.vcf{}",
        if gzipped { ".gz" } else { "" }
    )
}

/// Writes `groups.tsv`, one line per group: name, samples, first and last patient id.
///
/// # Errors
/// Returns the writer's error.
pub fn write_groups_summary(
    cohort: &Cohort,
    dir: &Path,
    vcfs_per_group: &[usize],
) -> io::Result<()> {
    let mut out = BufWriter::new(File::create(dir.join("groups.tsv"))?);
    writeln!(
        out,
        "group\tsamples\tindexed\tfirst_patient\tlast_patient\tvcfs"
    )?;
    for (group, vcfs) in cohort.groups.iter().zip(vcfs_per_group) {
        let patients = group.indexed.iter().map(|s| s.patient);
        writeln!(
            out,
            "{}\t{}\t{}\t{}\t{}\t{}",
            group.name,
            group.header_samples.len(),
            group.indexed.len(),
            patients.clone().min().unwrap_or(0),
            patients.max().unwrap_or(0),
            vcfs
        )?;
    }
    out.flush()
}

/// Writes the index file and the phenotype CSV of every group into `dir/index` and `dir/pheno`.
///
/// # Errors
/// Returns the writer's error.
pub fn write_group_files(
    cohort: &Cohort,
    seed: u64,
    dir: &Path,
    chromosomes: &[usize],
    gzipped: bool,
) -> io::Result<()> {
    fs::create_dir_all(dir.join("index"))?;
    fs::create_dir_all(dir.join("pheno"))?;
    for group in &cohort.groups {
        let mut index = BufWriter::new(File::create(
            dir.join("index")
                .join(format!("{}.vcfIndex.tsv", group.name)),
        )?);
        writeln!(index, "{}", super::cohort::INDEX_HEADER)?;
        for chromosome in chromosomes {
            let name = CHROMOSOMES[*chromosome];
            let path = format!("vcf/{}", vcf_file_name(&group.name, name, gzipped));
            group.write_index_line(&mut index, &path, index_label(name), gzipped)?;
        }
        index.flush()?;
        let mut pheno = BufWriter::new(File::create(
            dir.join("pheno")
                .join(format!("{}-allConcepts.csv", group.name)),
        )?);
        write_phenotypes(group, seed, &mut pheno)?;
        pheno.flush()?;
    }
    Ok(())
}

/// Writes a random dataset under `dir` (`vcf/`, `index/`, `pheno/` and `groups.tsv`), using up
/// to `threads` worker threads, reporting each finished file on standard error when `progress`
/// is set. The bytes written depend on `seed` and `shape` alone.
///
/// # Errors
/// Returns the first I/O error of any worker.
pub fn write_dataset(
    shape: &Shape,
    seed: u64,
    dir: &Path,
    threads: usize,
    progress: bool,
) -> io::Result<Output> {
    let started = Instant::now();
    let cohort = Cohort::random(shape, seed);
    fs::create_dir_all(dir.join("vcf"))?;
    let chromosomes: Vec<usize> = (0..CHROMOSOMES.len()).collect();
    let mut tasks: Vec<(usize, usize)> = (0..cohort.groups.len())
        .flat_map(|g| chromosomes.iter().map(move |c| (g, *c)))
        .collect();
    let cost = |&(g, c): &(usize, usize)| {
        chromosome_target(c, shape.variants) * u64::from(shape.group_samples[g])
    };
    tasks.sort_by_key(|t| std::cmp::Reverse(cost(t)));
    let next = AtomicUsize::new(0);
    let totals = Mutex::new(Output::default());
    let failure: Mutex<Option<io::Error>> = Mutex::new(None);
    let paths: Vec<PathBuf> = tasks
        .iter()
        .map(|(g, c)| {
            dir.join("vcf").join(vcf_file_name(
                &cohort.groups[*g].name,
                CHROMOSOMES[*c],
                true,
            ))
        })
        .collect();
    std::thread::scope(|scope| {
        for _ in 0..threads.max(1) {
            scope.spawn(|| {
                loop {
                    let i = next.fetch_add(1, Ordering::Relaxed);
                    if i >= tasks.len()
                        || failure
                            .lock()
                            .unwrap_or_else(PoisonError::into_inner)
                            .is_some()
                    {
                        return;
                    }
                    let (g, c) = tasks[i];
                    match write_vcf_file(seed, shape, &cohort.groups[g], g, c, &paths[i]) {
                        Ok(stats) => {
                            let mut totals = totals.lock().unwrap_or_else(PoisonError::into_inner);
                            totals.files += 1;
                            totals.records += stats.records;
                            totals.text_bytes += stats.text_bytes;
                            totals.vcf_bytes += stats.vcf_bytes;
                            if progress {
                                eprintln!(
                                    "{} {} records={} gz={} bytes ({}/{})",
                                    cohort.groups[g].name,
                                    CHROMOSOMES[c],
                                    stats.records,
                                    stats.vcf_bytes,
                                    totals.files,
                                    tasks.len()
                                );
                            }
                        }
                        Err(e) => {
                            *failure.lock().unwrap_or_else(PoisonError::into_inner) = Some(e);
                            return;
                        }
                    }
                }
            });
        }
    });
    if let Some(e) = failure.into_inner().unwrap_or_else(PoisonError::into_inner) {
        return Err(e);
    }
    write_group_files(&cohort, seed, dir, &chromosomes, true)?;
    write_groups_summary(&cohort, dir, &vec![chromosomes.len(); cohort.groups.len()])?;
    let mut out = totals.into_inner().unwrap_or_else(PoisonError::into_inner);
    out.files += cohort.groups.len() * 2 + 1;
    out.seconds = started.elapsed().as_secs_f64();
    Ok(out)
}
