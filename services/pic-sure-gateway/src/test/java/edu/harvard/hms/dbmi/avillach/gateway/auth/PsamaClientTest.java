package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

class PsamaClientTest {

    static WireMockServer psama;

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug that manifests as
        // "RST_STREAM: Stream cancelled" when RestClient's default JDK-backed request factory is used.
        psama = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        psama.start();
    }

    @AfterAll
    static void stop() {
        psama.stop();
    }

    private PsamaClient client() {
        return new PsamaClient(
            RestClient.builder().build(), "http://localhost:" + psama.port() + "/token/introspect",
            "http://localhost:" + psama.port() + "/open/validate", "service-token"
        );
    }

    @Test
    void postsIntrospectionWithServiceBearerAndParsesPrivileges() {
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":true,\"userId\":\"u-1\",\"email\":\"a@b\",\"sub\":\"s-1\",\"roles\":\"ADMIN\",\"privileges\":[\"SUPER_ADMIN\"]}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", Map.of("Target Service", "/info"));
        assertThat(resp.active()).isTrue();
        assertThat(resp.userId()).isEqualTo("u-1");
        assertThat(resp.privileges()).containsExactly("SUPER_ADMIN");

        psama.verify(postRequestedFor(urlEqualTo("/token/introspect")).withHeader("Authorization", equalTo("Bearer service-token")));
    }

    @Test
    void openValidateReturnsBareBoolean() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isTrue();
    }
}
