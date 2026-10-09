//! The partition pass: stream every input once, normalize each row's concept path, read its
//! value, and append it to one of N spill files chosen by concept.

use std::borrow::Cow;
use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;

use hpds_format::{NO_TIMESTAMP, java_string_cmp};
use rustc_hash::FxHashMap;

use super::rules::{
    ConfigMap, Mode, Timestamp, apply_dataset_root, classify_value, parse_patient, parse_timestamp,
};
use super::spill::{OutRow, write_row};
use crate::error::{EtlError, Result};
use crate::java::{normalize_concept_path, roll_up, rollup_text};

/// One input file and the name its CSV config is looked up under.
#[derive(Clone, Debug)]
pub(crate) struct InputFile {
    pub path: PathBuf,
    pub config_key: String,
}

/// What the partition pass hands to the build pass.
pub(crate) struct Partitioned {
    /// Concept id to concept path.
    pub concepts: Vec<String>,
    /// Raw concept path id to its rank in Java `String.compareTo` order; equal strings share
    /// a rank. Empty in new-search mode.
    pub raw_path_ranks: Vec<u32>,
    /// For each input, the `seq` of its first data record and its path.
    pub file_starts: Vec<(u64, PathBuf)>,
    /// The spill files, one per partition.
    pub spill_files: Vec<PathBuf>,
    /// Data records read, header rows excluded.
    pub rows_read: u64,
    /// Records with fewer than four fields, skipped.
    pub short_rows: u64,
}

/// How a sequential-mode file is cut into chunks, as `LowRAMCSVProcessor` does: a file of
/// `len` bytes gets `max(1, ceil(len / max_chunk_bytes))` chunks, and when there is more
/// than one, every chunk but the last holds `total_lines / chunks + 1` records, where
/// `total_lines` counts lines as `Files.lines` does.
struct Chunking {
    records_per_chunk: Option<u64>,
}

impl Chunking {
    fn for_file(path: &Path, max_chunk_bytes: f64) -> Result<Self> {
        let len = std::fs::metadata(path)
            .map_err(EtlError::io("reading metadata of", path))?
            .len();
        let chunks = ((len as f64 / max_chunk_bytes).ceil() as u64).max(1);
        if chunks == 1 {
            return Ok(Chunking {
                records_per_chunk: None,
            });
        }
        let lines = count_java_lines(path)?;
        Ok(Chunking {
            records_per_chunk: Some(lines / chunks + 1),
        })
    }

    fn chunk_of(&self, record_index: u64) -> u64 {
        self.records_per_chunk.map_or(0, |per| record_index / per)
    }
}

/// Counts lines the way `Files.lines(path).count()` does: `\n`, `\r` and `\r\n` each end
/// a line, and a final line without a terminator counts.
fn count_java_lines(path: &Path) -> Result<u64> {
    let mut file = File::open(path).map_err(EtlError::io("opening", path))?;
    let mut buf = vec![0u8; 1 << 20];
    let mut lines = 0u64;
    let mut pending = false;
    let mut after_cr = false;
    loop {
        let n = file.read(&mut buf).map_err(EtlError::io("reading", path))?;
        if n == 0 {
            break;
        }
        for &b in &buf[..n] {
            match b {
                b'\n' if after_cr => after_cr = false,
                b'\n' | b'\r' => {
                    lines += 1;
                    pending = false;
                    after_cr = b == b'\r';
                }
                _ => {
                    pending = true;
                    after_cr = false;
                }
            }
        }
    }
    Ok(lines + u64::from(pending))
}

struct RawPath {
    decoded: String,
    normalized: String,
}

struct Interner {
    raw_ids: FxHashMap<Box<[u8]>, u32>,
    raw_paths: Vec<RawPath>,
    concept_ids: FxHashMap<String, u32>,
    concepts: Vec<String>,
}

impl Interner {
    fn raw_path(&mut self, bytes: &[u8]) -> u32 {
        if let Some(&id) = self.raw_ids.get(bytes) {
            return id;
        }
        let decoded = String::from_utf8_lossy(bytes).into_owned();
        let normalized = normalize_concept_path(&decoded);
        let id = self.raw_paths.len() as u32;
        self.raw_paths.push(RawPath {
            decoded,
            normalized,
        });
        self.raw_ids.insert(bytes.into(), id);
        id
    }

    fn concept(&mut self, path: String) -> u32 {
        if let Some(&id) = self.concept_ids.get(&path) {
            return id;
        }
        let id = self.concepts.len() as u32;
        self.concepts.push(path.clone());
        self.concept_ids.insert(path, id);
        id
    }
}

fn partition_of(concept: u32, partitions: usize) -> usize {
    let mixed = u64::from(concept).wrapping_mul(0x9E37_79B9_7F4A_7C15) >> 32;
    (mixed % partitions as u64) as usize
}

fn field(record: &csv::ByteRecord, index: usize) -> Cow<'_, str> {
    String::from_utf8_lossy(record.get(index).unwrap_or_default())
}

/// Settings for [`partition`].
pub(crate) struct PartitionSettings<'a> {
    pub mode: Mode,
    pub rollup: bool,
    pub configs: &'a ConfigMap,
    pub spill_dir: &'a Path,
    pub partitions: usize,
    pub max_chunk_bytes: f64,
}

/// Streams `inputs` in order into `settings.partitions` spill files under
/// `settings.spill_dir`.
///
/// # Errors
/// I/O and CSV errors, and in sequential mode [`EtlError::InvalidPatient`] for the first
/// record (in file order) whose patient number is not a non-negative Java `int`, because
/// `LowRAMCSVProcessor` parses the patient of every record with at least four fields.
pub(crate) fn partition(
    inputs: &[InputFile],
    settings: &PartitionSettings<'_>,
) -> Result<Partitioned> {
    let ordered = settings.mode == Mode::Sequential;
    let mut spill_files = Vec::with_capacity(settings.partitions);
    let mut writers = Vec::with_capacity(settings.partitions);
    for i in 0..settings.partitions {
        let path = settings.spill_dir.join(format!("rows-{i:05}.bin"));
        let file = File::create(&path).map_err(EtlError::io("creating", &path))?;
        writers.push(BufWriter::with_capacity(256 * 1024, file));
        spill_files.push(path);
    }
    let mut interner = Interner {
        raw_ids: FxHashMap::default(),
        raw_paths: Vec::new(),
        concept_ids: FxHashMap::default(),
        concepts: Vec::new(),
    };
    let mut file_starts = Vec::with_capacity(inputs.len());
    let mut seq = 0u64;
    let mut short_rows = 0u64;
    let mut chunk_base = 0u64;
    let started = Instant::now();
    let mut record = csv::ByteRecord::new();

    for input in inputs {
        let path = &input.path;
        tracing::info!(file = %path.display(), "reading");
        file_starts.push((seq, path.clone()));
        let config = settings.configs.get(&input.config_key);
        let chunking = if ordered {
            Chunking::for_file(path, settings.max_chunk_bytes)?
        } else {
            Chunking {
                records_per_chunk: None,
            }
        };
        let file = File::open(path).map_err(EtlError::io("opening", path))?;
        let mut reader = csv::ReaderBuilder::new()
            .has_headers(true)
            .flexible(true)
            .from_reader(BufReader::with_capacity(1 << 20, file));
        let csv_error = |source| EtlError::Csv {
            path: path.clone(),
            source,
        };
        let mut concept_cache: FxHashMap<(u32, bool), u32> = FxHashMap::default();
        let mut record_index = 0u64;
        let mut last_chunk = 0u64;
        while reader.read_byte_record(&mut record).map_err(csv_error)? {
            let this_seq = seq;
            seq += 1;
            let index_in_file = record_index;
            record_index += 1;
            if seq.is_multiple_of(10_000_000) {
                tracing::info!(
                    rows = seq,
                    seconds = started.elapsed().as_secs(),
                    "partitioning"
                );
            }
            if record.len() < 4 {
                short_rows += 1;
                continue;
            }
            let raw_id = interner.raw_path(record.get(1).unwrap_or_default());
            let text_field = field(&record, 3);
            let rolled = settings.rollup && {
                let text = rollup_text(&text_field);
                roll_up(&interner.raw_paths[raw_id as usize].normalized, &text).is_some()
            };
            let concept = match concept_cache.get(&(raw_id, rolled)) {
                Some(&id) => id,
                None => {
                    let normalized = &interner.raw_paths[raw_id as usize].normalized;
                    let path_value = if rolled {
                        roll_up(normalized, &rollup_text(&text_field))
                            .unwrap_or_else(|| normalized.clone())
                    } else {
                        normalized.clone()
                    };
                    let id = interner.concept(apply_dataset_root(path_value, config));
                    concept_cache.insert((raw_id, rolled), id);
                    id
                }
            };
            let numeric_field = field(&record, 2);
            let value = classify_value(settings.mode, &numeric_field, &text_field);
            let timestamp_field = if record.len() > 4 {
                Some(field(&record, 4))
            } else {
                None
            };
            let (timestamp, bad_timestamp) = match parse_timestamp(timestamp_field.as_deref()) {
                Timestamp::Millis(t) => (t, false),
                Timestamp::Absent => (NO_TIMESTAMP, false),
                Timestamp::Unparseable => (NO_TIMESTAMP, true),
            };
            let patient_field = field(&record, 0);
            let patient = match parse_patient(&patient_field) {
                Ok(p) => Ok(p),
                Err(problem) if ordered => {
                    return Err(EtlError::InvalidPatient {
                        file: path.clone(),
                        record: index_in_file + 1,
                        value: patient_field.into_owned(),
                        problem: problem.describe(),
                    });
                }
                Err(_) => Err(patient_field.as_ref()),
            };
            let chunk = chunking.chunk_of(index_in_file);
            last_chunk = chunk;
            let row = OutRow {
                seq: this_seq,
                concept,
                patient,
                text: value.text,
                status: value.status,
                timestamp,
                bad_timestamp,
                chunk: (chunk_base + chunk) as u32,
                raw_path: raw_id,
                number: value.number,
                value: value.value,
            };
            let target = partition_of(concept, settings.partitions);
            write_row(&mut writers[target], &row, ordered)
                .map_err(EtlError::io("writing", &spill_files[target]))?;
        }
        chunk_base += last_chunk + 1;
    }
    for (writer, path) in writers.into_iter().zip(&spill_files) {
        writer
            .into_inner()
            .map_err(|e| EtlError::io("writing", path)(e.into_error()))?
            .flush()
            .map_err(EtlError::io("writing", path))?;
    }

    let raw_path_ranks = if ordered {
        rank_raw_paths(&interner.raw_paths)
    } else {
        Vec::new()
    };
    tracing::info!(
        rows = seq,
        concepts = interner.concepts.len(),
        raw_paths = interner.raw_paths.len(),
        seconds = started.elapsed().as_secs_f64(),
        "partitioned"
    );
    Ok(Partitioned {
        concepts: interner.concepts,
        raw_path_ranks,
        file_starts,
        spill_files,
        rows_read: seq,
        short_rows,
    })
}

fn rank_raw_paths(raw_paths: &[RawPath]) -> Vec<u32> {
    let mut order: Vec<u32> = (0..raw_paths.len() as u32).collect();
    order.sort_unstable_by(|&a, &b| {
        java_string_cmp(
            &raw_paths[a as usize].decoded,
            &raw_paths[b as usize].decoded,
        )
    });
    let mut ranks = vec![0u32; raw_paths.len()];
    let mut rank = 0u32;
    for (i, &id) in order.iter().enumerate() {
        if i > 0 && raw_paths[order[i - 1] as usize].decoded != raw_paths[id as usize].decoded {
            rank += 1;
        }
        ranks[id as usize] = rank;
    }
    ranks
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn line_count_matches_files_lines() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("x.csv");
        for (content, expected) in [
            ("", 0),
            ("a", 1),
            ("a\n", 1),
            ("a\nb", 2),
            ("a\r\nb\r\n", 2),
            ("a\rb\r", 2),
            ("\n\n", 2),
            ("a\r\n\r\nb", 3),
        ] {
            std::fs::write(&path, content).unwrap();
            assert_eq!(count_java_lines(&path).unwrap(), expected, "{content:?}");
        }
    }

    #[test]
    fn raw_paths_rank_in_java_order_with_ties_shared() {
        let raw = |s: &str| RawPath {
            decoded: s.into(),
            normalized: String::new(),
        };
        let ranks = rank_raw_paths(&[
            raw("b"),
            raw("\u{E000}"),
            raw("a"),
            raw("b"),
            raw("\u{10000}"),
        ]);
        assert_eq!(ranks, vec![1, 3, 0, 1, 2]);
    }
}
