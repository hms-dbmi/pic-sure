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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * End-to-end wiring check over real HTTP: {@code /system/status} composed through the real Spring context (HealthConfig bean ->
 * SystemHealthService -> SystemStatusController), against a WireMock downstream that is down, proving the legacy plain-text DEGRADED
 * contract survives the full stack, not just the unit-level fake in {@link SystemStatusControllerTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SystemStatusEndpointIT {

    static WireMockServer down;

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
        down = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        down.start();
        down.stubFor(get(urlEqualTo("/actuator/health")).willReturn(aResponse().withStatus(503)));
    }

    @AfterAll
    static void stop() {
        down.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("picsure.gateway.health.downstreams[0].name", () -> "hpds");
        r.add("picsure.gateway.health.downstreams[0].base-url", () -> "http://127.0.0.1:" + down.port());
        r.add("picsure.gateway.health.downstreams[0].require-status-up", () -> "true");
    }

    @Test
    void reportsDegradedTextOverHttpWhenADownstreamIsDown() {
        ResponseEntity<String> response = rest.getForEntity(url("/system/status"), String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull().matches(t -> t.isCompatibleWith(MediaType.TEXT_PLAIN));
        assertThat(response.getBody()).isEqualTo("ONE OR MORE COMPONENTS DEGRADED");
    }
}
