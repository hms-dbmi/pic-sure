package edu.harvard.hms.dbmi.avillach.gateway.error;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * The gateway's exception-to-HTTP mapping, mirroring the query-service's {@code GlobalExceptionHandler} so every service in the stack
 * answers with the same {@code {errorType, message, requestId}} body. Self-contained rather than extending commons'
 * {@code GatewayExceptionAdvice}: Spring's {@code ExceptionHandlerExceptionResolver} picks the FIRST {@code @ControllerAdvice} bean that
 * has ANY matching handler, so keeping a {@link PicsureException} handler in this same class guarantees the catch-all below always resolves
 * against a bean that also handles the specific cases.
 *
 * <p>Before this existed the gateway had no advice at all, so an unmapped exception fell through to Boot's {@code BasicErrorController} and
 * answered {@code {timestamp,status,error,path}} -- a shape that names neither the failure nor the request id, and is indistinguishable
 * from an error raised by any other Spring app in the chain.
 *
 * <p>NOTE this cannot catch everything the gateway does: the auth chain runs as servlet FILTERS, and an exception thrown in a filter never
 * reaches Spring MVC's exception resolvers. Those paths fail closed by writing {@link GatewayErrors} directly (see
 * {@code PsamaIntrospectionFilter} / {@code BufferingFilter}); this advice covers the routed/dispatched side.
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException e) {
        return body(e.getStatus(), e.getErrorType(), e.getMessage());
    }

    /**
     * Route absence must stay an honest 404 rather than being flattened by {@link #unknown}. The gateway front-ends every path in the
     * stack, so a request for a prefix no route owns is a routine client error, not a server fault.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "No such resource: " + e.getResourcePath());
    }

    /**
     * Preserves the status Spring MVC already decided on ({@code ResponseStatusException} and everything built on it) instead of letting
     * {@link #unknown} relabel a deliberate 4xx as a 500.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> statusException(ResponseStatusException e) {
        return body(e.getStatusCode(), errorTypeFor(e.getStatusCode()), detailOf(e, e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception e) {
        logger.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    /** Lowercased status name ({@code NOT_FOUND} -> {@code not_found}); non-standard codes fall back to a generic label. */
    private static String errorTypeFor(HttpStatusCode code) {
        HttpStatus resolved = HttpStatus.resolve(code.value());
        return resolved == null ? "error" : resolved.name().toLowerCase(Locale.ROOT);
    }

    private static String detailOf(ErrorResponse e, String fallback) {
        String detail = e.getBody() == null ? null : e.getBody().getDetail();
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        return fallback == null || fallback.isBlank() ? "Request could not be completed" : fallback;
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String errorType, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("errorType", errorType);
        b.put("message", message);
        b.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(status).body(b);
    }
}
