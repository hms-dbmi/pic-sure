//! The partition pass: stream every input once, normalize each row's concept path, read its
//! patient number, and append the row to one of N spill files chosen by concept. One thread
//! parses the CSV while another routes the records.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::mpsc;
use std::time::Instant;

use hpds_format::java_string_cmp;
use rustc_hash::FxHashMap;

use super::rules::{ConfigMap, Mode, apply_dataset_root, parse_patient};
use super::spill::{OutRow, PatientField, write_row};
use crate::error::{EtlError, Result};
use crate::java::{is_log4j_blank, normalize_concept_path, roll_up, rolls_up, rollup_text};
use crate::private_fs;

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
    /// Sequential mode: the `seq` of the first record of every chunk, ascending.
    pub chunk_starts: Vec<u64>,
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

/// Records handed from the CSV reader thread to the routing loop at a time.
const BATCH_RECORDS: usize = 4096;

/// Batches in flight between the CSV reader thread and the routing loop.
const BATCHES_IN_FLIGHT: usize = 4;

/// Decodes `record` as UTF-8, replacing each invalid sequence in a field with U+FFFD as
/// `String::from_utf8_lossy` does, and keeps its position.
fn decode(record: csv::ByteRecord) -> csv::StringRecord {
    let position = record.position().cloned();
    let mut decoded = csv::StringRecord::from_byte_record_lossy(record);
    decoded.set_position(position);
    decoded
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

/// Reads `path` as CSV on a separate thread and calls `route` on every data record in
/// order, so CSV parsing and UTF-8 decoding overlap routing. A field that is not valid
/// UTF-8 has each invalid sequence replaced by U+FFFD. Stops at the first error `route`
/// returns.
fn for_each_record(
    path: &Path,
    mut route: impl FnMut(&csv::StringRecord) -> Result<()>,
) -> Result<()> {
    let file = File::open(path).map_err(EtlError::io("opening", path))?;
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(true)
        .flexible(true)
        .from_reader(BufReader::with_capacity(1 << 20, file));
    let (full_tx, full_rx) =
        mpsc::sync_channel::<csv::Result<Vec<csv::StringRecord>>>(BATCHES_IN_FLIGHT);
    let (empty_tx, empty_rx) = mpsc::channel::<Vec<csv::StringRecord>>();
    std::thread::scope(|scope| {
        scope.spawn(move || {
            let mut raw = csv::ByteRecord::new();
            loop {
                let mut batch = empty_rx.try_recv().unwrap_or_default();
                batch.resize_with(BATCH_RECORDS, csv::StringRecord::new);
                let mut filled = 0;
                let mut failure = None;
                while filled < BATCH_RECORDS {
                    match reader.read_byte_record(&mut raw) {
                        Ok(true) => {
                            let slot = std::mem::take(&mut batch[filled]);
                            batch[filled] =
                                decode(std::mem::replace(&mut raw, slot.into_byte_record()));
                            filled += 1;
                        }
                        Ok(false) => break,
                        Err(e) => {
                            failure = Some(e);
                            break;
                        }
                    }
                }
                let done = filled < BATCH_RECORDS;
                batch.truncate(filled);
                if !batch.is_empty() && full_tx.send(Ok(batch)).is_err() {
                    return;
                }
                if let Some(e) = failure {
                    let _ = full_tx.send(Err(e));
                    return;
                }
                if done {
                    return;
                }
            }
        });
        let result = (|| {
            for batch in &full_rx {
                let batch = batch.map_err(|source| EtlError::Csv {
                    path: path.to_path_buf(),
                    source,
                })?;
                for record in &batch {
                    route(record)?;
                }
                let _ = empty_tx.send(batch);
            }
            Ok(())
        })();
        drop(full_rx);
        result
    })
}

/// Encoded rows bound for the spill files: `bytes` holds the rows back to back, and
/// `ends` the partition and end offset of each.
#[derive(Default)]
struct RoutedBatch {
    bytes: Vec<u8>,
    ends: Vec<(u32, u32)>,
}

/// Bytes of encoded rows the routing loop collects before handing them to the spill
/// writer thread.
const ROUTED_BATCH_BYTES: usize = 1 << 20;

/// The routing loop's end of the spill writer thread.
struct SpillSink {
    full: mpsc::SyncSender<RoutedBatch>,
    empty: mpsc::Receiver<RoutedBatch>,
    current: RoutedBatch,
    mode: Mode,
}

impl SpillSink {
    /// Queues `row` for spill file `partition`. Returns false when the writer thread has
    /// stopped.
    fn push(&mut self, partition: usize, row: &OutRow<'_>) -> bool {
        write_row(&mut self.current.bytes, row, self.mode).unwrap_or_default();
        self.current
            .ends
            .push((partition as u32, self.current.bytes.len() as u32));
        self.current.bytes.len() < ROUTED_BATCH_BYTES || self.flush()
    }

    /// Hands the queued rows to the writer thread. Returns false when it has stopped.
    fn flush(&mut self) -> bool {
        if self.current.ends.is_empty() {
            return true;
        }
        let next = self.empty.try_recv().unwrap_or_default();
        self.full
            .send(std::mem::replace(&mut self.current, next))
            .is_ok()
    }
}

/// The spill writer thread: appends every batch's rows to their spill files, then flushes
/// them.
fn write_spills(
    full: mpsc::Receiver<RoutedBatch>,
    empty: mpsc::Sender<RoutedBatch>,
    mut writers: Vec<BufWriter<File>>,
    paths: &[PathBuf],
) -> Result<()> {
    for mut batch in full {
        let mut start = 0usize;
        for &(partition, end) in &batch.ends {
            let (partition, end) = (partition as usize, end as usize);
            writers[partition]
                .write_all(&batch.bytes[start..end])
                .map_err(EtlError::io("writing", &paths[partition]))?;
            start = end;
        }
        batch.bytes.clear();
        batch.ends.clear();
        let _ = empty.send(batch);
    }
    for (writer, path) in writers.into_iter().zip(paths) {
        writer
            .into_inner()
            .map_err(|e| EtlError::io("writing", path)(e.into_error()))?
            .flush()
            .map_err(EtlError::io("writing", path))?;
    }
    Ok(())
}

/// Streams `inputs` in order into `settings.partitions` spill files under
/// `settings.spill_dir`. Values and timestamps are spilled unparsed; the build pass parses
/// them in parallel. Three threads share the work: one parses the CSV, one routes records,
/// one writes the spill files.
///
/// # Errors
/// I/O and CSV errors, and in sequential mode [`EtlError::InvalidPatient`] for the first
/// record (in file order) whose patient number is not a non-negative Java `int`, because
/// `LowRAMCSVProcessor` parses the patient of every record with at least four fields.
pub(crate) fn partition(
    inputs: &[InputFile],
    settings: &PartitionSettings<'_>,
) -> Result<Partitioned> {
    let mut spill_files = Vec::with_capacity(settings.partitions);
    let mut writers = Vec::with_capacity(settings.partitions);
    for i in 0..settings.partitions {
        let path = settings.spill_dir.join(format!("rows-{i:05}.bin"));
        let file = private_fs::create_file(&path).map_err(EtlError::io("creating", &path))?;
        writers.push(BufWriter::with_capacity(256 * 1024, file));
        spill_files.push(path);
    }
    let (full_tx, full_rx) = mpsc::sync_channel(BATCHES_IN_FLIGHT);
    let (empty_tx, empty_rx) = mpsc::channel();
    let paths = spill_files.clone();
    std::thread::scope(|scope| {
        let writer = scope.spawn(move || write_spills(full_rx, empty_tx, writers, &paths));
        let mut sink = SpillSink {
            full: full_tx,
            empty: empty_rx,
            current: RoutedBatch::default(),
            mode: settings.mode,
        };
        let routed =
            route(inputs, settings, &mut sink).map(|partitioned| (partitioned, sink.flush()));
        drop(sink);
        let written = writer
            .join()
            .unwrap_or_else(|_| Err(EtlError::ThreadPool("the spill writer panicked".into())));
        written?;
        let (partitioned, _) = routed?;
        Ok(Partitioned {
            spill_files,
            ..partitioned
        })
    })
}

/// The routing loop of [`partition`]. Returns everything but the spill file paths. When
/// the writer thread stops early, returns an error that its own error replaces.
fn route(
    inputs: &[InputFile],
    settings: &PartitionSettings<'_>,
    sink: &mut SpillSink,
) -> Result<Partitioned> {
    let sequential = settings.mode == Mode::Sequential;
    let mut interner = Interner {
        raw_ids: FxHashMap::default(),
        raw_paths: Vec::new(),
        concept_ids: FxHashMap::default(),
        concepts: Vec::new(),
    };
    let mut file_starts = Vec::with_capacity(inputs.len());
    let mut chunk_starts = Vec::new();
    let mut seq = 0u64;
    let mut short_rows = 0u64;
    let mut chunk_base = 0u64;
    let started = Instant::now();

    for input in inputs {
        let path = &input.path;
        tracing::info!(file = %path.display(), "reading");
        file_starts.push((seq, path.clone()));
        let config = settings.configs.get(&input.config_key);
        let chunking = if sequential {
            Chunking::for_file(path, settings.max_chunk_bytes)?
        } else {
            Chunking {
                records_per_chunk: None,
            }
        };
        let mut concept_cache: FxHashMap<(u32, bool), u32> = FxHashMap::default();
        let mut record_index = 0u64;
        let mut last_chunk = None;
        for_each_record(path, |record| {
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
            let chunk = chunk_base + chunking.chunk_of(index_in_file);
            if sequential && last_chunk != Some(chunk) {
                chunk_starts.push(this_seq);
                last_chunk = Some(chunk);
            }
            if record.len() < 4 {
                short_rows += 1;
                return Ok(());
            }
            let field = |i| record.get(i).unwrap_or_default();
            let raw_id = interner.raw_path(field(1).as_bytes());
            let rolled = settings.rollup
                && rolls_up(
                    &interner.raw_paths[raw_id as usize].normalized,
                    &rollup_text(field(3)),
                );
            let concept = match concept_cache.get(&(raw_id, rolled)) {
                Some(&id) => id,
                None => {
                    let normalized = &interner.raw_paths[raw_id as usize].normalized;
                    let path_value = if rolled {
                        roll_up(normalized, &rollup_text(field(3)))
                            .unwrap_or_else(|| normalized.clone())
                    } else {
                        normalized.clone()
                    };
                    let id = interner.concept(apply_dataset_root(path_value, config));
                    concept_cache.insert((raw_id, rolled), id);
                    id
                }
            };
            let line = || record.position().map_or(0, |p| p.line());
            let patient = match parse_patient(field(0)) {
                Ok(p) => PatientField::Parsed(p),
                Err(_) if sequential => {
                    return Err(EtlError::InvalidPatient {
                        file: path.clone(),
                        record: index_in_file + 1,
                        line: line(),
                    });
                }
                Err(_) => PatientField::Unparseable { line: line() },
            };
            let numeric = field(2);
            let is_text = match settings.mode {
                Mode::NewSearch => numeric.is_empty(),
                Mode::Sequential => is_log4j_blank(numeric),
            };
            let row = OutRow {
                seq: this_seq,
                concept,
                raw_path: raw_id,
                patient,
                numeric: numeric.as_bytes(),
                text: if is_text { field(3).as_bytes() } else { &[] },
                timestamp: field(4).as_bytes(),
            };
            if sink.push(partition_of(concept, settings.partitions), &row) {
                Ok(())
            } else {
                Err(EtlError::ThreadPool("the spill writer stopped".into()))
            }
        })?;
        chunk_base = last_chunk.map_or(chunk_base, |c| c + 1);
    }

    let raw_path_ranks = if sequential {
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
        chunk_starts,
        file_starts,
        spill_files: Vec::new(),
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
