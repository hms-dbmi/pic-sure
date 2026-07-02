package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.auth.GatewayAuthScope;
import edu.harvard.hms.dbmi.avillach.gateway.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.QueryAuthFetcher;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Ports the bulk of the WAR's {@code JWTFilter}: per-request PSAMA token introspection. Runs after {@code OpenAccessFilter} (no-bearer
 * short-circuit) and {@code BufferingFilter} (body buffering). Skips interim result/signed-url paths still owned by WildFly
 * ({@link GatewayAuthScope}). Allow-lists {@code GET /system/status} (audited as {@code SYSTEM_MONITOR}), any path ending in
 * {@code /openapi.json}, and any configured prefix — none of these call PSAMA. Every other request must carry a real {@code Bearer} token
 * (open access is handled entirely upstream by {@code OpenAccessFilter}); the introspection request sends the REAL request path as the
 * root-level {@code "Target Service"} (decision 4 — no canonical-mapping table) and the buffered body (or, for interim paths once they go
 * live in Phase 4, the {@link QueryAuthFetcher} dispatch result) as {@code "query"}, with {@code resourceCredentials} stripped and NEVER a
 * {@code formattedQuery} field (decision 5 — PSAMA only ever uses it for logging, never authorization). If the body is not valid JSON,
 * {@code "query"} is omitted entirely rather than forwarding the raw, unstripped bytes (which could still textually contain
 * {@code resourceCredentials}). On success this filter stashes {@code X-User-*} request attributes — including privileges (decision 7), the
 * real {@code @RolesAllowed} signal — for {@code IdentityPropagationFilter} to turn into outbound headers; on {@code tokenRefreshed} it
 * stashes {@link #ATTR_REFRESHED_TOKEN} for {@code TokenRefreshResponseFilter}; on a returned {@code query} it stashes
 * {@code BodyMutationFilter.ATTR_MUTATED_QUERY} for {@code BodyMutationFilter} to apply — this filter does NOT mutate the body itself.
 * {@code active:false} denies with a 401 additive error body (decision 10). {@link QueryAuthFetcher}/introspection infrastructure failures
 * ({@link PicsureException}, or any introspection transport error) are fail-closed: the mapped status/error body is written and the request
 * is never forwarded.
 */
public class PsamaIntrospectionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PsamaIntrospectionFilter.class);

    public static final String ATTR_REFRESHED_TOKEN = "refreshedToken";

    private final PsamaClient psama;
    private final AuditContext audit;
    private final ObjectMapper json;
    private final QueryAuthFetcher queryAuthFetcher;
    private final GatewayAuthScope scope;
    private final List<String> allowListPrefixes;
    private final String userIdClaim;

    public PsamaIntrospectionFilter(
        PsamaClient psama, AuditContext audit, ObjectMapper json, QueryAuthFetcher queryAuthFetcher, GatewayAuthScope scope,
        List<String> allowListPrefixes, String userIdClaim
    ) {
        this.psama = psama;
        this.audit = audit;
        this.json = json;
        this.queryAuthFetcher = queryAuthFetcher;
        this.scope = scope;
        this.allowListPrefixes = List.copyOf(allowListPrefixes);
        this.userIdClaim = userIdClaim;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return scope.interimOwnedByWildFly(req.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {

        String path = req.getRequestURI() == null ? "" : req.getRequestURI();
        String method = req.getMethod();

        if ("GET".equals(method) && (path.equals("/system/status") || path.equals("/v3/system/status"))) {
            audit.put("username", "SYSTEM_MONITOR");
            chain.doFilter(req, resp);
            return;
        }
        if (path.endsWith("/openapi.json")) {
            chain.doFilter(req, resp);
            return;
        }
        for (String prefix : allowListPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                chain.doFilter(req, resp);
                return;
            }
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

        Map<String, Object> requestMeta;
        try {
            requestMeta = buildRequestMeta(req, path);
        } catch (PicsureException e) {
            // QueryAuthFetcher fail-closed (decision 8/10): honest status + additive error body.
            mapPicsureException(resp, e);
            return;
        }

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

        if (intro == null || !intro.active()) {
            denied(resp, "invalid_token", "Token invalid or expired.");
            return;
        }

        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, intro.userId());
        req.setAttribute(GatewayUserResolver.HEADER_USER_SUBJECT, intro.sub());
        req.setAttribute(GatewayUserResolver.HEADER_USER_EMAIL, intro.email());
        req.setAttribute(GatewayUserResolver.HEADER_USER_ROLES, intro.roles() == null ? "" : intro.roles());
        // decision 7: privileges are the real @RolesAllowed signal — propagate them.
        req.setAttribute(
            GatewayUserResolver.HEADER_USER_PRIVILEGES, intro.privileges() == null ? "" : String.join(",", intro.privileges())
        );
        audit.put("auth_result", "success");

        if (Boolean.TRUE.equals(intro.tokenRefreshed()) && intro.token() != null) {
            req.setAttribute(ATTR_REFRESHED_TOKEN, intro.token());
        }
        if (intro.query() != null) {
            req.setAttribute(BodyMutationFilter.ATTR_MUTATED_QUERY, intro.query());
        }

        chain.doFilter(req, resp);
    }

    private void denied(HttpServletResponse resp, String reason, String message) throws IOException {
        audit.put("auth_result", "failure");
        audit.put("auth_failure_reason", reason);
        GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, "unauthorized", message);
    }

    private void mapPicsureException(HttpServletResponse resp, PicsureException e) throws IOException {
        audit.put("auth_result", "failure");
        audit.put("auth_failure_reason", "dispatch_error");
        GatewayErrors.write(resp, e.getStatus(), e.getErrorType(), e.getMessage());
    }

    private Map<String, Object> buildRequestMeta(HttpServletRequest req, String path) {
        Map<String, Object> requestMeta = new HashMap<>();
        // decision 4: send the REAL request path verbatim, root-level. No canonical-mapping table.
        requestMeta.put("Target Service", path);

        // result/signed-url: fetch the stored query over HTTP (DB-free). Dormant in Phase 2 (shouldNotFilter
        // skips these paths); live at the Phase 4 cutover when GatewayAuthScope no longer marks them interim.
        Optional<String> storedQuery = queryAuthFetcher.queryJsonForPath(path); // may throw PicsureException (fail-closed)
        try {
            if (storedQuery.isPresent()) {
                JsonNode stored = json.readTree(storedQuery.get());
                stripResourceCredentials(stored);
                requestMeta.put("query", stored);
            } else if (req instanceof BufferedRequestWrapper buffered && buffered.getBody().length > 0) {
                JsonNode body = json.readTree(buffered.getBody());
                stripResourceCredentials(body);
                requestMeta.put("query", body);
            }
        } catch (IOException e) {
            // Do NOT forward the raw, unstripped body: a malformed body may still textually contain
            // resourceCredentials secrets, and we have no parsed JSON to strip them from. Omit "query"
            // entirely rather than risk leaking it to PSAMA. Never log body content here.
            log.debug("request body was not valid JSON; omitting query from introspection payload");
        }
        // NO formattedQuery (decision 5): PSAMA uses it only for access-log strings, never for authorization.
        return requestMeta;
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
