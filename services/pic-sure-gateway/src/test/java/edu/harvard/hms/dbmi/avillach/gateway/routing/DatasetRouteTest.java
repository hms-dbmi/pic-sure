package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
 * The explicit {@code /dataset/**} route forwards VERBATIM (no prefix strip) to operations-service, the sole DB owner. Proves the
 * higher-priority route (order 100) matches; there is no catch-all fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatasetRouteTest {

    static WireMockServer operationsStub;

    @DynamicPropertySource
    static void operationsUrl(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        operationsStub.stop();
    }

    @BeforeEach
    void resetStub() {
        operationsStub.resetAll();
        operationsStub.stubFor(get(urlEqualTo("/dataset/abc-123")).willReturn(aResponse().withStatus(200).withBody("dataset-ok")));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void forwardsDatasetPathToOperationsServiceVerbatim() {
        ResponseEntity<String> response = rest.getForEntity("/dataset/abc-123", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("dataset-ok");
        // No prefix strip: operations-service saw the exact inbound path, /dataset/abc-123.
        operationsStub.verify(getRequestedFor(urlEqualTo("/dataset/abc-123")));
    }
}
