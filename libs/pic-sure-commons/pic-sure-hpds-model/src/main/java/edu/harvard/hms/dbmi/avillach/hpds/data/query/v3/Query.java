package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.*;
import java.util.stream.Collectors;

public record Query(
    @Schema(
        description = "A list of concept paths to select. Ignored for expectedResultType that do not return fields, such as COUNT"
    ) List<String> select,
    @Deprecated
    @Schema(
        description = "A list of filters specifically applied for authorization purposes", deprecated = true
    ) List<AuthorizationFilter> authorizationFilters,
    Set<UserConsent> userConsents,
    @Schema(description = "An object specifying phenotypic filters") PhenotypicClause phenotypicClause,
    @Schema(description = "A list of genomic filters") List<GenomicFilter> genomicFilters,
    @Schema(description = "An object specifying the result type") ResultType expectedResultType,
    @Schema(description = "An externally passed UUID to assign to this query") UUID picsureId,
    @Schema(description = "An internally generated UUID identifying this query") UUID id
) {

    public static final String CONSENTS_AUTHORIZATION_FILTER_NAME = "_consents";

    @Override
    public List<String> select() {
        return select == null ? List.of() : select;
    }

    @Deprecated
    @Override
    public List<AuthorizationFilter> authorizationFilters() {
        return authorizationFilters == null ? List.of() : authorizationFilters;
    }

    public Query setAuthorizationFilters(List<AuthorizationFilter> authorizationFilters) {
        return new Query(
            this.select, authorizationFilters == null ? List.of() : authorizationFilters, this.userConsents, this.phenotypicClause, this.genomicFilters,
            this.expectedResultType, this.picsureId, this.id
        );
    }


    @Override
    public Set<UserConsent> userConsents() {
        return userConsents == null ? Set.of() : userConsents;
    }

    public Query setUserConsents(Set<UserConsent> userConsents) {
        return new Query(
                this.select, this.authorizationFilters, userConsents != null ? userConsents : Set.of(), this.phenotypicClause, this.genomicFilters,
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
        return new Query(select, authorizationFilters, userConsents, phenotypicClause, genomicFilters, expectedResultType, picsureId, UUID.randomUUID());
    }
}
