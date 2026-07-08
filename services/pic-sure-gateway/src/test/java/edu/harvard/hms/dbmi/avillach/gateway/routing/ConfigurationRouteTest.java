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
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The explicit {@code /configuration/**} route forwards VERBATIM (no prefix strip) to operations-service. The auth/audit chain always
 * enforces, specifically proving the public config-GET bypass ({@code PsamaIntrospectionFilter#isPublicConfigurationRead}):
 * {@code GET /configuration/} and {@code GET /configuration/{id}/} must reach operations-service WITHOUT a Bearer token and WITHOUT ever
 * calling PSAMA — the introspection stub is wired to fail loudly (500) so any accidental introspection call surfaces as a test failure
 * rather than a silent pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigurationRouteTest {

    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
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
        operationsStub.stubFor(get(urlEqualTo("/configuration/")).willReturn(aResponse().withStatus(200).withBody("config-list-ok")));
        operationsStub
            .stubFor(get(urlEqualTo("/configuration/abc-123/")).willReturn(aResponse().withStatus(200).withBody("config-read-ok")));

        psamaStub.resetAll();
        // If the bypass ever regressed and introspection were called for these paths, fail loudly (500) instead of
        // silently succeeding — a passing test here must mean PSAMA was never reached.
        psamaStub.stubFor(any(anyUrl()).willReturn(serverError()));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void forwardsConfigurationRootGetWithoutBearerTokenAndSkipsIntrospection() {
        ResponseEntity<String> response = rest.getForEntity("/configuration/", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("config-list-ok");
        // No prefix strip: operations-service saw the exact inbound path, /configuration/.
        operationsStub.verify(getRequestedFor(urlEqualTo("/configuration/")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void forwardsConfigurationIdReadGetWithoutBearerTokenAndSkipsIntrospection() {
        ResponseEntity<String> response = rest.getForEntity("/configuration/abc-123/", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("config-read-ok");
        operationsStub.verify(getRequestedFor(urlEqualTo("/configuration/abc-123/")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }
}
