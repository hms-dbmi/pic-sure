package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Schema(description = "A PIC-SURE v3 query: which patients to match and what to return about them.")
public record Query(
    @Schema(
        description = "A list of concept paths to select. Ignored for expectedResultType that do not return fields, such as COUNT.",
        example = "[\"\\\\demographics\\\\AGE\\\\\", \"\\\\demographics\\\\SEX\\\\\"]"
    ) List<String> select, @Schema(hidden = true) List<AuthorizationFilter> authorizationFilters,
    @Schema(description = "An object specifying phenotypic filters.") PhenotypicClause phenotypicClause,
    @Schema(description = "A list of genomic filters.") List<GenomicFilter> genomicFilters,
    @Schema(
        description = "The result to compute. Which result types a path accepts depends on the path and the backend; the Queries "
            + "section lists them for each.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) ResultType expectedResultType,
    @Schema(
        description = "An externally passed UUID to assign to this query.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) UUID picsureId,
    @Schema(description = "An internally generated UUID identifying this query.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") UUID id,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT) @Schema(
        description = "When true, the query returns a small, fixed sample of the matching patients instead of all of them. "
            + "Defaults to false when omitted."
    ) boolean sample
) {

    /**
     * Creates a query that is not a sample.
     *
     * @param select the concept paths to select
     * @param authorizationFilters the filters that scope the query to the caller's consents
     * @param phenotypicClause the phenotypic filters
     * @param genomicFilters the genomic filters
     * @param expectedResultType the result to compute
     * @param picsureId the externally passed id of this query
     * @param id the internally generated id of this query
     */
    public Query(
        List<String> select, List<AuthorizationFilter> authorizationFilters, PhenotypicClause phenotypicClause,
        List<GenomicFilter> genomicFilters, ResultType expectedResultType, UUID picsureId, UUID id
    ) {
        this(select, authorizationFilters, phenotypicClause, genomicFilters, expectedResultType, picsureId, id, false);
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
            this.expectedResultType, this.picsureId, this.id, this.sample
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
        return new Query(
            select, authorizationFilters, phenotypicClause, genomicFilters, expectedResultType, picsureId, UUID.randomUUID(), sample
        );
    }
}
