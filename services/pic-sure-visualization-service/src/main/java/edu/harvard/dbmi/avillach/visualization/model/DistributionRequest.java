package edu.harvard.dbmi.avillach.visualization.model;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /distributions} request body. The removed resource registry's {@code hpdsResourceUUID} selector is gone: the auth/open
 * backend is chosen by the gateway-owned {@code X-Picsure-Access-Type} header (see {@code AccessTypeResolver}), and query-service picks its
 * HPDS backend from the request path. Clients still sending the old field are unaffected -- Spring Boot leaves Jackson's
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so it is ignored rather than rejected.
 */
public record DistributionRequest(@NotNull(message = "Request must contain a 'query' field") Query query) {
}
