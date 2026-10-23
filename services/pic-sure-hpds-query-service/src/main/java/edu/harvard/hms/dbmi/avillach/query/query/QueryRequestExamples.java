package edu.harvard.hms.dbmi.avillach.query.query;

/**
 * Request bodies for the OpenAPI examples of the query operations, one per expected result type. Each is a complete
 * {@link HpdsQueryRequest} that can be pasted into {@code curl -d '...'} or run from Swagger UI's "Try it out". The concept paths come from
 * the public Synthea dataset the all-in-one ships with; other environments substitute their own concept paths.
 */
public final class QueryRequestExamples {

    public static final String COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"COUNT\"}}";

    public static final String CROSS_COUNT =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"\\\\Synthea\\\\ACT Demographics\\\\Race\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"CROSS_COUNT\"}}";

    public static final String CATEGORICAL_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Race\\\\\"}]},\"genomicFilters\":[],\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}";

    public static final String CONTINUOUS_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\",\"min\":40,\"max\":60},\"genomicFilters\":[],\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}";

    public static final String OBSERVATION_CROSS_COUNT =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"OBSERVATION_CROSS_COUNT\"}}";

    public static final String INFO_COLUMN_LISTING =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"genomicFilters\":[],\"expectedResultType\":\"INFO_COLUMN_LISTING\"}}";

    public static final String VARIANT_COUNT_FOR_QUERY =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],\"expectedResultType\":\"VARIANT_COUNT_FOR_QUERY\"}}";

    public static final String VARIANT_LIST_FOR_QUERY =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],\"expectedResultType\":\"VARIANT_LIST_FOR_QUERY\"}}";

    public static final String VCF_EXCERPT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],\"expectedResultType\":\"VCF_EXCERPT\"}}";

    public static final String AGGREGATE_VCF_EXCERPT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],\"expectedResultType\":\"AGGREGATE_VCF_EXCERPT\"}}";

    public static final String PATIENTS =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"PATIENTS\"}}";

    public static final String DATAFRAME =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME\"}}";

    public static final String DATAFRAME_TIMESERIES =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME_TIMESERIES\"}}";

    public static final String DATAFRAME_PFB =
        "{\"query\":{\"select\":[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"\\\\Synthea\\\\ACT Demographics\\\\Age\\\\\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\",\"values\":[\"Female\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME_PFB\"}}";

    private QueryRequestExamples() {}
}
