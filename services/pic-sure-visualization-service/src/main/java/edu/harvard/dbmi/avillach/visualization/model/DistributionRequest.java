package edu.harvard.dbmi.avillach.visualization.model;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DistributionRequest(
    // Optional legacy field: the removed resource registry's backend selector. Passed through to HPDS bodies; access
    // type now comes from the gateway's X-User-Id header (see HpdsAccessResolver).
    UUID hpdsResourceUUID, @NotNull(message = "Request must contain a 'query' field") Query query
) {
}
