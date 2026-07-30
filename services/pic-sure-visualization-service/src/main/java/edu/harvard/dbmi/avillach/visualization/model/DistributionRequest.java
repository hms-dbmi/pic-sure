package edu.harvard.dbmi.avillach.visualization.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /distributions} request body. The removed resource registry's {@code hpdsResourceUUID} selector is gone: the auth/open
 * backend is chosen by the gateway-owned {@code X-Picsure-Access-Type} header (see {@code AccessTypeResolver}), and query-service picks its
 * HPDS backend from the request path. Clients still sending the old field are unaffected -- it is ignored rather than rejected.
 */
// TODO(well-defined-contracts): remove with Task 11 (viz bare Query + binning/info). StrictWebDeserializationConfig now makes every request
// body reject unmodelled properties; this endpoint is retyped to the contracts-module request shape in Task 11, at which point legacy
// clients stop sending hpdsResourceUUID and this opt-out goes away with it.
@JsonIgnoreProperties(ignoreUnknown = true)
public record DistributionRequest(@NotNull(message = "Request must contain a 'query' field") Query query) {
}
