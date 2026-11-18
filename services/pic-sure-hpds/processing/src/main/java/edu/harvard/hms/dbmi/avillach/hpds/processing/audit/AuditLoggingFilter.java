package edu.harvard.hms.dbmi.avillach.hpds.processing.audit;

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
import edu.harvard.hms.dbmi.avillach.commons.audit.ByteCountingResponseWrapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Generic audit logging filter shared by both the HPDS service and the genomic processor. <p> Event categorization is NOT done here — it is
 * set by {@link AuditInterceptor} (reading {@link AuditEvent} annotations) as request attributes. This filter reads those attributes and
 * falls back to OTHER / HTTP method if none are set. <p> Domain-specific metadata (query_id, result_type, etc.) is set by controllers via
 * {@link AuditAttributes#putMetadata}. <p> Not annotated {@code @Component} — each application registers it via its own
 * {@code LoggingConfig} to allow sharing across separately-scanned Spring Boot apps.
 */
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

        ByteCountingResponseWrapper countingResponse = new ByteCountingResponseWrapper(response);
        boolean chainCompleted = false;

        try {
            filterChain.doFilter(request, countingResponse);
            chainCompleted = true;
        } finally {
            try {
                if (loggingClient == null || !loggingClient.isEnabled()) {
                    return;
                }

                String fullPath = request.getRequestURI();
                String method = request.getMethod();
                long duration = System.currentTimeMillis() - startTime;

                // Read event categorization set by AuditInterceptor (from @AuditEvent annotations)
                String eventType = (String) request.getAttribute(AuditAttributes.EVENT_TYPE);
                String action = (String) request.getAttribute(AuditAttributes.ACTION);
                if (eventType == null) {
                    eventType = "UNLABELED";
                }
                if (action == null) {
                    action = method;
                }

                // Source IP
                String srcIp = extractSourceIp(request);

                // Destination IP and port
                String destIp = destIpConfig != null ? destIpConfig : request.getLocalAddr();
                int destPort = destPortConfig != null ? destPortConfig : request.getLocalPort();

                // Response info
                int responseStatus = response.getStatus();
                String contentType = response.getContentType();
                Long bytes = bytesTheClientReceived(request, countingResponse, chainCompleted);

                String queryString = request.getQueryString();
                RequestInfo requestInfo = RequestInfo.builder().method(method).url(fullPath).queryString(queryString).srcIp(srcIp)
                    .destIp(destIp).destPort(destPort).httpUserAgent(request.getHeader("User-Agent")).status(responseStatus)
                    .duration(duration).httpContentType(contentType).bytes(bytes).build();

                // Build metadata: api version + domain metadata from controllers
                String sessionId = SessionIdResolver.resolve(request.getHeader("X-Session-Id"), srcIp, request.getHeader("User-Agent"));
                Map<String, Object> metadata = new HashMap<>();
                if (fullPath.contains("/v3/")) {
                    metadata.put("api_version", "v3");
                }

                // Merge domain-specific metadata set by controllers via AuditAttributes.putMetadata()
                AuditAttributes.getMetadata(request).forEach(metadata::putIfAbsent);

                // Error map for 4xx/5xx
                Map<String, Object> errorMap = null;
                if (responseStatus >= 400) {
                    errorMap = new HashMap<>();
                    errorMap.put("status", responseStatus);
                    errorMap.put("error_type", responseStatus >= 500 ? "server_error" : "client_error");
                }

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

                // Send with bearer token passthrough
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
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Returns the body size to report, or null when the counted bytes are not what the client received.
     *
     * @param request the audited request
     * @param response the wrapper that counted the body
     * @param chainCompleted false when the rest of the chain exited by throwing
     * @return null after {@code sendError}, use of the response's writer, a thrown chain or the start of asynchronous processing; 0 for a
     *         {@code HEAD} request or a status that cannot carry a body (1xx, 204 and 304); otherwise the counted bytes
     */
    private static Long bytesTheClientReceived(HttpServletRequest request, ByteCountingResponseWrapper response, boolean chainCompleted) {
        if (!chainCompleted || response.isByteCountUnknown() || request.isAsyncStarted()) {
            return null;
        }
        int status = response.getStatus();
        boolean statusCannotCarryBody = (status >= 100 && status < 200) || status == 204 || status == 304;
        if ("HEAD".equalsIgnoreCase(request.getMethod()) || statusCannotCarryBody) {
            return 0L;
        }
        return response.getByteCount();
    }
}
