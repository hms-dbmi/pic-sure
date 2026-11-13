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
 * <p><b>Do not match a raw request URI for a security decision.</b> This class compares the string it is given and decodes nothing, so a
 * caller that passes the raw {@code getRequestURI()} can be evaded. Spring Security's default
 * {@link org.springframework.security.web.firewall.StrictHttpFirewall} rejects matrix parameters (e.g. {@code /hpds;x=y}), encoded slashes
 * ({@code %2F}) and double slashes ({@code //}), but it lets a percent-encoded letter through: {@code /%68pds/x} reaches the filters, reads
 * here as unowned, and is then decoded and routed as {@code /hpds/x}. A filter that allows, denies or strips by path must match the decoded
 * path from {@link edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath}, which resolves the path the way routing does. The
 * route drift-guard test passes configured route patterns, not request URIs, so it is not affected.
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
