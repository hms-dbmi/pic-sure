package edu.harvard.hms.dbmi.avillach.ai.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One INFO line per request -- method, path, status, duration -- in the same format as {@code pic-sure-gateway}'s {@code AccessLogFilter},
 * so one parse rule covers both services' logs. Registered inside {@code RequestIdFilter}, so the line carries {@code MDC[requestId]}.
 * Actuator endpoints are skipped: health probes and Prometheus scrapes would otherwise dominate the stream.
 */
class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger("ai.access");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            LOG.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
        }
    }
}
