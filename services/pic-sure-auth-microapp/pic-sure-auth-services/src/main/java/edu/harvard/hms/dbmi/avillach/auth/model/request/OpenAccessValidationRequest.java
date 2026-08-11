package edu.harvard.hms.dbmi.avillach.auth.model.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;

/**
 * The body of {@code POST /open/validate}: the same {@code request} node introspection carries, plus the {@code ipAddress} marker the
 * gateway sends for open access ({@code OpenAccessFilter#openAccessIpAddress} -- {@code "OPEN_ACCESS:<host>"}). Reusing
 * {@link TargetedRequest} is what keeps deployed JsonPath access rules resolving identically on the open path and the authorized path;
 * these rules are database rows, so the node they bind to cannot be re-shaped here.
 *
 * <p><b>Tolerant reader at EVERY level, deliberately.</b> This is the OPEN access path: a request PSAMA refuses to bind is a 400 back to
 * the gateway, which fails the request closed -- a user-visible outage for unauthenticated users. The endpoint previously bound
 * {@code Map<String, Object>} and narrowed it leniently inside {@code AuthorizationService}, so nothing here has ever been rejected for
 * shape. Preserving that under PSAMA's strict-by-default ObjectMapper takes three things, because tolerance at the outer level alone would
 * still have let a nested key deny the request:
 *
 * <ul> <li>{@code ignoreUnknown} here, for unknown keys beside {@code request} and {@code ipAddress};</li> <li>{@code ignoreUnknown} on
 * {@link TargetedRequest} itself, for unknown keys INSIDE {@code request} -- the legacy WAR sent {@code resourceUUID} there;</li> <li>the
 * creator below, for a {@code request} that is not an object at all.</li> </ul>
 *
 * <p>The trade is real and accepted: a client typo in a key is silently ignored here instead of surfacing as a 400, which on this endpoint
 * is the safer failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAccessValidationRequest(TargetedRequest request, String ipAddress) {

    /**
     * Binds {@code request} through a raw node so that a non-object cannot fail the bind. This reproduces exactly what
     * {@code AuthorizationService#asTargetedRequest} did before the endpoint was typed, which is the point: on this path availability
     * outranks strictness, because the alternative to degrading is denying.
     */
    @JsonCreator
    static OpenAccessValidationRequest fromJson(@JsonProperty("request") JsonNode request, @JsonProperty("ipAddress") String ipAddress) {
        return new OpenAccessValidationRequest(asTargetedRequest(request), ipAddress);
    }

    private static TargetedRequest asTargetedRequest(JsonNode request) {
        // Absent, or an explicit null: there is nothing to authorize, which AuthorizationService grants -- unchanged behaviour.
        if (request == null || request.isNull()) {
            return null;
        }
        if (!request.isObject()) {
            // Nothing a rule can bind to. Both components are NON_NULL on the contract, so this serializes to {} and every rule decides
            // on PathNotFoundException -- the same outcome an empty body has always produced. That depends on those NON_NULL
            // annotations: were either component emitted as null, a rule would match null instead and AccessRuleService#evaluateNode
            // would NPE.
            return new TargetedRequest(null, null);
        }
        JsonNode targetService = request.get("Target Service");
        JsonNode query = request.get("query");
        return new TargetedRequest(
            targetService == null || targetService.isNull() ? null : targetService.asText(), query == null || query.isNull() ? null : query
        );
    }
}
