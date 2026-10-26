package edu.harvard.hms.dbmi.avillach.commons.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * Builds the one registration of {@link InternalTokenFilter}, scoped by the servlet container to {@code /internal/*}. Container URL-pattern
 * matching is normalized and relative to the context path, the same routing the {@code DispatcherServlet} uses to reach the internal
 * controllers, so the gate and the endpoints cannot disagree about which requests are internal.
 */
public final class InternalTokenFilterRegistration {

    public static final String URL_PATTERN = "/internal/*";

    private InternalTokenFilterRegistration() {}

    /**
     * Creates the registration for the internal token gate.
     *
     * @param expectedToken the configured shared secret; a null or blank value rejects every internal request
     * @return a registration that maps {@link InternalTokenFilter} to {@code /internal/*} only
     */
    public static FilterRegistrationBean<InternalTokenFilter> create(String expectedToken) {
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(new InternalTokenFilter(expectedToken));
        registration.addUrlPatterns(URL_PATTERN);
        return registration;
    }
}
