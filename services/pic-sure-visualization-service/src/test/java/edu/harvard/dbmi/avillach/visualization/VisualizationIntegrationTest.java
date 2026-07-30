package edu.harvard.dbmi.avillach.visualization;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.dbmi.avillach.contracts.info.QueryFormat;
import edu.harvard.dbmi.avillach.contracts.info.ResourceInfo;
import edu.harvard.dbmi.avillach.visualization.model.BinnedDistribution;
import edu.harvard.dbmi.avillach.visualization.model.VisualizationResponse;
import edu.harvard.hms.dbmi.avillach.commons.identity.GatewayUserResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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
// Actuator is OFF BY DEFAULT (exposure defaults to 'none'); expose health so healthEndpoint_isPublic exercises the
// endpoint the AIO deployment enables via PICSURE_ACTUATOR_EXPOSURE=health.
@TestPropertySource(properties = {"management.endpoints.web.exposure.include=health"})
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
    void distributions_withAuthorizedAccessType_returnsOk() throws Exception {
        // Access type comes from the gateway-owned X-Picsure-Access-Type header; no hpdsResourceUUID needed (path-routed frontend omits
        // it).
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(body)
        ).andExpect(status().isOk()).andReturn();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertTrue(response.categoricalData().isEmpty());
        assertTrue(response.continuousData().isEmpty());
    }

    @Test
    void distributions_withOpenAccessType_returnsOk() throws Exception {
        // Open-access requests carry the gateway's OPEN_ACCESS:<host> marker in X-User-Id. That marker is non-blank, so
        // the access type must come from the dedicated header -- reading identity presence would classify this as authorized.
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "OPEN_ACCESS:aio.local")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN).content(body)
        ).andExpect(status().isOk()).andReturn();

        VisualizationResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), VisualizationResponse.class);
        assertNotNull(response);
        assertTrue(response.categoricalData().isEmpty());
        assertTrue(response.continuousData().isEmpty());
    }

    @Test
    void distributions_withoutAccessTypeHeader_returns400() throws Exception {
        // Fail closed: the header is absent only when the request bypassed the gateway auth chain, so there is no
        // trustworthy access type. Neither default is safe, so neither is taken.
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("X-Picsure-Access-Type"));
    }

    @Test
    void distributions_withUnrecognizedAccessType_returns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of()));

        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header(GatewayUserResolver.HEADER_ACCESS_TYPE, "superuser")
                .content(body)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void distributions_retiredHpdsResourceUUID_returns400() throws Exception {
        // The resource-registry selector is retired, not tolerated: the request model no longer opts out of strict
        // deserialization, so a client still sending it is told so instead of having the field silently dropped.
        String body =
            objectMapper.writeValueAsString(Map.of("hpdsResourceUUID", "550e8400-e29b-41d4-a716-446655440099", "query", Map.of()));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "test-user")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void distributions_retiredResourceCredentials_returns400() throws Exception {
        // The v1 envelope's other half. /distributions never read it; with the envelope gone from every hop, sending it
        // is a client-version mismatch worth surfacing.
        String body = objectMapper.writeValueAsString(Map.of("resourceCredentials", Map.of(), "query", Map.of()));

        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "test-user")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void distributions_nullQuery_returns400() throws Exception {
        String body = "{\"query\":null}";

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
    void binContinuous_nullContinuousDataField_returns400() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content("{\"continuousData\": null}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("error"));
    }

    @Test
    void binContinuous_invalidDataFormat_returns400() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content("{\"continuousData\": \"not a map\"}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void binContinuous_rejectsRawDataFormat() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("\\measurements\\bmi\\", Map.of("18.0", 100)));

        mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }

    @Test
    void binContinuous_retiredQueryFieldName_returns400() throws Exception {
        // The counts field was misnamed 'query' (it is per-concept value counts, not a query). The old name is not an
        // accepted alias: a caller on the old name gets told, rather than silently binning nothing.
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100))));

        MvcResult result = mockMvc.perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void binContinuous_retiredEnvelopeFields_returns400() throws Exception {
        // resourceUUID/resourceCredentials came from the v1 QueryRequest envelope this endpoint never read. With the
        // strictness opt-out gone, they are rejected rather than ignored.
        Map<String, Object> requestBody = Map.of(
            "continuousData", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100)), "resourceUUID", "550e8400-e29b-41d4-a716-446655440000",
            "resourceCredentials", Map.of()
        );

        MvcResult result = mockMvc
            .perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void binContinuous_returnsBinsUnderTheNamedWrapper() throws Exception {
        Map<String, Object> requestBody =
            Map.of("continuousData", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100, "25.0", 200, "30.0", 150)));

        MvcResult result = mockMvc
            .perform(post("/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody)))
            .andExpect(status().isOk()).andReturn();

        BinnedDistribution response = objectMapper.readValue(result.getResponse().getContentAsString(), BinnedDistribution.class);
        assertTrue(response.bins().containsKey("\\measurements\\bmi\\"));
        assertFalse(response.bins().get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void binContinuous_v3RouteReturnsBinsUnderTheNamedWrapper() throws Exception {
        Map<String, Object> requestBody =
            Map.of("continuousData", Map.of("\\measurements\\bmi\\", Map.of("18.0", 100, "25.0", 200, "30.0", 150)));

        MvcResult result = mockMvc.perform(
            post("/v3/bin/continuous").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(requestBody))
        ).andExpect(status().isOk()).andReturn();

        BinnedDistribution response = objectMapper.readValue(result.getResponse().getContentAsString(), BinnedDistribution.class);
        assertTrue(response.bins().containsKey("\\measurements\\bmi\\"));
        assertFalse(response.bins().get("\\measurements\\bmi\\").isEmpty());
    }

    @Test
    void info_returnsSharedResourceInfoContract() throws Exception {
        MvcResult result =
            mockMvc.perform(post("/info").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk()).andReturn();

        // The shared contracts.info record, not viz's own /info schema: one /info shape repo-wide.
        ResourceInfo info = objectMapper.readValue(result.getResponse().getContentAsString(), ResourceInfo.class);
        assertEquals("PIC-SURE Visualization Service", info.name());
        assertEquals(1, info.queryFormats().size());
        assertEquals("PIC-SURE Visualization Distributions", info.queryFormats().get(0).name());
    }

    @Test
    void queryFormat_returnsDistributionFormat() throws Exception {
        MvcResult result = mockMvc.perform(post("/query/format").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk()).andReturn();

        QueryFormat format = objectMapper.readValue(result.getResponse().getContentAsString(), QueryFormat.class);
        assertEquals("Request format for POST /distributions", format.description());
        assertTrue(format.specification().containsKey("query"));
        // hpdsResourceUUID is no longer part of the request: the backend is chosen by X-Picsure-Access-Type and the
        // path query-service is called on. Advertising it would tell clients to send a field nothing reads.
        assertFalse(result.getResponse().getContentAsString().contains("hpdsResourceUUID"));
    }
}
