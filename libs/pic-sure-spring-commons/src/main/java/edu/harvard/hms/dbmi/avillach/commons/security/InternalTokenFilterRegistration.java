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
     * Creates the registration for the internal token gate. Whitespace around the configured secret is removed before it is compared, so a
     * trailing line ending in one service's environment cannot make two identical secrets disagree. The header a caller sends is compared
     * as received.
     *
     * @param expectedToken the configured shared secret; a null value, or one that is blank once stripped, rejects every internal request
     * @return a registration that maps {@link InternalTokenFilter} to {@code /internal/*} only
     */
    public static FilterRegistrationBean<InternalTokenFilter> create(String expectedToken) {
        String strippedToken = expectedToken == null ? null : expectedToken.strip();
        FilterRegistrationBean<InternalTokenFilter> registration = new FilterRegistrationBean<>(new InternalTokenFilter(strippedToken));
        registration.addUrlPatterns(URL_PATTERN);
        return registration;
    }
}
