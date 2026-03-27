package edu.harvard.dbmi.avillach.security;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.Context;
import javax.ws.rs.ext.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.harvard.dbmi.avillach.data.entity.AuthUser;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.service.AuditContext;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;

@Provider
public class AuditLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private static final String AUDIT_START_TIME = "audit_start_time";

    private static final String DEST_IP;
    private static final Integer DEST_PORT;

    static {
        DEST_IP = System.getenv("DEST_IP");
        Integer port = null;
        String portStr = System.getenv("DEST_PORT");
        if (portStr != null) {
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                // ignore, will fallback to httpServletRequest
            }
        }
        DEST_PORT = port;
    }

    // Patterns for URL matching (work with optional /v3/ or similar version prefix)
    private static final Pattern QUERY_SUBMIT = Pattern.compile("^(/v\\d+)?/query/?$");
    private static final Pattern QUERY_SYNC = Pattern.compile("^(/v\\d+)?/query/sync/?$");
    private static final Pattern QUERY_STATUS = Pattern.compile("^(/v\\d+)?/query/[^/]+/status/?$");
    private static final Pattern QUERY_RESULT = Pattern.compile("^(/v\\d+)?/query/[^/]+/result/?$");
    private static final Pattern QUERY_SIGNED_URL = Pattern.compile("^(/v\\d+)?/query/[^/]+/signed-url/?$");
    private static final Pattern QUERY_METADATA = Pattern.compile("^(/v\\d+)?/query/[^/]+/metadata/?$");
    private static final Pattern SEARCH = Pattern.compile("^(/v\\d+)?/search/[^/]+/?$");
    private static final Pattern SEARCH_VALUES = Pattern.compile("^(/v\\d+)?/search/[^/]+/values/");

    @Inject
    LoggingClient loggingClient;

    @Context
    HttpServletRequest httpServletRequest;

    @Inject
    AuditContext auditContext;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        requestContext.setProperty(AUDIT_START_TIME, System.currentTimeMillis());
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        try {
            if (loggingClient == null || !loggingClient.isEnabled()) {
                return;
            }

            String fullPath = requestContext.getUriInfo().getRequestUri().getPath();

            // Skip paths that should not be logged
            if (
                fullPath.endsWith("/system/status") || fullPath.endsWith("/openapi.json") || fullPath.contains("/info/")
                    || fullPath.contains("/bin/continuous") || fullPath.contains("/proxy/pic-sure-logging/")
            ) {
                return;
            }

            // Strip servlet context path and application path prefix (e.g., /pic-sure-api-2/PICSURE)
            String path = fullPath;
            // Strip known context prefixes so URL patterns match correctly
            int picsureIdx = path.indexOf("/PICSURE");
            if (picsureIdx >= 0) {
                path = path.substring(picsureIdx + "/PICSURE".length());
            }

            // Calculate duration
            Long startTime = (Long) requestContext.getProperty(AUDIT_START_TIME);
            long duration = 0L;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }

            // Categorize event
            String method = requestContext.getMethod();
            String eventType = "OTHER";
            String action = method;

            if (QUERY_SYNC.matcher(path).matches() && "POST".equals(method)) {
                eventType = "QUERY";
                action = "query.sync";
            } else if (QUERY_SUBMIT.matcher(path).matches() && "POST".equals(method)) {
                eventType = "QUERY";
                action = "query.submitted";
            } else if (QUERY_STATUS.matcher(path).matches()) {
                eventType = "QUERY";
                action = "query.status";
            } else if (QUERY_RESULT.matcher(path).matches()) {
                eventType = "DATA_ACCESS";
                action = "query.result";
            } else if (QUERY_SIGNED_URL.matcher(path).matches()) {
                eventType = "DATA_ACCESS";
                action = "query.signed_url";
            } else if (QUERY_METADATA.matcher(path).matches()) {
                eventType = "QUERY";
                action = "query.metadata";
            } else if (SEARCH.matcher(path).matches() && "POST".equals(method)) {
                eventType = "SEARCH";
                action = "search.execute";
            } else if (SEARCH_VALUES.matcher(path).find()) {
                eventType = "SEARCH";
                action = "search.values";
            } else if (path.contains("/proxy/")) {
                eventType = "PROXY";
                action = "proxy.request";
            }

            // Determine source IP
            String srcIp = null;
            String xForwardedFor = httpServletRequest.getHeader("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                srcIp = xForwardedFor.split(",")[0].trim();
            } else {
                srcIp = httpServletRequest.getRemoteAddr();
            }

            // Determine dest IP and port
            String destIp = DEST_IP != null ? DEST_IP : httpServletRequest.getLocalAddr();
            int destPort = DEST_PORT != null ? DEST_PORT : httpServletRequest.getLocalPort();

            // Response info
            int responseStatus = responseContext.getStatus();
            String contentType = responseContext.getHeaderString("Content-Type");
            int lengthRaw = responseContext.getLength();
            Long bytes = lengthRaw >= 0 ? (long) lengthRaw : null;

            // Build RequestInfo
            RequestInfo requestInfo = RequestInfo.builder().method(method).url(fullPath).srcIp(srcIp).destIp(destIp).destPort(destPort)
                .httpUserAgent(httpServletRequest.getHeader("User-Agent")).status(responseStatus).duration(duration)
                .httpContentType(contentType).bytes(bytes).build();

            // Build metadata map (skip null values)
            Map<String, Object> metadata = new HashMap<>();
            String userId = null;
            String userEmail = null;

            Principal principal =
                requestContext.getSecurityContext() != null ? requestContext.getSecurityContext().getUserPrincipal() : null;
            if (principal instanceof AuthUser) {
                AuthUser authUser = (AuthUser) principal;
                userId = authUser.getUserId();
                userEmail = authUser.getEmail();
            }
            if (userId == null) {
                Object usernameProp = requestContext.getProperty("username");
                if (usernameProp != null) {
                    userId = usernameProp.toString();
                }
            }

            if (userId != null) {
                metadata.put("user_id", userId);
            }
            if (userEmail != null) {
                metadata.put("user_email", userEmail);
            }

            // Session ID
            String sessionId = httpServletRequest.getHeader("X-Session-Id");
            if (sessionId == null || sessionId.isEmpty()) {
                String raw = (srcIp != null ? srcIp : "") + "|"
                    + (httpServletRequest.getHeader("User-Agent") != null ? httpServletRequest.getHeader("User-Agent") : "");
                sessionId = Integer.toHexString(raw.hashCode());
            }
            metadata.put("session_id", sessionId);

            // API version
            if (fullPath.contains("/v3/")) {
                metadata.put("api_version", "v3");
            }

            // Merge domain-specific metadata from AuditContext (set by services).
            // Filter-managed keys take precedence over AuditContext values.
            if (auditContext != null) {
                auditContext.getMetadata().forEach(metadata::putIfAbsent);
            }

            // Build error map for 4xx/5xx
            Map<String, Object> errorMap = null;
            if (responseStatus >= 400) {
                errorMap = new HashMap<>();
                errorMap.put("status", responseStatus);
                errorMap.put("error_type", responseStatus >= 500 ? "server_error" : "client_error");
            }

            // Build the event
            LoggingEvent.Builder eventBuilder = LoggingEvent.builder(eventType).action(action).request(requestInfo).metadata(metadata);

            if (errorMap != null) {
                eventBuilder.error(errorMap);
            }

            LoggingEvent event = eventBuilder.build();

            // Send the event with bearer token passthrough
            String authHeader = requestContext.getHeaderString("Authorization");
            String requestId = httpServletRequest.getHeader("X-Request-Id");

            if (authHeader != null || requestId != null) {
                loggingClient.send(event, authHeader, requestId);
            } else {
                loggingClient.send(event);
            }

        } catch (Exception e) {
            logger.warn("AuditLoggingFilter failed to log request", e);
        }
    }
}
