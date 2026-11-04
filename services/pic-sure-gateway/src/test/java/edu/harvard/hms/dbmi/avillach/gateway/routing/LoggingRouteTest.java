package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * The explicit {@code /logging/**} route forwards to the logging service with the leading {@code /logging} segment stripped because the
 * service exposes {@code /audit} and {@code /health} at its root. The request must reach the logging stub through the order-100 route;
 * there is no catch-all fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoggingRouteTest {

    static WireMockServer loggingStub;

    @DynamicPropertySource
    static void loggingUrl(DynamicPropertyRegistry registry) {
        loggingStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        loggingStub.start();
        registry.add("LOGGING_URL", loggingStub::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        loggingStub.stop();
    }

    @BeforeEach
    void resetStub() {
        loggingStub.resetAll();
        // The logging service serves at root, so after StripPrefix the gateway forwards to /audit.
        loggingStub.stubFor(get(urlPathEqualTo("/audit")).willReturn(aResponse().withStatus(200).withBody("logging-ok")));
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
    void forwardsLoggingPathToLoggingServiceWithPrefixStripped() {
        ResponseEntity<String> response = rest.getForEntity(url("/logging/audit"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("logging-ok");
        // The /logging prefix was stripped: the logging service saw /audit, never /logging/audit.
        loggingStub.verify(getRequestedFor(urlEqualTo("/audit")));
    }
}
