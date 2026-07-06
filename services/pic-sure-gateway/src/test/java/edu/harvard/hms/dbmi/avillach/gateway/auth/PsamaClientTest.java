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
    void bindsUserIdFromPsamaUuidField() {
        // The REAL PSAMA inspect response carries the user UUID as "uuid" (UserService/UserClaims) -- there is no
        // "userId" field. X-User-Id propagation (and thus query/operations-service authn) depends on this binding.
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":true,\"uuid\":\"7c5e0618-0000-0000-0000-000000000000\",\"email\":\"a@b\","
                        + "\"sub\":\"LONG_TERM_TOKEN|s-1\",\"roles\":\"ADMIN\",\"privileges\":[\"SUPER_ADMIN\"]}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", Map.of("Target Service", "/hpds/auth/query/sync"));
        assertThat(resp.active()).isTrue();
        assertThat(resp.userId()).isEqualTo("7c5e0618-0000-0000-0000-000000000000");
    }

    @Test
    void openValidateReturnsBareBoolean() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isTrue();
    }
}
