package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

/**
 * Stable enumeration of failure reasons for Splunk/Elastic consumption.
 *
 * <p>{@link #rejectsRow()} tells whether a record with this reason means the row was dropped, or only that something about a kept row was
 * noted.
 */
public enum FailureReason {
    MISSING_PATIENT_ID("Patient ID column missing or null", true), INVALID_PATIENT_ID(
        "Patient ID not an integer", true
    ), MISSING_CONCEPT_PATH("Concept path missing or empty", true), MISSING_VALUE(
        "Both numeric and text values are null", true
    ), NUMERIC_PARSE_ERROR("Numeric value unparseable or not finite", false), BOTH_VALUES_TEXT_DISCARDED(
        "Row has both a numeric and a text value; the numeric value was kept and the text value discarded", false
    ), TIMESTAMP_PARSE_ERROR("Timestamp present but unparseable; the row was kept without a timestamp", false), MALFORMED_RECORD(
        "Record has too few columns", true
    ), FILE_READ_ERROR("Unable to read source file", true), UNKNOWN("Unknown failure", true);

    private final String description;
    private final boolean rejectsRow;

    FailureReason(String description, boolean rejectsRow) {
        this.description = description;
        this.rejectsRow = rejectsRow;
    }

    public String getDescription() {
        return description;
    }

    /** True when a record with this reason stands for a dropped row; false when the row was kept (see the detail text). */
    public boolean rejectsRow() {
        return rejectsRow;
    }
}
