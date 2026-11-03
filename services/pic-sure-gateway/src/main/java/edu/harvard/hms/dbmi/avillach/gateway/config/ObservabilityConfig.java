package edu.harvard.hms.dbmi.avillach.gateway.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import edu.harvard.hms.dbmi.avillach.commons.request.AccessLogFilter;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestFilterRegistrations;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InboundIdentityHeaderSanitizingFilter;
import edu.harvard.hms.dbmi.avillach.gateway.request.InternalEndpointGuardFilter;

@Configuration
public class ObservabilityConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return RequestFilterRegistrations.requestId();
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        return RequestFilterRegistrations.accessLog("gateway.access");
    }

    /**
     * Registered UNCONDITIONALLY, as an independent trust boundary alongside the always-on DB-free auth chain in {@code SecurityConfig}: a
     * client must never be able to inject the gateway-owned {@code X-User-*} identity headers. An {@code Authorization} header carrying a PSAMA
     * API key is removed from every request except one to the MCP endpoint, {@code /mcp}, where it is kept because {@code pic-sure-mcp}
     * forwards it on its loop-back calls. It runs after {@code OpenAccessFilter} so that filter can consume the key for PSAMA before it is removed from the
     * downstream-facing request. See {@link InboundIdentityHeaderSanitizingFilter}'s Javadoc for the trust-boundary rationale.
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
