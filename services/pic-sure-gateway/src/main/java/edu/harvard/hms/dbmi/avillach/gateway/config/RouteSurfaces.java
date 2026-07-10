package edu.harvard.hms.dbmi.avillach.gateway.config;

import java.util.List;

/**
 * Classifies a request path as a gateway-OWNED route surface (a direct route to a backend) or unowned (unmatched, and so 404s -- there is
 * no catch-all route). Used by the route drift-guard test to assert every configured route is covered by the default owned prefixes.
 *
 * <p>Matching is SEGMENT-SAFE: a prefix matches only on an exact equal or a following {@code /} boundary ({@code equals(prefix)} or
 * {@code startsWith(prefix + "/")}) — never a bare {@code startsWith}. So {@code /logging} covers {@code /logging} and {@code /logging/x}
 * but NOT {@code /loggingAdmin/x}, which is a different route.
 *
 * <p><b>Raw-requestURI safety.</b> Callers match on the raw {@code getRequestURI()} (not a decoded/normalized path). That is safe against
 * matrix-parameter (e.g. {@code /hpds;x=y}), encoded-slash ({@code %2F}) and double-slash ({@code //}) evasion ONLY because Spring
 * Security's default {@link org.springframework.security.web.firewall.StrictHttpFirewall} rejects those requests BEFORE the auth filters
 * run — so a path that reaches this matcher has already been normalized/blocked upstream. A future custom {@code HttpFirewall} that relaxes
 * those defaults would reopen the seam (a request could then present a raw URI that this segment-safe match reads as unowned/owned
 * differently than the backend resolves it); revisit this matching if the firewall is ever relaxed.
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

    /** True iff {@code path} is unowned (everything not on a gateway-owned route surface -- unmatched paths 404). */
    public boolean isCatchAll(String path) {
        return !isOwned(path);
    }

    List<String> ownedPrefixes() {
        return ownedPrefixes;
    }
}
