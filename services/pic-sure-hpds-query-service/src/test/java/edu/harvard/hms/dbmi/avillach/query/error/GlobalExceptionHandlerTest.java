package edu.harvard.hms.dbmi.avillach.query.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

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

    @Test
    void mapsUnreadableBodyTo400WithAFixedMessage() throws Exception {
        ResponseEntity<Object> resp = handler.handleException(
            new HttpMessageNotReadableException("JSON parse error: raw-client-payload", new MockHttpInputMessage(new byte[0])),
            new ServletWebRequest(new MockHttpServletRequest())
        );

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isInstanceOfSatisfying(
            Map.class,
            body -> assertThat(body).containsEntry("errorType", "bad_request").containsEntry("message", "Malformed request body")
                .containsKey("requestId")
        );
    }

    @Test
    void typeMismatchWithoutAPropertyNameStillReadsCleanly() throws Exception {
        ResponseEntity<Object> resp =
            handler.handleException(new TypeMismatchException("abc", Integer.class), new ServletWebRequest(new MockHttpServletRequest()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody())
            .isInstanceOfSatisfying(Map.class, body -> assertThat(body).containsEntry("message", "Invalid value for a request parameter"));
    }
}
