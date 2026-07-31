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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The explicit {@code /hpds/**} route forwards VERBATIM (no prefix strip) to the DB-free query-service — the query-service itself selects
 * auth vs. open (and v3 vs. legacy) from the path, so the gateway must not rewrite it. Proves the higher-priority route (order 100) matches
 * (no catch-all fallback exists), and that the backend sees the exact inbound path. {@code /hpds} is not allow-listed, so under the
 * always-on auth/audit chain the request needs a valid bearer plus an active PSAMA introspection stub.
 *
 * <p>{@code /query/{id}/status} is one of the bodyless stored-query reads, so the chain also fetches the query dispatch from
 * operations-service before introspecting (see {@code QueryAuthFetcher}); that stub is part of the fixture here, not the subject.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HpdsRouteTest {

    static WireMockServer hpdsStub;
    static WireMockServer psamaStub;
    static WireMockServer operationsStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        hpdsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        hpdsStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", hpdsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");

        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        hpdsStub.stop();
        psamaStub.stop();
        operationsStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        hpdsStub.resetAll();
        hpdsStub.stubFor(get(urlEqualTo("/hpds/auth/v3/query/abc-123/status")).willReturn(aResponse().withStatus(200).withBody("hpds-ok")));

        operationsStub.resetAll();
        operationsStub.stubFor(
            get(urlEqualTo("/operations/internal/queries/abc-123/dispatch"))
                .willReturn(okJson("{\"queryJson\":\"{\\\"expectedResultType\\\":\\\"COUNT\\\"}\"}"))
        );

        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );
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
    void forwardsHpdsPathToQueryServiceVerbatim() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        ResponseEntity<String> response =
            rest.exchange(url("/hpds/auth/v3/query/abc-123/status"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("hpds-ok");
        // No prefix strip: the query-service saw the exact inbound path, /hpds/auth/v3/query/abc-123/status.
        hpdsStub.verify(getRequestedFor(urlEqualTo("/hpds/auth/v3/query/abc-123/status")));
    }
}
