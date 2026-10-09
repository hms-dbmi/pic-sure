package edu.harvard.hms.dbmi.avillach.gateway.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.commons.error.PicsureExceptionAdvice;

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
    void unmappedExceptionBecomesAShaped500ThatLeaksNoInternals() throws Exception {
        ResponseEntity<Object> r = handler.handleUnexpected(new IllegalArgumentException("URI with undefined scheme"), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(r.getBody()).isInstanceOfSatisfying(
            Map.class,
            body -> assertThat(body).containsEntry("errorType", "internal_error").containsKey("requestId")
                .containsEntry("message", PicsureExceptionAdvice.SERVER_ERROR)
        );
    }

    @Test
    void unroutedPathStays404RatherThanBeingFlattenedTo500() throws Exception {
        ResponseEntity<Object> r = handler.handleException(new NoResourceFoundException(HttpMethod.GET, "/nope"), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isInstanceOfSatisfying(Map.class, body -> {
            assertThat(body).containsEntry("errorType", "not_found").containsEntry("message", PicsureExceptionAdvice.NOT_FOUND);
            assertThat(String.valueOf(body.get("message"))).doesNotContain("/nope");
        });
    }

    @Test
    void deliberateStatusExceptionKeepsItsOwnStatusAndReason() {
        ResponseEntity<Object> r =
            handler.statusException(new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "GET not supported"), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(r.getBody()).isInstanceOfSatisfying(Map.class, body -> {
            assertThat(body).containsEntry("errorType", "method_not_allowed");
            assertThat(String.valueOf(body.get("message"))).contains("GET not supported");
        });
    }

    @Test
    void statusExceptionWithoutAReasonCarriesTheFixedTextForItsStatus() {
        ResponseEntity<Object> r = handler.statusException(new ResponseStatusException(HttpStatus.NOT_FOUND), request());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isInstanceOfSatisfying(
            Map.class,
            body -> assertThat(body).containsEntry("errorType", "not_found").containsEntry("message", PicsureExceptionAdvice.NOT_FOUND)
        );
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest());
    }
}
