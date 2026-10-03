package edu.harvard.hms.dbmi.avillach.query.query;

/**
 * OpenAPI example bodies shared by the generic and the open synchronous query endpoints. Public only so that the aggregate package can
 * reference the same text; not part of any wire contract.
 */
public final class SyncExamples {

    public static final String VARIANT_COUNT_WITH_GENOMIC_FILTERS = "{\"count\":17,\"message\":\"Query ran successfully\"}";

    public static final String VARIANT_COUNT_WITHOUT_GENOMIC_FILTERS =
        "{\"count\":\"0\",\"message\":\"No variant filters were supplied, so no query was run.\"}";

    private SyncExamples() {}
}
