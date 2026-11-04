package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

import edu.harvard.hms.dbmi.avillach.gateway.filter.OpenAccessFilter;

/**
 * The open-access session refresh must reach the browser on a real proxied response. The proxied response is already committed when the
 * filter chain returns, even for a small body, so a mock-only filter test can't show this: a header set after the chain looks fine there
 * and is dropped here. Full context, with WireMock standing in for PSAMA's validate endpoint and for operations-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionRefreshRouteTest {

    private static final String SESSION_ID = "7c5e0618-5a3f-4d1b-9f0e-2b6c1d8a4e10";
    private static final String REFRESHED = "picsure_s_refreshed.session.token";

    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void urls(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
        registry.add("OPEN_ACCESS_VALIDATE_URL", () -> psamaStub.baseUrl() + "/auth/open/validate");
        registry.add("GATEWAY_OPEN_ACCESS_ENABLED", () -> "true");
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        operationsStub.resetAll();
        operationsStub.stubFor(get(urlEqualTo("/operations/small")).willReturn(aResponse().withStatus(200).withBody("x".repeat(1024))));
        operationsStub
            .stubFor(get(urlEqualTo("/operations/large")).willReturn(aResponse().withStatus(200).withBody("x".repeat(1024 * 1024))));
        psamaStub.resetAll();
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    /** Dial the loopback address, never the name; see {@code DatasetRouteTest#url}. */
    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private void psamaGrantsSession(String refreshedToken) {
        String refreshed = refreshedToken == null ? "null" : "\"" + refreshedToken + "\"";
        psamaStub.stubFor(
            post(urlEqualTo("/auth/open/validate")).willReturn(
                okJson(
                    "{\"valid\":true,\"keyType\":\"SESSION\",\"keyId\":\"" + SESSION_ID + "\",\"displayPrefix\":null,\"denial\":null,"
                        + "\"refreshedToken\":" + refreshed + "}"
                )
            )
        );
    }

    private ResponseEntity<String> anonymousRequest(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("picsure_s_current.session.token");
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/operations/small", "/operations/large"})
    void refreshedSessionReachesTheBrowser(String path) {
        psamaGrantsSession(REFRESHED);

        ResponseEntity<String> response = anonymousRequest(path);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(OpenAccessFilter.SESSION_REFRESH_HEADER)).isEqualTo(REFRESHED);
        // the credential is stripped before the request leaves the gateway
        operationsStub.verify(getRequestedFor(urlEqualTo(path)).withHeader(HttpHeaders.AUTHORIZATION, absent()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/operations/small", "/operations/large"})
    void noRefreshHeaderWhenPsamaReturnedNoRefresh(String path) {
        psamaGrantsSession(null);

        ResponseEntity<String> response = anonymousRequest(path);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().containsKey(OpenAccessFilter.SESSION_REFRESH_HEADER)).isFalse();
    }
}
