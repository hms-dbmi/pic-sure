package edu.harvard.dbmi.avillach.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Response body of {@code GET /internal/queries/{picsureId}} on the operations service: one persisted query row as the query service reads
 * it back. The operations service writes it and the query service reads it, so both bind this one record. No client reads it.
 *
 * <p>It is distinct from {@link DispatchResponse}, which carries the query body alone.
 */
@Schema(description = "A persisted query as the operations service returns it to the query service.")
public record StoredQuery(
    @Schema(
        description = "The PIC-SURE id of the query.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6",
        requiredMode = Schema.RequiredMode.REQUIRED
    ) UUID picsureId,
    @Schema(
        description = "The stored query request as a JSON string, with any resourceCredentials member removed. An empty string when the row holds no query.",
        example = "{\"query\":{\"expectedResultType\":\"COUNT\"}}"
    ) String query,
    @Schema(
        description = "The id HPDS assigned to the result, or null when none was recorded.",
        example = "8694e3d4-5cb4-410f-8431-993445e6d3f6"
    ) String resourceResultId,
    @Schema(
        description = "The query status as a PicSureStatus constant name, or null when the row has none.", example = "AVAILABLE"
    ) String status,
    @Schema(
        description = "The query format version. 3 marks a v3 query and null marks a query stored before v3.", example = "3"
    ) String version,
    @Schema(
        description = "Result metadata as base64-encoded UTF-8 JSON, or null when none was recorded.",
        example = "eyJwaWNzdXJlUXVlcnlJZCI6Ijg2OTRlM2Q0LTVjYjQtNDEwZi04NDMxLTk5MzQ0NWU2ZDNmNiJ9"
    ) String metadata, @Schema(description = "When the query was saved, in epoch milliseconds.", example = "1790777100000") Long startTime,
    @Schema(description = "When the query first became AVAILABLE, in epoch milliseconds.", example = "1790777100000") Long readyTime,
    @Schema(
        description = "The UUID of the user who owns the row, or null for a row stored without an owner.",
        example = "5f0c1f0e-6f0a-4a57-9d6e-0d1c6a3b9f11"
    ) UUID userUuid,
    @Schema(
        description = "The consents of the latest run, as consent concept path to sorted consent values, or null when none were recorded.",
        example = "{\"\\\\_consents\\\\\":[\"phs000001.c1\"]}"
    ) Map<String, List<String>> consents,
    @Schema(
        description = "The id of the latest recorded run of the query, or null when no run was recorded.",
        example = "0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77"
    ) UUID runId
) {

    /**
     * Builds a stored query with timing and no owner, consents or run, the shape of a row stored without an owner.
     *
     * @param picsureId the PIC-SURE id of the query
     * @param query the stored query request as a JSON string
     * @param resourceResultId the id HPDS assigned to the result
     * @param status the status as a {@link PicSureStatus} constant name
     * @param version the query format version
     * @param metadata result metadata as base64 text
     * @param startTime when the query was saved, in epoch milliseconds
     * @param readyTime when the query first became AVAILABLE, in epoch milliseconds
     */
    public StoredQuery(
        UUID picsureId, String query, String resourceResultId, String status, String version, String metadata, Long startTime,
        Long readyTime
    ) {
        this(picsureId, query, resourceResultId, status, version, metadata, startTime, readyTime, null, null, null);
    }

    /**
     * Builds a stored query that carries no timing, for callers that do not read the two server-owned timestamps.
     *
     * @param picsureId the PIC-SURE id of the query
     * @param query the stored query request as a JSON string
     * @param resourceResultId the id HPDS assigned to the result
     * @param status the status as a {@link PicSureStatus} constant name
     * @param version the query format version
     * @param metadata result metadata as base64 text
     */
    public StoredQuery(UUID picsureId, String query, String resourceResultId, String status, String version, String metadata) {
        this(picsureId, query, resourceResultId, status, version, metadata, null, null);
    }
}
