package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

/**
 * PSAMA's exception-to-HTTP mapping. Every answer is the {@code {errorType, message, requestId}} body every other PIC-SURE service emits,
 * replacing {@code PicSureResponseBody} ({@code {message, content}}) -- an envelope that put a generic label in {@code message} and the
 * actual detail in {@code content}, and that no other service in the stack spoke.
 *
 * <p>The {@code message} key is preserved deliberately: it is the one field admin clients read out of a PSAMA failure, and it now carries
 * the specific detail that used to hide in {@code content}. HTTP statuses are unchanged from the envelope era with one exception, noted on
 * {@code AccessRuleController#getAccessRuleById}, where "not found" was answering 500.
 *
 * <p>{@link PicsureException} is handled here as well as by commons' {@code GatewayExceptionAdvice} (imported on {@code Application}).
 * Spring's {@code ExceptionHandlerExceptionResolver} picks the FIRST {@code @ControllerAdvice} bean with ANY matching handler rather than
 * the most specific handler across all beans, so this advice must be self-sufficient for its own {@link #handleGenericException} catch-all
 * to never swallow a {@code PicsureException}; both handlers produce byte-identical bodies, so which one wins does not matter.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Carries the status and machine-readable type the throwing code chose. */
    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException ex) {
        return body(ex.getStatus(), ex.getErrorType(), ex.getMessage());
    }

    /** Database constraint violations, raised when deleting a record other entities still reference. */
    @ExceptionHandler({SQLIntegrityConstraintViolationException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(Exception ex) {
        logger.error("Database constraint violation: {}", ex.getMessage());
        return body(HttpStatus.CONFLICT, "conflict", "Cannot delete this resource as it's referenced by other entities in the system");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return body(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to perform this operation");
    }

    @ExceptionHandler(NotAuthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleNotAuthorized(NotAuthorizedException ex) {
        logger.warn("Not authorized: {}", ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", ex.getMessage());
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleUsernameNotFound(UsernameNotFoundException ex) {
        logger.warn("Username not found: {}", ex.getMessage());
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Invalid request argument: {}", ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage());
    }

    /**
     * A body the typed records cannot bind is the CALLER's error, so it must be a 400 rather than falling into
     * {@link #handleGenericException}'s 500 -- {@code @ExceptionHandler(Exception.class)} in this same advice wins over Spring's own
     * {@code DefaultHandlerExceptionResolver}, so without this mapping a client typo would look like a server fault.
     *
     * <p>SECURITY: the message is deliberately generic, matching the query service's handler. Jackson's own text names the fully-qualified
     * bound type, its reference chain, and the COMPLETE list of properties it does know -- a rejected {@code {"notAUserField":"x"}} on
     * {@code PUT /user} otherwise answers with all twelve of {@code User}'s field names. Worse, it quotes the offending content, and two of
     * the endpoints that can raise this are UNAUTHENTICATED: {@code POST /authentication/&#123;idpProvider&#125;}, whose body carries an
     * OIDC {@code code} or an Auth0 {@code access_token}, and {@code POST /open/validate}. The detail stays in the log, where it is still
     * available for diagnosis, and off the wire. {@code UnreadableBodyDisclosureTest} is the guard.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        logger.warn("Rejected an unreadable request body: {}", ex.getMostSpecificCause().getMessage());
        return body(HttpStatus.BAD_REQUEST, "bad_request", "Malformed or unrecognized request body");
    }

    /** Route absence stays an honest 404 instead of being flattened into the catch-all's 500. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "not_found", "No such resource: " + ex.getResourcePath());
    }

    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<Map<String, Object>> handleNullPointer(NullPointerException ex) {
        logger.error("Null pointer exception: ", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An internal server error occurred");
    }

    /** Preserves the status an upstream identity provider or API actually returned rather than relabelling it. */
    @ExceptionHandler({HttpClientErrorException.class, HttpServerErrorException.class})
    public ResponseEntity<Map<String, Object>> handleHttpClientError(Exception ex) {
        HttpStatusCode status = HttpStatus.BAD_GATEWAY;
        if (ex instanceof HttpClientErrorException clientEx) {
            status = clientEx.getStatusCode();
            logger.error("HTTP client error: {} - {}", status, clientEx.getMessage());
        } else if (ex instanceof HttpServerErrorException serverEx) {
            status = serverEx.getStatusCode();
            logger.error("HTTP server error: {} - {}", status, serverEx.getMessage());
        }
        return body(status, "upstream_error", ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntime(RuntimeException ex) {
        logger.error("Runtime exception: ", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An error occurred while processing your request");
    }

    /**
     * Fallback. The message is deliberately generic: an unmapped failure's own message is as likely to be a stack-trace fragment or a
     * connection string as anything a caller can act on.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        logger.error("Unhandled exception: ", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatusCode status, String errorType, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", errorType);
        body.put("message", message);
        body.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(status).body(body);
    }
}
