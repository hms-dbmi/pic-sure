package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditLoggingFilter;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRoute;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditRouteTable;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingClientFactory;

/**
 * Wires the audit-emission filter: {@link LoggingClient} (env-driven, no-op when unconfigured), the verified route table, and the
 * {@link AuditLoggingFilter} registration itself at {@link #AUDIT_FILTER_ORDER}, ahead of every filter that can short-circuit the chain.
 */
@Configuration
public class AuditFilterConfig {

    /**
     * Bounded on both sides, so not a roundable number. Below {@code OrderedRequestContextFilter} (-105), {@code emit()}'s read of the
     * {@code @RequestScope} {@link AuditContext} -- which happens after the chain unwinds, once {@code RequestContextFilter} has cleared
     * the holder -- throws {@code IllegalStateException: No thread-bound request found} into {@code AuditLoggingFilter}'s catch-and-warn,
     * silently ending all audit emission. Above {@code SecurityProperties.DEFAULT_FILTER_ORDER} (-100), Spring Security rejections are not
     * wrapped. A rate limiter registered at -100 or above has its 429s audited.
     */
    static final int AUDIT_FILTER_ORDER = -101;

    // Leading service prefix(es) + optional /v3, e.g. "/query", "/hpds/auth/v3/query".
    private static final String PFX = "^(?:/[a-z0-9-]+)+?(?:/v3)?";

    @Bean
    public LoggingClient loggingClient() {
        return LoggingClientFactory.create("api");
    }

    @Bean
    public AuditRouteTable auditRouteTable() {
        // First match wins, so query/sync must precede query.
        return new AuditRouteTable(
            List.of(
                new AuditRoute(Pattern.compile(PFX + "/query/sync/?$"), "POST", "QUERY", "query.sync"),
                new AuditRoute(Pattern.compile(PFX + "/query/?$"), "POST", "QUERY", "query.submitted"),
                new AuditRoute(Pattern.compile(PFX + "/query/[^/]+/status/?$"), null, "QUERY", "query.status"),
                new AuditRoute(Pattern.compile(PFX + "/query/[^/]+/result/?$"), null, "DATA_ACCESS", "query.result"),
                new AuditRoute(Pattern.compile(PFX + "/query/[^/]+/signed-url/?$"), null, "DATA_ACCESS", "query.signed_url"),
                new AuditRoute(Pattern.compile(PFX + "/query/[^/]+/metadata/?$"), null, "QUERY", "query.metadata"),
                new AuditRoute(Pattern.compile(PFX + "/search/[^/]+/?$"), "POST", "SEARCH", "search.execute"),
                new AuditRoute(Pattern.compile(PFX + "/search/[^/]+/values/"), null, "SEARCH", "search.values", true)
            )
        );
    }

    @Bean
    public FilterRegistrationBean<AuditLoggingFilter> auditLoggingFilter(LoggingClient client, AuditRouteTable routes, AuditContext audit) {
        // The base filter skips paths ending in /system/status or /openapi.json and paths containing /info/,
        // /bin/continuous, or /logging. Gateway-local actuator and API-documentation paths are also skipped.
        AuditLoggingFilter filter = new AuditLoggingFilter(
            client, routes, audit, List.of("/info/", "/bin/continuous", "/logging", "/actuator", "/openapi", "/swagger-ui")
        );
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(AUDIT_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
