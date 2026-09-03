package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Performs per-request PSAMA token introspection. Runs after {@code OpenAccessFilter} (no-bearer short-circuit) and {@code BufferingFilter}
 * (body buffering). The shared {@link PublicEndpointPolicy} exempts intentionally public routes from both authentication filters. Every
 * other request must carry a real {@code Bearer} token (open access is handled entirely upstream by {@code OpenAccessFilter}); the
 * introspection request sends the decoded path that Spring resolved as the root-level {@code "Target Service"}. It does not send or mutate
 * the request body. On success this filter stashes {@code X-User-*} request attributes, including privileges, for
 * {@code IdentityPropagationFilter} to turn into outbound headers. On token refresh it stashes {@link #ATTR_REFRESHED_TOKEN} for
 * {@code TokenRefreshResponseFilter}. An {@code active:false} response denies access. Introspection infrastructure failures
 * ({@link PicsureException}, or any transport error) fail closed: the mapped error is written and the request is never forwarded.
 */
public class PsamaIntrospectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PsamaIntrospectionFilter.class);

    public static final String ATTR_REFRESHED_TOKEN = "refreshedToken";

    private final PsamaClient psama;
    private final AuditContext audit;
    private final PublicEndpointPolicy publicEndpoints;

    public PsamaIntrospectionFilter(PsamaClient psama, AuditContext audit, PublicEndpointPolicy publicEndpoints) {
        this.psama = psama;
        this.audit = audit;
        this.publicEndpoints = publicEndpoints;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {

        String requestUri = req.getRequestURI();
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(req);
        String method = req.getMethod();

        PublicEndpointPolicy.Decision publicEndpoint = publicEndpoints.evaluate(method, requestUri);
        if (publicEndpoint.publicEndpoint()) {
            publicEndpoint.auditUsername().ifPresent(username -> audit.put("username", username));
            chain.doFilter(req, resp);
            return;
        }

        // OpenAccessFilter (order 20) already authenticated this no-bearer request via PSAMA's open-access
        // validate; demanding a Bearer token here would veto that grant. Only its dedicated grant attribute
        // skips introspection -- a user-id attribute alone (any other identity source) must still be introspected.
        if (Boolean.TRUE.equals(req.getAttribute(OpenAccessFilter.ATTR_OPEN_ACCESS_GRANTED))) {
            chain.doFilter(req, resp);
            return;
        }

        String authz = req.getHeader("Authorization");
        if (authz == null || authz.isBlank()) {
            denied(resp, "missing_token", "No authorization header found.");
            return;
        }
        if (!authz.startsWith("Bearer ")) {
            denied(resp, "malformed_token", "Authorization header is not a Bearer token.");
            return;
        }
        String token = authz.substring(7).trim();
        if (token.isEmpty()) {
            denied(resp, "empty_token", "No token found in authorization header.");
            return;
        }

        Map<String, Object> requestMeta = Map.of("Target Service", path);

        IntrospectionResponse intro;
        try {
            intro = psama.introspect(token, requestMeta);
        } catch (PicsureException e) {
            // Preserve the existing PicsureException mapping (status/errorType from the exception) rather than
            // flattening it into a generic 502 below.
            mapPicsureException(resp, e);
            return;
        } catch (Exception e) {
            log.error("PSAMA introspection failed", e);
            audit.put("auth_result", "failure");
            audit.put("auth_failure_reason", "introspection_unreachable");
            GatewayErrors.write(resp, HttpStatus.BAD_GATEWAY, "introspection_unreachable", "Token introspection failed.");
            return;
        }

        if (intro != null && !intro.active() && intro.sub() != null && intro.message() != null && !intro.message().isBlank()) {
            forbidden(resp, intro.message());
            return;
        }
        if (intro == null || !intro.active()) {
            denied(resp, "invalid_token", "Token invalid or expired.");
            return;
        }

        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, intro.userId());
        req.setAttribute(GatewayUserResolver.HEADER_USER_SUBJECT, intro.sub());
        req.setAttribute(GatewayUserResolver.HEADER_USER_EMAIL, intro.email());
        req.setAttribute(GatewayUserResolver.HEADER_USER_ROLES, intro.roles() == null ? "" : intro.roles());
        // privileges are the real @RolesAllowed signal — propagate them.
        req.setAttribute(
            GatewayUserResolver.HEADER_USER_PRIVILEGES, intro.privileges() == null ? "" : String.join(",", intro.privileges())
        );
        // Record that token introspection admitted this request. Backend routing remains path-based.
        req.setAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED);
        audit.put("auth_result", "success");

        if (Boolean.TRUE.equals(intro.tokenRefreshed()) && intro.token() != null) {
            req.setAttribute(ATTR_REFRESHED_TOKEN, intro.token());
        }
        chain.doFilter(req, resp);
    }

    private void denied(HttpServletResponse resp, String reason, String message) throws IOException {
        audit.put("auth_result", "failure");
        audit.put("auth_failure_reason", reason);
        GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    private void forbidden(HttpServletResponse resp, String message) throws IOException {
        audit.put("auth_result", "failure");
        audit.put("auth_failure_reason", "authorization_denied");
        GatewayErrors.write(resp, HttpStatus.FORBIDDEN, "forbidden", message);
    }

    private void mapPicsureException(HttpServletResponse resp, PicsureException e) throws IOException {
        audit.put("auth_result", "failure");
        audit.put("auth_failure_reason", "dispatch_error");
        GatewayErrors.write(resp, e.getStatus(), e.getErrorType(), e.getMessage());
    }

}
