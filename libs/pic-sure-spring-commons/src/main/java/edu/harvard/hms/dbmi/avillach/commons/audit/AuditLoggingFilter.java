package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.contracts.audit.RequestInfo;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Base {@link OncePerRequestFilter} that maps each non-skipped request to an {@link AuditRoute} and emits an audit event via the
 * {@link LoggingClient}, mirroring the legacy {@code edu.harvard.dbmi.avillach.security.AuditLoggingFilter}'s route table and skip-list
 * semantics (DB-free, no JAX-RS). {@code shouldNotFilter} is deliberately {@code protected} and non-final so gateway subclasses can widen
 * the skip set (e.g. interim/pass-through paths).
 */
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private final LoggingClient client;
    private final AuditRouteTable routes;
    private final AuditContext audit;
    private final List<String> skipContains;

    public AuditLoggingFilter(LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains) {
        this.client = client;
        this.routes = routes;
        this.audit = audit;
        this.skipContains = skipContains != null ? List.copyOf(skipContains) : List.of();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (client == null || !client.isEnabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        if (path.endsWith("/system/status") || path.endsWith("/openapi.json")) {
            return true;
        }
        for (String skip : skipContains) {
            if (skip != null && path.contains(skip)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            try {
                emit(request, response, System.currentTimeMillis() - startTime);
            } catch (Exception e) {
                logger.warn("AuditLoggingFilter failed to log request", e);
            }
        }
    }

    private void emit(HttpServletRequest request, HttpServletResponse response, long duration) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        AuditRoute route = routes != null ? routes.match(path, method).orElse(null) : null;
        String eventType = route != null ? route.getEventType() : "OTHER";
        String action = route != null ? route.getAction() : method;

        String requestId = request.getHeader("X-Request-Id");
        if (requestId == null || requestId.isBlank()) {
            requestId = MDC.get("requestId");
        }

        String srcIp = resolveSourceIp(request);
        String userAgent = request.getHeader("User-Agent");
        String sessionId = SessionIdResolver.resolve(request.getHeader("X-Session-Id"), srcIp, userAgent);

        RequestInfo requestInfo = RequestInfo.builder().requestId(requestId).method(method).url(path).queryString(request.getQueryString())
            .srcIp(srcIp).status(response.getStatus()).duration(duration).httpUserAgent(userAgent)
            .httpContentType(response.getContentType()).referrer(request.getHeader("Referer")).build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (audit != null) {
            audit.getMetadata().forEach(metadata::putIfAbsent);
        }

        LoggingEvent.Builder eventBuilder = LoggingEvent.builder(eventType).action(action).sessionId(sessionId).request(requestInfo)
            .metadata(metadata.isEmpty() ? null : metadata);

        if (response.getStatus() >= 400) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", response.getStatus());
            error.put("error_type", response.getStatus() >= 500 ? "server_error" : "client_error");
            eventBuilder.error(error);
        }

        LoggingEvent event = eventBuilder.build();

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null || requestId != null) {
            client.send(event, authHeader, requestId);
        } else {
            client.send(event);
        }
    }

    private String resolveSourceIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
