//! Writing and reading the aligned arrays of genomic block payloads: every array starts on
//! an 8-byte boundary and is followed by zero padding to the next one.

use std::ops::Range;

use bytemuck::Pod;

use crate::aligned::AlignedBuf;
use crate::error::{FormatError, Result};

/// Appends to a payload under construction.
#[derive(Default)]
pub(crate) struct PayloadWriter {
    pub(crate) buf: Vec<u8>,
}

impl PayloadWriter {
    pub(crate) fn u8(&mut self, value: u8) {
        self.buf.push(value);
    }

    pub(crate) fn u16(&mut self, value: u16) {
        self.buf.extend_from_slice(&value.to_le_bytes());
    }

    pub(crate) fn u32(&mut self, value: u32) {
        self.buf.extend_from_slice(&value.to_le_bytes());
    }

    pub(crate) fn zeros(&mut self, len: usize) {
        self.buf.resize(self.buf.len() + len, 0);
    }

    /// Appends `items` and pads to the next 8-byte boundary.
    pub(crate) fn array<T: Pod>(&mut self, items: &[T]) {
        self.bytes(bytemuck::cast_slice(items));
    }

    /// Appends `bytes` and pads to the next 8-byte boundary.
    pub(crate) fn bytes(&mut self, bytes: &[u8]) {
        self.buf.extend_from_slice(bytes);
        self.buf.resize(self.buf.len().next_multiple_of(8), 0);
    }
}

/// A `u32` length or offset, or an [`FormatError::InvalidInput`] naming `what`.
pub(crate) fn to_u32(value: usize, what: &str) -> Result<u32> {
    u32::try_from(value)
        .map_err(|_| FormatError::InvalidInput(format!("{what} {value} exceeds u32")))
}

/// Reads a payload front to back, checking every length against the bytes left and every
/// padding byte.
pub(crate) struct PayloadReader<'a> {
    pub(crate) bytes: &'a [u8],
    pub(crate) pos: usize,
    pub(crate) what: &'static str,
}

impl<'a> PayloadReader<'a> {
    pub(crate) fn new(bytes: &'a [u8], what: &'static str) -> Self {
        PayloadReader {
            bytes,
            pos: 0,
            what,
        }
    }

    fn take(&mut self, len: usize) -> Result<Range<usize>> {
        let end = self
            .pos
            .checked_add(len)
            .filter(|&end| end <= self.bytes.len())
            .ok_or(FormatError::Truncated(self.what))?;
        let range = self.pos..end;
        self.pos = end;
        Ok(range)
    }

    pub(crate) fn u8(&mut self) -> Result<u8> {
        let range = self.take(1)?;
        Ok(self.bytes[range.start])
    }

    pub(crate) fn u16(&mut self) -> Result<u16> {
        let range = self.take(2)?;
        Ok(u16::from_le_bytes([
            self.bytes[range.start],
            self.bytes[range.start + 1],
        ]))
    }

    pub(crate) fn u32(&mut self) -> Result<u32> {
        let range = self.take(4)?;
        let mut bytes = [0u8; 4];
        bytes.copy_from_slice(&self.bytes[range]);
        Ok(u32::from_le_bytes(bytes))
    }

    /// Reads `len` bytes that must be zero.
    pub(crate) fn reserved(&mut self, len: usize) -> Result<()> {
        let range = self.take(len)?;
        if self.bytes[range].iter().any(|&b| b != 0) {
            return Err(FormatError::corrupt(
                self.what,
                "reserved bytes are not zero",
            ));
        }
        Ok(())
    }

    /// The range of an array of `count` items of `T`, after checking that `count` items fit
    /// in the bytes left, and skips its zero padding.
    pub(crate) fn array<T: Pod>(&mut self, count: usize) -> Result<Range<usize>> {
        let len = count
            .checked_mul(size_of::<T>())
            .ok_or(FormatError::Truncated(self.what))?;
        let range = self.take(len)?;
        let padding = range.end.next_multiple_of(8) - range.end;
        self.reserved(padding)?;
        Ok(range)
    }

    /// The range of an array of `count + 1` items of `T`.
    pub(crate) fn offsets<T: Pod>(&mut self, count: usize) -> Result<Range<usize>> {
        let entries = count
            .checked_add(1)
            .ok_or(FormatError::Truncated(self.what))?;
        self.array::<T>(entries)
    }

    pub(crate) fn finish(&self) -> Result<()> {
        if self.pos == self.bytes.len() {
            Ok(())
        } else {
            Err(FormatError::corrupt(
                self.what,
                format!("{} trailing bytes", self.bytes.len() - self.pos),
            ))
        }
    }
}

/// The items of `range` in `buf` as `&[T]`. Every range a [`PayloadReader`] returns starts on
/// an 8-byte boundary of an aligned buffer, so the cast always succeeds.
pub(crate) fn view<'a, T: Pod>(buf: &'a AlignedBuf, range: &Range<usize>) -> &'a [T] {
    buf.as_bytes()
        .get(range.clone())
        .and_then(|bytes| bytemuck::try_cast_slice(bytes).ok())
        .unwrap_or(&[])
}

/// Checks an offset table: it starts at 0, never decreases, and ends at `data_len`.
pub(crate) fn check_offsets(offsets: &[u32], data_len: usize, what: &'static str) -> Result<()> {
    if offsets.first() != Some(&0) || offsets.last().map(|&l| l as usize) != Some(data_len) {
        return Err(FormatError::corrupt(
            what,
            "offset table does not span its data",
        ));
    }
    if offsets.windows(2).any(|w| w[0] > w[1]) {
        return Err(FormatError::corrupt(what, "offsets decrease"));
    }
    Ok(())
}

/// The last entry of an offset table at `range`, the length of the data it indexes.
pub(crate) fn data_len(buf: &AlignedBuf, range: &Range<usize>) -> usize {
    view::<u32>(buf, range).last().map_or(0, |&l| l as usize)
}

/// Checks that `text` is UTF-8 and that every offset falls on a character boundary, so
/// slicing it by consecutive offsets never fails.
pub(crate) fn check_text(bytes: &[u8], offsets: &[u32], what: &'static str) -> Result<()> {
    let text = std::str::from_utf8(bytes).map_err(|e| FormatError::corrupt(what, e.to_string()))?;
    if offsets.iter().any(|&o| !text.is_char_boundary(o as usize)) {
        return Err(FormatError::corrupt(what, "an offset splits a character"));
    }
    Ok(())
}

/// The `index`-th string of a text array checked by [`check_text`].
pub(crate) fn text_at<'a>(bytes: &'a [u8], offsets: &[u32], index: usize) -> &'a str {
    let (Some(&start), Some(&end)) = (offsets.get(index), offsets.get(index + 1)) else {
        return "";
    };
    bytes
        .get(start as usize..end as usize)
        .and_then(|b| std::str::from_utf8(b).ok())
        .unwrap_or("")
}

/// The `index`-th byte range of a byte array indexed by `offsets`.
pub(crate) fn bytes_at<'a>(bytes: &'a [u8], offsets: &[u32], index: usize) -> &'a [u8] {
    let (Some(&start), Some(&end)) = (offsets.get(index), offsets.get(index + 1)) else {
        return &[];
    };
    bytes.get(start as usize..end as usize).unwrap_or(&[])
}
