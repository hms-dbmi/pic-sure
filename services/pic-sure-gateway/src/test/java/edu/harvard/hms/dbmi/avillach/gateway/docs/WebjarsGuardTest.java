package edu.harvard.hms.dbmi.avillach.gateway.docs;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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

/**
 * Boot's default {@code /webjars/**} static mapping is closed by {@code WebjarsGuardConfig} regardless of the docs kill switch: a caller
 * with a valid bearer still gets 404, both with the console on and with it off.
 */
class WebjarsGuardTest {

    private static WireMockServer psamaStub() {
        WireMockServer stub = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        stub.start();
        return stub;
    }

    private static ResponseEntity<String> requestWebjarAsset(TestRestTemplate rest, int port, String version) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer user-token");
        String url = "http://127.0.0.1:" + port + "/webjars/swagger-ui/" + version + "/swagger-ui.css";
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    class WithDocsEnabled {

        static WireMockServer psamaStub;

        @DynamicPropertySource
        static void urls(DynamicPropertyRegistry registry) {
            psamaStub = psamaStub();
            registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
        }

        @AfterAll
        static void stop() {
            psamaStub.stop();
        }

        @BeforeEach
        void stubIntrospection() {
            psamaStub.resetAll();
            psamaStub.stubFor(
                post(urlEqualTo("/auth/token/inspect"))
                    .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"sub\":\"s-1\",\"email\":\"a@b\",\"role\":\"USER\"}"))
            );
        }

        @Autowired
        private TestRestTemplate rest;

        @Autowired
        private SwaggerUiAssets assets;

        @LocalServerPort
        int port;

        @Test
        void webjarFileIsNotServedEvenWithAValidBearer() {
            ResponseEntity<String> response = requestWebjarAsset(rest, port, assets.version());
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "picsure.gateway.docs.enabled=false")
    class WithDocsDisabled {

        static WireMockServer psamaStub;

        @DynamicPropertySource
        static void urls(DynamicPropertyRegistry registry) {
            psamaStub = psamaStub();
            registry.add("TOKEN_INTROSPECTION_URL", () -> psamaStub.baseUrl() + "/auth/token/inspect");
        }

        @AfterAll
        static void stop() {
            psamaStub.stop();
        }

        @BeforeEach
        void stubIntrospection() {
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

        @Test
        void webjarFileIsNotServedWithTheKillSwitchOff() {
            String version = new SwaggerUiAssets().version();
            ResponseEntity<String> response = requestWebjarAsset(rest, port, version);
            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }
}
