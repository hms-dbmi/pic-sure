package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

public enum PhenotypicFilterType {
    @Schema(description = "Specifies that a filter will match if a concept path contains any value")
    REQUIRED, @Schema(
        description = "Specifies that a filter will match if a concept path matches the values specified in the `PhenotypicFilter`"
    )
    FILTER, @Schema(
        description = "Specifies that a filter will match if the concept path contains any value for itself or any child concept paths"
    )
    ANY_RECORD_OF
}
