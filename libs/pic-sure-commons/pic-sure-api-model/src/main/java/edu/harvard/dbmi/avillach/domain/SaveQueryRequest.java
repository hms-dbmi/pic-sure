package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code POST /internal/queries} on the operations service. The query service sends it to persist a query it has just
 * dispatched to HPDS. Every component is optional: a synchronous query is stored with no status and no metadata. The query service and the
 * operations service exchange it, and no client reads it.
 *
 * <p>{@code status} travels as the {@link PicSureStatus} constant name and {@code metadata} as base64 text, so the wire carries plain
 * strings and the operations service answers 400 for a name or an encoding it cannot read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A query the query service asks the operations service to persist.")
public record SaveQueryRequest(
    @Schema(
        description = "The query request as a JSON string, stored as sent except that a top-level resourceCredentials member is removed.",
        example = "{\"query\":{\"expectedResultType\":\"COUNT\"}}"
    ) String query,
    @Schema(
        description = "The id HPDS assigned to the result, when it assigned one.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) String resourceResultId,
    @Schema(
        description = "The query status as a PicSureStatus constant name. An unknown name is answered with 400.", example = "PENDING"
    ) String status,
    @Schema(
        description = "The query format version. 3 marks a v3 query and an absent value marks a query stored before v3.", example = "3"
    ) String version,
    @Schema(
        description = "Result metadata as base64-encoded UTF-8 JSON. Text that is not base64 is answered with 400.",
        example = "eyJwaWNzdXJlUXVlcnlJZCI6Ijg2OTRlM2Q0LTVjYjQtNDEwZi04NDMxLTk5MzQ0NWU2ZDNmNiJ9"
    ) String metadata
) {
}
