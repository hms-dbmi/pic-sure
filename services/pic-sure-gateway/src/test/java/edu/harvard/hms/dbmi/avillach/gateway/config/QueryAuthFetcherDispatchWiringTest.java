package edu.harvard.hms.dbmi.avillach.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
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
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The internal query dispatch that {@code QueryAuthFetcher} fetches (for PSAMA introspection of {@code result}/{@code signed-url} paths)
 * lives on operations-service (the sole DB owner), NOT the query-service. Proves the wiring end-to-end through the real filter chain: a
 * {@code GET /query/{id}/result} request must fetch the dispatch from {@code OPERATIONS_SERVICE_URL}, then 404 (no route owns
 * {@code /query/**} — there is no catch-all to forward to). The query-service stub is wired to fail loudly (500) so an accidental dispatch
 * call there surfaces as a test failure rather than a silent pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"picsure.gateway.security.query-service-internal-token=internal-secret"})
class QueryAuthFetcherDispatchWiringTest {

    static WireMockServer operationsStub;
    static WireMockServer queryServiceStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        // Dispatch must NOT land here anymore -- if it does, this stub answers with 500 and the introspection call
        // (hence the whole request) fails.
        queryServiceStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        queryServiceStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", queryServiceStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        queryServiceStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        operationsStub.resetAll();
        operationsStub.stubFor(
            get(urlEqualTo("/operations/internal/queries/abc-123/dispatch"))
                .withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("internal-secret"))
                .willReturn(okJson("{\"queryJson\":\"{\\\"stored\\\":true}\"}"))
        );

        queryServiceStub.resetAll();

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
    void dispatchForResultPathIsFetchedFromOperationsServiceNotQueryService() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        ResponseEntity<String> response =
            rest.exchange(url("/query/abc-123/result"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // The dispatch succeeded via operations-service, so PSAMA introspection ran (active:true). No route owns
        // /query/**, so the request 404s -- there is no catch-all left to forward it to.
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        operationsStub.verify(1, getRequestedFor(urlEqualTo("/operations/internal/queries/abc-123/dispatch")));
        queryServiceStub.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * REGRESSION: the bodyless GET reads ({@code status}, {@code metadata}) need the dispatched stored query just as much as
     * {@code result}/{@code signed-url} do -- PSAMA's consent rules have nothing to evaluate without it, so every consent-governed user was
     * 401ing on status polling. Through the real filter chain, both must reach the same dispatch endpoint.
     */
    @Test
    void dispatchIsAlsoFetchedForTheBodylessStatusAndMetadataReads() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");

        rest.exchange(url("/hpds/auth/v3/query/abc-123/status"), HttpMethod.GET, new HttpEntity<>(headers), String.class);
        rest.exchange(url("/hpds/auth/v3/query/abc-123/metadata"), HttpMethod.GET, new HttpEntity<>(headers), String.class);

        operationsStub.verify(2, getRequestedFor(urlEqualTo("/operations/internal/queries/abc-123/dispatch")));
    }
}
