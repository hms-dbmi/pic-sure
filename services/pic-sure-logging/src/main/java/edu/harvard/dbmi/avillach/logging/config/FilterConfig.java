package edu.harvard.dbmi.avillach.logging.config;

import edu.harvard.dbmi.avillach.logging.filter.ApiKeyAuthFilter;
import edu.harvard.dbmi.avillach.logging.filter.RequestSizeLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filter ordering across the app: 0 CorsFilter (in WebConfig) — answers preflight and short-circuits it 1 ApiKeyAuthFilter — 401 2
 * RequestSizeLimitFilter — 413
 */
@Configuration
public class FilterConfig {

    /**
     * Order 1: authentication precedes the size check, matching Javalin, where app.before("/audit") ran before ctx.body() ever triggered
     * the maxRequestSize check. An unauthenticated oversized request therefore gets 401, not 413.
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilterRegistration(LoggingProperties properties) {
        FilterRegistrationBean<ApiKeyAuthFilter> registration = new FilterRegistrationBean<>(new ApiKeyAuthFilter(properties.apiKey()));
        registration.addUrlPatterns("/audit");
        registration.setOrder(1);
        return registration;
    }

    /** Order 2: runs after the API key check, so an unauthenticated oversized body gets 401, not 413. */
    @Bean
    public FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilterRegistration() {
        FilterRegistrationBean<RequestSizeLimitFilter> registration =
            new FilterRegistrationBean<>(new RequestSizeLimitFilter(RequestSizeLimitFilter.MAX_REQUEST_BYTES));
        registration.addUrlPatterns("/audit");
        registration.setOrder(2);
        return registration;
    }
}
