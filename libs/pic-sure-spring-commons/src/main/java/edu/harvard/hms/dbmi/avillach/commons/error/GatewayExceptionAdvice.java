package edu.harvard.hms.dbmi.avillach.commons.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Base {@code @RestControllerAdvice} mapping {@link PicsureException} to its carried status with an additive JSON body of
 * {@code {errorType, message, requestId}}, where {@code requestId} comes from {@code MDC[requestId]} (set by
 * {@code edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter}). Kept minimal and non-final so gateway/WAR call sites can extend
 * it with additional {@code @ExceptionHandler}s.
 */
@RestControllerAdvice
public class GatewayExceptionAdvice {

    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", exception.getErrorType());
        body.put("message", exception.getMessage());
        body.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(exception.getStatus()).body(body);
    }
}
