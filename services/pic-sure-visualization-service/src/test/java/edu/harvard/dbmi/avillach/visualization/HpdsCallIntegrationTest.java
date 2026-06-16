package edu.harvard.dbmi.avillach.visualization;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HpdsCallIntegrationTest {

    private static final String AUTHORIZED_UUID =
        "550e8400-e29b-41d4-a716-446655440000";
    private static final String OPEN_UUID =
        "550e8400-e29b-41d4-a716-446655440001";

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
    void distributions_authorized_categoricalFilter_callsHpdsAndReturnsBarChart()
        throws Exception {
        // Simulate HPDS response for categorical cross-counts
        Map<String, Map<String, Integer>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(
                Map.of("White", 45000, "Black", 12000, "Asian", 8000)
            )
        );

        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-token"))
            .andExpect(
                content().json(
                    "{\"query\":{\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}"
                )
            )
            .andRespond(
                withSuccess(
                    objectMapper.writeValueAsString(hpdsResponse),
                    MediaType.APPLICATION_JSON
                )
            );

        // Build a v3 query with a categorical filter
        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of(
                "phenotypicFilterType",
                "FILTER",
                "conceptPath",
                "\\demographics\\race\\",
                "values",
                List.of("White", "Black")
            ),
            "select",
            List.of(),
            "authorizationFilters",
            List.of(),
            "genomicFilters",
            List.of()
        );
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query)
        );

        MvcResult result = mockMvc
            .perform(
                post("/distributions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer test-token")
                    .content(body)
            )
            .andExpect(status().isOk())
            .andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            VisualizationResponse.class
        );
        assertNotNull(response);
        assertFalse(response.categoricalData().isEmpty());
        assertEquals(
            "demographics: race",
            response.categoricalData().get(0).title()
        );
        assertFalse(response.categoricalData().get(0).obfuscated());

        // Verify the frontend distribution DTO structure
        assertNotNull(response.categoricalData().get(0).categoricalMap());
        assertFalse(
            response.categoricalData().get(0).categoricalMap().isEmpty()
        );
    }

    @Test
    void distributions_authorized_continuousFilter_callsHpdsAndReturnsHistogram()
        throws Exception {
        // Simulate HPDS response for continuous cross-counts (raw values, not yet binned)
        Map<String, Map<String, Integer>> hpdsResponse = new LinkedHashMap<>();
        Map<String, Integer> bmiValues = new LinkedHashMap<>();
        bmiValues.put("18.0", 100);
        bmiValues.put("22.0", 200);
        bmiValues.put("26.0", 150);
        bmiValues.put("30.0", 100);
        bmiValues.put("35.0", 50);
        hpdsResponse.put("\\measurements\\bmi\\", bmiValues);

        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(
                content().json(
                    "{\"query\":{\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}"
                )
            )
            .andRespond(
                withSuccess(
                    objectMapper.writeValueAsString(hpdsResponse),
                    MediaType.APPLICATION_JSON
                )
            );

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of(
                "phenotypicFilterType",
                "FILTER",
                "conceptPath",
                "\\measurements\\bmi\\",
                "min",
                18.0,
                "max",
                40.0
            ),
            "select",
            List.of(),
            "authorizationFilters",
            List.of(),
            "genomicFilters",
            List.of()
        );
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query)
        );

        MvcResult result = mockMvc
            .perform(
                post("/distributions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer test-token")
                    .content(body)
            )
            .andExpect(status().isOk())
            .andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(),
            VisualizationResponse.class
        );
        assertFalse(response.continuousData().isEmpty());

        // Verify binning happened — x labels should be ranges, not raw values
        assertFalse(
            response
                .continuousData()
                .get(0)
                .continuousMap()
                .containsKey("18.0"),
            "Raw value should have been binned into a range"
        );

        // Total counts preserved
        int total = response
            .continuousData()
            .get(0)
            .continuousMap()
            .values()
            .stream()
            .mapToInt(ObfuscatedCount::count)
            .sum();
        assertEquals(600, total);
    }

    @Test
    void distributions_open_callsHpdsAndReturnsObfuscatedChart()
        throws Exception {
        Map<String, Map<String, ObfuscatedCount>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(Map.of(
                "White", new ObfuscatedCount(45000, "45000 ±3", 3),
                "Black", new ObfuscatedCount(12000, "12000"),
                "Other", new ObfuscatedCount(0, "< 10", 9)
            ))
        );

        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")),
            "select", List.of(),
            "authorizationFilters", List.of(),
            "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", OPEN_UUID, "query", query));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(), VisualizationResponse.class
        );
        assertFalse(response.categoricalData().isEmpty());
        assertTrue(response.categoricalData().get(0).obfuscated());
        Map<String, ObfuscatedCount> race = response.categoricalData().get(0).categoricalMap();
        assertEquals(new ObfuscatedCount(45000, "45000 ±3", 3), race.get("White"));
        assertEquals(new ObfuscatedCount(12000, "12000"), race.get("Black"));
        assertEquals(new ObfuscatedCount(0, "< 10", 9), race.get("Other"));
    }

    @Test
    void distributions_hpdsReturns500_returns502BadGateway() throws Exception {
        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withServerError().body("{\"error\":\"internal error\"}")
            );

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of(
                "phenotypicFilterType",
                "FILTER",
                "conceptPath",
                "\\demographics\\race\\",
                "values",
                List.of("White")
            ),
            "select",
            List.of(),
            "authorizationFilters",
            List.of(),
            "genomicFilters",
            List.of()
        );
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query)
        );

        MvcResult result = mockMvc
            .perform(
                post("/distributions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer test-token")
                    .content(body)
            )
            .andExpect(status().isBadGateway())
            .andReturn();

        mockServer.verify();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("HPDS query failed"));
    }

    @Test
    void distributions_hpdsTimeout_returns502BadGateway() throws Exception {
        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/v3/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withServiceUnavailable());

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of(
                "phenotypicFilterType",
                "FILTER",
                "conceptPath",
                "\\demographics\\race\\",
                "values",
                List.of("White")
            ),
            "select",
            List.of(),
            "authorizationFilters",
            List.of(),
            "genomicFilters",
            List.of()
        );
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", query)
        );

        mockMvc
            .perform(
                post("/distributions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer test-token")
                    .content(body)
            )
            .andExpect(status().isBadGateway());

        mockServer.verify();
    }

    @Test
    void distributions_open_continuous_callsHpdsAndForwardsBinnedObfuscatedValues()
        throws Exception {
        Map<String, Map<String, ObfuscatedCount>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put(
            "\\measurements\\bmi\\",
            new LinkedHashMap<>(Map.of(
                "18.0 - 24.0", new ObfuscatedCount(600, "600 ±3", 3),
                "24.0 - 30.0", new ObfuscatedCount(0, "< 10", 9),
                "30.0 +", new ObfuscatedCount(150, "150 ±3", 3)
            ))
        );

        mockServer
            .expect(requestTo("http://localhost:9999/mock-hpds/query/sync"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(
                withSuccess(
                    objectMapper.writeValueAsString(hpdsResponse),
                    MediaType.APPLICATION_JSON
                )
            );

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of(
                "phenotypicFilterType", "FILTER",
                "conceptPath", "\\measurements\\bmi\\",
                "min", 18.0,
                "max", 40.0
            ),
            "select", List.of(),
            "authorizationFilters", List.of(),
            "genomicFilters", List.of()
        );
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", OPEN_UUID, "query", query)
        );

        MvcResult result = mockMvc
            .perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(), VisualizationResponse.class
        );
        assertFalse(response.continuousData().isEmpty());
        assertTrue(response.continuousData().get(0).obfuscated());
        Map<String, ObfuscatedCount> bmi = response.continuousData().get(0).continuousMap();
        assertEquals(new ObfuscatedCount(600, "600 ±3", 3), bmi.get("18.0 - 24.0"));
        assertEquals(new ObfuscatedCount(0, "< 10", 9), bmi.get("24.0 - 30.0"));
        assertEquals(new ObfuscatedCount(150, "150 ±3", 3), bmi.get("30.0 +"));
    }
}
