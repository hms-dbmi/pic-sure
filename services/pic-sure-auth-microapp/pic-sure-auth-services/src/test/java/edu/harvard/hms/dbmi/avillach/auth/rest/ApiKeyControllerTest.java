package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.exceptions.PicSureResponseException;
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
import org.springframework.http.HttpStatus;
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
        "picsure_u_00000000000000000000000000000000000000000003tr27S", UUID.randomUUID(), "00000000", ApiKeyType.USER,
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

        ResponseEntity<ApiKeyCreationResponse> response = controller.createUserKey(new UserApiKeyRequest("captcha-token", "Jane Doe", "user@example.com"), request);

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

        PicSureResponseException rejected = assertThrows(
            PicSureResponseException.class, () -> controller.createUserKey(new UserApiKeyRequest("bad-token", null, null), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("CAPTCHA verification failed.", rejected.getMessage());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_openAccessDisabled() {
        controller = new ApiKeyController(apiKeyService, captchaVerifier, false, true);

        PicSureResponseException rejected = assertThrows(
            PicSureResponseException.class, () -> controller.createUserKey(new UserApiKeyRequest("captcha-token", null, null), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("API key generation is not enabled on this deployment.", rejected.getMessage());
        verify(captchaVerifier, never()).verify(any(), any());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_generationDisabled() {
        controller = new ApiKeyController(apiKeyService, captchaVerifier, true, false);

        PicSureResponseException rejected = assertThrows(
            PicSureResponseException.class, () -> controller.createUserKey(new UserApiKeyRequest("captcha-token", null, null), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        verify(captchaVerifier, never()).verify(any(), any());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testCreateUserKey_oversizedEmailRejected() {
        // no stubs: the length check must reject before the CAPTCHA or service is ever consulted
        PicSureResponseException rejected = assertThrows(
            PicSureResponseException.class,
            () -> controller.createUserKey(new UserApiKeyRequest("captcha-token", null, "a".repeat(250) + "@example.com"), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("Name and email must be at most 255 characters.", rejected.getMessage());
        verify(apiKeyService, never()).generateUserKey(any(), any());
    }

    @Test
    public void testListKeys() {
        ApiKeyMetadata metadata = new ApiKeyMetadata(
            UUID.randomUUID(), "00000000", ApiKeyType.USER, null, null, Instant.now(), Instant.now().plusSeconds(3600), null, null
        );
        ApiKeyPage keyPage = new ApiKeyPage(List.of(metadata), 1, 0, 100);
        when(apiKeyService.listKeys(0, 100, null)).thenReturn(keyPage);

        ResponseEntity<ApiKeyPage> response = controller.listKeys(0, 100, null);

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

        ResponseEntity<ApiKeyCreationResponse> response = controller.createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", null, true), request);

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
        assertThrows(
            PicSureResponseException.class,
            () -> controller.createPlatformKey(new PlatformApiKeyRequest(null, "a@b.com", null, false), request)
        );
        assertThrows(
            PicSureResponseException.class,
            () -> controller.createPlatformKey(new PlatformApiKeyRequest("Partner", " ", null, false), request)
        );
        verify(apiKeyService, never()).generatePlatformKey(any(), any(), any(), anyBoolean());
    }

    @Test
    public void testCreatePlatformKey_oversizedNameRejected() {
        assertThrows(
            PicSureResponseException.class,
            () -> controller.createPlatformKey(new PlatformApiKeyRequest("x".repeat(256), "a@b.com", null, false), request)
        );
        verify(apiKeyService, never()).generatePlatformKey(any(), any(), any(), anyBoolean());
    }

    @Test
    public void testCreatePlatformKey_success() {
        Instant expiresAt = Instant.now().plusSeconds(86400);
        when(apiKeyService.generatePlatformKey("Partner", "a@b.com", expiresAt, false)).thenReturn(creationResponse);

        ResponseEntity<ApiKeyCreationResponse> response =
            controller.createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", expiresAt, false), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(creationResponse, response.getBody());
    }

    @Test
    public void testCreatePlatformKey_pastExpiryReturnsError() {
        when(apiKeyService.generatePlatformKey(any(), any(), any(), anyBoolean())).thenThrow(new IllegalArgumentException("API key expiration must be in the future"));

        PicSureResponseException rejected = assertThrows(
            PicSureResponseException.class,
            () -> controller
                .createPlatformKey(new PlatformApiKeyRequest("Partner", "a@b.com", Instant.now().minusSeconds(1), false), request)
        );

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("API key expiration must be in the future", rejected.getMessage());
    }

    @Test
    public void testRevokeKey_success() {
        UUID uuid = UUID.randomUUID();
        ApiKeyMetadata metadata = new ApiKeyMetadata(
            uuid, "00000000", ApiKeyType.USER, null, null, Instant.now(), Instant.now().plusSeconds(3600), Instant.now(), null
        );
        when(apiKeyService.revokeKey(uuid)).thenReturn(Optional.of(metadata));

        ResponseEntity<ApiKeyMetadata> response = controller.revokeKey(uuid.toString(), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(metadata, response.getBody());
    }

    @Test
    public void testRevokeKey_notFound() {
        when(apiKeyService.revokeKey(any(UUID.class))).thenReturn(Optional.empty());

        PicSureResponseException rejected =
            assertThrows(PicSureResponseException.class, () -> controller.revokeKey(UUID.randomUUID().toString(), request));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("API key not found by given ID.", rejected.getMessage());
    }

    @Test
    public void testRevokeKey_malformedUuid() {
        PicSureResponseException rejected = assertThrows(PicSureResponseException.class, () -> controller.revokeKey("not-a-uuid", request));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatus());
        assertEquals("Invalid API key ID.", rejected.getMessage());
        assertFalse(String.valueOf(rejected.getContent()).contains("not-a-uuid"));
        verify(apiKeyService, never()).revokeKey(any(UUID.class));
    }
}
