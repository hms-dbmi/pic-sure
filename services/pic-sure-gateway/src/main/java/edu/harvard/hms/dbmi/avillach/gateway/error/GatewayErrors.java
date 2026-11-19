package edu.harvard.hms.dbmi.avillach.gateway.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the commons additive error-body shape ({@code {errorType, message, requestId}}, matching
 * {@code edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice}) directly to a servlet response, for filters that run before
 * Spring MVC's exception-handling machinery is reachable (e.g. {@code BufferingFilter}'s 413 short-circuit). {@code requestId} comes from
 * {@code MDC[requestId]}, set by {@code edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter}. The body goes to the response's
 * output stream as UTF-8 bytes and the {@code Content-Type} names that charset, so the encoding on the wire does not depend on the
 * container's default for character writers.
 */
public final class GatewayErrors {

    private static final String CONTENT_TYPE = "application/json;charset=UTF-8";

    private GatewayErrors() {}

    public static void write(HttpServletResponse resp, HttpStatus status, String errorType, String message) throws IOException {
        resp.setStatus(status.value());
        resp.setContentType(CONTENT_TYPE);
        String requestId = MDC.get("requestId");
        String body = "{\"errorType\":\"" + escape(errorType) + "\",\"message\":\"" + escape(message) + "\",\"requestId\":"
            + (requestId == null ? "null" : "\"" + escape(requestId) + "\"") + "}";
        resp.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
