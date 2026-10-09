package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import org.roaringbitmap.RoaringBitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * A decoded numeric block. {@link #values()} is sorted by value in Java {@code Double.compare} order, and {@link #patients()} is parallel
 * to it, so a range filter is two binary searches and a slice. Built once per cache load; every accessor is safe to call from many threads.
 */
public final class NumericLayoutBlock implements LayoutBlock {

    private final int size;

    private final byte[] payload;

    private final DoubleBuffer values;

    private final IntBuffer patients;

    private final LongBuffer timestamps;

    private final IntBuffer byKey;

    private final RoaringBitmap allPatients;

    private NumericLayoutBlock(int size, byte[] payload, DoubleBuffer values, IntBuffer patients, LongBuffer timestamps, IntBuffer byKey) {
        this.size = size;
        this.payload = payload;
        this.values = values;
        this.patients = patients;
        this.timestamps = timestamps;
        this.byKey = byKey;
        int[] all = new int[size];
        patients.get(0, all);
        this.allPatients = RoaringBitmap.bitmapOfUnordered(all);
        this.allPatients.runOptimize();
    }

    /**
     * Decodes a numeric payload: header, then {@code values f64[n]}, {@code patients u32[n]}, {@code timestamps i64[n]} when the block has
     * timestamps, and {@code by_key u32[n]}, each padded to 8 bytes.
     *
     * @param payload the decrypted, decompressed payload; kept and viewed in place
     * @param path the concept path, used in error messages
     * @return the decoded block
     * @throws LayoutFormatException when the payload is truncated, has trailing bytes, or has a bad header
     */
    static NumericLayoutBlock decode(byte[] payload, String path) {
        String what = "numeric block of " + path;
        LittleEndianReader reader = new LittleEndianReader(payload, what);
        long n = reader.u32();
        int hasTimestamp = reader.u8();
        reader.u8();
        reader.skip(2);
        if (hasTimestamp > 1 || n > Integer.MAX_VALUE) {
            throw new LayoutFormatException(what + " has a bad header");
        }
        int valuesAt = reader.alignedArray(n, 8);
        int patientsAt = reader.alignedArray(n, 4);
        int timestampsAt = hasTimestamp == 1 ? reader.alignedArray(n, 8) : -1;
        int byKeyAt = reader.alignedArray(n, 4);
        reader.finish();
        int count = (int) n;
        return new NumericLayoutBlock(
            count, payload, view(payload, valuesAt, count * 8).asDoubleBuffer(), view(payload, patientsAt, count * 4).asIntBuffer(),
            timestampsAt < 0 ? null : view(payload, timestampsAt, count * 8).asLongBuffer(), view(payload, byKeyAt, count * 4).asIntBuffer()
        );
    }

    static ByteBuffer view(byte[] payload, int offset, int length) {
        return ByteBuffer.wrap(payload, offset, length).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public int size() {
        return size;
    }

    /** @return values in Java {@code Double.compare} order; positions in this buffer are value positions */
    public DoubleBuffer values() {
        return values.duplicate();
    }

    /** @return the patient of each value position */
    public IntBuffer patients() {
        return patients.duplicate();
    }

    /** @return the timestamp of each value position, or null when the block has none */
    public LongBuffer timestamps() {
        return timestamps == null ? null : timestamps.duplicate();
    }

    /** @return value positions ordered by patient, then input order */
    public IntBuffer byKey() {
        return byKey.duplicate();
    }

    @Override
    public RoaringBitmap allPatients() {
        return allPatients;
    }

    /**
     * The first value position whose value is not below {@code min} in {@code Double.compare} order.
     *
     * @param min the inclusive lower bound
     * @return a position in {@code [0, size]}
     */
    public int lowerBound(double min) {
        int low = 0;
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Double.compare(values.get(mid), min) < 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * The first value position whose value is above {@code max} in {@code Double.compare} order.
     *
     * @param max the inclusive upper bound
     * @return a position in {@code [0, size]}
     */
    public int upperBound(double max) {
        int low = 0;
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (Double.compare(values.get(mid), max) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Patients with at least one value {@code v} such that {@code min <= v <= max} in {@code Double.compare} order. A null bound is open.
     * When {@code min} is above {@code max} the result is empty.
     *
     * @param min inclusive lower bound, or null
     * @param max inclusive upper bound, or null
     * @return the patients; the shared {@link #allPatients()} when every value matches, otherwise a new bitmap
     */
    public RoaringBitmap patientsInRange(Double min, Double max) {
        int start = min == null ? 0 : lowerBound(min);
        int end = max == null ? size : upperBound(max);
        if (end <= start) {
            return new RoaringBitmap();
        }
        if (start == 0 && end == size) {
            return allPatients;
        }
        int[] slice = new int[end - start];
        patients.get(start, slice);
        return RoaringBitmap.bitmapOfUnordered(slice);
    }

    @Override
    public long observationsIn(RoaringBitmap cohort) {
        long count = 0;
        for (int i = 0; i < size; i++) {
            if (cohort.contains(patients.get(i))) {
                count++;
            }
        }
        return count;
    }

    @Override
    public long heapBytes() {
        return payload.length + allPatients.getLongSizeInBytes() + 256;
    }
}
