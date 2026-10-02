package edu.harvard.dbmi.avillach.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request body for {@code PATCH /internal/queries/{picsureId}} on the operations service. Every component is optional and an absent or null
 * component leaves the stored value unchanged, so a caller sends only what moved: the status as a dispatch completes, or the result id once
 * HPDS assigns one.
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
    ) String metadata
) {
}
