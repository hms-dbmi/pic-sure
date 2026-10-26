package edu.harvard.hms.dbmi.avillach.gateway.request;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Edge guard, registered UNCONDITIONALLY before the DB-free authentication filters (see {@code ObservabilityConfig}): rejects any request
 * targeting {@code /operations/internal/**} or the top-level {@code /internal/**} (any depth, including the bare prefixes) with the
 * structured gateway 404 BEFORE routing or the auth chain can call PSAMA. Matching is on the raw request URI, as in {@code RouteSurfaces},
 * so a path that is not exactly one of these prefixes (for example {@code /internalfoo}) passes through. Those endpoints are
 * service-to-service only, gated by {@code X-PIC-SURE-INTERNAL-TOKEN}. They must not be reachable through the public gateway even with a
 * stolen token. A 404 rather than 403 deliberately does not confirm the endpoint exists.
 */
public class InternalEndpointGuardFilter extends OncePerRequestFilter {

    private static final String OPERATIONS_INTERNAL = "/operations/internal";
    private static final String INTERNAL = "/internal";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String path = req.getRequestURI() == null ? "" : req.getRequestURI();
        if (isInternalPath(path)) {
            GatewayErrors.write(resp, HttpStatus.NOT_FOUND, "not_found", "Not found.");
            return;
        }
        chain.doFilter(req, resp);
    }

    private static boolean isInternalPath(String path) {
        return path.equals(OPERATIONS_INTERNAL) || path.startsWith(OPERATIONS_INTERNAL + "/") || path.equals(INTERNAL)
            || path.startsWith(INTERNAL + "/");
    }
}
