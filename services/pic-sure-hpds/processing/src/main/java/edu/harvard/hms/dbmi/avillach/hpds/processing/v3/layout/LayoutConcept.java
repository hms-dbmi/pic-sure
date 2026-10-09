package edu.harvard.hms.dbmi.avillach.hpds.processing.v3.layout;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One entry of the {@code pheno.index} concept table: a concept's metadata and where its block lives in {@code pheno.blocks}.
 *
 * @param path the normalized concept path
 * @param categorical true for a categorical concept, false for a numeric one
 * @param blockOffset byte offset of the stored block in {@code pheno.blocks}
 * @param blockLength byte length of the stored block
 * @param observationCount number of observations
 * @param patientCount number of distinct patients
 * @param hasTimestamp true when at least one observation carries a timestamp
 * @param min smallest numeric value, null when absent
 * @param max largest numeric value, null when absent
 * @param timestampMin earliest timestamp, null when absent
 * @param timestampMax latest timestamp, null when absent
 * @param widthInBytes kept for {@code columnMeta.csv} parity
 * @param columnOffset kept for {@code columnMeta.csv} parity
 * @param categoryValues the categorical values in Java {@code String.compareTo} order, null when absent
 */
public record LayoutConcept(
    String path, boolean categorical, long blockOffset, long blockLength, long observationCount, long patientCount, boolean hasTimestamp,
    Double min, Double max, Long timestampMin, Long timestampMax, int widthInBytes, int columnOffset, List<String> categoryValues
) {

    /**
     * The largest decoded payload this concept's block may declare, the bound the Rust reader applies before allocating: the exact payload
     * size for its observation count, plus a quarter, plus 4096 bytes.
     *
     * @return the bound in bytes
     */
    public long maxPayloadLength() {
        long n = observationCount;
        long exact;
        if (categorical) {
            List<String> values = categoryValues == null ? List.of() : categoryValues;
            long k = values.size();
            long stringBytes = values.stream().mapToLong(v -> v.getBytes(StandardCharsets.UTF_8).length).sum();
            exact = 16 + 8 * (k + 1) + padded(stringBytes) + 8 * (k + 1) + padded(16 * k + 16 * n) + padded(4 * n) + padded(4 * n) + 8 * n;
        } else {
            exact = 8 + 8 * n + padded(4 * n) + 8 * n + padded(4 * n);
        }
        return exact + exact / 4 + 4096;
    }

    /**
     * Builds the {@link ColumnMeta} the javabin store would hold for this concept, for the dictionary that search, health and the cross
     * counts read. The observation offsets are the block's start and end in {@code pheno.blocks}. The timestamp bounds are left unset, as
     * no Java loader sets them.
     *
     * @return a new column meta
     */
    public ColumnMeta toColumnMeta() {
        ColumnMeta meta = new ColumnMeta().setName(path).setWidthInBytes(widthInBytes).setColumnOffset(columnOffset)
            .setCategorical(categorical).setAllObservationsOffset(blockOffset).setAllObservationsLength(blockOffset + blockLength)
            .setObservationCount((int) observationCount).setPatientCount((int) patientCount);
        if (min != null) {
            meta.setMin(min);
        }
        if (max != null) {
            meta.setMax(max);
        }
        if (categoryValues != null) {
            meta.setCategoryValues(categoryValues);
        }
        return meta;
    }

    private static long padded(long length) {
        return (length + 7) & ~7L;
    }
}
