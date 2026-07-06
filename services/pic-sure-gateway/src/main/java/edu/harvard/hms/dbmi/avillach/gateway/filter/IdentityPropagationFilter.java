package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SCG MVC forwards via a {@code HandlerFunction}; request attributes don't become outbound headers automatically. This filter wraps the
 * request and adds the {@code X-User-*} headers from the attributes the introspection/open-access filters set — including
 * {@code X-User-Privileges} (decision 7) — plus {@code X-Request-Id} (propagate the incoming one, reuse the commons
 * {@link RequestIdFilter}'s MDC value, or generate). Downstream maps {@code X-User-Privileges} to {@code GrantedAuthority}s for
 * {@code @RolesAllowed}-equivalent checks. <p> The five {@code X-User-*} headers ({@link GatewayUserResolver#HEADER_USER_ID},
 * {@code _SUBJECT}, {@code _EMAIL}, {@code _ROLES}, {@code _PRIVILEGES}) are gateway-owned: the wrapper NEVER falls through to the raw
 * client request for them. Whatever the gateway resolved (possibly nothing) is authoritative -- a client cannot spoof these by sending its
 * own values, even where the gateway resolved an empty/null value (e.g. open-access requests, users with no privileges).
 */
public class IdentityPropagationFilter extends OncePerRequestFilter {

    static final String HEADER_REQUEST_ID = "X-Request-Id"; // commons RequestIdFilter owns generation

    private final GatewayModeResolver modeResolver;

    public IdentityPropagationFilter(GatewayModeResolver modeResolver) {
        this.modeResolver = modeResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        if (!modeResolver.enforcesFor(req.getRequestURI())) {
            chain.doFilter(req, resp);
            return;
        }
        chain.doFilter(new IdentityHeadersRequest(req), resp);
    }

    static class IdentityHeadersRequest extends HttpServletRequestWrapper {

        /** Gateway-owned identity headers: never sourced from the raw client request, regardless of name casing. */
        private static final Set<String> GATEWAY_OWNED_HEADERS = Set.of(
            GatewayUserResolver.HEADER_USER_ID, GatewayUserResolver.HEADER_USER_SUBJECT, GatewayUserResolver.HEADER_USER_EMAIL,
            GatewayUserResolver.HEADER_USER_ROLES, GatewayUserResolver.HEADER_USER_PRIVILEGES
        );

        private final Map<String, String> overrides = new LinkedHashMap<>();

        IdentityHeadersRequest(HttpServletRequest req) {
            super(req);
            put(GatewayUserResolver.HEADER_USER_ID, attr(req, GatewayUserResolver.HEADER_USER_ID));
            put(GatewayUserResolver.HEADER_USER_SUBJECT, attr(req, GatewayUserResolver.HEADER_USER_SUBJECT));
            put(GatewayUserResolver.HEADER_USER_EMAIL, attr(req, GatewayUserResolver.HEADER_USER_EMAIL));
            put(GatewayUserResolver.HEADER_USER_ROLES, attr(req, GatewayUserResolver.HEADER_USER_ROLES));
            put(GatewayUserResolver.HEADER_USER_PRIVILEGES, attr(req, GatewayUserResolver.HEADER_USER_PRIVILEGES));
            put(HEADER_REQUEST_ID, resolveRequestId(req));
        }

        private static String resolveRequestId(HttpServletRequest req) {
            String requestId = req.getHeader(HEADER_REQUEST_ID);
            if (requestId != null && !requestId.isEmpty()) {
                return requestId;
            }
            // No inbound header: reuse the id the commons RequestIdFilter (runs earlier, highest precedence) already put in
            // MDC so response headers, access logs, downstream headers, and audit records all correlate under one id.
            String mdcRequestId = MDC.get(RequestIdFilter.MDC_KEY);
            if (mdcRequestId != null && !mdcRequestId.isEmpty()) {
                return mdcRequestId;
            }
            return UUID.randomUUID().toString();
        }

        private static String attr(HttpServletRequest r, String key) {
            Object v = r.getAttribute(key);
            return v == null ? null : v.toString();
        }

        private void put(String k, String v) {
            if (v != null && !v.isEmpty()) overrides.put(k, v);
        }

        /** Returns the canonical gateway-owned header name matching {@code name} case-insensitively, or {@code null} if not owned. */
        private static String ownedCanonicalName(String name) {
            for (String owned : GATEWAY_OWNED_HEADERS) {
                if (owned.equalsIgnoreCase(name)) return owned;
            }
            return null;
        }

        @Override
        public String getHeader(String name) {
            String owned = ownedCanonicalName(name);
            if (owned != null) {
                // Gateway-owned: authoritative even when unresolved (null) -- never fall through to the client's request.
                return overrides.get(owned);
            }
            String v = overrides.get(name);
            return v != null ? v : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> superNames = super.getHeaderNames();
            while (superNames != null && superNames.hasMoreElements()) {
                String n = superNames.nextElement();
                if (ownedCanonicalName(n) == null) {
                    // Drop any client-supplied gateway-owned header name; only the resolved value (added below, if any) may appear.
                    names.add(n);
                }
            }
            names.addAll(overrides.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String owned = ownedCanonicalName(name);
            if (owned != null) {
                String v = overrides.get(owned);
                return v != null ? Collections.enumeration(List.of(v)) : Collections.emptyEnumeration();
            }
            String v = overrides.get(name);
            if (v != null) return Collections.enumeration(List.of(v));
            return super.getHeaders(name);
        }
    }
}
