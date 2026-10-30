package edu.harvard.hms.dbmi.avillach.commons.audit;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

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
 * {@link LoggingClient}. Both the event-skip check and route matching use {@link RoutedRequestPath}, the decoded path a path-pattern router
 * matches, so an encoded spelling of a path is skipped or gets the same event type and action exactly as its plain spelling does; the
 * event's {@code url} stays the raw request URI. Requests {@link #skipsEvent} matches still run inside the filter and emit nothing, unless
 * the chain marks them with {@link #AUDIT_REQUIRED_ATTRIBUTE}; subclasses widen that skip set by overriding {@code skipsEvent}. Overriding
 * the protected, non-final {@code shouldNotFilter} bypasses the filter outright, so such requests are never audited, marked or not.
 */
public class AuditLoggingFilter extends OncePerRequestFilter {

    /** Request attribute that, when {@link Boolean#TRUE}, forces an event for a path or method this filter otherwise skips. */
    public static final String AUDIT_REQUIRED_ATTRIBUTE = AuditLoggingFilter.class.getName() + ".required";

    private static final Logger logger = LoggerFactory.getLogger(AuditLoggingFilter.class);

    private final LoggingClient client;
    private final AuditRouteTable routes;
    private final AuditContext audit;
    private final List<String> skipContains;
    private final ClientTypeAllowlist clientTypes;
    private final Predicate<String> forwardAuthorization;
    private final Function<HttpServletRequest, String> sourceIp;

    /**
     * Creates a filter that records the client-supplied {@code X-Client-Type} header as the audit event's {@code caller}.
     *
     * @param client the client audit events are sent through
     * @param routes the table mapping request paths to event types and actions
     * @param audit the per-request metadata merged into each event
     * @param skipContains path fragments whose requests are not audited
     */
    public AuditLoggingFilter(LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains) {
        this(client, routes, audit, skipContains, null);
    }

    /**
     * Creates a filter that accepts only listed client types from the header. The first four arguments are as for
     * {@link #AuditLoggingFilter(LoggingClient, AuditRouteTable, AuditContext, List)}.
     *
     * @param client see the four-argument constructor
     * @param routes see the four-argument constructor
     * @param audit see the four-argument constructor
     * @param skipContains see the four-argument constructor
     * @param clientTypes when non-null, {@code caller} is the {@link VerifiedCaller} request attribute when one is set, and otherwise the
     *        {@code X-Client-Type} header if it is on this list; an absent, unlisted or reserved header value gives {@code UNKNOWN}. The
     *        raw header is also recorded as metadata {@code client_type_claimed}. When null, the header is the {@code caller}
     */
    public AuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, ClientTypeAllowlist clientTypes
    ) {
        this(client, routes, audit, skipContains, clientTypes, authorization -> true);
    }

    /**
     * @param clientTypes as for the five-argument constructor
     * @param forwardAuthorization whether a request's {@code Authorization} value is passed on to the logging service, which reads user
     *        claims from it; a rejected value is dropped and the event is sent without one
     */
    public AuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, ClientTypeAllowlist clientTypes,
        Predicate<String> forwardAuthorization
    ) {
        this(client, routes, audit, skipContains, clientTypes, forwardAuthorization, AuditLoggingFilter::resolveSourceIp);
    }

    /**
     * @param clientTypes as above
     * @param forwardAuthorization as above
     * @param sourceIp the client address recorded as the event's {@code srcIp}, which also seeds the fallback session id
     */
    public AuditLoggingFilter(
        LoggingClient client, AuditRouteTable routes, AuditContext audit, List<String> skipContains, ClientTypeAllowlist clientTypes,
        Predicate<String> forwardAuthorization, Function<HttpServletRequest, String> sourceIp
    ) {
        this.client = client;
        this.routes = routes;
        this.audit = audit;
        this.skipContains = skipContains != null ? List.copyOf(skipContains) : List.of();
        this.clientTypes = clientTypes;
        this.forwardAuthorization = Objects.requireNonNull(forwardAuthorization, "forwardAuthorization");
        this.sourceIp = Objects.requireNonNull(sourceIp, "sourceIp");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return client == null || !client.isEnabled();
    }

    /** Whether the request's event is dropped unless {@link #AUDIT_REQUIRED_ATTRIBUTE} is set. */
    protected boolean skipsEvent(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = RoutedRequestPath.of(request);
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
            if (!skipsEvent(request) || Boolean.TRUE.equals(request.getAttribute(AUDIT_REQUIRED_ATTRIBUTE))) {
                try {
                    emit(request, response, System.currentTimeMillis() - startTime);
                } catch (Exception e) {
                    logger.warn("AuditLoggingFilter failed to log request", e);
                }
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

        String srcIp = sourceIp.apply(request);
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
        if (clientTypes != null) {
            caller =
                VerifiedCaller.get(request).orElseGet(() -> clientTypes.resolve(claimedClientType).orElse(ClientTypeAllowlist.UNKNOWN));
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
        if (authHeader != null && !forwardAuthorization.test(authHeader)) {
            authHeader = null;
        }
        if (authHeader != null || requestId != null) {
            client.send(event, authHeader, requestId);
        } else {
            client.send(event);
        }
    }

    /**
     * Takes the RIGHTMOST X-Forwarded-For entry, not the leftmost: clients can send an arbitrary XFF header of their own, and the trusted
     * front proxy (AIO httpd) APPENDS the address it saw -- so the rightmost entry is the nearest trusted hop and cannot be client-forged,
     * while the leftmost is whatever the client chose to send. An empty rightmost entry (a header of only commas, say) falls back to the
     * connection peer, so a malformed header never drops the event. The default {@code srcIp} source.
     */
    static String resolveSourceIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null) {
            String[] hops = forwardedFor.split(",");
            if (hops.length > 0 && !hops[hops.length - 1].isBlank()) {
                return hops[hops.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
