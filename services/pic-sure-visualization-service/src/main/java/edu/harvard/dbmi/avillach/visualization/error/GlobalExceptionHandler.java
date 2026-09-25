package edu.harvard.dbmi.avillach.visualization.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * The visualization service's exception-to-HTTP mapping, answering with an {@code {error}} body.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own request errors keep their statuses: a wrong method answers 405 with
 * {@code Allow}, an unsupported media type 415 with {@code Accept}, a type mismatch or a missing parameter 400. Those handlers are closer
 * matches than {@link #handleGenericException}, so Spring picks them first, and {@link #handleExceptionInternal} gives their responses the
 * same body shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String INTERNAL_ERROR = "Internal server error";

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadVisualizationRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadVisualizationRequest(BadVisualizationRequestException e) {
        logger.warn("Visualization request error: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ConsentDeniedException.class)
    public ResponseEntity<Map<String, String>> handleConsentDenied(ConsentDeniedException e) {
        logger.warn("Visualization consent denied: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("errorType", "consent_denied", "message", e.getMessage()));
    }

    @ExceptionHandler(HpdsUpstreamException.class)
    public ResponseEntity<Map<String, String>> handleHpdsUpstreamException(HpdsUpstreamException e) {
        logger.error("Upstream query-service error: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VisualizationConfigurationException.class)
    public ResponseEntity<Map<String, String>> handleVisualizationConfigurationException(VisualizationConfigurationException e) {
        logger.error("Service misconfiguration: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(VisualizationException.class)
    public ResponseEntity<Map<String, String>> handleVisualizationException(VisualizationException e) {
        Throwable cause = e.getCause();

        if (cause instanceof HttpStatusCodeException) {
            logger.error("Upstream query-service HTTP error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }

        if (cause instanceof ResourceAccessException) {
            logger.error("Upstream query-service unreachable: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
        }

        logger.warn("Visualization error: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * Answers a request body that fails bean validation with each field's message.
     *
     * @param e the validation failure
     * @param headers headers the base class prepared for the response
     * @param status the status the base class chose, always 400
     * @param request the current request
     * @return a 400 listing the invalid fields
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        String errors = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField() + ": " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        logger.warn("Validation error: {}", errors);
        return handleExceptionInternal(e, Map.of("error", errors), headers, status, request);
    }

    /**
     * Answers an unreadable request body without echoing the parser's message.
     *
     * @param e the parse failure
     * @param headers headers the base class prepared for the response
     * @param status the status the base class chose, always 400
     * @param request the current request
     * @return a 400 with a fixed message
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException e, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        logger.warn("Malformed request body: {}", e.getMessage());
        return handleExceptionInternal(e, Map.of("error", "Malformed request body"), headers, status, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    /**
     * Answers a path no handler serves.
     *
     * @param e the missing resource
     * @param headers headers the base class prepared for the response
     * @param status the status the base class chose, always 404
     * @param request the current request
     * @return a 404 with a fixed message
     */
    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(
        NoResourceFoundException e, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        logger.warn("Resource not found: {}", e.getResourcePath());
        return handleExceptionInternal(e, Map.of("error", "Not found"), headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGenericException(Exception e) {
        logger.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", INTERNAL_ERROR));
    }

    /**
     * Writes every response the base class produces in this service's {@code {error}} shape, keeping the status and headers it chose. A 4xx
     * carries Spring's detail; a 5xx is logged and carries the same text as {@link #handleGenericException}. A body an override already
     * built passes through unchanged.
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
        String message;
        if (statusCode.is5xxServerError()) {
            logger.error("Unexpected error", ex);
            message = INTERNAL_ERROR;
        } else {
            String detail = clientErrorDetail(ex, framework.getBody());
            message = detail == null || detail.isBlank() ? "Request could not be completed" : detail;
        }
        return new ResponseEntity<>(Map.of("error", message), framework.getHeaders(), framework.getStatusCode());
    }

    /**
     * Reads Spring's detail for a client error. A type mismatch names only the parameter, because Spring's own detail quotes the client's
     * value.
     */
    private static String clientErrorDetail(Exception ex, @Nullable Object body) {
        if (ex instanceof TypeMismatchException mismatch) {
            return "Invalid value for '" + mismatch.getPropertyName() + "'";
        }
        return body instanceof ProblemDetail problem ? problem.getDetail() : null;
    }
}
