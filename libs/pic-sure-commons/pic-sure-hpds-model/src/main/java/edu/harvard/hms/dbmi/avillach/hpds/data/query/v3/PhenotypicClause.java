package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One node of the phenotypic filter tree: a single filter, or a subquery that combines clauses")
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({@JsonSubTypes.Type(PhenotypicSubquery.class), @JsonSubTypes.Type(PhenotypicFilter.class)})
public sealed interface PhenotypicClause permits PhenotypicSubquery, PhenotypicFilter {

}
