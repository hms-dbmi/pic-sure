package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.forbidden;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.commons.error.PicsureException;

class QueryAuthFetcherTest {

    static WireMockServer qs;

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug that manifests as
        // "RST_STREAM: Stream cancelled" when RestClient's default JDK-backed request factory is used.
        qs = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        qs.start();
    }

    @AfterAll
    static void stop() {
        qs.stop();
    }

    private QueryAuthFetcher fetcher() {
        return new QueryAuthFetcher(RestClient.builder().build(), "http://127.0.0.1:" + qs.port(), "internal-secret");
    }

    @Test
    void fetchesStoredQueryForResultPathAndSendsInternalToken() {
        qs.stubFor(
            get(urlEqualTo("/operations/internal/queries/abc-123/dispatch")).willReturn(okJson("{\"queryJson\":\"{\\\"stored\\\":true}\"}"))
        );
        assertThat(fetcher().queryJsonForPath("/query/abc-123/result")).contains("{\"stored\":true}");
        qs.verify(
            getRequestedFor(urlEqualTo("/operations/internal/queries/abc-123/dispatch"))
                .withHeader("X-PIC-SURE-INTERNAL-TOKEN", equalTo("internal-secret"))
        );
    }

    @Test
    void failsClosedOn403AsForbiddenDeny() {
        qs.stubFor(get(urlEqualTo("/operations/internal/queries/forbidden/dispatch")).willReturn(forbidden()));
        assertThatThrownBy(() -> fetcher().queryJsonForPath("/query/forbidden/result")).isInstanceOf(PicsureException.class)
            .extracting(e -> ((PicsureException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void fetchesStoredQueryForV3SignedUrlPath() {
        qs.stubFor(get(urlEqualTo("/operations/internal/queries/q9/dispatch")).willReturn(okJson("{\"queryJson\":\"{\\\"v3\\\":1}\"}")));
        assertThat(fetcher().queryJsonForPath("/v3/query/q9/signed-url")).contains("{\"v3\":1}");
    }

    @Test
    void returnsEmptyForNonStoredQueryPaths() {
        assertThat(fetcher().queryJsonForPath("/query")).isEmpty();
        assertThat(fetcher().queryJsonForPath("/query/abc/status")).isEmpty();
    }

    @Test
    void failsClosedOn404AsNotFound() {
        qs.stubFor(get(urlEqualTo("/operations/internal/queries/missing/dispatch")).willReturn(notFound()));
        assertThatThrownBy(() -> fetcher().queryJsonForPath("/query/missing/result")).isInstanceOf(PicsureException.class)
            .extracting(e -> ((PicsureException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void failsClosedOnUpstream500AsBadGateway() {
        qs.stubFor(get(urlEqualTo("/operations/internal/queries/boom/dispatch")).willReturn(serverError()));
        assertThatThrownBy(() -> fetcher().queryJsonForPath("/query/boom/result")).isInstanceOf(PicsureException.class)
            .extracting(e -> ((PicsureException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void failsClosedOnEmptyBodyAsBadGateway() {
        qs.stubFor(get(urlEqualTo("/operations/internal/queries/empty/dispatch")).willReturn(okJson("{\"queryJson\":\"\"}")));
        assertThatThrownBy(() -> fetcher().queryJsonForPath("/query/empty/result")).isInstanceOf(PicsureException.class)
            .extracting(e -> ((PicsureException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
