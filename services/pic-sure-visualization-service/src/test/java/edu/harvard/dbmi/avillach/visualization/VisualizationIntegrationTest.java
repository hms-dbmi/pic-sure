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

    private static final String AUTHORIZED_UUID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OPEN_UUID = "550e8400-e29b-41d4-a716-446655440001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthEndpoint_isPublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void distributions_withAuthorizedHpdsUUID_returnsOk() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", AUTHORIZED_UUID, "query", Map.of()));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token").content(body)
        ).andExpect(status().isOk()).andReturn();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertTrue(response.categoricalData().isEmpty());
        assertTrue(response.continuousData().isEmpty());
    }

    @Test
    void distributions_withOpenHpdsUUID_returnsOk() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", OPEN_UUID, "query", Map.of()));

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk()).andReturn();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertTrue(response.categoricalData().isEmpty());
        assertTrue(response.continuousData().isEmpty());
    }

    @Test
    void distributions_missingHpdsResourceUUID_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("hpdsResourceUUID"));
    }

    @Test
    void distributions_unknownHpdsResourceUUID_returns400() throws Exception {
        String body =
            objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", "550e8400-e29b-41d4-a716-446655440099", "query", Map.of()));

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Unsupported HPDS resource UUID"));
    }

    @Test
    void distributions_nullQuery_returns400() throws Exception {
        String body = "{\"hpdsResourceUUID\":\"" + AUTHORIZED_UUID + "\",\"query\":null}";

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("query"));
    }

    @Test
    void distributions_malformedJson_returns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content("not valid json"))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void oldVisualizationV3Routes_areRemoved() throws Exception {
        mockMvc.perform(post("/visualization/v3/query/sync").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/visualization/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/visualization/v3/info").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
    }

    @Test
    void binContinuous_nullQueryField_returns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content("{\"query\": null}"))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("error"));
    }

    @Test
    void binContinuous_invalidDataFormat_returns400() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content("{\"query\": \"not a map\"}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void binContinuous_rejectsRawDataFormat() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("\\measurements\\bmi\\", Map.of("18.0", 100)));

        mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void binContinuous_acceptsQueryRequestFormat() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "query", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100, "25.0", 200, "30.0", 150)), "resourceUUID",
            "550e8400-e29b-41d4-a716-446655440000", "resourceCredentials", Map.of()
        );

        MvcResult result = mockMvc
            .perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertTrue(response.containsKey("\\measurements\\bmi\\"));
        assertFalse(response.get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void binContinuous_v3RouteAcceptsQueryRequestFormat() throws Exception {
        Map<String, Object> requestBody = Map.of(
            "query", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100, "25.0", 200, "30.0", 150)), "resourceUUID",
            "550e8400-e29b-41d4-a716-446655440000", "resourceCredentials", Map.of()
        );

        MvcResult result = mockMvc
            .perform(post("/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isOk()).andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertTrue(response.containsKey("\\measurements\\bmi\\"));
        assertFalse(response.get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void info_returnsResourceInfo() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/info").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("PIC-SURE Visualization Service"));
        assertTrue(content.contains("queryFormats"));
    }

    @Test
    void queryFormat_returnsDistributionFormat() throws Exception {
        MvcResult result = mockMvc.perform(post("/query/format").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("POST /distributions"));
        assertTrue(content.contains("hpdsResourceUUID"));
    }
}
