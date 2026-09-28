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
     * client must never be able to inject the gateway-owned {@code X-User-*} identity headers or the open-access API key. It runs after
     * {@code OpenAccessFilter} so that filter can consume the key for PSAMA before it is removed from the downstream-facing request. See
     * {@link InboundIdentityHeaderSanitizingFilter}'s Javadoc for the trust-boundary rationale.
     */
    @Bean
    public FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> inboundIdentityHeaderSanitizingFilter() {
        FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> registration =
            new FilterRegistrationBean<>(new InboundIdentityHeaderSanitizingFilter());
        registration.setOrder(25);
        return registration;
    }

    /**
     * Registered UNCONDITIONALLY ahead of every authentication filter: {@code /operations/internal/**} is service-to-service only and must
     * never be reachable through the public gateway or reach PSAMA. See {@link InternalEndpointGuardFilter}'s Javadoc.
     */
    @Bean
    public FilterRegistrationBean<InternalEndpointGuardFilter> internalEndpointGuardFilter() {
        FilterRegistrationBean<InternalEndpointGuardFilter> registration = new FilterRegistrationBean<>(new InternalEndpointGuardFilter());
        registration.setOrder(15);
        return registration;
    }
}
