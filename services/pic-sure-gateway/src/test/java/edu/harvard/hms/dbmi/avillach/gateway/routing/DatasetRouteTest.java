package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The explicit {@code /dataset/**} route forwards VERBATIM (no prefix strip) to operations-service, the sole DB owner. Proves the
 * higher-priority route (order 100) matches; there is no catch-all fallback. {@code /dataset} is not allow-listed, so under the always-on
 * auth/audit chain the request needs a valid bearer plus an active PSAMA introspection stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasetRouteTest {

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
        operationsStub.stubFor(get(urlEqualTo("/dataset/abc-123")).willReturn(aResponse().withStatus(200).withBody("dataset-ok")));

        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void forwardsDatasetPathToOperationsServiceVerbatim() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        ResponseEntity<String> response = rest.exchange("/dataset/abc-123", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("dataset-ok");
        // No prefix strip: operations-service saw the exact inbound path, /dataset/abc-123.
        operationsStub.verify(getRequestedFor(urlEqualTo("/dataset/abc-123")));
    }
}
