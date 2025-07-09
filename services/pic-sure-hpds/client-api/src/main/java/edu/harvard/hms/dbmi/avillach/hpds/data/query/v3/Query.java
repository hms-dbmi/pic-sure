package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.List;

public record Query(
        List<String> select,
        List<AuthorizationFilter> authorizationFilters,
        PhenotypicClause phenotypicClause,
        List<GenomicFilter> genomicFilters,
        ResultType expectedResultType,
        String picsureId, String id) {

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
        return switch (phenotypicClause) {
            case PhenotypicSubquery phenotypicSubquery -> phenotypicSubquery.phenotypicClauses().parallelStream()
                    .map(this::flatten)
                    .reduce((list1, list2) -> {
                        List<PhenotypicFilter> copy = new ArrayList<>(list1);
                        copy.addAll(list2);
                        return copy;
                    })
                    .orElseGet(List::of);
            case PhenotypicFilter phenotypicFilter -> List.of(phenotypicFilter);
        };
    }


}
