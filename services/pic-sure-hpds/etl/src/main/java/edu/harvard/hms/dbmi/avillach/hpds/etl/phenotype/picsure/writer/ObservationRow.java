package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure.writer;

import java.time.Instant;

/**
 * Canonical internal record for all observation sources.
 *
 * This represents a single observation that will be ingested into HPDS. It mirrors one row of the allConcepts CSV, with the timestamp
 * already normalised to an {@link Instant}.
 */
public record ObservationRow(
    int patientNum, String conceptPath, Double numericValue, // nullable - only for numeric observations
    String textValue, // nullable - only for categorical observations
    Instant dateTime // timestamp from configured dataset column
) {
    /**
     * Validates that this observation has exactly one value type set.
     */
    public boolean isValid() {
        return (numericValue != null) != (textValue != null); // XOR
    }

    /**
     * Returns true if this is a categorical (text) observation.
     */
    public boolean isCategorical() {
        return textValue != null;
    }

    /**
     * Returns true if this is a numeric observation.
     */
    public boolean isNumeric() {
        return numericValue != null;
    }

    /**
     * Gets the value as a Comparable for use with PhenoCube. Returns either String or Double.
     */
    @SuppressWarnings("unchecked")
    public <V extends Comparable<V>> V getValue() {
        if (isCategorical()) {
            return (V) textValue;
        } else {
            return (V) numericValue;
        }
    }
}
