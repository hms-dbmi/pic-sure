package edu.harvard.hms.dbmi.avillach.query.aggregate;

/**
 * Request bodies for the OpenAPI examples of the open access query, one per result type open access supports. Each is a complete
 * {@link OpenQueryRequest} that runs against the public Synthea dataset the all-in-one ships with, and carries no genomic filters because
 * open access does not accept them.
 */
public final class OpenQueryExamples {

    /** An open access COUNT: phenotypic filters only, no genomic filters. */
    public static final String COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"expectedResultType\":\"COUNT\"}}";

    /** An open access CROSS_COUNT: phenotypic filters only, no genomic filters. */
    public static final String CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"expectedResultType\":\"CROSS_COUNT\"}}";

    /** An open access CATEGORICAL_CROSS_COUNT: phenotypic filters only, no genomic filters. */
    public static final String CATEGORICAL_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Race\\\\\"}]},\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}";

    /** An open access CONTINUOUS_CROSS_COUNT: phenotypic filters only, no genomic filters. */
    public static final String CONTINUOUS_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\",\"min\":40,\"max\":60},\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}";

    /** An open access OBSERVATION_CROSS_COUNT: phenotypic filters only, no genomic filters. */
    public static final String OBSERVATION_CROSS_COUNT =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"expectedResultType\":\"OBSERVATION_CROSS_COUNT\"}}";

    private OpenQueryExamples() {}
}
