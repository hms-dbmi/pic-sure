package edu.harvard.hms.dbmi.avillach.query.aggregate;

/**
 * Captures the only two ways the v1 ({@code /aggregate-data-sharing}) and v3 ({@code /v3/aggregate-data-sharing}) WAR resources diverged:
 * the query field name used to carry the study-consents allow-list injected by {@code changeQueryToOpenCrossCount}
 * ({@code crossCountFields} for v1, {@code select} for v3), and the downstream path prefix applied ONLY to the {@code query/sync} and
 * {@code bin/continuous} calls (the v3 WAR's {@code getHttpResponse} hardcoded a {@code "/v3"} prefix; every other downstream call --
 * {@code /info}, {@code /search}, {@code /query}, {@code /query/{id}/status}, {@code /query/{id}/result}, {@code /query/format} -- stayed
 * unprefixed in both variants).
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
