package edu.harvard.hms.dbmi.avillach.gateway.error;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
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
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own request errors keep their statuses: a wrong method answers 405 with
 * {@code Allow}, an unsupported media type 415 with {@code Accept}, an unreadable body, a type mismatch or a missing parameter 400. Those
 * handlers are closer matches than {@link #unknown}, so Spring picks them first, and {@link #handleExceptionInternal} gives their responses
 * the unified body.
 *
 * <p>NOTE this cannot catch everything the gateway does: the auth chain runs as servlet FILTERS, and an exception thrown in a filter never
 * reaches Spring MVC's exception resolvers. Those paths fail closed by writing {@link GatewayErrors} directly (see
 * {@code PsamaIntrospectionFilter} / {@code BufferingFilter}); this advice covers the routed/dispatched side.
 */
@RestControllerAdvice
public class GatewayExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException e) {
        return body(e.getStatus(), e.getErrorType(), e.getMessage());
    }

    /**
     * Route absence must stay an honest 404 rather than being flattened by {@link #unknown}. The gateway front-ends every path in the
     * stack, so a request for a prefix no route owns is a routine client error, not a server fault.
     *
     * @param e the missing route
     * @param headers headers the base class prepared for the response
     * @param status the status the base class chose, always 404
     * @param request the current request
     * @return a 404 naming the path
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
        NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        return handleExceptionInternal(e, bodyMap("not_found", "No such resource: " + e.getResourcePath()), headers, status, request);
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

    /**
     * Writes every response the base class produces in the unified {@code {errorType, message, requestId}} shape, keeping the status and
     * headers it chose. A 4xx carries Spring's detail; a 5xx is logged and carries the same text as {@link #unknown}. A body an override
     * already built passes through unchanged.
     *
     * @param ex the exception being handled
     * @param body the body built so far, or {@code null}
     * @param headers response headers, such as {@code Allow} on a 405 and {@code Accept} on a 415
     * @param statusCode the response status
     * @param request the current request
     * @return the response, or {@code null} when the response is already committed
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
        Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request
    ) {
        ResponseEntity<Object> framework = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (framework == null || !(framework.getBody() == null || framework.getBody() instanceof ProblemDetail)) {
            return framework;
        }
        Map<String, Object> unified;
        if (statusCode.is5xxServerError()) {
            logger.error("Unhandled exception", ex);
            unified = bodyMap(statusCode.value() == 500 ? "internal_error" : errorTypeFor(statusCode), "An unexpected error occurred");
        } else {
            String detail = clientErrorDetail(ex, framework.getBody());
            unified = bodyMap(errorTypeFor(statusCode), detail == null || detail.isBlank() ? "Request could not be completed" : detail);
        }
        return new ResponseEntity<>(unified, framework.getHeaders(), framework.getStatusCode());
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
        return ResponseEntity.status(status).body(bodyMap(errorType, message));
    }

    private static Map<String, Object> bodyMap(String errorType, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("errorType", errorType);
        b.put("message", message);
        b.put("requestId", MDC.get("requestId"));
        return b;
    }

    /**
     * Reads Spring's detail for a client error, replacing the details that quote client input: a type mismatch names only the parameter,
     * and a 405 or 415 gets fixed text instead of the request's method or Content-Type.
     */
    private static String clientErrorDetail(Exception ex, @Nullable Object body) {
        if (ex instanceof TypeMismatchException mismatch) {
            String name = mismatch.getPropertyName();
            return name == null ? "Invalid value for a request parameter" : "Invalid value for '" + name + "'";
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return "This endpoint does not support the request method.";
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return "This endpoint does not accept the request's content type.";
        }
        return body instanceof ProblemDetail problem ? problem.getDetail() : null;
    }
}
