package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.config.ApplicationConfig;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenSessionResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.MutableClock;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.VerifiedSession;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /open/session}: standalone MockMvc over a real {@link OpenSessionService}, so the issued token is checked by the same
 * verifier {@code /open/validate} uses. The validate endpoint has its own tests in {@link OpenAccessControllerTest}.
 */
public class OpenAccessControllerSessionTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenSessionService openSessionService;

    @BeforeEach
    public void setUp() {
        openSessionService = OpenSessionFixtures.enabledService(new MutableClock(Instant.now()));
    }

    private MockMvc mockMvc(OpenSessionService service, boolean openIdpProviderIsEnabled) {
        // the application's ObjectMapper, so expiresAt goes out as PSAMA really writes it
        return MockMvcBuilders
            .standaloneSetup(new OpenAccessController(mock(AuthorizationService.class), service, openIdpProviderIsEnabled))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ApplicationConfig(null).objectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private static MvcResult createSession(MockMvc mockMvc, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/open/session")).andExpect(status().is(expectedStatus)).andReturn();
    }

    @Test
    public void testIssuesAVerifiableSession() throws Exception {
        MvcResult result = createSession(mockMvc(openSessionService, true), 200);

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = response.get("token").asText();
        assertTrue(token.startsWith("picsure_s_"), token);
        VerifiedSession session = openSessionService.verify(token).orElseThrow();
        assertEquals(session.expiresAt(), Instant.parse(response.get("expiresAt").asText()));
        assertEquals(2, response.size());
    }

    @Test
    public void testEachCallStartsANewSession() throws Exception {
        MockMvc mockMvc = mockMvc(openSessionService, true);

        String first = objectMapper.readTree(createSession(mockMvc, 200).getResponse().getContentAsString()).get("token").asText();
        String second = objectMapper.readTree(createSession(mockMvc, 200).getResponse().getContentAsString()).get("token").asText();

        assertNotEquals(
            openSessionService.verify(first).orElseThrow().sessionId(), openSessionService.verify(second).orElseThrow().sessionId()
        );
    }

    // the browser sends no body; one it sends anyway is ignored
    @Test
    public void testIgnoresARequestBody() throws Exception {
        mockMvc(openSessionService, true).perform(post("/open/session").contentType(MediaType.APPLICATION_JSON).content("{\"x\":1}"))
            .andExpect(status().isOk());
    }

    @Test
    public void testAuditRecordsTheSessionIdButNeverTheToken() throws Exception {
        MvcResult result = createSession(mockMvc(openSessionService, true), 200);

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        Map<String, Object> metadata = AuditAttributes.getMetadata(result.getRequest());
        assertEquals(openSessionService.verify(token).orElseThrow().sessionId(), metadata.get("open_session_id"));
        String jwt = token.substring("picsure_s_".length());
        metadata.values().forEach(value -> assertFalse(String.valueOf(value).contains(jwt)));
    }

    // 404, so the browser treats "sessions off" the same as a PSAMA that predates them: it goes keyless
    @Test
    public void testSessionsDisabledIsNotFound() throws Exception {
        MvcResult result = createSession(mockMvc(OpenSessionFixtures.disabledService(), true), 404);

        assertTrue(result.getResponse().getContentAsString().contains("not enabled"));
        assertFalse(AuditAttributes.getMetadata(result.getRequest()).containsKey("open_session_id"));
    }

    @Test
    public void testOpenIdpDisabledIsNotFound() throws Exception {
        createSession(mockMvc(openSessionService, false), 404);
    }

    @Test
    public void testResponseToStringRedactsTheToken() {
        OpenSessionResponse response = new OpenSessionResponse("picsure_s_secret-token-value", Instant.now());

        assertFalse(response.toString().contains("secret-token-value"));
    }
}
