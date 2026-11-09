package edu.harvard.hms.dbmi.avillach.commons.request;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * One INFO line per request -- method, path, status, duration -- in the same format on every service, so one parse rule covers all their
 * logs. Registered inside {@link RequestIdFilter}, so the line carries {@code MDC[requestId]}. Actuator endpoints are skipped: health
 * probes and Prometheus scrapes would otherwise dominate the stream.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private final Logger log;

    /** @param loggerName the logger the access lines go to, e.g. {@code gateway.access}, so a service's lines can be told apart */
    public AccessLogFilter(String loggerName) {
        this.log = LoggerFactory.getLogger(loggerName);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            filterChain.doFilter(request, response);
            failed = false;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            // An exception escaping the chain has not set the status yet; the container answers it with a 500.
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            log.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, durationMs);
        }
    }
}
