package edu.harvard.hms.dbmi.avillach.auth.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomApplicationDetails;
import edu.harvard.hms.dbmi.avillach.auth.model.CustomUserDetails;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(2)
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private static final String AUDIT_START_TIME = "audit_start_time";

    private static final String HEAD_METHOD = "HEAD";

    @Value("${DEST_IP:#{null}}")
    private String destIp;

    @Value("${DEST_PORT:#{null}}")
    private Integer destPort;

    private final LoggingClient loggingClient;

    @Autowired
    public AuditLoggingFilter(LoggingClient loggingClient) {
        this.loggingClient = loggingClient;
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        request.setAttribute(AUDIT_START_TIME, System.currentTimeMillis());

        ByteCountingResponseWrapper countingResponse = new ByteCountingResponseWrapper(response);
        boolean chainCompleted = false;

        try {
            filterChain.doFilter(request, countingResponse);
            chainCompleted = true;
        } finally {
            logRequest(request, response, countingResponse, chainCompleted);
        }
    }

    /**
     * Reads the size of the response body for the audit event. The size is the body bytes handed to the container, before compression or
     * chunk framing. <p> It is left unset when the container writes the body after this filter has sent its event: an exception left the
     * chain, async handling has started, or {@code sendError} was called. Otherwise it is 0 for a HEAD request, whose body the container
     * discards, after {@code sendRedirect}, and for a response with no body. Unset wins over 0.
     *
     * @param request the request being audited
     * @param response the counting wrapper the rest of the chain wrote to
     * @param chainCompleted whether the rest of the chain returned without throwing
     * @return the number of body bytes, or null to leave the field unset
     */
    private static Long bytesWritten(HttpServletRequest request, ByteCountingResponseWrapper response, boolean chainCompleted) {
        if (!chainCompleted || request.isAsyncStarted()) {
            return null;
        }
        OptionalLong byteCount = response.getByteCount();
        if (byteCount.isEmpty()) {
            return null;
        }
        return HEAD_METHOD.equals(request.getMethod()) ? 0L : byteCount.getAsLong();
    }

    /**
     * Builds and sends the audit event for a finished request. Nothing thrown while doing so reaches the caller.
     *
     * @param request the request being audited
     * @param response the response the status and content type are read from
     * @param countingResponse the counting wrapper the rest of the chain wrote to
     * @param chainCompleted whether the rest of the chain returned without throwing
     */
    private void logRequest(
        HttpServletRequest request, HttpServletResponse response, ByteCountingResponseWrapper countingResponse, boolean chainCompleted
    ) {
        try {
            if (loggingClient == null || !loggingClient.isEnabled()) {
                return;
            }

            String path = request.getRequestURI();

            // Strip context path (e.g., /auth)
            String contextPath = request.getContextPath();
            if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
            }

            // Skip paths that should not be logged
            if (
                path.startsWith("/actuator/") || path.endsWith("/swagger.yaml") || path.endsWith("/swagger.json")
                    || path.endsWith("/openapi.json")
            ) {
                return;
            }

            if ("/user/me/consents".equals(path) && "service".equalsIgnoreCase(request.getHeader("X-Client-Type"))) {
                return;
            }

            // Calculate duration
            Long startTime = (Long) request.getAttribute(AUDIT_START_TIME);
            long duration = 0L;
            if (startTime != null) {
                duration = System.currentTimeMillis() - startTime;
            }

            // Read event type and action from request attributes (set by AuditInterceptor from @AuditEvent annotations)
            String method = request.getMethod();
            String eventType = (String) request.getAttribute(AuditAttributes.EVENT_TYPE);
            String action = (String) request.getAttribute(AuditAttributes.ACTION);
            if (eventType == null) eventType = "UNLABELED";
            if (action == null) action = method.toLowerCase();

            // Determine source IP
            String srcIp = AuditAttributes.extractClientIp(request);

            // Determine dest IP and port
            String resolvedDestIp = destIp != null ? destIp : request.getLocalAddr();
            int resolvedDestPort = destPort != null ? destPort : request.getLocalPort();

            // Response info
            int responseStatus = response.getStatus();
            String contentType = response.getContentType();
            Long bytes = bytesWritten(request, countingResponse, chainCompleted);

            // Build RequestInfo
            RequestInfo requestInfo = RequestInfo.builder().method(method).url(request.getRequestURI()).srcIp(srcIp).destIp(resolvedDestIp)
                .destPort(resolvedDestPort).httpUserAgent(request.getHeader("User-Agent")).status(responseStatus).duration(duration)
                .httpContentType(contentType).bytes(bytes).build();

            // Build metadata
            Map<String, Object> metadata = new HashMap<>();

            // Extract user info from security context
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() != null) {
                Object principal = authentication.getPrincipal();
                if (principal instanceof CustomUserDetails userDetails) {
                    metadata.put("user_id", userDetails.getUser().getUuid().toString());
                    if (userDetails.getUser().getEmail() != null) {
                        metadata.put("user_email", userDetails.getUser().getEmail());
                    }
                } else if (principal instanceof CustomApplicationDetails appDetails) {
                    if (appDetails.getApplication() != null) {
                        metadata.put("app_id", appDetails.getApplication().getUuid().toString());
                        metadata.put("app_name", appDetails.getApplication().getName());
                    }
                }
            }

            // Session ID
            String sessionId = SessionIdResolver.resolve(request.getHeader("X-Session-Id"), srcIp, request.getHeader("User-Agent"));

            // Merge domain-specific metadata from AuditAttributes (set by services).
            // Filter-managed keys take precedence over AuditAttributes values.
            AuditAttributes.getMetadata(request).forEach(metadata::putIfAbsent);

            // Build error map for 4xx/5xx
            Map<String, Object> errorMap = null;
            if (responseStatus >= 400) {
                errorMap = new HashMap<>();
                errorMap.put("status", responseStatus);
                errorMap.put("error_type", responseStatus >= 500 ? "server_error" : "client_error");
            }

            // Build the event
            LoggingEvent.Builder eventBuilder =
                LoggingEvent.builder(eventType).action(action).sessionId(sessionId).request(requestInfo).metadata(metadata);

            String caller = request.getHeader("X-Client-Type");
            if (caller != null && !caller.isEmpty()) {
                eventBuilder.caller(caller);
            }

            if (errorMap != null) {
                eventBuilder.error(errorMap);
            }

            LoggingEvent event = eventBuilder.build();

            // Send the event with bearer token passthrough
            String authHeader = request.getHeader("Authorization");
            String requestId = request.getHeader("X-Request-Id");

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
