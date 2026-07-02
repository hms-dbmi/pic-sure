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

import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * SCG MVC forwards via a {@code HandlerFunction}; request attributes don't become outbound headers automatically. This filter wraps the
 * request and adds the {@code X-User-*} headers from the attributes the introspection/open-access filters set — including
 * {@code X-User-Privileges} (decision 7) — plus {@code X-Request-Id} (propagate the incoming one, or generate). Downstream maps
 * {@code X-User-Privileges} to {@code GrantedAuthority}s for {@code @RolesAllowed}-equivalent checks.
 */
public class IdentityPropagationFilter extends OncePerRequestFilter {

    static final String HEADER_REQUEST_ID = "X-Request-Id"; // commons RequestIdFilter owns generation

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        chain.doFilter(new IdentityHeadersRequest(req), resp);
    }

    static class IdentityHeadersRequest extends HttpServletRequestWrapper {
        private final Map<String, String> overrides = new LinkedHashMap<>();

        IdentityHeadersRequest(HttpServletRequest req) {
            super(req);
            put(GatewayUserResolver.HEADER_USER_ID, attr(req, GatewayUserResolver.HEADER_USER_ID));
            put(GatewayUserResolver.HEADER_USER_SUBJECT, attr(req, GatewayUserResolver.HEADER_USER_SUBJECT));
            put(GatewayUserResolver.HEADER_USER_EMAIL, attr(req, GatewayUserResolver.HEADER_USER_EMAIL));
            put(GatewayUserResolver.HEADER_USER_ROLES, attr(req, GatewayUserResolver.HEADER_USER_ROLES));
            put(GatewayUserResolver.HEADER_USER_PRIVILEGES, attr(req, GatewayUserResolver.HEADER_USER_PRIVILEGES));
            String requestId = req.getHeader(HEADER_REQUEST_ID);
            put(HEADER_REQUEST_ID, requestId != null && !requestId.isEmpty() ? requestId : UUID.randomUUID().toString());
        }

        private static String attr(HttpServletRequest r, String key) {
            Object v = r.getAttribute(key);
            return v == null ? null : v.toString();
        }

        private void put(String k, String v) {
            if (v != null && !v.isEmpty()) overrides.put(k, v);
        }

        @Override
        public String getHeader(String name) {
            String v = overrides.get(name);
            return v != null ? v : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.addAll(overrides.keySet());
            return Collections.enumeration(names);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String v = overrides.get(name);
            if (v != null) return Collections.enumeration(List.of(v));
            return super.getHeaders(name);
        }
    }
}
