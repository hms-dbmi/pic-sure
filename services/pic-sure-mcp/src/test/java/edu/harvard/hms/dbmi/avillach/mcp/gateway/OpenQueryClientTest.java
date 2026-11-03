package edu.harvard.hms.dbmi.avillach.mcp.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.PhenotypicFilterType;
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
 * Covers the single open query call: that it is the client's only public request method, the path, the exact request body, the replayed
 * caller headers, and the result-type allow-list that stops anything else from being sent.
 */
class OpenQueryClientTest {

    private static final String GATEWAY = "http://gateway.test:8080";
    private static final String BEARER = "Bearer caller-token";

    private MockRestServiceServer server;
    private OpenQueryClient client;
    private CallerHeaders caller;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder =
            RestClient.builder().baseUrl(GATEWAY).requestInterceptor(new GatewayRequestInterceptor(GATEWAY, "mcp-token"));
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenQueryClient(builder.build());
        caller = new CallerHeaders(BEARER, null, "req-9", null);
    }

    @Test
    void theAllowListIsExactlyTheFourObfuscatedTypes() {
        assertThat(OpenQueryClient.ALLOWED_RESULT_TYPES).containsExactlyInAnyOrder(
            ResultType.COUNT, ResultType.CROSS_COUNT, ResultType.CATEGORICAL_CROSS_COUNT, ResultType.CONTINUOUS_CROSS_COUNT
        );
        assertThatThrownBy(() -> OpenQueryClient.ALLOWED_RESULT_TYPES.add(ResultType.DATAFRAME))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theOnlyPublicRequestMethodIsQuerySync() {
        List<String> publicMethods = Arrays.stream(OpenQueryClient.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()).map(Method::getName).toList();

        assertThat(publicMethods).containsExactly("querySync");
        assertThat(OpenQueryClient.QUERY_SYNC_PATH).isEqualTo("/hpds/open/query/sync");
    }

    @Test
    void postsTheEnvelopeToTheOpenSyncPathAndReplaysTheCaller() {
        server.expect(requestTo(GATEWAY + "/hpds/open/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", BEARER)).andExpect(header("X-Request-Id", "req-9"))
            .andExpect(header("X-PIC-SURE-MCP-TOKEN", "mcp-token")).andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("""
                {"query":{"select":[],"authorizationFilters":[],
                  "phenotypicClause":{"phenotypicFilterType":"FILTER","conceptPath":"\\\\phs1\\\\sex\\\\","values":["Female"],
                    "min":null,"max":null,"not":null},
                  "genomicFilters":[],"expectedResultType":"COUNT","picsureId":null,"id":null}}""", true))
            .andRespond(withSuccess("1234 ±3", MediaType.APPLICATION_JSON));

        String body = client.querySync(
            new Query(
                null, null, new PhenotypicFilter(PhenotypicFilterType.FILTER, "\\phs1\\sex\\", Set.of("Female"), null, null, null), null,
                ResultType.COUNT, null, null
            ), caller
        );

        server.verify();
        assertThat(body).isEqualTo("1234 ±3");
    }

    @ParameterizedTest
    @EnumSource(
        value = ResultType.class, mode = EnumSource.Mode.EXCLUDE,
        names = {"COUNT", "CROSS_COUNT", "CATEGORICAL_CROSS_COUNT", "CONTINUOUS_CROSS_COUNT"}
    )
    void refusesEveryTypeOffTheAllowListWithoutSending(ResultType type) {
        Query query = new Query(null, null, null, null, type, null, null);

        assertThatThrownBy(() -> client.querySync(query, caller)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test
    void refusesAuthorizationFiltersAndIdsWithoutSending() {
        List<Query> queries = List.of(
            new Query(null, List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs1.c1"))), null, null, ResultType.COUNT, null, null),
            new Query(null, null, null, null, ResultType.COUNT, UUID.randomUUID(), null),
            new Query(null, null, null, null, ResultType.COUNT, null, UUID.randomUUID()),
            new Query(null, null, null, null, null, null, null)
        );
        for (Query query : queries) {
            assertThatThrownBy(() -> client.querySync(query, caller)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> client.querySync(null, caller)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }
}
