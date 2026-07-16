package edu.harvard.hms.dbmi.avillach.gateway.error;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes the commons additive error-body shape ({@code {errorType, message, requestId}}, matching
 * {@code edu.harvard.hms.dbmi.avillach.commons.error.GatewayExceptionAdvice}) directly to a servlet response, for filters that run before
 * Spring MVC's exception-handling machinery is reachable (e.g. {@code BufferingFilter}'s 413 short-circuit). {@code requestId} comes from
 * {@code MDC[requestId]}, set by {@code edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter}.
 */
public final class GatewayErrors {

    private GatewayErrors() {}

    public static void write(HttpServletResponse resp, HttpStatus status, String errorType, String message) throws IOException {
        resp.setStatus(status.value());
        resp.setContentType("application/json");
        String requestId = MDC.get("requestId");
        String body = "{\"errorType\":\"" + escape(errorType) + "\",\"message\":\"" + escape(message) + "\",\"requestId\":"
            + (requestId == null ? "null" : "\"" + escape(requestId) + "\"") + "}";
        resp.getWriter().write(body);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
