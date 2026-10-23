package edu.harvard.hms.dbmi.avillach.query.aggregate;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.GenomicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicClause;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The query an open access request carries: a v3 query without genomic data. Only {@code select}, {@code phenotypicClause} and
 * {@code expectedResultType} are documented. {@code genomicFilters}, {@code authorizationFilters}, {@code picsureId} and {@code id} are
 * still read, because clients send them empty or null on every query, but they are hidden from the OpenAPI document, and a non-empty
 * {@code genomicFilters} is refused rather than dropped, so a genomic query is never answered as if it had no genomic filter.
 *
 * @param select the concept paths an OBSERVATION_CROSS_COUNT counts; ignored by the other result types and replaced on a CROSS_COUNT
 * @param authorizationFilters accepted for compatibility and passed through unchanged
 * @param phenotypicClause the phenotypic filters
 * @param genomicFilters accepted only when absent or empty
 * @param expectedResultType the result to compute
 * @param picsureId accepted for compatibility and passed through unchanged
 * @param id accepted for compatibility and passed through unchanged
 */
@Schema(description = "A query on open access: phenotypic filters and a result type. Genomic filters are not accepted.")
public record OpenQuery(
    @Schema(
        description = "Concept paths to count observations for. Read only by OBSERVATION_CROSS_COUNT; open access replaces it with every "
            + "study consent on a CROSS_COUNT.",
        example = "[\"\\\\Synthea\\\\ACT Demographics\\\\Sex\\\\\"]"
    ) List<String> select, @Schema(hidden = true) List<AuthorizationFilter> authorizationFilters,
    @Schema(description = "The phenotypic filters patients must match.") PhenotypicClause phenotypicClause,
    @Schema(hidden = true) List<GenomicFilter> genomicFilters,
    @Schema(
        description = "The result to compute. Open access supports only these result types.", requiredMode = Schema.RequiredMode.REQUIRED,
        type = "string", allowableValues = {
            "COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT"}
    ) ResultType expectedResultType, @Schema(hidden = true) UUID picsureId, @Schema(hidden = true) UUID id
){

    /** Message of the 400 a query with genomic filters receives. */
    public static final String GENOMIC_FILTERS_REFUSED = "Genomic filters are not supported on open access.";

    /**
     * The v3 query the aggregate service runs.
     *
     * @return the same query as a v3 {@link Query}
     * @throws PicsureException 400 when the query carries genomic filters
     */
    public Query toQuery() {
        if (genomicFilters != null && !genomicFilters.isEmpty()) {
            throw new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", GENOMIC_FILTERS_REFUSED);
        }
        return new Query(select, authorizationFilters, phenotypicClause, List.of(), expectedResultType, picsureId, id);
    }
}
