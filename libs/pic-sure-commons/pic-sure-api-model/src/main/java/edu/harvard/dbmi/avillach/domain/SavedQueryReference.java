package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body of {@code POST /internal/queries} on the operations service: the id under which the query was persisted. The query service
 * reads it to answer its own caller with the PIC-SURE result id. No client reads it.
 */
@Schema(description = "The id under which the operations service persisted a query.")
public record SavedQueryReference(
    @Schema(
        description = "The PIC-SURE id of the persisted query.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID picsureId
) {
}
