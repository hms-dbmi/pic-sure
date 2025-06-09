package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;

import java.util.List;

public record Query(
        List<String> select,
        List<AuthorizationFilter> authorizationFilters,
        PhenotypicClause phenotypicClause,
        List<GenomicFilter> genomicFilters,
        ResultType expectedResultType
) {

}
