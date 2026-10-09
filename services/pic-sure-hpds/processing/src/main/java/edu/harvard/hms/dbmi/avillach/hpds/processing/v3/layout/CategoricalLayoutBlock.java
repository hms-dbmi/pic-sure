package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import org.roaringbitmap.FastAggregation;
import org.roaringbitmap.RoaringBitmap;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A decoded categorical block: a dictionary of distinct values in Java {@code String.compareTo} order, one roaring bitmap of patients per
 * value, and the observations ordered by patient. Built once per cache load; every accessor is safe to call from many threads.
 */
public final class CategoricalLayoutBlock implements LayoutBlock {

    private final int size;

    private final byte[] payload;

    private final String[] dictionary;

    private final RoaringBitmap[] bitmaps;

    private final RoaringBitmap allPatients;

    private final IntBuffer patients;

    private final IntBuffer valueIndices;

    private final LongBuffer timestamps;

    private CategoricalLayoutBlock(
        int size, byte[] payload, String[] dictionary, RoaringBitmap[] bitmaps, IntBuffer patients, IntBuffer valueIndices,
        LongBuffer timestamps
    ) {
        this.size = size;
        this.payload = payload;
        this.dictionary = dictionary;
        this.bitmaps = bitmaps;
        this.patients = patients;
        this.valueIndices = valueIndices;
        this.timestamps = timestamps;
        this.allPatients = bitmaps.length == 0 ? new RoaringBitmap() : FastAggregation.or(bitmaps);
        this.allPatients.runOptimize();
    }

    /**
     * Decodes a categorical payload: header with {@code n} and {@code k}, {@code string_offsets u64[k+1]}, the dictionary bytes,
     * {@code bitmap_offsets u64[k+1]}, the serialized bitmaps, {@code patients u32[n]}, {@code value_index u32[n]}, and
     * {@code timestamps i64[n]} when the block has timestamps, each padded to 8 bytes.
     *
     * @param payload the decrypted, decompressed payload; kept and viewed in place
     * @param path the concept path, used in error messages
     * @return the decoded block
     * @throws LayoutFormatException when the payload is truncated, has trailing bytes, or has inconsistent offsets
     */
    static CategoricalLayoutBlock decode(byte[] payload, String path) {
        String what = "categorical block of " + path;
        LittleEndianReader reader = new LittleEndianReader(payload, what);
        long n = reader.u32();
        int hasTimestamp = reader.u8();
        reader.u8();
        reader.skip(2);
        long k = reader.u32();
        reader.skip(4);
        if (hasTimestamp > 1 || n > Integer.MAX_VALUE || k >= Integer.MAX_VALUE / 8) {
            throw new LayoutFormatException(what + " has a bad header");
        }
        int entries = (int) k + 1;
        LongBuffer stringOffsets = NumericLayoutBlock.view(payload, reader.alignedArray(entries, 8), entries * 8).asLongBuffer();
        long stringLength = stringOffsets.get(entries - 1);
        int stringsAt = reader.alignedArray(stringLength, 1);
        LongBuffer bitmapOffsets = NumericLayoutBlock.view(payload, reader.alignedArray(entries, 8), entries * 8).asLongBuffer();
        long bitmapLength = bitmapOffsets.get(entries - 1);
        int bitmapsAt = reader.alignedArray(bitmapLength, 1);
        int count = (int) n;
        int patientsAt = reader.alignedArray(n, 4);
        int valueIndicesAt = reader.alignedArray(n, 4);
        int timestampsAt = hasTimestamp == 1 ? reader.alignedArray(n, 8) : -1;
        reader.finish();

        String[] dictionary = new String[(int) k];
        RoaringBitmap[] bitmaps = new RoaringBitmap[(int) k];
        for (int i = 0; i < k; i++) {
            long stringStart = stringOffsets.get(i);
            long stringEnd = stringOffsets.get(i + 1);
            long bitmapStart = bitmapOffsets.get(i);
            long bitmapEnd = bitmapOffsets.get(i + 1);
            if (stringStart > stringEnd || stringEnd > stringLength || bitmapStart > bitmapEnd || bitmapEnd > bitmapLength) {
                throw new LayoutFormatException(what + " has inconsistent offsets");
            }
            dictionary[i] = new String(payload, stringsAt + (int) stringStart, (int) (stringEnd - stringStart), StandardCharsets.UTF_8);
            bitmaps[i] = LayoutIndex
                .deserializeBitmap(payload, bitmapsAt + (int) bitmapStart, (int) (bitmapEnd - bitmapStart), "bitmap " + i + " of " + what);
        }
        if (k > 0 && (stringOffsets.get(0) != 0 || bitmapOffsets.get(0) != 0)) {
            throw new LayoutFormatException(what + " has inconsistent offsets");
        }
        IntBuffer valueIndices = NumericLayoutBlock.view(payload, valueIndicesAt, count * 4).asIntBuffer();
        for (int i = 0; i < count; i++) {
            int index = valueIndices.get(i);
            if (index < 0 || index >= k) {
                throw new LayoutFormatException(what + " has a value index out of range");
            }
        }
        return new CategoricalLayoutBlock(
            count, payload, dictionary, bitmaps, NumericLayoutBlock.view(payload, patientsAt, count * 4).asIntBuffer(), valueIndices,
            timestampsAt < 0 ? null : NumericLayoutBlock.view(payload, timestampsAt, count * 8).asLongBuffer()
        );
    }

    @Override
    public int size() {
        return size;
    }

    /** @return the distinct values in Java {@code String.compareTo} order */
    public List<String> dictionary() {
        return Arrays.asList(dictionary.clone());
    }

    /**
     * @param value a category value
     * @return its dictionary index, or a negative number when the block does not hold it
     */
    public int find(String value) {
        return Arrays.binarySearch(dictionary, value);
    }

    /**
     * @param index a dictionary index
     * @return the value at that index
     */
    public String value(int index) {
        return dictionary[index];
    }

    /**
     * @param index a dictionary index
     * @return the patients holding that value; shared, callers must not modify it
     */
    public RoaringBitmap bitmap(int index) {
        return bitmaps[index];
    }

    /** @return the number of distinct values */
    public int dictionarySize() {
        return dictionary.length;
    }

    /**
     * Patients holding at least one of {@code values}, by exact match. Values the block does not hold contribute nothing.
     *
     * @param values category values
     * @return a new bitmap
     */
    public RoaringBitmap patientsWithValues(Collection<String> values) {
        RoaringBitmap result = new RoaringBitmap();
        for (String value : values) {
            int index = find(value);
            if (index >= 0) {
                result.or(bitmaps[index]);
            }
        }
        return result;
    }

    /** @return the patient of each observation, ordered by patient, then input order */
    public IntBuffer patients() {
        return patients.duplicate();
    }

    /** @return the dictionary index of each observation, parallel to {@link #patients()} */
    public IntBuffer valueIndices() {
        return valueIndices.duplicate();
    }

    /** @return the timestamp of each observation, parallel to {@link #patients()}, or null when the block has none */
    public LongBuffer timestamps() {
        return timestamps == null ? null : timestamps.duplicate();
    }

    @Override
    public RoaringBitmap allPatients() {
        return allPatients;
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
        long bytes = payload.length + allPatients.getLongSizeInBytes() + 256;
        for (int i = 0; i < dictionary.length; i++) {
            bytes += 48 + 2L * dictionary[i].length() + bitmaps[i].getLongSizeInBytes();
        }
        return bytes;
    }
}
