//! The temporary row format the partition pass writes and the build pass reads back.
//!
//! A spilled row, little-endian:
//!
//! ```text
//! seq        u64  global data record number, counting every record of every input
//! concept    u32  concept id
//! patient    u32  patient number, 0 when the patient flag says it did not parse
//! flags      u8   see the FLAG_ constants
//! timestamp  i64  epoch milliseconds or NO_TIMESTAMP
//! chunk      u32  sequential mode only: global chunk number
//! raw_path   u32  sequential mode only: raw concept path id
//! number     f64  present numeric values only
//! text       u32 length + UTF-8 bytes, present text values only
//! bad_patient u32 length + UTF-8 bytes, rows whose patient did not parse only
//! ```

use std::io::{self, Write};

use super::rules::ValueStatus;

const FLAG_TEXT: u8 = 1;
const STATUS_SHIFT: u8 = 1;
const STATUS_MASK: u8 = 0b11 << STATUS_SHIFT;
const FLAG_BAD_PATIENT: u8 = 1 << 3;
const FLAG_BAD_TIMESTAMP: u8 = 1 << 4;

fn status_bits(status: ValueStatus) -> u8 {
    let code = match status {
        ValueStatus::Present => 0,
        ValueStatus::Blank => 1,
        ValueStatus::Unparseable => 2,
    };
    code << STATUS_SHIFT
}

/// One row as the partition pass hands it to [`write_row`].
#[derive(Clone, Copy, Debug)]
pub struct OutRow<'a> {
    pub seq: u64,
    pub concept: u32,
    pub patient: Result<u32, &'a str>,
    pub text: bool,
    pub status: ValueStatus,
    pub timestamp: i64,
    pub bad_timestamp: bool,
    pub chunk: u32,
    pub raw_path: u32,
    pub number: f64,
    pub value: &'a str,
}

fn put_str<W: Write>(out: &mut W, s: &str) -> io::Result<()> {
    out.write_all(&(s.len() as u32).to_le_bytes())?;
    out.write_all(s.as_bytes())
}

/// Appends `row` to `out`. `ordered` says whether the chunk and raw path fields are
/// written, which sequential mode needs and new-search mode does not.
pub fn write_row<W: Write>(out: &mut W, row: &OutRow<'_>, ordered: bool) -> io::Result<()> {
    let mut flags = status_bits(row.status);
    if row.text {
        flags |= FLAG_TEXT;
    }
    if row.patient.is_err() {
        flags |= FLAG_BAD_PATIENT;
    }
    if row.bad_timestamp {
        flags |= FLAG_BAD_TIMESTAMP;
    }
    out.write_all(&row.seq.to_le_bytes())?;
    out.write_all(&row.concept.to_le_bytes())?;
    out.write_all(&row.patient.unwrap_or(0).to_le_bytes())?;
    out.write_all(&[flags])?;
    out.write_all(&row.timestamp.to_le_bytes())?;
    if ordered {
        out.write_all(&row.chunk.to_le_bytes())?;
        out.write_all(&row.raw_path.to_le_bytes())?;
    }
    if row.status == ValueStatus::Present {
        if row.text {
            put_str(out, row.value)?;
        } else {
            out.write_all(&row.number.to_le_bytes())?;
        }
    }
    if let Err(raw) = row.patient {
        put_str(out, raw)?;
    }
    Ok(())
}

/// A spilled row decoded in place: text fields are byte ranges into the partition buffer.
#[derive(Clone, Copy, Debug)]
pub struct SpillRow {
    pub seq: u64,
    pub concept: u32,
    pub patient: u32,
    pub flags: u8,
    pub timestamp: i64,
    pub chunk: u32,
    pub raw_path: u32,
    pub number: f64,
    pub text_start: usize,
    pub text_len: u32,
    pub bad_patient_start: usize,
    pub bad_patient_len: u32,
}

impl SpillRow {
    /// True for a text row.
    pub fn is_text(&self) -> bool {
        self.flags & FLAG_TEXT != 0
    }

    /// The value status.
    pub fn status(&self) -> ValueStatus {
        match (self.flags & STATUS_MASK) >> STATUS_SHIFT {
            0 => ValueStatus::Present,
            1 => ValueStatus::Blank,
            _ => ValueStatus::Unparseable,
        }
    }

    /// True when the patient field did not parse.
    pub fn bad_patient(&self) -> bool {
        self.flags & FLAG_BAD_PATIENT != 0
    }

    /// True when the timestamp field was present but did not parse.
    pub fn bad_timestamp(&self) -> bool {
        self.flags & FLAG_BAD_TIMESTAMP != 0
    }

    /// The text value, for a present text row.
    pub fn text<'b>(&self, buf: &'b [u8]) -> &'b str {
        utf8(&buf[self.text_start..self.text_start + self.text_len as usize])
    }

    /// The raw patient field, for a row whose patient did not parse.
    pub fn bad_patient_text<'b>(&self, buf: &'b [u8]) -> &'b str {
        utf8(&buf[self.bad_patient_start..self.bad_patient_start + self.bad_patient_len as usize])
    }
}

fn utf8(bytes: &[u8]) -> &str {
    std::str::from_utf8(bytes).unwrap_or_default()
}

struct Cursor<'b> {
    buf: &'b [u8],
    pos: usize,
}

impl Cursor<'_> {
    fn take<const N: usize>(&mut self) -> io::Result<[u8; N]> {
        let bytes = self
            .buf
            .get(self.pos..self.pos + N)
            .ok_or_else(|| io::Error::new(io::ErrorKind::UnexpectedEof, "truncated spill row"))?;
        self.pos += N;
        Ok(bytes.try_into().unwrap_or([0; N]))
    }

    fn string(&mut self) -> io::Result<(usize, u32)> {
        let len = u32::from_le_bytes(self.take()?);
        let start = self.pos;
        if self.buf.len() < start + len as usize {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "truncated spill string",
            ));
        }
        self.pos += len as usize;
        Ok((start, len))
    }
}

/// Decodes every row of a spill file held in `buf`.
///
/// # Errors
/// `UnexpectedEof` when the buffer ends inside a row.
pub fn read_rows(buf: &[u8], ordered: bool) -> io::Result<Vec<SpillRow>> {
    let mut rows = Vec::new();
    let mut c = Cursor { buf, pos: 0 };
    while c.pos < buf.len() {
        let mut row = SpillRow {
            seq: u64::from_le_bytes(c.take()?),
            concept: u32::from_le_bytes(c.take()?),
            patient: u32::from_le_bytes(c.take()?),
            flags: c.take::<1>()?[0],
            timestamp: i64::from_le_bytes(c.take()?),
            chunk: 0,
            raw_path: 0,
            number: 0.0,
            text_start: 0,
            text_len: 0,
            bad_patient_start: 0,
            bad_patient_len: 0,
        };
        if ordered {
            row.chunk = u32::from_le_bytes(c.take()?);
            row.raw_path = u32::from_le_bytes(c.take()?);
        }
        if row.status() == ValueStatus::Present {
            if row.is_text() {
                (row.text_start, row.text_len) = c.string()?;
            } else {
                row.number = f64::from_le_bytes(c.take()?);
            }
        }
        if row.bad_patient() {
            (row.bad_patient_start, row.bad_patient_len) = c.string()?;
        }
        rows.push(row);
    }
    Ok(rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rows_round_trip() {
        let rows = [
            OutRow {
                seq: 7,
                concept: 3,
                patient: Ok(42),
                text: true,
                status: ValueStatus::Present,
                timestamp: 1000,
                bad_timestamp: false,
                chunk: 2,
                raw_path: 9,
                number: 0.0,
                value: "héllo",
            },
            OutRow {
                seq: 8,
                concept: 4,
                patient: Err("x1"),
                text: false,
                status: ValueStatus::Present,
                timestamp: i64::MIN,
                bad_timestamp: true,
                chunk: 0,
                raw_path: 1,
                number: -2.5,
                value: "",
            },
            OutRow {
                seq: 9,
                concept: 4,
                patient: Ok(1),
                text: false,
                status: ValueStatus::Unparseable,
                timestamp: 5,
                bad_timestamp: false,
                chunk: 0,
                raw_path: 1,
                number: 0.0,
                value: "",
            },
        ];
        for ordered in [false, true] {
            let mut buf = Vec::new();
            for row in &rows {
                write_row(&mut buf, row, ordered).unwrap();
            }
            let back = read_rows(&buf, ordered).unwrap();
            assert_eq!(back.len(), 3);
            assert_eq!((back[0].seq, back[0].concept, back[0].patient), (7, 3, 42));
            assert!(back[0].is_text());
            assert_eq!(back[0].text(&buf), "héllo");
            assert_eq!(back[0].timestamp, 1000);
            assert_eq!(back[0].chunk, if ordered { 2 } else { 0 });
            assert!(back[1].bad_patient() && back[1].bad_timestamp());
            assert_eq!(back[1].bad_patient_text(&buf), "x1");
            assert_eq!(back[1].number, -2.5);
            assert_eq!(back[2].status(), ValueStatus::Unparseable);
            assert!(read_rows(&buf[..buf.len() - 1], ordered).is_err());
        }
    }
}
