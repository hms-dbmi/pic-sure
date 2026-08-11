package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserMetadataMappingService;

/**
 * The two statuses this refactor deliberately corrected rather than froze. Both were cases where the old code's own text said what it meant
 * and the envelope said something else, so both are pinned here to keep them from drifting back.
 */
class AdminErrorStatusContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MockMvc mockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    /**
     * A miss answered {@code 500 {"message":"AccessRule not found","content":404}} -- the intended 404 landed in the envelope's payload
     * because {@code PICSUREResponse.error(String, T)} takes the second argument as content. A missing id is an ordinary client outcome,
     * not a server fault.
     */
    @Test
    void accessRuleMissIsA404NotA500() throws Exception {
        AccessRuleService accessRuleService = mock(AccessRuleService.class);
        when(accessRuleService.getAccessRuleById(anyString())).thenReturn(Optional.empty());

        MvcResult result = mockMvc(new AccessRuleController(accessRuleService)).perform(get("/accessRule/no-such-id"))
            .andExpect(status().isNotFound()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("not_found", body.get("errorType").asText());
        assertEquals("AccessRule not found", body.get("message").asText());
        assertFalse(body.has("content"), "the envelope must be gone");
    }

    /**
     * {@code POST /mapping} caught the service's {@link IllegalArgumentException} -- raised for an unknown connection, which is the
     * caller's mistake -- and answered 500, telling the caller a bad request was PSAMA's fault. It now falls through to the handler's 400
     * with the service's message intact.
     */
    @Test
    void mappingWithAnUnknownConnectionIsA400NotA500() throws Exception {
        UserMetadataMappingService mappingService = mock(UserMetadataMappingService.class);
        when(mappingService.addMappings(anyList())).thenThrow(new IllegalArgumentException("No connection found for id: nope"));

        MvcResult result = mockMvc(new UserMetadataMappingWebController(mappingService)).perform(
            post("/mapping").contentType("application/json")
                .content("[{\"generalMetadataJsonPath\":\"$.email\",\"auth0MetadataJsonPath\":\"$.email\"}]")
        ).andExpect(status().isBadRequest()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("bad_request", body.get("errorType").asText());
        assertEquals("No connection found for id: nope", body.get("message").asText());
    }
}
