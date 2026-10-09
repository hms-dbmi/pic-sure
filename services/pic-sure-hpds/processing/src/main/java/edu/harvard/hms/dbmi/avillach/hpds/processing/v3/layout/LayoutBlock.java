package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import org.roaringbitmap.RoaringBitmap;

/**
 * A decoded block of one concept's observations. The typed arrays are little-endian views over the decoded payload, with no per-element
 * boxing.
 */
public sealed interface LayoutBlock permits NumericLayoutBlock, CategoricalLayoutBlock {

    /** Value of the kind byte at offset 5 of a numeric payload. */
    int KIND_NUMERIC = 0;

    /** Value of the kind byte at offset 5 of a categorical payload. */
    int KIND_CATEGORICAL = 1;

    /** @return the number of observations */
    int size();

    /** @return every patient with at least one observation; shared, callers must not modify it */
    RoaringBitmap allPatients();

    /**
     * @param cohort a patient set
     * @return the number of observations whose patient is in {@code cohort}
     */
    long observationsIn(RoaringBitmap cohort);

    /** @return approximate heap bytes held, for the byte-bounded block cache */
    long heapBytes();

    /**
     * Decodes a payload by its kind byte.
     *
     * @param payload the decrypted, decompressed payload
     * @param path the concept path, used in error messages
     * @return the decoded block
     * @throws LayoutFormatException when the payload is corrupt
     */
    static LayoutBlock decode(byte[] payload, String path) {
        if (payload.length < 8) {
            throw new LayoutFormatException("block of " + path + " is truncated");
        }
        return switch (payload[5]) {
            case KIND_NUMERIC -> NumericLayoutBlock.decode(payload, path);
            case KIND_CATEGORICAL -> CategoricalLayoutBlock.decode(payload, path);
            default -> throw new LayoutFormatException("block of " + path + " has unknown kind " + payload[5]);
        };
    }
}
