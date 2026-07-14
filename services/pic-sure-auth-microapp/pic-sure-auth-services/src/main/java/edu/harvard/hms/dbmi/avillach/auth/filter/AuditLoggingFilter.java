package edu.harvard.hms.dbmi.avillach.auth.filter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

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

        try {
            filterChain.doFilter(request, response);
        } finally {
            logRequest(request, response);
        }
    }

    private void logRequest(HttpServletRequest request, HttpServletResponse response) {
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
            if (eventType == null) eventType = "OTHER";
            if (action == null) action = method.toLowerCase();

            // Determine source IP
            String srcIp = AuditAttributes.extractClientIp(request);

            // Determine dest IP and port
            String resolvedDestIp = destIp != null ? destIp : request.getLocalAddr();
            int resolvedDestPort = destPort != null ? destPort : request.getLocalPort();

            // Response info
            int responseStatus = response.getStatus();
            String contentType = response.getContentType();

            // Build RequestInfo
            RequestInfo requestInfo = RequestInfo.builder().method(method).url(request.getRequestURI()).srcIp(srcIp).destIp(resolvedDestIp)
                .destPort(resolvedDestPort).httpUserAgent(request.getHeader("User-Agent")).status(responseStatus).duration(duration)
                .httpContentType(contentType).build();

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
