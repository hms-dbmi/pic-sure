package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper that counts the body bytes written through it and changes nothing else. Every write goes straight to the wrapped
 * response, so the body is never held here, and headers, status, flushing and commit timing are those of the wrapped response. <p> Bytes
 * written through {@link #getOutputStream()} are counted exactly. Characters written through {@link #getWriter()} are counted as the number
 * of bytes they encode to in the response's character encoding. The count is the body as written to the response, before any compression or
 * transfer encoding the container applies, and it does not include a body the container writes outside this wrapper, such as an error page
 * rendered after {@code sendError}.
 */
public final class ByteCountingResponseWrapper extends HttpServletResponseWrapper {

    private final AtomicLong byteCount = new AtomicLong();
    private CountingOutputStream outputStream;
    private CountingPrintWriter writer;

    /**
     * Wraps a response so the bytes written to its body can be read back with {@link #getByteCount()}.
     *
     * @param response the response every call is passed on to
     */
    public ByteCountingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    /**
     * Returns the number of body bytes written so far.
     *
     * @return the byte count, 0 when nothing has been written
     */
    public long getByteCount() {
        return byteCount.get();
    }

    /**
     * Returns a stream that passes every call to the wrapped response's stream and counts the bytes it accepts.
     *
     * @return the counting stream, the same instance on every call until the response is reset
     * @throws IOException if the wrapped response cannot provide its stream
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (outputStream == null) {
            outputStream = new CountingOutputStream(super.getOutputStream(), byteCount);
        }
        return outputStream;
    }

    /**
     * Returns a writer that passes every call to the wrapped response's writer and counts the bytes the characters encode to.
     *
     * @return the counting writer, the same instance on every call until the response is reset
     * @throws IOException if the wrapped response cannot provide its writer
     */
    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            PrintWriter delegate = super.getWriter();
            writer = new CountingPrintWriter(delegate, new EncodedLengthCounter(resolveCharset(getCharacterEncoding()), byteCount));
        }
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
        byteCount.set(0);
        outputStream = null;
        writer = null;
    }

    private static Charset resolveCharset(String name) {
        if (name == null) {
            return StandardCharsets.ISO_8859_1;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /** Stream that forwards to the wrapped stream and adds each accepted write to the shared count. */
    private static final class CountingOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;
        private final AtomicLong byteCount;

        private CountingOutputStream(ServletOutputStream delegate, AtomicLong byteCount) {
            this.delegate = delegate;
            this.byteCount = byteCount;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            byteCount.incrementAndGet();
        }

        @Override
        public void write(byte[] b) throws IOException {
            delegate.write(b);
            byteCount.addAndGet(b.length);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            byteCount.addAndGet(len);
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

    /**
     * Writer that forwards characters to the wrapped response's writer unbuffered. The error state is the wrapped writer's, because that is
     * where a failed write to the client is recorded.
     */
    private static final class CountingPrintWriter extends PrintWriter {

        private final PrintWriter delegate;

        private CountingPrintWriter(PrintWriter delegate, EncodedLengthCounter counter) {
            super(new CountingWriter(delegate, counter));
            this.delegate = delegate;
        }

        @Override
        public boolean checkError() {
            return delegate.checkError();
        }
    }

    /** Passes characters to the wrapped writer, then counts the bytes they encode to. */
    private static final class CountingWriter extends Writer {

        private final PrintWriter delegate;
        private final EncodedLengthCounter counter;

        private CountingWriter(PrintWriter delegate, EncodedLengthCounter counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            delegate.write(cbuf, off, len);
            counter.count(CharBuffer.wrap(cbuf, off, len));
        }

        @Override
        public void write(String str, int off, int len) {
            delegate.write(str, off, len);
            counter.count(CharBuffer.wrap(str, off, off + len));
        }

        @Override
        public void write(int c) {
            delegate.write(c);
            counter.count(CharBuffer.wrap(new char[] {(char) c}));
        }

        @Override
        public void flush() {
            delegate.flush();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Measures how many bytes a run of characters encodes to without keeping the result. A high surrogate that ends one write is held back
     * and measured together with the low surrogate that starts the next one.
     */
    private static final class EncodedLengthCounter {

        private static final int SCRATCH_BYTES = 1024;

        private final CharsetEncoder encoder;
        private final AtomicLong byteCount;
        private final ByteBuffer scratch = ByteBuffer.allocate(SCRATCH_BYTES);
        private char heldHighSurrogate;
        private boolean holdingHighSurrogate;

        private EncodedLengthCounter(Charset charset, AtomicLong byteCount) {
            this.encoder =
                charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
            this.byteCount = byteCount;
        }

        private synchronized void count(CharBuffer chars) {
            long encoded = 0;
            while (holdingHighSurrogate && chars.hasRemaining()) {
                holdingHighSurrogate = false;
                encoded += encode(CharBuffer.wrap(new char[] {heldHighSurrogate, chars.get()}));
            }
            encoded += encode(chars);
            byteCount.addAndGet(encoded);
        }

        private long encode(CharBuffer input) {
            long encoded = 0;
            boolean scratchFull = true;
            while (scratchFull) {
                scratch.clear();
                scratchFull = encoder.encode(input, scratch, false).isOverflow();
                encoded += scratch.position();
            }
            if (input.hasRemaining()) {
                heldHighSurrogate = input.get();
                holdingHighSurrogate = true;
            }
            return encoded;
        }
    }
}
