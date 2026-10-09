package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * A bounds-checked little-endian cursor over a byte array, for the layout store's index and block payloads. Every read past the end throws
 * {@link LayoutFormatException} naming {@code what}.
 */
final class LittleEndianReader {

    private final ByteBuffer buffer;

    private final String what;

    /**
     * @param bytes the bytes to read; not copied
     * @param what a name for the structure being read, used in error messages
     */
    LittleEndianReader(byte[] bytes, String what) {
        this.buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        this.what = what;
    }

    /** @return the current offset from the start of the array */
    int position() {
        return buffer.position();
    }

    /** @return bytes left after the current offset */
    int remaining() {
        return buffer.remaining();
    }

    /**
     * Advances past {@code length} bytes and returns the offset they start at.
     *
     * @throws LayoutFormatException when fewer than {@code length} bytes remain
     */
    int skip(long length) {
        require(length);
        int start = buffer.position();
        buffer.position(start + (int) length);
        return start;
    }

    /**
     * Advances past {@code count} elements of {@code width} bytes and the zero padding to the next multiple of 8 from the start, and
     * returns the offset the elements start at.
     *
     * @throws LayoutFormatException when the array or its padding runs past the end
     */
    int alignedArray(long count, int width) {
        if (count < 0 || count > Integer.MAX_VALUE / width) {
            throw truncated();
        }
        int start = skip(count * width);
        int end = buffer.position();
        skip(((end + 7) & ~7) - end);
        return start;
    }

    /** @return the next byte, unsigned */
    int u8() {
        require(1);
        return buffer.get() & 0xff;
    }

    /** @return the next unsigned 16-bit integer */
    int u16() {
        require(2);
        return buffer.getShort() & 0xffff;
    }

    /** @return the next unsigned 32-bit integer */
    long u32() {
        require(4);
        return buffer.getInt() & 0xffffffffL;
    }

    /** @return the next signed 32-bit integer */
    int i32() {
        require(4);
        return buffer.getInt();
    }

    /** @return the next 64-bit integer */
    long i64() {
        require(8);
        return buffer.getLong();
    }

    /** @return the next 64-bit float */
    double f64() {
        require(8);
        return buffer.getDouble();
    }

    /** @return a copy of the next {@code length} bytes */
    byte[] bytes(long length) {
        require(length);
        byte[] out = new byte[(int) length];
        buffer.get(out);
        return out;
    }

    /** @return the next string: a u32 byte length followed by that many UTF-8 bytes */
    String string() {
        long length = u32();
        require(length);
        String value = new String(buffer.array(), buffer.position(), (int) length, StandardCharsets.UTF_8);
        buffer.position(buffer.position() + (int) length);
        return value;
    }

    /**
     * @throws LayoutFormatException unless every byte has been read
     */
    void finish() {
        if (buffer.hasRemaining()) {
            throw new LayoutFormatException(what + " has " + buffer.remaining() + " trailing bytes");
        }
    }

    private void require(long length) {
        if (length < 0 || length > buffer.remaining()) {
            throw truncated();
        }
    }

    private LayoutFormatException truncated() {
        return new LayoutFormatException(what + " is truncated");
    }
}
