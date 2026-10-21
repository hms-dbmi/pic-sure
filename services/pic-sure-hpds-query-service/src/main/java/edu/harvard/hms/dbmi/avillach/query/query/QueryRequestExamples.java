package edu.harvard.hms.dbmi.avillach.query.query;

/**
 * Request bodies for the OpenAPI examples of the query operations, one per expected result type. Each is a complete
 * {@link HpdsQueryRequest} that can be pasted into {@code curl -d '...'} or run from Swagger UI's "Try it out". The concept paths, value
 * and gene are {@code picsure.openapi.examples} placeholders whose defaults exist in the all-in-one's public Synthea dataset; a deployment
 * whose data differs sets those keys, and the shared OpenAPI customizer resolves them when the document is built.
 */
public final class QueryRequestExamples {

    /** A categorical concept path the examples filter on, its default escaped for use inside a JSON string. */
    static final String CATEGORICAL_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.categorical-concept-path:\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\}";

    /** A value of the categorical concept, used as its filter value. */
    public static final String CATEGORICAL_VALUE = "${picsure.openapi.examples.categorical-value:Female}";

    /**
     * A second categorical concept path, used where an example selects or requires two, its default escaped for use inside a JSON string.
     */
    static final String SECOND_CATEGORICAL_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.second-categorical-concept-path:\\\\Synthea\\\\ACT Demographics\\\\Race\\\\}";

    /** A continuous concept path, filtered to a range and selected in the exports, its default escaped for use inside a JSON string. */
    static final String CONTINUOUS_CONCEPT_PATH_IN_JSON =
        "${picsure.openapi.examples.continuous-concept-path:\\\\Synthea\\\\ACT Demographics\\\\Age\\\\}";

    /** A gene symbol the genomic examples filter on. */
    public static final String GENE = "${picsure.openapi.examples.gene:APOE}";

    public static final String COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"COUNT\"}}";

    public static final String CROSS_COUNT =
        "{\"query\":{\"select\":[\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"" + SECOND_CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\",\"values\":[\"" + CATEGORICAL_VALUE + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"CROSS_COUNT\"}}";

    public static final String CATEGORICAL_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":[{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"" + SECOND_CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\"}]},\"genomicFilters\":[],\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}";

    public static final String CONTINUOUS_CROSS_COUNT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CONTINUOUS_CONCEPT_PATH_IN_JSON
            + "\",\"min\":40,\"max\":60},\"genomicFilters\":[],\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}";

    public static final String OBSERVATION_CROSS_COUNT = "{\"query\":{\"select\":[\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
        + "\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
        + "\",\"values\":[\"" + CATEGORICAL_VALUE + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"OBSERVATION_CROSS_COUNT\"}}";

    public static final String INFO_COLUMN_LISTING =
        "{\"query\":{\"select\":[],\"phenotypicClause\":null,\"genomicFilters\":[],\"expectedResultType\":\"INFO_COLUMN_LISTING\"}}";

    public static final String VARIANT_COUNT_FOR_QUERY =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"" + GENE
            + "\"]}],\"expectedResultType\":\"VARIANT_COUNT_FOR_QUERY\"}}";

    public static final String VARIANT_LIST_FOR_QUERY =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"" + GENE
            + "\"]}],\"expectedResultType\":\"VARIANT_LIST_FOR_QUERY\"}}";

    public static final String VCF_EXCERPT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"" + GENE
            + "\"]}],\"expectedResultType\":\"VCF_EXCERPT\"}}";

    public static final String AGGREGATE_VCF_EXCERPT =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"" + GENE
            + "\"]}],\"expectedResultType\":\"AGGREGATE_VCF_EXCERPT\"}}";

    public static final String PATIENTS =
        "{\"query\":{\"select\":[],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\""
            + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"values\":[\"" + CATEGORICAL_VALUE
            + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"PATIENTS\"}}";

    public static final String DATAFRAME =
        "{\"query\":{\"select\":[\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"" + CONTINUOUS_CONCEPT_PATH_IN_JSON
            + "\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\",\"values\":[\"" + CATEGORICAL_VALUE + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME\"}}";

    public static final String DATAFRAME_TIMESERIES =
        "{\"query\":{\"select\":[\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"" + CONTINUOUS_CONCEPT_PATH_IN_JSON
            + "\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\",\"values\":[\"" + CATEGORICAL_VALUE + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME_TIMESERIES\"}}";

    public static final String DATAFRAME_PFB =
        "{\"query\":{\"select\":[\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON + "\",\"" + CONTINUOUS_CONCEPT_PATH_IN_JSON
            + "\"],\"phenotypicClause\":{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"" + CATEGORICAL_CONCEPT_PATH_IN_JSON
            + "\",\"values\":[\"" + CATEGORICAL_VALUE + "\"]},\"genomicFilters\":[],\"expectedResultType\":\"DATAFRAME_PFB\"}}";

    private QueryRequestExamples() {}
}
