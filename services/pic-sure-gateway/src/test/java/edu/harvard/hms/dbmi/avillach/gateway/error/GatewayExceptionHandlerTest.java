package edu.harvard.hms.dbmi.avillach.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

import org.springframework.http.HttpMethod;

/**
 * Verifies the gateway's exception response shape and ensures the catch-all does not relabel statuses already selected by Spring MVC. A
 * broad {@code @ExceptionHandler(Exception.class)} can otherwise swallow 404 and other 4xx responses because it wins the depth comparison.
 */
class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void picsureExceptionKeepsItsCarriedStatusAndErrorType() {
        ResponseEntity<Map<String, Object>> r =
            handler.handlePicsureException(new PicsureException(HttpStatus.BAD_GATEWAY, "dispatch_failed", "nope"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(r.getBody()).containsEntry("errorType", "dispatch_failed").containsEntry("message", "nope");
    }

    @Test
    void unmappedExceptionBecomesAShaped500ThatLeaksNoInternals() {
        ResponseEntity<Map<String, Object>> r = handler.unknown(new IllegalArgumentException("URI with undefined scheme"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).containsEntry("errorType", "internal_error").containsKey("requestId");
        // The raw cause is logged, never returned.
        assertThat(r.getBody()).containsEntry("message", "An unexpected error occurred");
    }

    @Test
    void unroutedPathStays404RatherThanBeingFlattenedTo500() {
        ResponseEntity<Map<String, Object>> r = handler.noRoute(new NoResourceFoundException(HttpMethod.GET, "/nope"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).containsEntry("errorType", "not_found");
        assertThat(String.valueOf(r.getBody().get("message"))).contains("/nope");
    }

    @Test
    void deliberateStatusExceptionKeepsItsOwnStatus() {
        ResponseEntity<Map<String, Object>> r =
            handler.statusException(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "GET not supported"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(r.getBody()).containsEntry("errorType", "method_not_allowed");
        assertThat(String.valueOf(r.getBody().get("message"))).contains("GET not supported");
    }
}
