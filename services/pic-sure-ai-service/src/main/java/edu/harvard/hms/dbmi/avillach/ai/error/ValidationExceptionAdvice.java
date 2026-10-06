package edu.harvard.hms.dbmi.avillach.ai.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice;

/**
 * This service's exception-to-HTTP mapping and its only advice bean. Inherits the {@code {errorType, message, requestId}} body, the
 * {@code PicsureException} mapping and the client-error and catch-all handling from {@link PicsureErrorBodyAdvice}, and shapes a
 * {@code @Valid}-rejected {@code ChatRequest} (missing {@code message}, {@code conversationId}, or {@code requestId}) as a 400 with a
 * field-level error, per Story 2's AC: "a request missing required fields... returns 400 with a field-level error, not 500."
 */
@RestControllerAdvice
public class ValidationExceptionAdvice extends PicsureErrorBodyAdvice {

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
        MethodArgumentNotValidException exception, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.put(error.getField(), error.getDefaultMessage()));

        Map<String, Object> body = body("validation_error", "Request failed validation");
        body.put("fieldErrors", fieldErrors);
        return handleExceptionInternal(exception, body, headers, HttpStatus.BAD_REQUEST, request);
    }
}
