package edu.harvard.hms.dbmi.avillach.query.error;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springdoc.api.OpenApiResourceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * This service's exception-to-HTTP mapping and its only advice bean. Inherits the {@code {errorType, message, requestId}} body, the
 * {@code PicsureException} mapping and the client-error and catch-all handling from {@link PicsureErrorBodyAdvice}, and adds
 * {@link HpdsCommunicationException} as a 502 because HPDS is upstream infrastructure, and an unknown OpenAPI group as a 404, which is how
 * a deployment without open access answers a request for the open access document.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends PicsureErrorBodyAdvice {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Answers a failed HPDS call with a 502 and logs its cause.
     *
     * @param e the failure
     * @return the 502 response
     */
    @ExceptionHandler(HpdsCommunicationException.class)
    public ResponseEntity<Map<String, Object>> hpdsUnavailable(HpdsCommunicationException e) {
        // The cause is a RestClientException whose message carries the HPDS status and response body. Without logging it
        // here, the 502 is undiagnosable from this service's logs.
        logger.error("HPDS call failed, returning 502", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body("upstream_unavailable", e.getMessage()));
    }

    /**
     * Answers a request for an OpenAPI group this deployment does not publish, such as the open access document when no open backend is
     * configured, with a 404.
     *
     * @param e the missing group
     * @return the 404 response
     */
    @ExceptionHandler(OpenApiResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> unknownApiDocument(OpenApiResourceNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("not_found", e.getMessage()));
    }
}
