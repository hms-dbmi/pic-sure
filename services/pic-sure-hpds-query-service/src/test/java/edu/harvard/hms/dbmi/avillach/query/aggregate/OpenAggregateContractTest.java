package edu.harvard.hms.dbmi.avillach.query.aggregate;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import edu.harvard.dbmi.avillach.domain.ContinuousBinningResponse;
import edu.harvard.dbmi.avillach.domain.GeneralQueryRequest;
import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Pins the open aggregate path against the bodies it produced while it rewrote the query as untyped JSON. For each result type the open
 * path serves, the frontend's exact request body goes in, and the test checks the response body byte for byte, the order of the downstream
 * calls, and each downstream body. The expected strings were captured from the code before it read a typed {@link Query}, with the same
 * stubs and the same obfuscation salt.
 *
 * <p>The search term and the binning request are compared byte for byte. A query sent to HPDS is compared as a value: both bodies are read
 * with a mapper that fails on unknown members, as HPDS reads them, and written back in record order. Byte equality cannot hold there for a
 * browser body, because the untyped path forwarded the browser's member order and omissions and the typed path writes every record
 * component. It does hold for a body already in record order, which is what the visualization service sends, and the last test pins that.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OpenAggregateContractTest {

    private static final ObjectMapper STRICT = new ObjectMapper();
    private static final String SYNC_URL = "/OPEN/v3/query/sync";
    private static final String SEARCH_URL = "/OPEN/v3/search";
    private static final String BIN_URL = "/VIZ/bin/continuous";

    private static final String CLAUSE = "{\"operator\":\"AND\",\"phenotypicClauses\":["
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"not\":false,\"values\":[\"Male\",\"Female\"]},"
        + "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\AGE\\\\\",\"not\":false,\"min\":40,\"max\":65},"
        + "{\"phenotypicFilterType\":\"REQUIRED\",\"conceptPath\":\"\\\\phs000007\\\\pht000009\\\\phv00000543\\\\FL200\\\\\",\"not\":false}],"
        + "\"not\":false}";
    private static final String GENOMIC = "[{\"key\":\"Gene_with_variant\",\"values\":[\"APOE\"]}]";
    private static final String CONSENT_SELECT =
        "[\"\\\\_studies_consents\\\\\",\"\\\\_studies_consents\\\\phs000007\\\\\",\"\\\\_studies_consents\\\\phs000179\\\\\"]";

    private static final String CONSENTS_SEARCH_RESULT = "{\"results\":{\"phenotypes\":{\"\\\\_studies_consents\\\\\":{},"
        + "\"\\\\_studies_consents\\\\phs000007\\\\\":{},\"\\\\_studies_consents\\\\phs000179\\\\\":{}},\"info\":{}},"
        + "\"searchQuery\":\"\\\\_studies_consents\\\\\"}";
    private static final String CROSS_COUNTS =
        "{\"\\\\_studies_consents\\\\\":1234,\"\\\\_studies_consents\\\\phs000007\\\\\":900,\"\\\\_studies_consents\\\\phs000179\\\\\":5}";
    private static final String SEARCH_TERM_SENT =
        "{\"@type\":\"GeneralQueryRequest\",\"query\":\"\\\\_studies_consents\\\\\",\"resourceUUID\":null}";
    private static final String BINNING_SENT =
        "{\"@type\":\"GeneralQueryRequest\",\"query\":{\"\\\\demographics\\\\AGE\\\\\":{\"42\":100,\"43\":250,\"51\":8}},\"resourceUUID\":null}";
    private static final String VCF = "CHROM\tPOSITION\tREF\tALT\tPatients with this variant in subset\n19\t44908684\tT\tC\t12/1234\n";

    static WireMockServer downstream;

    @BeforeAll
    static void start() {
        downstream = new WireMockServer(options().dynamicPort().http2PlainDisabled(true));
        downstream.start();
    }

    @AfterAll
    static void stop() {
        downstream.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("hpds.auth-url", () -> downstream.baseUrl() + "/PIC-SURE");
        registry.add("hpds.open-url", () -> downstream.baseUrl() + "/PIC-SURE");
        registry.add("aggregate.hpds-open-url", () -> downstream.baseUrl() + "/OPEN");
        registry.add("aggregate.visualization-url", () -> downstream.baseUrl() + "/VIZ");
        registry.add("aggregate.obfuscation.salt", () -> "pinned-salt");
        registry.add("consent.based.authorization.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void stubs() throws Exception {
        downstream.resetAll();
        downstream.stubFor(WireMock.post(urlEqualTo(SEARCH_URL)).willReturn(okJson(CONSENTS_SEARCH_RESULT)));
        downstream.stubFor(
            WireMock.post(urlEqualTo(SYNC_URL)).withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo("CROSS_COUNT")))
                .willReturn(okJson(CROSS_COUNTS).withHeader("queryMetadata", "cross-count-query-id"))
        );
        Map<String, Integer> ageBins = new LinkedHashMap<>();
        ageBins.put("40 - 49", 350);
        ageBins.put("50 - 59", 8);
        downstream.stubFor(
            WireMock.post(urlEqualTo(BIN_URL))
                .willReturn(okJson(STRICT.writeValueAsString(new ContinuousBinningResponse(Map.of("\\demographics\\AGE\\", ageBins)))))
        );
    }

    /** One row per result type on the open allow-list: what HPDS answers, the body the base returned, and the calls the base made. */
    static Stream<Arguments> servedResultTypes() {
        return Stream.of(
            Arguments.of("COUNT", "1234", "1237 ±3", List.of(SYNC_URL)),
            Arguments.of(
                "CROSS_COUNT", CROSS_COUNTS,
                "{\"\\\\_studies_consents\\\\\":\"1232 ±3\",\"\\\\_studies_consents\\\\phs000007\\\\\":\"898 ±3\","
                    + "\"\\\\_studies_consents\\\\phs000179\\\\\":\"< 10\"}",
                List.of(SEARCH_URL, SYNC_URL)
            ),
            Arguments.of(
                "CATEGORICAL_CROSS_COUNT", "{\"\\\\demographics\\\\SEX\\\\\":{\"Male\":5,\"Female\":700}}",
                "{\"\\\\demographics\\\\SEX\\\\\":{\"Female\":{\"count\":698,\"display\":\"698 ±3\",\"variance\":3},"
                    + "\"Male\":{\"count\":0,\"display\":\"< 10\",\"variance\":9}}}",
                List.of(SYNC_URL, SEARCH_URL, SYNC_URL)
            ),
            Arguments.of(
                "CONTINUOUS_CROSS_COUNT", "{\"\\\\demographics\\\\AGE\\\\\":{\"42\":100,\"43\":250,\"51\":8}}",
                "{\"\\\\demographics\\\\AGE\\\\\":{\"40 - 49\":{\"count\":348,\"display\":\"348 ±3\",\"variance\":3},"
                    + "\"50 - 59\":{\"count\":0,\"display\":\"< 10\",\"variance\":9}}}",
                List.of(SYNC_URL, SEARCH_URL, SYNC_URL, BIN_URL)
            ),
            passThrough(
                "INFO_COLUMN_LISTING",
                "[{\"key\":\"Gene_with_variant\",\"description\":\"The official symbol for a gene affected by a variant.\","
                    + "\"continuous\":false,\"min\":null,\"max\":null}]"
            ), passThrough("OBSERVATION_CROSS_COUNT", "{\"\\\\demographics\\\\SEX\\\\\":4321}"),
            passThrough("VARIANT_COUNT_FOR_QUERY", "{\"count\":17,\"message\":\"Query ran successfully\"}"),
            passThrough("AGGREGATE_VCF_EXCERPT", VCF), passThrough("VCF_EXCERPT", VCF)
        );
    }

    private static Arguments passThrough(String resultType, String hpdsBody) {
        return Arguments.of(resultType, hpdsBody, hpdsBody, List.of(SYNC_URL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("servedResultTypes")
    void responseAndDownstreamCallsMatchTheUntypedPath(String resultType, String hpdsBody, String expectedBody, List<String> expectedCalls)
        throws Exception {
        stubPrimary(resultType, hpdsBody);

        MvcResult result =
            mockMvc.perform(open("/hpds/open/query/sync").content(frontendBody(resultType))).andExpect(status().isOk()).andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(expectedBody);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(result.getResponse().getHeader("queryMetadata"))
            .isEqualTo("CROSS_COUNT".equals(resultType) ? "cross-count-query-id" : "hpds-query-id");

        List<LoggedRequest> sent = sent();
        assertThat(sent).extracting(LoggedRequest::getUrl).containsExactlyElementsOf(expectedCalls);
        boolean primarySeen = "CROSS_COUNT".equals(resultType);
        for (LoggedRequest request : sent) {
            if (SEARCH_URL.equals(request.getUrl())) {
                assertThat(request.getBodyAsString()).isEqualTo(SEARCH_TERM_SENT);
            } else if (BIN_URL.equals(request.getUrl())) {
                assertThat(request.getBodyAsString()).isEqualTo(BINNING_SENT);
            } else if (primarySeen) {
                assertThat(canonical(request.getBodyAsString())).isEqualTo(canonical(untypedCrossCountSent()));
            } else {
                assertThat(canonical(request.getBodyAsString())).isEqualTo(canonical(frontendBody(resultType)));
                primarySeen = true;
            }
        }
    }

    @Test
    void asyncCrossCountStoresAndDispatchesTheSameQueryAsTheUntypedPath() throws Exception {
        downstream.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());

        mockMvc.perform(open("/hpds/open/query").content(frontendBody("CROSS_COUNT"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceResultId").value("rr-1"));

        List<LoggedRequest> sent = sent();
        assertThat(sent).extracting(LoggedRequest::getUrl).containsExactly(SEARCH_URL, "/PIC-SURE/v3/query");
        assertThat(sent.get(0).getBodyAsString()).isEqualTo(SEARCH_TERM_SENT);
        assertThat(canonical(sent.get(1).getBodyAsString())).isEqualTo(canonical(untypedCrossCountSent()));
    }

    @Test
    void asyncSubmitStoresTheTypedQueryAndMetadataEchoesEveryMember() throws Exception {
        downstream.stubFor(
            WireMock.post(urlEqualTo("/PIC-SURE/v3/query")).willReturn(okJson("{\"resourceResultId\":\"rr-1\",\"status\":\"PENDING\"}"))
        );
        UUID picsureId = UUID.randomUUID();
        when(operationsClient.save(any())).thenReturn(picsureId);

        mockMvc.perform(open("/hpds/open/query").content(frontendBody("COUNT"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.picsureResultId").value(picsureId.toString()));

        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        String stored = saved.getValue().query();
        Query storedQuery = STRICT.treeToValue(STRICT.readTree(stored).get("query"), Query.class);
        Query submitted = STRICT.treeToValue(STRICT.readTree(frontendBody("COUNT")).get("query"), Query.class);
        assertThat(storedQuery).isEqualTo(submitted);

        when(operationsClient.get(picsureId)).thenReturn(new StoredQuery(picsureId, stored, "rr-1", "PENDING", "3", null));

        mockMvc.perform(get("/hpds/open/query/{id}/metadata", picsureId).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.picsureResultId").value(picsureId.toString()))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.select").isArray())
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.authorizationFilters").isArray())
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.phenotypicClause.phenotypicClauses").isArray())
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.genomicFilters[0].key").value("Gene_with_variant"))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query.expectedResultType").value("COUNT"))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query", hasKey("picsureId")))
            .andExpect(jsonPath("$.resultMetadata.queryJson.query", hasKey("id")));
    }

    @Test
    void bodyAlreadyInRecordOrderGoesToHpdsByteForByte() throws Exception {
        String fromVisualization = STRICT.writeValueAsString(
            new GeneralQueryRequest()
                .setQuery(STRICT.treeToValue(STRICT.readTree(frontendBody("OBSERVATION_CROSS_COUNT")).get("query"), Query.class))
        );
        stubPrimary("OBSERVATION_CROSS_COUNT", "{\"\\\\demographics\\\\SEX\\\\\":4321}");

        mockMvc.perform(open("/hpds/open/query/sync").content(fromVisualization)).andExpect(status().isOk());

        assertThat(sent()).hasSize(1);
        assertThat(sent().get(0).getBodyAsString()).isEqualTo(fromVisualization);
    }

    @Test
    void resultTypeOffTheAllowListIs400AndReachesNothing() throws Exception {
        mockMvc.perform(open("/hpds/open/query/sync").content(frontendBody("DATAFRAME"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorType").value("bad_request"))
            .andExpect(jsonPath("$.message").value("Incorrect result type: DATAFRAME"));

        downstream.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void v1BodyIs400OnBothOpenEndpoints() throws Exception {
        String v1 = "{\"query\":{\"expectedResultType\":\"COUNT\",\"fields\":[\"\\\\demographics\\\\AGE\\\\\"],"
            + "\"numericFilters\":{\"\\\\demographics\\\\AGE\\\\\":{\"min\":40}}}}";

        for (String path : List.of("/hpds/open/query/sync", "/hpds/open/query")) {
            mockMvc.perform(open(path).content(v1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        }

        downstream.verify(0, anyRequestedFor(anyUrl()));
        verifyNoInteractions(operationsClient);
    }

    private void stubPrimary(String resultType, String hpdsBody) {
        if ("CROSS_COUNT".equals(resultType)) {
            return;
        }
        downstream.stubFor(
            WireMock.post(urlEqualTo(SYNC_URL)).withRequestBody(matchingJsonPath("$.query.expectedResultType", equalTo(resultType)))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", "hpds-query-id").withBody(hpdsBody))
        );
    }

    private static MockHttpServletRequestBuilder open(String path) {
        return post(path).header(GatewayUserResolver.HEADER_USER_ID, "auth0|alice").contentType(MediaType.APPLICATION_JSON);
    }

    /** The frontend's body: every query member present, clauses without a type key, and not on every clause. */
    private static String frontendBody(String resultType) {
        return "{\"query\":{\"select\":[\"\\\\demographics\\\\SEX\\\\\"],\"authorizationFilters\":[],\"phenotypicClause\":" + CLAUSE
            + ",\"genomicFilters\":" + GENOMIC + ",\"expectedResultType\":\"" + resultType + "\",\"picsureId\":null,\"id\":null}}";
    }

    /** What the untyped path sent for the cross count: the client's query with select replaced and the result type forced. */
    private static String untypedCrossCountSent() {
        return "{\"@type\":\"GeneralQueryRequest\",\"query\":{\"select\":" + CONSENT_SELECT + ",\"authorizationFilters\":[],"
            + "\"phenotypicClause\":" + CLAUSE + ",\"genomicFilters\":" + GENOMIC
            + ",\"expectedResultType\":\"CROSS_COUNT\",\"picsureId\":null,\"id\":null},\"resourceUUID\":null}";
    }

    private static List<LoggedRequest> sent() {
        List<ServeEvent> events = new ArrayList<>(downstream.getAllServeEvents());
        Collections.reverse(events);
        return events.stream().map(ServeEvent::getRequest).toList();
    }

    private static String canonical(String envelope) throws Exception {
        return STRICT.writeValueAsString(STRICT.treeToValue(STRICT.readTree(envelope).get("query"), Query.class));
    }
}
