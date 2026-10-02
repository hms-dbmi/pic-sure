package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(description = "A filter the server adds to keep a query inside the data the caller may see")
public record AuthorizationFilter(
    @Schema(description = "A concept path this filter must match", example = "\\_consents\\") String conceptPath,
    @Schema(
        description = "Values for this concept path. Patients returned by this query must match at least one value for this concept path",
        example = "[\"phs000007.c1\", \"phs000007.c2\"]"
    ) Set<String> values
) {
}
