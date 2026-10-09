package edu.harvard.dbmi.avillach.visualization.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.WebRequest;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * The visualization service's exception-to-HTTP mapping, answering with an {@code {error}} body. Inherits the client-error and catch-all
 * handling from {@link PicsureExceptionAdvice} and adds the visualization domain's own exceptions. A consent denial alone answers with
 * {@code {errorType, message}}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends PicsureExceptionAdvice {

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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        logger.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @Override
    protected Object errorBody(HttpStatusCode status, String title, String detail) {
        return Map.of("error", detail);
    }
}
