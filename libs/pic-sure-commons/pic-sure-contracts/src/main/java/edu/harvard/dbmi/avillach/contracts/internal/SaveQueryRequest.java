package edu.harvard.dbmi.avillach.contracts.internal;

import edu.harvard.dbmi.avillach.contracts.query.v3.PicSureStatus;

/**
 * Body of {@code POST /internal/queries}: the caller hands the query store everything needed to persist a new row.
 *
 * <p>This is an INTERNAL contract -- the endpoint sits behind {@code X-PIC-SURE-INTERNAL-TOKEN} and network isolation, and no external
 * client is expected to ever construct one.
 */
public record SaveQueryRequest(
    String query,
    //
    String resourceResultId, PicSureStatus status, String version, String metadata
) {
}
