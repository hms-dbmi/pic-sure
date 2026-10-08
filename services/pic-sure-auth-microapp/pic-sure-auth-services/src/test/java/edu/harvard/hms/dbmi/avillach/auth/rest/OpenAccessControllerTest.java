package edu.harvard.hms.dbmi.avillach.auth.rest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.harvard.hms.dbmi.avillach.auth.entity.AccessRule;
import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.entity.Privilege;
import edu.harvard.hms.dbmi.avillach.auth.entity.Role;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApiKeyRepository;
import edu.harvard.hms.dbmi.avillach.auth.repository.UserConsentsRepository;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.AccessRuleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionFixtures.MutableClock;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.OpenSessionService.IssuedSession;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.SessionService;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.authorization.AuthorizationService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static edu.harvard.hms.dbmi.avillach.auth.service.impl.RoleService.MANAGED_OPEN_ACCESS_ROLE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests over a real {@link AuthorizationService} and a real {@link ApiKeyService} (repository mocked), so the JSON on
 * the wire, the {@code responseVersion} negotiation, and the enforcement table are exercised end to end.
 */
public class OpenAccessControllerTest {

    private static final List<String> RESPONSE_FIELDS = List.of("valid", "keyType", "keyId", "displayPrefix", "denial", "refreshedToken");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ApiKeyRepository apiKeyRepository;
    private ApiKeyService apiKeyService;
    private AccessRuleService accessRuleService;
    private RoleService roleService;
    private MutableClock clock;
    private OpenSessionService openSessionService;

    private record MintedKey(String plaintext, ApiKey stored) {
    }

    @BeforeEach
    public void setUp() {
        apiKeyRepository = mock(ApiKeyRepository.class);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        apiKeyService = new ApiKeyService(apiKeyRepository, "", "", 90, 365);
        clock = new MutableClock(Instant.now());
        openSessionService = OpenSessionFixtures.enabledService(clock);

        accessRuleService = mock(AccessRuleService.class);
        roleService = mock(RoleService.class);
        openAccessRulesPass(true);
    }

    private MockMvc mockMvc(boolean apiKeyEnforcementEnabled, boolean openIdpProviderIsEnabled) {
        AuthorizationService authorizationService = new AuthorizationService(
            accessRuleService, mock(SessionService.class), roleService, "fence,okta", mock(UserConsentsRepository.class), false, false,
            apiKeyService, openSessionService, apiKeyEnforcementEnabled
        );
        return MockMvcBuilders.standaloneSetup(new OpenAccessController(authorizationService, openSessionService, openIdpProviderIsEnabled))
            .build();
    }

    private void openAccessRulesPass(boolean pass) {
        AccessRule accessRule = new AccessRule();
        accessRule.setUuid(UUID.randomUUID());
        accessRule.setName("AR_TEST_OPEN");
        Privilege privilege = new Privilege();
        privilege.setAccessRules(Set.of(accessRule));
        Role openAccessRole = new Role();
        openAccessRole.setPrivileges(Set.of(privilege));
        when(roleService.getRoleByName(MANAGED_OPEN_ACCESS_ROLE_NAME)).thenReturn(openAccessRole);
        when(accessRuleService.evaluateAccessRule(any(), any())).thenReturn(pass);
    }

    private MintedKey mintKey(ApiKeyType keyType, Consumer<ApiKey> storedState) {
        clearInvocations(apiKeyRepository);
        ApiKeyCreationResponse created = keyType == ApiKeyType.USER ? apiKeyService.generateUserKey(null, null)
            : apiKeyService.generatePlatformKey("Partner", "partner@example.com", Instant.now().plus(30, ChronoUnit.DAYS), false);
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository, atLeastOnce()).save(captor.capture());
        ApiKey stored = captor.getValue();
        stored.setUuid(UUID.randomUUID());
        storedState.accept(stored);
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));
        return new MintedKey(created.apiKey(), stored);
    }

    private MintedKey mintKey(ApiKeyType keyType) {
        return mintKey(keyType, stored -> {
        });
    }

    private MvcResult validate(MockMvc mockMvc, String apiKey, Object responseVersion) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("request", Map.of("Target Service", "/query/sync"));
        body.put("ipAddress", "OPEN_ACCESS:aio.local");
        if (apiKey != null) {
            body.put("apiKey", apiKey);
        }
        if (responseVersion != null) {
            body.put(OpenAccessController.RESPONSE_VERSION, responseVersion);
        }
        return mockMvc
            .perform(post("/open/validate").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk()).andReturn();
    }

    private JsonNode validateV2(MockMvc mockMvc, String apiKey) throws Exception {
        JsonNode response = objectMapper.readTree(validate(mockMvc, apiKey, 2).getResponse().getContentAsString());
        assertTrue(response.isObject());
        // every field is always present, null or not, so the gateway never has to tell "absent" from "null"
        RESPONSE_FIELDS.forEach(field -> assertTrue(response.has(field), field));
        assertEquals(RESPONSE_FIELDS.size(), response.size());
        return response;
    }

    private static void assertNoKeyIdentity(JsonNode response) {
        assertTrue(response.get("keyType").isNull());
        assertTrue(response.get("keyId").isNull());
        assertTrue(response.get("displayPrefix").isNull());
        assertTrue(response.get("refreshedToken").isNull());
    }

    private static void assertKeyIdentity(MintedKey key, String expectedKeyType, JsonNode response) {
        assertEquals(expectedKeyType, response.get("keyType").asText());
        assertEquals(key.stored().getUuid().toString(), response.get("keyId").asText());
        assertEquals(key.stored().getDisplayPrefix(), response.get("displayPrefix").asText());
        assertTrue(response.get("refreshedToken").isNull());
    }

    @Test
    public void testUserKeyReportsIdentity() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);

        JsonNode response = validateV2(mockMvc(true, true), key.plaintext());

        assertTrue(response.get("valid").asBoolean());
        assertTrue(response.get("denial").isNull());
        assertKeyIdentity(key, "USER", response);
    }

    @Test
    public void testPlatformKeyReportsIdentity() throws Exception {
        MintedKey key = mintKey(ApiKeyType.PLATFORM);

        JsonNode response = validateV2(mockMvc(true, true), key.plaintext());

        assertTrue(response.get("valid").asBoolean());
        assertTrue(response.get("denial").isNull());
        assertKeyIdentity(key, "PLATFORM", response);
    }

    @Test
    public void testEnforcementOff_validKeyStillReportsIdentity() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);

        JsonNode response = validateV2(mockMvc(false, true), key.plaintext());

        assertTrue(response.get("valid").asBoolean());
        assertKeyIdentity(key, "USER", response);
    }

    @Test
    public void testEnforcementOn_noKeyDeniedAsKeyMissing() throws Exception {
        JsonNode response = validateV2(mockMvc(true, true), null);

        assertFalse(response.get("valid").asBoolean());
        assertEquals("key_missing", response.get("denial").asText());
        assertNoKeyIdentity(response);
    }

    @Test
    public void testEnforcementOff_noKeyAdmittedWithoutIdentity() throws Exception {
        JsonNode response = validateV2(mockMvc(false, true), null);

        assertTrue(response.get("valid").asBoolean());
        assertTrue(response.get("denial").isNull());
        assertNoKeyIdentity(response);
    }

    @Test
    public void testEnforcementOn_unknownRevokedExpiredAndMalformedKeysAreIndistinguishable() throws Exception {
        String unknown = mintKey(ApiKeyType.USER).plaintext();
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        MintedKey revoked = mintKey(ApiKeyType.USER, stored -> stored.setRevokedAt(Instant.now().minusSeconds(60)));
        MintedKey expired = mintKey(ApiKeyType.PLATFORM, stored -> stored.setExpiresAt(Instant.now().minusSeconds(60)));
        String malformed = "picsure_u_not-a-real-key";
        String userJwt = OpenSessionFixtures.applicationJwtUtil().createJwtToken(null, "psama", Map.of(), "user-subject", 60_000);
        MockMvc mockMvc = mockMvc(true, true);

        String unknownBody = validate(mockMvc, unknown, 2).getResponse().getContentAsString();
        String revokedBody = validate(mockMvc, revoked.plaintext(), 2).getResponse().getContentAsString();
        String expiredBody = validate(mockMvc, expired.plaintext(), 2).getResponse().getContentAsString();
        String malformedBody = validate(mockMvc, malformed, 2).getResponse().getContentAsString();
        String userJwtBody = validate(mockMvc, userJwt, 2).getResponse().getContentAsString();

        assertEquals(unknownBody, revokedBody);
        assertEquals(unknownBody, expiredBody);
        assertEquals(unknownBody, malformedBody);
        assertEquals(unknownBody, userJwtBody);
        JsonNode response = objectMapper.readTree(revokedBody);
        assertFalse(response.get("valid").asBoolean());
        assertEquals("key_invalid", response.get("denial").asText());
        assertNoKeyIdentity(response);
        assertFalse(revokedBody.contains(revoked.stored().getUuid().toString()));
        assertFalse(expiredBody.contains(expired.stored().getUuid().toString()));
    }

    @Test
    public void testEnforcementOff_invalidKeyAdmittedWithoutIdentity() throws Exception {
        MintedKey revoked = mintKey(ApiKeyType.USER, stored -> stored.setRevokedAt(Instant.now().minusSeconds(60)));

        JsonNode response = validateV2(mockMvc(false, true), revoked.plaintext());

        assertTrue(response.get("valid").asBoolean());
        assertTrue(response.get("denial").isNull());
        assertNoKeyIdentity(response);
    }

    @Test
    public void testValidKeyDeniedByAccessRules() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);
        openAccessRulesPass(false);

        for (boolean enforcement : List.of(true, false)) {
            JsonNode response = validateV2(mockMvc(enforcement, true), key.plaintext());

            assertFalse(response.get("valid").asBoolean());
            assertEquals("rules", response.get("denial").asText());
            assertNoKeyIdentity(response);
        }
    }

    @Test
    public void testOpenIdpDisabled_deniesWithNullKeyFields() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);
        MockMvc mockMvc = mockMvc(true, false);

        JsonNode response = validateV2(mockMvc, key.plaintext());

        assertFalse(response.get("valid").asBoolean());
        assertEquals("rules", response.get("denial").asText());
        assertNoKeyIdentity(response);
        assertEquals("false", validate(mockMvc, key.plaintext(), null).getResponse().getContentAsString());
    }

    @Test
    public void testBareBooleanUnlessResponseVersionIsTwo() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);
        MockMvc mockMvc = mockMvc(true, true);

        assertEquals("true", validate(mockMvc, key.plaintext(), null).getResponse().getContentAsString());
        assertEquals("false", validate(mockMvc, null, null).getResponse().getContentAsString());
        assertEquals("true", validate(mockMvc, key.plaintext(), 1).getResponse().getContentAsString());
        assertEquals("true", validate(mockMvc, key.plaintext(), "2").getResponse().getContentAsString());
        assertEquals("true", validate(mockMvc, key.plaintext(), 3).getResponse().getContentAsString());
        assertTrue(objectMapper.readTree(validate(mockMvc, key.plaintext(), 2).getResponse().getContentAsString()).isObject());
        assertTrue(objectMapper.readTree(validate(mockMvc, key.plaintext(), 2.0).getResponse().getContentAsString()).isObject());
    }

    @Test
    public void testPlaintextKeyNeverInResponseOrLogs() throws Exception {
        MintedKey valid = mintKey(ApiKeyType.USER);
        MintedKey revoked = mintKey(ApiKeyType.PLATFORM, stored -> stored.setRevokedAt(Instant.now().minusSeconds(60)));
        String malformed = "picsure_u_not-a-real-key";

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.ALL);
        StringBuilder responses = new StringBuilder();
        try {
            for (boolean enforcement : List.of(true, false)) {
                MockMvc mockMvc = mockMvc(enforcement, true);
                for (String presented : List.of(valid.plaintext(), revoked.plaintext(), malformed)) {
                    for (Object responseVersion : Arrays.asList(null, 2)) {
                        responses.append(validate(mockMvc, presented, responseVersion).getResponse().getContentAsString());
                    }
                }
            }
        } finally {
            root.detachAppender(appender);
            root.setLevel(previousLevel);
        }

        assertFalse(appender.list.isEmpty());
        for (String plaintext : List.of(valid.plaintext(), revoked.plaintext(), malformed)) {
            assertFalse(responses.toString().contains(plaintext));
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(plaintext), event.getFormattedMessage());
            }
        }
    }

    @Test
    public void testSessionReportsItsSessionId() throws Exception {
        IssuedSession issued = openSessionService.issue();

        JsonNode response = validateV2(mockMvc(true, true), issued.token());

        assertTrue(response.get("valid").asBoolean());
        assertTrue(response.get("denial").isNull());
        assertEquals("SESSION", response.get("keyType").asText());
        assertEquals(issued.sessionId(), response.get("keyId").asText());
        assertTrue(response.get("displayPrefix").isNull());
        assertTrue(response.get("refreshedToken").isNull());
        assertEquals("true", validate(mockMvc(true, true), issued.token(), null).getResponse().getContentAsString());
    }

    @Test
    public void testSessionPastHalfLifeCarriesARefreshedToken() throws Exception {
        IssuedSession issued = openSessionService.issue();
        clock.advance(Duration.ofMinutes(8));

        for (boolean enforcement : List.of(true, false)) {
            JsonNode response = validateV2(mockMvc(enforcement, true), issued.token());

            String refreshed = response.get("refreshedToken").asText();
            assertTrue(refreshed.startsWith("picsure_s_"), refreshed);
            assertEquals(issued.sessionId(), openSessionService.verify(refreshed).orElseThrow().sessionId());
        }
    }

    @Test
    public void testEnforcementOn_expiredSessionDeniedAsKeyInvalid() throws Exception {
        IssuedSession issued = openSessionService.issue();
        clock.advance(Duration.ofMinutes(16));

        JsonNode response = validateV2(mockMvc(true, true), issued.token());

        assertFalse(response.get("valid").asBoolean());
        assertEquals("key_invalid", response.get("denial").asText());
        assertNoKeyIdentity(response);
    }

    // sessions are stateless: issuing, validating, and refreshing never touch api_key
    @Test
    public void testSessionsNeverTouchTheApiKeyTable() throws Exception {
        clearInvocations(apiKeyRepository);
        IssuedSession issued = openSessionService.issue();
        MockMvc mockMvc = mockMvc(false, true);
        validateV2(mockMvc, issued.token());
        clock.advance(Duration.ofMinutes(8));
        String refreshed = validateV2(mockMvc, issued.token()).get("refreshedToken").asText();
        validateV2(mockMvc, refreshed);
        validateV2(mockMvc, "picsure_s_not-a-jwt");

        verifyNoInteractions(apiKeyRepository);
    }

    @Test
    public void testSessionTokensNeverInLogs() throws Exception {
        IssuedSession issued = openSessionService.issue();
        List<String> tokens = new ArrayList<>(List.of(issued.token()));

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level previousLevel = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        root.setLevel(Level.ALL);
        try {
            for (boolean enforcement : List.of(true, false)) {
                validate(mockMvc(enforcement, true), issued.token(), 2);
            }
            clock.advance(Duration.ofMinutes(8));
            tokens.add(validateV2(mockMvc(true, true), issued.token()).get("refreshedToken").asText());
            clock.advance(Duration.ofMinutes(16));
            validate(mockMvc(true, true), issued.token(), 2);
            validate(mockMvc(false, true), issued.token(), 2);
        } finally {
            root.detachAppender(appender);
            root.setLevel(previousLevel);
        }

        assertFalse(appender.list.isEmpty());
        for (String token : tokens) {
            String jwt = token.substring("picsure_s_".length());
            String signature = jwt.substring(jwt.lastIndexOf('.') + 1);
            for (ILoggingEvent event : appender.list) {
                assertFalse(event.getFormattedMessage().contains(signature), event.getFormattedMessage());
            }
        }
    }

    @Test
    public void testAuditMetadataUnchangedByResponseVersion() throws Exception {
        MintedKey key = mintKey(ApiKeyType.USER);
        MockMvc mockMvc = mockMvc(true, true);

        Map<String, Object> legacy = AuditAttributes.getMetadata(validate(mockMvc, key.plaintext(), null).getRequest());
        Map<String, Object> versioned = AuditAttributes.getMetadata(validate(mockMvc, key.plaintext(), 2).getRequest());

        assertEquals(Map.of("validation_result", "true", "target_service", "/query/sync"), legacy);
        assertEquals(legacy, versioned);
        Map<String, Object> denied = AuditAttributes.getMetadata(validate(mockMvc, null, 2).getRequest());
        assertEquals(Map.of("validation_result", "false", "target_service", "/query/sync"), denied);
    }
}
