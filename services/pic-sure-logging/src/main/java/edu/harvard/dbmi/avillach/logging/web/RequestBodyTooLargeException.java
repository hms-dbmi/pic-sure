package edu.harvard.dbmi.avillach.logging.web;

/**
 * Thrown from the counting input-stream wrapper when a chunked request body passes the cap.
 *
 * <p>Unchecked on purpose. An IOException raised during body reading is caught by Spring's AbstractMessageConverterMethodArgumentResolver
 * and rewrapped as HttpMessageNotReadableException, which maps to 400. This exception propagates to ApiExceptionHandler, which maps it to
 * 413.
 */
public class RequestBodyTooLargeException extends RuntimeException {

    public RequestBodyTooLargeException(String message) {
        super(message);
    }
}
