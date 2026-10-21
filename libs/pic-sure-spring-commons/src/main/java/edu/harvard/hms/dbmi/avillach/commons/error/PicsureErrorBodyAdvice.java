package edu.harvard.hms.dbmi.avillach.commons.error;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The {@link PicsureExceptionAdvice} that answers with the {@code {errorType, message, requestId}} body, where {@code requestId} comes from
 * {@code MDC[requestId]} (set by {@link edu.harvard.hms.dbmi.avillach.commons.request.RequestIdFilter}). Maps {@link PicsureException} to
 * its carried status and error type.
 *
 * <p>A service either imports this class as its advice or extends it with its own {@code @ExceptionHandler}s, never both, so the service
 * registers exactly one advice bean.
 */
@RestControllerAdvice
public class PicsureErrorBodyAdvice extends PicsureExceptionAdvice {

    /**
     * Answers a {@link PicsureException} with its carried status, error type and message.
     *
     * @param exception the exception
     * @return the response
     */
    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException exception) {
        return ResponseEntity.status(exception.getStatus()).body(body(exception.getErrorType(), exception.getMessage()));
    }

    @Override
    protected Object errorBody(HttpStatusCode status, String title, String detail) {
        return body(errorType(status), detail);
    }

    /**
     * Error type for a status: {@code internal_error} for 500, otherwise the lowercased status name ({@code NOT_FOUND} becomes
     * {@code not_found}), or {@code error} for a non-standard code.
     *
     * @param status the response status
     * @return the error type
     */
    protected static String errorType(HttpStatusCode status) {
        if (status.value() == HttpStatus.INTERNAL_SERVER_ERROR.value()) {
            return "internal_error";
        }
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "error" : resolved.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Builds the {@code {errorType, message, requestId}} body, reading {@code requestId} from the MDC.
     *
     * @param errorType the machine-readable error type
     * @param message the client-visible message
     * @return the body
     */
    protected static Map<String, Object> body(String errorType, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorType", errorType);
        body.put("message", message);
        body.put("requestId", MDC.get("requestId"));
        return body;
    }
}
