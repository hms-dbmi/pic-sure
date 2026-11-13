package edu.harvard.hms.dbmi.avillach.query;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

import edu.harvard.dbmi.avillach.domain.SaveQueryRequest;
import edu.harvard.dbmi.avillach.domain.StoredQuery;
import edu.harvard.dbmi.avillach.domain.UpdateQueryRequest;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import edu.harvard.hms.dbmi.avillach.query.operations.OperationsClient;

/**
 * Checks, on every route that sends a query to HPDS, that the {@code picsureId} a client put on the query is not in the body HPDS receives.
 * HPDS stores a result under the {@code picsureId} of the query it is given, so a value chosen by a client would let one caller replace the
 * result stored for another.
 *
 * <p>The whole service runs, with the real controllers, services and HPDS clients. WireMock stands in for the authorized and the open HPDS
 * backend and records what each receives. A Mockito {@link OperationsClient} stands in for the operations service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ClientSuppliedResultIdTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "auth0|alice";
    private static final String CLIENT_ID = "8694e3d4-5cb4-410f-8431-993445e6d3f6";
    private static final String QUERY_ID = "0b0e2f0c-6a55-4f0e-9a3c-2f4a3a1d9c11";
    private static final String HPDS_RESULT_ID = "5f0b6c1e-1d1f-4f6e-8a51-3f3a52b7c001";
    private static final String ASYNC_URL = "/PIC-SURE/v3/query";
    private static final String SYNC_URL = "/PIC-SURE/v3/query/sync";
    private static final String OPEN_SYNC_URL = "/v3/query/sync";
    private static final String CLAUSE =
        "{\"phenotypicFilterType\":\"FILTER\",\"conceptPath\":\"\\\\demographics\\\\SEX\\\\\",\"values\":[\"Female\"],"
            + "\"min\":null,\"max\":null,\"not\":null}";

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
        registry.add("aggregate.hpds-open-url", () -> "http://localhost:" + hpds.port());
        registry.add("aggregate.obfuscation.enabled", () -> false);
        registry.add("spring.cache.type", () -> "none");
        registry.add("consent.based.authorization.enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OperationsClient operationsClient;

    @BeforeEach
    void reset() {
        hpds.resetAll();
        hpds.stubFor(
            WireMock.post(urlEqualTo(ASYNC_URL))
                .willReturn(okJson("{\"resourceResultId\":\"" + HPDS_RESULT_ID + "\",\"status\":\"PENDING\"}"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo(SYNC_URL))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", HPDS_RESULT_ID).withBody("7"))
        );
        hpds.stubFor(
            WireMock.post(urlEqualTo(OPEN_SYNC_URL))
                .willReturn(aResponse().withStatus(200).withHeader("queryMetadata", HPDS_RESULT_ID).withBody("7"))
        );
        when(operationsClient.save(any())).thenReturn(UUID.randomUUID());
    }

    /** A request body as the frontend writes it, with the given JSON value on {@code picsureId}. */
    private static String body(String resultType, String picsureIdJson) {
        return "{\"query\":{\"select\":[\"\\\\demographics\\\\AGE\\\\\"],\"authorizationFilters\":[],\"phenotypicClause\":" + CLAUSE
            + ",\"genomicFilters\":[],\"expectedResultType\":\"" + resultType + "\",\"picsureId\":" + picsureIdJson + ",\"id\":\""
            + QUERY_ID + "\"}}";
    }

    private static String withClientId(String resultType) {
        return body(resultType, "\"" + CLIENT_ID + "\"");
    }

    private static String withoutClientId(String resultType) {
        return body(resultType, "null");
    }

    private MockHttpServletRequestBuilder submit(String path, String body) {
        return post(path).header(GatewayUserResolver.HEADER_USER_ID, USER).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** The one request HPDS received on a URL, parsed. */
    private static JsonNode received(String url) throws JsonProcessingException {
        List<ServeEvent> events = hpds.getAllServeEvents().stream().filter(event -> url.equals(event.getRequest().getUrl())).toList();
        assertThat(events).hasSize(1);
        return MAPPER.readTree(events.get(0).getRequest().getBodyAsString());
    }

    /** Asserts the body HPDS received keeps the query and carries no {@code picsureId} value. */
    private static void assertNoResultIdReachedHpds(JsonNode received, String resultType) {
        JsonNode query = received.get("query");
        assertThat(query.get("expectedResultType").asText()).isEqualTo(resultType);
        assertThat(query.path("picsureId").isNull() || query.path("picsureId").isMissingNode()).isTrue();
        assertThat(received.toString()).doesNotContain(CLIENT_ID);
    }

    @Test
    void authorizedSubmitDoesNotSendTheClientPicsureIdToHpds() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query", withClientId("DATAFRAME"))).andExpect(status().isOk());

        assertNoResultIdReachedHpds(received(ASYNC_URL), "DATAFRAME");
    }

    @Test
    void openSubmitDoesNotSendTheClientPicsureIdToHpds() throws Exception {
        mockMvc.perform(submit("/hpds/open/query", withClientId("COUNT"))).andExpect(status().isOk());

        assertNoResultIdReachedHpds(received(ASYNC_URL), "COUNT");
    }

    @Test
    void authorizedSyncDoesNotSendTheClientPicsureIdToHpds() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query/sync", withClientId("COUNT"))).andExpect(status().isOk());

        assertNoResultIdReachedHpds(received(SYNC_URL), "COUNT");
    }

    @Test
    void openSyncDoesNotSendTheClientPicsureIdToHpds() throws Exception {
        mockMvc.perform(submit("/hpds/open/query/sync", withClientId("COUNT"))).andExpect(status().isOk());

        assertNoResultIdReachedHpds(received(OPEN_SYNC_URL), "COUNT");
    }

    @Test
    void upgradeOfAStoredQueryDoesNotSendItsPicsureIdToHpds() throws Exception {
        UUID rowId = UUID.randomUUID();
        String storedV1 = "{\"query\":{\"expectedResultType\":\"COUNT\",\"picSureId\":\"" + CLIENT_ID
            + "\",\"categoryFilters\":{\"\\\\demographics\\\\SEX\\\\\":[\"Female\"]}}}";
        when(operationsClient.get(rowId)).thenReturn(new StoredQuery(rowId, storedV1, "rr-old", "AVAILABLE", null, null));
        hpds.stubFor(
            WireMock.post(urlEqualTo(ASYNC_URL + "/" + HPDS_RESULT_ID + "/status"))
                .willReturn(okJson("{\"resourceResultId\":\"" + HPDS_RESULT_ID + "\",\"status\":\"PENDING\"}"))
        );

        mockMvc.perform(get("/hpds/open/query/{id}/status", rowId).header(GatewayUserResolver.HEADER_USER_ID, USER))
            .andExpect(status().isOk());

        assertNoResultIdReachedHpds(received(ASYNC_URL), "COUNT");
        ArgumentCaptor<UpdateQueryRequest> patch = ArgumentCaptor.forClass(UpdateQueryRequest.class);
        verify(operationsClient, times(2)).update(eq(rowId), patch.capture());
        assertThat(patch.getAllValues().get(0).resourceResultId()).isEqualTo(HPDS_RESULT_ID);
    }

    @Test
    void submitStoresTheResultIdHpdsAnsweredWithAndNotTheClientPicsureId() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query", withClientId("DATAFRAME"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.resourceResultId").value(HPDS_RESULT_ID));

        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        assertThat(saved.getValue().resourceResultId()).isEqualTo(HPDS_RESULT_ID);
    }

    @Test
    void syncStoresTheResultIdHpdsAnsweredWithAndNotTheClientPicsureId() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query/sync", withClientId("COUNT"))).andExpect(status().isOk());

        ArgumentCaptor<SaveQueryRequest> saved = ArgumentCaptor.forClass(SaveQueryRequest.class);
        verify(operationsClient).save(saved.capture());
        assertThat(saved.getValue().resourceResultId()).isEqualTo(HPDS_RESULT_ID);
    }

    @Test
    void aQueryWithAClientPicsureIdReachesHpdsAsTheSameQueryWithoutOne() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query", withoutClientId("DATAFRAME"))).andExpect(status().isOk());
        JsonNode sentWithout = received(ASYNC_URL);
        hpds.resetRequests();

        mockMvc.perform(submit("/hpds/auth/query", withClientId("DATAFRAME"))).andExpect(status().isOk());

        assertThat(received(ASYNC_URL)).isEqualTo(sentWithout);
    }

    @Test
    void authorizedSubmitWithoutAPicsureIdReachesHpdsUnchanged() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query", withoutClientId("DATAFRAME"))).andExpect(status().isOk());

        JsonNode received = received(ASYNC_URL);
        assertThat(received.get("query")).isEqualTo(MAPPER.readTree(withoutClientId("DATAFRAME")).get("query"));
        assertThat(received.get("resourceUUID").isNull()).isTrue();
    }

    @Test
    void authorizedSyncWithoutAPicsureIdReachesHpdsUnchanged() throws Exception {
        mockMvc.perform(submit("/hpds/auth/query/sync", withoutClientId("COUNT"))).andExpect(status().isOk());

        assertThat(received(SYNC_URL).get("query")).isEqualTo(MAPPER.readTree(withoutClientId("COUNT")).get("query"));
    }

    @Test
    void openSyncWithoutAPicsureIdReachesHpdsUnchanged() throws Exception {
        mockMvc.perform(submit("/hpds/open/query/sync", withoutClientId("COUNT"))).andExpect(status().isOk());

        assertThat(received(OPEN_SYNC_URL).get("query")).isEqualTo(MAPPER.readTree(withoutClientId("COUNT")).get("query"));
    }
}
