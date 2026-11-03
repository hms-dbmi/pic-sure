package edu.harvard.hms.dbmi.avillach.ai.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.request.AccessLogFilter;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestFilterRegistrations;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * Registers the commons {@link RequestIdFilter} (same pattern as {@code pic-sure-operations-service}'s {@code RequestConfig}) so every
 * request/response carries {@code X-Request-Id} and every log line + {@code PicsureErrorBodyAdvice} error body on this service is tagged
 * with it.
 */
@Configuration
public class RequestConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return RequestFilterRegistrations.requestId();
    }

    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter() {
        return RequestFilterRegistrations.accessLog("ai.access");
    }
}
