package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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

        if (!psama.validateOpenAccess(body)) {
            audit.put("auth_result", "failure");
            audit.put("auth_action", "open_access.denied");
            GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "unauthorized", "User is not authorized.");
            return;
        }
        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, hostMarker);
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
