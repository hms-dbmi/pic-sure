//! A bound on how long a write to a connection may stay pending.

use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::task::{Context, Poll};
use std::time::Duration;

use hyper::rt::{Read, ReadBufCursor, Write};
use tokio::time::Sleep;

static CONNECTIONS_WRITE_STALLED: AtomicU64 = AtomicU64::new(0);

/// Connections this process closed because a write to them stayed pending for the whole
/// write stall bound ([`WriteStall`]).
pub fn connections_write_stalled() -> u64 {
    CONNECTIONS_WRITE_STALLED.load(Ordering::Relaxed)
}

/// A connection whose writes and flushes fail with [`io::ErrorKind::TimedOut`] once one has
/// stayed pending for `stall`: the client has read nothing for that long, so its socket
/// buffer stayed full. The failure ends the connection, which frees what its response holds
/// and closes the socket, instead of leaving it established for as long as the client
/// stays silent. Reads pass through unchanged, so an idle keep-alive connection, which
/// writes nothing, is left to the header read timeout.
pub struct WriteStall<T> {
    inner: T,
    stall: Duration,
    pending: Option<Pin<Box<Sleep>>>,
}

impl<T> WriteStall<T> {
    /// Wraps `inner`, failing its writes after `stall` pending.
    pub fn new(inner: T, stall: Duration) -> Self {
        WriteStall {
            inner,
            stall,
            pending: None,
        }
    }

    /// Passes a ready result through and forgets the pending timer; on a pending one, starts
    /// the timer if it is not running and fails once it has run out.
    fn watch<R>(
        &mut self,
        cx: &mut Context<'_>,
        result: Poll<io::Result<R>>,
    ) -> Poll<io::Result<R>> {
        if result.is_ready() {
            self.pending = None;
            return result;
        }
        let stall = self.stall;
        let timer = self
            .pending
            .get_or_insert_with(|| Box::pin(tokio::time::sleep(stall)));
        if timer.as_mut().poll(cx).is_pending() {
            return Poll::Pending;
        }
        self.pending = None;
        CONNECTIONS_WRITE_STALLED.fetch_add(1, Ordering::Relaxed);
        tracing::warn!(
            stall_seconds = stall.as_secs(),
            "closing a connection whose client read nothing within the write stall bound"
        );
        Poll::Ready(Err(io::Error::new(
            io::ErrorKind::TimedOut,
            "the client read nothing within the write stall bound",
        )))
    }
}

impl<T: Read + Unpin> Read for WriteStall<T> {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: ReadBufCursor<'_>,
    ) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl<T: Write + Unpin> Write for WriteStall<T> {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let result = Pin::new(&mut this.inner).poll_write(cx, buf);
        this.watch(cx, result)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        let result = Pin::new(&mut this.inner).poll_flush(cx);
        this.watch(cx, result)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }

    fn is_write_vectored(&self) -> bool {
        self.inner.is_write_vectored()
    }

    fn poll_write_vectored(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        bufs: &[io::IoSlice<'_>],
    ) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        let result = Pin::new(&mut this.inner).poll_write_vectored(cx, bufs);
        this.watch(cx, result)
    }
}
