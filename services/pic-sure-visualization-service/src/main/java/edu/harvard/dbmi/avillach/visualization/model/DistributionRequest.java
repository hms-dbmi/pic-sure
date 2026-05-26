package edu.harvard.dbmi.avillach.visualization.model;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record DistributionRequest(
    @NotNull(message = "Request must contain an 'hpdsResourceUUID' field") UUID hpdsResourceUUID,
    @NotNull(message = "Request must contain a 'query' field") Query query
) {
}
