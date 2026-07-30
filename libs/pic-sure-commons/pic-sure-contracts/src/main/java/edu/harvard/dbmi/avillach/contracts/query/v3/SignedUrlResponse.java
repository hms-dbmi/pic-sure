package edu.harvard.dbmi.avillach.contracts.query.v3;

import io.swagger.v3.oas.annotations.media.Schema;

public record SignedUrlResponse(@Schema(description = "Time-limited URL the caller can download results from") String signedUrl) {
}
