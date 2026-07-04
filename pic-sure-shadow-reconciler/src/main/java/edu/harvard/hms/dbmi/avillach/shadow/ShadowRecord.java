package edu.harvard.hms.dbmi.avillach.shadow;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One shadow-log record, as emitted verbatim (minified JSON, one per line) by either the gateway (side "GW") or WildFly (side "WF"). This
 * schema is copied verbatim from the plan's Global Constraints — it is a shared contract between three independently-built components
 * (gateway, WildFly, reconciler); do not drift from it here.
 *
 * <p>Deliberately defined locally rather than imported from the gateway module: the reconciler must not depend on gateway code so a gateway
 * bug can never trivially "match itself".
 */
public record ShadowRecord(
    String side, String correlationId, String channel, String tokenHash, String targetService, JsonNode query,
    boolean formattedQueryPresent, String ipAddress, String decision
) {
}
