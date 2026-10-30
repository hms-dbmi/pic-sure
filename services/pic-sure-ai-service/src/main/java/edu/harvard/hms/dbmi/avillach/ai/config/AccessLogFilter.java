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
        boolean failed = true;
        try {
            filterChain.doFilter(request, response);
            failed = false;
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            // An exception escaping the chain has not set the status yet; the container answers it with a 500.
            int status = failed && response.getStatus() < 400 ? 500 : response.getStatus();
            LOG.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), status, durationMs);
        }
    }
}
