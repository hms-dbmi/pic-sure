package edu.harvard.dbmi.avillach.logging.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Map;

/**
 * Handles only what reaches DispatcherServlet. 401 and the declared-Content-Length 413 are written by FilterRegistrationBean filters, which
 * run outside MVC and write their own responses. The chunked-body 413 is different: it arrives here as RequestBodyTooLargeException, thrown
 * during {@code @RequestBody} argument resolution.
 *
 * <p>Extends ResponseEntityExceptionHandler deliberately. ExceptionHandlerExceptionResolver runs before DefaultHandlerExceptionResolver, so
 * a bare @ExceptionHandler(Exception.class) would swallow Spring MVC's own exceptions and turn them into 500s. The one that matters: an
 * empty request body raises HttpMessageNotReadableException before the controller runs, and the frozen contract requires 400 there, not
 * 500. The base class maps it correctly.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(BadRequestException e) {
        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
    }

    /**
     * Raised by the counting wrapper on a chunked body that passes the 1 MB cap. The declared-Content-Length case never reaches here —
     * RequestSizeLimitFilter writes that 413 itself, outside DispatcherServlet.
     */
    @ExceptionHandler(RequestBodyTooLargeException.class)
    public ResponseEntity<Map<String, String>> handleTooLarge(RequestBodyTooLargeException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("status", "error", "message", "Request body too large"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("status", "error", "message", "Internal server error"));
    }
}
