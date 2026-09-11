package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles no-bearer requests when open access is enabled, short-circuiting before {@code PsamaIntrospectionFilter} runs. Triggers when open
 * access is enabled and {@code Authorization} is blank or at most 7 characters. The open-access payload contains the decoded path Spring
 * resolved as {@code "Target Service"} plus {@code ipAddress}; it does not contain a token or request body. PSAMA returns a bare boolean.
 * Routes selected by the shared {@link PublicEndpointPolicy} or disabled open access pass through untouched.
 *
 * <p>Requests under {@code open-path-prefixes} take this path even when a bearer token is present. The token is never read. The path
 * decides openness, not the client's header, so this grants less than introspection would.
 */
public class OpenAccessFilter extends OncePerRequestFilter {

    /**
     * Set to {@link Boolean#TRUE} only by this filter, only after PSAMA grants the open-access validate. This — not the user-id attribute,
     * which other identity sources may legitimately set — is the one signal {@code PsamaIntrospectionFilter} accepts to skip token
     * introspection.
     */
    public static final String ATTR_OPEN_ACCESS_GRANTED = OpenAccessFilter.class.getName() + ".granted";

    private static final Logger log = LoggerFactory.getLogger(OpenAccessFilter.class);

    private final PsamaClient psama;
    private final AuditContext audit;
    private final boolean openAccessEnabled;
    private final PublicEndpointPolicy publicEndpoints;
    private final List<String> openPathPrefixes;

    public OpenAccessFilter(
        PsamaClient psama, AuditContext audit, boolean openAccessEnabled, PublicEndpointPolicy publicEndpoints,
        List<String> openPathPrefixes
    ) {
        this.psama = psama;
        this.audit = audit;
        this.openAccessEnabled = openAccessEnabled;
        this.publicEndpoints = publicEndpoints;
        this.openPathPrefixes = openPathPrefixes == null ? List.of() : List.copyOf(openPathPrefixes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        if (publicEndpoints.evaluate(req.getMethod(), req.getRequestURI()).publicEndpoint()) {
            chain.doFilter(req, resp);
            return;
        }

        String authz = req.getHeader("Authorization");
        boolean noToken = authz == null || authz.isBlank() || authz.length() <= 7; // Missing or empty Bearer value.

        if (!openAccessEnabled || (!noToken && !isOpenPath(pathWithinApplication(req)))) {
            chain.doFilter(req, resp);
            return;
        }

        Map<String, Object> queryMap = buildOpenAccessRequest(req);
        Map<String, Object> body = new HashMap<>();
        body.put("request", queryMap);
        String hostMarker = openAccessIpAddress(req);
        body.put("ipAddress", hostMarker); // Open-access validation sends no token field.

        boolean granted;
        try {
            granted = psama.validateOpenAccess(body);
        } catch (Exception e) {
            // Mirror PsamaIntrospectionFilter's transport-failure handling: keep the gateway error shape
            // (rather than Spring's default 500) and record the same audit failure the deny path records.
            log.error("PSAMA open-access validation failed", e);
            audit.put("auth_result", "failure");
            audit.put("auth_action", "open_access.denied");
            audit.put("auth_failure_reason", "open_access_unreachable");
            GatewayErrors.write(resp, HttpStatus.BAD_GATEWAY, "open_access_unreachable", "Open access validation failed.");
            return;
        }
        if (!granted) {
            audit.put("auth_result", "failure");
            audit.put("auth_action", "open_access.denied");
            GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "unauthorized", "User is not authorized.");
            return;
        }
        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, hostMarker);
        req.setAttribute(ATTR_OPEN_ACCESS_GRANTED, Boolean.TRUE);
        // Record that the open-access flow admitted this request. Backend routing remains path-based.
        req.setAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN);
        audit.put("auth_result", "success");
        audit.put("auth_action", "open_access.granted");
        chain.doFilter(req, resp);
    }

    /**
     * Segment-boundary prefix match, the same rule {@code PublicEndpointPolicy} applies to the introspection allow-list. {@code /hpds/open}
     * matches itself and anything below it, never {@code /hpds/openx}. Matching uses the decoded path that the open-access payload carries,
     * so the gateway and PSAMA agree on which path was validated.
     */
    private boolean isOpenPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : openPathPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String pathWithinApplication(HttpServletRequest req) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(req);
    }

    /** Builds the {@code "OPEN_ACCESS:<host>"} marker sent as {@code ipAddress} to PSAMA's open-access validation endpoint. */
    private static String openAccessIpAddress(HttpServletRequest req) {
        return "OPEN_ACCESS:" + (req.getServerName() == null ? "unknown" : req.getServerName());
    }

    /** Builds the inner {@code { "Target Service" }} request map sent to PSAMA's open-access validate endpoint. */
    private Map<String, Object> buildOpenAccessRequest(HttpServletRequest req) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("Target Service", pathWithinApplication(req));
        return queryMap;
    }
}
