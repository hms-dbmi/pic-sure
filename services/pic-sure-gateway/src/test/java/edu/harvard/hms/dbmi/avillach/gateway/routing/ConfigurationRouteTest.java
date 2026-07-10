package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The single {@code /operations/**} route forwards VERBATIM (no prefix strip) to operations-service. The auth/audit chain always enforces,
 * specifically proving the public config-GET bypass ({@code PsamaIntrospectionFilter#isPublicConfigurationRead}):
 * {@code GET /operations/configuration/} and {@code GET /operations/configuration/{id}/} must reach operations-service WITHOUT a Bearer
 * token and WITHOUT ever calling PSAMA — the introspection stub is wired to fail loudly (500) so any accidental introspection call surfaces
 * as a test failure rather than a silent pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigurationRouteTest {

    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        operationsStub.resetAll();
        operationsStub
            .stubFor(get(urlEqualTo("/operations/configuration/")).willReturn(aResponse().withStatus(200).withBody("config-list-ok")));
        operationsStub.stubFor(
            get(urlEqualTo("/operations/configuration/abc-123/")).willReturn(aResponse().withStatus(200).withBody("config-read-ok"))
        );

        psamaStub.resetAll();
        // If the bypass ever regressed and introspection were called for these paths, fail loudly (500) instead of
        // silently succeeding — a passing test here must mean PSAMA was never reached.
        psamaStub.stubFor(any(anyUrl()).willReturn(serverError()));
    }

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

    @Test
    void forwardsConfigurationRootGetWithoutBearerTokenAndSkipsIntrospection() {
        ResponseEntity<String> response = rest.getForEntity(url("/operations/configuration/"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("config-list-ok");
        // No prefix strip: operations-service saw the exact inbound path, /operations/configuration/.
        operationsStub.verify(getRequestedFor(urlEqualTo("/operations/configuration/")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void forwardsConfigurationIdReadGetWithoutBearerTokenAndSkipsIntrospection() {
        ResponseEntity<String> response = rest.getForEntity(url("/operations/configuration/abc-123/"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("config-read-ok");
        operationsStub.verify(getRequestedFor(urlEqualTo("/operations/configuration/abc-123/")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }
}
