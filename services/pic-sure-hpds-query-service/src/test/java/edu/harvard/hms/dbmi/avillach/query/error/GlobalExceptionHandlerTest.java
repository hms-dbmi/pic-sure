package edu.harvard.hms.dbmi.avillach.query.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;
import edu.harvard.hms.dbmi.avillach.query.hpds.HpdsCommunicationException;

/**
 * Unit tests for {@link GlobalExceptionHandler}: HPDS upstream failures map to 502, {@link PicsureException} maps to its carried status,
 * and any other unmapped exception maps to 500. All three share the commons {@code {errorType,message,requestId}} body shape.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsHpdsCommunicationTo502() {
        ResponseEntity<Map<String, Object>> resp = handler.hpdsUnavailable(new HpdsCommunicationException("down"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).containsEntry("errorType", "upstream_unavailable").containsEntry("message", "down");
    }

    @Test
    void mapsPicsureExceptionToItsCarriedStatus() {
        ResponseEntity<Map<String, Object>> resp =
            handler.handlePicsureException(new PicsureException(HttpStatus.BAD_REQUEST, "bad_request", "nope"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("errorType", "bad_request").containsEntry("message", "nope");
    }

    @Test
    void mapsUnknownExceptionTo500WithCommonsShape() {
        ResponseEntity<Map<String, Object>> resp = handler.unknown(new RuntimeException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody()).containsKey("errorType").containsKey("message").containsKey("requestId");
    }
}
