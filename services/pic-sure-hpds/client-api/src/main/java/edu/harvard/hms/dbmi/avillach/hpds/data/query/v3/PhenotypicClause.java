package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(PhenotypicSubquery.class),
        @JsonSubTypes.Type(PhenotypicFilter.class) }
)
public sealed interface PhenotypicClause permits PhenotypicSubquery, PhenotypicFilter {

}
