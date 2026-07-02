package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Proves the transparent catch-all: any inbound path is forwarded to ${WILDFLY_URL} re-prefixed with /pic-sure-api-2/PICSURE/, and the
 * upstream response flows back unchanged. Also implicitly verifies the SC 2025.0.x property prefix
 * (spring.cloud.gateway.server.webmvc.routes) actually binds — if it did not, no route would match and this test would 404. <p> The DB-free
 * auth filter chain (Task 12) now runs in front of every request. This test exercises the catch-all's forwarding behavior in isolation from
 * PSAMA auth: {@code /query} is allow-listed here so {@code PsamaIntrospectionFilter} passes the no-bearer request straight through without
 * calling PSAMA, leaving the route-forwarding assertion below exactly as it was before the auth chain existed. Production config carries no
 * such allow-list.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "picsure.gateway.security.allow-list-prefixes[0]=/query")
class CatchAllRouteTest {

    static WireMockServer wildflyStub;

    @DynamicPropertySource
    static void wildflyUrl(DynamicPropertyRegistry registry) {
        wildflyStub = new WireMockServer(options().dynamicPort());
        wildflyStub.start();
        registry.add("WILDFLY_URL", wildflyStub::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        wildflyStub.stop();
    }

    @BeforeEach
    void stubLegacy() {
        wildflyStub.resetAll();
        wildflyStub
            .stubFor(get(urlPathMatching("/pic-sure-api-2/PICSURE/.*")).willReturn(aResponse().withStatus(200).withBody("legacy-ok")));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void forwardsAnyPathToWildflyWithLegacyPrefix() {
        ResponseEntity<String> response = rest.getForEntity("/query/abc123/status", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("legacy-ok");
        wildflyStub.verify(getRequestedFor(urlEqualTo("/pic-sure-api-2/PICSURE/query/abc123/status")));
    }
}
