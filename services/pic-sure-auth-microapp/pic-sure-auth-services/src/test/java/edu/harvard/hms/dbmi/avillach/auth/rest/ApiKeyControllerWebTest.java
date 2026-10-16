package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.GlobalExceptionHandler;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyMetadata;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests: exercise real JSON deserialization and request-parameter conversion through the {@link GlobalExceptionHandler}
 * advice, which plain controller unit tests bypass. The MockMvc writes bodies with this service's own {@code ObjectMapper}, so the success
 * bodies below are what the records serialize to on the wire and the error bodies are what the advice writes.
 */
public class ApiKeyControllerWebTest {

    private static final UUID KEY_ID = UUID.fromString("8694e3d4-5cb4-410f-8431-993445e6d3f6");
    private static final Instant CREATED = Instant.parse("2026-09-30T14:05:00Z");
    private static final Instant EXPIRES = Instant.parse("2026-10-30T14:05:00Z");

    private MockMvc mockMvc;
    private ApiKeyService apiKeyService;

    @BeforeEach
    public void setUp() {
        apiKeyService = Mockito.mock(ApiKeyService.class);
        ApiKeyController controller = new ApiKeyController(apiKeyService, (token, ip) -> true, true, true);
        mockMvc = FrozenWire.mockMvc(controller);
    }

    @Test
    public void testMalformedJsonBodyReturns400WithoutParserDetails() throws Exception {
        MvcResult result = mockMvc.perform(post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON).content("{ this is not json"))
            .andExpect(status().isBadRequest()).andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("fasterxml"));
    }

    @Test
    public void testMissingBodyReturns400() throws Exception {
        mockMvc.perform(post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isBadRequest());
    }

    @Test
    public void testJsonNullBodyReturns400OnPlatformEndpoint() throws Exception {
        // the JSON literal null binds the @RequestBody record itself to null - distinct from an absent body
        mockMvc.perform(post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON).content("null"))
            .andExpect(status().isBadRequest());
    }

    @Test
    public void testJsonNullBodyReturns400OnPublicUserEndpoint() throws Exception {
        mockMvc.perform(post("/open/apiKey").contentType(MediaType.APPLICATION_JSON).content("null")).andExpect(status().isBadRequest());
    }

    @Test
    public void testUnparseableExpiresAtReturns400() throws Exception {
        mockMvc.perform(
            post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Partner\",\"email\":\"a@b.com\",\"expiresAt\":\"not-a-date\"}")
        ).andExpect(status().isBadRequest());
    }

    @Test
    public void testInvalidKeyTypeParamReturns400WithoutEchoingValue() throws Exception {
        MvcResult result = mockMvc.perform(get("/apiKey").param("keyType", "bogus-type")).andExpect(status().isBadRequest()).andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("bogus-type"));
    }

    @Test
    public void testValidKeyTypeParamBindsCaseInsensitively() throws Exception {
        when(apiKeyService.listKeys(anyInt(), anyInt(), eq(ApiKeyType.PLATFORM))).thenReturn(new ApiKeyPage(List.of(), 0, 0, 100));

        mockMvc.perform(get("/apiKey").param("keyType", "PLATFORM")).andExpect(status().isOk());
    }

    @Test
    public void aUserKeyRequestWithExtraMembersStillBinds() throws Exception {
        when(apiKeyService.generateUserKey(any(), any())).thenReturn(creation(ApiKeyType.USER));

        mockMvc.perform(
            post("/open/apiKey").contentType(MediaType.APPLICATION_JSON)
                .content("{\"captchaToken\":\"captcha-token\",\"name\":null,\"email\":null,\"source\":\"public-access-page\"}")
        ).andExpect(status().isOk());
    }

    @Test
    public void aCreatedUserKeyIsTheRecordJsonWithThePlaintextKey() throws Exception {
        when(apiKeyService.generateUserKey("Jane Doe", "user@example.com")).thenReturn(creation(ApiKeyType.USER));

        mockMvc.perform(
            post("/open/apiKey").contentType(MediaType.APPLICATION_JSON)
                .content("{\"captchaToken\":\"captcha-token\",\"name\":\"Jane Doe\",\"email\":\"user@example.com\"}")
        ).andExpect(status().isOk()).andExpect(content().string(FrozenWire.MAPPER.writeValueAsString(creation(ApiKeyType.USER))))
            .andExpect(
                content().string(
                    "{\"apiKey\":\"picsure_u_00000000000000000000000000000000000000000003tr27S\",\"uuid\":\"" + KEY_ID
                        + "\",\"displayPrefix\":\"00000000\",\"keyType\":\"USER\",\"expiresAt\":\"2026-10-30T14:05:00Z\"}"
                )
            );
    }

    @Test
    public void aRevokedKeyIsTheMetadataRecordJson() throws Exception {
        ApiKeyMetadata revoked =
            new ApiKeyMetadata(KEY_ID, "00000000", ApiKeyType.PLATFORM, "Partner", "a@b.com", CREATED, null, CREATED, null);
        when(apiKeyService.revokeKey(KEY_ID)).thenReturn(Optional.of(revoked));

        mockMvc.perform(put("/apiKey/{keyId}/revoke", KEY_ID)).andExpect(status().isOk())
            .andExpect(content().string(FrozenWire.MAPPER.writeValueAsString(revoked))).andExpect(
                content().string(
                    "{\"uuid\":\"" + KEY_ID + "\",\"displayPrefix\":\"00000000\",\"keyType\":\"PLATFORM\",\"name\":\"Partner\","
                        + "\"email\":\"a@b.com\",\"createdAt\":\"2026-09-30T14:05:00Z\",\"expiresAt\":null,"
                        + "\"revokedAt\":\"2026-09-30T14:05:00Z\",\"lastUsedAt\":null}"
                )
            );
    }

    @Test
    public void aFailedCaptchaIs400WithTheReasonAsTheMessage() throws Exception {
        ApiKeyController controller = new ApiKeyController(apiKeyService, (token, ip) -> false, true, true);

        FrozenWire.mockMvc(controller)
            .perform(post("/open/apiKey").contentType(MediaType.APPLICATION_JSON).content("{\"captchaToken\":\"bad-token\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"CAPTCHA verification failed.\",\"content\":null}"));
    }

    @Test
    public void aMalformedKeyIdIs400WithoutEchoingIt() throws Exception {
        mockMvc.perform(put("/apiKey/{keyId}/revoke", "not-a-uuid")).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"Invalid API key ID.\",\"content\":null}"));
    }

    @Test
    public void anUnknownKeyIs400() throws Exception {
        when(apiKeyService.revokeKey(KEY_ID)).thenReturn(Optional.empty());

        mockMvc.perform(put("/apiKey/{keyId}/revoke", KEY_ID)).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"API key not found by given ID.\",\"content\":null}"));
    }

    @Test
    public void aPlatformKeyWhoseExpiryTheServiceRejectsIs400WithTheServiceMessage() throws Exception {
        when(apiKeyService.generatePlatformKey(any(), any(), any(), eq(false)))
            .thenThrow(new IllegalArgumentException("API key expiration must be in the future"));

        mockMvc.perform(
            post("/apiKey/platform").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Partner\",\"email\":\"a@b.com\",\"expiresAt\":\"2020-01-01T00:00:00Z\"}")
        ).andExpect(status().isBadRequest())
            .andExpect(content().string("{\"message\":\"API key expiration must be in the future\",\"content\":null}"));
    }

    private static ApiKeyCreationResponse creation(ApiKeyType type) {
        return new ApiKeyCreationResponse("picsure_u_00000000000000000000000000000000000000000003tr27S", KEY_ID, "00000000", type, EXPIRES);
    }
}
