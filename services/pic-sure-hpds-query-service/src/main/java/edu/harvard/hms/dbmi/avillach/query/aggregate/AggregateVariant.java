package edu.harvard.hms.dbmi.avillach.query.aggregate;

/**
 * Carries the two aggregate-surface details the legacy WAR resources parameterized: the query field name used to carry the study-consents
 * allow-list injected by {@code changeQueryToOpenCrossCount} ({@code select} in v3), and the downstream path prefix applied ONLY to the
 * {@code query/sync} and {@code bin/continuous} calls (the v3 WAR's {@code getHttpResponse} hardcoded a {@code "/v3"} prefix).
 *
 * <p>The {@code V1} constant ({@code crossCountFields}, no prefix) died with the v1 aggregate ingress: nothing dispatches it any more, and
 * the HPDS endpoints it pointed at ({@code /PIC-SURE/query/sync}, unversioned {@code /bin/continuous}) no longer exist.
 *
 * TODO(well-defined-contracts): Task 10 retypes the aggregate internals; a single-variant enum should collapse into the call sites then.
 */
public enum AggregateVariant {
    V3("select", "/v3");

    public final String consentsField;

    /** Prepended to the downstream path for query/sync + bin/continuous ONLY. */
    public final String downstreamVersionPrefix;

    AggregateVariant(String consentsField, String downstreamVersionPrefix) {
        this.consentsField = consentsField;
        this.downstreamVersionPrefix = downstreamVersionPrefix;
    }
}
