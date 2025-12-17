package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PhenotypicSubquery(
    @Schema(description = "Not implemented yet") Boolean not,
    @Schema(
        description = "A list of phenotypic clauses to be evaluated and combined using the `operator`"
    ) List<PhenotypicClause> phenotypicClauses,
    @Schema(description = "Specifies logic to combine `phenotypicClauses` in this subquery") Operator operator
) implements PhenotypicClause {
}
