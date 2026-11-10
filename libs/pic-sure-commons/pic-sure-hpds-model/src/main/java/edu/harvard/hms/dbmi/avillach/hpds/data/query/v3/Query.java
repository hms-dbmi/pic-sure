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
        description = "The id the result of this query is stored under. PIC-SURE assigns it. A value a client sends is discarded "
            + "before the query runs.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
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
     * @param picsureId the id the result of this query is stored under, assigned by the service that submits the query to HPDS
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

    /**
     * Returns a copy of this query stored under another result id. Every other component is kept as it is.
     *
     * @param picsureId the id the result is stored under, or null for a query that carries none
     * @return the copy
     */
    public Query setPicsureId(UUID picsureId) {
        return new Query(
            this.select, this.authorizationFilters, this.phenotypicClause, this.genomicFilters, this.expectedResultType, picsureId, this.id,
            this.sample
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
     * once. An existing id (e.g. one carried through from v1 translation by {@code QueryTranslator}) is never replaced. It is used for
     * correlation only, and it is deliberately NOT the key a result is stored or looked up under. HPDS derives that key separately (see
     * {@code QueryV3Service.resultKeyFor} in pic-sure-hpds): the query's {@link #picsureId()} when it has one, otherwise a UUIDv5 hash of
     * the query's content. Status, result, signed URL and file sharing requests all find the job by that key. The hash is taken over the
     * query as submitted, {@code id} included, so two identical bodies submitted without a {@code picsureId} and without an {@code id}
     * share one key, while a body that already carries an {@code id} hashes to a key of its own.
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
