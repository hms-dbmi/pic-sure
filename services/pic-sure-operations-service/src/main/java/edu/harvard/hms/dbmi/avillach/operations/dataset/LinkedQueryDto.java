package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the {@code queries} member of {@link NamedDatasetDto}: a query row filed under the dataset's general query, with the two
 * values that tell its variant apart from the other rows of that general query. The values are read from the columns of the query row, not
 * from its stored body.
 *
 * @param queryId the id of the query row
 * @param sample whether the query is a sample query
 * @param resultType the result type stored on the query row, or null when the row stores none
 */
@Schema(
    description = "One stored variant of the query a named dataset points at: the same query submitted by the caller with one sample flag and one result type."
)
public record LinkedQueryDto(
    @Schema(
        description = "The PIC-SURE id of the query stored for this variant.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID queryId,
    @Schema(
        description = "Whether the query returns a sample of the matching patients instead of all of them.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean sample,
    @Schema(
        description = "The result type the query asks for. Null when none is recorded for the stored query.", example = "DATAFRAME"
    ) String resultType
) {
}
