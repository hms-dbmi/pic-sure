package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.lang.NonNull;

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
    @Schema(description = "An object specifying the result type") ResultType expectedResultType,
    @Schema(description = "An externally passed UUID to assign to this query") UUID picsureId,
    @Schema(description = "An internally generated UUID identifying this query") UUID id
) {

    @Override
    @NonNull
    public List<String> select() {
        return select == null ? List.of() : select;
    }

    @Override
    @NonNull
    public List<AuthorizationFilter> authorizationFilters() {
        return authorizationFilters == null ? List.of() : authorizationFilters;
    }

    @Override
    @NonNull
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
     * Creates a UUID for this query if it does not already exist. Note: this behavior is different than previously, I do not believe there
     * is ever a valid reason to change the id once it is set, we should verify this with full regression testing in all environments.
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
