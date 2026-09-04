package edu.harvard.hms.dbmi.avillach.query.aggregate;

/**
 * Captures the two differences between v1 and v3 aggregate requests: the query field carrying the study-consents allow-list
 * ({@code crossCountFields} for v1 and {@code select} for v3), and the downstream path prefix applied only to {@code query/sync} and
 * {@code bin/continuous} calls.
 */
public enum AggregateVariant {
    V1("crossCountFields", ""), V3("select", "/v3");

    public final String consentsField;

    /** Prepended to the downstream path for query/sync + bin/continuous ONLY. */
    public final String downstreamVersionPrefix;

    AggregateVariant(String consentsField, String downstreamVersionPrefix) {
        this.consentsField = consentsField;
        this.downstreamVersionPrefix = downstreamVersionPrefix;
    }
}
