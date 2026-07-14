package edu.harvard.hms.dbmi.avillach.gateway.filter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import edu.harvard.hms.dbmi.avillach.gateway.auth.BufferedRequestWrapper;
import edu.harvard.hms.dbmi.avillach.gateway.error.GatewayErrors;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Buffers the request body (so downstream auth filters can read/mutate it), capped at GATEWAY_AUTH_MAX_BODY_BYTES. Over-cap → HTTP 413 with
 * the additive error body {errorType:REQUEST_BODY_TOO_LARGE,...} returned BEFORE PSAMA is ever called; body content is NEVER logged; a
 * body-too-large metric is emitted.
 */
public class BufferingFilter extends OncePerRequestFilter {

    static final String BODY_TOO_LARGE_METRIC = "gateway.auth.body_too_large";

    private final int maxBytes;
    private final MeterRegistry meterRegistry;

    public BufferingFilter(int maxBytes, MeterRegistry meterRegistry) {
        this.maxBytes = maxBytes;
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
        throws ServletException, IOException {
        byte[] body;
        try {
            body = readBody(req);
        } catch (BodyTooLargeException e) {
            // Do NOT log body content. Emit metric, return 413 with the additive error body, never call PSAMA.
            meterRegistry.counter(BODY_TOO_LARGE_METRIC).increment();
            GatewayErrors.write(
                resp, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_BODY_TOO_LARGE",
                "Request body exceeds the maximum size allowed for authorization processing."
            );
            return;
        }
        chain.doFilter(new BufferedRequestWrapper(req, body), resp);
    }

    private byte[] readBody(HttpServletRequest req) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, (int) req.getContentLengthLong()));
        try (InputStream in = req.getInputStream()) {
            byte[] buf = new byte[8192];
            int read, total = 0;
            while ((read = in.read(buf)) > 0) {
                total += read;
                if (total > maxBytes) {
                    throw new BodyTooLargeException();
                }
                out.write(buf, 0, read);
            }
        }
        return out.toByteArray();
    }

    private static final class BodyTooLargeException extends RuntimeException {
    }
}
