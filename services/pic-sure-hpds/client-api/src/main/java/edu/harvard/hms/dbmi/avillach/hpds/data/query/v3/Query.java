package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

import java.util.List;

public record Query(
        ResultType expectedResultType,
        List<AuthorizationFilter> authorizationFilters,
        List<String> select,
        PhenotypicClause phenotypicClause,
        List<GenomicFilter> genomicFilters
) {

}
