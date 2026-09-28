package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import com.github.tomakehurst.wiremock.WireMockServer;

class PsamaClientTest {

    static WireMockServer psama;

    @BeforeAll
    static void start() {
        // http2PlainDisabled avoids a known JDK HttpClient <-> WireMock(Jetty) h2c upgrade bug that manifests as
        // "RST_STREAM: Stream cancelled" when RestClient's default JDK-backed request factory is used.
        psama = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort().http2PlainDisabled(true));
        psama.start();
    }

    @AfterAll
    static void stop() {
        psama.stop();
    }

    private PsamaClient client() {
        return new PsamaClient(
            RestClient.builder().build(), "http://127.0.0.1:" + psama.port() + "/token/introspect",
            "http://127.0.0.1:" + psama.port() + "/open/validate", "service-token"
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
    void openValidateAcceptsBareBooleanTrueFromOlderPsamaAsAnonymousGrant() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));

        OpenAccessValidation validation = client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"));

        assertThat(validation).isEqualTo(new OpenAccessValidation(true, null, null, null, null, null));
    }

    @Test
    void openValidateAcceptsBareBooleanFalseWithNoDenialReason() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("false")));

        OpenAccessValidation validation = client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"));

        assertThat(validation).isEqualTo(new OpenAccessValidation(false, null, null, null, null, null));
    }

    @Test
    void openValidateParsesKeyIdentity() {
        psama.stubFor(
            post(urlEqualTo("/open/validate")).willReturn(
                okJson(
                    "{\"valid\":true,\"keyType\":\"USER\",\"keyId\":\"7c5e0618-0000-0000-0000-000000000000\",\"displayPrefix\":\"AbCd1234\","
                        + "\"denial\":null,\"refreshedToken\":null}"
                )
            )
        );

        OpenAccessValidation validation = client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"));

        assertThat(validation)
            .isEqualTo(new OpenAccessValidation(true, "USER", "7c5e0618-0000-0000-0000-000000000000", "AbCd1234", null, null));
    }

    @Test
    void openValidateParsesDenialReason() {
        psama.stubFor(
            post(urlEqualTo("/open/validate")).willReturn(
                okJson(
                    "{\"valid\":false,\"keyType\":null,\"keyId\":null,\"displayPrefix\":null,\"denial\":\"key_invalid\","
                        + "\"refreshedToken\":null}"
                )
            )
        );

        OpenAccessValidation validation = client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.denial()).isEqualTo(OpenAccessValidation.DENIAL_KEY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"true\"", "[true]", "{}"})
    void openValidateDeniesAnyOtherResponseShape(String body) {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson(body)));

        OpenAccessValidation validation = client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"));

        assertThat(validation.valid()).isFalse();
        assertThat(validation.keyId()).isNull();
    }

    @Test
    void openValidateDeniesAnEmptyBody() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(ok().withHeader("Content-Type", "application/json")));

        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host")).valid()).isFalse();
    }

    @Test
    void openValidateTreatsNonBooleanValidAsDenied() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("{\"valid\":\"true\",\"keyType\":\"USER\"}")));

        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host")).valid()).isFalse();
    }

    @Test
    void openValidateSendsResponseVersionTwoAlongsideTheCallersPayload() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));
        psama.resetRequests();

        // an immutable map: the client must add the field to a copy, not the caller's payload
        client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host", "apiKey", "picsure_testKeyValue123"));

        psama.verify(
            1,
            postRequestedFor(urlEqualTo("/open/validate")).withHeader("Authorization", equalTo("Bearer service-token"))
                .withRequestBody(matchingJsonPath("$." + PsamaClient.RESPONSE_VERSION, equalTo("2")))
                .withRequestBody(matchingJsonPath("$.ipAddress", equalTo("OPEN_ACCESS:host")))
                .withRequestBody(matchingJsonPath("$.apiKey", equalTo("picsure_testKeyValue123")))
        );
    }
}
