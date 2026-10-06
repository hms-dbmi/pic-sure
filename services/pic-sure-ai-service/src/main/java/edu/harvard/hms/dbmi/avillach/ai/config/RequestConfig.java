package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * Registers the commons {@link RequestIdFilter} (same pattern as {@code pic-sure-operations-service}'s {@code RequestConfig}) so every
 * request/response carries {@code X-Request-Id} and every log line + {@code GatewayExceptionAdvice} error body on this service is tagged
 * with it.
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
