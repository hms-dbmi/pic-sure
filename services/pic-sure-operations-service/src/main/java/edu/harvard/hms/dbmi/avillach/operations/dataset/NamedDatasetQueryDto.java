package edu.harvard.hms.dbmi.avillach.operations.dataset;

import java.util.UUID;

import edu.harvard.dbmi.avillach.domain.PicSureStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The {@code query} member of {@link NamedDatasetDto}, containing only the persisted query fields consumers read. Keeping this as a DTO
 * prevents the gzip-compressed blob, the {@code metadata} byte array, and persistence associations from reaching the wire. The
 * {@code query} field contains a JSON-encoded request wrapper whose {@code query} member is normalized to v3. Conversion affects only this
 * response; the stored query is unchanged.
 *
 * <p>{@code startTime} is epoch milliseconds because the frontend passes it to {@code new Date(...)} and sorts it numerically. A
 * {@code Long} pins that contract regardless of ObjectMapper configuration.
 */
@Schema(description = "The persisted query a named dataset points at, reduced to the fields clients read.")
public record NamedDatasetQueryDto(
    @Schema(
        description = "The PIC-SURE id of the query. Clients use it as the query id of the dataset.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6", requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID uuid,
    @Schema(
        description = "The stored query request, a JSON document carried as one string. Its query member is always in the v3 format: a query stored before v3 is converted for this response and the stored row is left as it was. An empty string when the row holds no query.",
        example = "{\"query\":{\"phenotypicClause\":null,\"expectedResultType\":\"COUNT\"}}"
    ) String query,
    @Schema(
        description = "When the query was saved, in epoch milliseconds, or null on a row with no start time.", example = "1790777100000"
    ) Long startTime, @Schema(description = "The last status recorded for the query, or null when the row has none.") PicSureStatus status
) {
}
