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

import java.util.List;
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

    /**
     * A bare v3 query the decomposer cannot chart: it carries a genomic filter (so it is not the empty query) but no select paths and no
     * phenotypic filters, so no sub-query is issued and no upstream call is made. That keeps these ingress tests about binding and access
     * type rather than about HPDS -- {@code HpdsCallIntegrationTest} owns the call itself.
     */
    private String genomicOnlyQuery() throws Exception {
        return objectMapper
            .writeValueAsString(Map.of("genomicFilters", List.of(Map.of("key", "Gene_with_variant", "values", List.of("APOE")))));
    }

    @Test
    void distributions_withAuthorizedAccessType_returnsOk() throws Exception {
        // The body IS the bare v3 query -- no wrapper. Access type comes from the gateway-owned X-Picsure-Access-Type
        // header; no hpdsResourceUUID needed (path-routed frontend omits it).
        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer test-token")
                .header("X-User-Id", "test-user").header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
                .content(genomicOnlyQuery())
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
        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "OPEN_ACCESS:aio.local")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_OPEN).content(genomicOnlyQuery())
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
        MvcResult result = mockMvc.perform(post("/distributions").contentType(MediaType.APPLICATION_JSON).content(genomicOnlyQuery()))
            .andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("X-Picsure-Access-Type"));
    }

    @Test
    void distributions_withUnrecognizedAccessType_returns400() throws Exception {
        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header(GatewayUserResolver.HEADER_ACCESS_TYPE, "superuser")
                .content(genomicOnlyQuery())
        ).andExpect(status().isBadRequest());
    }

    @Test
    void distributions_retiredQueryWrapper_returns400() throws Exception {
        // The wrapper is retired, and its own name is now the giveaway: the gateway's BodyMutationFilter replaces the
        // whole body with PSAMA's consent-mutated BARE query, so a body that still nests one under 'query' could never
        // survive the authorized path. Binding the bare Query makes that mismatch a 400 at the door instead of a
        // request that works unauthenticated and breaks the moment consent filtering kicks in.
        String body = objectMapper.writeValueAsString(Map.of("query", Map.of("select", List.of("\\demographics\\race\\"))));

        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "test-user")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
    }

    @Test
    void distributions_retiredHpdsResourceUUID_returns400() throws Exception {
        // The resource-registry selector is retired, not tolerated: it is not a field of the v3 Query the endpoint now
        // binds, and nothing opts out of strict deserialization, so a client still sending it is told so instead of
        // having the field silently dropped.
        String body = objectMapper.writeValueAsString(
            Map.of("hpdsResourceUUID", "550e8400-e29b-41d4-a716-446655440099", "select", List.of("\\demographics\\race\\"))
        );

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
        String body = objectMapper.writeValueAsString(Map.of("resourceCredentials", Map.of(), "select", List.of("\\demographics\\race\\")));

        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON).header("X-User-Id", "test-user")
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void distributions_emptyQueryObject_returns400() throws Exception {
        // '{}' binds an all-null Query, which is not a request this endpoint can answer: the decomposer reads only
        // select and the phenotypic filters, so with neither there is nothing to chart and the response would be an
        // unconditional empty 200 -- a success shape for a request that asked for nothing. Say so instead.
        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON)
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content("{}")
        ).andExpect(status().isBadRequest()).andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertTrue(responseBody.contains("select"), responseBody);
        assertTrue(responseBody.contains("filter"), responseBody);
    }

    @Test
    void distributions_queryWithOnlyIgnoredFields_returns400() throws Exception {
        // expectedResultType is NOT what makes a query answerable here: the decomposer overwrites it per sub-query
        // (CATEGORICAL_CROSS_COUNT / CONTINUOUS_CROSS_COUNT), so a body carrying only that is as empty as '{}'.
        String body = objectMapper.writeValueAsString(Map.of("expectedResultType", "COUNT", "select", List.of()));

        mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON)
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED).content(body)
        ).andExpect(status().isBadRequest());
    }

    @Test
    void distributions_absentBody_returns400() throws Exception {
        MvcResult result = mockMvc.perform(
            post("/distributions").contentType(MediaType.APPLICATION_JSON)
                .header(GatewayUserResolver.HEADER_ACCESS_TYPE, GatewayUserResolver.ACCESS_TYPE_AUTHORIZED)
        ).andExpect(status().isBadRequest()).andReturn();

        assertTrue(result.getResponse().getContentAsString().contains("Malformed request body"));
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
        // The advertised format is the BARE v3 query's own fields. A 'query' key here would tell clients to send a
        // wrapper the endpoint rejects -- and one the gateway's consent mutation strips off anyway.
        assertFalse(format.specification().containsKey("query"), format.specification().toString());
        assertTrue(format.specification().containsKey("select"));
        assertTrue(format.specification().containsKey("phenotypicClause"));
        // hpdsResourceUUID is no longer part of the request: the backend is chosen by X-Picsure-Access-Type and the
        // path query-service is called on. Advertising it would tell clients to send a field nothing reads.
        assertFalse(result.getResponse().getContentAsString().contains("hpdsResourceUUID"));
    }
}
