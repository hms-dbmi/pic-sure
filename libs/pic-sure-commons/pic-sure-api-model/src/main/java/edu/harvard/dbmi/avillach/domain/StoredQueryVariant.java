package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One element of the response body of {@code GET /internal/queries/variants} on the operations service: one query row a user stored under a
 * general query, without its body. The operations service writes it and the query service reads it, so both bind this one record. No client
 * reads it.
 */
@Schema(description = "One variant of a general query as the operations service lists it to the query service.")
public record StoredQueryVariant(
    @Schema(
        description = "The PIC-SURE id of the query row.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID picsureId, @Schema(description = "Whether the row returns a sample of the matching patients.", example = "false") boolean sample,
    @Schema(
        description = "The result type of the row as a ResultType constant name, or null when none was stored.", example = "DATAFRAME"
    ) String resultType,
    @Schema(
        description = "When the row was created, in epoch milliseconds, or null for a row stored without a creation time.",
        example = "1790777100000"
    ) Long createdAt
) {
}
