package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

public record QueryStatusResponse(
    @Schema(description = "PIC-SURE-wide id of this query") UUID picsureId, @Schema(
        description = "Real status values", allowableValues = {
            "QUEUED", "PENDING", "ERROR", "AVAILABLE"}
    ) PicSureStatus status, @Schema(description = "Raw status string reported by the backing resource") String resourceStatus,
    @Schema(description = "Result id assigned by the backing resource") String resourceResultId, long sizeInBytes, long startTime,
    long duration, long expiration,
    @Schema(description = "Open-ended resource metadata; known keys: queryJson, queryResultMetadata") Map<String, Object> resultMetadata
){

    public QueryStatusResponse {
        resultMetadata = resultMetadata == null ? Map.of() : resultMetadata;
    }
}
