package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.AccessLogFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InboundIdentityHeaderSanitizingFilter;

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
     * Registered UNCONDITIONALLY (unlike the DB-free auth chain in {@code SecurityConfig}, which only registers when the resolved effective
     * mode is not TRANSPARENT): a client must never be able to inject the gateway-owned {@code X-User-*} identity headers, regardless of
     * whether the auth chain is on. See {@link InboundIdentityHeaderSanitizingFilter}'s Javadoc for the trust-boundary rationale.
     */
    @Bean
    public FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> inboundIdentityHeaderSanitizingFilter() {
        FilterRegistrationBean<InboundIdentityHeaderSanitizingFilter> registration =
            new FilterRegistrationBean<>(new InboundIdentityHeaderSanitizingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        return registration;
    }
}
