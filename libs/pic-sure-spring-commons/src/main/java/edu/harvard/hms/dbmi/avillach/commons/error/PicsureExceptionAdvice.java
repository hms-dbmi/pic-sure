package edu.harvard.hms.dbmi.avillach.commons.error;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Shared exception-to-HTTP mapping for every PIC-SURE service. Each service registers exactly one {@code @RestControllerAdvice} that
 * extends this class, directly or through {@link PicsureErrorBodyAdvice}, and implements {@link #errorBody} to render its own body shape.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps the statuses Spring MVC chooses for its own request errors: a wrong method
 * answers 405 with {@code Allow}, an unsupported media type 415 with {@code Accept}, an unreadable body, a type mismatch or a missing
 * parameter 400, an unmapped path 404. Those handlers are closer matches than {@link #handleUnexpected}, so Spring picks them first, and
 * {@link #handleExceptionInternal} renders their responses. Any other exception no subclass handler claims becomes a logged 500.
 *
 * <p>Client-visible text for these responses is fixed and lives here, in the constants below. Spring's own detail text can quote the
 * request's path, method, Content-Type or parameter value, so it is never passed through.
 *
 * <p>A subclass must not declare an {@code @ExceptionHandler} for an exception type {@link ResponseEntityExceptionHandler} already handles,
 * or Spring refuses to start with an ambiguous mapping. Override the matching protected {@code handle...} method instead and pass the body
 * it builds to {@link #handleExceptionInternal}, which returns a non-{@link ProblemDetail} body unchanged.
 */
public abstract class PicsureExceptionAdvice extends ResponseEntityExceptionHandler {

    /** Detail for a request body that is missing or cannot be parsed. */
    public static final String BODY_UNREADABLE = "The request body could not be parsed.";

    /** Detail for a 404, whether the path is unmapped or a handler reported a missing resource. */
    public static final String NOT_FOUND = "The requested resource does not exist.";

    /** Detail for a 405. */
    public static final String METHOD_NOT_ALLOWED = "This endpoint does not support the request method.";

    /** Detail for a 406. */
    public static final String NOT_ACCEPTABLE = "This endpoint cannot produce any of the accepted media types.";

    /** Detail for a 413. */
    public static final String PAYLOAD_TOO_LARGE = "The request is too large.";

    /** Detail for a 415. */
    public static final String UNSUPPORTED_MEDIA_TYPE = "This endpoint does not accept the request's content type.";

    /** Detail for any other client error. */
    public static final String CLIENT_ERROR = "The request could not be completed.";

    /** Detail for every server error this class renders. */
    public static final String SERVER_ERROR =
        "An unexpected error occurred. Please contact the system administrator with the time this error occurred.";

    private static final Logger logger = LoggerFactory.getLogger(PicsureExceptionAdvice.class);

    private static final Set<String> SECURITY_EXCEPTIONS =
        Set.of("org.springframework.security.access.AccessDeniedException", "org.springframework.security.core.AuthenticationException");

    /**
     * Detail for a required request parameter the request left out.
     *
     * @param name the parameter's name
     * @return the detail naming the parameter
     */
    public static String missingParameter(String name) {
        return "Required parameter '" + name + "' is missing.";
    }

    /**
     * Detail for a parameter whose value could not be converted. Names the parameter only, never the value the client sent.
     *
     * @param name the parameter's name
     * @return the detail naming the parameter
     */
    public static String invalidParameter(String name) {
        return "Invalid value for parameter '" + name + "'.";
    }

    /**
     * Answers any exception no closer handler claims with a logged 500 carrying {@link #SERVER_ERROR}.
     *
     * <p>A Spring Security {@code AccessDeniedException} or {@code AuthenticationException}, such as a denied {@code @PreAuthorize}, is
     * rethrown unchanged so Spring MVC leaves it unresolved and the security filter chain answers it with 401 or 403. The types are matched
     * by name because not every service has Spring Security on its classpath.
     *
     * @param ex the unhandled exception
     * @param request the current request
     * @return the 500 response, or {@code null} when the response is already committed
     * @throws Exception {@code ex} itself, when it is a Spring Security exception
     */
    @ExceptionHandler(Exception.class)
    public final ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        if (isSecurityException(ex)) {
            throw ex;
        }
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    /**
     * Renders every response the base class produces through {@link #errorBody}, keeping the status and headers it chose. A 5xx is logged
     * and carries {@link #SERVER_ERROR}; a 4xx carries the fixed detail for its exception or status. A body that is not a
     * {@link ProblemDetail}, such as one an override already built, passes through unchanged.
     *
     * @param ex the exception being handled
     * @param body the body built so far, or {@code null}
     * @param headers response headers, such as {@code Allow} on a 405 and {@code Accept} on a 415
     * @param statusCode the response status
     * @param request the current request
     * @return the response, or {@code null} when the response is already committed
     */
    @Override
    protected final ResponseEntity<Object> handleExceptionInternal(
        Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request
    ) {
        ResponseEntity<Object> framework = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (framework == null || !(framework.getBody() == null || framework.getBody() instanceof ProblemDetail)) {
            return framework;
        }
        String detail;
        if (statusCode.is5xxServerError()) {
            logger.error("Unhandled exception", ex);
            detail = SERVER_ERROR;
        } else {
            detail = clientErrorDetail(ex, statusCode);
        }
        Object rendered = errorBody(framework.getStatusCode(), reasonPhrase(statusCode), detail);
        return new ResponseEntity<>(rendered, framework.getHeaders(), framework.getStatusCode());
    }

    /**
     * Builds this service's error body.
     *
     * @param status the response status
     * @param title the status's HTTP reason phrase, such as {@code Not Found}
     * @param detail fixed text that never quotes the request, one of the constants on this class or the text of {@link #missingParameter}
     *        or {@link #invalidParameter}
     * @return the body to write
     */
    protected abstract Object errorBody(HttpStatusCode status, String title, String detail);

    private static boolean isSecurityException(Throwable ex) {
        for (Class<?> type = ex.getClass(); type != null; type = type.getSuperclass()) {
            if (SECURITY_EXCEPTIONS.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private static String reasonPhrase(HttpStatusCode statusCode) {
        HttpStatus resolved = HttpStatus.resolve(statusCode.value());
        return resolved == null ? "Error" : resolved.getReasonPhrase();
    }

    private static String clientErrorDetail(Exception ex, HttpStatusCode statusCode) {
        if (ex instanceof HttpMessageNotReadableException) {
            return BODY_UNREADABLE;
        }
        if (ex instanceof MissingServletRequestParameterException missing) {
            return missingParameter(missing.getParameterName());
        }
        if (ex instanceof TypeMismatchException mismatch && mismatch.getPropertyName() != null) {
            return invalidParameter(mismatch.getPropertyName());
        }
        return switch (statusCode.value()) {
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> CLIENT_ERROR;
        };
    }
}
