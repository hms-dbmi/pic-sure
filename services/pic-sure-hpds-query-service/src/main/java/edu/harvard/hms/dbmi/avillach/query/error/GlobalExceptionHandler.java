package edu.harvard.hms.dbmi.avillach.query.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * This service's own exception-to-HTTP mapping. {@code pic-sure-spring-commons}' {@code GatewayExceptionAdvice} already maps
 * {@link PicsureException} to its carried status with the {@code {errorType,message,requestId}} body shape -- that handler is duplicated
 * here (identical behavior) rather than relied upon exclusively, because Spring's {@code ExceptionHandlerExceptionResolver} picks the FIRST
 * {@code @ControllerAdvice} bean (in an unspecified-by-us order) that has ANY matching handler for a given exception, not the most-specific
 * match across all beans. Keeping a self-contained {@link PicsureException} handler in this same class guarantees this advice always
 * resolves the most specific handler for its own {@link #unknown} catch-all, regardless of whichever advice bean Spring happens to consult
 * first -- {@code GatewayExceptionAdvice}'s equivalent handler (if consulted first) produces the identical response.
 *
 * <p>Adds three mappings the commons base does not have: {@link HpdsCommunicationException} -&gt; 502 (HPDS is upstream infrastructure; the
 * legacy WAR returned 500 for this case, which was never an honest status), {@link NoResourceFoundException} -&gt; 404 (route absence for
 * removed v1 generic ingress), and any other unmapped exception -&gt; 500, all sharing the same commons error body shape.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PicsureException.class)
    public ResponseEntity<Map<String, Object>> handlePicsureException(PicsureException e) {
        return body(e.getStatus(), e.getErrorType(), e.getMessage());
    }

    @ExceptionHandler(HpdsCommunicationException.class)
    public ResponseEntity<Map<String, Object>> hpdsUnavailable(HpdsCommunicationException e) {
        // The cause is a RestClientException whose message carries the HPDS status and response body (e.g. "403 ...
        // Resource is locked") -- without logging it here, the 502 is undiagnosable from this service's logs.
        logger.error("HPDS call failed, returning 502", e);
        return body(HttpStatus.BAD_GATEWAY, "upstream_unavailable", e.getMessage());
    }

    /**
     * Route absence must surface as an honest 404, not fall into the {@link #unknown} 500 catch-all. This matters since the legacy v1 query
     * ingress ({@code /hpds/{backend}/query/**}) was removed: callers still submitting to those routes must see "gone", not "server error".
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noRoute(NoResourceFoundException e) {
        return body(HttpStatus.NOT_FOUND, "not_found", "No such resource: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unknown(Exception e) {
        logger.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String errorType, String message) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("errorType", errorType);
        b.put("message", message);
        b.put("requestId", MDC.get("requestId"));
        return ResponseEntity.status(status).body(b);
    }
}
