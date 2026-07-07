package edu.harvard.hms.dbmi.avillach.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Locks in the security posture the whole actuator change rests on: with NO {@code PICSURE_ACTUATOR_EXPOSURE} set,
 * {@code management.endpoints.web.exposure.include} resolves to empty (see application.yml), so every {@code /actuator/**} endpoint is
 * unmapped (404) -- nothing is exposed to the world by default. The public {@code /system/status} basic health check is a
 * {@code RouterFunction}, not an actuator endpoint, so it stays available regardless. AIO opts back in via gateway.env; the
 * enabled-and-gated behavior is proven by {@link ActuatorSecurityIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class ActuatorDisabledByDefaultTest {

    @LocalServerPort
    int port;
    private final RestTemplate rest = new RestTemplate();

    @BeforeEach
    void noThrow() {
        rest.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse r) {
                return false;
            }

            @Override
            public void handleError(ClientHttpResponse r) {}
        });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void actuatorEndpointsAreUnmappedByDefault() {
        assertThat(rest.getForEntity(url("/actuator/health"), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(url("/actuator/health/liveness"), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(url("/actuator/info"), String.class).getStatusCode().value()).isEqualTo(404);
        assertThat(rest.getForEntity(url("/actuator/prometheus"), String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void systemStatusBasicHealthCheckStaysAvailable() {
        ResponseEntity<String> r = rest.getForEntity(url("/system/status"), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).isEqualTo("RUNNING");
    }
}
