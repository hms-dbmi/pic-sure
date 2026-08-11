package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A time-limited URL from which query results can be downloaded directly")
public record SignedUrlResponse(@Schema(description = "Time-limited URL the caller can download results from") String signedUrl) {
}
