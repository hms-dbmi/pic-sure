package edu.harvard.dbmi.avillach.visualization;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.model.ObfuscatedCount;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import java.net.ConnectException;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(HpdsCallIntegrationTest.MockHpdsRestClient.class)
class HpdsCallIntegrationTest {

    @TestConfiguration
    static class MockHpdsRestClient {
        static final RestClient.Builder BUILDER = RestClient.builder();
        static final MockRestServiceServer SERVER = MockRestServiceServer.bindTo(BUILDER).build();

        @Bean
        @Primary
        RestClient mockBoundRestClient() {
            return BUILDER.build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String AUTH_SYNC_URL = "http://localhost:9999/mock-query-service/hpds/auth/v3/query/sync";
    private static final String OPEN_SYNC_URL = "http://localhost:9999/mock-query-service/hpds/open/v3/query/sync";

    private MockRestServiceServer mockServer;

    @BeforeEach
    void setUp() {
        mockServer = MockHpdsRestClient.SERVER;
        mockServer.reset();
    }

    @Test
    void distributions_authorized_categoricalFilter_callsHpdsAndReturnsBarChart() throws Exception {
        // Simulate HPDS response for categorical cross-counts
        Map<String, Map<String, Integer>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put("\\demographics\\race\\", new LinkedHashMap<>(Map.of("White", 45000, "Black", 12000, "Asian", 8000)));

        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/auth/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer test-token")).andExpect(header("X-User-Id", "test-user"))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CATEGORICAL_CROSS_COUNT\"}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        // Build a v3 query with a categorical filter
        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White", "Black")),
            "select", List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        String body = objectMapper.writeValueAsString(Map.of("query", query));

        MvcResult result = mockMvc.perform(
            post("/auth/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertFalse(response.categoricalData().isEmpty());
        assertEquals("demographics: race", response.categoricalData().get(0).title());
        assertFalse(response.categoricalData().get(0).obfuscated());

        // Verify the frontend distribution DTO structure
        assertNotNull(response.categoricalData().get(0).categoricalMap());
        assertFalse(response.categoricalData().get(0).categoricalMap().isEmpty());
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

        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/auth/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andExpect(content().json("{\"query\":{\"expectedResultType\":\"CONTINUOUS_CROSS_COUNT\"}}"))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause", Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\measurements\\bmi\\", "min", 18.0, "max", 40.0),
            "select", List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        String body = objectMapper.writeValueAsString(Map.of("query", query));

        MvcResult result = mockMvc.perform(
            post("/auth/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertFalse(response.continuousData().isEmpty());

        // Verify binning happened — x labels should be ranges, not raw values
        assertFalse(response.continuousData().get(0).continuousMap().containsKey("18.0"), "Raw value should have been binned into a range");

        // Total counts preserved
        int total = response.continuousData().get(0).continuousMap().values().stream().mapToInt(ObfuscatedCount::count).sum();
        assertEquals(600, total);
    }

    @Test
    void distributions_open_callsHpdsAndReturnsObfuscatedChart() throws Exception {
        Map<String, Map<String, ObfuscatedCount>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put(
            "\\demographics\\race\\",
            new LinkedHashMap<>(
                Map.of(
                    "White", new ObfuscatedCount(45000, "45000 ±3", 3), "Black", new ObfuscatedCount(12000, "12000"), "Other",
                    new ObfuscatedCount(0, "< 10", 9)
                )
            )
        );

        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/open/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        String body = objectMapper.writeValueAsString(Map.of("query", query));

        MvcResult result = mockMvc.perform(
            post("/open/distributions").contentType(MediaType.APPLICATION_JSON)
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertFalse(response.categoricalData().isEmpty());
        assertTrue(response.categoricalData().get(0).obfuscated());
        Map<String, ObfuscatedCount> race = response.categoricalData().get(0).categoricalMap();
        assertEquals(new ObfuscatedCount(45000, "45000 ±3", 3), race.get("White"));
        assertEquals(new ObfuscatedCount(12000, "12000"), race.get("Black"));
        assertEquals(new ObfuscatedCount(0, "< 10", 9), race.get("Other"));
    }

    @Test
    void distributions_hpdsReturns500_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/auth/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withServerError().body("{\"error\":\"internal error\"}"));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        String body = objectMapper.writeValueAsString(Map.of("query", query));

        MvcResult result = mockMvc.perform(
            post("/auth/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(body)
        ).andExpect(status().isBadGateway()).andReturn();

        mockServer.verify();

        String responseBody = result.getResponse().getContentAsString();
        // Names the hop that actually failed. This service no longer calls HPDS, so a message saying it did would send
        // an operator to the wrong container.
        assertTrue(responseBody.contains("Query service request failed"));
    }

    @Test
    void distributions_queryServiceConsentDenialReturns403() throws Exception {
        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/auth/v3/query/sync"))
            .andRespond(withStatus(org.springframework.http.HttpStatus.FORBIDDEN).body("{\"errorType\":\"consent_denied\"}"));

        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );

        mockMvc.perform(
            post("/auth/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(objectMapper.writeValueAsString(Map.of("query", query)))
        ).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("consent_denied"));

        mockServer.verify();
    }

    /**
     * A 503 from the query service is a status the service did not expect, so it takes the non-403 status branch and surfaces as a 502
     * whose message carries the downstream status.
     */
    @Test
    void distributions_queryServiceReturns503_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(AUTH_SYNC_URL)).andExpect(method(HttpMethod.POST)).andRespond(withServiceUnavailable());

        mockMvc.perform(authDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("Query service request failed with status 503: Service Unavailable"));

        mockServer.verify();
    }

    /**
     * A 4xx other than 403 from the query service is not a consent decision, so it is reported as a 502 carrying the downstream status
     * rather than as a 403.
     */
    @Test
    void distributions_queryServiceReturns404_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(AUTH_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.NOT_FOUND).body("{\"error\":\"Not found\"}"));

        mockMvc.perform(authDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("Query service request failed with status 404: Not Found"));

        mockServer.verify();
    }

    /**
     * An I/O failure on the query-service call (connection refused, reset, or read timeout) reaches the service as a
     * {@code ResourceAccessException} and surfaces as a 502 naming the I/O error.
     */
    @Test
    void distributions_queryServiceUnreachable_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(AUTH_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withException(new ConnectException("Connection refused")));

        mockMvc.perform(authDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error", startsWith("Query service request failed: I/O error on POST request for \"" + AUTH_SYNC_URL)))
            .andExpect(jsonPath("$.error", containsString("Connection refused")));

        mockServer.verify();
    }

    /**
     * A 200 from the query service whose body cannot be read as cross counts fails outside the HTTP and I/O branches, and the catch-all
     * wraps it as a 502 naming the extraction failure.
     */
    @Test
    void distributions_queryServiceReturnsUnreadableBody_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(AUTH_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("[\"not\", \"cross counts\"]", MediaType.APPLICATION_JSON));

        mockMvc.perform(authDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error", startsWith("Query service request failed: Error while extracting response")));

        mockServer.verify();
    }

    /** A 403 from the query service on the open path is reported as a consent denial, the same as on the auth path. */
    @Test
    void distributions_open_queryServiceConsentDenialReturns403() throws Exception {
        mockServer.expect(requestTo(OPEN_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{\"errorType\":\"consent_denied\"}"));

        mockMvc.perform(openDistributions()).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorType").value("consent_denied"));

        mockServer.verify();
    }

    /** A 500 from the query service on the open path surfaces as a 502 carrying the downstream status. */
    @Test
    void distributions_open_queryServiceReturns500_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(OPEN_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withServerError().body("{\"error\":\"internal error\"}"));

        mockMvc.perform(openDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("Query service request failed with status 500: Internal Server Error"));

        mockServer.verify();
    }

    /** An I/O failure on the open-path query-service call surfaces as a 502 naming the I/O error. */
    @Test
    void distributions_open_queryServiceUnreachable_returns502BadGateway() throws Exception {
        mockServer.expect(requestTo(OPEN_SYNC_URL)).andExpect(method(HttpMethod.POST))
            .andRespond(withException(new ConnectException("Connection refused")));

        mockMvc.perform(openDistributions()).andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error", startsWith("Query service request failed: I/O error on POST request for \"" + OPEN_SYNC_URL)));

        mockServer.verify();
    }

    /**
     * Builds an authorized-path distributions request for a single categorical filter, which decomposes into exactly one query-service
     * call.
     */
    private MockHttpServletRequestBuilder authDistributions() throws Exception {
        return post("/auth/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
            .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
            .content(singleCategoricalFilterBody());
    }

    /**
     * Builds an open-path distributions request for a single categorical filter, which decomposes into exactly one query-service call.
     */
    private MockHttpServletRequestBuilder openDistributions() throws Exception {
        return post("/open/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "OPEN_ACCESS:aio.local")
            .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN).content(singleCategoricalFilterBody());
    }

    private String singleCategoricalFilterBody() throws Exception {
        Map<String, Object> query = Map.of(
            "phenotypicClause",
            Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\demographics\\race\\", "values", List.of("White")), "select",
            List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        return objectMapper.writeValueAsString(Map.of("query", query));
    }

    @Test
    void distributions_open_continuous_callsHpdsAndForwardsBinnedObfuscatedValues() throws Exception {
        Map<String, Map<String, ObfuscatedCount>> hpdsResponse = new LinkedHashMap<>();
        hpdsResponse.put(
            "\\measurements\\bmi\\",
            new LinkedHashMap<>(
                Map.of(
                    "18.0 - 24.0", new ObfuscatedCount(600, "600 ±3", 3), "24.0 - 30.0", new ObfuscatedCount(0, "< 10", 9), "30.0 +",
                    new ObfuscatedCount(150, "150 ±3", 3)
                )
            )
        );

        mockServer.expect(requestTo("http://localhost:9999/mock-query-service/hpds/open/v3/query/sync")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess(objectMapper.writeValueAsString(hpdsResponse), MediaType.APPLICATION_JSON));

        Map<String, Object> query = Map.of(
            "phenotypicClause", Map.of("phenotypicFilterType", "FILTER", "conceptPath", "\\measurements\\bmi\\", "min", 18.0, "max", 40.0),
            "select", List.of(), "authorizationFilters", List.of(), "genomicFilters", List.of(), "expectedResultType", "COUNT"
        );
        String body = objectMapper.writeValueAsString(Map.of("query", query));

        MvcResult result = mockMvc.perform(
            post("/open/distributions").contentType(MediaType.APPLICATION_JSON)
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN).content(body)
        ).andExpect(status().isOk()).andReturn();

        mockServer.verify();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertFalse(response.continuousData().isEmpty());
        assertTrue(response.continuousData().get(0).obfuscated());
        Map<String, ObfuscatedCount> bmi = response.continuousData().get(0).continuousMap();
        assertEquals(new ObfuscatedCount(600, "600 ±3", 3), bmi.get("18.0 - 24.0"));
        assertEquals(new ObfuscatedCount(0, "< 10", 9), bmi.get("24.0 - 30.0"));
        assertEquals(new ObfuscatedCount(150, "150 ±3", 3), bmi.get("30.0 +"));
    }
}
