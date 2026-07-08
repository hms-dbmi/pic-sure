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
 * {@link ActuatorHealthIT} does) and asserts the JSON composite shows the "hpds" component DOWN. {@code show-details} is forced to
 * {@code always} for this test context only -- production defaults to {@code when_authorized} (Phase 6 gates detail by app token; see
 * application.yml).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
// Actuator is OFF BY DEFAULT (exposure defaults to 'none'); expose health and force show-details=always for this context.
@TestPropertySource(properties = {"management.endpoints.web.exposure.include=health", "management.endpoint.health.show-details=always"})
class ActuatorHealthEndpointIT {

    static WireMockServer hpds;

    @Autowired
    private TestRestTemplate rest;

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
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
        r.add("picsure.gateway.health.downstreams[0].base-url", () -> "http://localhost:" + hpds.port());
        r.add("picsure.gateway.health.downstreams[0].require-status-up", () -> "true");
    }

    @Test
    void actuatorHealthShowsDownDownstreamAsDown() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

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
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonNode body = new ObjectMapper().readTree(response.getBody());
        assertThat(body.path("status").asText()).isEqualTo("UP");
    }
}
