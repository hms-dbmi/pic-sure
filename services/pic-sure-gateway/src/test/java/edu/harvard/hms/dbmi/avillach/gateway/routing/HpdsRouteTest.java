package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
 * The explicit {@code /hpds/**} route forwards requests verbatim to the DB-free query service. The query service selects the backend and
 * API version from the path, so the gateway must not rewrite it. Proves the higher-priority route (order 100) matches (no catch-all
 * fallback exists), and that the backend sees the exact inbound path. {@code /hpds} is not allow-listed, so under the always-on auth/audit
 * chain the request needs a valid bearer plus an active PSAMA introspection stub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HpdsRouteTest {

    static WireMockServer hpdsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        hpdsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        hpdsStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", hpdsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        hpdsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        hpdsStub.resetAll();
        hpdsStub.stubFor(get(urlEqualTo("/hpds/auth/v3/query/abc-123/status")).willReturn(aResponse().withStatus(200).withBody("hpds-ok")));

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

    @ParameterizedTest
    @CsvSource(
        {"/hpds/%61uth/v3/query/abc-123/status, /hpds/auth/v3/query/abc-123/status",
            "/hpds/a%75th/v3/query/abc-123/status, /hpds/auth/v3/query/abc-123/status",
            "/hpds/%61%75%74%68/v3/query/abc-123/status, /hpds/auth/v3/query/abc-123/status"}
    )
    void introspectionUsesTheDecodedNormalizedRoutePath(String requestPath, String resolvedPath) {
        hpdsStub.stubFor(get(urlMatching("/hpds/.*")).willReturn(aResponse().withStatus(200).withBody("hpds-ok")));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");

        ResponseEntity<String> response =
            rest.exchange(URI.create(url(requestPath)), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        psamaStub.verify(
            postRequestedFor(urlEqualTo("/auth/token/inspect"))
                .withRequestBody(matchingJsonPath("$.request['Target Service']", equalTo(resolvedPath)))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"/hpds//auth///v3/query/abc-123/status", "/hpds%2Fauth/v3/query/abc-123/status"})
    void rejectedSlashVariantDoesNotReachIntrospection(String requestPath) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");

        ResponseEntity<String> response =
            rest.exchange(URI.create(url(requestPath)), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        psamaStub.verify(0, postRequestedFor(urlEqualTo("/auth/token/inspect")));
    }
}
