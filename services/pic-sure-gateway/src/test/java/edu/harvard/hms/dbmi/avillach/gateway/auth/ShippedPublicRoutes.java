package edu.harvard.hms.dbmi.avillach.gateway.auth;

import java.util.List;
import java.util.Set;

import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicRoute.MatchKind;

/**
 * In-code twin of the {@code public-routes} block shipped in {@code application.yml}. Unit tests build their policy from it;
 * {@code PublicRoutesBindingTest} asserts the bound yaml equals it, so the two cannot drift apart unnoticed.
 */
public final class ShippedPublicRoutes {

    private ShippedPublicRoutes() {}

    public static List<PublicRoute> routes() {
        return List.of(
            new PublicRoute("/system/status", MatchKind.EXACT, Set.of("GET"), null, "SYSTEM_MONITOR"),
            new PublicRoute("/openapi.json", MatchKind.SUFFIX, null, null, null),
            new PublicRoute("/operations/banners/active", MatchKind.EXACT, Set.of("GET"), null, null),
            new PublicRoute("/operations/configuration", MatchKind.SINGLE_SEGMENT_CHILD, Set.of("GET"), Set.of("admin"), null),
            prefix("/logging"), prefix("/actuator"), prefix("/openapi"), prefix("/swagger-ui")
        );
    }

    public static PublicRoute prefix(String path) {
        return new PublicRoute(path, MatchKind.PREFIX, null, null, null);
    }
}
