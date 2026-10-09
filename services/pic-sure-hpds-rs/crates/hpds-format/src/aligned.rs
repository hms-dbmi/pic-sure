/// An owned byte buffer whose start is 8-byte aligned, so any 8-byte-aligned range inside
/// it can be viewed as `&[u64]`, `&[f64]` or `&[i64]` without copying.
#[derive(Clone, Default, PartialEq, Eq)]
pub struct AlignedBuf {
    words: Vec<u64>,
    len: usize,
}

impl std::fmt::Debug for AlignedBuf {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("AlignedBuf")
            .field("len", &self.len)
            .finish()
    }
}

impl AlignedBuf {
    /// A zero-filled buffer of `len` bytes.
    pub fn zeroed(len: usize) -> Self {
        AlignedBuf {
            words: vec![0u64; len.div_ceil(8)],
            len,
        }
    }

    /// A buffer holding a copy of `bytes`.
    pub fn copy_from(bytes: &[u8]) -> Self {
        let mut buf = Self::zeroed(bytes.len());
        buf.as_bytes_mut().copy_from_slice(bytes);
        buf
    }

    /// Length in bytes.
    pub fn len(&self) -> usize {
        self.len
    }

    /// True when the buffer holds no bytes.
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// The buffer contents.
    pub fn as_bytes(&self) -> &[u8] {
        &bytemuck::cast_slice::<u64, u8>(&self.words)[..self.len]
    }

    /// The buffer contents, mutable.
    pub fn as_bytes_mut(&mut self) -> &mut [u8] {
        &mut bytemuck::cast_slice_mut::<u64, u8>(&mut self.words)[..self.len]
    }
}
