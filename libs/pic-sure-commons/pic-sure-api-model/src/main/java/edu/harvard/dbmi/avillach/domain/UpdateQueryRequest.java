package edu.harvard.dbmi.avillach.domain;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code PATCH /internal/queries/{picsureId}} on the operations service. Every component is optional and an absent or null
 * component leaves the stored value unchanged, so a caller sends only what moved: the status as a dispatch completes, or the result id once
 * HPDS assigns one. {@code query} and {@code version} are replaced together when the query service upgrades a row stored before v3 to the
 * v3 format. The query service and the operations service exchange it, and no client reads it.
 *
 * <p>{@code runId} names the run the caller loaded with the row. When the row has a newer run by the time the update arrives, the status is
 * left as it is, so a poller that read an older run cannot overwrite the status of the current one.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "A partial update to a persisted query. A null or absent field leaves the stored value unchanged.")
public record UpdateQueryRequest(
    @Schema(
        description = "The new status as a PicSureStatus constant name. The first change to AVAILABLE also stamps the ready time.",
        example = "AVAILABLE"
    ) String status,
    @Schema(description = "The id HPDS assigned to the result.", example = "8694e3d4-5cb4-410f-8431-993445e6d3f6") String resourceResultId,
    @Schema(
        description = "Result metadata as base64-encoded UTF-8 JSON. Text that is not base64 is answered with 400.",
        example = "eyJwaWNzdXJlUXVlcnlJZCI6Ijg2OTRlM2Q0LTVjYjQtNDEwZi04NDMxLTk5MzQ0NWU2ZDNmNiJ9"
    ) String metadata,
    @Schema(
        description = "The serialized QueryRequest that replaces the stored one. Resource credentials are stripped before it is stored.",
        example = "{\"query\":{\"phenotypicClause\":null,\"expectedResultType\":\"COUNT\"}}"
    ) String query, @Schema(description = "The query format version that replaces the stored one.", example = "3") String version,
    @Schema(
        description = "The id of the run the caller read with the row. A status sent with an id other than the row's latest run is not written.",
        example = "0b6d6a0e-3c56-4c0b-9a38-0b1f5c1f3a77"
    ) UUID runId
) {

    /**
     * Builds an update that names no run, which is applied whatever the row's latest run is.
     *
     * @param status the new status as a {@link PicSureStatus} constant name
     * @param resourceResultId the id HPDS assigned to the result
     * @param metadata result metadata as base64 text
     * @param query the serialized query request that replaces the stored one
     * @param version the query format version that replaces the stored one
     */
    public UpdateQueryRequest(String status, String resourceResultId, String metadata, String query, String version) {
        this(status, resourceResultId, metadata, query, version, null);
    }
}
