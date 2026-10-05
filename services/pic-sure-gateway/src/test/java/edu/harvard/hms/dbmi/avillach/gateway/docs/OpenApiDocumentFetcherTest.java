package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;

/** The fetcher returns the upstream JSON object or throws; it never sends credentials and never waits past the read timeout. */
class OpenApiDocumentFetcherTest {

    static WireMockServer upstream;
    static OpenApiDocumentFetcher fetcher;
    static DocumentedService service;

    @BeforeAll
    static void start() {
        upstream = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        upstream.start();
        fetcher = OpenApiDocumentFetcher.withTimeouts(500, 500, new ObjectMapper());
        service = new DocumentedService("demo", "Demo", upstream.baseUrl(), "/demo/v3/api-docs", "/picsure/demo");
    }

    @AfterAll
    static void stop() {
        upstream.stop();
    }

    @BeforeEach
    void reset() {
        upstream.resetAll();
    }

    @Test
    void returnsTheUpstreamObjectWithoutCredentials() {
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson("{\"openapi\":\"3.0.1\",\"paths\":{}}")));
        ObjectNode document = fetcher.fetch(service);
        assertThat(document.get("openapi").asText()).isEqualTo("3.0.1");
        upstream.verify(getRequestedFor(urlEqualTo("/demo/v3/api-docs")).withoutHeader("Authorization"));
    }

    @Test
    void non2xxIsUpstreamUnavailable() {
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> fetcher.fetch(service)).isInstanceOf(UpstreamUnavailable.class)
            .satisfies(ex -> assertThat(((UpstreamUnavailable) ex).service()).isEqualTo("demo"));
    }

    @Test
    void readTimeoutIsUpstreamUnavailable() {
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson("{}").withFixedDelay(2000)));
        assertThatThrownBy(() -> fetcher.fetch(service)).isInstanceOf(UpstreamUnavailable.class);
    }

    @Test
    void connectionFailureIsUpstreamUnavailable() {
        DocumentedService dead = new DocumentedService("dead", "Dead", "http://127.0.0.1:1", null, "/picsure/dead");
        assertThatThrownBy(() -> fetcher.fetch(dead)).isInstanceOf(UpstreamUnavailable.class);
    }

    @Test
    void nonObjectBodyIsUpstreamUnavailable() {
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(okJson("[]")));
        assertThatThrownBy(() -> fetcher.fetch(service)).isInstanceOf(UpstreamUnavailable.class);
        upstream.stubFor(get(urlEqualTo("/demo/v3/api-docs")).willReturn(aResponse().withStatus(200).withBody("not json")));
        assertThatThrownBy(() -> fetcher.fetch(service)).isInstanceOf(UpstreamUnavailable.class);
    }

    @Test
    void uriTemplateCharactersInTheDocsPathDoNotThrowIllegalArgumentException() {
        DocumentedService templated = new DocumentedService("demo", "Demo", upstream.baseUrl(), "/v3/api-docs/{x}", "/picsure/demo");
        assertThatThrownBy(() -> fetcher.fetch(templated)).isInstanceOf(UpstreamUnavailable.class);
    }
}
