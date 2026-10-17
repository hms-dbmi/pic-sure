package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import java.io.Serializable;
import java.util.Objects;

/**
 * Immutable fingerprint for observation uniqueness checking.
 *
 * Represents the unique key for an observation: (patientNum, conceptPath, value, timestamp)
 *
 * The spooling store builds one per observation while merging a concept's spooled partials, to drop exact duplicates (same patient, value
 * and timestamp) from the final cube.
 */
public record ObservationFingerprint(
    int patientNum, String conceptPath, String valueFingerprint, // normalized string representation (numeric or categorical)
    long timestampEpochMillis
) implements Serializable {

    /**
     * Create fingerprint from ObservationRow.
     *
     * Normalizes numeric values to consistent precision (10 decimal places) to ensure "12.3" and "12.30" are treated as the same
     * observation.
     */
    public static ObservationFingerprint from(ObservationRow row) {
        String valueFp;
        if (row.isCategorical()) {
            valueFp = row.textValue();
        } else {
            // Normalize numeric values to consistent precision
            valueFp = String.format("%.10f", row.numericValue());
        }

        long timestamp = row.dateTime() != null ? row.dateTime().toEpochMilli() : 0L;

        return new ObservationFingerprint(row.patientNum(), row.conceptPath(), valueFp, timestamp);
    }

    /**
     * Custom hash code using all fields for better distribution. Uses standard Objects.hash().
     */
    @Override
    public int hashCode() {
        return Objects.hash(patientNum, conceptPath, valueFingerprint, timestampEpochMillis);
    }

    /**
     * Equality check for deduplication. Two observations are duplicates if all four fields match exactly.
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ObservationFingerprint other)) return false;
        return patientNum == other.patientNum && timestampEpochMillis == other.timestampEpochMillis
            && Objects.equals(conceptPath, other.conceptPath) && Objects.equals(valueFingerprint, other.valueFingerprint);
    }
}
