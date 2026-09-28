package edu.harvard.hms.dbmi.avillach.auth.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.harvard.hms.dbmi.avillach.auth.config.ApplicationConfig;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.response.OpenSessionResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.MutableClock;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.VerifiedSession;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.captcha.TurnstileCaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc over a real {@link OpenSessionService}, so the issued token is checked by the same verifier {@code /open/validate}
 * uses.
 */
public class OpenSessionControllerTest {

    private static final String SESSION_ACTION = "open-access-session";
    private static final String API_KEY_ACTION = "generate-api-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private OpenSessionService openSessionService;
    private HttpServer siteVerifyServer;
    private CloseableHttpClient httpClient;
    private final AtomicReference<String> siteVerifyBody = new AtomicReference<>();

    @BeforeEach
    public void setUp() throws IOException {
        openSessionService = OpenSessionFixtures.enabledService(new MutableClock(Instant.now()));
        siteVerifyServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        siteVerifyServer.createContext("/siteverify", exchange -> {
            byte[] body = siteVerifyBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        siteVerifyServer.start();
        httpClient = HttpClients.createDefault();
    }

    @AfterEach
    public void tearDown() throws IOException {
        siteVerifyServer.stop(0);
        httpClient.close();
    }

    private MockMvc mockMvc(OpenSessionService service, CaptchaVerifier verifier, boolean openIdpProviderIsEnabled) {
        // the application's ObjectMapper, so expiresAt goes out as PSAMA really writes it
        return MockMvcBuilders.standaloneSetup(new OpenSessionController(service, verifier, openIdpProviderIsEnabled))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ApplicationConfig(null).objectMapper()))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockMvc mockMvc(CaptchaVerifier verifier) {
        return mockMvc(openSessionService, verifier, true);
    }

    private TurnstileCaptchaVerifier sessionTurnstile(String siteVerifyUrl) {
        return new TurnstileCaptchaVerifier(httpClient, objectMapper, "session-secret", siteVerifyUrl, SESSION_ACTION);
    }

    private String siteVerifyUrl() {
        return "http://127.0.0.1:" + siteVerifyServer.getAddress().getPort() + "/siteverify";
    }

    private static MvcResult createSession(MockMvc mockMvc, String body, int expectedStatus) throws Exception {
        return mockMvc.perform(post("/open/session").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().is(expectedStatus)).andReturn();
    }

    @Test
    public void testIssuesAVerifiableSession() throws Exception {
        MvcResult result = createSession(mockMvc((token, ip) -> true), "{\"captchaToken\":\"t\"}", 200);

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = response.get("token").asText();
        assertTrue(token.startsWith("picsure_s_"), token);
        VerifiedSession session = openSessionService.verify(token).orElseThrow();
        assertEquals(session.expiresAt(), Instant.parse(response.get("expiresAt").asText()));
        assertEquals(2, response.size());
    }

    @Test
    public void testAuditRecordsTheSessionIdButNeverTheToken() throws Exception {
        MvcResult result = createSession(mockMvc((token, ip) -> true), "{\"captchaToken\":\"t\"}", 200);

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        Map<String, Object> metadata = AuditAttributes.getMetadata(result.getRequest());
        assertEquals(openSessionService.verify(token).orElseThrow().sessionId(), metadata.get("open_session_id"));
        String jwt = token.substring("picsure_s_".length());
        metadata.values().forEach(value -> assertFalse(String.valueOf(value).contains(jwt)));
    }

    @Test
    public void testPassesTheCaptchaTokenAndClientIpToTheVerifier() throws Exception {
        CaptchaVerifier verifier = mock(CaptchaVerifier.class);
        when(verifier.verify(any(), any())).thenReturn(true);

        mockMvc(verifier).perform(
            post("/open/session").contentType(MediaType.APPLICATION_JSON).content("{\"captchaToken\":\"the-token\"}")
                .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
        ).andExpect(status().isOk());

        verify(verifier).verify("the-token", "203.0.113.9");
    }

    @Test
    public void testCaptchaFailureIssuesNothing() throws Exception {
        MvcResult result = createSession(mockMvc((token, ip) -> false), "{\"captchaToken\":\"t\"}", 400);

        assertFalse(result.getResponse().getContentAsString().contains("picsure_s_"));
        Map<String, Object> metadata = AuditAttributes.getMetadata(result.getRequest());
        assertEquals("failure", metadata.get("captcha_result"));
        assertFalse(metadata.containsKey("open_session_id"));
    }

    // 404, so the browser can tell "go keyless" from a CAPTCHA failure, and treat an older PSAMA the same way
    @Test
    public void testSessionsDisabledIsNotFound() throws Exception {
        CaptchaVerifier verifier = mock(CaptchaVerifier.class);
        MvcResult result = createSession(mockMvc(OpenSessionFixtures.disabledService(), verifier, true), "{\"captchaToken\":\"t\"}", 404);

        assertTrue(result.getResponse().getContentAsString().contains("not enabled"));
        verify(verifier, never()).verify(any(), any());
    }

    @Test
    public void testOpenIdpDisabledIsNotFound() throws Exception {
        CaptchaVerifier verifier = mock(CaptchaVerifier.class);

        createSession(mockMvc(openSessionService, verifier, false), "{\"captchaToken\":\"t\"}", 404);

        verify(verifier, never()).verify(any(), any());
    }

    @Test
    public void testMissingOrNullBodyIsRejected() throws Exception {
        MockMvc mockMvc = mockMvc((token, ip) -> true);

        mockMvc.perform(post("/open/session").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
        createSession(mockMvc, "null", 400);
    }

    // with CAPTCHA disabled for sessions the browser sends no token at all
    @Test
    public void testUngatedIssuanceAcceptsABodyWithoutACaptchaToken() throws Exception {
        createSession(mockMvc((token, ip) -> true), "{}", 200);
    }

    @Test
    public void testTurnstileTokenForTheSessionActionIsAccepted() throws Exception {
        siteVerifyBody.set("{\"success\": true, \"action\": \"" + SESSION_ACTION + "\"}");

        createSession(mockMvc(sessionTurnstile(siteVerifyUrl())), "{\"captchaToken\":\"t\"}", 200);
    }

    // a token farmed from the API page's widget must not start a session
    @Test
    public void testTurnstileTokenForTheApiKeyActionIsRejected() throws Exception {
        siteVerifyBody.set("{\"success\": true, \"action\": \"" + API_KEY_ACTION + "\"}");

        createSession(mockMvc(sessionTurnstile(siteVerifyUrl())), "{\"captchaToken\":\"t\"}", 400);
    }

    @Test
    public void testMissingMalformedOrReusedTurnstileTokenIsRejected() throws Exception {
        MockMvc mockMvc = mockMvc(sessionTurnstile(siteVerifyUrl()));
        siteVerifyBody.set("{\"success\": false, \"error-codes\": [\"missing-input-response\"]}");
        createSession(mockMvc, "{}", 400);
        siteVerifyBody.set("{\"success\": false, \"error-codes\": [\"invalid-input-response\"]}");
        createSession(mockMvc, "{\"captchaToken\":\"garbage\"}", 400);
        siteVerifyBody.set("{\"success\": false, \"error-codes\": [\"timeout-or-duplicate\"]}");
        createSession(mockMvc, "{\"captchaToken\":\"used-once\"}", 400);
    }

    @Test
    public void testSiteVerifyTransportErrorFailsClosed() throws Exception {
        String unreachable = siteVerifyUrl();
        siteVerifyServer.stop(0);

        createSession(mockMvc(sessionTurnstile(unreachable)), "{\"captchaToken\":\"t\"}", 400);
    }

    @Test
    public void testResponseToStringRedactsTheToken() {
        OpenSessionResponse response = new OpenSessionResponse("picsure_s_secret-token-value", Instant.now());

        assertFalse(response.toString().contains("secret-token-value"));
    }
}
