package edu.harvard.hms.dbmi.avillach.hpds.data.query;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Specifies the return type of this query")
public enum ResultType {

    @Schema(description = "Return a count of patients matching this query")
    COUNT,

    @Schema(
        description = "Return a CSV with an observation for each concept for each patient included in the query. If there are multiple facts for a specific patient, they will be tab separated"
    )
    DATAFRAME,

    @Schema(
        description = "Create a `DATAFRAME`, but do not allow conventional access to it. Instead, the dataframe can be sent to a S3 bucket by an admin"
    )
    SECRET_ADMIN_DATAFRAME,

    @Schema(description = "Return one patient count for each concept path included in the `select` field of the Query")
    CROSS_COUNT,

    @Schema(
        description = "Return multiple patient count for each categorical concept and its given variables included in any filters of the Query"
    )
    CATEGORICAL_CROSS_COUNT,

    @Schema(description = "Return one patient count for each continuous concept path included in any filters of the query")
    CONTINUOUS_CROSS_COUNT,

    @Schema(description = "Return all variant info column metadata")
    INFO_COLUMN_LISTING,

    @Schema(
        description = "Return the number of observations for included patients and included fields, broken up across the included cross count fields."
    )
    OBSERVATION_CROSS_COUNT,

    @Schema(
        description = "Return the count of unique `VariantSpec` that are the result of applying all genomic and phenotypic filters in the query. This is used by clients to limit queries to reasonable numbers of variants."
    )
    VARIANT_COUNT_FOR_QUERY,

    @Schema(
        description = "Return unique `VariantSpec` as strings that are the result of applying all genomic and phenotypic filters in the query."
    )
    VARIANT_LIST_FOR_QUERY,

    @Schema(description = "Return quasi-VCF lines for the variants expressed in the query.")
    VCF_EXCERPT,

    @Schema(description = "Return quasi-VCF lines for the variants expressed in the query without patient data.")
    AGGREGATE_VCF_EXCERPT,

    @Schema(
        description = "Export data in the same format as the HPDS csv loader, which is suitable to time series analysis and/or loading into another instance of HPDS."
    )
    DATAFRAME_TIMESERIES,

    /**
     * Exports data as PFB, using avro <a href="https://uc-cdis.github.io/pypfb/">https://uc-cdis.github.io/pypfb/</a>
     */
    @Schema(description = "Export data as PFB, using avro [https://uc-cdis.github.io/pypfb/](https://uc-cdis.github.io/pypfb/)")
    DATAFRAME_PFB,

    @Schema(description = "Return patients associated with this query")
    PATIENTS
}
