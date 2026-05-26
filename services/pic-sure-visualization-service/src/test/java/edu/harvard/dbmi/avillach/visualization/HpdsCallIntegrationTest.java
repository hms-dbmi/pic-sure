package edu.harvard.dbmi.avillach.visualization;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HpdsCallIntegrationTest {

    private static final String AUTHORIZED_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OPEN_UUID = "550e8400-e29b-41d4-a716-446655440001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RestTemplate restTemplate;

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockRestServiceServer.createServer(restTemplate);
    }

    @Test
    void distributions_authorized_categoricalFilter_callsHpdsAndReturnsBarChart() throws Exception {
        // Simulate HPDS response for categorical cross-counts
        Map<String, Map<String, Integer>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000, "Asian", 8000)));

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        // Build a v3 query with a categorical filter
        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White", "Black")),
            "select", List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertFalse(response.charts().isEmpty());
        assertEquals("bar", response.charts().get(0).chartType());
        assertEquals("Variable distribution of demographics: race", response.charts().get(0).title());
        assertFalse(response.charts().get(0).isObfuscated());

        // Verify the Plotly trace structure
        Map<String, Object> trace = response.charts().get(0).traces().get(0);
        assertNotNull(trace.get("x"));
        assertNotNull(trace.get("y"));
        assertEquals("bar", trace.get("type"));
    }

    @Test
    void distributions_authorized_continuousFilter_callsHpdsAndReturnsHistogram() throws Exception {
        // Simulate HPDS response for continuous cross-counts (raw values, not yet binned)
        Map<String, Map<String, Integer>> hpdsResponse = new LinkedHashMap<>();
        Map<String, Integer> bmiValues = new LinkedHashMap<>();
        bmiValues.put("18.0", 100);
        bmiValues.put("22.0", 200);
        bmiValues.put("26.0", 150);
        bmiValues.put("30.0", 100);
        bmiValues.put("35.0", 50);
        hpdsResponse.put("\\measurements\\bmi\\", bmiValues);

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause", Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\measurements\\bmi\\", "min", 18.0, "max", 40.0),
            "select", List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertFalse(response.charts().isEmpty());
        assertEquals("histogram", response.charts().get(0).chartType());

        // Verify binning happened — x labels should be ranges, not raw values
        @SuppressWarnings("unchecked")
        List<String> xValues = (List<String>) response.charts().get(0).traces().get(0).get("x");
        assertFalse(xValues.contains("18.0"), "Raw value should have been binned into a range");

        // Total counts preserved
        @SuppressWarnings("unchecked")
        List<Integer> yValues = (List<Integer>) response.charts().get(0).traces().get(0).get("y");
        int total = yValues.stream().mapToInt(Integer::intValue).sum();
        assertEquals(600, total);
    }

    @Test
    void distributions_open_callsHpdsAndReturnsObfuscatedChart() throws Exception {
        // Simulate HPDS open response with obfuscation markers (string values)
        Map<String, Map<String, String>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", "45000±3", "Black", "12000", "Other", "< 10")));

        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", OPEN_UUID, "query", query));

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertFalse(response.charts().isEmpty());
        assertTrue(response.charts().get(0).isObfuscated());

        // Verify obfuscation markers were cleaned
        @SuppressWarnings("unchecked")
        List<Integer> yValues = (List<Integer>) response.charts().get(0).traces().get(0).get("y");
        // "< 10" should have become 9, "45000±3" should have become 45000
        assertTrue(yValues.contains(9));
        assertTrue(yValues.contains(45000));
    }

    @Test
    void distributions_hpdsReturns500_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withServerError().body("{\"error\":\"internal error\"}"));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isBadGateway()).andReturn();

        mockServer.verify();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("HPDS query failed"));
    }

    @Test
    void distributions_hpdsTimeout_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withServiceUnavailable());

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query));

        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isBadGateway());

        mockServer.verify();
    }
}
