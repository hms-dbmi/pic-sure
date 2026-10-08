package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.OpenAccessValidation;
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
 * resolved as {@code "Target Service"} plus {@code ipAddress}; it does not contain a token or request body. A denial whose reason is the
 * API key gets its own 401 error type ({@code api_key_missing} / {@code api_key_invalid}); every other denial is {@code unauthorized}. On a
 * grant the {@link OpenAccessValidation} is stored as {@link #ATTR_OPEN_ACCESS_VALIDATION}. Routes selected by the shared
 * {@link PublicEndpointPolicy}, a real bearer token, or disabled open access pass through untouched.
 */
public class OpenAccessFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-PICSURE-API-Key";

    /**
     * Set to {@link Boolean#TRUE} only by this filter, only after PSAMA grants the open-access validate. This — not the user-id attribute,
     * which other identity sources may legitimately set — is the one signal {@code PsamaIntrospectionFilter} accepts to skip token
     * introspection.
     */
    public static final String ATTR_OPEN_ACCESS_GRANTED = OpenAccessFilter.class.getName() + ".granted";

    /**
     * The {@link OpenAccessValidation} PSAMA granted, set only by this filter and only on a grant. Later filters read the verified key
     * identity from here, so none of them needs the {@value #API_KEY_HEADER} header or trusts what the caller claimed in it.
     */
    public static final String ATTR_OPEN_ACCESS_VALIDATION = OpenAccessFilter.class.getName() + ".validation";

    /** 401 error type when PSAMA requires a key and none was presented. Part of the frontend contract. */
    public static final String ERROR_API_KEY_MISSING = "api_key_missing";

    /** 401 error type when the presented key is unknown, malformed, expired, or revoked. Part of the frontend contract. */
    public static final String ERROR_API_KEY_INVALID = "api_key_invalid";

    private record Denial(String errorType, String failureReason, String message) {
    }

    private static final Denial KEY_MISSING = new Denial(ERROR_API_KEY_MISSING, ERROR_API_KEY_MISSING, "An API key is required.");
    private static final Denial KEY_INVALID = new Denial(ERROR_API_KEY_INVALID, ERROR_API_KEY_INVALID, "API key is not valid.");
    private static final Denial RULES_DENIED = new Denial("unauthorized", "access_rules_denied", "User is not authorized.");
    private static final Denial NOT_AUTHORIZED = new Denial("unauthorized", "open_access_denied", "User is not authorized.");

    private static final Logger log = LoggerFactory.getLogger(OpenAccessFilter.class);

    private final PsamaClient psama;
    private final AuditContext audit;
    private final boolean openAccessEnabled;
    private final PublicEndpointPolicy publicEndpoints;

    public OpenAccessFilter(PsamaClient psama, AuditContext audit, boolean openAccessEnabled, PublicEndpointPolicy publicEndpoints) {
        this.psama = psama;
        this.audit = audit;
        this.openAccessEnabled = openAccessEnabled;
        this.publicEndpoints = publicEndpoints;
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

        if (!openAccessEnabled || !noToken) {
            chain.doFilter(req, resp);
            return;
        }

        Map<String, Object> queryMap = buildOpenAccessRequest(req);
        Map<String, Object> body = new HashMap<>();
        body.put("request", queryMap);
        String hostMarker = openAccessIpAddress(req);
        body.put("ipAddress", hostMarker); // Open-access validation sends no token field.
        String apiKey = req.getHeader(API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            body.put("apiKey", apiKey);
        }

        OpenAccessValidation validation;
        try {
            validation = psama.validateOpenAccess(body);
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
        if (validation == null || !validation.valid()) {
            denied(resp, validation == null ? null : validation.denial());
            return;
        }
        req.setAttribute(GatewayUserResolver.HEADER_USER_ID, hostMarker);
        req.setAttribute(ATTR_OPEN_ACCESS_GRANTED, Boolean.TRUE);
        req.setAttribute(ATTR_OPEN_ACCESS_VALIDATION, validation);
        // Record that the open-access flow admitted this request. Backend routing remains path-based.
        req.setAttribute(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN);
        audit.put("auth_result", "success");
        audit.put("auth_action", "open_access.granted");
        chain.doFilter(req, resp);
    }

    /**
     * Key denials get their own error type so a client can tell "get a new key or session" apart from "the access rules said no". A denial
     * PSAMA gave no reason for (a bare-boolean {@code false}) or one this gateway doesn't know is {@code unauthorized}, and its audit
     * reason claims no cause.
     */
    private void denied(HttpServletResponse resp, String reason) throws IOException {
        Denial denial = switch (reason == null ? "" : reason) {
            case OpenAccessValidation.DENIAL_KEY_MISSING -> KEY_MISSING;
            case OpenAccessValidation.DENIAL_KEY_INVALID -> KEY_INVALID;
            case OpenAccessValidation.DENIAL_RULES -> RULES_DENIED;
            default -> NOT_AUTHORIZED;
        };
        audit.put("auth_result", "failure");
        audit.put("auth_action", "open_access.denied");
        audit.put("auth_failure_reason", denial.failureReason());
        GatewayErrors.write(resp, HttpStatus.UNAUTHORIZED, denial.errorType(), denial.message());
    }

    /** Builds the {@code "OPEN_ACCESS:<host>"} marker sent as {@code ipAddress} to PSAMA's open-access validation endpoint. */
    private static String openAccessIpAddress(HttpServletRequest req) {
        return "OPEN_ACCESS:" + (req.getServerName() == null ? "unknown" : req.getServerName());
    }

    /** Builds the inner {@code { "Target Service" }} request map sent to PSAMA's open-access validate endpoint. */
    private Map<String, Object> buildOpenAccessRequest(HttpServletRequest req) {
        Map<String, Object> queryMap = new HashMap<>();
        queryMap.put("Target Service", UrlPathHelper.defaultInstance.getPathWithinApplication(req));
        return queryMap;
    }
}
