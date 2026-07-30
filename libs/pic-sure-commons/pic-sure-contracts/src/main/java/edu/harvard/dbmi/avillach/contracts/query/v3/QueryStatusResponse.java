package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Status of a single query, as reported by PIC-SURE and by the resource backing it")
public record QueryStatusResponse(
    @Schema(description = "PIC-SURE-wide id of this query") UUID picsureId, @Schema(
        description = "Real status values", allowableValues = {
            "QUEUED", "PENDING", "ERROR", "AVAILABLE"}
    ) PicSureStatus status, @Schema(description = "Raw status string reported by the backing resource") String resourceStatus,
    @Schema(description = "Result id assigned by the backing resource") String resourceResultId,
    @Schema(description = "Size of the result in bytes. Populated only once the query has succeeded; 0 otherwise") long sizeInBytes,
    @Schema(description = "Epoch milliseconds at which the query was queued") long startTime,
    @Schema(description = "Milliseconds elapsed between the query being queued and completing; 0 while it is still running") long duration,
    @Schema(
        description = "Epoch milliseconds at which the results expire; 0 when the backing resource reports no expiration"
    ) long expiration,
    @Schema(description = "Open-ended resource metadata; known keys: queryJson, queryResultMetadata") Map<String, Object> resultMetadata
){

    public QueryStatusResponse {
        resultMetadata = resultMetadata == null ? Map.of() : resultMetadata;
    }
}
