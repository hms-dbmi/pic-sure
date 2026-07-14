package edu.harvard.dbmi.avillach.dictionary;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private final String destIpConfig;
    private final Integer destPortConfig;
    private final LoggingClient loggingClient;

    public AuditLoggingFilter(LoggingClient loggingClient, String destIp, Integer destPort) {
        this.loggingClient = loggingClient;
        this.destIpConfig = destIp;
        this.destPortConfig = destPort;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {

        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                if (loggingClient == null || !loggingClient.isEnabled()) return;

                String fullPath = request.getRequestURI();
                String method = request.getMethod();
                long duration = System.currentTimeMillis() - startTime;

                String eventType = (String) request.getAttribute(AuditAttributes.EVENT_TYPE);
                String action = (String) request.getAttribute(AuditAttributes.ACTION);
                if (eventType == null) eventType = "OTHER";
                if (action == null) action = method.toLowerCase();

                String srcIp = extractSourceIp(request);
                String destIp = destIpConfig != null ? destIpConfig : request.getLocalAddr();
                int destPort = destPortConfig != null ? destPortConfig : request.getLocalPort();

                int responseStatus = response.getStatus();
                String contentType = response.getContentType();
                Long bytes = parseContentLength(response.getHeader("Content-Length"));

                String queryString = request.getQueryString();
                RequestInfo requestInfo = RequestInfo.builder().method(method).url(fullPath).queryString(queryString).srcIp(srcIp)
                    .destIp(destIp).destPort(destPort).httpUserAgent(request.getHeader("User-Agent")).status(responseStatus)
                    .duration(duration).httpContentType(contentType).bytes(bytes).build();

                Map<String, Object> metadata = new HashMap<>();
                String sessionId = SessionIdResolver.resolve(request.getHeader("X-Session-Id"), srcIp, request.getHeader("User-Agent"));

                AuditAttributes.getMetadata(request).forEach(metadata::putIfAbsent);

                Map<String, Object> errorMap = null;
                if (responseStatus >= 400) {
                    errorMap = new HashMap<>();
                    errorMap.put("status", responseStatus);
                    errorMap.put("error_type", responseStatus >= 500 ? "server_error" : "client_error");
                }

                LoggingEvent.Builder eventBuilder =
                    LoggingEvent.builder(eventType).action(action).sessionId(sessionId).request(requestInfo).metadata(metadata);
                if (errorMap != null) eventBuilder.error(errorMap);
                LoggingEvent event = eventBuilder.build();

                String authHeader = request.getHeader("Authorization");
                String requestId = request.getHeader("X-Request-Id");
                if (authHeader != null || requestId != null) {
                    loggingClient.send(event, authHeader, requestId);
                } else {
                    loggingClient.send(event);
                }
            } catch (Exception e) {
                log.warn("AuditLoggingFilter failed to log request", e);
            }
        }
    }

    static String extractSourceIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    static Long parseContentLength(String header) {
        if (header == null) return null;
        try {
            return Long.parseLong(header);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
