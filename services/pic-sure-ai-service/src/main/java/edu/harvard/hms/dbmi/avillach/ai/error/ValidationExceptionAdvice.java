package edu.harvard.hms.dbmi.avillach.ai.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps a {@code @Valid}-rejected {@code ChatRequest} (missing {@code message}, {@code conversationId}, or {@code requestId}) to 400 with a
 * field-level error body, per Story 2's AC: "a request missing required fields... returns 400 with a field-level error, not 500." Separate
 * from the shared {@code GatewayExceptionAdvice} (which only maps {@code PicsureException}) since bean-validation failures on a
 * {@code @RequestBody} are a different exception type this service is the first to need to shape a response for.
 */
@RestControllerAdvice
public class ValidationExceptionAdvice {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", "validation_error");
        body.put("message", "Request failed validation");
        body.put("fieldErrors", fieldErrors);
        body.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
