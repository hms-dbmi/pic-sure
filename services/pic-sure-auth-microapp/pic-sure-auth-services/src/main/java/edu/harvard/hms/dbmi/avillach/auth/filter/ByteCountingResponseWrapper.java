package edu.harvard.hms.dbmi.avillach.auth.filter;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Response wrapper that counts the body bytes written through it and changes nothing else. <p> Every write goes straight to the wrapped
 * response, so the wrapper holds no body data and leaves headers, status, flushing and the moment of commit to the wrapped response. Bytes
 * written through {@link #getOutputStream()} are counted as written. Characters written through {@link #getWriter()} are counted as the
 * bytes they encode to in the response's character encoding. <p> The count is the body bytes handed to the container, before compression or
 * chunk framing. It is 0 when nothing was written, and 0 after {@code sendRedirect}, because the container discards anything buffered and
 * sends the redirect without a body. It is unknown, and {@link #getByteCount()} is empty, once {@code sendError} has been called, because
 * the container then writes an error body that never passes through the wrapper. Unknown wins over 0. <p> The wrapper cannot see three
 * cases, which the caller has to handle: a HEAD request, where the container discards the body after it was counted, an exception that
 * leaves the filter chain, after which the container writes the error response, and async handling, where the body is written after the
 * caller has read the count. <p> HPDS keeps a copy of this class with the same behavior, because the two services share no module that has
 * the servlet API.
 */
final class ByteCountingResponseWrapper extends HttpServletResponseWrapper {

    private static final int SCRATCH_BYTES = 1024;

    private final AtomicLong byteCount = new AtomicLong();
    private CountingOutputStream countingStream;
    private CountingPrintWriter countingWriter;
    private volatile boolean containerOwnsBody;
    private volatile boolean bodyDiscardedByRedirect;

    /**
     * @param response the response to write to
     */
    ByteCountingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    /**
     * @return empty once {@code sendError} has handed the body to the container and the size of what the client receives is unknown,
     *         otherwise 0 after {@code sendRedirect} or when nothing was written, otherwise the number of body bytes written through this
     *         wrapper so far
     */
    OptionalLong getByteCount() {
        if (containerOwnsBody) {
            return OptionalLong.empty();
        }
        if (bodyDiscardedByRedirect) {
            return OptionalLong.of(0);
        }
        return OptionalLong.of(byteCount.get());
    }

    /**
     * Sends the error through the wrapped response, then marks the count as unknown because the container writes the error body.
     *
     * @param sc the error status code
     * @throws IOException if the wrapped response fails to send the error
     */
    @Override
    public void sendError(int sc) throws IOException {
        super.sendError(sc);
        containerOwnsBody = true;
    }

    /**
     * Sends the error through the wrapped response, then marks the count as unknown because the container writes the error body.
     *
     * @param sc the error status code
     * @param msg the error message
     * @throws IOException if the wrapped response fails to send the error
     */
    @Override
    public void sendError(int sc, String msg) throws IOException {
        super.sendError(sc, msg);
        containerOwnsBody = true;
    }

    /**
     * Sends the redirect through the wrapped response, then fixes the count at 0 because the container discards anything buffered.
     *
     * @param location the redirect location
     * @throws IOException if the wrapped response fails to send the redirect
     */
    @Override
    public void sendRedirect(String location) throws IOException {
        super.sendRedirect(location);
        bodyDiscardedByRedirect = true;
    }

    /**
     * Asks the wrapped response for its stream on every call, so the wrapped response decides whether the call is allowed.
     *
     * @return a counting stream over the stream the wrapped response returned, reused while that stream stays the same object
     * @throws IOException if the wrapped response fails to provide its stream
     */
    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        ServletOutputStream target = super.getOutputStream();
        if (countingStream == null || countingStream.target != target) {
            countingStream = new CountingOutputStream(target);
        }
        return countingStream;
    }

    /**
     * Asks the wrapped response for its writer on every call, so the wrapped response decides whether the call is allowed.
     *
     * @return a counting writer over the writer the wrapped response returned, reused while that writer stays the same object
     * @throws IOException if the wrapped response fails to provide its writer
     */
    @Override
    public PrintWriter getWriter() throws IOException {
        PrintWriter target = super.getWriter();
        if (countingWriter == null || countingWriter.target != target) {
            countingWriter = new CountingPrintWriter(target, responseCharset());
        }
        return countingWriter;
    }

    /**
     * Resets the wrapped response and the count, since the body written so far is discarded.
     */
    @Override
    public void reset() {
        super.reset();
        discardCount();
    }

    /**
     * Resets the wrapped response's buffer and the count, since the body written so far is discarded.
     */
    @Override
    public void resetBuffer() {
        super.resetBuffer();
        discardCount();
    }

    private void discardCount() {
        byteCount.set(0);
        countingStream = null;
        countingWriter = null;
    }

    private Charset responseCharset() {
        String name = getCharacterEncoding();
        if (name == null) {
            return StandardCharsets.ISO_8859_1;
        }
        try {
            return Charset.forName(name);
        } catch (IllegalArgumentException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    /**
     * Output stream that passes every call to the wrapped stream and adds the bytes it accepted to the count.
     */
    private final class CountingOutputStream extends ServletOutputStream {

        private final ServletOutputStream target;

        private CountingOutputStream(ServletOutputStream target) {
            this.target = target;
        }

        @Override
        public void write(int b) throws IOException {
            target.write(b);
            byteCount.incrementAndGet();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            target.write(b, off, len);
            byteCount.addAndGet(len);
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }

        @Override
        public boolean isReady() {
            return target.isReady();
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            target.setWriteListener(writeListener);
        }
    }

    /**
     * Print writer that sends everything to the wrapped writer through a {@link CountingWriter} and reports the wrapped writer's errors.
     */
    private final class CountingPrintWriter extends PrintWriter {

        private final PrintWriter target;

        private CountingPrintWriter(PrintWriter target, Charset charset) {
            super(new CountingWriter(target, charset));
            this.target = target;
        }

        @Override
        public boolean checkError() {
            boolean ownError = super.checkError();
            return target.checkError() || ownError;
        }
    }

    /**
     * Writer that passes every call to the wrapped writer and adds the encoded size of the characters to the count. The characters are
     * encoded into a scratch buffer that is thrown away. A high surrogate at the end of one write is held and counted with the write that
     * completes the pair.
     */
    private final class CountingWriter extends Writer {

        private final Writer target;
        private final CharsetEncoder encoder;
        private final ByteBuffer scratch = ByteBuffer.allocate(SCRATCH_BYTES);
        private char heldHighSurrogate;

        private CountingWriter(Writer target, Charset charset) {
            this.target = target;
            this.encoder =
                charset.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        }

        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            target.write(cbuf, off, len);
            count(CharBuffer.wrap(cbuf, off, len));
        }

        @Override
        public void write(String str, int off, int len) throws IOException {
            target.write(str, off, len);
            count(CharBuffer.wrap(str, off, off + len));
        }

        @Override
        public void flush() throws IOException {
            target.flush();
        }

        @Override
        public void close() throws IOException {
            target.close();
        }

        private void count(CharBuffer written) {
            CharBuffer input = written;
            if (heldHighSurrogate != 0) {
                input = CharBuffer.allocate(written.remaining() + 1).put(heldHighSurrogate).put(written).flip();
                heldHighSurrogate = 0;
            }
            CoderResult result;
            do {
                result = encoder.encode(input, scratch, false);
                byteCount.addAndGet(scratch.position());
                scratch.clear();
            } while (result.isOverflow());
            if (input.hasRemaining()) {
                heldHighSurrogate = input.get();
            }
        }
    }
}
