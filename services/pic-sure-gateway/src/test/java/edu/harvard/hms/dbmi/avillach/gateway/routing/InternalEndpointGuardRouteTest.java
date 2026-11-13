package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Sends real HTTP requests through a booted gateway whose dictionary and operations routes point at stubs that answer 200 to anything. A
 * request for an internal path must be answered 404 by the gateway and never reach a stub, however its letters are percent-encoded, while a
 * public path on the same route still goes through. A client-supplied {@code X-PIC-SURE-INTERNAL-TOKEN} header must not reach a stub
 * either. Every request carries a bearer that the PSAMA stub reports as active, so authentication is not what stops it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalEndpointGuardRouteTest {

    private static final String INTERNAL_TOKEN_HEADER = "X-PIC-SURE-INTERNAL-TOKEN";

    static WireMockServer dictionaryStub;
    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        dictionaryStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        dictionaryStub.start();
        registry.add("DICTIONARY_URL", dictionaryStub::baseUrl);

        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
    }

    @AfterAll
    static void stopStubs() {
        dictionaryStub.stop();
        operationsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        dictionaryStub.resetAll();
        dictionaryStub.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("dictionary-reached")));
        operationsStub.resetAll();
        operationsStub.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("operations-reached")));
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

    private ResponseEntity<String> postThroughGateway(String rawPath) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        headers.set(INTERNAL_TOKEN_HEADER, "stolen-internal-token");
        headers.setContentType(MediaType.APPLICATION_JSON);
        URI uri = URI.create("http://127.0.0.1:" + port + rawPath);
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);
    }

    @ParameterizedTest(name = "{0} is a 404 that never reaches the dictionary")
    @ValueSource(
        strings = {"/dictionary/internal", "/dictionary/internal/concepts/export-metadata",
            "/dictionary/%69nternal/concepts/export-metadata", "/dictionary/interna%6C/concepts/export-metadata",
            "/dictionary/interna%6c/concepts/export-metadata", "/%64ictionary/internal/concepts/export-metadata",
            "/dictionary/%69%6E%74%65%72%6e%61%6c/concepts/export-metadata"}
    )
    void internalDictionaryPathsNeverReachTheDictionary(String rawPath) {
        ResponseEntity<String> response = postThroughGateway(rawPath);

        assertThat(response.getStatusCode().value()).as(rawPath).isEqualTo(404);
        assertThat(response.getBody()).as(rawPath).doesNotContain("dictionary-reached");
        dictionaryStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest(name = "{0} is a 404 that never reaches the operations service")
    @ValueSource(
        strings = {"/operations/internal", "/operations/internal/queries/abc/dispatch", "/operations/%69nternal/queries/abc/dispatch",
            "/operations/interna%6C/queries/abc/dispatch", "/%6Fperations/internal/queries/abc/dispatch",
            "/%6fperations/internal/queries/abc/dispatch"}
    )
    void internalOperationsPathsNeverReachTheOperationsService(String rawPath) {
        ResponseEntity<String> response = postThroughGateway(rawPath);

        assertThat(response.getStatusCode().value()).as(rawPath).isEqualTo(404);
        assertThat(response.getBody()).as(rawPath).doesNotContain("operations-reached");
        operationsStub.verify(0, anyRequestedFor(anyUrl()));
    }

    /**
     * A double-encoded letter, a matrix parameter and a dot segment are refused by the request firewall before the guard runs, so the
     * status is the firewall's 400 or the guard's 404 depending on which answers first. Either way nothing is forwarded.
     *
     * @param rawPath the request path exactly as sent
     */
    @ParameterizedTest(name = "{0} is refused and never forwarded")
    @ValueSource(
        strings = {"/dictionary/%2569nternal/concepts/export-metadata", "/dictionary/internal;x/concepts/export-metadata",
            "/dictionary/%49nternal/../internal/concepts/export-metadata", "/operations/%2569nternal/queries/abc/dispatch",
            "/operations/internal;x/queries/abc/dispatch"}
    )
    void formsTheFirewallRefusesAreNeverForwarded(String rawPath) {
        ResponseEntity<String> response = postThroughGateway(rawPath);

        assertThat(response.getStatusCode().value()).as(rawPath).isIn(400, 404);
        dictionaryStub.verify(0, anyRequestedFor(anyUrl()));
        operationsStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @ParameterizedTest(name = "{0} is forwarded to the dictionary")
    @ValueSource(strings = {"/dictionary/internalized", "/dictionary/concepts/detail", "/dictionary/concepts/internal"})
    void publicDictionaryPathsAreForwarded(String rawPath) {
        ResponseEntity<String> response = postThroughGateway(rawPath);

        assertThat(response.getStatusCode().value()).as(rawPath).isEqualTo(200);
        assertThat(response.getBody()).as(rawPath).isEqualTo("dictionary-reached");
        dictionaryStub.verify(1, postRequestedFor(urlEqualTo(rawPath.substring("/dictionary".length()))));
    }

    @Test
    void aClientSuppliedInternalTokenHeaderNeverReachesADownstreamRoute() {
        ResponseEntity<String> response = postThroughGateway("/dictionary/concepts/detail");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        dictionaryStub.verify(1, postRequestedFor(urlEqualTo("/concepts/detail")).withoutHeader(INTERNAL_TOKEN_HEADER));
    }
}
