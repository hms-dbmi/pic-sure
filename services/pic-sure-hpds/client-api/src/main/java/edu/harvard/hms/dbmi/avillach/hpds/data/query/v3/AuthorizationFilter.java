package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record AuthorizationFilter(
    @Schema(description = "A concept path this filter must match") String conceptPath,
    @Schema(
        description = "Values for this concept path. Patients returned by this query must match at least one value for this concept path"
    ) List<String> values
) {
}
