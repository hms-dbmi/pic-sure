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
 * The explicit {@code /hpds/**} route forwards VERBATIM (no prefix strip) to the DB-free query-service — the query-service itself selects
 * auth vs. open (and v3 vs. legacy) from the path, so the gateway must not rewrite it. Proves the higher-priority route (order 100) matches
 * (no catch-all fallback exists), and that the backend sees the exact inbound path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HpdsRouteTest {

    static WireMockServer hpdsStub;

    @DynamicPropertySource
    static void hpdsUrl(DynamicPropertyRegistry registry) {
        hpdsStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpdsStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", hpdsStub::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        hpdsStub.stop();
    }

    @BeforeEach
    void resetStub() {
        hpdsStub.resetAll();
        hpdsStub.stubFor(get(urlEqualTo("/hpds/auth/v3/query/abc-123/status")).willReturn(aResponse().withStatus(200).withBody("hpds-ok")));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void forwardsHpdsPathToQueryServiceVerbatim() {
        ResponseEntity<String> response = rest.getForEntity("/hpds/auth/v3/query/abc-123/status", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("hpds-ok");
        // No prefix strip: the query-service saw the exact inbound path, /hpds/auth/v3/query/abc-123/status.
        hpdsStub.verify(getRequestedFor(urlEqualTo("/hpds/auth/v3/query/abc-123/status")));
    }
}
