package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code PATCH /internal/queries/{picsureId}}. Every field is nullable and an absent field means "leave unchanged", so a caller can
 * advance just the status as a dispatch completes without re-sending the whole row.
 */
@Schema(description = "Partial update of a stored query; every field is optional and null means \"leave unchanged\"")
public record UpdateQueryRequest(
    @Schema(
        description = "New PIC-SURE status; travels as the enum NAME, never its ordinal", allowableValues = {
            "QUEUED", "PENDING", "ERROR", "AVAILABLE"}
    ) PicSureStatus status, @Schema(description = "Result id assigned by the backing resource") String resourceResultId,
    @Schema(description = "base64-encoded metadata bytes") String metadata
){
}
