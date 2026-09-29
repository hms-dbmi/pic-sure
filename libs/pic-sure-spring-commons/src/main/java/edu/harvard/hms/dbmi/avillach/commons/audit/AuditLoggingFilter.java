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
import edu.harvard.dbmi.avillach.logging.RequestInfo;
import edu.harvard.dbmi.avillach.logging.SessionIdResolver;
import edu.harvard.hms.dbmi.avillach.commons.request.RoutedRequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Base {@link OncePerRequestFilter} that maps each non-skipped request to an {@link AuditRoute} and emits an audit event through the
 * {@link LoggingClient}. Routes are matched on {@link RoutedRequestPath}, the decoded path a path-pattern router matches, so an encoded
 * spelling of a routed path gets the same event type and action; the event's {@code url} stays the raw request URI. {@code shouldNotFilter}
 * is deliberately {@code protected} and non-final so gateway subclasses can widen the skip set for pass-through paths.
 */
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private final LoggingClient client;
    private final AuditRouteTable routes;
    private final AuditContext audit;
    private final List<String> skipContains;
    private final boolean verifiedCallerOnly;

    /**
     * Creates a filter that records the client-supplied {@code X-Client-Type} header as the audit event's {@code caller}.
     *
     * @param client the client audit events are sent through
     * @param routes the table mapping request paths to event types and actions
     * @param audit the per-request metadata merged into each event
     * @param skipContains path fragments whose requests are not audited
     */
    public AuditLoggingFilter(LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains) {
        this(client, routes, audit, skipContains, false);
    }

    /**
     * Creates a filter that can take the audit event's {@code caller} only from a verified source.
     *
     * @param client the client audit events are sent through
     * @param routes the table mapping request paths to event types and actions
     * @param audit the per-request metadata merged into each event
     * @param skipContains path fragments whose requests are not audited
     * @param verifiedCallerOnly when true, {@code caller} comes only from the {@link VerifiedCaller} request attribute and a raw
     *        {@code X-Client-Type} header is recorded as metadata {@code client_type_claimed}; when false, the header is the {@code caller}
     */
    public AuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, boolean verifiedCallerOnly
    ) {
        this.client = client;
        this.routes = routes;
        this.audit = audit;
        this.skipContains = skipContains != null ? List.copyOf(skipContains) : List.of();
        this.verifiedCallerOnly = verifiedCallerOnly;
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

        AuditRoute route = routes != null ? routes.match(RoutedRequestPath.of(request), method).orElse(null) : null;
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

        String claimedClientType = request.getHeader("X-Client-Type");
        boolean hasClaimedClientType = claimedClientType != null && !claimedClientType.isEmpty();
        String caller;
        if (verifiedCallerOnly) {
            caller = VerifiedCaller.get(request).orElse(null);
            if (hasClaimedClientType) {
                metadata.put("client_type_claimed", claimedClientType);
            }
        } else {
            caller = hasClaimedClientType ? claimedClientType : null;
        }

        LoggingEvent.Builder eventBuilder = LoggingEvent.builder(eventType).action(action).sessionId(sessionId).request(requestInfo)
            .metadata(metadata.isEmpty() ? null : metadata);

        if (caller != null) {
            eventBuilder.caller(caller);
        }

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

    /**
     * Takes the RIGHTMOST X-Forwarded-For entry, not the leftmost: clients can send an arbitrary XFF header of their own, and the trusted
     * front proxy (AIO httpd) APPENDS the address it saw -- so the rightmost entry is the nearest trusted hop and cannot be client-forged,
     * while the leftmost is whatever the client chose to send.
     */
    private String resolveSourceIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
