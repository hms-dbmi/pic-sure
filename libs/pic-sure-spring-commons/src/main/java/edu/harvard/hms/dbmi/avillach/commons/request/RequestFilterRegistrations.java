package edu.harvard.hms.dbmi.avillach.commons.request;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;

/**
 * The registrations every service makes for {@link RequestIdFilter} and {@link AccessLogFilter}, in the order that makes the access line
 * carry the request id: the id filter first, the access log one below it.
 */
public final class RequestFilterRegistrations {

    private RequestFilterRegistrations() {}

    /** @return the {@link RequestIdFilter}, ahead of every other filter so every log line and error body is tagged with the id */
    public static FilterRegistrationBean<RequestIdFilter> requestId() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * @param loggerName the logger the access lines go to
     * @return an {@link AccessLogFilter} one order below {@link #requestId()}, so every access line carries {@code MDC[requestId]}
     */
    public static FilterRegistrationBean<AccessLogFilter> accessLog(String loggerName) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter(loggerName));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
