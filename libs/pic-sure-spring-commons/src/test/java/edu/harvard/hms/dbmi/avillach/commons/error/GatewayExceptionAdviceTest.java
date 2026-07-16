package edu.harvard.hms.dbmi.avillach.commons.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GatewayExceptionAdviceTest {

    private final GatewayExceptionAdvice advice = new GatewayExceptionAdvice();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void mapsExceptionToItsStatusWithAdditiveJsonBody() {
        MDC.put("requestId", "req-123");

        PicsureException exception = new PicsureException(HttpStatus.UNAUTHORIZED, "auth.missing_token", "No authorization header found.");
        ResponseEntity<Map<String, Object>> response = advice.handlePicsureException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("errorType", "auth.missing_token")
            .containsEntry("message", "No authorization header found.").containsEntry("requestId", "req-123");
    }

    @Test
    void requestIdIsNullWhenMdcIsEmpty() {
        PicsureException exception = new PicsureException(HttpStatus.FORBIDDEN, "auth.forbidden", "nope");
        ResponseEntity<Map<String, Object>> response = advice.handlePicsureException(exception);

        assertThat(response.getBody()).containsEntry("requestId", null);
    }
}
