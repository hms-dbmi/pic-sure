package edu.harvard.hms.dbmi.avillach.auth.rest;

import edu.harvard.dbmi.avillach.logging.AuditEvent;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.request.PlatformApiKeyRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.request.UserApiKeyRequest;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyMetadata;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage;
import edu.harvard.hms.dbmi.avillach.auth.model.response.PICSUREResponse;
import edu.harvard.hms.dbmi.avillach.auth.service.CaptchaVerifier;
import edu.harvard.hms.dbmi.avillach.auth.service.impl.ApiKeyService;
import edu.harvard.hms.dbmi.avillach.auth.utils.AuditAttributes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;
import java.util.UUID;

import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.ADMIN;
import static edu.harvard.hms.dbmi.avillach.auth.utils.AuthNaming.AuthRoleNaming.SUPER_ADMIN;

/**
 * <p>Endpoints for open-access API keys. Key generation for anonymous users is public (CAPTCHA-gated); listing, platform-key minting, and
 * revocation are admin operations.</p> <br>The plaintext key appears only in the creation response body. It is never persisted or logged.
 */
@Tag(name = "API Key Management")
@Controller
public class ApiKeyController {

    private static final int MAX_METADATA_FIELD_LENGTH = 255;
    private static final int MAX_PAGE_SIZE = 1000;

    private final ApiKeyService apiKeyService;
    private final CaptchaVerifier captchaVerifier;
    private final boolean openIdpProviderIsEnabled;
    private final boolean generationEnabled;

    @Autowired
    public ApiKeyController(
        ApiKeyService apiKeyService, CaptchaVerifier captchaVerifier,
        @Value("${open.idp.provider.is.enabled}") boolean openIdpProviderIsEnabled,
        @Value("${api.key.generation.enabled}") boolean generationEnabled
    ) {
        this.apiKeyService = apiKeyService;
        this.captchaVerifier = captchaVerifier;
        this.openIdpProviderIsEnabled = openIdpProviderIsEnabled;
        this.generationEnabled = generationEnabled;
    }

    @Operation(
        description = "Generate a USER API key for open access. Public endpoint, gated by CAPTCHA. The key is returned once and cannot be recovered."
    )
    @AuditEvent(type = "ACCESS", action = "api_key.create")
    @PostMapping(produces = "application/json", path = "/open/apiKey")
    public ResponseEntity<?> createUserKey(
        @Parameter(
            required = true, description = "captchaToken (required when CAPTCHA is enabled) and optional contact name/email"
        ) @RequestBody UserApiKeyRequest keyRequest, HttpServletRequest request
    ) {
        if (!generationEnabled || !openIdpProviderIsEnabled) {
            return PICSUREResponse.protocolError("API key generation is not enabled on this deployment.");
        }
        String name = normalize(keyRequest.name());
        String email = normalize(keyRequest.email());
        if (tooLong(name) || tooLong(email)) {
            return PICSUREResponse.protocolError("Name and email must be at most " + MAX_METADATA_FIELD_LENGTH + " characters.");
        }
        if (!captchaVerifier.verify(keyRequest.captchaToken(), AuditAttributes.extractClientIp(request))) {
            AuditAttributes.putMetadata(request, "captcha_result", "failure");
            return PICSUREResponse.protocolError("CAPTCHA verification failed.");
        }

        ApiKeyCreationResponse created = apiKeyService.generateUserKey(name, email);
        AuditAttributes.putMetadata(request, "api_key_id", created.uuid().toString());
        AuditAttributes.putMetadata(request, "api_key_prefix", created.displayPrefix());
        return PICSUREResponse.success(created);
    }

    @Operation(
        description = "GET a page of API key metadata (never key material), newest first, optionally filtered by keyType, "
            + "requires ADMIN or SUPER_ADMIN role"
    )
    @AuditEvent(type = "OTHER", action = "api_key.list")
    @RolesAllowed({ADMIN, SUPER_ADMIN})
    @GetMapping(produces = "application/json", path = "/apiKey")
    public ResponseEntity<ApiKeyPage> listKeys(
        @RequestParam(value = "page", defaultValue = "0") int page, @RequestParam(value = "size", defaultValue = "100") int size,
        @RequestParam(value = "keyType", required = false) ApiKeyType keyType
    ) {
        return PICSUREResponse.success(apiKeyService.listKeys(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE), keyType));
    }

    @Operation(
        description = "Mint a PLATFORM API key for a partner service, requires SUPER_ADMIN role. Expiry: an explicit ISO-8601 expiresAt,"
            + " or neverExpires=true (mutually exclusive), or neither for the configured platform TTL default."
            + " The key is returned once and cannot be recovered."
    )
    @AuditEvent(type = "ADMIN", action = "api_key.platform.create")
    @RolesAllowed({SUPER_ADMIN})
    @PostMapping(produces = "application/json", path = "/apiKey/platform")
    public ResponseEntity<?> createPlatformKey(
        @Parameter(
            required = true, description = "name and contact email (both required), optional ISO-8601 expiresAt"
        ) @RequestBody PlatformApiKeyRequest keyRequest, HttpServletRequest request
    ) {
        String name = normalize(keyRequest.name());
        String email = normalize(keyRequest.email());
        if (name == null || email == null) {
            return PICSUREResponse.protocolError("Platform keys require a name and a contact email.");
        }
        if (tooLong(name) || tooLong(email)) {
            return PICSUREResponse.protocolError("Name and email must be at most " + MAX_METADATA_FIELD_LENGTH + " characters.");
        }

        ApiKeyCreationResponse created;
        try {
            created = apiKeyService.generatePlatformKey(name, email, keyRequest.expiresAt(), keyRequest.neverExpires());
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.protocolError(e.getMessage());
        }
        AuditAttributes.putMetadata(request, "api_key_id", created.uuid().toString());
        AuditAttributes.putMetadata(request, "api_key_prefix", created.displayPrefix());
        return PICSUREResponse.success(created);
    }

    @Operation(description = "Revoke an API key by UUID, requires SUPER_ADMIN role. Revocation is permanent.")
    @AuditEvent(type = "ADMIN", action = "api_key.revoke")
    @RolesAllowed({SUPER_ADMIN})
    @PutMapping(produces = "application/json", path = "/apiKey/{keyId}/revoke")
    public ResponseEntity<?> revokeKey(
        @Parameter(required = true, description = "UUID of the API key to revoke") @PathVariable("keyId") String keyId,
        HttpServletRequest request
    ) {
        // the raw keyId is client-controlled: never echo it back or record it; the parsed UUID is canonical
        UUID uuid;
        try {
            uuid = UUID.fromString(keyId);
        } catch (IllegalArgumentException e) {
            return PICSUREResponse.protocolError("Invalid API key ID.");
        }

        AuditAttributes.putMetadata(request, "api_key_id", uuid.toString());
        Optional<ApiKeyMetadata> revoked = apiKeyService.revokeKey(uuid);
        if (revoked.isEmpty()) {
            return PICSUREResponse.protocolError("API key not found by given ID.");
        }
        return PICSUREResponse.success(revoked.get());
    }

    // strips control characters at the boundary: these free-text fields flow into audit metadata and logs
    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > MAX_METADATA_FIELD_LENGTH;
    }
}
