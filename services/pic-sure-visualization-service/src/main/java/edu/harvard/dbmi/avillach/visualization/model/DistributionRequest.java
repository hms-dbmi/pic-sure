package edu.harvard.dbmi.avillach.visualization.model;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import jakarta.validation.constraints.NotNull;

/**
 * The {@code POST /distributions} request body: a bare v3 {@link Query} under {@code query}, and nothing else.
 *
 * <p>The removed resource registry's {@code hpdsResourceUUID} selector is gone: the auth/open backend is chosen by the gateway-owned
 * {@code X-Picsure-Access-Type} header (see {@code AccessTypeResolver}), and query-service picks its HPDS backend from the request path. A
 * client still sending {@code hpdsResourceUUID} (or the v1 envelope's {@code resourceCredentials}) now gets a 400 rather than having the
 * field silently dropped -- the field named a routing decision this service no longer makes, so accepting it quietly would keep the lie
 * alive.
 */
public record DistributionRequest(@NotNull(message = "Request must contain a 'query' field") Query query) {
}
