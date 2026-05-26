package edu.harvard.dbmi.avillach.visualization.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.logging.LoggingClient;
import edu.harvard.dbmi.avillach.logging.LoggingEvent;
import edu.harvard.dbmi.avillach.visualization.error.VisualizationException;
import edu.harvard.dbmi.avillach.visualization.model.AccessType;
import edu.harvard.dbmi.avillach.visualization.model.DistributionType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.ResultType;
import edu.harvard.hms.dbmi.avillach.hpds.data.query.v3.Query;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class HpdsClientTest {

    private static final UUID RESOURCE_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private LoggingClient loggingClient;

    private MockRestServiceServer mockServer;
    private HpdsClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        client = new HpdsClient(restTemplate, loggingClient, "http://localhost:9999/mock-hpds");
    }

    @Test
    void getAuthCrossCounts_postsHpdsQueryRequest() throws Exception {
        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        expected.put("\\test\\", Map.of("a", 1));

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token")).andExpect(header("X-Request-Id", "request-1"))
            .andExpect(header("Accept", MediaType.ALL_VALUE))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"resourceUUID\":\"" + RESOURCE_UUID + "\"}"))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}"))
            .andExpect(content().json("{\"resourceCredentials\":{}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, Integer>> result = client.getAuthCrossCounts(
            query, ResultType.CATEGORICAL_CROSS_COUNT, RESOURCE_UUID, "Bearer token", "request-1", AccessType.AUTHORIZED,
            DistributionType.CATEGORICAL
        );

        assertEquals(expected, result);
        mockServer.verify();

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), eq("Bearer token"), eq("request-1"));
        LoggingEvent event = eventCaptor.getValue();
        assertEquals("QUERY", event.getEventType());
        assertEquals("visualization.hpds.query", event.getAction());
        assertEquals("request-1", event.getRequest().getRequestId());
        assertEquals(200, event.getRequest().getStatus());
        assertEquals("authorized", event.getMetadata().get("access_type"));
        assertEquals("categorical", event.getMetadata().get("distribution_kind"));
    }

    @Test
    void getAuthCrossCounts_logsHpdsResponseShape() throws Exception {
        Map<String, Integer> ageValues = new LinkedHashMap<>();
        ageValues.put("18.0", 2);
        ageValues.put("19.0", 3);

        Map<String, Map<String, Integer>> expected = new LinkedHashMap<>();
        expected.put("\\Nhanes\\demographics\\AGE\\", ageValues);

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer token")).andExpect(header("X-Request-Id", "request-2"))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of("\\Nhanes\\demographics\\AGE\\"), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, Integer>> result = client.getAuthCrossCounts(
            query, ResultType.CONTINUOUS_CROSS_COUNT, RESOURCE_UUID, "Bearer token", "request-2", AccessType.AUTHORIZED,
            DistributionType.CONTINUOUS
        );

        assertEquals(expected, result);
        mockServer.verify();

        ArgumentCaptor<LoggingEvent> eventCaptor = ArgumentCaptor.forClass(LoggingEvent.class);
        verify(loggingClient).send(eventCaptor.capture(), eq("Bearer token"), eq("request-2"));
        LoggingEvent event = eventCaptor.getValue();
        assertEquals(1, event.getMetadata().get("response_series_count"));
        assertEquals(2, event.getMetadata().get("response_point_count"));
        assertEquals(List.of("\\Nhanes\\demographics\\AGE\\"), event.getMetadata().get("response_series_keys"));
    }

    @Test
    void getAuthCrossCounts_withNoUUID_throwsVisualizationException() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationException ex = assertThrows(
            VisualizationException.class, () -> client.getAuthCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, null, "Bearer token")
        );
        assertTrue(ex.getMessage().contains("HPDS resource UUID is required"));
    }

    @Test
    void getOpenCrossCounts_postsHpdsQueryRequestWithoutAuthorizationHeader() throws Exception {
        Map<String, Map<String, String>> expected = new LinkedHashMap<>();
        expected.put("\\test\\", Map.of("a", "1"));

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(headerDoesNotExist("Authorization")).andExpect(header("Accept", MediaType.ALL_VALUE))
            .andExpect(content().json("{\"resourceUUID\":\"" + RESOURCE_UUID + "\"}"))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}"))
            .andExpect(content().json("{\"resourceCredentials\":{}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(expected), MediaType.APPLICATION_JSON));

        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);
        Map<String, Map<String, String>> result = client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, RESOURCE_UUID, null);

        assertEquals(expected, result);
        mockServer.verify();
    }

    @Test
    void getOpenCrossCounts_withNoUUID_throwsVisualizationException() {
        Query query = new Query(List.of(), List.of(), null, List.of(), null, null, null);

        VisualizationException ex = assertThrows(
            VisualizationException.class, () -> client.getOpenCrossCounts(query, ResultType.CATEGORICAL_CROSS_COUNT, null, null)
        );
        assertTrue(ex.getMessage().contains("HPDS resource UUID is required"));
    }
}
