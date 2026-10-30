package edu.harvard.hms.dbmi.avillach.gateway.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Journey (g) of {@link RateLimitJourneyTest}: with {@code RATE_LIMIT_ENABLED=false} the same small limits, trusted proxy and allowlist
 * limit nothing. Full context, real HTTP, WireMock for PSAMA and operations-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitDisabledJourneyTest {

    private static final int LIMIT = 2;

    static WireMockServer operationsStub;
    static WireMockServer psamaStub;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        operationsStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        operationsStub.start();
        registry.add("OPERATIONS_SERVICE_URL", operationsStub::baseUrl);

        psamaStub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psamaStub.start();
        registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
        registry.add("OPEN_ACCESS_VALIDATE_URL", () -> psamaStub.baseUrl() + "/auth/open/validate");
        registry.add("GATEWAY_OPEN_ACCESS_ENABLED", () -> "true");

        registry.add("RATE_LIMIT_ENABLED", () -> "false");
        registry.add("RATE_LIMIT_TRUSTED_PROXIES", () -> "127.0.0.1");
        registry.add("RATE_LIMIT_PLATFORM_ALLOWLISTED_SOURCE_CIDRS", () -> "198.51.100.0/24");
        registry.add("RATE_LIMIT_IP_PREVALIDATION_PER_MINUTE", () -> LIMIT);
        for (String tier : List.of("USER", "SESSION", "IP")) {
            registry.add("RATE_LIMIT_" + tier + "_BURST_PER_MINUTE", () -> LIMIT);
            registry.add("RATE_LIMIT_" + tier + "_SUSTAINED_PER_DAY", () -> LIMIT);
        }
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        psamaStub.stop();
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    @Test
    void requestsFarOverTheConfiguredLimitsAllPass() {
        operationsStub.stubFor(get(urlEqualTo("/operations/data")).willReturn(aResponse().withStatus(200).withBody("upstream")));
        psamaStub.stubFor(
            post(urlEqualTo("/auth/open/validate")).willReturn(
                okJson(
                    "{\"valid\":true,\"keyType\":\"USER\",\"keyId\":\"0d0c6b0e-1a2b-4c3d-8e4f-00000000000a\",\"displayPrefix\":null,"
                        + "\"denial\":null,\"refreshedToken\":null}"
                )
            )
        );
        int requests = 5 * LIMIT;

        for (String bearer : new String[] {null, "picsure_u_keyAlpha"}) {
            HttpHeaders headers = new HttpHeaders();
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
            headers.set("X-Forwarded-For", "203.0.113.70");
            for (int i = 0; i < requests; i++) {
                assertThat(
                    rest.exchange("http://127.0.0.1:" + port + "/operations/data", HttpMethod.GET, new HttpEntity<>(headers), String.class)
                        .getStatusCode().value()
                ).isEqualTo(200);
            }
        }

        psamaStub.verify(2 * requests, postRequestedFor(urlEqualTo("/auth/open/validate")));
        operationsStub.verify(2 * requests, getRequestedFor(urlEqualTo("/operations/data")));
    }
}
