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
 * Wires the audit-emission filter: {@link LoggingClient} (env-driven, no-op when unconfigured), the verified route table (mirrors the
 * legacy WAR {@code AuditLoggingFilter.java:55-65}, minus the removed {@code PROXY} row -- there is no {@code /proxy} prefix in the new
 * per-service scheme), and the {@link AuditLoggingFilter} registration itself at order 60, the outermost emitter on the way out of the
 * chain (after {@code IdentityPropagationFilter} at 50 in {@code SecurityConfig}).
 */
@Configuration
public class AuditFilterConfig {

    // Leading service prefix(es) + optional /v3, e.g. "/query", "/hpds/auth/v3/query".
    private static final String PFX = "^(?:/[a-z0-9-]+)+?(?:/v3)?";

    @Bean
    public LoggingClient loggingClient() {
        return LoggingClientFactory.create("api");
    }

    @Bean
    public AuditRouteTable auditRouteTable() {
        // First match wins (query/sync before query). Mirrors WAR AuditLoggingFilter.java:55-65 minus PROXY.
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
        // VERIFIED skip-list (AuditLoggingFilter.java:122-127): ends-with /system/status, /openapi.json (base
        // class); contains /info/, /bin/continuous, /logging (was /proxy/pic-sure-logging/ -- no /proxy prefix in
        // the new scheme). Gateway-local /actuator, /openapi, /swagger-ui added on top (net-new concerns).
        AuditLoggingFilter filter = new AuditLoggingFilter(
            client, routes, audit, List.of("/info/", "/bin/continuous", "/logging", "/actuator", "/openapi", "/swagger-ui")
        );
        var registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(60); // outermost emitter on the way out
        registration.addUrlPatterns("/*");
        return registration;
    }
}
