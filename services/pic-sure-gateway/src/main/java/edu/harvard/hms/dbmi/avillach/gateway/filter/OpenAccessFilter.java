package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Handles no-bearer requests when open access is enabled, short-circuiting before {@code PsamaIntrospectionFilter} runs. Triggers when open
 * access is enabled AND {@code Authorization} is blank or ≤ 7 chars ({@code JWTFilter.java:154-157}). The open-access payload is a
 * different shape than introspection ({@code JWTFilter.java:389-394}): {@code { "request": { "Target Service": "<real path>", "query":
 * <body minus resourceCredentials> }, "ipAddress": "OPEN_ACCESS:<host>" }} — no {@code token} field, adds {@code ipAddress}. PSAMA returns
 * a bare boolean: {@code true} grants with username {@code OPEN_ACCESS:<host>}; {@code false} denies with a 401. A real bearer token, or
 * open access disabled, passes through untouched.
 */
public class OpenAccessFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-PICSURE-API-Key";

    /**
     * Set to {@link Boolean#TRUE} only by this filter, only after PSAMA grants the open-access validate. This — not the user-id attribute,
     * which other identity sources may legitimately set — is the one signal {@code PsamaIntrospectionFilter} accepts to skip token
     * introspection.
     */
    public static final String ATTR_OPEN_ACCESS_GRANTED = OpenAccessFilter.class.getName() + ".granted";

    private static final Logger log = LoggerFactory.getLogger(OpenAccessFilter.class);

    private final PsamaClient psama;
    private final AuditContext audit;
    private final ObjectMapper json;
    private final boolean openAccessEnabled;

    public OpenAccessFilter(PsamaClient psama, AuditContext audit, ObjectMapper json, boolean openAccessEnabled) {
        this.psama = psama;
        this.audit = audit;
        this.json = json;
        this.openAccessEnabled = openAccessEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String authz = req.getHeader("Authorization");
        boolean noToken = authz == null || authz.isBlank() || authz.length() <= 7; // JWTFilter.java:154-157

        if (!openAccessEnabled || !noToken) {
            chain.doFilter(req, resp);
            return;
        }

        Map<String, Object> queryMap = buildOpenAccessRequest(req);
        Map<String, Object> body = new HashMap<>();
        body.put("request", queryMap);
        String hostMarker = openAccessIpAddress(req);
        body.put("ipAddress", hostMarker); // NO token field (JWTFilter.java:389-394)
        String apiKey = req.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            body.put("apiKey", apiKey);
        }

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
        // Downstream services select the open HPDS backend from this. They cannot infer it from the user id above:
        // OPEN_ACCESS:<host> is non-blank and so reads as an authenticated user to any presence check.
        req.setAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN);
        audit.put("auth_result", "success");
        audit.put("auth_action", "open_access.granted");
        chain.doFilter(req, resp);
    }

    /** {@code "OPEN_ACCESS:<host>"} marker sent as {@code ipAddress} to PSAMA's open-access validate endpoint (JWTFilter.java:389-394). */
    private static String openAccessIpAddress(HttpServletRequest req) {
        return "OPEN_ACCESS:" + (req.getServerName() == null ? "unknown" : req.getServerName());
    }

    /** Builds the inner {@code { "Target Service", "query" }} request map sent to PSAMA's open-access validate endpoint. */
    private Map<String, Object> buildOpenAccessRequest(HttpServletRequest req) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("Target Service", req.getRequestURI()); // real path
        if (req instanceof BufferedRequestWrapper buffered && buffered.getBody().length > 0) {
            try {
                JsonNode parsed = json.readTree(buffered.getBody());
                stripResourceCredentials(parsed);
                queryMap.put("query", parsed);
            } catch (IOException ignored) {
                // best-effort, mirrors the WAR
            }
        }
        return queryMap;
    }

    private void stripResourceCredentials(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ((ObjectNode) node).remove("resourceCredentials");
            node.fields().forEachRemaining(e -> stripResourceCredentials(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::stripResourceCredentials);
        }
    }
}
