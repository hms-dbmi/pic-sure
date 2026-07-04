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
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayModeResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowRecord;
import edu.harvard.hms.dbmi.avillach.gateway.shadow.ShadowSupport;
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
 * open access disabled, passes through untouched. Skips interim (result/signed-url) paths still owned by WildFly.
 *
 * <p><b>OBSERVE mode</b> (the parity-verification shadow path): the observe branch is taken per request, and ONLY on the legacy catch-all
 * surface ({@link GatewayModeResolver#observesFor}) with the SAME {@code noToken} precondition as the real {@code validateOpenAccess} call
 * above (a request carrying a real bearer token is left untouched here -- {@code PsamaIntrospectionFilter}'s own observe branch records it
 * on the introspection channel instead). Gateway-owned routes run the real enforce path even in OBSERVE. For an observed catch-all request
 * it builds the same open-access request shape via {@link #buildOpenAccessRequest}, emits one {@code SHADOW_GW} record
 * (channel=open-access), and always forwards unchanged -- no {@code validateOpenAccess} call, no attribute mutation, never a denial.
 * Deliberately NOT gated on {@code openAccessEnabled}: the shadow record captures what the request WOULD look like, independent of this
 * gateway instance's local feature toggle, so a toggle mismatch against WildFly's real configuration surfaces as a divergence rather than
 * being hidden. Any failure while building the shadow request is swallowed -- OBSERVE must never block or alter real traffic.
 */
public class OpenAccessFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OpenAccessFilter.class);

    private final PsamaClient psama;
    private final AuditContext audit;
    private final ObjectMapper json;
    private final GatewayAuthScope scope;
    private final boolean openAccessEnabled;
    private final GatewayModeResolver modeResolver;

    public OpenAccessFilter(
        PsamaClient psama, AuditContext audit, ObjectMapper json, GatewayAuthScope scope, boolean openAccessEnabled,
        GatewayModeResolver modeResolver
    ) {
        this.psama = psama;
        this.audit = audit;
        this.json = json;
        this.scope = scope;
        this.openAccessEnabled = openAccessEnabled;
        this.modeResolver = modeResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return scope.interimOwnedByWildFly(req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        String authz = req.getHeader("Authorization");
        boolean noToken = authz == null || authz.isBlank() || authz.length() <= 7; // JWTFilter.java:154-157

        if (modeResolver.observesFor(req.getRequestURI()) && noToken) {
            observeAndForward(req, resp, chain);
            return;
        }

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

    /**
     * OBSERVE-mode shadow branch (parity verification): builds the open-access request shape via the same {@link #buildOpenAccessRequest}
     * used by the real (ENFORCE) path, emits a {@code SHADOW_GW} record, and always forwards the request unchanged -- no
     * {@code validateOpenAccess} call, no attribute mutation, never a denial. Any failure while building the shadow request is swallowed
     * (logged at debug) so OBSERVE can never block or alter real traffic.
     */
    private void observeAndForward(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws IOException, ServletException {
        try {
            Map<String, Object> queryMap = buildOpenAccessRequest(req);
            ShadowSupport.emit(
                ShadowRecord.gwOpenAccess(
                    correlationId(req), null, (String) queryMap.get("Target Service"), queryMap.get("query"), openAccessIpAddress(req)
                )
            );
        } catch (Exception e) {
            log.debug("observe-mode shadow build failed; forwarding request unchanged", e);
        }
        chain.doFilter(req, resp);
    }

    /** The correlation id {@code CorrelationIdFilter} stashed on the request, or {@code "unknown"} if absent. */
    private static String correlationId(HttpServletRequest req) {
        Object attr = req.getAttribute(ShadowSupport.ATTR_CORRELATION_ID);
        return attr != null ? attr.toString() : "unknown";
    }

    /** {@code "OPEN_ACCESS:<host>"} marker sent as {@code ipAddress} to PSAMA's open-access validate endpoint (JWTFilter.java:389-394). */
    private static String openAccessIpAddress(HttpServletRequest req) {
        return "OPEN_ACCESS:" + (req.getServerName() == null ? "unknown" : req.getServerName());
    }

    /** Builds the inner {@code { "Target Service", "query" }} request map shared by both the real validate call and the OBSERVE shadow. */
    private Map<String, Object> buildOpenAccessRequest(HttpServletRequest req) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("Target Service", req.getRequestURI()); // real path (decision 4)
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
