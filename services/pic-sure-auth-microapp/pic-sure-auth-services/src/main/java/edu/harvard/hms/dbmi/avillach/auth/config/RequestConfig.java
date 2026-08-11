package edu.harvard.hms.dbmi.avillach.auth.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * Registers the commons {@link RequestIdFilter} (mirroring the gateway's {@code ObservabilityConfig} and the operations service's
 * {@code RequestConfig}) so every PSAMA request/response carries {@code X-Request-Id}, every log line is tagged with it, and the
 * {@code requestId} in {@link edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler}'s error bodies is a real value a user
 * can quote rather than {@code null}.
 *
 * <p>Registered explicitly because the filter lives outside this application's scanned base package.
 */
@Configuration
public class RequestConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
