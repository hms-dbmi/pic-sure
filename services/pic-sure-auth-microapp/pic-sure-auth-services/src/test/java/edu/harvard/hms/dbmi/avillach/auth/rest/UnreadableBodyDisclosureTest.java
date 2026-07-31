package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authentication.AuthenticationServiceRegistry;

/**
 * A rejected body must not narrate itself back to the caller.
 *
 * <p>Jackson's {@code UnrecognizedPropertyException} message carries the fully-qualified bound type, a reference chain, and the COMPLETE
 * list of properties the type does know -- a free inventory of {@code User}, {@code Role} and {@code Privilege} for anyone who can POST. It
 * also quotes the offending content, and {@code POST /authentication/&#123;idpProvider&#125;} is UNAUTHENTICATED and its body carries an
 * OIDC {@code code} or an Auth0 {@code access_token}. The detail belongs in the log, not on the wire.
 */
class UnreadableBodyDisclosureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GENERIC = "Malformed or unrecognized request body";

    private MockMvc mockMvc(Object controller) {
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    private static void assertDisclosesNothing(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        JsonNode body = MAPPER.readTree(raw);

        assertEquals("bad_request", body.get("errorType").asText());
        assertEquals(GENERIC, body.get("message").asText(), "the caller gets a fixed message, never Jackson's");

        String lowered = raw.toLowerCase(Locale.ROOT);
        assertFalse(lowered.contains("reference chain"), "Jackson's reference chain leaked: " + raw);
        assertFalse(lowered.contains("unrecognized field"), "Jackson's exception text leaked: " + raw);
        assertFalse(lowered.contains("known properties"), "the bound type's property inventory leaked: " + raw);
        assertFalse(lowered.contains("edu.harvard"), "a fully-qualified internal type name leaked: " + raw);
    }

    /** Admin path: the danger is the property inventory of the entity being bound. */
    @Test
    void aMalformedAdminBodyDisclosesNoEntityPropertyList() throws Exception {
        MvcResult result = mockMvc(new UserController(mock(UserService.class)))
            .perform(put("/user").contentType("application/json").content("[{\"notAUserField\":\"x\"}]")).andExpect(status().isBadRequest())
            .andReturn();

        assertDisclosesNothing(result);
        // The entity's real field names must not be readable off a rejection.
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("subject"), "User's property names leaked: " + raw);
        assertFalse(raw.contains("generalMetadata"), "User's property names leaked: " + raw);
    }

    /** Unauthenticated path, and the body being quoted would be credentials. */
    @Test
    void aMalformedAuthenticationBodyDisclosesNeitherJacksonInternalsNorTheCredential() throws Exception {
        MvcResult result =
            mockMvc(new AuthenticationController(mock(AuthenticationServiceRegistry.class), mock(SessionService.class))).perform(
                post("/authentication/ras").contentType("application/json")
                    .content("{\"code\":\"SUPER-SECRET-OIDC-CODE\",\"access_token\":[\"not-a-string\"]}")
            ).andExpect(status().isBadRequest()).andReturn();

        assertDisclosesNothing(result);
        String raw = result.getResponse().getContentAsString();
        assertFalse(raw.contains("SUPER-SECRET-OIDC-CODE"), "the rejected body's credential was echoed back: " + raw);
    }

    /** Syntactically broken JSON takes the same path and must be just as quiet. */
    @Test
    void unparseableJsonAlsoAnswersTheGenericMessage() throws Exception {
        MvcResult result = mockMvc(new UserController(mock(UserService.class)))
            .perform(put("/user").contentType("application/json").content("[{\"email\":")).andExpect(status().isBadRequest()).andReturn();

        assertDisclosesNothing(result);
    }
}
