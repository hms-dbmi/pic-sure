package edu.harvard.hms.dbmi.avillach.operations.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import edu.harvard.hms.dbmi.avillach.commons.request.RequestFilterRegistrations;
import edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter;

/**
 * Registers the commons {@link RequestIdFilter} (mirrors {@code pic-sure-gateway}'s {@code ObservabilityConfig}) so every request/response
 * carries {@code X-Request-Id} and every log line + {@link edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice} error body
 * on this service is tagged with it.
 */
@Configuration
public class RequestConfig {

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        return RequestFilterRegistrations.requestId();
    }
}
