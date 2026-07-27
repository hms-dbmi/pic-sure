package edu.harvard.dbmi.avillach.visualization.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.visualization.model.DistributionType;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.AuthorizationFilter;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(MockitoExtension.class)
class QueryServiceClientTest {

    private static final String BASE_URL = "http://localhost:9999/mock-query-service";

    private static final QueryServiceClient.GatewayIdentity IDENTITY =
        new QueryServiceClient.GatewayIdentity("u-1", "sub-1", "a@b", "ROLE_X", "PRIV_A,PRIV_B");

    private static final QueryServiceClient.GatewayIdentity OPEN_IDENTITY =
        new QueryServiceClient.GatewayIdentity("OPEN_ACCESS:predev.example.org", null, null, "", "");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LoggingClient loggingClient;

    private MockRestServiceServer mockServer;
    private QueryServiceClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new QueryServiceClient(builder.build(), loggingClient, BASE_URL);
    }

    @Test
    void getAuthCrossCounts_postsToAuthBackendWithIdentityHeaders() throws Exception {
        // query-service gates /hpds/** behind .authenticated(), which its GatewayPrivilegesFilter satisfies only when
        // X-User-Id is present -- so the gateway's identity must be forwarded, not dropped.
        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        expected.put("\\test\\", Map.of("a", 1));

        mockServer.expect(requestTo(BASE_URL + "/hpds/auth/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", "u-1")).andExpect(header("X-User-Subject", "sub-1")).andExpect(header("X-User-Email", "a@b"))
            .andExpect(header("X-User-Roles", "ROLE_X")).andExpect(header("X-User-Privileges", "PRIV_A,PRIV_B"))
            .andExpect(header("X-Request-Id", "request-1")).andExpect(header("Accept", MediaType.ALL_VALUE))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}"))
            .andExpect(content().json("{\"resourceCredentials\":{}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, Integer>> result =
            client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, IDENTITY, "request-1", DistributionType.CATEGORICAL);

        assertEquals(expected, result);
        mockServer.verify();
    }

    @Test
    void getOpenCrossCounts_postsToOpenBackend() throws Exception {
        // The open path goes to AggregateV3Controller, which applies threshold/variance obfuscation before answering.
        Map<String, Map<String, ObfuscatedCount>> expected = new LinkedHashMap<>();
        expected.put("\\test\\", Map.of("a", new ObfuscatedCount(1, "1")));

        mockServer.expect(requestTo(BASE_URL + "/hpds/open/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-User-Id", "OPEN_ACCESS:predev.example.org"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, ObfuscatedCount>> result =
            client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, OPEN_IDENTITY, "request-2", DistributionType.CATEGORICAL);

        assertEquals(expected, result);
        mockServer.verify();
    }

    @Test
    void blankIdentityComponentsAreNotSentAsHeaders() throws Exception {
        mockServer.expect(requestTo(BASE_URL + "/hpds/open/v3/query/sync")).andExpect(headerDoesNotExist("X-User-Subject"))
            .andExpect(headerDoesNotExist("X-User-Email")).andExpect(headerDoesNotExist("X-User-Roles"))
            .andExpect(headerDoesNotExist("X-User-Privileges")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, OPEN_IDENTITY, "request-3", DistributionType.CATEGORICAL);

        mockServer.verify();
    }

    @Test
    void outboundBodyCarriesNoResourceUuid() throws Exception {
        // The removed resource registry's selector. query-service picks its backend from the path segment and only
        // echoes this field back, so sending it is dead weight that reads as if it still routed something.
        mockServer.expect(requestTo(BASE_URL + "/hpds/auth/v3/query/sync")).andExpect(jsonPath("$.resourceUUID").doesNotExist())
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, IDENTITY, "request-4", DistributionType.CATEGORICAL);

        mockServer.verify();
    }

    @Test
    void authorizationFiltersSurviveIntoTheSubQuery() throws Exception {
        // REGRESSION GUARD (predev 502): PSAMA injects consent filters and the gateway's BodyMutationFilter swaps them
        // into the body before this service sees it. Auth HPDS defaults hpds.requireAuthorizationFilter=true, so
        // dropping them here makes every authorized distribution fail with "Authorization filter is required".
        mockServer.expect(requestTo(BASE_URL + "/hpds/auth/v3/query/sync"))
            .andExpect(jsonPath("$.query.authorizationFilters[0].conceptPath").value("\\_consents\\"))
            .andExpect(jsonPath("$.query.authorizationFilters[0].values[0]").value("phs000001.c1"))
            .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        Query query = new Query(
            List.of(), List.of(new AuthorizationFilter("\\_consents\\", Set.of("phs000001.c1"))), null, List.of(), null, null, null
        );
        client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, IDENTITY, "request-5", DistributionType.CATEGORICAL);

        mockServer.verify();
    }

    @Test
    void auditEventNamesQueryService() throws Exception {
        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        expected.put("\\test\\", Map.of("a", 1));

        mockServer.expect(requestTo(BASE_URL + "/hpds/auth/v3/query/sync"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, IDENTITY, "request-6", DistributionType.CATEGORICAL);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("request-6"));
        LoggingEvent event = eventCaptor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("visualization.query-service.query", event.getAction());
        // The destination recorded must be the hop actually made, not the HPDS hop this service no longer performs.
        assertEquals("/hpds/auth/v3/query/sync", event.getRequest().getUrl());
        assertEquals("localhost", event.getRequest().getDestIp());
        assertEquals(9999, event.getRequest().getDestPort());
        assertEquals(200, event.getRequest().getStatus());
        assertEquals("authorized", event.getMetadata().get("access_type"));
        assertEquals("categorical", event.getMetadata().get("distribution_kind"));
        assertNull(event.getMetadata().get("resource_uuid"));
    }

    @Test
    void responseShapeIsLoggedForObservability() throws Exception {
        Map<String, Integer> ageValues = new LinkedHashMap<>();
        ageValues.put("18.0", 2);
        ageValues.put("19.0", 3);

        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        expected.put("\\Nhanes\\demographics\\AGE\\", ageValues);

        mockServer.expect(requestTo(BASE_URL + "/hpds/auth/v3/query/sync"))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of("\\Nhanes\\demographics\\AGE\\"), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, Integer>> result =
            client.getAuthCrossCounts(query, ResultType.CONTINUOUS_CROSS_COUNT, IDENTITY, "request-7", DistributionType.CONTINUOUS);

        assertEquals(expected, result);

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), isNull(), eq("request-7"));
        LoggingEvent event = eventCaptor.getValue();
        assertEquals(1, event.getMetadata().get("response_series_count"));
        assertEquals(2, event.getMetadata().get("response_point_count"));
        assertEquals(List.of("\\Nhanes\\demographics\\AGE\\"), event.getMetadata().get("response_series_keys"));
    }
}
