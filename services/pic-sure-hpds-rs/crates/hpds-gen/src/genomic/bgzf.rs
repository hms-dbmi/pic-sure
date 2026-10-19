//! A BGZF writer: the blocked gzip format of `bgzip`, which Java's `BlockCompressedInputStream`
//! requires for VCFs the index marks as gzipped.

use std::io::{self, Write};

use flate2::{Compress, Compression, Crc, FlushCompress, Status};

const BLOCK_INPUT_BYTES: usize = 0xff00;
const HEADER_BYTES: usize = 18;
const FOOTER_BYTES: usize = 8;
const EOF_BLOCK: [u8; 28] = [
    0x1f, 0x8b, 0x08, 0x04, 0, 0, 0, 0, 0, 0xff, 0x06, 0, 0x42, 0x43, 0x02, 0, 0x1b, 0, 0x03, 0, 0,
    0, 0, 0, 0, 0, 0, 0,
];

/// Writes bytes as BGZF blocks of at most 65,280 input bytes, ending with the empty EOF block.
pub struct BgzfWriter<W: Write> {
    inner: W,
    pending: Vec<u8>,
    block: Vec<u8>,
    compress: Compress,
    written: u64,
}

impl<W: Write> BgzfWriter<W> {
    /// Creates a writer over `inner` that deflates each block at `level` (0 to 9).
    pub fn new(inner: W, level: u32) -> Self {
        Self {
            inner,
            pending: Vec::with_capacity(BLOCK_INPUT_BYTES),
            block: Vec::with_capacity(BLOCK_INPUT_BYTES + 1024),
            compress: Compress::new(Compression::new(level), false),
            written: 0,
        }
    }

    /// Compressed bytes handed to the inner writer so far, EOF block excluded.
    pub fn compressed_bytes(&self) -> u64 {
        self.written
    }

    fn flush_block(&mut self) -> io::Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        self.block.clear();
        self.block.resize(HEADER_BYTES, 0);
        self.compress.reset();
        let status = self
            .compress
            .compress_vec(&self.pending, &mut self.block, FlushCompress::Finish)
            .map_err(io::Error::other)?;
        if status != Status::StreamEnd {
            return Err(io::Error::other("deflate did not finish a BGZF block"));
        }
        let mut crc = Crc::new();
        crc.update(&self.pending);
        self.block.extend_from_slice(&crc.sum().to_le_bytes());
        self.block
            .extend_from_slice(&(self.pending.len() as u32).to_le_bytes());
        let total = self.block.len();
        if total - 1 > u16::MAX as usize {
            return Err(io::Error::other("a BGZF block outgrew 64 KiB"));
        }
        self.block[..HEADER_BYTES].copy_from_slice(&[
            0x1f,
            0x8b,
            0x08,
            0x04,
            0,
            0,
            0,
            0,
            0,
            0xff,
            0x06,
            0,
            0x42,
            0x43,
            0x02,
            0,
            ((total - 1) & 0xff) as u8,
            ((total - 1) >> 8) as u8,
        ]);
        debug_assert!(total >= HEADER_BYTES + FOOTER_BYTES);
        self.inner.write_all(&self.block)?;
        self.written += total as u64;
        self.pending.clear();
        Ok(())
    }

    /// Flushes the last block, writes the EOF block and returns the inner writer.
    ///
    /// # Errors
    /// Returns the inner writer's error, or a deflate failure.
    pub fn finish(mut self) -> io::Result<W> {
        self.flush_block()?;
        self.inner.write_all(&EOF_BLOCK)?;
        self.inner.flush()?;
        Ok(self.inner)
    }
}

impl<W: Write> Write for BgzfWriter<W> {
    fn write(&mut self, mut buf: &[u8]) -> io::Result<usize> {
        let total = buf.len();
        while !buf.is_empty() {
            let room = BLOCK_INPUT_BYTES - self.pending.len();
            let take = room.min(buf.len());
            self.pending.extend_from_slice(&buf[..take]);
            buf = &buf[take..];
            if self.pending.len() == BLOCK_INPUT_BYTES {
                self.flush_block()?;
            }
        }
        Ok(total)
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
