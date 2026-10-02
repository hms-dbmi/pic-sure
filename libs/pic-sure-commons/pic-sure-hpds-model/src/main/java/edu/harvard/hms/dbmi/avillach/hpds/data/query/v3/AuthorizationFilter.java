package edu.harvard.hms.dbmi.avillach.hpds.data.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Set;

@Schema(
    description = "A consent filter. On the authorized backend the server builds these from the caller's consents and replaces whatever the client sent; on the open backend they are not used."
)
public record AuthorizationFilter(
    @Schema(description = "A concept path this filter must match", example = "\\_consents\\") String conceptPath,
    @Schema(
        description = "Values for this concept path. Patients returned by this query must match at least one value for this concept path",
        example = "[\"phs000007.c1\", \"phs000007.c2\"]"
    ) Set<String> values
) {
}
