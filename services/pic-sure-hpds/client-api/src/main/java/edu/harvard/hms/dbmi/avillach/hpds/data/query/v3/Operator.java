package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

public enum Operator {
    @Schema(description = "Filters combined with AND will return patients who match all filters")
    AND, @Schema(description = "Filters combined with OR will return patients who match any filters")
    OR
}
