package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper that counts the body bytes written through its output stream and changes nothing else. Every write goes straight to the
 * wrapped response, so the body is never held here, and headers, status, flushing and commit timing are those of the wrapped response. <p>
 * The count is the body bytes handed to the container through {@link #getOutputStream()}, before any compression or chunk framing the
 * container applies. <p> The count is not always what the client receives. {@link #isByteCountUnknown()} returns true, and the count says
 * nothing about the response, in two cases: after either {@code sendError} overload, where the container renders the body itself outside
 * this wrapper, and once {@link #getWriter()} has been called, because characters go to the wrapped response's own writer and are not
 * counted. After {@link #sendRedirect(String)} the container discards whatever was buffered, so the count is 0. A {@link #reset()} or
 * {@link #resetBuffer()} returns the count to 0 with the discarded body. <p> The wrapper is not thread-safe. It is written to and read on
 * the thread that handles the request. Once asynchronous processing has started another thread may still be writing the body, so a caller
 * must treat the count as unknown from then on.
 */
public final class ByteCountingResponseWrapper extends HttpServletResponseWrapper {

    private long byteCount;
    private CountingOutputStream outputStream;
    private boolean byteCountUnknown;
    private boolean bodyDiscardedByRedirect;

    /**
     * Wraps a response so the bytes written to its body can be read back with {@link #getByteCount()}.
     *
     * @param response the response every call is passed on to
     */
    public ByteCountingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    /**
     * Returns the number of body bytes written to the output stream so far. The value does not describe the response when
     * {@link #isByteCountUnknown()} is true.
     *
     * @return the byte count, 0 when nothing has been written or the response was redirected
     */
    public long getByteCount() {
        return bodyDiscardedByRedirect ? 0 : byteCount;
    }

    /**
     * Tells whether the body the client receives was written where this wrapper could not count it. That is so after {@code sendError},
     * which leaves the container to render the body later, and after {@link #getWriter()}, whose characters are not counted. It stays true
     * for the rest of the request.
     *
     * @return true when {@link #getByteCount()} is not the size of the body the client receives
     */
    public boolean isByteCountUnknown() {
        return byteCountUnknown;
    }

    /**
     * Passes the error to the wrapped response and marks the count as unknown, since the container now writes the body.
     *
     * @param sc the error status code
     * @throws IOException if the wrapped response fails to send the error
     * @throws IllegalStateException if the response is already committed, in which case the count still stands
     */
    @Override
    public void sendError(int sc) throws IOException {
        super.sendError(sc);
        byteCountUnknown = true;
    }

    /**
     * Passes the error to the wrapped response and marks the count as unknown, since the container now writes the body.
     *
     * @param sc the error status code
     * @param msg the error message
     * @throws IOException if the wrapped response fails to send the error
     * @throws IllegalStateException if the response is already committed, in which case the count still stands
     */
    @Override
    public void sendError(int sc, String msg) throws IOException {
        super.sendError(sc, msg);
        byteCountUnknown = true;
    }

    /**
     * Passes the redirect to the wrapped response and reports 0 bytes from then on, since the container discards the buffered body.
     *
     * @param location the redirect target
     * @throws IOException if the wrapped response fails to send the redirect
     * @throws IllegalStateException if the response is already committed, in which case the count still stands
     */
    @Override
    public void sendRedirect(String location) throws IOException {
        super.sendRedirect(location);
        bodyDiscardedByRedirect = true;
    }

    /**
     * Returns a stream that passes every call to the wrapped response's stream and counts the bytes it accepts.
     *
     * @return the counting stream, the same instance for as long as the wrapped response returns the same stream
     * @throws IOException if the wrapped response cannot provide its stream
     * @throws IllegalStateException if the wrapped response refuses, as it does once its writer has been obtained
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        ServletOutputStream delegate = super.getOutputStream();
        if (outputStream == null || outputStream.delegate != delegate) {
            outputStream = new CountingOutputStream(delegate);
        }
        return outputStream;
    }

    /**
     * Returns the wrapped response's own writer and marks the count as unknown, since what is written to it is not counted.
     *
     * @return the writer of the wrapped response
     * @throws IOException if the wrapped response cannot provide its writer
     * @throws IllegalStateException if the wrapped response refuses, as it does once its stream has been obtained, in which case the count
     *         still stands
     */
    @Override
    public PrintWriter getWriter() throws IOException {
        PrintWriter writer = super.getWriter();
        byteCountUnknown = true;
        return writer;
    }

    /**
     * Resets the wrapped response and the count, since the body written so far is discarded.
     *
     * @throws IllegalStateException if the response is already committed, in which case the count is kept
     */
    @Override
    public void reset() {
        super.reset();
        discardCountedBody();
    }

    /**
     * Clears the wrapped response's buffer and the count, since the body written so far is discarded.
     *
     * @throws IllegalStateException if the response is already committed, in which case the count is kept
     */
    @Override
    public void resetBuffer() {
        super.resetBuffer();
        discardCountedBody();
    }

    private void discardCountedBody() {
        byteCount = 0;
        outputStream = null;
    }

    /** Stream that forwards to the wrapped stream and adds each accepted write to the wrapper's count. */
    private final class CountingOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private CountingOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            byteCount++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            byteCount += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            byteCount += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            delegate.setWriteListener(writeListener);
        }
    }
}
