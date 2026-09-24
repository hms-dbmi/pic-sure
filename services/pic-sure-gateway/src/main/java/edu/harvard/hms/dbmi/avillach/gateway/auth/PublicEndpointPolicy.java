package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.List;
import java.util.Optional;

/**
 * Classifies the gateway routes that are intentionally reachable without authentication. Every rule comes from the configured
 * {@link PublicRoute} list ({@code picsure.gateway.security.public-routes}); the first entry that matches decides, and a request no entry
 * matches is protected.
 */
public final class PublicEndpointPolicy {

    private static final Decision PROTECTED = new Decision(false, Optional.empty());

    private final List<PublicRoute> routes;

    public PublicEndpointPolicy(List<PublicRoute> routes) {
        this.routes = routes == null ? List.of() : List.copyOf(routes);
    }

    public Decision evaluate(String method, String path) {
        if (method == null || path == null) {
            return PROTECTED;
        }
        for (PublicRoute route : routes) {
            if (route.matches(method, path)) {
                return new Decision(true, Optional.ofNullable(route.auditUsername()));
            }
        }
        return PROTECTED;
    }

    public record Decision(boolean publicEndpoint, Optional<String> auditUsername) {
    }
}
