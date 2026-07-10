package edu.harvard.hms.dbmi.avillach.gateway.health;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * End-to-end: hits the real {@code GET /actuator/health} HTTP endpoint (not just the {@link DownstreamHealthContributor} bean directly, as
 * {@link ActuatorHealthIT} does) and asserts the JSON composite shows the "hpds" component DOWN. Actuator is OFF BY DEFAULT (empty exposure
 * in application.yml), so this test context explicitly exposes {@code health} and forces {@code show-details} to {@code always} --
 * production exposes/gates via PICSURE_ACTUATOR_EXPOSURE / PICSURE_ACTUATOR_DETAILS (see application.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"management.endpoints.web.exposure.include=health", "management.endpoint.health.show-details=always"})
class ActuatorHealthEndpointIT {

    static WireMockServer hpds;

    @Autowired
    private TestRestTemplate rest;


    @LocalServerPort
    int port;

    /**
     * Dial the loopback ADDRESS, never the name. {@code localhost} resolves to {@code ::1} before {@code 127.0.0.1} on macOS, while these
     * test servers bind the IPv4 wildcard -- so an unrelated local process holding the same port number on {@code [::]} can answer instead,
     * and the test fails with a bewildering status from a server it never meant to contact. Relative {@code TestRestTemplate} URLs go
     * through {@code LocalHostUriTemplateHandler}, which hardcodes the name {@code localhost}; absolute URLs bypass it entirely.
     */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        hpds.start();
        hpds.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(503)));
    }

    @AfterAll
    static void stop() {
        hpds.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("picsure.gateway.health.downstreams[0].name", () -> "hpds");
        r.add("picsure.gateway.health.downstreams[0].base-url", () -> "http://127.0.0.1:" + hpds.port());
        r.add("picsure.gateway.health.downstreams[0].require-status-up", () -> "true");
    }

    @Test
    void actuatorHealthShowsDownDownstreamAsDown() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/health"), String.class);

        JsonNode body = new ObjectMapper().readTree(response.getBody());
        JsonNode hpdsComponent = body.path("components").path("downstreams").path("components").path("hpds");

        assertThat(hpdsComponent.path("status").asText()).isEqualTo("DOWN");
        assertThat(body.path("status").asText()).isEqualTo("DOWN");
    }

    /**
     * Pins the operational invariant this class exists to guard: {@code downstreams} is a top-level composite contributor, so a single DOWN
     * sibling flips the ROOT {@code /actuator/health} to DOWN (asserted above) -- but {@code /actuator/health/liveness} is pinned via
     * {@code management.endpoint.health.group.liveness.include: livenessState} (application.yml) and must stay UP regardless. Deploy
     * smoke-polls and container healthchecks must probe this shallow endpoint, not the aggregate root, or a degraded/restarting downstream
     * (e.g. HPDS) would fail the gateway's own healthcheck and could kill the container.
     */
    @Test
    void livenessStaysUpWhenDownstreamIsDown() throws Exception {
        ResponseEntity<String> response = rest.getForEntity(url("/actuator/health/liveness"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }
}
