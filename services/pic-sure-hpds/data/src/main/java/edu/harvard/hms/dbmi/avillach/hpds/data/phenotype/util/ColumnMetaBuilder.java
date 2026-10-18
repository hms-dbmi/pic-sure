package edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.util;

import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.ColumnMeta;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.KeyAndValue;
import edu.harvard.hms.dbmi.avillach.hpds.data.phenotype.PhenoCube;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Shared utility for building ColumnMeta from PhenoCube.
 *
 * <p>Provides consistent metadata generation across: <ul> <li>Normal ingestion (the PICSURELoader spooling writer)</li> <li>Metadata
 * rebuild</li> <li>Data recovery operations</li> </ul>
 *
 * <p>This ensures that rebuilt metadata exactly matches originally ingested metadata.
 */
public class ColumnMetaBuilder {

    /**
     * Build ColumnMeta from PhenoCube with optional widthInBytes override.
     *
     * <p>This method extracts all metadata from the cube including: <ul> <li>Observation and patient counts</li> <li>Category values (for
     * categorical concepts)</li> <li>Min/max values (for numeric concepts)</li> <li>Width in bytes (calculated or provided)</li> </ul>
     *
     * <p><b>Note</b>: This method does NOT set allObservationsOffset/Length. Those must be set by the caller based on file position.
     *
     * @param cube The PhenoCube to extract metadata from
     * @param widthInBytes Optional width override (null = calculate from data). For ingestion, pass ConceptMetadata.columnWidth. For
     *        rebuild, pass null to recalculate.
     * @return ColumnMeta with all fields populated except offsets/lengths
     */
    @SuppressWarnings("unchecked")
    public static ColumnMeta fromPhenoCube(PhenoCube<?> cube, Integer widthInBytes) {
        String conceptPath = cube.name;
        boolean isCategorical = cube.isStringType();

        ColumnMeta meta = new ColumnMeta().setName(conceptPath).setCategorical(isCategorical);

        // Count observations
        KeyAndValue<?>[] observations = cube.sortedByKey();
        meta.setObservationCount(observations.length);

        // Count unique patients
        Set<Integer> uniquePatients = Arrays.stream(observations).map(KeyAndValue::getKey).collect(Collectors.toSet());
        meta.setPatientCount(uniquePatients.size());

        // Extract category values or min/max based on type
        if (isCategorical) {
            // Extract unique categorical values (null-safe, sorted)
            TreeSet<String> uniqueValues = new TreeSet<>();
            for (Object value : cube.keyBasedArray()) {
                if (value != null) {
                    uniqueValues.add(value.toString());
                }
            }
            List<String> categoryValues = new ArrayList<>(uniqueValues);
            meta.setCategoryValues(categoryValues);

            // Calculate width: longest category value
            if (widthInBytes != null) {
                meta.setWidthInBytes(widthInBytes);
            } else {
                int maxWidth = categoryValues.stream().mapToInt(String::length).max().orElse(1);
                meta.setWidthInBytes(maxWidth);
            }

        } else {
            // Extract min/max for numeric. Track whether any value was seen instead of using
            // Double.MIN_VALUE as a "max" seed: that is the smallest POSITIVE double, so an all-negative
            // concept would otherwise report a NaN max.
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            boolean sawValue = false;
            for (Object value : cube.keyBasedArray()) {
                if (value instanceof Double d && !d.isNaN()) {
                    sawValue = true;
                    min = Math.min(min, d);
                    max = Math.max(max, d);
                }
            }
            meta.setMin(sawValue ? min : Double.NaN);
            meta.setMax(sawValue ? max : Double.NaN);

            // Numeric width is always 1 (stored as Double)
            meta.setWidthInBytes(widthInBytes != null ? widthInBytes : 1);
        }

        // Track timestamp min/max
        Long timestampMin = null;
        Long timestampMax = null;
        boolean hasTimestamp = false;

        for (KeyAndValue<?> kv : observations) {
            Long ts = kv.getTimestamp();
            // Valid timestamp: non-null AND not Long.MIN_VALUE (default sentinel from KeyAndValue constructor)
            if (ts != null && ts != Long.MIN_VALUE) {
                hasTimestamp = true;
                if (timestampMin == null || ts < timestampMin) {
                    timestampMin = ts;
                }
                if (timestampMax == null || ts > timestampMax) {
                    timestampMax = ts;
                }
            }
        }

        meta.setHasTimestamp(hasTimestamp);
        if (hasTimestamp) {
            meta.setTimestampMin(timestampMin);
            meta.setTimestampMax(timestampMax);
        }

        return meta;
    }

    /**
     * Build ColumnMeta from PhenoCube, calculating width from data.
     *
     * <p>Convenience method for rebuild scenarios where width should be recalculated from actual data rather than using pre-computed value.
     *
     * @param cube The PhenoCube to extract metadata from
     * @return ColumnMeta with all fields populated except offsets/lengths
     */
    public static ColumnMeta fromPhenoCube(PhenoCube<?> cube) {
        return fromPhenoCube(cube, null);
    }

    /**
     * Write columnMeta.csv data dictionary file.
     *
     * <p>CSV columns: <ol> <li>name</li> <li>widthInBytes</li> <li>columnOffset</li> <li>isCategorical</li> <li>categoryValues
     * (µ-delimited)</li> <li>min</li> <li>max</li> <li>allObservationsOffset</li> <li>allObservationsLength</li> <li>observationCount</li>
     * <li>patientCount</li> <li>hasTimestamp</li> <li>timestampMin</li> <li>timestampMax</li> </ol>
     *
     * @param metadataMap TreeMap of ColumnMeta entries
     * @param csvPath Path to write CSV file
     * @throws IOException if writing fails
     */
    public static void writeColumnMetaCsv(TreeMap<String, ColumnMeta> metadataMap, Path csvPath) throws IOException {
        // Use RFC4180 format to ensure proper quoting of fields containing commas, quotes, or newlines
        CSVFormat format = CSVFormat.RFC4180;

        try (
            BufferedWriter writer =
                Files.newBufferedWriter(csvPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING); CSVPrinter printer =
                    new CSVPrinter(writer, format)
        ) {

            for (Map.Entry<String, ColumnMeta> entry : metadataMap.entrySet()) {
                ColumnMeta columnMeta = entry.getValue();
                Object[] columnMetaOut = new Object[14];

                // Build category values string (µ-delimited)
                StringBuilder categoryValuesStr = new StringBuilder();
                if (columnMeta.getCategoryValues() != null && !columnMeta.getCategoryValues().isEmpty()) {
                    AtomicInteger index = new AtomicInteger(1);
                    columnMeta.getCategoryValues().forEach(value -> {
                        categoryValuesStr.append(value);
                        if (index.get() != columnMeta.getCategoryValues().size()) {
                            categoryValuesStr.append("µ");
                        }
                        index.incrementAndGet();
                    });
                }

                columnMetaOut[0] = columnMeta.getName();
                columnMetaOut[1] = columnMeta.getWidthInBytes();
                columnMetaOut[2] = columnMeta.getColumnOffset();
                columnMetaOut[3] = columnMeta.isCategorical();
                columnMetaOut[4] = categoryValuesStr.toString();
                columnMetaOut[5] = columnMeta.getMin();
                columnMetaOut[6] = columnMeta.getMax();
                columnMetaOut[7] = columnMeta.getAllObservationsOffset();
                columnMetaOut[8] = columnMeta.getAllObservationsLength();
                columnMetaOut[9] = columnMeta.getObservationCount();
                columnMetaOut[10] = columnMeta.getPatientCount();
                columnMetaOut[11] = columnMeta.hasTimestamp();
                columnMetaOut[12] = columnMeta.getTimestampMin();
                columnMetaOut[13] = columnMeta.getTimestampMax();

                printer.printRecord(columnMetaOut);
            }
        }
    }
}
