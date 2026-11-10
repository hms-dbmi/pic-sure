package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One entry of the {@code queries} member of {@link NamedDatasetDto}: a query linked to the dataset, with the two values that tell its
 * variant apart from the dataset's other queries.
 *
 * @param queryId the id of the linked query
 * @param sample whether the query is a sample query
 * @param resultType the result type the query asks for, or null when its stored body names none
 */
@Schema(description = "A query linked to a named dataset. A dataset links at most one query for each pair of sample and resultType.")
public record LinkedQueryDto(
    @Schema(
        description = "The PIC-SURE id of the linked query.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID queryId,
    @Schema(
        description = "Whether the query returns a sample of the matching patients instead of all of them.",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) boolean sample,
    @Schema(
        description = "The result type the query asks for, as its stored body names it. Null when the stored body names none.",
        example = "DATAFRAME"
    ) String resultType
) {
}
