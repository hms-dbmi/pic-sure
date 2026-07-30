package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of {@code POST /internal/queries}: the caller hands the query store everything needed to persist a new row.
 *
 * <p>This is an INTERNAL contract -- the endpoint sits behind {@code X-PIC-SURE-INTERNAL-TOKEN} and network isolation, and no external
 * client is expected to ever construct one.
 */
@Schema(description = "Request to persist a new query in the internal query store")
public record SaveQueryRequest(
    @Schema(description = "Canonical bare v3 Query JSON; opaque to the store") String query,
    @Schema(description = "Result id assigned by the backing resource; null when the query has not been dispatched yet") //
    String resourceResultId,
    @Schema(
        description = "Initial PIC-SURE status; travels as the enum NAME, never its ordinal", allowableValues = {
            "QUEUED", "PENDING", "ERROR", "AVAILABLE"}
    ) PicSureStatus status, @Schema(description = "Query-format version this query was written against, e.g. \"v3\"") String version,
    @Schema(description = "base64-encoded metadata bytes") String metadata
){
}
