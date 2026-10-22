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

    /** A gene symbol the genomic examples filter on. */
    public static final String GENE = "APOE";

    /** A term the search examples look for. */
    public static final String SEARCH_TERM = "age";

    /** What a COUNT example returns, shown as its description. */
    public static final String COUNT_DESCRIPTION = "Counts the patients who match the filters.";

    /** What a CROSS_COUNT example returns, shown as its description. */
    public static final String CROSS_COUNT_DESCRIPTION =
        "Counts the matching patients who have a value for each concept path in `select`, one count per path.";

    /** What a CATEGORICAL_CROSS_COUNT example returns, shown as its description. */
    public static final String CATEGORICAL_CROSS_COUNT_DESCRIPTION =
        "For each categorical concept the filters name, counts the matching patients who have each of its values.";

    /** What a CONTINUOUS_CROSS_COUNT example returns, shown as its description. */
    public static final String CONTINUOUS_CROSS_COUNT_DESCRIPTION =
        "For each continuous concept the filters name, counts the matching patients at each of its values.";

    /** What a OBSERVATION_CROSS_COUNT example returns, shown as its description. */
    public static final String OBSERVATION_CROSS_COUNT_DESCRIPTION =
        "Counts the observations, not the patients, that the matching patients have for each concept path in `select`.";

    /** What a INFO_COLUMN_LISTING example returns, shown as its description. */
    public static final String INFO_COLUMN_LISTING_DESCRIPTION =
        "Lists the genomic annotation columns, such as `Gene_with_variant`, with their descriptions. The filters are ignored.";

    /** What a VARIANT_COUNT_FOR_QUERY example returns, shown as its description. */
    public static final String VARIANT_COUNT_FOR_QUERY_DESCRIPTION =
        "Counts the distinct variants that match the genomic and phenotypic filters. Clients use it to keep a variant query to a manageable size.";

    /** What a VARIANT_LIST_FOR_QUERY example returns, shown as its description. */
    public static final String VARIANT_LIST_FOR_QUERY_DESCRIPTION =
        "Lists the distinct variants that match the genomic and phenotypic filters.";

    /** What a VCF_EXCERPT example returns, shown as its description. */
    public static final String VCF_EXCERPT_DESCRIPTION =
        "Returns VCF-style lines for the variants the query matches, including patient-level data.";

    /** What a AGGREGATE_VCF_EXCERPT example returns, shown as its description. */
    public static final String AGGREGATE_VCF_EXCERPT_DESCRIPTION =
        "Returns the same VCF-style lines as VCF_EXCERPT without patient-level data.";

    /** What a PATIENTS example returns, shown as its description. */
    public static final String PATIENTS_DESCRIPTION = "Lists the ids of the patients who match the filters.";

    /** What a DATAFRAME example returns, shown as its description. */
    public static final String DATAFRAME_DESCRIPTION =
        "Exports a CSV with one row per matching patient and a column for each concept path in `select`. A patient with several values for one concept has them tab-separated in one cell.";

    /** What a DATAFRAME_TIMESERIES example returns, shown as its description. */
    public static final String DATAFRAME_TIMESERIES_DESCRIPTION =
        "Exports the matching patients' observations of the concepts in `select` as a CSV in the HPDS loader format, one row per observation with its timestamp, suited to time-series analysis or loading into another HPDS.";

    /** What a DATAFRAME_PFB example returns, shown as its description. */
    public static final String DATAFRAME_PFB_DESCRIPTION =
        "Exports the matching patients' values for the concepts in `select` as an Avro PFB file.";

    private QueryRequestExamples() {}
}
