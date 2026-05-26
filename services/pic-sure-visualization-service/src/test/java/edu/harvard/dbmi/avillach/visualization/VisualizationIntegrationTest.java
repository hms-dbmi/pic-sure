package edu.harvard.dbmi.avillach.visualization;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VisualizationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void querySync_withoutRequestSourceHeader_returns403() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        mockMvc.perform(post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
    }

    @Test
    void querySync_withRequestSourceHeader_returnsOk() throws Exception {
        // Empty query with no filters should return empty charts, not an error
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        MvcResult result = mockMvc.perform(
            post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized")
                .header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isOk()).andReturn();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertTrue(response.charts().isEmpty());
    }

    @Test
    void querySync_nullQuery_returns400() throws Exception {
        // {"query": null} should fail @NotNull validation
        String body = "{\"query\": null}";

        MvcResult result = mockMvc.perform(
            post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized")
                .content(body)
        ).andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("query"));
    }

    @Test
    void querySync_missingQueryField_returns400() throws Exception {
        // {} with no "query" key should fail @NotNull validation
        String body = "{}";

        MvcResult result = mockMvc.perform(
            post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized")
                .content(body)
        ).andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("query"));
    }

    @Test
    void querySync_malformedJson_returns400() throws Exception {
        String body = "not valid json";

        MvcResult result = mockMvc.perform(
            post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized")
                .content(body)
        ).andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("Malformed request body"));
    }

    @Test
    void querySync_emptyBody_returns400() throws Exception {
        mockMvc.perform(
            post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized").content("")
        ).andExpect(status().isBadRequest());
    }

    @Test
    void binContinuous_nullQueryField_returns400() throws Exception {
        String body = "{\"query\": null}";

        MvcResult result = mockMvc.perform(post("/visualization/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("error"));
    }

    @Test
    void binContinuous_invalidDataFormat_returns400() throws Exception {
        // query field is a string, not a map — should fail convertValue
        String body = "{\"query\": \"not a map\"}";

        MvcResult result = mockMvc.perform(post("/visualization/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("Could not parse continuous data"));
    }

    @Test
    void binContinuous_acceptsQueryRequestFormat() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "query", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100, "25.0", 200, "30.0", 150)), "resourceUUID",
            "550e8400-e29b-41d4-a716-446655440000", "resourceCredentials", Map.of()
        );

        String body = objectMapper.writeValueAsString(requestBody);

        MvcResult result = mockMvc.perform(post("/visualization/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertTrue(response.containsKey("\\measurements\\bmi\\"));
        assertFalse(response.get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void info_withRequestSourceHeader_returnsResourceInfo() throws Exception {
        MvcResult result = mockMvc.perform(
            post("/visualization/v3/info").contentType(MediaType.APPLICATION_JSON).header("request-source", "Authorized").content("{}")
        ).andExpect(status().isOk()).andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("PIC-SURE Visualization Service"));
    }
}
