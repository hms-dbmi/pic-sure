package edu.harvard.hms.dbmi.avillach.hpds.etl.phenotype.picsure;

/**
 * Stable enumeration of failure reasons for Splunk/Elastic consumption.
 */
public enum FailureReason {
    MISSING_PATIENT_ID("Patient ID column missing or null"), INVALID_PATIENT_ID("Patient ID not an integer"), MISSING_CONCEPT_PATH(
        "Concept path missing or empty"
    ), MISSING_VALUE("Both numeric and text values are null"), NUMERIC_PARSE_ERROR("Numeric value unparseable"), BOTH_VALUES_TEXT_DISCARDED(
        "Row has both a numeric and a text value; the numeric value was kept and the text value discarded"
    ), FILE_READ_ERROR("Unable to read source file"), UNKNOWN("Unknown failure");

    private final String description;

    FailureReason(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
