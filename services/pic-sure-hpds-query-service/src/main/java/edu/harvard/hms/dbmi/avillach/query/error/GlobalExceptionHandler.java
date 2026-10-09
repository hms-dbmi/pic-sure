package edu.harvard.hms.dbmi.avillach.query.error;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureErrorBodyAdvice;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * This service's exception-to-HTTP mapping and its only advice bean. Inherits the {@code {errorType, message, requestId}} body, the
 * {@code PicsureException} mapping and the client-error and catch-all handling from {@link PicsureErrorBodyAdvice}, and adds
 * {@link HpdsCommunicationException} as a 502 because HPDS is upstream infrastructure.
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
}
