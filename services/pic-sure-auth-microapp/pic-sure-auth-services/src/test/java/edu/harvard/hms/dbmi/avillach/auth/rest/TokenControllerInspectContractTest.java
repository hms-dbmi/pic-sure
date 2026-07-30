package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionRequest;
import edu.harvard.dbmi.avillach.contracts.auth.IntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.TokenIntrospectionResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.TokenService;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * The wire contract of {@code POST /token/inspect}, exercised through the real Jackson binding rather than the service API. <p> The mapper
 * here is deliberately {@code new ObjectMapper()} because that is exactly what PSAMA's {@code ApplicationConfig#objectMapper} bean is: a
 * user-defined ObjectMapper bean beats Spring Boot's auto-configured one, so the MVC message converter is NOT the Boot-relaxed mapper other
 * services get. Its defaults -- notably {@code FAIL_ON_UNKNOWN_PROPERTIES=true} -- are what the endpoint actually enforces.
 */
class TokenControllerInspectContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String V3_QUERY_BODY = """
        {"token":"user-token","request":{"Target Service":"/hpds/auth/v3/query",\
        "query":{"expectedResultType":"COUNT","select":["\\\\demographics\\\\SEX\\\\"]}}}""";

    private TokenService tokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TokenController(tokenService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    /** The flat {Target Service, query} shape the gateway sends binds straight onto the contract record. */
    @Test
    void bindsTheFlatGatewayShapeOntoTheContract() throws Exception {
        when(tokenService.inspectToken(any())).thenReturn(new TokenIntrospectionResponse(granted(null)));

        mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY)).andExpect(status().isOk());

        ArgumentCaptor<IntrospectionRequest> captor = ArgumentCaptor.forClass(IntrospectionRequest.class);
        verify(tokenService).inspectToken(captor.capture());
        IntrospectionRequest bound = captor.getValue();

        assertEquals("user-token", bound.token());
        assertEquals("/hpds/auth/v3/query", bound.request().targetService());
        assertTrue(bound.request().query().isObject(), "the v3 body must bind as an object node");
        assertEquals("COUNT", bound.request().query().get("expectedResultType").asText());
    }

    /**
     * The consent-mutated query goes out as a JSON OBJECT. Emitting it as an escaped string -- which PSAMA did until the contract landed --
     * leaves {@code $.query} matching while {@code $.query.<field>} silently stops resolving.
     */
    @Test
    void mutatedQueryIsAnObjectNodeNotAString() throws Exception {
        JsonNode mutated = MAPPER.readTree("{\"expectedResultType\":\"COUNT\",\"authorizationFilters\":[{\"consent\":\"phs000001.c1\"}]}");
        when(tokenService.inspectToken(any())).thenReturn(new TokenIntrospectionResponse(granted(mutated)));

        MvcResult result = mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY))
            .andExpect(status().isOk()).andReturn();

        IntrospectionResponse response = MAPPER.readValue(result.getResponse().getContentAsString(), IntrospectionResponse.class);
        assertTrue(response.query().isObject(), "query must serialize as an object, never as an escaped string");
        assertEquals("COUNT", response.query().get("expectedResultType").asText());
        assertFalse(result.getResponse().getContentAsString().contains("\"query\":\""), "query was serialized as a string");
    }

    /** Roles go out as a real JSON array, not the comma-joined string every consumer had to split back apart. */
    @Test
    void rolesAreAJsonArray() throws Exception {
        when(tokenService.inspectToken(any())).thenReturn(new TokenIntrospectionResponse(granted(null)));

        MvcResult result =
            mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY)).andExpect(status().isOk())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("roles").isArray(), "roles must be a JSON array: " + body.get("roles"));
        assertEquals(List.of("MANAGED_ROLE", "PIC-SURE Top Admin"), MAPPER.readValue(result.getResponse().getContentAsString(),
            IntrospectionResponse.class).roles());
    }

    /**
     * PSAMA has always explained a denial with an unmodelled {@code message}. The contract does not model it -- nothing may branch on its
     * wording -- but it still has to reach the wire, and the tolerant contract reader has to survive it.
     */
    @Test
    void denialKeepsItsMessageAndStillReadsAsTheContract() throws Exception {
        when(tokenService.inspectToken(any())).thenReturn(TokenIntrospectionResponse.denied("user doesn't exist"));

        MvcResult result =
            mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY)).andExpect(status().isOk())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("user doesn't exist", body.get("message").asText());
        assertFalse(body.get("active").asBoolean());

        IntrospectionResponse response = MAPPER.readValue(result.getResponse().getContentAsString(), IntrospectionResponse.class);
        assertFalse(response.active());
        assertNull(response.userId());
    }

    /** The user UUID is emitted under the contract's own name; its {@code uuid} alias keeps older readers working. */
    @Test
    void userIdIsEmittedAndReadableUnderBothNames() throws Exception {
        when(tokenService.inspectToken(any())).thenReturn(new TokenIntrospectionResponse(granted(null)));

        MvcResult result =
            mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY)).andExpect(status().isOk())
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("11111111-2222-3333-4444-555555555555", body.get("userId").asText());

        IntrospectionResponse viaAlias =
            MAPPER.readValue("{\"active\":true,\"uuid\":\"11111111-2222-3333-4444-555555555555\"}", IntrospectionResponse.class);
        assertEquals("11111111-2222-3333-4444-555555555555", viaAlias.userId());
    }

    /**
     * Documents what the strict record binding actually does under PSAMA's own mapper today: an unknown top-level key is REJECTED with a
     * 400, because {@code ApplicationConfig} publishes a bare {@code new ObjectMapper()} and Spring Boot's relaxed mapper never applies. If
     * PSAMA later adopts pic-sure-spring-commons this stays a 400 by intent -- the strict-deserialization customizer enforces the same
     * thing deliberately.
     */
    @Test
    void unknownTopLevelFieldIsRejected() throws Exception {
        String withExtra = "{\"token\":\"user-token\",\"request\":{\"Target Service\":\"/x\"},\"formattedQuery\":\"leftover\"}";

        mockMvc.perform(post("/token/inspect").contentType("application/json").content(withExtra)).andExpect(status().isBadRequest());
    }

    /** Audit coverage survives the retype: the resource label now comes from the path, since v3 bodies carry no resourceUUID. */
    @Test
    void auditMetadataIsDerivedFromTheTargetServicePath() throws Exception {
        when(tokenService.inspectToken(any())).thenReturn(new TokenIntrospectionResponse(granted(null)));

        MvcResult result =
            mockMvc.perform(post("/token/inspect").contentType("application/json").content(V3_QUERY_BODY)).andExpect(status().isOk())
                .andReturn();

        assertEquals("granted", result.getRequest().getAttribute("audit.ctx.authz_result"));
        assertEquals("/hpds/auth/v3/query", result.getRequest().getAttribute("audit.ctx.target_service"));
        assertEquals("hpds", result.getRequest().getAttribute("audit.ctx.resource_id"));
        assertEquals("false", result.getRequest().getAttribute("audit.ctx.authz_token_refreshed"));
    }

    private static IntrospectionResponse granted(JsonNode query) {
        return new IntrospectionResponse(
            true, "11111111-2222-3333-4444-555555555555", "fence|user", "user@example.org", List.of("MANAGED_ROLE", "PIC-SURE Top Admin"),
            List.of("QUERY"), false, null, query
        );
    }
}
