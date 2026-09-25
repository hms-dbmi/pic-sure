package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Global exception handler for the PICSURE Auth application. Provides centralized exception handling for various types of exceptions.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring MVC's own request errors keep their statuses: a wrong method answers 405 with
 * {@code Allow}, an unsupported media type 415 with {@code Accept}, a missing parameter 400 and an unknown path 404. Those handlers are
 * closer matches than the {@link RuntimeException} and {@link Exception} fallbacks below, so Spring picks them first, and
 * {@link #handleExceptionInternal} gives their responses this service's {@code {message, content}} body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String UNEXPECTED_MESSAGE = "An unexpected error occurred";
    private static final String UNEXPECTED_CONTENT = "Please contact the system administrator with the time this error occurred.";

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handles database constraint violations which occur when trying to delete records that are referenced by other entities through
     * foreign keys.
     * 
     * @param ex The exception thrown by the database or Spring's data layer
     * @return A response with HTTP 409 Conflict status and explanatory message
     */
    @ExceptionHandler({SQLIntegrityConstraintViolationException.class, DataIntegrityViolationException.class})
    public ResponseEntity<?> handleConstraintViolation(Exception ex) {
        logger.error("Database constraint violation: {}", ex.getMessage());
        return PICSUREResponse.error(
            HttpStatus.CONFLICT, "Cannot delete this resource as it's referenced by other entities in the system",
            "This resource is still being used by other records in the database. You must remove those references before deleting this item."
        );
    }

    /**
     * Handles cases where a user attempts an operation they don't have permission for.
     * 
     * @param ex The access denied exception
     * @return A response with HTTP 403 Forbidden status and explanatory message
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<?> handleAccessDenied(AccessDeniedException ex) {
        logger.warn("Access denied: {}", ex.getMessage());
        return PICSUREResponse.error(HttpStatus.FORBIDDEN, "You do not have permission to perform this operation", ex.getMessage());
    }

    /**
     * Handles NotAuthorizedException which is thrown when a user is not authorized to perform certain operations.
     * 
     * @param ex The not authorized exception
     * @return A response with HTTP 401 Unauthorized status and explanatory message
     */
    @ExceptionHandler(NotAuthorizedException.class)
    public ResponseEntity<?> handleNotAuthorized(NotAuthorizedException ex) {
        logger.warn("Not authorized: {}", ex.getMessage());
        return PICSUREResponse.error(HttpStatus.UNAUTHORIZED, "Authorization failed", ex.getMessage());
    }

    /**
     * Answers an unreadable request body without echoing the parser's message, which can quote the client's payload.
     *
     * @param ex the parse failure
     * @param headers headers the base class prepared for the response
     * @param status the status the base class chose, always 400
     * @param request the current request
     * @return a 400 with a fixed message
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
        HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request
    ) {
        logger.warn("Rejected unreadable request body");
        return handleExceptionInternal(
            ex, PICSUREResponse.error(HttpStatus.BAD_REQUEST, "Malformed request body", "The request body could not be parsed.").getBody(),
            headers, status, request
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Class<?> requiredType = ex.getRequiredType();
        String expected = requiredType == null ? "a different type"
            : requiredType.isEnum()
                ? "one of " + Arrays.stream(requiredType.getEnumConstants()).map(Object::toString).collect(Collectors.joining(", "))
                : "a " + requiredType.getSimpleName();
        logger.warn("Type mismatch for request parameter '{}': expected {}", ex.getName(), expected);
        return PICSUREResponse
            .error(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'", "Expected " + expected + ".");
    }

    /**
     * Handles IllegalArgumentException, which is commonly used for validation errors.
     *
     * @param ex The exception
     * @return A response with HTTP 400 Bad Request status
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Invalid request argument: {}", ex.getMessage());
        return PICSUREResponse.error(HttpStatus.BAD_REQUEST, "Invalid request", ex.getMessage());
    }

    /**
     * Handles NullPointerException, often indicating a system error.
     * 
     * @param ex The exception
     * @return A response with HTTP 500 Internal Server Error status
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<?> handleNullPointer(NullPointerException ex) {
        logger.error("Null pointer exception: ", ex);
        return PICSUREResponse.error(
            HttpStatus.INTERNAL_SERVER_ERROR, "An internal server error occurred",
            "Please contact the system administrator with the time this error occurred."
        );
    }

    /**
     * Handles UsernameNotFoundException thrown during authentication.
     * 
     * @param ex The exception
     * @return A response with HTTP 401 Unauthorized status
     */
    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<?> handleUsernameNotFound(UsernameNotFoundException ex) {
        logger.warn("Username not found: {}", ex.getMessage());
        return PICSUREResponse.error(HttpStatus.UNAUTHORIZED, "Authentication failed", ex.getMessage());
    }

    /**
     * Handles HTTP client errors from external API calls.
     * 
     * @param ex The HTTP client error exception
     * @return A response with the appropriate HTTP status from the exception
     */
    @ExceptionHandler({HttpClientErrorException.class, HttpServerErrorException.class})
    public ResponseEntity<?> handleHttpClientError(Exception ex) {
        HttpStatus status = HttpStatus.BAD_GATEWAY;
        String errorMsg = ex.getMessage();

        if (ex instanceof HttpClientErrorException clientEx) {
            status = HttpStatus.valueOf(clientEx.getStatusCode().value());
            logger.error("HTTP client error: {} - {}", status, clientEx.getMessage());
        } else if (ex instanceof HttpServerErrorException serverEx) {
            status = HttpStatus.valueOf(serverEx.getStatusCode().value());
            logger.error("HTTP server error: {} - {}", status, serverEx.getMessage());
        }

        return PICSUREResponse.error(status, "Error communicating with external service", errorMsg);
    }

    /**
     * Handles RuntimeException, which includes many business logic errors.
     * 
     * @param ex The runtime exception
     * @return A response with HTTP 500 Internal Server Error status
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<?> handleRuntime(RuntimeException ex) {
        logger.error("Runtime exception: ", ex);
        return PICSUREResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, "An error occurred while processing your request", ex.getMessage());
    }

    /**
     * Fallback handler for all other exceptions that aren't specifically handled.
     * 
     * @param ex The exception
     * @return A response with HTTP 500 Internal Server Error status
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGenericException(Exception ex) {
        logger.error("Unhandled exception: ", ex);
        return PICSUREResponse.error(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED_MESSAGE, UNEXPECTED_CONTENT);
    }

    /**
     * Writes every response the base class produces in this service's {@code {message, content}} shape, keeping the status and headers it
     * chose. A 4xx carries the status's reason phrase and Spring's detail; a 5xx is logged and carries the same fixed text as
     * {@link #handleGenericException}. A body an override already built passes through unchanged.
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
        Object unified;
        if (statusCode.is5xxServerError()) {
            logger.error("Unhandled exception: ", ex);
            unified = PICSUREResponse.error(UNEXPECTED_MESSAGE, UNEXPECTED_CONTENT).getBody();
        } else {
            unified = PICSUREResponse.error(reasonPhrase(statusCode), detail(framework.getBody())).getBody();
        }
        return new ResponseEntity<>(unified, framework.getHeaders(), framework.getStatusCode());
    }

    private static String reasonPhrase(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved == null ? "Request could not be completed" : resolved.getReasonPhrase();
    }

    private static String detail(@Nullable Object body) {
        String detail = body instanceof ProblemDetail problem ? problem.getDetail() : null;
        return detail == null || detail.isBlank() ? "Request could not be completed" : detail;
    }
}
