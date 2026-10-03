package edu.harvard.hms.dbmi.avillach.auth.exceptions;

import org.springframework.http.HttpStatus;

import java.util.Objects;

/**
 * Ends a request with a chosen status and this service's {@code {message, content}} error body. A controller throws it instead of returning
 * an error {@code ResponseEntity}, so the handler's declared return type names its success body alone.
 * {@link GlobalExceptionHandler#handlePicSureResponse} writes the response.
 *
 * <p>It is unrelated to {@code edu.harvard.dbmi.avillach.util.exception.PicsureException} in pic-sure-commons, which is a different type
 * with a different body. This service does not depend on commons and maps this exception itself through {@link GlobalExceptionHandler}.</p>
 */
public class PicSureResponseException extends RuntimeException {

    private final HttpStatus status;
    private final String content;

    /**
     * Creates the exception for one error response.
     *
     * @param status the response status
     * @param message the {@code message} member of the error body
     * @param content the {@code content} member of the error body, or {@code null} when the message says everything
     */
    public PicSureResponseException(HttpStatus status, String message, String content) {
        super(message);
        this.status = Objects.requireNonNull(status, "status");
        this.content = content;
    }

    /**
     * Returns the status the response carries.
     *
     * @return the response status
     */
    public HttpStatus getStatus() {
        return status;
    }

    /**
     * Returns the detail written as the {@code content} member of the error body.
     *
     * @return the detail, or {@code null}
     */
    public String getContent() {
        return content;
    }
}
