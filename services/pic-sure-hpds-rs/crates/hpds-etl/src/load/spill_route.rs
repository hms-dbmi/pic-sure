//! The spill routing pass: stream every input once, normalize each row's concept path, read its
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
use crate::java::{
    is_log4j_blank, normalize_concept_path, normalized_text_value, resplit_concept_path, roll_up,
    rolls_up,
};
use crate::private_fs;

/// One input file and the name its CSV config is looked up under.
#[derive(Clone, Debug)]
pub(crate) struct InputFile {
    pub path: PathBuf,
    pub config_key: String,
}

/// What the spill routing pass hands to the build pass.
pub(crate) struct SpillRouted {
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
    /// Input files that start with a UTF-8 byte order mark.
    pub inputs_with_byte_order_mark: u64,
    /// Records with text after a quoted field's closing quote.
    pub records_with_text_after_closing_quote: u64,
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

fn spill_file_of(concept: u32, partitions: usize) -> usize {
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

/// Settings for [`route_to_spills`].
pub(crate) struct SpillRouteSettings<'a> {
    pub mode: Mode,
    pub rollup: bool,
    pub configs: &'a ConfigMap,
    pub spill_dir: &'a Path,
    pub partitions: usize,
    pub max_chunk_bytes: f64,
}

/// What a CSV input held that the loader reads leniently and Java's Commons CSV parser
/// does not: counted in `etl-report.json` and logged once per file.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct InputQuirks {
    /// The file starts with a UTF-8 byte order mark, which the CSV reader skips. Commons
    /// CSV keeps it as part of the first field.
    pub byte_order_mark: bool,
    /// Records in which a quoted field's closing quote is followed by something other than
    /// a comma, a line end or another quote. The CSV reader appends those characters to the
    /// field; Commons CSV fails with "invalid char between encapsulated token and
    /// delimiter".
    pub records_with_text_after_closing_quote: u64,
}

const UTF8_BYTE_ORDER_MARK: [u8; 3] = [0xEF, 0xBB, 0xBF];

/// Where [`QuirkScan`] is in the CSV grammar, tracked as the `csv` crate's reader moves
/// through it with the default dialect (comma, double quote, doubled quotes as escapes).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum QuoteState {
    StartField,
    Unquoted,
    Quoted,
    QuoteInQuoted,
}

/// A reader that passes its input through unchanged while recording its
/// [`InputQuirks`].
struct QuirkScan<R> {
    inner: R,
    state: QuoteState,
    bom_matched: usize,
    bom_done: bool,
    record_flagged: bool,
    quirks: InputQuirks,
}

impl<R> QuirkScan<R> {
    fn new(inner: R) -> Self {
        QuirkScan {
            inner,
            state: QuoteState::StartField,
            bom_matched: 0,
            bom_done: false,
            record_flagged: false,
            quirks: InputQuirks::default(),
        }
    }

    fn scan(&mut self, mut bytes: &[u8]) {
        while !self.bom_done {
            let Some((&byte, rest)) = bytes.split_first() else {
                return;
            };
            if byte == UTF8_BYTE_ORDER_MARK[self.bom_matched] {
                self.bom_matched += 1;
                bytes = rest;
                if self.bom_matched == UTF8_BYTE_ORDER_MARK.len() {
                    self.bom_done = true;
                    self.quirks.byte_order_mark = true;
                }
            } else {
                self.bom_done = true;
                if self.bom_matched > 0 {
                    self.step(UTF8_BYTE_ORDER_MARK[0]);
                }
            }
        }
        for &byte in bytes {
            self.step(byte);
        }
    }

    fn step(&mut self, byte: u8) {
        use QuoteState::{QuoteInQuoted, Quoted, StartField, Unquoted};
        self.state = match (self.state, byte) {
            (StartField, b'"') => Quoted,
            (StartField | Unquoted | QuoteInQuoted, b',') => StartField,
            (StartField | Unquoted | QuoteInQuoted, b'\n' | b'\r') => {
                self.end_record();
                StartField
            }
            (StartField | Unquoted, _) => Unquoted,
            (Quoted, b'"') => QuoteInQuoted,
            (Quoted, _) => Quoted,
            (QuoteInQuoted, b'"') => Quoted,
            (QuoteInQuoted, _) => {
                self.record_flagged = true;
                Unquoted
            }
        };
    }

    fn end_record(&mut self) {
        if self.record_flagged {
            self.quirks.records_with_text_after_closing_quote += 1;
            self.record_flagged = false;
        }
    }
}

impl<R: Read> Read for QuirkScan<R> {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        let n = self.inner.read(buf)?;
        if n == 0 {
            self.end_record();
        } else {
            self.scan(&buf[..n]);
        }
        Ok(n)
    }
}

/// Reads `path` as CSV on a separate thread and calls `route` on every data record in
/// order, so CSV parsing and UTF-8 decoding overlap routing. With `header`, the first
/// record is a header and skipped. A field that is not valid UTF-8 has each invalid
/// sequence replaced by U+FFFD. Stops at the first error `route` returns. Returns what the
/// file held that Java's CSV parser would not have accepted.
fn for_each_record(
    path: &Path,
    header: bool,
    mut route: impl FnMut(&csv::StringRecord) -> Result<()>,
) -> Result<InputQuirks> {
    let file = File::open(path).map_err(EtlError::io("opening", path))?;
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(header)
        .flexible(true)
        .from_reader(QuirkScan::new(BufReader::with_capacity(1 << 20, file)));
    let (full_tx, full_rx) =
        mpsc::sync_channel::<csv::Result<Vec<csv::StringRecord>>>(BATCHES_IN_FLIGHT);
    let (empty_tx, empty_rx) = mpsc::channel::<Vec<csv::StringRecord>>();
    std::thread::scope(|scope| {
        let reader_thread = scope.spawn(move || {
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
                    break;
                }
                if let Some(e) = failure {
                    let _ = full_tx.send(Err(e));
                    break;
                }
                if done {
                    break;
                }
            }
            reader.get_ref().quirks
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
        let quirks = reader_thread
            .join()
            .map_err(|_| EtlError::ThreadPool("the CSV reader panicked".into()))?;
        result.map(|()| quirks)
    })
}

/// Logs, once for the file `path`, what it held that Java's CSV parser would not accept.
fn warn_about_quirks(path: &Path, quirks: InputQuirks) {
    if quirks.byte_order_mark {
        tracing::warn!(
            file = %path.display(),
            "the file starts with a UTF-8 byte order mark, which is skipped; Java's CSV parser keeps it in the first field"
        );
    }
    if quirks.records_with_text_after_closing_quote > 0 {
        tracing::warn!(
            file = %path.display(),
            records = quirks.records_with_text_after_closing_quote,
            "records have text after a closing quote, which is kept as part of the field; Java's CSV parser fails on them"
        );
    }
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
pub(crate) fn route_to_spills(
    inputs: &[InputFile],
    settings: &SpillRouteSettings<'_>,
) -> Result<SpillRouted> {
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
        Ok(SpillRouted {
            spill_files,
            ..partitioned
        })
    })
}

/// The routing loop of [`route_to_spills`]. Returns everything but the spill file paths. When
/// the writer thread stops early, returns an error that its own error replaces.
fn route(
    inputs: &[InputFile],
    settings: &SpillRouteSettings<'_>,
    sink: &mut SpillSink,
) -> Result<SpillRouted> {
    let sequential = settings.mode == Mode::Sequential;
    let csv_loader = settings.mode == Mode::Partitioned;
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
    let mut inputs_with_byte_order_mark = 0u64;
    let mut records_with_text_after_closing_quote = 0u64;
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
        let mut csv_loader_bases: FxHashMap<u32, String> = FxHashMap::default();
        let mut record_index = 0u64;
        let mut last_chunk = None;
        let quirks = for_each_record(path, !csv_loader, |record| {
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
            if csv_loader {
                let base = csv_loader_bases.entry(raw_id).or_insert_with(|| {
                    resplit_concept_path(&apply_dataset_root(
                        interner.raw_paths[raw_id as usize].normalized.clone(),
                        config,
                    ))
                });
                let rolled = rolls_up(base, &normalized_text_value(field(3)));
                let concept = match concept_cache.get(&(raw_id, rolled)) {
                    Some(&id) => id,
                    None => {
                        let path_value = if rolled {
                            roll_up(base, &normalized_text_value(field(3)))
                                .unwrap_or_else(|| base.clone())
                        } else {
                            base.clone()
                        };
                        let id = interner.concept(path_value);
                        concept_cache.insert((raw_id, rolled), id);
                        id
                    }
                };
                return route_row(record, this_seq, concept, raw_id, settings, sink);
            }
            let rolled = settings.rollup
                && rolls_up(
                    &interner.raw_paths[raw_id as usize].normalized,
                    &normalized_text_value(field(3)),
                );
            let concept = match concept_cache.get(&(raw_id, rolled)) {
                Some(&id) => id,
                None => {
                    let normalized = &interner.raw_paths[raw_id as usize].normalized;
                    let path_value = if rolled {
                        roll_up(normalized, &normalized_text_value(field(3)))
                            .unwrap_or_else(|| normalized.clone())
                    } else {
                        normalized.clone()
                    };
                    let id = interner.concept(apply_dataset_root(path_value, config));
                    concept_cache.insert((raw_id, rolled), id);
                    id
                }
            };
            if sequential && parse_patient(field(0)).is_err() {
                return Err(EtlError::InvalidPatient {
                    file: path.clone(),
                    record: index_in_file + 1,
                    line: record.position().map_or(0, |p| p.line()),
                });
            }
            route_row(record, this_seq, concept, raw_id, settings, sink)
        })?;
        warn_about_quirks(path, quirks);
        inputs_with_byte_order_mark += u64::from(quirks.byte_order_mark);
        records_with_text_after_closing_quote += quirks.records_with_text_after_closing_quote;
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
    Ok(SpillRouted {
        concepts: interner.concepts,
        raw_path_ranks,
        chunk_starts,
        file_starts,
        spill_files: Vec::new(),
        rows_read: seq,
        short_rows,
        inputs_with_byte_order_mark,
        records_with_text_after_closing_quote,
    })
}

/// Spills one record of `concept`. The value and timestamp go unparsed; a patient number
/// that does not parse is spilled with the record's line, for the build pass to report if
/// the row would have been stored. The text field is spilled only for rows that may be
/// text.
///
/// # Errors
/// [`EtlError::ThreadPool`] when the spill writer thread has stopped.
fn route_row(
    record: &csv::StringRecord,
    seq: u64,
    concept: u32,
    raw_path: u32,
    settings: &SpillRouteSettings<'_>,
    sink: &mut SpillSink,
) -> Result<()> {
    let field = |i| record.get(i).unwrap_or_default();
    let patient = match parse_patient(field(0)) {
        Ok(p) => PatientField::Parsed(p),
        Err(_) => PatientField::Unparseable {
            line: record.position().map_or(0, |p| p.line()),
        },
    };
    let numeric = field(2);
    let maybe_text = match settings.mode {
        Mode::NewSearch | Mode::Partitioned => numeric.is_empty(),
        Mode::Sequential => is_log4j_blank(numeric),
    };
    let row = OutRow {
        seq,
        concept,
        raw_path,
        patient,
        numeric: numeric.as_bytes(),
        text: if maybe_text { field(3).as_bytes() } else { &[] },
        timestamp: field(4).as_bytes(),
    };
    if sink.push(spill_file_of(concept, settings.partitions), &row) {
        Ok(())
    } else {
        Err(EtlError::ThreadPool("the spill writer stopped".into()))
    }
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

    fn quirks(input: &[u8]) -> InputQuirks {
        let mut whole = QuirkScan::new(input);
        std::io::copy(&mut whole, &mut std::io::sink()).unwrap();
        let mut bytewise = QuirkScan::new(input);
        let mut one = [0u8; 1];
        while bytewise.read(&mut one).unwrap() == 1 {}
        assert_eq!(whole.quirks, bytewise.quirks, "{input:?}");
        whole.quirks
    }

    #[test]
    fn a_byte_order_mark_is_noticed_only_at_the_start() {
        assert!(quirks(b"\xEF\xBB\xBF1,a,,x\n").byte_order_mark);
        assert!(!quirks(b"1,a,,\xEF\xBB\xBF\n").byte_order_mark);
        assert!(!quirks(b"\xEF\xBB1,a\n").byte_order_mark);
        assert!(!quirks(b"").byte_order_mark);
        assert_eq!(
            quirks(b"\xEF\xBB\xBF\"a\"x,b\n").records_with_text_after_closing_quote,
            1,
            "a quoted first field after the mark is still a quoted field"
        );
    }

    #[test]
    fn text_after_a_closing_quote_is_counted_once_per_record() {
        let count = |input: &[u8]| quirks(input).records_with_text_after_closing_quote;
        assert_eq!(count(b"1,\"a\",\"b\"\r\n2,\"c\"\"d\",e\n"), 0);
        assert_eq!(count(b"1,\"a\"x,\"b\"y\n2,\"c\" ,d\n3,e,f"), 2);
        assert_eq!(count(b"1,\"a\nb\"x"), 1, "the last record has no line end");
        assert_eq!(
            count(b"1,a\"b\"c,d\n"),
            0,
            "a quote inside an unquoted field is literal"
        );
        assert_eq!(count(b"1,\"a,\"\"x\"\"\",y\n"), 0);
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
