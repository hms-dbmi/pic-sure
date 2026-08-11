package edu.harvard.hms.dbmi.avillach.auth.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.harvard.dbmi.avillach.contracts.auth.UserConsentsResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.LongTermTokenResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.UserService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.BdcConsentsBuilder;

/**
 * The wire contract of the {@code /user/me/**} surface, exercised through real Jackson binding. The mapper is {@code new ObjectMapper()}
 * for the same reason {@code TokenControllerInspectContractTest} uses one: PSAMA declares its own ObjectMapper bean, so that -- not Boot's
 * relaxed mapper -- is what the MVC converters use.
 */
class UserControllerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UserService userService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(MAPPER)).build();
    }

    /**
     * {@code /user/me/consents} declared {@code @PathVariable("userId")} against a path with no {@code {userId}} template, so Spring could
     * never resolve the argument: the endpoint answered 500 for every caller. The user now comes from the security context like every other
     * {@code /me} endpoint, and the body is the bare {@link UserConsentsResponse} contract -- two keys, nothing else. The JPA entity it
     * used to serialize carried the {@code user_consents} row's own uuid; that is storage, not answer, and it is off the wire deliberately.
     *
     * <p>The fixture is keyed by {@link BdcConsentsBuilder}'s own constants on purpose. This map is keyed by CONCEPT PATH -- not by phs
     * accession -- and its values are the consent identifiers verbatim ({@code phs000007.c1}, {@code open_access-1000Genomes}). An earlier
     * fixture here read {@code Map.of("phs000001", Set.of("c1", "c2"))}, which is wrong on both counts, and a downstream client was
     * documented against that shape; referencing the producer's constants means the fixture cannot drift from what the builder writes.
     */
    @Test
    void consentsResolvesTheUserFromTheSecurityContextAndReturnsTheRecordBare() throws Exception {
        UUID userId = UUID.randomUUID();
        UserConsentsResponse consents = new UserConsentsResponse(
            userId.toString(),
            Map.of(
                BdcConsentsBuilder.CONSENTS_KEY, Set.of("phs000007.c1", "open_access-1000Genomes"),
                BdcConsentsBuilder.HARMONIZED_CONSENTS_KEY, Set.of("phs000007.c1")
            )
        );
        when(userService.getUserConsents()).thenReturn(consents);

        MvcResult result = mockMvc.perform(get("/user/me/consents")).andExpect(status().isOk()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals(userId.toString(), body.get("userId").asText());
        JsonNode consentsNode = body.get("consents");
        assertTrue(consentsNode.get(BdcConsentsBuilder.CONSENTS_KEY).isArray(), "consents must serialize as the map the record holds");
        assertTrue(consentsNode.get(BdcConsentsBuilder.HARMONIZED_CONSENTS_KEY).isArray(), "every concept path serializes the same way");
        assertEquals(
            Set.of("phs000007.c1", "open_access-1000Genomes"),
            StreamSupport.stream(consentsNode.get(BdcConsentsBuilder.CONSENTS_KEY).spliterator(), false).map(JsonNode::asText)
                .collect(Collectors.toSet()),
            "consent identifiers ride verbatim -- the client matches them against dictionary values as-is"
        );
        assertFalse(body.has("content"), "the response envelope must be gone");
        assertFalse(body.has("message"), "the response envelope must be gone");
        assertFalse(body.has("uuid"), "the user_consents row id is storage, not contract: " + body);
        assertEquals(2, body.size(), "userId and consents, and nothing else");
    }

    /** No {@code userId} argument survives: taking one from the caller is what made the endpoint unresolvable in the first place. */
    @Test
    void consentsTakesNoPathVariable() throws Exception {
        Method consents = UserController.class.getMethod("getUserConsents");
        assertEquals(0, consents.getParameterCount(), "the phantom @PathVariable must be gone");
    }

    @Test
    void refreshLongTermTokenReturnsTheTypedRecordWithItsHistoricalKey() throws Exception {
        when(userService.refreshUserToken(org.mockito.ArgumentMatchers.any(HttpHeaders.class)))
            .thenReturn(new LongTermTokenResponse("LONG_TERM_TOKEN|abc"));

        MvcResult result = mockMvc.perform(get("/user/me/refresh_long_term_token")).andExpect(status().isOk()).andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertEquals("LONG_TERM_TOKEN|abc", body.get("userLongTermToken").asText());
        assertEquals(1, body.size(), "no envelope, no extra keys");
    }

    /**
     * The {@code /me/queryTemplate} endpoints are gone. They were frozen rather than retyped because their value was a JSON document
     * carried as a String inside a one-key map; the v2 query removal deleted the stored {@code Privilege#queryTemplate} they merged, so
     * there is nothing left to serve. Callers of the old shape must move to a successor endpoint.
     */
    @Test
    void queryTemplateEndpointsAreGone() {
        for (Method method : UserController.class.getDeclaredMethods()) {
            assertFalse(
                method.getName().toLowerCase().contains("querytemplate"),
                "UserController must not expose a queryTemplate handler: " + method
            );
        }
    }
}
