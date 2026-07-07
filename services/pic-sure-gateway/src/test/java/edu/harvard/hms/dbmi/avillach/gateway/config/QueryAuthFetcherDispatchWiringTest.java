package edu.harvard.hms.dbmi.avillach.gateway.config;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The internal query dispatch that {@code QueryAuthFetcher} fetches (for PSAMA introspection of {@code result}/{@code signed-url} paths)
 * lives on operations-service (the sole DB owner), NOT the query-service. Proves the wiring end-to-end through the real filter chain: a
 * {@code GET /query/{id}/result} request must fetch the dispatch from {@code OPERATIONS_SERVICE_URL}. The query-service stub is wired to
 * fail loudly (500) so an accidental dispatch call there surfaces as a test failure rather than a silent pass.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"picsure.gateway.security.auth-enabled=true", "picsure.gateway.security.gateway-owns-query-read-auth=true",
        "picsure.gateway.security.query-service-internal-token=internal-secret"}
)
class QueryAuthFetcherDispatchWiringTest {

    static WireMockServer operationsStub;
    static WireMockServer queryServiceStub;
    static WireMockServer psamaStub;
    static WireMockServer wildflyStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        // Dispatch must NOT land here anymore -- if it does, this stub answers with 500 and the introspection call
        // (hence the whole request) fails.
        queryServiceStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        queryServiceStub.start();
        registry.add("HPDS_QUERY_SERVICE_URL", queryServiceStub::baseUrl);

        psamaStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");

        // /query/{id}/result isn't one of the explicitly configured routes, so it falls through to the WildFly catch-all. Stub
        // it so the test exercises a clean 200 round trip rather than an unresolved-host connection failure.
        wildflyStub = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        wildflyStub.start();
        registry.add("WILDFLY_URL", wildflyStub::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        queryServiceStub.stop();
        psamaStub.stop();
        wildflyStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        operationsStub.resetAll();
        operationsStub.stubFor(
            get(urlEqualTo("/internal/queries/abc-123/dispatch")).withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("internal-secret"))
                .willReturn(okJson("{\"queryJson\":\"{\\\"stored\\\":true}\"}"))
        );

        queryServiceStub.resetAll();

        psamaStub.resetAll();
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
        );

        wildflyStub.resetAll();
        wildflyStub
            .stubFor(get(urlPathMatching("/pic-sure-api-2/PICSURE/.*")).willReturn(aResponse().withStatus(200).withBody("legacy-ok")));
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void dispatchForResultPathIsFetchedFromOperationsServiceNotQueryService() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        ResponseEntity<String> response = rest.exchange("/query/abc-123/result", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // The dispatch succeeded via operations-service, so PSAMA introspection ran (active:true) and the request
        // was forwarded all the way to the (stubbed) legacy backend.
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("legacy-ok");
        operationsStub.verify(1, getRequestedFor(urlEqualTo("/internal/queries/abc-123/dispatch")));
        queryServiceStub.verify(0, anyRequestedFor(anyUrl()));
    }
}
