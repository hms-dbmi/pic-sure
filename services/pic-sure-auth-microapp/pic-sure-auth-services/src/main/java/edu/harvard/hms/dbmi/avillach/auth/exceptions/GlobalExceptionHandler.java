package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.sql.SQLIntegrityConstraintViolationException;

/**
 * Global exception handler for the PICSURE Auth application.
 * Provides centralized exception handling for various types of exceptions.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    
    /**
     * Handles database constraint violations which occur when trying to delete
     * records that are referenced by other entities through foreign keys.
     * 
     * @param ex The exception thrown by the database or Spring's data layer
     * @return A response with HTTP 409 Conflict status and explanatory message
     */
    @ExceptionHandler({SQLIntegrityConstraintViolationException.class, DataIntegrityViolationException.class})
    public ResponseEntity<?> handleConstraintViolation(Exception ex) {
        logger.error("Database constraint violation: {}", ex.getMessage());
        return PICSUREResponse.error(
            HttpStatus.CONFLICT, 
            "Cannot delete this resource as it's referenced by other entities in the system", 
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
        return PICSUREResponse.error(
            HttpStatus.FORBIDDEN,
            "You do not have permission to perform this operation",
            ex.getMessage()
        );
    }
    
    /**
     * Handles NotAuthorizedException which is thrown when a user is not authorized
     * to perform certain operations.
     * 
     * @param ex The not authorized exception
     * @return A response with HTTP 401 Unauthorized status and explanatory message
     */
    @ExceptionHandler(NotAuthorizedException.class)
    public ResponseEntity<?> handleNotAuthorized(NotAuthorizedException ex) {
        logger.warn("Not authorized: {}", ex.getMessage());
        return PICSUREResponse.error(
            HttpStatus.UNAUTHORIZED,
            "Authorization failed",
            ex.getMessage()
        );
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
        return PICSUREResponse.error(
            HttpStatus.BAD_REQUEST,
            "Invalid request",
            ex.getMessage()
        );
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
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An internal server error occurred",
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
        return PICSUREResponse.error(
            HttpStatus.UNAUTHORIZED,
            "Authentication failed",
            ex.getMessage()
        );
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
        
        return PICSUREResponse.error(
            status,
            "Error communicating with external service",
            errorMsg
        );
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
        return PICSUREResponse.error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An error occurred while processing your request",
            ex.getMessage()
        );
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
        return PICSUREResponse.error(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "An unexpected error occurred",
            "Please contact the system administrator with the time this error occurred."
        );
    }
} 