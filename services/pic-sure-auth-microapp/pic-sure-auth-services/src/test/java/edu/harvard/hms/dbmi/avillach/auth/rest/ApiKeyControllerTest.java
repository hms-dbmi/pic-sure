package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PlatformApiKeyRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserApiKeyRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyMetadata;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class ApiKeyControllerTest {

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private CaptchaVerifier captchaVerifier;

    private ApiKeyController controller;
    private HttpServletRequest request;

    private final ApiKeyCreationResponse creationResponse = new ApiKeyCreationResponse(
        "picsure_0000000000000000000000000000000000000000000", UUID.randomUUID(), "00000000", ApiKeyType.USER,
        Instant.now().plusSeconds(3600)
    );

    @BeforeEach
    public void setUp() {
        controller = new ApiKeyController(apiKeyService, captchaVerifier, true, true);
        request = new MockHttpServletRequest();
    }

    @Test
    public void testCreateUserKey_success() {
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
        when(apiKeyService.generateUserKey(any(), anyString())).thenReturn(creationResponse);

        ResponseEntity<?> response = controller.createUserKey(new UserApiKeyRequest("captcha-token", "Jane Doe", "user@example.com"), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(creationResponse, response.getBody());
        verify(apiKeyService).generateUserKey("Jane Doe", "user@example.com");
    }

    @Test
    public void testCreateUserKey_blankEmailNormalizedToNull() {
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
        when(apiKeyService.generateUserKey(any(), any())).thenReturn(creationResponse);

        controller.createUserKey(new UserApiKeyRequest("captcha-token", "  ", "  "), request);

        verify(apiKeyService).generateUserKey(null, null);
    }

    @Test
    public void testCreateUserKey_controlCharactersStripped() {
        when(captchaVerifier.verify(any(), any())).thenReturn(true);
        when(apiKeyService.generateUserKey(any(), any())).thenReturn(creationResponse);

        controller.createUserKey(new UserApiKeyRequest("captcha-token", "Jane\nDoe", "user@example.com\r\n"), request);

        verify(apiKeyService).generateUserKey("JaneDoe", "user@example.com");
    }

    @Test
    public void testCreateUserKey_captchaFailure() {
        when(captchaVerifier.verify(any(), any())).thenReturn(false);

        ResponseEntity<?> response = controller.createUserKey(new UserApiKeyRequest("bad-token", null, null), request);

        assertNotEquals(200, response.getStatusCode().value());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_openAccessDisabled() {
        controller = new ApiKeyController(apiKeyService, captchaVerifier, false, true);

        ResponseEntity<?> response = controller.createUserKey(new UserApiKeyRequest("captcha-token", null, null), request);

        assertNotEquals(200, response.getStatusCode().value());
        verify(captchaVerifier, never()).verify(any(), any());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_generationDisabled() {
        controller = new ApiKeyController(apiKeyService, captchaVerifier, true, false);

        ResponseEntity<?> response = controller.createUserKey(new UserApiKeyRequest("captcha-token", null, null), request);

        assertNotEquals(200, response.getStatusCode().value());
        verify(captchaVerifier, never()).verify(any(), any());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_oversizedEmailRejected() {
        // no stubs: the length check must reject before the CAPTCHA or service is ever consulted
        ResponseEntity<?> response =
            controller.createUserKey(new UserApiKeyRequest("captcha-token", null, "a".repeat(250) + "@example.com"), request);

        assertNotEquals(200, response.getStatusCode().value());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testListKeys() {
        ApiKeyMetadata metadata = new ApiKeyMetadata(
            UUID.randomUUID(), "00000000", ApiKeyType.USER, null, null, Instant.now(), Instant.now().plusSeconds(3600), null, null
        );
        ApiKeyPage keyPage = new ApiKeyPage(List.of(metadata), 1, 0, 100);
        when(apiKeyService.listKeys(0, 100, null)).thenReturn(keyPage);

        ResponseEntity<?> response = controller.listKeys(0, 100, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(keyPage, response.getBody());
    }

    @Test
    public void testListKeys_filtersByKeyType() {
        ApiKeyPage keyPage = new ApiKeyPage(List.of(), 0, 0, 100);
        when(apiKeyService.listKeys(0, 100, ApiKeyType.PLATFORM)).thenReturn(keyPage);

        controller.listKeys(0, 100, ApiKeyType.PLATFORM);

        verify(apiKeyService).listKeys(0, 100, ApiKeyType.PLATFORM);
    }

    @Test
    public void testCreatePlatformKey_neverExpiresPassedThrough() {
        when(apiKeyService.generatePlatformKey("Partner", "a@b.com", null, true)).thenReturn(creationResponse);

        ResponseEntity<?> response = controller.createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", null, true), request);

        assertEquals(200, response.getStatusCode().value());
        verify(apiKeyService).generatePlatformKey("Partner", "a@b.com", null, true);
    }

    @Test
    public void testListKeys_clampsPageParams() {
        when(apiKeyService.listKeys(0, 1000, null)).thenReturn(new ApiKeyPage(List.of(), 0, 0, 1000));

        controller.listKeys(-5, 999999, null);

        verify(apiKeyService).listKeys(0, 1000, null);
    }

    @Test
    public void testCreatePlatformKey_requiresNameAndEmail() {
        assertNotEquals(
            200, controller.createPlatformKey(new PlatformApiKeyRequest(null, "a@b.com", null, false), request).getStatusCode().value()
        );
        assertNotEquals(
            200, controller.createPlatformKey(new PlatformApiKeyRequest("Partner", " ", null, false), request).getStatusCode().value()
        );
        verify(apiKeyService, never()).generatePlatformKey(any(), any(), any(), anyBoolean());
    }

    @Test
    public void testCreatePlatformKey_oversizedNameRejected() {
        ResponseEntity<?> response =
            controller.createPlatformKey(new PlatformApiKeyRequest("x".repeat(256), "a@b.com", null, false), request);

        assertNotEquals(200, response.getStatusCode().value());
        verify(apiKeyService, never()).generatePlatformKey(any(), any(), any(), anyBoolean());
    }

    @Test
    public void testCreatePlatformKey_success() {
        Instant expiresAt = Instant.now().plusSeconds(86400);
        when(apiKeyService.generatePlatformKey("Partner", "a@b.com", expiresAt, false)).thenReturn(creationResponse);

        ResponseEntity<?> response =
            controller.createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", expiresAt, false), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(creationResponse, response.getBody());
    }

    @Test
    public void testCreatePlatformKey_pastExpiryReturnsError() {
        when(apiKeyService.generatePlatformKey(any(), any(), any(), anyBoolean())).thenThrow(new IllegalArgumentException("API key expiration must be in the future"));

        ResponseEntity<?> response = controller.createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", Instant.now().minusSeconds(1), false), request);

        assertNotEquals(200, response.getStatusCode().value());
    }

    @Test
    public void testRevokeKey_success() {
        UUID uuid = UUID.randomUUID();
        ApiKeyMetadata metadata = new ApiKeyMetadata(
            uuid, "00000000", ApiKeyType.USER, null, null, Instant.now(), Instant.now().plusSeconds(3600), Instant.now(), null
        );
        when(apiKeyService.revokeKey(uuid)).thenReturn(Optional.of(metadata));

        ResponseEntity<?> response = controller.revokeKey(uuid.toString(), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(metadata, response.getBody());
    }

    @Test
    public void testRevokeKey_notFound() {
        when(apiKeyService.revokeKey(any(UUID.class))).thenReturn(Optional.empty());

        assertNotEquals(200, controller.revokeKey(UUID.randomUUID().toString(), request).getStatusCode().value());
    }

    @Test
    public void testRevokeKey_malformedUuid() {
        ResponseEntity<?> response = controller.revokeKey("not-a-uuid", request);

        assertNotEquals(200, response.getStatusCode().value());
        assertFalse(String.valueOf(response.getBody()).contains("not-a-uuid"));
        verify(apiKeyService, never()).revokeKey(any(UUID.class));
    }
}
