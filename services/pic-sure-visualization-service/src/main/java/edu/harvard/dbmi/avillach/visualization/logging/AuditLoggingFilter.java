package edu.harvard.dbmi.avillach.visualization.logging;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;
import edu.harvard.dbmi.avillach.logging.RequestInfoBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String SESSION_ID_HEADER = "X-Session-Id";

    private final LoggingClient loggingClient;

    public AuditLoggingFilter(LoggingClient loggingClient) {
        this.loggingClient = loggingClient;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return switch (request.getRequestURI()) {
            case "/distributions", "/bin/continuous" -> false;
            default -> true;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String requestId = resolveRequestId(request);
        String sessionId = request.getHeader(SESSION_ID_HEADER);
        long startTime = System.currentTimeMillis();
        Exception failure = null;

        request.setAttribute(AuditLoggingContext.START_TIME_ATTR, startTime);
        request.setAttribute(AuditLoggingContext.REQUEST_ID_ATTR, requestId);
        request.setAttribute(AuditLoggingContext.AUTHORIZATION_ATTR, request.getHeader("Authorization"));
        request.setAttribute(AuditLoggingContext.SESSION_ID_ATTR, sessionId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);

        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            try {
                sendEvent(request, response, requestId, sessionId, duration, failure);
            } finally {
                MDC.remove("requestId");
            }
        }
    }

    private void sendEvent(
        HttpServletRequest request, HttpServletResponse response, String requestId, String sessionId, long duration, Exception failure
    ) {
        try {
            String action = switch (request.getRequestURI()) {
                case "/distributions" -> "visualization.distributions";
                case "/bin/continuous" -> "visualization.bin_continuous";
                default -> "visualization.request";
            };

            LoggingEvent.Builder builder = LoggingEvent.builder("QUERY").action(action).sessionId(sessionId)
                .request(requestInfo(request, response, requestId, duration)).metadata(AuditLoggingContext.metadata(request));

            Map<String, Object> error = errorMap(response.getStatus(), failure);
            if (!error.isEmpty()) {
                builder.error(error);
            }

            loggingClient.send(builder.build(), request.getHeader("Authorization"), requestId);
        } catch (Exception e) {
            logger.debug("Failed to send audit log event: {}", e.getMessage());
        }
    }

    private static RequestInfo requestInfo(HttpServletRequest request, HttpServletResponse response, String requestId, long duration) {
        return new RequestInfoBuilder().requestId(requestId).method(request.getMethod()).url(request.getRequestURI())
            .queryString(request.getQueryString()).srcIp(sourceIp(request)).httpUserAgent(request.getHeader("User-Agent"))
            .httpContentType(request.getContentType()).status(response.getStatus()).duration(duration)
            .referrer(request.getHeader("Referer")).build();
    }

    private static Map<String, Object> errorMap(int status, Exception failure) {
        Map<String, Object> error = new LinkedHashMap<>();
        if (status >= 400) {
            error.put("status", status);
            error.put("error_type", status >= 500 ? "server_error" : "client_error");
        }
        if (failure != null) {
            error.put("exception", failure.getClass().getSimpleName());
            if (failure.getMessage() != null) {
                error.put("message", failure.getMessage());
            }
        }
        return error;
    }

    private static String resolveRequestId(HttpServletRequest request) {
        String headerValue = request.getHeader(REQUEST_ID_HEADER);
        if (headerValue != null && !headerValue.isBlank()) {
            return headerValue;
        }
        return UUID.randomUUID().toString();
    }

    private static String sourceIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
