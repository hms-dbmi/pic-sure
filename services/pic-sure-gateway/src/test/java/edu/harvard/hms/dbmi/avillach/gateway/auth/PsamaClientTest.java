package edu.harvard.hms.dbmi.avillach.gateway.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;
import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;

class PsamaClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void postsIntrospectionWithServiceBearerAndParsesRolesAndPrivileges() {
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":true,\"userId\":\"u-1\",\"email\":\"a@b\",\"sub\":\"s-1\",\"roles\":[\"ADMIN\",\"USER\"],"
                        + "\"privileges\":[\"SUPER_ADMIN\"]}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", new TargetedRequest("/info", null));
        assertThat(resp.active()).isTrue();
        assertThat(resp.userId()).isEqualTo("u-1");
        assertThat(resp.roles()).containsExactly("ADMIN", "USER");
        assertThat(resp.privileges()).containsExactly("SUPER_ADMIN");

        psama.verify(postRequestedFor(urlEqualTo("/token/introspect")).withHeader("Authorization", equalTo("Bearer service-token")));
    }

    @Test
    void bindsUserIdFromPsamaUuidField() {
        // A PSAMA that has not been redeployed still carries the user UUID as "uuid" (UserService/UserClaims).
        // X-User-Id propagation (and thus query/operations-service authn) depends on this alias.
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":true,\"uuid\":\"7c5e0618-0000-0000-0000-000000000000\",\"email\":\"a@b\","
                        + "\"sub\":\"LONG_TERM_TOKEN|s-1\",\"roles\":[\"ADMIN\"],\"privileges\":[\"SUPER_ADMIN\"]}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", new TargetedRequest("/hpds/auth/query/sync", null));
        assertThat(resp.active()).isTrue();
        assertThat(resp.userId()).isEqualTo("7c5e0618-0000-0000-0000-000000000000");
    }

    /**
     * PSAMA copies every JWT claim into the inspect response and adds an unmodelled {@code message}; the contract is a tolerant reader so
     * none of that may break binding.
     */
    @Test
    void ignoresUnmodelledFieldsPsamaSendsAlongside() {
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":false,\"message\":\"User doesn't have enough privileges.\",\"exp\":1785439482,\"iat\":1785435882,"
                        + "\"jti\":\"j-1\",\"whatever\":{\"nested\":true}}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", new TargetedRequest("/hpds/auth/v3/query", null));
        assertThat(resp.active()).isFalse();
    }

    /**
     * SECURITY: deployed FISMA access rules are JsonPath strings in PSAMA's database, evaluated against the {@code request} node of the
     * body this client actually PUTs on the wire. The contracts module pins the record's serialization against a bare ObjectMapper;
     * <em>this</em> test pins the bytes the GATEWAY sends, through the RestClient message converter that serializes them in production. A
     * Jackson customization, a naming strategy, or a converter swap that broke the rules would show up here and nowhere else.
     */
    @Test
    void serializesTheIntrospectionBodyDeployedJsonPathRulesCanStillResolve() throws Exception {
        psama.resetRequests();
        psama.stubFor(post(urlEqualTo("/token/introspect")).willReturn(okJson("{\"active\":true,\"userId\":\"u-1\"}")));

        client().introspect(
            "user-token", new TargetedRequest("/hpds/auth/v3/query", MAPPER.readTree("{\"expectedResultType\":\"COUNT\",\"select\":[]}"))
        );

        String sent = psama.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(JsonPath.<String>read(sent, "$.token")).isEqualTo("user-token");
        // The two deployed rule families, anchored at the request node.
        assertThat(JsonPath.<String>read(sent, "$.request.['Target Service']")).isEqualTo("/hpds/auth/v3/query");
        assertThat(JsonPath.<String>read(sent, "$.request.query.expectedResultType")).isEqualTo("COUNT");
        // The query must stay a JSON OBJECT: as an escaped string, $.query still matches while $.query.<field> silently stops resolving.
        assertThat(JsonPath.parse(sent).<Object>read("$.request.query")).isInstanceOf(Map.class);
        assertThat(sent).contains("\"Target Service\"").doesNotContain("targetService");
    }

    /** Absence must stay absence: PSAMA's extractAndCheckRule decides differently on PathNotFound than on a null match. */
    @Test
    void omitsTheQueryKeyEntirelyWhenThereIsNoBodyToAuthorize() {
        psama.resetRequests();
        psama.stubFor(post(urlEqualTo("/token/introspect")).willReturn(okJson("{\"active\":true,\"userId\":\"u-1\"}")));

        client().introspect("user-token", new TargetedRequest("/picsure/proxy/dictionary/search", null));

        String sent = psama.getAllServeEvents().getFirst().getRequest().getBodyAsString();
        assertThat(JsonPath.<String>read(sent, "$.request.['Target Service']")).isEqualTo("/picsure/proxy/dictionary/search");
        assertThatThrownBy(() -> JsonPath.read(sent, "$.request.query")).isInstanceOf(PathNotFoundException.class);
    }

    /** The shape a pre-retyping PSAMA answers with. Still read, so this gateway can be deployed ahead of PSAMA. */
    @Test
    void openValidateReadsTheLegacyBareBoolean() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("true")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isTrue();

        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("false")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isFalse();
    }

    /** The shape PSAMA answers with now that its surface is typed: {@code ValidationResponse}. */
    @Test
    void openValidateReadsTheTypedValidationRecord() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("{\"valid\":true}")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isTrue();

        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("{\"valid\":false}")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isFalse();
    }

    /** An answer the gateway cannot read is not an answer it may treat as a grant: this is the unauthenticated path. */
    @Test
    void openValidateDeniesOnAnUnrecognizedShape() {
        psama.stubFor(post(urlEqualTo("/open/validate")).willReturn(okJson("{\"authorized\":true}")));
        assertThat(client().validateOpenAccess(Map.of("ipAddress", "OPEN_ACCESS:host"))).isFalse();
    }

    @Test
    void parsesTheConsentMutatedQueryAsAnObjectNode() {
        psama.stubFor(
            post(urlEqualTo("/token/introspect")).willReturn(
                okJson(
                    "{\"active\":true,\"userId\":\"u-1\",\"roles\":[\"ADMIN\"],\"privileges\":[],"
                        + "\"query\":{\"expectedResultType\":\"COUNT\",\"_topmed_consents\":[\"phs1\"]}}"
                )
            )
        );

        IntrospectionResponse resp = client().introspect("user-token", new TargetedRequest("/hpds/auth/v3/query", null));
        assertThat(resp.query()).isNotNull();
        assertThat(resp.query().isObject()).isTrue();
        assertThat(resp.query().get("_topmed_consents").get(0).asText()).isEqualTo("phs1");
    }

    @Test
    void leavesTheMutatedQueryNullWhenPsamaOmitsIt() {
        psama.stubFor(
            post(urlEqualTo("/token/introspect"))
                .willReturn(okJson("{\"active\":true,\"userId\":\"u-1\",\"roles\":[\"ADMIN\"],\"privileges\":[]}"))
        );

        IntrospectionResponse resp = client().introspect("user-token", new TargetedRequest("/hpds/auth/v3/query", null));
        assertThat(resp.query()).isNull();
        assertThat(resp.roles()).isEqualTo(List.of("ADMIN"));
    }
}
