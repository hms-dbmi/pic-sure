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
 * Edge guard, registered UNCONDITIONALLY right after {@link InboundIdentityHeaderSanitizingFilter} (see {@code ObservabilityConfig}):
 * rejects any request targeting {@code /operations/internal/**} (any depth) with the structured gateway 404 BEFORE routing or the auth
 * chain ever sees it. Those endpoints are service-to-service only, gated by {@code X-PIC-SURE-INTERNAL-TOKEN}. They must not be reachable
 * through the public gateway even with a stolen token. A 404 rather than 403 deliberately does not confirm the endpoint exists.
 */
public class InternalEndpointGuardFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String path = req.getRequestURI() == null ? "" : req.getRequestURI();
        if (path.equals("/operations/internal") || path.startsWith("/operations/internal/")) {
            GatewayErrors.write(resp, HttpStatus.NOT_FOUND, "not_found", "Not found.");
            return;
        }
        chain.doFilter(req, resp);
    }
}
