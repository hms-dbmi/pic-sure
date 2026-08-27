package edu.harvard.hms.dbmi.avillach.gateway.routing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BannerRouteTest {

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
    }

    @AfterAll
    static void stopStubs() {
        operationsStub.stop();
        psamaStub.stop();
    }

    @BeforeEach
    void resetStubs() {
        operationsStub.resetAll();
        operationsStub
            .stubFor(get(urlEqualTo("/operations/banners/active")).willReturn(aResponse().withStatus(200).withBody("banner-feed-ok")));
        psamaStub.resetAll();
        psamaStub.stubFor(any(anyUrl()).willReturn(serverError()));
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    int port;

    @Test
    void forwardsActiveBannerFeedAnonymouslyWithoutStrippingThePath() {
        ResponseEntity<String> response = rest.getForEntity("http://127.0.0.1:" + port + "/operations/banners/active", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("banner-feed-ok");
        operationsStub.verify(getRequestedFor(urlEqualTo("/operations/banners/active")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void rejectsAnUnauthenticatedManagementMutationAtTheGateway() {
        operationsStub.stubFor(post(urlEqualTo("/operations/banners")).willReturn(aResponse().withStatus(201)));

        ResponseEntity<String> response = rest.postForEntity("http://127.0.0.1:" + port + "/operations/banners", "{}", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        operationsStub.verify(0, anyRequestedFor(urlEqualTo("/operations/banners")));
        psamaStub.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void propagatesResolvedPrivilegesForManagementAuthorizationAtTheOperationsBoundary() {
        psamaStub.stubFor(
            post(urlEqualTo("/auth/token/inspect")).willReturn(
                okJson(
                    "{\"active\":true,\"userId\":\"researcher-id\",\"sub\":\"researcher-sub\",\"email\":\"r@example.org\","
                        + "\"roles\":\"USER\",\"privileges\":[\"PIC_SURE_ANY_QUERY\"]}"
                )
            )
        );
        operationsStub.stubFor(post(urlEqualTo("/operations/banners")).willReturn(aResponse().withStatus(403)));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("researcher-token");

        ResponseEntity<String> response = rest
            .exchange("http://127.0.0.1:" + port + "/operations/banners", HttpMethod.POST, new HttpEntity<>("{}", headers), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        operationsStub.verify(
            postRequestedFor(urlEqualTo("/operations/banners"))
                .withHeader("X-User-Id", equalTo("researcher-id"))
                .withHeader("X-User-Privileges", equalTo("PIC_SURE_ANY_QUERY"))
        );
    }
}
