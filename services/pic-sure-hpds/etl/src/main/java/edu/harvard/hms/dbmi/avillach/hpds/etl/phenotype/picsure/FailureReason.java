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
    ), FILE_READ_ERROR("Unable to read source file", true), UNKNOWN("Unknown failure", true), NO_CONSENT(
        "The patient has no consent that covers the row (combined input); the row was not written to any partition", true
    ), INVALID_CONSENT_NAME(
        "Every consent the row was meant for has a name that cannot name a partition (combined input); the row was not written", true
    ), CONSENT_ROW_REJECTED(
        "A row of the consents variable breaks the contract of a consent row (combined input); it grants no consent and was not written",
        true
    ), INVALID_STUDY_NODE(
        "The study node (first node) of the concept path has leading or trailing whitespace or U+FFFD, so it changes when the loader "
            + "normalizes the path, or it is another spelling (case, blanks, U+FFFD) of a study some consent names (combined input); the "
            + "row cannot be routed safely and was not written",
        true
    );

    private final String description;
    private final boolean rejectsRow;

    FailureReason(String description, boolean rejectsRow) {
        this.description = description;
        this.rejectsRow = rejectsRow;
    }

    public String getDescription() {
        return description;
    }

    /**
     * True when {@code CSVLoader} would have stopped on such a row (a patient id or a number it cannot parse, or an unexpected error)
     * instead of skipping it. PICSURELoader fails the partition when more than {@code maxRejectedRows} of them are dropped. A timestamp it
     * cannot parse is the same kind of problem but the row is kept; it is limited by {@code maxTimestampFailures}.
     */
    public boolean crashesCsvLoader() {
        return switch (this) {
            case MISSING_PATIENT_ID, INVALID_PATIENT_ID, NUMERIC_PARSE_ERROR, UNKNOWN -> true;
            default -> false;
        };
    }

    /** True when a record with this reason stands for a dropped row; false when the row was kept (see the detail text). */
    public boolean rejectsRow() {
        return rejectsRow;
    }
}
