package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.AccessLogFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InboundIdentityHeaderSanitizingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InternalEndpointGuardFilter;

@Configuration
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        // One order below RequestIdFilter so every access line carries MDC[requestId].
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    /**
     * Registered UNCONDITIONALLY, as an independent trust boundary alongside the always-on DB-free auth chain in {@code SecurityConfig}: a
     * client must never be able to inject the gateway-owned {@code X-User-*} identity headers. See
     * {@link InboundIdentityHeaderSanitizingFilter}'s Javadoc for the trust-boundary rationale.
     */
    @Bean
    public FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> inboundIdentityHeaderSanitizingFilter() {
        FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> registration =
            new FilterRegistrationBean<>(new InboundIdentityHeaderSanitizingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }

    /**
     * Also registered UNCONDITIONALLY, right after the sanitizer: {@code /operations/internal/**} is service-to-service only and must never
     * be reachable through the public gateway. See {@link InternalEndpointGuardFilter}'s Javadoc.
     */
    @Bean
    public FilterRegistrationBean<InternalEndpointGuardFilter> internalEndpointGuardFilter() {
        FilterRegistrationBean<InternalEndpointGuardFilter> registration = new FilterRegistrationBean<>(new InternalEndpointGuardFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 3);
        return registration;
    }
}
