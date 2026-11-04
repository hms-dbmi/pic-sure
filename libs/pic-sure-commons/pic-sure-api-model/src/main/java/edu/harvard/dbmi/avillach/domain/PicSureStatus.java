package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The status of a query.")
public enum PicSureStatus {
    @Schema(description = "Accepted and waiting for a worker, or waiting to be retried.")
    QUEUED, @Schema(description = "Running on the resource.")
    PENDING, @Schema(description = "Failed. The result will never become available.")
    ERROR, @Schema(description = "Finished. The result can be fetched.")
    AVAILABLE
}
