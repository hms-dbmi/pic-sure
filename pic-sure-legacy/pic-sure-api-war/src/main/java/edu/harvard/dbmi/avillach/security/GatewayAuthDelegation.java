package edu.harvard.dbmi.avillach.security;

import java.util.regex.Pattern;

/**
 * WAR-side mirror of the gateway's {@code GatewayAuthScope} (Option A, decision 11 / P-M2 of the gateway rewrite spec). Decides whether
 * WildFly must bypass its own auth/audit for a given request path because the gateway already handled it.
 * <p>
 * Two independently-configured flags govern the decision:
 * <ul>
 * <li>{@code GATEWAY_OWNS_AUTH} - master switch (java:global/gatewayOwnsAuth, see {@link edu.harvard.dbmi.avillach.PicSureWarInit}).
 * {@code false} (the default, and how the WAR must ship before the gateway's auth/audit chain is live) means WildFly owns
 * authentication and auditing for every request, exactly as it always has. {@code true} means the gateway is the trust boundary for
 * every route EXCEPT the query-read paths below.</li>
 * <li>{@code GATEWAY_OWNS_QUERY_READ_AUTH} - only consulted once {@code GATEWAY_OWNS_AUTH} is true. {@code false} (Phase 2) keeps
 * {@code result}/{@code signed-url} owned by WildFly's full {@code JWTFilter}/{@code AuditLoggingFilter} path, interim until the
 * gateway's query-auth fetch (Phase 4) goes live and flips this to {@code true}.</li>
 * </ul>
 * The query-read regex mirrors the gateway's {@code GatewayAuthScope} query-read pattern and the result/signed-url path detection
 * already used in {@code JWTFilter.prepareRequestMap}, so the two sides agree on exactly where the boundary sits.
 */
public final class GatewayAuthDelegation {

    // (/v<digits>)?/query/{id}/(result|signed-url) with optional trailing slash.
    static final Pattern QUERY_READ_PATHS = Pattern.compile(".*/query/[^/]+/(?:result|signed-url)/?$");

    private final boolean gatewayOwnsAuth;
    private final boolean gatewayOwnsQueryReadAuth;

    public GatewayAuthDelegation(boolean gatewayOwnsAuth, boolean gatewayOwnsQueryReadAuth) {
        this.gatewayOwnsAuth = gatewayOwnsAuth;
        this.gatewayOwnsQueryReadAuth = gatewayOwnsQueryReadAuth;
    }

    /**
     * True when the gateway already authenticated and audited this request, so WildFly must bypass its own {@code JWTFilter} /
     * {@code AuditLoggingFilter} logic and trust the gateway-set {@code X-User-*} headers instead ({@code GatewayHeaderFilter} rebuilds
     * the {@code SecurityContext} from them).
     */
    public boolean gatewayOwnsAuth(String path) {
        if (!gatewayOwnsAuth) {
            return false; // legacy behavior: WildFly owns everything
        }
        if (path == null || path.isBlank()) {
            // Fail CLOSED: an unresolvable path must not silently default to gateway-owned, or WildFly would skip its
            // own JWTFilter/AuditLoggingFilter for a request it can't even identify -- a gap in the audit trail.
            // WildFly authenticates and audits instead, exactly as it would with the master switch off.
            return false;
        }
        if (QUERY_READ_PATHS.matcher(path).matches()) {
            return gatewayOwnsQueryReadAuth; // Phase 2: false -> WildFly still owns result/signed-url
        }
        return true; // all other paths are gateway-owned once GATEWAY_OWNS_AUTH is true
    }
}
