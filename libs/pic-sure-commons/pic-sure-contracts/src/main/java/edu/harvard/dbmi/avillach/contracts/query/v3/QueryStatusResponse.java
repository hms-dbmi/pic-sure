package edu.harvard.dbmi.avillach.contracts.query.v3;


import java.util.Map;
import java.util.UUID;

public record QueryStatusResponse(
    UUID picsureId, PicSureStatus status, String resourceStatus, String resourceResultId, long sizeInBytes, long startTime, long duration,
    long expiration, Map<String, Object> resultMetadata
) {

    public QueryStatusResponse {
        resultMetadata = resultMetadata == null ? Map.of() : resultMetadata;
    }
}
