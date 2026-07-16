package edu.harvard.hms.dbmi.avillach.commons.error;

import org.springframework.http.HttpStatus;

/**
 * Fail-closed exception used by the gateway's auth filters. Carries an HTTP status and a machine-readable error type so callers (e.g.
 * {@link GatewayExceptionAdvice}) can render a consistent error body without inspecting the exception message.
 */
public class PicsureException extends RuntimeException {

    private final HttpStatus status;
    private final String errorType;

    public PicsureException(HttpStatus status, String errorType, String message) {
        super(message);
        this.status = status;
        this.errorType = errorType;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorType() {
        return errorType;
    }
}
