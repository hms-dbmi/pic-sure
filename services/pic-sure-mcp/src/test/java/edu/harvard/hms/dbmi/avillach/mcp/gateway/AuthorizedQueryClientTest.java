package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.mcp.caller.CallerHeaders;
import edu.harvard.hms.dbmi.avillach.mcp.config.GatewayRequestInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Covers the one authorized call: its path, the replayed caller token, and the count-types-only guard that runs before anything is sent.
 */
class AuthorizedQueryClientTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String BEARER = "Bearer caller-token";

    private MockRestServiceServer server;
    private AuthorizedQueryClient client;
    private CallerHeaders caller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient gateway = builder.build();
        client = new AuthorizedQueryClient(gateway, gateway);
        caller = new CallerHeaders(BEARER, null, "req-9", null);
    }

    @Test
    void theOnlyPublicRequestMethodIsCountSync() {
        List<String> publicMethods = Arrays.stream(AuthorizedQueryClient.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).map(Method::getName).toList();

        assertThat(publicMethods).containsExactly("countSync");
        assertThat(AuthorizedQueryClient.AUTHORIZED_QUERY_SYNC_PATH).isEqualTo("/hpds/auth/query/sync");
    }

    @Test
    void theAllowListIsExactlyTheFiveCountTypes() {
        assertThat(AuthorizedQueryClient.ALLOWED_RESULT_TYPES).containsExactlyInAnyOrder(
            ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT,
            ResultType.OBSERVATION_CROSS_COUNT
        );
    }

    @ParameterizedTest
    @EnumSource(
        value = ResultType.class, names = {"CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT"}
    )
    void aCrossCountGoesThroughTheCrossCountClientAndACountDoesNot(ResultType type) {
        RestClient.Builder countBuilder = RestClient.builder().baseUrl(GATEWAY);
        RestClient.Builder crossCountBuilder = RestClient.builder().baseUrl(GATEWAY);
        MockRestServiceServer countServer = MockRestServiceServer.bindTo(countBuilder).build();
        MockRestServiceServer crossCountServer = MockRestServiceServer.bindTo(crossCountBuilder).build();
        AuthorizedQueryClient routed = new AuthorizedQueryClient(countBuilder.build(), crossCountBuilder.build());
        countServer.expect(requestTo(GATEWAY + AuthorizedQueryClient.AUTHORIZED_QUERY_SYNC_PATH))
            .andExpect(jsonPath("$.query.expectedResultType").value("COUNT")).andRespond(withSuccess("42", MediaType.APPLICATION_JSON));
        crossCountServer.expect(requestTo(GATEWAY + AuthorizedQueryClient.AUTHORIZED_QUERY_SYNC_PATH))
            .andExpect(jsonPath("$.query.expectedResultType").value(type.name())).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        routed.countSync(new Query(null, null, null, null, ResultType.COUNT, null, null), caller);
        routed.countSync(new Query(List.of("\\a\\"), null, null, null, type, null, null), caller);

        countServer.verify();
        crossCountServer.verify();
    }

    @Test
    void postsACountToTheAuthorizedSyncPathAsTheCaller() {
        server.expect(requestTo(GATEWAY + "/hpds/auth/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token"))
            .andExpect(jsonPath("$.query.expectedResultType").value("COUNT")).andRespond(withSuccess("42", MediaType.APPLICATION_JSON));

        String body = client.countSync(new Query(null, null, null, null, ResultType.COUNT, null, null), caller);

        server.verify();
        assertThat(body).isEqualTo("42");
    }

    @ParameterizedTest
    @EnumSource(
        value = ResultType.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT", "OBSERVATION_CROSS_COUNT"}
    )
    void refusesEveryNonCountTypeWithoutSending(ResultType type) {
        assertThatThrownBy(() -> client.countSync(new Query(null, null, null, null, type, null, null), caller))
            .isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void refusesAuthorizationFiltersIdsAndAMissingTokenWithoutSending() {
        List<Query> queries = List.of(
            new Query(null, List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs1.c1"))), null, null, ResultType.COUNT, null, null),
            new Query(null, null, null, null, ResultType.COUNT, UUID.randomUUID(), null),
            new Query(null, null, null, null, ResultType.COUNT, null, UUID.randomUUID())
        );
        for (Query query : queries) {
            assertThatThrownBy(() -> client.countSync(query, caller)).isInstanceOf(IllegalArgumentException.class);
        }
        Query plain = new Query(null, null, null, null, ResultType.COUNT, null, null);
        assertThatThrownBy(() -> client.countSync(plain, new CallerHeaders(null, null, "r", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.countSync(plain, new CallerHeaders("  ", null, "r", null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.countSync(null, caller)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
