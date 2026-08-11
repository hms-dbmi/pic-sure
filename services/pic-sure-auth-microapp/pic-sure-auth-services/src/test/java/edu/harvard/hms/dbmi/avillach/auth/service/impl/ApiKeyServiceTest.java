package edu.harvard.hms.dbmi.avillach.auth.service.impl;

import edu.harvard.hms.dbmi.avillach.auth.entity.ApiKey;
import edu.harvard.hms.dbmi.avillach.auth.enums.ApiKeyType;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyCreationResponse;
import edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyMetadata;
import edu.harvard.hms.dbmi.avillach.auth.repository.ApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ContextConfiguration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@SpringBootTest
@ContextConfiguration(classes = {ApiKeyService.class})
public class ApiKeyServiceTest {

    private static final long USER_TTL_DAYS = 90;
    private static final long PLATFORM_TTL_DAYS = 365;

    @MockBean
    private ApiKeyRepository apiKeyRepository;

    private ApiKeyService apiKeyService;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> invocation.getArgument(0));
        apiKeyService = new ApiKeyService(apiKeyRepository, "", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
    }

    @Test
    public void testGenerateUserKey_formatAndPersistedFields() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, "user@example.com");

        assertTrue(response.apiKey().matches("^picsure_[0-9A-Za-z]{43}$"));
        assertEquals(response.apiKey().substring("picsure_".length(), "picsure_".length() + 8), response.displayPrefix());
        assertEquals(ApiKeyType.USER, response.keyType());

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals(sha256(response.apiKey()), saved.getKeyHash());
        assertEquals(ApiKeyService.SCHEME_SHA256, saved.getHashScheme());
        assertEquals(ApiKeyType.USER, saved.getKeyType());
        assertEquals("user@example.com", saved.getEmail());
        assertNull(saved.getName());
        assertNull(saved.getRevokedAt());
        assertNull(saved.getLastUsedAt());
        long ttlDays = ChronoUnit.DAYS.between(saved.getCreatedAt(), saved.getExpiresAt());
        assertEquals(USER_TTL_DAYS, ttlDays);
        assertFalse(saved.getKeyHash().contains(response.apiKey()));
    }

    @Test
    public void testGenerateUserKey_withPepperUsesHmacScheme() {
        apiKeyService = new ApiKeyService(apiKeyRepository, "test-pepper", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);

        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        ApiKey saved = captor.getValue();
        assertEquals(ApiKeyService.SCHEME_HMAC_SHA256, saved.getHashScheme());
        assertNotEquals(sha256(response.apiKey()), saved.getKeyHash());
    }

    @Test
    public void testGeneratePlatformKey_customExpiryAndMetadata() {
        Instant expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);

        ApiKeyCreationResponse response = apiKeyService.generatePlatformKey("Partner X", "partner@example.com", expiresAt, false);

        assertEquals(ApiKeyType.PLATFORM, response.keyType());
        assertEquals(expiresAt, response.expiresAt());
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertEquals("Partner X", captor.getValue().getName());
        assertEquals("partner@example.com", captor.getValue().getEmail());
    }

    @Test
    public void testVerifyKey_validKey() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        Optional<ApiKey> verified = apiKeyService.verifyKey(response.apiKey());

        assertTrue(verified.isPresent());
        assertEquals(ApiKeyType.USER, verified.get().getKeyType());
    }

    @Test
    public void testVerifyKey_wrongPrefixSkipsLookup() {
        assertTrue(apiKeyService.verifyKey("sk_live_notourkey").isEmpty());
        assertTrue(apiKeyService.verifyKey(null).isEmpty());
        verify(apiKeyRepository, never()).findByKeyHash(anyString());
    }

    @Test
    public void testVerifyKey_unknownKey() {
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());

        assertTrue(apiKeyService.verifyKey("picsure_0000000000000000000000000000000000000000000").isEmpty());
    }

    @Test
    public void testVerifyKey_revokedKey() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response).setRevokedAt(Instant.now());
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isEmpty());
    }

    @Test
    public void testVerifyKey_expiredKey() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response).setExpiresAt(Instant.now().minusSeconds(1));
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isEmpty());
    }

    @Test
    public void testVerifyKey_prePepperKeyStillVerifiesAfterPepperEnabled() {
        // key minted while no pepper was configured...
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        // ...must still verify on a service configured with a pepper
        ApiKeyService pepperedService = new ApiKeyService(apiKeyRepository, "test-pepper", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        Optional<ApiKey> verified = pepperedService.verifyKey(response.apiKey());

        assertTrue(verified.isPresent());
        assertEquals(ApiKeyService.SCHEME_SHA256, verified.get().getHashScheme());
    }

    @Test
    public void testVerifyKey_updatesLastUsedWhenStale() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response).setLastUsedAt(Instant.now().minusSeconds(300));
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));
        clearInvocations(apiKeyRepository);

        apiKeyService.verifyKey(response.apiKey());

        verify(apiKeyRepository).touchLastUsed(eq(stored.getUuid()), any(Instant.class), any(Instant.class));
        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    public void testVerifyKey_skipsLastUsedWriteWhenRecent() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response).setLastUsedAt(Instant.now().minusSeconds(5));
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));
        clearInvocations(apiKeyRepository);

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isPresent());

        verify(apiKeyRepository, never()).touchLastUsed(any(UUID.class), any(Instant.class), any(Instant.class));
        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    public void testRevokeKey_setsRevokedAtOnce() {
        UUID uuid = UUID.randomUUID();
        ApiKey stored = new ApiKey().setDisplayPrefix("abcdefgh").setKeyType(ApiKeyType.USER).setCreatedAt(Instant.now())
            .setExpiresAt(Instant.now().plusSeconds(3600));
        stored.setUuid(uuid);
        when(apiKeyRepository.findById(uuid)).thenReturn(Optional.of(stored));

        Optional<ApiKeyMetadata> revoked = apiKeyService.revokeKey(uuid);

        assertTrue(revoked.isPresent());
        assertNotNull(revoked.get().revokedAt());
        Instant firstRevokedAt = stored.getRevokedAt();
        clearInvocations(apiKeyRepository);

        apiKeyService.revokeKey(uuid);

        assertEquals(firstRevokedAt, stored.getRevokedAt());
        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    public void testVerifyKey_keyFromRotatedPepperStillVerifies() {
        // key minted under the old pepper...
        ApiKeyService oldPepperService = new ApiKeyService(apiKeyRepository, "old-pepper", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        ApiKeyCreationResponse response = oldPepperService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        // ...verifies after rotation to a new pepper with the old one kept as previous
        ApiKeyService rotatedService = new ApiKeyService(apiKeyRepository, "new-pepper", "old-pepper", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        assertTrue(rotatedService.verifyKey(response.apiKey()).isPresent());

        // ...and while the current pepper is accidentally omitted entirely
        ApiKeyService pepperlessService = new ApiKeyService(apiKeyRepository, "", "old-pepper", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        assertTrue(pepperlessService.verifyKey(response.apiKey()).isPresent());
    }

    @Test
    public void testVerifyKey_keyFromTwoRotationsAgoVerifiesViaPepperList() {
        ApiKeyService oldestPepperService = new ApiKeyService(apiKeyRepository, "pepper-v1", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        ApiKeyCreationResponse response = oldestPepperService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        ApiKeyService twiceRotatedService =
            new ApiKeyService(apiKeyRepository, "pepper-v3", "pepper-v2, pepper-v1", USER_TTL_DAYS, PLATFORM_TTL_DAYS);

        assertTrue(twiceRotatedService.verifyKey(response.apiKey()).isPresent());
    }

    @Test
    public void testVerifyKey_lastUsedWriteFailureDoesNotRejectKey() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));
        when(apiKeyRepository.touchLastUsed(any(UUID.class), any(Instant.class), any(Instant.class)))
            .thenThrow(new RuntimeException("db is read-only"));

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isPresent());
    }

    @Test
    public void testGeneratePlatformKey_pastExpiryRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> apiKeyService.generatePlatformKey("Partner X", "partner@example.com", Instant.now().minusSeconds(1), false)
        );
        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    public void testGeneratePlatformKey_nullExpiryAppliesPlatformTtl() {
        ApiKeyCreationResponse response = apiKeyService.generatePlatformKey("Partner X", "partner@example.com", null, false);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertEquals(PLATFORM_TTL_DAYS, ChronoUnit.DAYS.between(captor.getValue().getCreatedAt(), captor.getValue().getExpiresAt()));
        assertEquals(ApiKeyType.PLATFORM, response.keyType());
    }

    @Test
    public void testGeneratePlatformKey_neverExpiresStoresNullExpiry() {
        ApiKeyCreationResponse response = apiKeyService.generatePlatformKey("Partner X", "partner@example.com", null, true);

        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertNull(captor.getValue().getExpiresAt());
        assertNull(response.expiresAt());
    }

    @Test
    public void testGeneratePlatformKey_neverExpiresWithExplicitDateRejected() {
        assertThrows(
            IllegalArgumentException.class,
            () -> apiKeyService.generatePlatformKey("Partner X", "partner@example.com", Instant.now().plusSeconds(3600), true)
        );
        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    public void testVerifyKey_nullExpiryNeverExpires() {
        ApiKeyCreationResponse response = apiKeyService.generatePlatformKey("Partner X", "partner@example.com", null, true);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        Optional<ApiKey> verified = apiKeyService.verifyKey(response.apiKey());

        assertTrue(verified.isPresent());
        assertNull(verified.get().getExpiresAt());
    }

    @Test
    public void testVerifyKey_nullExpiryUserKeyRejected() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);
        // such a row can only exist where a deployment schema is missing the platform-only CHECK
        ApiKey stored = storedKeyFor(response).setExpiresAt(null);
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isEmpty());
    }

    @Test
    public void testConstructor_rejectsNonPositiveTtl() {
        assertThrows(IllegalStateException.class, () -> new ApiKeyService(apiKeyRepository, "", "", USER_TTL_DAYS, 0));
        assertThrows(IllegalStateException.class, () -> new ApiKeyService(apiKeyRepository, "", "", 0, PLATFORM_TTL_DAYS));
        assertThrows(IllegalStateException.class, () -> new ApiKeyService(apiKeyRepository, "", "", -1, PLATFORM_TTL_DAYS));
    }

    @Test
    public void testVerifyKey_hmacKeyRejectedWhenNoPeppersConfigured() {
        // key minted under a pepper, presented to a service with no peppers at all: the SHA256
        // fallback lookup must not accept the HMAC-schemed row
        ApiKeyService pepperedService = new ApiKeyService(apiKeyRepository, "test-pepper", "", USER_TTL_DAYS, PLATFORM_TTL_DAYS);
        ApiKeyCreationResponse response = pepperedService.generateUserKey(null, null);
        ApiKey stored = storedKeyFor(response);
        when(apiKeyRepository.findByKeyHash(anyString())).thenReturn(Optional.empty());
        when(apiKeyRepository.findByKeyHash(stored.getKeyHash())).thenReturn(Optional.of(stored));

        assertTrue(apiKeyService.verifyKey(response.apiKey()).isEmpty());
    }

    @Test
    public void testCreationResponse_toStringRedactsPlaintext() {
        ApiKeyCreationResponse response = apiKeyService.generateUserKey(null, null);

        assertFalse(response.toString().contains(response.apiKey()));
        assertTrue(response.toString().contains(response.displayPrefix()));
    }

    @Test
    public void testListKeys_mapsAllMetadataFields() {
        Instant createdAt = Instant.now().minusSeconds(3600);
        Instant expiresAt = Instant.now().plusSeconds(3600);
        Instant revokedAt = Instant.now().minusSeconds(60);
        Instant lastUsedAt = Instant.now().minusSeconds(120);
        ApiKey stored = new ApiKey().setKeyHash("deadbeef").setHashScheme(ApiKeyService.SCHEME_SHA256).setDisplayPrefix("abcdefgh")
            .setKeyType(ApiKeyType.PLATFORM).setName("Partner X").setEmail("partner@example.com").setCreatedAt(createdAt)
            .setExpiresAt(expiresAt).setRevokedAt(revokedAt).setLastUsedAt(lastUsedAt);
        stored.setUuid(UUID.randomUUID());
        when(apiKeyRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(stored)));

        edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage keyPage = apiKeyService.listKeys(0, 100, null);

        assertEquals(1, keyPage.totalCount());
        assertEquals(1, keyPage.keys().size());
        ApiKeyMetadata metadata = keyPage.keys().get(0);
        assertEquals(stored.getUuid(), metadata.uuid());
        assertEquals("abcdefgh", metadata.displayPrefix());
        assertEquals(ApiKeyType.PLATFORM, metadata.keyType());
        assertEquals("Partner X", metadata.name());
        assertEquals("partner@example.com", metadata.email());
        assertEquals(createdAt, metadata.createdAt());
        assertEquals(expiresAt, metadata.expiresAt());
        assertEquals(revokedAt, metadata.revokedAt());
        assertEquals(lastUsedAt, metadata.lastUsedAt());
    }

    @Test
    public void testListKeys_usesUuidTiebreakerForKeysCreatedInTheSameSecond() {
        when(apiKeyRepository.findAll(any(Pageable.class))).thenReturn(org.springframework.data.domain.Page.empty());

        apiKeyService.listKeys(2, 50, null);

        ArgumentCaptor<Pageable> pageRequest = ArgumentCaptor.forClass(Pageable.class);
        verify(apiKeyRepository).findAll(pageRequest.capture());
        assertEquals(
            java.util.List.of(new Sort.Order(Sort.Direction.DESC, "createdAt"), new Sort.Order(Sort.Direction.DESC, "uuid")),
            pageRequest.getValue().getSort().stream().toList()
        );
    }

    @Test
    public void testListKeys_filtersByKeyType() {
        ApiKey stored = new ApiKey().setKeyHash("deadbeef").setHashScheme(ApiKeyService.SCHEME_SHA256).setDisplayPrefix("abcdefgh")
            .setKeyType(ApiKeyType.PLATFORM).setCreatedAt(Instant.now()).setExpiresAt(Instant.now().plusSeconds(3600));
        stored.setUuid(UUID.randomUUID());
        when(apiKeyRepository.findByKeyType(eq(ApiKeyType.PLATFORM), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(stored)));

        edu.harvard.hms.dbmi.avillach.auth.model.response.ApiKeyPage keyPage = apiKeyService.listKeys(0, 100, ApiKeyType.PLATFORM);

        assertEquals(1, keyPage.keys().size());
        assertEquals(ApiKeyType.PLATFORM, keyPage.keys().get(0).keyType());
        verify(apiKeyRepository, never()).findAll(any(org.springframework.data.domain.Pageable.class));
    }

    @Test
    public void testRevokeKey_unknownUuid() {
        when(apiKeyRepository.findById(any(UUID.class))).thenReturn(Optional.empty());

        assertTrue(apiKeyService.revokeKey(UUID.randomUUID()).isEmpty());
    }

    private ApiKey storedKeyFor(ApiKeyCreationResponse response) {
        ArgumentCaptor<ApiKey> captor = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository, atLeastOnce()).save(captor.capture());
        ApiKey stored = captor.getValue();
        stored.setUuid(UUID.randomUUID());
        return stored;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
