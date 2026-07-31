package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.auth.TargetedRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.OpenAccessValidationRequest;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;

/**
 * The wire contract of {@code POST /open/validate}. This is the unauthenticated path, so the binding rules matter more here than anywhere
 * else on PSAMA's surface: a body PSAMA refuses is a denial, and a denial is an outage for open-access users.
 */
class OpenAccessControllerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GATEWAY_BODY = """
        {"request":{"Target Service":"/hpds/open/v3/query","query":{"expectedResultType":"COUNT"}},\
        "ipAddress":"OPEN_ACCESS:my.host"}""";

    private MockMvc mockMvc(AuthorizationService authorizationService, boolean openIdpEnabled) {
        return MockMvcBuilders.standaloneSetup(new OpenAccessController(authorizationService, openIdpEnabled))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    @Test
    void bindsTheGatewayBodyOntoTheTypedRequest() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);

        mockMvc(authorizationService, true).perform(post("/open/validate").contentType("application/json").content(GATEWAY_BODY))
            .andExpect(status().isOk());

        ArgumentCaptor<OpenAccessValidationRequest> captor = ArgumentCaptor.forClass(OpenAccessValidationRequest.class);
        verify(authorizationService).openAccessRequestIsValid(captor.capture());
        OpenAccessValidationRequest bound = captor.getValue();
        assertEquals("/hpds/open/v3/query", bound.request().targetService());
        assertEquals("COUNT", bound.request().query().get("expectedResultType").asText());
        assertEquals("OPEN_ACCESS:my.host", bound.ipAddress());
    }

    /**
     * The endpoint used to bind {@code Map<String, Object>}, which cannot have an unknown property. Under PSAMA's strict ObjectMapper the
     * typed record has to opt back into tolerance or a client sending one extra key would take open access down.
     */
    @Test
    void unknownTopLevelKeysAreToleratedRatherThanRejected() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);
        String body = """
            {"request":{"Target Service":"/hpds/open/v3/query"},"ipAddress":"OPEN_ACCESS:my.host",\
            "someFutureKey":"whatever"}""";

        mockMvc(authorizationService, true).perform(post("/open/validate").contentType("application/json").content(body))
            .andExpect(status().isOk());
    }

    /**
     * Tolerance at the outer level alone is not tolerance: an unknown key INSIDE {@code request} would fail the nested bind and deny the
     * request just as surely. {@code resourceUUID} is not hypothetical -- the legacy WAR sent it in exactly this position.
     */
    @Test
    void unknownKeysNestedInsideRequestAreAlsoTolerated() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);
        String body = """
            {"request":{"Target Service":"/hpds/open/v3/query","resourceUUID":"8f8b0-1","query":{"expectedResultType":"COUNT"}},\
            "ipAddress":"OPEN_ACCESS:my.host"}""";

        mockMvc(authorizationService, true).perform(post("/open/validate").contentType("application/json").content(body))
            .andExpect(status().isOk());

        ArgumentCaptor<OpenAccessValidationRequest> captor = ArgumentCaptor.forClass(OpenAccessValidationRequest.class);
        verify(authorizationService).openAccessRequestIsValid(captor.capture());
        assertEquals("/hpds/open/v3/query", captor.getValue().request().targetService(), "the modelled keys still bind");
    }

    /**
     * A {@code request} that is not an object at all degrades to an empty {@link TargetedRequest} rather than failing the bind -- the
     * behaviour {@code AuthorizationService#asTargetedRequest} had before the endpoint was typed. Both components stay null so the node
     * serializes to {@code {}} and every rule decides on PathNotFoundException, which is what an empty body has always produced.
     */
    @Test
    void aNonObjectRequestDegradesToAnEmptyNodeInsteadOfDenying() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);

        mockMvc(authorizationService, true)
            .perform(post("/open/validate").contentType("application/json").content("{\"request\":\"not-an-object\"}"))
            .andExpect(status().isOk());

        ArgumentCaptor<OpenAccessValidationRequest> captor = ArgumentCaptor.forClass(OpenAccessValidationRequest.class);
        verify(authorizationService).openAccessRequestIsValid(captor.capture());
        TargetedRequest degraded = captor.getValue().request();
        assertNotNull(degraded, "a non-object request must degrade, not vanish");
        assertNull(degraded.targetService());
        assertNull(degraded.query());
    }

    /** An absent or explicitly null {@code request} stays absent, which AuthorizationService grants -- unchanged behaviour. */
    @Test
    void anAbsentOrNullRequestBindsAsAbsent() throws Exception {
        for (String body : new String[] {"{\"ipAddress\":\"OPEN_ACCESS:my.host\"}", "{\"request\":null}"}) {
            AuthorizationService authorizationService = mock(AuthorizationService.class);
            when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);

            mockMvc(authorizationService, true).perform(post("/open/validate").contentType("application/json").content(body))
                .andExpect(status().isOk());

            ArgumentCaptor<OpenAccessValidationRequest> captor = ArgumentCaptor.forClass(OpenAccessValidationRequest.class);
            verify(authorizationService).openAccessRequestIsValid(captor.capture());
            assertNull(captor.getValue().request(), "no request node to authorize: " + body);
        }
    }

    @Test
    void answersTheTypedValidationRecord() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.openAccessRequestIsValid(any())).thenReturn(true);

        MvcResult result = mockMvc(authorizationService, true)
            .perform(post("/open/validate").contentType("application/json").content(GATEWAY_BODY)).andExpect(status().isOk()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertTrue(body.isObject(), "the body is now an object, not a bare boolean -- the gateway reads {valid}");
        assertTrue(body.get("valid").asBoolean());
    }

    /** With the open IdP disabled the answer is a denial, and it must be the same shape as every other answer. */
    @Test
    void deniesInTheSameShapeWhenOpenIdpIsDisabled() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);

        MvcResult result = mockMvc(authorizationService, false)
            .perform(post("/open/validate").contentType("application/json").content(GATEWAY_BODY)).andExpect(status().isOk()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertFalse(body.get("valid").asBoolean());
    }
}
