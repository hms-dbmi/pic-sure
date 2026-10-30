package edu.harvard.hms.dbmi.avillach.gateway.config;

import static org.mockito.Mockito.when;

import java.util.Comparator;
import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.hms.dbmi.avillach.commons.audit.AuditContext;
import edu.harvard.hms.dbmi.avillach.commons.net.CidrRange;
import edu.harvard.hms.dbmi.avillach.commons.net.ClientIpResolver;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PsamaClient;
import edu.harvard.hms.dbmi.avillach.gateway.auth.PublicEndpointPolicy;
import edu.harvard.hms.dbmi.avillach.gateway.filter.AuditFilterConfig;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitProperties;
import edu.harvard.hms.dbmi.avillach.gateway.ratelimit.RateLimitStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The gateway's servlet filters, from {@code RequestIdFilter} to the identity-header sanitizer, built from their configuration classes and
 * run in configured order without a Spring context. A filter added to or reordered in {@link SecurityConfig}, {@link ObservabilityConfig}
 * or {@link AuditFilterConfig} belongs in the list here.
 */
final class AuditFilterChain {

    /** Stands in for the proxied route: anything that gets past the filters is answered 200. */
    private static final HttpServlet UPSTREAM = new HttpServlet() {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) {}
    };

    private AuditFilterChain() {}

    /**
     * Sends {@code request} through the chain. {@code trustedProxies} is {@code RATE_LIMIT_TRUSTED_PROXIES}; the limiter is enabled only
     * when it is non-empty, as the gateway requires.
     */
    static MockHttpServletResponse send(
        List<String> trustedProxies, RateLimitStore store, LoggingClient logging, PsamaClient psama, MockHttpServletRequest request
    ) throws Exception {
        when(logging.isEnabled()).thenReturn(true);
        AuditContext audit = new AuditContext();
        GatewaySecurityProperties props =
            new GatewaySecurityProperties(List.of(), true, 1024, "http://psama.local/introspect", "http://psama.local/open-access", "svc");
        RateLimitProperties rateLimit = new RateLimitProperties(
            !trustedProxies.isEmpty(), new RateLimitProperties.KeyLimits(60, 3000), new RateLimitProperties.KeyLimits(60, 3000),
            new RateLimitProperties.IpLimits(60, 3000, 300), new RateLimitProperties.Store(1000), trustedProxies, List.of()
        );
        PublicEndpointPolicy publicEndpoints = new PublicEndpointPolicy(List.of());
        ClientIpResolver clientIps = new ClientIpResolver(trustedProxies.stream().map(CidrRange::parse).toList());
        SecurityConfig security = new SecurityConfig();
        ObservabilityConfig observability = new ObservabilityConfig();
        AuditFilterConfig auditConfig = new AuditFilterConfig();
        List<FilterRegistrationBean<?>> registrations = List.of(
            observability.requestIdFilter(), observability.accessLogFilter(),
            auditConfig.auditLoggingFilter(logging, auditConfig.auditRouteTable(), audit, rateLimit, clientIps),
            security.bufferingFilter(props, new SimpleMeterRegistry()),
            security.prevalidationThrottleFilter(props, publicEndpoints, rateLimit, clientIps, store, audit),
            observability.internalEndpointGuardFilter(), security.openAccessFilter(psama, audit, props, publicEndpoints),
            security.perKeyRateLimitFilter(rateLimit, clientIps, store, audit), observability.inboundIdentityHeaderSanitizingFilter()
        );
        Filter[] filters = registrations.stream().sorted(Comparator.comparingInt(FilterRegistrationBean::getOrder))
            .map(FilterRegistrationBean::getFilter).toArray(Filter[]::new);
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(UPSTREAM, filters).doFilter(request, response);
        return response;
    }
}
