//! The temporary row format the partition pass writes and the build pass reads back.
//!
//! The partition pass only routes rows: values and timestamps are spilled as the raw field
//! text and parsed by the build pass, which runs in parallel. A spilled row, little-endian:
//!
//! ```text
//! seq        u64     global data record number, counting every record of every input
//! concept    u32     concept id
//! raw_path   u32     sequential mode only: raw concept path id
//! flags      u8      bit 0: the patient field did not parse
//! patient    u32     patient number, when it parsed
//! line       u64     the record's line in its file, when the patient did not parse
//! numeric    varint length + UTF-8 bytes, the NUMERIC_VALUE field
//! text       varint length + UTF-8 bytes, the TEXT_VALUE field; empty for a numeric row
//! timestamp  varint length + UTF-8 bytes, the timestamp field; empty when absent
//! ```
//!
//! Lengths are LEB128. Fields that are not valid UTF-8 are spilled with each invalid
//! sequence replaced by U+FFFD, so every spilled field is valid UTF-8.

use std::io::{self, Write};

use hpds_format::NO_TIMESTAMP;

use super::rules::{Mode, Timestamp, ValueStatus, classify_value, parse_timestamp};

const FLAG_BAD_PATIENT: u8 = 1;

const ROW_TEXT: u8 = 1;
const ROW_STATUS_SHIFT: u8 = 1;
const ROW_STATUS_MASK: u8 = 0b11 << ROW_STATUS_SHIFT;
const ROW_BAD_PATIENT: u8 = 1 << 3;
const ROW_BAD_TIMESTAMP: u8 = 1 << 4;

/// One row as the partition pass hands it to [`write_row`].
#[derive(Clone, Copy, Debug)]
pub struct OutRow<'a> {
    pub seq: u64,
    pub concept: u32,
    pub raw_path: u32,
    pub patient: PatientField,
    pub numeric: &'a [u8],
    pub text: &'a [u8],
    pub timestamp: &'a [u8],
}

/// A row's patient: the parsed number, or the line of a record whose patient field did
/// not parse.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PatientField {
    Parsed(u32),
    Unparseable { line: u64 },
}

fn put_len<W: Write>(out: &mut W, mut len: usize) -> io::Result<()> {
    let mut bytes = [0u8; 10];
    let mut n = 0;
    loop {
        let low = (len & 0x7F) as u8;
        len >>= 7;
        if len == 0 {
            bytes[n] = low;
            n += 1;
            break;
        }
        bytes[n] = low | 0x80;
        n += 1;
    }
    out.write_all(&bytes[..n])
}

fn put_bytes<W: Write>(out: &mut W, bytes: &[u8]) -> io::Result<()> {
    put_len(out, bytes.len())?;
    out.write_all(bytes)
}

/// Appends `row` to `out`. The raw path is written in sequential mode only, which orders
/// rows by it.
pub fn write_row<W: Write>(out: &mut W, row: &OutRow<'_>, mode: Mode) -> io::Result<()> {
    out.write_all(&row.seq.to_le_bytes())?;
    out.write_all(&row.concept.to_le_bytes())?;
    if mode == Mode::Sequential {
        out.write_all(&row.raw_path.to_le_bytes())?;
    }
    match row.patient {
        PatientField::Parsed(patient) => {
            out.write_all(&[0])?;
            out.write_all(&patient.to_le_bytes())?;
        }
        PatientField::Unparseable { line } => {
            out.write_all(&[FLAG_BAD_PATIENT])?;
            out.write_all(&line.to_le_bytes())?;
        }
    }
    put_bytes(out, row.numeric)?;
    put_bytes(out, row.text)?;
    put_bytes(out, row.timestamp)
}

/// A spilled row decoded and parsed: its value classified, its timestamp read, and its
/// text value a byte range of the partition buffer. 40 bytes, so a partition of `n` rows
/// needs `40 n` bytes beside its spill file while it is built.
#[derive(Clone, Copy, Debug)]
pub struct SpillRow {
    pub seq: u64,
    pub timestamp: i64,
    value: u64,
    pub concept: u32,
    pub patient: u32,
    /// Sequential mode: the rank of the raw concept path in Java string order. 0 in
    /// new-search mode, which does not order by it.
    pub raw_path_rank: u32,
    flags: u8,
}

impl SpillRow {
    /// True for a text row.
    pub fn is_text(&self) -> bool {
        self.flags & ROW_TEXT != 0
    }

    /// The value status.
    pub fn status(&self) -> ValueStatus {
        match (self.flags & ROW_STATUS_MASK) >> ROW_STATUS_SHIFT {
            0 => ValueStatus::Present,
            1 => ValueStatus::Blank,
            _ => ValueStatus::Unparseable,
        }
    }

    /// True when the patient field did not parse.
    pub fn bad_patient(&self) -> bool {
        self.flags & ROW_BAD_PATIENT != 0
    }

    /// True when the timestamp field was present but did not parse.
    pub fn bad_timestamp(&self) -> bool {
        self.flags & ROW_BAD_TIMESTAMP != 0
    }

    /// The number, for a present numeric row.
    pub fn number(&self) -> f64 {
        f64::from_bits(self.value)
    }

    /// The trimmed text value, for a present text row.
    pub fn text<'b>(&self, buf: &'b [u8]) -> &'b str {
        let start = (self.value >> 32) as usize;
        let len = (self.value & 0xFFFF_FFFF) as usize;
        std::str::from_utf8(&buf[start..start + len]).unwrap_or_default()
    }

    /// The record's line in its input file, for a row whose patient did not parse.
    pub fn line(&self) -> u64 {
        self.value
    }
}

struct Cursor<'b> {
    buf: &'b [u8],
    pos: usize,
}

fn truncated() -> io::Error {
    io::Error::new(io::ErrorKind::UnexpectedEof, "truncated spill row")
}

impl<'b> Cursor<'b> {
    fn take<const N: usize>(&mut self) -> io::Result<[u8; N]> {
        let bytes = self.buf.get(self.pos..self.pos + N).ok_or_else(truncated)?;
        self.pos += N;
        Ok(bytes.try_into().unwrap_or([0; N]))
    }

    fn len(&mut self) -> io::Result<usize> {
        let mut len = 0usize;
        for shift in (0..64).step_by(7) {
            let byte = *self.buf.get(self.pos).ok_or_else(truncated)?;
            self.pos += 1;
            len |= usize::from(byte & 0x7F) << shift;
            if byte & 0x80 == 0 {
                return Ok(len);
            }
        }
        Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "spill length too long",
        ))
    }

    fn str(&mut self) -> io::Result<&'b str> {
        let len = self.len()?;
        let bytes = self
            .buf
            .get(self.pos..self.pos + len)
            .ok_or_else(truncated)?;
        self.pos += len;
        std::str::from_utf8(bytes)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "spill field is not UTF-8"))
    }
}

/// Decodes and parses every row of a spill file held in `buf` under `mode`'s rules.
/// `raw_path_ranks` maps a raw path id to its rank; it is read in sequential mode only.
///
/// # Errors
/// `UnexpectedEof` when the buffer ends inside a row, `InvalidData` for a corrupt row or a
/// buffer of 4 GiB or more, whose text offsets would not fit 32 bits.
pub fn read_rows(buf: &[u8], mode: Mode, raw_path_ranks: &[u32]) -> io::Result<Vec<SpillRow>> {
    if u32::try_from(buf.len()).is_err() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "spill partition is 4 GiB or larger; load with more --partitions",
        ));
    }
    let mut rows = Vec::new();
    let mut c = Cursor { buf, pos: 0 };
    while c.pos < buf.len() {
        let seq = u64::from_le_bytes(c.take()?);
        let concept = u32::from_le_bytes(c.take()?);
        let raw_path_rank = match mode {
            Mode::Sequential => {
                let raw_path = u32::from_le_bytes(c.take()?) as usize;
                *raw_path_ranks.get(raw_path).ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidData, "unknown raw path id")
                })?
            }
            Mode::NewSearch => 0,
        };
        let bad_patient = c.take::<1>()?[0] & FLAG_BAD_PATIENT != 0;
        let (patient, line) = if bad_patient {
            (0, u64::from_le_bytes(c.take()?))
        } else {
            (u32::from_le_bytes(c.take()?), 0)
        };
        let numeric = c.str()?;
        let text = c.str()?;
        let timestamp_field = c.str()?;

        let value = classify_value(mode, numeric, text);
        let mut flags = match value.status {
            ValueStatus::Present => 0,
            ValueStatus::Blank => 1,
            ValueStatus::Unparseable => 2,
        } << ROW_STATUS_SHIFT;
        if value.text {
            flags |= ROW_TEXT;
        }
        let timestamp = match parse_timestamp(Some(timestamp_field)) {
            Timestamp::Millis(t) => t,
            Timestamp::Absent => NO_TIMESTAMP,
            Timestamp::Unparseable => {
                flags |= ROW_BAD_TIMESTAMP;
                NO_TIMESTAMP
            }
        };
        let packed_value = if bad_patient {
            flags |= ROW_BAD_PATIENT;
            line
        } else if value.text && value.status == ValueStatus::Present {
            let start = value.value.as_ptr() as usize - buf.as_ptr() as usize;
            ((start as u64) << 32) | value.value.len() as u64
        } else {
            value.number.to_bits()
        };
        rows.push(SpillRow {
            seq,
            timestamp,
            value: packed_value,
            concept,
            patient,
            raw_path_rank,
            flags,
        });
    }
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spill_rows_are_forty_bytes() {
        assert_eq!(std::mem::size_of::<SpillRow>(), 40);
    }

    #[test]
    fn lengths_round_trip_as_leb128() {
        for len in [0usize, 1, 127, 128, 300, 16_383, 16_384, u32::MAX as usize] {
            let mut buf = Vec::new();
            put_len(&mut buf, len).unwrap();
            let mut c = Cursor { buf: &buf, pos: 0 };
            assert_eq!(c.len().unwrap(), len);
            assert_eq!(c.pos, buf.len());
        }
    }

    #[test]
    fn rows_round_trip() {
        let rows = [
            OutRow {
                seq: 7,
                concept: 3,
                raw_path: 1,
                patient: PatientField::Parsed(42),
                numeric: b"",
                text: "  héllo ".as_bytes(),
                timestamp: b"1000",
            },
            OutRow {
                seq: 8,
                concept: 4,
                raw_path: 0,
                patient: PatientField::Unparseable { line: 12 },
                numeric: b"-2.5",
                text: b"",
                timestamp: b"x",
            },
            OutRow {
                seq: 9,
                concept: 4,
                raw_path: 0,
                patient: PatientField::Parsed(1),
                numeric: b"abc",
                text: b"",
                timestamp: b"",
            },
            OutRow {
                seq: 10,
                concept: 4,
                raw_path: 1,
                patient: PatientField::Parsed(2),
                numeric: b" 3.5 ",
                text: b"",
                timestamp: b"",
            },
        ];
        let ranks = [5, 9];
        for mode in [Mode::NewSearch, Mode::Sequential] {
            let mut buf = Vec::new();
            for row in &rows {
                write_row(&mut buf, row, mode).unwrap();
            }
            let back = read_rows(&buf, mode, &ranks).unwrap();
            assert_eq!(back.len(), 4);
            assert_eq!((back[0].seq, back[0].concept, back[0].patient), (7, 3, 42));
            assert!(back[0].is_text());
            assert_eq!(back[0].text(&buf), "héllo");
            assert_eq!(back[0].timestamp, 1000);
            let expected_rank = if mode == Mode::Sequential { 9 } else { 0 };
            assert_eq!(back[0].raw_path_rank, expected_rank);
            assert!(back[1].bad_patient() && back[1].bad_timestamp());
            assert_eq!(back[1].line(), 12);
            assert_eq!(back[1].timestamp, NO_TIMESTAMP);
            assert_eq!(back[2].status(), ValueStatus::Unparseable);
            assert_eq!(back[3].number(), 3.5);
            assert!(!back[3].is_text());
            assert!(read_rows(&buf[..buf.len() - 1], mode, &ranks).is_err());
        }
    }
}
