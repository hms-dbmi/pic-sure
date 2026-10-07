package edu.harvard.hms.dbmi.avillach.query.error;

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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * This service's own exception-to-HTTP mapping. {@code pic-sure-spring-commons}' {@code PicsureErrorBodyAdvice} already maps
 * {@link PicsureException} to its carried status with the {@code {errorType,message,requestId}} body shape. That handler is duplicated here
 * (identical behavior) rather than relied upon exclusively, because Spring's {@code ExceptionHandlerExceptionResolver} picks the FIRST
 * {@code @ControllerAdvice} bean (in an unspecified-by-us order) that has ANY matching handler for a given exception, not the most-specific
 * match across all beans. Keeping a self-contained {@link PicsureException} handler in this same class guarantees this advice always
 * resolves the most specific handler for its own {@link #unknown} catch-all, regardless of whichever advice bean Spring happens to consult
 * first. {@code PicsureErrorBodyAdvice}'s equivalent handler, if consulted first, produces the identical response.
 *
 * <p>Adds three mappings the commons base does not have: {@link HpdsCommunicationException} -&gt; 502 because HPDS is upstream
 * infrastructure, {@link NoResourceFoundException} -&gt; 404 for route absence, and any other unmapped exception -&gt; 500. All share the
 * same commons error body shape.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own request errors keep their statuses: a wrong method answers 405 with
 * {@code Allow}, an unsupported media type 415 with {@code Accept}, an unreadable body, a type mismatch or a missing parameter 400. Those
 * handlers are closer matches than {@link #unknown}, so Spring picks them first, and {@link #handleExceptionInternal} gives their responses
 * the same body shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException e) {
        return body(e.getStatus(), e.getErrorType(), e.getMessage());
    }

    @ExceptionHandler(HpdsCommunicationException.class)
    public ResponseEntity<Map<String, Object>> hpdsUnavailable(HpdsCommunicationException e) {
        // The cause is a RestClientException whose message carries the HPDS status and response body. Without logging it
        // here, the 502 is undiagnosable from this service's logs.
        logger.error("HPDS call failed, returning 502", e);
        return body(HttpStatus.BAD_GATEWAY, "upstream_unavailable", e.getMessage());
    }

    /**
     * Route absence must surface as a 404 rather than falling into the {@link #unknown} 500 catch-all.
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception e) {
        logger.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    /**
     * Writes every response the base class produces in the commons {@code {errorType, message, requestId}} shape, keeping the status and
     * headers it chose. A 4xx carries the text {@link #clientErrorDetail} chooses, which never quotes the request; a 5xx is logged and
     * carries the same text as {@link #unknown}. A body an override already built passes through unchanged.
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

    private static String errorTypeFor(HttpStatusCode code) {
        HttpStatus resolved = HttpStatus.resolve(code.value());
        return resolved == null ? "error" : resolved.name().toLowerCase(Locale.ROOT);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String errorType, String message) {
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
