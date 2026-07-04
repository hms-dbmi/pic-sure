package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

/**
 * Classifies a request path as a gateway-OWNED route surface (a direct route to a new backend with no WildFly counterpart) or the legacy
 * catch-all surface (forwarded to WildFly). Drives the per-request OBSERVE split in {@code GatewayModeResolver}: owned surfaces stay fully
 * enforced during an observe window; only the catch-all is observed (shadow-logged, forwarded unchanged) since WildFly still enforces
 * there.
 *
 * <p>Matching is SEGMENT-SAFE: a prefix matches only on an exact equal or a following {@code /} boundary ({@code equals(prefix)} or
 * {@code startsWith(prefix + "/")}) — never a bare {@code startsWith}. So {@code /logging} covers {@code /logging} and {@code /logging/x}
 * but NOT {@code /loggingAdmin/x}, which is a different route.
 */
public final class RouteSurfaces {

    private final List<String> ownedPrefixes;

    public RouteSurfaces(List<String> ownedPrefixes) {
        this.ownedPrefixes = List.copyOf(ownedPrefixes);
    }

    /** A {@code RouteSurfaces} over {@link RouteSurfaceProperties#DEFAULT_OWNED_PREFIXES} — for tests and non-Spring callers. */
    public static RouteSurfaces withDefaults() {
        return new RouteSurfaces(RouteSurfaceProperties.DEFAULT_OWNED_PREFIXES);
    }

    /** True iff {@code path} is on a gateway-owned route surface (segment-safe prefix match). Null/blank → not owned. */
    public boolean isOwned(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        for (String prefix : ownedPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** True iff {@code path} is on the legacy catch-all surface (everything not gateway-owned). */
    public boolean isCatchAll(String path) {
        return !isOwned(path);
    }

    List<String> ownedPrefixes() {
        return ownedPrefixes;
    }
}
