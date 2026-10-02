package edu.harvard.hms.dbmi.avillach.query.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Posts each client's exact request body at the query lifecycle endpoints and checks that it binds to the strict v3 {@link Query}, and that
 * a v1 body answers 400 on every endpoint that binds {@link HpdsQueryRequest}. WireMock stands in for HPDS and a Mockito
 * {@link OperationsClient} for the operations service. Consent authorization is off so the query HPDS receives is the query the client
 * sent, with nothing added.
 *
 * <p>The frontend body is what {@code getQueryRequestV3} in {@code src/lib/utilities/QueryBuilder.ts} produces after
 * {@code serializeQueryV3} strips each clause's {@code type} key: all seven query members, and {@code not} on every clause. The Python body
 * is what {@code build_query_body} in {@code src/picsure/_services/query_run.py} produces: no {@code authorizationFilters}, null
 * {@code picsureId} and {@code id}, and float bounds.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class HpdsQueryRequestBindingTest {

    private static final String USER = "auth0|alice";
    private static final ObjectMapper STRICT = new ObjectMapper();

    private static final String FRONTEND_BODY = "{\"query\":{\"select\":[\"\\\\demographics\\\\SEX\\\\\"],\"authorizationFilters\":[],"
        + "\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":["
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"not\":false,\"values\":[\"Male\",\"Female\"]},"
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false,\"min\":40,\"max\":65},"
        + "{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\phs000007\\\\pht000009\\\\phv00000543\\\\FL200\\\\\",\"not\":false},"
        + "{\"phenotypicFilterType\":\"ANY_RECORD_OF\",\"conceptPath\":\"\\\\phs000007\\\\pht000010\\\\\",\"not\":false}],\"not\":false},"
        + "\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],"
        + "\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}}";

    private static final String FRONTEND_BLANK_BODY = "{\"query\":{\"select\":[],\"authorizationFilters\":[],\"phenotypicClause\":null,"
        + "\"genomicFilters\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}}";

    private static final String PYTHON_BODY = "{\"query\":{\"select\":[\"\\\\demographics\\\\SEX\\\\\",\"\\\\demographics\\\\AGE\\\\\"],"
        + "\"phenotypicClause\":{\"operator\":\"AND\",\"phenotypicClauses\":["
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"not\":false,\"values\":[\"Male\",\"Female\"]},"
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false,\"min\":40.0,\"max\":65.0}],"
        + "\"not\":false},\"genomicFilters\":[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}],"
        + "\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}}";

    private static final String PYTHON_SINGLE_CLAUSE_BODY = "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],"
        + "\"phenotypicClause\":{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false},"
        + "\"genomicFilters\":[],\"expectedResultType\":\"COUNT\",\"picsureId\":null,\"id\":null}}";

    private static final String V1_BODY = "{\"query\":{\"expectedResultType\":\"COUNT\",\"fields\":[\"\\\\demographics\\\\AGE\\\\\"],"
        + "\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"min\":40}}}}";

    static WireMockServer hpds;

    @BeforeAll
    static void start() {
        hpds = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        hpds.start();
    }

    @AfterAll
    static void stop() {
        hpds.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> "http://localhost:" + hpds.port() + "/PIC-SURE");
        registry.add("consent.based.authorization.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper springMapper;

    @MockitoBean
    private OperationsClient operationsClient;

    private final UUID storedId = UUID.randomUUID();

    @BeforeEach
    void stubs() {
        hpds.resetAll();
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/sync"))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "hpds-query-id").withBody("1234"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query/rr-1/status"))
                .willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"AVAILABLE\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
        when(operationsClient.get(storedId)).thenReturn(new StoredQuery(storedId, "{}", "rr-1", "AVAILABLE", "3", null));
    }

    static Stream<Arguments> clientBodies() {
        return Stream.of(
            Arguments.of("frontend with filters", FRONTEND_BODY), Arguments.of("frontend without filters", FRONTEND_BLANK_BODY),
            Arguments.of("python clause group", PYTHON_BODY), Arguments.of("python single clause", PYTHON_SINGLE_CLAUSE_BODY)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientBodies")
    void clientBodyBindsOnSubmitAndReachesHpdsAsTheSameQuery(String name, String body) throws Exception {
        mockMvc.perform(identified(post("/hpds/auth/query")).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceResultId").value("rr-1"));

        assertThat(queryReceivedAt("/PIC-SURE/v3/query")).isEqualTo(canonical(body));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientBodies")
    void clientBodyBindsOnSyncAndReachesHpdsAsTheSameQuery(String name, String body) throws Exception {
        mockMvc.perform(identified(post("/hpds/auth/query/sync")).content(body)).andExpect(status().isOk());

        assertThat(queryReceivedAt("/PIC-SURE/v3/query/sync")).isEqualTo(canonical(body));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientBodies")
    void clientBodyBindsOnStatus(String name, String body) throws Exception {
        mockMvc.perform(identified(post("/hpds/auth/query/{id}/status", storedId)).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("AVAILABLE"));
    }

    @Test
    void envelopeMembersOtherThanQueryAreIgnored() throws Exception {
        String body = "{\"@type\":\"GeneralQueryRequest\",\"resourceUUID\":\"8694e3d4-5cb4-410f-8431-993445e6d3f6\","
            + "\"resourceCredentials\":{\"BEARER_TOKEN\":\"not-a-real-token\"},\"query\":{\"expectedResultType\":\"COUNT\"}}";

        mockMvc.perform(identified(post("/hpds/auth/query")).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceID").value((Object) null));

        hpds.verify(postRequestedFor(urlEqualTo("/PIC-SURE/v3/query")).withRequestBody(WireMock.notContaining("not-a-real-token")));
        assertThat(queryReceivedAt("/PIC-SURE/v3/query")).isEqualTo(canonical(body));
    }

    /**
     * Pins the premise behind the consent-on path: the strict read of a client body yields the same {@link Query} the earlier path got by
     * reading the body as a map and converting its {@code query} member with a default mapper.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("clientBodies")
    void strictReadEqualsTheLenientConvertedQuery(String name, String body) throws Exception {
        Query strict = springMapper.readValue(body, HpdsQueryRequest.class).query();
        Map<String, Object> asMap = springMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        Query lenient = new ObjectMapper().convertValue(asMap.get("query"), Query.class);

        assertThat(strict).isEqualTo(lenient);
    }

    static Stream<Arguments> endpointsThatBindAQuery() {
        UUID id = UUID.randomUUID();
        return Stream.of(
            Arguments.of("submit", "/hpds/auth/query"), Arguments.of("sync", "/hpds/auth/query/sync"),
            Arguments.of("status", "/hpds/auth/query/" + id + "/status"), Arguments.of("result", "/hpds/auth/query/" + id + "/result"),
            Arguments.of("signed-url", "/hpds/auth/query/" + id + "/signed-url")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("endpointsThatBindAQuery")
    void v1BodyIsRefusedWith400BeforeAnythingRuns(String name, String path) throws Exception {
        mockMvc.perform(identified(post(path)).content(V1_BODY)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request")).andExpect(jsonPath("$.message").value("Malformed request body"));

        verifyNoInteractions(operationsClient);
        hpds.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void unknownMemberInsideAClauseIsRefusedWith400() throws Exception {
        String body = "{\"query\":{\"expectedResultType\":\"COUNT\",\"phenotypicClause\":"
            + "{\"type\":\"PhenotypicFilter\",\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false}}}";

        mockMvc.perform(identified(post("/hpds/auth/query")).content(body)).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void resultTypeTheServerDoesNotDefineIsRefusedWith400() throws Exception {
        mockMvc.perform(identified(post("/hpds/auth/query")).content("{\"query\":{\"expectedResultType\":\"SECRET_ADMIN_DATAFRAME\"}}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorType").value("bad_request"));

        hpds.verify(0, anyRequestedFor(anyUrl()));
    }

    private static MockHttpServletRequestBuilder identified(MockHttpServletRequestBuilder request) {
        return request.header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON);
    }

    private String queryReceivedAt(String url) throws Exception {
        return canonical(hpds.findAll(postRequestedFor(urlEqualTo(url))).get(0).getBodyAsString());
    }

    /** Reads the {@code query} member with a mapper that fails on unknown members, as HPDS does, and writes it back in record order. */
    private static String canonical(String envelope) throws Exception {
        return STRICT.writeValueAsString(STRICT.treeToValue(STRICT.readTree(envelope).get("query"), Query.class));
    }
}
