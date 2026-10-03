package edu.harvard.dbmi.avillach.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Where a query stands, as PIC-SURE reports it to clients.")
public enum PicSureStatus {
    @Schema(description = "Accepted and waiting for a worker, or waiting to be retried.")
    QUEUED, @Schema(description = "Running on the resource.")
    PENDING, @Schema(description = "Failed. The result will never become available.")
    ERROR, @Schema(description = "Finished. The result can be fetched.")
    AVAILABLE
}
