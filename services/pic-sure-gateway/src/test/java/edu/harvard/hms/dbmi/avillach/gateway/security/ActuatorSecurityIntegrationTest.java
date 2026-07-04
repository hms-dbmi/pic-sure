package edu.harvard.hms.dbmi.avillach.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Task 7: full-context proof that the Phase-6 actuator chain (order 0) and the gateway's Phase-2 permit-all main chain (order 10) coexist
 * end-to-end -- health stays open (never 401) and shallow without a token, component detail and {@code /actuator/prometheus} are gated by
 * {@code X-Application-Token}. Deep health indicators may report UP or DOWN in this isolated context (no live PSAMA/downstreams are
 * configured on the {@code local} profile) -- what matters is that the status code is never 401 and detail visibility tracks the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability // prometheus export is disabled by default in @SpringBootTest contexts
@ActiveProfiles("local")
@TestPropertySource(properties = {"picsure.actuator.require-token=true", "picsure.actuator.token=secret-xyz"})
class ActuatorSecurityIntegrationTest {

    @LocalServerPort
    int port;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper json = new ObjectMapper();

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
    void healthIsOpenAndShallowWithoutToken() throws Exception {
        ResponseEntity<String> r = rest.getForEntity(url("/actuator/health"), String.class);
        assertThat(r.getStatusCode().value()).isNotEqualTo(401); // open to the load balancer
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.has("status")).isTrue();
        assertThat(body.has("components")).isFalse(); // no detail leaks to anonymous callers
    }

    @Test
    void livenessIsOpenWithoutToken() {
        ResponseEntity<String> r = rest.getForEntity(url("/actuator/health/liveness"), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void healthRevealsDetailWithToken() throws Exception {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Application-Token", "secret-xyz");
        ResponseEntity<String> r = rest.exchange(url("/actuator/health"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        JsonNode body = json.readTree(r.getBody());
        assertThat(body.has("components")).isTrue(); // DETAIL revealed by valid token
    }

    @Test
    void prometheusRejectedWithoutToken() {
        ResponseEntity<String> r = rest.getForEntity(url("/actuator/prometheus"), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void prometheusOkWithToken() {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Application-Token", "secret-xyz");
        ResponseEntity<String> r = rest.exchange(url("/actuator/prometheus"), HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
    }

    /**
     * Coexistence: a non-actuator, non-gated endpoint must remain reachable -- the actuator securityMatcher scopes to /actuator/** only.
     */
    @Test
    void systemStatusRemainsReachableWithoutToken() {
        ResponseEntity<String> r = rest.getForEntity(url("/system/status"), String.class);
        assertThat(r.getStatusCode().value()).isNotEqualTo(401);
    }
}
