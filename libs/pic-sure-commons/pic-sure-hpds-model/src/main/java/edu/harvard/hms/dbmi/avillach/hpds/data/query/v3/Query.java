package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record Query(
    @Schema(
        description = "A list of concept paths to select. Ignored for expectedResultType that do not return fields, such as COUNT"
    ) List<String> select,
    @Schema(
        description = "A list of filters specifically applied for authorization purposes"
    ) List<AuthorizationFilter> authorizationFilters,
    @Schema(description = "An object specifying phenotypic filters") PhenotypicClause phenotypicClause,
    @Schema(description = "A list of genomic filters") List<GenomicFilter> genomicFilters,
    @Schema(description = "An object specifying the result type", requiredMode = Schema.RequiredMode.REQUIRED) @JsonProperty(
        required = true
    ) ResultType expectedResultType, @Schema(description = "An externally passed UUID to assign to this query") UUID picsureId,
    @Schema(description = "An internally generated UUID identifying this query") UUID id
) {

    /**
     * The deserialization entry point, which refuses a query that does not name a result type. Enforcement lives here rather than in the
     * canonical constructor so it applies to JSON only: in-process construction stays permissive, which matters because
     * {@code CountV3Processor} builds per-concept probe queries with a null result type and {@code QueryTranslator} carries through
     * whatever a v1 query had. <p> {@code @JsonProperty(required = true)} alone is not enough. It rejects an absent field but accepts an
     * explicit {@code "expectedResultType": null}, which is exactly as easy to send, so the null check below is what closes that half. <p>
     * Why this is a security control and not just validation: PSAMA decides whether a body carries a query before it decides whether to
     * attach consent filters. A body with no result type was read as "no query here", granted unscoped, and then given a result type
     * downstream by the visualization decomposer. Refusing it at the model boundary means every service that binds this record refuses it
     * too.
     */
    @JsonCreator
    static Query fromJson(
        @JsonProperty("select") List<String> select, @JsonProperty("authorizationFilters") List<AuthorizationFilter> authorizationFilters,
        @JsonProperty("phenotypicClause") PhenotypicClause phenotypicClause,
        @JsonProperty("genomicFilters") List<GenomicFilter> genomicFilters,
        @JsonProperty(value = "expectedResultType", required = true) ResultType expectedResultType,
        @JsonProperty("picsureId") UUID picsureId, @JsonProperty("id") UUID id
    ) {
        if (expectedResultType == null) {
            throw new IllegalArgumentException("expectedResultType is required and must name a valid result type");
        }
        return new Query(select, authorizationFilters, phenotypicClause, genomicFilters, expectedResultType, picsureId, id);
    }

    @Override
    public List<String> select() {
        return select == null ? List.of() : select;
    }

    @Override
    public List<AuthorizationFilter> authorizationFilters() {
        return authorizationFilters == null ? List.of() : authorizationFilters;
    }

    public Query setAuthorizationFilters(List<AuthorizationFilter> authorizationFilters) {
        return new Query(
            this.select, authorizationFilters == null ? List.of() : authorizationFilters, this.phenotypicClause, this.genomicFilters,
            this.expectedResultType, this.picsureId, this.id
        );
    }


    @Override
    public List<GenomicFilter> genomicFilters() {
        return genomicFilters == null ? List.of() : genomicFilters;
    }

    public List<PhenotypicFilter> allFilters() {
        return flatten(phenotypicClause);
    }

    private List<PhenotypicFilter> flatten(PhenotypicClause phenotypicClause) {
        if (phenotypicClause == null) {
            return List.of();
        }
        return switch (phenotypicClause) {
            case PhenotypicSubquery phenotypicSubquery -> phenotypicSubquery.phenotypicClauses().parallelStream().map(this::flatten)
                .reduce((list1, list2) -> {
                    List<PhenotypicFilter> copy = new ArrayList<>(list1);
                    copy.addAll(list2);
                    return copy;
                }).orElseGet(List::of);
            case PhenotypicFilter phenotypicFilter -> List.of(phenotypicFilter);
        };
    }

    /**
     * Returns this query with a non-null {@link #id()}, generating one only when absent.
     *
     * <p>Semantics: the v3 {@code id} is a per-query-instance RANDOM identifier, not a stable content-derived key. It is assigned at most
     * once — an existing id (e.g. one carried through from v1 translation by {@code QueryTranslator}) is never replaced — and is used for
     * correlation only: HPDS log lines and file-sharing result lookup. It is deliberately NOT a dedup/caching key: HPDS derives its
     * result-cache key separately as a UUIDv5 content hash of the query (see {@code QueryV3Service.initializeResult} in pic-sure-hpds),
     * matching v1's {@code QueryDecorator#setId} behavior. Two identical query bodies submitted without ids therefore get different
     * {@code id}s but the same content-hash cache key.
     *
     * @return this query or a copy of this query with the UUID set
     */
    public Query generateId() {
        if (id != null) {
            return this;
        }
        return new Query(select, authorizationFilters, phenotypicClause, genomicFilters, expectedResultType, picsureId, UUID.randomUUID());
    }
}
